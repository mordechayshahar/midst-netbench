"""Unit tests for Virtual Queue logic.

Tests VQ increment/decrement, clamping, watermark detection, and peak tracking.
Since P4 registers run on the switch, these tests validate the control-plane
model and expected behavior of the data-plane registers.
"""

import pytest
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "controller"))


# ---- VQ Model (mirrors P4 register logic) ----

class VirtualQueueModel:
    """Python model of the P4 virtual queue for testing."""

    def __init__(self, high_wm_bits=60000, low_wm_bits=24000):
        self.depth_bits = 0
        self.peak_bits = 0
        self.hwm_crossings = 0
        self.lwm_crossings = 0
        self.high_wm = high_wm_bits
        self.low_wm = low_wm_bits

    def on_arrival(self, packet_size_bits):
        """Returns (crossed_high_wm,)"""
        was_below = self.depth_bits < self.high_wm
        self.depth_bits += packet_size_bits

        crossed_high = False
        if was_below and self.depth_bits >= self.high_wm:
            crossed_high = True
            self.hwm_crossings += 1

        if self.depth_bits > self.peak_bits:
            self.peak_bits = self.depth_bits

        return crossed_high

    def on_departure(self, packet_size_bits):
        """Returns (crossed_low_wm,)"""
        was_above = self.depth_bits > self.low_wm

        if self.depth_bits > packet_size_bits:
            self.depth_bits -= packet_size_bits
        else:
            self.depth_bits = 0  # clamp

        crossed_low = False
        if was_above and self.depth_bits <= self.low_wm:
            crossed_low = True
            self.lwm_crossings += 1

        return crossed_low


# ---- Tests ----

class TestVirtualQueueBasic:
    """Basic VQ increment/decrement tests."""

    def test_initial_state(self):
        vq = VirtualQueueModel()
        assert vq.depth_bits == 0
        assert vq.peak_bits == 0
        assert vq.hwm_crossings == 0

    def test_single_arrival(self):
        vq = VirtualQueueModel()
        vq.on_arrival(12000)  # 1 packet = 1500 bytes * 8 bits
        assert vq.depth_bits == 12000

    def test_multiple_arrivals(self):
        vq = VirtualQueueModel()
        for _ in range(5):
            vq.on_arrival(12000)
        assert vq.depth_bits == 60000

    def test_arrival_departure_balance(self):
        vq = VirtualQueueModel()
        for _ in range(10):
            vq.on_arrival(12000)
        for _ in range(10):
            vq.on_departure(12000)
        assert vq.depth_bits == 0


class TestVirtualQueueClamping:
    """VQ underflow clamping tests."""

    def test_departure_below_zero_clamps(self):
        vq = VirtualQueueModel()
        vq.on_arrival(12000)
        vq.on_departure(24000)  # more than current depth
        assert vq.depth_bits == 0

    def test_empty_departure_clamps(self):
        vq = VirtualQueueModel()
        vq.on_departure(12000)
        assert vq.depth_bits == 0

    def test_multiple_departures_clamp(self):
        vq = VirtualQueueModel()
        vq.on_arrival(12000)
        vq.on_departure(6000)
        vq.on_departure(6000)
        assert vq.depth_bits == 0
        vq.on_departure(6000)  # extra departure
        assert vq.depth_bits == 0


class TestVirtualQueueWatermarks:
    """High/low watermark crossing detection."""

    def test_high_watermark_crossing(self):
        vq = VirtualQueueModel(high_wm_bits=60000)
        # Send 4 packets (48000 < 60000), no crossing
        for _ in range(4):
            crossed = vq.on_arrival(12000)
            assert not crossed

        # 5th packet crosses (60000 >= 60000)
        crossed = vq.on_arrival(12000)
        assert crossed
        assert vq.hwm_crossings == 1

    def test_high_watermark_no_double_crossing(self):
        vq = VirtualQueueModel(high_wm_bits=60000)
        for _ in range(5):
            vq.on_arrival(12000)
        # Already above, 6th packet should NOT trigger crossing
        crossed = vq.on_arrival(12000)
        assert not crossed
        assert vq.hwm_crossings == 1

    def test_low_watermark_crossing(self):
        vq = VirtualQueueModel(low_wm_bits=24000)
        # Fill to 60000
        for _ in range(5):
            vq.on_arrival(12000)

        # Depart 3 packets: 60000 -> 24000 (crosses low WM)
        for i in range(3):
            crossed = vq.on_departure(12000)
            if i < 2:
                assert not crossed  # 48000, 36000 still above
            else:
                assert crossed  # 24000 <= 24000

        assert vq.lwm_crossings == 1

    def test_low_watermark_no_crossing_when_below(self):
        vq = VirtualQueueModel(low_wm_bits=24000)
        # Start below low WM
        vq.on_arrival(12000)  # 12000 < 24000
        crossed = vq.on_departure(12000)
        assert not crossed  # was already below

    def test_multiple_window_cycles(self):
        vq = VirtualQueueModel(high_wm_bits=60000, low_wm_bits=24000)

        # Cycle 1: cross high
        for _ in range(5):
            vq.on_arrival(12000)
        assert vq.hwm_crossings == 1

        # Drain below low
        for _ in range(4):
            vq.on_departure(12000)
        assert vq.lwm_crossings == 1

        # Cycle 2: cross high again
        for _ in range(5):
            vq.on_arrival(12000)
        assert vq.hwm_crossings == 2


class TestVirtualQueuePeak:
    """Peak depth tracking."""

    def test_peak_tracks_maximum(self):
        vq = VirtualQueueModel()
        for _ in range(10):
            vq.on_arrival(12000)
        assert vq.peak_bits == 120000

        # Departures don't affect peak
        for _ in range(5):
            vq.on_departure(12000)
        assert vq.peak_bits == 120000

    def test_peak_updates_on_new_high(self):
        vq = VirtualQueueModel()
        for _ in range(5):
            vq.on_arrival(12000)
        assert vq.peak_bits == 60000

        for _ in range(3):
            vq.on_departure(12000)
        for _ in range(5):
            vq.on_arrival(12000)
        assert vq.peak_bits == 84000  # 60000 - 36000 + 60000


class TestVirtualQueueDropPath:
    """Test that dropped packets DO decrement VQ (Phase 9 fix).

    Without drop-path decrement, phantom packets inflate VQ permanently
    and monitoring windows get stuck open. Drop = VQ departure without
    Sout update.
    """

    def test_drop_calls_on_departure(self):
        vq = VirtualQueueModel()
        for _ in range(5):
            vq.on_arrival(12000)
        assert vq.depth_bits == 60000

        # Drop path calls on_departure — VQ decremented
        vq.on_departure(12000)  # simulates drop-path VQ decrement
        assert vq.depth_bits == 48000

    def test_vq_returns_to_zero_with_drops_and_egress(self):
        vq = VirtualQueueModel(high_wm_bits=60000, low_wm_bits=24000)
        for _ in range(5):
            vq.on_arrival(12000)  # 60000

        # 2 egress departures
        vq.on_departure(12000)  # 48000
        vq.on_departure(12000)  # 36000
        assert vq.depth_bits == 36000

        # 3 drop-path departures (same on_departure call)
        vq.on_departure(12000)  # 24000 — crosses LWM
        vq.on_departure(12000)  # 12000
        vq.on_departure(12000)  # 0
        assert vq.depth_bits == 0  # all accounted for


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
