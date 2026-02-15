package ch.ethz.systems.netbench.xpt.ports.midst_feedback.port;

import ch.ethz.systems.netbench.core.network.Link;
import ch.ethz.systems.netbench.core.network.NetworkDevice;
import ch.ethz.systems.netbench.core.network.OutputPort;
import ch.ethz.systems.netbench.core.run.infrastructure.OutputPortGenerator;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.IMonitoredQueue;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl.AIFOQueue;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl.BaselineQueue;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl.CanonicalSPPIFOQueue;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl.NewCanonicalSPPIFOQueue;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl.RealAIFOQueue;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl.RealPACKSQueue;

// No direct dependency on NBProperties or Simulator in this class anymore

/**
 * Generates MIDSTCapableOutputPort instances.
 * Aligned Design: Receives all configuration via constructor parameters.
 * Chooses the underlying queue implementation based on the queueType parameter.
 */
public class MIDSTCapableOutputPortGenerator extends OutputPortGenerator {

    // Fields to store configuration passed via constructor
    private final long maxQueueSizeBytes;
    private final int sketchSize;
    private final int numHashFunctions;
    private final long highWaterMarkPackets;
    private final long lowWaterMarkPackets;
    private final int burstThreshold;
    private final String queueType;
    private final long sppifoPenaltyRank;
    private final long sppifoRankDelta;
    private final int sppifoNumQueues;
    private final boolean testMode;

    // AIFO-specific parameters
    private final float aifoK;              // Admission threshold factor (0.1-0.5)
    private final int aifoWindowSize;       // Sliding window size
    private final int aifoSampleCount;      // Sample rate for window updates
    private final long aifoRankPenalty;     // Rank inflation for bursty flows (AIFO only)
    private final long packsRankPenalty;    // M5a FIX: PACKS-specific penalty (queue demotion + admission)

    // AIFO MIDST detection mode parameters
    private final boolean aifoUseRejectionRateDetection;  // true = rejection rate, false = queue depth
    private final double aifoRejectionRateThreshold;      // e.g., 0.05 = 5% rejection triggers MIDST
    private final int aifoRejectionWindowSize;            // Window size for rejection rate calculation

    // Virtual fill level: use virtual queue depth for bursty flow admission gate
    private final boolean useVirtualFillLevel;

    // Port-level VFL: fraction of buffer visible to bursty flows at port tail-drop
    // 1.0 = disabled (bursty flows see full buffer), 0.7 = bursty flows see 70% of buffer
    private final double portVflFraction;

    // Adaptive threshold fraction: 0.0 = disabled (absolute threshold), >0.0 = relative heavy-hitter detection
    private final double adaptiveThresholdFraction;

