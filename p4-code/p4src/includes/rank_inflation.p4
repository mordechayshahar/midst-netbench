/* Rank Inflation & Bursty Flow Tracking
 *
 * The control plane writes bursty_flags after each monitoring window.
 * Data plane reads these flags to:
 *   1. Inflate the rank of bursty flows (add RANK_PENALTY)
 *   2. Compute Virtual Fill Level (VFL): bursty flows see VQ depth
 *      as their effective queue size, creating a two-level admission gate.
 *
 * Flow ID is mapped to BURSTY_FLOW_TABLE_SIZE via hash for register indexing.
 */

#ifndef _MIDST_RANK_INFLATION_P4_
#define _MIDST_RANK_INFLATION_P4_

// ============================================================
// Bursty Flow Register (written by control plane)
// ============================================================

register<bit<1>>(BURSTY_FLOW_TABLE_SIZE) bursty_flags;

// ============================================================
// Bursty Lookup - check if current flow is marked bursty
// ============================================================

control BurstyLookup(
    inout headers_t hdr,
    inout midst_metadata_t meta,
    inout standard_metadata_t standard_metadata
) {
    apply {
        // Use CRC16 hash to map flow_id to bursty_flags index
        // (HashAlgorithm.identity does NOT exist in P4_16)
        bit<32> flow_idx;
        hash(flow_idx, HashAlgorithm.crc16, 32w0,
             { meta.flow_id }, BURSTY_FLOW_TABLE_SIZE);

        bit<1> is_bursty;
        bursty_flags.read(is_bursty, flow_idx);
        meta.is_bursty = is_bursty;

        // Base rank: source depends on priority mode
        // Safe: we only reach here if ipv4.isValid() (checked in midst_main.p4)
#ifdef MIDST_PRIORITY_SRPT
        // SRPT mode: rank = remaining flow bytes, encoded in identification field
        // (16 bits, 0-65535). Traffic generators encode min(remaining >> 10, 0xFFFF).
        meta.base_rank = (bit<32>)hdr.ipv4.identification;
#else
        // Default: rank from DSCP field (6 bits, 0-63)
        meta.base_rank = (bit<32>)hdr.ipv4.dscp;
#endif
    }
}

// ============================================================
// Rank Inflation - apply penalty to bursty flows
// ============================================================

control RankInflation(
    inout headers_t hdr,
    inout midst_metadata_t meta,
    inout standard_metadata_t standard_metadata
) {
    apply {
        // Apply rank penalty if bursty
        if (meta.is_bursty == 1) {
            meta.effective_rank = meta.base_rank + RANK_PENALTY;
        } else {
            meta.effective_rank = meta.base_rank;
        }

        // Compute Virtual Fill Level (VFL)
        // Bursty flows see VQ depth as their effective queue occupancy.
        // Normal flows see actual queue occupancy.
        if (meta.is_bursty == 1) {
            // VQ depth in packets: approximate by dividing bits by (MTU*8)
            // 12000 bits per packet. Using >> 14 approximates / 16384
            // which is close enough for admission thresholding.
            meta.effective_queue_size = (bit<32>)(meta.vq_depth >> 14);
        } else {
            // Use BMv2 dequeue depth (available as 19-bit value in v1model).
            // NOTE: In ingress, enq_qdepth reflects queue occupancy at
            // the time of enqueue. This is approximate but standard.
            meta.effective_queue_size = (bit<32>)standard_metadata.enq_qdepth;
        }
    }
}

#endif /* _MIDST_RANK_INFLATION_P4_ */
