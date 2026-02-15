package ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl;

import ch.ethz.systems.netbench.core.Simulator;
import ch.ethz.systems.netbench.core.config.NBProperties;
import ch.ethz.systems.netbench.core.network.Packet;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.ICongestionSignaler;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.IMonitoredQueue;

// Import the actual PriorityHeader interface provided by the user
import ch.ethz.systems.netbench.xpt.tcpbase.PriorityHeader;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import ch.ethz.systems.netbench.core.log.SimulationLogger;

/**
 * A queue implementing SP-PIFO (Strict Priority Packet In First Out) logic
 * based on packet rank (priority). Lower rank means higher priority.
 * It uses packet count based watermarks for congestion signaling and reacts
 * to congestion feedback by penalizing the rank of specified flows.
 *
 * Uses java.util.PriorityQueue internally, making it non-blocking but requiring
 * external synchronization if used concurrently (relies on NetBench's single thread).
 * Lock is used only for state consistency during watermark checks and signaling.
 * Provides FIFO ordering for packets with equal effective rank.
 */
public class CanonicalSPPIFOQueue extends AbstractQueue<Packet> implements IMonitoredQueue {

    private static boolean LOGGING_ENABLED = true; // Class-level logging flag

    private final long highWaterMarkPackets;
    private final long lowWaterMarkPackets;
    private final long congestionPenaltyRank;

    // Internal queue ordered by effective rank (priority)
    private final PriorityQueue<PacketWrapper> internalQueue;

    // State for signaling and feedback
    private volatile boolean congestionDetected = false;
    private ICongestionSignaler signaler = null;
    private final ReentrantLock stateLock = new ReentrantLock(); // Lock for signaling state consistency

    // Set to store IDs of flows currently considered congested (penalized)
    private final Set<Long> congestedFlowIds = ConcurrentHashMap.newKeySet();

    /**
     * Inner class to wrap packets with their effective rank used for ordering.
     * Implements Comparable for use in PriorityQueue. Includes sequence number for FIFO tie-breaking.
     */
    private static class PacketWrapper implements Comparable<PacketWrapper> {
        final Packet packet;
        final long effectiveRank;
        final long sequence; // For FIFO tie-breaking

        private static long offerCounter = 0; // Global counter for FIFO tie-breaking

        PacketWrapper(Packet packet, long effectiveRank) {
            this.packet = packet;
            this.effectiveRank = effectiveRank;
            // Assign a sequence number atomically for FIFO tie-breaking
            synchronized (PacketWrapper.class) {
                this.sequence = offerCounter++;
            }
        }

        @Override
        public int compareTo(PacketWrapper other) {
            // Compare by effective rank first (lower rank = higher priority)
            int rankCompare = Long.compare(this.effectiveRank, other.effectiveRank);
            if (rankCompare == 0) {
                // If ranks are equal, use FIFO order (lower sequence number = inserted earlier)
                return Long.compare(this.sequence, other.sequence);
            }
            return rankCompare;
        }

        // equals and hashCode are not strictly necessary for PriorityQueue correctness,
        // unless you plan to use contains() or remove(Object) based on value equality.
        // Default object identity works for ordering.
    }


