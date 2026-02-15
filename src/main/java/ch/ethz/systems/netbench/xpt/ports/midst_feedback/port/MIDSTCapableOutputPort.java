package ch.ethz.systems.netbench.xpt.ports.midst_feedback.port;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;

// Removed: import ch.ethz.systems.netbench.core.config.NBProperties; // No longer needed
import ch.ethz.systems.netbench.core.Simulator;
import ch.ethz.systems.netbench.core.log.SimulationLogger;
import ch.ethz.systems.netbench.core.network.Link;
import ch.ethz.systems.netbench.core.network.NetworkDevice;
import ch.ethz.systems.netbench.core.network.OutputPort;
import ch.ethz.systems.netbench.core.network.Packet;
import ch.ethz.systems.netbench.core.network.PacketDispatchedEvent;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.ICongestionSignaler;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.IMonitoredQueue;
// NEW: Import the new MIDST black box module
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.core.MIDST;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.core.MIDSTImpl;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl.NewCanonicalSPPIFOQueue;
// Assuming PacketMetadata_MIDST class exists and is defined appropriately elsewhere
// import ch.ethz.systems.netbench.xpt.ports.midst_feedback.port.PacketMetadata_MIDST;

/**
 * An Output Port implementing the MIDST monitoring logic.
 * Aligned Design: Receives all configuration via constructor parameters.
 * It interacts with an IMonitoredQueue, acting as its ICongestionSignaler,
 * and sends congestion feedback back to the queue.
 * Contains the Count-Min sketch logic.
 */
public class MIDSTCapableOutputPort extends OutputPort implements ICongestionSignaler {

    // Instance flag for verbose internal logging.
    protected final boolean internalLoggingEnabled;
    protected final boolean testMode;
 // In MIDSTCapableOutputPort.java (typically near other field declarations)
 // VV ADD THIS LINE AT THE TOP OF YOUR CLASS VV
 private static final boolean DEBUG_FORCE_ALL_PACKETS_TO_QUEUE = false; // <<< SET TO true for this test
 // ^^ ADD THIS LINE AT THE TOP OF YOUR CLASS ^^

    // MIDST Core Components
    protected final AtomicIntegerArray sin;  // Count-Min Sketch for ingress
    protected final AtomicIntegerArray sout; // Count-Min Sketch for egress
    protected final AtomicIntegerArray sinDirtyFlags; // Tracks which sin entries are active in monitoring period
    protected final AtomicIntegerArray soutDirtyFlags; // Tracks which sout entries are active in monitoring period
    protected final long maxQueueSizeBits;
    protected final int numHashFunctions;
    protected final int sketchSize;
    protected volatile boolean isMonitoring = false;
    // Store metadata associated with packets *currently* in the port/queue
    // Ensure PacketMetadata_MIDST exists and has getHashIndices()
    protected final Map<Packet, PacketMetadata_MIDST> packetMetadataMap = new ConcurrentHashMap<>();
    // Keep track of flows seen during the current monitoring window
    protected final Set<Long> activeFlowsInWindow = ConcurrentHashMap.newKeySet();

    // Configuration / State (passed via constructor)
    protected final int midstBurstThreshold;

    // Reference to the queue (typed to interface)
    protected final IMonitoredQueue monitoredQueue;

    // Watermark thresholds for port-level congestion detection (from config)
    private final long midstHighWatermarkBits;
    private final long midstLowWatermarkBits;
    private boolean midstCongestionDetected = false;

    // Port-level VFL: effective buffer size for bursty flows at port tail-drop
    // When < maxQueueSizeBits, bursty flows see a smaller buffer and get dropped earlier
    private final long portVflBurstyMaxBits;

    // =========================================================================
    // NEW METRICS TRACKING
    // =========================================================================

    // MED #9: Bytes during congestion
    private long bytesDuringCongestion = 0;
    private long currentCongestionBytes = 0;

    // LOW #11: Sketch reset count
    private int sketchResetCount = 0;

    // HIGH #5: Count-Min Sketch accuracy tracking
    // Track true flow sizes vs estimated sizes for accuracy measurement
    private final Map<Long, Integer> trueFlowPacketCounts = new ConcurrentHashMap<>();

    // LOW #12: Hash collision tracking
    private long totalCollisions = 0;
    private long totalHashOps = 0;

    // =========================================================================
    // NEW: MIDST Black Box Module (Virtual Queue Trigger)
    // =========================================================================
    /** The new MIDST module with virtual queue trigger - works for all schedulers */
    protected final MIDST midst;

