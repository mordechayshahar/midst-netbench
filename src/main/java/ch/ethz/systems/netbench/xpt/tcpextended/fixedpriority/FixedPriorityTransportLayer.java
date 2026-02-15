package ch.ethz.systems.netbench.xpt.tcpextended.fixedpriority;

import ch.ethz.systems.netbench.core.network.Socket;
import ch.ethz.systems.netbench.core.network.TransportLayer;

/**
 * Transport layer that creates FixedPrioritySocket instances.
 *
 * Configuration parameters are passed from the generator and stored here
 * to be used when creating new sockets.
 */
public class FixedPriorityTransportLayer extends TransportLayer {

    private final PriorityMode priorityMode;
    private final long priorityMultiplier;
    private final long basePriority;
    private final long smallFlowRank;
    private final long mediumFlowRank;
    private final long largeFlowRank;
    private final long smallThresholdBytes;
    private final long largeThresholdBytes;

    /**
     * Create the Fixed Priority transport layer.
     *
     * @param identifier          Parent network device identifier
     * @param priorityMode        How to assign priority
     * @param priorityMultiplier  Multiplier for FLOW_ID_BASED mode
     * @param basePriority        Base priority for UNIFORM mode
     * @param smallFlowRank       Priority for small flows
     * @param mediumFlowRank      Priority for medium flows
     * @param largeFlowRank       Priority for large flows
     * @param smallThresholdBytes Threshold for small flows (bytes)
     * @param largeThresholdBytes Threshold for large flows (bytes)
     */
    public FixedPriorityTransportLayer(
            int identifier,
            PriorityMode priorityMode,
            long priorityMultiplier,
            long basePriority,
            long smallFlowRank,
            long mediumFlowRank,
            long largeFlowRank,
            long smallThresholdBytes,
            long largeThresholdBytes
    ) {
        super(identifier);
        this.priorityMode = priorityMode;
        this.priorityMultiplier = priorityMultiplier;
        this.basePriority = basePriority;
        this.smallFlowRank = smallFlowRank;
        this.mediumFlowRank = mediumFlowRank;
        this.largeFlowRank = largeFlowRank;
        this.smallThresholdBytes = smallThresholdBytes;
        this.largeThresholdBytes = largeThresholdBytes;
    }

    @Override
    protected Socket createSocket(long flowId, int destinationId, long flowSizeByte) {
        return new FixedPrioritySocket(
                this,
                flowId,
                this.identifier,
                destinationId,
                flowSizeByte,
                priorityMode,
                priorityMultiplier,
                basePriority,
                smallFlowRank,
                mediumFlowRank,
                largeFlowRank,
                smallThresholdBytes,
                largeThresholdBytes
        );
    }
}