    /**
     * Constructor.
     * @param highWaterMarkPackets Congestion threshold in packet count.
     * @param lowWaterMarkPackets  Decongestion threshold in packet count.
     * @param congestionPenaltyRank Value added to rank of congested flows (should be > 0).
     */
    public CanonicalSPPIFOQueue(long highWaterMarkPackets, long lowWaterMarkPackets, long congestionPenaltyRank) {
         // Input validation
         if (lowWaterMarkPackets >= highWaterMarkPackets) {
             if (lowWaterMarkPackets > highWaterMarkPackets) {
                throw new IllegalArgumentException("Low water mark packets ("+lowWaterMarkPackets+") cannot be greater than high water mark packets ("+highWaterMarkPackets+").");
             } else if (highWaterMarkPackets > 0) { // Only warn if HWM > 0 and they are equal
                 System.err.println("Warning: CanonicalSPPIFOQueue Low water mark packets ("+lowWaterMarkPackets+") equals high water mark ("+highWaterMarkPackets+"). Signaling might be unstable.");
             }
         }
         if (congestionPenaltyRank <= 0) {
              System.err.println("Warning: CanonicalSPPIFOQueue congestionPenaltyRank (" + congestionPenaltyRank + ") is not positive. Penalization might not have intended effect.");
              // Consider throwing an IllegalArgumentException if penalty must be positive
              // throw new IllegalArgumentException("Congestion penalty rank must be positive.");
         }
        this.highWaterMarkPackets = highWaterMarkPackets;
        this.lowWaterMarkPackets = lowWaterMarkPackets;
        this.congestionPenaltyRank = congestionPenaltyRank;

        // Initial capacity for PriorityQueue (doesn't restrict actual size)
        this.internalQueue = new PriorityQueue<>(); // Uses PacketWrapper's compareTo

        // Initialize logging
        NBProperties config = Simulator.getConfiguration(); // May be null if called outside simulation context
        if (config != null) {
            LOGGING_ENABLED = config.getBooleanPropertyWithDefault("midst_sppifo_queue_internal_logging_enabled", false);
        }
        if (LOGGING_ENABLED) System.out.println("CanonicalSPPIFOQueue created: HWM=" + highWaterMarkPackets + " pkts, LWM=" + lowWaterMarkPackets + " pkts, Penalty=" + congestionPenaltyRank);
    }

    @Override
    public void setCongestionSignaler(ICongestionSignaler signaler) {
        this.signaler = signaler;
     // PASTE THESE LINES:
        if (LOGGING_ENABLED) { // LOGGING_ENABLED is the static boolean in your queue
            String signalerHash = (this.signaler == null) ? "NULL" : Integer.toString(this.signaler.hashCode());
            System.out.println("CONSOLE_QUEUE_DEBUG: Queue " + this.hashCode() + " setCongestionSignaler. Signaler is now: " + signalerHash + ". Time=" + Simulator.getCurrentTime());
            SimulationLogger.logInfo("SPPIFO_QUEUE_INTERNAL_DEBUG", "SetSignaler,QueueHash=" + this.hashCode() + ",SignalerHash=" + signalerHash + ",Time=" + Simulator.getCurrentTime());
        }
    }

