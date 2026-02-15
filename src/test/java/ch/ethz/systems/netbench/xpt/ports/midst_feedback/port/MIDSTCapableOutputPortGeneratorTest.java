package ch.ethz.systems.netbench.xpt.ports.midst_feedback.port;

// TODO: This test uses JUnit 5 (Jupiter) but the codebase uses JUnit 4.
// Convert to JUnit 4 patterns during Phase 3 MIDST implementation.
// See ERROR_FIX_PLAN.md for conversion guide.
//
// TODO: MIDSTCapableOutputPortGenerator constructor signature changed
// Constructor now requires 11 params (added sppifoRankDelta and sppifoNumQueues)
// Added default values: sppifoRankDelta=0L, sppifoNumQueues=8

// Static imports for Assertions and Mockito static methods
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

// JUnit 5 imports
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
// Mockito imports
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

// Core NetBench classes
import ch.ethz.systems.netbench.core.Simulator;
import ch.ethz.systems.netbench.core.config.NBProperties;
import ch.ethz.systems.netbench.core.log.SimulationLogger;
import ch.ethz.systems.netbench.core.network.Link;
import ch.ethz.systems.netbench.core.network.NetworkDevice;
import ch.ethz.systems.netbench.core.network.OutputPort;
// Specific queues being tested
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl.AIFOQueue;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl.BaselineQueue;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl.CanonicalSPPIFOQueue;

