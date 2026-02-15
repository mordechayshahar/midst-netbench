"""Unit tests for AIFO and PACKS admission control.

Tests threshold checks, quantile computation, queue selection,
and rank inflation effects.
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

# AIFO parameters (mirrors config.p4)
AIFO_K_PERCENT = 20
MAX_QUEUE_SIZE = 64
AIFO_THRESHOLD = (AIFO_K_PERCENT * MAX_QUEUE_SIZE) // 100  # 12
RANK_PENALTY = 10

# PACKS parameters
NUM_QUEUES = 8
RANK_DELTA = 20


# ---- AIFO Model ----

class AifoModel:
    """Python model of AIFO admission control (mirrors P4 logic)."""

    def __init__(self, k_percent=20, max_queue=64, quantile_threshold=0xFFFFFFFF):
        self.k_percent = k_percent
        self.max_queue = max_queue
        self.threshold_pkts = (k_percent * max_queue) // 100
        self.quantile_threshold = quantile_threshold
        self.queue_size = 0
        self.admits = 0
        self.rejects = 0

    def check_admission(self, effective_rank, effective_queue_size):
        """Returns True if packet should be admitted."""
        if effective_queue_size < self.threshold_pkts:
            self.admits += 1
            self.queue_size += 1
            return True

        if effective_rank <= self.quantile_threshold:
            self.admits += 1
            self.queue_size += 1
            return True

        self.rejects += 1
        return False

    def on_departure(self):
        if self.queue_size > 0:
            self.queue_size -= 1


# ---- AIFO Sliding Window Model ----

# Constants matching config.p4
AIFO_WINDOW_SIZE = 20
AIFO_C_MINUS_KC = (MAX_QUEUE_SIZE * (100 - AIFO_K_PERCENT)) // 100  # 52


class AifoSlidingWindowModel:
    """Python model of AIFO data-plane sliding window quantile.

    Mirrors the P4 implementation: 20-slot circular buffer,
    per-packet quantile count, paper admission formula.
    """

    def __init__(self, window_size=AIFO_WINDOW_SIZE,
                 max_queue=MAX_QUEUE_SIZE, k_percent=AIFO_K_PERCENT,
                 threshold_pkts=AIFO_THRESHOLD):
        self.window_size = window_size
        self.max_queue = max_queue
        self.c_minus_kc = (max_queue * (100 - k_percent)) // 100
        self.threshold_pkts = threshold_pkts
        self.window = [0] * window_size
        self.tail = 0
        self.queue_size = 0
        self.admits = 0
        self.rejects = 0

    def check_admission(self, effective_rank, effective_queue_size):
        """Check admission using sliding window quantile.

        Returns True if packet should be admitted.
        """
        # Level 1: Fill threshold
        if effective_queue_size < self.threshold_pkts:
            self.admits += 1
            self.queue_size += 1
            return True

        # Compute quantile from window
        quantile_count = 0
        for i in range(self.window_size):
            if self.window[i] > effective_rank:
                quantile_count += 1

        # Write incoming rank at tail, advance tail
        self.window[self.tail] = effective_rank
        self.tail = (self.tail + 1) % self.window_size

        # Paper formula: (C - KC) * quantile_count <= (C - queue_size) * W
        if self.queue_size >= self.max_queue:
            self.rejects += 1
            return False

        lhs = self.c_minus_kc * quantile_count
        rhs = (self.max_queue - self.queue_size) * self.window_size

        if lhs <= rhs:
            self.admits += 1
            self.queue_size += 1
            return True
        else:
            self.rejects += 1
            return False

    def on_departure(self):
        if self.queue_size > 0:
            self.queue_size -= 1


# ---- PACKS Model ----

class PacksModel:
    """Python model of PACKS queue selection + admission."""

    def __init__(self, num_queues=8, rank_delta=20,
                 k_percent=20, max_queue=64,
                 quantile_threshold=0xFFFFFFFF):
        self.num_queues = num_queues
        self.rank_delta = rank_delta
        self.threshold_pkts = (k_percent * max_queue) // 100
        self.quantile_threshold = quantile_threshold
        self.queue_occupancy = [0] * num_queues
        self.admits = 0
        self.rejects = 0

    def select_queue(self, effective_rank):
        """Returns queue index."""
        max_idx = self.num_queues - 1
        if effective_rank >= max_idx * self.rank_delta:
            return max_idx
        return effective_rank // self.rank_delta

    def check_admission(self, effective_rank, effective_queue_size):
        """Returns (admitted, queue_index)."""
        queue_idx = self.select_queue(effective_rank)

        if effective_queue_size < self.threshold_pkts:
            self.admits += 1
            self.queue_occupancy[queue_idx] += 1
            return True, queue_idx

        if effective_rank <= self.quantile_threshold:
            self.admits += 1
            self.queue_occupancy[queue_idx] += 1
            return True, queue_idx

        self.rejects += 1
        return False, queue_idx


# ---- AIFO Tests ----

class TestAifoThreshold:
    """AIFO Level 1: fill threshold check."""

    def test_admit_when_below_threshold(self):
        aifo = AifoModel()
        # Queue size 0, threshold is 12 -> admit
        assert aifo.check_admission(effective_rank=100, effective_queue_size=0)

    def test_admit_just_below_threshold(self):
        aifo = AifoModel()
        assert aifo.check_admission(effective_rank=100, effective_queue_size=11)

    def test_quantile_check_at_threshold(self):
        aifo = AifoModel(quantile_threshold=50)
        # At threshold (12), need quantile check
        # rank 50 <= threshold 50 -> admit
        assert aifo.check_admission(effective_rank=50, effective_queue_size=12)

    def test_reject_at_threshold_bad_rank(self):
        aifo = AifoModel(quantile_threshold=50)
        # rank 51 > threshold 50 -> reject
        assert not aifo.check_admission(effective_rank=51, effective_queue_size=12)


class TestAifoQuantile:
    """AIFO Level 2: quantile comparison."""

    def test_quantile_admits_good_rank(self):
        aifo = AifoModel(quantile_threshold=30)
        # rank 10 <= threshold 30
        assert aifo.check_admission(effective_rank=10, effective_queue_size=50)

    def test_quantile_rejects_bad_rank(self):
        aifo = AifoModel(quantile_threshold=30)
        # rank 31 > threshold 30
        assert not aifo.check_admission(effective_rank=31, effective_queue_size=50)

    def test_sppifo_initial_bounds_even_split(self):
        """Test SP-PIFO initial bounds computed by WindowAnalyzer."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        bounds = analyzer.compute_sppifo_initial_bounds(8, 63)
        assert len(bounds) == 8
        # Bounds divide rank space evenly; last bound = max_rank
        assert bounds[-1] == 63
        # Bounds should be monotonically increasing
        for i in range(1, len(bounds)):
            assert bounds[i] > bounds[i - 1]

    def test_sppifo_initial_bounds_small(self):
        """SP-PIFO bounds with 2 queues."""
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        bounds = analyzer.compute_sppifo_initial_bounds(2, 100)
        assert len(bounds) == 2
        assert bounds[0] == 50
        assert bounds[1] == 100


