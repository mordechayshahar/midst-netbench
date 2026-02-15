/* Virtual Queue - Tofino TNA
 * Uses RegisterAction for atomic read-modify-write operations.
 *
 * Key invariant: VQ tracks ALL arrivals minus ALL departures,
 * including dropped packets (critical for AIFO/PACKS correctness).
 * Without drop-path decrement, phantom packets inflate VQ permanently.
 *
 * Watermark detection compares the software-maintained vq_depth_bits
 * register against configured thresholds — does not rely on hardware
 * queue occupancy counters. Tofino uses RegisterAction for atomic RMW.
 */

#ifndef _MIDST_TOFINO_VIRTUAL_QUEUE_P4_
#define _MIDST_TOFINO_VIRTUAL_QUEUE_P4_

// ============================================================
// VQ Registers
// ============================================================

Register<bit<64>, bit<1>>(1, 0) vq_depth_bits;
Register<bit<64>, bit<1>>(1, 0) vq_peak_bits;
Register<bit<32>, bit<1>>(1, 0) vq_hwm_crossings;
Register<bit<32>, bit<1>>(1, 0) vq_lwm_crossings;

// ============================================================
// VQ Arrival RegisterActions
// ============================================================

RegisterAction<bit<64>, bit<1>, bit<64>>(vq_depth_bits) ra_vq_arrival = {
    void apply(inout bit<64> val, out bit<64> result) {
        val = val + meta.packet_size_bits;
        result = val;
    }
};

RegisterAction<bit<64>, bit<1>, bit<64>>(vq_peak_bits) ra_vq_peak_update = {
    void apply(inout bit<64> val, out bit<64> result) {
        if (meta.vq_depth > val) {
            val = meta.vq_depth;
        }
        result = val;
    }
};

RegisterAction<bit<32>, bit<1>, bit<32>>(vq_hwm_crossings) ra_hwm_incr = {
    void apply(inout bit<32> val) {
        val = val + 1;
    }
};

// ============================================================
// VQ Departure RegisterActions
// ============================================================

RegisterAction<bit<64>, bit<1>, bit<64>>(vq_depth_bits) ra_vq_departure = {
    void apply(inout bit<64> val, out bit<64> result) {
        if (val > meta.packet_size_bits) {
            val = val - meta.packet_size_bits;
        } else {
            val = 0;
        }
        result = val;
    }
};

RegisterAction<bit<32>, bit<1>, bit<32>>(vq_lwm_crossings) ra_lwm_incr = {
    void apply(inout bit<32> val) {
        val = val + 1;
    }
};

// ============================================================
// VQ Arrival Control
// ============================================================

control TnaVirtualQueueArrival(
    inout headers_t hdr,
    inout midst_metadata_t meta
) {
    apply {
        // Atomic increment and read new depth
        meta.vq_depth = ra_vq_arrival.execute(0);

        // Detect high watermark crossing
        meta.crossed_high_wm = 0;
        // Note: In a real Tofino implementation, the previous-value comparison
        // would be done inside the RegisterAction with a predicate output.
        // This is a simplified skeleton.
        if (meta.vq_depth >= HIGH_WATERMARK_BITS) {
            // Approximate: we flag crossing if depth is at/above HWM
            // Real impl would track previous state in a separate register
            meta.crossed_high_wm = 1;
        }

        // Update peak
        ra_vq_peak_update.execute(0);
    }
}

// ============================================================
// VQ Departure Control (Egress AND drop path)
// ============================================================

control TnaVirtualQueueDeparture(
    inout headers_t hdr,
    inout midst_metadata_t meta
) {
    apply {
        meta.vq_depth = ra_vq_departure.execute(0);

        meta.crossed_low_wm = 0;
        if (meta.vq_depth <= LOW_WATERMARK_BITS) {
            meta.crossed_low_wm = 1;
        }
    }
}

#endif /* _MIDST_TOFINO_VIRTUAL_QUEUE_P4_ */
