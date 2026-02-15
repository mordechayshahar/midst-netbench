package ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl;

// TODO: This test uses JUnit 5 (Jupiter) but the codebase uses JUnit 4.
// Convert to JUnit 4 patterns during Phase 3 MIDST implementation.
// See ERROR_FIX_PLAN.md for conversion guide.

import ch.ethz.systems.netbench.core.network.Packet;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.ICongestionSignaler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

// Import the actual PriorityHeader interface provided by the user
import ch.ethz.systems.netbench.xpt.tcpbase.PriorityHeader;

import java.util.Collections;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AIFOQueue.
 * Tests basic queue functionality, signaling, and the MIDST feedback reaction
 * (preferential dropping of congested flows).
 */
class AIFOQueueTest {

    private AIFOQueue queue;
    private ICongestionSignaler mockSignaler;

    // Mock Packets implementing PriorityHeader
    private Packet mockPkt_F1_R10; // Flow 1, Rank 10
    private Packet mockPkt_F2_R5;  // Flow 2, Rank 5

    // Watermarks are PACKET COUNTS
    private final long HIGH_WATER_MARK_PACKETS = 2;
    private final long LOW_WATER_MARK_PACKETS = 1;


    // Helper method to create mock packets implementing PriorityHeader
    private Packet createMockPacket(long flowId, long rank) {
        Packet pkt = Mockito.mock(Packet.class, withSettings().extraInterfaces(PriorityHeader.class));
        PriorityHeader header = (PriorityHeader) pkt;
        when(header.getFlowId()).thenReturn(flowId);
        when(header.getPriority()).thenReturn(rank);
        return pkt;
    }

    @BeforeEach
    void setUp() {
        mockSignaler = Mockito.mock(ICongestionSignaler.class);
        queue = new AIFOQueue(HIGH_WATER_MARK_PACKETS, LOW_WATER_MARK_PACKETS);
        queue.setCongestionSignaler(mockSignaler);

        // Create mocks
        mockPkt_F1_R10 = createMockPacket(1L, 10L);
        mockPkt_F2_R5 = createMockPacket(2L, 5L);
    }

    // --- Basic Queue and Signaling Tests (Similar to SPPIFO/Baseline) ---

    @Test
    void testInitialState() {
        assertEquals(0, queue.size());
        assertTrue(queue.isEmpty());
        assertFalse(queue.isCongestionDetected());
    }

    @Test
    void testOfferAndSize_Normal() {
        assertTrue(queue.offer(mockPkt_F1_R10));
        assertEquals(1, queue.size());
        assertTrue(queue.offer(mockPkt_F2_R5));
        assertEquals(2, queue.size());
    }

    @Test
    void testPollEmptyReturnsNull() {
        assertNull(queue.poll());
    }

     @Test
    void testRemoveEmptyThrowsException() {
        assertThrows(NoSuchElementException.class, () -> queue.remove());
    }

    @Test
    void testPeekEmptyReturnsNull() {
        assertNull(queue.peek());
    }

    @Test
    void testPollReturnsLowestRank() { // Assumes rank-based ordering for now
        queue.offer(mockPkt_F1_R10); // Rank 10
        queue.offer(mockPkt_F2_R5);  // Rank 5

        assertEquals(2, queue.size());
        assertSame(mockPkt_F2_R5, queue.peek());
        assertSame(mockPkt_F2_R5, queue.poll());
        assertEquals(1, queue.size());
        assertSame(mockPkt_F1_R10, queue.poll());
        assertEquals(0, queue.size());
    }

    @Test
    void testHighWatermarkSignal() {
        // HWM = 2
        queue.offer(mockPkt_F1_R10); // size=1
        assertFalse(queue.isCongestionDetected());
        verify(mockSignaler, never()).signalCongestionStart();

        queue.offer(mockPkt_F2_R5);  // size=2, HWM reached
        assertTrue(queue.isCongestionDetected());
        verify(mockSignaler, times(1)).signalCongestionStart();

        // Offer another - should be accepted if not congested flow
        Packet mockPkt_F3_R15 = createMockPacket(3L, 15L);
        assertTrue(queue.offer(mockPkt_F3_R15)); // size=3
        assertTrue(queue.isCongestionDetected());
        verify(mockSignaler, times(1)).signalCongestionStart(); // No new signal
    }

