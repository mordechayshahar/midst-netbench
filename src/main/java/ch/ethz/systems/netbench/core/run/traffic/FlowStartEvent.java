package ch.ethz.systems.netbench.core.run.traffic;

import ch.ethz.systems.netbench.core.network.Event;
import ch.ethz.systems.netbench.core.network.TransportLayer;

public class FlowStartEvent extends Event {

    private final TransportLayer transportLayer;
    private final int targetId;
    private final long flowSizeByte;
    private final boolean wasScheduledDuringBurst;

    /**
     * Create event which will happen the given amount of nanoseconds later.
     *
     * @param timeFromNowNs     Time it will take before happening from now in nanoseconds
     * @param transportLayer    Source transport layer that wants to send the flow to the target
     * @param targetId          Target network device identifier
     * @param flowSizeByte      Size of the flow to send in bytes
     * @param isPlannerInBurst 
     */
    public FlowStartEvent(long timeFromNowNs, TransportLayer transportLayer, int targetId, long flowSizeByte) {
        this(timeFromNowNs, transportLayer, targetId, flowSizeByte, false);
    }

    public FlowStartEvent(long timeFromNowNs, TransportLayer transportLayer, int targetId, long flowSizeByte, boolean isPlannerInBurst) {
        super(timeFromNowNs);
        this.transportLayer = transportLayer;
        this.targetId = targetId;
        this.flowSizeByte = flowSizeByte;
        this.wasScheduledDuringBurst = isPlannerInBurst;
    }
    
    public boolean isWasScheduledDuringBurst()
    {
    	return this.wasScheduledDuringBurst;
    }

    @Override
    public void trigger() {
        transportLayer.startFlow(targetId, flowSizeByte, wasScheduledDuringBurst);
    }

}
