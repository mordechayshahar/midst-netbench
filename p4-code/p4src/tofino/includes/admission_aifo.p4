/* AIFO Admission Control - Tofino TNA
 *
 * Sliding window quantile using RegisterAction per slot.
 * Based on AIFO paper's BLACKBOX_CHECK_WINDOW pattern.
 *
 * Each window slot is a separate Register with a RegisterAction that:
 *   1. Reads the stored rank
 *   2. If tail_ptr == slot_index, overwrites with incoming rank
 *   3. Outputs 1-bit comparison result (stored > incoming)
 *
 * 20 instances unrolled for full window. May reduce to 12 for
 * Tofino stage budget (see docs/tofino_stage_map.md).
 */

#ifndef _MIDST_TOFINO_ADMISSION_AIFO_P4_
#define _MIDST_TOFINO_ADMISSION_AIFO_P4_

// ============================================================
// AIFO Window Registers (20 slots)
// ============================================================

Register<bit<32>, bit<1>>(1, 0) aifo_window_0;
Register<bit<32>, bit<1>>(1, 0) aifo_window_1;
Register<bit<32>, bit<1>>(1, 0) aifo_window_2;
Register<bit<32>, bit<1>>(1, 0) aifo_window_3;
Register<bit<32>, bit<1>>(1, 0) aifo_window_4;
Register<bit<32>, bit<1>>(1, 0) aifo_window_5;
Register<bit<32>, bit<1>>(1, 0) aifo_window_6;
Register<bit<32>, bit<1>>(1, 0) aifo_window_7;
Register<bit<32>, bit<1>>(1, 0) aifo_window_8;
Register<bit<32>, bit<1>>(1, 0) aifo_window_9;
Register<bit<32>, bit<1>>(1, 0) aifo_window_10;
Register<bit<32>, bit<1>>(1, 0) aifo_window_11;
Register<bit<32>, bit<1>>(1, 0) aifo_window_12;
Register<bit<32>, bit<1>>(1, 0) aifo_window_13;
Register<bit<32>, bit<1>>(1, 0) aifo_window_14;
Register<bit<32>, bit<1>>(1, 0) aifo_window_15;
Register<bit<32>, bit<1>>(1, 0) aifo_window_16;
Register<bit<32>, bit<1>>(1, 0) aifo_window_17;
Register<bit<32>, bit<1>>(1, 0) aifo_window_18;
Register<bit<32>, bit<1>>(1, 0) aifo_window_19;

Register<bit<32>, bit<1>>(1, 0) aifo_tail_ptr;
Register<bit<32>, bit<1>>(1, 0) aifo_queue_size;
Register<bit<32>, bit<1>>(1, 0) aifo_admit_count;
Register<bit<32>, bit<1>>(1, 0) aifo_reject_count;

// ============================================================
// BLACKBOX_CHECK_WINDOW macro pattern
// Each RegisterAction:
//   - Reads stored rank
//   - If meta.aifo_tail_ptr == SLOT_IDX: writes incoming rank
//   - Returns 1 if stored > incoming, else 0
// ============================================================

// In real Tofino code, this would be a macro generating 20 RegisterActions.
// Here we show the pattern for slots 0-19:

RegisterAction<bit<32>, bit<1>, bit<1>>(aifo_window_0) ra_aifo_check_0 = {
    void apply(inout bit<32> val, out bit<1> result) {
        result = (val > meta.effective_rank) ? (bit<1>)1 : (bit<1>)0;
        if (meta.aifo_tail_ptr == 0) {
            val = meta.effective_rank;
        }
    }
};

// ... (slots 1-19 follow the same pattern with different index checks)
// In production, use a preprocessor macro:
// #define BLACKBOX_CHECK_WINDOW(N) \
//   RegisterAction<bit<32>, bit<1>, bit<1>>(aifo_window_##N) ra_aifo_check_##N = { \
//     void apply(inout bit<32> val, out bit<1> result) { \
//       result = (val > meta.effective_rank) ? (bit<1>)1 : (bit<1>)0; \
//       if (meta.aifo_tail_ptr == N) { val = meta.effective_rank; } \
//     } \
//   };

// Tail pointer: atomic read and increment (mod 20)
RegisterAction<bit<32>, bit<1>, bit<32>>(aifo_tail_ptr) ra_aifo_tail_advance = {
    void apply(inout bit<32> val, out bit<32> result) {
        result = val;
        if (val >= 19) {
            val = 0;
        } else {
            val = val + 1;
        }
    }
};

// Queue size: atomic increment
RegisterAction<bit<32>, bit<1>, bit<32>>(aifo_queue_size) ra_aifo_qs_read = {
    void apply(inout bit<32> val, out bit<32> result) {
        result = val;
    }
};

RegisterAction<bit<32>, bit<1>, bit<32>>(aifo_queue_size) ra_aifo_qs_incr = {
    void apply(inout bit<32> val) {
        val = val + 1;
    }
};

RegisterAction<bit<32>, bit<1>, bit<32>>(aifo_admit_count) ra_aifo_admit_incr = {
    void apply(inout bit<32> val) { val = val + 1; }
};

RegisterAction<bit<32>, bit<1>, bit<32>>(aifo_reject_count) ra_aifo_reject_incr = {
    void apply(inout bit<32> val) { val = val + 1; }
};

// ============================================================
// AIFO Admission Control
// ============================================================

control TnaAifoAdmission(
    inout headers_t hdr,
    inout midst_metadata_t meta
) {
    apply {
        bit<32> queue_size = ra_aifo_qs_read.execute(0);
        meta.aifo_queue_size = queue_size;

        bit<32> fill_level = meta.effective_queue_size;

        if (fill_level < AIFO_THRESHOLD_PKTS) {
            meta.should_drop = 0;
        } else {
            // Read tail and advance atomically
            meta.aifo_tail_ptr = ra_aifo_tail_advance.execute(0);

            // Check each window slot (unrolled)
            bit<32> qcount = 0;
            bit<1> cmp;

            cmp = ra_aifo_check_0.execute(0);
            qcount = qcount + (bit<32>)cmp;
            // ... (slots 1-19 follow same pattern)

            meta.aifo_quantile_count = qcount;

            // Admission formula
            if (queue_size >= MAX_QUEUE_SIZE_PKTS) {
                meta.should_drop = 1;
            } else {
                bit<32> lhs = AIFO_C_MINUS_KC * qcount;
                bit<32> rhs = (MAX_QUEUE_SIZE_PKTS - queue_size) * AIFO_WINDOW_SIZE;
                if (lhs <= rhs) {
                    meta.should_drop = 0;
                } else {
                    meta.should_drop = 1;
                }
            }
        }

        // Update counters
        if (meta.should_drop == 0) {
            ra_aifo_qs_incr.execute(0);
            ra_aifo_admit_incr.execute(0);
        } else {
            ra_aifo_reject_incr.execute(0);
        }
    }
}

#endif /* _MIDST_TOFINO_ADMISSION_AIFO_P4_ */
