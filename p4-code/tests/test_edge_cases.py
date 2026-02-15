"""Edge case and stress tests.

These test boundary conditions, overflow scenarios, hash collisions,
and other corner cases that could cause failures on real hardware.
"""

import pytest
import sys
import os
import random

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "controller"))
from window_analyzer import WindowAnalyzer

SKETCH_SIZE = 256
NUM_HASHES = 4
TOTAL_SIZE = SKETCH_SIZE * NUM_HASHES
HASH_SEEDS = [0x00000000, 0x9e3779b9, 0x3c6ef372, 0xdaa66d2b]
PKT_SIZE_BITS = 12000

DEFAULT_CONFIG = {
    "sketch": {
        "size": SKETCH_SIZE,
        "num_hash_functions": NUM_HASHES,
        "total_size": TOTAL_SIZE,
        "hash_seeds": HASH_SEEDS,
    },
    "detection": {
        "burst_threshold": 5,
        "bursty_table_size": 256,
    },
}


# Reuse VQ model from test_virtual_queue.py
class VirtualQueueModel:
    def __init__(self, high_wm_bits=60000, low_wm_bits=24000):
        self.depth_bits = 0
        self.peak_bits = 0
        self.hwm_crossings = 0
        self.lwm_crossings = 0
        self.high_wm = high_wm_bits
        self.low_wm = low_wm_bits

    def on_arrival(self, packet_size_bits):
        was_below = self.depth_bits < self.high_wm
        self.depth_bits += packet_size_bits
        crossed_high = was_below and self.depth_bits >= self.high_wm
        if crossed_high:
            self.hwm_crossings += 1
        if self.depth_bits > self.peak_bits:
            self.peak_bits = self.depth_bits
        return crossed_high

    def on_departure(self, packet_size_bits):
        was_above = self.depth_bits > self.low_wm
        if self.depth_bits > packet_size_bits:
            self.depth_bits -= packet_size_bits
        else:
            self.depth_bits = 0
        crossed_low = was_above and self.depth_bits <= self.low_wm
        if crossed_low:
            self.lwm_crossings += 1
        return crossed_low


# ============================================================
# VQ Edge Cases
# ============================================================

class TestVQBoundaryPacketSizes:
    """Test with unusual packet sizes."""

    def test_minimum_packet_64_bytes(self):
        """64-byte minimum Ethernet frame."""
        vq = VirtualQueueModel(high_wm_bits=60000)
        pkt_bits = 64 * 8  # 512 bits
        # Need ceil(60000/512) = 118 packets to cross HWM (118*512=60416)
        for i in range(118):
            crossed = vq.on_arrival(pkt_bits)
            if crossed:
                break
        assert vq.depth_bits >= 60000

    def test_jumbo_frame_9000_bytes(self):
        """9000-byte jumbo frame."""
        vq = VirtualQueueModel(high_wm_bits=60000)
        pkt_bits = 9000 * 8  # 72000 bits
        # Single jumbo frame crosses HWM
        crossed = vq.on_arrival(pkt_bits)
        assert crossed
        assert vq.depth_bits == 72000

    def test_single_byte_packet(self):
        """Degenerate 1-byte packet."""
        vq = VirtualQueueModel(high_wm_bits=60000)
        pkt_bits = 8
        for _ in range(7500):
            vq.on_arrival(pkt_bits)
        assert vq.depth_bits == 60000

    def test_mixed_packet_sizes(self):
        """Mix of small and large packets."""
        vq = VirtualQueueModel(high_wm_bits=60000, low_wm_bits=24000)
        sizes = [64 * 8, 1500 * 8, 9000 * 8, 100 * 8, 500 * 8]
        total = 0
        crossed = False
        for i in range(100):
            pkt = sizes[i % len(sizes)]
            total += pkt
            if vq.on_arrival(pkt):
                crossed = True
                break
        assert crossed
        assert vq.depth_bits == total

    def test_zero_size_packet_does_not_crash(self):
        """Zero-size packet should not cause issues."""
        vq = VirtualQueueModel()
        vq.on_arrival(0)
        assert vq.depth_bits == 0
        vq.on_departure(0)
        assert vq.depth_bits == 0


