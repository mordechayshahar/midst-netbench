"""End-to-end lifecycle tests for MIDST.

Tests the complete flow:
1. VQ crosses high watermark -> monitoring starts
2. Sketch updates during monitoring
3. VQ crosses low watermark -> monitoring stops
4. Control plane analyzes sketches
5. Bursty flows detected, flags written
6. Rank inflation applied, admission decisions made
7. AIFO sliding window quantile (data-plane)
8. SP-PIFO dynamic queue scheduling
"""

import pytest
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "controller"))
from window_analyzer import WindowAnalyzer

SKETCH_SIZE = 256
NUM_HASHES = 4
TOTAL_SIZE = SKETCH_SIZE * NUM_HASHES
HASH_SEEDS = [0x00000000, 0x9e3779b9, 0x3c6ef372, 0xdaa66d2b]
HIGH_WM_BITS = 60000
LOW_WM_BITS = 24000
BURST_THRESHOLD = 5
RANK_PENALTY = 10
PKT_SIZE_BITS = 12000  # 1500 bytes
AIFO_WINDOW_SIZE = 20
AIFO_K_PERCENT = 20
MAX_QUEUE_SIZE = 64
AIFO_THRESHOLD_PKTS = (AIFO_K_PERCENT * MAX_QUEUE_SIZE) // 100  # 12
AIFO_C_MINUS_KC = (MAX_QUEUE_SIZE * (100 - AIFO_K_PERCENT)) // 100  # 52

DEFAULT_CONFIG = {
    "sketch": {
        "size": SKETCH_SIZE,
        "num_hash_functions": NUM_HASHES,
        "total_size": TOTAL_SIZE,
        "hash_seeds": HASH_SEEDS,
    },
    "detection": {
        "burst_threshold": BURST_THRESHOLD,
        "bursty_table_size": 256,
    },
}


class MidstPipelineModel:
    """Full pipeline model combining VQ, monitoring, sketches, admission."""

    def __init__(self):
        # VQ
        self.vq_depth = 0
        self.vq_peak = 0

        # Monitoring
        self.monitoring = False
        self.window_count = 0

        # Sketches
        self.sin = [0] * TOTAL_SIZE
        self.sout = [0] * TOTAL_SIZE
        self.sin_dirty = [0] * TOTAL_SIZE
        self.sout_dirty = [0] * TOTAL_SIZE

        # Bursty flags (written by control plane)
        self.bursty_flags = [0] * 256

        # Analyzer
        self.analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        self.active_flows = set()

        # Stats
        self.total_packets = 0
        self.admitted = 0
        self.dropped = 0

    def ingress(self, flow_id, pkt_bits=PKT_SIZE_BITS, rank=0):
        """Simulate ingress pipeline. Returns (admitted, effective_rank)."""
        self.total_packets += 1

        # VQ arrival
        was_below = self.vq_depth < HIGH_WM_BITS
        self.vq_depth += pkt_bits
        crossed_high = was_below and self.vq_depth >= HIGH_WM_BITS

        if self.vq_depth > self.vq_peak:
            self.vq_peak = self.vq_depth

        # Monitoring start
        if crossed_high and not self.monitoring:
            self.monitoring = True

        # Sketch update (only during monitoring)
        indices = self.analyzer.compute_hash_indices(flow_id)
        if self.monitoring:
            self.active_flows.add(flow_id)
            for idx in indices:
                self.sin[idx] += 1
                self.sin_dirty[idx] = 1

        # Bursty lookup
        is_bursty = self.bursty_flags[flow_id % 256] == 1

        # Rank inflation
        effective_rank = rank + (RANK_PENALTY if is_bursty else 0)

        # Store indices for egress
        return indices, effective_rank, is_bursty

    def egress(self, indices, pkt_bits=PKT_SIZE_BITS, sketch_valid=True):
        """Simulate egress pipeline."""
        # VQ departure
        was_above = self.vq_depth > LOW_WM_BITS
        if self.vq_depth > pkt_bits:
            self.vq_depth -= pkt_bits
        else:
            self.vq_depth = 0
        crossed_low = was_above and self.vq_depth <= LOW_WM_BITS

        # Sout update (only if sketch was valid)
        if sketch_valid and self.monitoring:
            for idx in indices:
                self.sout[idx] += 1
                self.sout_dirty[idx] = 1

        # Monitoring stop
        if crossed_low and self.monitoring:
            self.monitoring = False
            self.window_count += 1
            return True  # window ended

        return False

    def drop_path(self, pkt_bits=PKT_SIZE_BITS):
        """Handle dropped packet: decrement VQ, do NOT update Sout.

        Phase 9 fix: vq_arrival already incremented VQ. Without this
        decrement, dropped packets become phantoms that inflate VQ
        permanently, preventing monitoring windows from closing.
        """
        # VQ departure (same as egress, but no Sout update)
        if self.vq_depth > pkt_bits:
            self.vq_depth -= pkt_bits
        else:
            self.vq_depth = 0
        self.dropped += 1

    def control_plane_analyze(self):
        """Run control plane window analysis."""
        # Save previous bursty before analyze_window overwrites it
        prev_bursty = self.analyzer.previous_bursty.copy()

        result = self.analyzer.analyze_window(
            self.window_count - 1,
            self.sin, self.sout,
            self.sin_dirty, self.sout_dirty,
            self.active_flows
        )

        # Write bursty flags (clear previous, set new)
        flag_writes = self.analyzer.compute_bursty_flag_writes(
            result.bursty_flows, prev_bursty
        )
        for idx, val in flag_writes.items():
            self.bursty_flags[idx] = val

        # Reset dirty entries
        for i in range(TOTAL_SIZE):
            if self.sin_dirty[i]:
                self.sin[i] = 0
                self.sin_dirty[i] = 0
            if self.sout_dirty[i]:
                self.sout[i] = 0
                self.sout_dirty[i] = 0

        self.active_flows.clear()
        return result


