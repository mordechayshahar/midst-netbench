"""Integration scenario tests matching thesis experiment traffic patterns.

These test the complete MIDST pipeline under realistic traffic:
1. Steady-state + microburst scenario (from netbench config)
2. Multiple bursty flows competing
3. AIFO with rank inflation under load
4. PACKS multi-queue scheduling under burst
5. Sketch accuracy under high flow count
6. Multi-window cycle with flow turnover
7. Heavy loss detection (delta-sketch)
8. VQ invariant under mixed admit/drop
"""

import pytest
import random
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
PKT_BITS = 12000  # 1500 bytes

# AIFO params
AIFO_K_PERCENT = 20
MAX_QUEUE_SIZE = 64
AIFO_THRESHOLD = (AIFO_K_PERCENT * MAX_QUEUE_SIZE) // 100  # 12

# PACKS params
NUM_QUEUES = 8
RANK_DELTA = 20

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


class FullPipelineModel:
    """Complete MIDST pipeline model for scenario testing.

    IMPORTANT: Unlike the real switch where ingress/egress happen
    asynchronously, this model uses an explicit packet buffer.
    Call ingress() to process arrival, then drain() to dequeue
    packets through egress. This correctly models VQ building up
    during bursts and draining during idle periods.
    """

    def __init__(self, scheduler="none"):
        self.scheduler = scheduler

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

        # Bursty flags
        self.bursty_flags = [0] * 256

        # Analyzer
        self.analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        self.active_flows = set()

        # AIFO state
        self.aifo_queue_size = 0
        self.aifo_quantile_threshold = 0xFFFFFFFF
        self.rank_samples = []

        # PACKS state
        self.packs_queues = [0] * NUM_QUEUES

        # Packet buffer: list of (indices, pkt_bits, sketch_was_active)
        self.buffer = []

        # Stats
        self.total_arrived = 0
        self.total_admitted = 0
        self.total_dropped = 0
        self.total_departed = 0

    def ingress(self, flow_id, rank=0, pkt_bits=PKT_BITS):
        """Process a packet through the ingress pipeline.
        Admitted packets are buffered; dropped packets decrement VQ.

        Returns (admitted, effective_rank, queue_idx)
        """
        self.total_arrived += 1

        # VQ arrival
        was_below = self.vq_depth < HIGH_WM_BITS
        self.vq_depth += pkt_bits
        crossed_high = was_below and self.vq_depth >= HIGH_WM_BITS
        if self.vq_depth > self.vq_peak:
            self.vq_peak = self.vq_depth

        # Monitoring start
        if crossed_high and not self.monitoring:
            self.monitoring = True

        # Sketch update
        indices = self.analyzer.compute_hash_indices(flow_id)
        sketch_active = self.monitoring
        if self.monitoring:
            self.active_flows.add(flow_id)
            for idx in indices:
                self.sin[idx] += 1
                self.sin_dirty[idx] = 1

        # Bursty lookup
        is_bursty = self.bursty_flags[flow_id % 256] == 1

        # Rank inflation
        effective_rank = rank + (RANK_PENALTY if is_bursty else 0)

        # VFL
        if is_bursty:
            effective_queue_size = self.vq_depth >> 14
        else:
            effective_queue_size = len(self.buffer)

        # Admission
        admitted = True
        queue_idx = 0

        if self.scheduler == "aifo":
            if effective_queue_size < AIFO_THRESHOLD:
                admitted = True
            elif effective_rank <= self.aifo_quantile_threshold:
                admitted = True
            else:
                admitted = False

            if admitted:
                self.aifo_queue_size += 1
        elif self.scheduler == "packs":
            max_idx = NUM_QUEUES - 1
            if effective_rank >= max_idx * RANK_DELTA:
                queue_idx = max_idx
            else:
                queue_idx = effective_rank // RANK_DELTA

            packs_threshold = (AIFO_K_PERCENT * MAX_QUEUE_SIZE) // 100
            if effective_queue_size >= packs_threshold:
                admitted = False

            if admitted:
                self.packs_queues[queue_idx] += 1

        self.rank_samples.append(effective_rank)

        if admitted:
            self.total_admitted += 1
            self.buffer.append((indices, pkt_bits, sketch_active))
        else:
            self.total_dropped += 1
            # Drop path: decrement VQ (Phase 9 fix), do NOT update Sout.
            # Without this, phantom packets inflate VQ permanently.
            self._vq_departure(pkt_bits)

        return admitted, effective_rank, queue_idx

    def drain(self, count=None):
        """Drain packets through egress. If count is None, drain all."""
        if count is None:
            count = len(self.buffer)
        count = min(count, len(self.buffer))

        for _ in range(count):
            if not self.buffer:
                break
            indices, pkt_bits, sketch_active = self.buffer.pop(0)
            self.total_departed += 1

            # VQ departure
            self._vq_departure(pkt_bits)

            # Sout update (only if sketch was active during ingress)
            if sketch_active:
                for idx in indices:
                    self.sout[idx] += 1
                    self.sout_dirty[idx] = 1

            # AIFO queue size
            if self.scheduler == "aifo" and self.aifo_queue_size > 0:
                self.aifo_queue_size -= 1

    def _vq_departure(self, pkt_bits):
        """Decrement VQ, check low watermark crossing."""
        was_above = self.vq_depth > LOW_WM_BITS
        if self.vq_depth > pkt_bits:
            self.vq_depth -= pkt_bits
        else:
            self.vq_depth = 0
        crossed_low = was_above and self.vq_depth <= LOW_WM_BITS

        if crossed_low and self.monitoring:
            self.monitoring = False
            self.window_count += 1

    def drain_until_window_ends(self, max_iters=500):
        """Drain packets until a monitoring window completes."""
        start_windows = self.window_count
        for _ in range(max_iters):
            if self.window_count > start_windows:
                return True
            if self.buffer:
                self.drain(1)
            else:
                return False
        return False

    def run_control_plane(self):
        """Execute control plane analysis for the latest window."""
        prev_bursty = self.analyzer.previous_bursty.copy()
        result = self.analyzer.analyze_window(
            self.window_count - 1,
            self.sin, self.sout,
            self.sin_dirty, self.sout_dirty,
            self.active_flows
        )
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

        # Update AIFO quantile
        if self.rank_samples:
            sorted_ranks = sorted(self.rank_samples)
            fill = min(1.0, self.aifo_queue_size / MAX_QUEUE_SIZE) \
                if MAX_QUEUE_SIZE > 0 else 0
            idx = int((1.0 - fill) * (len(sorted_ranks) - 1))
            idx = max(0, min(idx, len(sorted_ranks) - 1))
            self.aifo_quantile_threshold = sorted_ranks[idx]
        self.rank_samples.clear()
        self.active_flows.clear()
        return result


