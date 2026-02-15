#!/usr/bin/env python3
"""
Deep Research Metrics Analysis for MIDST-Enhanced SP-PIFO
=========================================================

This script computes advanced metrics that a thesis supervisor would want:

1. FAIRNESS METRICS
   - Jain's Fairness Index (JFI) on normalized FCT
   - Per-size-class fairness

2. SLOWDOWN ANALYSIS
   - FCT / Ideal FCT (how much slower than wire speed?)
   - Slowdown distribution

3. GOODPUT ANALYSIS
   - Useful bytes delivered vs total bytes sent
   - Efficiency ratio

4. FLOW COMPLETION RATE
   - What % of flows complete within various time budgets?
   - SLA compliance analysis

5. QUEUE DYNAMICS (MIDST only - has queue logging)
   - Queue occupancy distribution
   - Time spent congested vs uncongested
   - Congestion event frequency

6. CONGESTION DETECTION ANALYSIS
   - How many flows flagged as bursty?
   - Characteristics of flagged flows

7. TAIL LATENCY ANALYSIS
   - P90, P95, P99, P99.9 FCT
   - Tail ratio (P99/P50)

8. THROUGHPUT FAIRNESS
   - Throughput distribution across flows
   - Max-min fairness analysis
"""

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import os
import re
import sys

# Paths — accept CLI arguments or use defaults
if len(sys.argv) >= 3:
    BASELINE_DIR = sys.argv[1]
    MIDST_DIR = sys.argv[2]
    OUTPUT_DIR = os.path.join(MIDST_DIR, "analysis", "research_metrics")
    print(f"Using CLI paths: baseline={BASELINE_DIR}, midst={MIDST_DIR}")
elif len(sys.argv) == 2:
    # Single directory mode — analyze one folder against itself (basic metrics only)
    BASELINE_DIR = sys.argv[1]
    MIDST_DIR = sys.argv[1]
    OUTPUT_DIR = os.path.join(sys.argv[1], "analysis", "research_metrics")
    print(f"Single-directory mode: {BASELINE_DIR}")
else:
    BASELINE_DIR = "temp/thesis_demo/phase4/baseline_no_midst"
    MIDST_DIR = "temp/thesis_demo/phase4/midst_sppifo"
    OUTPUT_DIR = "analysis/research_metrics"
    print("Using default paths (no CLI args provided)")

os.makedirs(OUTPUT_DIR, exist_ok=True)

# Constants
LINK_BANDWIDTH_GBPS = 10
LINK_BANDWIDTH_BPS = LINK_BANDWIDTH_GBPS * 1e9
LINK_DELAY_NS = 500
RTT_NS = 4 * LINK_DELAY_NS  # 2 links each way

def load_flow_data(directory):
    """Load flow completion data. Supports both 9-col and 12-col CSV formats."""
    fct_file = os.path.join(directory, "flow_completion.csv.log")

    # Detect column count from first line
    with open(fct_file, 'r') as f:
        first_line = f.readline().strip()
    ncols = len(first_line.split(','))

    if ncols == 12:
        df = pd.read_csv(fct_file, header=None, names=[
            'flow_id', 'source', 'dest', 'size_sent', 'size_total',
            'start_time', 'end_time', 'duration_ns', 'completed',
            'is_bursty_gt', 'is_bursty_detected', 'is_srpt'
        ])
        # Convert TRUE/FALSE strings to boolean
        for col in ['is_bursty_gt', 'is_bursty_detected', 'is_srpt']:
            df[col] = df[col].astype(str).str.strip().str.upper() == 'TRUE'
        print(f"  Loaded 12-column CSV with bursty/SRPT flags from {directory}")
    else:
        df = pd.read_csv(fct_file, header=None, names=[
            'flow_id', 'source', 'dest', 'size_sent', 'size_total',
            'start_time', 'end_time', 'duration_ns', 'completed'
        ])
        # Add default columns for backward compatibility
        df['is_bursty_gt'] = False
        df['is_bursty_detected'] = False
        df['is_srpt'] = False
        print(f"  Loaded 9-column CSV (no bursty/SRPT flags) from {directory}")

    return df

