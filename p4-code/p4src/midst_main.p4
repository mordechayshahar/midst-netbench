/* MIDST - Microburst Identification with Delta-Sketch Technology
 * Top-level P4_16 program for BMv2 v1model
 *
 * Packet lifecycle:
 *   Ingress: parse -> VQ arrival -> monitoring check -> sketch update
 *            -> bursty lookup -> rank inflation -> VFL -> admission -> forward/drop
 *   Egress:  VQ departure -> Sout update -> monitoring stop check -> strip header
 *
 * Drop path: calls vq_on_departure, does NOT update Sout
 */

#include <core.p4>
#include <v1model.p4>

#include "includes/headers.p4"
#include "includes/virtual_queue.p4"
#include "includes/monitoring.p4"
#include "includes/sketches.p4"
#include "includes/rank_inflation.p4"
#ifdef MIDST_ENABLE_AIFO
#include "includes/admission_aifo.p4"
#endif
#ifdef MIDST_ENABLE_PACKS
#include "includes/admission_packs.p4"
#endif
#ifdef MIDST_ENABLE_SPPIFO
#include "includes/admission_sppifo.p4"
#endif
#ifdef MIDST_ENABLE_STATISTICS
#include "includes/statistics.p4"
#endif

// ============================================================
// Ingress Control
// ============================================================

control MidstIngress(
    inout headers_t hdr,
    inout midst_metadata_t meta,
    inout standard_metadata_t standard_metadata
) {
    // L2 forwarding table
    action forward(bit<9> port) {
        standard_metadata.egress_spec = port;
    }

    action broadcast() {
        standard_metadata.mcast_grp = 1;
    }

    action drop() {
        mark_to_drop(standard_metadata);
    }

    table l2_forward {
        key = {
            hdr.ethernet.dst_addr: exact;
        }
        actions = {
            forward;
            broadcast;
            drop;
        }
        size = 256;
        default_action = broadcast();
    }

    // Instantiate sub-controls (MUST be at control top-level, NOT inside apply)
    VirtualQueueArrival()    vq_arrival;
    VirtualQueueDeparture()  vq_departure_drop;  // Phase 9: VQ decrement on drop path
    MonitoringCheck()        monitoring_check;
    SketchUpdateIngress()    sketch_update;
    BurstyLookup()           bursty_lookup;
    RankInflation()          rank_inflation;
#ifdef MIDST_ENABLE_AIFO
    AifoAdmission()          aifo_admission;
#endif
#ifdef MIDST_ENABLE_PACKS
    PacksAdmission()         packs_admission;
#endif
#ifdef MIDST_ENABLE_SPPIFO
    SppifoScheduling()       sppifo_scheduling;
#endif
#ifdef MIDST_ENABLE_STATISTICS
    StatisticsIngress()      stats_ingress;
#endif

    apply {
        if (!hdr.ipv4.isValid()) {
            // Non-IP: just forward
            l2_forward.apply();
            return;
        }

        // Step 1: Compute flow ID from 5-tuple
        if (hdr.tcp.isValid()) {
            hash(meta.flow_id, HashAlgorithm.crc32, 32w0,
                 { hdr.ipv4.src_addr, hdr.ipv4.dst_addr,
                   hdr.ipv4.protocol, hdr.tcp.src_port, hdr.tcp.dst_port },
                 32w0xFFFFFFFF);
        } else if (hdr.udp.isValid()) {
            hash(meta.flow_id, HashAlgorithm.crc32, 32w0,
                 { hdr.ipv4.src_addr, hdr.ipv4.dst_addr,
                   hdr.ipv4.protocol, hdr.udp.src_port, hdr.udp.dst_port },
                 32w0xFFFFFFFF);
        } else {
            hash(meta.flow_id, HashAlgorithm.crc32, 32w0,
                 { hdr.ipv4.src_addr, hdr.ipv4.dst_addr, hdr.ipv4.protocol },
                 32w0xFFFFFFFF);
        }

        // Initialize metadata fields that may not be set by all code paths
        meta.should_drop = 0;
        meta.is_bursty = 0;
        meta.effective_rank = 0;
        meta.effective_queue_size = 0;

        // Compute packet size in bits
        meta.packet_size_bits = ((bit<64>)standard_metadata.packet_length) << 3;

        // Step 2: VQ arrival (increments VQ, detects high watermark crossing)
        vq_arrival.apply(hdr, meta, standard_metadata);

        // Step 3: Monitoring check (start/stop window based on watermark crossings)
        monitoring_check.apply(hdr, meta, standard_metadata);

        // Step 4: Compute sketch hashes and update Sin (only during monitoring)
        sketch_update.apply(hdr, meta, standard_metadata);

        // Step 5: Insert sketch_meta header for egress
        if (meta.monitoring_state == WINDOW_STATE_MONITORING) {
            hdr.sketch_meta.setValid();
            hdr.sketch_meta.flow_id     = meta.flow_id;
            hdr.sketch_meta.hash_idx_0  = meta.hash_idx_0;
            hdr.sketch_meta.hash_idx_1  = meta.hash_idx_1;
            hdr.sketch_meta.hash_idx_2  = meta.hash_idx_2;
            hdr.sketch_meta.hash_idx_3  = meta.hash_idx_3;
            hdr.sketch_meta.sketch_valid = 1;
            hdr.sketch_meta._pad        = 0;
            // Swap ethertype so egress parser can find it
            hdr.ethernet.ethertype      = ETHERTYPE_SKETCH_META;
        }

        // Step 6: Bursty flow lookup
        bursty_lookup.apply(hdr, meta, standard_metadata);

        // Step 7: Rank inflation (add penalty if bursty)
        rank_inflation.apply(hdr, meta, standard_metadata);

        // Step 8: Admission control
#ifdef MIDST_ENABLE_AIFO
        aifo_admission.apply(hdr, meta, standard_metadata);
#endif
#ifdef MIDST_ENABLE_PACKS
        packs_admission.apply(hdr, meta, standard_metadata);
#endif
#ifdef MIDST_ENABLE_SPPIFO
        // SP-PIFO scheduling (queue selection only, no admission gating)
        sppifo_scheduling.apply(hdr, meta, standard_metadata);
#endif

        // Step 9: Forward or drop
        if (meta.should_drop == 1) {
            // CRITICAL: Remove phantom packet from VQ (Phase 9 fix).
            // vq_arrival already incremented VQ; without this, dropped packets
            // inflate VQ permanently (2.2M phantoms for PACKS ~80% drop rate).
            vq_departure_drop.apply(hdr, meta, standard_metadata);
            // Do NOT update Sout sketch — drop = loss for Δ-sketch purposes.
#ifdef MIDST_ENABLE_STATISTICS
            stats_ingress.apply(hdr, meta, standard_metadata);
#endif
            mark_to_drop(standard_metadata);
            return;
        }

        l2_forward.apply();

#ifdef MIDST_ENABLE_STATISTICS
        // Step 10: Update ingress statistics
        stats_ingress.apply(hdr, meta, standard_metadata);
#endif
    }
}

