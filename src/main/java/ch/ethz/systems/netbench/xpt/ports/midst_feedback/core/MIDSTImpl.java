package ch.ethz.systems.netbench.xpt.ports.midst_feedback.core;

import ch.ethz.systems.netbench.core.network.Packet;
import ch.ethz.systems.netbench.core.log.SimulationLogger;
import ch.ethz.systems.netbench.core.Simulator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MIDST Implementation: Microburst Detection using Count-Min Sketch
 *
 * This is the main implementation of the MIDST black box module.
 * It uses a Virtual Queue trigger for universal congestion detection
 * that works with any scheduler (SP-PIFO, AIFO, PACKS).
 *
 * Architecture:
 * 1. Virtual Queue tracks arrivals - departures (universal trigger)
 * 2. When virtual queue > high watermark: start monitoring window
 * 3. Sin/Sout sketches track flow-level statistics during monitoring
 * 4. When virtual queue < low watermark: stop monitoring, compute results
 * 5. Schedulers query isFlowBursty() to get burst classification
 *
 * Key Features:
 * - Preserves original MIDST paper design (window-based, Δ-sketch)
 * - Virtual queue trigger works for ALL scheduler types
 * - Thread-safe operations with ConcurrentHashMap and AtomicIntegerArray
 * - Comprehensive logging for experiment analysis
 *
 * @author MSc Thesis - MIDST + Programmable Packet Scheduling
 */
public class MIDSTImpl implements MIDST {

    // =========================================================================
    // CONFIGURATION
    // =========================================================================

    /** Size of each hash row in the sketch */
    private final int sketchSize;

    /** Number of hash functions (rows) in the sketch */
    private final int numHashFunctions;

    /** Threshold: flow is bursty if Sin estimate >= this (absolute floor) */
    private final int burstThreshold;

    /** Adaptive threshold: flow is bursty if its fraction of window traffic >= this.
     *  0.0 = disabled (use absolute burstThreshold only). */
    private final double adaptiveThresholdFraction;

    /** Maximum packets per sketch analysis window before rotation.
     *  When monitoring stays open (sustained congestion), the sketch must be
     *  periodically analyzed and reset to prevent saturation. After this many
     *  packets, trigger a rotation: analyze → reset → continue monitoring.
     *  Default: sketchSize * 4 (principled: tied to sketch capacity).
     *  P4 feasibility: one register + one comparison. */
    private final int maxWindowPackets;

    /** Enable detailed logging */
    private final boolean loggingEnabled;

    /** Port identifier for logging */
    private final String portId;

    // =========================================================================
    // VIRTUAL QUEUE (Trigger)
    // =========================================================================

    /** Virtual queue for congestion detection */
    private final VirtualQueue virtualQueue;

    // =========================================================================
    // SKETCHES
    // =========================================================================

    /** Ingress sketch: counts packets arriving per flow */
    private final AtomicIntegerArray sin;

    /** Egress sketch: counts packets departing per flow */
    private final AtomicIntegerArray sout;

    /** Dirty flags for Sin (which entries need reset) */
    private final AtomicIntegerArray sinDirtyFlags;

    /** Dirty flags for Sout (which entries need reset) */
    private final AtomicIntegerArray soutDirtyFlags;

    // =========================================================================
    // MONITORING STATE
    // =========================================================================

    /** Whether currently in a monitoring window */
    private volatile boolean isMonitoring = false;

    /** Flows seen during current monitoring window */
    private final Set<Long> activeFlowsInWindow = ConcurrentHashMap.newKeySet();

    /** Current set of bursty flows (updated at end of each window) */
    private final Set<Long> currentBurstyFlows = ConcurrentHashMap.newKeySet();

    /** Current set of heavy losers (updated at end of each window) */
    private final Set<Long> currentHeavyLosers = ConcurrentHashMap.newKeySet();

    /** Hash indices stored for each packet (for egress sketch update) */
    private final Map<Packet, int[]> packetHashIndices = new ConcurrentHashMap<>();

    // =========================================================================
    // STATISTICS
    // =========================================================================

