package ch.ethz.systems.netbench.xpt.ports.midst_feedback.core;

import ch.ethz.systems.netbench.core.Simulator;
import ch.ethz.systems.netbench.core.config.NBProperties;
import ch.ethz.systems.netbench.core.log.SimulationLogger;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for VirtualQueue - the universal congestion trigger for MIDST.
 *
 * These tests verify that the virtual queue correctly:
 * 1. Tracks arrivals and departures
 * 2. Detects high watermark crossings (congestion start)
 * 3. Detects low watermark crossings (congestion end)
 * 4. Handles edge cases gracefully
 */
class VirtualQueueTest {

    private static final long PACKET_SIZE_BITS = 1500L * 8L; // 12000 bits
    private static final long HIGH_WATERMARK_BITS = 5 * PACKET_SIZE_BITS; // 5 packets
    private static final long LOW_WATERMARK_BITS = 2 * PACKET_SIZE_BITS;  // 2 packets

    private static NBProperties mockConfig;
    private VirtualQueue virtualQueue;

    @BeforeAll
    static void setupLogger() {
        mockConfig = Mockito.mock(NBProperties.class);
        when(mockConfig.getPropertyWithDefault(eq("run_folder_name"), any())).thenReturn("test_virtual_queue");
        when(mockConfig.getPropertyWithDefault(eq("run_folder_base_dir"), any())).thenReturn("temp");
        when(mockConfig.getBooleanPropertyWithDefault(anyString(), eq(false))).thenReturn(false);
        when(mockConfig.getAllPropertiesToString()).thenReturn("# Mock Config\n");

        try {
            SimulationLogger.open(mockConfig);
        } catch (Exception e) {
            fail("SimulationLogger initialization failed: " + e.getMessage());
        }
    }

    @AfterAll
    static void tearDownLogger() {
        try {
            SimulationLogger.closeAndThrowaway();
        } catch (Exception e) {
            // Ignore
        }
    }

    @BeforeEach
    void setUp() {
        Simulator.setup(0, mockConfig);
        virtualQueue = new VirtualQueue(HIGH_WATERMARK_BITS, LOW_WATERMARK_BITS,
                                         "test->port", false);
    }

    @AfterEach
    void tearDown() {
        Simulator.reset();
    }

    // =========================================================================
    // BASIC OPERATIONS
    // =========================================================================

    @Test
    @DisplayName("Virtual queue starts at zero")
    void initialState_shouldBeZero() {
        assertEquals(0, virtualQueue.getDepthBits());
        assertEquals(0, virtualQueue.getDepthPackets());
        assertFalse(virtualQueue.isAboveHighWatermark());
        assertTrue(virtualQueue.isBelowLowWatermark());
    }

    @Test
    @DisplayName("Arrival increases virtual queue depth")
    void onArrival_shouldIncreaseDepth() {
        virtualQueue.onArrival(PACKET_SIZE_BITS);

        assertEquals(PACKET_SIZE_BITS, virtualQueue.getDepthBits());
        assertEquals(1, virtualQueue.getDepthPackets());
    }

    @Test
    @DisplayName("Departure decreases virtual queue depth")
    void onDeparture_shouldDecreaseDepth() {
        // First add some packets
        virtualQueue.onArrival(PACKET_SIZE_BITS);
        virtualQueue.onArrival(PACKET_SIZE_BITS);
        assertEquals(2, virtualQueue.getDepthPackets());

        // Now depart one
        virtualQueue.onDeparture(PACKET_SIZE_BITS);

        assertEquals(1, virtualQueue.getDepthPackets());
    }

    @Test
    @DisplayName("Depth never goes negative")
    void onDeparture_shouldNotGoNegative() {
        // Depart without any arrivals
        virtualQueue.onDeparture(PACKET_SIZE_BITS);

        assertEquals(0, virtualQueue.getDepthBits());
    }

    // =========================================================================
    // WATERMARK CROSSINGS
    // =========================================================================

    @Test
    @DisplayName("Crossing high watermark returns true")
    void onArrival_crossingHighWatermark_returnsTrue() {
        // Add packets up to just below high watermark
        for (int i = 0; i < 4; i++) {
            boolean crossed = virtualQueue.onArrival(PACKET_SIZE_BITS);
            assertFalse(crossed, "Should not cross high watermark yet");
        }

        // This arrival should cross the high watermark (5 packets)
        boolean crossed = virtualQueue.onArrival(PACKET_SIZE_BITS);

        assertTrue(crossed, "Should cross high watermark");
        assertTrue(virtualQueue.isAboveHighWatermark());
        assertEquals(1, virtualQueue.getHighWatermarkCrossings());
    }