    /**
     * Aligned Constructor. Receives all necessary configuration parameters directly.
     *
     * @param ownNetworkDevice      Device this port belongs to.
     * @param targetNetworkDevice   Device this port connects to.
     * @param link                  Link connecting the devices.
     * @param queue                 The IMonitoredQueue instance to use.
     * @param maxQueueSizeBytes     Max buffer size in bytes.
     * @param sketchSize            Size of each hash array in sketches.
     * @param numHashFunctions      Number of hash functions (arrays) in sketches.
     * @param burstThreshold        Threshold (estimated packets) to mark flow as congested.
     * @param highWatermarkPackets  High watermark for VQ monitoring (in packets).
     * @param lowWatermarkPackets   Low watermark for VQ monitoring (in packets).
     * @param portVflFraction       Port-level VFL fraction (0.0-1.0). 1.0 = disabled.
     * @param adaptiveThresholdFraction Adaptive threshold fraction (0.0 = disabled, >0 = relative detection).
     */
    public MIDSTCapableOutputPort(NetworkDevice ownNetworkDevice,
                                  NetworkDevice targetNetworkDevice,
                                  Link link,
                                  IMonitoredQueue queue, // Use interface type
                                  long maxQueueSizeBytes,
                                  int sketchSize,
                                  int numHashFunctions,
                                  int burstThreshold,
                                  long highWatermarkPackets,
                                  long lowWatermarkPackets,
                                  boolean testMode,
                                  boolean internalLoggingEnabled, // Pass flag directly
                                  double portVflFraction, // Port-level VFL: fraction of buffer for bursty flows
                                  double adaptiveThresholdFraction // Adaptive threshold for relative heavy-hitter detection
                                 ) {

        // Pass the queue instance to the superclass constructor
        // The 'isReceiver=true' argument from the original MIDSTOutputPort might be relevant
        // depending on how NetBench handles queue ownership/directionality. Assuming 'true' is correct.
        // The new unaligned code also used 'true'.
        super(ownNetworkDevice, targetNetworkDevice, link, queue, true);
        this.monitoredQueue = queue; // Store interface reference

        // Initialize MIDST fields from parameters
        this.maxQueueSizeBits = maxQueueSizeBytes * 8L;
        this.sketchSize = sketchSize;
        this.numHashFunctions = numHashFunctions;
        this.sin = new AtomicIntegerArray(sketchSize * numHashFunctions);
        this.sout = new AtomicIntegerArray(sketchSize * numHashFunctions);
        this.sinDirtyFlags = new AtomicIntegerArray(sketchSize * numHashFunctions);
        this.soutDirtyFlags = new AtomicIntegerArray(sketchSize * numHashFunctions);
        this.testMode = testMode;
        this.midstBurstThreshold = burstThreshold;
        this.internalLoggingEnabled = internalLoggingEnabled; // Store instance logging flag

        // Initialize watermarks from config (convert packets to bits: packets * 1500 bytes * 8 bits)
        this.midstHighWatermarkBits = highWatermarkPackets * 1500L * 8L;
        this.midstLowWatermarkBits = lowWatermarkPackets * 1500L * 8L;

        // Port-level VFL: compute effective buffer size for bursty flows
        // portVflFraction = 1.0 means disabled (bursty see full buffer)
        // portVflFraction = 0.7 means bursty flows see 70% of buffer, dropped earlier
        this.portVflBurstyMaxBits = (long)(this.maxQueueSizeBits * Math.max(0.0, Math.min(1.0, portVflFraction)));

        // NEW: Initialize the MIDST black box module with virtual queue trigger
        String portId = ownNetworkDevice.getIdentifier() + "->" + targetNetworkDevice.getIdentifier();
        this.midst = new MIDSTImpl(
            sketchSize,
            numHashFunctions,
            burstThreshold,
            this.midstHighWatermarkBits,  // From config, not hardcoded
            this.midstLowWatermarkBits,
            portId,
            internalLoggingEnabled,
            adaptiveThresholdFraction
        );

        // Register this port as the signaler for the queue
        // Ensure the queue implementation correctly calls the signaler methods
        this.monitoredQueue.setCongestionSignaler(this);

        if (this.internalLoggingEnabled) System.out.println("MIDSTCapableOutputPort created for " + getOwnId() + "->" + getTargetId());
     // PASTE THIS LINE:
        if (this.internalLoggingEnabled) { SimulationLogger.logInfo("MIDST_PORT_DEBUG", "CONSTRUCTOR,Port=" + getOwnId() + "->" + getTargetId() + ",internalLoggingEnabled=" + this.internalLoggingEnabled + ",midstBurstThreshold=" + this.midstBurstThreshold + ",sketchSize=" + this.sketchSize + ",numHashFunctions=" + this.numHashFunctions + ",maxQueueSizeBytes=" + (this.maxQueueSizeBits / 8L) + ",Time=" + Simulator.getCurrentTime()); }
        
        if (this.internalLoggingEnabled) {
            SimulationLogger.logInfo("QUEUE_MAPPING_DEBUG", 
                "PORT_CREATED,Port=" + getOwnId() + "->" + getTargetId() + 
                ",QueueHash=" + monitoredQueue.hashCode() + 
                ",QueueClass=" + monitoredQueue.getClass().getSimpleName() + 
                ",Time=" + Simulator.getCurrentTime());
        }
    }

    // --- ICongestionSignaler Implementation ---
    // (Remains the same as the 'new unaligned code', just update logging checks)

    @Override
    public void signalCongestionStart() {
        // Ensure this is idempotent if called multiple times
        if (!isMonitoring) {
            // Use synchronized block if necessary, but volatile might be sufficient
            // if start/end signals are guaranteed to be serialized by the queue.
            // Let's assume serialized calls for now.
            if (!isMonitoring) { // Double check
                isMonitoring = true;
                resetDirtyFlags(); // Clear flags for the new monitoring window
                activeFlowsInWindow.clear(); // Clear flows tracked from previous window

             // PASTE THIS LINE:
                if (this.internalLoggingEnabled) { SimulationLogger.logInfo("MIDST_PORT_DEBUG", "SIGNAL_CONGESTION_START,Port=" + getOwnId() + "->" + getTargetId() + ",Action=StartingMIDSTMonitoring,QueueSizePackets=" + getQueue().size() + ",Time=" + Simulator.getCurrentTime()); }
                if (!testMode && this.internalLoggingEnabled) {
                    SimulationLogger.logInfo("MIDST_MONITOR", "START, Time=" + Simulator.getCurrentTime() +
                            ", Port=" + getOwnId() + "->" + getTargetId() + ", QPkts=" + getQueue().size());
                }
                if (this.internalLoggingEnabled) System.out.println("Port " + getOwnId() + "->" + getTargetId() + " START MONITORING at " + Simulator.getCurrentTime());
            }
        } else if (this.internalLoggingEnabled) {
            System.out.println("Port " + getOwnId() + "->" + getTargetId() + " Received redundant START signal.");
         // PASTE THIS LINE:
            SimulationLogger.logInfo("MIDST_PORT_DEBUG", "SIGNAL_CONGESTION_START_REDUNDANT,Port=" + getOwnId() + "->" + getTargetId() + ",Time=" + Simulator.getCurrentTime());
        }
    }