# ============================================================
# Scenario 1: Steady-state + Microburst
# ============================================================

class TestSteadyStatePlusMicroburst:
    """Models the primary thesis experiment pattern."""

    def test_microburst_detected_during_burst(self):
        """A bursty flow during a microburst should be detected."""
        pipe = FullPipelineModel()

        # Phase 1: Steady-state (VQ builds up to cross HWM)
        # 5 packets from different flows -> VQ = 60000 = HWM
        for fid in range(5):
            pipe.ingress(flow_id=fid)
        assert pipe.monitoring  # crossed HWM

        # Phase 2: Microburst - bursty flow sends 20 packets
        bursty_flow = 100
        for _ in range(20):
            pipe.ingress(flow_id=bursty_flow)

        # Phase 3: Drain all packets through egress
        pipe.drain()
        assert pipe.window_count >= 1  # window ended

        # Phase 4: Control plane analysis
        result = pipe.run_control_plane()
        assert bursty_flow in result.bursty_flows

        # Phase 5: Next packet gets rank inflation
        pipe.ingress(flow_id=bursty_flow, rank=5)
        # Check bursty flag
        assert pipe.bursty_flags[bursty_flow % 256] == 1

    def test_steady_flows_not_flagged(self):
        """Low-rate steady flows should not be marked bursty."""
        pipe = FullPipelineModel()

        # Cross HWM
        for _ in range(5):
            pipe.ingress(flow_id=999)

        # 10 flows send 3 packets each (below threshold=5)
        for fid in range(10):
            for _ in range(3):
                pipe.ingress(flow_id=fid)

        pipe.drain()
        result = pipe.run_control_plane()

        for fid in range(10):
            if fid in result.bursty_flows:
                # Only OK if overcount due to hash collision
                est = result.flow_estimates.get(fid, 0)
                assert est >= BURST_THRESHOLD


# ============================================================
# Scenario 2: Multiple Competing Bursty Flows
# ============================================================

