package ch.ethz.systems.netbench.core.network;

import ch.ethz.systems.netbench.core.Simulator;
import ch.ethz.systems.netbench.core.log.SimulationLogger;
import ch.ethz.systems.netbench.ext.basic.IpPacket;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl.NewCanonicalSPPIFOQueue;
import ch.ethz.systems.netbench.xpt.tcpbase.FullExtTcpPacket;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The transport layer represents the entity that communicates
 * over the network. It belongs to a single {@link NetworkDevice network device}.
 * It starts a new {@link Socket socket} for each flow originating from here,
 * and creates a new receiving socket for each packet it receives with
 * an unfamiliar flow identifier.
 *
 * @see NetworkDevice
 * @see Socket
 */
public abstract class TransportLayer {

    // Generator for unique flow identifiers amongst all transport layers
    private static long flowIdCounter = 0;
    private static Map<Long, TransportLayer> flowIdToReceiver = new HashMap<>();

    // Map the flow identifier to the responsible socket
    private Map<Long, Socket> flowIdToSocket;
    private Set<Long> finishedFlowIds;

    // Map priority carried by data packets to flow identifier. Purpose: so that the same priority can
    // be applied to ACK packets as well. Only works for those cases in which priorities are fixed throughout all packets
    // of the flow.
    private Map<Long, Long> flowIdToPriority;

    private NetworkDevice networkDevice;
    protected final int identifier;

    public TransportLayer(int identifier) {
        this.identifier = identifier;
        this.flowIdToSocket = new HashMap<>();
        this.finishedFlowIds = new HashSet<>();
        this.flowIdToPriority = new HashMap<>();
    }

    /**
     * Pass the packet to the network device.
     *
     * @param packet    Packet instance
     */
    public final void send(Packet packet) {
        networkDevice.receiveFromTransportLayer(packet);
    }

    /**
     * Set the associated network device.
     *
     * Is not part of constructor because both the network device
     * and the server require a reference to each other.
     *
     * @param networkDevice     Network device instance
     */
    public final void setNetworkDevice(NetworkDevice networkDevice) {
        this.networkDevice = networkDevice;
    }

    /**
     * Reception of a packet from its network device (through the intermediary).
     * // TODO: make it package-local?
     *
     * @param genericPacket    Packet instance
     */
    public void receive(Packet genericPacket) {

        IpPacket packet = (IpPacket) genericPacket;
        Socket socket = flowIdToSocket.get(packet.getFlowId());

        // If the socket does not yet exist, it is an incoming socket
        if (socket == null && !finishedFlowIds.contains(packet.getFlowId())) {

            // Create the socket instance in the other direction
            socket = createSocket(packet.getFlowId(), packet.getSourceId(),-1);
            flowIdToReceiver.put(packet.getFlowId(), this);
            flowIdToSocket.put(packet.getFlowId(), socket);
        }

        // Give packet to socket (we do not care about stray packets)
        if (socket != null) {
            socket.handle(packet);
        }

    }

    /**
     * Start the sending of a flow to the destination (backward-compatible overload).
     *
     * @param destination       Destination network device identifier
     * @param flowSizeByte      Byte size of the flow
     */
    public void startFlow(int destination, long flowSizeByte) {
        startFlow(destination, flowSizeByte, false);
    }

    /**
     * Start the sending of a flow to the destination.
     *
     * @param destination                Destination network device identifier
     * @param flowSizeByte               Byte size of the flow
     * @param wasScheduledDuringBurst    Whether the flow was scheduled during a microburst
     */
    public void startFlow(int destination, long flowSizeByte, boolean wasScheduledDuringBurst) {

    	// *** CAPTURE THE ID TO BE USED FOR THIS FLOW ***
        long currentFlowId = flowIdCounter;

        // C1a fix: Register ground-truth bursty status HERE (not in MicroburstTrafficPlanner)
        // because flowIdCounter is only valid at startFlow() time, not at scheduling time.
        SimulationLogger.registerFlowBurstyGroundTruth(currentFlowId, wasScheduledDuringBurst);

        // C2 fix: Register bursty status with the queue HERE for the same reason.
        NewCanonicalSPPIFOQueue.registerFlowBurstyStatus(currentFlowId, wasScheduledDuringBurst);

        // Create new outgoing socket
        Socket socket = createSocket(flowIdCounter, destination, flowSizeByte);
        flowIdToSocket.put(flowIdCounter, socket);
        
        // *** ADD LOGGING HERE ***
        // Log the link between the assigned flow ID and its properties
        SimulationLogger.logInfo("FLOW_INITIATED",
            currentFlowId + "," +           // The actual NetBench Flow ID
            this.identifier + "," +        // Source Node ID
            destination + "," +            // Destination Node ID
            flowSizeByte + "," +           // Flow Size
            Simulator.getCurrentTime() + "," +     // Actual Start Time (ns)
            (wasScheduledDuringBurst ? "T" : "F") // Add the flag as T/F
        );
        // *************************
        
        flowIdCounter++;

        // Start the socket off as initiator
        socket.markAsSender();
        socket.start();
    }

    /**
     * Create a socket instance
     * .
     * The created socket should assume it is a receiver, unless their
     * {@link Socket#start() start} method has been called.
     *
     * @param flowId            Flow identifier of the socket
     * @param destinationId     Destination network device identifier
     * @param flowSizeByte      Flow size to be transferred from source to destination in bytes
     *
     * @return  Socket instance
     */
    protected abstract Socket createSocket(long flowId, int destinationId, long flowSizeByte);

    /**
     * Remove the socket from the transport layer after the flow has been finished.
     *
     * @param flowId    Flow identifier
     */
    private void removeSocket(long flowId) {
        this.finishedFlowIds.add(flowId);
        this.flowIdToSocket.remove(flowId);
    }

    public long getFlowPriority(long flowId){
        return this.flowIdToPriority.get(flowId);
    }

    /**
     * Clean up the socket references of a specific flow identifier (also overreaches
     * to the receiver).
     *
     * @param flowId    Flow identifier
     */
    void cleanupSockets(long flowId) {
        this.removeSocket(flowId);
        flowIdToReceiver.get(flowId).removeSocket(flowId);
    }

    /**
     * Get the next flow ID to be used.
     * @return The next flow ID
     */
    public static long getNextFlowId() {
        return flowIdCounter;
    }

    /**
     * Reset the static run state.
     */
    public static void staticReset() {
        flowIdCounter = 0;
        flowIdToReceiver.clear();
    }

}