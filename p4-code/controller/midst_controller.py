"""MIDST Controller - Main control plane for BMv2 simple_switch.

Responsibilities:
1. Listen for digest messages (WINDOW_START, WINDOW_END)
2. Trigger window analysis on WINDOW_END
3. Write bursty_flags to switch registers
4. Periodic health monitoring (VQ sanity, window cycling)
5. Stats collection and JSON Lines logging
6. AIFO quantile threshold computation
"""

import json
import logging
import os
import signal
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Set

import yaml

from window_analyzer import WindowAnalyzer, WindowResult

logger = logging.getLogger(__name__)

# BMv2 Thrift interface
# In production, this uses the runtime_CLI or p4runtime_lib
# For development, we use the simple_switch_CLI wrapper
try:
    from sswitch_runtime import SimpleSwitch
    from sswitch_runtime.ttypes import *
    from thrift.transport import TSocket, TTransport
    from thrift.protocol import TBinaryProtocol, TMultiplexedProtocol
    HAS_THRIFT = True
except ImportError:
    HAS_THRIFT = False
    logger.warning("Thrift not available; running in dry-run mode")


class SwitchConnection:
    """Wrapper around BMv2 Thrift connection for register read/write."""

    def __init__(self, host: str, port: int):
        self.host = host
        self.port = port
        self.connected = False
        self._transport = None
        self._client = None

    def connect(self):
        """Establish Thrift connection to simple_switch."""
        if not HAS_THRIFT:
            logger.info("Dry-run mode: no Thrift connection")
            return

        try:
            socket = TSocket.TSocket(self.host, self.port)
            self._transport = TTransport.TBufferedTransport(socket)
            protocol = TBinaryProtocol.TBinaryProtocol(self._transport)
            mp = TMultiplexedProtocol.TMultiplexedProtocol(
                protocol, "simple_switch"
            )
            self._client = SimpleSwitch.Client(mp)
            self._transport.open()
            self.connected = True
            logger.info("Connected to switch at %s:%d", self.host, self.port)
        except Exception as e:
            logger.error("Failed to connect to switch: %s", e)
            self.connected = False

    def disconnect(self):
        if self._transport:
            self._transport.close()
            self.connected = False

    def register_read(self, name: str, index: int = 0) -> int:
        """Read a single register entry."""
        if not self.connected:
            return 0
        try:
            return self._client.bm_register_read(0, name, index)
        except Exception as e:
            logger.error("register_read(%s, %d) failed: %s", name, index, e)
            return 0

    def register_read_all(self, name: str, count: int) -> List[int]:
        """Read all entries of a register."""
        if not self.connected:
            return [0] * count
        values = []
        for i in range(count):
            values.append(self.register_read(name, i))
        return values

    def register_write(self, name: str, index: int, value: int):
        """Write a single register entry."""
        if not self.connected:
            return
        try:
            self._client.bm_register_write(0, name, index, value)
        except Exception as e:
            logger.error("register_write(%s, %d, %d) failed: %s",
                         name, index, value, e)

    def register_reset(self, name: str):
        """Reset all entries of a register to 0."""
        if not self.connected:
            return
        try:
            self._client.bm_register_reset(0, name)
        except Exception as e:
            logger.error("register_reset(%s) failed: %s", name, e)


