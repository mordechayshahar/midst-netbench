package ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl;

// TODO: This test uses JUnit 5 (Jupiter) but the codebase uses JUnit 4.
// Convert to JUnit 4 patterns during Phase 3 MIDST implementation.
// See ERROR_FIX_PLAN.md for conversion guide.

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import ch.ethz.systems.netbench.core.Simulator;
import ch.ethz.systems.netbench.core.config.NBProperties;
import ch.ethz.systems.netbench.core.log.SimulationLogger;
import ch.ethz.systems.netbench.core.network.Packet;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.ICongestionSignaler;
// Import the actual PriorityHeader interface provided by the user
import ch.ethz.systems.netbench.xpt.tcpbase.PriorityHeader;

/**
 * Unit tests for CanonicalSPPIFOQueue.
 */
class CanonicalSPPIFOQueueTest {

    private static NBProperties mockConfig;

    private CanonicalSPPIFOQueue queue;
    private ICongestionSignaler mockSignaler;

    // Mock Packets implementing PriorityHeader
    private Packet mockPkt_F1_R10, mockPkt_F1_R20; // Flow 1, Ranks 10, 20
    private Packet mockPkt_F2_R5, mockPkt_F2_R15;  // Flow 2, Ranks 5, 15
    private Packet mockPkt_F3_R10; // Flow 3, Rank 10 (same base rank as F1_R10)

    // Watermarks are PACKET COUNTS
    private final long HIGH_WATER_MARK_PACKETS = 3;
    private final long LOW_WATER_MARK_PACKETS = 1;
    // Penalty added to rank for congested flows
    private final long CONGESTION_PENALTY_RANK = 100000L;

    @BeforeAll
    static void setupSimulatorAndLogger() {
        mockConfig = Mockito.mock(NBProperties.class);
        // Mock properties needed by SimulationLogger.open() (called internally by Simulator.setup)
        when(mockConfig.getPropertyWithDefault(eq("run_folder_name"), any())).thenReturn("test_run_SPPIFOQueueTest");
        when(mockConfig.getPropertyWithDefault(eq("run_folder_base_dir"), any())).thenReturn("temp");
        when(mockConfig.getBooleanPropertyWithDefault(anyString(), eq(false))).thenReturn(false);
        when(mockConfig.getAllPropertiesToString()).thenReturn("# Mock Config for SPPIFOQueueTest\n");

        // Defensive reset in case a previous test class didn't clean up
        try { Simulator.reset(); } catch (Exception e) { /* ignore */ }

        // Simulator.setup() also calls SimulationLogger.open() internally
        Simulator.setup(0, mockConfig);
    }

    @AfterAll
    static void tearDownSimulatorAndLogger() {
        // Simulator.reset() also calls SimulationLogger.closeAndThrowaway() internally
        try { Simulator.reset(); } catch (Exception e) { /* ignore */ }
    }

    // Helper method to create mock packets implementing PriorityHeader
    private Packet createMockPacket(long flowId, long rank) {
        // Use Mockito's ability to mock classes and implement interfaces
        Packet pkt = Mockito.mock(Packet.class, withSettings().extraInterfaces(PriorityHeader.class));
        PriorityHeader header = (PriorityHeader) pkt;
        when(header.getFlowId()).thenReturn(flowId);
        when(header.getPriority()).thenReturn(rank);
        // Optional: Give packets a size if needed for other tests
        // when(pkt.getSizeBit()).thenReturn(8000L); // e.g., 1000 bytes
        return pkt;
    }

    @BeforeEach
    void setUp() {
        mockSignaler = Mockito.mock(ICongestionSignaler.class);
        // Pass penalty during construction
        queue = new CanonicalSPPIFOQueue(HIGH_WATER_MARK_PACKETS, LOW_WATER_MARK_PACKETS, CONGESTION_PENALTY_RANK);
        queue.setCongestionSignaler(mockSignaler);

        // Create mocks
        mockPkt_F1_R10 = createMockPacket(1L, 10L);
        mockPkt_F1_R20 = createMockPacket(1L, 20L);
        mockPkt_F2_R5 = createMockPacket(2L, 5L);
        mockPkt_F2_R15 = createMockPacket(2L, 15L);
        mockPkt_F3_R10 = createMockPacket(3L, 10L);
    }

    @Test
    void testInitialState() {
        assertEquals(0, queue.size());
        assertTrue(queue.isEmpty());
        assertFalse(queue.isCongestionDetected());
    }

    @Test
    void testOfferAndSize() {
        assertTrue(queue.offer(mockPkt_F1_R10));
        assertEquals(1, queue.size());
        assertTrue(queue.offer(mockPkt_F2_R5));
        assertEquals(2, queue.size());
    }

