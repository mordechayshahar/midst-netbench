/* AIFO Admission Control with Data-Plane Sliding Window Quantile
 *
 * Based on SIGCOMM'21 AIFO paper (netx-repo/AIFO).
 *
 * Instead of relying on control-plane quantile computation, this
 * implementation uses a 20-slot sliding window in the data plane:
 *
 * 1. 20 registers store recent packet ranks in a circular buffer
 * 2. Per-packet: write rank at window[tail], increment tail mod 20
 * 3. Count slots where stored_rank > incoming_rank -> quantile_count
 * 4. Admission formula (from paper):
 *    (C - K*C) * quantile_count <= (C - queue_size) * WINDOW_SIZE
 *    where C = max queue capacity, K = admission parameter
 *
 * BMv2 approach: 20 individual registers + cascading if comparisons.
 * Verbose but correct for the software switch target.
 */

#ifndef _MIDST_ADMISSION_AIFO_P4_
#define _MIDST_ADMISSION_AIFO_P4_

// ============================================================
// AIFO Sliding Window Registers (20 slots)
// ============================================================

register<bit<32>>(1) aifo_window_0;
register<bit<32>>(1) aifo_window_1;
register<bit<32>>(1) aifo_window_2;
register<bit<32>>(1) aifo_window_3;
register<bit<32>>(1) aifo_window_4;
register<bit<32>>(1) aifo_window_5;
register<bit<32>>(1) aifo_window_6;
register<bit<32>>(1) aifo_window_7;
register<bit<32>>(1) aifo_window_8;
register<bit<32>>(1) aifo_window_9;
register<bit<32>>(1) aifo_window_10;
register<bit<32>>(1) aifo_window_11;
register<bit<32>>(1) aifo_window_12;
register<bit<32>>(1) aifo_window_13;
register<bit<32>>(1) aifo_window_14;
register<bit<32>>(1) aifo_window_15;
register<bit<32>>(1) aifo_window_16;
register<bit<32>>(1) aifo_window_17;
register<bit<32>>(1) aifo_window_18;
register<bit<32>>(1) aifo_window_19;

register<bit<32>>(1) aifo_tail_ptr;          // circular buffer write pointer (0-19)
register<bit<32>>(1) aifo_queue_size;        // current queue occupancy (packets)
register<bit<32>>(1) aifo_admit_count;       // total admits
register<bit<32>>(1) aifo_reject_count;      // total rejects

// ============================================================
// AIFO Admission Control with Sliding Window
// ============================================================