def load_throughput_data(directory):
    """Load flow throughput data."""
    tp_file = os.path.join(directory, "flow_throughput.csv.log")
    df = pd.read_csv(tp_file, header=None, names=[
        'flow_id', 'source', 'dest', 'bytes', 'start_time', 'end_time'
    ])
    return df

def parse_statistics(filepath):
    """Parse statistics.log file."""
    stats = {}
    with open(filepath, 'r') as f:
        for line in f:
            line = line.strip()
            if line and ':' in line:
                key, value = line.split(':', 1)
                stats[key.strip()] = int(value.strip())
    return stats

def parse_congestion_events(console_file):
    """Parse congestion detection events from console output."""
    events = []
    pattern = r'Port (\d+)->(\d+) Flow (\d+) identified as congested \(EstSize=(\d+), Threshold=(\d+)\)'

    with open(console_file, 'r') as f:
        for line in f:
            match = re.search(pattern, line)
            if match:
                events.append({
                    'src_port': int(match.group(1)),
                    'dst_port': int(match.group(2)),
                    'flow_id': int(match.group(3)),
                    'est_size': int(match.group(4)),
                    'threshold': int(match.group(5))
                })
    return pd.DataFrame(events)

def calculate_ideal_fct(size_bytes, bandwidth_bps=LINK_BANDWIDTH_BPS, rtt_ns=RTT_NS):
    """Calculate ideal FCT = transmission time + propagation delay."""
    transmission_ns = (size_bytes * 8 / bandwidth_bps) * 1e9
    return transmission_ns + rtt_ns

def jains_fairness_index(values):
    """Calculate Jain's Fairness Index: (sum(x))^2 / (n * sum(x^2))"""
    n = len(values)
    if n == 0:
        return 0
    sum_x = np.sum(values)
    sum_x2 = np.sum(values ** 2)
    if sum_x2 == 0:
        return 1
    return (sum_x ** 2) / (n * sum_x2)

# =============================================================================
# LOAD DATA
# =============================================================================
print("=" * 70)
print("DEEP RESEARCH METRICS ANALYSIS")
print("MIDST-Enhanced SP-PIFO Performance Evaluation")
print("=" * 70)

baseline_flows = load_flow_data(BASELINE_DIR)
midst_flows = load_flow_data(MIDST_DIR)

baseline_completed = baseline_flows[baseline_flows['completed'] == True].copy()
midst_completed = midst_flows[midst_flows['completed'] == True].copy()

baseline_stats = parse_statistics(os.path.join(BASELINE_DIR, "statistics.log"))
midst_stats = parse_statistics(os.path.join(MIDST_DIR, "statistics.log"))

# Parse congestion events
congestion_events = parse_congestion_events(os.path.join(MIDST_DIR, "console.txt"))

print(f"\nDataset Summary:")
print(f"  Baseline: {len(baseline_flows):,} total flows, {len(baseline_completed):,} completed")
print(f"  MIDST:    {len(midst_flows):,} total flows, {len(midst_completed):,} completed")
print(f"  Congestion events detected: {len(congestion_events)}")

# =============================================================================
# 1. SLOWDOWN ANALYSIS
# =============================================================================
print("\n" + "=" * 70)
print("1. SLOWDOWN ANALYSIS (FCT / Ideal FCT)")
print("=" * 70)

# Calculate ideal FCT and slowdown for each flow
baseline_completed['ideal_fct_ns'] = baseline_completed['size_total'].apply(calculate_ideal_fct)
midst_completed['ideal_fct_ns'] = midst_completed['size_total'].apply(calculate_ideal_fct)

baseline_completed['slowdown'] = baseline_completed['duration_ns'] / baseline_completed['ideal_fct_ns']
midst_completed['slowdown'] = midst_completed['duration_ns'] / midst_completed['ideal_fct_ns']

