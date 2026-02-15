"""Unit tests for Count-Min Delta-Sketch operations.

Tests hash distribution, sketch increment, dirty flags,
and the control-plane analysis in WindowAnalyzer.
"""

import pytest
import sys
import os
import zlib
from collections import Counter

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "controller"))
from window_analyzer import WindowAnalyzer

SKETCH_SIZE = 256
NUM_HASHES = 4
TOTAL_SIZE = SKETCH_SIZE * NUM_HASHES
HASH_SEEDS = [0x00000000, 0x9e3779b9, 0x3c6ef372, 0xdaa66d2b]
BURST_THRESHOLD = 5

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


class TestHashDistribution:
    """Verify hash functions produce reasonable distribution."""

    def test_all_indices_in_range(self):
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        for flow_id in range(1000):
            indices = analyzer.compute_hash_indices(flow_id)
            assert len(indices) == NUM_HASHES
            for row, idx in enumerate(indices):
                assert row * SKETCH_SIZE <= idx < (row + 1) * SKETCH_SIZE, \
                    f"Index {idx} out of range for row {row}"

    def test_distribution_uniformity(self):
        """Verify hashes spread across buckets (chi-squared-like check)."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        bucket_counts = [Counter() for _ in range(NUM_HASHES)]

        for flow_id in range(10000):
            indices = analyzer.compute_hash_indices(flow_id)
            for row, idx in enumerate(indices):
                col = idx - row * SKETCH_SIZE
                bucket_counts[row][col] += 1

        # Each bucket should have ~10000/256 = ~39 entries
        # Allow 3x deviation (very generous for uniform)
        expected = 10000 / SKETCH_SIZE
        for row in range(NUM_HASHES):
            for col in range(SKETCH_SIZE):
                count = bucket_counts[row].get(col, 0)
                assert count < expected * 4, \
                    f"Row {row}, col {col} has {count} entries (expected ~{expected})"

    def test_different_seeds_produce_different_indices(self):
        """Different hash rows should produce different column indices."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        indices = analyzer.compute_hash_indices(42)
        columns = [idx % SKETCH_SIZE for idx in indices]
        # Very unlikely all 4 hashes produce same column
        assert len(set(columns)) > 1, "All hash functions produced same column"

    def test_deterministic(self):
        """Same flow_id always produces same indices."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        idx1 = analyzer.compute_hash_indices(12345)
        idx2 = analyzer.compute_hash_indices(12345)
        assert idx1 == idx2


class TestSketchIncrement:
    """Test sketch update and Count-Min estimation."""

    def _make_sketch(self):
        return [0] * TOTAL_SIZE

    def test_single_flow_increment(self):
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        sin = self._make_sketch()

        flow_id = 100
        indices = analyzer.compute_hash_indices(flow_id)

        # Simulate 5 packet arrivals
        for _ in range(5):
            for idx in indices:
                sin[idx] += 1

        estimate = analyzer.estimate_flow_ingress(flow_id, sin)
        assert estimate == 5

    def test_multiple_flows_no_collision(self):
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        sin = self._make_sketch()

        # Two flows
        flow_a, flow_b = 100, 200
        idx_a = analyzer.compute_hash_indices(flow_a)
        idx_b = analyzer.compute_hash_indices(flow_b)

        for _ in range(10):
            for idx in idx_a:
                sin[idx] += 1
        for _ in range(3):
            for idx in idx_b:
                sin[idx] += 1

        est_a = analyzer.estimate_flow_ingress(flow_a, sin)
        est_b = analyzer.estimate_flow_ingress(flow_b, sin)

        # Count-Min guarantees no undercount
        assert est_a >= 10
        assert est_b >= 3

    def test_count_min_overcount_bounded(self):
        """With many flows, overcount should be bounded."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        sin = self._make_sketch()

        # Insert 100 flows with 1 packet each
        for fid in range(100):
            indices = analyzer.compute_hash_indices(fid)
            for idx in indices:
                sin[idx] += 1

        # Check first flow: should be close to 1
        est = analyzer.estimate_flow_ingress(0, sin)
        assert est >= 1
        assert est <= 10  # generous bound for 100 flows in 256 buckets

    def test_empty_sketch_returns_zero(self):
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        sin = self._make_sketch()
        est = analyzer.estimate_flow_ingress(42, sin)
        assert est == 0