    /** Number of completed monitoring windows */
    private int monitoringWindowCount = 0;

    /** Total packets classified as bursty */
    private long totalBurstyPackets = 0;

    /** Total packets classified as normal */
    private long totalNormalPackets = 0;

    /** Total bursty flows detected across all windows */
    private long totalBurstyFlowsDetected = 0;

    /** Total packets seen during current monitoring window (for adaptive threshold) */
    private final AtomicLong totalWindowPackets = new AtomicLong(0);

    // =========================================================================
    // CONSTRUCTOR
    // =========================================================================

    /**
     * Create a new MIDST instance with virtual queue trigger.
     *
     * @param sketchSize Size of each hash row (columns per row)
     * @param numHashFunctions Number of hash functions (rows)
     * @param burstThreshold Packets threshold to classify as bursty (absolute floor)
     * @param highWatermarkBits Virtual queue threshold to start monitoring
     * @param lowWatermarkBits Virtual queue threshold to stop monitoring
     * @param portId Port identifier for logging (e.g., "1->2")
     * @param loggingEnabled Enable detailed logging
     * @param adaptiveThresholdFraction Fraction of window traffic to classify as bursty (0.0 = disabled)
     */
    public MIDSTImpl(int sketchSize, int numHashFunctions, int burstThreshold,
                     long highWatermarkBits, long lowWatermarkBits,
                     String portId, boolean loggingEnabled,
                     double adaptiveThresholdFraction) {

        // Validate parameters
        if (sketchSize <= 0) {
            throw new IllegalArgumentException("Sketch size must be positive: " + sketchSize);
        }
        if (numHashFunctions <= 0) {
            throw new IllegalArgumentException("Number of hash functions must be positive: " + numHashFunctions);
        }
        if (burstThreshold <= 0) {
            throw new IllegalArgumentException("Burst threshold must be positive: " + burstThreshold);
        }
        if (adaptiveThresholdFraction < 0.0 || adaptiveThresholdFraction > 1.0) {
            throw new IllegalArgumentException("Adaptive threshold fraction must be in [0.0, 1.0]: " + adaptiveThresholdFraction);
        }

        this.sketchSize = sketchSize;
        this.numHashFunctions = numHashFunctions;
        this.burstThreshold = burstThreshold;
        this.adaptiveThresholdFraction = adaptiveThresholdFraction;
        this.maxWindowPackets = sketchSize * 4; // Default: 4x sketch width (e.g., 1024 for 256-bucket sketch)
        this.portId = portId;
        this.loggingEnabled = loggingEnabled;

        // Initialize virtual queue
        this.virtualQueue = new VirtualQueue(highWatermarkBits, lowWatermarkBits,
                                              portId, loggingEnabled);

        // Initialize sketches
        int totalSize = sketchSize * numHashFunctions;
        this.sin = new AtomicIntegerArray(totalSize);
        this.sout = new AtomicIntegerArray(totalSize);
        this.sinDirtyFlags = new AtomicIntegerArray(totalSize);
        this.soutDirtyFlags = new AtomicIntegerArray(totalSize);

        // Log initialization
        if (loggingEnabled) {
            SimulationLogger.logInfo("MIDST_INIT",
                "Port=" + portId +
                ",SketchSize=" + sketchSize +
                ",NumHashFunctions=" + numHashFunctions +
                ",BurstThreshold=" + burstThreshold +
                ",AdaptiveThresholdFraction=" + adaptiveThresholdFraction +
                ",HighWatermarkBits=" + highWatermarkBits +
                ",LowWatermarkBits=" + lowWatermarkBits);
        }

        SimulationLogger.logInfo("MIDST_CREATED",
            "Port=" + portId + ",Type=VirtualQueueTrigger");
    }

    /**
     * Backward-compatible constructor (adaptiveThresholdFraction defaults to 0.0).
     */
    public MIDSTImpl(int sketchSize, int numHashFunctions, int burstThreshold,
                     long highWatermarkBits, long lowWatermarkBits,
                     String portId, boolean loggingEnabled) {
        this(sketchSize, numHashFunctions, burstThreshold,
             highWatermarkBits, lowWatermarkBits,
             portId, loggingEnabled, 0.0);
    }

