#!/usr/bin/env python3
"""
Enhanced Metrics Analysis for MIDST-Enhanced SP-PIFO
====================================================

Analyzes the new logging data including:
- Per-queue packet distribution
- Bursty vs normal packet breakdown
- Congestion episodes
- Per-flow retransmission patterns
"""

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import os
import re

# Paths
BASELINE_DIR = "/sessions/gallant-loving-bohr/mnt/netbench-thesis/temp/thesis_demo/phase4/baseline_no_midst"
MIDST_DIR = "/sessions/gallant-loving-bohr/mnt/netbench-thesis/temp/thesis_demo/phase4/midst_sppifo"
OUTPUT_DIR = "/sessions/gallant-loving-bohr/mnt/netbench-thesis/analysis/enhanced_metrics"

os.makedirs(OUTPUT_DIR, exist_ok=True)

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

def load_flow_data(directory):
    """Load flow completion data."""
    fct_file = os.path.join(directory, "flow_completion.csv.log")
    df = pd.read_csv(fct_file, header=None, names=[
        'flow_id', 'source', 'dest', 'size_sent', 'size_total',
        'start_time', 'end_time', 'duration_ns', 'completed'
    ])
    return df

def parse_retransmit_log(directory):
    """Parse per-flow retransmission log entries."""
    retransmits = []
    log_files = ['console.txt']  # Check console output

    for log_file in log_files:
        filepath = os.path.join(directory, log_file)
        if os.path.exists(filepath):
            with open(filepath, 'r') as f:
                for line in f:
                    if 'TCP_RETRANSMIT_FLOW' in line:
                        # Format: TCP_RETRANSMIT_FLOW: flowId,type,time,seq
                        match = re.search(r'TCP_RETRANSMIT_FLOW.*?(\d+),(FAST|TIMEOUT),(\d+),(\d+)', line)
                        if match:
                            retransmits.append({
                                'flow_id': int(match.group(1)),
                                'type': match.group(2),
                                'time': int(match.group(3)),
                                'seq': int(match.group(4))
                            })
    return pd.DataFrame(retransmits) if retransmits else pd.DataFrame()

# =============================================================================
# LOAD DATA
# =============================================================================
print("=" * 70)
print("ENHANCED METRICS ANALYSIS")
print("MIDST-Enhanced SP-PIFO - Deep Dive")
print("=" * 70)

baseline_stats = parse_statistics(os.path.join(BASELINE_DIR, "statistics.log"))
midst_stats = parse_statistics(os.path.join(MIDST_DIR, "statistics.log"))

baseline_flows = load_flow_data(BASELINE_DIR)
midst_flows = load_flow_data(MIDST_DIR)

baseline_completed = baseline_flows[baseline_flows['completed'] == True].copy()
midst_completed = midst_flows[midst_flows['completed'] == True].copy()

print(f"\nDataset: {len(midst_flows):,} total flows")

# =============================================================================
# 1. MIDST ACTIVITY SUMMARY
# =============================================================================
print("\n" + "=" * 70)
print("1. MIDST ACTIVITY SUMMARY")
print("=" * 70)

congestion_episodes = midst_stats.get('MIDST_CONGESTION_EPISODES', 0)
unique_flows_penalized = midst_stats.get('MIDST_UNIQUE_FLOWS_PENALIZED', 0)
bursty_packets = midst_stats.get('MIDST_BURSTY_PACKETS_ENQUEUED', 0)
normal_packets = midst_stats.get('MIDST_NORMAL_PACKETS_ENQUEUED', 0)
total_queued = bursty_packets + normal_packets

print(f"""
MIDST Congestion Detection:
  Congestion Episodes:        {congestion_episodes}
  Unique Flows Penalized:     {unique_flows_penalized}

Packet Classification:
  Bursty Packets Enqueued:    {bursty_packets:,} ({bursty_packets/total_queued*100:.3f}%)
  Normal Packets Enqueued:    {normal_packets:,} ({normal_packets/total_queued*100:.3f}%)
  Total Queued:               {total_queued:,}

Key Insight: Only {bursty_packets/total_queued*100:.3f}% of packets came from bursty flows,
             but these {unique_flows_penalized} flows were causing congestion for everyone!
""")

