/* MIDST Shared Headers and Metadata - Target-Independent
 * Contains all header/metadata structs used by both BMv2 and Tofino.
 *
 * NOTE: Parser and deparser are target-specific and live in the
 * respective target directories (bmv2/includes/headers.p4 or
 * tofino/includes/headers.p4).
 */

#ifndef _MIDST_COMMON_HEADERS_P4_
#define _MIDST_COMMON_HEADERS_P4_

#include "constants.p4"

// ============================================================
// Standard Headers
// ============================================================

header ethernet_h {
    bit<48> dst_addr;
    bit<48> src_addr;
    bit<16> ethertype;
}

header ipv4_h {
    bit<4>  version;
    bit<4>  ihl;
    bit<6>  dscp;
    bit<2>  ecn;
    bit<16> total_len;
    bit<16> identification;
    bit<3>  flags;
    bit<13> frag_offset;
    bit<8>  ttl;
    bit<8>  protocol;
    bit<16> checksum;
    bit<32> src_addr;
    bit<32> dst_addr;
}

header tcp_h {
    bit<16> src_port;
    bit<16> dst_port;
    bit<32> seq_no;
    bit<32> ack_no;
    bit<4>  data_offset;
    bit<4>  res;
    bit<8>  flags;
    bit<16> window;
    bit<16> checksum;
    bit<16> urgent_ptr;
}

header udp_h {
    bit<16> src_port;
    bit<16> dst_port;
    bit<16> length;
    bit<16> checksum;
}

// ============================================================
// MIDST Internal Header (inserted between Ethernet and IP)
// Carries hash indices from ingress to egress
// Ethertype: 0x4D53 ("MS")
// ============================================================

header sketch_meta_h {
    bit<32> flow_id;       // 5-tuple hash for flow identification
    bit<32> hash_idx_0;    // Flat index into sketch row 0
    bit<32> hash_idx_1;    // Flat index into sketch row 1
    bit<32> hash_idx_2;    // Flat index into sketch row 2
    bit<32> hash_idx_3;    // Flat index into sketch row 3
    bit<1>  sketch_valid;  // 1 if sketch was updated in ingress
    bit<7>  _pad;
    // Total: 4+4+4+4+4+1 = 21 bytes + 7 pad bits = 22 bytes
}

// ============================================================
// Pipeline Metadata
// ============================================================

struct midst_metadata_t {
    // Flow identification
    bit<32> flow_id;
    bit<64> packet_size_bits;

    // Virtual queue state
    bit<64> vq_depth;          // current VQ depth after update
    bit<1>  crossed_high_wm;   // VQ crossed high watermark (up)
    bit<1>  crossed_low_wm;    // VQ crossed low watermark (down)

    // Monitoring
    bit<8>  monitoring_state;  // IDLE or MONITORING
    bit<1>  window_started;    // window just started this packet
    bit<1>  window_ended;      // window just ended this packet

    // Sketch hash indices (computed once, used in ingress + stored in header)
    bit<32> hash_idx_0;
    bit<32> hash_idx_1;
    bit<32> hash_idx_2;
    bit<32> hash_idx_3;

    // Bursty flow state
    bit<1>  is_bursty;
    bit<32> base_rank;
    bit<32> effective_rank;

    // Virtual Fill Level
    bit<32> effective_queue_size;

    // Admission control
    bit<1>  should_drop;
    bit<8>  output_queue;      // PACKS/SP-PIFO queue index

    // AIFO sliding window (data-plane quantile)
    bit<32> aifo_quantile_count;  // number of window slots with rank > incoming
    bit<32> aifo_tail_ptr;        // circular buffer write pointer
    bit<32> aifo_queue_size;      // current queue occupancy for AIFO formula

    // SP-PIFO state
    bit<32> sppifo_queue_idx;     // selected queue from cascading check
    bit<32> sppifo_cost;          // blocking reaction cost
    bit<1>  sppifo_blocked;       // packet was pushed to worse queue

    // Counters (scratch)
    bit<32> tmp_counter;

    // Digest data (populated in ingress, sent to control plane)
    bit<32> digest_type;
    bit<32> digest_window_number;
}

// ============================================================
// Header Struct
// ============================================================

// ============================================================
// Digest struct for control plane notification
// Used by both BMv2 (digest<T>) and Tofino (Digest<T>)
// ============================================================

struct window_digest_t {
    bit<32> digest_type;     // WINDOW_START=1, WINDOW_END=2
    bit<32> window_number;
    bit<64> vq_depth;
}

// ============================================================
// Header Struct
// ============================================================

struct headers_t {
    ethernet_h    ethernet;
    sketch_meta_h sketch_meta;    // internal, stripped before egress
    ipv4_h        ipv4;
    tcp_h         tcp;
    udp_h         udp;
}

#endif /* _MIDST_COMMON_HEADERS_P4_ */
