package ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl;

import ch.ethz.systems.netbench.core.Simulator;
import ch.ethz.systems.netbench.core.config.NBProperties;
import ch.ethz.systems.netbench.core.log.SimulationLogger;
import ch.ethz.systems.netbench.core.network.Packet;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.core.MIDST;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.ICongestionSignaler;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.IMonitoredQueue;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.port.MIDSTCapableOutputPort;
import ch.ethz.systems.netbench.xpt.tcpbase.PriorityHeader;

import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Real AIFO (Admit or Ignore, First-Out) Queue with MIDST integration.
 *
 * Based on the official AIFO implementation from:
 * "Programmable Packet Scheduling with a Single Queue" (SIGCOMM 2021)
 * https://github.com/netx-repo/AIFO
 *
 * AIFO Algorithm:
 * 1. Maintains a sliding window of recent packet ranks
 * 2. Uses quantile comparison for admission control
 * 3. Admits packet if: (queueSize <= k*maxQueue) OR (rank passes quantile test)
 * 4. Uses a single FIFO queue for accepted packets
 *
 * MIDST Twist:
 * When MIDST identifies a flow as bursty, we inflate its rank by a penalty.
 * This makes bursty flows more likely to be dropped by the quantile comparison.
 */
public class RealAIFOQueue extends AbstractQueue<Packet> implements IMonitoredQueue {

    private static boolean LOGGING_ENABLED = true;

    // Core AIFO parameters
    private final float k;                    // Admission threshold factor (0.1 - 0.5)
    private final int windowSize;             // Sliding window size
    private final int sampleCount;            // Sample rate for window updates
    private final long maxQueueSizePackets;   // Maximum queue capacity (C in paper)

    // MIDST parameters
    private final long highWaterMarkPackets;
    private final long lowWaterMarkPackets;
    private final long rankPenalty;           // Rank inflation for bursty flows

    // Virtual fill level for bursty flow admission
    private final boolean useVirtualFillLevel;

    // Congestion detection mode for MIDST integration
    private final boolean useRejectionRateDetection;  // true = rejection rate, false = queue depth
    private final double rejectionRateThreshold;      // Threshold for congestion (e.g., 0.05 = 5%)
    private final int rejectionWindowSize;            // Window size for rejection rate calculation

    // Rejection rate tracking (sliding window)
    private final LinkedList<Boolean> recentDecisions; // true = admitted, false = rejected
    private int recentAdmissions = 0;
    private int recentRejections = 0;

    // Internal state
    private final Queue<Packet> buffQueue;    // Main FIFO queue for accepted packets
    private final LinkedList<Packet> qWindow; // Sliding window for quantile computation
    private int sampleCounter;                // Counter for sampling

    // MIDST integration
    private volatile boolean congestionDetected = false;
    private ICongestionSignaler signaler = null;
    private final Set<Long> burstyFlowIds = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private final ReentrantLock stateLock = new ReentrantLock();

    // NEW: Direct reference to MIDST module for virtual queue-based bursty detection
    private MIDST midstModule = null;

    // Statistics
    private long packetsDroppedByAIFO = 0;
    private long packetsDroppedDueToBursty = 0;
    private long packetsAdmitted = 0;

    /**
     * Constructor with all parameters including MIDST congestion detection mode.
     *
     * @param k                          Admission threshold factor (recommended: 0.1-0.5)
     * @param windowSize                 Sliding window size for quantile computation
     * @param sampleCount                Sample rate (1 = every packet, N = every Nth packet)
     * @param maxQueueSizePackets        Maximum queue capacity in packets
     * @param highWaterMarkPackets       MIDST high watermark for congestion detection (queue depth mode)
     * @param lowWaterMarkPackets        MIDST low watermark for congestion end (queue depth mode)
     * @param rankPenalty                Rank inflation for bursty flows (MIDST twist)
     * @param useRejectionRateDetection  true = detect congestion via rejection rate, false = queue depth
     * @param rejectionRateThreshold     Rejection rate threshold for congestion (e.g., 0.05 = 5%)
     * @param rejectionWindowSize        Window size for rejection rate calculation
     */
    public RealAIFOQueue(float k, int windowSize, int sampleCount, long maxQueueSizePackets,
                         long highWaterMarkPackets, long lowWaterMarkPackets, long rankPenalty,
                         boolean useRejectionRateDetection, double rejectionRateThreshold,
                         int rejectionWindowSize,
                         boolean useVirtualFillLevel) {

        if (k < 0 || k > 1) {
            throw new IllegalArgumentException("k must be between 0 and 1, got: " + k);
        }
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be positive, got: " + windowSize);
        }