print(f"\nSlowdown Statistics (lower is better, 1.0 = optimal):")
print(f"{'Metric':<20} {'Baseline':>15} {'MIDST':>15} {'Change':>12}")
print("-" * 62)

for metric, func in [('Average', np.mean), ('Median', np.median),
                     ('P90', lambda x: np.percentile(x, 90)),
                     ('P99', lambda x: np.percentile(x, 99))]:
    b_val = func(baseline_completed['slowdown'])
    m_val = func(midst_completed['slowdown'])
    change = (m_val - b_val) / b_val * 100
    print(f"{metric:<20} {b_val:>15.2f}x {m_val:>15.2f}x {change:>+11.1f}%")

# =============================================================================
# 2. JAIN'S FAIRNESS INDEX
# =============================================================================
print("\n" + "=" * 70)
print("2. JAIN'S FAIRNESS INDEX")
print("=" * 70)

# Fairness on normalized FCT (slowdown)
baseline_jfi = jains_fairness_index(baseline_completed['slowdown'].values)
midst_jfi = jains_fairness_index(midst_completed['slowdown'].values)

print(f"\nJain's Fairness Index on Slowdown (1.0 = perfectly fair):")
print(f"  Baseline: {baseline_jfi:.6f}")
print(f"  MIDST:    {midst_jfi:.6f}")
print(f"  Change:   {(midst_jfi - baseline_jfi) / baseline_jfi * 100:+.2f}%")

# Fairness by size class
def categorize_size(size_bytes):
    if size_bytes < 100000:
        return 'Small (<100KB)'
    elif size_bytes < 1000000:
        return 'Medium (100KB-1MB)'
    else:
        return 'Large (>1MB)'

baseline_completed['size_cat'] = baseline_completed['size_total'].apply(categorize_size)
midst_completed['size_cat'] = midst_completed['size_total'].apply(categorize_size)

print(f"\nPer-Size-Class Fairness:")
for cat in ['Small (<100KB)', 'Medium (100KB-1MB)', 'Large (>1MB)']:
    b_data = baseline_completed[baseline_completed['size_cat'] == cat]['slowdown'].values
    m_data = midst_completed[midst_completed['size_cat'] == cat]['slowdown'].values

    b_jfi = jains_fairness_index(b_data) if len(b_data) > 0 else 0
    m_jfi = jains_fairness_index(m_data) if len(m_data) > 0 else 0

    print(f"  {cat:<20} Baseline: {b_jfi:.4f}, MIDST: {m_jfi:.4f}")

# =============================================================================
# 3. TAIL LATENCY ANALYSIS
# =============================================================================
print("\n" + "=" * 70)
print("3. TAIL LATENCY ANALYSIS")
print("=" * 70)

percentiles = [50, 90, 95, 99, 99.9]

print(f"\nFCT Percentiles (ms):")
print(f"{'Percentile':<12} {'Baseline':>15} {'MIDST':>15} {'Change':>12}")
print("-" * 54)

for p in percentiles:
    b_val = np.percentile(baseline_completed['duration_ns'], p) / 1e6
    m_val = np.percentile(midst_completed['duration_ns'], p) / 1e6
    change = (m_val - b_val) / b_val * 100
    print(f"P{p:<11} {b_val:>15.2f} {m_val:>15.2f} {change:>+11.1f}%")

# Tail ratio (how much worse is the tail vs median?)
baseline_tail_ratio = np.percentile(baseline_completed['duration_ns'], 99) / np.percentile(baseline_completed['duration_ns'], 50)
midst_tail_ratio = np.percentile(midst_completed['duration_ns'], 99) / np.percentile(midst_completed['duration_ns'], 50)

print(f"\nTail Ratio (P99/P50 - lower is better):")
print(f"  Baseline: {baseline_tail_ratio:.2f}x")
print(f"  MIDST:    {midst_tail_ratio:.2f}x")

# =============================================================================
# 4. SLA COMPLIANCE ANALYSIS
# =============================================================================
print("\n" + "=" * 70)
print("4. SLA COMPLIANCE ANALYSIS")
print("=" * 70)

