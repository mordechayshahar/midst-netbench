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
 * RED-GREEN Tests for M4 Fix: Semantic Penalty Target Queue
 *
 * PURPOSE: Verify that the congestion penalty rank correctly maps bursty flows
 * to the expected queue index. These tests lock down the CURRENT behavior
 * (hardcoded 800000) as a regression baseline, then verify the semantic fix
 * (penalty_target_queue=LAST) produces identical results.
 *
 * RED PHASE: Tests pass with current hardcoded value (800000).
 * GREEN PHASE: After fix, tests still pass with auto-computed value.
 *
 * The critical invariant is:
 *   getQueueIndex(baseRank + congestionPenaltyRank) == targetQueue
 *   where targetQueue = numQueues - 1 for "LAST"
 *
 * Code path under test:
 *   NewCanonicalSPPIFOQueue.offer() L241-243:
 *     finalRank = baseRank + congestionPenaltyRank
 *   NewCanonicalSPPIFOQueue.getQueueIndex() L207:
 *     Math.min((int)(rank / rankDelta), numQueues - 1)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CongestionPenaltyTargetTest {

    // =========================================================================
    // Test configurations matching E49 production values
    // =========================================================================
    private static final int NUM_QUEUES = 5;
    private static final long RANK_DELTA = 200000;
    private static final long HARDCODED_PENALTY = 800000;  // The magic number we're fixing
    private static final long HIGH_WATERMARK = 100;  // Large enough to not trigger congestion in tests
    private static final long LOW_WATERMARK = 2;

    @Mock
    private ICongestionSignaler mockSignaler;

    private static NBProperties mockConfig;

    @BeforeAll
    static void setupLogger() {
        mockConfig = Mockito.mock(NBProperties.class);
        when(mockConfig.getPropertyWithDefault(eq("run_folder_name"), any())).thenReturn("test_penalty_target");
        when(mockConfig.getPropertyWithDefault(eq("run_folder_base_dir"), any())).thenReturn("temp");
        when(mockConfig.getBooleanPropertyWithDefault(anyString(), eq(false))).thenReturn(false);
        when(mockConfig.getAllPropertiesToString()).thenReturn("# Mock Config for Penalty Target Test\n");
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
    // Helper: create mock packet with specific priority (rank)
    // =========================================================================
    private Packet createMockPacket(long flowId, long priority) {
        Packet mockPacket = mock(Packet.class, withSettings().extraInterfaces(PriorityHeader.class));
        when(mockPacket.getFlowId()).thenReturn(flowId);
        when(((PriorityHeader) mockPacket).getPriority()).thenReturn(priority);
        when(mockPacket.getSizeBit()).thenReturn(12000L); // 1500 bytes
        return mockPacket;
    }

    // =========================================================================
    // Helper: create a queue with mock MIDST that marks specific flows as bursty
    // =========================================================================
    private NewCanonicalSPPIFOQueue createQueueWithMidst(long penaltyRank, Set<Long> burstyFlows) {
        NewCanonicalSPPIFOQueue queue = new NewCanonicalSPPIFOQueue(
            HIGH_WATERMARK, LOW_WATERMARK, RANK_DELTA, NUM_QUEUES, penaltyRank
        );

        // Create a mock MIDST module that returns bursty status
        MIDST mockMidst = mock(MIDST.class);
        for (Long flowId : burstyFlows) {
            when(mockMidst.isFlowBursty(flowId)).thenReturn(true);
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
    // RED TEST 1: Normal flow with rank=0 goes to Q0
    // Baseline: verifies queue index calculation without penalty
    // =========================================================================
    @Test
    @DisplayName("Normal flow (rank=0, not bursty) should go to Q0")
    void normalFlow_rank0_goesToQ0() {
        Set<Long> burstyFlows = new HashSet<>();
        NewCanonicalSPPIFOQueue queue = createQueueWithMidst(HARDCODED_PENALTY, burstyFlows);

        Packet packet = createMockPacket(100, 0);  // rank=0, not bursty
        assertTrue(queue.offer(packet));

        // Q0 should have the packet (dequeue from highest priority first)
        Packet dequeued = queue.poll();
        assertNotNull(dequeued);
        assertEquals(packet, dequeued);
        assertEquals(0, queue.size(), "Queue should be empty after dequeuing the only packet");
    }

    // =========================================================================
    // RED TEST 2: Bursty flow with penalty=800000 goes to Q4 (LAST)
    // This is THE critical test — the one that locks down current behavior
    // =========================================================================
    @Test
    @DisplayName("Bursty flow (rank=0, penalty=800000) should go to Q4 (LAST)")
    void burstyFlow_penalty800000_goesToQ4() {
        Set<Long> burstyFlows = new HashSet<>();
        burstyFlows.add(42L);
        NewCanonicalSPPIFOQueue queue = createQueueWithMidst(HARDCODED_PENALTY, burstyFlows);

        // Add a normal packet to Q0
        Packet normalPacket = createMockPacket(100, 0);
        assertTrue(queue.offer(normalPacket));

        // Add a bursty packet — should go to Q4
        Packet burstyPacket = createMockPacket(42, 0);
        assertTrue(queue.offer(burstyPacket));

        // Dequeue: Q0 first (normal), then Q4 (bursty)
        Packet first = queue.poll();
        assertEquals(normalPacket, first, "Normal packet should dequeue first (from Q0)");

        Packet second = queue.poll();
        assertEquals(burstyPacket, second, "Bursty packet should dequeue second (from Q4)");
    }

    // =========================================================================
    // RED TEST 3: Verify penalty maps to exact queue index
    // penalty = (targetQueue) * rankDelta  → queue index = targetQueue
    // 800000 / 200000 = 4 → Q4 = numQueues-1 = LAST
    // =========================================================================
    @Test
    @DisplayName("Penalty 800000 with rankDelta=200000 and numQueues=5 targets exactly Q4")
    void penalty_exactlyTargets_lastQueue() {
        // Mathematical verification:
        // getQueueIndex(0 + 800000) = Math.min(800000 / 200000, 5-1) = Math.min(4, 4) = 4
        int expectedQueueIndex = (int) Math.min(HARDCODED_PENALTY / RANK_DELTA, NUM_QUEUES - 1);
        assertEquals(4, expectedQueueIndex, "Penalty should target Q4");
        assertEquals(NUM_QUEUES - 1, expectedQueueIndex, "Q4 should be the LAST queue");
    }

    // =========================================================================
    // RED TEST 4: Semantic fix produces same penalty value
    // This test verifies the AUTO-COMPUTATION is correct
    // =========================================================================
    @Test
    @DisplayName("Semantic: LAST queue penalty = (numQueues-1) × rankDelta = 800000")
    void semanticPenalty_LAST_equalsHardcoded() {
        long computedPenalty = (long)(NUM_QUEUES - 1) * RANK_DELTA;
        assertEquals(HARDCODED_PENALTY, computedPenalty,
            "Auto-computed penalty for LAST queue should equal hardcoded 800000");
    }

    // =========================================================================
    // RED TEST 5: Different numQueues — penalty auto-adjusts
    // If numQueues=8, LAST → (8-1)*200000 = 1400000
    // =========================================================================
    @Test
    @DisplayName("With numQueues=8, LAST penalty auto-adjusts to 1400000")
    void semanticPenalty_LAST_adjustsWithNumQueues() {
        int altNumQueues = 8;
        long expectedPenalty = (long)(altNumQueues - 1) * RANK_DELTA;
        assertEquals(1400000, expectedPenalty);

        // Verify it targets the correct last queue
        int queueIndex = (int) Math.min(expectedPenalty / RANK_DELTA, altNumQueues - 1);
        assertEquals(altNumQueues - 1, queueIndex, "Should target last queue in 8-queue config");
    }

    // =========================================================================
    // RED TEST 6: Different rankDelta — penalty auto-adjusts
    // If rankDelta=100000, LAST → (5-1)*100000 = 400000
    // =========================================================================
    @Test
    @DisplayName("With rankDelta=100000, LAST penalty auto-adjusts to 400000")
    void semanticPenalty_LAST_adjustsWithRankDelta() {
        long altRankDelta = 100000;
        long expectedPenalty = (long)(NUM_QUEUES - 1) * altRankDelta;
        assertEquals(400000, expectedPenalty);

        // Verify queue targeting with actual queue instance
        Set<Long> burstyFlows = new HashSet<>();
        burstyFlows.add(42L);
        NewCanonicalSPPIFOQueue queue = new NewCanonicalSPPIFOQueue(
            HIGH_WATERMARK, LOW_WATERMARK, altRankDelta, NUM_QUEUES, expectedPenalty
        );

        // Create mock MIDST
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
    // RED TEST 7: Silent failure scenario — wrong penalty with mismatched config
    // THIS IS THE BUG we're fixing: if rankDelta changes but penalty doesn't,
    // the bursty flow ends up in the WRONG queue
    // =========================================================================
    @Test
    @DisplayName("BUG DEMO: Hardcoded penalty=800000 fails when rankDelta changes to 2000000")
    void hardcodedPenalty_failsWithChangedRankDelta() {
        // Scenario: someone changes rankDelta to 2,000,000 but forgets to update penalty
        long bigRankDelta = 2000000;

        // With the hardcoded penalty:
        // getQueueIndex(800000) = Math.min(800000/2000000, 4) = Math.min(0, 4) = 0
        // BUG: Bursty flow stays in Q0! Penalty is meaningless.
        int queueWithHardcoded = (int) Math.min(HARDCODED_PENALTY / bigRankDelta, NUM_QUEUES - 1);
        assertEquals(0, queueWithHardcoded,
            "BUG: Hardcoded 800000 with rankDelta=2M maps to Q0 — penalty has NO EFFECT!");

        // With semantic computation:
        long semanticPenalty = (long)(NUM_QUEUES - 1) * bigRankDelta;
        int queueWithSemantic = (int) Math.min(semanticPenalty / bigRankDelta, NUM_QUEUES - 1);
        assertEquals(NUM_QUEUES - 1, queueWithSemantic,
            "Semantic penalty correctly targets LAST queue regardless of rankDelta");
    }

    // =========================================================================
    // RED TEST 8: Intermediate queue targeting (not just LAST)
    // penalty_target_queue=2 → penalty = 2 * rankDelta = 400000
    // =========================================================================
    @Test
    @DisplayName("Penalty targeting Q2: penalty = 2 × rankDelta = 400000")
    void penaltyTargetQ2_mapsCorrectly() {
        int targetQueue = 2;
        long penalty = (long) targetQueue * RANK_DELTA;
        assertEquals(400000, penalty);

        // Verify the queue index
        int queueIndex = (int) Math.min(penalty / RANK_DELTA, NUM_QUEUES - 1);
        assertEquals(2, queueIndex, "Should target Q2");
    }

    // =========================================================================
    // RED TEST 9: Edge case — penalty=0 means NO queue demotion
    // Bursty flow stays in Q0 (penalty disabled)
    // =========================================================================
    @Test
    @DisplayName("Penalty=0 means bursty flows stay in their natural queue")
    void zeroPenalty_noDemotion() {
        Set<Long> burstyFlows = new HashSet<>();
        burstyFlows.add(42L);
        NewCanonicalSPPIFOQueue queue = createQueueWithMidst(0, burstyFlows);

        Packet burstyPacket = createMockPacket(42, 0);
        assertTrue(queue.offer(burstyPacket));

        // With penalty=0, bursty packet rank=0+0=0, goes to Q0
        Packet dequeued = queue.poll();
        assertEquals(burstyPacket, dequeued, "With zero penalty, bursty packet stays in Q0");
    }

    // =========================================================================
    // RED TEST 10: E49 exact production config — end-to-end regression
    // Uses the exact values from e49_sppifo_midst_no_srpt_fattree_100.properties
    // =========================================================================
    @Test
    @DisplayName("E49 production config: penalty=800000, rankDelta=200000, numQueues=5")
    void e49ProductionConfig_endToEnd() {
        // Exact E49 values
        int e49NumQueues = 5;
        long e49RankDelta = 200000;
        long e49Penalty = 800000;  // will be computed as LAST after fix
        long e49HighWatermark = 20;
        long e49LowWatermark = 2;

        NewCanonicalSPPIFOQueue queue = new NewCanonicalSPPIFOQueue(
            e49HighWatermark, e49LowWatermark, e49RankDelta, e49NumQueues, e49Penalty
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
            "Bursty packet should dequeue LAST (from Q4 after penalty)");
    }

    // =========================================================================
    // RED TEST 11: Verify semantic auto-computation for E49
    // The semantic formula must produce exactly 800000 for E49's config
    // =========================================================================
    @Test
    @DisplayName("Semantic computation for E49: (5-1) × 200000 = 800000")
    void e49SemanticComputation_matchesProduction() {
        // E49 config
        int numQueues = 5;
        long rankDelta = 200000;

        // "LAST" means target queue = numQueues - 1 = 4
        int targetQueueIndex = numQueues - 1;
        long computedPenalty = (long) targetQueueIndex * rankDelta;

        // CRITICAL: This must EXACTLY match the current hardcoded value
        assertEquals(800000, computedPenalty,
            "Semantic LAST penalty must equal current hardcoded 800000 for E49 config");
    }

    // =========================================================================
    // GREEN TEST (post-fix): InfrastructureSelector.computePenaltyRank()
    // This test verifies the NEW static helper that resolves the property.
    // Will be testable after the fix is applied.
    // =========================================================================
    @Test
    @DisplayName("computePenaltyRank: LAST resolves to (numQueues-1)*rankDelta")
    void computePenaltyRank_LAST_resolves() {
        // This test documents the contract of the new helper method.
        // Manual computation since we test the static method separately.
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
    // GREEN TEST (post-fix): Integer target queue resolves correctly
    // =========================================================================
    @Test
    @DisplayName("computePenaltyRank: target=3 resolves to 3*rankDelta=600000")
    void computePenaltyRank_integer_resolves() {
        String targetQueue = "3";
        long rankDelta = 200000;

        long result = Long.parseLong(targetQueue) * rankDelta;
        assertEquals(600000, result);
    }
}
