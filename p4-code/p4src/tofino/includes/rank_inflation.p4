/* Rank Inflation & Bursty Flow Tracking - Tofino TNA
 * Uses RegisterAction for bursty_flags lookup.
 */

#ifndef _MIDST_TOFINO_RANK_INFLATION_P4_
#define _MIDST_TOFINO_RANK_INFLATION_P4_

Register<bit<1>, bit<32>>(BURSTY_FLOW_TABLE_SIZE, 0) bursty_flags;

Hash<bit<32>>(HashAlgorithm_t.CRC16) bursty_hash;

RegisterAction<bit<1>, bit<32>, bit<1>>(bursty_flags) ra_bursty_read = {
    void apply(inout bit<1> val, out bit<1> result) {
        result = val;
    }
};

control TnaBurstyLookup(
    inout headers_t hdr,
    inout midst_metadata_t meta
) {
    apply {
        bit<32> flow_idx = bursty_hash.get({meta.flow_id});
        flow_idx = flow_idx % BURSTY_FLOW_TABLE_SIZE;

        meta.is_bursty = ra_bursty_read.execute(flow_idx);

#ifdef MIDST_PRIORITY_SRPT
        meta.base_rank = (bit<32>)hdr.ipv4.identification;
#else
        meta.base_rank = (bit<32>)hdr.ipv4.dscp;
#endif
    }
}

control TnaRankInflation(
    inout headers_t hdr,
    inout midst_metadata_t meta
) {
    apply {
        if (meta.is_bursty == 1) {
            meta.effective_rank = meta.base_rank + RANK_PENALTY;
        } else {
            meta.effective_rank = meta.base_rank;
        }

        if (meta.is_bursty == 1) {
            meta.effective_queue_size = (bit<32>)(meta.vq_depth >> 14);
        } else {
            meta.effective_queue_size = 0; // Tofino: use queueing metadata
        }
    }
}

#endif /* _MIDST_TOFINO_RANK_INFLATION_P4_ */