sla_thresholds_ms = [10, 50, 100, 200, 500, 1000]

print(f"\nFlows completing within SLA threshold:")
print(f"{'Threshold':<12} {'Baseline':>12} {'MIDST':>12} {'Improvement':>12}")
print("-" * 48)

for threshold_ms in sla_thresholds_ms:
    threshold_ns = threshold_ms * 1e6

    b_count = len(baseline_completed[baseline_completed['duration_ns'] <= threshold_ns])
    m_count = len(midst_completed[midst_completed['duration_ns'] <= threshold_ns])

    b_pct = b_count / len(baseline_completed) * 100
    m_pct = m_count / len(midst_completed) * 100

    print(f"<{threshold_ms}ms       {b_pct:>11.1f}% {m_pct:>11.1f}% {m_pct - b_pct:>+11.1f}%")

# =============================================================================
# 5. CONGESTION DETECTION ANALYSIS
# =============================================================================
print("\n" + "=" * 70)
print("5. CONGESTION DETECTION ANALYSIS (MIDST)")
print("=" * 70)

if len(congestion_events) > 0:
    unique_flows = congestion_events['flow_id'].nunique()
    total_events = len(congestion_events)
    events_per_flow = congestion_events.groupby('flow_id').size()

    print(f"\nCongestion Detection Summary:")
    print(f"  Total detection events: {total_events}")
    print(f"  Unique flows flagged: {unique_flows}")
    print(f"  Avg events per flagged flow: {total_events / unique_flows:.1f}")
    print(f"  Max events for single flow: {events_per_flow.max()}")

    # Analyze characteristics of flagged flows
    flagged_flow_ids = congestion_events['flow_id'].unique()
    flagged_flows = midst_completed[midst_completed['flow_id'].isin(flagged_flow_ids)]
    non_flagged_flows = midst_completed[~midst_completed['flow_id'].isin(flagged_flow_ids)]

    print(f"\nFlagged vs Non-Flagged Flow Comparison:")
    print(f"{'Metric':<25} {'Flagged':>15} {'Non-Flagged':>15}")
    print("-" * 55)

    if len(flagged_flows) > 0:
        print(f"{'Count':<25} {len(flagged_flows):>15,} {len(non_flagged_flows):>15,}")
        print(f"{'Avg Size (KB)':<25} {flagged_flows['size_total'].mean()/1000:>15.1f} {non_flagged_flows['size_total'].mean()/1000:>15.1f}")
        print(f"{'Median Size (KB)':<25} {flagged_flows['size_total'].median()/1000:>15.1f} {non_flagged_flows['size_total'].median()/1000:>15.1f}")
        print(f"{'Avg FCT (ms)':<25} {flagged_flows['duration_ns'].mean()/1e6:>15.2f} {non_flagged_flows['duration_ns'].mean()/1e6:>15.2f}")
        print(f"{'Avg Slowdown':<25} {flagged_flows['slowdown'].mean():>15.2f}x {non_flagged_flows['slowdown'].mean():>15.2f}x")

    # Estimated burst sizes
    print(f"\nBurst Size Distribution (packets estimated during congestion):")
    print(f"  Mean: {congestion_events['est_size'].mean():.0f} packets")
    print(f"  Median: {congestion_events['est_size'].median():.0f} packets")
    print(f"  Max: {congestion_events['est_size'].max():.0f} packets")
    print(f"  Min: {congestion_events['est_size'].min():.0f} packets")

