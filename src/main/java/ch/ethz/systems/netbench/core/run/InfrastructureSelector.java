package ch.ethz.systems.netbench.core.run;

import ch.ethz.systems.netbench.core.Simulator;
import ch.ethz.systems.netbench.core.config.exceptions.PropertyValueInvalidException;
import ch.ethz.systems.netbench.core.run.infrastructure.IntermediaryGenerator;
import ch.ethz.systems.netbench.core.run.infrastructure.LinkGenerator;
import ch.ethz.systems.netbench.core.run.infrastructure.NetworkDeviceGenerator;
import ch.ethz.systems.netbench.core.run.infrastructure.OutputPortGenerator;
import ch.ethz.systems.netbench.core.run.infrastructure.TransportLayerGenerator;
import ch.ethz.systems.netbench.ext.bare.BareTransportLayerGenerator;
import ch.ethz.systems.netbench.ext.basic.EcnTailDropOutputPortGenerator;
import ch.ethz.systems.netbench.ext.basic.PerfectSimpleLinkGenerator;
import ch.ethz.systems.netbench.ext.basic.SplitBandwidthLinkGenerator;
import ch.ethz.systems.netbench.ext.basic.TailDropOutputPortGenerator;
import ch.ethz.systems.netbench.ext.demo.DemoIntermediaryGenerator;
import ch.ethz.systems.netbench.ext.demo.DemoTransportLayerGenerator;
import ch.ethz.systems.netbench.ext.ecmp.EcmpSwitchGenerator;
import ch.ethz.systems.netbench.ext.ecmp.ForwarderSwitchGenerator;
import ch.ethz.systems.netbench.ext.flowlet.IdentityFlowletIntermediaryGenerator;
import ch.ethz.systems.netbench.ext.flowlet.UniformFlowletIntermediaryGenerator;
import ch.ethz.systems.netbench.ext.hybrid.EcmpThenValiantSwitchGenerator;
import ch.ethz.systems.netbench.ext.valiant.RangeValiantSwitchGenerator;
import ch.ethz.systems.netbench.xpt.asaf.routing.priority.PriorityFlowletIntermediaryGenerator;
import ch.ethz.systems.netbench.xpt.newreno.newrenodctcp.NewRenoDctcpTransportLayerGenerator;
import ch.ethz.systems.netbench.xpt.newreno.newrenotcp.NewRenoTcpTransportLayerGenerator;
import ch.ethz.systems.netbench.xpt.ports.AFQ.AFQOutputPortGenerator;
import ch.ethz.systems.netbench.xpt.ports.FIFO.FIFOOutputPortGenerator;
import ch.ethz.systems.netbench.xpt.ports.Greedy.GreedyOutputPortGenerator_Advanced;
import ch.ethz.systems.netbench.xpt.ports.Greedy.GreedyOutputPortGenerator_Simple;

import ch.ethz.systems.netbench.xpt.ports.PIFO.PIFOOutputPortGenerator;
import ch.ethz.systems.netbench.xpt.ports.PIFO_WFQ.WFQPIFOOutputPortGenerator;
import ch.ethz.systems.netbench.xpt.ports.SPPIFO.SPPIFOOutputPortGenerator;
import ch.ethz.systems.netbench.xpt.ports.SPPIFO_WFQ.WFQSPPIFOOutputPortGenerator;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.port.MIDSTCapableOutputPortGenerator;
import ch.ethz.systems.netbench.xpt.simple.simpledctcp.SimpleDctcpTransportLayerGenerator;
import ch.ethz.systems.netbench.xpt.simple.simpletcp.SimpleTcpTransportLayerGenerator;
import ch.ethz.systems.netbench.xpt.sourcerouting.EcmpThenSourceRoutingSwitchGenerator;
import ch.ethz.systems.netbench.xpt.sourcerouting.SourceRoutingSwitchGenerator;
import ch.ethz.systems.netbench.xpt.tcpextended.buffertcp.BufferTcpTransportLayerGenerator;
import ch.ethz.systems.netbench.xpt.tcpextended.fixedpriority.FixedPriorityTransportLayerGenerator;
import ch.ethz.systems.netbench.xpt.tcpextended.lstftcp.LstfTcpTransportLayerGenerator;
import ch.ethz.systems.netbench.xpt.tcpextended.pfabric.PfabricTransportLayerGenerator;
import ch.ethz.systems.netbench.xpt.tcpextended.pfzero.PfzeroTransportLayerGenerator;
import ch.ethz.systems.netbench.xpt.tcpextended.sphalftcp.SpHalfTcpTransportLayerGenerator;
import ch.ethz.systems.netbench.xpt.tcpextended.sptcp.SpTcpTransportLayerGenerator;

