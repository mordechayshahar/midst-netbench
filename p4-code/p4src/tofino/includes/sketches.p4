/* Count-Min Delta-Sketch - Tofino TNA
 * Uses Hash<> extern and RegisterAction for sketch operations.
 */

#ifndef _MIDST_TOFINO_SKETCHES_P4_
#define _MIDST_TOFINO_SKETCHES_P4_

// ============================================================
// Sketch Registers
// ============================================================

Register<bit<32>, bit<32>>(SKETCH_TOTAL_SIZE, 0) sin_sketch;
Register<bit<32>, bit<32>>(SKETCH_TOTAL_SIZE, 0) sout_sketch;
Register<bit<1>, bit<32>>(SKETCH_TOTAL_SIZE, 0)  sin_dirty;
Register<bit<1>, bit<32>>(SKETCH_TOTAL_SIZE, 0)  sout_dirty;

// ============================================================
// Hash Externs
// ============================================================

Hash<bit<32>>(HashAlgorithm_t.CRC32) sketch_hash_0;
Hash<bit<32>>(HashAlgorithm_t.CRC32) sketch_hash_1;
Hash<bit<32>>(HashAlgorithm_t.CRC32) sketch_hash_2;
Hash<bit<32>>(HashAlgorithm_t.CRC32) sketch_hash_3;

// ============================================================
// Sin RegisterActions (increment)
// ============================================================

RegisterAction<bit<32>, bit<32>, bit<32>>(sin_sketch) ra_sin_incr = {
    void apply(inout bit<32> val) {
        val = val + 1;
    }
};

RegisterAction<bit<1>, bit<32>, bit<1>>(sin_dirty) ra_sin_mark_dirty = {
    void apply(inout bit<1> val) {
        val = 1;
    }
};

// ============================================================
// Sout RegisterActions (increment)
// ============================================================

RegisterAction<bit<32>, bit<32>, bit<32>>(sout_sketch) ra_sout_incr = {
    void apply(inout bit<32> val) {
        val = val + 1;
    }
};

RegisterAction<bit<1>, bit<32>, bit<1>>(sout_dirty) ra_sout_mark_dirty = {
    void apply(inout bit<1> val) {
        val = 1;
    }
};

// ============================================================
// Ingress Sketch Update
// ============================================================

control TnaSketchUpdateIngress(
    inout headers_t hdr,
    inout midst_metadata_t meta
) {
    apply {
        // Compute hash indices using Tofino Hash extern
        bit<32> h0 = sketch_hash_0.get({meta.flow_id ^ HASH_SEED_0});
        bit<32> h1 = sketch_hash_1.get({meta.flow_id ^ HASH_SEED_1});
        bit<32> h2 = sketch_hash_2.get({meta.flow_id ^ HASH_SEED_2});
        bit<32> h3 = sketch_hash_3.get({meta.flow_id ^ HASH_SEED_3});

        // Map to sketch indices (mod SKETCH_SIZE per row)
        meta.hash_idx_0 = h0 % SKETCH_SIZE;
        meta.hash_idx_1 = SKETCH_SIZE + (h1 % SKETCH_SIZE);
        meta.hash_idx_2 = (SKETCH_SIZE << 1) + (h2 % SKETCH_SIZE);
        meta.hash_idx_3 = (SKETCH_SIZE << 1) + SKETCH_SIZE + (h3 % SKETCH_SIZE);

        // Update Sin during monitoring
        if (meta.monitoring_state == WINDOW_STATE_MONITORING) {
            ra_sin_incr.execute(meta.hash_idx_0);
            ra_sin_mark_dirty.execute(meta.hash_idx_0);
            ra_sin_incr.execute(meta.hash_idx_1);
            ra_sin_mark_dirty.execute(meta.hash_idx_1);
            ra_sin_incr.execute(meta.hash_idx_2);
            ra_sin_mark_dirty.execute(meta.hash_idx_2);
            ra_sin_incr.execute(meta.hash_idx_3);
            ra_sin_mark_dirty.execute(meta.hash_idx_3);
        }
    }
}

// ============================================================
// Egress Sketch Update
// ============================================================

control TnaSketchUpdateEgress(
    inout headers_t hdr,
    inout midst_metadata_t meta
) {
    apply {
        if (hdr.sketch_meta.isValid() && hdr.sketch_meta.sketch_valid == 1) {
            bit<32> idx0 = hdr.sketch_meta.hash_idx_0;
            bit<32> idx1 = hdr.sketch_meta.hash_idx_1;
            bit<32> idx2 = hdr.sketch_meta.hash_idx_2;
            bit<32> idx3 = hdr.sketch_meta.hash_idx_3;

            ra_sout_incr.execute(idx0);
            ra_sout_mark_dirty.execute(idx0);
            ra_sout_incr.execute(idx1);
            ra_sout_mark_dirty.execute(idx1);
            ra_sout_incr.execute(idx2);
            ra_sout_mark_dirty.execute(idx2);
            ra_sout_incr.execute(idx3);
            ra_sout_mark_dirty.execute(idx3);
        }
    }
}

#endif /* _MIDST_TOFINO_SKETCHES_P4_ */
