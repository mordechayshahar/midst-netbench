package ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl;

import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import ch.ethz.systems.netbench.core.Simulator;
import ch.ethz.systems.netbench.core.config.NBProperties;
import ch.ethz.systems.netbench.core.log.SimulationLogger;
import ch.ethz.systems.netbench.core.network.OutputPort;
import ch.ethz.systems.netbench.core.network.Packet;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.core.MIDST;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.ICongestionSignaler;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.IMonitoredQueue;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.port.MIDSTCapableOutputPort;
import ch.ethz.systems.netbench.xpt.tcpbase.PriorityHeader;

/**
 * A new implementation of SP-PIFO (Strict Priority Packet In First Out) logic
 * based on packet rank (priority) with multiple queues.
 * Each queue handles a specific rank range, and the last queue handles any rank above its minimum.
 * Uses packet count based watermarks for congestion signaling.
 */
public class NewCanonicalSPPIFOQueue extends AbstractQueue<Packet> implements IMonitoredQueue {

    private static boolean LOGGING_ENABLED = true;

    // Static map to track bursty status of flows
    private static final ConcurrentHashMap<Long, Boolean> flowIdToBursty = new ConcurrentHashMap<>();

    // Static method to register bursty status
    public static void registerFlowBurstyStatus(long flowId, boolean isBursty) {
        flowIdToBursty.put(flowId, isBursty);
    }

    /** C2: Query ground-truth bursty status (used by drop counters in MIDSTCapableOutputPort). */
    public static boolean isFlowBurstyGroundTruth(long flowId) {
        return flowIdToBursty.getOrDefault(flowId, false);
    }

    // Configuration parameters
    private final int numQueues;
    private final long rankDelta;
    private final long highWaterMarkPackets;
    private final long lowWaterMarkPackets;

    // Queue structure
    private final List<Queue<Packet>> queues;
    private final ReentrantLock stateLock = new ReentrantLock();
    private ICongestionSignaler signaler = null;

    // Statistics tracking
    private final AtomicLong enqueuedPackets = new AtomicLong(0);
    private final AtomicLong dequeuedPackets = new AtomicLong(0);
    private final AtomicLong droppedPackets = new AtomicLong(0);
    private volatile boolean congestionDetected = false;

    // MIDST feedback tracking
    private final Set<Long> congestedFlowIds = new HashSet<>();
    private final long congestionPenaltyRank;

    // Flag to track if we're already in congested state (for signaling)
    private volatile boolean signalerCongestionActive = false;

    // Per-queue statistics
    private final List<AtomicLong> queueEnqueuedPackets;
    private final List<AtomicLong> queueDequeuedPackets;
    private final List<AtomicLong> queueDroppedPackets;

    // Add OutputPort reference
    private OutputPort outputPort = null;
    // NEW: Direct reference to MIDST module for virtual queue-based bursty detection
    private MIDST midstModule = null;

    // C3a: Port identifier for inversion tracking (set via setOutputPort or setCongestionSignaler)
    private int portIdForInversions = 0;

    // =========================================================================
    // NEW METRICS TRACKING (All 15 metrics)
    // =========================================================================

    // HIGH #1: Queue Residence Time - track enqueue timestamps
    private final Map<Packet, Long> packetEnqueueTime = new ConcurrentHashMap<>();
    private final AtomicLong totalResidenceTimeNs = new AtomicLong(0);
    private final AtomicLong residenceTimePacketCount = new AtomicLong(0);

    // HIGH #2: Congestion Episode Duration
    private volatile long currentCongestionStartTime = 0;
    private final AtomicLong totalCongestionDurationNs = new AtomicLong(0);

    // HIGH #3: Bursty Flow FCT Tracking - static set to track which flows were ever penalized
    private static final Set<Long> everPenalizedFlows = ConcurrentHashMap.newKeySet();