class InfrastructureSelector {

    private InfrastructureSelector() {
        // Only static class
    }

    /**
     * M4 FIX: Compute SP-PIFO congestion penalty rank from semantic target queue.
     *
     * Resolves the penalty rank for SP-PIFO/PACKS queue demotion:
     * - If "midst_penalty_target_queue" is set to "LAST" or an integer,
     *   auto-computes penalty = targetQueue × rankDelta
     * - Falls back to explicit "midst_sppifo_congestion_penalty_rank" for backward compatibility
     *
     * This eliminates the implicit coupling between penalty, rankDelta, and numQueues
     * that previously caused silent failures when any parameter changed independently.
     *
     * @return The computed congestion penalty rank value
     */
    private static long computeSppifoPenaltyRank() {
        String targetQueueStr = Simulator.getConfiguration().getPropertyWithDefault(
            "midst_penalty_target_queue", null);

        if (targetQueueStr != null && !targetQueueStr.trim().isEmpty()) {
            long rankDelta = Simulator.getConfiguration().getLongPropertyWithDefault(
                "midst_sppifo_rank_delta", 200);
            int numQueues = Simulator.getConfiguration().getIntegerPropertyWithDefault(
                "midst_sppifo_num_queues", 5);

            targetQueueStr = targetQueueStr.trim();

            if (targetQueueStr.equalsIgnoreCase("LAST")) {
                long computed = (long)(numQueues - 1) * rankDelta;
                System.out.println("[M4 FIX] midst_penalty_target_queue=LAST -> penalty=" + computed +
                    " (numQueues=" + numQueues + ", rankDelta=" + rankDelta +
                    ", targetQueue=" + (numQueues - 1) + ")");
                return computed;
            }

            try {
                int targetQueue = Integer.parseInt(targetQueueStr);
                if (targetQueue < 0 || targetQueue >= numQueues) {
                    throw new IllegalArgumentException(
                        "midst_penalty_target_queue=" + targetQueue +
                        " out of range [0, " + (numQueues - 1) + "] for numQueues=" + numQueues);
                }
                long computed = (long) targetQueue * rankDelta;
                System.out.println("[M4 FIX] midst_penalty_target_queue=" + targetQueue +
                    " -> penalty=" + computed + " (rankDelta=" + rankDelta + ")");
                return computed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "midst_penalty_target_queue must be 'LAST' or an integer queue index, got: '"
                    + targetQueueStr + "'");
            }
        }