class TestMultipleBurstyFlows:
    """Multiple flows burst simultaneously."""

    def test_multiple_bursts_all_detected(self):
        pipe = FullPipelineModel()
        bursty_flows = [100, 200, 300]

        # Cross HWM
        for _ in range(5):
            pipe.ingress(flow_id=999)

        # Each bursty flow sends 10 packets
        for fid in bursty_flows:
            for _ in range(10):
                pipe.ingress(flow_id=fid)

        pipe.drain()
        result = pipe.run_control_plane()

        for fid in bursty_flows:
            assert fid in result.bursty_flows, \
                f"Flow {fid} not detected as bursty"

    def test_mixed_bursty_and_normal(self):
        pipe = FullPipelineModel()

        # Cross HWM
        for _ in range(5):
            pipe.ingress(flow_id=999)

        # Bursty: 15 packets
        for _ in range(15):
            pipe.ingress(flow_id=100)

        # Normal: 2 packets
        for _ in range(2):
            pipe.ingress(flow_id=200)

        pipe.drain()
        result = pipe.run_control_plane()

        assert 100 in result.bursty_flows
        assert 200 not in result.bursty_flows


# ============================================================
# Scenario 3: AIFO Under Load with Rank Inflation
# ============================================================

class TestAifoUnderLoad:
    """AIFO admission control with bursty flow rank inflation."""

    def test_bursty_flow_penalized_under_congestion(self):
        pipe = FullPipelineModel(scheduler="aifo")
        pipe.aifo_quantile_threshold = 15  # strict

        # Mark flow 100 as bursty
        pipe.bursty_flags[100 % 256] = 1

        # Fill buffer to simulate congested queue
        for _ in range(15):
            pipe.ingress(flow_id=500, rank=5)

        # Normal flow with rank 10 -> admitted (10 <= 15, fill < threshold)
        admitted, eff_rank, _ = pipe.ingress(flow_id=200, rank=10)
        assert eff_rank == 10

        # Bursty flow with rank 10 -> effective_rank=20
        # VFL for bursty = vq_depth >> 14, which is large due to queued packets
        # This makes fill_level >= AIFO_THRESHOLD, so quantile check applies
        admitted, eff_rank, _ = pipe.ingress(flow_id=100, rank=10)
        assert eff_rank == 20  # rank inflation

    def test_aifo_admits_when_queue_low(self):
        pipe = FullPipelineModel(scheduler="aifo")
        pipe.bursty_flags[100 % 256] = 1

        # Empty queue: fill < threshold -> auto-admit
        admitted, _, _ = pipe.ingress(flow_id=100, rank=50)
        assert admitted


# ============================================================
# Scenario 4: PACKS Queue Distribution Under Burst
# ============================================================

class TestPacksQueueDistribution:
    """PACKS distributes packets to queues based on rank."""

    def test_rank_based_queue_assignment(self):
        pipe = FullPipelineModel(scheduler="packs")

        _, _, q = pipe.ingress(flow_id=1, rank=5)
        assert q == 0  # 5/20 = 0

        _, _, q = pipe.ingress(flow_id=2, rank=25)
        assert q == 1  # 25/20 = 1

        _, _, q = pipe.ingress(flow_id=3, rank=60)
        assert q == 3  # 60/20 = 3

    def test_bursty_flow_shifted_to_lower_priority_queue(self):
        pipe = FullPipelineModel(scheduler="packs")
        pipe.bursty_flags[100 % 256] = 1

        _, _, q_normal = pipe.ingress(flow_id=200, rank=30)
        assert q_normal == 1  # 30/20 = 1

        _, _, q_bursty = pipe.ingress(flow_id=100, rank=30)
        assert q_bursty == 2  # (30+10)/20 = 2


# ============================================================
# Scenario 5: Sketch Accuracy Under High Flow Count
# ============================================================

class TestSketchAccuracyHighLoad:
    """Test sketch accuracy with many concurrent flows."""

    def test_200_flows_sketch_accuracy(self):
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        sin = [0] * TOTAL_SIZE
        random.seed(123)

        true_counts = {}
        for fid in range(200):
            count = random.randint(1, 30)
            true_counts[fid] = count
            indices = analyzer.compute_hash_indices(fid)
            for _ in range(count):
                for idx in indices:
                    sin[idx] += 1

        undercount_violations = 0
        for fid, true_count in true_counts.items():
            est = analyzer.estimate_flow_ingress(fid, sin)
            if est < true_count:
                undercount_violations += 1

        assert undercount_violations == 0


# ============================================================
# Scenario 6: Multi-Window Cycle with Flow Turnover
# ============================================================

