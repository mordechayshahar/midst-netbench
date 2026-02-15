package ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl;

import ch.ethz.systems.netbench.core.Simulator;
import ch.ethz.systems.netbench.core.log.SimulationLogger;
import ch.ethz.systems.netbench.core.network.Packet;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.core.MIDST;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.ICongestionSignaler;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.IMonitoredQueue;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.port.MIDSTCapableOutputPort;
import ch.ethz.systems.netbench.xpt.tcpbase.PriorityHeader;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * PACKS Queue with MIDST integration.
 * Based on: "Everything Matters in Programmable Packet Scheduling" (NSDI 2025)
 *
 * Combines:
 * - Multi-queue scheduling (from SP-PIFO) for better rank ordering
 * - Quantile-based admission control (from AIFO) for smarter dropping
 */
public class RealPACKSQueue extends AbstractQueue<Packet> implements IMonitoredQueue {

    private static boolean LOGGING_ENABLED = true;

    // Multi-Queue Scheduling (SP-PIFO style)
    private final int numQueues;
    private final long rankDelta;
    private final List<Queue<Packet>> priorityQueues;

    // Admission Control (AIFO style)
    private final float k;
    private final int windowSize;
    private final int sampleCount;
    private final LinkedList<Packet> rankWindow;
    private int sampleCounter;

    // Queue Capacity
    private final long maxQueueSizePackets;

    // MIDST Integration
    private final long highWaterMarkPackets;
    private final long lowWaterMarkPackets;
    private final long rankPenalty;

    // Virtual fill level for bursty flow admission
    private final boolean useVirtualFillLevel;

    // Congestion detection
    private final boolean useRejectionRateDetection;
    private final double rejectionRateThreshold;
    private final int rejectionWindowSize;
    private final LinkedList<Boolean> recentDecisions;
    private int recentAdmissions = 0;
    private int recentRejections = 0;

    // MIDST state
    private volatile boolean congestionDetected = false;
    private ICongestionSignaler signaler = null;
    private final Set<Long> burstyFlowIds = ConcurrentHashMap.newKeySet();
    private final ReentrantLock stateLock = new ReentrantLock();

    // NEW: Direct reference to MIDST module for virtual queue-based bursty detection
    private MIDST midstModule = null;

    // Statistics
    private final AtomicLong packetsAdmitted = new AtomicLong(0);
    private final AtomicLong packetsDroppedByAdmission = new AtomicLong(0);
    private final AtomicLong packetsDroppedDueToBursty = new AtomicLong(0);

    public RealPACKSQueue(
            float k, int windowSize, int sampleCount,
            int numQueues, long rankDelta,
            long maxQueueSizePackets,
            long highWaterMarkPackets, long lowWaterMarkPackets, long rankPenalty,
            boolean useRejectionRateDetection, double rejectionRateThreshold, int rejectionWindowSize,
            boolean useVirtualFillLevel) {

        this.k = k;
        this.windowSize = windowSize;
        this.sampleCount = sampleCount;
        this.rankWindow = new LinkedList<>();
        this.sampleCounter = 0;

        this.numQueues = numQueues;
        this.rankDelta = rankDelta;
        this.priorityQueues = new ArrayList<>(numQueues);
        for (int i = 0; i < numQueues; i++) {
            priorityQueues.add(new LinkedBlockingQueue<>());
        }

        this.maxQueueSizePackets = maxQueueSizePackets;
        this.highWaterMarkPackets = highWaterMarkPackets;
        this.lowWaterMarkPackets = lowWaterMarkPackets;
        this.rankPenalty = rankPenalty;

        this.useVirtualFillLevel = useVirtualFillLevel;

        this.useRejectionRateDetection = useRejectionRateDetection;
        this.rejectionRateThreshold = rejectionRateThreshold;
        this.rejectionWindowSize = rejectionWindowSize;
        this.recentDecisions = new LinkedList<>();

        SimulationLogger.logInfo("PACKS_QUEUE", String.format(
            "Created: k=%.2f, numQueues=%d, rankDelta=%d, maxSize=%d",
            k, numQueues, rankDelta, maxQueueSizePackets));
    }

    // ========== IMonitoredQueue Interface ==========

    @Override
    public void setCongestionSignaler(ICongestionSignaler signaler) {
        this.signaler = signaler;
        // NEW: If the signaler is a MIDSTCapableOutputPort, get the MIDST module
        // This allows us to query bursty flow status directly via virtual queue trigger
        if (signaler instanceof MIDSTCapableOutputPort) {
            this.midstModule = ((MIDSTCapableOutputPort) signaler).getMIDST();
            if (LOGGING_ENABLED) {
                SimulationLogger.logInfo("PACKS_QUEUE", "MIDST module connected - using virtual queue trigger");
            }
        }
    }