# =============================================================================
# 5b. BURSTY / SRPT COHORT ANALYSIS (12-col CSV)
# =============================================================================
if midst_completed['is_bursty_gt'].any() or midst_completed['is_srpt'].any():
    print("\n" + "=" * 70)
    print("5b. BURSTY / SRPT COHORT ANALYSIS")
    print("=" * 70)

    # Bursty ground-truth cohort
    for label, df_name, df in [("BASELINE", "baseline", baseline_completed),
                                ("MIDST", "midst", midst_completed)]:
        bursty = df[df['is_bursty_gt'] == True]
        non_bursty = df[df['is_bursty_gt'] == False]

        print(f"\n  {label} — Bursty vs Non-Bursty (Ground Truth):")
        print(f"  {'Cohort':<18} {'Count':>8} {'Avg FCT (ms)':>14} {'Med FCT (ms)':>14} {'P99 FCT (ms)':>14}")
        print("  " + "-" * 68)

        for coh_name, coh_df in [('Bursty GT', bursty), ('Non-Bursty GT', non_bursty)]:
            if len(coh_df) > 0:
                avg = coh_df['duration_ns'].mean() / 1e6
                med = coh_df['duration_ns'].median() / 1e6
                p99 = np.percentile(coh_df['duration_ns'], 99) / 1e6
                print(f"  {coh_name:<18} {len(coh_df):>8,} {avg:>14.2f} {med:>14.2f} {p99:>14.2f}")
            else:
                print(f"  {coh_name:<18} {'(none)':>8}")

    # MIDST-detected bursty cohort
    midst_detected = midst_completed[midst_completed['is_bursty_detected'] == True]
    midst_not_detected = midst_completed[midst_completed['is_bursty_detected'] == False]
    if len(midst_detected) > 0:
        print(f"\n  MIDST Detected vs Not-Detected:")
        print(f"  {'Cohort':<18} {'Count':>8} {'Avg FCT (ms)':>14} {'Med FCT (ms)':>14} {'P99 FCT (ms)':>14}")
        print("  " + "-" * 68)
        for coh_name, coh_df in [('Detected', midst_detected), ('Not-Detected', midst_not_detected)]:
            if len(coh_df) > 0:
                avg = coh_df['duration_ns'].mean() / 1e6
                med = coh_df['duration_ns'].median() / 1e6
                p99 = np.percentile(coh_df['duration_ns'], 99) / 1e6
                print(f"  {coh_name:<18} {len(coh_df):>8,} {avg:>14.2f} {med:>14.2f} {p99:>14.2f}")

    # SRPT cohort (for partial SRPT experiments)
    for label, df in [("BASELINE", baseline_completed), ("MIDST", midst_completed)]:
        srpt = df[df['is_srpt'] == True]
        non_srpt = df[df['is_srpt'] == False]
        if len(srpt) > 0 and len(non_srpt) > 0:
            print(f"\n  {label} — SRPT vs Non-SRPT:")
            print(f"  {'Cohort':<18} {'Count':>8} {'Avg FCT (ms)':>14} {'Med FCT (ms)':>14} {'P99 FCT (ms)':>14}")
            print("  " + "-" * 68)
            for coh_name, coh_df in [('SRPT', srpt), ('Non-SRPT', non_srpt)]:
                avg = coh_df['duration_ns'].mean() / 1e6
                med = coh_df['duration_ns'].median() / 1e6
                p99 = np.percentile(coh_df['duration_ns'], 99) / 1e6
                print(f"  {coh_name:<18} {len(coh_df):>8,} {avg:>14.2f} {med:>14.2f} {p99:>14.2f}")
else:
    print("\n  (No bursty/SRPT flags in CSV — skipping cohort analysis)")

# =============================================================================
# 6. GOODPUT ANALYSIS
# =============================================================================
print("\n" + "=" * 70)
print("6. GOODPUT ANALYSIS")
print("=" * 70)

# Goodput = useful data delivered / time
# We'll analyze per-flow throughput

baseline_completed['throughput_mbps'] = (baseline_completed['size_sent'] * 8) / (baseline_completed['duration_ns'] / 1e3)  # Mbps
midst_completed['throughput_mbps'] = (midst_completed['size_sent'] * 8) / (midst_completed['duration_ns'] / 1e3)  # Mbps

print(f"\nPer-Flow Throughput (Mbps):")
print(f"{'Metric':<20} {'Baseline':>15} {'MIDST':>15} {'Change':>12}")
print("-" * 62)