        // Backward compatibility: use explicit penalty rank (legacy property)
        return Simulator.getConfiguration().getLongPropertyWithDefault(
            "midst_sppifo_congestion_penalty_rank", 0);
    }

    /**
     * M5a FIX: Compute PACKS congestion penalty rank from semantic target queue.
     *
     * Separate from SP-PIFO's penalty — each scheduler owns its own property.
     * PACKS penalty serves dual purpose: queue demotion AND admission rejection.
     * - If "packs_penalty_target_queue" is set to "LAST" or an integer,
     *   auto-computes penalty = targetQueue × rankDelta
     * - Falls back to explicit "aifo_rank_penalty" for backward compatibility
     *
     * @return The computed PACKS penalty rank value
     */
    private static long computePacksPenaltyRank() {
        String targetQueueStr = Simulator.getConfiguration().getPropertyWithDefault(
            "packs_penalty_target_queue", null);

        if (targetQueueStr != null && !targetQueueStr.trim().isEmpty()) {
            long rankDelta = Simulator.getConfiguration().getLongPropertyWithDefault(
                "midst_sppifo_rank_delta", 200);
            int numQueues = Simulator.getConfiguration().getIntegerPropertyWithDefault(
                "midst_sppifo_num_queues", 5);

            targetQueueStr = targetQueueStr.trim();

            if (targetQueueStr.equalsIgnoreCase("LAST")) {
                long computed = (long)(numQueues - 1) * rankDelta;
                System.out.println("[M5a FIX] packs_penalty_target_queue=LAST -> penalty=" + computed +
                    " (numQueues=" + numQueues + ", rankDelta=" + rankDelta +
                    ", targetQueue=" + (numQueues - 1) + ")");
                return computed;
            }

            try {
                int targetQueue = Integer.parseInt(targetQueueStr);
                if (targetQueue < 0 || targetQueue >= numQueues) {
                    throw new IllegalArgumentException(
                        "packs_penalty_target_queue=" + targetQueue +
                        " out of range [0, " + (numQueues - 1) + "] for numQueues=" + numQueues);
                }
                long computed = (long) targetQueue * rankDelta;
                System.out.println("[M5a FIX] packs_penalty_target_queue=" + targetQueue +
                    " -> penalty=" + computed + " (rankDelta=" + rankDelta + ")");
                return computed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "packs_penalty_target_queue must be 'LAST' or an integer queue index, got: '"
                    + targetQueueStr + "'");
            }
        }

        // Backward compatibility: use explicit aifo_rank_penalty (legacy shared property)
        return Simulator.getConfiguration().getLongPropertyWithDefault(
            "aifo_rank_penalty", 200);
    }

    /**
     * M5b FIX: Compute AIFO admission penalty from semantic keyword.
     *
     * AIFO has NO multi-queue scheduling — it's a single FIFO buffer with a
     * quantile-based admission gate. The penalty's ONLY effect is rank inflation
     * that causes bursty packets to fail the quantile test and get DROPPED.
     * There is no "queue demotion" concept in AIFO.
     *
     * - "REJECT" → large penalty (1,000,000,000) guaranteed to fail quantile test
     *   regardless of window composition or fill level. Safe for int cast in
     *   compareQuantile() (RealAIFOQueue L243: (int) effectiveRank).
     * - Numeric value → used as explicit penalty
     * - Falls back to "aifo_rank_penalty" for backward compatibility
     *
     * @return The computed AIFO admission penalty value
     */
    private static long computeAifoAdmissionPenalty() {
        String penaltyStr = Simulator.getConfiguration().getPropertyWithDefault(
            "aifo_admission_penalty", null);

        if (penaltyStr != null && !penaltyStr.trim().isEmpty()) {
            penaltyStr = penaltyStr.trim();

            if (penaltyStr.equalsIgnoreCase("REJECT")) {
                // REJECT = guaranteed admission failure for bursty flows.
                // Value must be (a) large enough to dominate any real rank in quantile test,
                // (b) safe for long→int cast in compareQuantile(), (c) safe for baseRank + penalty overflow.
                // 1 billion satisfies all three: fits int (< 2.1B), dominates rank=0 (no SRPT).
                long computed = 1_000_000_000L;
                System.out.println("[M5b FIX] aifo_admission_penalty=REJECT -> penalty=" + computed +
                    " (guaranteed quantile test failure for bursty flows)");
                return computed;
            }

            try {
                long penalty = Long.parseLong(penaltyStr);
                if (penalty < 0) {
                    throw new IllegalArgumentException(
                        "aifo_admission_penalty must be non-negative, got: " + penalty);
                }
                System.out.println("[M5b FIX] aifo_admission_penalty=" + penalty);
                return penalty;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "aifo_admission_penalty must be 'REJECT' or a non-negative integer, got: '"
                    + penaltyStr + "'");
            }
        }

        // Backward compatibility: use explicit aifo_rank_penalty (legacy shared property)
        return Simulator.getConfiguration().getLongPropertyWithDefault(
            "aifo_rank_penalty", 200);
    }

    /**
     * Select the network device generator, which, given its identifier,
     * generates an appropriate network device (possibly with transport layer).
     *
     * Selected using following properties:
     * network_device=...
     * network_device_intermediary=...
     *
     * @return  Network device generator.
     */
    static NetworkDeviceGenerator selectNetworkDeviceGenerator() {

        /*
         * Select intermediary generator.
         */
        IntermediaryGenerator intermediaryGenerator;
        switch (Simulator.getConfiguration().getPropertyOrFail("network_device_intermediary")) {

            case "demo": {
                intermediaryGenerator = new DemoIntermediaryGenerator();
                break;
            }

            case "identity": {
                intermediaryGenerator = new IdentityFlowletIntermediaryGenerator();
                break;
            }

            case "uniform": {
                intermediaryGenerator = new UniformFlowletIntermediaryGenerator();
                break;
            }

            case "low_high_priority": {
                intermediaryGenerator = new PriorityFlowletIntermediaryGenerator();
                break;
            }

            default:
                throw new PropertyValueInvalidException(
                        Simulator.getConfiguration(),
                        "network_device_intermediary"
                );

        }

        /*
         * Select network device generator.
         */
        switch (Simulator.getConfiguration().getPropertyOrFail("network_device")) {

            case "forwarder_switch":
                return new ForwarderSwitchGenerator(intermediaryGenerator, Simulator.getConfiguration().getGraphDetails().getNumNodes());

            case "ecmp_switch":
                return new EcmpSwitchGenerator(intermediaryGenerator, Simulator.getConfiguration().getGraphDetails().getNumNodes());

            case "random_valiant_ecmp_switch":
                return new RangeValiantSwitchGenerator(intermediaryGenerator, Simulator.getConfiguration().getGraphDetails().getNumNodes());

            case "ecmp_then_random_valiant_ecmp_switch":
                return new EcmpThenValiantSwitchGenerator(intermediaryGenerator, Simulator.getConfiguration().getGraphDetails().getNumNodes());

            case "source_routing_switch":
                return new SourceRoutingSwitchGenerator(intermediaryGenerator, Simulator.getConfiguration().getGraphDetails().getNumNodes());

            case "ecmp_then_source_routing_switch":
                return new EcmpThenSourceRoutingSwitchGenerator(intermediaryGenerator, Simulator.getConfiguration().getGraphDetails().getNumNodes());

            default:
                throw new PropertyValueInvalidException(
                        Simulator.getConfiguration(),
                        "network_device"
                );

        }

    }

    /**
     * Select the link generator which creates a link instance given two
     * directed network devices.
     *
     * Selected using following property:
     * link=...
     *
     * @return  Link generator
     */
    static LinkGenerator selectLinkGenerator() {

        switch (Simulator.getConfiguration().getPropertyOrFail("link")) {

            case "perfect_simple":
                return new PerfectSimpleLinkGenerator(
                        Simulator.getConfiguration().getLongPropertyOrFail("link_delay_ns"),
                        Simulator.getConfiguration().getDoublePropertyOrFail("link_bandwidth_bit_per_ns")
                );

            case "split_bw":
                return new SplitBandwidthLinkGenerator(
                        Simulator.getConfiguration().getLongPropertyOrFail("link_delay_ns"),
                        Simulator.getConfiguration().getDoublePropertyOrFail("link_bandwidth_bit_per_ns")
                );

            default:
                throw new PropertyValueInvalidException(
                        Simulator.getConfiguration(),
                        "link"
                );

        }

    }

    /**
     * Select the output port generator which creates a port instance given two
     * directed network devices and the corresponding link.
     *
     * Selected using following property:
     * output_port=...
     *
     * @return  Output port generator
     */
    static OutputPortGenerator selectOutputPortGenerator() {

        switch (Simulator.getConfiguration().getPropertyOrFail("output_port")) {

            case "tail_drop":
                return new TailDropOutputPortGenerator(
                        Simulator.getConfiguration().getLongPropertyOrFail("output_port_max_queue_size_bytes")
                );

            case "ecn_tail_drop":
                return new EcnTailDropOutputPortGenerator(
                        Simulator.getConfiguration().getLongPropertyOrFail("output_port_max_queue_size_bytes"),
                        Simulator.getConfiguration().getLongPropertyOrFail("output_port_ecn_threshold_k_bytes")
                );

            case "sppifo":
                return new SPPIFOOutputPortGenerator(
                        Simulator.getConfiguration().getLongPropertyOrFail("output_port_number_queues"),
                        Simulator.getConfiguration().getLongPropertyOrFail("output_port_max_size_per_queue_packets"),
                        Simulator.getConfiguration().getPropertyOrFail("output_port_step_size")
                );

            case "greedy_simple":
                return new GreedyOutputPortGenerator_Simple(
                        Simulator.getConfiguration().getLongPropertyOrFail("output_port_number_queues"),
                        Simulator.getConfiguration().getLongPropertyOrFail("output_port_max_size_per_queue_packets"),
                        Simulator.getConfiguration().getLongPropertyOrFail("output_port_adaptation_period"),
                        Simulator.getConfiguration().getLongPropertyOrFail("output_port_max_rank")
                );

            case "greedy_advanced":
                return new GreedyOutputPortGenerator_Advanced(
                        Simulator.getConfiguration().getLongPropertyOrFail("output_port_number_queues"),
                        Simulator.getConfiguration().getLongPropertyOrFail("output_port_max_size_per_queue_packets"),
                        Simulator.getConfiguration().getPropertyOrFail("output_port_initialization"),
                        Simulator.getConfiguration().getPropertyOrFail("output_port_fix_queue_bounds")
                );

            case "pifo":
                return new PIFOOutputPortGenerator(
                        Simulator.getConfiguration().getLongPropertyOrFail("output_port_max_size_packets")
                );

            case "wfqsppifo":
                return new WFQSPPIFOOutputPortGenerator(
                        Simulator.getConfiguration().getLongPropertyOrFail("output_port_number_queues"),
                        Simulator.getConfiguration().getLongPropertyOrFail("output_port_max_size_per_queue_packets")
                );

            case "wfqpifo":
                return new WFQPIFOOutputPortGenerator(
                        Simulator.getConfiguration().getLongPropertyOrFail("output_port_max_size_packets")
                );

            case "fifo":
                return new FIFOOutputPortGenerator(
                        Simulator.getConfiguration().getLongPropertyOrFail("output_port_max_size_packets")
                );

            case "afq":
                return new AFQOutputPortGenerator(
                        Simulator.getConfiguration().getLongPropertyOrFail("output_port_number_queues"),
                        Simulator.getConfiguration().getLongPropertyOrFail("output_port_max_size_per_queue_packets"),
                        Simulator.getConfiguration().getLongPropertyOrFail("output_port_bytes_per_round")
                );
                
            case "midst_feedback":
                return new MIDSTCapableOutputPortGenerator(
                        Simulator.getConfiguration().getLongPropertyOrFail("output_port_max_queue_size_bytes"),
                        Simulator.getConfiguration().getIntegerPropertyOrFail("midst_sketch_size"),
                        Simulator.getConfiguration().getIntegerPropertyOrFail("midst_num_hash_functions"),
                        Simulator.getConfiguration().getLongPropertyOrFail("midst_high_watermark_packets"),
                        Simulator.getConfiguration().getLongPropertyOrFail("midst_low_watermark_packets"),
                        Simulator.getConfiguration().getIntegerPropertyOrFail("midst_burst_threshold"),
                        Simulator.getConfiguration().getPropertyOrFail("midst_feedback_queue_type"),
                        computeSppifoPenaltyRank(),  // M4 FIX: semantic resolution from midst_penalty_target_queue
                        Simulator.getConfiguration().getLongPropertyWithDefault("midst_sppifo_rank_delta", 200),
                        Simulator.getConfiguration().getIntegerPropertyWithDefault("midst_sppifo_num_queues", 5),
                        false, // testMode
                        // AIFO-specific parameters (with defaults)
                        (float) Simulator.getConfiguration().getDoublePropertyWithDefault("aifo_k", 0.1),
                        Simulator.getConfiguration().getIntegerPropertyWithDefault("aifo_window_size", 16),
                        Simulator.getConfiguration().getIntegerPropertyWithDefault("aifo_sample_count", 1),
                        computeAifoAdmissionPenalty(),  // M5b FIX: AIFO-specific admission penalty (REJECT or numeric)
                        computePacksPenaltyRank(),  // M5a FIX: PACKS-specific penalty from packs_penalty_target_queue
                        // AIFO MIDST detection mode (rejection rate vs queue depth)
                        Simulator.getConfiguration().getBooleanPropertyWithDefault("aifo_use_rejection_rate_detection", false),
                        Simulator.getConfiguration().getDoublePropertyWithDefault("aifo_rejection_rate_threshold", 0.05),
                        Simulator.getConfiguration().getIntegerPropertyWithDefault("aifo_rejection_window_size", 1000),
                        // Virtual fill level: use virtual queue depth for bursty flow admission gate
                        Simulator.getConfiguration().getBooleanPropertyWithDefault("midst_use_virtual_fill_level", false),
                        // Port-level VFL: fraction of buffer visible to bursty flows (0.0-1.0, 1.0 = disabled)
                        Simulator.getConfiguration().getDoublePropertyWithDefault("midst_port_vfl_fraction", 1.0),
                        // Adaptive threshold: 0.0 = absolute threshold only, >0.0 = relative heavy-hitter detection
                        Simulator.getConfiguration().getDoublePropertyWithDefault("midst_adaptive_threshold_fraction", 0.0)
                );


            default:
                throw new PropertyValueInvalidException(
                        Simulator.getConfiguration(),
                        "output_port"
                );

        }

    }

    /**
     * Select the transport layer generator.
     *
     * @return  Transport layer generator
     */
    static TransportLayerGenerator selectTransportLayerGenerator() {

        switch (Simulator.getConfiguration().getPropertyOrFail("transport_layer")) {

            case "demo":
                return new DemoTransportLayerGenerator();

            case "bare":
                return new BareTransportLayerGenerator();

            case "tcp":
                return new NewRenoTcpTransportLayerGenerator();

            case "lstf_tcp":
                return new LstfTcpTransportLayerGenerator(
                        Simulator.getConfiguration().getPropertyOrFail("transport_layer_rank_distribution"),
                        Simulator.getConfiguration().getLongPropertyOrFail("transport_layer_rank_bound")
                );

            case "sp_tcp":
                return new SpTcpTransportLayerGenerator();

            case "sp_half_tcp":
                return new SpHalfTcpTransportLayerGenerator();

            case "pfabric":
                return new PfabricTransportLayerGenerator();

            case "fixed_priority_tcp":
                return new FixedPriorityTransportLayerGenerator();

            case "pfzero":
                return new PfzeroTransportLayerGenerator();
                
            case "buffertcp":
                return new BufferTcpTransportLayerGenerator();
            
                case "dctcp":
                return new NewRenoDctcpTransportLayerGenerator();

            case "simple_tcp":
                return new SimpleTcpTransportLayerGenerator();

            case "simple_dctcp":
                return new SimpleDctcpTransportLayerGenerator();

            default:
                throw new PropertyValueInvalidException(
                        Simulator.getConfiguration(),
                        "transport_layer"
                );

        }

    }

}