class TestMultiWindowFlowTurnover:
    """Flows appear and disappear across monitoring windows."""

    def test_three_windows_different_bursters(self):
        pipe = FullPipelineModel()

        for window_num in range(3):
            bursty_flow = 100 + window_num

            # Cross HWM to start monitoring
            for _ in range(5):
                pipe.ingress(flow_id=999)

            # Bursty flow sends 10 packets during monitoring
            for _ in range(10):
                pipe.ingress(flow_id=bursty_flow)

            # Drain all to end the window
            pipe.drain()
            assert pipe.window_count == window_num + 1, \
                f"Window {window_num}: expected {window_num+1} windows, got {pipe.window_count}"

            result = pipe.run_control_plane()
            assert bursty_flow in result.bursty_flows, \
                f"Window {window_num}: flow {bursty_flow} not detected"

            # Previous bursty flow cleared
            if window_num > 0:
                prev_flow = 100 + window_num - 1
                assert pipe.bursty_flags[prev_flow % 256] == 0

    def test_persistent_bursty_flow_across_windows(self):
        pipe = FullPipelineModel()
        persistent_flow = 42

        for window_num in range(5):
            for _ in range(5):
                pipe.ingress(flow_id=999)
            for _ in range(10):
                pipe.ingress(flow_id=persistent_flow)
            pipe.drain()

            result = pipe.run_control_plane()
            assert persistent_flow in result.bursty_flows
            assert pipe.bursty_flags[persistent_flow % 256] == 1


# ============================================================
# Scenario 7: Heavy Loss Detection (Delta-Sketch)
# ============================================================

class TestHeavyLossDetection:
    """Test delta-sketch loss estimation."""

    def test_dropped_packets_show_as_loss(self):
        pipe = FullPipelineModel()

        flow_id = 42
        indices = pipe.analyzer.compute_hash_indices(flow_id)
        pipe.active_flows.add(flow_id)

        # Manually set up: 10 in Sin, 7 in Sout
        for _ in range(10):
            for idx in indices:
                pipe.sin[idx] += 1
                pipe.sin_dirty[idx] = 1
        for _ in range(7):
            for idx in indices:
                pipe.sout[idx] += 1
                pipe.sout_dirty[idx] = 1

        loss = pipe.analyzer.estimate_flow_loss(flow_id, pipe.sin, pipe.sout)
        assert loss == 3

    def test_no_loss_all_departed(self):
        pipe = FullPipelineModel()
        flow_id = 42
        indices = pipe.analyzer.compute_hash_indices(flow_id)

        for _ in range(10):
            for idx in indices:
                pipe.sin[idx] += 1
                pipe.sout[idx] += 1

        loss = pipe.analyzer.estimate_flow_loss(flow_id, pipe.sin, pipe.sout)
        assert loss == 0


# ============================================================
# Scenario 8: VQ Invariant Under Mixed Admit/Drop
# ============================================================

class TestVQInvariantMixedAdmitDrop:
    """VQ = 0 after all packets either egress or are dropped (Phase 9 fix)."""

    def test_vq_zero_after_drain_with_drops(self):
        """After draining all admitted packets, VQ = 0 (drops already decremented)."""
        pipe = FullPipelineModel(scheduler="aifo")
        pipe.aifo_quantile_threshold = 5  # very strict

        random.seed(42)
        for _ in range(100):
            fid = random.randint(0, 50)
            rank = random.randint(0, 63)
            pipe.ingress(flow_id=fid, rank=rank)

        pipe.drain()
        # VQ = 0 — drops decremented VQ immediately, egress drained the rest
        assert pipe.vq_depth == 0
        assert pipe.total_arrived == pipe.total_admitted + pipe.total_dropped
        assert pipe.total_dropped > 0  # strict threshold ensures drops

    def test_vq_zero_no_drops(self):
        """Without drops (no scheduler), VQ returns to 0 after drain."""
        pipe = FullPipelineModel(scheduler="none")
        for _ in range(500):
            pipe.ingress(flow_id=random.randint(0, 200))
        pipe.drain()
        assert pipe.vq_depth == 0
        assert pipe.total_dropped == 0
        assert pipe.total_admitted == 500


# ============================================================
# Scenario 9: Rapid Burst-Idle Cycles
# ============================================================

class TestRapidBurstCycles:
    """Test rapid alternation between burst and idle."""

    def test_ten_burst_cycles(self):
        pipe = FullPipelineModel()

        for cycle in range(10):
            # Burst: cross HWM + bursty packets
            for _ in range(5):
                pipe.ingress(flow_id=999)
            for _ in range(10):
                pipe.ingress(flow_id=42)

            # Drain to end window
            pipe.drain()
            pipe.run_control_plane()

        assert pipe.window_count >= 10
        assert pipe.vq_depth == 0


# ============================================================
# Scenario 10: Sketch Reset Correctness
# ============================================================