for metric, func in [('Average', np.mean), ('Median', np.median),
                     ('P10 (worst)', lambda x: np.percentile(x, 10)),
                     ('P90 (best)', lambda x: np.percentile(x, 90))]:
    b_val = func(baseline_completed['throughput_mbps'])
    m_val = func(midst_completed['throughput_mbps'])
    change = (m_val - b_val) / b_val * 100
    print(f"{metric:<20} {b_val:>15.1f} {m_val:>15.1f} {change:>+11.1f}%")

# Total goodput
total_bytes_baseline = baseline_completed['size_sent'].sum()
total_bytes_midst = midst_completed['size_sent'].sum()

# Assume 1 second simulation
print(f"\nAggregate Statistics:")
print(f"  Total bytes delivered (Baseline): {total_bytes_baseline/1e9:.2f} GB")
print(f"  Total bytes delivered (MIDST):    {total_bytes_midst/1e9:.2f} GB")
print(f"  Change: {(total_bytes_midst - total_bytes_baseline) / total_bytes_baseline * 100:+.1f}%")

# =============================================================================
# 7. FLOW SIZE vs SLOWDOWN CORRELATION
# =============================================================================
print("\n" + "=" * 70)
print("7. FLOW SIZE vs SLOWDOWN CORRELATION")
print("=" * 70)

# Correlation between flow size and slowdown
baseline_corr = baseline_completed['size_total'].corr(baseline_completed['slowdown'])
midst_corr = midst_completed['size_total'].corr(midst_completed['slowdown'])

print(f"\nCorrelation between Flow Size and Slowdown:")
print(f"  Baseline: {baseline_corr:.4f}")
print(f"  MIDST:    {midst_corr:.4f}")
print(f"  (Positive = larger flows have higher slowdown)")
print(f"  (MIDST closer to 0 = more size-independent performance)")

# =============================================================================
# 8. SUMMARY TABLE
# =============================================================================
print("\n" + "=" * 70)
print("COMPREHENSIVE METRICS SUMMARY")
print("=" * 70)

print("""
┌─────────────────────────────────────┬─────────────┬─────────────┬──────────────┐
│ Metric                              │  Baseline   │    MIDST    │   Change     │
├─────────────────────────────────────┼─────────────┼─────────────┼──────────────┤""")

metrics = [
    ("Completed Flows", f"{len(baseline_completed):,}", f"{len(midst_completed):,}", f"{(len(midst_completed) - len(baseline_completed)) / len(baseline_completed) * 100:+.1f}%"),
    ("Completion Rate", f"{len(baseline_completed)/len(baseline_flows)*100:.1f}%", f"{len(midst_completed)/len(midst_flows)*100:.1f}%", "-"),
    ("Average FCT (ms)", f"{baseline_completed['duration_ns'].mean()/1e6:.1f}", f"{midst_completed['duration_ns'].mean()/1e6:.1f}", f"{(midst_completed['duration_ns'].mean() - baseline_completed['duration_ns'].mean()) / baseline_completed['duration_ns'].mean() * 100:+.1f}%"),
    ("Median FCT (ms)", f"{baseline_completed['duration_ns'].median()/1e6:.1f}", f"{midst_completed['duration_ns'].median()/1e6:.1f}", f"{(midst_completed['duration_ns'].median() - baseline_completed['duration_ns'].median()) / baseline_completed['duration_ns'].median() * 100:+.1f}%"),
    ("P99 FCT (ms)", f"{np.percentile(baseline_completed['duration_ns'], 99)/1e6:.1f}", f"{np.percentile(midst_completed['duration_ns'], 99)/1e6:.1f}", f"{(np.percentile(midst_completed['duration_ns'], 99) - np.percentile(baseline_completed['duration_ns'], 99)) / np.percentile(baseline_completed['duration_ns'], 99) * 100:+.1f}%"),
    ("Average Slowdown", f"{baseline_completed['slowdown'].mean():.1f}x", f"{midst_completed['slowdown'].mean():.1f}x", f"{(midst_completed['slowdown'].mean() - baseline_completed['slowdown'].mean()) / baseline_completed['slowdown'].mean() * 100:+.1f}%"),
    ("Median Slowdown", f"{baseline_completed['slowdown'].median():.1f}x", f"{midst_completed['slowdown'].median():.1f}x", f"{(midst_completed['slowdown'].median() - baseline_completed['slowdown'].median()) / baseline_completed['slowdown'].median() * 100:+.1f}%"),
    ("Jain's Fairness Index", f"{baseline_jfi:.4f}", f"{midst_jfi:.4f}", f"{(midst_jfi - baseline_jfi) / baseline_jfi * 100:+.2f}%"),
    ("Tail Ratio (P99/P50)", f"{baseline_tail_ratio:.2f}x", f"{midst_tail_ratio:.2f}x", f"{(midst_tail_ratio - baseline_tail_ratio) / baseline_tail_ratio * 100:+.1f}%"),
    ("Size-Slowdown Corr", f"{baseline_corr:.4f}", f"{midst_corr:.4f}", "-"),
    ("Avg Throughput (Mbps)", f"{baseline_completed['throughput_mbps'].mean():.0f}", f"{midst_completed['throughput_mbps'].mean():.0f}", f"{(midst_completed['throughput_mbps'].mean() - baseline_completed['throughput_mbps'].mean()) / baseline_completed['throughput_mbps'].mean() * 100:+.1f}%"),
    ("Flows < 100ms", f"{len(baseline_completed[baseline_completed['duration_ns'] <= 100e6])/len(baseline_completed)*100:.1f}%", f"{len(midst_completed[midst_completed['duration_ns'] <= 100e6])/len(midst_completed)*100:.1f}%", "-"),
]