    @Override
    public void signalCongestionEnd() {
        if (this.internalLoggingEnabled) { SimulationLogger.logInfo("MIDST_DEBUG_SIGNAL_CONGESTION_END", "Called for Port=" + getOwnId() + "->" + getTargetId() + ",Time=" + Simulator.getCurrentTime()); }
        if (isMonitoring) {
            synchronized(this) {
                if (isMonitoring) { // Double-check lock
                    isMonitoring = false; 

                    if (this.internalLoggingEnabled) { 
                        SimulationLogger.logInfo("MIDST_PORT_DEBUG", "SIGNAL_CONGESTION_END,Port=" + getOwnId() + "->" + getTargetId() + ",Action=StoppingMIDSTMonitoring,QueueSizePackets=" + getQueue().size() + ",Time=" + Simulator.getCurrentTime());
                    }

                    if (!testMode) {
                        SimulationLogger.logInfo("MIDST_MONITOR", "END,Time=" + Simulator.getCurrentTime() +
                                ",Port=" + getOwnId() + "->" + getTargetId() + ",QPkts=" + getQueue().size());
                    }
                    if (this.internalLoggingEnabled) {
                        System.out.println("Port " + getOwnId() + "->" + getTargetId() + " STOP MONITORING at " + Simulator.getCurrentTime() + " (Queue signal)");
                    }

                    // PASTE TRY-CATCH BLOCK HERE
                    try {
                        Map<Long, Integer> congestedFlowData = getCongestedFlows(this.midstBurstThreshold);

                        if (this.internalLoggingEnabled) { 
                            SimulationLogger.logInfo("MIDST_PORT_DEBUG", "GET_CONGESTED_FLOWS_RESULT,Port=" + getOwnId() + "->" + getTargetId() + ",CongestedFlowCount=" + congestedFlowData.size() + ",CongestedFlows=" + congestedFlowData.keySet().toString() + ",ThresholdUsed=" + this.midstBurstThreshold + ",Time=" + Simulator.getCurrentTime());
                        }

                        if (!congestedFlowData.isEmpty()) {
                            if (this.internalLoggingEnabled) {
                                SimulationLogger.logInfo("MIDST_DEBUG_OUTPUTPORT_APPLY_CONGESTION_FEEDBACK", "PenalizedFlows=" + congestedFlowData.keySet());
                                SimulationLogger.logInfo("MIDST_PORT_DEBUG", "APPLYING_FEEDBACK,Port=" + getOwnId() + "->" + getTargetId() + ",FlowsToPenalize=" + congestedFlowData.keySet().toString() + ",Time=" + Simulator.getCurrentTime());
                            }
                            monitoredQueue.applyCongestionFeedback(congestedFlowData.keySet());
                        } else {
                            if (this.internalLoggingEnabled) {
                                SimulationLogger.logInfo("MIDST_DEBUG_OUTPUTPORT_APPLY_CONGESTION_FEEDBACK", "PenalizedFlows=EMPTY");
                                SimulationLogger.logInfo("MIDST_PORT_DEBUG", "APPLYING_EMPTY_FEEDBACK,Port=" + getOwnId() + "->" + getTargetId() + ",Reason=NoFlowsExceededThreshold" + ",Time=" + Simulator.getCurrentTime());
                            }
                            monitoredQueue.applyCongestionFeedback(Collections.emptySet());
                        }
                        resetDirtySketchEntries();

                    } catch (Exception e) {
                        // PASTE THIS EXCEPTION LOGGING
                        System.err.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                        System.err.println("EXCEPTION in MIDSTCapableOutputPort.signalCongestionEnd() for port " + getOwnId() + "->" + getTargetId() + " at time " + Simulator.getCurrentTime());
                        e.printStackTrace(System.err); // Print stack trace to console standard error
                        SimulationLogger.logInfo("MIDST_PORT_ERROR", "EXCEPTION_IN_SIGNAL_CONGESTION_END,Port=" + getOwnId() + "->" + getTargetId() + ",Time=" + Simulator.getCurrentTime() + ",ExceptionType=" + e.getClass().getName() + ",Message=" + e.getMessage());
                        System.err.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                    }
                    // END OF TRY-CATCH BLOCK

                }
            }
        } else if (this.internalLoggingEnabled) {
            SimulationLogger.logInfo("MIDST_PORT_DEBUG", "SIGNAL_CONGESTION_END_REDUNDANT,Port=" + getOwnId() + "->" + getTargetId() + ",Time=" + Simulator.getCurrentTime());
            System.out.println("Port " + getOwnId() + "->" + getTargetId() + " Received redundant END signal.");
        }
    }

    // --- MIDST Core Methods ---
    // (Remains the same as the 'new unaligned code', just update logging checks)

    /** Resets only the dirty flags, not the sketch values. Called at START of monitoring. */
    protected void resetDirtyFlags() {
        for (int i = 0; i < sketchSize * numHashFunctions; i++) {
            sinDirtyFlags.set(i, 0);
            soutDirtyFlags.set(i, 0);
        }
        if (this.internalLoggingEnabled) System.out.println("Port " + getOwnId() + "->" + getTargetId() + " Reset dirty flags.");
    }

