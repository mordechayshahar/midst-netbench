# MIDST P4 - Microburst Identification with Delta-Sketch Technology

P4_16 implementation of the MIDST system for BMv2 (behavioral model), with a Python control plane. Part of the MSc thesis hardware validation chapter.

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                    INGRESS PIPELINE                  │
│                                                     │
│  Parse → VQ Arrival → Monitoring → Sin Update       │
│       → Bursty Lookup → Rank Inflation → VFL        │
│       → Admission (AIFO/PACKS) → Forward/Drop       │
└────────────────────────┬────────────────────────────┘
                         │ sketch_meta header
┌────────────────────────▼────────────────────────────┐
│                    EGRESS PIPELINE                   │
│                                                     │
│  VQ Departure → Sout Update → Monitor Stop Check    │
│              → Strip sketch_meta → Emit             │
└─────────────────────────────────────────────────────┘
                         ▲
┌────────────────────────┴────────────────────────────┐
│               PYTHON CONTROL PLANE                   │
│                                                     │
│  Digest listener → Window analysis → Bursty flags   │
│  AIFO quantile → Stats logging → Health monitoring  │
└─────────────────────────────────────────────────────┘
```

## Key Components

| Component | File | Description |
|-----------|------|-------------|
| Virtual Queue | `p4src/includes/virtual_queue.p4` | Tracks arrival-departure balance, watermark crossings |
| Monitoring | `p4src/includes/monitoring.p4` | IDLE/MONITORING state machine, digest messages |
| Sketches | `p4src/includes/sketches.p4` | Count-Min Sin/Sout registers, dirty flags |
| Rank Inflation | `p4src/includes/rank_inflation.p4` | Bursty flag lookup, rank penalty, VFL |
| AIFO | `p4src/includes/admission_aifo.p4` | Fill threshold + quantile admission |
| PACKS | `p4src/includes/admission_packs.p4` | Multi-queue selection + admission |
| Statistics | `p4src/includes/statistics.p4` | 14 counter registers |
| Controller | `controller/midst_controller.py` | Main control loop |
| Analyzer | `controller/window_analyzer.py` | Sketch analysis, bursty detection, adaptive threshold (φ) |

## Quick Start

```bash
# Compile (base, no admission control)
make compile

# Compile with AIFO
make compile-aifo

# Compile with PACKS
make compile-packs

# Run tests (no switch required)
make test

# Run BMv2 switch
make run

# Run control plane (separate terminal)
make controller

# Start Mininet topology (requires sudo)
make topology
```

## Feature Flags

Features are enabled via P4 preprocessor flags:

| Flag | Description |
|------|-------------|
| `MIDST_ENABLE_AIFO` | AIFO admission control |
| `MIDST_ENABLE_PACKS` | PACKS multi-queue admission |
| `MIDST_ENABLE_STATISTICS` | Counter registers for monitoring |
| `MIDST_PRIORITY_SRPT` | SRPT priority: rank from `ipv4.identification` (host encodes remaining bytes) |

## Design Decisions

1. **BMv2 v1model** target for development and testing
2. **Sketch header** (22-byte, ethertype 0x4D53) carries hash indices ingress→egress, stripped before output
3. **Feature flags** via `#ifdef` for compile-time enable/disable
4. **Control plane via Thrift** for register read/write
5. **Drop path VQ decrement**: dropped packets MUST decrement VQ to prevent phantom inflation (Phase 9 fix — without this, PACKS ~80% drop rate causes VQ to ramp to infinity)
6. **Adaptive bursty threshold** (φ parameter) in control plane only — reduces false positives without any data-plane change
7. **Partial SRPT** is host-side only — traffic generators encode remaining bytes in the IPv4 identification field; the P4 data plane simply reads it

## Testing

```bash
make test          # all tests
make test-vq       # virtual queue
make test-sketch   # sketch operations
make test-admission # AIFO/PACKS
make test-e2e      # full lifecycle
```

## References

- Java reference implementation: `netbench-thesis/src/main/java/.../midst_feedback/`
- Technical reference: `netbench-thesis/MIDST_P4_TECHNICAL_REFERENCE.md`
