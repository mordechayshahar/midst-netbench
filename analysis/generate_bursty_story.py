#!/usr/bin/env python3
"""
Generate visualization telling the MIDST bursty flow story.
"""

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
from pathlib import Path

OUTPUT_DIR = Path("/sessions/gallant-loving-bohr/mnt/netbench-thesis/analysis/phase4_results")
OUTPUT_DIR.mkdir(exist_ok=True)

CONSOLE_LOG = "/sessions/gallant-loving-bohr/mnt/.claude/projects/-sessions-gallant-loving-bohr/debc698c-1a18-473a-bd6a-02cb64e3085e/tool-results/toolu_01C8uBbZPrkXSqU3DmvjR9xB.txt"

def get_bursty_flow_ids():
    """Extract flow IDs that were identified as congested/bursty."""
    bursty_flows = set()
    with open(CONSOLE_LOG, 'r') as f:
        for line in f:
            if "identified as congested" in line:
                parts = line.split("Flow ")
                if len(parts) > 1:
                    flow_id = int(parts[1].split()[0])
                    bursty_flows.add(flow_id)
    return bursty_flows


def load_fct_data(filepath):
    """Load flow completion data."""
    df = pd.read_csv(filepath, header=None, names=[
        'flow_id', 'source', 'dest', 'sent_bytes', 'total_bytes',
        'start_time_ns', 'end_time_ns', 'fct_ns', 'completed'
    ])
    df = df[df['completed'] == True]
    df['fct_ms'] = df['fct_ns'] / 1_000_000
    df['fct_us'] = df['fct_ns'] / 1_000
    df['flow_size_kb'] = df['total_bytes'] / 1024
    return df


def categorize_size(size_bytes):
    if size_bytes < 100_000:
        return 'Small\n(<100KB)'
    elif size_bytes < 1_000_000:
        return 'Medium\n(100KB-1MB)'
    else:
        return 'Large\n(>=1MB)'


