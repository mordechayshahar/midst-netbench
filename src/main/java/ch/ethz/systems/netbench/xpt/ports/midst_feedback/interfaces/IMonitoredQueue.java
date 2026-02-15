package ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces;

import ch.ethz.systems.netbench.core.network.Packet;
import java.util.Queue;
import java.util.Set;

/**
 * Interface for queues that work with the MIDSTCapableOutputPort.
 * Extends the standard Java Queue interface and adds methods for:
 * 1. Setting the object that should receive congestion signals (the Port).
 * 2. Receiving congestion feedback from the Port.
 */
public interface IMonitoredQueue extends Queue<Packet> {

    /**
     * Sets the signaler object (typically the Output Port) that this queue
     * should notify when congestion state changes (based on watermarks).
     *
     * @param signaler The object implementing ICongestionSignaler to notify.
     */
    void setCongestionSignaler(ICongestionSignaler signaler);

    /**
     * Applies congestion feedback received from the monitoring port.
     * The queue implementation should use this information to adjust its
     * internal behavior (e.g., ranking, dropping) according to its specific
     * discipline (SP-PIFO, AIFO, etc.). For BaselineQueue, this does nothing.
     *
     * @param congestedFlowIds A set of flow identifiers identified as congested by the port.
     */
    void applyCongestionFeedback(Set<Long> congestedFlowIds);

    // Removed getCurrentSizeBytes() as the baseline MIDSTQueue doesn't track bytes for signaling.

}