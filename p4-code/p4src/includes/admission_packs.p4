/* PACKS Admission Control + Queue Selection
 *
 * PACKS uses multiple priority queues. Queue selection formula:
 *   queue_idx = min(effective_rank / rank_delta, num_queues - 1)
 *
 * Admission uses same fill-level gate as AIFO but with PACKS-specific k.
 * Each queue has independent occupancy tracking.
 */

#ifndef _MIDST_ADMISSION_PACKS_P4_
#define _MIDST_ADMISSION_PACKS_P4_

// ============================================================
// PACKS Registers
// ============================================================

register<bit<32>>(NUM_QUEUES) packs_queue_occupancy; // per-queue occupancy
register<bit<32>>(1) packs_quantile_threshold;        // control plane written
register<bit<32>>(1) packs_admit_count;
register<bit<32>>(1) packs_reject_count;

// ============================================================
// PACKS Admission Control + Queue Selection
// ============================================================

control PacksAdmission(
    inout headers_t hdr,
    inout midst_metadata_t meta,
    inout standard_metadata_t standard_metadata
) {
    apply {
        // ---- Queue Selection ----
        // queue_idx = min(effective_rank / rank_delta, num_queues - 1)
        bit<32> max_queue_idx = NUM_QUEUES - 1;
        bit<32> computed_idx;

        if (meta.effective_rank >= max_queue_idx * RANK_DELTA) {
            // Rank too high, put in lowest-priority queue
            computed_idx = max_queue_idx;
        } else {
            computed_idx = meta.effective_rank / RANK_DELTA;
        }
        meta.output_queue = (bit<8>)computed_idx;

        // ---- Admission Control ----
        // Use VFL for bursty flows
        bit<32> fill_level = meta.effective_queue_size;

        if (fill_level < PACKS_THRESHOLD_PKTS) {
            // Below threshold, always admit
            meta.should_drop = 0;
        } else {
            // Quantile comparison (same pattern as AIFO)
            bit<32> q_threshold;
            packs_quantile_threshold.read(q_threshold, 0);

            if (meta.effective_rank <= q_threshold) {
                meta.should_drop = 0;
            } else {
                meta.should_drop = 1;
            }
        }

        // Update per-queue occupancy
        if (meta.should_drop == 0) {
            bit<32> occ;
            packs_queue_occupancy.read(occ, computed_idx);
            packs_queue_occupancy.write(computed_idx, occ + 1);

            bit<32> admits;
            packs_admit_count.read(admits, 0);
            packs_admit_count.write(0, admits + 1);

            // Set BMv2 output queue (qid field in standard_metadata)
            // BMv2 supports up to 8 queues per port
            standard_metadata.priority = (bit<3>)computed_idx;
        } else {
            bit<32> rejects;
            packs_reject_count.read(rejects, 0);
            packs_reject_count.write(0, rejects + 1);
        }
    }
}

#endif /* _MIDST_ADMISSION_PACKS_P4_ */