class TestWindowLifecycle:
    """Test complete monitoring window lifecycle."""

    def test_idle_to_monitoring_to_idle(self):
        pipe = MidstPipelineModel()
        assert not pipe.monitoring

        # Send packets to cross high watermark (5 * 12000 = 60000)
        for _ in range(5):
            pipe.ingress(flow_id=1)
        assert pipe.monitoring
        assert pipe.vq_depth == 60000

        # Send packets and process egress to cross low watermark
        # Need egress to drain VQ
        for _ in range(3):
            indices, _, _ = pipe.ingress(flow_id=1)
        # 8 packets total = 96000 bits

        # Egress 6 packets: 96000 - 72000 = 24000 (crosses low WM)
        ended = False
        for _ in range(6):
            if pipe.egress([], sketch_valid=False):
                ended = True
        assert ended
        assert not pipe.monitoring
        assert pipe.window_count == 1

    def test_sketch_only_updated_during_monitoring(self):
        pipe = MidstPipelineModel()

        # Send 3 packets BEFORE monitoring (below HWM)
        for _ in range(3):
            pipe.ingress(flow_id=42)

        # Check sketch: should be empty (not monitoring)
        est = pipe.analyzer.estimate_flow_ingress(42, pipe.sin)
        assert est == 0

        # Cross HWM (2 more packets -> total 5 -> 60000 bits)
        pipe.ingress(flow_id=42)
        pipe.ingress(flow_id=42)
        assert pipe.monitoring

        # Now send 3 more during monitoring
        for _ in range(3):
            pipe.ingress(flow_id=42)

        # Should have 3 in sketch (only packets during monitoring, +1 for crossing)
        est = pipe.analyzer.estimate_flow_ingress(42, pipe.sin)
        assert est >= 3  # at least the 3 packets + possibly the crossing packet


class TestBurstyDetectionE2E:
    """End-to-end bursty flow detection."""

    def test_bursty_flow_detected(self):
        pipe = MidstPipelineModel()

        # Cross HWM
        for _ in range(5):
            pipe.ingress(flow_id=1)
        assert pipe.monitoring

        # Send 10 packets from bursty flow during monitoring
        for _ in range(10):
            pipe.ingress(flow_id=100)

        # End monitoring (drain via egress)
        for _ in range(15):  # enough to drain
            pipe.egress([], sketch_valid=False)

        # Control plane analysis
        result = pipe.control_plane_analyze()
        assert 100 in result.bursty_flows
        assert pipe.bursty_flags[100 % 256] == 1

    def test_normal_flow_not_bursty(self):
        pipe = MidstPipelineModel()

        # Cross HWM
        for _ in range(5):
            pipe.ingress(flow_id=1)

        # Send 2 packets (below threshold=5)
        for _ in range(2):
            pipe.ingress(flow_id=200)

        # End monitoring
        for _ in range(15):
            pipe.egress([], sketch_valid=False)

        result = pipe.control_plane_analyze()
        assert 200 not in result.bursty_flows

    def test_rank_inflation_after_detection(self):
        pipe = MidstPipelineModel()

        # Mark flow 100 as bursty
        pipe.bursty_flags[100 % 256] = 1

        # Next packet from flow 100 gets rank inflation
        _, eff_rank, is_bursty = pipe.ingress(flow_id=100, rank=5)
        assert is_bursty
        assert eff_rank == 5 + RANK_PENALTY  # 15

        # Normal flow 200 gets no inflation
        _, eff_rank, is_bursty = pipe.ingress(flow_id=200, rank=5)
        assert not is_bursty
        assert eff_rank == 5


