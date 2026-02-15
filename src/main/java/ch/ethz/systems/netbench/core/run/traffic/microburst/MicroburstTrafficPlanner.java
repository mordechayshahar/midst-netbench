package ch.ethz.systems.netbench.core.run.traffic.microburst;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import ch.ethz.systems.netbench.core.Simulator;
import ch.ethz.systems.netbench.core.log.SimulationLogger;
import ch.ethz.systems.netbench.core.network.TransportLayer;
import ch.ethz.systems.netbench.core.run.traffic.FlowStartEvent;
import ch.ethz.systems.netbench.core.run.traffic.TrafficPlanner;
import ch.ethz.systems.netbench.ext.poissontraffic.flowsize.FlowSizeDistribution;
import ch.ethz.systems.netbench.ext.poissontraffic.flowsize.PFabricDataMiningLowerBoundFSD;
import ch.ethz.systems.netbench.ext.poissontraffic.flowsize.PFabricDataMiningUpperBoundFSD;
import ch.ethz.systems.netbench.ext.poissontraffic.flowsize.PFabricWebSearchLowerBoundFSD;
import ch.ethz.systems.netbench.ext.poissontraffic.flowsize.PFabricWebSearchUpperBoundFSD;
import ch.ethz.systems.netbench.ext.poissontraffic.flowsize.ParetoFSD;
import ch.ethz.systems.netbench.ext.poissontraffic.flowsize.UniformFSD;

public class MicroburstTrafficPlanner extends TrafficPlanner {

    private final Map<Integer, TransportLayer> idToTransportLayer;
    private final double steadyLambda;        // Steady traffic rate (flows per second)
    private final double burstLambda;         // Burst traffic rate (flows per second)
    private final long burstIntervalNs;       // Interval between bursts (nanoseconds)
    private final long burstDurationNs;       // Duration of each burst (nanoseconds)
    private final FlowSizeDistribution flowSizeDistribution;
    private final Random rng;
    private final List<Integer> hostIdList;
    private final int numHosts;
    private final double burstFraction; // Fraction of flows in burst
    private List<int[]> burstyFlows; // List of source-destination pairs for the current burst

    // New fields for tracking burst state and flow count
    private boolean inBurst = false;
    private long flowsInCurrentPeriod = 0;
    private long totalFlowsGenerated = 0;

    public MicroburstTrafficPlanner(Map<Integer, TransportLayer> idToTransportLayer) {
        super(idToTransportLayer);
        this.idToTransportLayer = idToTransportLayer;

        // Read configuration parameters
        this.steadyLambda = Simulator.getConfiguration().getDoublePropertyOrFail("microburst_steady_lambda");
        this.burstLambda = Simulator.getConfiguration().getDoublePropertyOrFail("microburst_burst_lambda");
        this.burstIntervalNs = Simulator.getConfiguration().getLongPropertyOrFail("microburst_burst_interval_ns");
        this.burstDurationNs = Simulator.getConfiguration().getLongPropertyOrFail("microburst_burst_duration_ns");
        this.burstFraction = Simulator.getConfiguration().getDoublePropertyOrFail("microburst_fraction");

        // Initialize flow size distribution
        String flowSizeDistName = Simulator.getConfiguration().getPropertyOrFail("traffic_flow_size_dist");
        this.flowSizeDistribution = initializeFlowSizeDistribution(flowSizeDistName);

        // Get the list of host IDs
        this.hostIdList = new ArrayList<>(idToTransportLayer.keySet());
        this.numHosts = hostIdList.size();

        // Initialize random number generator
        this.rng = Simulator.selectIndependentRandom("microburst_traffic_planner");
        this.burstyFlows = new ArrayList<>();

        // Log configuration
        logConfiguration();
    }

    private FlowSizeDistribution initializeFlowSizeDistribution(String flowSizeDistName) {
        switch (flowSizeDistName) {
            case "pfabric_data_mining_lower_bound":
                return new PFabricDataMiningLowerBoundFSD();
            case "pfabric_data_mining_upper_bound":
                return new PFabricDataMiningUpperBoundFSD();
            case "pfabric_web_search_lower_bound":
                return new PFabricWebSearchLowerBoundFSD();
            case "pfabric_web_search_upper_bound":
                return new PFabricWebSearchUpperBoundFSD();
            case "pareto":
                double paretoShape = Simulator.getConfiguration().getDoublePropertyOrFail("traffic_flow_size_dist_pareto_shape");
                long paretoMeanKB = Simulator.getConfiguration().getLongPropertyOrFail("traffic_flow_size_dist_pareto_mean_kilobytes");
                return new ParetoFSD(paretoShape, paretoMeanKB);
            case "uniform":
                long uniformMeanBytes = Simulator.getConfiguration().getLongPropertyOrFail("traffic_flow_size_dist_uniform_mean_bytes");
                return new UniformFSD(uniformMeanBytes);
            default:
                throw new RuntimeException("Unknown flow size distribution: " + flowSizeDistName);
        }
    }

