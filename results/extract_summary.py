#!/usr/bin/env python3
"""
Extract key experiment metrics from netbench simulation results.

Reads flow_completion.csv and statistics.log from each experiment directory
and generates summary statistics suitable for analysis and git version control.
"""

import os
import csv
import json
from pathlib import Path
from collections import defaultdict
import statistics as stats_module

# Define experiment directories
BASE_PATH = "/sessions/compassionate-nifty-edison/mnt/netbench-thesis/netbench-thesis-clean/temp/new_experiments/fattree"
RESULTS_PATH = "/sessions/compassionate-nifty-edison/mnt/netbench-thesis/netbench-thesis-clean/results"

EXPERIMENTS = [
    "e44_packs_baseline_no_srpt_fattree_100",
    "e45_packs_midst_no_srpt_fattree_100",
    "e46_aifo_baseline_no_srpt_fattree_100",
    "e47_aifo_midst_no_srpt_fattree_100",
    "e48_sppifo_baseline_no_srpt_fattree_100",
    "e49_sppifo_midst_no_srpt_fattree_100",
]


def extract_flow_metrics(exp_dir):
    """
    Extract FCT metrics from flow_completion.csv.log

    Format (columns):
    0: flow_id
    1: source
    2: destination
    3: size_requested
    4: size_completed
    5: start_time
    6: end_time
    7: fct (end_time - start_time)
    8: completed
    9-11: flags

    Returns: dict with flow completion metrics
    """
    flow_file = os.path.join(exp_dir, "flow_completion.csv.log")

    if not os.path.exists(flow_file):
        return None

    fcts = []
    flows_completed = 0
    flows_total = 0

    try:
        with open(flow_file, 'r') as f:
            reader = csv.reader(f)
            for row in reader:
                flows_total += 1
                if len(row) >= 9:
                    # Column 7 is FCT in nanoseconds
                    fct_ns = int(row[7])
                    # Column 8 is completion status (TRUE/FALSE)
                    completed = row[8].upper() == "TRUE"

                    if completed:
                        flows_completed += 1
                        # Convert nanoseconds to milliseconds
                        fct_ms = fct_ns / 1_000_000
                        fcts.append(fct_ms)
    except Exception as e:
        print(f"Error reading flow file {flow_file}: {e}")
        return None

    if not fcts:
        return None

    # Calculate statistics
    fcts_sorted = sorted(fcts)
    n = len(fcts_sorted)

    result = {
        'flows_completed': flows_completed,
        'flows_total': flows_total,
        'mean_fct_ms': stats_module.mean(fcts),
        'median_fct_ms': stats_module.median(fcts),
        'p99_fct_ms': fcts_sorted[int(n * 0.99)] if n > 0 else 0,
        'p95_fct_ms': fcts_sorted[int(n * 0.95)] if n > 0 else 0,
        'p90_fct_ms': fcts_sorted[int(n * 0.90)] if n > 0 else 0,
        'min_fct_ms': min(fcts),
        'max_fct_ms': max(fcts),
    }

    return result


def extract_statistics(exp_dir):
    """
    Extract packet and error statistics from statistics.log

    Returns: dict with all statistics from the log file
    """
    stats_file = os.path.join(exp_dir, "statistics.log")

    if not os.path.exists(stats_file):
        return {}

    stats_dict = {}

    try:
        with open(stats_file, 'r') as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                # Format: "KEY: VALUE" (may be prefixed with "N→" in some versions)
                if ':' in line:
                    # Handle both formats: "N→KEY: VALUE" and "KEY: VALUE"
                    if '→' in line:
                        parts = line.split('→', 1)
                        rest = parts[1] if len(parts) == 2 else line
                    else:
                        rest = line

                    if ':' in rest:
                        key, value = rest.split(':', 1)
                        key = key.strip()
                        value = value.strip()
                        try:
                            stats_dict[key] = int(value)
                        except ValueError:
                            stats_dict[key] = value
    except Exception as e:
        print(f"Error reading statistics file {stats_file}: {e}")

    return stats_dict