    @Test
    @DisplayName("Staying above high watermark does not re-trigger")
    void onArrival_stayingAboveHighWatermark_returnsFalse() {
        // Cross the high watermark
        for (int i = 0; i < 5; i++) {
            virtualQueue.onArrival(PACKET_SIZE_BITS);
        }
        assertEquals(1, virtualQueue.getHighWatermarkCrossings());

        // Add more packets - should not trigger again
        boolean crossed = virtualQueue.onArrival(PACKET_SIZE_BITS);

        assertFalse(crossed, "Should not re-trigger while above watermark");
        assertEquals(1, virtualQueue.getHighWatermarkCrossings());
    }

    @Test
    @DisplayName("Crossing low watermark returns true")
    void onDeparture_crossingLowWatermark_returnsTrue() {
        // First fill up above high watermark
        for (int i = 0; i < 6; i++) {
            virtualQueue.onArrival(PACKET_SIZE_BITS);
        }
        assertTrue(virtualQueue.isAboveHighWatermark());

        // Depart packets until we cross low watermark
        for (int i = 0; i < 3; i++) {
            boolean crossed = virtualQueue.onDeparture(PACKET_SIZE_BITS);
            assertFalse(crossed, "Should not cross low watermark yet");
        }

        // This departure should cross below low watermark (2 packets)
        boolean crossed = virtualQueue.onDeparture(PACKET_SIZE_BITS);

        assertTrue(crossed, "Should cross low watermark");
        assertTrue(virtualQueue.isBelowLowWatermark());
        assertEquals(1, virtualQueue.getLowWatermarkCrossings());
    }

    // =========================================================================
    // FULL CONGESTION CYCLE
    // =========================================================================

    @Test
    @DisplayName("Full congestion cycle: cross high, then low")
    void fullCongestionCycle_shouldTrackCrossings() {
        // Phase 1: Build up (cross high watermark)
        for (int i = 0; i < 5; i++) {
            virtualQueue.onArrival(PACKET_SIZE_BITS);
        }
        assertEquals(1, virtualQueue.getHighWatermarkCrossings());
        assertTrue(virtualQueue.isAboveHighWatermark());

        // Phase 2: Drain (cross low watermark)
        for (int i = 0; i < 4; i++) {
            virtualQueue.onDeparture(PACKET_SIZE_BITS);
        }
        assertEquals(1, virtualQueue.getLowWatermarkCrossings());
        assertTrue(virtualQueue.isBelowLowWatermark());
    }

    @Test
    @DisplayName("Multiple congestion cycles")
    void multipleCongestionCycles_shouldTrackAllCrossings() {
        // Cycle 1
        for (int i = 0; i < 5; i++) virtualQueue.onArrival(PACKET_SIZE_BITS);
        for (int i = 0; i < 5; i++) virtualQueue.onDeparture(PACKET_SIZE_BITS);

        // Cycle 2
        for (int i = 0; i < 5; i++) virtualQueue.onArrival(PACKET_SIZE_BITS);
        for (int i = 0; i < 5; i++) virtualQueue.onDeparture(PACKET_SIZE_BITS);

        assertEquals(2, virtualQueue.getHighWatermarkCrossings());
        assertEquals(2, virtualQueue.getLowWatermarkCrossings());
    }

    // =========================================================================
    // STATISTICS
    // =========================================================================

    @Test
    @DisplayName("Peak depth is tracked correctly")
    void peakDepth_shouldBeTracked() {
        // Build up to 6 packets
        for (int i = 0; i < 6; i++) {
            virtualQueue.onArrival(PACKET_SIZE_BITS);
        }
        long peakAfterBuildUp = virtualQueue.getPeakDepthBits();

        // Drain to 2 packets
        for (int i = 0; i < 4; i++) {
            virtualQueue.onDeparture(PACKET_SIZE_BITS);
        }

        // Peak should still be 6 packets
        assertEquals(peakAfterBuildUp, virtualQueue.getPeakDepthBits());
        assertEquals(6 * PACKET_SIZE_BITS, virtualQueue.getPeakDepthBits());
    }

    @Test
    @DisplayName("Total bytes are tracked")
    void totalBytes_shouldBeTracked() {
        virtualQueue.onArrival(PACKET_SIZE_BITS);
        virtualQueue.onArrival(PACKET_SIZE_BITS);
        virtualQueue.onDeparture(PACKET_SIZE_BITS);

        assertEquals(2 * 1500, virtualQueue.getTotalArrivedBytes());
        assertEquals(1500, virtualQueue.getTotalDepartedBytes());
        assertEquals(1500, virtualQueue.getInFlightBytes());
    }

    // =========================================================================
    // EDGE CASES
    // =========================================================================

    @Test
    @DisplayName("Constructor rejects invalid watermarks")
    void constructor_withInvalidWatermarks_shouldThrow() {
        // Low >= High
        assertThrows(IllegalArgumentException.class, () -> {
            new VirtualQueue(100, 100, "test", false);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new VirtualQueue(100, 200, "test", false);
        });

        // Negative low watermark
        assertThrows(IllegalArgumentException.class, () -> {
            new VirtualQueue(100, -10, "test", false);
        });
    }