    /**
     * Convenience constructor with packet-based watermarks.
     */
    public static MIDSTImpl withPacketWatermarks(int sketchSize, int numHashFunctions,
                                                  int burstThreshold,
                                                  int highWatermarkPackets,
                                                  int lowWatermarkPackets,
                                                  String portId, boolean loggingEnabled) {
        long bitsPerPacket = 1500L * 8L;
        return new MIDSTImpl(sketchSize, numHashFunctions, burstThreshold,
                             highWatermarkPackets * bitsPerPacket,
                             lowWatermarkPackets * bitsPerPacket,
                             portId, loggingEnabled, 0.0);
    }

    /**
     * Convenience constructor with packet-based watermarks and adaptive threshold.
     */
    public static MIDSTImpl withPacketWatermarks(int sketchSize, int numHashFunctions,
                                                  int burstThreshold,
                                                  int highWatermarkPackets,
                                                  int lowWatermarkPackets,
                                                  String portId, boolean loggingEnabled,
                                                  double adaptiveThresholdFraction) {
        long bitsPerPacket = 1500L * 8L;
        return new MIDSTImpl(sketchSize, numHashFunctions, burstThreshold,
                             highWatermarkPackets * bitsPerPacket,
                             lowWatermarkPackets * bitsPerPacket,
                             portId, loggingEnabled, adaptiveThresholdFraction);
    }

    // =========================================================================
    // PACKET LIFECYCLE METHODS
    // =========================================================================

    @Override
    public void onPacketArrival(Packet packet) {
        long flowId = packet.getFlowId();
        long packetSizeBits = packet.getSizeBit();

        // 1. Update virtual queue (ALWAYS, regardless of monitoring state)
        boolean crossedHigh = virtualQueue.onArrival(packetSizeBits);

        // 2. Check if we should start monitoring
        if (crossedHigh && !isMonitoring) {
            startMonitoring();
        }

        // 3. Update ingress sketch (only during monitoring)
        if (isMonitoring) {
            updateIngressSketch(packet);
        }

        // 4. Classify packet for statistics
        if (isMonitoring) {
            if (currentBurstyFlows.contains(flowId)) {
                totalBurstyPackets++;
                SimulationLogger.increaseStatisticCounter("MIDST_BURSTY_PACKETS_ENQUEUED");
            } else {
                totalNormalPackets++;
                SimulationLogger.increaseStatisticCounter("MIDST_NORMAL_PACKETS_ENQUEUED");
            }
        }

        // 5. Detailed logging
        if (loggingEnabled) {
            SimulationLogger.logInfo("MIDST_ARRIVAL",
                "Port=" + portId +
                ",Flow=" + flowId +
                ",VirtualQ=" + virtualQueue.getDepthBits() +
                ",Monitoring=" + isMonitoring +
                ",IsBursty=" + currentBurstyFlows.contains(flowId) +
                ",Time=" + Simulator.getCurrentTime());
        }
    }

    @Override
    public void onPacketDeparture(Packet packet) {
        long packetSizeBits = packet.getSizeBit();

        // 1. Update virtual queue
        boolean crossedLow = virtualQueue.onDeparture(packetSizeBits);

        // 2. Update egress sketch (if we have metadata from arrival)
        updateEgressSketch(packet);

        // 3. Check if we should stop monitoring
        if (crossedLow && isMonitoring) {
            stopMonitoring();
        }
    }

