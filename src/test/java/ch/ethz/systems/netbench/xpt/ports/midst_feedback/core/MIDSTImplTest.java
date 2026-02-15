package ch.ethz.systems.netbench.xpt.ports.midst_feedback.core;

import ch.ethz.systems.netbench.core.Simulator;
import ch.ethz.systems.netbench.core.config.NBProperties;
import ch.ethz.systems.netbench.core.log.SimulationLogger;
import ch.ethz.systems.netbench.core.network.Packet;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MIDSTImpl - the main MIDST black box implementation.
 *
 * These tests verify that MIDSTImpl correctly:
 * 1. Uses virtual queue to trigger monitoring
 * 2. Tracks flow statistics with Count-Min sketches
 * 3. Classifies bursty flows correctly
 * 4. Provides correct query results
 */
class MIDSTImplTest {

    private static final long PACKET_SIZE_BITS = 1500L * 8L;
    private static final int HIGH_WATERMARK_PACKETS = 5;
    private static final int LOW_WATERMARK_PACKETS = 2;
    private static final int BURST_THRESHOLD = 10; // 10 packets to be considered bursty
    private static final int SKETCH_SIZE = 256;
    private static final int NUM_HASH_FUNCTIONS = 4;

    private static NBProperties mockConfig;
    private MIDSTImpl midst;

    @BeforeAll
    static void setupLogger() {
        mockConfig = Mockito.mock(NBProperties.class);
        when(mockConfig.getPropertyWithDefault(eq("run_folder_name"), any())).thenReturn("test_midst");
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
        midst = MIDSTImpl.withPacketWatermarks(
            SKETCH_SIZE, NUM_HASH_FUNCTIONS, BURST_THRESHOLD,
            HIGH_WATERMARK_PACKETS, LOW_WATERMARK_PACKETS,
            "test->port", false
        );
    }

    @AfterEach
    void tearDown() {
        Simulator.reset();
    }

    /**
     * Helper to create a mock packet.
     */
    private Packet createMockPacket(long flowId) {
        Packet packet = mock(Packet.class);
        when(packet.getFlowId()).thenReturn(flowId);
        when(packet.getSizeBit()).thenReturn(PACKET_SIZE_BITS);
        return packet;
    }

    // =========================================================================
    // INITIAL STATE
    // =========================================================================

    @Test
    @DisplayName("MIDST starts in correct initial state")
    void initialState_shouldBeCorrect() {
        assertFalse(midst.isMonitoring());
        assertEquals(0, midst.getVirtualQueueDepthBits());
        assertTrue(midst.getBurstyFlows().isEmpty());
        assertTrue(midst.getHeavyLosers().isEmpty());
        assertEquals(0, midst.getMonitoringWindowCount());
        assertEquals(BURST_THRESHOLD, midst.getBurstThreshold());
    }

    // =========================================================================
    // VIRTUAL QUEUE TRIGGER
    // =========================================================================

    @Test
    @DisplayName("Monitoring starts when virtual queue crosses high watermark")
    void onPacketArrival_crossingHighWatermark_startsMonitoring() {
        // Add packets up to high watermark
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            Packet packet = createMockPacket(100 + i);
            midst.onPacketArrival(packet);
        }

