package ch.ethz.systems.netbench.xpt.ports.midst_feedback.core;

import ch.ethz.systems.netbench.core.log.SimulationLogger;
import ch.ethz.systems.netbench.core.Simulator;

/**
 * Virtual Queue: Universal Congestion Detection Trigger for MIDST
 *
 * The virtual queue tracks what the queue depth WOULD be if there were no
 * admission control (no packet drops). This provides a universal congestion
 * signal that works regardless of the scheduler type.
 *
 * Formula: virtualQueue = arrivals - departures
 *
 * Why this works for all schedulers:
 * - SP-PIFO: No admission control → virtual queue = real queue (same behavior)
 * - AIFO: Threshold-based drops → virtual queue still fills during bursts
 * - PACKS: Quantile-based drops → virtual queue fills (not hidden by admission control)
 *
 * The key insight is that virtual queue measures the FUNDAMENTAL traffic imbalance
 * (more arriving than can be processed), which is the true definition of congestion,
 * regardless of how the scheduler handles it.
 *
 * P4 Feasibility: This is trivially implementable in P4 as a single register:
 * - Ingress: increment by packet size
 * - Egress: decrement by packet size
 * - Compare against watermarks
 *
 * @author MSc Thesis - MIDST + Programmable Packet Scheduling
 */
public class VirtualQueue {

    // =========================================================================
    // STATE
    // =========================================================================

    /** Current virtual queue depth in bits */
    private long virtualQueueBits;

    /** High watermark: start monitoring when virtual queue exceeds this */
    private final long highWatermarkBits;

    /** Low watermark: stop monitoring when virtual queue drops below this */
    private final long lowWatermarkBits;

    /** Enable detailed logging for debugging */
    private final boolean loggingEnabled;

    /** Port identifier for logging */
    private final String portId;

    // =========================================================================
    // STATISTICS
    // =========================================================================

    /** Peak virtual queue depth observed */
    private long peakDepthBits;

    /** Number of times high watermark was crossed */
    private int highWatermarkCrossings;

    /** Number of times low watermark was crossed */
    private int lowWatermarkCrossings;

    /** Total bytes that have arrived */
    private long totalArrivedBytes;

    /** Total bytes that have departed */
    private long totalDepartedBytes;

    // =========================================================================
    // CONSTRUCTOR
    // =========================================================================

    /**
     * Create a new Virtual Queue with specified watermarks.
     *
     * @param highWatermarkBits Threshold to start monitoring (in bits)
     * @param lowWatermarkBits Threshold to stop monitoring (in bits)
     * @param portId Identifier for logging (e.g., "1->2")
     * @param loggingEnabled Enable detailed logging
     */
    public VirtualQueue(long highWatermarkBits, long lowWatermarkBits,
                        String portId, boolean loggingEnabled) {
        if (highWatermarkBits <= lowWatermarkBits) {
            throw new IllegalArgumentException(
                "High watermark (" + highWatermarkBits + ") must be greater than " +
                "low watermark (" + lowWatermarkBits + ")");
        }
        if (lowWatermarkBits < 0) {
            throw new IllegalArgumentException(
                "Low watermark cannot be negative: " + lowWatermarkBits);
        }

        this.virtualQueueBits = 0;
        this.highWatermarkBits = highWatermarkBits;
        this.lowWatermarkBits = lowWatermarkBits;
        this.portId = portId;
        this.loggingEnabled = loggingEnabled;

        this.peakDepthBits = 0;
        this.highWatermarkCrossings = 0;
        this.lowWatermarkCrossings = 0;
        this.totalArrivedBytes = 0;
        this.totalDepartedBytes = 0;

        if (loggingEnabled) {
            SimulationLogger.logInfo("VIRTUAL_QUEUE_INIT",
                "Port=" + portId +
                ",HighWatermarkBits=" + highWatermarkBits +
                ",LowWatermarkBits=" + lowWatermarkBits +
                ",HighWatermarkPackets=" + (highWatermarkBits / (1500 * 8)) +
                ",LowWatermarkPackets=" + (lowWatermarkBits / (1500 * 8)));
        }
    }

    /**
     * Convenience constructor with default packet size assumption (1500 bytes).
     *
     * @param highWatermarkPackets Threshold in packets to start monitoring
     * @param lowWatermarkPackets Threshold in packets to stop monitoring
     * @param portId Identifier for logging
     * @param loggingEnabled Enable detailed logging
     */
    public static VirtualQueue fromPacketCounts(int highWatermarkPackets,
                                                 int lowWatermarkPackets,
                                                 String portId,
                                                 boolean loggingEnabled) {
        long bitsPerPacket = 1500L * 8L; // Standard MTU
        return new VirtualQueue(
            highWatermarkPackets * bitsPerPacket,
            lowWatermarkPackets * bitsPerPacket,
            portId,
            loggingEnabled
        );
    }

    // =========================================================================
    // CORE OPERATIONS
    // =========================================================================

