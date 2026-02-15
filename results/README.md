# Experiment Results Summary

This directory contains extracted summary metrics from 6 Fat Tree (k=4) network simulation experiments comparing different queueing disciplines with and without MIDST packet classification.

## Generated Files

### Primary Summary Files (Git-friendly)

- **summary.csv** - Aggregated metrics from all experiments in CSV format for easy comparison and analysis
- **summary.json** - Machine-readable JSON format with complete metrics for programmatic access
- **extract_summary.py** - Python script that extracts metrics from raw simulation results

### Individual Experiment Summaries

Human-readable text summaries for each experiment:
- `e44_packs_baseline_no_srpt_fattree_100_summary.txt` - PACKS baseline
- `e45_packs_midst_no_srpt_fattree_100_summary.txt` - PACKS + MIDST
- `e46_aifo_baseline_no_srpt_fattree_100_summary.txt` - AIFO baseline
- `e47_aifo_midst_no_srpt_fattree_100_summary.txt` - AIFO + MIDST
- `e48_sppifo_baseline_no_srpt_fattree_100_summary.txt` - SP-PIFO baseline
- `e49_sppifo_midst_no_srpt_fattree_100_summary.txt` - SP-PIFO + MIDST

## Key Metrics Extracted

### Flow Completion Time (FCT) Metrics
- Total flows completed / total flows
- Mean, median FCT in milliseconds
- 90th, 95th, 99th percentile FCT
- Min/max FCT

### Packet and Network Statistics
- Total packets enqueued
- Packets dropped (by category: bursty, normal, queue reject)
- TCP retransmission timeouts (RTO)
- TCP fast retransmit events
- Packet drop rate as percentage

### MIDST-Specific Metrics (For E45, E47, E49)
- Bursty flows detected
- Bursty/normal packet classification counts
- Monitoring windows statistics
- Congestion detection events
- Sketch reset/rotation counts
- Unique flows penalized

### Queue-Specific Metrics (For SP-PIFO E48, E49)
- Per-queue (0-4) enqueue/dequeue counts
- Queue promotion/demotion statistics

## File Sizes

All summary files are intentionally small (KB-scale) for version control:
- Individual summaries: 1.8-2.7 KB each
- CSV summary: 2.6 KB
- JSON summary: 6.3 KB
- Python script: 12 KB
- **Total: ~48 KB** (no multi-GB raw logs)

## Raw Data Location

Original experiment data is in:
```
/sessions/compassionate-nifty-edison/mnt/netbench-thesis/netbench-thesis-clean/temp/new_experiments/fattree/
```

Each experiment directory contains:
- `flow_completion.csv.log` - Per-flow FCT data
- `statistics.log` - Aggregated statistics
- Various other logs (not included in summary, can be excluded from version control)

## Usage Examples

### Viewing Aggregated Results
```bash
cat summary.csv
python3 -m json.tool summary.json
```

### Viewing Individual Experiment
```bash
cat e45_packs_midst_no_srpt_fattree_100_summary.txt
```

### Regenerating Summaries
```bash
python3 extract_summary.py
```

The script will:
1. Read flow_completion.csv.log and statistics.log from each experiment
2. Calculate FCT percentiles
3. Generate/update all summary files
4. Report processing results

## Experiment Configuration

- **Topology**: Fat Tree (k=4) with 4 servers per ToR
- **Total servers**: 64 (8 ToRs × 4 servers each)
- **Load**: 100% link utilization
- **No SRPT** enabled in all experiments

### Queueing Disciplines Tested
1. **PACKS** - Priority-based Auto-tuning Congestion-aware Queue Scheduler
2. **AIFO** - Adaptive Incast-aware Fair Queueing
3. **SP-PIFO** - Shortest Remaining Flow to Priority Idle Queue (from prior work)

### Packet Classification Methods
- **Baseline** - Standard traffic (no classification)
- **MIDST** - Multi-level In-packet Schematic Detection of Heavy-hitter Transfers

## Interpretation Guide

### FCT Metrics
- **Mean FCT**: Average completion time; lower is better
- **Median FCT**: Typical flow performance; significant for user experience
- **P99 FCT**: Tail latency; important for SLA compliance

### Packet Drops
- **Drop rate**: Percentage of enqueued packets dropped
- **Bursty drops**: Packets from detected bursty flows
- **RTO count**: High RTO indicates congestion and retransmission burden

### MIDST Metrics
- **Bursty flows detected**: Accuracy of MIDST classification
- **Monitoring windows**: Activity of the MIDST monitoring mechanism
- **Congestion episodes**: When MIDST detects and acts on congestion
- **Unique flows penalized**: Flows subjected to congestion control

## Notes for Version Control

These summary files are designed to be committed to git:
- Small file sizes (KB-range)
- Deterministic output (no random variation)
- Text-based formats (CSV, JSON, TXT)
- Self-contained (no dependencies on raw data)

Do NOT commit the raw .log files from temp/new_experiments/ as they are multi-GB in size.