def process_experiment(exp_name):
    """
    Process a single experiment and extract all metrics.

    Returns: dict with all extracted metrics
    """
    exp_dir = os.path.join(BASE_PATH, exp_name)

    if not os.path.isdir(exp_dir):
        print(f"Warning: Directory not found: {exp_dir}")
        return None

    print(f"Processing {exp_name}...", end=" ")

    # Extract flow metrics
    flow_metrics = extract_flow_metrics(exp_dir)
    if flow_metrics is None:
        print("FAILED (no flow data)")
        return None

    # Extract statistics
    stats = extract_statistics(exp_dir)

    # Combine results
    result = {
        'experiment': exp_name,
        'flow_metrics': flow_metrics,
        'statistics': stats,
    }

    print("OK")
    return result


def write_summary_csv(results):
    """
    Write all experiments to a single CSV summary file.
    """
    output_file = os.path.join(RESULTS_PATH, "summary.csv")

    # Collect all unique keys from all experiments
    flow_keys = set()
    stat_keys = set()

    for result in results:
        if result:
            flow_keys.update(result['flow_metrics'].keys())
            stat_keys.update(result['statistics'].keys())

    flow_keys = sorted(flow_keys)
    stat_keys = sorted(stat_keys)

    # Write CSV
    try:
        with open(output_file, 'w', newline='') as f:
            writer = csv.DictWriter(
                f,
                fieldnames=['experiment'] + flow_keys + stat_keys
            )
            writer.writeheader()

            for result in results:
                if result:
                    row = {'experiment': result['experiment']}
                    row.update(result['flow_metrics'])

                    # Convert statistics values to strings
                    stats_str = {k: str(v) if v is not None else ''
                                for k, v in result['statistics'].items()}
                    row.update(stats_str)

                    writer.writerow(row)

        print(f"Written: {output_file}")
    except Exception as e:
        print(f"Error writing CSV: {e}")