    /**
     * Calculates effective rank, wraps packet, adds to the PriorityQueue, and checks watermarks.
     * @param packet The packet to add.
     * @return true (as PriorityQueue is unbounded).
     * @throws NullPointerException if packet is null.
     */
    @Override
    public boolean offer(Packet packet) {
        Objects.requireNonNull(packet, "Cannot offer null packet");

        long baseRank;
        long flowId = packet.getFlowId();
        String isBursty = "unknown"; // TODO: Replace with actual bursty status if available

        if (packet instanceof PriorityHeader) {
            baseRank = ((PriorityHeader) packet).getPriority();
        } else {
            // Assign a default high rank (low priority) if no header present
            baseRank = 100000; // Ensure lower priority than penalized packets
            if (LOGGING_ENABLED) System.out.println("Warning: Offered packet without PriorityHeader (Flow=" + flowId + "). Assigning max rank.");
        }
        
     // PASTE THIS DEBUG BLOCK HERE:
        boolean isCurrentlyConsideredPenalizedByQueue = congestedFlowIds.contains(flowId);

        if (LOGGING_ENABLED) { // LOGGING_ENABLED is your queue's static/instance boolean flag
            SimulationLogger.logInfo("SPPIFO_QUEUE_OFFER_DEBUG", 
                                    "CheckPenalizedState,Queue=" + this.hashCode() + 
                                    ",Flow=" + flowId + 
                                    ",IsConsideredPenalized=" + isCurrentlyConsideredPenalizedByQueue + 
                                    ",CongestedSetContents=" + congestedFlowIds.toString() + // Log current state of the set
                                    ",Time=" + Simulator.getCurrentTime());
        }
        // END OF DEBUG BLOCK
        
        long effectiveRank = baseRank;
        boolean loggedAsPenalized = false; // Initialize here

        if (isCurrentlyConsideredPenalizedByQueue) { // Use the variable from the debug block
            try {
                effectiveRank = Math.addExact(baseRank, congestionPenaltyRank);
                loggedAsPenalized = true; // Set if penalty is applied
            } catch (ArithmeticException e) {
                System.err.println("Rank overflow occurred for flow " + flowId + "! Clamping to MAX_VALUE.");
                effectiveRank = Long.MAX_VALUE;
                loggedAsPenalized = true; // It was intended to be penalized
            }
        }

        // Apply penalty if flow is marked congested
        boolean penalized = congestedFlowIds.contains(flowId);
        if (penalized) {
            // Add penalty - check for potential overflow, though unlikely with typical ranks/penalties
            try {
                effectiveRank = Math.addExact(baseRank, congestionPenaltyRank);
            } catch (ArithmeticException e) {
                System.err.println("Rank overflow occurred for flow " + flowId + "! Clamping to MAX_VALUE.");
                effectiveRank = Long.MAX_VALUE;
            }
        }
     // This is your existing SPPIFO_RANK_OFFER log, ensure it uses loggedAsPenalized:
        if (LOGGING_ENABLED) {
            SimulationLogger.logInfo("SPPIFO_RANK_OFFER",
                Simulator.getCurrentTime()  + "," +  
                this.hashCode()  + "," +  
                flowId  + "," +  
                baseRank  + "," +  
                loggedAsPenalized  + "," +  // USE THE 'loggedAsPenalized' VARIABLE
                (loggedAsPenalized ? congestionPenaltyRank : 0)  + "," +  // USE 'loggedAsPenalized' VARIABLE
                effectiveRank  + "," +  
                isBursty
            );
        }

        // Wrap packet and add to internal queue
        PacketWrapper wrapper = new PacketWrapper(packet, effectiveRank);
        boolean added = internalQueue.offer(wrapper);

        // Check watermarks if successfully added
        if (added) {
            checkWatermarks();
        } else {
            // Should not happen with PriorityQueue unless there's an unexpected issue
            System.err.println("CRITICAL: internalQueue.offer failed in SPPIFO Queue!");
         // In the else block for if (!added)
         // ...
         if (LOGGING_ENABLED) {
             SimulationLogger.logInfo("SPPIFO_DROP", // This is your custom SPPIFO_DROP
                 Simulator.getCurrentTime()  + "," + 
                 this.hashCode() + "," + 
                 flowId + "," + 
                 loggedAsPenalized + "," + // USE 'loggedAsPenalized'
                 isBursty
             );
         }
         // ...
        }
        return added;
    }

    /**
     * Retrieves and removes the head PacketWrapper (lowest effective rank), unwraps the Packet,
     * or returns null if this queue is empty. Checks watermarks after removing.
     * @return The head packet, or null if empty.
     */
    @Override
    public Packet poll() {
        stateLock.lock(); // Lock to ensure size check and poll are consistent for signaling
        PacketWrapper wrapper = null;
        try {
            wrapper = internalQueue.poll(); // Poll from the priority queue
            if (wrapper != null) {
                checkWatermarks(); // Check watermarks after successful removal
            }
        } finally {
            stateLock.unlock();
        }
        return (wrapper != null) ? wrapper.packet : null;
    }

    /**
     * Retrieves, but does not remove, the head PacketWrapper (lowest effective rank), unwraps the Packet,
     * or returns null if this queue is empty.
     * @return The head packet, or null if empty.
     */
    @Override
    public Packet peek() {
        // Peek is generally safe without lock if internalQueue is thread-safe or only accessed by one thread
        // PriorityQueue is NOT thread-safe, but NetBench uses single thread. Still, safer to lock?
        // Let's keep peek lock-free for now, assuming single-threaded access from NetBench core.
        PacketWrapper wrapper = internalQueue.peek();
        return (wrapper != null) ? wrapper.packet : null;
    }

