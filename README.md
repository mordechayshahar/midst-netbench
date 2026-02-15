# MIDST: Microburst Detection and Scheduler Integration for Datacenter Networks

**MSc Thesis — ETH Zurich, Networked Systems Group**

**Author:** Mordechay Shahar
**Supervisor:** Prof. Laurent Vanbever, Alon Kfir

## Abstract

MIDST integrates microburst detection with packet scheduling algorithms to improve flow completion times in datacenter networks. By detecting bursty flows via Count-Min Sketches and applying rank-based penalties, MIDST reduces tail latency across three scheduling disciplines:

| Scheduler | FCT Improvement (P99) |
|-----------|----------------------|
| SP-PIFO   | −23.2%               |
| AIFO      | −10.6%               |
| PACKS     | −6.9%                |

Results obtained on fat-tree (k=4) topology at 100% load.

## Repository Structure

```
├── src/                    # Java simulation (NetBench-based)
│   ├── main/java/.../
│   │   ├── core/           # Simulator framework
│   │   ├── ext/            # Basic networking components
│   │   └── xpt/
│   │       ├── ports/
│   │       │   ├── SPPIFO/         # SP-PIFO scheduler [NSDI'20]
│   │       │   ├── PIFO/           # PIFO scheduler [NSDI'20]
│   │       │   ├── FIFO/           # FIFO baseline [NSDI'20]
│   │       │   └── midst_feedback/ # MIDST integration
│   │       │       ├── core/       # MIDST detection + Virtual Queue
│   │       │       ├── interfaces/ # Queue monitoring contracts
│   │       │       ├── port/       # MIDSTCapableOutputPort
│   │       │       └── queue/impl/ # AIFO, PACKS, SP-PIFO queues
│   │       ├── newreno/            # TCP New Reno transport
│   │       └── tcpbase/            # TCP packet definitions
│   └── test/java/...       # Unit tests (60 files, ~300 tests)
├── p4-code/                # P4 data plane implementation
│   ├── p4src/
│   │   ├── common/         # Shared headers and constants
│   │   ├── includes/       # BMv2 (v1model) modules
│   │   ├── tofino/         # Tofino (TNA) modules
│   │   └── midst_main.p4   # Main pipeline
│   ├── controller/         # Control plane (Python)
│   └── tests/              # P4 model tests (pytest)
├── experiments/            # Experiment configurations (E44-E49)
├── analysis/               # Python analysis scripts
├── figures/                # Thesis figures and plots
└── pom.xml                 # Maven build (Java 8)
```

## Building

### Java Simulation

```bash
# Compile
mvn clean compile

# Run tests
mvn test

# Build executable JAR
mvn assembly:single
java -jar NetBench.jar experiments/E48_sppifo_baseline.properties
```

**Requirements:** Java 8+, Maven 3.x

### P4 Data Plane

```bash
cd p4-code

# Compile for BMv2 (specific scheduler)
make compile-aifo
make compile-packs
make compile-sppifo
make compile-full    # All schedulers

# Run tests
make test
```

**Requirements:** p4c compiler, BMv2, Python 3.8+ with pytest

## Key Contributions

1. **Virtual Queue** — Congestion trigger based on `totalArrivals − totalDepartures`, independent of admission control keeping real queues shallow
2. **Penalty/Demotion Feedback Loop** — Bursty flows get rank inflated, causing queue demotion and/or admission rejection
3. **Universal Architecture** — `MIDSTCapableOutputPort` works with any scheduler implementing `IMonitoredQueue`
4. **Virtual Fill Level (VFL)** — Two-level admission gate for stricter bursty flow control
5. **P4 Implementation** — Full data plane with BMv2 and Tofino support, conditional compile flags per scheduler

## Attribution