/**
 * Unit tests for the refactored MIDSTCapableOutputPortGenerator,
 * which now receives configuration via constructor arguments.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MIDSTCapableOutputPortGeneratorTest {

    private static NBProperties mockConfig;

    // Mocks for generate() method parameters
    @Mock private NetworkDevice mockOwnDevice;
    @Mock private NetworkDevice mockTargetDevice;
    @Mock private Link mockLink;

    // Define common parameters for easier test setup
    private final long MAX_QUEUE_SIZE_BYTES = 20000;
    private final int SKETCH_SIZE = 128;
    private final int NUM_HASH_FUNC = 3;
    private final long HWM_PACKETS = 100;
    private final long LWM_PACKETS = 50;
    private final int BURST_THRESHOLD = 10;
    private final long SPPIFO_PENALTY = 50000L;
    private final long SPPIFO_RANK_DELTA = 0L;
    private final int SPPIFO_NUM_QUEUES = 8;
    private final boolean TEST_MODE = false;

    @BeforeAll
    static void setupSimulatorAndLogger() {
        mockConfig = Mockito.mock(NBProperties.class);
        // Mock properties needed by SimulationLogger.open() (called internally by Simulator.setup)
        when(mockConfig.getPropertyWithDefault(eq("run_folder_name"), any())).thenReturn("test_run_GeneratorTest");
        when(mockConfig.getPropertyWithDefault(eq("run_folder_base_dir"), any())).thenReturn("temp");
        when(mockConfig.getBooleanPropertyWithDefault(anyString(), eq(false))).thenReturn(false);
        when(mockConfig.getAllPropertiesToString()).thenReturn("# Mock Config for GeneratorTest\n");

        // Defensive reset in case a previous test class didn't clean up
        try { Simulator.reset(); } catch (Exception e) { /* ignore */ }

        // Simulator.setup() also calls SimulationLogger.open() internally
        Simulator.setup(0, mockConfig);
    }

    @AfterAll
    static void tearDownSimulatorAndLogger() {
        // Simulator.reset() also calls SimulationLogger.closeAndThrowaway() internally
        try { Simulator.reset(); } catch (Exception e) { /* ignore */ }
    }

    @BeforeEach
    void setUp() {
        // Basic mock setup needed for the generate() call and internal port creation
        when(mockOwnDevice.getIdentifier()).thenReturn(1);
        when(mockTargetDevice.getIdentifier()).thenReturn(2);
        when(mockLink.getBandwidthBitPerNs()).thenReturn(10.0); // Ensure double
        // No need to mock NBProperties or Simulator here anymore
    }

    @Test
    void generate_withBaselineQueueType_createsBaselineQueue() {
        // Arrange
        String queueType = "baseline";
        // Instantiate generator with required parameters for this test case
        MIDSTCapableOutputPortGenerator generator = new MIDSTCapableOutputPortGenerator(
            MAX_QUEUE_SIZE_BYTES, SKETCH_SIZE, NUM_HASH_FUNC, HWM_PACKETS, LWM_PACKETS,
            BURST_THRESHOLD, queueType, SPPIFO_PENALTY, SPPIFO_RANK_DELTA, SPPIFO_NUM_QUEUES, TEST_MODE
        );

        // Act
        OutputPort generatedPort = generator.generate(mockOwnDevice, mockTargetDevice, mockLink);

        // Assert
        assertNotNull(generatedPort, "Generated port should not be null");
        assertTrue(generatedPort instanceof MIDSTCapableOutputPort, "Generated port should be MIDSTCapableOutputPort");
        MIDSTCapableOutputPort midstPort = (MIDSTCapableOutputPort) generatedPort;
        // Check the queue type created by the generator
        assertTrue(midstPort.getQueue() instanceof BaselineQueue, "Queue should be BaselineQueue");
    }

    @Test
    void generate_withSppifoQueueType_createsSppifoQueue() {
        // Arrange
        String queueType = "sppifo";
        // Instantiate generator with required parameters for this test case
        MIDSTCapableOutputPortGenerator generator = new MIDSTCapableOutputPortGenerator(
            MAX_QUEUE_SIZE_BYTES, SKETCH_SIZE, NUM_HASH_FUNC, HWM_PACKETS, LWM_PACKETS,
            BURST_THRESHOLD, queueType, SPPIFO_PENALTY, SPPIFO_RANK_DELTA, SPPIFO_NUM_QUEUES, TEST_MODE
        );

        // Act
        OutputPort generatedPort = generator.generate(mockOwnDevice, mockTargetDevice, mockLink);

        // Assert
        assertNotNull(generatedPort, "Generated port should not be null");
        assertTrue(generatedPort instanceof MIDSTCapableOutputPort, "Generated port should be MIDSTCapableOutputPort");
        MIDSTCapableOutputPort midstPort = (MIDSTCapableOutputPort) generatedPort;
        // Check the queue type created by the generator
        assertTrue(midstPort.getQueue() instanceof CanonicalSPPIFOQueue, "Queue should be CanonicalSPPIFOQueue");
    }

     @Test
    void generate_withAifoQueueType_createsAifoQueue() {
        // Arrange
        String queueType = "aifo";
        // Instantiate generator with required parameters for this test case
        MIDSTCapableOutputPortGenerator generator = new MIDSTCapableOutputPortGenerator(
            MAX_QUEUE_SIZE_BYTES, SKETCH_SIZE, NUM_HASH_FUNC, HWM_PACKETS, LWM_PACKETS,
            BURST_THRESHOLD, queueType, SPPIFO_PENALTY, SPPIFO_RANK_DELTA, SPPIFO_NUM_QUEUES, TEST_MODE
        );

        // Act
        OutputPort generatedPort = generator.generate(mockOwnDevice, mockTargetDevice, mockLink);

        // Assert
        assertNotNull(generatedPort, "Generated port should not be null");
        assertTrue(generatedPort instanceof MIDSTCapableOutputPort, "Generated port should be MIDSTCapableOutputPort");
        MIDSTCapableOutputPort midstPort = (MIDSTCapableOutputPort) generatedPort;
        // Check the queue type created by the generator
        assertTrue(midstPort.getQueue() instanceof AIFOQueue, "Queue should be AIFOQueue");
    }

    @Test
    void generate_withUnknownQueueType_defaultsToBaselineQueue() {
        // Arrange
        String queueType = "some_unknown_type";
        // Instantiate generator with required parameters for this test case
        MIDSTCapableOutputPortGenerator generator = new MIDSTCapableOutputPortGenerator(
            MAX_QUEUE_SIZE_BYTES, SKETCH_SIZE, NUM_HASH_FUNC, HWM_PACKETS, LWM_PACKETS,
            BURST_THRESHOLD, queueType, SPPIFO_PENALTY, SPPIFO_RANK_DELTA, SPPIFO_NUM_QUEUES, TEST_MODE
        );
        // Check console output for expected warning log from generator

        // Act
        OutputPort generatedPort = generator.generate(mockOwnDevice, mockTargetDevice, mockLink);

        // Assert
        assertNotNull(generatedPort, "Generated port should not be null");
        assertTrue(generatedPort instanceof MIDSTCapableOutputPort, "Generated port should be MIDSTCapableOutputPort");
        MIDSTCapableOutputPort midstPort = (MIDSTCapableOutputPort) generatedPort;
        // Check the queue type created by the generator (should default)
        assertTrue(midstPort.getQueue() instanceof BaselineQueue, "Queue should default to BaselineQueue for unknown type");
    }

     @Test
    void generate_withNullQueueType_defaultsToBaselineQueue() {
        // Arrange
        String queueType = null; // Test null handling
         // Instantiate generator with required parameters for this test case
        MIDSTCapableOutputPortGenerator generator = new MIDSTCapableOutputPortGenerator(
            MAX_QUEUE_SIZE_BYTES, SKETCH_SIZE, NUM_HASH_FUNC, HWM_PACKETS, LWM_PACKETS,
            BURST_THRESHOLD, queueType, SPPIFO_PENALTY, SPPIFO_RANK_DELTA, SPPIFO_NUM_QUEUES, TEST_MODE
        );

        // Act
        OutputPort generatedPort = generator.generate(mockOwnDevice, mockTargetDevice, mockLink);

        // Assert
        assertNotNull(generatedPort, "Generated port should not be null");
        assertTrue(generatedPort instanceof MIDSTCapableOutputPort, "Generated port should be MIDSTCapableOutputPort");
        MIDSTCapableOutputPort midstPort = (MIDSTCapableOutputPort) generatedPort;
         // Check the queue type created by the generator (should default)
        assertTrue(midstPort.getQueue() instanceof BaselineQueue, "Queue should default to BaselineQueue for null type");
    }

    // Assumption check: Ensure the MIDSTCapableOutputPort constructor tested here
    // actually matches the one expected by the Generator (i.e., taking primitives/String,
    // NOT NBProperties directly). If the Port constructor wasn't refactored too,
    // the generator's `generate` method would fail at the `new MIDSTCapableOutputPort(...)` line.
}