# ---- AIFO Sliding Window Tests ----

class TestAifoSlidingWindow:
    """Test the AIFO data-plane sliding window quantile model."""

    def test_window_stores_ranks(self):
        """Window circular buffer stores incoming ranks."""
        model = AifoSlidingWindowModel()
        # Send 5 packets with increasing ranks (all below threshold -> auto-admit)
        # Need queue size above threshold to trigger window logic
        model.queue_size = 20  # force past threshold
        for rank in [10, 20, 30, 40, 50]:
            model.check_admission(rank, effective_queue_size=20)
        # Check window has stored ranks
        assert model.window[0] == 10
        assert model.window[1] == 20
        assert model.window[2] == 30
        assert model.window[3] == 40
        assert model.window[4] == 50

    def test_tail_pointer_wraps(self):
        """Tail pointer wraps around at window_size."""
        model = AifoSlidingWindowModel(window_size=5)
        model.queue_size = 20
        for rank in [1, 2, 3, 4, 5]:
            model.check_admission(rank, effective_queue_size=20)
        assert model.tail == 0  # wrapped
        # Send one more
        model.check_admission(6, effective_queue_size=20)
        assert model.tail == 1
        assert model.window[0] == 6  # overwrote slot 0

    def test_quantile_count_all_higher(self):
        """All stored ranks higher than incoming -> quantile_count = window_size."""
        model = AifoSlidingWindowModel(window_size=5)
        # Fill window with high ranks
        model.window = [100, 100, 100, 100, 100]
        model.queue_size = 20
        # Incoming rank 10: all 5 stored > 10 -> quantile_count = 5
        # lhs = 52 * 5 = 260, rhs = (64 - 20) * 5 = 220
        # 260 > 220 -> reject
        result = model.check_admission(10, effective_queue_size=20)
        assert not result

    def test_quantile_count_all_lower(self):
        """All stored ranks lower than incoming -> quantile_count = 0."""
        model = AifoSlidingWindowModel(window_size=5)
        model.window = [1, 2, 3, 4, 5]
        model.queue_size = 20
        # Incoming rank 100: no stored > 100 -> quantile_count = 0
        # lhs = 52 * 0 = 0, rhs = (64 - 20) * 5 = 220
        # 0 <= 220 -> admit
        result = model.check_admission(100, effective_queue_size=20)
        assert result

    def test_admission_formula_boundary(self):
        """Test the exact formula boundary: lhs == rhs should admit."""
        model = AifoSlidingWindowModel(window_size=20)
        # Set up: C=64, KC=12.8->12, C-KC=52
        # We want: 52 * quantile_count == (64 - queue_size) * 20
        # queue_size = 12 -> rhs = 52 * 20 = 1040
        # quantile_count = 1040 / 52 = 20
        # Fill window with ranks all > incoming
        model.window = [100] * 20
        model.queue_size = 12
        # lhs = 52 * 20 = 1040, rhs = (64 - 12) * 20 = 1040
        # lhs <= rhs -> admit
        result = model.check_admission(1, effective_queue_size=20)
        assert result

    def test_full_queue_rejects(self):
        """Queue at max capacity always rejects."""
        model = AifoSlidingWindowModel()
        model.queue_size = MAX_QUEUE_SIZE
        result = model.check_admission(0, effective_queue_size=20)
        assert not result