    @Override
    public int size() {
        return internalQueue.size();
    }

    /**
     * Returns an iterator over the packets in the queue.
     * Note: The order is NOT guaranteed to be the priority order.
     * @return an Iterator.
     */
    @Override
    public Iterator<Packet> iterator() {
        // Provides iterator over internal PriorityQueue's current state (order not guaranteed)
        Iterator<PacketWrapper> wrapperIterator = internalQueue.iterator();
        return new Iterator<Packet>() {
            @Override
            public boolean hasNext() {
                return wrapperIterator.hasNext();
            }
            @Override
            public Packet next() {
                return wrapperIterator.next().packet; // Unwrap the packet
            }
             @Override
             public void remove() {
                 // Optional: Implement remove if needed, requires calling wrapperIterator.remove()
                 // and potentially recalculating byte size and checking watermarks.
                 throw new UnsupportedOperationException("Remove not implemented for this iterator.");
             }
        };
    }

    @Override
    public void clear() {
         stateLock.lock();
         try {
             internalQueue.clear();
             congestedFlowIds.clear(); // Clear congestion state too
             if (congestionDetected) {
                 // Reset local state, but don't signal end here, as it wasn't a natural drain
                 if (LOGGING_ENABLED) System.out.println("CanonicalSPPIFOQueue: Cleared while congestion was active. Resetting state.");
                 congestionDetected = false;
             }
         } finally {
             stateLock.unlock();
         }
    }