        this.k = k;
        this.windowSize = windowSize;
        this.sampleCount = Math.max(1, sampleCount);
        this.maxQueueSizePackets = maxQueueSizePackets;
        this.highWaterMarkPackets = highWaterMarkPackets;
        this.lowWaterMarkPackets = lowWaterMarkPackets;
        this.rankPenalty = rankPenalty;

        // Virtual fill level for bursty flow admission
        this.useVirtualFillLevel = useVirtualFillLevel;

        // MIDST congestion detection mode
        this.useRejectionRateDetection = useRejectionRateDetection;
        this.rejectionRateThreshold = rejectionRateThreshold;
        this.rejectionWindowSize = rejectionWindowSize;
        this.recentDecisions = new LinkedList<>();

        this.buffQueue = new LinkedList<>();
        this.qWindow = new LinkedList<>();
        this.sampleCounter = 0;

        // Initialize logging
        NBProperties config = Simulator.getConfiguration();
        if (config != null) {
            LOGGING_ENABLED = config.getBooleanPropertyWithDefault("midst_real_aifo_logging_enabled", false);
        }

        if (LOGGING_ENABLED) {
            System.out.println("RealAIFOQueue created: k=" + k + ", windowSize=" + windowSize +
                             ", sampleCount=" + sampleCount + ", maxQueue=" + maxQueueSizePackets +
                             ", rankPenalty=" + rankPenalty +
                             ", useRejectionRateDetection=" + useRejectionRateDetection +
                             ", rejectionRateThreshold=" + rejectionRateThreshold);
        }
    }

    /**
     * Backward-compatible constructor (uses queue depth detection by default).
     */
    public RealAIFOQueue(float k, int windowSize, int sampleCount, long maxQueueSizePackets,
                         long highWaterMarkPackets, long lowWaterMarkPackets, long rankPenalty) {
        this(k, windowSize, sampleCount, maxQueueSizePackets, highWaterMarkPackets,
             lowWaterMarkPackets, rankPenalty,
             false, 0.05, 1000,  // Default: queue depth detection
             false);             // Default: no virtual fill level
    }

    @Override
    public void setCongestionSignaler(ICongestionSignaler signaler) {
        this.signaler = signaler;
        // NEW: If the signaler is a MIDSTCapableOutputPort, get the MIDST module
        // This allows us to query bursty flow status directly via virtual queue trigger
        if (signaler instanceof MIDSTCapableOutputPort) {
            this.midstModule = ((MIDSTCapableOutputPort) signaler).getMIDST();
            if (LOGGING_ENABLED) {
                SimulationLogger.logInfo("AIFO_QUEUE", "MIDST module connected - using virtual queue trigger");
            }
        }
    }

    /**
     * AIFO admission control with MIDST twist.
     *
     * @param packet The packet to potentially admit
     * @return true if admitted, false if dropped
     */
    @Override
    public boolean offer(Packet packet) {
        Objects.requireNonNull(packet, "Cannot offer null packet");

        stateLock.lock();
        try {
            long flowId = packet.getFlowId();

            // Get the packet's rank (priority)
            long baseRank = Long.MAX_VALUE;
            if (packet instanceof PriorityHeader) {
                baseRank = ((PriorityHeader) packet).getPriority();
            }

            // =========================================================================
            // NEW: Query MIDST module directly for bursty flow status
            // This uses the virtual queue trigger which works for ALL schedulers.
            // Falls back to callback-based burstyFlowIds if MIDST not available.
            // =========================================================================
            boolean isBurstyFlow;
            if (midstModule != null) {
                // NEW: Use virtual queue-based bursty detection
                isBurstyFlow = midstModule.isFlowBursty(flowId);
                if (LOGGING_ENABLED && isBurstyFlow) {
                    SimulationLogger.logInfo("AIFO_MIDST_QUERY",
                        "Flow=" + flowId + ",Bursty=true,VirtualQ=" + midstModule.getVirtualQueueDepthBits() +
                        ",Monitoring=" + midstModule.isMonitoring() + ",Time=" + Simulator.getCurrentTime());
                }
            } else {
                // Fallback: Use callback-based bursty flow set
                isBurstyFlow = burstyFlowIds.contains(flowId);
            }

            // MIDST TWIST: Inflate rank for bursty flows
            long effectiveRank = baseRank;
            if (isBurstyFlow) {
                effectiveRank = baseRank + rankPenalty;
                if (LOGGING_ENABLED) {
                    System.out.println("RealAIFO: Flow " + flowId + " is bursty, inflating rank from " +
                                     baseRank + " to " + effectiveRank);
                }
            }

            // Current queue occupancy
            int currentQueueSize = buffQueue.size();

            // For bursty flows with virtual fill level enabled, use the virtual queue
            // depth instead of the real queue size for the admission gate.
            int effectiveQueueSize;
            if (isBurstyFlow && useVirtualFillLevel && midstModule != null) {
                long virtualQueuePackets = midstModule.getVirtualQueueDepthBits() / (1500L * 8L);
                effectiveQueueSize = (int) Math.min(virtualQueuePackets, maxQueueSizePackets);
                if (LOGGING_ENABLED) {
                    SimulationLogger.logInfo("AIFO_VIRTUAL_FILL",
                        "Flow=" + flowId + ",RealSize=" + currentQueueSize +
                        ",VirtualSize=" + effectiveQueueSize +
                        ",MaxSize=" + maxQueueSizePackets +
                        ",Time=" + Simulator.getCurrentTime());
                }
            } else {
                effectiveQueueSize = currentQueueSize;
            }

            // AIFO Admission Decision:
            // Admit if: (queueSize <= k * maxQueue) OR (quantile test passes)
            boolean admitByThreshold = (effectiveQueueSize <= k * maxQueueSizePackets);

            // Calculate quantile threshold: (1/(1-k)) * (maxQueue - queueSize) / maxQueue
            double quantileThreshold = (1.0 / (1.0 - k)) * (maxQueueSizePackets - effectiveQueueSize) / maxQueueSizePackets;
            boolean admitByQuantile = compareQuantile((int) effectiveRank, quantileThreshold);

            boolean shouldAdmit = admitByThreshold || admitByQuantile;

            // Also check if queue has space
            boolean hasSpace = (currentQueueSize + 1 <= maxQueueSizePackets);

            if (shouldAdmit && hasSpace) {
                // ADMIT the packet
                buffQueue.offer(packet);
                packetsAdmitted++;

                // Log bursty vs normal packet for MIDST statistics
                if (isBurstyFlow) {
                    SimulationLogger.increaseStatisticCounter("MIDST_BURSTY_PACKETS_ENQUEUED");
                } else {
                    SimulationLogger.increaseStatisticCounter("MIDST_NORMAL_PACKETS_ENQUEUED");
                }

                // Update sliding window (sample-based)
                updateSlidingWindow(packet);

                // Track admission decision for rejection-rate detection
                trackAdmissionDecision(true);

                // Check MIDST congestion (mode-dependent)
                checkCongestion();

                if (LOGGING_ENABLED && packetsAdmitted % 10000 == 0) {
                    System.out.println("RealAIFO stats: admitted=" + packetsAdmitted +
                                     ", droppedAIFO=" + packetsDroppedByAIFO +
                                     ", droppedBursty=" + packetsDroppedDueToBursty);
                }

                return true;
            } else {
                // DROP the packet
                packetsDroppedByAIFO++;
                if (isBurstyFlow) {
                    packetsDroppedDueToBursty++;
                }

                // C3c: Functional inversion tracking — count queued packets with worse rank
                // than the dropped packet (those packets "shouldn't be there" in a perfect scheduler)
                if (SimulationLogger.hasInversionsTrackingEnabled()) {
                    int functionalInversions = 0;
                    for (Packet qp : buffQueue) {
                        if (qp instanceof PriorityHeader) {
                            long qRank = ((PriorityHeader) qp).getPriority();
                            if (qRank > baseRank) { // Use baseRank (pre-penalty) for fair comparison
                                functionalInversions++;
                            }
                        }
                    }
                    if (functionalInversions > 0) {
                        SimulationLogger.logInversionsPerRank(
                            System.identityHashCode(this) % 100000,
                            (int) baseRank, functionalInversions);
                    }
                }

                // Track rejection decision for rejection-rate detection
                trackAdmissionDecision(false);

                // Check MIDST congestion (mode-dependent) - rejection may trigger congestion!
                checkCongestion();

                SimulationLogger.increaseStatisticCounter("PACKETS_DROPPED");
                SimulationLogger.increaseStatisticCounter("PACKETS_DROPPED_AIFO");
                if (isBurstyFlow) {
                    SimulationLogger.increaseStatisticCounter("PACKETS_DROPPED_AIFO_BURSTY");
                }

                if (LOGGING_ENABLED) {
                    System.out.println("RealAIFO: DROPPED packet from flow " + flowId +
                                     " (rank=" + effectiveRank + ", bursty=" + isBurstyFlow +
                                     ", qSize=" + currentQueueSize + ", threshold=" + admitByThreshold +
                                     ", quantile=" + admitByQuantile + ")");
                }

                return false;
            }
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Quantile comparison for admission control.
     * Returns true if the packet's rank is "good enough" compared to the sliding window.
     *
     * @param rank     The packet's effective rank (lower is better)
     * @param quantile The quantile threshold (0 to 1+)
     * @return true if packet should be admitted based on quantile
     */
    private boolean compareQuantile(int rank, double quantile) {
        if (qWindow.isEmpty()) {
            return true;  // Empty window = admit everything
        }

        // Count packets in window with LOWER priority (higher rank) than this packet
        int betterCount = 0;
        for (Packet p : qWindow) {
            if (p instanceof PriorityHeader) {
                long windowRank = ((PriorityHeader) p).getPriority();
                if (rank > windowRank) {
                    betterCount++;
                }
            }
        }

        // Admit if this packet is NOT in the worst (quantile * windowSize) packets
        // i.e., fewer than (quantile * windowSize) packets have better rank
        return (betterCount * 1.0 < quantile * qWindow.size());
    }

    /**
     * Update the sliding window with sampled packets.
     */
    private void updateSlidingWindow(Packet packet) {
        sampleCounter++;
        if (sampleCounter >= sampleCount) {
            sampleCounter = 0;

            if (qWindow.size() >= windowSize) {
                qWindow.poll();  // Remove oldest
            }
            qWindow.offer(packet);  // Add newest
        }
    }

    @Override
    public Packet poll() {
        stateLock.lock();
        try {
            Packet packet = buffQueue.poll();
            if (packet != null) {
                checkCongestion();
            }
            return packet;
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public Packet peek() {
        return buffQueue.peek();
    }

    @Override
    public int size() {
        return buffQueue.size();
    }

    @Override
    public Iterator<Packet> iterator() {
        return buffQueue.iterator();
    }

    @Override
    public void clear() {
        stateLock.lock();
        try {
            buffQueue.clear();
            qWindow.clear();
            burstyFlowIds.clear();
            sampleCounter = 0;
            if (congestionDetected) {
                congestionDetected = false;
            }
            // Reset rejection rate tracking
            recentDecisions.clear();
            recentAdmissions = 0;
            recentRejections = 0;
        } finally {
            stateLock.unlock();
        }
    }

    // --- Rejection Rate Tracking ---

    /**
     * Track admission decision in sliding window for rejection rate calculation.
     */
    private void trackAdmissionDecision(boolean admitted) {
        if (!useRejectionRateDetection) {
            return;  // Only track if using rejection rate detection
        }

        // Add new decision
        recentDecisions.add(admitted);
        if (admitted) {
            recentAdmissions++;
        } else {
            recentRejections++;
        }

        // Remove old decisions if window is full
        while (recentDecisions.size() > rejectionWindowSize) {
            boolean oldDecision = recentDecisions.poll();
            if (oldDecision) {
                recentAdmissions--;
            } else {
                recentRejections--;
            }
        }
    }

    /**
     * Calculate current rejection rate.
     */
    private double getCurrentRejectionRate() {
        int total = recentAdmissions + recentRejections;
        if (total == 0) {
            return 0.0;
        }
        return (double) recentRejections / total;
    }

    // --- MIDST Congestion Detection (Mode-Dependent) ---

    /**
     * Check for congestion using the configured detection mode.
     * Mode 1 (queue depth): Signal when queue exceeds high watermark
     * Mode 2 (rejection rate): Signal when rejection rate exceeds threshold
     */
    private void checkCongestion() {
        if (useRejectionRateDetection) {
            checkRejectionRateCongestion();
        } else {
            checkQueueDepthCongestion();
        }
    }

    /**
     * Original queue-depth based congestion detection.
     */
    private void checkQueueDepthCongestion() {
        int currentSize = buffQueue.size();

        if (!congestionDetected && currentSize >= highWaterMarkPackets) {
            congestionDetected = true;
            if (LOGGING_ENABLED) {
                System.out.println("RealAIFO: Congestion START [QueueDepth] (size=" + currentSize +
                                 " >= HWM=" + highWaterMarkPackets + ")");
            }
            if (signaler != null) {
                signaler.signalCongestionStart();
            }
        } else if (congestionDetected && currentSize <= lowWaterMarkPackets) {
            congestionDetected = false;
            if (LOGGING_ENABLED) {
                System.out.println("RealAIFO: Congestion END [QueueDepth] (size=" + currentSize +
                                 " <= LWM=" + lowWaterMarkPackets + ")");
            }
            if (signaler != null) {
                signaler.signalCongestionEnd();
            }
        }
    }

    /**
     * Rejection-rate based congestion detection for AIFO.
     * Signals congestion when AIFO is dropping a significant fraction of packets.
     */
    private void checkRejectionRateCongestion() {
        // Need minimum samples before making decisions
        if (recentDecisions.size() < rejectionWindowSize / 2) {
            return;
        }

        double rejectionRate = getCurrentRejectionRate();

        if (!congestionDetected && rejectionRate >= rejectionRateThreshold) {
            congestionDetected = true;
            SimulationLogger.increaseStatisticCounter("MIDST_AIFO_CONGESTION_DETECTED");
            if (LOGGING_ENABLED) {
                System.out.println("RealAIFO: Congestion START [RejectionRate] (rate=" +
                                 String.format("%.2f%%", rejectionRate * 100) +
                                 " >= threshold=" + String.format("%.2f%%", rejectionRateThreshold * 100) + ")");
            }
            if (signaler != null) {
                signaler.signalCongestionStart();
            }
        } else if (congestionDetected && rejectionRate < rejectionRateThreshold / 2) {
            // Use hysteresis: end congestion when rate drops to half of threshold
            congestionDetected = false;
            if (LOGGING_ENABLED) {
                System.out.println("RealAIFO: Congestion END [RejectionRate] (rate=" +
                                 String.format("%.2f%%", rejectionRate * 100) +
                                 " < threshold/2=" + String.format("%.2f%%", rejectionRateThreshold * 100 / 2) + ")");
            }
            if (signaler != null) {
                signaler.signalCongestionEnd();
            }
        }
    }

    /**
     * MIDST feedback: update the set of bursty flows.
     * Packets from bursty flows will have their rank inflated, making them more likely to be dropped.
     */
    @Override
    public void applyCongestionFeedback(Set<Long> congestedFlowIds) {
        Objects.requireNonNull(congestedFlowIds);

        burstyFlowIds.clear();
        if (!congestedFlowIds.isEmpty()) {
            burstyFlowIds.addAll(congestedFlowIds);
            if (LOGGING_ENABLED) {
                System.out.println("RealAIFO: MIDST feedback - " + congestedFlowIds.size() +
                                 " bursty flows will have rank inflated by " + rankPenalty);
            }
        }
    }

    public boolean isCongestionDetected() {
        return congestionDetected;
    }

    // --- Statistics ---

    public long getPacketsDroppedByAIFO() {
        return packetsDroppedByAIFO;
    }

    public long getPacketsDroppedDueToBursty() {
        return packetsDroppedDueToBursty;
    }

    public long getPacketsAdmitted() {
        return packetsAdmitted;
    }
}