    // HIGH #4: Packet Drop by Flow - track drops per flow
    private final Map<Long, AtomicLong> dropsPerFlow = new ConcurrentHashMap<>();

    // MED #6: Rank/Priority Distribution
    private final AtomicLong totalBaseRank = new AtomicLong(0);
    private final AtomicLong totalFinalRank = new AtomicLong(0);
    private final AtomicLong rankSampleCount = new AtomicLong(0);

    // MED #7: Per-Queue Dequeue Count (already have queueDequeuedPackets, just need to use it)

    // MED #8: Penalty Duration per Flow
    private final Map<Long, Long> flowPenaltyStartTime = new ConcurrentHashMap<>();
    private final AtomicLong totalPenaltyDurationNs = new AtomicLong(0);
    private final AtomicLong penaltyDurationFlowCount = new AtomicLong(0);

    // MED #10: Concurrent Penalized Flows - track max
    private final AtomicLong maxConcurrentPenalizedFlows = new AtomicLong(0);

    /**
     * Constructor.
     * @param highWaterMarkPackets Congestion threshold in packet count.
     * @param lowWaterMarkPackets  Decongestion threshold in packet count.
     * @param rankDelta           Range of ranks per queue.
     * @param numQueues           Number of priority queues.
     * @param congestionPenaltyRank Penalty rank to apply to congested flows.
     */
    public NewCanonicalSPPIFOQueue(long highWaterMarkPackets, long lowWaterMarkPackets, long rankDelta, int numQueues, long congestionPenaltyRank) {
        // Input validation
        if (lowWaterMarkPackets >= highWaterMarkPackets) {
            if (lowWaterMarkPackets > highWaterMarkPackets) {
                throw new IllegalArgumentException("Low water mark packets ("+lowWaterMarkPackets+") cannot be greater than high water mark packets ("+highWaterMarkPackets+").");
            } else if (highWaterMarkPackets > 0) {
                System.err.println("Warning: NewCanonicalSPPIFOQueue Low water mark packets ("+lowWaterMarkPackets+") equals high water mark ("+highWaterMarkPackets+"). Signaling might be unstable.");
            }
        }

        // Get configuration
        NBProperties config = Simulator.getConfiguration();
        this.numQueues = numQueues;
        this.rankDelta = rankDelta;
        this.congestionPenaltyRank = congestionPenaltyRank;
        this.highWaterMarkPackets = highWaterMarkPackets;
        this.lowWaterMarkPackets = lowWaterMarkPackets;

        // Initialize queues
        this.queues = new ArrayList<>(numQueues);
        this.queueEnqueuedPackets = new ArrayList<>(numQueues);
        this.queueDequeuedPackets = new ArrayList<>(numQueues);
        this.queueDroppedPackets = new ArrayList<>(numQueues);
        
        for (int i = 0; i < numQueues; i++) {
            this.queues.add(new LinkedList<>());
            this.queueEnqueuedPackets.add(new AtomicLong(0));
            this.queueDequeuedPackets.add(new AtomicLong(0));
            this.queueDroppedPackets.add(new AtomicLong(0));
        }

        // Initialize logging
        if (config != null) {
            LOGGING_ENABLED = config.getBooleanPropertyWithDefault("midst_sppifo_queue_internal_logging_enabled", false);
        }
        if (LOGGING_ENABLED) {
            System.out.println("NewCanonicalSPPIFOQueue created: " +
                             "Queues=" + numQueues + 
                             ", RankDelta=" + rankDelta + 
                             ", HWM=" + highWaterMarkPackets + 
                             ", LWM=" + lowWaterMarkPackets +
                             ", PenaltyRank=" + congestionPenaltyRank);
        }
    }