| Component | Source | Tag |
|-----------|--------|-----|
| NetBench simulator | [ndal-eth/netbench](https://github.com/ndal-eth/netbench) | ORIGINAL |
| SP-PIFO/PIFO/FIFO | [nsg-ethz/SP-PIFO](https://github.com/nsg-ethz/SP-PIFO) (NSDI'20) | ORIGINAL |
| AIFO admission | Zhong et al., NSDI 2021 | BASED-ON |
| MIDST detection | Kfir et al., ACM SOSR 2022 | BASED-ON |
| Feedback integration, VQ, P4 | This thesis | THESIS |

## Experiments

Six configurations on fat-tree k=4 (32 servers), 100% load:

| ID | Config | Description |
|----|--------|-------------|
| E44 | PACKS baseline | PACKS without MIDST |
| E45 | PACKS + MIDST | PACKS with feedback |
| E46 | AIFO baseline | AIFO without MIDST |
| E47 | AIFO + MIDST | AIFO with feedback |
| E48 | SP-PIFO baseline | SP-PIFO without MIDST |
| E49 | SP-PIFO + MIDST | SP-PIFO with feedback |

## Future Work

**Adaptive Detection Thresholds.** The current sketch-based detection uses fixed thresholds (`burst_threshold`, `high_watermark`, `adaptive_threshold_fraction`) that require per-topology calibration. Under PACKS, admission control keeps real queues shallow, making queue-depth triggers ineffective; under AIFO+SRPT, equalized per-flow traffic requires lowering the burst threshold from 100 to 20 packets. A self-tuning mechanism — for example, a relative burst threshold based on per-flow ingress percentiles, or penalty decay proportional to the fraction of flows classified as bursty — would eliminate manual tuning and improve portability across schedulers and topologies.

**Penalty Mechanism Decoupling for PACKS.** PACKS currently applies a single `rankPenalty` to both admission control and multi-queue assignment. This creates a binary outcome: bursty flows are either fully admitted (when real fill level is below the admission threshold) or fully starved (with VFL). Decoupling the penalty into independent admission and scheduling components — as SP-PIFO naturally achieves through queue demotion without an admission gate — would allow graduated penalization and avoid the overcorrection that causes PACKS to lose 1.3% of completed flows under MIDST.

**Graduated Penalty Escalation.** The current penalty is binary: a flow is either "bursty" (full demotion to the last queue) or "normal" (no penalty). A progressive escalation system would track per-flow offense history across monitoring windows — first-time offenders demoted one queue (Q0→Q1), repeat offenders demoted further (Q2), and only persistent offenders sent to the last queue (Q4). The penalty for offense level *n* would be `min(n, numQueues−1) × rankDelta`, with a decay mechanism that forgives flows not flagged in subsequent windows. This provides proportional response: transiently bursty flows receive gentler treatment while persistent heavy hitters are fully penalized. The trade-off is slower reaction to genuine persistent offenders (3 windows vs 1) and one additional tuning parameter (decay rate). In the P4 data plane, this requires one register array for offense counts and a match-action table mapping offense levels to penalty values — feasible within Tofino stage constraints.

**Topological Scaling.** MIDST is aggregate-neutral on simple 2-node topologies (0.1–0.5% of flows detected) but achieves significant improvement on fat-tree k=4 (12–22% recall, up to −23.2% P50). Evaluating on larger topologies (k=8, k=16) and production-like traffic matrices (e.g., with rack-local affinity and hot-spot patterns) would characterize how MIDST's value scales with fan-in degree and path diversity.

**Sketch Rotation Tuning.** The sketch rotation mechanism (analyze + reset every `sketchSize × 4` packets) prevents count-min sketch saturation during sustained congestion. The current rotation interval is conservative; shorter rotations would improve detection freshness but risk missing slow-building bursts. Exploring the trade-off between rotation frequency, sketch dimensions, and detection accuracy — particularly under varying congestion durations — is an open question.

**Tofino Hardware Validation.** The P4 implementation includes both BMv2 (v1model) and Tofino (TNA) targets with conditional compilation per scheduler. While the BMv2 version passes all functional tests, the Tofino pipeline has not been validated on physical hardware. Key concerns include stage allocation (the current design targets 12 stages), register access patterns for the count-min sketch, and recirculation costs for sketch rotation.

**Per-Flow Fairness Analysis.** Aggregate FCT improvements mask MIDST's per-flow behavior. In E49 (SP-PIFO), non-bursty flows see a 29.5% P50 reduction while bursty flows see 13.7% — the bursty penalty ratio widens from 1.36× to 1.67×. A deeper analysis of per-flow fairness, including Jain's fairness index and flow-size-conditioned CDF comparisons, would better quantify MIDST's fairness properties.

**Integration with SRPT.** When SRPT priorities are available, all schedulers already achieve near-optimal flow ordering, and MIDST becomes aggregate-neutral. Exploring hybrid strategies — where MIDST activates only when SRPT information is unavailable (e.g., encrypted traffic where flow sizes are unknown) — would extend MIDST's applicability to mixed-knowledge environments.

## License

This project builds upon NetBench (MIT) and SP-PIFO. See individual source files for licensing details.
