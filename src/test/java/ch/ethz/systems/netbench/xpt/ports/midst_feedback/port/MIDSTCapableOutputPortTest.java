package ch.ethz.systems.netbench.xpt.ports.midst_feedback.port;

// TODO: This test uses JUnit 5 (Jupiter) but the codebase uses JUnit 4.
// Convert to JUnit 4 patterns during Phase 3 MIDST implementation.
// See ERROR_FIX_PLAN.md for conversion guide.

// Core NetBench classes
import ch.ethz.systems.netbench.core.Simulator; // Still needed for logger init
import ch.ethz.systems.netbench.core.config.NBProperties;
import ch.ethz.systems.netbench.core.log.SimulationLogger;
import ch.ethz.systems.netbench.core.network.Link;
import ch.ethz.systems.netbench.core.network.NetworkDevice;
import ch.ethz.systems.netbench.core.network.OutputPort;
import ch.ethz.systems.netbench.core.network.Packet;

// Interfaces and Queues being tested/mocked
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.ICongestionSignaler;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.IMonitoredQueue;

// JUnit 5 imports
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

// Mockito imports
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
// NOTE: No longer need MockedStatic or mockStatic if Port constructor doesn't call Simulator.getConfig()
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

// Java utilities
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

// Static imports for Assertions and Mockito static methods
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MIDSTCapableOutputPort.
 * Assumes its constructor now takes primitive/object arguments directly, not NBProperties.
 * Still initializes SimulationLogger via @BeforeAll.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MIDSTCapableOutputPortTest {

    // --- Mocks ---
    @Mock private NetworkDevice mockOwnDevice;
    @Mock private NetworkDevice mockTargetDevice;
    @Mock private Link mockLink;
    @Mock private IMonitoredQueue mockQueue;
    @Mock private Packet mockPacket;

    // Static mock config ONLY for SimulationLogger.open()
    @Mock private static NBProperties mockConfigurationForLogger;

    // --- Test Subject ---
    private MIDSTCapableOutputPort port;

    // --- Test Parameters ---
    // Use final fields for parameters passed to constructor
    private final long maxQueueSizeBytes = 15000;
    private final int sketchSize = 10;
    private final int numHashFunctions = 2;
    private final int burstThreshold = 5;
    private final long highWatermarkPackets = 50;
    private final long lowWatermarkPackets = 10;
    private final boolean testMode = false;
    private final boolean internalLoggingEnabled = false; // Assumed parameter for logging
    private final double portVflFraction = 1.0; // 1.0 = disabled (bursty flows see full buffer)


    // --- Logger Initialization & Cleanup ---
    @BeforeAll
    static void setupLogger() {
        mockConfigurationForLogger = Mockito.mock(NBProperties.class);
        System.out.println("PortTest: Configuring mocks for SimulationLogger.open()...");

        // Mock properties needed ONLY by SimulationLogger.open()
        when(mockConfigurationForLogger.getPropertyWithDefault(eq("run_folder_name"), any())).thenReturn("test_run_MIDSTPortTest");
        when(mockConfigurationForLogger.getPropertyWithDefault(eq("run_folder_base_dir"), any())).thenReturn("temp");
        when(mockConfigurationForLogger.getBooleanPropertyWithDefault(anyString(), eq(false))).thenReturn(false); // Default for flags
        when(mockConfigurationForLogger.getAllPropertiesToString()).thenReturn("# Mock Config for PortTest Logger\n");

        System.out.println("Mock configuration for Logger setup complete.");

        // Defensive reset in case a previous test class didn't clean up
        try { Simulator.reset(); } catch (Exception e) { /* ignore */ }

        // Simulator.setup() also calls SimulationLogger.open() internally
        Simulator.setup(0, mockConfigurationForLogger);
        System.out.println("PortTest: Simulator and SimulationLogger initialized.");
    }

    @AfterAll
    static void tearDownLogger() {
        // Simulator.reset() also calls SimulationLogger.closeAndThrowaway() internally
        try {
             Simulator.reset();
             System.out.println("PortTest: Simulator reset and SimulationLogger closed.");
        } catch (Exception e) {
             System.err.println("PortTest: Error during cleanup: " + e.getMessage());
        }
    }


    // --- Test Setup ---
    @BeforeEach
    void setUp() {
        // Mock basic device/link properties needed by OutputPort superclass constructor
        when(mockOwnDevice.getIdentifier()).thenReturn(1);
        when(mockTargetDevice.getIdentifier()).thenReturn(2);
        when(mockLink.getBandwidthBitPerNs()).thenReturn(10.0); // Ensure double

        // Reset queue mock between tests
        reset(mockQueue);

        // Create the port instance using the NEW assumed constructor signature
        // Pass the primitive/boolean parameters directly
        port = new MIDSTCapableOutputPort(
                mockOwnDevice,
                mockTargetDevice,
                mockLink,
                mockQueue,
                maxQueueSizeBytes,
                sketchSize,
                numHashFunctions,
                burstThreshold,
                highWatermarkPackets,
                lowWatermarkPackets,
                testMode,
                internalLoggingEnabled, // Pass the boolean directly
                portVflFraction, // Port-level VFL: 1.0 = disabled
                0.0 // adaptiveThresholdFraction: 0.0 = disabled (absolute threshold only)
        );

        // Verify signaler was set (still happens in constructor)
        verify(mockQueue).setCongestionSignaler(port);
    }

    // --- Tests ---

    // Test constructor initializes state based on passed parameters
    @Test
    void constructor_initializesStateCorrectly() {
        assertEquals(maxQueueSizeBytes * 8, port.maxQueueSizeBits);
        assertEquals(sketchSize, port.sketchSize);
        assertEquals(numHashFunctions, port.numHashFunctions);
        assertEquals(burstThreshold, port.midstBurstThreshold); // Check if threshold stored correctly
        assertEquals(testMode, port.testMode); // Check testMode field if needed
        // Check internalLoggingEnabled field if it exists and is needed

        assertNotNull(port.sin);
        assertNotNull(port.sout);
        assertNotNull(port.sinDirtyFlags);
        assertNotNull(port.soutDirtyFlags);
        assertEquals(sketchSize * numHashFunctions, port.sin.length(), "Flat sketch array size mismatch");
        assertFalse(port.isMonitoring, "Should not be monitoring initially");
        assertTrue(port.packetMetadataMap.isEmpty(), "Metadata map should be empty initially");
        assertTrue(port.activeFlowsInWindow.isEmpty(), "Active flows set should be empty initially");
    }

    // Test signalCongestionStart (remains the same)
    @Test
    void signalCongestionStart_setsMonitoringTrue_resetsFlags() {
       port.sinDirtyFlags.set(0, 1);
       port.soutDirtyFlags.set(1, 1);
       port.activeFlowsInWindow.add(99L);
       port.isMonitoring = false;
       port.signalCongestionStart();
       assertTrue(port.isMonitoring, "isMonitoring should be true after start signal");
       assertTrue(port.activeFlowsInWindow.isEmpty(), "Active flows should be cleared on start");
       for (int i = 0; i < port.sinDirtyFlags.length(); i++) {
           assertEquals(0, port.sinDirtyFlags.get(i), "Sin flag at index " + i + " should be 0");
           assertEquals(0, port.soutDirtyFlags.get(i), "Sout flag at index " + i + " should be 0");
       }
   }

    // Test signalCongestionEnd (remains the same)
    @Test
    void signalCongestionEnd_setsMonitoringFalse_appliesFeedback_resetsEntries() {
        port.signalCongestionStart();
        long congestedFlowId = 1L; long normalFlowId = 2L;
        int[] congestedFlatIndices = port.getHashIndices(congestedFlowId);
        int[] normalFlatIndices = port.getHashIndices(normalFlowId);
        port.activeFlowsInWindow.add(congestedFlowId); port.activeFlowsInWindow.add(normalFlowId);
        port.sin.set(congestedFlatIndices[0], burstThreshold + 1); port.sin.set(congestedFlatIndices[1], burstThreshold + 2);
        port.sinDirtyFlags.set(congestedFlatIndices[0], 1); port.sinDirtyFlags.set(congestedFlatIndices[1], 1);
        port.sin.set(normalFlatIndices[0], burstThreshold - 1); port.sin.set(normalFlatIndices[1], burstThreshold - 2);
        port.sinDirtyFlags.set(normalFlatIndices[0], 1); port.sinDirtyFlags.set(normalFlatIndices[1], 1);
        port.sout.set(congestedFlatIndices[0], 2); port.soutDirtyFlags.set(congestedFlatIndices[0], 1);
        port.sout.set(normalFlatIndices[1], 1); port.soutDirtyFlags.set(normalFlatIndices[1], 1);

        port.signalCongestionEnd();

        assertFalse(port.isMonitoring);
        ArgumentCaptor<Set<Long>> feedbackCaptor = ArgumentCaptor.forClass(Set.class);
        verify(mockQueue).applyCongestionFeedback(feedbackCaptor.capture());
        assertEquals(Collections.singleton(congestedFlowId), feedbackCaptor.getValue());
        assertEquals(0, port.sin.get(congestedFlatIndices[0])); assertEquals(0, port.sin.get(congestedFlatIndices[1]));
        assertEquals(0, port.sinDirtyFlags.get(congestedFlatIndices[0])); assertEquals(0, port.sinDirtyFlags.get(congestedFlatIndices[1]));
        assertEquals(0, port.sout.get(congestedFlatIndices[0])); assertEquals(0, port.soutDirtyFlags.get(congestedFlatIndices[0]));
        assertEquals(0, port.sin.get(normalFlatIndices[0])); assertEquals(0, port.sin.get(normalFlatIndices[1]));
        assertEquals(0, port.sinDirtyFlags.get(normalFlatIndices[0])); assertEquals(0, port.sinDirtyFlags.get(normalFlatIndices[1]));
        assertEquals(0, port.sout.get(normalFlatIndices[1])); assertEquals(0, port.soutDirtyFlags.get(normalFlatIndices[1]));
    }

    // Test enqueue when buffer full (remains the same)
    @Test
    void enqueue_whenBufferFull_dropsPacket_removesMetadata() {
        long packetSizeBits = maxQueueSizeBytes * 8 + 8;
        long flowId = 456L;
        when(mockPacket.getSizeBit()).thenReturn(packetSizeBits); when(mockPacket.getFlowId()).thenReturn(flowId);
        port.isMonitoring = true;
        int[] actualIndices = port.getHashIndices(flowId);
        PacketMetadata_MIDST meta = new PacketMetadata_MIDST(new int[]{1,1}, actualIndices);
        port.packetMetadataMap.put(mockPacket, meta);
        port.activeFlowsInWindow.add(flowId);

        port.enqueue(mockPacket);

        verify(mockQueue, never()).offer(mockPacket);
        assertFalse(port.packetMetadataMap.containsKey(mockPacket));
        assertTrue(port.activeFlowsInWindow.contains(flowId));
    }

    // Test enqueue when space available (remains the same, assertions confirmed correct previously)
    @Test
    void enqueue_whenSpaceAvailable_updatesIngress_offersOrDispatches() {
        long flowId = 123L;
        when(mockPacket.getSizeBit()).thenReturn(1000L * 8L); when(mockPacket.getFlowId()).thenReturn(flowId);
        port.isMonitoring = true; when(mockQueue.offer(mockPacket)).thenReturn(true);
        // port.isSending = false; // Start idle

        assertTrue(port.isMonitoring(), "PRE-ASSERT: isMonitoring should be true");
        int[] actualIndices = port.getHashIndices(flowId);
        assertEquals(0, port.sin.get(actualIndices[0]), "PRE-ASSERT: Initial sin value");

        port.enqueue(mockPacket);

        assertTrue(port.packetMetadataMap.containsKey(mockPacket));
        assertEquals(1, port.sin.get(actualIndices[0])); assertEquals(1, port.sin.get(actualIndices[1]));
        assertEquals(1, port.sinDirtyFlags.get(actualIndices[0])); assertEquals(1, port.sinDirtyFlags.get(actualIndices[1]));
        assertTrue(port.activeFlowsInWindow.contains(flowId));
        // Assuming starts idle based on default OutputPort state
        verify(mockQueue, never()).offer(mockPacket); // Because it dispatches immediately
        assertTrue(port.getIsSending()); // Check state changed
    }

    // Test dispatch (remains the same, assertions confirmed correct previously)
    @Test
    void dispatch_callsUpdateEgress_andRemovesMetadata() {
         long flowId = 789L;
         when(mockPacket.getFlowId()).thenReturn(flowId);
         int[] actualIndices = port.getHashIndices(flowId);
         int[] ingressCounts = {5, 5};
         PacketMetadata_MIDST meta = new PacketMetadata_MIDST(ingressCounts, actualIndices);
         port.packetMetadataMap.put(mockPacket, meta);
         assertTrue(port.packetMetadataMap.containsKey(mockPacket));
         assertArrayEquals(actualIndices, port.packetMetadataMap.get(mockPacket).getHashIndices());
         port.sout.set(actualIndices[0], 1); port.sout.set(actualIndices[1], 2);
         port.soutDirtyFlags.set(actualIndices[0], 0); port.soutDirtyFlags.set(actualIndices[1], 0);
         port.isMonitoring = true;
         when(mockQueue.isEmpty()).thenReturn(true);

         assertEquals(1, port.sout.get(actualIndices[0]), "PRE-ASSERT: Initial sout value");

         port.dispatch(mockPacket);

         assertEquals(2, port.sout.get(actualIndices[0]));
         assertEquals(3, port.sout.get(actualIndices[1]));
         assertEquals(1, port.soutDirtyFlags.get(actualIndices[0]));
         assertEquals(1, port.soutDirtyFlags.get(actualIndices[1]));
         assertFalse(port.packetMetadataMap.containsKey(mockPacket));
    }

    // Test updateIngressSketch when not monitoring (remains the same)
    @Test
    void updateIngressSketch_whenNotMonitoring_doesNothing() {
        long flowId = 111L;
        port.isMonitoring = false;
        when(mockPacket.getFlowId()).thenReturn(flowId);
        int[] actualIndices = port.getHashIndices(flowId);

        port.updateIngressSketch(mockPacket);

        assertEquals(0, port.sin.get(actualIndices[0])); assertEquals(0, port.sin.get(actualIndices[1]));
        assertEquals(0, port.sinDirtyFlags.get(actualIndices[0])); assertEquals(0, port.sinDirtyFlags.get(actualIndices[1]));
        assertFalse(port.packetMetadataMap.containsKey(mockPacket));
        assertFalse(port.activeFlowsInWindow.contains(flowId));
    }

    // Test updateEgressSketch when no metadata (remains the same)
    @Test
    void updateEgressSketch_whenNoMetadata_doesNothing() {
        long flowId = 222L;
        int[] actualIndices = port.getHashIndices(flowId);
        port.isMonitoring = true;
        when(mockPacket.getFlowId()).thenReturn(flowId);
        assertFalse(port.packetMetadataMap.containsKey(mockPacket));

        port.updateEgressSketch(mockPacket);

        assertEquals(0, port.sout.get(actualIndices[0])); assertEquals(0, port.sout.get(actualIndices[1]));
        assertEquals(0, port.soutDirtyFlags.get(actualIndices[0])); assertEquals(0, port.soutDirtyFlags.get(actualIndices[1]));
    }
}