// ============================================================
// Egress Control
// ============================================================

control MidstEgress(
    inout headers_t hdr,
    inout midst_metadata_t meta,
    inout standard_metadata_t standard_metadata
) {
    VirtualQueueDeparture() vq_departure;
    SketchUpdateEgress()    sketch_egress;
    MonitoringStopCheck()   monitoring_stop;
#ifdef MIDST_ENABLE_STATISTICS
    StatisticsEgress()      stats_egress;
#endif

    apply {
        if (!hdr.ipv4.isValid()) {
            return;
        }

        // Recompute packet size (egress metadata may differ)
        meta.packet_size_bits = ((bit<64>)standard_metadata.packet_length) << 3;

        // Step 1: VQ departure (decrement VQ, detect low watermark crossing)
        vq_departure.apply(hdr, meta, standard_metadata);

        // Step 2: Update Sout sketch (only if sketch_meta valid from ingress)
        sketch_egress.apply(hdr, meta, standard_metadata);

        // Step 3: Check if monitoring window should stop
        monitoring_stop.apply(hdr, meta, standard_metadata);

        // Step 4: Strip sketch_meta header before sending on wire
        if (hdr.sketch_meta.isValid()) {
            hdr.ethernet.ethertype = ETHERTYPE_IPV4;
            hdr.sketch_meta.setInvalid();
        }

#ifdef MIDST_ENABLE_STATISTICS
        stats_egress.apply(hdr, meta, standard_metadata);
#endif
    }
}

// ============================================================
// Switch Pipeline
// ============================================================

V1Switch(
    MidstParser(),
    MidstVerifyChecksum(),
    MidstIngress(),
    MidstEgress(),
    MidstComputeChecksum(),
    MidstDeparser()
) main;