for name, b, m, c in metrics:
    print(f"│ {name:<35} │ {b:>11} │ {m:>11} │ {c:>12} │")

print("└─────────────────────────────────────┴─────────────┴─────────────┴──────────────┘")

# =============================================================================
# GENERATE VISUALIZATIONS
# =============================================================================
print("\n\nGenerating visualizations...")

# Figure 1: Slowdown Distribution
fig, axes = plt.subplots(1, 2, figsize=(12, 4))

ax1 = axes[0]
ax1.hist(baseline_completed['slowdown'].clip(upper=100), bins=50, alpha=0.7, label='Baseline', color='#2ecc71')
ax1.hist(midst_completed['slowdown'].clip(upper=100), bins=50, alpha=0.7, label='MIDST', color='#9b59b6')
ax1.set_xlabel('Slowdown (FCT / Ideal FCT)')
ax1.set_ylabel('Number of Flows')
ax1.set_title('Slowdown Distribution')
ax1.legend()
ax1.set_xlim(0, 100)

ax2 = axes[1]
baseline_slowdown_sorted = np.sort(baseline_completed['slowdown'].values)
midst_slowdown_sorted = np.sort(midst_completed['slowdown'].values)
ax2.plot(baseline_slowdown_sorted, np.arange(1, len(baseline_slowdown_sorted)+1) / len(baseline_slowdown_sorted),
         label='Baseline', color='#2ecc71', linewidth=2)
ax2.plot(midst_slowdown_sorted, np.arange(1, len(midst_slowdown_sorted)+1) / len(midst_slowdown_sorted),
         label='MIDST', color='#9b59b6', linewidth=2)
ax2.set_xlabel('Slowdown')
ax2.set_ylabel('CDF')
ax2.set_title('Slowdown CDF')
ax2.legend()
ax2.set_xlim(0, 50)
ax2.grid(True, alpha=0.3)

plt.tight_layout()
plt.savefig(os.path.join(OUTPUT_DIR, 'slowdown_analysis.png'), dpi=150)
plt.close()
print("  ✓ slowdown_analysis.png")

# Figure 2: Size vs Slowdown Scatter
fig, axes = plt.subplots(1, 2, figsize=(12, 5))

ax1 = axes[0]
ax1.scatter(baseline_completed['size_total']/1000, baseline_completed['slowdown'],
            alpha=0.3, s=5, c='#2ecc71')
