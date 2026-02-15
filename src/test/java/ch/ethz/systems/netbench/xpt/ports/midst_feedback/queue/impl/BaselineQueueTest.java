package ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl;

// TODO: This test uses JUnit 5 (Jupiter) but the codebase uses JUnit 4.
// Convert to JUnit 4 patterns during Phase 3 MIDST implementation.
// See ERROR_FIX_PLAN.md for conversion guide.

import ch.ethz.systems.netbench.core.network.Packet;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.ICongestionSignaler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BaselineQueueTest {

    private BaselineQueue queue;
    private ICongestionSignaler mockSignaler;
    private Packet mockPacket1;
    private Packet mockPacket2;
    private Packet mockPacket3;

    // Watermarks are now PACKET COUNTS
    private final long HIGH_WATER_MARK_PACKETS = 2;
    private final long LOW_WATER_MARK_PACKETS = 1;

    @BeforeEach
    void setUp() {
        mockSignaler = Mockito.mock(ICongestionSignaler.class);
        // Capacity doesn't matter much if we rely on packet count watermarks for signaling
        queue = new BaselineQueue(Integer.MAX_VALUE, HIGH_WATER_MARK_PACKETS, LOW_WATER_MARK_PACKETS);
        queue.setCongestionSignaler(mockSignaler);

        // Create distinct mock packets
        mockPacket1 = Mockito.mock(Packet.class);
        mockPacket2 = Mockito.mock(Packet.class);
        mockPacket3 = Mockito.mock(Packet.class);
    }

    @Test
    void testInitialState() {
        assertEquals(0, queue.size());
        assertTrue(queue.isEmpty());
        assertFalse(queue.isCongestionDetected(), "Should not be congested initially");
    }

    @Test
    void testOfferUpdatesSize() {
        assertTrue(queue.offer(mockPacket1));
        assertEquals(1, queue.size());
        assertFalse(queue.isEmpty());

        assertTrue(queue.offer(mockPacket2));
        assertEquals(2, queue.size());
    }

    @Test
    void testPollUpdatesSize() {
        queue.offer(mockPacket1);
        queue.offer(mockPacket2);

        assertSame(mockPacket1, queue.poll());
        assertEquals(1, queue.size());

        assertSame(mockPacket2, queue.poll());
        assertEquals(0, queue.size());
        assertTrue(queue.isEmpty());
    }

    @Test
    void testHighWatermarkSignal() {
        // Add 1 packet (below HWM=2)
        queue.offer(mockPacket1);
        assertEquals(1, queue.size());
        assertFalse(queue.isCongestionDetected());
        verify(mockSignaler, never()).signalCongestionStart();

        // Add 2nd packet (reaches HWM=2)
        queue.offer(mockPacket2);
        assertEquals(2, queue.size());
        assertTrue(queue.isCongestionDetected());
        verify(mockSignaler, times(1)).signalCongestionStart(); // Signal triggered

        // Add 3rd packet (still above HWM)
        queue.offer(mockPacket3);
        assertEquals(3, queue.size());
        assertTrue(queue.isCongestionDetected());
        verify(mockSignaler, times(1)).signalCongestionStart(); // Should not trigger again
    }

    @Test
    void testLowWatermarkSignal() {
        // Reach congestion (add 2 packets)
        queue.offer(mockPacket1);
        queue.offer(mockPacket2); // size=2, congested
        assertTrue(queue.isCongestionDetected());
        verify(mockSignaler, times(1)).signalCongestionStart();
        verify(mockSignaler, never()).signalCongestionEnd();

        // Poll 1 packet (remaining size=1, reaches LWM=1)
        queue.poll();
        assertEquals(1, queue.size());
        assertFalse(queue.isCongestionDetected());
        verify(mockSignaler, times(1)).signalCongestionEnd(); // Signal triggered

        // Poll last packet (remaining size=0, below LWM)
        queue.poll();
        assertEquals(0, queue.size());
        assertFalse(queue.isCongestionDetected());
        verify(mockSignaler, times(1)).signalCongestionEnd(); // Should not trigger again
    }


    @Test
    void testFluctuationAroundWatermarks() {
        // 1. Cross HWM (size 1 -> 2)
        queue.offer(mockPacket1); // size=1
        queue.offer(mockPacket2); // size=2, HWM crossed
        assertTrue(queue.isCongestionDetected());
        verify(mockSignaler, times(1)).signalCongestionStart();

        // 2. Drop below HWM but not below or at LWM (size 2 -> remove not possible with poll)
        // Let's add a 3rd packet first
        queue.offer(mockPacket3); // size=3
        verify(mockSignaler, times(1)).signalCongestionStart(); // No change

        // 3. Drop to size 2 (still >= HWM)
        queue.poll(); // size=2 (polled 1)
        assertTrue(queue.isCongestionDetected());
        verify(mockSignaler, never()).signalCongestionEnd();

        // 4. Drop to size 1 (reaches LWM)
        queue.poll(); // size=1 (polled 2)
        assertFalse(queue.isCongestionDetected());
        verify(mockSignaler, times(1)).signalCongestionEnd(); // Signal end here

        // 5. Go back above HWM (size 1 -> 2)
        queue.offer(mockPacket1); // size=2 (added 1) - NOTE: adding same mock, just for size count
        assertTrue(queue.isCongestionDetected());
        verify(mockSignaler, times(2)).signalCongestionStart(); // Signal start again

        // 6. Drop below LWM again (size 2 -> 1 -> 0)
        queue.poll(); // size=1 (polled 3)
        assertFalse(queue.isCongestionDetected());
        verify(mockSignaler, times(2)).signalCongestionEnd(); // Signal end again
        queue.poll(); // size=0 (polled 1)
        assertFalse(queue.isCongestionDetected());
        verify(mockSignaler, times(2)).signalCongestionEnd(); // No new signal
    }

    @Test
    void testApplyCongestionFeedback() {
        // Ensure this method exists and doesn't throw errors, and doesn't change state
        boolean initialState = queue.isCongestionDetected();
        long initialSize = queue.size();
        assertDoesNotThrow(() -> queue.applyCongestionFeedback(Set.of(1L, 2L)));
        assertEquals(initialState, queue.isCongestionDetected());
        assertEquals(initialSize, queue.size());
    }

     @Test
    void testSignalerNotSet() {
         // Arrange - Use watermarks 1, 0 for easy trigger
        BaselineQueue queueWithoutSignaler = new BaselineQueue(Integer.MAX_VALUE, 1, 0);

         // Act & Assert - Offer to cross HWM=1
         assertDoesNotThrow(() -> queueWithoutSignaler.offer(mockPacket1)); // Should not throw NullPointerException
         assertTrue(queueWithoutSignaler.isCongestionDetected()); // State should still update

         // Poll to cross LWM=0
         assertDoesNotThrow(() -> queueWithoutSignaler.poll()); // Should not throw NullPointerException
         assertFalse(queueWithoutSignaler.isCongestionDetected()); // State should still update
    }

     @Test
    void testClearResetsCongestionState() {
        // Arrange
        queue.offer(mockPacket1);
        queue.offer(mockPacket2); // size=2, congested
        assertTrue(queue.isCongestionDetected());

        // Act
        queue.clear();

        // Assert
        assertEquals(0, queue.size());
        assertFalse(queue.isCongestionDetected(), "Congestion state should reset on clear");
        // Verify no signals were sent during clear
        verify(mockSignaler, times(1)).signalCongestionStart(); // Only from initial offers
        verify(mockSignaler, never()).signalCongestionEnd();
    }
}