    /**
     * Called on EVERY packet arrival (before any admission control).
     * Always increments the virtual queue, regardless of whether the packet
     * will be accepted or dropped.
     *
     * @param packetSizeBits Size of the arriving packet in bits
     * @return true if this arrival caused the virtual queue to cross the high watermark
     */
    public synchronized boolean onArrival(long packetSizeBits) {
        boolean wasBelow = virtualQueueBits < highWatermarkBits;

        virtualQueueBits += packetSizeBits;
        totalArrivedBytes += packetSizeBits / 8;

        // Track peak
        if (virtualQueueBits > peakDepthBits) {
            peakDepthBits = virtualQueueBits;
        }

        boolean crossedHigh = wasBelow && virtualQueueBits >= highWatermarkBits;
        if (crossedHigh) {
            highWatermarkCrossings++;
            if (loggingEnabled) {
                SimulationLogger.logInfo("VIRTUAL_QUEUE_HIGH_CROSSED",
                    "Port=" + portId +
                    ",DepthBits=" + virtualQueueBits +
                    ",ThresholdBits=" + highWatermarkBits +
                    ",CrossingCount=" + highWatermarkCrossings +
                    ",Time=" + Simulator.getCurrentTime());
            }
        }

        return crossedHigh;
    }

    /**
     * Called on EVERY packet departure (successful transmission).
     * Decrements the virtual queue.
     *
     * @param packetSizeBits Size of the departing packet in bits
     * @return true if this departure caused the virtual queue to cross below the low watermark
     */
    public synchronized boolean onDeparture(long packetSizeBits) {
        boolean wasAbove = virtualQueueBits > lowWatermarkBits;

        virtualQueueBits -= packetSizeBits;
        totalDepartedBytes += packetSizeBits / 8;

        // Safety: prevent negative (shouldn't happen in normal operation)
        if (virtualQueueBits < 0) {
            if (loggingEnabled) {
                SimulationLogger.logInfo("VIRTUAL_QUEUE_NEGATIVE_CORRECTED",
                    "Port=" + portId +
                    ",WasNegative=" + virtualQueueBits +
                    ",Time=" + Simulator.getCurrentTime());
            }
            virtualQueueBits = 0;
        }

        boolean crossedLow = wasAbove && virtualQueueBits <= lowWatermarkBits;
        if (crossedLow) {
            lowWatermarkCrossings++;
            if (loggingEnabled) {
                SimulationLogger.logInfo("VIRTUAL_QUEUE_LOW_CROSSED",
                    "Port=" + portId +
                    ",DepthBits=" + virtualQueueBits +
                    ",ThresholdBits=" + lowWatermarkBits +
                    ",CrossingCount=" + lowWatermarkCrossings +
                    ",Time=" + Simulator.getCurrentTime());
            }
        }

        return crossedLow;
    }

    // =========================================================================
    // STATE QUERIES
    // =========================================================================

    /**
     * Check if virtual queue is currently above high watermark.
     * Used to determine if monitoring should be active.
     *
     * @return true if virtualQueue >= highWatermark
     */
    public boolean isAboveHighWatermark() {
        return virtualQueueBits >= highWatermarkBits;
    }

    /**
     * Check if virtual queue is currently below low watermark.
     * Used to determine if monitoring should stop.
     *
     * @return true if virtualQueue <= lowWatermark
     */
    public boolean isBelowLowWatermark() {
        return virtualQueueBits <= lowWatermarkBits;
    }

    /**
     * Get current virtual queue depth in bits.
     *
     * @return Current depth in bits
     */
    public long getDepthBits() {
        return virtualQueueBits;
    }

    /**
     * Get current virtual queue depth in packets (assuming 1500 byte packets).
     *
     * @return Approximate depth in packets
     */
    public long getDepthPackets() {
        return virtualQueueBits / (1500L * 8L);
    }

    /**
     * Get high watermark threshold in bits.
     *
     * @return High watermark in bits
     */
    public long getHighWatermarkBits() {
        return highWatermarkBits;
    }

    /**
     * Get low watermark threshold in bits.
     *
     * @return Low watermark in bits
     */
    public long getLowWatermarkBits() {
        return lowWatermarkBits;
    }

    // =========================================================================
    // STATISTICS
    // =========================================================================

    /**
     * Get the peak virtual queue depth observed.
     *
     * @return Peak depth in bits
     */
    public long getPeakDepthBits() {
        return peakDepthBits;
    }

    /**
     * Get number of times high watermark was crossed (congestion start events).
     *
     * @return Number of high watermark crossings
     */
    public int getHighWatermarkCrossings() {
        return highWatermarkCrossings;
    }

    /**
     * Get number of times low watermark was crossed (congestion end events).
     *
     * @return Number of low watermark crossings
     */
    public int getLowWatermarkCrossings() {
        return lowWatermarkCrossings;
    }

    /**
     * Get total bytes that have arrived at this virtual queue.
     *
     * @return Total arrived bytes
     */
    public long getTotalArrivedBytes() {
        return totalArrivedBytes;
    }

    /**
     * Get total bytes that have departed from this virtual queue.
     *
     * @return Total departed bytes
     */
    public long getTotalDepartedBytes() {
        return totalDepartedBytes;
    }

    /**
     * Get the "in-flight" bytes (arrived but not yet departed).
     * This represents the virtual backlog.
     *
     * @return In-flight bytes
     */
    public long getInFlightBytes() {
        return totalArrivedBytes - totalDepartedBytes;
    }

    @Override
    public String toString() {
        return "VirtualQueue{" +
            "port=" + portId +
            ", depth=" + virtualQueueBits + " bits (" + getDepthPackets() + " pkts)" +
            ", highWM=" + highWatermarkBits +
            ", lowWM=" + lowWatermarkBits +
            ", peak=" + peakDepthBits +
            ", crossings=" + highWatermarkCrossings + "/" + lowWatermarkCrossings +
            "}";
    }
}
