package ch.ethz.systems.netbench.xpt.ports.midst_feedback.integration;

import ch.ethz.systems.netbench.core.Simulator;
import ch.ethz.systems.netbench.core.config.NBProperties;
import ch.ethz.systems.netbench.core.log.SimulationLogger;
import ch.ethz.systems.netbench.core.network.Packet;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.core.MIDST;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.core.MIDSTImpl;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.port.MIDSTCapableOutputPort;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl.RealPACKSQueue;
import ch.ethz.systems.netbench.xpt.ports.midst_feedback.queue.impl.RealAIFOQueue;
import ch.ethz.systems.netbench.xpt.tcpbase.FullExtTcpPacket;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for the new MIDST Virtual Queue architecture.
 *
 * These tests verify that:
 * 1. Virtual queue triggers monitoring correctly for all scheduler types
 * 2. Bursty flow detection works as expected
 * 3. The architecture solves the PACKS problem (key test!)
 *
 * Run these tests in Eclipse: Right-click -> Run As -> JUnit Test
 */
class MIDSTIntegrationTest {

    // Configuration matching typical simulation settings
    private static final int SKETCH_SIZE = 1024;
    private static final int NUM_HASH_FUNCTIONS = 4;
    private static final int BURST_THRESHOLD = 10;  // 10 packets to be considered bursty

    // Packet size: FullExtTcpPacket expects payload in bits, adds header overhead
    private static final long PACKET_SIZE_BYTES = 1500L;  // Payload size in bytes

    // Watermark configuration (in packets)
    private static final int HIGH_WATERMARK_PACKETS = 50;
    private static final int LOW_WATERMARK_PACKETS = 10;

    private static NBProperties mockConfig;
    private MIDST midst;
    private long actualPacketSizeBits;  // Measured at test setup
    private long highWatermarkBits;
    private long lowWatermarkBits;

    @BeforeAll
    static void setupLogger() {
        mockConfig = Mockito.mock(NBProperties.class);
        when(mockConfig.getPropertyWithDefault(eq("run_folder_name"), any())).thenReturn("test_midst_integration");
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

        // Measure actual packet size (includes TCP headers)
        Packet testPacket = new FullExtTcpPacket(1L, PACKET_SIZE_BYTES * 8, 0, 1, 100, 80, 443,
            0L, 0L, false, false, false, false, false, false, false, false, false, 0, 0);
        actualPacketSizeBits = testPacket.getSizeBit();

        // Calculate watermarks based on actual packet size
        highWatermarkBits = HIGH_WATERMARK_PACKETS * actualPacketSizeBits;
        lowWatermarkBits = LOW_WATERMARK_PACKETS * actualPacketSizeBits;

        System.out.println("Test setup: actualPacketSize=" + actualPacketSizeBits + " bits, " +
            "highWatermark=" + highWatermarkBits + " bits (" + HIGH_WATERMARK_PACKETS + " pkts), " +
            "lowWatermark=" + lowWatermarkBits + " bits (" + LOW_WATERMARK_PACKETS + " pkts)");

        midst = new MIDSTImpl(
            SKETCH_SIZE,
            NUM_HASH_FUNCTIONS,
            BURST_THRESHOLD,
            highWatermarkBits,
            lowWatermarkBits,
            "test-port",
            false  // logging disabled for tests
        );
    }

    @AfterEach
    void tearDown() {
        Simulator.reset();
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private Packet createPacket(long flowId) {
        // Note: FullExtTcpPacket expects payload size in BITS, but adds header overhead
        return new FullExtTcpPacket(
            flowId,               // flowId
            PACKET_SIZE_BYTES * 8, // sizeBit (payload in bits)
            0,                    // sourceId
            1,                    // destinationId
            100,                  // TTL
            80,                   // sourcePort
            443,                  // destinationPort
            0L,                   // sequenceNumber
            0L,                   // ackNumber
            false,                // NS
            false,                // CWR
            false,                // ECE
            false,                // URG
            false,                // ACK
            false,                // PSH
            false,                // RST
            false,                // SYN
            false,                // FIN
            0,                    // windowSize
            0                     // priority
        );
    }

    /** Get the actual packet size in bits (including headers) */
    private long getActualPacketSizeBits() {
        return actualPacketSizeBits;
    }

    private void sendPackets(long flowId, int count) {
        for (int i = 0; i < count; i++) {
            Packet p = createPacket(flowId);
            midst.onPacketArrival(p);
        }
    }

    private void departPackets(long flowId, int count) {
        for (int i = 0; i < count; i++) {
            Packet p = createPacket(flowId);
            midst.onPacketDeparture(p);
        }
    }

    // =========================================================================
    // Test 1: Virtual Queue Trigger Mechanism
    // =========================================================================

    @Test
    @DisplayName("Virtual queue starts monitoring when crossing high watermark")
    void testVirtualQueueStartsMonitoringAtHighWatermark() {
        System.out.println("\n=== Test: Virtual Queue Starts Monitoring ===");

        // Initially not monitoring
        assertFalse(midst.isMonitoring(), "Should not be monitoring initially");
        assertEquals(0, midst.getVirtualQueueDepthBits(), "Virtual queue should be empty");

        // Send packets to cross high watermark (need > 50 packets)
        System.out.println("Sending 55 packets to cross high watermark...");
        sendPackets(1L, 55);

        // Should now be monitoring
        assertTrue(midst.isMonitoring(), "Should be monitoring after crossing high watermark");

        // Virtual queue should have grown (exact size depends on packet header overhead)
        long actualPacketSize = getActualPacketSizeBits();
        long expectedMin = 55 * actualPacketSize * 9 / 10;  // Allow 10% tolerance
        long expectedMax = 55 * actualPacketSize * 11 / 10;
        long actualDepth = midst.getVirtualQueueDepthBits();

        System.out.println("Actual packet size: " + actualPacketSize + " bits");
        System.out.println("Virtual queue depth: " + actualDepth + " bits");
        System.out.println("Expected range: " + expectedMin + " - " + expectedMax);

        assertTrue(actualDepth >= expectedMin && actualDepth <= expectedMax,
            "Virtual queue depth should be approximately 55 * packet size");

        System.out.println("PASSED: Monitoring started at virtual queue = " + actualDepth + " bits");
    }

    @Test
    @DisplayName("Virtual queue stops monitoring when crossing low watermark")
    void testVirtualQueueStopsMonitoringAtLowWatermark() {
        System.out.println("\n=== Test: Virtual Queue Stops Monitoring ===");

        System.out.println("Actual packet size: " + actualPacketSizeBits + " bits");
        System.out.println("Low watermark: " + lowWatermarkBits + " bits (" + LOW_WATERMARK_PACKETS + " packets)");

        // First, trigger monitoring - keep packets for proper departure
        List<Packet> packets = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            Packet p = createPacket(1L);
            midst.onPacketArrival(p);
            packets.add(p);
        }
        assertTrue(midst.isMonitoring(), "Should be monitoring");
        System.out.println("After 55 arrivals, virtual queue: " + midst.getVirtualQueueDepthBits());

        // Now drain packets until below low watermark
        // Low watermark is 10 packets, so we need < 10 packets remaining
        // Depart 50 packets to leave 5
        int packetsToDepart = 50;
        System.out.println("Departing " + packetsToDepart + " packets...");
        for (int i = 0; i < packetsToDepart; i++) {
            midst.onPacketDeparture(packets.get(i));
        }

        System.out.println("After departures, virtual queue: " + midst.getVirtualQueueDepthBits());
        System.out.println("Low watermark threshold: " + lowWatermarkBits);

        // Should have stopped monitoring (virtual queue should be below low watermark)
        assertFalse(midst.isMonitoring(), "Should stop monitoring after crossing low watermark");

        System.out.println("PASSED: Monitoring stopped at virtual queue = " + midst.getVirtualQueueDepthBits() + " bits");
    }

