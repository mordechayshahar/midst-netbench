/* MIDST Statistics Counters
 *
 * 14 counter registers for monitoring, debugging, and thesis evaluation.
 * All counters are read-only from the control plane perspective
 * (control plane reads and resets them periodically).
 */

#ifndef _MIDST_STATISTICS_P4_
#define _MIDST_STATISTICS_P4_

// ============================================================
// Statistics Registers
// ============================================================

// Packet counters
register<bit<64>>(1) stat_total_packets;       // 1. all packets processed
register<bit<64>>(1) stat_ip_packets;           // 2. IPv4 packets
register<bit<64>>(1) stat_non_ip_packets;       // 3. non-IPv4 (forwarded as-is)
register<bit<64>>(1) stat_bursty_packets;       // 4. packets from bursty flows
register<bit<64>>(1) stat_normal_packets;       // 5. packets from normal flows
register<bit<64>>(1) stat_admitted_packets;     // 6. packets admitted by scheduler
register<bit<64>>(1) stat_dropped_packets;      // 7. packets dropped by admission

// Sketch counters
register<bit<64>>(1) stat_sin_updates;          // 8. Sin sketch increments
register<bit<64>>(1) stat_sout_updates;         // 9. Sout sketch increments

// Window counters
register<bit<64>>(1) stat_windows_started;      // 10. monitoring windows started
register<bit<64>>(1) stat_windows_ended;        // 11. monitoring windows ended

// VQ counters
register<bit<64>>(1) stat_vq_arrivals;          // 12. VQ arrival events
register<bit<64>>(1) stat_vq_departures;        // 13. VQ departure events

// Byte counters
register<bit<64>>(1) stat_total_bytes;          // 14. total bytes processed

// ============================================================
// Ingress Statistics
// ============================================================

control StatisticsIngress(
    inout headers_t hdr,
    inout midst_metadata_t meta,
    inout standard_metadata_t standard_metadata
) {
    apply {
        bit<64> val;

        // Total packets
        stat_total_packets.read(val, 0);
        stat_total_packets.write(0, val + 1);

        // Total bytes
        stat_total_bytes.read(val, 0);
        stat_total_bytes.write(0, val + (bit<64>)standard_metadata.packet_length);

        // IP vs non-IP
        if (hdr.ipv4.isValid()) {
            stat_ip_packets.read(val, 0);
            stat_ip_packets.write(0, val + 1);
        } else {
            stat_non_ip_packets.read(val, 0);
            stat_non_ip_packets.write(0, val + 1);
        }

        // Bursty vs normal
        if (meta.is_bursty == 1) {
            stat_bursty_packets.read(val, 0);
            stat_bursty_packets.write(0, val + 1);
        } else {
            stat_normal_packets.read(val, 0);
            stat_normal_packets.write(0, val + 1);
        }

        // Admitted vs dropped
        if (meta.should_drop == 0) {
            stat_admitted_packets.read(val, 0);
            stat_admitted_packets.write(0, val + 1);
        } else {
            stat_dropped_packets.read(val, 0);
            stat_dropped_packets.write(0, val + 1);
        }

        // Sin updates (only during monitoring)
        if (meta.monitoring_state == WINDOW_STATE_MONITORING) {
            stat_sin_updates.read(val, 0);
            stat_sin_updates.write(0, val + 1);
        }

        // VQ arrivals
        stat_vq_arrivals.read(val, 0);
        stat_vq_arrivals.write(0, val + 1);

        // Window started
        if (meta.window_started == 1) {
            stat_windows_started.read(val, 0);
            stat_windows_started.write(0, val + 1);
        }
    }
}

// ============================================================
// Egress Statistics
// ============================================================

control StatisticsEgress(
    inout headers_t hdr,
    inout midst_metadata_t meta,
    inout standard_metadata_t standard_metadata
) {
    apply {
        bit<64> val;

        // Sout updates
        if (hdr.sketch_meta.isValid() && hdr.sketch_meta.sketch_valid == 1) {
            stat_sout_updates.read(val, 0);
            stat_sout_updates.write(0, val + 1);
        }

        // VQ departures
        stat_vq_departures.read(val, 0);
        stat_vq_departures.write(0, val + 1);

        // Window ended
        if (meta.window_ended == 1) {
            stat_windows_ended.read(val, 0);
            stat_windows_ended.write(0, val + 1);
        }
    }
}

#endif /* _MIDST_STATISTICS_P4_ */