def write_individual_summaries(results):
    """
    Write individual summary files for each experiment.
    """
    for result in results:
        if not result:
            continue

        exp_name = result['experiment']
        output_file = os.path.join(RESULTS_PATH, f"{exp_name}_summary.txt")

        try:
            with open(output_file, 'w') as f:
                f.write(f"Experiment: {exp_name}\n")
                f.write("=" * 80 + "\n\n")

                # Flow metrics
                f.write("FLOW COMPLETION TIME METRICS\n")
                f.write("-" * 80 + "\n")
                fm = result['flow_metrics']
                f.write(f"Total flows completed:      {fm['flows_completed']:>15,}\n")
                f.write(f"Total flows:                {fm['flows_total']:>15,}\n")
                f.write(f"Mean FCT (ms):              {fm['mean_fct_ms']:>15,.2f}\n")
                f.write(f"Median FCT (ms):            {fm['median_fct_ms']:>15,.2f}\n")
                f.write(f"P95 FCT (ms):               {fm['p95_fct_ms']:>15,.2f}\n")
                f.write(f"P99 FCT (ms):               {fm['p99_fct_ms']:>15,.2f}\n")
                f.write(f"Min FCT (ms):               {fm['min_fct_ms']:>15,.2f}\n")
                f.write(f"Max FCT (ms):               {fm['max_fct_ms']:>15,.2f}\n")
                f.write("\n")

                # Statistics
                f.write("PACKET AND ERROR STATISTICS\n")
                f.write("-" * 80 + "\n")
                stats = result['statistics']

                # Group statistics for readability
                packet_stats = {k: v for k, v in stats.items()
                               if 'PACKET' in k or 'ENQUEUED' in k}
                drop_stats = {k: v for k, v in stats.items()
                             if 'DROPPED' in k}
                tcp_stats = {k: v for k, v in stats.items()
                            if 'TCP' in k or 'RETRANSMIT' in k or 'RTO' in k}
                midst_stats = {k: v for k, v in stats.items()
                              if 'MIDST' in k}

                if packet_stats:
                    f.write("Packet Statistics:\n")
                    for key in sorted(packet_stats.keys()):
                        f.write(f"  {key:<40} {packet_stats[key]:>15,}\n")
                    f.write("\n")

                if drop_stats:
                    f.write("Dropped Packets:\n")
                    for key in sorted(drop_stats.keys()):
                        f.write(f"  {key:<40} {drop_stats[key]:>15,}\n")
                    f.write("\n")

                if tcp_stats:
                    f.write("TCP Retransmission Statistics:\n")
                    for key in sorted(tcp_stats.keys()):
                        f.write(f"  {key:<40} {tcp_stats[key]:>15,}\n")
                    f.write("\n")

                if midst_stats:
                    f.write("MIDST-Specific Statistics:\n")
                    for key in sorted(midst_stats.keys()):
                        f.write(f"  {key:<40} {midst_stats[key]:>15,}\n")
                    f.write("\n")

                # Summary metrics
                if tcp_stats and 'TOTAL_PACKETS_ENQUEUED' in stats:
                    f.write("KEY METRICS\n")
                    f.write("-" * 80 + "\n")
                    total_enqueued = stats.get('TOTAL_PACKETS_ENQUEUED', 1)
                    total_dropped = (stats.get('PACKETS_DROPPED_BURSTY_GT', 0) +
                                    stats.get('PACKETS_DROPPED_NORMAL_GT', 0) +
                                    stats.get('PACKETS_DROPPED_QUEUE_REJECT', 0))
                    total_rto = stats.get('TCP_RETRANSMISSION_TIMEOUT', 0)
                    total_fast_retrans = stats.get('TCP_FAST_RETRANSMIT', 0)

                    f.write(f"Total packets dropped:      {total_dropped:>15,}\n")
                    f.write(f"Packet drop rate:           {(total_dropped/total_enqueued*100):>14.2f}%\n")
                    f.write(f"RTO count:                  {total_rto:>15,}\n")
                    f.write(f"Fast retransmit count:      {total_fast_retrans:>15,}\n")

        except Exception as e:
            print(f"Error writing summary for {exp_name}: {e}")


def write_json_summary(results):
    """
    Write a JSON summary for programmatic access.
    """
    output_file = os.path.join(RESULTS_PATH, "summary.json")

    try:
        json_data = []
        for result in results:
            if result:
                json_data.append({
                    'experiment': result['experiment'],
                    'flow_metrics': result['flow_metrics'],
                    'statistics': result['statistics'],
                })

        with open(output_file, 'w') as f:
            json.dump(json_data, f, indent=2)

        print(f"Written: {output_file}")
    except Exception as e:
        print(f"Error writing JSON: {e}")


def main():
    """
    Main entry point: extract all experiments and generate summaries.
    """
    print("=" * 80)
    print("Extracting experiment metrics...")
    print("=" * 80)

    results = []
    for exp_name in EXPERIMENTS:
        result = process_experiment(exp_name)
        results.append(result)

    print("\n" + "=" * 80)
    print("Generating summary files...")
    print("=" * 80 + "\n")

    # Write outputs
    write_summary_csv(results)
    write_json_summary(results)
    write_individual_summaries(results)

    print("\n" + "=" * 80)
    print("Summary:")
    print("=" * 80)
    print(f"Processed experiments: {sum(1 for r in results if r)}/{len(EXPERIMENTS)}")
    print(f"Results directory: {RESULTS_PATH}")
    print(f"\nGenerated files:")
    print(f"  - summary.csv (aggregated metrics)")
    print(f"  - summary.json (machine-readable)")
    for exp_name in EXPERIMENTS:
        print(f"  - {exp_name}_summary.txt (human-readable)")


if __name__ == "__main__":
    main()