    @Override
    public void onPacketDropped(Packet packet) {
        // PHASE 9 FIX: Remove phantom packet from virtual queue.
        //
        // onPacketArrival() already incremented VQ for this packet. Since it was
        // dropped (e.g., by PACKS/AIFO admission control), onPacketDeparture()
        // will never be called for it. Without this decrement, every dropped packet
        // inflates the VQ permanently — creating "phantom packets" that make the VQ
        // a one-way ramp. For PACKS (~80% drop rate, ~2.2M drops/sim) this makes
        // monitoring windows stuck open forever.
        //
        // With this fix, VQ ≈ real queue depth, which oscillates naturally between
        // watermarks, enabling proper monitoring window cycling (36+ windows).
        //
        // For SP-PIFO (which rarely drops), this has negligible effect.
        //
        // Validated by: VirtualQueueTest (H2 PROOF tests) and vq_drop_behavior_proof.py
        long packetSizeBits = packet.getSizeBit();
        boolean crossedLow = virtualQueue.onDeparture(packetSizeBits);

        // Don't update Sout sketch — the drop IS a loss for Δ-sketch purposes.
        // Sin was incremented but Sout won't be, so the sketch correctly reflects
        // that this flow's packets are being lost.
        packetHashIndices.remove(packet);

        // If VQ crossed below low watermark, stop monitoring
        if (crossedLow && isMonitoring) {
            stopMonitoring();
        }
    }

    // =========================================================================
    // QUERY API
    // =========================================================================

    @Override
    public boolean isFlowBursty(long flowId) {
        // Check if flow was marked bursty in previous window
        if (currentBurstyFlows.contains(flowId)) {
            // C1b: Register detected bursty status for enhanced CSV logging
            SimulationLogger.registerFlowBurstyDetected(flowId);
            return true;
        }

        // REAL-TIME DETECTION: If currently monitoring, check if flow exceeds threshold NOW
        // This ensures bursty flows are penalized IMMEDIATELY, not one window later!
        if (isMonitoring && activeFlowsInWindow.contains(flowId)) {
            int ingressEstimate = getFlowIngressEstimate(flowId);

            boolean detected;
            if (adaptiveThresholdFraction > 0.0) {
                // Adaptive (relative) detection
                long total = totalWindowPackets.get();
                double fraction = (total > 0) ? (double) ingressEstimate / total : 0.0;
                detected = (fraction >= adaptiveThresholdFraction) && (ingressEstimate >= burstThreshold);
            } else {
                // Legacy absolute detection
                detected = (ingressEstimate >= burstThreshold);
            }

            if (detected) {
                currentBurstyFlows.add(flowId);
                // C1b: Register detected bursty status for enhanced CSV logging
                SimulationLogger.registerFlowBurstyDetected(flowId);
                return true;
            }
        }

        return false;
    }

    @Override
    public Set<Long> getBurstyFlows() {
        return Collections.unmodifiableSet(new HashSet<>(currentBurstyFlows));
    }

    @Override
    public Set<Long> getHeavyLosers() {
        return Collections.unmodifiableSet(new HashSet<>(currentHeavyLosers));
    }

    @Override
    public int getFlowLossEstimate(long flowId) {
        int[] indices = getHashIndices(flowId);
        int minLoss = Integer.MAX_VALUE;
        for (int idx : indices) {
            int loss = sin.get(idx) - sout.get(idx);
            minLoss = Math.min(minLoss, loss);
        }
        return Math.max(0, minLoss);
    }

    @Override
    public int getFlowIngressEstimate(long flowId) {
        int[] indices = getHashIndices(flowId);
        int minCount = Integer.MAX_VALUE;
        for (int idx : indices) {
            minCount = Math.min(minCount, sin.get(idx));
        }
        return minCount == Integer.MAX_VALUE ? 0 : minCount;
    }

    // =========================================================================
    // STATE QUERIES
    // =========================================================================

    @Override
    public boolean isMonitoring() {
        return isMonitoring;
    }

    @Override
    public long getVirtualQueueDepthBits() {
        return virtualQueue.getDepthBits();
    }

    @Override
    public long getHighWatermarkBits() {
        return virtualQueue.getHighWatermarkBits();
    }

    @Override
    public long getLowWatermarkBits() {
        return virtualQueue.getLowWatermarkBits();
    }

    @Override
    public int getBurstThreshold() {
        return burstThreshold;
    }

    @Override
    public double getAdaptiveThresholdFraction() {
        return adaptiveThresholdFraction;
    }

    @Override
    public long getTotalWindowPackets() {
        return totalWindowPackets.get();
    }

