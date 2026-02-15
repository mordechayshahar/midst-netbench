"""Window Analyzer - Sketch analysis and bursty flow detection.

After a monitoring window ends (WINDOW_END digest), this module:
1. Reads Sin/Sout registers from the switch
2. Computes per-flow Count-Min estimates
3. Marks flows exceeding burst_threshold as bursty
4. Writes bursty_flags back to the switch
5. Resets only dirty sketch entries (sparse reset)
"""

import logging
import zlib
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Set

logger = logging.getLogger(__name__)


@dataclass
class WindowResult:
    """Result of analyzing a monitoring window."""
    window_number: int
    bursty_flows: Set[int] = field(default_factory=set)
    heavy_losers: Set[int] = field(default_factory=set)
    flow_estimates: Dict[int, int] = field(default_factory=dict)
    flow_loss_estimates: Dict[int, int] = field(default_factory=dict)
    dirty_count: int = 0


class WindowAnalyzer:
    """Analyzes Count-Min Delta-Sketches to detect bursty flows."""

    def __init__(self, config: dict):
        self.sketch_size = config["sketch"]["size"]
        self.num_hashes = config["sketch"]["num_hash_functions"]
        self.total_size = config["sketch"]["total_size"]
        self.hash_seeds = config["sketch"]["hash_seeds"]
        self.burst_threshold = config["detection"]["burst_threshold"]
        self.bursty_table_size = config["detection"]["bursty_table_size"]
        self.adaptive_threshold_fraction = config["detection"].get(
            "adaptive_threshold_fraction", 0.0
        )

        # Track active flows seen (populated by control plane from digests)
        self.active_flows: Set[int] = set()
        # Previous window's bursty flows (for decay/carry-over)
        self.previous_bursty: Set[int] = set()

    def compute_hash_indices(self, flow_id: int) -> List[int]:
        """Compute CRC32-based hash indices matching the P4 data plane.

        Uses the same golden-ratio seeds to ensure consistency.
        Returns flat indices into the sketch array.
        """
        indices = []
        for row, seed in enumerate(self.hash_seeds):
            # XOR flow_id with seed, then CRC32, then mod sketch_size
            val = flow_id ^ seed
            # Pack as unsigned 32-bit for CRC32
            packed = val.to_bytes(4, byteorder="big", signed=False)
            h = zlib.crc32(packed) & 0xFFFFFFFF
            col = h % self.sketch_size
            flat_idx = row * self.sketch_size + col
            indices.append(flat_idx)
        return indices

    def estimate_flow_ingress(self, flow_id: int, sin: List[int]) -> int:
        """Count-Min estimate: minimum across all hash rows in Sin."""
        indices = self.compute_hash_indices(flow_id)
        counts = [sin[idx] for idx in indices]
        return min(counts) if counts else 0

    def estimate_flow_loss(self, flow_id: int,
                           sin: List[int], sout: List[int]) -> int:
        """Delta-Sketch estimate: min(Sin[i] - Sout[i]) across rows.

        Returns max(0, min_delta) to avoid negative estimates.
        """
        indices = self.compute_hash_indices(flow_id)
        deltas = [sin[idx] - sout[idx] for idx in indices]
        return max(0, min(deltas)) if deltas else 0

    def analyze_window(self, window_number: int,
                       sin: List[int], sout: List[int],
                       sin_dirty: List[int], sout_dirty: List[int],
                       active_flows: Set[int]) -> WindowResult:
        """Analyze a completed monitoring window.

        Args:
            window_number: Sequential window identifier
            sin: Ingress sketch array (SKETCH_TOTAL_SIZE entries)
            sout: Egress sketch array (SKETCH_TOTAL_SIZE entries)
            sin_dirty: Dirty flags for Sin
            sout_dirty: Dirty flags for Sout
            active_flows: Set of flow IDs seen during this window

        Returns:
            WindowResult with bursty flows and estimates
        """
        result = WindowResult(window_number=window_number)

        # Compute total window packets from sketch row 0 sum.
        # Each packet increments exactly one cell per row, so the
        # sum of any single row gives the total packet count.
        total_window_packets = sum(sin[0:self.sketch_size])

        for flow_id in active_flows:
            ingress_est = self.estimate_flow_ingress(flow_id, sin)
            result.flow_estimates[flow_id] = ingress_est

            # Check if bursty: absolute count AND adaptive fraction
            is_bursty = ingress_est >= self.burst_threshold
            if is_bursty and self.adaptive_threshold_fraction > 0 \
                    and total_window_packets > 0:
                fraction = ingress_est / total_window_packets
                is_bursty = fraction >= self.adaptive_threshold_fraction

            if is_bursty:
                result.bursty_flows.add(flow_id)
                logger.info(
                    "Window %d: flow %d is bursty (estimate=%d, fraction=%.3f, "
                    "total=%d, threshold=%d, phi=%.3f)",
                    window_number, flow_id, ingress_est,
                    ingress_est / total_window_packets if total_window_packets > 0 else 0,
                    total_window_packets, self.burst_threshold,
                    self.adaptive_threshold_fraction
                )

            # Check for loss (heavy loser)
            loss_est = self.estimate_flow_loss(flow_id, sin, sout)
            result.flow_loss_estimates[flow_id] = loss_est
            if loss_est > 0:
                result.heavy_losers.add(flow_id)

        # Count dirty entries
        result.dirty_count = sum(1 for d in sin_dirty if d) + \
                             sum(1 for d in sout_dirty if d)

        logger.info(
            "Window %d analysis: %d active flows, %d bursty, %d heavy losers, "
            "%d dirty entries",
            window_number, len(active_flows), len(result.bursty_flows),
            len(result.heavy_losers), result.dirty_count
        )

        self.previous_bursty = result.bursty_flows
        return result

    def get_dirty_indices(self, dirty_flags: List[int]) -> List[int]:
        """Return list of indices where dirty flag is set."""
        return [i for i, d in enumerate(dirty_flags) if d]

    def compute_bursty_flag_writes(self, bursty_flows: Set[int],
                                   previously_bursty: Optional[Set[int]] = None
                                   ) -> Dict[int, int]:
        """Compute register writes for bursty_flags.

        Maps flow_id -> register index (flow_id % bursty_table_size)
        and returns {index: 1} for bursty, {index: 0} for cleared.

        Args:
            bursty_flows: Flows detected as bursty in current window
            previously_bursty: Flows that were bursty in the previous window
                (if None, uses self.previous_bursty)
        """
        writes: Dict[int, int] = {}
        to_clear = previously_bursty if previously_bursty is not None \
                   else self.previous_bursty

        # Clear all previous bursty flags
        for flow_id in to_clear:
            idx = flow_id % self.bursty_table_size
            writes[idx] = 0

        # Set new bursty flags
        for flow_id in bursty_flows:
            idx = flow_id % self.bursty_table_size
            writes[idx] = 1

        return writes

    def compute_sppifo_initial_bounds(self, num_queues: int,
                                       max_rank: int) -> List[int]:
        """Compute initial SP-PIFO queue bounds.

        Divides the rank space [0, max_rank] evenly across num_queues.
        Bound i = (i+1) * (max_rank / num_queues).
        The last bound is set to max_rank to catch all remaining packets.

        Args:
            num_queues: Number of SP-PIFO queues (typically 8)
            max_rank: Maximum expected rank value

        Returns:
            List of num_queues bound values
        """
        bounds = []
        step = max_rank // num_queues
        for i in range(num_queues):
            if i == num_queues - 1:
                bounds.append(max_rank)
            else:
                bounds.append((i + 1) * step)
        return bounds
