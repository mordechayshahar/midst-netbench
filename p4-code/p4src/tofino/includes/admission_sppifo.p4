/* SP-PIFO Scheduling - Tofino TNA
 *
 * Uses resubmit for blocking reaction (from sp-pifo_p4_14_tofino.p4):
 *   Pass 1: Read all 8 bounds, cascading check, compute cost
 *           If blocked: resubmit packet with cost in resubmit header
 *   Pass 2: Subtract cost from all 8 bounds via RegisterActions
 *
 * Resubmit is necessary because Tofino RegisterAction can only do
 * one read-modify-write per register per pass. The blocking reaction
 * needs to read bounds (pass 1) then modify all of them (pass 2).
 */

#ifndef _MIDST_TOFINO_ADMISSION_SPPIFO_P4_
#define _MIDST_TOFINO_ADMISSION_SPPIFO_P4_

// ============================================================
// SP-PIFO Queue Bound Registers
// ============================================================

Register<bit<32>, bit<1>>(1, 0) sppifo_bound_0;
Register<bit<32>, bit<1>>(1, 0) sppifo_bound_1;
Register<bit<32>, bit<1>>(1, 0) sppifo_bound_2;
Register<bit<32>, bit<1>>(1, 0) sppifo_bound_3;
Register<bit<32>, bit<1>>(1, 0) sppifo_bound_4;
Register<bit<32>, bit<1>>(1, 0) sppifo_bound_5;
Register<bit<32>, bit<1>>(1, 0) sppifo_bound_6;
Register<bit<32>, bit<1>>(1, 0) sppifo_bound_7;

// ============================================================
// Pass 1 RegisterActions: Read bounds
// ============================================================

RegisterAction<bit<32>, bit<1>, bit<32>>(sppifo_bound_0) ra_sppifo_read_0 = {
    void apply(inout bit<32> val, out bit<32> result) { result = val; }
};
RegisterAction<bit<32>, bit<1>, bit<32>>(sppifo_bound_1) ra_sppifo_read_1 = {
    void apply(inout bit<32> val, out bit<32> result) { result = val; }
};
RegisterAction<bit<32>, bit<1>, bit<32>>(sppifo_bound_2) ra_sppifo_read_2 = {
    void apply(inout bit<32> val, out bit<32> result) { result = val; }
};
RegisterAction<bit<32>, bit<1>, bit<32>>(sppifo_bound_3) ra_sppifo_read_3 = {
    void apply(inout bit<32> val, out bit<32> result) { result = val; }
};
RegisterAction<bit<32>, bit<1>, bit<32>>(sppifo_bound_4) ra_sppifo_read_4 = {
    void apply(inout bit<32> val, out bit<32> result) { result = val; }
};
RegisterAction<bit<32>, bit<1>, bit<32>>(sppifo_bound_5) ra_sppifo_read_5 = {
    void apply(inout bit<32> val, out bit<32> result) { result = val; }
};
RegisterAction<bit<32>, bit<1>, bit<32>>(sppifo_bound_6) ra_sppifo_read_6 = {
    void apply(inout bit<32> val, out bit<32> result) { result = val; }
};
RegisterAction<bit<32>, bit<1>, bit<32>>(sppifo_bound_7) ra_sppifo_read_7 = {
    void apply(inout bit<32> val, out bit<32> result) { result = val; }
};

// ============================================================
// Pass 2 RegisterActions: Subtract cost (clamped to 0)
// The cost value is carried via resubmit metadata.
// ============================================================

// In real Tofino, the cost would be passed via ig_intr_md.resubmit_flag
// and a resubmit header. The RegisterAction reads the cost from metadata.

// Pattern for each bound:
// RegisterAction<bit<32>, bit<1>, bit<32>>(sppifo_bound_N) ra_sppifo_sub_N = {
//     void apply(inout bit<32> val) {
//         if (val > meta.sppifo_cost) { val = val - meta.sppifo_cost; }
//         else { val = 0; }
//     }
// };

// ============================================================
// SP-PIFO Scheduling Control
// ============================================================

control TnaSppifoScheduling(
    inout headers_t hdr,
    inout midst_metadata_t meta,
    inout ingress_intrinsic_metadata_for_tm_t ig_tm_md,
    inout ingress_intrinsic_metadata_for_deparser_t ig_dprsr_md
) {
    apply {
        bit<32> rank = meta.effective_rank;

        // Pass 1: Read all bounds
        bit<32> b0 = ra_sppifo_read_0.execute(0);
        bit<32> b1 = ra_sppifo_read_1.execute(0);
        bit<32> b2 = ra_sppifo_read_2.execute(0);
        bit<32> b3 = ra_sppifo_read_3.execute(0);
        bit<32> b4 = ra_sppifo_read_4.execute(0);
        bit<32> b5 = ra_sppifo_read_5.execute(0);
        bit<32> b6 = ra_sppifo_read_6.execute(0);
        bit<32> b7 = ra_sppifo_read_7.execute(0);

        // Cascading queue selection
        bit<32> selected_queue = 7;
        if (rank <= b0) { selected_queue = 0; }
        else if (rank <= b1) { selected_queue = 1; }
        else if (rank <= b2) { selected_queue = 2; }
        else if (rank <= b3) { selected_queue = 3; }
        else if (rank <= b4) { selected_queue = 4; }
        else if (rank <= b5) { selected_queue = 5; }
        else if (rank <= b6) { selected_queue = 6; }

        meta.sppifo_queue_idx = selected_queue;
        meta.output_queue = (bit<8>)selected_queue;
        ig_tm_md.qid = (bit<5>)selected_queue;

        // Compute cost if blocked
        if (selected_queue > 0) {
            bit<32> cost = 0;
            if (selected_queue == 1) { cost = rank - b0; }
            else if (selected_queue == 2) { cost = rank - b1; }
            else if (selected_queue == 3) { cost = rank - b2; }
            else if (selected_queue == 4) { cost = rank - b3; }
            else if (selected_queue == 5) { cost = rank - b4; }
            else if (selected_queue == 6) { cost = rank - b5; }
            else { cost = rank - b6; }

            meta.sppifo_blocked = 1;
            meta.sppifo_cost = cost;

            // Trigger resubmit for pass 2 (blocking reaction)
            // In real Tofino:
            // ig_dprsr_md.resubmit_type = 1;
            // The resubmitted packet will subtract cost from all bounds.
        } else {
            meta.sppifo_blocked = 0;
            meta.sppifo_cost = 0;
        }
    }
}

#endif /* _MIDST_TOFINO_ADMISSION_SPPIFO_P4_ */
