package ch.ethz.systems.netbench.xpt.ports.midst_feedback.core;

import ch.ethz.systems.netbench.core.network.Packet;
import java.util.Set;

/**
 * MIDST: Microburst Detection using Count-Min Sketch
 *
 * A black-box module for detecting bursty flows and heavy losers.
 * Uses a virtual queue trigger that works uniformly with any packet scheduler
 * (SP-PIFO, AIFO, PACKS, etc.).
 *
 * Architecture:
 * - Virtual Queue: Tracks arrivals - departures to detect congestion
 * - Sin Sketch: Count-Min sketch for arriving packets
 * - Sout Sketch: Count-Min sketch for departing packets
 * - Δ-sketch: Sin - Sout estimates packet loss per flow
 *
 * Key Innovation (Virtual Queue Trigger):
 * Unlike queue-depth triggers (which fail when admission control prevents buildup)
 * or rejection-rate triggers (which fail when drops are statistically spread),
 * the virtual queue measures the fundamental arrival-departure imbalance and
 * works uniformly across all scheduler types.
 *
 * Reference: Landau Feibish et al., "Flow-Level Loss Detection with Δ-Sketches", SOSR 2022
 *
 * @author MSc Thesis - MIDST + Programmable Packet Scheduling
 */
public interface MIDST {

    // =========================================================================
    // PACKET LIFECYCLE METHODS
    // Called by the output port to notify MIDST of packet events
    // =========================================================================

    /**
     * Called when a packet arrives at the port (BEFORE any admission control).
     * This method:
     * 1. Updates the virtual queue (always, regardless of monitoring state)
     * 2. Checks if monitoring should start (virtual queue > high watermark)
     * 3. Updates the ingress sketch Sin (only during monitoring)
     *
     * @param packet The arriving packet
     */
    void onPacketArrival(Packet packet);

    /**
     * Called when a packet successfully departs (transmitted on the wire).
     * This method:
     * 1. Updates the virtual queue (decrement)
     * 2. Updates the egress sketch Sout
     * 3. Checks if monitoring should stop (virtual queue < low watermark)
     *
     * @param packet The departing packet
     */
    void onPacketDeparture(Packet packet);

    /**
     * Called when a packet is dropped (by admission control or buffer overflow).
     * This method:
     * 1. Does NOT update the virtual queue (packet never truly "left")
     * 2. Does NOT update Sout (packet didn't depart)
     * 3. Removes any stored metadata for this packet
     *
     * The Δ-sketch (Sin - Sout) will correctly reflect the loss because
     * Sin was incremented on arrival but Sout won't be incremented.
     *
     * @param packet The dropped packet
     */
    void onPacketDropped(Packet packet);

    // =========================================================================
    // QUERY API
    // Called by schedulers to get burst/loss information
    // =========================================================================

    /**
     * Check if a specific flow is currently classified as bursty.
     * A flow is bursty if its estimated packet count in the Sin sketch
     * exceeded the burst threshold during the most recent monitoring window.
     *
     * This method can be called at any time by the scheduler to inform
     * admission control or ranking decisions.
     *
     * @param flowId The flow identifier
     * @return true if the flow is classified as bursty, false otherwise
     */
    boolean isFlowBursty(long flowId);

    /**
     * Get all flows currently classified as bursty.
     *
     * @return Unmodifiable set of bursty flow IDs
     */
    Set<Long> getBurstyFlows();

    /**
     * Get flows that experienced significant packet loss (heavy losers).
     * A flow is a heavy loser if Δ-sketch (Sin - Sout) shows significant loss.
     *
     * Only meaningful after a monitoring window completes.
     *
     * @return Unmodifiable set of heavy loser flow IDs
     */
    Set<Long> getHeavyLosers();

    /**
     * Get the estimated loss count for a specific flow.
     * Computed as min(Sin[i] - Sout[i]) across all hash indices.
     *
     * @param flowId The flow identifier
     * @return Estimated number of lost packets (0 if no loss detected)
     */
    int getFlowLossEstimate(long flowId);

    /**
     * Get the estimated ingress count for a specific flow.
     * Computed as min(Sin[i]) across all hash indices (Count-Min estimate).
     *
     * @param flowId The flow identifier
     * @return Estimated number of packets that arrived from this flow
     */
    int getFlowIngressEstimate(long flowId);

    // =========================================================================
    // STATE QUERIES
    // For debugging and monitoring
    // =========================================================================

    /**
     * Check if MIDST is currently in a monitoring window.
     * Monitoring is active when virtual queue exceeds high watermark
     * and stops when it drops below low watermark.
     *
     * @return true if currently monitoring, false otherwise
     */
    boolean isMonitoring();

    /**
     * Get current virtual queue depth in bits.
     * Virtual queue = arrivals - departures (simulates queue without drops).
     *
     * @return Virtual queue depth in bits
     */
    long getVirtualQueueDepthBits();

    /**
     * Get the high watermark threshold in bits.
     * Monitoring starts when virtual queue exceeds this value.
     *
     * @return High watermark in bits
     */
    long getHighWatermarkBits();

    /**
     * Get the low watermark threshold in bits.
     * Monitoring stops when virtual queue drops below this value.
     *
     * @return Low watermark in bits
     */
    long getLowWatermarkBits();

    /**
     * Get the burst threshold (number of packets).
     * A flow is classified as bursty if its Sin estimate >= this threshold.
     * When adaptive detection is enabled, this serves as the absolute minimum floor.
     *
     * @return Burst threshold in packets
     */
    int getBurstThreshold();

    /**
     * Get the adaptive threshold fraction (phi).
     * When > 0.0, a flow is bursty only if its packet fraction in the window >= phi
     * AND its absolute count >= burstThreshold.
     * When 0.0 (default), only the absolute burstThreshold is used.
     *
     * @return Adaptive threshold fraction (0.0 = disabled)
     */
    double getAdaptiveThresholdFraction();

    /**
     * Get the total number of packets seen during the current/last monitoring window.
     * Used for adaptive threshold computation.
     *
     * @return Total packets in window
     */
    long getTotalWindowPackets();

    // =========================================================================
    // STATISTICS
    // For experiment analysis and thesis results
    // =========================================================================

    /**
     * Get the total number of monitoring windows that have occurred.
     *
     * @return Number of completed monitoring windows
     */
    int getMonitoringWindowCount();

    /**
     * Get the total number of packets classified as bursty across all windows.
     *
     * @return Total bursty packet count
     */
    long getTotalBurstyPackets();

    /**
     * Get the total number of packets classified as normal across all windows.
     *
     * @return Total normal packet count
     */
    long getTotalNormalPackets();
}