    /** Resets sketch values for entries marked as dirty. Called at END of monitoring. */
    protected void resetDirtySketchEntries() {
        int resetCountSin = 0;
        int resetCountSout = 0;
        for (int i = 0; i < sin.length(); i++) {
            if (sinDirtyFlags.compareAndSet(i, 1, 0)) { // Atomically check and reset flag
                sin.set(i, 0); // Reset sketch value
                resetCountSin++;
            }
        }
        for (int i = 0; i < sout.length(); i++) {
            if (soutDirtyFlags.compareAndSet(i, 1, 0)) { // Atomically check and reset flag
                sout.set(i, 0); // Reset sketch value
                resetCountSout++;
            }
        }

        // LOW #11: Track sketch reset count
        sketchResetCount++;
        SimulationLogger.increaseStatisticCounter("MIDST_SKETCH_RESETS");
        if (this.internalLoggingEnabled) {
            SimulationLogger.logInfo("SKETCH_RESET",
                sketchResetCount + "," + resetCountSin + "," + resetCountSout + "," + Simulator.getCurrentTime());
        }

        if (this.internalLoggingEnabled) System.out.println("Port " + getOwnId() + "->" + getTargetId() + " Reset dirty sketch entries: Sin=" + resetCountSin + ", Sout=" + resetCountSout);
    }

    /** Calculates hash indices (flat array mapping) for a packet/flow. */
    public int[] getHashIndices(Packet packet) {
        return getHashIndices(packet.getFlowId());
    }

    /** Calculates hash indices (flat array mapping) for a flow ID. */
    protected int[] getHashIndices(long flowId) {
        int[] flatIndices = new int[numHashFunctions];
        long hashInput = flowId; // Use local variable for clarity
        for (int i = 0; i < numHashFunctions; i++) {
            int seed = i * 0x9e3779b9; // Simple seed variation
            int hashValue = Math.abs(Long.hashCode(hashInput ^ seed) % sketchSize); // Index within virtual row [0, sketchSize)
            int flatIndex = i * sketchSize + hashValue; // Map to flat array index

            // Basic bounds check (defensive programming)
            if (flatIndex < 0 || flatIndex >= sketchSize * numHashFunctions) {
                System.err.println("CRITICAL HASH INDEX ERROR: Port=" + getOwnId() + "->" + getTargetId() + " Flow=" + flowId +
                                   " Func=" + i + ", HashVal=" + hashValue + ", FlatIdx=" + flatIndex + ", Max=" + (sketchSize * numHashFunctions));
                // Simple recovery, might distort results but prevents crash
                flatIndex = Math.abs(flatIndex % (sketchSize * numHashFunctions));
            }
            flatIndices[i] = flatIndex;
        }
        return flatIndices;
    }

    /** Updates Ingress Sketch (Sin) and stores metadata when a packet arrives. */
    protected void updateIngressSketch(Packet packet) {
        if (!isMonitoring) {
            return; // Do nothing if not actively monitoring
        }

        long flowId = packet.getFlowId();
        activeFlowsInWindow.add(flowId); // Track flow seen in this window

        // HIGH #5: Track true flow packet count for CMS accuracy measurement
        trueFlowPacketCounts.compute(flowId, (k, v) -> (v == null) ? 1 : v + 1);

        int[] flatIndices = getHashIndices(packet);
        int[] counts = new int[numHashFunctions]; // Store counts *after* incrementing

        // LOW #12: Track potential collisions
        int trueCountForFlow = trueFlowPacketCounts.get(flowId);

        for (int i = 0; i < flatIndices.length; i++) {
            int flatIndex = flatIndices[i];

            // Mark the entry as dirty if it's not already (ensures reset at end)
            sinDirtyFlags.compareAndSet(flatIndex, 0, 1); // Set flag if 0, ignore if already 1

            int countBeforeIncrement = sin.get(flatIndex);
            int currentCount = sin.incrementAndGet(flatIndex);
            counts[i] = currentCount; // Store the new count

            // LOW #12: Detect collision - if count before increment exceeds our true count,
            // another flow has contributed to this bucket
            if (countBeforeIncrement >= trueCountForFlow) {
                totalCollisions++;
            }
            totalHashOps++;

            // Optional internal logging
            // if (this.internalLoggingEnabled) {
            //    System.out.println("Ingress: Flow " + flowId + ", Index " + flatIndex + ", New Sin=" + currentCount);
            // }
        }

        // Store metadata associated with the packet object
        // Assuming PacketMetadata_MIDST constructor takes (counts, flatIndices)
        packetMetadataMap.put(packet, new PacketMetadata_MIDST(counts, flatIndices));
    }

    /** Updates Egress Sketch (Sout) when a packet is successfully dispatched. */
    protected void updateEgressSketch(Packet packet) {
        // Remove metadata first to ensure atomicity regarding the packet's lifecycle in the map
        PacketMetadata_MIDST metadata = packetMetadataMap.remove(packet);

        if (metadata == null) {
            // Packet might have arrived before monitoring started, or was dropped. This is expected.
            // Only log if potentially unexpected (e.g., during monitoring where metadata *should* exist)
            if (this.internalLoggingEnabled && isMonitoring) {
                 System.out.println("Egress: Warning - No metadata for dispatched packet flow " + packet.getFlowId() + " (Monitoring=" + isMonitoring + ")");
            }
            return; // Exit if no metadata
        }

        int[] flatIndices = metadata.getHashIndices(); // Get indices from stored metadata

        for (int flatIndex : flatIndices) {
            // Mark the corresponding *sout* entry as dirty if monitoring is *currently* active.
            // This ensures the sout entries used during the window are reset later.
            if (isMonitoring) {
                 soutDirtyFlags.compareAndSet(flatIndex, 0, 1);
            }

            // Increment egress count. This happens regardless of current monitoring state,
            // as it corresponds to a packet that *did* have ingress metadata.
            int currentCount = sout.incrementAndGet(flatIndex);

            // Optional internal logging
            // if (this.internalLoggingEnabled) {
            //    System.out.println("Egress: Flow " + packet.getFlowId() + ", Index " + flatIndex + ", New Sout=" + currentCount);
            // }
        }
    }