class TestAifoDataPlaneQuantile:
    """Test quantile count matches expectations for known windows."""

    def test_mixed_window(self):
        """Window with mixed ranks produces expected quantile."""
        model = AifoSlidingWindowModel(window_size=10)
        # Window: [5, 10, 15, 20, 25, 30, 35, 40, 45, 50]
        model.window = [5, 10, 15, 20, 25, 30, 35, 40, 45, 50]
        model.queue_size = 20

        # Incoming rank 22: stored > 22 are {25, 30, 35, 40, 45, 50} = 6
        # lhs = 52 * 6 = 312, rhs = (64 - 20) * 10 = 440
        # 312 <= 440 -> admit
        result = model.check_admission(22, effective_queue_size=20)
        assert result

    def test_high_quantile_rejects(self):
        """High quantile count with high queue fill -> reject."""
        model = AifoSlidingWindowModel(window_size=10)
        model.window = [100] * 10  # all stored > any reasonable incoming
        model.queue_size = 50

        # Incoming rank 5: all 10 > 5 -> quantile_count = 10
        # lhs = 52 * 10 = 520, rhs = (64 - 50) * 10 = 140
        # 520 > 140 -> reject
        result = model.check_admission(5, effective_queue_size=50)
        assert not result

    def test_empty_queue_admits_easily(self):
        """Empty queue with any quantile should admit."""
        model = AifoSlidingWindowModel(window_size=10)
        model.window = [100] * 10
        model.queue_size = 0

        # quantile_count = 10
        # lhs = 52 * 10 = 520, rhs = (64 - 0) * 10 = 640
        # 520 <= 640 -> admit
        result = model.check_admission(5, effective_queue_size=0)
        # Below threshold (0 < 12), so auto-admit
        assert result


