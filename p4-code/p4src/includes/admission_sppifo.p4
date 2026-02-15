/* SP-PIFO: Shortest-Path Push-In First-Out Scheduling
 *
 * Based on NSDI'20 SP-PIFO paper (nsg-ethz/SP-PIFO).
 *
 * SP-PIFO approximates a PIFO using a fixed number of FIFO queues
 * with dynamic bounds that adapt to the rank distribution:
 *
 * 1. 8 queue_bound registers define upper rank limits for each queue
 * 2. Cascading check: if rank <= bound_i -> assign to queue i
 * 3. Blocking reaction: when a packet is pushed to queue i instead
 *    of queue i-1 (the ideal queue), compute cost = rank - bound_{i-1},
 *    then subtract cost from ALL bounds to adapt
 * 4. Bounds are clamped to >= 0
 *
 * SP-PIFO is a pure scheduling mechanism (no admission gating).
 * It determines which queue a packet goes to, and can work
 * alongside AIFO/PACKS admission control or independently.
 *
 * Feature-gated with #ifdef MIDST_ENABLE_SPPIFO
 */

#ifndef _MIDST_ADMISSION_SPPIFO_P4_
#define _MIDST_ADMISSION_SPPIFO_P4_

// ============================================================
// SP-PIFO Queue Bound Registers (8 queues)
// Written by controller at startup, updated by blocking reaction
// ============================================================

register<bit<32>>(1) sppifo_bound_0;
register<bit<32>>(1) sppifo_bound_1;
register<bit<32>>(1) sppifo_bound_2;
register<bit<32>>(1) sppifo_bound_3;
register<bit<32>>(1) sppifo_bound_4;
register<bit<32>>(1) sppifo_bound_5;
register<bit<32>>(1) sppifo_bound_6;
register<bit<32>>(1) sppifo_bound_7;

// ============================================================
// SP-PIFO Queue Selection + Blocking Reaction
// ============================================================

control SppifoScheduling(
    inout headers_t hdr,
    inout midst_metadata_t meta,
    inout standard_metadata_t standard_metadata
) {
    apply {
        bit<32> rank = meta.effective_rank;

        // Read all 8 bounds
        bit<32> b0; sppifo_bound_0.read(b0, 0);
        bit<32> b1; sppifo_bound_1.read(b1, 0);
        bit<32> b2; sppifo_bound_2.read(b2, 0);
        bit<32> b3; sppifo_bound_3.read(b3, 0);
        bit<32> b4; sppifo_bound_4.read(b4, 0);
        bit<32> b5; sppifo_bound_5.read(b5, 0);
        bit<32> b6; sppifo_bound_6.read(b6, 0);
        bit<32> b7; sppifo_bound_7.read(b7, 0);

        // ---- Cascading Queue Selection ----
        // Find the first queue whose bound >= rank
        bit<32> selected_queue = 7;  // default: last queue
        bit<32> ideal_queue = 0;     // the queue the packet "wants" to be in
        bit<1>  is_blocked = 0;
        bit<32> cost = 0;

        if (rank <= b0) {
            selected_queue = 0;
        } else if (rank <= b1) {
            selected_queue = 1;
        } else if (rank <= b2) {
            selected_queue = 2;
        } else if (rank <= b3) {
            selected_queue = 3;
        } else if (rank <= b4) {
            selected_queue = 4;
        } else if (rank <= b5) {
            selected_queue = 5;
        } else if (rank <= b6) {
            selected_queue = 6;
        }
        // else: selected_queue stays 7

        meta.sppifo_queue_idx = selected_queue;
        meta.output_queue = (bit<8>)selected_queue;

        // Set BMv2 output queue
        if (selected_queue < 8) {
            standard_metadata.priority = (bit<3>)selected_queue;
        }

        // ---- Blocking Reaction ----
        // Determine the ideal queue: the queue the packet would go to
        // if bounds perfectly matched. The ideal queue for a packet
        // is the one just below where it ended up.
        // A packet is "blocked" if it goes to a higher-numbered queue
        // than queue 0 and there was room in a lower queue.
        //
        // Cost = rank - bound_{selected_queue - 1}
        // Subtract cost from ALL bounds to adapt.

        if (selected_queue > 0) {
            // The packet was pushed beyond queue 0. Compute cost as
            // the distance from the bound of the queue just below.
            if (selected_queue == 1) {
                cost = rank - b0;
            } else if (selected_queue == 2) {
                cost = rank - b1;
            } else if (selected_queue == 3) {
                cost = rank - b2;
            } else if (selected_queue == 4) {
                cost = rank - b3;
            } else if (selected_queue == 5) {
                cost = rank - b4;
            } else if (selected_queue == 6) {
                cost = rank - b5;
            } else {
                cost = rank - b6;
            }

            is_blocked = 1;
            meta.sppifo_blocked = 1;
            meta.sppifo_cost = cost;

            // Subtract cost from all bounds (clamp to 0)
            if (b0 > cost) { b0 = b0 - cost; } else { b0 = 0; }
            if (b1 > cost) { b1 = b1 - cost; } else { b1 = 0; }
            if (b2 > cost) { b2 = b2 - cost; } else { b2 = 0; }
            if (b3 > cost) { b3 = b3 - cost; } else { b3 = 0; }
            if (b4 > cost) { b4 = b4 - cost; } else { b4 = 0; }
            if (b5 > cost) { b5 = b5 - cost; } else { b5 = 0; }
            if (b6 > cost) { b6 = b6 - cost; } else { b6 = 0; }
            if (b7 > cost) { b7 = b7 - cost; } else { b7 = 0; }

            // Write back updated bounds
            sppifo_bound_0.write(0, b0);
            sppifo_bound_1.write(0, b1);
            sppifo_bound_2.write(0, b2);
            sppifo_bound_3.write(0, b3);
            sppifo_bound_4.write(0, b4);
            sppifo_bound_5.write(0, b5);
            sppifo_bound_6.write(0, b6);
            sppifo_bound_7.write(0, b7);
        } else {
            meta.sppifo_blocked = 0;
            meta.sppifo_cost = 0;
        }
    }
}

#endif /* _MIDST_ADMISSION_SPPIFO_P4_ */