ax1.set_xlabel('Flow Size (KB)')
ax1.set_ylabel('Slowdown')
ax1.set_title(f'Baseline: Size vs Slowdown (r={baseline_corr:.3f})')
ax1.set_xscale('log')
ax1.set_yscale('log')
ax1.set_ylim(1, 1000)

ax2 = axes[1]
ax2.scatter(midst_completed['size_total']/1000, midst_completed['slowdown'],
            alpha=0.3, s=5, c='#9b59b6')
ax2.set_xlabel('Flow Size (KB)')
ax2.set_ylabel('Slowdown')
ax2.set_title(f'MIDST: Size vs Slowdown (r={midst_corr:.3f})')
ax2.set_xscale('log')
ax2.set_yscale('log')
ax2.set_ylim(1, 1000)

plt.tight_layout()
plt.savefig(os.path.join(OUTPUT_DIR, 'size_vs_slowdown.png'), dpi=150)
plt.close()
print("  ✓ size_vs_slowdown.png")

# Figure 3: SLA Compliance
fig, ax = plt.subplots(figsize=(10, 5))

sla_thresholds = [10, 50, 100, 200, 500, 1000]
baseline_compliance = []
midst_compliance = []

for t in sla_thresholds:
    t_ns = t * 1e6
    baseline_compliance.append(len(baseline_completed[baseline_completed['duration_ns'] <= t_ns]) / len(baseline_completed) * 100)
    midst_compliance.append(len(midst_completed[midst_completed['duration_ns'] <= t_ns]) / len(midst_completed) * 100)

x = np.arange(len(sla_thresholds))
width = 0.35

bars1 = ax.bar(x - width/2, baseline_compliance, width, label='Baseline', color='#2ecc71')
bars2 = ax.bar(x + width/2, midst_compliance, width, label='MIDST', color='#9b59b6')

ax.set_xlabel('SLA Threshold (ms)')
ax.set_ylabel('% Flows Meeting SLA')
ax.set_title('SLA Compliance: % Flows Completing Within Threshold')
ax.set_xticks(x)
ax.set_xticklabels([f'<{t}ms' for t in sla_thresholds])
ax.legend()
ax.set_ylim(0, 100)

for bars in [bars1, bars2]:
    for bar in bars:
        height = bar.get_height()
        ax.annotate(f'{height:.0f}%', xy=(bar.get_x() + bar.get_width()/2, height),
                   ha='center', va='bottom', fontsize=8)

plt.tight_layout()
plt.savefig(os.path.join(OUTPUT_DIR, 'sla_compliance.png'), dpi=150)
plt.close()
print("  ✓ sla_compliance.png")

# Figure 4: Congestion Event Analysis
if len(congestion_events) > 0:
    fig, axes = plt.subplots(1, 2, figsize=(12, 4))

    ax1 = axes[0]
    events_per_flow = congestion_events.groupby('flow_id').size()
    ax1.hist(events_per_flow.values, bins=range(1, events_per_flow.max()+2),
             color='#e74c3c', edgecolor='black', alpha=0.7)
    ax1.set_xlabel('Number of Congestion Events')
    ax1.set_ylabel('Number of Flows')
    ax1.set_title('Congestion Events per Flow')

    ax2 = axes[1]
    ax2.hist(congestion_events['est_size'], bins=30, color='#e74c3c', edgecolor='black', alpha=0.7)
    ax2.set_xlabel('Estimated Burst Size (packets)')
    ax2.set_ylabel('Count')
    ax2.set_title('Burst Size Distribution at Detection')
    ax2.axvline(x=100, color='black', linestyle='--', label='Threshold')
    ax2.legend()

    plt.tight_layout()
    plt.savefig(os.path.join(OUTPUT_DIR, 'congestion_events.png'), dpi=150)
    plt.close()
    print("  ✓ congestion_events.png")

print(f"\nAll visualizations saved to: {OUTPUT_DIR}")