    /**
     * Get the maximum packets per sketch analysis window.
     * After this many packets, the sketch is rotated (analyzed + reset).
     *
     * @return Maximum window packets before rotation
     */
    public int getMaxWindowPackets() {
        return maxWindowPackets;
    }

    // =========================================================================
    // STATISTICS
    // =========================================================================

    @Override
    public int getMonitoringWindowCount() {
        return monitoringWindowCount;
    }

    @Override
    public long getTotalBurstyPackets() {
        return totalBurstyPackets;
    }

    @Override
    public long getTotalNormalPackets() {
        return totalNormalPackets;
    }

    // =========================================================================
    // INTERNAL: Monitoring Window Management
    // =========================================================================

    /**
     * Start a new monitoring window.
     */
    private synchronized void startMonitoring() {
        if (isMonitoring) return; // Already monitoring

        isMonitoring = true;
        activeFlowsInWindow.clear();
        totalWindowPackets.set(0);
        resetDirtyFlags();

        SimulationLogger.logInfo("MIDST_MONITOR_START",
            "Port=" + portId +
            ",VirtualQueueBits=" + virtualQueue.getDepthBits() +
            ",ThresholdBits=" + virtualQueue.getHighWatermarkBits() +
            ",WindowNumber=" + (monitoringWindowCount + 1) +
            ",Time=" + Simulator.getCurrentTime());

        SimulationLogger.increaseStatisticCounter("MIDST_MONITORING_WINDOWS_STARTED");

        if (loggingEnabled) {
            System.out.println("MIDST: Port " + portId + " START monitoring at " +
                               Simulator.getCurrentTime() + " (VQ=" +
                               virtualQueue.getDepthPackets() + " pkts)");
        }
    }

    /**
     * Stop the current monitoring window and compute results.
     */
    private synchronized void stopMonitoring() {
        if (!isMonitoring) return; // Not monitoring

        isMonitoring = false;
        monitoringWindowCount++;

        // Identify bursty flows (heavy hitters in Sin)
        currentBurstyFlows.clear();
        currentHeavyLosers.clear();

        long windowTotal = totalWindowPackets.get();

        for (Long flowId : activeFlowsInWindow) {
            int ingressEstimate = getFlowIngressEstimate(flowId);

            // Check if bursty (adaptive or absolute)
            boolean isBursty;
            if (adaptiveThresholdFraction > 0.0) {
                // Adaptive (relative) detection: fraction of window traffic
                double fraction = (windowTotal > 0) ? (double) ingressEstimate / windowTotal : 0.0;
                isBursty = (fraction >= adaptiveThresholdFraction) && (ingressEstimate >= burstThreshold);
            } else {
                // Legacy absolute detection
                isBursty = (ingressEstimate >= burstThreshold);
            }

            if (isBursty) {
                currentBurstyFlows.add(flowId);
                totalBurstyFlowsDetected++;
                SimulationLogger.increaseStatisticCounter("MIDST_BURSTY_FLOWS_DETECTED");
                // C1b fix: Register detected status immediately so flows that complete
                // before isFlowBursty() is called again still appear in the CSV.
                // Without this, burst flows that finish during the monitoring window
                // are detected by analyzeCongestionWindow() (stats counter) but never
                // registered in flowBurstyDetected (CSV column), causing undercounting.
                SimulationLogger.registerFlowBurstyDetected(flowId);

                if (loggingEnabled) {
                    SimulationLogger.logInfo("MIDST_BURSTY_FLOW",
                        "Port=" + portId +
                        ",FlowId=" + flowId +
                        ",IngressEstimate=" + ingressEstimate +
                        ",Threshold=" + burstThreshold +
                        ",AdaptiveFraction=" + (windowTotal > 0 ? String.format("%.4f", (double) ingressEstimate / windowTotal) : "N/A") +
                        ",AdaptiveThreshold=" + adaptiveThresholdFraction +
                        ",WindowTotalPackets=" + windowTotal +
                        ",Time=" + Simulator.getCurrentTime());
                }
            }

            // Check for loss (heavy loser)
            int lossEstimate = getFlowLossEstimate(flowId);
            if (lossEstimate > 0) {
                currentHeavyLosers.add(flowId);
            }
        }

        // Clean sketches
        resetDirtySketchEntries();

        SimulationLogger.logInfo("MIDST_MONITOR_STOP",
            "Port=" + portId +
            ",VirtualQueueBits=" + virtualQueue.getDepthBits() +
            ",BurstyFlowsFound=" + currentBurstyFlows.size() +
            ",HeavyLosersFound=" + currentHeavyLosers.size() +
            ",FlowsAnalyzed=" + activeFlowsInWindow.size() +
            ",TotalWindowPackets=" + windowTotal +
            ",AdaptiveThreshold=" + adaptiveThresholdFraction +
            ",WindowNumber=" + monitoringWindowCount +
            ",Time=" + Simulator.getCurrentTime());

        SimulationLogger.increaseStatisticCounter("MIDST_MONITORING_WINDOWS_COMPLETED");

        if (loggingEnabled) {
            System.out.println("MIDST: Port " + portId + " STOP monitoring at " +
                               Simulator.getCurrentTime() + " - Found " +
                               currentBurstyFlows.size() + " bursty flows");
        }
    }