    // --- Corrected test for poll() on empty queue ---
    @Test
    void testPollEmptyReturnsNull() {
        // Poll on empty PriorityQueue (and per Queue interface) returns null
        assertNull(queue.poll(), "poll() on empty queue should return null");
    }

    // --- Optional: Test for remove() on empty queue ---
    @Test
    void testRemoveEmptyThrowsException() {
        // remove() *is* expected to throw an exception when empty (as implemented by AbstractQueue)
        assertThrows(NoSuchElementException.class, () -> queue.remove(),
                     "remove() on empty queue should throw NoSuchElementException");
    }


    @Test
    void testPeekEmptyReturnsNull() {
        assertNull(queue.peek());
    }

    @Test
    void testPollReturnsLowestRank() {
        queue.offer(mockPkt_F1_R10); // Rank 10
        queue.offer(mockPkt_F2_R5);  // Rank 5
        queue.offer(mockPkt_F2_R15); // Rank 15

        assertEquals(3, queue.size());
        assertSame(mockPkt_F2_R5, queue.peek(), "Peek should show lowest rank");
        assertSame(mockPkt_F2_R5, queue.poll(), "Poll should return lowest rank");
        assertEquals(2, queue.size());

        assertSame(mockPkt_F1_R10, queue.peek(), "Peek should show next lowest rank");
        assertSame(mockPkt_F1_R10, queue.poll(), "Poll should return next lowest rank");
        assertEquals(1, queue.size());

        assertSame(mockPkt_F2_R15, queue.peek());
        assertSame(mockPkt_F2_R15, queue.poll());
        assertEquals(0, queue.size());
        assertTrue(queue.isEmpty());
    }

    @Test
    void testPollReturnsFIFOForEqualRank() {
        // Offer two packets with the same rank 10
        queue.offer(mockPkt_F1_R10); // Offered first
        queue.offer(mockPkt_F3_R10); // Offered second

        assertEquals(2, queue.size());
        // Check FIFO tie-breaking added via PacketWrapper sequence
        assertSame(mockPkt_F1_R10, queue.poll(), "First packet offered with same rank should be polled first");
        assertSame(mockPkt_F3_R10, queue.poll(), "Second packet offered with same rank should be polled second");
        assertEquals(0, queue.size());
    }

    // --- Test Watermark Signaling (based on packet count) ---

    @Test
    void testHighWatermarkSignal() {
        // HWM = 3
        queue.offer(mockPkt_F1_R10); // size=1
        queue.offer(mockPkt_F2_R5);  // size=2
        assertFalse(queue.isCongestionDetected());
        verify(mockSignaler, never()).signalCongestionStart();

        queue.offer(mockPkt_F2_R15); // size=3, HWM reached
        assertTrue(queue.isCongestionDetected());
        verify(mockSignaler, times(1)).signalCongestionStart();

        queue.offer(mockPkt_F3_R10); // size=4
        assertTrue(queue.isCongestionDetected());
        verify(mockSignaler, times(1)).signalCongestionStart(); // No new signal
    }

     @Test
    void testLowWatermarkSignal() {
        // HWM=3, LWM=1
        // Reach congestion
        queue.offer(mockPkt_F1_R10);
        queue.offer(mockPkt_F2_R5);
        queue.offer(mockPkt_F2_R15); // size=3, congested
        assertTrue(queue.isCongestionDetected());
        verify(mockSignaler, times(1)).signalCongestionStart();

        // Poll one packet
        queue.poll(); // size=2 (polled R5), still > LWM
        assertTrue(queue.isCongestionDetected());
        verify(mockSignaler, never()).signalCongestionEnd();

        // Poll another packet
        queue.poll(); // size=1 (polled R10), LWM reached
        assertFalse(queue.isCongestionDetected());
        verify(mockSignaler, times(1)).signalCongestionEnd();

        // Poll last packet
        queue.poll(); // size=0 (polled R15)
        assertFalse(queue.isCongestionDetected());
        verify(mockSignaler, times(1)).signalCongestionEnd(); // No new signal
    }

    // --- Test Feedback Mechanism ---