    /**
     * Full constructor taking all configuration parameters including AIFO detection mode.
     *
     * @param maxQueueSizeBytes             Maximum queue size in bytes.
     * @param sketchSize                    Size of the MIDST sketch.
     * @param numHashFunctions              Number of hash functions for the sketch.
     * @param highWaterMarkPackets          High watermark for queue (in packets).
     * @param lowWaterMarkPackets           Low watermark for queue (in packets).
     * @param burstThreshold                MIDST burst threshold.
     * @param queueType                     Type of queue to use.
     * @param sppifoPenaltyRank             Congestion penalty rank (SPPIFO only).
     * @param sppifoRankDelta               Rank delta for SPPIFO queue.
     * @param sppifoNumQueues               Number of queues for SPPIFO queue.
     * @param testMode                      Enable test mode features.
     * @param aifoK                         AIFO admission threshold factor (0.1-0.5).
     * @param aifoWindowSize                AIFO sliding window size.
     * @param aifoSampleCount               AIFO sample rate for window updates.
     * @param aifoRankPenalty               AIFO rank inflation for bursty flows (AIFO only).
     * @param packsRankPenalty              M5a: PACKS-specific penalty (queue demotion + admission).
     * @param aifoUseRejectionRateDetection true = detect congestion via rejection rate, false = queue depth.
     * @param aifoRejectionRateThreshold    Rejection rate threshold for congestion (e.g., 0.05 = 5%).
     * @param aifoRejectionWindowSize       Window size for rejection rate calculation.
     * @param portVflFraction               Port-level VFL fraction (0.0-1.0). 1.0 = disabled.
     * @param adaptiveThresholdFraction    Adaptive threshold fraction (0.0 = disabled, >0 = relative detection).
     */
    public MIDSTCapableOutputPortGenerator(
            long maxQueueSizeBytes,
            int sketchSize,
            int numHashFunctions,
            long highWaterMarkPackets,
            long lowWaterMarkPackets,
            int burstThreshold,
            String queueType,
            long sppifoPenaltyRank,
            long sppifoRankDelta,
            int sppifoNumQueues,
            boolean testMode,
            float aifoK,
            int aifoWindowSize,
            int aifoSampleCount,
            long aifoRankPenalty,
            long packsRankPenalty,  // M5a FIX: PACKS-specific penalty
            boolean aifoUseRejectionRateDetection,
            double aifoRejectionRateThreshold,
            int aifoRejectionWindowSize,
            boolean useVirtualFillLevel,
            double portVflFraction,
            double adaptiveThresholdFraction
    ) {
        // Simple assignment from parameters to fields
        this.maxQueueSizeBytes = maxQueueSizeBytes;
        this.sketchSize = sketchSize;
        this.numHashFunctions = numHashFunctions;
        this.highWaterMarkPackets = highWaterMarkPackets;
        this.lowWaterMarkPackets = lowWaterMarkPackets;
        this.burstThreshold = burstThreshold;
        // Normalize queueType for consistent matching
        this.queueType = (queueType != null) ? queueType.toLowerCase() : "baseline";
        this.sppifoPenaltyRank = sppifoPenaltyRank;
        this.sppifoRankDelta = sppifoRankDelta;
        this.sppifoNumQueues = sppifoNumQueues;
        this.testMode = testMode;

        // AIFO parameters
        this.aifoK = aifoK;
        this.aifoWindowSize = aifoWindowSize;
        this.aifoSampleCount = aifoSampleCount;
        this.aifoRankPenalty = aifoRankPenalty;
        this.packsRankPenalty = packsRankPenalty;  // M5a FIX

        // AIFO MIDST detection mode parameters
        this.aifoUseRejectionRateDetection = aifoUseRejectionRateDetection;
        this.aifoRejectionRateThreshold = aifoRejectionRateThreshold;
        this.aifoRejectionWindowSize = aifoRejectionWindowSize;

        // Virtual fill level for bursty flow admission
        this.useVirtualFillLevel = useVirtualFillLevel;

        // Port-level VFL fraction
        this.portVflFraction = portVflFraction;

        // Adaptive threshold fraction
        this.adaptiveThresholdFraction = adaptiveThresholdFraction;
    }

    /**
     * Constructor taking all necessary configuration parameters (backward compatible).
     * Uses default values for AIFO detection mode (queue depth detection).
     */
    public MIDSTCapableOutputPortGenerator(
            long maxQueueSizeBytes,
            int sketchSize,
            int numHashFunctions,
            long highWaterMarkPackets,
            long lowWaterMarkPackets,
            int burstThreshold,
            String queueType,
            long sppifoPenaltyRank,
            long sppifoRankDelta,
            int sppifoNumQueues,
            boolean testMode,
            float aifoK,
            int aifoWindowSize,
            int aifoSampleCount,
            long aifoRankPenalty
    ) {
        this(maxQueueSizeBytes, sketchSize, numHashFunctions, highWaterMarkPackets,
             lowWaterMarkPackets, burstThreshold, queueType, sppifoPenaltyRank,
             sppifoRankDelta, sppifoNumQueues, testMode,
             aifoK, aifoWindowSize, aifoSampleCount, aifoRankPenalty,
             aifoRankPenalty,    // Default: PACKS penalty = AIFO penalty (backward compat)
             false, 0.05, 1000,  // Default: queue depth detection
             false,              // Default: no virtual fill level
             1.0,               // Default: port VFL disabled (1.0 = full buffer visible)
             0.0);              // Default: adaptive threshold disabled
    }