def create_bursty_story_chart():
    """Create visualization comparing same flows across baseline and MIDST."""
    # Load data
    midst_df = load_fct_data("/sessions/gallant-loving-bohr/mnt/netbench-thesis/temp/thesis_demo/phase4/midst_sppifo/flow_completion.csv.log")
    baseline_df = load_fct_data("/sessions/gallant-loving-bohr/mnt/netbench-thesis/temp/thesis_demo/phase4/baseline_no_midst/flow_completion.csv.log")

    bursty_flow_ids = get_bursty_flow_ids()
    print(f"Found {len(bursty_flow_ids)} unique bursty flows identified by MIDST")

    # Get flows that completed in BOTH simulations
    common_flows = set(baseline_df['flow_id']) & set(midst_df['flow_id'])
    bursty_in_both = bursty_flow_ids & common_flows
    non_bursty_in_both = common_flows - bursty_flow_ids

    print(f"Flows completed in both: {len(common_flows)}")
    print(f"  Bursty: {len(bursty_in_both)}")
    print(f"  Non-bursty: {len(non_bursty_in_both)}")

    # Get data for same flows
    baseline_bursty = baseline_df[baseline_df['flow_id'].isin(bursty_in_both)]
    midst_bursty = midst_df[midst_df['flow_id'].isin(bursty_in_both)]
    baseline_non_bursty = baseline_df[baseline_df['flow_id'].isin(non_bursty_in_both)]
    midst_non_bursty = midst_df[midst_df['flow_id'].isin(non_bursty_in_both)]

    # Add size categories
    midst_df['size_cat'] = midst_df['total_bytes'].apply(categorize_size)
    midst_df['is_bursty'] = midst_df['flow_id'].isin(bursty_flow_ids)

    # Create figure with 2x2 subplots
    fig, axes = plt.subplots(2, 2, figsize=(14, 10))

    # ===== Plot 1: Bursty vs Non-Bursty by Size Category =====
    ax1 = axes[0, 0]

    categories = ['Small\n(<100KB)', 'Medium\n(100KB-1MB)', 'Large\n(>=1MB)']
    x = np.arange(len(categories))
    width = 0.35

    bursty_counts = []
    non_bursty_counts = []
    for cat in categories:
        bursty_counts.append(len(midst_df[(midst_df['size_cat'] == cat) & (midst_df['is_bursty'])]))
        non_bursty_counts.append(len(midst_df[(midst_df['size_cat'] == cat) & (~midst_df['is_bursty'])]))

    bars1 = ax1.bar(x - width/2, bursty_counts, width, label='Bursty', color='#e74c3c', alpha=0.8, edgecolor='black')
    bars2 = ax1.bar(x + width/2, non_bursty_counts, width, label='Normal', color='#2ecc71', alpha=0.8, edgecolor='black')

    # Add count labels
    for bar, count in zip(bars1, bursty_counts):
        if count > 0:
            ax1.annotate(f'{count}',
                        xy=(bar.get_x() + bar.get_width()/2, bar.get_height()),
                        xytext=(0, 3), textcoords='offset points',
                        ha='center', va='bottom', fontsize=10, fontweight='bold')
    for bar, count in zip(bars2, non_bursty_counts):
        if count > 0:
            ax1.annotate(f'{count:,}',
                        xy=(bar.get_x() + bar.get_width()/2, bar.get_height()),
                        xytext=(0, 3), textcoords='offset points',
                        ha='center', va='bottom', fontsize=10, fontweight='bold')

    ax1.set_ylabel('Number of Flows', fontsize=11)
    ax1.set_xticks(x)
    ax1.set_xticklabels(categories, fontsize=10)
    ax1.set_title('What MIDST Detected:\nLarge Flows Are All Bursty', fontsize=12, fontweight='bold')
    ax1.legend(loc='upper right', fontsize=10)
    ax1.set_yscale('log')  # Log scale because of big difference
    ax1.grid(axis='y', alpha=0.3)

    # ===== Plot 2: Bursty Flows - Before vs After =====
    ax2 = axes[0, 1]

    baseline_bursty_fct = baseline_bursty['fct_us'].mean()
    midst_bursty_fct = midst_bursty['fct_us'].mean()

    x_pos = np.arange(2)
    width = 0.6
    fcts = [baseline_bursty_fct, midst_bursty_fct]
    colors = ['#3498db', '#9b59b6']

    bars = ax2.bar(x_pos, fcts, width, color=colors, alpha=0.8, edgecolor='black')
    for bar, fct in zip(bars, fcts):
        ax2.annotate(f'{fct:,.0f} µs',
                    xy=(bar.get_x() + bar.get_width()/2, bar.get_height()),
                    xytext=(0, 5), textcoords='offset points',
                    ha='center', va='bottom', fontsize=11, fontweight='bold')

    improvement = (baseline_bursty_fct - midst_bursty_fct) / baseline_bursty_fct * 100
    ax2.set_ylabel('Average FCT (µs)', fontsize=11)
    ax2.set_xticks(x_pos)
    ax2.set_xticklabels(['Before\n(SP-PIFO)', 'After\n(SP-PIFO + MIDST)'], fontsize=11)
    ax2.set_title(f'Bursty Flows ({len(bursty_in_both)} flows): -{improvement:.0f}%', fontsize=12, fontweight='bold')
    ax2.set_ylim(bottom=0)
    ax2.grid(axis='y', alpha=0.3)

    # ===== Plot 3: Non-Bursty Flows - Before vs After =====
    ax3 = axes[1, 0]

    baseline_non_bursty_fct = baseline_non_bursty['fct_us'].mean()
    midst_non_bursty_fct = midst_non_bursty['fct_us'].mean()

    fcts = [baseline_non_bursty_fct, midst_non_bursty_fct]
    colors = ['#3498db', '#9b59b6']

    bars = ax3.bar(x_pos, fcts, width, color=colors, alpha=0.8, edgecolor='black')
    for bar, fct in zip(bars, fcts):
        ax3.annotate(f'{fct:,.0f} µs',
                    xy=(bar.get_x() + bar.get_width()/2, bar.get_height()),
                    xytext=(0, 5), textcoords='offset points',
                    ha='center', va='bottom', fontsize=11, fontweight='bold')

    improvement = (baseline_non_bursty_fct - midst_non_bursty_fct) / baseline_non_bursty_fct * 100
    ax3.set_ylabel('Average FCT (µs)', fontsize=11)
    ax3.set_xticks(x_pos)
    ax3.set_xticklabels(['Before\n(SP-PIFO)', 'After\n(SP-PIFO + MIDST)'], fontsize=11)
    ax3.set_title(f'Normal Flows ({len(non_bursty_in_both):,} flows): -{improvement:.0f}%', fontsize=12, fontweight='bold')
    ax3.set_ylim(bottom=0)
    ax3.grid(axis='y', alpha=0.3)

    # ===== Plot 4: Summary - Before/After for Both =====
    ax4 = axes[1, 1]

    x = np.arange(2)
    width = 0.35

    # Before (baseline)
    before_fcts = [baseline_bursty_fct, baseline_non_bursty_fct]
    # After (MIDST)
    after_fcts = [midst_bursty_fct, midst_non_bursty_fct]

    bars1 = ax4.bar(x - width/2, before_fcts, width, label='Before (SP-PIFO)', color='#3498db', alpha=0.8, edgecolor='black')
    bars2 = ax4.bar(x + width/2, after_fcts, width, label='After (SP-PIFO + MIDST)', color='#9b59b6', alpha=0.8, edgecolor='black')

    # Add value labels
    for bar, fct in zip(bars1, before_fcts):
        ax4.annotate(f'{fct/1000:.0f}ms',
                    xy=(bar.get_x() + bar.get_width()/2, bar.get_height()),
                    xytext=(0, 3), textcoords='offset points',
                    ha='center', va='bottom', fontsize=9)
    for bar, fct in zip(bars2, after_fcts):
        ax4.annotate(f'{fct/1000:.0f}ms',
                    xy=(bar.get_x() + bar.get_width()/2, bar.get_height()),
                    xytext=(0, 3), textcoords='offset points',
                    ha='center', va='bottom', fontsize=9)

    # Add improvement percentages
    for i, (b, a) in enumerate(zip(before_fcts, after_fcts)):
        improvement = (b - a) / b * 100
        ax4.annotate(f'-{improvement:.0f}%',
                    xy=(x[i], max(b, a)),
                    xytext=(0, 25), textcoords='offset points',
                    ha='center', va='bottom', fontsize=12, fontweight='bold', color='#2e7d32')

    ax4.set_ylabel('Average FCT (µs)', fontsize=11)
    ax4.set_xticks(x)
    ax4.set_xticklabels(['Bursty Flows\n(42)', 'Normal Flows\n(13,995)'], fontsize=11)
    ax4.set_title('Summary: Both Flow Types Benefit', fontsize=12, fontweight='bold')
    ax4.legend(loc='upper right', fontsize=10)
    ax4.set_ylim(bottom=0, top=max(before_fcts)*1.3)
    ax4.grid(axis='y', alpha=0.3)

    plt.suptitle('MIDST: Better Congestion Management Benefits All Flows',
                 fontsize=16, fontweight='bold', y=1.02)
    plt.tight_layout()

    output_path = OUTPUT_DIR / 'midst_bursty_flow_story.png'
    fig.savefig(output_path, dpi=300, bbox_inches='tight')
    plt.close(fig)
    print(f"Saved: {output_path}")
    return output_path


if __name__ == '__main__':
    create_bursty_story_chart()