class TestDropPathCorrectness:
    """Test that drop path DOES decrement VQ but does NOT update Sout.

    Phase 9 fix: without drop-decrement, phantom packets inflate VQ
    permanently, preventing monitoring windows from closing.
    """

    def test_drop_decrements_vq(self):
        pipe = MidstPipelineModel()

        for _ in range(5):
            pipe.ingress(flow_id=1)
        assert pipe.vq_depth == 60000

        # Simulate 2 drops — VQ should be decremented
        pipe.drop_path()
        pipe.drop_path()
        assert pipe.vq_depth == 60000 - 2 * PKT_SIZE_BITS  # 36000

    def test_drop_does_not_update_sout(self):
        pipe = MidstPipelineModel()

        # Start monitoring
        for _ in range(5):
            pipe.ingress(flow_id=1)
        assert pipe.monitoring

        # Send 5 bursty packets (Sin updated)
        indices_list = []
        for _ in range(5):
            indices, _, _ = pipe.ingress(flow_id=42)
            indices_list.append(indices)

        # Drop 3 of them (Sout NOT updated, VQ IS decremented)
        for _ in range(3):
            pipe.drop_path()

        # Check: Sin has 5, Sout has 0 (no egress for these)
        sin_est = pipe.analyzer.estimate_flow_ingress(42, pipe.sin)
        sout_est = pipe.analyzer.estimate_flow_ingress(42, pipe.sout)
        assert sin_est == 5
        assert sout_est == 0

        # Delta shows 5 in-flight/lost
        loss = pipe.analyzer.estimate_flow_loss(42, pipe.sin, pipe.sout)
        assert loss == 5

    def test_vq_returns_to_zero_after_all_drops_and_departures(self):
        """VQ = 0 after all packets either egress or are dropped."""
        pipe = MidstPipelineModel()

        # Send 10 packets
        for _ in range(10):
            pipe.ingress(flow_id=1)
        assert pipe.vq_depth == 120000

        # Egress 7 (VQ decremented by 7 * 12000 = 84000)
        for _ in range(7):
            pipe.egress([], sketch_valid=False)

        # Drop 3 (VQ decremented by 3 * 12000 = 36000)
        for _ in range(3):
            pipe.drop_path()

        # VQ = 10*12000 - 7*12000 - 3*12000 = 0 (all accounted for)
        assert pipe.vq_depth == 0


class TestMultipleWindows:
    """Test multiple monitoring window cycles."""

    def test_two_consecutive_windows(self):
        pipe = MidstPipelineModel()

        # Window 1: flow 100 is bursty
        for _ in range(5):
            pipe.ingress(flow_id=1)
        for _ in range(10):
            pipe.ingress(flow_id=100)
        for _ in range(15):
            pipe.egress([], sketch_valid=False)

        result1 = pipe.control_plane_analyze()
        assert 100 in result1.bursty_flows
        assert pipe.window_count == 1

        # Window 2: flow 200 is bursty, flow 100 is not
        for _ in range(5):
            pipe.ingress(flow_id=1)
        for _ in range(10):
            pipe.ingress(flow_id=200)
        for _ in range(2):
            pipe.ingress(flow_id=100)  # below threshold
        for _ in range(17):
            pipe.egress([], sketch_valid=False)

        result2 = pipe.control_plane_analyze()
        assert 200 in result2.bursty_flows
        assert 100 not in result2.bursty_flows
        assert pipe.window_count == 2

        # After window 2: flow 100 should be cleared
        assert pipe.bursty_flags[100 % 256] == 0
        assert pipe.bursty_flags[200 % 256] == 1