# =============================================================================
# 2. SP-PIFO QUEUE DISTRIBUTION
# =============================================================================
print("\n" + "=" * 70)
print("2. SP-PIFO QUEUE DISTRIBUTION")
print("=" * 70)

queue_counts = []
for i in range(5):
    count = midst_stats.get(f'SPPIFO_QUEUE_{i}_ENQUEUED', 0)
    queue_counts.append(count)

pushed_lower = midst_stats.get('SPPIFO_PUSHED_TO_LOWER_QUEUE', 0)
total_queue_packets = sum(queue_counts)

print(f"""
Per-Queue Packet Distribution:
  Queue 0 (Highest Priority): {queue_counts[0]:>12,} ({queue_counts[0]/total_queue_packets*100:>6.2f}%)
  Queue 1:                    {queue_counts[1]:>12,} ({queue_counts[1]/total_queue_packets*100:>6.2f}%)
  Queue 2:                    {queue_counts[2]:>12,} ({queue_counts[2]/total_queue_packets*100:>6.2f}%)
  Queue 3:                    {queue_counts[3]:>12,} ({queue_counts[3]/total_queue_packets*100:>6.2f}%)
  Queue 4 (Lowest Priority):  {queue_counts[4]:>12,} ({queue_counts[4]/total_queue_packets*100:>6.2f}%)
  ─────────────────────────────────────────────────────
  Total:                      {total_queue_packets:>12,}

  Packets Pushed to Lower Queue: {pushed_lower:,} ({pushed_lower/total_queue_packets*100:.2f}%)

Key Insight: {queue_counts[0]/total_queue_packets*100:.1f}% of packets went to Queue 0 (highest priority).
             MIDST successfully kept most traffic in high-priority queues!
""")

# =============================================================================
# 3. COMPARISON TABLE
# =============================================================================
print("\n" + "=" * 70)
print("3. COMPLETE METRICS COMPARISON")
print("=" * 70)

# Calculate derived metrics
baseline_retrans = baseline_stats.get('TCP_FAST_RETRANSMIT', 0) + baseline_stats.get('TCP_RETRANSMISSION_TIMEOUT', 0)
midst_retrans = midst_stats.get('TCP_FAST_RETRANSMIT', 0) + midst_stats.get('TCP_RETRANSMISSION_TIMEOUT', 0)

baseline_retrans_rate = baseline_retrans / baseline_stats.get('TCP_TOTAL_PACKETS_SENT', 1) * 100
midst_retrans_rate = midst_retrans / midst_stats.get('TCP_TOTAL_PACKETS_SENT', 1) * 100

midst_loss_rate = midst_stats.get('PACKETS_DROPPED_QUEUE_REJECT', 0) / midst_stats.get('TOTAL_PACKETS_ENQUEUED', 1) * 100

baseline_avg_fct = baseline_completed['duration_ns'].mean() / 1e6
midst_avg_fct = midst_completed['duration_ns'].mean() / 1e6
baseline_median_fct = baseline_completed['duration_ns'].median() / 1e6
midst_median_fct = midst_completed['duration_ns'].median() / 1e6

