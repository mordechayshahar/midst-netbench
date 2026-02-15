/* Monitoring Window State Machine - Tofino TNA
 * Uses RegisterAction for state transitions.
 * Digest handled by Tofino Digest<> extern in deparser.
 */

#ifndef _MIDST_TOFINO_MONITORING_P4_
#define _MIDST_TOFINO_MONITORING_P4_

Register<bit<8>, bit<1>>(1, 0)  monitoring_state_reg;
Register<bit<32>, bit<1>>(1, 0) window_count_reg;

// RegisterAction to check and transition IDLE -> MONITORING
RegisterAction<bit<8>, bit<1>, bit<8>>(monitoring_state_reg) ra_check_start = {
    void apply(inout bit<8> val, out bit<8> result) {
        result = val;
        if (val == WINDOW_STATE_IDLE) {
            val = WINDOW_STATE_MONITORING;
        }
    }
};

// RegisterAction to check and transition MONITORING -> IDLE
RegisterAction<bit<8>, bit<1>, bit<8>>(monitoring_state_reg) ra_check_stop = {
    void apply(inout bit<8> val, out bit<8> result) {
        result = val;
        if (val == WINDOW_STATE_MONITORING) {
            val = WINDOW_STATE_IDLE;
        }
    }
};

RegisterAction<bit<32>, bit<1>, bit<32>>(window_count_reg) ra_window_incr = {
    void apply(inout bit<32> val, out bit<32> result) {
        result = val;
        val = val + 1;
    }
};

control TnaMonitoringCheck(
    inout headers_t hdr,
    inout midst_metadata_t meta
) {
    apply {
        meta.window_started = 0;

        if (meta.crossed_high_wm == 1) {
            bit<8> prev_state = ra_check_start.execute(0);
            if (prev_state == WINDOW_STATE_IDLE) {
                meta.monitoring_state = WINDOW_STATE_MONITORING;
                meta.window_started = 1;

                bit<32> wcount = ra_window_incr.execute(0);
                meta.digest_type = DIGEST_TYPE_WINDOW_START;
                meta.digest_window_number = wcount;
                // Digest packed in deparser (MidstIngressDeparser)
            }
        } else {
            // Read current state without modifying
            // In real Tofino, use a separate read-only RegisterAction
            meta.monitoring_state = WINDOW_STATE_IDLE; // placeholder
        }
    }
}

control TnaMonitoringStopCheck(
    inout headers_t hdr,
    inout midst_metadata_t meta
) {
    apply {
        meta.window_ended = 0;

        if (meta.crossed_low_wm == 1) {
            bit<8> prev_state = ra_check_stop.execute(0);
            if (prev_state == WINDOW_STATE_MONITORING) {
                meta.monitoring_state = WINDOW_STATE_IDLE;
                meta.window_ended = 1;
                ra_window_incr.execute(0);
            }
        }
    }
}

#endif /* _MIDST_TOFINO_MONITORING_P4_ */