class MidstController:
    """Main MIDST control plane loop."""

    DIGEST_WINDOW_START = 1
    DIGEST_WINDOW_END = 2

    def __init__(self, config_path: str):
        with open(config_path) as f:
            self.config = yaml.safe_load(f)

        # Setup logging
        log_cfg = self.config.get("logging", {})
        logging.basicConfig(
            level=getattr(logging, log_cfg.get("level", "INFO")),
            format=log_cfg.get("format", "%(asctime)s [%(levelname)s] %(message)s")
        )

        self.switch = SwitchConnection(
            self.config["switch"]["thrift_host"],
            self.config["switch"]["thrift_port"]
        )
        self.analyzer = WindowAnalyzer(self.config)

        self.stats_file = self.config["controller"]["log_file"]
        self.stats_interval = self.config["controller"]["stats_interval_s"]
        self.health_interval = self.config["controller"]["health_check_interval_s"]

        self.scheduler_mode = self.config.get("scheduler", {}).get("mode", "aifo")

        self.running = False
        self.last_stats_time = 0.0
        self.last_health_time = 0.0
        self.window_count = 0
        self.active_flows: Set[int] = set()

    def start(self):
        """Start the control plane loop."""
        logger.info("MIDST Controller starting (scheduler=%s)...",
                     self.scheduler_mode)
        self.switch.connect()

        if self.scheduler_mode == "sppifo":
            self._initialize_sppifo_bounds()

        self.running = True

        # Handle graceful shutdown
        signal.signal(signal.SIGINT, self._shutdown)
        signal.signal(signal.SIGTERM, self._shutdown)

        try:
            self._main_loop()
        except KeyboardInterrupt:
            pass
        finally:
            self.switch.disconnect()
            logger.info("MIDST Controller stopped.")

    def _shutdown(self, signum, frame):
        logger.info("Shutdown signal received")
        self.running = False

    def _main_loop(self):
        """Main polling loop."""
        poll_ms = self.config["controller"]["poll_interval_ms"]
        poll_s = poll_ms / 1000.0

        while self.running:
            now = time.time()

            # Check for window events by polling monitoring state
            self._poll_monitoring_state()

            # Periodic stats collection
            if now - self.last_stats_time >= self.stats_interval:
                self._collect_and_log_stats()
                self.last_stats_time = now

            # Periodic health check
            if now - self.last_health_time >= self.health_interval:
                self._health_check()
                self.last_health_time = now

            time.sleep(poll_s)

    def _poll_monitoring_state(self):
        """Poll monitoring window state and handle transitions.

        In a real BMv2 setup, we would listen for digest messages.
        For polling-based approach, we check window_count register.
        """
        current_window = self.switch.register_read("window_count_reg", 0)

        if current_window > self.window_count:
            # A window just completed
            logger.info("Window %d completed (was %d)",
                        current_window, self.window_count)
            self.window_count = current_window
            self._handle_window_end(current_window - 1)

    def _handle_window_end(self, window_number: int):
        """Handle WINDOW_END: analyze sketches, mark bursty flows."""
        logger.info("Analyzing window %d...", window_number)

        total_size = self.config["sketch"]["total_size"]

        # Step 1: Read sketches from switch
        sin = self.switch.register_read_all("sin_sketch", total_size)
        sout = self.switch.register_read_all("sout_sketch", total_size)
        sin_dirty = self.switch.register_read_all("sin_dirty", total_size)
        sout_dirty = self.switch.register_read_all("sout_dirty", total_size)

        # Step 2: Infer active flows from dirty entries
        # Since we can't enumerate flows directly, we scan for non-zero Sin entries
        # In practice, flows are tracked via a flow table or digest
        inferred_flows = self._infer_active_flows(sin, sin_dirty)

        # Step 3: Save previous bursty before analysis overwrites it
        prev_bursty = self.analyzer.previous_bursty.copy()

        # Step 4: Analyze window
        result = self.analyzer.analyze_window(
            window_number, sin, sout, sin_dirty, sout_dirty, inferred_flows
        )

        # Step 5: Write bursty_flags to switch (clear previous, set new)
        flag_writes = self.analyzer.compute_bursty_flag_writes(
            result.bursty_flows, prev_bursty
        )
        for idx, val in flag_writes.items():
            self.switch.register_write("bursty_flags", idx, val)

        logger.info("Wrote %d bursty flag updates", len(flag_writes))

        # Step 5: Reset dirty sketch entries (sparse reset)
        dirty_sin = self.analyzer.get_dirty_indices(sin_dirty)
        dirty_sout = self.analyzer.get_dirty_indices(sout_dirty)

        for idx in dirty_sin:
            self.switch.register_write("sin_sketch", idx, 0)
            self.switch.register_write("sin_dirty", idx, 0)

        for idx in dirty_sout:
            self.switch.register_write("sout_sketch", idx, 0)
            self.switch.register_write("sout_dirty", idx, 0)

        logger.info("Reset %d Sin + %d Sout dirty entries",
                     len(dirty_sin), len(dirty_sout))

        # Step 6: Log window result
        self._log_window_result(result)

    def _infer_active_flows(self, sin: List[int],
                             sin_dirty: List[int]) -> Set[int]:
        """Infer active flow IDs from sketch state.

        Since we can't enumerate flows from the sketch alone (it's lossy),
        we maintain a set of known flow IDs. In practice, this would come
        from a flow table or ingress digest.

        For now, return the set of flows tracked by previous windows
        plus any flows registered via the flow tracking mechanism.
        """
        # In a real deployment, flows are tracked via:
        # 1. A match-action table that records flow_id on first packet
        # 2. Periodic digest messages from the data plane
        # 3. External flow tracking (e.g., from SDN controller)
        return self.active_flows.copy()

    def register_flow(self, flow_id: int):
        """Register a flow ID for tracking (called externally)."""
        self.active_flows.add(flow_id)

    def _initialize_sppifo_bounds(self):
        """Write initial SP-PIFO queue bounds to switch registers.

        Called once at startup when scheduler mode is 'sppifo'.
        Bounds divide the rank space evenly across queues.
        """
        sppifo_cfg = self.config.get("sppifo", {})
        num_queues = sppifo_cfg.get("num_queues", 8)
        max_rank = sppifo_cfg.get("max_rank", 63)
        initial_bounds = sppifo_cfg.get("initial_bounds", None)

        if initial_bounds and len(initial_bounds) == num_queues:
            bounds = initial_bounds
        else:
            bounds = self.analyzer.compute_sppifo_initial_bounds(
                num_queues, max_rank
            )

        for i, bound in enumerate(bounds):
            self.switch.register_write(f"sppifo_bound_{i}", 0, bound)
            logger.info("SP-PIFO bound[%d] = %d", i, bound)

        logger.info("Initialized %d SP-PIFO bounds (max_rank=%d)",
                     len(bounds), max_rank)

    def _collect_and_log_stats(self):
        """Read all statistics registers and log as JSON Lines."""
        stats = {
            "timestamp": datetime.utcnow().isoformat() + "Z",
            "vq_depth_bits": self.switch.register_read("vq_depth_bits", 0),
            "vq_peak_bits": self.switch.register_read("vq_peak_bits", 0),
            "vq_hwm_crossings": self.switch.register_read("vq_hwm_crossings", 0),
            "vq_lwm_crossings": self.switch.register_read("vq_lwm_crossings", 0),
            "monitoring_state": self.switch.register_read("monitoring_state_reg", 0),
            "window_count": self.switch.register_read("window_count_reg", 0),
        }

        # Read statistics counters (only if compiled with MIDST_ENABLE_STATISTICS)
        stat_registers = [
            "stat_total_packets", "stat_ip_packets", "stat_non_ip_packets",
            "stat_bursty_packets", "stat_normal_packets",
            "stat_admitted_packets", "stat_dropped_packets",
            "stat_sin_updates", "stat_sout_updates",
            "stat_windows_started", "stat_windows_ended",
            "stat_vq_arrivals", "stat_vq_departures",
            "stat_total_bytes",
        ]
        for reg_name in stat_registers:
            stats[reg_name] = self.switch.register_read(reg_name, 0)

        # Write JSON Lines
        try:
            with open(self.stats_file, "a") as f:
                f.write(json.dumps(stats) + "\n")
        except IOError as e:
            logger.error("Failed to write stats: %s", e)

        logger.debug("Stats collected: %d total packets, VQ=%d bits",
                     stats.get("stat_total_packets", 0),
                     stats["vq_depth_bits"])

    def _health_check(self):
        """Verify system health: VQ sanity, window cycling, sketch overflow."""
        vq_depth = self.switch.register_read("vq_depth_bits", 0)
        vq_peak = self.switch.register_read("vq_peak_bits", 0)
        mon_state = self.switch.register_read("monitoring_state_reg", 0)
        window_count = self.switch.register_read("window_count_reg", 0)

        # VQ sanity: depth should never exceed a reasonable maximum
        max_vq_bits = 10 * 1024 * 1024 * 8  # 10 MB in bits
        if vq_depth > max_vq_bits:
            logger.warning("VQ depth %d bits exceeds 10MB! Possible leak.", vq_depth)

        # Window cycling: if monitoring for too long, warn
        if mon_state == 1 and window_count == self.window_count:
            logger.warning(
                "Monitoring window stuck (state=MONITORING, window=%d). "
                "VQ may not be crossing low watermark.",
                window_count
            )

        # Sketch overflow: check for large counter values
        total_size = self.config["sketch"]["total_size"]
        sin_max = max(
            self.switch.register_read("sin_sketch", i)
            for i in range(min(16, total_size))  # sample first 16
        )
        if sin_max > 10000:
            logger.warning("Sin sketch counter overflow risk: max=%d", sin_max)

        logger.debug("Health: VQ=%d, peak=%d, state=%d, windows=%d",
                     vq_depth, vq_peak, mon_state, window_count)

    def _log_window_result(self, result: WindowResult):
        """Log window analysis result as JSON Lines."""
        entry = {
            "timestamp": datetime.utcnow().isoformat() + "Z",
            "event": "window_analysis",
            "window_number": result.window_number,
            "bursty_flow_count": len(result.bursty_flows),
            "heavy_loser_count": len(result.heavy_losers),
            "bursty_flows": list(result.bursty_flows),
            "dirty_entries": result.dirty_count,
            "flow_estimates": {str(k): v for k, v in result.flow_estimates.items()},
        }
        try:
            with open(self.stats_file, "a") as f:
                f.write(json.dumps(entry) + "\n")
        except IOError as e:
            logger.error("Failed to log window result: %s", e)


def main():
    config_path = sys.argv[1] if len(sys.argv) > 1 else "config.yaml"

    if not os.path.exists(config_path):
        print(f"Config file not found: {config_path}")
        sys.exit(1)

    controller = MidstController(config_path)
    controller.start()


if __name__ == "__main__":
    main()