    /** Estimate current size for a given flow using the Ingress sketch. */
    protected int estimateFlowSizeIngress(long flowId) {
        int estimatedSize = Integer.MAX_VALUE;
        int[] flatIndices = getHashIndices(flowId);
        for (int flatIndex : flatIndices) {
            // Read the current count from the ingress sketch
            int count = sin.get(flatIndex);
            estimatedSize = Math.min(estimatedSize, count);
        }
        // Ensure non-negative estimate
        return Math.max(0, estimatedSize);
    }

    /** Get flows seen in the last window whose estimated ingress size exceeds a threshold. */
    protected Map<Long, Integer> getCongestedFlows(int threshold) {
    	if (this.internalLoggingEnabled) { SimulationLogger.logInfo("MIDST_PORT_DEBUG", "GET_CONGESTED_FLOWS_ENTRY,Port=" + getOwnId() + "->" + getTargetId() + ",Threshold=" + threshold + ",ActiveFlowsInWindowCount=" + activeFlowsInWindow.size() + ",Time=" + Simulator.getCurrentTime()); }
        Map<Long, Integer> congestedFlows = new HashMap<>();
     // PASTE THIS BLOCK:
        if (this.internalLoggingEnabled) {
            SimulationLogger.logInfo("MIDST_PORT_DEBUG", "GET_CONGESTED_FLOWS_START_ANALYSIS,Port=" + getOwnId() + "->" + getTargetId() +
                                        ",ActiveFlowsInWindowCount=" + activeFlowsInWindow.size() +
                                        ",Threshold=" + threshold +
                                        ",Time=" + Simulator.getCurrentTime());
        }

        // HIGH #5: Calculate CMS accuracy for this monitoring window
        long totalAbsError = 0;
        long totalTrueCount = 0;
        int flowsCompared = 0;

        // Iterate only over flows actually seen during this monitoring window
        for (Long flowId : activeFlowsInWindow) {
            int estimatedSize = estimateFlowSizeIngress(flowId);

            // HIGH #5: Compare estimated vs true count for accuracy
            Integer trueCount = trueFlowPacketCounts.get(flowId);
            if (trueCount != null && trueCount > 0) {
                int absError = Math.abs(estimatedSize - trueCount);
                totalAbsError += absError;
                totalTrueCount += trueCount;
                flowsCompared++;

                // Log individual accuracy measurement
                if (this.internalLoggingEnabled) {
                    SimulationLogger.logInfo("CMS_ACCURACY",
                        flowId + "," + trueCount + "," + estimatedSize + "," + absError + "," + Simulator.getCurrentTime());
                }
            }

         // PASTE THIS BLOCK:
            if (this.internalLoggingEnabled && estimatedSize > 0) { // Log only potentially interesting flows (or remove estimatedSize > 0 for all)
                SimulationLogger.logInfo("MIDST_PORT_DEBUG", "GET_CONGESTED_FLOWS_FLOW_ESTIMATE,Port=" + getOwnId() + "->" + getTargetId() +
                                           ",FlowID=" + flowId + ",EstimatedSize=" + estimatedSize +
                                           ",Threshold=" + threshold + // Also log threshold here for easy comparison
                                           ",Time=" + Simulator.getCurrentTime());
            }
            if (estimatedSize >= threshold) {
                congestedFlows.put(flowId, estimatedSize);
                if (this.internalLoggingEnabled) {
                    System.out.println("Port " + getOwnId() + "->" + getTargetId() + " Flow " + flowId + " identified as congested (EstSize=" + estimatedSize + ", Threshold=" + threshold + ")");
                 // PASTE THIS LINE:
                    SimulationLogger.logInfo("MIDST_PORT_DEBUG", "GET_CONGESTED_FLOWS_FLOW_IDENTIFIED,Port=" + getOwnId() + "->" + getTargetId() + ",FlowID=" + flowId + ",EstimatedSize=" + estimatedSize + ",Threshold=" + threshold + ",Time=" + Simulator.getCurrentTime());
                }
            }
        }

        // HIGH #5: Log aggregate CMS accuracy for this window
        if (flowsCompared > 0 && this.internalLoggingEnabled) {
            double avgError = (double) totalAbsError / flowsCompared;
            double relativeError = totalTrueCount > 0 ? (double) totalAbsError / totalTrueCount : 0;
            SimulationLogger.logInfo("CMS_ACCURACY_WINDOW",
                flowsCompared + "," + avgError + "," + relativeError + "," + Simulator.getCurrentTime());
        }

        // Clear true counts for next window
        trueFlowPacketCounts.clear();

        // LOW #12: Log hash collision estimate for this window
        if (totalHashOps > 0 && this.internalLoggingEnabled) {
            double collisionRate = (double) totalCollisions / totalHashOps;
            SimulationLogger.logInfo("CMS_HASH_COLLISIONS",
                totalCollisions + "," + totalHashOps + "," + collisionRate + "," + Simulator.getCurrentTime());
        }
        // Reset collision counters for next window
        totalCollisions = 0;
        totalHashOps = 0;

        return congestedFlows;
    }