    @Test
    void testLowWatermarkSignal() {
        // HWM=2, LWM=1
        // Reach congestion
        queue.offer(mockPkt_F1_R10);
        queue.offer(mockPkt_F2_R5); // size=2, congested
        assertTrue(queue.isCongestionDetected());
        verify(mockSignaler, times(1)).signalCongestionStart();

        // Poll one packet
        queue.poll(); // size=1 (polled R5), LWM reached
        assertFalse(queue.isCongestionDetected());
        verify(mockSignaler, times(1)).signalCongestionEnd();

        // Poll last packet
        queue.poll(); // size=0 (polled R10)
        assertFalse(queue.isCongestionDetected());
        verify(mockSignaler, times(1)).signalCongestionEnd(); // No new signal
    }

    // --- Test Feedback Mechanism (AIFO-like Dropping) ---

    @Test
    void testApplyCongestionFeedback_CongestedFlowDroppedOnOffer() {
        // Mark Flow 1 as congested -> should be dropped by offer()
        Set<Long> congested = Collections.singleton(1L);
        queue.applyCongestionFeedback(congested);

        // Offer non-congested packet (Flow 2) -> should succeed
        assertTrue(queue.offer(mockPkt_F2_R5), "Non-congested flow should be accepted");
        assertEquals(1, queue.size());

        // Offer congested packet (Flow 1) -> should be dropped (offer returns false)
        assertFalse(queue.offer(mockPkt_F1_R10), "Congested flow packet should be dropped by offer");
        assertEquals(1, queue.size(), "Queue size should not increase after dropping offer");

        // Verify packet wasn't added
        assertNotSame(mockPkt_F1_R10, queue.peek()); // Should still be F2_R5
    }

    @Test
    void testApplyCongestionFeedback_MultipleFlows() {
        // Mark Flows 1 and 3 as congested
        Set<Long> congested = new HashSet<>(Set.of(1L, 3L));
        queue.applyCongestionFeedback(congested);

        Packet mockPkt_F3_R20 = createMockPacket(3L, 20L);

        // Offer packets
        assertTrue(queue.offer(mockPkt_F2_R5), "Flow 2 (not congested) should be accepted");
        assertFalse(queue.offer(mockPkt_F1_R10), "Flow 1 (congested) should be dropped");
        assertFalse(queue.offer(mockPkt_F3_R20), "Flow 3 (congested) should be dropped");

        assertEquals(1, queue.size(), "Only non-congested packet should be in queue");
        assertSame(mockPkt_F2_R5, queue.peek());
    }

     @Test
    void testApplyCongestionFeedback_ClearedOnEmptySet() {
        // Mark Flow 1 as congested initially
        Set<Long> congested = Collections.singleton(1L);
        queue.applyCongestionFeedback(congested);

        // Offer from Flow 1 -> fails
        assertFalse(queue.offer(mockPkt_F1_R10));
        assertEquals(0, queue.size());

        // Clear feedback
        queue.applyCongestionFeedback(Collections.emptySet());

        // Offer from Flow 1 again -> should succeed now
        assertTrue(queue.offer(mockPkt_F1_R10), "Previously congested flow should now be accepted");
        assertEquals(1, queue.size());
        assertSame(mockPkt_F1_R10, queue.peek());
    }

    @Test
    void testClearResetsCongestionAndFeedbackState() {
         // Arrange
         queue.offer(mockPkt_F1_R10);
         queue.offer(mockPkt_F2_R5); // size=2, congested
         assertTrue(queue.isCongestionDetected());
         Set<Long> congestedSet = Collections.singleton(1L);
         queue.applyCongestionFeedback(congestedSet); // Mark flow 1 congested

         // Act
         queue.clear();

         // Assert
         assertEquals(0, queue.size());
         assertTrue(queue.isEmpty());
         assertFalse(queue.isCongestionDetected(), "Congestion state should reset on clear");
         // Verify feedback state cleared by trying to offer packet from flow 1
         assertTrue(queue.offer(mockPkt_F1_R10), "Flow 1 should not be dropped after clear");
         assertEquals(1, queue.size());
    }
}