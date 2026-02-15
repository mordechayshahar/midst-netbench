package ch.ethz.systems.netbench.xpt.ports.midst_feedback.core;

import ch.ethz.systems.netbench.core.Simulator;
import ch.ethz.systems.netbench.core.config.NBProperties;
import ch.ethz.systems.netbench.core.log.SimulationLogger;
import ch.ethz.systems.netbench.core.network.Packet;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RED Tests for Sketch Saturation Under Sustained Monitoring
 * ===========================================================
 *
 * PROBLEM REFRAMING (Phase 10 insight):
 * --------------------------------------
 * The monitoring window staying open under sustained oversubscription is NOT a bug.
 * At 31:1 fan-in under PACKS, the port IS permanently congested. MIDST is correct
 * that congestion exists — the window SHOULD stay open.
 *
 * The REAL problem is SKETCH SATURATION:
 *   - The 256-bucket count-min sketch accumulates for the entire monitoring window
 *   - Under sustained monitoring (960ms out of 1000ms simulation), ~24K+ packets
 *     flow through a 256x3=768 entry sketch
 *   - Hash collisions dominate → every flow appears bursty → 99.3% false positives
 *
 * WHY ORIGINAL MIDST DIDN'T NEED THIS:
 *   The original MIDST paper (SOSR 2022, Section 3.1) was evaluated on a Tofino
 *   switch where oversubscription is TEMPORARY. Microbursts spike the queue, then
 *   the queue drains between bursts. Their l << h design ensures cleaning time.
 *   Under permanent fan-in oversubscription (our PACKS/AIFO setup), the queue
 *   never drains. ConQuest (same queue-depth trigger) would break identically.
 *
 * THE FIX: SKETCH ROTATION
 *   Periodically analyze and reset the sketch while monitoring CONTINUES.
 *   After N packets (tied to sketch capacity), trigger:
 *     1. Analyze current sketch → identify bursty flows
 *     2. Reset sketch counters
 *     3. Continue monitoring (don't close the window)
 *   This is the same cleaning the original MIDST does between windows,
 *   but performed DURING sustained windows via packet-count trigger.
 *
 * CROSS-PAPER PRECEDENT:
 *   CoDel uses time-bounded intervals. PIE uses periodic T_UPDATE timers.
 *   FlowRadar/UnivMon use fixed time epochs. We use packet-count epochs,
 *   which is P4-trivial (one register + one comparison) and avoids the
 *   problems of time-based approaches under variable congestion patterns.
 *
 * TEST STRUCTURE:
 *   RED  R1: Sketch must be analyzed during sustained PACKS monitoring
 *   RED  R2: Same config must produce sketch analysis for BOTH PACKS and SP-PIFO
 *   RED  R3: Sketch accumulation must be bounded (not exceed sketch capacity)
 *   GREEN R4: VQ must remain bounded (Phase 9 regression guard)
 *   GREEN R5: Window cycling works under non-oversubscribed traffic (existing behavior)
 */
class VirtualQueueStuckWindowTest {

    private static final long PKT = 1500L * 8L; // 12000 bits per packet

    private static NBProperties mockConfig;

    @BeforeAll
    static void setupLogger() {
        mockConfig = Mockito.mock(NBProperties.class);
        when(mockConfig.getPropertyWithDefault(eq("run_folder_name"), any())).thenReturn("test_stuck_window");
        when(mockConfig.getPropertyWithDefault(eq("run_folder_base_dir"), any())).thenReturn("temp");
        when(mockConfig.getBooleanPropertyWithDefault(anyString(), eq(false))).thenReturn(false);
        when(mockConfig.getAllPropertiesToString()).thenReturn("# Mock Config\n");

        try {
            SimulationLogger.open(mockConfig);
        } catch (Exception e) {
            fail("SimulationLogger initialization failed: " + e.getMessage());
        }
    }

    @AfterAll
    static void tearDownLogger() {
        try {
            SimulationLogger.closeAndThrowaway();
        } catch (Exception e) {
            // Ignore
        }
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
    // HELPER: Create mock packet with specified flow ID and standard size
    // =========================================================================

    private Packet createMockPacket(long flowId) {
        Packet p = mock(Packet.class);
        when(p.getFlowId()).thenReturn(flowId);
        when(p.getSizeBit()).thenReturn(PKT);
        return p;
    }

    // =========================================================================
    // RED TEST R1: Sketch must be analyzed during sustained PACKS monitoring
    //
    // This is the CORE test. Under PACKS at 31:1 fan-in, the port is permanently
    // congested. MIDST correctly keeps the monitoring window open. But the sketch
    // accumulates indefinitely — after ~768 entries (256 buckets x 3 rows),
    // collisions dominate and every flow appears bursty.
    //
    // The sketch MUST be periodically analyzed and reset ("rotated") even when
    // the monitoring window stays open. Each rotation produces a fresh sketch
    // analysis, keeping detection accurate.
    //
    // This test feeds 2000+ packets through sustained monitoring and asserts
    // that at least one sketch analysis cycle completed (monitoringWindowCount >= 1).
    // Currently: stopMonitoring() never fires → monitoringWindowCount = 0 → RED.
    // =========================================================================

    @Test
    @DisplayName("R1 RED: Sketch must be analyzed during sustained PACKS monitoring")
    void r1_sketchMustBeAnalyzedDuringSustainedMonitoring() {
        // --- Configuration matching real experiments ---
        // Sketch: 256 buckets, 3 hash functions (768 total entries)
        // Watermarks: high=50, low=2 packets
        // Burst threshold: 5 packets (not important for this test)
        MIDSTImpl midst = MIDSTImpl.withPacketWatermarks(
            256, 3, 5,     // sketchSize, numHashFunctions, burstThreshold
            50, 2,         // highWatermarkPackets, lowWatermarkPackets
            "packs-sustained", false
        );

        // -----------------------------------------------------------
        // Phase 1: Push VQ above high watermark to open monitoring.
        //
        // Simulate 55 accepted packet arrivals (VQ: 0 → 55).
        // Monitoring opens when VQ crosses high=50 (at packet 50).
        // Packets 50-55 are counted in the sketch (6 packets).
        // -----------------------------------------------------------
        List<Packet> initialPackets = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            Packet p = createMockPacket(i % 10); // 10 different flows
            midst.onPacketArrival(p);
            initialPackets.add(p);
        }
        assertTrue(midst.isMonitoring(),
            "Monitoring should be active (VQ=55 > high=50)");
        assertEquals(0, midst.getMonitoringWindowCount(),
            "No sketch analysis completed yet");

        // -----------------------------------------------------------
        // Phase 2: Sustained PACKS traffic.
        //
        // Under PACKS at 31:1, most packets are dropped by admission control.
        // Phase 9 cancels each drop's VQ effect: arrival (+PKT) then drop (-PKT).
        // VQ stays at 55 — stuck above low=2, monitoring stays open.
        //
        // Each arrival during monitoring updates the ingress sketch.
        // After 768+ arrivals, the 256x3 sketch is saturated.
        // After 2000 arrivals, detection is completely unreliable.
        //
        // We model this as: for each "tick", one packet arrives and is
        // immediately dropped (Phase 9). VQ stays constant at 55.
        // -----------------------------------------------------------
        int sustainedPackets = 2000; // Well above sketch capacity (768)
        for (int i = 0; i < sustainedPackets; i++) {
            Packet p = createMockPacket(i % 10); // 10 flows in rotation
            midst.onPacketArrival(p);       // VQ: 55 → 56, sketch updated
            midst.onPacketDropped(p);       // VQ: 56 → 55, Phase 9 cancel
        }

        // Verify: monitoring is STILL active (VQ at 55, above low=2)
        assertTrue(midst.isMonitoring(),
            "Monitoring should remain active under sustained congestion (VQ=55 > low=2)");

        System.out.println("[R1] Sustained PACKS monitoring: " +
            "monitoringWindowCount=" + midst.getMonitoringWindowCount() +
            ", totalWindowPackets=" + midst.getTotalWindowPackets() +
            ", isMonitoring=" + midst.isMonitoring());

        // ============================================================
        // THE CORE RED ASSERTION:
        //
        // After 2000 packets through a 768-entry sketch, the sketch MUST
        // have been analyzed at least once. Without sketch rotation, the
        // sketch just accumulates → saturates → 99.3% false positives.
        //
        // Currently: monitoringWindowCount = 0 because stopMonitoring()
        // is never called (VQ stays at 55, never crosses low=2).
        //
        // Fix: add sketch rotation after N packets during monitoring.
        // Each rotation calls analyze+reset, incrementing windowCount.
        // ============================================================
        assertTrue(midst.getMonitoringWindowCount() >= 1,
            "R1 FAILS: Sketch was NEVER analyzed during sustained monitoring! " +
            sustainedPackets + " packets accumulated in a 256x3 sketch (capacity=768). " +
            "Hash collisions saturate the sketch → every flow appears bursty (99.3% FP). " +
            "monitoringWindowCount=" + midst.getMonitoringWindowCount() + ". " +
            "Fix: add sketch rotation — periodic analyze+reset while monitoring continues.");
    }

    // =========================================================================
    // RED TEST R2: Sketch analysis works across scheduler types
    //
    // Different schedulers produce different monitoring patterns:
    //   - PACKS: permanent congestion → monitoring stays open → needs rotation
    //   - SP-PIFO: temporary congestion → monitoring cycles naturally
    //
    // The SAME MIDSTImpl configuration must produce sketch analysis for BOTH.
    // SP-PIFO already works (windows cycle). PACKS does NOT (stuck window).
    //
    // With sketch rotation: PACKS gets analysis via rotation, SP-PIFO gets it
    // via natural window cycling. Same config, both work.
    // =========================================================================

    @Test
    @DisplayName("R2 RED: Same config must produce sketch analysis for BOTH PACKS and SP-PIFO")
    void r2_crossScheduler_sketchAnalysisMustWorkForBoth() {
        // SAME watermarks and sketch config for both schedulers
        int sketchSize = 256;
        int numHash = 3;
        int burstThreshold = 5;
        int highWM = 50; // packets
        int lowWM = 2;   // packets

        // =============================================
        // SCENARIO A: PACKS (sustained oversubscription)
        // =============================================
        // VQ stabilizes at ~55 between watermarks → window stuck open
        MIDSTImpl midstPacks = MIDSTImpl.withPacketWatermarks(
            sketchSize, numHash, burstThreshold,
            highWM, lowWM, "packs", false
        );

        // Open monitoring: 55 arrivals → VQ=55 > high=50
        for (int i = 0; i < 55; i++) {
            midstPacks.onPacketArrival(createMockPacket(i % 10));
        }
        assertTrue(midstPacks.isMonitoring(), "PACKS: monitoring should be active");

        // Sustained traffic: 2000 arrival+drop pairs (VQ stays at 55)
        for (int i = 0; i < 2000; i++) {
            Packet p = createMockPacket(i % 10);
            midstPacks.onPacketArrival(p);
            midstPacks.onPacketDropped(p);
        }

        // =============================================
        // SCENARIO B: SP-PIFO (temporary bursts)
        // =============================================
        // Queue fills during bursts, drains between bursts → windows cycle
        MIDSTImpl midstSppifo = MIDSTImpl.withPacketWatermarks(
            sketchSize, numHash, burstThreshold,
            highWM, lowWM, "sppifo", false
        );

        // 5 burst cycles with full drain between
        for (int cycle = 0; cycle < 5; cycle++) {
            // Burst: 55 arrivals (VQ: 0 → 55, crosses high=50)
            List<Packet> burstPackets = new ArrayList<>();
            for (int i = 0; i < 55; i++) {
                Packet p = createMockPacket(i % 10);
                midstSppifo.onPacketArrival(p);
                burstPackets.add(p);
            }

            // Drain: all 55 packets depart (VQ: 55 → 0, crosses low=2)
            for (Packet p : burstPackets) {
                midstSppifo.onPacketDeparture(p);
            }
        }

        System.out.println("[R2] PACKS:   monitoringWindowCount=" +
            midstPacks.getMonitoringWindowCount() + ", isMonitoring=" + midstPacks.isMonitoring());
        System.out.println("[R2] SP-PIFO: monitoringWindowCount=" +
            midstSppifo.getMonitoringWindowCount() + ", isMonitoring=" + midstSppifo.isMonitoring());

        // ============================================================
        // ASSERTIONS: BOTH must have at least 1 sketch analysis
        // ============================================================
        boolean packsAnalyzed = midstPacks.getMonitoringWindowCount() >= 1;
        boolean sppifoAnalyzed = midstSppifo.getMonitoringWindowCount() >= 1;

        assertTrue(packsAnalyzed && sppifoAnalyzed,
            "R2 FAILS: Same config (sketch=" + sketchSize + "x" + numHash +
            ", high=" + highWM + ", low=" + lowWM + ") does not produce sketch analysis for both. " +
            "PACKS: windowCount=" + midstPacks.getMonitoringWindowCount() +
            " (sustained monitoring, no rotation → STUCK). " +
            "SP-PIFO: windowCount=" + midstSppifo.getMonitoringWindowCount() +
            " (natural cycling → works). " +
            "Fix: sketch rotation gives PACKS analysis without changing SP-PIFO behavior.");
    }

    // =========================================================================
    // RED TEST R3: Sketch accumulation must be bounded during sustained monitoring
    //
    // The count-min sketch has finite capacity (256 x 3 = 768 entries).
    // After ~768 packet updates, hash collisions saturate the sketch.
    // Under sustained monitoring, the sketch must NOT accumulate more than
    // a bounded number of packets before being analyzed and reset.
    //
    // Bound: sketchCapacity * K where K is a small constant (e.g., K=2).
    // This ensures the sketch is rotated before hash collisions dominate.
    //
    // Currently: totalWindowPackets grows without bound during stuck monitoring
    // (observed: 24,000+ packets in a single window in real experiments).
    // =========================================================================

    @Test
    @DisplayName("R3 RED: Sketch accumulation must be bounded during sustained monitoring")
    void r3_sketchAccumulationMustBeBounded() {
        int sketchSize = 256;
        int numHash = 3;
        int sketchCapacity = sketchSize * numHash; // 768 entries

        MIDSTImpl midst = MIDSTImpl.withPacketWatermarks(
            sketchSize, numHash, 5,
            50, 2,
            "bounded-sketch", false
        );

        // Open monitoring: 55 arrivals → VQ=55 > high=50
        for (int i = 0; i < 55; i++) {
            midst.onPacketArrival(createMockPacket(i % 10));
        }
        assertTrue(midst.isMonitoring(), "Monitoring should be active");

        // Sustained traffic: 2000 arrival+drop pairs
        // Each arrival increments totalWindowPackets
        for (int i = 0; i < 2000; i++) {
            Packet p = createMockPacket(i % 10);
            midst.onPacketArrival(p);
            midst.onPacketDropped(p);
        }

        long totalAccumulated = midst.getTotalWindowPackets();
        // Principled bound: 2x sketch capacity = 1536
        // After this many packets, the sketch is definitely saturated.
        // A correct implementation rotates the sketch BEFORE this point.
        long maxAllowedAccumulation = sketchCapacity * 2L; // 1536

        System.out.println("[R3] Sketch accumulation: totalWindowPackets=" + totalAccumulated +
            ", maxAllowed=" + maxAllowedAccumulation +
            ", sketchCapacity=" + sketchCapacity);

        // ============================================================
        // KEY ASSERTION: sketch accumulation must be bounded
        //
        // Currently: totalWindowPackets = 2006 (6 from initial burst + 2000 sustained)
        // This FAR exceeds 2x sketch capacity (1536).
        // In real experiments: 24,000+ packets accumulate in one window.
        //
        // Fix: sketch rotation resets totalWindowPackets after each cycle.
        // The value at any point should be <= maxWindowPackets (roughly sketch capacity).
        // ============================================================
        assertTrue(totalAccumulated <= maxAllowedAccumulation,
            "R3 FAILS: Sketch accumulated " + totalAccumulated + " packets in a single analysis window! " +
            "Sketch capacity is " + sketchCapacity + " entries (max allowed: " + maxAllowedAccumulation + "). " +
            "After " + sketchCapacity + " packets, hash collisions saturate the sketch. " +
            "In experiments: 24K+ packets accumulate → every flow appears bursty. " +
            "Fix: sketch rotation resets counters every N packets during sustained monitoring.");
    }

    // =========================================================================
    // RED TEST R6: Bursty flow state must persist across sketch rotations
    //
    // When monitoring stays open and the sketch rotates, flows detected as bursty
    // in rotation N must STILL be classified as bursty after rotation N+1.
    // Without this, the CSV isBurstyDetected flag is never set for 3-tier experiments
    // (R1b/R4b/R5b) because currentBurstyFlows is cleared on each rotation.
    //
    // This test:
    //   1. Creates a dominant flow that will be detected as bursty
    //   2. Feeds enough packets to trigger at least one rotation
    //   3. Feeds more packets from OTHER flows (so the dominant flow is NOT in the
    //      current rotation's sketch)
    //   4. Asserts isFlowBursty() still returns true for the dominant flow
    //
    // Currently: isFlowBursty() returns false after rotation → RED.
    // Fix: rotateSketch() preserves accumulated bursty flows.
    // =========================================================================

    @Test
    @DisplayName("R6 RED: Bursty flow state must persist across sketch rotations")
    void r6_burstyFlowStateMustPersistAcrossRotations() {
        // Use adaptive threshold to ensure detection is meaningful
        MIDSTImpl midst = MIDSTImpl.withPacketWatermarks(
            256, 3, 5,     // sketchSize, numHashFunctions, burstThreshold
            50, 2,         // highWatermarkPackets, lowWatermarkPackets
            "persist-test", false,
            0.05           // adaptiveThresholdFraction = 5%
        );

        int maxWinPkts = midst.getMaxWindowPackets(); // Should be 1024

        // Phase 1: Open monitoring (55 arrivals → VQ=55 > high=50)
        for (int i = 0; i < 55; i++) {
            midst.onPacketArrival(createMockPacket(100)); // all from flow 100
        }
        assertTrue(midst.isMonitoring(), "Monitoring should be active");

        // Phase 2: Feed dominant flow (flow 100) heavily to be detected as bursty.
        // With 5% adaptive threshold and flow 100 having most packets, it should be bursty.
        // Feed enough to trigger first rotation (maxWindowPackets = 1024).
        // Only ~5 of the 55 initial packets counted in the sketch (monitoring starts
        // at VQ=50, so packets 50-54 are the only ones in updateIngressSketch).
        // Need ~1020 more arrival+drops to reach 1024 total.
        int packetsToRotation = maxWinPkts + 50; // Comfortably over threshold
        for (int i = 0; i < packetsToRotation; i++) {
            // 80% from flow 100 (dominant), 20% from other flows
            long flowId = (i % 5 == 0) ? (i % 10 + 200) : 100;
            Packet p = createMockPacket(flowId);
            midst.onPacketArrival(p);
            midst.onPacketDropped(p);
        }

        // Verify: at least one rotation happened
        assertTrue(midst.getMonitoringWindowCount() >= 1,
            "At least one rotation should have occurred after " + packetsToRotation + " packets");

        // Flow 100 should be bursty (detected in first rotation)
        boolean burstyAfterRotation1 = midst.isFlowBursty(100);

        System.out.println("[R6] After rotation 1: isFlowBursty(100)=" + burstyAfterRotation1 +
            ", windowCount=" + midst.getMonitoringWindowCount());

        // Phase 3: Feed a SECOND rotation worth of packets, but from DIFFERENT flows.
        // Flow 100 is NOT present in this rotation's sketch.
        for (int i = 0; i < maxWinPkts + 10; i++) {
            Packet p = createMockPacket(300 + (i % 20)); // flows 300-319, NOT 100
            midst.onPacketArrival(p);
            midst.onPacketDropped(p);
        }

        // Verify: second rotation happened
        assertTrue(midst.getMonitoringWindowCount() >= 2,
            "At least two rotations should have occurred");

        // ============================================================
        // THE CORE RED ASSERTION:
        //
        // Flow 100 was detected as bursty in rotation 1.
        // It is NOT in rotation 2's sketch (no packets from flow 100).
        // isFlowBursty(100) must STILL return true.
        //
        // Currently: stopMonitoring() in rotation 2 clears currentBurstyFlows,
        // then repopulates with only rotation 2's detections (flows 300-319).
        // Flow 100 is lost → isFlowBursty(100) returns false → RED.
        //
        // Fix: rotateSketch() preserves accumulated bursty flows.
        // ============================================================
        boolean burstyAfterRotation2 = midst.isFlowBursty(100);

        System.out.println("[R6] After rotation 2: isFlowBursty(100)=" + burstyAfterRotation2 +
            ", windowCount=" + midst.getMonitoringWindowCount());

        assertTrue(burstyAfterRotation2,
            "R6 FAILS: Flow 100 was detected as bursty in rotation 1, but isFlowBursty(100)=" +
            burstyAfterRotation2 + " after rotation 2 (which had different flows). " +
            "Bursty state must persist across rotations within the same monitoring session. " +
            "Without this, the CSV isBurstyDetected flag is never set for 3-tier experiments.");
    }

    // =========================================================================
    // GREEN REGRESSION R4: VQ must not grow unboundedly
    //
    // This preserves the Phase 9 requirement. Without Phase 9, VQ grows as a
    // one-way ramp under PACKS (arrival_rate >> departure_rate at oversubscribed
    // ports). Any fix must keep VQ bounded.
    // =========================================================================

    @Test
    @DisplayName("R4 GREEN: VQ must remain bounded under heavy drop traffic")
    void r4_regression_vqMustStayBounded() {
        long hwm = 50 * PKT;
        long lwm = 2 * PKT;

        // Simulate HEAVY oversubscription: 31 arrivals per 1 departure
        // With Phase 9: each drop calls onDeparture → VQ stays bounded
        // Without Phase 9: only real departures decrement → VQ ramps unboundedly

        VirtualQueue vqWithPhase9 = new VirtualQueue(hwm, lwm, "bounded-test", false);

        int numRounds = 100;
        for (int round = 0; round < numRounds; round++) {
            // 31 arrivals
            for (int i = 0; i < 31; i++) {
                vqWithPhase9.onArrival(PKT);
            }
            // 30 Phase 9 drops
            for (int i = 0; i < 30; i++) {
                vqWithPhase9.onDeparture(PKT); // Phase 9: decrement for drops
            }
            // 1 real departure
            vqWithPhase9.onDeparture(PKT);
        }

        long finalDepth = vqWithPhase9.getDepthPackets();
        System.out.println("[R4] With Phase 9: finalVQ=" + finalDepth + " pkts after " + numRounds + " rounds");

        // With Phase 9: VQ should be bounded (each round: +31 -30 -1 = 0 net)
        assertTrue(finalDepth < 50,
            "VQ with Phase 9 should stay bounded, got " + finalDepth + " packets");

        // Verify: WITHOUT Phase 9, VQ would explode
        VirtualQueue vqNoPhase9 = new VirtualQueue(hwm, lwm, "unbounded-test", false);
        for (int round = 0; round < numRounds; round++) {
            for (int i = 0; i < 31; i++) {
                vqNoPhase9.onArrival(PKT);
            }
            // Only 1 real departure (no Phase 9 drops)
            vqNoPhase9.onDeparture(PKT);
        }

        long unboundedDepth = vqNoPhase9.getDepthPackets();
        System.out.println("[R4] Without Phase 9: finalVQ=" + unboundedDepth + " pkts after " + numRounds + " rounds");

        assertTrue(unboundedDepth > 1000,
            "Without Phase 9, VQ should grow unboundedly, got " + unboundedDepth + " packets");
    }

    // =========================================================================
    // GREEN REGRESSION R5: Non-oversubscribed traffic cycles properly
    //
    // This is the scenario from the existing H2 test: inter-burst has very few
    // arrivals relative to departures, so VQ drains below low watermark naturally.
    // This must keep working with any fix.
    // =========================================================================

    @Test
    @DisplayName("R5 GREEN: Window cycling works under non-oversubscribed traffic")
    void r5_regression_nonOversubscribedTrafficCycles() {
        long hwm = 50 * PKT;
        long lwm = 10 * PKT;
        VirtualQueue vq = new VirtualQueue(hwm, lwm, "non-oversubscribed", false);

        boolean monitoring = false;
        int started = 0, completed = 0;

        for (int cycle = 0; cycle < 10; cycle++) {
            // Burst: 100 arrivals, 80 Phase 9 drops, 10 real departures
            for (int i = 0; i < 100; i++) {
                boolean ch = vq.onArrival(PKT);
                if (ch && !monitoring) { monitoring = true; started++; }
            }
            for (int i = 0; i < 80; i++) { // Phase 9 drops
                boolean cl = vq.onDeparture(PKT);
                if (cl && monitoring) { monitoring = false; completed++; }
            }
            for (int i = 0; i < 10; i++) { // real departures
                boolean cl = vq.onDeparture(PKT);
                if (cl && monitoring) { monitoring = false; completed++; }
            }

            // Inter-burst: LOW arrivals, HIGH departures → queue drains
            for (int i = 0; i < 3; i++) {
                boolean ch = vq.onArrival(PKT);
                if (ch && !monitoring) { monitoring = true; started++; }
            }
            for (int i = 0; i < 30; i++) {
                boolean cl = vq.onDeparture(PKT);
                if (cl && monitoring) { monitoring = false; completed++; }
            }
        }

        System.out.println("[R5] Non-oversubscribed: started=" + started +
                           ", completed=" + completed + ", finalVQ=" + vq.getDepthPackets());

        // This SHOULD pass (matches existing H2 behavior)
        assertTrue(started >= 2, "Multiple windows should start");
        assertTrue(completed >= 2, "Multiple windows should complete");
        assertTrue(Math.abs(started - completed) <= 1,
            "Starts and completions should match (" + started + " vs " + completed + ")");
    }
}