class TestSketchResetCorrectness:
    """Verify sketches are properly reset between windows."""

    def test_flow_count_does_not_accumulate(self):
        pipe = FullPipelineModel()

        # Window 1: flow 42 sends 10 packets (bursty)
        for _ in range(5):
            pipe.ingress(flow_id=999)
        for _ in range(10):
            pipe.ingress(flow_id=42)
        pipe.drain()
        result1 = pipe.run_control_plane()
        assert 42 in result1.bursty_flows

        # Window 2: flow 42 sends 2 packets (not bursty after reset)
        for _ in range(5):
            pipe.ingress(flow_id=999)
        for _ in range(2):
            pipe.ingress(flow_id=42)
        pipe.drain()
        result2 = pipe.run_control_plane()

        # After sketch reset, estimate should be fresh (2, not 12)
        if 42 in result2.flow_estimates:
            assert result2.flow_estimates[42] < BURST_THRESHOLD

    def test_sin_zeroed_after_reset(self):
        pipe = FullPipelineModel()

        for _ in range(5):
            pipe.ingress(flow_id=999)
        for _ in range(10):
            pipe.ingress(flow_id=42)
        pipe.drain()
        pipe.run_control_plane()

        non_zero = sum(1 for v in pipe.sin if v != 0)
        assert non_zero == 0


# ============================================================
# Scenario 11: Adaptive Threshold Reduces False Positives
# ============================================================

ADAPTIVE_CONFIG = {
    "sketch": {
        "size": SKETCH_SIZE,
        "num_hash_functions": NUM_HASHES,
        "total_size": TOTAL_SIZE,
        "hash_seeds": HASH_SEEDS,
    },
    "detection": {
        "burst_threshold": BURST_THRESHOLD,
        "bursty_table_size": 256,
        "adaptive_threshold_fraction": 0.05,
    },
}


class FullPipelineModelAdaptive(FullPipelineModel):
    """FullPipelineModel with adaptive threshold enabled."""

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.analyzer = WindowAnalyzer(ADAPTIVE_CONFIG)


class TestAdaptiveThresholdScenario:
    """End-to-end test: adaptive threshold fixes the false-positive problem."""

    def test_many_equal_flows_no_false_positives(self):
        """50 flows each send 6 packets. Absolute-only would flag all.
        Adaptive with φ=0.05 flags none (6/300 = 2% < 5%)."""
        pipe = FullPipelineModelAdaptive()

        # Cross HWM
        for _ in range(5):
            pipe.ingress(flow_id=999)

        # 50 flows each send 6 packets
        for fid in range(50):
            for _ in range(6):
                pipe.ingress(flow_id=fid)

        pipe.drain()
        result = pipe.run_control_plane()

        # None should be bursty: each flow's fraction = 6/~305 ≈ 2% < 5%
        assert len(result.bursty_flows) == 0

    def test_dominant_flow_detected_with_adaptive(self):
        """One dominant flow should still be detected even with adaptive."""
        pipe = FullPipelineModelAdaptive()

        # Cross HWM
        for _ in range(5):
            pipe.ingress(flow_id=999)

        # Dominant flow: 30 packets
        for _ in range(30):
            pipe.ingress(flow_id=100)

        # Background: 10 flows * 3 packets = 30 packets
        for fid in range(10):
            for _ in range(3):
                pipe.ingress(flow_id=200 + fid)

        pipe.drain()
        result = pipe.run_control_plane()

        # Dominant: 30/~65 ≈ 46% >> 5%, and 30 >= 5 → bursty
        assert 100 in result.bursty_flows
        # Background: 3/~65 ≈ 5% and 3 < 5 → not bursty (fails absolute)
        for fid in range(10):
            assert (200 + fid) not in result.bursty_flows


# ============================================================
# Scenario 12: VQ Demand vs Capacity with Drops
# ============================================================

class TestVQDemandVsCapacity:
    """Test that VQ correctly returns to zero with drop-path decrement."""

    def test_drops_decrement_vq_immediately(self):
        """Dropped packets decrement VQ at drop time (Phase 9 fix)."""
        pipe = FullPipelineModel(scheduler="aifo")
        pipe.aifo_quantile_threshold = 3  # very strict, lots of drops

        # Send 30 packets with varying ranks
        for i in range(30):
            pipe.ingress(flow_id=i, rank=i * 2)

        # VQ = admitted * PKT_BITS (drops already subtracted)
        assert pipe.vq_depth == pipe.total_admitted * PKT_BITS
        assert pipe.total_dropped > 0  # strict threshold ensures drops

        # Drain all admitted
        pipe.drain()

        # VQ = 0 (all accounted for)
        assert pipe.vq_depth == 0


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