    @Override
    public void setCongestionSignaler(ICongestionSignaler signaler) {
        this.signaler = signaler;
        // NEW: If the signaler is a MIDSTCapableOutputPort, get the MIDST module
        // This allows us to query bursty flow status directly via virtual queue trigger
        if (signaler instanceof MIDSTCapableOutputPort) {
            this.midstModule = ((MIDSTCapableOutputPort) signaler).getMIDST();
            if (LOGGING_ENABLED) {
                SimulationLogger.logInfo("SPPIFO_QUEUE", "MIDST module connected - using virtual queue trigger");
            }
        }
    }

    /**
     * Determines which queue a packet should go to based on its rank.
     * @param rank The packet's rank
     * @return The index of the queue to use
     */
    private int getQueueIndex(long rank) {
        // Lower ranks go to higher priority queues (queue 0)
        // Higher ranks go to lower priority queues (queue numQueues-1)
        // For example, if rankDelta is 1000 and numQueues is 5:
        // rank 0-999 -> queue 0 (highest priority)
        // rank 1000-1999 -> queue 1
        // rank 2000-2999 -> queue 2
        // rank 3000-3999 -> queue 3
        // rank 4000+ -> queue 4 (lowest priority)
        return Math.min((int)(rank / rankDelta), numQueues - 1);
    }

    @Override
    public boolean offer(Packet packet) {
        Objects.requireNonNull(packet, "Cannot offer null packet");
        long flowId = packet.getFlowId();
        long baseRank = 0;
        if (packet instanceof PriorityHeader) {
            baseRank = ((PriorityHeader) packet).getPriority();
        } else {
            baseRank = Long.MAX_VALUE - 1; // Assign low priority if no header
        }

        // =========================================================================
        // NEW: Query MIDST module directly for bursty flow status
        // This uses the virtual queue trigger which works for ALL schedulers.
        // Falls back to callback-based congestedFlowIds if MIDST not available.
        // =========================================================================
        boolean isPenalized;
        if (midstModule != null) {
            // NEW: Use virtual queue-based bursty detection
            isPenalized = midstModule.isFlowBursty(flowId);
            if (LOGGING_ENABLED && isPenalized) {
                SimulationLogger.logInfo("SPPIFO_MIDST_QUERY",
                    "Flow=" + flowId + ",Bursty=true,VirtualQ=" + midstModule.getVirtualQueueDepthBits() +
                    ",Monitoring=" + midstModule.isMonitoring() + ",Time=" + Simulator.getCurrentTime());
            }
        } else {
            // Fallback: Use callback-based congested flow set
            isPenalized = congestedFlowIds.contains(flowId);
        }

        // Calculate final rank (base + penalty)
        long finalRank = baseRank;
        if (isPenalized) {
            finalRank += congestionPenaltyRank;
            // Log penalized packet
            SimulationLogger.logInfo("MIDST_PENALTY",
                Simulator.getCurrentTime() + "," +
                flowId + "," +
                baseRank + "," +
                congestionPenaltyRank + "," +
                finalRank
            );
        }

        // MED #6: Track rank distribution
        totalBaseRank.addAndGet(baseRank);
        totalFinalRank.addAndGet(finalRank);
        rankSampleCount.incrementAndGet();

        // Find appropriate queue based on rank
        int queueIndex = getQueueIndex(finalRank);
        Queue<Packet> targetQueue = queues.get(queueIndex);
        long maxQueueSize = highWaterMarkPackets;

        // Try to add to assigned queue first
        if (targetQueue.size() < maxQueueSize) {
            targetQueue.offer(packet);
            enqueuedPackets.incrementAndGet();
            queueEnqueuedPackets.get(queueIndex).incrementAndGet();

            // HIGH #1: Track enqueue time for residence time calculation
            packetEnqueueTime.put(packet, Simulator.getCurrentTime());

            // Log per-queue statistics
            SimulationLogger.increaseStatisticCounter("SPPIFO_QUEUE_" + queueIndex + "_ENQUEUED");

            // Log bursty vs normal packet
            if (isPenalized) {
                SimulationLogger.increaseStatisticCounter("MIDST_BURSTY_PACKETS_ENQUEUED");
            } else {
                SimulationLogger.increaseStatisticCounter("MIDST_NORMAL_PACKETS_ENQUEUED");
            }

            checkForCongestion(); // Signal congestion if we've reached HWM
            return true;
        }

        // If assigned queue is full, try lower priority queues
        for (int i = queueIndex + 1; i < numQueues; i++) {
            Queue<Packet> lowerQueue = queues.get(i);
            long lowerMaxQueueSize = highWaterMarkPackets;
            if (lowerQueue.size() < lowerMaxQueueSize) {
                // Add to head of lower priority queue
                if (lowerQueue instanceof LinkedList) {
                    ((LinkedList<Packet>) lowerQueue).addFirst(packet);
                } else {
                    lowerQueue.offer(packet);
                }
                enqueuedPackets.incrementAndGet();
                queueEnqueuedPackets.get(i).incrementAndGet();

                // HIGH #1: Track enqueue time for residence time calculation
                packetEnqueueTime.put(packet, Simulator.getCurrentTime());

                // Log per-queue statistics (pushed to lower queue)
                SimulationLogger.increaseStatisticCounter("SPPIFO_QUEUE_" + i + "_ENQUEUED");
                SimulationLogger.increaseStatisticCounter("SPPIFO_PUSHED_TO_LOWER_QUEUE");

                // Log bursty vs normal packet
                if (isPenalized) {
                    SimulationLogger.increaseStatisticCounter("MIDST_BURSTY_PACKETS_ENQUEUED");
                } else {
                    SimulationLogger.increaseStatisticCounter("MIDST_NORMAL_PACKETS_ENQUEUED");
                }

                checkForCongestion(); // Signal congestion if we've reached HWM
                return true;
            }
        }

        // If no queue has space, drop the packet
        droppedPackets.incrementAndGet();
        queueDroppedPackets.get(queueIndex).incrementAndGet();

        // HIGH #4: Track drops per flow
        dropsPerFlow.computeIfAbsent(flowId, k -> new AtomicLong(0)).incrementAndGet();
        SimulationLogger.increaseStatisticCounter("SPPIFO_DROPS_TOTAL");
        SimulationLogger.logInfo("SPPIFO_DROP_FLOW", flowId + "," + Simulator.getCurrentTime() + "," + queueIndex);

        return false;
    }

