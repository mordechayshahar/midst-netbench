/* Count-Min Delta-Sketch Operations
 *
 * Two parallel sketches:
 *   Sin (ingress): counts arriving packets per flow during monitoring window
 *   Sout (egress): counts departing packets per flow during monitoring window
 *
 * Delta estimate: Sin[flow] - Sout[flow] = in-flight / lost packets
 *
 * Dirty flags track which entries were written during a window,
 * so the control plane only resets those entries (sparse reset).
 *
 * Hash indices are computed once in ingress and carried to egress
 * via the sketch_meta header (Option A: pre-compute approach).
 */

#ifndef _MIDST_SKETCHES_P4_
#define _MIDST_SKETCHES_P4_

// ============================================================
// Sketch Registers
// ============================================================

// Sin: ingress sketch (incremented when packet arrives during monitoring)
register<bit<32>>(SKETCH_TOTAL_SIZE) sin_sketch;
// Sout: egress sketch (incremented when packet departs during monitoring)
register<bit<32>>(SKETCH_TOTAL_SIZE) sout_sketch;

// Dirty flags: 1 bit per entry, tracks which entries need reset
// Using per-entry registers for BMv2 simplicity (no bit packing)
register<bit<1>>(SKETCH_TOTAL_SIZE) sin_dirty;
register<bit<1>>(SKETCH_TOTAL_SIZE) sout_dirty;

// ============================================================
// Ingress Sketch Update
// Computes hash indices and increments Sin if monitoring is active
// ============================================================

control SketchUpdateIngress(
    inout headers_t hdr,
    inout midst_metadata_t meta,
    inout standard_metadata_t standard_metadata
) {
    apply {
        // Always compute hash indices (needed for bursty lookup too)
        bit<32> h0;
        bit<32> h1;
        bit<32> h2;
        bit<32> h3;

        hash(h0, HashAlgorithm.crc32, 32w0,
             { meta.flow_id ^ HASH_SEED_0 }, SKETCH_SIZE);
        hash(h1, HashAlgorithm.crc32, 32w0,
             { meta.flow_id ^ HASH_SEED_1 }, SKETCH_SIZE);
        hash(h2, HashAlgorithm.crc32, 32w0,
             { meta.flow_id ^ HASH_SEED_2 }, SKETCH_SIZE);
        hash(h3, HashAlgorithm.crc32, 32w0,
             { meta.flow_id ^ HASH_SEED_3 }, SKETCH_SIZE);

        // Convert to flat indices: row * SKETCH_SIZE + column
        meta.hash_idx_0 = h0;                        // row 0
        meta.hash_idx_1 = SKETCH_SIZE + h1;           // row 1
        meta.hash_idx_2 = (SKETCH_SIZE << 1) + h2;    // row 2 (2*256)
        meta.hash_idx_3 = (SKETCH_SIZE << 1) + SKETCH_SIZE + h3; // row 3 (3*256)

        // Only update Sin during monitoring
        if (meta.monitoring_state == WINDOW_STATE_MONITORING) {
            bit<32> count_0;
            bit<32> count_1;
            bit<32> count_2;
            bit<32> count_3;

            // Read, increment, write for each hash row
            sin_sketch.read(count_0, meta.hash_idx_0);
            sin_sketch.write(meta.hash_idx_0, count_0 + 1);
            sin_dirty.write(meta.hash_idx_0, 1);

            sin_sketch.read(count_1, meta.hash_idx_1);
            sin_sketch.write(meta.hash_idx_1, count_1 + 1);
            sin_dirty.write(meta.hash_idx_1, 1);

            sin_sketch.read(count_2, meta.hash_idx_2);
            sin_sketch.write(meta.hash_idx_2, count_2 + 1);
            sin_dirty.write(meta.hash_idx_2, 1);

            sin_sketch.read(count_3, meta.hash_idx_3);
            sin_sketch.write(meta.hash_idx_3, count_3 + 1);
            sin_dirty.write(meta.hash_idx_3, 1);
        }
    }
}

// ============================================================
// Egress Sketch Update
// Reads hash indices from sketch_meta header, increments Sout
// Only updates if sketch_valid is set (packet arrived during monitoring)
// ============================================================

control SketchUpdateEgress(
    inout headers_t hdr,
    inout midst_metadata_t meta,
    inout standard_metadata_t standard_metadata
) {
    apply {
        // Only update Sout if sketch metadata is valid
        // (meaning ingress updated Sin for this packet)
        if (hdr.sketch_meta.isValid() && hdr.sketch_meta.sketch_valid == 1) {
            bit<32> count_0;
            bit<32> count_1;
            bit<32> count_2;
            bit<32> count_3;

            // Read hash indices from the header
            bit<32> idx0 = hdr.sketch_meta.hash_idx_0;
            bit<32> idx1 = hdr.sketch_meta.hash_idx_1;
            bit<32> idx2 = hdr.sketch_meta.hash_idx_2;
            bit<32> idx3 = hdr.sketch_meta.hash_idx_3;

            // Read, increment, write for each hash row
            sout_sketch.read(count_0, idx0);
            sout_sketch.write(idx0, count_0 + 1);
            sout_dirty.write(idx0, 1);

            sout_sketch.read(count_1, idx1);
            sout_sketch.write(idx1, count_1 + 1);
            sout_dirty.write(idx1, 1);

            sout_sketch.read(count_2, idx2);
            sout_sketch.write(idx2, count_2 + 1);
            sout_dirty.write(idx2, 1);

            sout_sketch.read(count_3, idx3);
            sout_sketch.write(idx3, count_3 + 1);
            sout_dirty.write(idx3, 1);
        }
    }
}

#endif /* _MIDST_SKETCHES_P4_ */