    // Checks if the queue length (packet count) meets/exceeds the high-water mark
    private void checkForCongestion() {
        stateLock.lock();
        try {
            // Use internalQueue.size()
            if (!congestionDetected && internalQueue.size() >= highWaterMarkPackets) {
                congestionDetected = true;
                if (LOGGING_ENABLED) {
                    System.out.println("SPPIFOQueue Congestion START: Size=" + internalQueue.size() + " >= HWM=" + highWaterMarkPackets);
                }
                // Safely call signaler
                ICongestionSignaler currentSignaler = this.signaler;
                if (currentSignaler != null) {
                    try {
                        currentSignaler.signalCongestionStart();
                    } catch (Exception e) {
                        System.err.println("ERROR calling signalCongestionStart on signaler: " + e.getMessage());
                        e.printStackTrace();
                    }
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
            // Use internalQueue.size()
            if (congestionDetected && internalQueue.size() <= lowWaterMarkPackets) {
                congestionDetected = false;
                if (LOGGING_ENABLED) {
                    System.out.println("SPPIFOQueue Congestion END: Size=" + internalQueue.size() + " <= LWM=" + lowWaterMarkPackets);
                }
                 // Safely call signaler
                 ICongestionSignaler currentSignaler = this.signaler;
                 if (currentSignaler != null) {
                    try {
                        currentSignaler.signalCongestionEnd();
                    } catch (Exception e) {
                        System.err.println("ERROR calling signalCongestionEnd on signaler: " + e.getMessage());
                        e.printStackTrace();
                    }
                 }
            }
        } finally {
            stateLock.unlock();
        }
    }

    /** Combined check, ensuring state consistency with lock. */
    private void checkWatermarks() {
        stateLock.lock();
        try {
        	if (LOGGING_ENABLED) { SimulationLogger.logInfo("SPPIFO_QUEUE_INTERNAL_DEBUG", "CheckWatermarks_ENTRY,QueueHash=" + this.hashCode() + ",CurrentSize=" + internalQueue.size() + ",HWM=" + highWaterMarkPackets + ",LWM=" + lowWaterMarkPackets + ",CongestionDetectedFlag=" + congestionDetected + ",SignalerIsNull=" + (this.signaler == null) + ",Time=" + Simulator.getCurrentTime()); }
            long currentSize = internalQueue.size(); // Check size once under lock
            // Check for crossing High Water Mark upwards
            if (!congestionDetected && currentSize >= highWaterMarkPackets) {
                congestionDetected = true;
                 if (LOGGING_ENABLED) System.out.println("SPPIFOQueue Congestion START signaled: Size=" + currentSize + " >= HWM=" + highWaterMarkPackets);
                 ICongestionSignaler currentSignaler = this.signaler;
                 
                 if (LOGGING_ENABLED) {
                     String signalerHash = (currentSignaler == null) ? "NULL" : Integer.toString(currentSignaler.hashCode());
                     System.out.println("CONSOLE_QUEUE_DEBUG: Queue " + this.hashCode() + " ABOUT TO CALL signalCongestionStart. Signaler: " + signalerHash + ". Time=" + Simulator.getCurrentTime());
                     SimulationLogger.logInfo("SPPIFO_QUEUE_INTERNAL_DEBUG", "AttemptSignalStart,QueueHash=" + this.hashCode() + ",SignalerHash=" + signalerHash + ",Time=" + Simulator.getCurrentTime());
                 }
                 
                 if (currentSignaler != null) {
                     currentSignaler.signalCongestionStart(); // Call while still holding lock? Usually okay in single thread.
                 }
            }
            // Check for crossing Low Water Mark downwards (only if previously congested)
            // Use 'else if' to avoid potential double signal if HWM=LWM and size hits it exactly
            else if (congestionDetected && currentSize <= lowWaterMarkPackets) {
                congestionDetected = false;
                 if (LOGGING_ENABLED) System.out.println("SPPIFOQueue Congestion END signaled: Size=" + currentSize + " <= LWM=" + lowWaterMarkPackets);
                 ICongestionSignaler currentSignaler = this.signaler;
                 
                 // PASTE THESE LINES:
                 if (LOGGING_ENABLED) {
                     String signalerHash = (currentSignaler == null) ? "NULL" : Integer.toString(currentSignaler.hashCode());
                     System.out.println("CONSOLE_QUEUE_DEBUG: Queue " + this.hashCode() + " ABOUT TO CALL signalCongestionEnd. Signaler: " + signalerHash + ". Time=" + Simulator.getCurrentTime());
                     SimulationLogger.logInfo("SPPIFO_QUEUE_INTERNAL_DEBUG", "AttemptSignalEnd,QueueHash=" + this.hashCode() + ",SignalerHash=" + signalerHash + ",Time=" + Simulator.getCurrentTime());
                 }
                 
                 if (currentSignaler != null) {
                    currentSignaler.signalCongestionEnd();
                 }
            }
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Updates the internal set of flows that should receive a rank penalty.
     * An empty set clears the penalties for subsequently offered packets.
     * @param congestedFlowIds A set of flow identifiers identified as congested by the port.
     */
    @Override
    public void applyCongestionFeedback(Set<Long> congestedFlowIds) {
         Objects.requireNonNull(congestedFlowIds);
         // No lock needed here as congestedFlowIds is thread-safe ConcurrentHashMap.newKeySet()
         // If it wasn't thread-safe, a lock would be needed.

         // Replace the contents atomically (or as near as needed)
         this.congestedFlowIds.clear();
         if (congestedFlowIds != null && !congestedFlowIds.isEmpty()) {
              this.congestedFlowIds.addAll(congestedFlowIds);
              if (LOGGING_ENABLED) {
                    SimulationLogger.logInfo("SPPIFO_PENALIZE",
                        Simulator.getCurrentTime() + "," + this.hashCode() + "," + congestedFlowIds.toString());
              }
         } else {
             if (LOGGING_ENABLED) {
                 SimulationLogger.logInfo("SPPIFO_PENALIZE",
                        Simulator.getCurrentTime() + "," + this.hashCode() + "," + "cleared");
             }
         }
         // Note: This only affects packets offered *after* this call.
    }

    /** Returns true if the queue currently considers itself in a congested state. */
    public boolean isCongestionDetected() {
        // Reading volatile boolean is thread-safe
        return congestionDetected;
    }
}