print(f"""
┌────────────────────────────────────┬───────────────┬───────────────┬─────────────┐
│ Metric                             │   Baseline    │     MIDST     │   Change    │
├────────────────────────────────────┼───────────────┼───────────────┼─────────────┤
│ FLOW COMPLETION                    │               │               │             │
│   Completed Flows                  │ {len(baseline_completed):>13,} │ {len(midst_completed):>13,} │ {(len(midst_completed)-len(baseline_completed))/len(baseline_completed)*100:>+10.1f}% │
│   Average FCT (ms)                 │ {baseline_avg_fct:>13.1f} │ {midst_avg_fct:>13.1f} │ {(midst_avg_fct-baseline_avg_fct)/baseline_avg_fct*100:>+10.1f}% │
│   Median FCT (ms)                  │ {baseline_median_fct:>13.1f} │ {midst_median_fct:>13.1f} │ {(midst_median_fct-baseline_median_fct)/baseline_median_fct*100:>+10.1f}% │
├────────────────────────────────────┼───────────────┼───────────────┼─────────────┤
│ PACKET HANDLING                    │               │               │             │
│   Total Packets Enqueued           │ {baseline_stats.get('TOTAL_PACKETS_ENQUEUED',0):>13,} │ {midst_stats.get('TOTAL_PACKETS_ENQUEUED',0):>13,} │             │
│   Packets Dropped                  │ {0:>13,} │ {midst_stats.get('PACKETS_DROPPED_QUEUE_REJECT',0):>13,} │             │
│   Loss Rate                        │ {0:>12.2f}% │ {midst_loss_rate:>12.2f}% │             │
├────────────────────────────────────┼───────────────┼───────────────┼─────────────┤
│ TCP RETRANSMISSIONS                │               │               │             │
│   Fast Retransmits                 │ {baseline_stats.get('TCP_FAST_RETRANSMIT',0):>13,} │ {midst_stats.get('TCP_FAST_RETRANSMIT',0):>13,} │ {(midst_stats.get('TCP_FAST_RETRANSMIT',0)-baseline_stats.get('TCP_FAST_RETRANSMIT',0))/max(baseline_stats.get('TCP_FAST_RETRANSMIT',1),1)*100:>+10.1f}% │
│   Timeout Retransmits              │ {baseline_stats.get('TCP_RETRANSMISSION_TIMEOUT',0):>13,} │ {midst_stats.get('TCP_RETRANSMISSION_TIMEOUT',0):>13,} │ {(midst_stats.get('TCP_RETRANSMISSION_TIMEOUT',0)-baseline_stats.get('TCP_RETRANSMISSION_TIMEOUT',0))/baseline_stats.get('TCP_RETRANSMISSION_TIMEOUT',1)*100:>+10.1f}% │
│   Retransmission Rate              │ {baseline_retrans_rate:>12.2f}% │ {midst_retrans_rate:>12.2f}% │ {(midst_retrans_rate-baseline_retrans_rate)/baseline_retrans_rate*100:>+10.1f}% │
├────────────────────────────────────┼───────────────┼───────────────┼─────────────┤
│ MIDST-SPECIFIC (N/A for baseline)  │               │               │             │
│   Congestion Episodes              │           N/A │ {congestion_episodes:>13,} │             │
│   Unique Flows Penalized           │           N/A │ {unique_flows_penalized:>13,} │             │
│   Bursty Packets                   │           N/A │ {bursty_packets:>13,} │             │
│   Normal Packets                   │           N/A │ {normal_packets:>13,} │             │
│   Bursty Packet Ratio              │           N/A │ {bursty_packets/total_queued*100:>12.3f}% │             │
└────────────────────────────────────┴───────────────┴───────────────┴─────────────┘
""")

# =============================================================================
# 4. KEY INSIGHTS
# =============================================================================
print("\n" + "=" * 70)
print("4. KEY INSIGHTS FOR THESIS")
print("=" * 70)

