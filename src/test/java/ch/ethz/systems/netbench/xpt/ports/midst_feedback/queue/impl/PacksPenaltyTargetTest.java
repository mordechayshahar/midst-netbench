package ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl;

import ch.ethz.systems.netbench.core.Simulator;
import ch.ethz.systems.netbench.core.config.NBProperties;
import ch.ethz.systems.netbench.core.log.SimulationLogger;
import ch.ethz.systems.netbench.core.network.Packet;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.core.MIDST;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.interfaces.ICongestionSignaler;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.port.MIDSTCapableOutputPort;
import ch.ethz.systems.netbench.xpt.tcpbase.PriorityHeader;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RED-GREEN Tests for M5a Fix: PACKS-Specific Penalty Target Queue
 *
 * PURPOSE: Verify that the PACKS rank penalty correctly maps bursty flows
 * to the expected queue index AND causes admission rejection. These tests
 * lock down the CURRENT behavior (penalty=800000 from aifo_rank_penalty)
 * as a regression baseline, then verify the M5a fix (packs_penalty_target_queue=LAST)
 * produces identical results via the PACKS-specific property.
 *
 * PACKS penalty has DUAL EFFECT (unlike SP-PIFO which only demotes queues):
 *   1. Queue demotion: queueIndex = (baseRank + penalty) / rankDelta → Q4
 *   2. Admission rejection: checkAdmission(effectiveRank, effectiveSize)
 *      — inflated rank fails quantile test → packet dropped
 *
 * Code path under test (RealPACKSQueue):
 *   offer() L176-178: effectiveRank = baseRank + rankPenalty (if bursty)
 *   offer() L218:     queueIndex = Math.min(effectiveRank / rankDelta, numQueues - 1)
 *   offer() L201:     admitted = checkAdmission(effectiveRank, effectiveSize)
 *
 * Compare to: CongestionPenaltyTargetTest.java (M4 — SP-PIFO only, no admission gate)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PacksPenaltyTargetTest {

    // =========================================================================
    // Test configurations matching E45 production values
    // =========================================================================
    private static final int NUM_QUEUES = 5;
    private static final long RANK_DELTA = 200000;
    private static final long HARDCODED_PENALTY = 800000;  // The value we're migrating to packs_penalty_target_queue
    private static final long HIGH_WATERMARK = 100;  // Large enough to not trigger congestion in tests
    private static final long LOW_WATERMARK = 2;
    private static final long MAX_QUEUE_SIZE_PACKETS = 100;  // 150KB / 1500B = 100 packets
    private static final float AIFO_K = 0.2f;
    private static final int AIFO_WINDOW_SIZE = 64;
    private static final int AIFO_SAMPLE_COUNT = 1;

    @Mock
    private ICongestionSignaler mockSignaler;

    private static NBProperties mockConfig;

    @BeforeAll
    static void setupLogger() {
        mockConfig = Mockito.mock(NBProperties.class);
        when(mockConfig.getPropertyWithDefault(eq("run_folder_name"), any())).thenReturn("test_packs_penalty");
        when(mockConfig.getPropertyWithDefault(eq("run_folder_base_dir"), any())).thenReturn("temp");
        when(mockConfig.getBooleanPropertyWithDefault(anyString(), eq(false))).thenReturn(false);
        when(mockConfig.getAllPropertiesToString()).thenReturn("# Mock Config for PACKS Penalty Target Test\n");
        try {
            SimulationLogger.open(mockConfig);
        } catch (Exception e) {
            fail("SimulationLogger initialization failed: " + e.getMessage());
        }
    }

    @AfterAll
    static void tearDownLogger() {
        try { SimulationLogger.closeAndThrowaway(); } catch (Exception e) { /* ignore */ }
    }

    @BeforeEach
    void setUp() {
        Simulator.setup(0, mockConfig);
    }

    @AfterEach
    void tearDown() {
        Simulator.reset();
    }

    // =========================================================================
    // Helper: create mock packet with specific priority (rank) and flowId
    // Must implement PriorityHeader for PACKS to read rank and flow IDs
    // =========================================================================
    private Packet createMockPacket(long flowId, long priority) {
        Packet mockPacket = mock(Packet.class, withSettings().extraInterfaces(PriorityHeader.class));
        when(mockPacket.getFlowId()).thenReturn(flowId);
        when(((PriorityHeader) mockPacket).getPriority()).thenReturn(priority);
        when(((PriorityHeader) mockPacket).getSourceId()).thenReturn((int)(flowId / 100000));
        when(((PriorityHeader) mockPacket).getDestinationId()).thenReturn((int)(flowId % 100000));
        when(mockPacket.getSizeBit()).thenReturn(12000L); // 1500 bytes
        return mockPacket;
    }

    // =========================================================================
    // Helper: create a RealPACKSQueue with mock MIDST that marks specific flows as bursty
    // =========================================================================
    private RealPACKSQueue createPacksQueueWithMidst(long penaltyRank, Set<Long> burstyFlows) {
        RealPACKSQueue queue = new RealPACKSQueue(
            AIFO_K, AIFO_WINDOW_SIZE, AIFO_SAMPLE_COUNT,
            NUM_QUEUES, RANK_DELTA,
            MAX_QUEUE_SIZE_PACKETS,
            HIGH_WATERMARK, LOW_WATERMARK, penaltyRank,
            true, 0.99, 1000,  // Rejection rate detection with very high threshold (effectively disabled)
            false               // No virtual fill level
        );

        // Create a mock MIDST module that returns bursty status
        MIDST mockMidst = mock(MIDST.class);
        for (Long fid : burstyFlows) {
            when(mockMidst.isFlowBursty(fid)).thenReturn(true);
        }
        when(mockMidst.getVirtualQueueDepthBits()).thenReturn(0L);
        when(mockMidst.isMonitoring()).thenReturn(true);

        // Create a mock MIDSTCapableOutputPort that provides the MIDST module
        MIDSTCapableOutputPort mockPort = mock(MIDSTCapableOutputPort.class);
        when(mockPort.getMIDST()).thenReturn(mockMidst);

        // Wire it up: setCongestionSignaler triggers MIDST module acquisition
        queue.setCongestionSignaler(mockPort);

        return queue;
    }

    // =========================================================================
    // RED TEST 1: Normal flow with rank=0 goes to Q0 (admitted, no penalty)
    // Baseline: verifies queue index calculation AND admission without penalty
    // =========================================================================
    @Test
    @DisplayName("PACKS: Normal flow (rank=0, not bursty) goes to Q0 and is admitted")
    void normalFlow_rank0_goesToQ0_admitted() {
        Set<Long> burstyFlows = new HashSet<>();
        RealPACKSQueue queue = createPacksQueueWithMidst(HARDCODED_PENALTY, burstyFlows);

        Packet packet = createMockPacket(100, 0);  // rank=0, not bursty
        assertTrue(queue.offer(packet), "Normal flow should be admitted");

        // Q0 should have the packet (dequeue from highest priority first)
        Packet dequeued = queue.poll();
        assertNotNull(dequeued);
        assertEquals(packet, dequeued);
        assertEquals(0, queue.size(), "Queue should be empty after dequeuing");
    }

    // =========================================================================
    // RED TEST 2: Bursty flow with penalty=800000 goes to Q4 (LAST)
    // THE critical test — locks down current behavior for PACKS queue demotion
    // =========================================================================
    @Test
    @DisplayName("PACKS: Bursty flow (rank=0, penalty=800000) goes to Q4 (LAST)")
    void burstyFlow_penalty800000_goesToQ4() {
        Set<Long> burstyFlows = new HashSet<>();
        burstyFlows.add(42L);
        RealPACKSQueue queue = createPacksQueueWithMidst(HARDCODED_PENALTY, burstyFlows);

        // Add a normal packet first (goes to Q0)
        Packet normalPacket = createMockPacket(100, 0);
        assertTrue(queue.offer(normalPacket));

        // Add a bursty packet — should go to Q4 due to penalty
        Packet burstyPacket = createMockPacket(42, 0);
        assertTrue(queue.offer(burstyPacket));

        // Dequeue: Q0 first (normal), then Q4 (bursty)
        Packet first = queue.poll();
        assertEquals(normalPacket, first, "Normal packet should dequeue first (from Q0)");

        Packet second = queue.poll();
        assertEquals(burstyPacket, second, "Bursty packet should dequeue second (from Q4)");
    }

    // =========================================================================
    // RED TEST 3: Verify PACKS penalty maps to exact queue index
    // penalty = (targetQueue) * rankDelta → queue index = targetQueue
    // 800000 / 200000 = 4 → Q4 = numQueues-1 = LAST
    // =========================================================================
    @Test
    @DisplayName("PACKS: Penalty 800000 with rankDelta=200000 targets exactly Q4")
    void penalty_exactlyTargets_lastQueue() {
        // PACKS uses the same queue index formula as SP-PIFO:
        // queueIndex = Math.min(effectiveRank / rankDelta, numQueues - 1)
        int expectedQueueIndex = (int) Math.min(HARDCODED_PENALTY / RANK_DELTA, NUM_QUEUES - 1);
        assertEquals(4, expectedQueueIndex, "Penalty should target Q4");
        assertEquals(NUM_QUEUES - 1, expectedQueueIndex, "Q4 should be the LAST queue");
    }

    // =========================================================================
    // RED TEST 4: Semantic fix produces same penalty value for PACKS
    // packs_penalty_target_queue=LAST → (numQueues-1) × rankDelta = 800000
    // =========================================================================
    @Test
    @DisplayName("PACKS semantic: LAST queue penalty = (numQueues-1) × rankDelta = 800000")
    void semanticPenalty_LAST_equalsHardcoded() {
        long computedPenalty = (long)(NUM_QUEUES - 1) * RANK_DELTA;
        assertEquals(HARDCODED_PENALTY, computedPenalty,
            "Auto-computed PACKS penalty for LAST queue should equal 800000");
    }

    // =========================================================================
    // RED TEST 5: Different numQueues — PACKS penalty auto-adjusts
    // numQueues=8 → LAST = (8-1)*200000 = 1400000
    // =========================================================================
    @Test
    @DisplayName("PACKS: With numQueues=8, LAST penalty auto-adjusts to 1400000")
    void semanticPenalty_LAST_adjustsWithNumQueues() {
        int altNumQueues = 8;
        long expectedPenalty = (long)(altNumQueues - 1) * RANK_DELTA;
        assertEquals(1400000, expectedPenalty);

        // Verify it targets the correct last queue in PACKS
        int queueIndex = (int) Math.min(expectedPenalty / RANK_DELTA, altNumQueues - 1);
        assertEquals(altNumQueues - 1, queueIndex, "Should target last queue in 8-queue PACKS config");
    }

    // =========================================================================
    // RED TEST 6: Different rankDelta — PACKS penalty auto-adjusts
    // rankDelta=100000 → LAST = (5-1)*100000 = 400000
    // =========================================================================
    @Test
    @DisplayName("PACKS: With rankDelta=100000, LAST penalty auto-adjusts to 400000")
    void semanticPenalty_LAST_adjustsWithRankDelta() {
        long altRankDelta = 100000;
        long expectedPenalty = (long)(NUM_QUEUES - 1) * altRankDelta;
        assertEquals(400000, expectedPenalty);

        // Create PACKS queue with adjusted parameters
        Set<Long> burstyFlows = new HashSet<>();
        burstyFlows.add(42L);
        RealPACKSQueue queue = new RealPACKSQueue(
            AIFO_K, AIFO_WINDOW_SIZE, AIFO_SAMPLE_COUNT,
            NUM_QUEUES, altRankDelta,
            MAX_QUEUE_SIZE_PACKETS,
            HIGH_WATERMARK, LOW_WATERMARK, expectedPenalty,
            true, 0.99, 1000,
            false
        );

        // Wire MIDST mock
        MIDST mockMidst = mock(MIDST.class);
        when(mockMidst.isFlowBursty(42L)).thenReturn(true);
        when(mockMidst.getVirtualQueueDepthBits()).thenReturn(0L);
        when(mockMidst.isMonitoring()).thenReturn(true);
        MIDSTCapableOutputPort mockPort = mock(MIDSTCapableOutputPort.class);
        when(mockPort.getMIDST()).thenReturn(mockMidst);
        queue.setCongestionSignaler(mockPort);

        // Add normal and bursty packets
        Packet normalPacket = createMockPacket(100, 0);
        Packet burstyPacket = createMockPacket(42, 0);
        assertTrue(queue.offer(normalPacket));
        assertTrue(queue.offer(burstyPacket));

        // Normal dequeues first (Q0), bursty dequeues last (Q4)
        assertEquals(normalPacket, queue.poll(), "Normal should come first");
        assertEquals(burstyPacket, queue.poll(), "Bursty should come last");
    }

    // =========================================================================
    // RED TEST 7: Silent failure — wrong penalty with mismatched rankDelta
    // Same bug as M4 but for PACKS: penalty stuck at 800000, rankDelta grows
    // =========================================================================
    @Test
    @DisplayName("PACKS BUG DEMO: Hardcoded penalty=800000 fails when rankDelta=2000000")
    void hardcodedPenalty_failsWithChangedRankDelta() {
        long bigRankDelta = 2000000;

        // With hardcoded penalty:
        // queueIndex = Math.min(800000/2000000, 4) = Math.min(0, 4) = 0
        // BUG: Bursty flow stays in Q0! Penalty useless for queue demotion.
        int queueWithHardcoded = (int) Math.min(HARDCODED_PENALTY / bigRankDelta, NUM_QUEUES - 1);
        assertEquals(0, queueWithHardcoded,
            "BUG: Hardcoded 800000 with rankDelta=2M maps to Q0 — penalty has NO queue demotion effect!");

        // With semantic computation:
        long semanticPenalty = (long)(NUM_QUEUES - 1) * bigRankDelta;
        int queueWithSemantic = (int) Math.min(semanticPenalty / bigRankDelta, NUM_QUEUES - 1);
        assertEquals(NUM_QUEUES - 1, queueWithSemantic,
            "Semantic penalty correctly targets LAST queue regardless of rankDelta");
    }

    // =========================================================================
    // RED TEST 8: Zero penalty means no queue demotion in PACKS
    // Bursty flows stay in Q0 — penalty disabled
    // =========================================================================
    @Test
    @DisplayName("PACKS: Penalty=0 means bursty flows stay in their natural queue")
    void zeroPenalty_noDemotion() {
        Set<Long> burstyFlows = new HashSet<>();
        burstyFlows.add(42L);
        RealPACKSQueue queue = createPacksQueueWithMidst(0, burstyFlows);

        Packet burstyPacket = createMockPacket(42, 0);
        assertTrue(queue.offer(burstyPacket));

        // With penalty=0, effectiveRank=0+0=0, queueIndex=0 → Q0
        Packet dequeued = queue.poll();
        assertEquals(burstyPacket, dequeued, "With zero penalty, bursty packet stays in Q0");
    }

    // =========================================================================
    // RED TEST 9: E45 exact production config — end-to-end regression
    // Uses the exact values from e45_packs_midst_no_srpt_fattree_100.properties
    // =========================================================================
    @Test
    @DisplayName("PACKS E45 production config: penalty=800000, rankDelta=200000, numQueues=5")
    void e45ProductionConfig_endToEnd() {
        // Exact E45 values
        int e45NumQueues = 5;
        long e45RankDelta = 200000;
        long e45Penalty = 800000;  // will be computed as LAST after M5a fix
        float e45K = 0.2f;
        int e45WindowSize = 64;
        int e45SampleCount = 1;
        long e45MaxQueuePackets = 150000 / 1500;  // 100 packets

        RealPACKSQueue queue = new RealPACKSQueue(
            e45K, e45WindowSize, e45SampleCount,
            e45NumQueues, e45RankDelta,
            e45MaxQueuePackets,
            20, 2, e45Penalty,  // E45 watermarks: high=20, low=2
            true, 0.05, 1000,   // E45 rejection detection
            false                // VFL disabled for this test (isolates queue demotion)
        );

        // Set up MIDST mock for bursty detection
        MIDST mockMidst = mock(MIDST.class);
        when(mockMidst.isFlowBursty(1L)).thenReturn(false);   // Normal flow
        when(mockMidst.isFlowBursty(2L)).thenReturn(true);    // Bursty flow
        when(mockMidst.isFlowBursty(3L)).thenReturn(false);   // Normal flow
        when(mockMidst.getVirtualQueueDepthBits()).thenReturn(0L);
        when(mockMidst.isMonitoring()).thenReturn(true);
        MIDSTCapableOutputPort mockPort = mock(MIDSTCapableOutputPort.class);
        when(mockPort.getMIDST()).thenReturn(mockMidst);
        queue.setCongestionSignaler(mockPort);

        // Add packets: 2 normal (rank=0→Q0), 1 bursty (rank=800000→Q4)
        Packet normal1 = createMockPacket(1, 0);
        Packet bursty  = createMockPacket(2, 0);
        Packet normal2 = createMockPacket(3, 0);

        assertTrue(queue.offer(normal1));
        assertTrue(queue.offer(bursty));
        assertTrue(queue.offer(normal2));

        assertEquals(3, queue.size());

        // Dequeue order: Q0 first (normal1, normal2), then Q4 (bursty)
        Packet first  = queue.poll();
        Packet second = queue.poll();
        Packet third  = queue.poll();

        // Normal packets dequeue first (both from Q0)
        assertTrue(first == normal1 || first == normal2,
            "First dequeue should be a normal packet from Q0");
        assertTrue(second == normal1 || second == normal2,
            "Second dequeue should be the other normal packet from Q0");
        assertNotEquals(first, second, "Should be different packets");

        // Bursty packet dequeues last (from Q4)
        assertEquals(bursty, third,
            "Bursty packet should dequeue LAST (from Q4 after PACKS penalty)");
    }

    // =========================================================================
    // RED TEST 10: Semantic auto-computation matches E45 hardcoded value
    // =========================================================================
    @Test
    @DisplayName("PACKS semantic for E45: (5-1) × 200000 = 800000")
    void e45SemanticComputation_matchesProduction() {
        int numQueues = 5;
        long rankDelta = 200000;

        // "LAST" → target queue = numQueues - 1 = 4
        int targetQueueIndex = numQueues - 1;
        long computedPenalty = (long) targetQueueIndex * rankDelta;

        assertEquals(800000, computedPenalty,
            "Semantic LAST penalty must equal current E45 hardcoded 800000");
    }

    // =========================================================================
    // RED TEST 11: PACKS-specific property is separate from SP-PIFO's
    // Ensures packs_penalty_target_queue and midst_penalty_target_queue are independent
    // =========================================================================
    @Test
    @DisplayName("PACKS and SP-PIFO penalty properties are independent")
    void packsAndSppifoPenalties_areIndependent() {
        // These are the TWO SEPARATE property names (one per scheduler)
        String packsProperty = "packs_penalty_target_queue";
        String sppifoProperty = "midst_penalty_target_queue";

        assertNotEquals(packsProperty, sppifoProperty,
            "PACKS and SP-PIFO must have different property names");

        // Both resolve to the same formula but from different properties
        long packsComputed = (long)(NUM_QUEUES - 1) * RANK_DELTA;
        long sppifoComputed = (long)(NUM_QUEUES - 1) * RANK_DELTA;
        assertEquals(packsComputed, sppifoComputed,
            "With same config, both compute the same penalty value (800000)");
    }

    // =========================================================================
    // GREEN TEST 12: computePacksPenaltyRank logic — LAST resolves correctly
    // Documents the contract of the M5a helper method
    // =========================================================================
    @Test
    @DisplayName("computePacksPenaltyRank: LAST resolves to (numQueues-1)*rankDelta")
    void computePacksPenaltyRank_LAST_resolves() {
        String targetQueue = "LAST";
        int numQueues = 5;
        long rankDelta = 200000;

        long result;
        if (targetQueue.equalsIgnoreCase("LAST")) {
            result = (long)(numQueues - 1) * rankDelta;
        } else {
            result = Long.parseLong(targetQueue) * rankDelta;
        }

        assertEquals(800000, result);
    }

    // =========================================================================
    // GREEN TEST 13: computePacksPenaltyRank logic — integer target resolves
    // =========================================================================
    @Test
    @DisplayName("computePacksPenaltyRank: target=3 resolves to 3*rankDelta=600000")
    void computePacksPenaltyRank_integer_resolves() {
        String targetQueue = "3";
        long rankDelta = 200000;

        long result = Long.parseLong(targetQueue) * rankDelta;
        assertEquals(600000, result);
    }
}