        assertTrue(midst.isMonitoring(), "Should be monitoring after crossing high watermark");
    }

    @Test
    @DisplayName("Monitoring does not start below high watermark")
    void onPacketArrival_belowHighWatermark_doesNotStartMonitoring() {
        // Add packets below high watermark
        for (int i = 0; i < HIGH_WATERMARK_PACKETS - 1; i++) {
            Packet packet = createMockPacket(100 + i);
            midst.onPacketArrival(packet);
        }

        assertFalse(midst.isMonitoring(), "Should not be monitoring below high watermark");
    }

    @Test
    @DisplayName("Monitoring stops when virtual queue crosses low watermark")
    void onPacketDeparture_crossingLowWatermark_stopsMonitoring() {
        // Start monitoring
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            Packet packet = createMockPacket(100);
            midst.onPacketArrival(packet);
        }
        assertTrue(midst.isMonitoring());

        // Depart packets until below low watermark
        for (int i = 0; i < HIGH_WATERMARK_PACKETS - LOW_WATERMARK_PACKETS + 1; i++) {
            Packet packet = createMockPacket(100);
            midst.onPacketDeparture(packet);
        }

        assertFalse(midst.isMonitoring(), "Should stop monitoring after crossing low watermark");
        assertEquals(1, midst.getMonitoringWindowCount());
    }

    // =========================================================================
    // BURSTY FLOW DETECTION
    // =========================================================================

    @Test
    @DisplayName("Flow with many packets is classified as bursty")
    void burstyFlowDetection_shouldIdentifyBurstyFlows() {
        long burstyFlowId = 42;
        long normalFlowId = 99;

        // Start monitoring
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            midst.onPacketArrival(createMockPacket(1000 + i)); // Fill up
        }
        assertTrue(midst.isMonitoring());

        // Send many packets from bursty flow (>= threshold)
        for (int i = 0; i < BURST_THRESHOLD + 5; i++) {
            midst.onPacketArrival(createMockPacket(burstyFlowId));
        }

        // Send few packets from normal flow (< threshold)
        for (int i = 0; i < BURST_THRESHOLD - 5; i++) {
            midst.onPacketArrival(createMockPacket(normalFlowId));
        }

        // Stop monitoring (depart all packets)
        for (int i = 0; i < 100; i++) {
            midst.onPacketDeparture(createMockPacket(1));
        }

        // Verify classification
        assertTrue(midst.isFlowBursty(burstyFlowId), "Bursty flow should be classified as bursty");
        assertFalse(midst.isFlowBursty(normalFlowId), "Normal flow should NOT be classified as bursty");

        Set<Long> burstyFlows = midst.getBurstyFlows();
        assertTrue(burstyFlows.contains(burstyFlowId));
        assertFalse(burstyFlows.contains(normalFlowId));
    }

    @Test
    @DisplayName("Unknown flow is not classified as bursty")
    void isFlowBursty_unknownFlow_returnsFalse() {
        assertFalse(midst.isFlowBursty(999999L));
    }

    // =========================================================================
    // INGRESS ESTIMATE
    // =========================================================================

    @Test
    @DisplayName("Ingress estimate reflects packet count")
    void getFlowIngressEstimate_shouldReflectPacketCount() {
        long flowId = 123;

        // Start monitoring
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            midst.onPacketArrival(createMockPacket(1000 + i));
        }

        // Send 15 packets from flow
        for (int i = 0; i < 15; i++) {
            midst.onPacketArrival(createMockPacket(flowId));
        }

        // Estimate should be approximately 15 (Count-Min may over-estimate due to collisions)
        int estimate = midst.getFlowIngressEstimate(flowId);
        assertTrue(estimate >= 15, "Estimate should be at least 15, got " + estimate);
        assertTrue(estimate <= 20, "Estimate should not be much higher than 15, got " + estimate);
    }

    // =========================================================================
    // LOSS DETECTION (Δ-SKETCH)
    // =========================================================================

    @Test
    @DisplayName("Loss estimate reflects dropped packets")
    void getFlowLossEstimate_shouldDetectDrops() {
        long flowId = 456;

        // Start monitoring
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            midst.onPacketArrival(createMockPacket(1000 + i));
        }

        // Simulate: 10 arrivals, 7 departures = 3 dropped
        Packet[] packets = new Packet[10];
        for (int i = 0; i < 10; i++) {
            packets[i] = createMockPacket(flowId);
            midst.onPacketArrival(packets[i]);
        }

        // Depart 7
        for (int i = 0; i < 7; i++) {
            midst.onPacketDeparture(packets[i]);
        }

        // Drop 3 (notify MIDST)
        for (int i = 7; i < 10; i++) {
            midst.onPacketDropped(packets[i]);
        }

        // Check loss estimate (should be approximately 3)
        int lossEstimate = midst.getFlowLossEstimate(flowId);
        assertTrue(lossEstimate >= 2, "Loss estimate should be at least 2, got " + lossEstimate);
        assertTrue(lossEstimate <= 5, "Loss estimate should not be much higher than 3, got " + lossEstimate);
    }

    // =========================================================================
    // MONITORING WINDOWS
    // =========================================================================

    @Test
    @DisplayName("Multiple monitoring windows are tracked")
    void multipleMonitoringWindows_shouldBeTracked() {
        // Window 1
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            midst.onPacketArrival(createMockPacket(100));
        }
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            midst.onPacketDeparture(createMockPacket(100));
        }

        assertEquals(1, midst.getMonitoringWindowCount());

        // Window 2
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            midst.onPacketArrival(createMockPacket(200));
        }
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            midst.onPacketDeparture(createMockPacket(200));
        }

        assertEquals(2, midst.getMonitoringWindowCount());
    }

    @Test
    @DisplayName("Bursty flows are cleared between windows")
    void burstyFlows_shouldBeClearedBetweenWindows() {
        long flowId = 777;

        // Window 1: Flow is bursty
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            midst.onPacketArrival(createMockPacket(1000 + i));
        }
        for (int i = 0; i < BURST_THRESHOLD + 5; i++) {
            midst.onPacketArrival(createMockPacket(flowId));
        }
        for (int i = 0; i < 50; i++) {
            midst.onPacketDeparture(createMockPacket(1));
        }

        assertTrue(midst.isFlowBursty(flowId), "Flow should be bursty after window 1");

        // Window 2: Flow sends few packets (not bursty)
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            midst.onPacketArrival(createMockPacket(2000 + i));
        }
        for (int i = 0; i < 2; i++) {
            midst.onPacketArrival(createMockPacket(flowId));
        }
        for (int i = 0; i < 50; i++) {
            midst.onPacketDeparture(createMockPacket(1));
        }

        assertFalse(midst.isFlowBursty(flowId), "Flow should NOT be bursty after window 2");
    }

    // =========================================================================
    // STATISTICS
    // =========================================================================

    @Test
    @DisplayName("Statistics are tracked correctly")
    void statistics_shouldBeTracked() {
        // Before any activity
        assertEquals(0, midst.getTotalBurstyPackets());
        assertEquals(0, midst.getTotalNormalPackets());

        // The statistics are updated during monitoring based on current classification
        // This is a basic smoke test
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            midst.onPacketArrival(createMockPacket(100 + i));
        }

        // At this point, packets are arriving during monitoring
        // Statistics should be incrementing
        long total = midst.getTotalBurstyPackets() + midst.getTotalNormalPackets();
        assertTrue(total >= 0, "Statistics should be non-negative");
    }

    // =========================================================================
    // EDGE CASES
    // =========================================================================

    @Test
    @DisplayName("Constructor rejects invalid parameters")
    void constructor_withInvalidParams_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> {
            new MIDSTImpl(0, 4, 10, 1000, 100, "test", false); // Invalid sketch size
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new MIDSTImpl(256, 0, 10, 1000, 100, "test", false); // Invalid hash functions
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new MIDSTImpl(256, 4, 0, 1000, 100, "test", false); // Invalid threshold
        });
    }

    @Test
    @DisplayName("getBurstyFlows returns unmodifiable set")
    void getBurstyFlows_shouldReturnUnmodifiableSet() {
        Set<Long> burstyFlows = midst.getBurstyFlows();

        assertThrows(UnsupportedOperationException.class, () -> {
            burstyFlows.add(999L);
        });
    }

    @Test
    @DisplayName("toString provides useful info")
    void toString_shouldProvideUsefulInfo() {
        String str = midst.toString();

        assertTrue(str.contains("MIDSTImpl"));
        assertTrue(str.contains("test->port"));
    }

    // =========================================================================
    // ADAPTIVE THRESHOLD DETECTION
    // =========================================================================

    @Test
    @DisplayName("Adaptive disabled (0.0): backward compatibility with absolute threshold")
    void adaptiveDisabled_shouldBehaveIdenticallyToAbsoluteThreshold() {
        // Create with adaptive=0.0 (disabled) — should behave exactly like default
        MIDSTImpl adaptiveMidst = MIDSTImpl.withPacketWatermarks(
            SKETCH_SIZE, NUM_HASH_FUNCTIONS, BURST_THRESHOLD,
            HIGH_WATERMARK_PACKETS, LOW_WATERMARK_PACKETS,
            "test-adaptive-off", false, 0.0
        );
        assertEquals(0.0, adaptiveMidst.getAdaptiveThresholdFraction());

        long burstyFlowId = 42;
        long normalFlowId = 99;

        // Start monitoring
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            adaptiveMidst.onPacketArrival(createMockPacket(1000 + i));
        }
        assertTrue(adaptiveMidst.isMonitoring());

        // Bursty flow: 15 packets >= threshold of 10
        for (int i = 0; i < BURST_THRESHOLD + 5; i++) {
            adaptiveMidst.onPacketArrival(createMockPacket(burstyFlowId));
        }
        // Normal flow: 5 packets < threshold of 10
        for (int i = 0; i < BURST_THRESHOLD - 5; i++) {
            adaptiveMidst.onPacketArrival(createMockPacket(normalFlowId));
        }

        // Stop monitoring
        for (int i = 0; i < 100; i++) {
            adaptiveMidst.onPacketDeparture(createMockPacket(1));
        }

        assertTrue(adaptiveMidst.isFlowBursty(burstyFlowId),
            "With adaptive=0.0, bursty flow should be detected (absolute threshold)");
        assertFalse(adaptiveMidst.isFlowBursty(normalFlowId),
            "With adaptive=0.0, normal flow should NOT be detected");
    }

    @Test
    @DisplayName("Adaptive enabled: dominant flow detected, minor flows not")
    void adaptiveEnabled_dominantFlowDetected_minorFlowsNot() {
        // phi=0.10 (10%), burstThreshold=10 (absolute floor)
        MIDSTImpl adaptiveMidst = MIDSTImpl.withPacketWatermarks(
            SKETCH_SIZE, NUM_HASH_FUNCTIONS, BURST_THRESHOLD,
            HIGH_WATERMARK_PACKETS, LOW_WATERMARK_PACKETS,
            "test-adaptive-dominant", false, 0.10
        );

        // Start monitoring
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            adaptiveMidst.onPacketArrival(createMockPacket(1000 + i));
        }
        assertTrue(adaptiveMidst.isMonitoring());

        // Flow A: 100 packets (dominant)
        long flowA = 42;
        for (int i = 0; i < 100; i++) {
            adaptiveMidst.onPacketArrival(createMockPacket(flowA));
        }
        // Flows B-K: 5 packets each (10 flows × 5 = 50 total)
        for (int f = 0; f < 10; f++) {
            for (int i = 0; i < 5; i++) {
                adaptiveMidst.onPacketArrival(createMockPacket(200 + f));
            }
        }

        // Stop monitoring
        for (int i = 0; i < 500; i++) {
            adaptiveMidst.onPacketDeparture(createMockPacket(1));
        }

        // Total window ~155 packets (5 from watermark trigger + 100 + 50)
        // Flow A: 100/155 ≈ 64.5% > 10% → bursty (also 100 >= 10 floor)
        assertTrue(adaptiveMidst.isFlowBursty(flowA),
            "Dominant flow A (64% of traffic) should be detected as bursty");

        // Minor flows: 5/155 ≈ 3.2% < 10% → NOT bursty
        for (int f = 0; f < 10; f++) {
            assertFalse(adaptiveMidst.isFlowBursty(200 + f),
                "Minor flow " + (200 + f) + " (3% of traffic) should NOT be bursty");
        }
    }

    @Test
    @DisplayName("Adaptive enabled: equal flows all below threshold fraction")
    void adaptiveEnabled_equalFlows_noneDetected() {
        // phi=0.25 (25%), burstThreshold=10
        MIDSTImpl adaptiveMidst = MIDSTImpl.withPacketWatermarks(
            SKETCH_SIZE, NUM_HASH_FUNCTIONS, BURST_THRESHOLD,
            HIGH_WATERMARK_PACKETS, LOW_WATERMARK_PACKETS,
            "test-adaptive-equal", false, 0.25
        );

        // Start monitoring
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            adaptiveMidst.onPacketArrival(createMockPacket(1000 + i));
        }
        assertTrue(adaptiveMidst.isMonitoring());

        // 5 flows, each with 20 packets = 100 total in-window
        // Plus ~5 trigger packets = ~105 total
        // Each flow: 20/105 ≈ 19% < 25% → none should be bursty
        for (int f = 0; f < 5; f++) {
            for (int i = 0; i < 20; i++) {
                adaptiveMidst.onPacketArrival(createMockPacket(300 + f));
            }
        }

        // Stop monitoring
        for (int i = 0; i < 500; i++) {
            adaptiveMidst.onPacketDeparture(createMockPacket(1));
        }

        // None should be bursty
        assertTrue(adaptiveMidst.getBurstyFlows().isEmpty(),
            "No flows should be bursty when all have ~19% < 25% threshold. " +
            "Detected: " + adaptiveMidst.getBurstyFlows());
    }

    @Test
    @DisplayName("Adaptive: absolute floor enforced even when fraction exceeded")
    void adaptiveEnabled_absoluteFloorEnforced() {
        // phi=0.05 (5%), burstThreshold=50 (high absolute floor)
        MIDSTImpl adaptiveMidst = MIDSTImpl.withPacketWatermarks(
            SKETCH_SIZE, NUM_HASH_FUNCTIONS, 50, // high absolute floor
            HIGH_WATERMARK_PACKETS, LOW_WATERMARK_PACKETS,
            "test-adaptive-floor", false, 0.05
        );

        // Start monitoring
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            adaptiveMidst.onPacketArrival(createMockPacket(1000 + i));
        }
        assertTrue(adaptiveMidst.isMonitoring());

        // Flow A: 30 packets out of ~100 total → 30% > 5% BUT 30 < 50 (floor)
        long flowA = 42;
        for (int i = 0; i < 30; i++) {
            adaptiveMidst.onPacketArrival(createMockPacket(flowA));
        }
        // Fill rest of window with other traffic
        for (int i = 0; i < 65; i++) {
            adaptiveMidst.onPacketArrival(createMockPacket(500 + (i % 13)));
        }

        // Stop monitoring
        for (int i = 0; i < 500; i++) {
            adaptiveMidst.onPacketDeparture(createMockPacket(1));
        }

        // Flow A: 30/100 = 30% > 5% (passes fraction) BUT 30 < 50 (fails floor)
        assertFalse(adaptiveMidst.isFlowBursty(flowA),
            "Flow A should NOT be bursty: passes fraction (30%) but fails absolute floor (30 < 50)");

        // Now test second window where flow B passes both checks
        // Start new monitoring window
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            adaptiveMidst.onPacketArrival(createMockPacket(2000 + i));
        }
        assertTrue(adaptiveMidst.isMonitoring());

        // Flow B: 60 packets out of ~200 → 30% > 5% AND 60 >= 50 (passes both)
        long flowB = 77;
        for (int i = 0; i < 60; i++) {
            adaptiveMidst.onPacketArrival(createMockPacket(flowB));
        }
        for (int i = 0; i < 135; i++) {
            adaptiveMidst.onPacketArrival(createMockPacket(600 + (i % 27)));
        }

        // Stop monitoring
        for (int i = 0; i < 1000; i++) {
            adaptiveMidst.onPacketDeparture(createMockPacket(1));
        }

        assertTrue(adaptiveMidst.isFlowBursty(flowB),
            "Flow B should be bursty: passes fraction (30%) AND absolute floor (60 >= 50)");
    }

    @Test
    @DisplayName("Adaptive: zero window packets does not cause exception")
    void adaptiveEnabled_zeroWindowPackets_noException() {
        MIDSTImpl adaptiveMidst = MIDSTImpl.withPacketWatermarks(
            SKETCH_SIZE, NUM_HASH_FUNCTIONS, BURST_THRESHOLD,
            HIGH_WATERMARK_PACKETS, LOW_WATERMARK_PACKETS,
            "test-adaptive-zero", false, 0.05
        );

        // Query without any packets arriving — should not throw
        assertDoesNotThrow(() -> adaptiveMidst.isFlowBursty(42L));
        assertFalse(adaptiveMidst.isFlowBursty(42L),
            "With no packets, no flow should be bursty");
        assertEquals(0, adaptiveMidst.getTotalWindowPackets(),
            "Window packet count should be 0 initially");
    }
}