 // In MIDSTCapableOutputPort.java
 // VV REPLACE YOUR ENTIRE ENQUEUE METHOD WITH THIS VV
 @Override
 public void enqueue(Packet packet) {
     SimulationLogger.increaseStatisticCounter("TOTAL_PACKETS_ENQUEUED");
     long flowId = packet.getFlowId();
     String portId = getOwnId() + "->" + getTargetId();

     // =========================================================================
     // NEW: Virtual Queue Trigger - MUST be called BEFORE any admission control!
     // This tracks all arrivals to build the virtual queue, which is how MIDST
     // detects congestion for ALL scheduler types (SP-PIFO, AIFO, PACKS).
     // =========================================================================
     midst.onPacketArrival(packet);

     if (this.internalLoggingEnabled) {
         SimulationLogger.logInfo("MIDST_PORT_ENQUEUE_DEBUG", "ENTRY,Port=" + portId + ",Flow=" + flowId +
                                 ",PktSizeBits=" + packet.getSizeBit() +
                                 ",BufferOccupiedBits=" + getBufferOccupiedBits() +
                                 ",MaxQueueSizeBits=" + this.maxQueueSizeBits +
                                 ",IsSending=" + getIsSending() + // Log current sending state
                                 ",DEBUG_FORCE_Q_ACTIVE=" + DEBUG_FORCE_ALL_PACKETS_TO_QUEUE + // Log debug flag state
                                 ",MIDSTVirtualQ=" + midst.getVirtualQueueDepthBits() + // Log MIDST virtual queue
                                 ",MIDSTMonitoring=" + midst.isMonitoring() + // Log MIDST monitoring state
                                 ",Time=" + Simulator.getCurrentTime());
     }

     if (this.internalLoggingEnabled) {
    	    SimulationLogger.logInfo("QUEUE_MAPPING_DEBUG",
    	        "FLOW_TO_QUEUE_MAPPING,Flow=" + flowId +
    	        ",Port=" + getOwnId() + "->" + getTargetId() +
    	        ",QueueHash=" + monitoredQueue.hashCode() +
    	        ",PacketHash=" + packet.hashCode() +
    	        ",Time=" + Simulator.getCurrentTime());
    	}

     updateIngressSketch(packet); // Legacy sketch update (kept for comparison)

     // PORT-LEVEL VFL: Bursty flows see a smaller effective buffer
     // Non-bursty flows see the full buffer. This converts rank-blind tail-drop
     // into MIDST-aware selective drop — the missing "lever 0" for SP-PIFO.
     boolean isBurstyFlow = midst.isFlowBursty(flowId);
     long effectiveMaxBits = isBurstyFlow ? this.portVflBurstyMaxBits : this.maxQueueSizeBits;

     if (isBurstyFlow && this.portVflBurstyMaxBits < this.maxQueueSizeBits) {
         // Log port-level VFL activation
         if (this.internalLoggingEnabled) {
             SimulationLogger.logInfo("MIDST_PORT_VFL_DEBUG", "PORT_VFL_CHECK,Port=" + portId +
                 ",Flow=" + flowId + ",IsBursty=true" +
                 ",EffectiveMaxBits=" + effectiveMaxBits +
                 ",RealMaxBits=" + this.maxQueueSizeBits +
                 ",BufferOccupied=" + getBufferOccupiedBits() +
                 ",Time=" + Simulator.getCurrentTime());
         }
         // Track port-level VFL drops vs normal drops
         if (getBufferOccupiedBits() + packet.getSizeBit() > effectiveMaxBits &&
             getBufferOccupiedBits() + packet.getSizeBit() <= this.maxQueueSizeBits) {
             // This packet would have been admitted without port VFL — count as VFL drop
             SimulationLogger.increaseStatisticCounter("MIDST_PORT_VFL_DROPS");
         }
     }

     if (getBufferOccupiedBits() + packet.getSizeBit() <= effectiveMaxBits) {
         // Packet fits the effective buffer (full buffer for normal, reduced for bursty)

         if (DEBUG_FORCE_ALL_PACKETS_TO_QUEUE) {
             // --- DEBUG PATH: Force offer to SPPIFO queue to test SPPIFO ranking ---
             if (this.internalLoggingEnabled) {
                 SimulationLogger.logInfo("MIDST_PORT_ENQUEUE_DEBUG", "DEBUG_PATH_FORCING_OFFER_TO_QUEUE,Port=" + portId + ",Flow=" + flowId + ",OriginalIsSending=" + getIsSending() + ",Time=" + Simulator.getCurrentTime());
             }

             boolean wasPreviouslyIdleAndQueueEmpty = !getIsSending() && getQueue().isEmpty();

             boolean offered = monitoredQueue.offer(packet); // Offer to SPPIFO queue

             if (offered) {
                 increaseBufferOccupiedBits(packet.getSizeBit()); 
                 getLogger().logQueueState(getQueue().size(), getBufferOccupiedBits(), Simulator.getCurrentTime());

                 if (wasPreviouslyIdleAndQueueEmpty) { 
                     // If port was idle AND the queue was empty before we added this packet,
                     // we need to kick-start the dispatch process for the item at the head of the queue.
                     // This mimics the port becoming free and then checking its (now non-empty) queue.
                     if (!getQueue().isEmpty()) { // Should be true since 'offered' is true
                         setIsSending(true); // Port is now going to be busy sending from its queue.
                         getLogger().logLinkUtilized(true); // Link will be utilized.

                         Packet packetToDispatch = getQueue().peek(); // Get head of queue 
                                                                     // (should be the packet just offered).

                         if (packetToDispatch != null) { 
                             if (this.internalLoggingEnabled) {
                                 SimulationLogger.logInfo("MIDST_PORT_ENQUEUE_DEBUG", "DEBUG_PATH_KICKSTART_DISPATCH,Port=" + portId + ",Flow=" + packetToDispatch.getFlowId() + ",PktToDispatchHash=" + packetToDispatch.hashCode() + ",Time=" + Simulator.getCurrentTime());
                             }
                             // Schedule event for when this packet *finishes* transmission.
                             // The PacketDispatchedEvent handler will call this.dispatch(packetToDispatch),
                             // which will then poll() this packet from the queue.
                             Simulator.registerEvent(new PacketDispatchedEvent(
                                     (long) ((double) packetToDispatch.getSizeBit() / getLink().getBandwidthBitPerNs()),
                                     packetToDispatch, 
                                     this
                             ));
                         } else {
                             // This case (offered true, but queue peek is null) is highly unlikely.
                             setIsSending(false); // Revert if something went wrong.
                             getLogger().logLinkUtilized(false);
                             if (this.internalLoggingEnabled) {
                                  SimulationLogger.logInfo("MIDST_PORT_ENQUEUE_DEBUG", "DEBUG_PATH_KICKSTART_WARN_QUEUE_EMPTY_AFTER_OFFER,Port=" + portId + ",Flow=" + flowId + ",Time=" + Simulator.getCurrentTime());
                             }
                         }
                     }
                 }
                 // If port was already sending, or if it was idle but queue was NOT empty,
                 // the existing dispatch mechanism (when current packet finishes) will handle polling the queue.
             } else {
                 // SPPIFO Queue's offer() returned false
                 if (this.internalLoggingEnabled) {
                      SimulationLogger.logInfo("MIDST_PORT_ENQUEUE_DEBUG", "QUEUE_REJECTED_PACKET,Port=" + portId + ",Flow=" + flowId + ",Time=" + Simulator.getCurrentTime());
                 }
                 // NEW: Notify MIDST of dropped packet for loss tracking
                 midst.onPacketDropped(packet);
                 SimulationLogger.increaseStatisticCounter("PACKETS_DROPPED_QUEUE_REJECT");
                 // C2: Split drop counter by bursty ground truth
                 trackBurstyDropCounter(flowId);
                 if (this.internalLoggingEnabled) {
                     SimulationLogger.logInfo("PACKET_DROP_QUEUE_REJECT", flowId + "," + Simulator.getCurrentTime() + "," + packet.getSizeBit() + "," + portId);
                 }
                 packetMetadataMap.remove(packet);
             }

         } else { // Normal operation (DEBUG_FORCE_ALL_PACKETS_TO_QUEUE is false)
             // --- NORMAL PATH: Original enqueue logic ---
             if (!getIsSending()) {
                 // Immediate dispatch - packet goes straight to wire, NOT queued
                 // So we do NOT increase buffer (matches EcnTailDropOutputPort behavior)
                 if (this.internalLoggingEnabled) {
                     SimulationLogger.logInfo("MIDST_PORT_ENQUEUE_DEBUG", "IMMEDIATE_DISPATCH,Port=" + portId + ",Flow=" + flowId + ",Time=" + Simulator.getCurrentTime());
                 }
                 getLogger().logLinkUtilized(true);
                 setIsSending(null);
                 // NOTE: Do NOT call increaseBufferOccupiedBits here - packet is sent immediately, not queued
                 // The dispatch() method only decreases buffer for packets polled from queue
                 Simulator.registerEvent(new PacketDispatchedEvent(
                     (long) ((double) packet.getSizeBit() / getLink().getBandwidthBitPerNs()),
                     packet,
                     this
                 ));
             } else { 
                 if (this.internalLoggingEnabled) {
                     SimulationLogger.logInfo("MIDST_PORT_ENQUEUE_DEBUG", "OFFERING_TO_QUEUE,Port=" + portId + ",Flow=" + flowId + ",Time=" + Simulator.getCurrentTime());
                 }
                 boolean offered = monitoredQueue.offer(packet);
                 if (offered) {
                     increaseBufferOccupiedBits(packet.getSizeBit()); 
                     getLogger().logQueueState(getQueue().size(), getBufferOccupiedBits(), Simulator.getCurrentTime());
                 } else {
                     if (this.internalLoggingEnabled) {
                         SimulationLogger.logInfo("MIDST_PORT_ENQUEUE_DEBUG", "QUEUE_REJECTED_PACKET,Port=" + portId + ",Flow=" + flowId + ",Time=" + Simulator.getCurrentTime());
                     }
                     // NEW: Notify MIDST of dropped packet for loss tracking
                     midst.onPacketDropped(packet);
                     SimulationLogger.increaseStatisticCounter("PACKETS_DROPPED_QUEUE_REJECT");
                     // C2: Split drop counter by bursty ground truth
                     trackBurstyDropCounter(flowId);
                     if (this.internalLoggingEnabled) {
                         SimulationLogger.logInfo("PACKET_DROP_QUEUE_REJECT", flowId + "," + Simulator.getCurrentTime() + "," + packet.getSizeBit() + "," + portId);
                     }
                     packetMetadataMap.remove(packet);
                 }
             }
         }
     } else {
         // Port's byte buffer is full.
         if (this.internalLoggingEnabled) {
             SimulationLogger.logInfo("MIDST_PORT_ENQUEUE_DEBUG", "PORT_BUFFER_FULL_DROP,Port=" + portId + ",Flow=" + flowId +
                                     ",PktSizeBits=" + packet.getSizeBit() +
                                     ",BufferOccupiedBits=" + getBufferOccupiedBits() +
                                     ",MaxQueueSizeBits=" + this.maxQueueSizeBits +
                                     ",Time=" + Simulator.getCurrentTime());
         }
         // NEW: Notify MIDST of dropped packet for loss tracking
         midst.onPacketDropped(packet);
         SimulationLogger.increaseStatisticCounter("PACKETS_DROPPED");
         // C2: Split drop counter by bursty ground truth
         trackBurstyDropCounter(flowId);
         if (this.internalLoggingEnabled) {
             SimulationLogger.logInfo("PACKET_DROP",
                 flowId + "," + Simulator.getCurrentTime() + "," + packet.getSizeBit() + "," + portId);
         }
         packetMetadataMap.remove(packet);
     }

     // Congestion detection debug log (guarded to prevent initialization.info bloat)
     if (this.internalLoggingEnabled) {
         SimulationLogger.logInfo("DEBUG_CONGESTION_CHECK",
             "BufferOccupiedBits=" + getBufferOccupiedBits() +
             ",HighWatermarkBits=" + midstHighWatermarkBits +
             ",LowWatermarkBits=" + midstLowWatermarkBits +
             ",Detected=" + midstCongestionDetected +
             ",Time=" + Simulator.getCurrentTime());
     }
     // MED #9: Track bytes during congestion
     if (midstCongestionDetected) {
         currentCongestionBytes += packet.getSizeBit() / 8; // Convert bits to bytes
     }

    // MIDST congestion detection and feedback
     // Use VIRTUAL QUEUE depth for congestion detection — the virtual queue tracks
     // all arrivals (including packets rejected by admission control), so it reflects
     // true traffic demand. Without this, PACKS/AIFO admission control keeps the real
     // buffer low, preventing watermark-based detection from ever triggering.
     long congestionDepthBits = midst.getVirtualQueueDepthBits();
     if (!midstCongestionDetected && congestionDepthBits >= midstHighWatermarkBits) {
         midstCongestionDetected = true;
         currentCongestionBytes = 0; // Reset for new congestion episode
         Map<Long, Integer> congestedFlows = getCongestedFlows(midstBurstThreshold);
         SimulationLogger.logInfo("MIDST_PORT_CONGESTION_START",
             "Port=" + getOwnId() + "->" + getTargetId() +
             ",VirtualQueueBits=" + congestionDepthBits +
             ",BufferOccupiedBits=" + getBufferOccupiedBits() +
             ",HighWatermarkBits=" + midstHighWatermarkBits +
             ",Time=" + Simulator.getCurrentTime());
         monitoredQueue.applyCongestionFeedback(congestedFlows.keySet());
     }
     // End congestion monitoring if virtual queue drops below low watermark
     if (midstCongestionDetected && congestionDepthBits <= midstLowWatermarkBits) {
         midstCongestionDetected = false;

         // MED #9: Log bytes during this congestion episode
         bytesDuringCongestion += currentCongestionBytes;
         SimulationLogger.logInfo("BYTES_DURING_CONGESTION_EPISODE",
             currentCongestionBytes + "," + Simulator.getCurrentTime());
         SimulationLogger.increaseStatisticCounter("MIDST_BYTES_DURING_CONGESTION");

         SimulationLogger.logInfo("MIDST_PORT_CONGESTION_END",
             "Port=" + getOwnId() + "->" + getTargetId() +
             ",VirtualQueueBits=" + congestionDepthBits +
             ",BufferOccupiedBits=" + getBufferOccupiedBits() +
             ",LowWatermarkBits=" + midstLowWatermarkBits +
             ",BytesDuringEpisode=" + currentCongestionBytes +
             ",Time=" + Simulator.getCurrentTime());
         monitoredQueue.applyCongestionFeedback(Collections.emptySet());
     }
 }
 
