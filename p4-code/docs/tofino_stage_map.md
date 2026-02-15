# Tofino Stage Budget Analysis

## Overview

Tofino ASIC has **12 ingress stages** and **12 egress stages**. Each stage can
host a limited number of SRAM/TCAM resources (tables, registers, counters).
The key constraint is that each register can only be accessed **once per packet
per stage** via a RegisterAction.

## Ingress Stage Allocation

| Stage | Module | Registers | Notes |
|-------|--------|-----------|-------|
| 0 | VQ arrival | `vq_depth_bits`, `vq_peak_bits` | Atomic RMW for depth, peak update |
| 1 | Monitoring | `monitoring_state_reg`, `window_count_reg` | State transition + window increment |
| 2 | Sketch hash 0 | `sin_sketch[row0]`, `sin_dirty[row0]` | Hash<> + RegisterAction |
| 3 | Sketch hash 1 | `sin_sketch[row1]`, `sin_dirty[row1]` | Hash<> + RegisterAction |
| 4 | Sketch hash 2 | `sin_sketch[row2]`, `sin_dirty[row2]` | Hash<> + RegisterAction |
| 5 | Sketch hash 3 | `sin_sketch[row3]`, `sin_dirty[row3]` | Hash<> + RegisterAction |
| 6 | Bursty lookup | `bursty_flags` | Hash<CRC16> + RegisterAction read |
| 7 | AIFO window 0-4 | `aifo_window_0..4`, `aifo_tail_ptr` | 5 window slots + tail |
| 8 | AIFO window 5-9 | `aifo_window_5..9` | 5 window slots |
| 9 | AIFO window 10-14 | `aifo_window_10..14` | 5 window slots (if budget allows) |
| 10 | AIFO admission | `aifo_queue_size`, admit/reject counters | Formula computation |
| 11 | SP-PIFO bounds | `sppifo_bound_0..7` | Read bounds, cascading check |

**Total: 12 stages (exactly at budget)**

## Egress Stage Allocation

| Stage | Module | Registers | Notes |
|-------|--------|-----------|-------|
| 0 | VQ departure | `vq_depth_bits` | Atomic decrement |
| 1 | Sout hash 0 | `sout_sketch[row0]`, `sout_dirty[row0]` | |
| 2 | Sout hash 1 | `sout_sketch[row1]`, `sout_dirty[row1]` | |
| 3 | Sout hash 2 | `sout_sketch[row2]`, `sout_dirty[row2]` | |
| 4 | Sout hash 3 | `sout_sketch[row3]`, `sout_dirty[row3]` | |
| 5 | Monitor stop | `monitoring_state_reg`, `window_count_reg` | State transition |
| 6-11 | Statistics | Counter registers | Optional, 1 register per stage |

**Total: 6 stages required (well within budget)**

## Conflicts and Mitigations

### AIFO Window Size vs. Stage Budget

**Problem:** The full 20-slot AIFO window requires 20+ RegisterAction
instances. Even packing 5 per stage, this needs 4 stages (7-10).
With the rest of the pipeline, we're at exactly 12 stages.

**Mitigations:**
1. **Reduce window to 12 slots:** 3 stages instead of 4. Saves 1 stage.
   Impact: Slightly less accurate quantile, but still effective per paper's
   ablation study.
2. **Feature flag:** Use `#ifdef` to select AIFO *or* SP-PIFO (not both).
   This frees 1-2 stages when only one scheduler is active.
3. **Combine sketch dirty flags with counters:** Pack `sin_dirty` into the
   same register as `sin_sketch` using a wider RegisterAction (bit<33>).
   Saves 4 stages total.

### SP-PIFO Blocking Reaction

**Problem:** SP-PIFO blocking requires reading 8 bounds (pass 1) then
modifying all 8 (pass 2). Can't do both in one pass.

**Mitigation:** Use **resubmit**. Blocked packets are resubmitted with
cost in the resubmit header. Pass 2 only executes the subtract-cost
RegisterActions. Non-blocked packets (majority) pass through in 1 pass.

### AIFO + SP-PIFO Coexistence

**Problem:** If both AIFO and SP-PIFO are enabled simultaneously,
stages 7-11 are all consumed (AIFO window + SP-PIFO bounds).

**Mitigation:** Compile-time mutual exclusion:
```
#if defined(MIDST_ENABLE_AIFO) && defined(MIDST_ENABLE_SPPIFO)
#error "Cannot enable both AIFO and SP-PIFO simultaneously on Tofino"
#endif
```

Or reduce AIFO window to 8 slots (2 stages) to make room.

### Statistics Counters in Ingress

**Problem:** 14 stat registers need stages. In ingress, all 12 stages
are already allocated.

**Mitigation:** Statistics counters use the Tofino **Counter extern**
instead of Register, which doesn't consume pipeline stages. Counters
are read-only from the data plane and don't need RegisterAction.

## Recommended Configurations

### Config A: AIFO-focused (default)
- AIFO with 20-slot window (stages 7-10)
- No SP-PIFO
- 12 ingress stages used

### Config B: SP-PIFO-focused
- SP-PIFO with 8 bounds (stage 7-8)
- No AIFO
- 10 ingress stages used (room for statistics)

### Config C: Compact AIFO + SP-PIFO
- AIFO with 8-slot window (stages 7-8)
- SP-PIFO with 8 bounds (stages 9-10)
- 12 ingress stages used
- Reduced quantile accuracy

## Register-to-Stage Mapping Detail

```
Stage 0:  [vq_depth_bits]  [vq_peak_bits]
Stage 1:  [monitoring_state_reg]  [window_count_reg]
Stage 2:  [sin_sketch:0-255]  [sin_dirty:0-255]
Stage 3:  [sin_sketch:256-511]  [sin_dirty:256-511]
Stage 4:  [sin_sketch:512-767]  [sin_dirty:512-767]
Stage 5:  [sin_sketch:768-1023]  [sin_dirty:768-1023]
Stage 6:  [bursty_flags]  [l2_forward TCAM]
Stage 7:  [aifo_window_0..4]  [aifo_tail_ptr]
Stage 8:  [aifo_window_5..9]
Stage 9:  [aifo_window_10..14]
Stage 10: [aifo_window_15..19]  [aifo_queue_size]
Stage 11: [aifo_admit_count]  [aifo_reject_count]
```