control AifoAdmission(
    inout headers_t hdr,
    inout midst_metadata_t meta,
    inout standard_metadata_t standard_metadata
) {
    apply {
        // Read current queue size
        bit<32> queue_size;
        aifo_queue_size.read(queue_size, 0);
        meta.aifo_queue_size = queue_size;

        // Use VFL for bursty flows, actual queue for normal
        bit<32> fill_level = meta.effective_queue_size;

        // Level 1: Fill threshold check (same as original)
        // If fill level below k * max_size, always admit
        if (fill_level < AIFO_THRESHOLD_PKTS) {
            meta.should_drop = 0;
        } else {
            // ---- Sliding Window Quantile Computation ----
            // Read tail pointer
            bit<32> tail;
            aifo_tail_ptr.read(tail, 0);
            meta.aifo_tail_ptr = tail;

            bit<32> incoming_rank = meta.effective_rank;
            bit<32> quantile_count = 0;
            bit<32> stored_rank;

            // Read each window slot. If tail == slot_index, write incoming_rank.
            // Count how many stored ranks are strictly greater than incoming.

            // Slot 0
            aifo_window_0.read(stored_rank, 0);
            if (tail == 0) { aifo_window_0.write(0, incoming_rank); }
            if (stored_rank > incoming_rank) { quantile_count = quantile_count + 1; }

            // Slot 1
            aifo_window_1.read(stored_rank, 0);
            if (tail == 1) { aifo_window_1.write(0, incoming_rank); }
            if (stored_rank > incoming_rank) { quantile_count = quantile_count + 1; }

            // Slot 2
            aifo_window_2.read(stored_rank, 0);
            if (tail == 2) { aifo_window_2.write(0, incoming_rank); }
            if (stored_rank > incoming_rank) { quantile_count = quantile_count + 1; }

            // Slot 3
            aifo_window_3.read(stored_rank, 0);
            if (tail == 3) { aifo_window_3.write(0, incoming_rank); }
            if (stored_rank > incoming_rank) { quantile_count = quantile_count + 1; }

            // Slot 4
            aifo_window_4.read(stored_rank, 0);
            if (tail == 4) { aifo_window_4.write(0, incoming_rank); }
            if (stored_rank > incoming_rank) { quantile_count = quantile_count + 1; }

            // Slot 5
            aifo_window_5.read(stored_rank, 0);
            if (tail == 5) { aifo_window_5.write(0, incoming_rank); }
            if (stored_rank > incoming_rank) { quantile_count = quantile_count + 1; }

            // Slot 6
            aifo_window_6.read(stored_rank, 0);
            if (tail == 6) { aifo_window_6.write(0, incoming_rank); }
            if (stored_rank > incoming_rank) { quantile_count = quantile_count + 1; }

            // Slot 7
            aifo_window_7.read(stored_rank, 0);
            if (tail == 7) { aifo_window_7.write(0, incoming_rank); }
            if (stored_rank > incoming_rank) { quantile_count = quantile_count + 1; }

            // Slot 8
            aifo_window_8.read(stored_rank, 0);
            if (tail == 8) { aifo_window_8.write(0, incoming_rank); }
            if (stored_rank > incoming_rank) { quantile_count = quantile_count + 1; }

            // Slot 9
            aifo_window_9.read(stored_rank, 0);
            if (tail == 9) { aifo_window_9.write(0, incoming_rank); }
            if (stored_rank > incoming_rank) { quantile_count = quantile_count + 1; }

            // Slot 10
            aifo_window_10.read(stored_rank, 0);
            if (tail == 10) { aifo_window_10.write(0, incoming_rank); }
            if (stored_rank > incoming_rank) { quantile_count = quantile_count + 1; }

            // Slot 11
            aifo_window_11.read(stored_rank, 0);
            if (tail == 11) { aifo_window_11.write(0, incoming_rank); }
            if (stored_rank > incoming_rank) { quantile_count = quantile_count + 1; }

            // Slot 12
            aifo_window_12.read(stored_rank, 0);
            if (tail == 12) { aifo_window_12.write(0, incoming_rank); }
            if (stored_rank > incoming_rank) { quantile_count = quantile_count + 1; }

            // Slot 13
            aifo_window_13.read(stored_rank, 0);
            if (tail == 13) { aifo_window_13.write(0, incoming_rank); }
            if (stored_rank > incoming_rank) { quantile_count = quantile_count + 1; }

            // Slot 14
            aifo_window_14.read(stored_rank, 0);
            if (tail == 14) { aifo_window_14.write(0, incoming_rank); }
            if (stored_rank > incoming_rank) { quantile_count = quantile_count + 1; }

            // Slot 15
            aifo_window_15.read(stored_rank, 0);
            if (tail == 15) { aifo_window_15.write(0, incoming_rank); }
            if (stored_rank > incoming_rank) { quantile_count = quantile_count + 1; }

            // Slot 16
            aifo_window_16.read(stored_rank, 0);
            if (tail == 16) { aifo_window_16.write(0, incoming_rank); }
            if (stored_rank > incoming_rank) { quantile_count = quantile_count + 1; }

            // Slot 17
            aifo_window_17.read(stored_rank, 0);
            if (tail == 17) { aifo_window_17.write(0, incoming_rank); }
            if (stored_rank > incoming_rank) { quantile_count = quantile_count + 1; }

            // Slot 18
            aifo_window_18.read(stored_rank, 0);
            if (tail == 18) { aifo_window_18.write(0, incoming_rank); }
            if (stored_rank > incoming_rank) { quantile_count = quantile_count + 1; }

            // Slot 19
            aifo_window_19.read(stored_rank, 0);
            if (tail == 19) { aifo_window_19.write(0, incoming_rank); }
            if (stored_rank > incoming_rank) { quantile_count = quantile_count + 1; }

            meta.aifo_quantile_count = quantile_count;

            // Advance tail pointer: tail = (tail + 1) % 20
            bit<32> new_tail = tail + 1;
            if (new_tail >= AIFO_WINDOW_SIZE) {
                new_tail = 0;
            }
            aifo_tail_ptr.write(0, new_tail);

            // ---- Admission Decision (Paper Formula) ----
            // (C - K*C) * quantile_count <= (C - queue_size) * WINDOW_SIZE
            // Left side: AIFO_C_MINUS_KC * quantile_count
            // Right side: (MAX_QUEUE_SIZE_PKTS - queue_size) * AIFO_WINDOW_SIZE
            // If queue_size >= C, right side <= 0, so reject.

            if (queue_size >= MAX_QUEUE_SIZE_PKTS) {
                // Queue full -> reject
                meta.should_drop = 1;
            } else {
                bit<32> lhs = AIFO_C_MINUS_KC * quantile_count;
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
            // Admit: increment queue size
            bit<32> qs;
            aifo_queue_size.read(qs, 0);
            aifo_queue_size.write(0, qs + 1);

            bit<32> admits;
            aifo_admit_count.read(admits, 0);
            aifo_admit_count.write(0, admits + 1);
        } else {
            bit<32> rejects;
            aifo_reject_count.read(rejects, 0);
            aifo_reject_count.write(0, rejects + 1);
        }
    }
}

#endif /* _MIDST_ADMISSION_AIFO_P4_ */