    /**
     * Rotate the sketch during sustained monitoring.
     *
     * When monitoring stays open continuously (e.g., under PACKS at 31:1 fan-in),
     * the sketch accumulates indefinitely and saturates. This method performs a
     * "rotation": analyze the current sketch, reset it, and continue monitoring.
     *
     * This is equivalent to the cleaning the original MIDST paper performs between
     * monitoring windows, but triggered by packet count instead of VQ crossing
     * the low watermark.
     *
     * Sequence: stopMonitoring() → startMonitoring()
     * The monitoring gap is instantaneous (within the same call).
     * stopMonitoring() analyzes bursty flows and resets dirty sketch entries.
     * startMonitoring() clears counters and prepares for the next rotation.
     *
     * P4 feasibility: one register (packet counter) + one comparison per arrival.
     */
    private synchronized void rotateSketch() {
        if (!isMonitoring) return; // Safety: only rotate during monitoring

        SimulationLogger.logInfo("MIDST_SKETCH_ROTATION",
            "Port=" + portId +
            ",PacketsInWindow=" + totalWindowPackets.get() +
            ",MaxWindowPackets=" + maxWindowPackets +
            ",WindowNumber=" + (monitoringWindowCount + 1) +
            ",Time=" + Simulator.getCurrentTime());

        SimulationLogger.increaseStatisticCounter("MIDST_SKETCH_ROTATIONS");

        if (loggingEnabled) {
            System.out.println("MIDST: Port " + portId + " ROTATE sketch at " +
                               Simulator.getCurrentTime() + " (packets=" +
                               totalWindowPackets.get() + ", max=" + maxWindowPackets + ")");
        }

        // Preserve bursty flow state across rotations.
        //
        // stopMonitoring() clears currentBurstyFlows before re-analyzing the sketch,
        // which loses detections from previous rotations. Without this, a flow detected
        // as bursty in rotation N is forgotten by rotation N+1, so isFlowBursty()
        // returns false when the flow completes → the CSV isBurstyDetected flag is never
        // set. This explains why R1b/R4b/R5b (3-tier, 100% rotation-driven) show 0
        // detected flows in CSV despite statistics.log showing 180/92/188 detections.
        //
        // R2b (FatTree) works because 49% of its windows close naturally (VQ crosses
        // low watermark), giving flows a chance to be in currentBurstyFlows at completion.
        //
        // Fix: save accumulated bursty flows, let stopMonitoring analyze+reset the
        // sketch, then restore previous detections. Flows detected in ANY rotation
        // within this continuous monitoring session remain classified as bursty.
        Set<Long> accumulatedBurstyFlows = new HashSet<>(currentBurstyFlows);

        // Analyze current sketch and reset (same as end-of-window)
        stopMonitoring();
        startMonitoring();

        // Restore: merge previous detections with this rotation's new detections
        currentBurstyFlows.addAll(accumulatedBurstyFlows);
    }