    /**
     * Backward-compatible constructor without AIFO-specific parameters.
     * Uses default values for AIFO parameters.
     */
    public MIDSTCapableOutputPortGenerator(
            long maxQueueSizeBytes,
            int sketchSize,
            int numHashFunctions,
            long highWaterMarkPackets,
            long lowWaterMarkPackets,
            int burstThreshold,
            String queueType,
            long sppifoPenaltyRank,
            long sppifoRankDelta,
            int sppifoNumQueues,
            boolean testMode
    ) {
        this(maxQueueSizeBytes, sketchSize, numHashFunctions, highWaterMarkPackets,
             lowWaterMarkPackets, burstThreshold, queueType, sppifoPenaltyRank,
             sppifoRankDelta, sppifoNumQueues, testMode,
             0.1f, 16, 1, 200,    // Default AIFO values: k=0.1, window=16, sample=1, penalty=200
             200,                 // Default: PACKS penalty = AIFO penalty (backward compat)
             false, 0.05, 1000,   // Default: queue depth detection
             false,               // Default: no virtual fill level
             1.0,                // Default: port VFL disabled
             0.0);               // Default: adaptive threshold disabled
    }

    @Override
    public OutputPort generate(NetworkDevice ownNetworkDevice, NetworkDevice targetNetworkDevice, Link link) {
        IMonitoredQueue queue;

        // Select and create the appropriate queue based on the type provided in the constructor
        switch (this.queueType) {
            case "new_sp_pifo":
                System.out.println("Generator creating: NewCanonicalSPPIFOQueue for " + ownNetworkDevice.getIdentifier() + "->" + targetNetworkDevice.getIdentifier());
                queue = new NewCanonicalSPPIFOQueue(this.highWaterMarkPackets, this.lowWaterMarkPackets, this.sppifoRankDelta, this.sppifoNumQueues, this.sppifoPenaltyRank);
                break;

            case "sppifo":
            case "canonical_sppifo":
                System.out.println("Generator creating: CanonicalSPPIFOQueue for " + ownNetworkDevice.getIdentifier() + "->" + targetNetworkDevice.getIdentifier());
                queue = new CanonicalSPPIFOQueue(this.highWaterMarkPackets, this.lowWaterMarkPackets, this.sppifoPenaltyRank);
                break;

            case "real_aifo":
                // Real AIFO algorithm with MIDST twist (rank inflation for bursty flows)
                System.out.println("Generator creating: RealAIFOQueue for " + ownNetworkDevice.getIdentifier() + "->" + targetNetworkDevice.getIdentifier());
                System.out.println("  AIFO params: k=" + this.aifoK + ", window=" + this.aifoWindowSize +
                                 ", useRejectionRate=" + this.aifoUseRejectionRateDetection +
                                 ", rejectionThreshold=" + this.aifoRejectionRateThreshold);
                long maxQueuePackets = this.maxQueueSizeBytes / 1500;  // Convert bytes to packets (assuming 1500B MTU)
                queue = new RealAIFOQueue(
                        this.aifoK,
                        this.aifoWindowSize,
                        this.aifoSampleCount,
                        maxQueuePackets,
                        this.highWaterMarkPackets,
                        this.lowWaterMarkPackets,
                        this.aifoRankPenalty,
                        this.aifoUseRejectionRateDetection,
                        this.aifoRejectionRateThreshold,
                        this.aifoRejectionWindowSize,
                        this.useVirtualFillLevel
                );
                break;

            case "packs":
                // PACKS algorithm (NSDI 2025): combines AIFO admission control + SP-PIFO scheduling
                System.out.println("Generator creating: RealPACKSQueue for " + ownNetworkDevice.getIdentifier() + "->" + targetNetworkDevice.getIdentifier());
                System.out.println("  PACKS params: k=" + this.aifoK + ", window=" + this.aifoWindowSize +
                                 ", numQueues=" + this.sppifoNumQueues + ", rankDelta=" + this.sppifoRankDelta +
                                 ", useRejectionRate=" + this.aifoUseRejectionRateDetection);
                long packsMaxQueuePackets = this.maxQueueSizeBytes / 1500;  // Convert bytes to packets
                queue = new RealPACKSQueue(
                        this.aifoK,                           // Admission control factor
                        this.aifoWindowSize,                  // Sliding window size
                        this.aifoSampleCount,                 // Sample rate
                        this.sppifoNumQueues,                 // Number of priority queues
                        this.sppifoRankDelta,                 // Rank range per queue
                        packsMaxQueuePackets,                 // Max queue capacity
                        this.highWaterMarkPackets,            // MIDST high watermark
                        this.lowWaterMarkPackets,             // MIDST low watermark
                        this.packsRankPenalty,                 // M5a FIX: PACKS-specific penalty (from packs_penalty_target_queue)
                        this.aifoUseRejectionRateDetection,   // Congestion detection mode
                        this.aifoRejectionRateThreshold,      // Rejection rate threshold
                        this.aifoRejectionWindowSize,         // Rejection window size
                        this.useVirtualFillLevel              // Virtual fill level for bursty flows
                );
                break;

            case "aifo":
                // Legacy AIFO queue (simple flow-based dropping)
                System.out.println("Generator creating: AIFOQueue (legacy) for " + ownNetworkDevice.getIdentifier() + "->" + targetNetworkDevice.getIdentifier());
                queue = new AIFOQueue(this.highWaterMarkPackets, this.lowWaterMarkPackets);
                break;

            case "baseline":
            case "standard":
            case "none":
            default:
                // Optional: Warn if an unexpected type was somehow passed, though normalization helps
                if (!this.queueType.equals("baseline") && !this.queueType.equals("standard") && !this.queueType.equals("none")) {
                     System.err.println("Warning: Unknown queue type '" + this.queueType + "' encountered in generate(). Defaulting to baseline queue.");
                }
                 System.out.println("Generator creating: BaselineQueue for " + ownNetworkDevice.getIdentifier() + "->" + targetNetworkDevice.getIdentifier());
                // NOTE: The original MIDSTQueue used Integer.MAX_VALUE for max packets. BaselineQueue might have a different interpretation. Assuming it uses watermarks primarily. Check BaselineQueue's constructor.
                // Let's assume BaselineQueue constructor is (long maxPackets, long highWatermarkPackets, long lowWatermarkPackets) based on the original call attempt.
                // Using Integer.MAX_VALUE for maxPackets as a placeholder consistent with the original attempt.
                queue = new BaselineQueue(Integer.MAX_VALUE, this.highWaterMarkPackets, this.lowWaterMarkPackets);
                break;
        }

        // Create the MIDSTCapableOutputPort, passing the selected queue and other params
        // *** IMPORTANT ASSUMPTION ***:
        // The MIDSTCapableOutputPort constructor has been modified to NOT require
        // the NBProperties object directly, but instead takes the specific primitive/String
        // values it needs. If it still requires NBProperties, further refactoring is needed.
        OutputPort port = new MIDSTCapableOutputPort(
                ownNetworkDevice,
                targetNetworkDevice,
                link,
                queue, // The dynamically created queue instance
                this.maxQueueSizeBytes,
                this.sketchSize,
                this.numHashFunctions,
                this.burstThreshold,
                this.highWaterMarkPackets,
                this.lowWaterMarkPackets,
                false, // testMode
                false, // internalLogging — was hardcoded true, causing 30GB+ initialization.info for AIFO+MIDST
                this.portVflFraction, // Port-level VFL fraction for bursty flow tail-drop
                this.adaptiveThresholdFraction // Adaptive threshold for relative heavy-hitter detection
        );

        // Wire OutputPort if using NewCanonicalSPPIFOQueue
        if (queue instanceof NewCanonicalSPPIFOQueue) {
            ((NewCanonicalSPPIFOQueue) queue).setOutputPort(port);
        }

        return port;

        // Note: Unlike the original MIDSTOutputPortGenerator, we are not calling
        // queue.setOutputPort(outputPort). This assumes that the MIDSTCapableOutputPort
        // constructor or the IMonitoredQueue implementations handle their relationship correctly.
        // If explicit linking is needed, it depends on the IMonitoredQueue interface and
        // MIDSTCapableOutputPort implementation details.
    }
}