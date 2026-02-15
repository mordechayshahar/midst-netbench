/* PACKS Admission Control - Tofino TNA
 * Queue selection + fill-level admission using RegisterAction.
 */

#ifndef _MIDST_TOFINO_ADMISSION_PACKS_P4_
#define _MIDST_TOFINO_ADMISSION_PACKS_P4_

Register<bit<32>, bit<32>>(NUM_QUEUES, 0) packs_queue_occupancy;
Register<bit<32>, bit<1>>(1, 0) packs_quantile_threshold;
Register<bit<32>, bit<1>>(1, 0) packs_admit_count;
Register<bit<32>, bit<1>>(1, 0) packs_reject_count;

RegisterAction<bit<32>, bit<1>, bit<32>>(packs_quantile_threshold) ra_packs_thresh_read = {
    void apply(inout bit<32> val, out bit<32> result) { result = val; }
};

RegisterAction<bit<32>, bit<32>, bit<32>>(packs_queue_occupancy) ra_packs_occ_incr = {
    void apply(inout bit<32> val) { val = val + 1; }
};

RegisterAction<bit<32>, bit<1>, bit<32>>(packs_admit_count) ra_packs_admit_incr = {
    void apply(inout bit<32> val) { val = val + 1; }
};

RegisterAction<bit<32>, bit<1>, bit<32>>(packs_reject_count) ra_packs_reject_incr = {
    void apply(inout bit<32> val) { val = val + 1; }
};

control TnaPacksAdmission(
    inout headers_t hdr,
    inout midst_metadata_t meta,
    inout ingress_intrinsic_metadata_for_tm_t ig_tm_md
) {
    apply {
        // Queue selection
        bit<32> max_queue_idx = NUM_QUEUES - 1;
        bit<32> computed_idx;
        if (meta.effective_rank >= max_queue_idx * RANK_DELTA) {
            computed_idx = max_queue_idx;
        } else {
            computed_idx = meta.effective_rank / RANK_DELTA;
        }
        meta.output_queue = (bit<8>)computed_idx;
        ig_tm_md.qid = (bit<5>)computed_idx;

        // Admission
        bit<32> fill_level = meta.effective_queue_size;
        if (fill_level < PACKS_THRESHOLD_PKTS) {
            meta.should_drop = 0;
        } else {
            bit<32> q_threshold = ra_packs_thresh_read.execute(0);
            if (meta.effective_rank <= q_threshold) {
                meta.should_drop = 0;
            } else {
                meta.should_drop = 1;
            }
        }

        if (meta.should_drop == 0) {
            ra_packs_occ_incr.execute(computed_idx);
            ra_packs_admit_incr.execute(0);
        } else {
            ra_packs_reject_incr.execute(0);
        }
    }
}

#endif /* _MIDST_TOFINO_ADMISSION_PACKS_P4_ */