    @Test
    void testApplyCongestionFeedback_AffectsPollingOrder() {
        // Base ranks: F2_R5 (5), F1_R10 (10), F3_R10 (10)
        // Initially expect: F2_R5, then F1_R10 (offered first), then F3_R10

        // Mark Flow 1 as congested
        Set<Long> congested = Collections.singleton(1L);
        queue.applyCongestionFeedback(congested);

        // Offer packets - Rank calculation happens here implicitly now
        queue.offer(mockPkt_F1_R10); // Flow 1, Base Rank 10 -> Effective Rank 10 + PENALTY
        queue.offer(mockPkt_F2_R5);  // Flow 2, Base Rank 5 -> Effective Rank 5
        queue.offer(mockPkt_F3_R10); // Flow 3, Base Rank 10 -> Effective Rank 10

        // Expected order: F2_R5 (5), F3_R10 (10), F1_R10 (10 + PENALTY)
        assertEquals(3, queue.size());
        assertSame(mockPkt_F2_R5, queue.poll(), "Lowest base rank non-congested polled first");
        assertEquals(2, queue.size());
        assertSame(mockPkt_F3_R10, queue.poll(), "Next lowest base rank non-congested polled second");
        assertEquals(1, queue.size());
        assertSame(mockPkt_F1_R10, queue.poll(), "Congested packet polled last");
        assertEquals(0, queue.size());
    }

     @Test
    void testApplyCongestionFeedback_ClearedOnEmptySet() {
        // Mark Flow 1 as congested initially
        Set<Long> congested = Collections.singleton(1L);
        queue.applyCongestionFeedback(congested);

        // Offer packets
        queue.offer(mockPkt_F1_R10); // Congested -> Rank 10 + Penalty
        queue.offer(mockPkt_F2_R5);  // Not congested -> Rank 5

        // Simulate congestion end signal -> empty feedback set
        queue.applyCongestionFeedback(Collections.emptySet()); // Clears internal congested set

        // Offer another packet from Flow 1 (should NOT be penalized now)
        queue.offer(mockPkt_F1_R20); // Base Rank 20 -> Effective Rank 20

        // Expected order: F2_R5 (5), F1_R20 (20), F1_R10 (10 + Penalty)
        // The packet offered *while* congested retains its high effective rank.
        assertEquals(3, queue.size());
        assertSame(mockPkt_F2_R5, queue.poll(), "Rank 5 polled first");
        assertSame(mockPkt_F1_R20, queue.poll(), "Rank 20 (offered after clear) polled second");
        assertSame(mockPkt_F1_R10, queue.poll(), "Rank 10+Penalty (offered before clear) polled last");
        assertEquals(0, queue.size());
    }

    @Test
    void testOfferNullPacket() {
         assertThrows(NullPointerException.class, () -> queue.offer(null));
    }

    @Test
    void testOfferPacketWithoutPriorityHeader() {
        Packet noHeaderPacket = Mockito.mock(Packet.class); // Doesn't implement PriorityHeader
        when(noHeaderPacket.getFlowId()).thenReturn(99L);

        queue.offer(mockPkt_F2_R5); // Rank 5
        assertDoesNotThrow(() -> queue.offer(noHeaderPacket)); // Should be handled gracefully
        assertEquals(2, queue.size());

        // Packet without header should be polled last (lowest priority based on implementation)
        assertSame(mockPkt_F2_R5, queue.poll());
        assertSame(noHeaderPacket, queue.poll());
    }

     @Test
    void testSignalerNotSet() {
         // Arrange - Use watermarks 1, 0
        CanonicalSPPIFOQueue queueWithoutSignaler = new CanonicalSPPIFOQueue(1, 0, CONGESTION_PENALTY_RANK);

         // Act & Assert - Offer to cross HWM=1
         assertDoesNotThrow(() -> queueWithoutSignaler.offer(mockPkt_F1_R10));
         assertTrue(queueWithoutSignaler.isCongestionDetected());

         // Poll to cross LWM=0
         assertDoesNotThrow(() -> queueWithoutSignaler.poll());
         assertFalse(queueWithoutSignaler.isCongestionDetected());
    }

    @Test
    void testClearResetsState() {
         // Arrange
         queue.offer(mockPkt_F1_R10);
         queue.offer(mockPkt_F2_R5);
         queue.offer(mockPkt_F3_R10); // size=3, congested
         assertTrue(queue.isCongestionDetected());
         Set<Long> congested = Collections.singleton(1L);
         queue.applyCongestionFeedback(congested); // Set internal state

         // Act
         queue.clear();

         // Assert
         assertEquals(0, queue.size(), "Queue size should be 0 after clear");
         assertTrue(queue.isEmpty(), "Queue should be empty after clear");
         assertFalse(queue.isCongestionDetected(), "Congestion state should reset on clear");
         // Verify internal congested set is cleared too (test side-effect)
         queue.offer(mockPkt_F1_R10); // Base Rank 10
         queue.offer(mockPkt_F2_R5);  // Base Rank 5
         assertSame(mockPkt_F2_R5, queue.poll()); // Rank 5 comes first
         assertSame(mockPkt_F1_R10, queue.poll()); // Rank 10 comes second (no penalty)
    }
}