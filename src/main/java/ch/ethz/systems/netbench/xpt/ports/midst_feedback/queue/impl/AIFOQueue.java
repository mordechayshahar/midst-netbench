package ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl;

import ch.ethz.systems.netbench.core.Simulator;
import ch.ethz.systems.netbench.core.config.NBProperties;
import ch.ethz.systems.netbench.core.network.Packet;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.ICongestionSignaler;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.IMonitoredQueue;

// Import the actual PriorityHeader interface if using rank-based ordering
import ch.ethz.systems.netbench.xpt.tcpbase.PriorityHeader;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A queue implementing AIFO-like behavior where flows identified as congested
 * by MIDST feedback have their packets proactively dropped on arrival (offer fails).
 * Base queuing uses SP-PIFO rank order for packets that are accepted.
 * Signals congestion state based on packet count watermarks.
 */
public class AIFOQueue extends AbstractQueue<Packet> implements IMonitoredQueue {

    private static boolean LOGGING_ENABLED = false; // Class-level logging flag

    private final long highWaterMarkPackets;
    private final long lowWaterMarkPackets;

    // Internal queue ordered by rank (priority) for accepted packets
    // Using the same wrapper and comparator as SPPIFO for consistency in base ordering
    private final PriorityQueue<PacketWrapper> internalQueue;

    // State for signaling and feedback
    private volatile boolean congestionDetected = false;
    private ICongestionSignaler signaler = null;
    private final ReentrantLock stateLock = new ReentrantLock(); // Lock for signaling state consistency

    // Set to store IDs of flows currently considered congested (packets dropped on offer)
    private final Set<Long> congestedFlowIdsDrop = ConcurrentHashMap.newKeySet();

    /**
     * Inner class to wrap packets with their effective rank used for ordering.
     * Copied from CanonicalSPPIFOQueue for consistent base ordering.
     */
    private static class PacketWrapper implements Comparable<PacketWrapper> {
        final Packet packet;
        final long effectiveRank; // Rank is read but not penalized here
        final long sequence;

        private static long offerCounter = 0;

        PacketWrapper(Packet packet, long effectiveRank) {
            this.packet = packet;
            this.effectiveRank = effectiveRank;
            synchronized (PacketWrapper.class) {
                this.sequence = offerCounter++;
            }
        }

        @Override
        public int compareTo(PacketWrapper other) {
            int rankCompare = Long.compare(this.effectiveRank, other.effectiveRank);
            if (rankCompare == 0) {
                return Long.compare(this.sequence, other.sequence);
            }
            return rankCompare;
        }
    }

     // Comparator for the PriorityQueue - Copied from CanonicalSPPIFOQueue
    private static class PacketRankComparator implements Comparator<PacketWrapper> {
        @Override
        public int compare(PacketWrapper pw1, PacketWrapper pw2) {
             Packet p1 = pw1.packet;
             Packet p2 = pw2.packet;
            if (!(p1 instanceof PriorityHeader) || !(p2 instanceof PriorityHeader)) {
                boolean p1HasHeader = p1 instanceof PriorityHeader;
                boolean p2HasHeader = p2 instanceof PriorityHeader;
                if (p1HasHeader && !p2HasHeader) return -1;
                if (!p1HasHeader && p2HasHeader) return 1;
                // If ranks are equal or neither has header, use sequence for FIFO
                return Long.compare(pw1.sequence, pw2.sequence);
            }
            long rank1 = ((PriorityHeader) p1).getPriority();
            long rank2 = ((PriorityHeader) p2).getPriority();
            int rankCompare = Long.compare(rank1, rank2);
             if (rankCompare == 0) {
                 // If ranks are equal, use sequence for FIFO
                 return Long.compare(pw1.sequence, pw2.sequence);
             }
            return rankCompare;
        }
    }