    @Override
    public void dispatch(Packet dispatchedPacket) {
        // =========================================================================
        // NEW: Virtual Queue Trigger - track departures for virtual queue
        // This is how MIDST knows when congestion has eased (virtual queue drains)
        // =========================================================================
        midst.onPacketDeparture(dispatchedPacket);

        // 1. Update egress sketch (removes metadata internally)
        updateEgressSketch(dispatchedPacket);

        // 2. Call superclass dispatch to handle sending and dequeuing logic
        super.dispatch(dispatchedPacket);
    }

     // --- Utility Methods ---

     // Expose monitoring state if needed by external components or tests
     public boolean isMonitoring() {
         return isMonitoring;
     }

    /**
     * Get the MIDST black box module.
     * Queues can use this to query bursty flow status for admission decisions.
     *
     * @return The MIDST instance for this port
     */
    public MIDST getMIDST() {
        return midst;
    }

    /**
     * C2: Track split drop counters by ground-truth bursty status.
     * Uses the static registry in NewCanonicalSPPIFOQueue which is populated
     * by MicroburstTrafficPlanner for ALL flows regardless of scheduler.
     */
    private void trackBurstyDropCounter(long flowId) {
        boolean isBurstyGT = NewCanonicalSPPIFOQueue.isFlowBurstyGroundTruth(flowId);
        if (isBurstyGT) {
            SimulationLogger.increaseStatisticCounter("PACKETS_DROPPED_BURSTY_GT");
        } else {
            SimulationLogger.increaseStatisticCounter("PACKETS_DROPPED_NORMAL_GT");
        }
    }
}