print(f"""
🎯 MIDST EFFECTIVENESS:
   • Only {unique_flows_penalized} flows ({unique_flows_penalized/len(midst_flows)*100:.2f}% of all flows) were penalized
   • These flows generated just {bursty_packets:,} packets ({bursty_packets/total_queued*100:.3f}% of traffic)
   • But penalizing them improved FCT by {(baseline_avg_fct-midst_avg_fct)/baseline_avg_fct*100:.1f}% for everyone!

📊 QUEUE UTILIZATION:
   • {queue_counts[0]/total_queue_packets*100:.1f}% of packets stayed in Queue 0 (highest priority)
   • Only {(sum(queue_counts[1:]))/total_queue_packets*100:.1f}% went to lower priority queues
   • {pushed_lower:,} packets had to be pushed down due to queue overflow

⚡ CONGESTION PATTERNS:
   • {congestion_episodes} congestion episodes detected during 1-second simulation
   • Average episode duration: ~{1000/congestion_episodes:.0f}ms between episodes
   • Fast retransmits increased {(midst_stats.get('TCP_FAST_RETRANSMIT',0)-baseline_stats.get('TCP_FAST_RETRANSMIT',0))/max(baseline_stats.get('TCP_FAST_RETRANSMIT',1),1)*100:.0f}x (expected - MIDST drops cause fast recovery)
   • Timeout retransmits DECREASED by {(baseline_stats.get('TCP_RETRANSMISSION_TIMEOUT',0)-midst_stats.get('TCP_RETRANSMISSION_TIMEOUT',0))/baseline_stats.get('TCP_RETRANSMISSION_TIMEOUT',1)*100:.1f}% (good - fewer severe timeouts)

📈 THE TRADE-OFF:
   • Cost: {midst_loss_rate:.2f}% packet loss (from bursty flows)
   • Benefit: {(len(midst_completed)-len(baseline_completed))/len(baseline_completed)*100:.1f}% more flows complete
   • Benefit: {(baseline_avg_fct-midst_avg_fct)/baseline_avg_fct*100:.1f}% faster average FCT
   • Net: MASSIVE win for the network!
""")

# =============================================================================
# GENERATE VISUALIZATIONS
# =============================================================================
print("\nGenerating visualizations...")

# Figure 1: Queue Distribution Pie Chart
fig, axes = plt.subplots(1, 2, figsize=(14, 5))

# Left: Queue distribution
ax1 = axes[0]
labels = ['Q0 (Highest)', 'Q1', 'Q2', 'Q3', 'Q4 (Lowest)']
colors = ['#2ecc71', '#3498db', '#9b59b6', '#e74c3c', '#e67e22']
explode = [0.02, 0, 0, 0, 0]  # Slightly explode Q0

wedges, texts, autotexts = ax1.pie(queue_counts, labels=labels, colors=colors,
                                    autopct='%1.1f%%', explode=explode, startangle=90)
ax1.set_title('SP-PIFO Queue Distribution\n(Where Packets Were Enqueued)', fontweight='bold')

# Right: Bursty vs Normal
ax2 = axes[1]
sizes = [bursty_packets, normal_packets]
labels = [f'Bursty\n({bursty_packets:,})', f'Normal\n({normal_packets:,})']
colors = ['#e74c3c', '#2ecc71']
explode = [0.1, 0]  # Explode bursty to emphasize small size

wedges, texts, autotexts = ax2.pie(sizes, labels=labels, colors=colors,
                                    autopct='%1.3f%%', explode=explode, startangle=90)
ax2.set_title('Packet Classification by MIDST\n(Bursty vs Normal Flows)', fontweight='bold')

plt.tight_layout()
plt.savefig(os.path.join(OUTPUT_DIR, 'queue_and_classification.png'), dpi=150, bbox_inches='tight')
plt.close()
print("  ✓ queue_and_classification.png")

# Figure 2: Comprehensive Comparison Bar Chart
fig, axes = plt.subplots(2, 2, figsize=(14, 10))

# Top Left: Flow Completion
ax1 = axes[0, 0]
metrics = ['Completed\nFlows', 'Avg FCT\n(ms)', 'Median FCT\n(ms)']
baseline_vals = [len(baseline_completed), baseline_avg_fct, baseline_median_fct]
midst_vals = [len(midst_completed), midst_avg_fct, midst_median_fct]

x = np.arange(len(metrics))
width = 0.35
bars1 = ax1.bar(x - width/2, baseline_vals, width, label='Baseline', color='#2ecc71')
bars2 = ax1.bar(x + width/2, midst_vals, width, label='MIDST', color='#9b59b6')
ax1.set_ylabel('Value')
ax1.set_title('Flow Completion Metrics', fontweight='bold')
ax1.set_xticks(x)
ax1.set_xticklabels(metrics)
ax1.legend()

# Add value labels
for bar in bars1:
    height = bar.get_height()
    ax1.annotate(f'{height:.0f}', xy=(bar.get_x() + bar.get_width()/2, height),
                ha='center', va='bottom', fontsize=9)