    /**
     * Constructor.
     * @param highWaterMarkPackets Congestion threshold in packet count.
     * @param lowWaterMarkPackets  Decongestion threshold in packet count.
     */
    public AIFOQueue(long highWaterMarkPackets, long lowWaterMarkPackets) {
         if (lowWaterMarkPackets >= highWaterMarkPackets) {
             if (lowWaterMarkPackets > highWaterMarkPackets) {
                throw new IllegalArgumentException("Low water mark packets ("+lowWaterMarkPackets+") cannot be greater than high water mark packets ("+highWaterMarkPackets+").");
             } else if (highWaterMarkPackets > 0) {
                 System.err.println("Warning: AIFOQueue Low water mark packets ("+lowWaterMarkPackets+") equals high water mark ("+highWaterMarkPackets+"). Signaling might be unstable.");
             }
         }
        this.highWaterMarkPackets = highWaterMarkPackets;
        this.lowWaterMarkPackets = lowWaterMarkPackets;

        // Use comparator that handles PriorityHeader and FIFO tie-breaking
        this.internalQueue = new PriorityQueue<>(11, new PacketRankComparator());

        // Initialize logging
        NBProperties config = Simulator.getConfiguration();
        if (config != null) {
            LOGGING_ENABLED = config.getBooleanPropertyWithDefault("midst_aifo_queue_internal_logging_enabled", false);
        }
        if (LOGGING_ENABLED) System.out.println("AIFOQueue created: HWM=" + highWaterMarkPackets + " pkts, LWM=" + lowWaterMarkPackets + " pkts");
    }

    @Override
    public void setCongestionSignaler(ICongestionSignaler signaler) {
        this.signaler = signaler;
    }

    /**
     * Offers a packet to the queue *only if* its flow is not marked as congested.
     * If accepted, uses base rank for ordering and checks watermarks.
     * @param packet The packet to offer.
     * @return true if the packet was accepted, false if it was dropped due to belonging to a congested flow.
     * @throws NullPointerException if packet is null.
     */
    @Override
    public boolean offer(Packet packet) {
        Objects.requireNonNull(packet, "Cannot offer null packet");
        long flowId = packet.getFlowId();

        // AIFO-like action: Drop if flow is marked congested
        if (congestedFlowIdsDrop.contains(flowId)) {
            if (LOGGING_ENABLED) {
                System.out.println("AIFOQueue: Dropping packet from congested flow " + flowId + " on offer.");
            }
            // TODO: Maybe log this drop via SimulationLogger? Needs careful thought on statistics name.
            // SimulationLogger.increaseStatisticCounter("PACKETS_DROPPED_AIFO_FEEDBACK");
            return false; // Packet rejected/dropped by AIFO feedback policy
        }

        // If not dropped by feedback, proceed with normal SP-PIFO-like offer
        long baseRank = 0;
        if (packet instanceof PriorityHeader) {
            baseRank = ((PriorityHeader) packet).getPriority();
        } else {
            baseRank = Long.MAX_VALUE - 1; // Assign low priority if no header
            if (LOGGING_ENABLED) System.out.println("Warning: AIFOQueue offered packet without PriorityHeader (Flow=" + flowId + "). Assigning max rank.");
        }

        // Wrap packet (using base rank as effective rank, no penalty applied here)
        PacketWrapper wrapper = new PacketWrapper(packet, baseRank);
        boolean added = internalQueue.offer(wrapper);

        // Check watermarks if successfully added
        if (added) {
            checkWatermarks();
        } else {
             System.err.println("CRITICAL: internalQueue.offer failed in AIFO Queue!");
        }
        return added;
    }

    /**
     * Retrieves and removes the head PacketWrapper (lowest rank), unwraps the Packet,
     * or returns null if this queue is empty. Checks watermarks after removing.
     * @return The head packet, or null if empty.
     */
    @Override
    public Packet poll() {
        stateLock.lock();
        PacketWrapper wrapper = null;
        try {
            wrapper = internalQueue.poll();
            if (wrapper != null) {
                checkWatermarks(); // Check after removing
            }
        } finally {
            stateLock.unlock();
        }
        return (wrapper != null) ? wrapper.packet : null;
    }