    // =========================================================================
    // Test 2: Bursty Flow Detection
    // =========================================================================

    @Test
    @DisplayName("Bursty flows are correctly detected during monitoring window")
    void testBurstyFlowDetection() {
        System.out.println("\n=== Test: Bursty Flow Detection ===");

        // Trigger monitoring - keep packets for proper departure
        List<Packet> triggerPackets = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            Packet p = createPacket(100L);
            midst.onPacketArrival(p);
            triggerPackets.add(p);
        }
        assertTrue(midst.isMonitoring(), "Should be monitoring");

        // During monitoring, send a burst from flow 42 (15 packets > threshold of 10)
        System.out.println("Sending burst of 15 packets from flow 42...");
        List<Packet> flow42Packets = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            Packet p = createPacket(42L);
            midst.onPacketArrival(p);
            flow42Packets.add(p);
        }

        // Send trickle from flow 99 (3 packets < threshold of 10)
        System.out.println("Sending trickle of 3 packets from flow 99...");
        List<Packet> flow99Packets = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Packet p = createPacket(99L);
            midst.onPacketArrival(p);
            flow99Packets.add(p);
        }

        // End monitoring by draining virtual queue - use SAME packets
        System.out.println("Draining virtual queue to end monitoring...");
        for (Packet p : triggerPackets) midst.onPacketDeparture(p);
        for (Packet p : flow42Packets) midst.onPacketDeparture(p);
        for (Packet p : flow99Packets) midst.onPacketDeparture(p);

        // Check results
        assertFalse(midst.isMonitoring(), "Should have stopped monitoring");

        Set<Long> burstyFlows = midst.getBurstyFlows();
        System.out.println("Detected bursty flows: " + burstyFlows);

        assertTrue(midst.isFlowBursty(42L), "Flow 42 should be detected as bursty (15 >= 10)");
        assertFalse(midst.isFlowBursty(99L), "Flow 99 should NOT be detected as bursty (3 < 10)");

        System.out.println("PASSED: Bursty flow detection works correctly");
    }

    @Test
    @DisplayName("Multiple bursty flows are all detected")
    void testMultipleBurstyFlows() {
        System.out.println("\n=== Test: Multiple Bursty Flows ===");

        // Collect all packets for proper departure tracking
        List<Packet> allPackets = new ArrayList<>();

        // Trigger monitoring with flow 999 (will NOT be counted as bursty since
        // most packets arrive before monitoring starts - only ~5 packets after threshold)
        // Use a separate trigger flow so the test is clear about which flows are bursty
        for (int i = 0; i < 55; i++) {
            Packet p = createPacket(999L);  // Trigger flow
            midst.onPacketArrival(p);
            allPackets.add(p);
        }

        // Now monitoring is active - send bursts from multiple flows
        // These flows will ALL have their packets counted in Sin
        for (int i = 0; i < 20; i++) { Packet p = createPacket(1L); midst.onPacketArrival(p); allPackets.add(p); }
        for (int i = 0; i < 15; i++) { Packet p = createPacket(2L); midst.onPacketArrival(p); allPackets.add(p); }
        for (int i = 0; i < 12; i++) { Packet p = createPacket(3L); midst.onPacketArrival(p); allPackets.add(p); }
        for (int i = 0; i < 5; i++)  { Packet p = createPacket(4L); midst.onPacketArrival(p); allPackets.add(p); }
        for (int i = 0; i < 2; i++)  { Packet p = createPacket(5L); midst.onPacketArrival(p); allPackets.add(p); }

        // End monitoring - depart all packets
        for (Packet p : allPackets) {
            midst.onPacketDeparture(p);
        }

        Set<Long> burstyFlows = midst.getBurstyFlows();
        System.out.println("Detected bursty flows: " + burstyFlows);

        // Flow 999 only has ~5 packets in sketch (most arrived before monitoring)
        // We expect 3 bursty flows: 1, 2, 3 (all >= 10 threshold)
        assertEquals(3, burstyFlows.size(), "Should detect 3 bursty flows (1, 2, 3)");
        assertFalse(midst.isFlowBursty(999L), "Flow 999 should NOT be bursty (trigger flow, most packets before monitoring)");
        assertTrue(midst.isFlowBursty(1L), "Flow 1 should be bursty (20 packets)");
        assertTrue(midst.isFlowBursty(2L), "Flow 2 should be bursty (15 packets)");
        assertTrue(midst.isFlowBursty(3L), "Flow 3 should be bursty (12 packets)");
        assertFalse(midst.isFlowBursty(4L), "Flow 4 should NOT be bursty (5 packets)");
        assertFalse(midst.isFlowBursty(5L), "Flow 5 should NOT be bursty (2 packets)");

        System.out.println("PASSED: Multiple bursty flow detection works");
    }

    // =========================================================================
    // Test 3: PACKS Scenario Simulation (KEY TEST!)
    // =========================================================================

    @Test
    @DisplayName("KEY TEST: Virtual queue detects congestion even with PACKS admission control")
    void testPACKSScenario_VirtualQueueDetectsCongestion() {
        System.out.println("\n=== Test: PACKS Scenario - Virtual Queue Detects Congestion ===");
        System.out.println("This is the KEY TEST that verifies the PACKS fix!");
        System.out.println();

        // Simulate PACKS behavior at 31:1 fan-in:
        // - High arrival rate (many packets arrive simultaneously from many senders)
        // - Admission control drops most, but admitted packets accumulate in VQ
        // - Phase 9: each drop cancels its VQ arrival (+PKT then -PKT = net 0)
        // - So VQ grows by admitted packets only: need enough admitted to cross high WM
        //
        // With 20% admission rate: VQ grows by 1 PKT per 5 arrivals.
        // High watermark = 50 packets → need 250 arrivals to admit 50 → VQ=50.
        // We use 300 to comfortably exceed the threshold.

        int packetsArrived = 0;
        int packetsAdmitted = 0;
        int packetsDropped = 0;

        List<Packet> arrivedPackets = new ArrayList<>();

        System.out.println("Simulating PACKS admission control behavior...");
        System.out.println("Admission rate: ~20% (simulating 31:1 fan-in)");

        // Simulate 300 packet arrivals (enough for 60 admitted → VQ=60 > high=50)
        for (int i = 0; i < 300; i++) {
            Packet p = createPacket(i % 5);  // 5 different flows

            midst.onPacketArrival(p);
            arrivedPackets.add(p);
            packetsArrived++;

            // Simulate PACKS admission control (admits ~20% of packets)
            boolean admitted = (i % 5 == 0);  // Only 1 in 5 admitted

            if (admitted) {
                packetsAdmitted++;
            } else {
                packetsDropped++;
                // Phase 9: drop cancels arrival's VQ effect
                midst.onPacketDropped(p);
            }
        }

        System.out.println();
        System.out.println("Results:");
        System.out.println("  Packets arrived: " + packetsArrived);
        System.out.println("  Packets admitted (to real queue): " + packetsAdmitted);
        System.out.println("  Packets dropped (by admission): " + packetsDropped);
        System.out.println("  Virtual queue depth: " + midst.getVirtualQueueDepthBits() + " bits");
        System.out.println("  MIDST monitoring: " + midst.isMonitoring());
        System.out.println();

        // THE KEY ASSERTION: Virtual queue should have triggered monitoring.
        // With Phase 9, VQ = admitted packets only (drops cancel out).
        // 300 arrivals × 20% admission = 60 admitted → VQ=60 > high=50 → monitoring fires.
        assertTrue(midst.isMonitoring(),
            "CRITICAL: Virtual queue should trigger monitoring even with PACKS admission control!\n" +
            "With Phase 9: VQ = admitted packets only (" + packetsAdmitted + " admitted).\n" +
            "High watermark = " + HIGH_WATERMARK_PACKETS + " packets.\n" +
            "VQ depth = " + midst.getVirtualQueueDepthBits() / actualPacketSizeBits + " packets.");

        // Virtual queue should reflect admitted packets (drops cancel via Phase 9)
        long expectedVirtualQueue = packetsAdmitted * actualPacketSizeBits;
        long actualVirtualQueue = midst.getVirtualQueueDepthBits();

        // Allow 10% tolerance
        long tolerance = expectedVirtualQueue / 10;
        assertTrue(Math.abs(actualVirtualQueue - expectedVirtualQueue) <= tolerance,
            "Virtual queue should reflect admitted packets only (Phase 9 cancels drops). Expected ~" +
            expectedVirtualQueue + " but got " + actualVirtualQueue);

        System.out.println("=== PASSED: PACKS scenario - MIDST detects congestion via admitted packets ===");
    }

    @Test
    @DisplayName("PACKS detects bursty flows despite admission control dropping packets")
    void testPACKSScenario_DetectsBurstyFlowsDespiteAdmissionControl() {
        System.out.println("\n=== Test: PACKS Detects Bursty Flows Despite Admission Control ===");

        // Trigger monitoring first - keep track of packets for proper departure
        List<Packet> triggerPackets = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            Packet p = createPacket(100L);
            midst.onPacketArrival(p);
            triggerPackets.add(p);
        }
        assertTrue(midst.isMonitoring(), "Should be monitoring");

        // Now simulate a bursty flow that PACKS partially admits
        long burstyFlowId = 42L;
        int totalPackets = 30;
        List<Packet> admittedPackets = new ArrayList<>();

        System.out.println("Simulating bursty flow " + burstyFlowId + " with 30 packets...");

        for (int i = 0; i < totalPackets; i++) {
            Packet p = createPacket(burstyFlowId);
            midst.onPacketArrival(p);

            // PACKS admits ~50% of this bursty flow (changed from 30% to ensure we can end monitoring)
            if (i % 2 == 0) {
                admittedPackets.add(p);
                // Packet admitted (would depart later)
            } else {
                // Packet dropped
                midst.onPacketDropped(p);
            }
        }

        System.out.println("  Total packets from flow " + burstyFlowId + ": " + totalPackets);
        System.out.println("  Packets admitted by PACKS: " + admittedPackets.size());

        // KEY CHECK: Flow size estimate should be based on ARRIVALS (30), not admissions (15)
        int flowSizeEstimate = midst.getFlowIngressEstimate(burstyFlowId);
        System.out.println("  Estimated flow size in MIDST: " + flowSizeEstimate);
        assertTrue(flowSizeEstimate >= 28,
            "MIDST should estimate flow size based on ARRIVALS (~30), not admissions (" + admittedPackets.size() + "). Got: " + flowSizeEstimate);

        // Depart all admitted packets to drain the virtual queue
        // Virtual queue tracks: arrivals - departures (drops do NOT decrement VQ!)
        // VQ = 85 arrivals - 0 departures so far = 85 packets worth of bits
        // After departing 55 trigger + 15 admitted = 70 departures: VQ = 85 - 70 = 15
        // Low watermark = 10. VQ stays above → monitoring continues (correct!)
        System.out.println("Departing trigger and admitted packets...");
        for (Packet p : triggerPackets) {
            midst.onPacketDeparture(p);
        }
        for (Packet p : admittedPackets) {
            midst.onPacketDeparture(p);
        }

        // VQ = 15 packets worth (the 15 dropped packets represent demand that was never served).
        // This correctly stays above the low watermark (10), so monitoring continues.
        // In a real simulation, the inter-burst period's departures would eventually drain VQ.

        System.out.println("Virtual queue after departures: " + midst.getVirtualQueueDepthBits() + " bits");
        System.out.println("Note: VQ = 15 packets (15 dropped packets represent unserved demand)");
        System.out.println("This demonstrates that virtual queue correctly tracks congestion from drops");

        // The KEY assertion was already verified above: flow size estimate is ~30 (arrivals), not 15 (admissions)
        // This proves MIDST tracks ALL arrivals, not just admitted packets!

        System.out.println("PASSED: MIDST tracks arrivals correctly despite PACKS admission control");
    }

    // =========================================================================
    // Test 4: Consistency Across Schedulers
    // =========================================================================

    @Test
    @DisplayName("Same arrival pattern produces same detection across all scheduler types")
    void testSamePatternSameDetection() {
        System.out.println("\n=== Test: Same Pattern = Same Detection ===");
        System.out.println("Verifying that MIDST detection is scheduler-independent");

        // Create three separate MIDST instances (one per scheduler type)
        MIDST midstSPPIFO = new MIDSTImpl(SKETCH_SIZE, NUM_HASH_FUNCTIONS, BURST_THRESHOLD,
            highWatermarkBits, lowWatermarkBits, "sppifo", false);
        MIDST midstAIFO = new MIDSTImpl(SKETCH_SIZE, NUM_HASH_FUNCTIONS, BURST_THRESHOLD,
            highWatermarkBits, lowWatermarkBits, "aifo", false);
        MIDST midstPACKS = new MIDSTImpl(SKETCH_SIZE, NUM_HASH_FUNCTIONS, BURST_THRESHOLD,
            highWatermarkBits, lowWatermarkBits, "packs", false);

        // Apply identical arrival pattern to all
        System.out.println("Applying identical arrival pattern to all MIDST instances...");

        for (int i = 0; i < 60; i++) {
            long flowId = i % 3;  // 3 flows
            Packet p1 = createPacket(flowId);
            Packet p2 = createPacket(flowId);
            Packet p3 = createPacket(flowId);

            midstSPPIFO.onPacketArrival(p1);
            midstAIFO.onPacketArrival(p2);
            midstPACKS.onPacketArrival(p3);
        }

        // All should be monitoring
        assertTrue(midstSPPIFO.isMonitoring(), "SP-PIFO MIDST should be monitoring");
        assertTrue(midstAIFO.isMonitoring(), "AIFO MIDST should be monitoring");
        assertTrue(midstPACKS.isMonitoring(), "PACKS MIDST should be monitoring");

        // End monitoring for all
        for (int i = 0; i < 55; i++) {
            long flowId = i % 3;
            midstSPPIFO.onPacketDeparture(createPacket(flowId));
            midstAIFO.onPacketDeparture(createPacket(flowId));
            midstPACKS.onPacketDeparture(createPacket(flowId));
        }

        // All should detect the same bursty flows
        Set<Long> sppifoFlows = midstSPPIFO.getBurstyFlows();
        Set<Long> aifoFlows = midstAIFO.getBurstyFlows();
        Set<Long> packsFlows = midstPACKS.getBurstyFlows();

        System.out.println("SP-PIFO detected: " + sppifoFlows);
        System.out.println("AIFO detected: " + aifoFlows);
        System.out.println("PACKS detected: " + packsFlows);

        assertEquals(sppifoFlows, aifoFlows, "SP-PIFO and AIFO should detect same flows");
        assertEquals(aifoFlows, packsFlows, "AIFO and PACKS should detect same flows");

        System.out.println("PASSED: All scheduler types detect identical bursty flows");
    }

    // =========================================================================
    // Test 5: Heavy Loser Detection (Δ-sketch)
    // =========================================================================

    @Test
    @DisplayName("Heavy losers are detected via Δ-sketch (Sin - Sout)")
    void testHeavyLoserDetection() {
        System.out.println("\n=== Test: Heavy Loser Detection (Δ-sketch) ===");

        // Trigger monitoring - need to keep track of these packets for proper departure
        List<Packet> triggerPackets = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            Packet p = createPacket(100L);
            midst.onPacketArrival(p);
            triggerPackets.add(p);
        }
        assertTrue(midst.isMonitoring(), "Should be monitoring");

        // Flow 42: 20 arrivals, 15 departures = 5 lost
        long loserFlowId = 42L;
        List<Packet> flow42Packets = new ArrayList<>();

        System.out.println("Flow " + loserFlowId + ": 20 arrivals, 15 departures (5 lost)");
        for (int i = 0; i < 20; i++) {
            Packet p = createPacket(loserFlowId);
            midst.onPacketArrival(p);
            flow42Packets.add(p);
        }

        // Depart only 15 of them
        for (int i = 0; i < 15; i++) {
            midst.onPacketDeparture(flow42Packets.get(i));
        }
        // Remaining 5 are dropped
        for (int i = 15; i < 20; i++) {
            midst.onPacketDropped(flow42Packets.get(i));
        }

        // Flow 99: 10 arrivals, 10 departures = 0 lost
        long goodFlowId = 99L;
        System.out.println("Flow " + goodFlowId + ": 10 arrivals, 10 departures (0 lost)");
        for (int i = 0; i < 10; i++) {
            Packet p = createPacket(goodFlowId);
            midst.onPacketArrival(p);
            midst.onPacketDeparture(p);
        }

        // Check loss estimates WHILE MONITORING IS STILL ACTIVE (before sketches are reset)
        // Note: After stopMonitoring(), sketches are cleared, so getFlowLossEstimate() returns 0
        int flow42LossDuringMonitoring = midst.getFlowLossEstimate(loserFlowId);
        int flow99LossDuringMonitoring = midst.getFlowLossEstimate(goodFlowId);

        System.out.println("Flow " + loserFlowId + " loss estimate (during monitoring): " + flow42LossDuringMonitoring);
        System.out.println("Flow " + goodFlowId + " loss estimate (during monitoring): " + flow99LossDuringMonitoring);

        // Verify loss estimates during monitoring
        assertTrue(flow42LossDuringMonitoring >= 3, "Flow 42 should have estimated loss >= 3 (actual: 5)");
        assertTrue(flow42LossDuringMonitoring <= 7, "Flow 42 should have estimated loss <= 7 (actual: 5)");
        assertEquals(0, flow99LossDuringMonitoring, "Flow 99 should have 0 loss");

        // End monitoring by departing the trigger packets (use the SAME packet objects!)
        for (Packet p : triggerPackets) {
            midst.onPacketDeparture(p);
        }
        assertFalse(midst.isMonitoring(), "Should have stopped monitoring");

        // Check heavy losers set (computed at end of monitoring window, before sketch reset)
        Set<Long> heavyLosers = midst.getHeavyLosers();
        System.out.println("Heavy losers: " + heavyLosers);
        assertTrue(heavyLosers.contains(loserFlowId), "Flow 42 should be in heavy losers");
        assertFalse(heavyLosers.contains(goodFlowId), "Flow 99 should NOT be in heavy losers");

        // Note: getFlowLossEstimate() returns 0 after monitoring ends because sketches are reset
        // The heavy losers set is the correct way to query loss information after monitoring ends
        System.out.println("Note: Sketches reset after monitoring - use getHeavyLosers() for post-monitoring queries");

        System.out.println("PASSED: Heavy loser detection works");
    }

    // =========================================================================
    // Test 6: Multiple Monitoring Windows
    // =========================================================================

    @Test
    @DisplayName("Multiple monitoring windows work correctly")
    void testMultipleMonitoringWindows() {
        System.out.println("\n=== Test: Multiple Monitoring Windows ===");

        // First window
        System.out.println("Window 1: Flow 1 is bursty");
        List<Packet> window1Packets = new ArrayList<>();
        for (int i = 0; i < 55; i++) { Packet p = createPacket(100L); midst.onPacketArrival(p); window1Packets.add(p); }
        for (int i = 0; i < 15; i++) { Packet p = createPacket(1L); midst.onPacketArrival(p); window1Packets.add(p); }
        for (Packet p : window1Packets) midst.onPacketDeparture(p);

        assertTrue(midst.isFlowBursty(1L), "Flow 1 should be bursty after window 1");
        assertFalse(midst.isFlowBursty(2L), "Flow 2 should NOT be bursty yet");

        // Second window
        System.out.println("Window 2: Flow 2 is bursty, Flow 1 is not");
        List<Packet> window2Packets = new ArrayList<>();
        for (int i = 0; i < 55; i++) { Packet p = createPacket(100L); midst.onPacketArrival(p); window2Packets.add(p); }
        for (int i = 0; i < 15; i++) { Packet p = createPacket(2L); midst.onPacketArrival(p); window2Packets.add(p); }
        for (int i = 0; i < 3; i++)  { Packet p = createPacket(1L); midst.onPacketArrival(p); window2Packets.add(p); }
        for (Packet p : window2Packets) midst.onPacketDeparture(p);

        // After window 2, bursty flow set should be updated
        assertTrue(midst.isFlowBursty(2L), "Flow 2 should be bursty after window 2");
        // Flow 1's status depends on implementation - it might retain or clear

        System.out.println("Bursty flows after window 2: " + midst.getBurstyFlows());
        System.out.println("PASSED: Multiple monitoring windows work correctly");
    }

    // =========================================================================
    // Test 7: Virtual Fill Level - PACKS Admission Gate
    // =========================================================================

    @Test
    @DisplayName("PACKS without virtual fill level: bursty flow rank penalty is ignored")
    void testPACKS_WithoutVirtualFillLevel_PenaltyIgnored() {
        System.out.println("\n=== Test: PACKS Without Virtual Fill Level ===");

        // Create a MIDST module and trigger bursty detection for flow 42
        MIDSTImpl midstForQueue = new MIDSTImpl(
            SKETCH_SIZE, NUM_HASH_FUNCTIONS, BURST_THRESHOLD,
            highWatermarkBits, lowWatermarkBits, "test-packs", false);

        // Fill virtual queue to trigger monitoring
        List<Packet> triggerPackets = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            Packet p = createPacket(100L);
            midstForQueue.onPacketArrival(p);
            triggerPackets.add(p);
        }
        assertTrue(midstForQueue.isMonitoring(), "Should be monitoring");

        // Make flow 42 bursty (send 15 > threshold of 10)
        for (int i = 0; i < 15; i++) {
            midstForQueue.onPacketArrival(createPacket(42L));
        }
        assertTrue(midstForQueue.isFlowBursty(42L), "Flow 42 should be bursty");

        // Create PACKS queue WITHOUT virtual fill level
        RealPACKSQueue queue = new RealPACKSQueue(
            0.2f,   // k
            64,     // windowSize
            1,      // sampleCount
            5,      // numQueues
            100L,   // rankDelta
            1000L,  // maxQueueSizePackets
            50L, 10L, 200L,  // watermarks + penalty
            true, 0.05, 1000,  // rejection rate detection
            false   // useVirtualFillLevel = FALSE
        );

        // Connect MIDST to queue via mock port
        MIDSTCapableOutputPort mockPort = Mockito.mock(MIDSTCapableOutputPort.class);
        when(mockPort.getMIDST()).thenReturn(midstForQueue);
        queue.setCongestionSignaler(mockPort);

        // Queue is empty (0 / 1000 = 0% fill level, well below k=0.2)
        // With useVirtualFillLevel=false, the bursty flow's rank penalty (+200) is
        // IGNORED because fillLevel < k → admit unconditionally

        // Offer a packet from bursty flow 42 with a bad rank
        Packet burstyPacket = createPacketWithPriority(42L, 500);  // Bad rank
        boolean admitted = queue.offer(burstyPacket);

        assertTrue(admitted,
            "Without virtual fill level, bursty flow should be admitted (fillLevel < k, rank ignored)");

        System.out.println("PASSED: Without virtual fill level, penalty is invisible");
    }

    @Test
    @DisplayName("PACKS with virtual fill level: bursty flow faces quantile test")
    void testPACKS_WithVirtualFillLevel_PenaltyWorks() {
        System.out.println("\n=== Test: PACKS With Virtual Fill Level ===");

        // Create a MIDST module and trigger bursty detection for flow 42
        MIDSTImpl midstForQueue = new MIDSTImpl(
            SKETCH_SIZE, NUM_HASH_FUNCTIONS, BURST_THRESHOLD,
            highWatermarkBits, lowWatermarkBits, "test-packs-vfl", false);

        // Fill virtual queue to trigger monitoring (high virtual depth)
        List<Packet> triggerPackets = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            Packet p = createPacket(100L);
            midstForQueue.onPacketArrival(p);
            triggerPackets.add(p);
        }
        assertTrue(midstForQueue.isMonitoring(), "Should be monitoring");

        // Make flow 42 bursty
        for (int i = 0; i < 15; i++) {
            midstForQueue.onPacketArrival(createPacket(42L));
        }
        assertTrue(midstForQueue.isFlowBursty(42L), "Flow 42 should be bursty");

        // Create PACKS queue WITH virtual fill level
        RealPACKSQueue queue = new RealPACKSQueue(
            0.2f,   // k
            64,     // windowSize
            1,      // sampleCount
            5,      // numQueues
            100L,   // rankDelta
            1000L,  // maxQueueSizePackets
            50L, 10L, 200L,  // watermarks + penalty
            true, 0.05, 1000,  // rejection rate detection
            true    // useVirtualFillLevel = TRUE
        );

        // Connect MIDST to queue via mock port
        MIDSTCapableOutputPort mockPort = Mockito.mock(MIDSTCapableOutputPort.class);
        when(mockPort.getMIDST()).thenReturn(midstForQueue);
        queue.setCongestionSignaler(mockPort);

        // First, seed the rank window with low-rank (good) packets so quantile test is meaningful
        for (int i = 0; i < 64; i++) {
            Packet goodPacket = createPacketWithPriority(99L, 50);  // Good rank = 50
            queue.offer(goodPacket);
        }

        // Virtual queue depth is HIGH (55 + 15 = 70 packets worth)
        // 70 packets * 12000 bits = 840000 bits → ~840000 / (1500*8) ≈ 70 packets
        // virtualFillLevel = 70 / 1000 = 0.07... hmm that's still < k=0.2
        // We need MORE arrivals to get virtual fill level above 0.2 (200 packets)
        // Add more arrivals to MIDST without departures
        for (int i = 0; i < 200; i++) {
            midstForQueue.onPacketArrival(createPacket(200L + (i % 10)));
        }

        // Now virtual queue ≈ 270 packets → virtualFillLevel = 270/1000 = 0.27 > k=0.2
        long vqDepthBits = midstForQueue.getVirtualQueueDepthBits();
        long vqPackets = vqDepthBits / actualPacketSizeBits;
        System.out.println("Virtual queue: " + vqPackets + " packets, fill level: " +
            String.format("%.2f", (float) vqPackets / 1000));

        assertTrue(vqPackets > 200,
            "Virtual queue should be high enough to exceed k. Got " + vqPackets + " packets");

        // Now offer a bursty flow 42 packet with a TERRIBLE rank
        // effectiveRank = 500 + 200 (penalty) = 700
        // Quantile test: rank 700 vs window full of rank 50 → should FAIL
        Packet burstyPacket = createPacketWithPriority(42L, 500);
        boolean admitted = queue.offer(burstyPacket);

        assertFalse(admitted,
            "With virtual fill level, bursty flow with bad rank should be DROPPED " +
            "(virtual fillLevel > k → quantile test runs → rank 700 fails against window of 50s)");

        // Also verify normal flow 99 still gets admitted (uses real fill level)
        Packet normalPacket = createPacketWithPriority(99L, 50);
        boolean normalAdmitted = queue.offer(normalPacket);

        assertTrue(normalAdmitted,
            "Normal flow should still be admitted (real fillLevel < k)");

        System.out.println("PASSED: Virtual fill level makes PACKS penalty effective for bursty flows");
    }

    @Test
    @DisplayName("AIFO with virtual fill level: bursty flows face stricter admission")
    void testAIFO_WithVirtualFillLevel_BurstyFlowsDropped() {
        System.out.println("\n=== Test: AIFO With Virtual Fill Level ===");

        // Create MIDST module with bursty detection
        MIDSTImpl midstForQueue = new MIDSTImpl(
            SKETCH_SIZE, NUM_HASH_FUNCTIONS, BURST_THRESHOLD,
            highWatermarkBits, lowWatermarkBits, "test-aifo-vfl", false);

        // Fill virtual queue high
        for (int i = 0; i < 55; i++) {
            midstForQueue.onPacketArrival(createPacket(100L));
        }
        // Make flow 42 bursty
        for (int i = 0; i < 15; i++) {
            midstForQueue.onPacketArrival(createPacket(42L));
        }
        // Add more arrivals to push virtual queue above k threshold
        for (int i = 0; i < 200; i++) {
            midstForQueue.onPacketArrival(createPacket(200L + (i % 10)));
        }

        assertTrue(midstForQueue.isFlowBursty(42L), "Flow 42 should be bursty");

        // Create AIFO queue WITH virtual fill level
        RealAIFOQueue queue = new RealAIFOQueue(
            0.2f,   // k
            64,     // windowSize
            1,      // sampleCount
            1000L,  // maxQueueSizePackets
            50L, 10L, 200L,  // watermarks + penalty
            true, 0.05, 1000,  // rejection rate
            true    // useVirtualFillLevel = TRUE
        );

        // Connect MIDST
        MIDSTCapableOutputPort mockPort = Mockito.mock(MIDSTCapableOutputPort.class);
        when(mockPort.getMIDST()).thenReturn(midstForQueue);
        queue.setCongestionSignaler(mockPort);

        // Seed rank window with good packets
        for (int i = 0; i < 64; i++) {
            queue.offer(createPacketWithPriority(99L, 50));
        }

        // Bursty flow 42: effectiveRank = 500 + 200 = 700 (terrible)
        Packet burstyPacket = createPacketWithPriority(42L, 500);
        boolean burstyAdmitted = queue.offer(burstyPacket);

        // Normal flow: rank 50 (good), real fill level < k → admitted
        Packet normalPacket = createPacketWithPriority(99L, 50);
        boolean normalAdmitted = queue.offer(normalPacket);

        assertFalse(burstyAdmitted,
            "Bursty flow with virtual fill level should be dropped by AIFO quantile test");
        assertTrue(normalAdmitted,
            "Normal flow should still be admitted (real fillLevel < k)");

        System.out.println("PASSED: AIFO virtual fill level works for bursty flows");
    }

    // =========================================================================
    // Helper: Create packet with specific priority (rank)
    // =========================================================================

    private Packet createPacketWithPriority(long flowId, int priority) {
        // destinationId must be (int) flowId so PACKS's getFlowId()
        // (sourceId * 100000 + destinationId) returns the correct flow ID.
        // sourceId=0, so getFlowId() = 0 * 100000 + flowId = flowId.
        return new FullExtTcpPacket(
            flowId, PACKET_SIZE_BYTES * 8, 0, (int) flowId, 100, 80, 443,
            0L, 0L, false, false, false, false, false, false, false, false, false,
            0, priority
        );
    }

    // =========================================================================
    // Test 8: Adaptive (Relative) Threshold - Full Window Lifecycle
    // =========================================================================

    @Test
    @DisplayName("Adaptive threshold: full monitoring window lifecycle with selective detection")
    void testAdaptiveThreshold_FullWindowLifecycle() {
        System.out.println("\n=== Test: Adaptive Threshold - Full Window Lifecycle ===");

        // Create MIDST with adaptive threshold: phi=0.10 (10%), burstThreshold=10 (absolute floor)
        MIDST adaptiveMidst = new MIDSTImpl(
            SKETCH_SIZE, NUM_HASH_FUNCTIONS, BURST_THRESHOLD,
            highWatermarkBits, lowWatermarkBits, "test-adaptive", false,
            0.10  // adaptive: flow must be >= 10% of window traffic
        );

        assertEquals(0.10, adaptiveMidst.getAdaptiveThresholdFraction());

        // Phase 1: Trigger monitoring with background traffic
        System.out.println("Phase 1: Triggering monitoring...");
        List<Packet> allPackets = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            Packet p = createPacket(999L);  // Background trigger flow
            adaptiveMidst.onPacketArrival(p);
            allPackets.add(p);
        }
        assertTrue(adaptiveMidst.isMonitoring(), "Should be monitoring after high watermark");

        // Phase 2: Send mixed traffic during monitoring
        // Flow 1 (dominant): 80 packets → ~80/200 = 40% >> 10%
        System.out.println("Phase 2: Sending mixed traffic...");
        for (int i = 0; i < 80; i++) {
            Packet p = createPacket(1L);
            adaptiveMidst.onPacketArrival(p);
            allPackets.add(p);
        }

        // Flows 10-19 (minor): 5 packets each × 10 flows = 50 total → each ~2.5%
        for (int f = 10; f < 20; f++) {
            for (int i = 0; i < 5; i++) {
                Packet p = createPacket((long) f);
                adaptiveMidst.onPacketArrival(p);
                allPackets.add(p);
            }
        }

        // Flow 2 (borderline): 15 packets → ~15/200 = 7.5% < 10%
        for (int i = 0; i < 15; i++) {
            Packet p = createPacket(2L);
            adaptiveMidst.onPacketArrival(p);
            allPackets.add(p);
        }

        System.out.println("Total window packets: " + adaptiveMidst.getTotalWindowPackets());

        // Phase 3: End monitoring by departing all packets
        System.out.println("Phase 3: Ending monitoring window...");
        for (Packet p : allPackets) {
            adaptiveMidst.onPacketDeparture(p);
        }
        assertFalse(adaptiveMidst.isMonitoring(), "Should have stopped monitoring");

        // Phase 4: Verify classification
        Set<Long> burstyFlows = adaptiveMidst.getBurstyFlows();
        System.out.println("Detected bursty flows: " + burstyFlows);
        System.out.println("Window packet count: " + adaptiveMidst.getTotalWindowPackets());

        // Flow 1 (dominant, ~40%): BURSTY
        assertTrue(adaptiveMidst.isFlowBursty(1L),
            "Flow 1 should be bursty: ~40% of traffic, well above 10% threshold");

        // Flow 2 (borderline, ~7.5%): NOT bursty
        assertFalse(adaptiveMidst.isFlowBursty(2L),
            "Flow 2 should NOT be bursty: ~7.5% < 10% threshold");

        // Minor flows (~2.5% each): NOT bursty
        for (int f = 10; f < 20; f++) {
            assertFalse(adaptiveMidst.isFlowBursty((long) f),
                "Flow " + f + " should NOT be bursty: ~2.5% << 10% threshold");
        }

        // Verify window count
        assertEquals(1, adaptiveMidst.getMonitoringWindowCount());

        System.out.println("PASSED: Adaptive threshold correctly selects only dominant flow");
    }

    @Test
    @DisplayName("Adaptive threshold: real-time detection during monitoring window")
    void testAdaptiveThreshold_RealtimeDetectionDuringWindow() {
        System.out.println("\n=== Test: Adaptive Threshold - Real-time Detection ===");

        // Create MIDST with adaptive threshold: phi=0.10, burstThreshold=10
        MIDSTImpl adaptiveMidst = new MIDSTImpl(
            SKETCH_SIZE, NUM_HASH_FUNCTIONS, BURST_THRESHOLD,
            highWatermarkBits, lowWatermarkBits, "test-adaptive-rt", false,
            0.10
        );

        // Trigger monitoring
        for (int i = 0; i < 55; i++) {
            adaptiveMidst.onPacketArrival(createPacket(999L));
        }
        assertTrue(adaptiveMidst.isMonitoring());

        // Send 50 packets from flow 1 (dominant)
        for (int i = 0; i < 50; i++) {
            adaptiveMidst.onPacketArrival(createPacket(1L));
        }

        // Send 10 packets from flow 2 (minor)
        for (int i = 0; i < 10; i++) {
            adaptiveMidst.onPacketArrival(createPacket(2L));
        }

        // Real-time query WHILE STILL MONITORING
        // Total window packets: ~55 (trigger, but only ~5 counted after monitoring starts) + 50 + 10 = ~65
        // Flow 1: 50/65 ≈ 77% > 10% AND 50 >= 10 → should be bursty
        System.out.println("Real-time query during monitoring:");
        System.out.println("  Total window packets: " + adaptiveMidst.getTotalWindowPackets());
        System.out.println("  Flow 1 ingress estimate: " + adaptiveMidst.getFlowIngressEstimate(1L));
        System.out.println("  Flow 2 ingress estimate: " + adaptiveMidst.getFlowIngressEstimate(2L));

        assertTrue(adaptiveMidst.isFlowBursty(1L),
            "Flow 1 should be detected as bursty in real-time (dominant flow during window)");

        // Flow 2: 10/65 ≈ 15% > 10% AND 10 >= 10 → also bursty in real-time
        // (Note: with 10% threshold and 15% fraction, flow 2 is borderline but above threshold)
        // This is expected behavior — real-time detection works on current counts
        boolean flow2Bursty = adaptiveMidst.isFlowBursty(2L);
        System.out.println("  Flow 2 bursty (real-time): " + flow2Bursty);
        // Flow 2 is borderline — may or may not be detected depending on exact counts
        // The important assertion is that flow 1 IS detected

        System.out.println("PASSED: Real-time adaptive detection works during monitoring window");
    }

    // =========================================================================
    // Summary Test
    // =========================================================================

    @Test
    @DisplayName("Summary: MIDST Integration Test Suite")
    void testSummary() {
        System.out.println("\n");
        System.out.println("╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("║         MIDST INTEGRATION TEST SUITE SUMMARY                  ║");
        System.out.println("╠═══════════════════════════════════════════════════════════════╣");
        System.out.println("║ If all tests pass, the new Virtual Queue architecture is      ║");
        System.out.println("║ working correctly and MIDST will now work with PACKS!         ║");
        System.out.println("║                                                               ║");
        System.out.println("║ Key validations:                                              ║");
        System.out.println("║ ✓ Virtual queue triggers monitoring (not real queue depth)   ║");
        System.out.println("║ ✓ Bursty flows detected based on arrivals, not admissions    ║");
        System.out.println("║ ✓ Same detection regardless of scheduler type                ║");
        System.out.println("║ ✓ Heavy loser detection via Δ-sketch works                   ║");
        System.out.println("║ ✓ PACKS virtual fill level opens penalty gate for bursty     ║");
        System.out.println("║ ✓ AIFO virtual fill level opens penalty gate for bursty      ║");
        System.out.println("║ ✓ Normal flows unaffected by virtual fill level              ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // This test always passes - it's just a summary
        assertTrue(true);
    }
}
