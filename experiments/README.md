# Experiment Configurations

Fat-Tree Topology Experiments (E44-E49) - Non-SRPT Baseline

## Overview

These are the six key experiments comparing baseline schedulers vs MIDST-enhanced variants on a fat-tree (k=4) topology at 100% load without SRPT overhead.

### Experiment Configurations

- **E44_packs_baseline.properties** - PACKS baseline scheduler, non-SRPT
- **E45_packs_midst.properties** - PACKS with MIDST enhancement, non-SRPT
- **E46_aifo_baseline.properties** - AIFO baseline scheduler, non-SRPT
- **E47_aifo_midst.properties** - AIFO with MIDST enhancement, non-SRPT
- **E48_sppifo_baseline.properties** - SP-PIFO baseline scheduler, non-SRPT
- **E49_sppifo_midst.properties** - SP-PIFO with MIDST enhancement, non-SRPT

### Topology

- **fat_tree_k4.topology** - Fat-tree datacenter topology with k=4 (16 servers, 8 switches)

## Running Experiments

Each configuration file specifies the full experimental setup including:
- Topology file path
- Scheduler type and MIDST parameters
- Load characteristics (100% traffic)
- Traffic flow sizes and distributions
- Simulation duration

To run an experiment:
```bash
java -jar simulator.jar --config E44_packs_baseline.properties
```

## Results

Analysis scripts in the `../analysis/` directory process the output CSV files to generate:
- FCT CDFs and comparisons
- P99 latency improvements
- Tail latency breakdowns
- Queue residence distributions
- Bursty vs non-bursty flow analysis

Key visualization outputs are in `../figures/` directory.