class TestDeltaSketch:
    """Test Sin - Sout delta estimation."""

    def _make_sketch(self):
        return [0] * TOTAL_SIZE

    def test_no_loss(self):
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        sin = self._make_sketch()
        sout = self._make_sketch()

        flow_id = 42
        indices = analyzer.compute_hash_indices(flow_id)

        # 10 arrive, 10 depart
        for _ in range(10):
            for idx in indices:
                sin[idx] += 1
                sout[idx] += 1

        loss = analyzer.estimate_flow_loss(flow_id, sin, sout)
        assert loss == 0

    def test_with_loss(self):
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        sin = self._make_sketch()
        sout = self._make_sketch()

        flow_id = 42
        indices = analyzer.compute_hash_indices(flow_id)

        # 10 arrive, 7 depart -> 3 in-flight/lost
        for _ in range(10):
            for idx in indices:
                sin[idx] += 1
        for _ in range(7):
            for idx in indices:
                sout[idx] += 1

        loss = analyzer.estimate_flow_loss(flow_id, sin, sout)
        assert loss == 3

    def test_negative_loss_clamped_to_zero(self):
        """If Sout > Sin (race condition), delta should be 0."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        sin = self._make_sketch()
        sout = self._make_sketch()

        flow_id = 42
        indices = analyzer.compute_hash_indices(flow_id)

        for _ in range(5):
            for idx in indices:
                sin[idx] += 1
        for _ in range(8):
            for idx in indices:
                sout[idx] += 1

        loss = analyzer.estimate_flow_loss(flow_id, sin, sout)
        assert loss == 0


class TestSketchNoUpdateWhenIdle:
    """Verify sketch is NOT updated when monitoring is IDLE.

    This is enforced in P4 by the `if monitoring_state == MONITORING` guard.
    We test the expected behavior pattern here.
    """

    def test_idle_state_no_sketch_update(self):
        """Model test: sketch should not change when idle."""
        sin = [0] * TOTAL_SIZE
        monitoring = False

        # Simulate packet arrival when idle
        flow_id = 42
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        indices = analyzer.compute_hash_indices(flow_id)

        if monitoring:
            for idx in indices:
                sin[idx] += 1

        # Sketch should remain zero
        est = analyzer.estimate_flow_ingress(flow_id, sin)
        assert est == 0


class TestBurstyFlowDetection:
    """Test WindowAnalyzer bursty flow identification."""

    def _make_sketch(self):
        return [0] * TOTAL_SIZE

    def test_flow_above_threshold_is_bursty(self):
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        sin = self._make_sketch()
        sout = self._make_sketch()
        dirty = [0] * TOTAL_SIZE

        flow_id = 42
        indices = analyzer.compute_hash_indices(flow_id)

        # 5 packets = threshold
        for _ in range(5):
            for idx in indices:
                sin[idx] += 1
                dirty[idx] = 1

        result = analyzer.analyze_window(
            0, sin, sout, dirty, [0] * TOTAL_SIZE, {flow_id}
        )
        assert flow_id in result.bursty_flows

    def test_flow_below_threshold_not_bursty(self):
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        sin = self._make_sketch()
        sout = self._make_sketch()
        dirty = [0] * TOTAL_SIZE

        flow_id = 42
        indices = analyzer.compute_hash_indices(flow_id)

        # 4 packets < threshold (5)
        for _ in range(4):
            for idx in indices:
                sin[idx] += 1
                dirty[idx] = 1

        result = analyzer.analyze_window(
            0, sin, sout, dirty, [0] * TOTAL_SIZE, {flow_id}
        )
        assert flow_id not in result.bursty_flows


class TestAdaptiveThresholdFraction:
    """Test adaptive threshold: flow_pkts / total_pkts >= φ.

    When adaptive_threshold_fraction > 0, bursty detection requires BOTH:
    1. Absolute count >= burst_threshold
    2. Relative fraction >= adaptive_threshold_fraction
    """

    def _make_sketch(self):
        return [0] * TOTAL_SIZE

    def _make_config(self, phi):
        return {
            "sketch": {
                "size": SKETCH_SIZE,
                "num_hash_functions": NUM_HASHES,
                "total_size": TOTAL_SIZE,
                "hash_seeds": HASH_SEEDS,
            },
            "detection": {
                "burst_threshold": BURST_THRESHOLD,
                "bursty_table_size": 256,
                "adaptive_threshold_fraction": phi,
            },
        }

    def test_high_fraction_flow_is_bursty(self):
        """A flow with high fraction of window traffic is bursty."""
        analyzer = WindowAnalyzer(self._make_config(phi=0.05))
        sin = self._make_sketch()
        dirty = [0] * TOTAL_SIZE

        # Flow sends 10 out of 20 total packets (50% >> 5%)
        flow_id = 42
        bg_flow = 99
        for fid, count in [(flow_id, 10), (bg_flow, 10)]:
            indices = analyzer.compute_hash_indices(fid)
            for _ in range(count):
                for idx in indices:
                    sin[idx] += 1
                    dirty[idx] = 1

        result = analyzer.analyze_window(
            0, sin, self._make_sketch(), dirty, [0] * TOTAL_SIZE,
            {flow_id, bg_flow}
        )
        assert flow_id in result.bursty_flows

    def test_low_fraction_flow_not_bursty(self):
        """A flow above absolute threshold but below φ is NOT bursty."""
        analyzer = WindowAnalyzer(self._make_config(phi=0.10))  # 10%
        sin = self._make_sketch()
        dirty = [0] * TOTAL_SIZE

        # Flow sends 6 packets (>= threshold=5), but total = 200
        # Fraction = 6/200 = 3% < 10% → NOT bursty
        flow_id = 42
        indices_flow = analyzer.compute_hash_indices(flow_id)
        for _ in range(6):
            for idx in indices_flow:
                sin[idx] += 1
                dirty[idx] = 1

        # Background: 194 packets spread across many flows
        for bg_fid in range(194):
            indices_bg = analyzer.compute_hash_indices(1000 + bg_fid)
            for idx in indices_bg:
                sin[idx] += 1
                dirty[idx] = 1

        active = {flow_id} | {1000 + i for i in range(194)}
        result = analyzer.analyze_window(
            0, sin, self._make_sketch(), dirty, [0] * TOTAL_SIZE, active
        )
        # 6 >= burst_threshold (5) BUT fraction 6/200 = 0.03 < 0.10
        assert flow_id not in result.bursty_flows

    def test_phi_zero_disables_adaptive(self):
        """With φ=0.0, only absolute threshold is used (backward compat)."""
        analyzer = WindowAnalyzer(self._make_config(phi=0.0))
        sin = self._make_sketch()
        dirty = [0] * TOTAL_SIZE

        flow_id = 42
        indices = analyzer.compute_hash_indices(flow_id)
        # 5 packets >= threshold, total = 1000
        for _ in range(5):
            for idx in indices:
                sin[idx] += 1
                dirty[idx] = 1

        # Add lots of background traffic
        for bg_fid in range(995):
            bg_indices = analyzer.compute_hash_indices(2000 + bg_fid)
            for idx in bg_indices:
                sin[idx] += 1
                dirty[idx] = 1

        active = {flow_id} | {2000 + i for i in range(995)}
        result = analyzer.analyze_window(
            0, sin, self._make_sketch(), dirty, [0] * TOTAL_SIZE, active
        )
        # φ=0 means adaptive check is disabled, only absolute threshold
        assert flow_id in result.bursty_flows

    def test_false_positive_reduction(self):
        """Core scenario: many small flows above absolute threshold but
        below fraction threshold should NOT be flagged (the 99.8% fix)."""
        analyzer = WindowAnalyzer(self._make_config(phi=0.05))
        sin = self._make_sketch()
        dirty = [0] * TOTAL_SIZE

        # 100 flows each send 6 packets (total = 600)
        # Each flow: fraction = 6/600 = 1% < 5% → NOT bursty
        flow_ids = list(range(100))
        for fid in flow_ids:
            indices = analyzer.compute_hash_indices(fid)
            for _ in range(6):
                for idx in indices:
                    sin[idx] += 1
                    dirty[idx] = 1

        active = set(flow_ids)
        result = analyzer.analyze_window(
            0, sin, self._make_sketch(), dirty, [0] * TOTAL_SIZE, active
        )

        # With absolute-only threshold: ALL 100 flows would be bursty (6>=5)
        # With adaptive φ=0.05: fraction = 6/600 = 0.01 < 0.05 → none bursty
        assert len(result.bursty_flows) == 0

    def test_dominant_flow_detected(self):
        """A flow dominating the window should be detected."""
        analyzer = WindowAnalyzer(self._make_config(phi=0.05))
        sin = self._make_sketch()
        dirty = [0] * TOTAL_SIZE

        # Dominant flow sends 50 packets, 10 background flows send 5 each
        # Total = 50 + 50 = 100. Dominant fraction = 50/100 = 50% >> 5%
        dominant = 42
        indices_d = analyzer.compute_hash_indices(dominant)
        for _ in range(50):
            for idx in indices_d:
                sin[idx] += 1
                dirty[idx] = 1

        bg_flows = list(range(10))
        for fid in bg_flows:
            indices_bg = analyzer.compute_hash_indices(1000 + fid)
            for _ in range(5):
                for idx in indices_bg:
                    sin[idx] += 1
                    dirty[idx] = 1

        active = {dominant} | {1000 + i for i in range(10)}
        result = analyzer.analyze_window(
            0, sin, self._make_sketch(), dirty, [0] * TOTAL_SIZE, active
        )
        assert dominant in result.bursty_flows
        # Background flows: 5/100 = 5% = φ → borderline, depends on exact count
        # With CM overcount they might hit 5% or slightly above


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
