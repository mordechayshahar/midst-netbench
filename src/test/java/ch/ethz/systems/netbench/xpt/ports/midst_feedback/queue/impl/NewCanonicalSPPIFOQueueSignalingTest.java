package ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl;

import ch.ethz.systems.netbench.core.Simulator;
import ch.ethz.systems.netbench.core.config.NBProperties;
import ch.ethz.systems.netbench.core.log.SimulationLogger;
import ch.ethz.systems.netbench.core.network.Packet;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.ICongestionSignaler;
import ch.ethz.systems.netbench.xpt.tcpbase.PriorityHeader;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RED TESTS: These tests expose the bug where NewCanonicalSPPIFOQueue
 * never calls signaler.signalCongestionStart() or signaler.signalCongestionEnd().
 *
 * The tests will FAIL until the queue is fixed to properly signal congestion
 * based on watermark thresholds.
 *
 * Expected behavior (that is currently broken):
 * 1. When queue size >= highWaterMarkPackets, call signaler.signalCongestionStart()
 * 2. When queue size <= lowWaterMarkPackets (after being congested), call signaler.signalCongestionEnd()
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NewCanonicalSPPIFOQueueSignalingTest {

    // Test parameters
    private static final long HIGH_WATERMARK_PACKETS = 5;  // Trigger congestion at 5 packets
    private static final long LOW_WATERMARK_PACKETS = 2;   // End congestion at 2 packets
    private static final long RANK_DELTA = 100;
    private static final int NUM_QUEUES = 3;
    private static final long CONGESTION_PENALTY_RANK = 200;

    @Mock
    private ICongestionSignaler mockSignaler;

    private NewCanonicalSPPIFOQueue queue;

    // Static mock config for SimulationLogger
    private static NBProperties mockConfig;

    @BeforeAll
    static void setupLogger() {
        mockConfig = Mockito.mock(NBProperties.class);
        when(mockConfig.getPropertyWithDefault(eq("run_folder_name"), any())).thenReturn("test_signaling");
        when(mockConfig.getPropertyWithDefault(eq("run_folder_base_dir"), any())).thenReturn("temp");
        when(mockConfig.getBooleanPropertyWithDefault(anyString(), eq(false))).thenReturn(false);
        when(mockConfig.getAllPropertiesToString()).thenReturn("# Mock Config for Signaling Test\n");

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
        // Initialize Simulator with mock config
        Simulator.setup(0, mockConfig);

        // Create queue with test parameters
        queue = new NewCanonicalSPPIFOQueue(
            HIGH_WATERMARK_PACKETS,
            LOW_WATERMARK_PACKETS,
            RANK_DELTA,
            NUM_QUEUES,
            CONGESTION_PENALTY_RANK
        );

        // Set the mock signaler
        queue.setCongestionSignaler(mockSignaler);
    }

    @AfterEach
    void tearDown() {
        Simulator.reset();
    }

    /**
     * Helper to create a mock packet with priority.
     */
    private Packet createMockPacket(long flowId, long priority) {
        // Create a mock that implements both Packet and PriorityHeader
        Packet mockPacket = mock(Packet.class, withSettings().extraInterfaces(PriorityHeader.class));
        when(mockPacket.getFlowId()).thenReturn(flowId);
        when(((PriorityHeader) mockPacket).getPriority()).thenReturn(priority);
        when(mockPacket.getSizeBit()).thenReturn(12000L); // 1500 bytes
        return mockPacket;
    }

    // ========================================================================
    // RED TEST 1: Queue should call signalCongestionStart when reaching HWM
    // ========================================================================
    @Test
    @DisplayName("RED TEST: Queue should call signalCongestionStart when reaching high watermark")
    void queue_shouldSignalCongestionStart_whenReachingHighWatermark() {
        // Add packets up to high watermark
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            Packet packet = createMockPacket(100 + i, 10);
            assertTrue(queue.offer(packet), "Packet " + i + " should be accepted");
        }

        // After reaching HIGH_WATERMARK_PACKETS, signalCongestionStart should have been called
        // THIS TEST WILL FAIL because NewCanonicalSPPIFOQueue never calls signaler methods
        verify(mockSignaler, times(1)).signalCongestionStart();
    }

    // ========================================================================
    // RED TEST 2: Queue should NOT signal before reaching HWM
    // ========================================================================
    @Test
    @DisplayName("RED TEST: Queue should NOT signal congestion before reaching high watermark")
    void queue_shouldNotSignalCongestion_beforeReachingHighWatermark() {
        // Add packets just below high watermark
        for (int i = 0; i < HIGH_WATERMARK_PACKETS - 1; i++) {
            Packet packet = createMockPacket(100 + i, 10);
            assertTrue(queue.offer(packet), "Packet " + i + " should be accepted");
        }

        // signalCongestionStart should NOT have been called yet
        verify(mockSignaler, never()).signalCongestionStart();
    }

    // ========================================================================
    // RED TEST 3: Queue should call signalCongestionEnd when dropping to LWM
    // ========================================================================
    @Test
    @DisplayName("RED TEST: Queue should call signalCongestionEnd when dropping to low watermark")
    void queue_shouldSignalCongestionEnd_whenDroppingToLowWatermark() {
        // First, fill queue to trigger congestion
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            Packet packet = createMockPacket(100 + i, 10);
            queue.offer(packet);
        }

        // Now drain the queue down to low watermark
        int packetsToRemove = (int) (HIGH_WATERMARK_PACKETS - LOW_WATERMARK_PACKETS);
        for (int i = 0; i < packetsToRemove; i++) {
            Packet polled = queue.poll();
            assertNotNull(polled, "Should be able to poll packet " + i);
        }

        // After dropping to LOW_WATERMARK_PACKETS, signalCongestionEnd should be called
        // THIS TEST WILL FAIL because NewCanonicalSPPIFOQueue never calls signaler methods
        verify(mockSignaler, times(1)).signalCongestionEnd();
    }

    // ========================================================================
    // RED TEST 4: Full congestion cycle should signal both start and end
    // ========================================================================
    @Test
    @DisplayName("RED TEST: Full congestion cycle should signal start then end")
    void queue_shouldSignalFullCongestionCycle() {
        // Phase 1: Fill queue to trigger congestion
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            Packet packet = createMockPacket(100 + i, 10);
            queue.offer(packet);
        }

        // Should have signaled congestion start
        verify(mockSignaler, times(1)).signalCongestionStart();

        // Phase 2: Drain queue completely
        while (queue.poll() != null) {
            // Keep draining
        }

        // Should have signaled congestion end
        verify(mockSignaler, times(1)).signalCongestionEnd();
    }

    // ========================================================================
    // RED TEST 5: Multiple congestion cycles should signal appropriately
    // ========================================================================
    @Test
    @DisplayName("RED TEST: Multiple congestion cycles should trigger multiple signals")
    void queue_shouldSignalMultipleCongestionCycles() {
        // Cycle 1: Fill and drain
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            queue.offer(createMockPacket(100 + i, 10));
        }
        while (queue.poll() != null) { }

        // Cycle 2: Fill and drain again
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            queue.offer(createMockPacket(200 + i, 20));
        }
        while (queue.poll() != null) { }

        // Should have 2 start signals and 2 end signals
        verify(mockSignaler, times(2)).signalCongestionStart();
        verify(mockSignaler, times(2)).signalCongestionEnd();
    }

    // ========================================================================
    // RED TEST 6: Congestion signal should not repeat while already congested
    // ========================================================================
    @Test
    @DisplayName("RED TEST: Congestion start should not repeat while already congested")
    void queue_shouldNotRepeatCongestionStart_whileAlreadyCongested() {
        // Fill to HWM
        for (int i = 0; i < HIGH_WATERMARK_PACKETS; i++) {
            queue.offer(createMockPacket(100 + i, 10));
        }

        // Add more packets (should NOT trigger another start signal)
        for (int i = 0; i < 3; i++) {
            queue.offer(createMockPacket(200 + i, 10));
        }

        // Should only have ONE congestion start signal
        verify(mockSignaler, times(1)).signalCongestionStart();
    }

    // ========================================================================
    // RED TEST 7: Signaler must be set for signals to work
    // ========================================================================
    @Test
    @DisplayName("Queue should not crash if signaler is null")
    void queue_shouldNotCrash_whenSignalerIsNull() {
        // Create queue without setting signaler
        NewCanonicalSPPIFOQueue queueNoSignaler = new NewCanonicalSPPIFOQueue(
            HIGH_WATERMARK_PACKETS,
            LOW_WATERMARK_PACKETS,
            RANK_DELTA,
            NUM_QUEUES,
            CONGESTION_PENALTY_RANK
        );
        // Don't call setCongestionSignaler

        // Should not throw exception when adding packets
        assertDoesNotThrow(() -> {
            for (int i = 0; i < HIGH_WATERMARK_PACKETS + 2; i++) {
                queueNoSignaler.offer(createMockPacket(100 + i, 10));
            }
        });
    }

    // ========================================================================
    // COMPARISON TEST: Verify BaselineQueue signals correctly (reference)
    // ========================================================================
    @Test
    @DisplayName("Reference: Verify test setup is correct by checking queue state")
    void verifyTestSetup_queueSizeMatchesExpectations() {
        // This test verifies our test setup is correct
        assertEquals(0, queue.size(), "Queue should start empty");

        // Add some packets
        for (int i = 0; i < 3; i++) {
            queue.offer(createMockPacket(100 + i, 10));
        }
        assertEquals(3, queue.size(), "Queue should have 3 packets");

        // Remove one
        queue.poll();
        assertEquals(2, queue.size(), "Queue should have 2 packets after poll");
    }
}
