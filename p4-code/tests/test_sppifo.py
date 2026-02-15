"""Unit tests for SP-PIFO scheduling.

Tests queue selection with dynamic bounds, blocking reaction,
cost computation, bound adaptation, and clamping.

Python model mirrors the P4 implementation in admission_sppifo.p4.
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

RANK_PENALTY = 10


# ---- SP-PIFO Model ----

class SpPifoModel:
    """Python model of SP-PIFO scheduling (mirrors P4 logic).

    8 queue bounds with cascading selection and blocking reaction.
    """

    def __init__(self, num_queues=8, initial_bounds=None):
        self.num_queues = num_queues
        if initial_bounds:
            self.bounds = list(initial_bounds)
        else:
            # Default: evenly spaced bounds for rank 0-63
            step = 63 // num_queues
            self.bounds = [(i + 1) * step for i in range(num_queues)]
            self.bounds[-1] = 63

    def schedule(self, rank):
        """Select queue and apply blocking reaction.

        Returns (selected_queue, blocked, cost).
        """
        # Cascading check
        selected_queue = self.num_queues - 1
        for i in range(self.num_queues - 1):
            if rank <= self.bounds[i]:
                selected_queue = i
                break

        blocked = selected_queue > 0
        cost = 0

        if blocked:
            # Cost = rank - bound of the queue just below
            cost = rank - self.bounds[selected_queue - 1]

            # Subtract cost from all bounds (clamp to 0)
            for i in range(self.num_queues):
                self.bounds[i] = max(0, self.bounds[i] - cost)

        return selected_queue, blocked, cost


# ---- Queue Selection Tests ----

class TestSpPifoQueueSelection:
    """Test cascading queue selection."""

    def test_rank_in_first_queue(self):
        model = SpPifoModel(initial_bounds=[10, 20, 30, 40, 50, 60, 70, 80])
        queue, blocked, cost = model.schedule(5)
        assert queue == 0
        assert not blocked
        assert cost == 0

    def test_rank_at_bound_boundary(self):
        model = SpPifoModel(initial_bounds=[10, 20, 30, 40, 50, 60, 70, 80])
        queue, blocked, cost = model.schedule(10)
        assert queue == 0
        assert not blocked

    def test_rank_just_above_first_bound(self):
        model = SpPifoModel(initial_bounds=[10, 20, 30, 40, 50, 60, 70, 80])
        queue, blocked, cost = model.schedule(11)
        assert queue == 1
        assert blocked

    def test_rank_in_middle_queue(self):
        model = SpPifoModel(initial_bounds=[10, 20, 30, 40, 50, 60, 70, 80])
        queue, blocked, cost = model.schedule(35)
        assert queue == 3

    def test_rank_in_last_queue(self):
        model = SpPifoModel(initial_bounds=[10, 20, 30, 40, 50, 60, 70, 80])
        queue, blocked, cost = model.schedule(100)
        assert queue == 7

    def test_rank_zero_goes_to_first_queue(self):
        model = SpPifoModel(initial_bounds=[10, 20, 30, 40, 50, 60, 70, 80])
        queue, blocked, cost = model.schedule(0)
        assert queue == 0
        assert not blocked


# ---- Blocking Reaction Tests ----

class TestSpPifoBlockingReaction:
    """Test cost computation and bound adjustment."""

    def test_blocked_cost_computed_correctly(self):
        model = SpPifoModel(initial_bounds=[10, 20, 30, 40, 50, 60, 70, 80])
        # rank 15 > bound[0]=10, so goes to queue 1
        queue, blocked, cost = model.schedule(15)
        assert queue == 1
        assert blocked
        assert cost == 15 - 10  # rank - bound[0] = 5

    def test_bounds_reduced_by_cost(self):
        bounds = [10, 20, 30, 40, 50, 60, 70, 80]
        model = SpPifoModel(initial_bounds=bounds)
        _, _, cost = model.schedule(15)
        assert cost == 5
        # All bounds should be reduced by 5
        for i in range(8):
            assert model.bounds[i] == bounds[i] - 5

    def test_bounds_clamped_to_zero(self):
        model = SpPifoModel(initial_bounds=[3, 20, 30, 40, 50, 60, 70, 80])
        # rank 25 > bound[0]=3, goes to queue 1. cost = 25 - 3 = 22
        # But we need rank <= bound[1]=20 to be false for queue 2
        # rank 25 > 20, so goes to queue 2. cost = 25 - 20 = 5
        queue, _, cost = model.schedule(25)
        assert queue == 2
        assert cost == 25 - 20  # = 5
        # bound[0] was 3, 3 - 5 -> clamped to 0
        assert model.bounds[0] == 0

    def test_no_blocking_for_queue_zero(self):
        model = SpPifoModel(initial_bounds=[100, 200, 300, 400, 500, 600, 700, 800])
        original_bounds = list(model.bounds)
        queue, blocked, cost = model.schedule(50)
        assert queue == 0
        assert not blocked
        assert cost == 0
        # Bounds unchanged
        assert model.bounds == original_bounds


# ---- Multi-Packet Adaptation Tests ----

class TestSpPifoAdaptation:
    """Test how bounds adapt over multiple packets."""

    def test_bounds_decrease_with_blocked_traffic(self):
        model = SpPifoModel(initial_bounds=[10, 20, 30, 40, 50, 60, 70, 80])
        original_b0 = model.bounds[0]

        # Send packets with increasing ranks that cause blocking
        for rank in [15, 25, 35]:
            model.schedule(rank)

        # Bounds should have decreased
        assert model.bounds[0] < original_b0

    def test_bounds_converge_to_zero_under_high_ranks(self):
        model = SpPifoModel(initial_bounds=[10, 20, 30, 40, 50, 60, 70, 80])

        # Send many high-rank packets
        for _ in range(100):
            model.schedule(100)

        # All bounds should be at 0
        for b in model.bounds:
            assert b == 0

    def test_queue_zero_packets_dont_change_bounds(self):
        model = SpPifoModel(initial_bounds=[50, 100, 150, 200, 250, 300, 350, 400])
        original_bounds = list(model.bounds)

        # Send many low-rank packets (all go to queue 0)
        for rank in range(50):
            model.schedule(rank)

        # Bounds unchanged
        assert model.bounds == original_bounds


# ---- Integration with Rank Inflation ----

class TestSpPifoRankInflation:
    """Test SP-PIFO with rank inflation for bursty flows."""

    def test_bursty_flow_goes_to_higher_queue(self):
        model = SpPifoModel(initial_bounds=[10, 20, 30, 40, 50, 60, 70, 80])

        # Normal flow: rank 5 -> queue 0
        q_normal, _, _ = model.schedule(5)
        assert q_normal == 0

        # Reset bounds for fair comparison
        model2 = SpPifoModel(initial_bounds=[10, 20, 30, 40, 50, 60, 70, 80])
        # Bursty flow: rank 5 + penalty 10 = 15 -> queue 1
        q_bursty, _, _ = model2.schedule(5 + RANK_PENALTY)
        assert q_bursty == 1

    def test_penalty_causes_blocking_reaction(self):
        model = SpPifoModel(initial_bounds=[10, 20, 30, 40, 50, 60, 70, 80])

        # Without penalty: rank 10 -> queue 0, no blocking
        q, blocked, _ = model.schedule(10)
        assert q == 0
        assert not blocked

        model2 = SpPifoModel(initial_bounds=[10, 20, 30, 40, 50, 60, 70, 80])
        # With penalty: rank 10+10=20 -> queue 1, blocking
        q, blocked, cost = model2.schedule(10 + RANK_PENALTY)
        assert q == 1
        assert blocked
        assert cost == 20 - 10  # = 10


# ---- SP-PIFO Initial Bounds Tests ----

class TestSpPifoInitialBounds:
    """Test controller-computed initial bounds."""

    def test_even_split_8_queues(self):
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        bounds = analyzer.compute_sppifo_initial_bounds(8, 63)
        assert len(bounds) == 8
        assert bounds[-1] == 63
        for i in range(1, len(bounds)):
            assert bounds[i] > bounds[i - 1]

    def test_even_split_2_queues(self):
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        bounds = analyzer.compute_sppifo_initial_bounds(2, 100)
        assert bounds == [50, 100]

    def test_even_split_4_queues(self):
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        bounds = analyzer.compute_sppifo_initial_bounds(4, 100)
        assert len(bounds) == 4
        assert bounds[-1] == 100

    def test_single_queue(self):
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        bounds = analyzer.compute_sppifo_initial_bounds(1, 63)
        assert bounds == [63]


# ---- Edge Cases ----

class TestSpPifoEdgeCases:
    """Edge cases in SP-PIFO scheduling."""

    def test_all_bounds_zero(self):
        """All bounds at 0: everything goes to last queue."""
        model = SpPifoModel(initial_bounds=[0, 0, 0, 0, 0, 0, 0, 0])
        queue, _, _ = model.schedule(1)
        assert queue == 7

    def test_rank_zero_all_bounds_zero(self):
        """Rank 0 with all bounds at 0: goes to queue 0."""
        model = SpPifoModel(initial_bounds=[0, 0, 0, 0, 0, 0, 0, 0])
        queue, blocked, _ = model.schedule(0)
        assert queue == 0
        assert not blocked

    def test_very_large_rank(self):
        model = SpPifoModel(initial_bounds=[10, 20, 30, 40, 50, 60, 70, 80])
        queue, blocked, cost = model.schedule(0xFFFF)
        assert queue == 7
        assert blocked
        assert cost == 0xFFFF - 70  # rank - bound[6]

    def test_bounds_never_negative(self):
        """After any sequence of packets, bounds are always >= 0."""
        model = SpPifoModel(initial_bounds=[5, 10, 15, 20, 25, 30, 35, 40])
        for rank in [100, 200, 300, 50, 60, 70]:
            model.schedule(rank)
        for b in model.bounds:
            assert b >= 0


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