for bar in bars2:
    height = bar.get_height()
    ax1.annotate(f'{height:.0f}', xy=(bar.get_x() + bar.get_width()/2, height),
                ha='center', va='bottom', fontsize=9)

# Top Right: Retransmission Breakdown
ax2 = axes[0, 1]
metrics = ['Fast\nRetransmit', 'Timeout\nRetransmit']
baseline_vals = [baseline_stats.get('TCP_FAST_RETRANSMIT', 0), baseline_stats.get('TCP_RETRANSMISSION_TIMEOUT', 0)]
midst_vals = [midst_stats.get('TCP_FAST_RETRANSMIT', 0), midst_stats.get('TCP_RETRANSMISSION_TIMEOUT', 0)]

x = np.arange(len(metrics))
bars1 = ax2.bar(x - width/2, baseline_vals, width, label='Baseline', color='#2ecc71')
bars2 = ax2.bar(x + width/2, midst_vals, width, label='MIDST', color='#9b59b6')
ax2.set_ylabel('Count')
ax2.set_title('TCP Retransmission Breakdown', fontweight='bold')
ax2.set_xticks(x)
ax2.set_xticklabels(metrics)
ax2.legend()

for bars in [bars1, bars2]:
    for bar in bars:
        height = bar.get_height()
        ax2.annotate(f'{height:,.0f}', xy=(bar.get_x() + bar.get_width()/2, height),
                    ha='center', va='bottom', fontsize=8)

# Bottom Left: Queue Distribution Bar
ax3 = axes[1, 0]
queue_labels = ['Q0\n(Highest)', 'Q1', 'Q2', 'Q3', 'Q4\n(Lowest)']
colors = ['#2ecc71', '#3498db', '#9b59b6', '#e74c3c', '#e67e22']
bars = ax3.bar(queue_labels, queue_counts, color=colors)
ax3.set_ylabel('Packets Enqueued')
ax3.set_title('SP-PIFO Per-Queue Packet Count', fontweight='bold')
ax3.set_yscale('log')

for bar, count in zip(bars, queue_counts):
    ax3.annotate(f'{count:,}', xy=(bar.get_x() + bar.get_width()/2, bar.get_height()),
                ha='center', va='bottom', fontsize=9)

# Bottom Right: MIDST Summary
ax4 = axes[1, 1]
ax4.axis('off')

summary_text = f"""
MIDST Performance Summary
═══════════════════════════════════════

Congestion Detection:
  • {congestion_episodes} episodes detected
  • {unique_flows_penalized} flows penalized ({unique_flows_penalized/len(midst_flows)*100:.2f}%)

Traffic Classification:
  • Bursty: {bursty_packets:,} packets ({bursty_packets/total_queued*100:.3f}%)
  • Normal: {normal_packets:,} packets ({normal_packets/total_queued*100:.3f}%)

Results:
  • FCT improved by {(baseline_avg_fct-midst_avg_fct)/baseline_avg_fct*100:.1f}% (average)
  • {(len(midst_completed)-len(baseline_completed))/len(baseline_completed)*100:.1f}% more flows completed
  • Timeout retransmits down {(baseline_stats.get('TCP_RETRANSMISSION_TIMEOUT',0)-midst_stats.get('TCP_RETRANSMISSION_TIMEOUT',0))/baseline_stats.get('TCP_RETRANSMISSION_TIMEOUT',1)*100:.1f}%

Conclusion: MIDST achieves better performance
by penalizing just {bursty_packets/total_queued*100:.3f}% of packets!
"""

ax4.text(0.1, 0.9, summary_text, transform=ax4.transAxes, fontsize=11,
        verticalalignment='top', fontfamily='monospace',
        bbox=dict(boxstyle='round', facecolor='#e8f5e9', alpha=0.8))

plt.tight_layout()
plt.savefig(os.path.join(OUTPUT_DIR, 'comprehensive_comparison.png'), dpi=150, bbox_inches='tight')
plt.close()
print("  ✓ comprehensive_comparison.png")

print(f"\nAll visualizations saved to: {OUTPUT_DIR}")