    /**
     * Retrieves, but does not remove, the head PacketWrapper (lowest rank), unwraps the Packet,
     * or returns null if this queue is empty.
     * @return The head packet, or null if empty.
     */
    @Override
    public Packet peek() {
        // Assuming single-threaded access for peek is safe enough
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
     */
    @Override
    public Iterator<Packet> iterator() {
        Iterator<PacketWrapper> wrapperIterator = internalQueue.iterator();
        return new Iterator<Packet>() {
            @Override public boolean hasNext() { return wrapperIterator.hasNext(); }
            @Override public Packet next() { return wrapperIterator.next().packet; }
            @Override public void remove() { throw new UnsupportedOperationException(); }
        };
    }

     @Override
    public void clear() {
         stateLock.lock();
         try {
             internalQueue.clear();
             congestedFlowIdsDrop.clear(); // Clear feedback state
             if (congestionDetected) {
                 if (LOGGING_ENABLED) System.out.println("AIFOQueue: Cleared while congestion was active. Resetting state.");
                 congestionDetected = false;
             }
         } finally {
             stateLock.unlock();
         }
    }

    // --- Watermark Checks and Signaling (Identical to CanonicalSPPIFOQueue) ---

    private void checkForCongestion() {
        stateLock.lock();
        try {
            if (!congestionDetected && internalQueue.size() >= highWaterMarkPackets) {
                congestionDetected = true;
                if (LOGGING_ENABLED) System.out.println("AIFOQueue Congestion START: Size=" + internalQueue.size() + " >= HWM=" + highWaterMarkPackets);
                ICongestionSignaler currentSignaler = this.signaler;
                if (currentSignaler != null) currentSignaler.signalCongestionStart();
            }
        } finally { stateLock.unlock(); }
    }

    private void checkForDecongestion() {
        stateLock.lock();
        try {
            if (congestionDetected && internalQueue.size() <= lowWaterMarkPackets) {
                congestionDetected = false;
                if (LOGGING_ENABLED) System.out.println("AIFOQueue Congestion END: Size=" + internalQueue.size() + " <= LWM=" + lowWaterMarkPackets);
                ICongestionSignaler currentSignaler = this.signaler;
                if (currentSignaler != null) currentSignaler.signalCongestionEnd();
            }
        } finally { stateLock.unlock(); }
    }

    private void checkWatermarks() {
        stateLock.lock();
        try {
            long currentSize = internalQueue.size();
            if (!congestionDetected && currentSize >= highWaterMarkPackets) {
                congestionDetected = true;
                if (LOGGING_ENABLED) System.out.println("AIFOQueue Congestion START signaled: Size=" + currentSize + " >= HWM=" + highWaterMarkPackets);
                ICongestionSignaler cs = this.signaler; if (cs != null) cs.signalCongestionStart();
            } else if (congestionDetected && currentSize <= lowWaterMarkPackets) {
                congestionDetected = false;
                if (LOGGING_ENABLED) System.out.println("AIFOQueue Congestion END signaled: Size=" + currentSize + " <= LWM=" + lowWaterMarkPackets);
                ICongestionSignaler cs = this.signaler; if (cs != null) cs.signalCongestionEnd();
            }
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Updates the internal set of flows whose packets should be dropped upon offer.
     * An empty set clears this dropping behavior.
     * @param congestedFlowIds A set of flow identifiers identified as congested by the port.
     */
    @Override
    public void applyCongestionFeedback(Set<Long> congestedFlowIds) {
         Objects.requireNonNull(congestedFlowIds);
         // Update the set used for dropping
         this.congestedFlowIdsDrop.clear();
         if (congestedFlowIds != null && !congestedFlowIds.isEmpty()) {
              this.congestedFlowIdsDrop.addAll(congestedFlowIds);
              if (LOGGING_ENABLED) {
                  System.out.println("AIFOQueue: Applying drop policy to flows: " + congestedFlowIds);
              }
         } else {
              if (LOGGING_ENABLED) {
                 // System.out.println("AIFOQueue: Cleared drop policy.");
              }
         }
    }

    /** Returns true if the queue currently considers itself in a congested state based on watermarks. */
    public boolean isCongestionDetected() {
        return congestionDetected;
    }
}