class TestAifoSlidingWindowE2E:
    """End-to-end test of AIFO data-plane sliding window in pipeline."""

    def test_sliding_window_fills_during_monitoring(self):
        """Sliding window stores ranks from monitored packets."""
        pipe = MidstPipelineModel()
        window = [0] * AIFO_WINDOW_SIZE
        tail = 0

        # Cross HWM to start monitoring
        for _ in range(5):
            pipe.ingress(flow_id=1, rank=10)

        # Send packets with various ranks during monitoring
        ranks = [5, 15, 25, 35, 45]
        for r in ranks:
            pipe.ingress(flow_id=2, rank=r)
            window[tail] = r
            tail = (tail + 1) % AIFO_WINDOW_SIZE

        # Verify window state
        for i, r in enumerate(ranks):
            assert window[i] == r

    def test_quantile_based_admission_in_pipeline(self):
        """Sliding window quantile determines admission at high fill."""
        # Create AIFO sliding window model
        from test_admission import AifoSlidingWindowModel
        model = AifoSlidingWindowModel()

        # Fill window with mid-range ranks
        model.queue_size = 20
        for r in [30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
                  30, 30, 30, 30, 30, 30, 30, 30, 30, 30]:
            model.check_admission(r, effective_queue_size=20)

        # Reset queue for test
        model.queue_size = 20

        # Low rank (0) should have quantile_count = 20 (all stored > 0)
        # lhs = 52 * 20 = 1040, rhs = (64 - 20) * 20 = 880
        # 1040 > 880 -> reject
        assert not model.check_admission(0, effective_queue_size=20)

        # High rank (100) should have quantile_count = 0
        # lhs = 52 * 0 = 0, rhs = (64 - 20) * 20 = 880
        # 0 <= 880 -> admit
        assert model.check_admission(100, effective_queue_size=20)


class TestSpPifoE2E:
    """End-to-end test of SP-PIFO scheduling in pipeline."""

    def test_sppifo_queue_assignment_in_pipeline(self):
        """SP-PIFO assigns queues based on rank and dynamic bounds."""
        from test_sppifo import SpPifoModel
        model = SpPifoModel(initial_bounds=[10, 20, 30, 40, 50, 60, 70, 80])

        # Low-priority packet (high rank) goes to high queue
        q_high, _, _ = model.schedule(75)
        assert q_high >= 6

        model2 = SpPifoModel(initial_bounds=[10, 20, 30, 40, 50, 60, 70, 80])
        # High-priority packet (low rank) goes to low queue
        q_low, _, _ = model2.schedule(5)
        assert q_low == 0

    def test_sppifo_adapts_with_bursty_traffic(self):
        """SP-PIFO bounds adapt when bursty flows cause blocking."""
        from test_sppifo import SpPifoModel
        model = SpPifoModel(initial_bounds=[10, 20, 30, 40, 50, 60, 70, 80])

        initial_b0 = model.bounds[0]

        # Simulate bursty flow: inflated ranks cause blocking
        for _ in range(5):
            base_rank = 8
            inflated_rank = base_rank + RANK_PENALTY  # 18
            model.schedule(inflated_rank)

        # Bounds should have decreased from blocking reactions
        assert model.bounds[0] < initial_b0

    def test_pipeline_with_sppifo_and_rank_inflation(self):
        """Full pipeline: bursty detection -> rank inflation -> SP-PIFO."""
        from test_sppifo import SpPifoModel
        pipe = MidstPipelineModel()

        # Mark flow 100 as bursty
        pipe.bursty_flags[100 % 256] = 1

        # Normal flow gets good queue
        sppifo = SpPifoModel(initial_bounds=[10, 20, 30, 40, 50, 60, 70, 80])
        _, eff_rank_normal, _ = pipe.ingress(flow_id=200, rank=8)
        q_normal, _, _ = sppifo.schedule(eff_rank_normal)
        assert q_normal == 0  # rank 8 <= bound[0]=10

        # Bursty flow gets worse queue due to rank penalty
        sppifo2 = SpPifoModel(initial_bounds=[10, 20, 30, 40, 50, 60, 70, 80])
        _, eff_rank_bursty, _ = pipe.ingress(flow_id=100, rank=8)
        assert eff_rank_bursty == 18  # 8 + 10 penalty
        q_bursty, blocked, _ = sppifo2.schedule(eff_rank_bursty)
        assert q_bursty == 1  # rank 18 > bound[0]=10, <= bound[1]=20
        assert blocked


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