    /**
     * Checks if the queue has reached high water mark and signals congestion start.
     * This is called after successfully adding a packet.
     */
    private void checkForCongestion() {
        stateLock.lock();
        try {
            int totalSize = size();
            if (!signalerCongestionActive && totalSize >= highWaterMarkPackets) {
                signalerCongestionActive = true;

                // HIGH #2: Record congestion start time for duration tracking
                currentCongestionStartTime = Simulator.getCurrentTime();

                // Track congestion episodes
                SimulationLogger.increaseStatisticCounter("MIDST_CONGESTION_EPISODES");

                // Log congestion start with timestamp for duration analysis
                SimulationLogger.logInfo("CONGESTION_EPISODE_START",
                    currentCongestionStartTime + "," + totalSize);

                if (LOGGING_ENABLED) {
                    System.out.println("NewCanonicalSPPIFOQueue Congestion START: Size=" + totalSize + " >= HWM=" + highWaterMarkPackets);
                    SimulationLogger.logInfo("SPPIFO_CONGESTION_START",
                        "Size=" + totalSize + ",HWM=" + highWaterMarkPackets + ",Time=" + Simulator.getCurrentTime());
                }
                if (signaler != null) {
                    signaler.signalCongestionStart();
                } else if (LOGGING_ENABLED) {
                    System.out.println("NewCanonicalSPPIFOQueue: Signaler not set, cannot signal start.");
                }
            }
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Checks if the queue has dropped to low water mark and signals congestion end.
     * This is called after removing a packet.
     */
    private void checkForDecongestion() {
        stateLock.lock();
        try {
            int totalSize = size();
            if (signalerCongestionActive && totalSize <= lowWaterMarkPackets) {
                signalerCongestionActive = false;

                // HIGH #2: Calculate congestion duration
                long congestionEndTime = Simulator.getCurrentTime();
                if (currentCongestionStartTime > 0) {
                    long congestionDuration = congestionEndTime - currentCongestionStartTime;
                    totalCongestionDurationNs.addAndGet(congestionDuration);

                    // Log congestion episode end with duration
                    SimulationLogger.logInfo("CONGESTION_EPISODE_END",
                        congestionEndTime + "," + congestionDuration + "," + totalSize);
                }
                currentCongestionStartTime = 0; // Reset for next episode

                if (LOGGING_ENABLED) {
                    System.out.println("NewCanonicalSPPIFOQueue Congestion END: Size=" + totalSize + " <= LWM=" + lowWaterMarkPackets);
                    SimulationLogger.logInfo("SPPIFO_CONGESTION_END",
                        "Size=" + totalSize + ",LWM=" + lowWaterMarkPackets + ",Time=" + Simulator.getCurrentTime());
                }
                if (signaler != null) {
                    signaler.signalCongestionEnd();
                } else if (LOGGING_ENABLED) {
                    System.out.println("NewCanonicalSPPIFOQueue: Signaler not set, cannot signal end.");
                }
            }
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public Packet poll() {
        stateLock.lock();
        try {
            // Try each queue in priority order
            for (int queueIndex = 0; queueIndex < queues.size(); queueIndex++) {
                Queue<Packet> queue = queues.get(queueIndex);
                Packet packet = queue.poll();
                if (packet != null) {
                    dequeuedPackets.incrementAndGet();
                    queueDequeuedPackets.get(queueIndex).incrementAndGet();

                    // MED #7: Log per-queue dequeue
                    SimulationLogger.increaseStatisticCounter("SPPIFO_QUEUE_" + queueIndex + "_DEQUEUED");

                    // C3a: Rank inversion tracking (same pattern as SPPIFOQueue)
                    if (SimulationLogger.hasInversionsTrackingEnabled()) {
                        long polledRank = (packet instanceof PriorityHeader) ?
                            ((PriorityHeader) packet).getPriority() : Long.MAX_VALUE;
                        int inversions = 0;
                        for (int qi = 0; qi < queues.size(); qi++) {
                            for (Packet p : queues.get(qi)) {
                                if (p instanceof PriorityHeader) {
                                    long r = ((PriorityHeader) p).getPriority();
                                    if (r < polledRank) {
                                        inversions++;
                                    }
                                }
                            }
                        }
                        if (inversions > 0) {
                            SimulationLogger.logInversionsPerRank(portIdForInversions, (int) polledRank, inversions);
                        }
                    }

                    // HIGH #1: Calculate and track queue residence time
                    Long enqueueTime = packetEnqueueTime.remove(packet);
                    if (enqueueTime != null) {
                        long residenceTime = Simulator.getCurrentTime() - enqueueTime;
                        totalResidenceTimeNs.addAndGet(residenceTime);
                        residenceTimePacketCount.incrementAndGet();
                        // Log individual residence time for detailed analysis
                        SimulationLogger.logInfo("QUEUE_RESIDENCE_TIME",
                            packet.getFlowId() + "," + residenceTime + "," + queueIndex + "," + Simulator.getCurrentTime());
                    }

                    checkForDecongestion(); // Signal decongestion if we've dropped to LWM
                    return packet;
                }
            }
            return null;
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public Packet peek() {
        // Check queues in priority order
        for (Queue<Packet> queue : queues) {
            Packet packet = queue.peek();
            if (packet != null) {
                return packet;
            }
        }
        return null;
    }

    @Override
    public int size() {
        return queues.stream().mapToInt(Queue::size).sum();
    }

    @Override
    public Iterator<Packet> iterator() {
        // Create a combined iterator over all queues
        List<Iterator<Packet>> iterators = new ArrayList<>();
        for (Queue<Packet> queue : queues) {
            iterators.add(queue.iterator());
        }
        return new Iterator<Packet>() {
            private int currentIterator = 0;

            @Override
            public boolean hasNext() {
                while (currentIterator < iterators.size()) {
                    if (iterators.get(currentIterator).hasNext()) {
                        return true;
                    }
                    currentIterator++;
                }
                return false;
            }

            @Override
            public Packet next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return iterators.get(currentIterator).next();
            }
        };
    }

    @Override
    public void clear() {
        stateLock.lock();
        try {
            for (Queue<Packet> queue : queues) {
                queue.clear();
            }
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public void applyCongestionFeedback(Set<Long> congestedFlowIds) {
        SimulationLogger.logInfo("MIDST_DEBUG_APPLY_CONGESTION_FEEDBACK_ENTRY", "CalledWith=" + congestedFlowIds);
        long currentTime = Simulator.getCurrentTime();

        // MED #8: Calculate penalty duration for flows that are no longer penalized
        for (Long flowId : this.congestedFlowIds) {
            if (!congestedFlowIds.contains(flowId)) {
                // Flow penalty ended
                Long penaltyStartTime = flowPenaltyStartTime.remove(flowId);
                if (penaltyStartTime != null) {
                    long penaltyDuration = currentTime - penaltyStartTime;
                    totalPenaltyDurationNs.addAndGet(penaltyDuration);
                    penaltyDurationFlowCount.incrementAndGet();
                    SimulationLogger.logInfo("FLOW_PENALTY_END",
                        flowId + "," + penaltyDuration + "," + currentTime);
                }
            }
        }

        // Count unique flows being penalized and track penalty start time
        for (Long flowId : congestedFlowIds) {
            if (!this.congestedFlowIds.contains(flowId)) {
                SimulationLogger.increaseStatisticCounter("MIDST_UNIQUE_FLOWS_PENALIZED");

                // HIGH #3: Track this flow as ever penalized (for FCT tracking)
                everPenalizedFlows.add(flowId);
                SimulationLogger.logInfo("BURSTY_FLOW_IDENTIFIED", flowId + "," + currentTime);

                // MED #8: Record penalty start time for new penalties
                flowPenaltyStartTime.put(flowId, currentTime);
                SimulationLogger.logInfo("FLOW_PENALTY_START", flowId + "," + currentTime);
            }
        }

        // MED #10: Track max concurrent penalized flows
        int currentPenalizedCount = congestedFlowIds.size();
        long previousMax = maxConcurrentPenalizedFlows.get();
        while (currentPenalizedCount > previousMax) {
            if (maxConcurrentPenalizedFlows.compareAndSet(previousMax, currentPenalizedCount)) {
                SimulationLogger.logInfo("MAX_CONCURRENT_PENALIZED",
                    currentPenalizedCount + "," + currentTime);
                break;
            }
            previousMax = maxConcurrentPenalizedFlows.get();
        }

        // Log current concurrent count for time-series analysis
        SimulationLogger.logInfo("CONCURRENT_PENALIZED_FLOWS",
            currentPenalizedCount + "," + currentTime);

        this.congestedFlowIds.clear();
        this.congestedFlowIds.addAll(congestedFlowIds);
        if (LOGGING_ENABLED) {
            if (!congestedFlowIds.isEmpty()) {
                SimulationLogger.logInfo("MIDST_DEBUG_APPLY_CONGESTION_FEEDBACK", "PenalizedFlows=" + congestedFlowIds);
            } else {
                SimulationLogger.logInfo("MIDST_DEBUG_APPLY_CONGESTION_FEEDBACK", "PenalizedFlows=EMPTY");
            }
        }
    }

    /**
     * Static method to check if a flow was ever penalized (for FCT analysis)
     */
    public static boolean wasFlowEverPenalized(long flowId) {
        return everPenalizedFlows.contains(flowId);
    }

    /**
     * Static method to get all ever-penalized flows (for batch analysis)
     */
    public static Set<Long> getEverPenalizedFlows() {
        return new java.util.HashSet<>(everPenalizedFlows);
    }

    public boolean isCongestionDetected() {
        // Check if any queue is over threshold
        return queues.stream().anyMatch(q -> q.size() >= highWaterMarkPackets);
    }

    // Per-queue statistics methods
    public long getQueueEnqueuedPacketCount(int queueIndex) {
        return queueEnqueuedPackets.get(queueIndex).get();
    }

    public long getQueueDequeuedPacketCount(int queueIndex) {
        return queueDequeuedPackets.get(queueIndex).get();
    }

    public long getQueueDroppedPacketCount(int queueIndex) {
        return queueDroppedPackets.get(queueIndex).get();
    }

    public void resetQueueCounters() {
        for (int i = 0; i < numQueues; i++) {
            queueEnqueuedPackets.get(i).set(0);
            queueDequeuedPackets.get(i).set(0);
            queueDroppedPackets.get(i).set(0);
        }
    }

    // Statistics methods
    public void resetCounters() {
        enqueuedPackets.set(0);
        dequeuedPackets.set(0);
        droppedPackets.set(0);
    }

    public long getEnqueuedPacketCount() {
        return enqueuedPackets.get();
    }

    public long getDequeuedPacketCount() {
        return dequeuedPackets.get();
    }

    public long getDroppedPacketCount() {
        return droppedPackets.get();
    }

    /**
     * Rebalances packets by moving them back to their correct queues when possible.
     * This is called after dequeuing to maintain proper priority ordering.
     */
    private void rebalanceQueues() {
        // Start from highest priority queue (index 0)
        for (int i = 0; i < queues.size() - 1; i++) {
            Queue<Packet> currentQueue = queues.get(i);
            
            // If current queue is not full, check lower priority queues for packets that belong here
            if (currentQueue.size() < highWaterMarkPackets) {
                // Check all lower priority queues
                for (int j = i + 1; j < queues.size(); j++) {
                    Queue<Packet> lowerQueue = queues.get(j);
                    if (lowerQueue.isEmpty()) continue;
                    
                    // Get all packets from lower queue
                    List<Packet> packetsToMove = new ArrayList<>();
                    while (!lowerQueue.isEmpty()) {
                        Packet p = lowerQueue.poll();
                        if (p != null) {
                            packetsToMove.add(p);
                        }
                    }
                    
                    // Try to move packets back to their correct queues
                    for (Packet p : packetsToMove) {
                        long rank = ((PriorityHeader) p).getPriority();
                        int correctQueue = getQueueIndex(rank);
                        
                        if (correctQueue == i && currentQueue.size() < highWaterMarkPackets) {
                            // This packet belongs in current queue and there's space
                            currentQueue.offer(p);
                            if (LOGGING_ENABLED) {
                                SimulationLogger.logInfo("PACKET_REBALANCE",
                                    Simulator.getCurrentTime() + "," +
                                    p.getFlowId() + "," +
                                    rank + "," +
                                    j + "," +  // from queue
                                    i);        // to queue
                            }
                        } else {
                            // Put back in lower queue
                            lowerQueue.offer(p);
                        }
                    }
                }
            }
        }
    }

    // Add setter for OutputPort
    public void setOutputPort(OutputPort outputPort) {
        this.outputPort = outputPort;
        // C3a: Derive a port ID for inversion tracking
        if (outputPort != null) {
            this.portIdForInversions = System.identityHashCode(outputPort) % 100000;
        }
    }

    // =========================================================================
    // GETTER METHODS FOR NEW METRICS
    // =========================================================================

    // HIGH #1: Queue Residence Time getters
    public long getTotalResidenceTimeNs() {
        return totalResidenceTimeNs.get();
    }

    public long getResidenceTimePacketCount() {
        return residenceTimePacketCount.get();
    }

    public double getAverageResidenceTimeNs() {
        long count = residenceTimePacketCount.get();
        return count > 0 ? (double) totalResidenceTimeNs.get() / count : 0.0;
    }

    // HIGH #2: Congestion Duration getter
    public long getTotalCongestionDurationNs() {
        return totalCongestionDurationNs.get();
    }

    // MED #6: Rank Distribution getters
    public double getAverageBaseRank() {
        long count = rankSampleCount.get();
        return count > 0 ? (double) totalBaseRank.get() / count : 0.0;
    }

    public double getAverageFinalRank() {
        long count = rankSampleCount.get();
        return count > 0 ? (double) totalFinalRank.get() / count : 0.0;
    }

    public double getAverageRankPenalty() {
        long count = rankSampleCount.get();
        if (count == 0) return 0.0;
        return (double) (totalFinalRank.get() - totalBaseRank.get()) / count;
    }

    // MED #8: Penalty Duration getters
    public long getTotalPenaltyDurationNs() {
        return totalPenaltyDurationNs.get();
    }

    public long getPenaltyDurationFlowCount() {
        return penaltyDurationFlowCount.get();
    }

    public double getAveragePenaltyDurationNs() {
        long count = penaltyDurationFlowCount.get();
        return count > 0 ? (double) totalPenaltyDurationNs.get() / count : 0.0;
    }

    // MED #10: Concurrent Penalized Flows getter
    public long getMaxConcurrentPenalizedFlows() {
        return maxConcurrentPenalizedFlows.get();
    }

    // HIGH #4: Drops per flow getter
    public Map<Long, Long> getDropsPerFlow() {
        Map<Long, Long> result = new HashMap<>();
        for (Map.Entry<Long, AtomicLong> entry : dropsPerFlow.entrySet()) {
            result.put(entry.getKey(), entry.getValue().get());
        }
        return result;
    }

    /**
     * Log final statistics at end of simulation
     */
    public void logFinalStatistics() {
        // HIGH #1: Residence time
        SimulationLogger.logInfo("FINAL_METRIC_RESIDENCE_TIME",
            "AvgNs=" + getAverageResidenceTimeNs() +
            ",TotalNs=" + totalResidenceTimeNs.get() +
            ",PacketCount=" + residenceTimePacketCount.get());

        // HIGH #2: Congestion duration
        SimulationLogger.logInfo("FINAL_METRIC_CONGESTION_DURATION",
            "TotalNs=" + totalCongestionDurationNs.get());

        // MED #6: Rank distribution
        SimulationLogger.logInfo("FINAL_METRIC_RANK_DISTRIBUTION",
            "AvgBaseRank=" + getAverageBaseRank() +
            ",AvgFinalRank=" + getAverageFinalRank() +
            ",AvgPenalty=" + getAverageRankPenalty() +
            ",SampleCount=" + rankSampleCount.get());

        // MED #8: Penalty duration
        SimulationLogger.logInfo("FINAL_METRIC_PENALTY_DURATION",
            "AvgNs=" + getAveragePenaltyDurationNs() +
            ",TotalNs=" + totalPenaltyDurationNs.get() +
            ",FlowCount=" + penaltyDurationFlowCount.get());

        // MED #10: Max concurrent penalized
        SimulationLogger.logInfo("FINAL_METRIC_MAX_CONCURRENT_PENALIZED",
            "Max=" + maxConcurrentPenalizedFlows.get());

        // HIGH #3: Bursty flows count
        SimulationLogger.logInfo("FINAL_METRIC_BURSTY_FLOWS",
            "TotalEverPenalized=" + everPenalizedFlows.size());
    }
} 