    @Test
    @DisplayName("Factory method creates valid queue")
    void fromPacketCounts_shouldCreateValidQueue() {
        VirtualQueue vq = VirtualQueue.fromPacketCounts(50, 10, "factory-test", false);

        assertEquals(50 * PACKET_SIZE_BITS, vq.getHighWatermarkBits());
        assertEquals(10 * PACKET_SIZE_BITS, vq.getLowWatermarkBits());
    }

    @Test
    @DisplayName("toString provides useful info")
    void toString_shouldProvideUsefulInfo() {
        String str = virtualQueue.toString();

        assertTrue(str.contains("VirtualQueue"));
        assertTrue(str.contains("test->port"));
    }

    // =========================================================================
    // PACKS DROP BEHAVIOR: THE CRITICAL H2 TEST
    //
    // PACKS uses aggressive admission control, dropping ~80% of packets.
    // Every arriving packet calls onArrival() (VQ incremented).
    // Dropped packets may or may not call onDeparture() — THIS IS THE QUESTION.
    // Only transmitted packets call onDeparture() via the normal path.
    //
    // This test simulates realistic PACKS traffic: periodic bursts with
    // high arrival rate, limited link-rate departures, and many drops.
    // It runs both strategies and asserts which one produces proper cycling.
    // =========================================================================

    /**
     * Simulates a PACKS-like traffic pattern on a VirtualQueue, returning
     * the number of high watermark crossings (monitoring windows started)
     * and low watermark crossings (monitoring windows completed).
     *
     * @param decrementOnDrop if true, call onDeparture for each dropped packet
     * @return int[3]: {highCrossings, lowCrossings, finalDepthPackets}
     */
    private int[] simulatePACKSTraffic(boolean decrementOnDrop) {
        // --- PACKS-realistic parameters (matching R1 experiment) ---
        // Buffer: 166 packets (250KB), HWM: 50 pkts, LWM: 10 pkts
        long hwmBits = 50 * PACKET_SIZE_BITS;
        long lwmBits = 10 * PACKET_SIZE_BITS;
        VirtualQueue vq = new VirtualQueue(hwmBits, lwmBits, "packs-test", false);

        int numBurstCycles = 10;
        boolean isMonitoring = false;
        int monitoringStarted = 0;
        int monitoringCompleted = 0;

        for (int cycle = 0; cycle < numBurstCycles; cycle++) {

            // === BURST PHASE: high arrival rate ===
            // 100 packets arrive; 80 are dropped by admission control; 20 enqueued
            // Link transmits 10 packets during this phase
            for (int i = 0; i < 100; i++) {
                boolean crossedHigh = vq.onArrival(PACKET_SIZE_BITS);
                if (crossedHigh && !isMonitoring) {
                    isMonitoring = true;
                    monitoringStarted++;
                }
            }
            // 80 drops (PACKS admission control rejects)
            for (int i = 0; i < 80; i++) {
                if (decrementOnDrop) {
                    boolean crossedLow = vq.onDeparture(PACKET_SIZE_BITS);
                    if (crossedLow && isMonitoring) {
                        isMonitoring = false;
                        monitoringCompleted++;
                    }
                }
                // else: no VQ update for drops
            }
            // 10 actual link departures during burst
            for (int i = 0; i < 10; i++) {
                boolean crossedLow = vq.onDeparture(PACKET_SIZE_BITS);
                if (crossedLow && isMonitoring) {
                    isMonitoring = false;
                    monitoringCompleted++;
                }
            }

            // === INTER-BURST PHASE: low arrival rate ===
            // 3 arrivals (steady-state), 0 drops, 30 departures (link drains)
            for (int i = 0; i < 3; i++) {
                boolean crossedHigh = vq.onArrival(PACKET_SIZE_BITS);
                if (crossedHigh && !isMonitoring) {
                    isMonitoring = true;
                    monitoringStarted++;
                }
            }
            for (int i = 0; i < 30; i++) {
                boolean crossedLow = vq.onDeparture(PACKET_SIZE_BITS);
                if (crossedLow && isMonitoring) {
                    isMonitoring = false;
                    monitoringCompleted++;
                }
            }
        }

        int finalDepthPackets = (int) vq.getDepthPackets();
        return new int[]{ monitoringStarted, monitoringCompleted, finalDepthPackets };
    }