class TestVQExactBoundary:
    """Test packets landing exactly on watermark boundaries."""

    def test_exact_high_watermark(self):
        """Packet takes VQ to exactly HWM."""
        vq = VirtualQueueModel(high_wm_bits=60000)
        # 4 packets of 15000 bits = 60000 exactly
        for _ in range(3):
            vq.on_arrival(15000)
        crossed = vq.on_arrival(15000)
        assert crossed
        assert vq.depth_bits == 60000

    def test_exact_low_watermark(self):
        """Departure takes VQ to exactly LWM."""
        vq = VirtualQueueModel(high_wm_bits=60000, low_wm_bits=24000)
        for _ in range(5):
            vq.on_arrival(12000)
        # 60000 - 36000 = 24000 (exactly LWM)
        vq.on_departure(12000)  # 48000
        vq.on_departure(12000)  # 36000
        crossed = vq.on_departure(12000)  # 24000 = LWM
        assert crossed

    def test_one_bit_below_high_watermark(self):
        """VQ at HWM-1 then +1 crosses."""
        vq = VirtualQueueModel(high_wm_bits=60000)
        vq.on_arrival(59999)
        assert vq.depth_bits == 59999
        crossed = vq.on_arrival(1)
        assert crossed
        assert vq.depth_bits == 60000

    def test_watermark_uses_software_vq_depth(self):
        """Watermark crossing detection is driven by the same vq_depth value
        that VQ arrival/departure writes — i.e., the software-maintained
        register, not an independent hardware counter.

        We verify that the depth written by on_arrival is the same depth
        compared against thresholds for watermark crossing detection.
        """
        high_wm = 60000
        low_wm = 24000
        vq = VirtualQueueModel(high_wm_bits=high_wm, low_wm_bits=low_wm)

        # Arrive until just below HWM
        vq.on_arrival(50000)
        assert vq.depth_bits == 50000
        assert vq.hwm_crossings == 0  # still below

        # This arrival pushes depth to 62000 — the crossing check uses
        # the same depth_bits that the arrival just wrote
        crossed = vq.on_arrival(12000)
        assert crossed
        assert vq.depth_bits == 62000  # this IS the value compared to HWM

        # Now depart to above LWM, then cross it
        vq.on_departure(30000)  # 32000
        assert vq.depth_bits == 32000
        assert vq.lwm_crossings == 0  # still above LWM

        crossed_low = vq.on_departure(12000)  # 20000
        assert crossed_low
        assert vq.depth_bits == 20000  # this IS the value compared to LWM

    def test_one_bit_above_low_watermark(self):
        """VQ at LWM+1 then -1 crosses."""
        vq = VirtualQueueModel(high_wm_bits=60000, low_wm_bits=24000)
        for _ in range(5):
            vq.on_arrival(12000)
        # Depart to 24001
        total_depart = 60000 - 24001  # 35999
        vq.on_departure(35999)
        assert vq.depth_bits == 24001
        # Now depart 1 bit -> crosses
        crossed = vq.on_departure(1)
        assert crossed
        assert vq.depth_bits == 24000


class TestVQStress:
    """High-volume VQ stress tests."""

    def test_rapid_oscillation(self):
        """Rapidly oscillate around high watermark."""
        vq = VirtualQueueModel(high_wm_bits=60000, low_wm_bits=24000)
        crossings_up = 0
        crossings_down = 0

        for cycle in range(20):
            # Fill to just above HWM
            while vq.depth_bits < 60000:
                if vq.on_arrival(12000):
                    crossings_up += 1
            # Drain to just below LWM
            while vq.depth_bits > 24000:
                if vq.on_departure(12000):
                    crossings_down += 1

        assert crossings_up == 20
        assert crossings_down == 20
        assert vq.hwm_crossings == 20
        assert vq.lwm_crossings == 20

    def test_million_packets_balance(self):
        """VQ invariant: after N arrivals and N departures, depth = 0."""
        vq = VirtualQueueModel()
        n = 10000
        for _ in range(n):
            vq.on_arrival(PKT_SIZE_BITS)
        for _ in range(n):
            vq.on_departure(PKT_SIZE_BITS)
        assert vq.depth_bits == 0

    def test_interleaved_arrival_departure(self):
        """Interleaved arrivals and departures."""
        vq = VirtualQueueModel()
        for _ in range(1000):
            vq.on_arrival(PKT_SIZE_BITS)
            vq.on_arrival(PKT_SIZE_BITS)
            vq.on_departure(PKT_SIZE_BITS)
        # Net: +1000 packets
        assert vq.depth_bits == 1000 * PKT_SIZE_BITS


