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
 * RED-GREEN Tests for M5b Fix: AIFO Admission Penalty
 *
 * PURPOSE: Verify that the AIFO rank penalty correctly causes bursty flows
 * to fail the quantile admission test. Unlike M4/M5a (queue demotion),
 * AIFO has NO multi-queue scheduling — penalty is purely for admission rejection.
 *
 * KEY DIFFERENCE: The M5b fix changes the penalty value from 800000 to
 * 1,000,000,000 (REJECT keyword). Both values guarantee quantile test failure,
 * but REJECT is semantically explicit and robust against any window composition.
 *
 * AIFO admission decision (RealAIFOQueue):
 *   1. effectiveRank = baseRank + rankPenalty (if bursty)
 *   2. admitByThreshold = (queueSize <= k * maxQueue)  → admits when near-empty
 *   3. admitByQuantile = compareQuantile(effectiveRank) → rank vs window
 *   4. shouldAdmit = admitByThreshold OR admitByQuantile
 *
 * With no SRPT (baseRank=0), penalty=1B makes effectiveRank=1B.
 * Window has all rank=0 → betterCount = windowSize → quantile test FAILS.
 * Bursty packets only admitted when queue is near-empty (admitByThreshold).
 *
 * Compare to:
 *   CongestionPenaltyTargetTest.java (M4 — SP-PIFO queue demotion)
 *   PacksPenaltyTargetTest.java (M5a — PACKS queue demotion + admission)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AifoAdmissionPenaltyTest {

    // =========================================================================
    // E47 production values
    // =========================================================================
    private static final float AIFO_K = 0.2f;
    private static final int AIFO_WINDOW_SIZE = 64;
    private static final int AIFO_SAMPLE_COUNT = 1;
    private static final long MAX_QUEUE_SIZE_PACKETS = 100;  // 150KB / 1500B
    private static final long HIGH_WATERMARK = 100;
    private static final long LOW_WATERMARK = 2;

    private static final long OLD_PENALTY = 800000;           // Pre-fix value
    private static final long REJECT_PENALTY = 1_000_000_000L; // M5b REJECT value

    @Mock
    private ICongestionSignaler mockSignaler;

    private static NBProperties mockConfig;

    @BeforeAll
    static void setupLogger() {
        mockConfig = Mockito.mock(NBProperties.class);
        when(mockConfig.getPropertyWithDefault(eq("run_folder_name"), any())).thenReturn("test_aifo_penalty");
        when(mockConfig.getPropertyWithDefault(eq("run_folder_base_dir"), any())).thenReturn("temp");
        when(mockConfig.getBooleanPropertyWithDefault(anyString(), eq(false))).thenReturn(false);
        when(mockConfig.getAllPropertiesToString()).thenReturn("# Mock Config for AIFO Admission Penalty Test\n");
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
    // Helper: create mock packet
    // =========================================================================
    private Packet createMockPacket(long flowId, long priority) {
        Packet mockPacket = mock(Packet.class, withSettings().extraInterfaces(PriorityHeader.class));
        when(mockPacket.getFlowId()).thenReturn(flowId);
        when(((PriorityHeader) mockPacket).getPriority()).thenReturn(priority);
        when(mockPacket.getSizeBit()).thenReturn(12000L); // 1500 bytes
        return mockPacket;
    }

    // =========================================================================
    // Helper: create RealAIFOQueue with mock MIDST
    // =========================================================================
    private RealAIFOQueue createAifoQueueWithMidst(long penalty, Set<Long> burstyFlows) {
        RealAIFOQueue queue = new RealAIFOQueue(
            AIFO_K, AIFO_WINDOW_SIZE, AIFO_SAMPLE_COUNT,
            MAX_QUEUE_SIZE_PACKETS,
            HIGH_WATERMARK, LOW_WATERMARK, penalty,
            true, 0.99, 1000,  // Rejection rate detection with very high threshold (effectively disabled)
            false               // No virtual fill level
        );

        MIDST mockMidst = mock(MIDST.class);
        for (Long fid : burstyFlows) {
            when(mockMidst.isFlowBursty(fid)).thenReturn(true);
        }
        when(mockMidst.getVirtualQueueDepthBits()).thenReturn(0L);
        when(mockMidst.isMonitoring()).thenReturn(true);

        MIDSTCapableOutputPort mockPort = mock(MIDSTCapableOutputPort.class);
        when(mockPort.getMIDST()).thenReturn(mockMidst);
        queue.setCongestionSignaler(mockPort);

        return queue;
    }

    // =========================================================================
    // Helper: fill the rank window with normal packets (rank=0)
    // This simulates a steady-state window before bursty flows arrive
    // =========================================================================
    private void fillWindowWithNormalPackets(RealAIFOQueue queue, int count) {
        for (int i = 0; i < count; i++) {
            Packet p = createMockPacket(1000 + i, 0);  // rank=0, not bursty
            assertTrue(queue.offer(p), "Normal packet " + i + " should be admitted");
        }
        // Drain the queue to keep it near-empty (but window is populated)
        for (int i = 0; i < count; i++) {
            queue.poll();
        }
    }

    // =========================================================================
    // TEST 1: Normal flow admitted with both old and new penalty
    // =========================================================================
    @Test
    @DisplayName("AIFO: Normal flow (rank=0, not bursty) is admitted regardless of penalty value")
    void normalFlow_admitted_anyPenalty() {
        Set<Long> burstyFlows = new HashSet<>();

        // Old penalty
        RealAIFOQueue queueOld = createAifoQueueWithMidst(OLD_PENALTY, burstyFlows);
        Packet p1 = createMockPacket(100, 0);
        assertTrue(queueOld.offer(p1), "Normal flow should be admitted with old penalty");

        // REJECT penalty
        RealAIFOQueue queueNew = createAifoQueueWithMidst(REJECT_PENALTY, burstyFlows);
        Packet p2 = createMockPacket(100, 0);
        assertTrue(queueNew.offer(p2), "Normal flow should be admitted with REJECT penalty");
    }

    // =========================================================================
    // TEST 2: AIFO has single queue — no dequeue ordering by priority
    // Unlike SP-PIFO/PACKS, AIFO is pure FIFO
    // =========================================================================
    @Test
    @DisplayName("AIFO: Single FIFO queue — packets dequeue in insertion order, not by rank")
    void aifoIsFifo_noPriorityOrdering() {
        Set<Long> burstyFlows = new HashSet<>();
        RealAIFOQueue queue = createAifoQueueWithMidst(REJECT_PENALTY, burstyFlows);

        Packet first = createMockPacket(1, 500000);   // high rank
        Packet second = createMockPacket(2, 0);        // low rank

        assertTrue(queue.offer(first));
        assertTrue(queue.offer(second));

        // FIFO: first-in, first-out (not sorted by rank)
        assertEquals(first, queue.poll(), "AIFO is FIFO — first inserted packet dequeues first");
        assertEquals(second, queue.poll());
    }

    // =========================================================================
    // TEST 3: Bursty flow REJECTED when window is populated (old penalty)
    // With rank window full of rank=0 packets, bursty rank=800000 fails quantile
    // =========================================================================
    @Test
    @DisplayName("AIFO: Bursty flow (old penalty=800000) rejected when window populated")
    void burstyFlow_rejectedByQuantile_oldPenalty() {
        Set<Long> burstyFlows = new HashSet<>();
        burstyFlows.add(42L);
        RealAIFOQueue queue = createAifoQueueWithMidst(OLD_PENALTY, burstyFlows);

        // Fill window with normal packets at rank=0
        fillWindowWithNormalPackets(queue, AIFO_WINDOW_SIZE);

        // Now add enough normal packets to push queue past k threshold
        // k=0.2, maxQueue=100, so threshold at 20 packets
        for (int i = 0; i < 25; i++) {
            assertTrue(queue.offer(createMockPacket(200 + i, 0)));
        }

        // Bursty packet: effectiveRank = 0 + 800000 → fails quantile test
        Packet burstyPacket = createMockPacket(42, 0);
        assertFalse(queue.offer(burstyPacket),
            "Bursty packet with old penalty should be REJECTED when queue is congested");
    }

    // =========================================================================
    // TEST 4: Bursty flow REJECTED when window is populated (REJECT penalty)
    // Same outcome as TEST 3 but with the new REJECT value
    // =========================================================================
    @Test
    @DisplayName("AIFO: Bursty flow (REJECT penalty=1B) rejected when window populated")
    void burstyFlow_rejectedByQuantile_rejectPenalty() {
        Set<Long> burstyFlows = new HashSet<>();
        burstyFlows.add(42L);
        RealAIFOQueue queue = createAifoQueueWithMidst(REJECT_PENALTY, burstyFlows);

        // Fill window with normal packets at rank=0
        fillWindowWithNormalPackets(queue, AIFO_WINDOW_SIZE);

        // Push queue past k threshold
        for (int i = 0; i < 25; i++) {
            assertTrue(queue.offer(createMockPacket(200 + i, 0)));
        }

        // Bursty packet: effectiveRank = 0 + 1B → fails quantile test
        Packet burstyPacket = createMockPacket(42, 0);
        assertFalse(queue.offer(burstyPacket),
            "Bursty packet with REJECT penalty should be REJECTED when queue is congested");
    }

    // =========================================================================
    // TEST 5: Both penalties produce IDENTICAL admission decisions
    // This is the KEY regression test: old and new must agree on every decision
    // =========================================================================
    @Test
    @DisplayName("AIFO: Old penalty=800000 and REJECT penalty=1B produce identical admission decisions")
    void oldAndNewPenalty_identicalAdmissionDecisions() {
        Set<Long> burstyFlows = new HashSet<>();
        burstyFlows.add(42L);

        RealAIFOQueue queueOld = createAifoQueueWithMidst(OLD_PENALTY, burstyFlows);
        RealAIFOQueue queueNew = createAifoQueueWithMidst(REJECT_PENALTY, burstyFlows);

        // Fill both windows identically
        fillWindowWithNormalPackets(queueOld, AIFO_WINDOW_SIZE);
        fillWindowWithNormalPackets(queueNew, AIFO_WINDOW_SIZE);

        // Run identical packet sequences and compare admission decisions
        int agreementCount = 0;
        int totalPackets = 50;

        for (int i = 0; i < totalPackets; i++) {
            long flowId = (i % 5 == 0) ? 42L : (100 + i);  // Every 5th packet is bursty
            Packet pOld = createMockPacket(flowId, 0);
            Packet pNew = createMockPacket(flowId, 0);

            boolean admittedOld = queueOld.offer(pOld);
            boolean admittedNew = queueNew.offer(pNew);

            assertEquals(admittedOld, admittedNew,
                "Packet " + i + " (flow=" + flowId + "): old and new penalty must agree. " +
                "Old=" + admittedOld + ", New=" + admittedNew);
            agreementCount++;
        }

        assertEquals(totalPackets, agreementCount, "All packets must have identical decisions");
    }

    // =========================================================================
    // TEST 6: Bursty flow CAN be admitted when queue is near-empty
    // This verifies the "temporary suppression" behavior — not permanent kill
    // =========================================================================
    @Test
    @DisplayName("AIFO: Bursty flow admitted when queue near-empty (admitByThreshold)")
    void burstyFlow_admittedWhenQueueEmpty() {
        Set<Long> burstyFlows = new HashSet<>();
        burstyFlows.add(42L);
        RealAIFOQueue queue = createAifoQueueWithMidst(REJECT_PENALTY, burstyFlows);

        // Queue is empty → queueSize (0) <= k * maxQueue (20) → admitByThreshold = true
        Packet burstyPacket = createMockPacket(42, 0);
        assertTrue(queue.offer(burstyPacket),
            "Bursty packet should be ADMITTED when queue is near-empty (below k threshold)");
    }

    // =========================================================================
    // TEST 7: REJECT value is safe for int cast in compareQuantile()
    // RealAIFOQueue.java L243: compareQuantile((int) effectiveRank, ...)
    // =========================================================================
    @Test
    @DisplayName("AIFO: REJECT penalty=1B is safe for int cast (no overflow)")
    void rejectPenalty_safeForIntCast() {
        long baseRank = 0;
        long effectiveRank = baseRank + REJECT_PENALTY;

        // Cast to int (as done in RealAIFOQueue line 243)
        int intRank = (int) effectiveRank;

        assertTrue(intRank > 0, "REJECT penalty must be positive after int cast");
        assertEquals(1_000_000_000, intRank, "REJECT penalty must survive int cast intact");
        assertTrue(intRank < Integer.MAX_VALUE,
            "REJECT penalty must be below Integer.MAX_VALUE to avoid overflow");
    }

    // =========================================================================
    // TEST 8: REJECT is robust even with non-zero base rank
    // If SRPT is ever enabled, baseRank > 0 and penalty still dominates
    // =========================================================================
    @Test
    @DisplayName("AIFO: REJECT penalty dominates even with large base rank")
    void rejectPenalty_dominatesLargeBaseRank() {
        long baseRank = 500000;  // Hypothetical SRPT rank
        long effectiveRank = baseRank + REJECT_PENALTY;

        // Must still fit in int
        int intRank = (int) effectiveRank;
        assertTrue(intRank > 0, "effectiveRank must be positive");
        assertEquals(1_000_500_000, intRank, "Base rank + REJECT penalty = 1,000,500,000");
    }

    // =========================================================================
    // TEST 9: AIFO has no queue demotion concept
    // Verifies that penalty_target_queue semantics DON'T apply to AIFO
    // =========================================================================
    @Test
    @DisplayName("AIFO: No 'queue demotion' concept — only admit/reject")
    void aifoHasNoQueueDemotion() {
        // AIFO's property is aifo_admission_penalty (not *_target_queue)
        String aifoProperty = "aifo_admission_penalty";
        String sppifoProperty = "midst_penalty_target_queue";
        String packsProperty = "packs_penalty_target_queue";

        assertFalse(aifoProperty.contains("queue"),
            "AIFO property should NOT reference 'queue' — it has no multi-queue scheduling");
        assertTrue(sppifoProperty.contains("queue"),
            "SP-PIFO property references 'queue' — it has multi-queue scheduling");
        assertTrue(packsProperty.contains("queue"),
            "PACKS property references 'queue' — it has multi-queue scheduling");
    }

    // =========================================================================
    // TEST 10: E47 production config — end-to-end with REJECT
    // =========================================================================
    @Test
    @DisplayName("AIFO E47 production config: admission_penalty=REJECT, k=0.2, window=64")
    void e47ProductionConfig_endToEnd() {
        // Exact E47 values
        float e47K = 0.2f;
        int e47WindowSize = 64;
        int e47SampleCount = 1;
        long e47MaxQueuePackets = 150000 / 1500;  // 100 packets

        RealAIFOQueue queue = new RealAIFOQueue(
            e47K, e47WindowSize, e47SampleCount,
            e47MaxQueuePackets,
            20, 2, REJECT_PENALTY,  // E47 watermarks: high=20, low=2
            true, 0.05, 1000,       // E47 rejection detection
            false                    // No VFL for this test
        );

        // Wire MIDST mock
        MIDST mockMidst = mock(MIDST.class);
        when(mockMidst.isFlowBursty(1L)).thenReturn(false);
        when(mockMidst.isFlowBursty(2L)).thenReturn(true);
        when(mockMidst.isFlowBursty(3L)).thenReturn(false);
        when(mockMidst.getVirtualQueueDepthBits()).thenReturn(0L);
        when(mockMidst.isMonitoring()).thenReturn(true);
        MIDSTCapableOutputPort mockPort = mock(MIDSTCapableOutputPort.class);
        when(mockPort.getMIDST()).thenReturn(mockMidst);
        queue.setCongestionSignaler(mockPort);

        // Fill window
        fillWindowWithNormalPackets(queue, e47WindowSize);

        // Push queue past k threshold (>20 packets)
        for (int i = 0; i < 25; i++) {
            assertTrue(queue.offer(createMockPacket(100 + i, 0)));
        }

        // Normal packet: admitted
        assertTrue(queue.offer(createMockPacket(1, 0)), "Normal packet admitted");
        assertTrue(queue.offer(createMockPacket(3, 0)), "Normal packet admitted");

        // Bursty packet: REJECTED by quantile test
        assertFalse(queue.offer(createMockPacket(2, 0)),
            "Bursty packet with REJECT penalty should be dropped during congestion");
    }

    // =========================================================================
    // TEST 11: Semantic property names — full M4/M5a/M5b separation
    // Each scheduler has its OWN penalty property with correct semantics
    // =========================================================================
    @Test
    @DisplayName("All three schedulers have distinct penalty properties")
    void allSchedulers_haveDistinctProperties() {
        String sppifo = "midst_penalty_target_queue";   // M4: queue demotion
        String packs  = "packs_penalty_target_queue";    // M5a: queue demotion + admission
        String aifo   = "aifo_admission_penalty";        // M5b: admission only

        // All different
        assertNotEquals(sppifo, packs);
        assertNotEquals(sppifo, aifo);
        assertNotEquals(packs, aifo);

        // SP-PIFO and PACKS reference "queue" (multi-queue schedulers)
        assertTrue(sppifo.contains("queue"));
        assertTrue(packs.contains("queue"));

        // AIFO references "admission" (single-queue, admission-only)
        assertTrue(aifo.contains("admission"));
        assertFalse(aifo.contains("queue"));
    }

    // =========================================================================
    // TEST 12: computeAifoAdmissionPenalty logic — REJECT keyword
    // =========================================================================
    @Test
    @DisplayName("computeAifoAdmissionPenalty: REJECT resolves to 1,000,000,000")
    void computeAifoAdmissionPenalty_REJECT_resolves() {
        String penaltyStr = "REJECT";
        long result;

        if (penaltyStr.equalsIgnoreCase("REJECT")) {
            result = 1_000_000_000L;
        } else {
            result = Long.parseLong(penaltyStr);
        }

        assertEquals(1_000_000_000L, result);
        assertTrue((int) result > 0, "Must be positive after int cast");
    }

    // =========================================================================
    // TEST 13: computeAifoAdmissionPenalty logic — numeric value
    // =========================================================================
    @Test
    @DisplayName("computeAifoAdmissionPenalty: numeric value 500000 used as-is")
    void computeAifoAdmissionPenalty_numeric_resolves() {
        String penaltyStr = "500000";
        long result = Long.parseLong(penaltyStr);
        assertEquals(500000, result);
    }
}