    @Test
    @DisplayName("H2 PROOF: Without drop-decrement, VQ grows unbounded under PACKS traffic")
    void packsTraffic_withoutDropDecrement_VQGrowsUnbounded() {
        int[] result = simulatePACKSTraffic(false);
        int started = result[0];
        int completed = result[1];
        int finalDepth = result[2];

        // Without drop-decrement: VQ accumulates +90 phantom packets per burst
        // (100 arrivals - 10 departures, drops not subtracted).
        // Inter-burst drains only 27 (30 departures - 3 arrivals).
        // Net per cycle: +63 packets. After 10 cycles: ~630 packets above LWM.
        //
        // Monitoring starts on first burst and NEVER stops.
        assertEquals(1, started,
            "Without drop-decrement: only 1 monitoring window should open (the first)");
        assertEquals(0, completed,
            "Without drop-decrement: monitoring window NEVER closes (VQ never returns to LWM)");
        assertTrue(finalDepth > 100,
            "Without drop-decrement: VQ should grow to hundreds of packets, was: " + finalDepth);

        System.out.println("[NO DROP-DECREMENT]  started=" + started
            + ", completed=" + completed + ", finalVQ=" + finalDepth + " pkts");
    }

    @Test
    @DisplayName("H2 PROOF: With drop-decrement, VQ cycles properly under PACKS traffic")
    void packsTraffic_withDropDecrement_VQCyclesProperly() {
        int[] result = simulatePACKSTraffic(true);
        int started = result[0];
        int completed = result[1];
        int finalDepth = result[2];

        // With drop-decrement: VQ net per burst = 100 - 80 - 10 = +10 packets.
        // Inter-burst: +3 - 30 = -27. Net per cycle: 10 - 27 = -17.
        // VQ oscillates: rises during burst (crosses HWM), drains during
        // inter-burst (crosses LWM). Multiple windows open and close.
        assertTrue(started >= 2,
            "With drop-decrement: multiple monitoring windows should start, got: " + started);
        assertTrue(completed >= 2,
            "With drop-decrement: multiple monitoring windows should complete, got: " + completed);
        assertTrue(finalDepth < 50,
            "With drop-decrement: VQ should stay bounded (< HWM), was: " + finalDepth);

        // Windows started and completed should be close (proper cycling)
        assertTrue(Math.abs(started - completed) <= 1,
            "With drop-decrement: started (" + started + ") and completed ("
            + completed + ") should be within 1 of each other");

        System.out.println("[WITH DROP-DECREMENT] started=" + started
            + ", completed=" + completed + ", finalVQ=" + finalDepth + " pkts");
    }

    @Test
    @DisplayName("H2 PROOF: SP-PIFO (no drops) behaves identically under both strategies")
    void sppifoTraffic_noDrop_bothStrategiesIdentical() {
        // SP-PIFO has no admission control — all packets are enqueued, none dropped.
        // Both strategies should produce identical VQ behavior.
        long hwmBits = 50 * PACKET_SIZE_BITS;
        long lwmBits = 10 * PACKET_SIZE_BITS;

        VirtualQueue vqA = new VirtualQueue(hwmBits, lwmBits, "sppifo-no-decrement", false);
        VirtualQueue vqB = new VirtualQueue(hwmBits, lwmBits, "sppifo-with-decrement", false);

        // Simulate SP-PIFO: all packets enqueued, no drops
        int numCycles = 5;
        for (int cycle = 0; cycle < numCycles; cycle++) {
            // Burst: 60 arrivals, 10 departures, 0 drops
            for (int i = 0; i < 60; i++) {
                vqA.onArrival(PACKET_SIZE_BITS);
                vqB.onArrival(PACKET_SIZE_BITS);
            }
            for (int i = 0; i < 10; i++) {
                vqA.onDeparture(PACKET_SIZE_BITS);
                vqB.onDeparture(PACKET_SIZE_BITS);
            }
            // Inter-burst: 3 arrivals, 55 departures, 0 drops
            for (int i = 0; i < 3; i++) {
                vqA.onArrival(PACKET_SIZE_BITS);
                vqB.onArrival(PACKET_SIZE_BITS);
            }
            for (int i = 0; i < 55; i++) {
                vqA.onDeparture(PACKET_SIZE_BITS);
                vqB.onDeparture(PACKET_SIZE_BITS);
            }
        }

        // With zero drops, both strategies are identical
        assertEquals(vqA.getDepthBits(), vqB.getDepthBits(),
            "SP-PIFO (no drops): both strategies should give identical VQ depth");
        assertEquals(vqA.getHighWatermarkCrossings(), vqB.getHighWatermarkCrossings(),
            "SP-PIFO (no drops): high watermark crossings should be identical");
        assertEquals(vqA.getLowWatermarkCrossings(), vqB.getLowWatermarkCrossings(),
            "SP-PIFO (no drops): low watermark crossings should be identical");

        System.out.println("[SP-PIFO] crossings: " + vqA.getHighWatermarkCrossings()
            + "/" + vqA.getLowWatermarkCrossings()
            + ", finalVQ=" + vqA.getDepthPackets() + " pkts");
    }
}