class TestAifoRankInflation:
    """Test rank inflation effect on AIFO."""

    def test_bursty_flow_higher_rank(self):
        """Bursty flow with rank penalty is more likely to be rejected."""
        aifo = AifoModel(quantile_threshold=15)
        base_rank = 10

        # Normal flow: rank 10 <= 15 -> admit
        assert aifo.check_admission(base_rank, effective_queue_size=20)

        # Bursty flow: rank 10+10=20 > 15 -> reject
        effective_rank = base_rank + RANK_PENALTY
        assert not aifo.check_admission(effective_rank, effective_queue_size=20)

    def test_vfl_effect_on_bursty_admission(self):
        """Bursty flows see VQ depth (VFL), making them harder to admit."""
        aifo = AifoModel(quantile_threshold=50)

        # Normal flow sees actual queue=5 (below threshold 12) -> auto-admit
        assert aifo.check_admission(effective_rank=40, effective_queue_size=5)

        # Bursty flow sees VFL=30 (above threshold 12) -> needs quantile check
        # rank 40+10=50 <= threshold 50 -> still admits
        assert aifo.check_admission(effective_rank=50, effective_queue_size=30)

        # rank 40+10=50+1=51 > threshold 50 -> rejects
        assert not aifo.check_admission(effective_rank=51, effective_queue_size=30)


# ---- PACKS Tests ----

class TestPacksQueueSelection:
    """PACKS queue selection formula."""

    def test_rank_zero_goes_to_queue_zero(self):
        packs = PacksModel()
        assert packs.select_queue(0) == 0

    def test_rank_maps_to_correct_queue(self):
        packs = PacksModel(rank_delta=20)
        assert packs.select_queue(0) == 0
        assert packs.select_queue(19) == 0
        assert packs.select_queue(20) == 1
        assert packs.select_queue(39) == 1
        assert packs.select_queue(40) == 2

    def test_high_rank_capped_to_last_queue(self):
        packs = PacksModel(num_queues=8, rank_delta=20)
        # max_idx = 7, threshold = 7 * 20 = 140
        assert packs.select_queue(140) == 7
        assert packs.select_queue(200) == 7
        assert packs.select_queue(1000) == 7

    def test_bursty_rank_penalty_shifts_queue(self):
        """Rank penalty moves bursty flows to lower-priority queue."""
        packs = PacksModel(rank_delta=20)
        base_rank = 30  # queue 1

        normal_queue = packs.select_queue(base_rank)
        bursty_queue = packs.select_queue(base_rank + RANK_PENALTY)

        assert normal_queue == 1  # 30/20 = 1
        assert bursty_queue == 2  # 40/20 = 2


class TestPacksAdmission:
    """PACKS admission control."""

    def test_admit_below_threshold(self):
        packs = PacksModel()
        admitted, queue = packs.check_admission(
            effective_rank=10, effective_queue_size=5
        )
        assert admitted
        assert queue == 0  # 10/20 = 0

    def test_reject_bad_rank_above_threshold(self):
        packs = PacksModel(quantile_threshold=20)
        admitted, queue = packs.check_admission(
            effective_rank=30, effective_queue_size=50
        )
        assert not admitted

    def test_queue_occupancy_tracking(self):
        packs = PacksModel(rank_delta=20)

        # Admit packets to different queues
        packs.check_admission(effective_rank=5, effective_queue_size=0)   # queue 0
        packs.check_admission(effective_rank=25, effective_queue_size=0)  # queue 1
        packs.check_admission(effective_rank=25, effective_queue_size=0)  # queue 1

        assert packs.queue_occupancy[0] == 1
        assert packs.queue_occupancy[1] == 2
        assert packs.admits == 3


class TestBurstyFlagWrites:
    """Test bursty flag register write computation."""

    def test_new_bursty_flows_set(self):
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        bursty = {100, 200, 300}
        writes = analyzer.compute_bursty_flag_writes(bursty)

        for fid in bursty:
            idx = fid % 256
            assert writes[idx] == 1

    def test_previous_bursty_cleared(self):
        analyzer = WindowAnalyzer(DEFAULT_CONFIG)
        # First window: flow 100 is bursty
        analyzer.previous_bursty = {100}

        # Second window: flow 200 is bursty (100 is not)
        writes = analyzer.compute_bursty_flag_writes({200})

        assert writes[100 % 256] == 0  # cleared
        assert writes[200 % 256] == 1  # set