    // =========================================================================
    // INTERNAL: Sketch Operations
    // =========================================================================

    /**
     * Update ingress sketch (Sin) for a packet.
     */
    private void updateIngressSketch(Packet packet) {
        long flowId = packet.getFlowId();
        activeFlowsInWindow.add(flowId);

        int[] indices = getHashIndices(flowId);
        for (int idx : indices) {
            sinDirtyFlags.compareAndSet(idx, 0, 1); // Mark as dirty
            sin.incrementAndGet(idx);
        }

        // Track total packets for adaptive threshold
        long currentCount = totalWindowPackets.incrementAndGet();

        // Store indices for egress update later
        packetHashIndices.put(packet, indices);

        // SKETCH ROTATION: If we've accumulated maxWindowPackets, rotate the sketch.
        // This prevents sketch saturation under sustained monitoring (e.g., PACKS at 31:1).
        // The rotation analyzes the current sketch, resets it, and continues monitoring.
        // Without this, a 256x3 sketch saturates after ~768 packets → 99.3% false positives.
        if (currentCount >= maxWindowPackets) {
            rotateSketch();
        }
    }

    /**
     * Update egress sketch (Sout) for a packet.
     */
    private void updateEgressSketch(Packet packet) {
        int[] indices = packetHashIndices.remove(packet);
        if (indices == null) {
            // Packet arrived before monitoring started, or already processed
            return;
        }

        for (int idx : indices) {
            if (isMonitoring) {
                soutDirtyFlags.compareAndSet(idx, 0, 1); // Mark as dirty
            }
            sout.incrementAndGet(idx);
        }
    }

    /**
     * Calculate hash indices for a flow ID.
     * Uses the same hash functions as the original MIDST implementation.
     */
    private int[] getHashIndices(long flowId) {
        int[] indices = new int[numHashFunctions];
        for (int i = 0; i < numHashFunctions; i++) {
            int seed = i * 0x9e3779b9; // Golden ratio hash mixing
            int hashValue = Math.abs(Long.hashCode(flowId ^ seed) % sketchSize);
            indices[i] = i * sketchSize + hashValue; // Flat array index
        }
        return indices;
    }

    /**
     * Reset dirty flags at the start of a monitoring window.
     */
    private void resetDirtyFlags() {
        int total = sketchSize * numHashFunctions;
        for (int i = 0; i < total; i++) {
            sinDirtyFlags.set(i, 0);
            soutDirtyFlags.set(i, 0);
        }
    }

    /**
     * Reset sketch entries that were used (marked dirty) at the end of a window.
     */
    private void resetDirtySketchEntries() {
        int total = sketchSize * numHashFunctions;
        int resetCount = 0;
        for (int i = 0; i < total; i++) {
            if (sinDirtyFlags.compareAndSet(i, 1, 0)) {
                sin.set(i, 0);
                resetCount++;
            }
            if (soutDirtyFlags.compareAndSet(i, 1, 0)) {
                sout.set(i, 0);
            }
        }

        SimulationLogger.increaseStatisticCounter("MIDST_SKETCH_RESETS");

        if (loggingEnabled) {
            SimulationLogger.logInfo("MIDST_SKETCH_RESET",
                "Port=" + portId +
                ",EntriesReset=" + resetCount +
                ",Time=" + Simulator.getCurrentTime());
        }
    }

    // =========================================================================
    // UTILITY
    // =========================================================================

    /**
     * Get the virtual queue instance (for testing/debugging).
     */
    public VirtualQueue getVirtualQueue() {
        return virtualQueue;
    }

    @Override
    public String toString() {
        return "MIDSTImpl{" +
            "port=" + portId +
            ", monitoring=" + isMonitoring +
            ", virtualQueue=" + virtualQueue.getDepthPackets() + " pkts" +
            ", burstyFlows=" + currentBurstyFlows.size() +
            ", windowCount=" + monitoringWindowCount +
            "}";
    }
}
