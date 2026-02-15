/* Virtual Queue Logic
 * Tracks logical queue depth via arrival/departure counters.
 * Generates high/low watermark crossing signals for monitoring windows.
 *
 * Key invariant: VQ tracks ALL arrivals minus ALL departures,
 * including dropped packets (critical for AIFO/PACKS correctness).
 * Without drop-path decrement, phantom packets inflate VQ permanently.
 *
 * Watermark detection compares the software-maintained vq_depth_bits
 * register against configured thresholds — does not rely on hardware
 * queue occupancy counters.
 */

#ifndef _MIDST_VIRTUAL_QUEUE_P4_
#define _MIDST_VIRTUAL_QUEUE_P4_

// ============================================================
// VQ Registers (single-element for single output port)
// ============================================================

register<bit<64>>(1) vq_depth_bits;    // current virtual queue depth
register<bit<64>>(1) vq_peak_bits;     // peak depth (high watermark tracking)
register<bit<32>>(1) vq_hwm_crossings; // count of high watermark crossings
register<bit<32>>(1) vq_lwm_crossings; // count of low watermark crossings

// ============================================================
// VQ Arrival (called in Ingress for every packet)
// ============================================================

control VirtualQueueArrival(
    inout headers_t hdr,
    inout midst_metadata_t meta,
    inout standard_metadata_t standard_metadata
) {
    apply {
        bit<64> current_depth;
        bit<64> new_depth;

        // Read current VQ depth
        vq_depth_bits.read(current_depth, 0);

        // Check if currently below high watermark
        bit<1> was_below_hwm = 0;
        if (current_depth < HIGH_WATERMARK_BITS) {
            was_below_hwm = 1;
        }

        // Increment VQ
        new_depth = current_depth + meta.packet_size_bits;

        // Detect high watermark crossing (was below, now at or above)
        meta.crossed_high_wm = 0;
        if (was_below_hwm == 1 && new_depth >= HIGH_WATERMARK_BITS) {
            meta.crossed_high_wm = 1;
            // Increment crossing counter
            bit<32> crossings;
            vq_hwm_crossings.read(crossings, 0);
            vq_hwm_crossings.write(0, crossings + 1);
        }

        // Write new depth
        vq_depth_bits.write(0, new_depth);
        meta.vq_depth = new_depth;

        // Update peak
        bit<64> peak;
        vq_peak_bits.read(peak, 0);
        if (new_depth > peak) {
            vq_peak_bits.write(0, new_depth);
        }
    }
}

// ============================================================
// VQ Departure (called in Egress AND on drop path)
// ============================================================

control VirtualQueueDeparture(
    inout headers_t hdr,
    inout midst_metadata_t meta,
    inout standard_metadata_t standard_metadata
) {
    apply {
        bit<64> current_depth;
        bit<64> new_depth;

        // Read current VQ depth
        vq_depth_bits.read(current_depth, 0);

        // Check if currently above low watermark
        bit<1> was_above_lwm = 0;
        if (current_depth > LOW_WATERMARK_BITS) {
            was_above_lwm = 1;
        }

        // Decrement VQ with clamping (prevent underflow)
        if (current_depth > meta.packet_size_bits) {
            new_depth = current_depth - meta.packet_size_bits;
        } else {
            new_depth = 0;
        }

        // Detect low watermark crossing (was above, now at or below)
        meta.crossed_low_wm = 0;
        if (was_above_lwm == 1 && new_depth <= LOW_WATERMARK_BITS) {
            meta.crossed_low_wm = 1;
            // Increment crossing counter
            bit<32> crossings;
            vq_lwm_crossings.read(crossings, 0);
            vq_lwm_crossings.write(0, crossings + 1);
        }

        // Write new depth
        vq_depth_bits.write(0, new_depth);
        meta.vq_depth = new_depth;
    }
}

#endif /* _MIDST_VIRTUAL_QUEUE_P4_ */
