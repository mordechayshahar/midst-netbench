/* Monitoring Window State Machine
 *
 * States: IDLE <-> MONITORING
 *   IDLE -> MONITORING: triggered by VQ high watermark crossing
 *   MONITORING -> IDLE: triggered by VQ low watermark crossing
 *
 * Watermark signals (crossed_high_wm, crossed_low_wm) come from the
 * register-based VQ (vq_depth_bits), not from hardware queue counters.
 *
 * On IDLE->MONITORING, sends a digest to the control plane with the
 * window number and VQ depth. The control plane listens for these.
 *
 * On MONITORING->IDLE, increments window_count_reg. The control plane
 * polls this register to detect window completion (digest() is only
 * available in ingress in v1model).
 */

#ifndef _MIDST_MONITORING_P4_
#define _MIDST_MONITORING_P4_

// ============================================================
// Monitoring Registers
// ============================================================

register<bit<8>>(1) monitoring_state_reg;   // IDLE=0, MONITORING=1
register<bit<32>>(1) window_count_reg;      // total windows completed

// ============================================================
// Digest struct: window_digest_t is defined in common/headers.p4
// v1model: extern void digest<T>(in bit<32> receiver, in T data);
// ============================================================

// ============================================================
// Monitoring Check (Ingress) - handles IDLE -> MONITORING
// ============================================================

control MonitoringCheck(
    inout headers_t hdr,
    inout midst_metadata_t meta,
    inout standard_metadata_t standard_metadata
) {
    apply {
        bit<8> state;
        monitoring_state_reg.read(state, 0);
        meta.monitoring_state = state;
        meta.window_started = 0;

        // If VQ crossed high watermark and we're IDLE, start monitoring
        if (meta.crossed_high_wm == 1 && state == WINDOW_STATE_IDLE) {
            monitoring_state_reg.write(0, WINDOW_STATE_MONITORING);
            meta.monitoring_state = WINDOW_STATE_MONITORING;
            meta.window_started = 1;

            // Read current window count for digest
            bit<32> wcount;
            window_count_reg.read(wcount, 0);

            // Populate digest data in metadata
            meta.digest_type = DIGEST_TYPE_WINDOW_START;
            meta.digest_window_number = wcount;

            // Send digest to control plane
            // BMv2 ignores the receiver parameter; we use 1 as convention
            digest<window_digest_t>(1, {
                meta.digest_type,
                meta.digest_window_number,
                meta.vq_depth
            });
        }
    }
}

// ============================================================
// Monitoring Stop Check (Egress) - handles MONITORING -> IDLE
// NOTE: digest() is only available in ingress in v1model.
// Window completion is signaled by incrementing window_count_reg,
// which the control plane polls periodically.
// ============================================================

control MonitoringStopCheck(
    inout headers_t hdr,
    inout midst_metadata_t meta,
    inout standard_metadata_t standard_metadata
) {
    apply {
        bit<8> state;
        monitoring_state_reg.read(state, 0);
        meta.window_ended = 0;

        // If VQ crossed low watermark and we're MONITORING, stop
        if (meta.crossed_low_wm == 1 && state == WINDOW_STATE_MONITORING) {
            monitoring_state_reg.write(0, WINDOW_STATE_IDLE);
            meta.monitoring_state = WINDOW_STATE_IDLE;
            meta.window_ended = 1;

            // Increment window count (control plane polls this)
            bit<32> wcount;
            window_count_reg.read(wcount, 0);
            window_count_reg.write(0, wcount + 1);
        }
    }
}

#endif /* _MIDST_MONITORING_P4_ */