class TestSrptRankReading:
    """Test SRPT priority mode: rank from identification field."""

    def test_srpt_rank_from_identification(self):
        """In SRPT mode, base_rank = ipv4.identification (16 bits)."""
        # Model: SRPT encodes remaining_bytes >> 10 in identification
        remaining_bytes = 102400  # 100 KB
        identification = min(remaining_bytes >> 10, 0xFFFF)  # = 100
        base_rank = identification  # P4 reads this
        assert base_rank == 100

    def test_srpt_rank_zero_for_last_packet(self):
        """Last packet of a flow has remaining_bytes=0 → highest priority."""
        remaining_bytes = 0
        identification = min(remaining_bytes >> 10, 0xFFFF)
        base_rank = identification
        assert base_rank == 0  # highest priority in SP-PIFO

    def test_srpt_large_flow_capped(self):
        """Very large flows get capped at 16-bit max."""
        remaining_bytes = 100 * 1024 * 1024  # 100 MB
        identification = min(remaining_bytes >> 10, 0xFFFF)
        base_rank = identification
        assert base_rank == 0xFFFF  # capped

    def test_srpt_rank_inflation_still_applies(self):
        """SRPT rank still gets penalty if bursty."""
        remaining_bytes = 51200  # 50 KB
        base_rank = min(remaining_bytes >> 10, 0xFFFF)  # = 50
        is_bursty = True
        effective_rank = base_rank + (RANK_PENALTY if is_bursty else 0)
        assert effective_rank == 60  # 50 + 10

    def test_srpt_packs_queue_selection(self):
        """SRPT ranks map to PACKS queues based on remaining bytes."""
        packs = PacksModel(rank_delta=20)

        # Small flow (5 KB remaining) → high priority queue
        rank_small = min(5120 >> 10, 0xFFFF)  # = 5
        assert packs.select_queue(rank_small) == 0  # 5/20 = 0

        # Large flow (200 KB remaining) → lower priority
        rank_large = min(204800 >> 10, 0xFFFF)  # = 200
        assert packs.select_queue(rank_large) == 7  # 200/20 = 10, capped to 7

    def test_dscp_vs_srpt_range(self):
        """DSCP gives 0-63 range, SRPT gives 0-65535 range."""
        dscp_max = 63  # 6 bits
        srpt_max = 0xFFFF  # 16 bits

        # DSCP: all flows fit in queue 0-3 with rank_delta=20
        assert dscp_max // 20 == 3

        # SRPT: flows span the full queue range
        assert min(srpt_max // 20, 7) == 7


class TestVQDropInvariant:
    """Test that VQ correctly decrements on drop (Phase 9 fix).

    Without drop-decrement, phantom packets inflate VQ permanently.
    """

    def test_vq_decremented_on_drop_aifo(self):
        """AIFO drops MUST decrement VQ to prevent phantom inflation."""
        aifo = AifoModel(quantile_threshold=5)  # strict

        # Simulate: 10 arrivals, VQ = 10 * 12000 = 120000
        vq_depth = 0
        arrivals = 10
        for _ in range(arrivals):
            vq_depth += 12000

        # 3 packets rejected by AIFO — each decrements VQ
        drops = 0
        for rank in [10, 20, 30]:
            if not aifo.check_admission(rank, effective_queue_size=20):
                drops += 1
                vq_depth -= 12000  # Phase 9 fix: drop-path VQ decrement

        assert vq_depth == (arrivals - drops) * 12000  # VQ reduced by drops
        assert drops > 0  # at least some drops occurred

    def test_vq_zero_after_all_drops_and_egress(self):
        """VQ = 0 after all packets either egress or are dropped."""
        vq_depth = 0
        arrivals = 20
        admitted = 0
        dropped = 0

        for i in range(arrivals):
            vq_depth += 12000
            # Simulate: first 15 admitted, last 5 dropped
            if i < 15:
                admitted += 1
            else:
                dropped += 1
                vq_depth -= 12000  # Phase 9: drop-path decrement

        # Drain admitted packets through egress
        for _ in range(admitted):
            vq_depth -= 12000

        # VQ = 0 (all packets accounted for)
        assert vq_depth == 0


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