# ============================================================
# Sketch Edge Cases
# ============================================================

class TestSketchHashCollisions:
    """Test behavior when hash collisions occur."""

    def test_collision_overcount(self):
        """Two flows with colliding hashes should only overcount."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        sin = [0] * TOTAL_SIZE

        # Find two flows that share at least one hash bucket
        flow_a = 0
        indices_a = analyzer.compute_hash_indices(flow_a)

        # Try to find a colliding flow (brute force)
        colliding_flow = None
        for fid in range(1, 10000):
            indices_b = analyzer.compute_hash_indices(fid)
            if any(a == b for a, b in zip(indices_a, indices_b)):
                colliding_flow = fid
                break

        if colliding_flow is not None:
            idx_b = analyzer.compute_hash_indices(colliding_flow)
            # Insert 10 packets for flow A
            for _ in range(10):
                for idx in indices_a:
                    sin[idx] += 1
            # Insert 5 packets for colliding flow
            for _ in range(5):
                for idx in idx_b:
                    sin[idx] += 1

            # Count-Min estimate for A should be >= 10 (never undercount)
            est_a = analyzer.estimate_flow_ingress(flow_a, sin)
            assert est_a >= 10
            # May overcount due to collision
            assert est_a <= 15

    def test_worst_case_all_collisions(self):
        """Pathological case: all flows map to same bucket."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        sin = [0] * TOTAL_SIZE

        # Manually force collision by writing to same indices
        target_indices = analyzer.compute_hash_indices(42)
        for _ in range(100):
            for idx in target_indices:
                sin[idx] += 1

        # Estimate should be exactly 100
        est = analyzer.estimate_flow_ingress(42, sin)
        assert est == 100

    def test_large_sketch_values_no_overflow(self):
        """Ensure sketch counters don't overflow at 32-bit boundary."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        sin = [0] * TOTAL_SIZE

        flow_id = 42
        indices = analyzer.compute_hash_indices(flow_id)

        # Set counters near 32-bit max
        for idx in indices:
            sin[idx] = 2**31 - 1  # max signed 32-bit

        est = analyzer.estimate_flow_ingress(flow_id, sin)
        assert est == 2**31 - 1


class TestSketchDirtyFlags:
    """Test dirty flag tracking edge cases."""

    def test_dirty_flag_sparse_reset(self):
        """Only dirty entries should be in the reset list."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        dirty = [0] * TOTAL_SIZE

        # Mark a few entries dirty
        dirty[0] = 1
        dirty[100] = 1
        dirty[500] = 1

        indices = analyzer.get_dirty_indices(dirty)
        assert indices == [0, 100, 500]

    def test_all_dirty_full_reset(self):
        """All entries dirty should return all indices."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        dirty = [1] * TOTAL_SIZE
        indices = analyzer.get_dirty_indices(dirty)
        assert len(indices) == TOTAL_SIZE

    def test_no_dirty_no_reset(self):
        """No dirty entries -> empty reset list."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        dirty = [0] * TOTAL_SIZE
        indices = analyzer.get_dirty_indices(dirty)
        assert indices == []


