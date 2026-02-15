package ch.ethz.systems.netbench.core.config;

public class BaseAllowedProperties {

    private BaseAllowedProperties() {
        // Private constructor, cannot be constructed
    }

    public static final String[] LOG = new String[]{
            "enable_log_port_queue_state",
            "enable_log_flow_throughput",
            "enable_generate_human_readable_flow_completion_log",
    };

    public static final String[] PROPERTIES_RUN = new String[] {

            // General
            "scenario_topology_file",
            "scenario_topology_extend_with_servers",
            "scenario_topology_extend_servers_per_tl_node",
            "seed",
            "run_time_s",
            "run_time_ns",
            "run_folder_name",
            "run_folder_base_dir",
            "analysis_command",
            "finish_when_first_flows_finish",

            // Infrastructure
            "transport_layer",
            "network_device",
            "network_device_intermediary",
            "output_port",
            "link",

            // Routing
            "network_device_routing",

            // Traffic
            "traffic",
            "traffic_flow_size_dist",
            "traffic_probabilities_file",
            "traffic_probabilities_generator",
            "traffic_probabilities_active_fraction",
            "traffic_probabilities_active_fraction_is_ordered",
            "traffic_lambda_flow_starts_per_s"


    };

    public static final String[] EXTENSION = new String[]{

            // Basic
            "output_port_max_queue_size_bytes",
            "output_port_ecn_threshold_k_bytes",
            "link_delay_ns",
            "link_bandwidth_bit_per_ns",

            // Poisson traffic
            "traffic_pair_type",
            "traffic_pair_flow_size_byte",
            "traffic_pairs",
            "traffic_arrivals_list",
            "traffic_flow_size_dist_uniform_mean_bytes",
            "traffic_flow_size_dist_pareto_shape",
            "traffic_flow_size_dist_pareto_mean_kilobytes",
            "traffic_pareto_skew_shape",

            // Flowlet
            "FLOWLET_GAP_NS",

            // VLB
            "routing_random_valiant_node_range_lower_incl",
            "routing_random_valiant_node_range_upper_incl",
            "routing_ecmp_then_valiant_switch_threshold_bytes",

            "traffic_probabilities_fraction_A",
            "traffic_probabilities_mass_A",


    };

    public static final String[] EXPERIMENTAL = new String[]{

            // TCP
            "TCP_ROUND_TRIP_TIMEOUT_NS",
            "TCP_MAX_SEGMENT_SIZE",
            "TCP_MAX_WINDOW_SIZE",
            "TCP_LOSS_WINDOW_SIZE",
            "TCP_INITIAL_SLOW_START_THRESHOLD",
            "TCP_INITIAL_WINDOW_SIZE",
            "TCP_MINIMUM_SSTHRESH",
            "TCP_MINIMUM_ROUND_TRIP_TIMEOUT",
            "DCTCP_WEIGHT_NEW_ESTIMATION",
            "enable_log_congestion_window",
            "enable_log_packet_burst_gap",
            "tcp_priority_mode",
            "tcp_partial_srpt_fraction",

            // K-shortest-paths
            "k_for_k_shortest_paths",

            // K-paths
            // "k_paths_k_threshold",
            // "k_paths_file",
            "allow_source_routing_skip_duplicate_paths",
            "allow_source_routing_add_duplicate_paths",


            "spark_error_distribution",
            "routing_ecmp_then_source_routing_switch_threshold_bytes"

    };
    
    public static final String[] MICROBURST = new String[]{
            "microburst_steady_lambda",
            "microburst_burst_lambda",
            "microburst_burst_interval_ns",
            "microburst_burst_duration_ns",
            "microburst_fraction"
    };
    
    public static final String[] SPPIFO = new String[]{
    	    "enable_rank_mapping",
    	    "enable_queue_bound_tracking",
    	    "enable_unpifoness_tracking",
    	    "enable_inversions_tracking",
    	    "sppifo_trace_enabled",
    	    "output_port_number_queues",
    	    "output_port_max_size_per_queue_packets",
    	    "output_port_step_size"
    	};

    public static final String[] FIXED_PRIORITY = new String[]{
            "priority_mode",
            "priority_multiplier",
            "priority_base",
            "priority_small_flow_rank",
            "priority_medium_flow_rank",
            "priority_large_flow_rank",
            "priority_small_threshold_bytes",
            "priority_large_threshold_bytes"
    };
    
    public static final String[] MIDST = new String[]{
    	    "midst_sketch_size",
    	    "midst_num_hash_functions",
    	    "midst_high_watermark_packets",
    	    "midst_low_watermark_packets",
    	    "midst_burst_threshold",
    	    "midst_feedback_queue_type",
    	    "midst_sppifo_congestion_penalty_rank",
    	    "midst_penalty_target_queue",  // M4 FIX: SP-PIFO semantic penalty ("LAST" or queue index)
    	    "packs_penalty_target_queue",  // M5a FIX: PACKS semantic penalty ("LAST" or queue index)
    	    "midst_sppifo_rank_delta",
    	    "midst_sppifo_num_queues",
    	    "midst_baseline_queue_internal_logging_enabled",
    	    "midst_sppifo_queue_internal_logging_enabled",
    	    "midst_aifo_queue_internal_logging_enabled",
    	    "midst_real_aifo_logging_enabled",
    	    "midst_use_virtual_fill_level",
    	    "midst_port_vfl_fraction",
    	    "midst_adaptive_threshold_fraction",
    	    // Real AIFO parameters
    	    "aifo_k",
    	    "aifo_window_size",
    	    "aifo_sample_count",
    	    "aifo_rank_penalty",
    	    "aifo_admission_penalty",  // M5b FIX: AIFO-specific admission rejection penalty ("REJECT" or numeric)
    	    // AIFO MIDST detection mode (rejection rate vs queue depth)
    	    "aifo_use_rejection_rate_detection",
    	    "aifo_rejection_rate_threshold",
    	    "aifo_rejection_window_size",
    	};


}
