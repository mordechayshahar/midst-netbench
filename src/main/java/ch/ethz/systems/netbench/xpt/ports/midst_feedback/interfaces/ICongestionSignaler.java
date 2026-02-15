package ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces;

/**
 * Interface defining the signals a monitored queue can send back
 * to its controlling port (e.g., MIDSTCapableOutputPort)
 * regarding congestion state changes based on watermarks.
 */
public interface ICongestionSignaler {

    /**
     * Called by the queue when its state indicates that congestion
     * has started (e.g., occupancy crossed the high watermark).
     * The signaler (Port) should typically start monitoring upon this signal.
     */
    void signalCongestionStart();

    /**
     * Called by the queue when its state indicates that congestion
     * has ended (e.g., occupancy dropped below the low watermark).
     * The signaler (Port) should typically stop monitoring and process
     * results upon this signal.
     */
    void signalCongestionEnd();

}