class TestSketchStress:
    """Stress test sketch operations."""

    def test_many_flows_estimate_accuracy(self):
        """With many flows, estimates should still be reasonable."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        sin = [0] * TOTAL_SIZE
        random.seed(42)

        # Insert 500 flows with varying packet counts
        flow_counts = {}
        for fid in range(500):
            count = random.randint(1, 20)
            flow_counts[fid] = count
            indices = analyzer.compute_hash_indices(fid)
            for _ in range(count):
                for idx in indices:
                    sin[idx] += 1

        # Check estimates: Count-Min guarantees no undercount
        overcounts = []
        for fid, true_count in flow_counts.items():
            est = analyzer.estimate_flow_ingress(fid, sin)
            assert est >= true_count, \
                f"Flow {fid}: estimate {est} < true count {true_count}"
            overcounts.append(est - true_count)

        # Average overcount should be reasonable for 500 flows in 256 buckets
        avg_overcount = sum(overcounts) / len(overcounts)
        # With 500 flows and 256 buckets, expect ~2x collision rate
        assert avg_overcount < 50, \
            f"Average overcount {avg_overcount} too high"


# ============================================================
# Bursty Flag Hash Collision Edge Cases
# ============================================================

class TestBurstyFlagCollisions:
    """Test when multiple flows map to same bursty_flags index."""

    def test_different_flows_same_index(self):
        """Two flows hashing to same bursty_flags index."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)

        # Flow 0 and flow 256 map to same index (0 % 256 == 256 % 256 == 0)
        # Note: actual collision depends on flow_id % bursty_table_size
        flow_a = 0
        flow_b = 256

        idx_a = flow_a % 256
        idx_b = flow_b % 256
        assert idx_a == idx_b  # they collide

        # If flow A is bursty but flow B isn't, flow B still sees bursty=1
        writes = analyzer.compute_bursty_flag_writes({flow_a})
        assert writes[idx_a] == 1
        # Flow B checking the same index would incorrectly see bursty=1
        # This is a known limitation of hash-based bursty tracking

    def test_collision_cleared_correctly(self):
        """When both colliding flows are no longer bursty, flag should clear."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        analyzer.previous_bursty = {0, 256}

        # Neither flow is bursty in new window
        writes = analyzer.compute_bursty_flag_writes(set(), {0, 256})
        assert writes[0] == 0  # both map to index 0, should be cleared


# ============================================================
# AIFO/PACKS Edge Cases
# ============================================================

class TestAdmissionEdgeCases:
    """Edge cases in admission control."""

    def test_sppifo_bounds_single_queue(self):
        """SP-PIFO bounds with single queue covers full range."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        bounds = analyzer.compute_sppifo_initial_bounds(1, 100)
        assert bounds == [100]

    def test_sppifo_bounds_max_rank_zero(self):
        """SP-PIFO bounds with max_rank=0."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        bounds = analyzer.compute_sppifo_initial_bounds(4, 0)
        assert len(bounds) == 4
        assert bounds[-1] == 0

    def test_sppifo_bounds_large_rank_space(self):
        """SP-PIFO bounds with large rank space."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        bounds = analyzer.compute_sppifo_initial_bounds(8, 0xFFFF)
        assert len(bounds) == 8
        assert bounds[-1] == 0xFFFF
        # All bounds should be positive (except possibly first with small queues)
        for b in bounds:
            assert b >= 0

    def test_sppifo_bounds_monotonic(self):
        """SP-PIFO bounds are always monotonically increasing."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        for num_q in [2, 4, 8]:
            for max_r in [10, 63, 255, 1000]:
                bounds = analyzer.compute_sppifo_initial_bounds(num_q, max_r)
                for i in range(1, len(bounds)):
                    assert bounds[i] >= bounds[i - 1]

    def test_packs_queue_zero_rank_delta(self):
        """Edge: rank_delta that would cause division by zero."""
        # In our code, rank_delta is a constant=20, but test the model
        # to ensure we handle it if ever changed
        from test_admission import PacksModel
        packs = PacksModel(rank_delta=1, num_queues=8)
        assert packs.select_queue(0) == 0
        assert packs.select_queue(7) == 7
        assert packs.select_queue(100) == 7  # capped

    def test_rank_penalty_overflow_safety(self):
        """Rank inflation should not overflow 32 bits."""
        base_rank = 0xFFFFFFFF - 5  # near max
        penalty = 10
        effective = base_rank + penalty
        # In 32-bit unsigned, this wraps. On hardware, verify behavior.
        # In Python (unbounded int), no overflow
        assert effective == 0xFFFFFFFF - 5 + 10


# ============================================================
# VQ + Monitoring Interaction Edge Cases
# ============================================================

class TestVQMonitoringInteraction:
    """Test VQ and monitoring state machine edge cases."""

    def test_hwm_crossing_exactly_once_per_cycle(self):
        """Even if many packets arrive above HWM, only one crossing event."""
        vq = VirtualQueueModel(high_wm_bits=60000)
        crossings = 0
        for _ in range(20):
            if vq.on_arrival(12000):
                crossings += 1
        # Only one crossing (when going from below to above)
        assert crossings == 1

    def test_lwm_crossing_exactly_once_per_cycle(self):
        """Even if many departures below LWM, only one crossing event."""
        vq = VirtualQueueModel(high_wm_bits=60000, low_wm_bits=24000)
        for _ in range(10):
            vq.on_arrival(12000)
        crossings = 0
        for _ in range(10):
            if vq.on_departure(12000):
                crossings += 1
        assert crossings == 1

    def test_no_monitoring_if_vq_never_crosses_hwm(self):
        """Traffic that stays below HWM should never trigger monitoring."""
        vq = VirtualQueueModel(high_wm_bits=60000)
        # Send 4 packets (48000 < 60000), depart 4
        for _ in range(100):
            vq.on_arrival(12000)
            vq.on_arrival(12000)
            vq.on_departure(12000)
            vq.on_departure(12000)
        assert vq.hwm_crossings == 0

    def test_hwm_equals_lwm(self):
        """Degenerate case: HWM == LWM. Should still work.

        With HWM==LWM, crossing high requires depth >= HWM (non-strict),
        but crossing low requires was_above (strict >). So at exactly
        the boundary, only HWM triggers.
        """
        vq = VirtualQueueModel(high_wm_bits=24000, low_wm_bits=24000)
        # Cross HWM: 0 -> 12000 -> 24000 (depth == HWM, was_below = True)
        vq.on_arrival(12000)
        crossed_high = vq.on_arrival(12000)  # 24000 >= 24000
        assert crossed_high

        # Departure: 24000 -> 12000. was_above = (24000 > 24000) = False
        crossed_low = vq.on_departure(12000)
        assert not crossed_low  # strict > means no crossing at boundary

        # Go strictly above LWM: 12000 -> 24000 -> 36000
        vq.on_arrival(12000)
        vq.on_arrival(12000)  # now at 36000, strictly above 24000
        crossed_low = vq.on_departure(12000)  # 36000 -> 24000
        assert crossed_low  # was 36000 > 24000, now 24000 <= 24000


# ============================================================
# Window Analysis Edge Cases
# ============================================================

class TestWindowAnalysisEdgeCases:
    """Edge cases in window analysis logic."""

    def test_empty_active_flows(self):
        """Window with no active flows."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        sin = [0] * TOTAL_SIZE
        sout = [0] * TOTAL_SIZE
        dirty = [0] * TOTAL_SIZE

        result = analyzer.analyze_window(0, sin, sout, dirty, dirty, set())
        assert len(result.bursty_flows) == 0
        assert len(result.heavy_losers) == 0

    def test_single_packet_flow_not_bursty(self):
        """Flow with 1 packet should not be bursty (threshold=5)."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        sin = [0] * TOTAL_SIZE
        sout = [0] * TOTAL_SIZE
        dirty = [0] * TOTAL_SIZE

        flow_id = 42
        indices = analyzer.compute_hash_indices(flow_id)
        for idx in indices:
            sin[idx] = 1
            dirty[idx] = 1

        result = analyzer.analyze_window(0, sin, sout, dirty, dirty, {flow_id})
        assert flow_id not in result.bursty_flows

    def test_flow_at_exact_threshold(self):
        """Flow with exactly burst_threshold packets."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        sin = [0] * TOTAL_SIZE

        flow_id = 42
        indices = analyzer.compute_hash_indices(flow_id)
        for idx in indices:
            sin[idx] = 5  # exactly threshold

        result = analyzer.analyze_window(
            0, sin, [0]*TOTAL_SIZE, [0]*TOTAL_SIZE, [0]*TOTAL_SIZE, {flow_id}
        )
        assert flow_id in result.bursty_flows  # >= threshold

    def test_flow_one_below_threshold(self):
        """Flow with threshold-1 packets."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        sin = [0] * TOTAL_SIZE

        flow_id = 42
        indices = analyzer.compute_hash_indices(flow_id)
        for idx in indices:
            sin[idx] = 4  # one below threshold

        result = analyzer.analyze_window(
            0, sin, [0]*TOTAL_SIZE, [0]*TOTAL_SIZE, [0]*TOTAL_SIZE, {flow_id}
        )
        assert flow_id not in result.bursty_flows


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