    @Override
    public void applyCongestionFeedback(Set<Long> congestedFlowIds) {
        Objects.requireNonNull(congestedFlowIds);
        burstyFlowIds.clear();
        burstyFlowIds.addAll(congestedFlowIds);
        if (LOGGING_ENABLED && !congestedFlowIds.isEmpty()) {
            SimulationLogger.logInfo("PACKS_FEEDBACK", "Bursty flows: " + congestedFlowIds.size());
        }
    }

    // ========== Core Queue Operations ==========

    @Override
    public boolean offer(Packet packet) {
        if (packet == null) return false;

        stateLock.lock();
        try {
            long flowId = getFlowId(packet);
            // MIDST uses packet.getFlowId() internally for flow tracking,
            // which differs from PACKS' getFlowId() (sourceId*100000+destinationId).
            // We must query MIDST with the same ID it uses to register bursty flows.
            long midstFlowId = packet.getFlowId();
            long baseRank = getPacketRank(packet);

            // =========================================================================
            // NEW: Query MIDST module directly for bursty flow status
            // This uses the virtual queue trigger which works for ALL schedulers,
            // including PACKS where admission control prevents queue from filling.
            // Falls back to callback-based burstyFlowIds if MIDST not available.
            // =========================================================================
            boolean isBursty;
            if (midstModule != null) {
                // NEW: Use virtual queue-based bursty detection
                // NOTE: Must use midstFlowId (packet.getFlowId()) not flowId (PACKS formula)
                isBursty = midstModule.isFlowBursty(midstFlowId);
                if (LOGGING_ENABLED && isBursty) {
                    SimulationLogger.logInfo("PACKS_MIDST_QUERY",
                        "Flow=" + flowId + ",Bursty=true,VirtualQ=" + midstModule.getVirtualQueueDepthBits() +
                        ",Monitoring=" + midstModule.isMonitoring() + ",Time=" + Simulator.getCurrentTime());
                }
            } else {
                // Fallback: Use callback-based bursty flow set
                // burstyFlowIds uses MIDST's flow IDs (packet.getFlowId()), so use midstFlowId
                isBursty = burstyFlowIds.contains(midstFlowId);
            }

            // MIDST: Apply rank penalty if bursty
            long effectiveRank = baseRank;
            if (isBursty) {
                effectiveRank += rankPenalty;
            }

            // PACKS Admission Control
            // For bursty flows with virtual fill level enabled, use the virtual queue
            // depth instead of the real queue size. This opens the admission gate
            // (fillLevel >= k) during virtual congestion, making bursty flows face
            // the quantile test even when PACKS keeps the real queue small.
            int totalSize = getTotalQueueSize();
            int effectiveSize;
            if (isBursty && useVirtualFillLevel && midstModule != null) {
                long virtualQueuePackets = midstModule.getVirtualQueueDepthBits() / (1500L * 8L);
                effectiveSize = (int) Math.min(virtualQueuePackets, maxQueueSizePackets);
                if (LOGGING_ENABLED) {
                    SimulationLogger.logInfo("PACKS_VIRTUAL_FILL",
                        "Flow=" + flowId + ",RealSize=" + totalSize +
                        ",VirtualSize=" + effectiveSize +
                        ",MaxSize=" + maxQueueSizePackets +
                        ",Time=" + Simulator.getCurrentTime());
                }
            } else {
                effectiveSize = totalSize;
            }
            boolean admitted = checkAdmission(effectiveRank, effectiveSize);
            trackDecision(admitted);

            if (!admitted) {
                packetsDroppedByAdmission.incrementAndGet();
                if (isBursty) packetsDroppedDueToBursty.incrementAndGet();
                checkCongestion(totalSize);
                return false;
            }

            // Overflow check
            if (totalSize >= maxQueueSizePackets) {
                checkCongestion(totalSize);
                return false;
            }

            // SP-PIFO scheduling: select queue
            int queueIndex = (int) Math.min(effectiveRank / rankDelta, numQueues - 1);
            priorityQueues.get(queueIndex).offer(packet);
            packetsAdmitted.incrementAndGet();

            // Log bursty vs normal packet for MIDST statistics
            if (isBursty) {
                SimulationLogger.increaseStatisticCounter("MIDST_BURSTY_PACKETS_ENQUEUED");
            } else {
                SimulationLogger.increaseStatisticCounter("MIDST_NORMAL_PACKETS_ENQUEUED");
            }

            // Update rank window
            sampleCounter++;
            if (sampleCounter >= sampleCount) {
                sampleCounter = 0;
                rankWindow.addLast(packet);
                if (rankWindow.size() > windowSize) {
                    rankWindow.removeFirst();
                }
            }

            checkCongestion(totalSize + 1);
            return true;

        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public Packet poll() {
        stateLock.lock();
        try {
            for (int i = 0; i < numQueues; i++) {
                Queue<Packet> queue = priorityQueues.get(i);
                if (!queue.isEmpty()) {
                    Packet p = queue.poll();

                    // C3b: Rank inversion tracking for PACKS multi-queue scheduling
                    if (SimulationLogger.hasInversionsTrackingEnabled()) {
                        long polledRank = (p instanceof PriorityHeader) ?
                            ((PriorityHeader) p).getPriority() : Long.MAX_VALUE;
                        int inversions = 0;
                        for (int qi = 0; qi < numQueues; qi++) {
                            for (Packet pkt : priorityQueues.get(qi)) {
                                if (pkt instanceof PriorityHeader) {
                                    long r = ((PriorityHeader) pkt).getPriority();
                                    if (r < polledRank) {
                                        inversions++;
                                    }
                                }
                            }
                        }
                        if (inversions > 0) {
                            SimulationLogger.logInversionsPerRank(
                                System.identityHashCode(this) % 100000,
                                (int) polledRank, inversions);
                        }
                    }

                    checkCongestionEnd(getTotalQueueSize());
                    return p;
                }
            }
            return null;
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public Packet peek() {
        stateLock.lock();
        try {
            for (int i = 0; i < numQueues; i++) {
                if (!priorityQueues.get(i).isEmpty()) {
                    return priorityQueues.get(i).peek();
                }
            }
            return null;
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public int size() {
        return getTotalQueueSize();
    }

    private int getTotalQueueSize() {
        int total = 0;
        for (Queue<Packet> q : priorityQueues) {
            total += q.size();
        }
        return total;
    }

    @Override
    public Iterator<Packet> iterator() {
        List<Packet> all = new ArrayList<>();
        for (Queue<Packet> q : priorityQueues) {
            all.addAll(q);
        }
        return all.iterator();
    }

    // ========== Admission Control ==========

    private boolean checkAdmission(long rank, int currentSize) {
        float fillLevel = (float) currentSize / maxQueueSizePackets;
        if (fillLevel < k) {
            return true;
        }

        // Quantile check
        if (rankWindow.isEmpty()) {
            return true;
        }

        List<Long> ranks = new ArrayList<>();
        for (Packet p : rankWindow) {
            ranks.add(getPacketRank(p));
        }
        Collections.sort(ranks);

        int index = (int) Math.floor((1.0 - fillLevel) * (ranks.size() - 1));
        index = Math.max(0, Math.min(index, ranks.size() - 1));
        long threshold = ranks.get(index);

        return rank <= threshold;
    }

    // ========== Congestion Detection ==========

    private void trackDecision(boolean admitted) {
        if (!useRejectionRateDetection) return;

        recentDecisions.addLast(admitted);
        if (admitted) recentAdmissions++;
        else recentRejections++;

        while (recentDecisions.size() > rejectionWindowSize) {
            boolean removed = recentDecisions.removeFirst();
            if (removed) recentAdmissions--;
            else recentRejections--;
        }
    }

    private double getRejectionRate() {
        int total = recentAdmissions + recentRejections;
        return total == 0 ? 0.0 : (double) recentRejections / total;
    }

    private void checkCongestion(int currentSize) {
        boolean shouldSignal;
        if (useRejectionRateDetection) {
            shouldSignal = getRejectionRate() >= rejectionRateThreshold;
        } else {
            shouldSignal = currentSize >= highWaterMarkPackets;
        }

        if (shouldSignal && !congestionDetected) {
            congestionDetected = true;
            SimulationLogger.increaseStatisticCounter("MIDST_PACKS_CONGESTION_DETECTED");
            if (signaler != null) {
                signaler.signalCongestionStart();
            }
        }
    }

    private void checkCongestionEnd(int currentSize) {
        boolean shouldEnd;
        if (useRejectionRateDetection) {
            shouldEnd = getRejectionRate() < rejectionRateThreshold / 2;
        } else {
            shouldEnd = currentSize <= lowWaterMarkPackets;
        }

        if (shouldEnd && congestionDetected) {
            congestionDetected = false;
            if (signaler != null) {
                signaler.signalCongestionEnd();
            }
        }
    }

    // ========== Utility ==========

    private long getFlowId(Packet packet) {
        if (packet instanceof PriorityHeader) {
            return ((PriorityHeader) packet).getSourceId() * 100000L +
                   ((PriorityHeader) packet).getDestinationId();
        }
        return packet.getFlowId();
    }

    private long getPacketRank(Packet packet) {
        if (packet instanceof PriorityHeader) {
            return ((PriorityHeader) packet).getPriority();
        }
        return 0;
    }

    public String getStatsSummary() {
        return String.format("PACKS: admitted=%d, dropped=%d, burstyDrops=%d",
            packetsAdmitted.get(), packetsDroppedByAdmission.get(), packetsDroppedDueToBursty.get());
    }
}
