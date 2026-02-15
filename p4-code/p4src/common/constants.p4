/* MIDST Shared Constants - Target-Independent
 * Microburst Identification with Delta-Sketch Technology
 *
 * This file contains all #define constants and const values
 * shared between BMv2 and Tofino targets.
 */

#ifndef _MIDST_COMMON_CONSTANTS_P4_
#define _MIDST_COMMON_CONSTANTS_P4_

// ============================================================
// Feature Flags (compile-time enable/disable)
// Usage: p4c -DMIDST_ENABLE_AIFO ...
// ============================================================
// #define MIDST_ENABLE_AIFO
// #define MIDST_ENABLE_PACKS
// #define MIDST_ENABLE_SPPIFO
// #define MIDST_ENABLE_STATISTICS
// #define MIDST_PRIORITY_SRPT      // rank from ipv4.identification (SRPT remaining bytes)

// ============================================================
// Virtual Queue Parameters
// ============================================================
const bit<32> HIGH_WATERMARK_PACKETS = 5;
const bit<32> LOW_WATERMARK_PACKETS  = 2;
// In bits: HW = 5 * 1500 * 8 = 60000, LW = 2 * 1500 * 8 = 24000
const bit<64> HIGH_WATERMARK_BITS    = 60000;
const bit<64> LOW_WATERMARK_BITS     = 24000;
const bit<16> STANDARD_MTU_BYTES     = 1500;

// ============================================================
// Count-Min Sketch Parameters
// ============================================================
const bit<32> SKETCH_SIZE          = 256;    // columns per hash row
const bit<32> NUM_HASH_FUNCTIONS   = 4;      // hash rows
const bit<32> SKETCH_TOTAL_SIZE    = 1024;   // SKETCH_SIZE * NUM_HASH_FUNCTIONS

// Golden ratio seeds for CRC32 hash diversification
const bit<32> HASH_SEED_0 = 32w0x00000000;
const bit<32> HASH_SEED_1 = 32w0x9e3779b9;
const bit<32> HASH_SEED_2 = 32w0x3c6ef372;
const bit<32> HASH_SEED_3 = 32w0xdaa66d2b;

// ============================================================
// Bursty Flow Detection
// ============================================================
const bit<32> BURST_THRESHOLD      = 5;      // packets per window
const bit<32> BURSTY_FLOW_TABLE_SIZE = 256;  // flow_id hashed to this range
const bit<32> RANK_PENALTY         = 10;     // added to bursty flow ranks

// ============================================================
// AIFO Parameters
// ============================================================
const bit<32> AIFO_K_PERCENT       = 20;     // k=0.2 expressed as percentage
const bit<32> MAX_QUEUE_SIZE_PKTS  = 64;     // max queue occupancy in packets
const bit<32> AIFO_SAMPLE_WINDOW   = 64;     // quantile sampling window
// Threshold = k * max_queue = 0.2 * 64 = 12 packets
const bit<32> AIFO_THRESHOLD_PKTS  = 12;

// AIFO Sliding Window Parameters (data-plane quantile)
const bit<32> AIFO_WINDOW_SIZE     = 20;     // number of rank slots in sliding window
// Admission formula: (C - K*C) * quantile_count <= (C - queue_size) * WINDOW_SIZE
// C = MAX_QUEUE_SIZE_PKTS, K = AIFO_K_PERCENT/100
// (1-K)*C = (1-0.2)*64 = 51.2 -> use 52 (ceiling)
// Precomputed: (100 - AIFO_K_PERCENT) * MAX_QUEUE_SIZE_PKTS / 100
const bit<32> AIFO_C_MINUS_KC      = 52;     // ceil((1-0.2)*64)

// ============================================================
// PACKS Parameters
// ============================================================
const bit<32> NUM_QUEUES           = 8;      // priority queues
const bit<32> RANK_DELTA           = 20;     // rank spacing between queues
const bit<32> PACKS_K_PERCENT      = 20;     // admission k for PACKS
// Precomputed threshold = PACKS_K_PERCENT * MAX_QUEUE_SIZE_PKTS / 100 = 12
const bit<32> PACKS_THRESHOLD_PKTS = 12;

// ============================================================
// SP-PIFO Parameters
// ============================================================
const bit<32> SPPIFO_NUM_QUEUES    = 8;      // number of priority queues
// Initial bounds: evenly spaced across rank range [0, max_rank]
// For DSCP (0-63): bounds = [8, 16, 24, 32, 40, 48, 56, 63]
// For SRPT (0-65535): bounds = [8192, 16384, 24576, 32768, 40960, 49152, 57344, 65535]
// Written by controller at startup

// ============================================================
// Monitoring Window
// ============================================================
const bit<8>  WINDOW_STATE_IDLE       = 0;
const bit<8>  WINDOW_STATE_MONITORING = 1;

// ============================================================
// Digest / Clone Session IDs
// ============================================================
const bit<32> DIGEST_TYPE_WINDOW_START = 1;
const bit<32> DIGEST_TYPE_WINDOW_END   = 2;

// ============================================================
// EtherTypes
// ============================================================
const bit<16> ETHERTYPE_IPV4       = 0x0800;
const bit<16> ETHERTYPE_SKETCH_META = 0x4D53; // "MS" for MIDST Sketch

// ============================================================
// Clone/Mirror Session IDs (for drop path VQ fix)
// ============================================================
const bit<32> CLONE_SESSION_DROP   = 100;

#endif /* _MIDST_COMMON_CONSTANTS_P4_ */
