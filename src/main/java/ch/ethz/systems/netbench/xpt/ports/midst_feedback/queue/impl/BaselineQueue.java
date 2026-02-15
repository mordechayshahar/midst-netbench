package ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl;

import ch.ethz.systems.netbench.core.Simulator; // Keep for potential logging/config access
import ch.ethz.systems.netbench.core.config.NBProperties;
import ch.ethz.systems.netbench.core.network.Packet;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.ICongestionSignaler;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.IMonitoredQueue;

import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock; // Using lock for state consistency

/**
 * A baseline FIFO queue implementation based on the validated MIDSTQueue logic.
 * It monitors its packet occupancy count against watermarks and signals a
 * registered ICongestionSignaler.
 * This queue represents the "No Action" strategy, as its applyCongestionFeedback
 * method does nothing. Watermarks are based on PACKET COUNT.
 */
public class BaselineQueue extends LinkedBlockingQueue<Packet> implements IMonitoredQueue {

    // Copied relevant fields and logic from MIDSTQueue provided by user

    private static final long serialVersionUID = 1L; // Keep consistent if serialization matters
    private static boolean LOGGING_ENABLED = false; // Class-level logging flag, potentially set by config

    private final long highWaterMarkPackets; // Watermark in packets
    private final long lowWaterMarkPackets;  // Watermark in packets

    // Using AtomicLong for counters just like original, though not strictly necessary in single thread
    private final AtomicLong enqueuedPackets = new AtomicLong(0);
    private final AtomicLong dequeuedPackets = new AtomicLong(0);
    private final AtomicLong droppedPackets = new AtomicLong(0); // Note: offer doesn't drop here based on size limit, only if super.offer fails

    private volatile boolean congestionDetected = false; // Tracks congestion state

    private ICongestionSignaler signaler = null; // The port to signal
    private final ReentrantLock stateLock = new ReentrantLock(); // Lock for atomic state checks/updates

    /**
     * Constructor.
     * @param capacity            Internal queue capacity (e.g., Integer.MAX_VALUE).
     * @param highWaterMarkPackets Congestion threshold in packet count.
     * @param lowWaterMarkPackets  Decongestion threshold in packet count.
     */
    public BaselineQueue(int capacity, long highWaterMarkPackets, long lowWaterMarkPackets) {
        super(capacity);
         if (lowWaterMarkPackets >= highWaterMarkPackets) {
             if (lowWaterMarkPackets > highWaterMarkPackets) {
                 throw new IllegalArgumentException("Low water mark ("+lowWaterMarkPackets+") cannot be greater than high water mark ("+highWaterMarkPackets+").");
             } else {
                 System.err.println("Warning: BaselineQueue Low water mark packets ("+lowWaterMarkPackets+") is equal to high water mark packets ("+highWaterMarkPackets+").");
             }
         }
        this.highWaterMarkPackets = highWaterMarkPackets;
        this.lowWaterMarkPackets = lowWaterMarkPackets;

        // Initialize logging - Check config only once if possible
        if (Simulator.getConfiguration() != null) { // Check if Simulator is setup
            LOGGING_ENABLED = Simulator.getConfiguration().getBooleanPropertyWithDefault("midst_baseline_queue_internal_logging_enabled", false);
        }
         if (LOGGING_ENABLED) System.out.println("BaselineQueue created: HWM=" + highWaterMarkPackets + " pkts, LWM=" + lowWaterMarkPackets + " pkts");
    }

    @Override
    public void setCongestionSignaler(ICongestionSignaler signaler) {
        this.signaler = signaler;
    }

    @Override
    public boolean offer(Packet packet) {
        boolean added = super.offer(packet);
        if (!added) {
            droppedPackets.incrementAndGet(); // Count internal LinkedBlockingQueue rejections (e.g. capacity)
            // Maybe log drop here if needed
        } else {
            enqueuedPackets.incrementAndGet();
            checkForCongestion(); // Check if congestion has started (based on size())
        }
        return added;
    }

    @Override
    public Packet poll() {
        Packet packet = super.poll();
        if (packet != null) {
            dequeuedPackets.incrementAndGet();
            checkForDecongestion(); // Check if congestion has ended (based on size())
        }
        return packet;
    }

     @Override
    public Packet take() throws InterruptedException {
        Packet packet = super.take();
        // Assuming take() implies successful removal
        dequeuedPackets.incrementAndGet();
        checkForDecongestion();
        return packet;
    }

    // Checks if the queue length (packet count) exceeds the high-water mark
    private void checkForCongestion() {
        stateLock.lock();
        try {
            // Use size() which is provided by LinkedBlockingQueue
            if (!congestionDetected && size() >= highWaterMarkPackets) {
                congestionDetected = true;
                if (LOGGING_ENABLED) {
                    System.out.println("BaselineQueue Congestion START: Size=" + size() + " >= HWM=" + highWaterMarkPackets);
                }
                // Call the new signaler interface method
                if (signaler != null) {
                    signaler.signalCongestionStart();
                } else if (LOGGING_ENABLED) {
                     System.out.println("BaselineQueue: Signaler not set, cannot signal start.");
                }
            }
        } finally {
            stateLock.unlock();
        }
    }

    // Checks if the queue length (packet count) falls to or below the low-water mark
    private void checkForDecongestion() {
        stateLock.lock();
        try {
            // Use size()
            if (congestionDetected && size() <= lowWaterMarkPackets) {
                congestionDetected = false;
                if (LOGGING_ENABLED) {
                    System.out.println("BaselineQueue Congestion END: Size=" + size() + " <= LWM=" + lowWaterMarkPackets);
                }
                 // Call the new signaler interface method
                 if (signaler != null) {
                    signaler.signalCongestionEnd();
                 } else if (LOGGING_ENABLED) {
                      System.out.println("BaselineQueue: Signaler not set, cannot signal end.");
                 }
            }
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Baseline queue ignores feedback. This method does nothing.
     */
    @Override
    public void applyCongestionFeedback(Set<Long> congestedFlowIds) {
        // Takes no action based on feedback.
        if (LOGGING_ENABLED && !congestedFlowIds.isEmpty()) {
             // System.out.println("BaselineQueue: Received congestion feedback - Ignoring.");
        }
    }

    // Reset counters - Copied from original MIDSTQueue
    public void resetCounters() {
        enqueuedPackets.set(0);
        dequeuedPackets.set(0);
        droppedPackets.set(0);
    }

    // Utility methods to get statistics - Copied from original MIDSTQueue
    public long getEnqueuedPacketCount() {
        return enqueuedPackets.get();
    }

    public long getDequeuedPacketCount() {
        return dequeuedPackets.get();
    }

    public long getDroppedPacketCount() {
        return droppedPackets.get();
    }

    // Expose congestion state if needed - Copied from original MIDSTQueue
    public boolean isCongestionDetected() {
        return congestionDetected;
    }

     @Override
    public void clear() {
         stateLock.lock();
         try {
             super.clear();
             // Reset counters as well? Original didn't explicitly do this on clear.
             // Let's stick to original behavior unless specified.
             // Reset congestion state if queue is cleared.
             if (congestionDetected) {
                 if (LOGGING_ENABLED) System.out.println("BaselineQueue: Cleared while congestion was active. Resetting state.");
                 congestionDetected = false;
             }
         } finally {
             stateLock.unlock();
         }
    }

    // NOTE: startLossMonitoring() and stopLossMonitoring() from original MIDSTQueue
    // are effectively replaced by the direct calls to signaler.signalCongestionStart/End()

}