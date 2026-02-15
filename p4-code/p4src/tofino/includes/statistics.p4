/* MIDST Statistics Counters - Tofino TNA
 * Uses RegisterAction for counter updates.
 */

#ifndef _MIDST_TOFINO_STATISTICS_P4_
#define _MIDST_TOFINO_STATISTICS_P4_

Register<bit<64>, bit<1>>(1, 0) stat_total_packets;
Register<bit<64>, bit<1>>(1, 0) stat_ip_packets;
Register<bit<64>, bit<1>>(1, 0) stat_non_ip_packets;
Register<bit<64>, bit<1>>(1, 0) stat_bursty_packets;
Register<bit<64>, bit<1>>(1, 0) stat_normal_packets;
Register<bit<64>, bit<1>>(1, 0) stat_admitted_packets;
Register<bit<64>, bit<1>>(1, 0) stat_dropped_packets;
Register<bit<64>, bit<1>>(1, 0) stat_sin_updates;
Register<bit<64>, bit<1>>(1, 0) stat_sout_updates;
Register<bit<64>, bit<1>>(1, 0) stat_windows_started;
Register<bit<64>, bit<1>>(1, 0) stat_windows_ended;
Register<bit<64>, bit<1>>(1, 0) stat_vq_arrivals;
Register<bit<64>, bit<1>>(1, 0) stat_vq_departures;
Register<bit<64>, bit<1>>(1, 0) stat_total_bytes;

// Increment RegisterActions (one per counter)
RegisterAction<bit<64>, bit<1>, bit<64>>(stat_total_packets) ra_stat_total_pkts = {
    void apply(inout bit<64> val) { val = val + 1; }
};
RegisterAction<bit<64>, bit<1>, bit<64>>(stat_ip_packets) ra_stat_ip_pkts = {
    void apply(inout bit<64> val) { val = val + 1; }
};
RegisterAction<bit<64>, bit<1>, bit<64>>(stat_non_ip_packets) ra_stat_non_ip_pkts = {
    void apply(inout bit<64> val) { val = val + 1; }
};
RegisterAction<bit<64>, bit<1>, bit<64>>(stat_bursty_packets) ra_stat_bursty_pkts = {
    void apply(inout bit<64> val) { val = val + 1; }
};
RegisterAction<bit<64>, bit<1>, bit<64>>(stat_normal_packets) ra_stat_normal_pkts = {
    void apply(inout bit<64> val) { val = val + 1; }
};
RegisterAction<bit<64>, bit<1>, bit<64>>(stat_admitted_packets) ra_stat_admitted_pkts = {
    void apply(inout bit<64> val) { val = val + 1; }
};
RegisterAction<bit<64>, bit<1>, bit<64>>(stat_dropped_packets) ra_stat_dropped_pkts = {
    void apply(inout bit<64> val) { val = val + 1; }
};
RegisterAction<bit<64>, bit<1>, bit<64>>(stat_sin_updates) ra_stat_sin_upd = {
    void apply(inout bit<64> val) { val = val + 1; }
};
RegisterAction<bit<64>, bit<1>, bit<64>>(stat_sout_updates) ra_stat_sout_upd = {
    void apply(inout bit<64> val) { val = val + 1; }
};
RegisterAction<bit<64>, bit<1>, bit<64>>(stat_windows_started) ra_stat_win_start = {
    void apply(inout bit<64> val) { val = val + 1; }
};
RegisterAction<bit<64>, bit<1>, bit<64>>(stat_windows_ended) ra_stat_win_end = {
    void apply(inout bit<64> val) { val = val + 1; }
};
RegisterAction<bit<64>, bit<1>, bit<64>>(stat_vq_arrivals) ra_stat_vq_arr = {
    void apply(inout bit<64> val) { val = val + 1; }
};
RegisterAction<bit<64>, bit<1>, bit<64>>(stat_vq_departures) ra_stat_vq_dep = {
    void apply(inout bit<64> val) { val = val + 1; }
};

control TnaStatisticsIngress(
    inout headers_t hdr,
    inout midst_metadata_t meta
) {
    apply {
        ra_stat_total_pkts.execute(0);
        ra_stat_vq_arr.execute(0);

        if (hdr.ipv4.isValid()) {
            ra_stat_ip_pkts.execute(0);
        } else {
            ra_stat_non_ip_pkts.execute(0);
        }

        if (meta.is_bursty == 1) {
            ra_stat_bursty_pkts.execute(0);
        } else {
            ra_stat_normal_pkts.execute(0);
        }

        if (meta.should_drop == 0) {
            ra_stat_admitted_pkts.execute(0);
        } else {
            ra_stat_dropped_pkts.execute(0);
        }

        if (meta.monitoring_state == WINDOW_STATE_MONITORING) {
            ra_stat_sin_upd.execute(0);
        }

        if (meta.window_started == 1) {
            ra_stat_win_start.execute(0);
        }
    }
}

control TnaStatisticsEgress(
    inout headers_t hdr,
    inout midst_metadata_t meta
) {
    apply {
        ra_stat_vq_dep.execute(0);

        if (hdr.sketch_meta.isValid() && hdr.sketch_meta.sketch_valid == 1) {
            ra_stat_sout_upd.execute(0);
        }

        if (meta.window_ended == 1) {
            ra_stat_win_end.execute(0);
        }
    }
}

#endif /* _MIDST_TOFINO_STATISTICS_P4_ */
