package ch.ethz.systems.netbench.xpt.tcpextended.fixedpriority;

import ch.ethz.systems.netbench.core.log.SimulationLogger;
import ch.ethz.systems.netbench.core.network.TransportLayer;
import ch.ethz.systems.netbench.xpt.newreno.newrenotcp.NewRenoTcpSocket;
import ch.ethz.systems.netbench.xpt.tcpbase.FullExtTcpPacket;

/**
 * TCP Socket with configurable fixed priority assignment.
 *
 * Unlike PfabricSocket which assigns priority based on remaining flow size,
 * this socket assigns a FIXED priority to all packets in a flow based on
 * the configured mode:
 *
 * - FLOW_ID_BASED: priority = flowId * multiplier
 * - SIZE_BUCKET: priority based on flow size category (small/medium/large)
 * - UNIFORM: all packets get the same base priority
 *
 * This is useful for:
 * 1. Deterministic testing of SP-PIFO (predictable priorities)
 * 2. MIDST testing where penalty application is the differentiator
 */
public class FixedPrioritySocket extends NewRenoTcpSocket {

    private final PriorityMode priorityMode;
    private final long assignedPriority;

    /**
     * Create a FixedPrioritySocket.
     *
     * @param transportLayer     Transport layer
     * @param flowId             Flow identifier
     * @param sourceId           Source network device identifier
     * @param destinationId      Target network device identifier
     * @param flowSizeByte       Size of the flow in bytes
     * @param priorityMode       How to assign priority
     * @param priorityMultiplier Multiplier for FLOW_ID_BASED mode
     * @param basePriority       Base priority for UNIFORM mode
     * @param smallFlowRank      Priority for small flows in SIZE_BUCKET mode
     * @param mediumFlowRank     Priority for medium flows in SIZE_BUCKET mode
     * @param largeFlowRank      Priority for large flows in SIZE_BUCKET mode
     * @param smallThresholdBytes Threshold for small flows (bytes)
     * @param largeThresholdBytes Threshold for large flows (bytes)
     */
    public FixedPrioritySocket(
            TransportLayer transportLayer,
            long flowId,
            int sourceId,
            int destinationId,
            long flowSizeByte,
            PriorityMode priorityMode,
            long priorityMultiplier,
            long basePriority,
            long smallFlowRank,
            long mediumFlowRank,
            long largeFlowRank,
            long smallThresholdBytes,
            long largeThresholdBytes
    ) {
        super(transportLayer, flowId, sourceId, destinationId, flowSizeByte);
        this.priorityMode = priorityMode;

        // Calculate the fixed priority for this flow based on mode
        this.assignedPriority = calculatePriority(
                flowId, flowSizeByte, priorityMode,
                priorityMultiplier, basePriority,
                smallFlowRank, mediumFlowRank, largeFlowRank,
                smallThresholdBytes, largeThresholdBytes
        );

        // Log priority assignment for verification
        SimulationLogger.logInfo("PRIORITY_ASSIGNED",
            "flowId=" + flowId +
            ",priority=" + assignedPriority +
            ",mode=" + priorityMode +
            ",flowSizeBytes=" + flowSizeByte);
    }

    /**
     * Calculate the priority for this flow based on the configured mode.
     */
    private static long calculatePriority(
            long flowId,
            long flowSizeByte,
            PriorityMode mode,
            long priorityMultiplier,
            long basePriority,
            long smallFlowRank,
            long mediumFlowRank,
            long largeFlowRank,
            long smallThresholdBytes,
            long largeThresholdBytes
    ) {
        switch (mode) {
            case FLOW_ID_BASED:
                return flowId * priorityMultiplier;

            case SIZE_BUCKET:
                if (flowSizeByte < smallThresholdBytes) {
                    return smallFlowRank;
                } else if (flowSizeByte < largeThresholdBytes) {
                    return mediumFlowRank;
                } else {
                    return largeFlowRank;
                }

            case UNIFORM:
            default:
                return basePriority;
        }
    }

    /**
     * Create a packet with the assigned fixed priority.
     * This overrides the parent class to set the priority field.
     */
    @Override
    protected FullExtTcpPacket createPacket(
            long dataSizeByte,
            long sequenceNumber,
            long ackNumber,
            boolean ACK,
            boolean SYN,
            boolean ECE
    ) {
        return new FullExtTcpPacket(
                flowId, dataSizeByte, sourceId, destinationId,
                100, 80, 80, // TTL, source port, destination port
                sequenceNumber, ackNumber, // Seq number, Ack number
                false, false, ECE, // NS, CWR, ECE
                false, ACK, false, // URG, ACK, PSH
                false, SYN, false, // RST, SYN, FIN
                congestionWindow, // Window size (inherited from parent)
                assignedPriority // FIXED PRIORITY for all packets in this flow
        );
    }

    /**
     * Get the assigned priority for this socket (for debugging/testing).
     */
    public long getAssignedPriority() {
        return assignedPriority;
    }

    /**
     * Get the priority mode for this socket (for debugging/testing).
     */
    public PriorityMode getPriorityMode() {
        return priorityMode;
    }
}
