package ch.ethz.systems.netbench.xpt.tcpextended.fixedpriority;

import ch.ethz.systems.netbench.core.Simulator;
import ch.ethz.systems.netbench.core.log.SimulationLogger;
import ch.ethz.systems.netbench.core.network.TransportLayer;
import ch.ethz.systems.netbench.core.run.infrastructure.TransportLayerGenerator;

/**
 * Generator for FixedPriorityTransportLayer instances.
 *
 * Configuration properties:
 *
 * Required:
 *   transport_layer=fixed_priority_tcp
 *   priority_mode=flow_id_based | size_bucket | uniform
 *
 * For FLOW_ID_BASED mode:
 *   priority_multiplier=100  (default: 100)
 *
 * For SIZE_BUCKET mode:
 *   priority_small_flow_rank=50    (default: 50)
 *   priority_medium_flow_rank=100  (default: 100)
 *   priority_large_flow_rank=200   (default: 200)
 *   priority_small_threshold_bytes=10000   (default: 10KB)
 *   priority_large_threshold_bytes=100000  (default: 100KB)
 *
 * For UNIFORM mode:
 *   priority_base=100  (default: 100)
 */
public class FixedPriorityTransportLayerGenerator extends TransportLayerGenerator {

    private final PriorityMode priorityMode;
    private final long priorityMultiplier;
    private final long basePriority;
    private final long smallFlowRank;
    private final long mediumFlowRank;
    private final long largeFlowRank;
    private final long smallThresholdBytes;
    private final long largeThresholdBytes;

    public FixedPriorityTransportLayerGenerator() {
        // Parse priority mode
        String modeStr = Simulator.getConfiguration().getPropertyOrFail("priority_mode");
        switch (modeStr.toLowerCase()) {
            case "flow_id_based":
                this.priorityMode = PriorityMode.FLOW_ID_BASED;
                break;
            case "size_bucket":
                this.priorityMode = PriorityMode.SIZE_BUCKET;
                break;
            case "uniform":
                this.priorityMode = PriorityMode.UNIFORM;
                break;
            default:
                throw new IllegalArgumentException(
                    "Invalid priority_mode: " + modeStr +
                    ". Must be one of: flow_id_based, size_bucket, uniform"
                );
        }

        // Read configuration with defaults
        this.priorityMultiplier = Simulator.getConfiguration()
                .getLongPropertyWithDefault("priority_multiplier", 100L);

        this.basePriority = Simulator.getConfiguration()
                .getLongPropertyWithDefault("priority_base", 100L);

        this.smallFlowRank = Simulator.getConfiguration()
                .getLongPropertyWithDefault("priority_small_flow_rank", 50L);

        this.mediumFlowRank = Simulator.getConfiguration()
                .getLongPropertyWithDefault("priority_medium_flow_rank", 100L);

        this.largeFlowRank = Simulator.getConfiguration()
                .getLongPropertyWithDefault("priority_large_flow_rank", 200L);

        this.smallThresholdBytes = Simulator.getConfiguration()
                .getLongPropertyWithDefault("priority_small_threshold_bytes", 10000L);

        this.largeThresholdBytes = Simulator.getConfiguration()
                .getLongPropertyWithDefault("priority_large_threshold_bytes", 100000L);

        // Log configuration
        SimulationLogger.logInfo("Transport layer", "FIXED_PRIORITY_TCP");
        SimulationLogger.logInfo("Priority mode", priorityMode.toString());

        switch (priorityMode) {
            case FLOW_ID_BASED:
                SimulationLogger.logInfo("Priority multiplier", String.valueOf(priorityMultiplier));
                break;
            case SIZE_BUCKET:
                SimulationLogger.logInfo("Small flow rank", String.valueOf(smallFlowRank));
                SimulationLogger.logInfo("Medium flow rank", String.valueOf(mediumFlowRank));
                SimulationLogger.logInfo("Large flow rank", String.valueOf(largeFlowRank));
                SimulationLogger.logInfo("Small threshold (bytes)", String.valueOf(smallThresholdBytes));
                SimulationLogger.logInfo("Large threshold (bytes)", String.valueOf(largeThresholdBytes));
                break;
            case UNIFORM:
                SimulationLogger.logInfo("Base priority", String.valueOf(basePriority));
                break;
        }
    }

    @Override
    public TransportLayer generate(int identifier) {
        return new FixedPriorityTransportLayer(
                identifier,
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