    private void logConfiguration() {
        SimulationLogger.logInfo("Traffic planner", "MicroburstTrafficPlanner");
        SimulationLogger.logInfo("Microburst steady lambda", Double.toString(steadyLambda));
        SimulationLogger.logInfo("Microburst burst lambda", Double.toString(burstLambda));
        SimulationLogger.logInfo("Microburst burst interval (ns)", Long.toString(burstIntervalNs));
        SimulationLogger.logInfo("Microburst burst duration (ns)", Long.toString(burstDurationNs));
        SimulationLogger.logInfo("Flow size distribution", flowSizeDistribution.getClass().getSimpleName());
    }

    @Override
    public void createPlan(long durationNs) {
        long currentTimeNs = 0;
        long nextBurstStartNs = burstIntervalNs;
        long nextBurstEndNs = nextBurstStartNs + burstDurationNs;
        
        SimulationLogger.logInfo("SIMULATION START", "Duration: " + durationNs + " ns");
        System.out.println("\n=== SIMULATION START ===\nDuration: " + durationNs + " ns\n");

        while (currentTimeNs < durationNs) {
        	if (currentTimeNs % 1_000_000_000L < 100) { // Every ~1 second of simulated time
        	    System.out.println("DEBUG: currentTimeNs = " + currentTimeNs);
        	}
            double lambda;
            long periodEndNs;

            if (currentTimeNs < nextBurstStartNs) {
                lambda = steadyLambda;
                periodEndNs = nextBurstStartNs;
            } else if (currentTimeNs >= nextBurstStartNs && currentTimeNs < nextBurstEndNs) {
                lambda = burstLambda;
                periodEndNs = nextBurstEndNs;

                if (!inBurst) {
                    inBurst = true;
                    flowsInCurrentPeriod = 0;
                    burstyFlows.clear();

                    int numBurstyFlows = (int) Math.ceil(burstFraction * numHosts * (numHosts - 1));
                    for (int i = 0; i < numBurstyFlows; i++) {
                        int srcId = hostIdList.get(rng.nextInt(numHosts));
                        int dstId;
                        do {
                            dstId = hostIdList.get(rng.nextInt(numHosts));
                        } while (dstId == srcId);
                        burstyFlows.add(new int[]{srcId, dstId});
                    }
                    SimulationLogger.logInfo("BURST START", "Time: " + currentTimeNs + " ns");
                }
            } else {
                if (inBurst) {
                    inBurst = false;
                    SimulationLogger.logInfo("BURST END", "Time: " + currentTimeNs + " ns");
                }
                nextBurstStartNs += burstIntervalNs;
                nextBurstEndNs = nextBurstStartNs + burstDurationNs;
                continue;
            }

            double u = rng.nextDouble();
            // Compute arrival time in floating point
            double continuousTimeNs = -Math.log(u) / lambda * 1e9;

            // Convert to long
            long interArrivalTimeNs = (long) continuousTimeNs;

            // Enforce a minimum of 1 ns to avoid zero arrival time
            if (interArrivalTimeNs < 1) {
                interArrivalTimeNs = 1;
            }

            // Continue as before
            if (currentTimeNs + interArrivalTimeNs > periodEndNs) {
                interArrivalTimeNs = periodEndNs - currentTimeNs;
                currentTimeNs = periodEndNs;
            } else {
                currentTimeNs += interArrivalTimeNs;
            }

            if (currentTimeNs <= durationNs) {
                scheduleFlow(currentTimeNs);
                if (inBurst) {
                    flowsInCurrentPeriod++;
                }
                totalFlowsGenerated++;
            }
        }

        SimulationLogger.logInfo("SIMULATION END", "Total Flows Generated: " + totalFlowsGenerated);
        System.out.println("\n=== SIMULATION END ===\nTotal Flows Generated: " + totalFlowsGenerated + "\n");
    }
    
    private void scheduleFlow(long timeNs) {
        // Select source and destination nodes randomly from hosts
        int srcId = hostIdList.get(rng.nextInt(numHosts));
        int dstId;
        do {
            dstId = hostIdList.get(rng.nextInt(numHosts));
        } while (dstId == srcId);

        // Make srcId and dstId effectively final for use in the lambda
        final int finalSrcId = srcId;
        final int finalDstId = dstId;

        // Determine if the flow is part of the burst
        boolean isPartOfBurst = inBurst && burstyFlows.stream()
                .anyMatch(pair -> pair[0] == finalSrcId && pair[1] == finalDstId);

        // Generate flow size
        long flowSizeByte = flowSizeDistribution.generateFlowSizeByte();

        // NOTE: Ground-truth registration (C1a) and bursty queue status (C2) are now
        // done in TransportLayer.startFlow() where flowIdCounter is the correct runtime ID.
        // Previously they were registered here with getNextFlowId(), which returned 0 for
        // ALL flows since flowIdCounter only increments inside startFlow().

        // Log flow scheduling
        SimulationLogger.logInfo("Flow Scheduling",
            "Time: " + timeNs + " ns, " +
            "From: " + srcId + ", To: " + dstId + ", " +
            "Size: " + flowSizeByte + " bytes, " +
            "In Burst: " + inBurst + ", " +
            "Part of Burst: " + isPartOfBurst + ", " +
            "Flows in current period: " + flowsInCurrentPeriod);

        // Create FlowStartEvent and register it
        FlowStartEvent event = new FlowStartEvent(
            timeNs - Simulator.getCurrentTime(),
            idToTransportLayer.get(srcId),
            dstId,
            flowSizeByte,
            isPartOfBurst
        );
        Simulator.registerEvent(event);
    }

}