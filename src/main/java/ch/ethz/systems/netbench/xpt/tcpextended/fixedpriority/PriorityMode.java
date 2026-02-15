package ch.ethz.systems.netbench.xpt.tcpextended.fixedpriority;

/**
 * Enum defining the different priority assignment modes for FixedPrioritySocket.
 */
public enum PriorityMode {
    /**
     * Priority = flowId * multiplier
     * Useful for deterministic testing where different flows should have predictable priorities.
     */
    FLOW_ID_BASED,

    /**
     * Priority based on flow size buckets:
     * - Small flows (< smallThreshold): low priority value (higher urgency)
     * - Medium flows (smallThreshold <= size < largeThreshold): medium priority
     * - Large flows (>= largeThreshold): high priority value (lower urgency)
     */
    SIZE_BUCKET,

    /**
     * All flows get the same base priority.
     * Useful for MIDST testing where penalty application is the differentiator.
     */
    UNIFORM
}
