#!/usr/bin/env python3
"""
Comprehensive Analysis Script for All 15 MIDST Metrics
======================================================

This script analyzes all 15 metrics added to the NetBench simulation:

HIGH PRIORITY:
1. Queue Residence Time - Time packets spend in queue
2. Congestion Episode Duration - How long congestion events last
3. Bursty Flow FCT Tracking - FCT comparison for bursty vs normal flows
4. Packet Drop by Flow - Which flows experience drops
5. Count-Min Sketch Accuracy - CMS estimation error

MEDIUM PRIORITY:
6. Rank/Priority Distribution - Base vs final rank analysis
7. Per-Queue Dequeue Count - Dequeue distribution across queues
8. Penalty Duration per Flow - How long flows stay penalized
9. Bytes During Congestion - Traffic volume during congestion
10. Concurrent Penalized Flows - Max simultaneous penalized flows

LOW PRIORITY:
11. Sketch Reset Count - How often CMS is reset
12. Hash Collision Estimate - Collision rate in CMS
13. Queue Overflow Events - Drop events (already existed)
14. RTT Samples - TCP RTT measurements
15. Congestion Window Evolution - TCP cwnd changes

Usage:
    python analyze_all_15_metrics.py [--midst-dir DIR] [--baseline-dir DIR]
"""

import os
import re
import sys
import argparse
from collections import defaultdict
import numpy as np

# Optional matplotlib for visualization
try:
    import matplotlib.pyplot as plt
    HAS_MATPLOTLIB = True
except ImportError:
    HAS_MATPLOTLIB = False
    print("Warning: matplotlib not available, skipping visualizations")


def parse_log_file(filepath):
    """Parse a log file and return entries grouped by log type."""
    entries = defaultdict(list)

    if not os.path.exists(filepath):
        return entries

    with open(filepath, 'r') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue

            # Log format: TYPE,values...
            parts = line.split(',', 1)
            if len(parts) >= 1:
                log_type = parts[0]
                values = parts[1] if len(parts) > 1 else ""
                entries[log_type].append(values)

    return entries


def analyze_queue_residence_time(log_entries):
    """HIGH #1: Analyze queue residence time from QUEUE_RESIDENCE_TIME logs."""
    print("\n" + "="*60)
    print("HIGH #1: QUEUE RESIDENCE TIME ANALYSIS")
    print("="*60)

    residence_times = []
    per_queue_times = defaultdict(list)

    for entry in log_entries.get('QUEUE_RESIDENCE_TIME', []):
        # Format: flowId,residenceTimeNs,queueIndex,timestamp
        parts = entry.split(',')
        if len(parts) >= 3:
            try:
                residence_ns = int(parts[1])
                queue_idx = int(parts[2])
                residence_times.append(residence_ns)
                per_queue_times[queue_idx].append(residence_ns)
            except ValueError:
                continue

    if residence_times:
        residence_times = np.array(residence_times)
        print(f"Total packets tracked: {len(residence_times)}")
        print(f"Average residence time: {np.mean(residence_times)/1e6:.3f} ms")
        print(f"Median residence time: {np.median(residence_times)/1e6:.3f} ms")
        print(f"95th percentile: {np.percentile(residence_times, 95)/1e6:.3f} ms")
        print(f"99th percentile: {np.percentile(residence_times, 99)/1e6:.3f} ms")
        print(f"Max residence time: {np.max(residence_times)/1e6:.3f} ms")

        print("\nPer-queue residence times:")
        for q in sorted(per_queue_times.keys()):
            times = np.array(per_queue_times[q])
            print(f"  Queue {q}: avg={np.mean(times)/1e6:.3f}ms, "
                  f"median={np.median(times)/1e6:.3f}ms, "
                  f"count={len(times)}")
    else:
        print("No queue residence time data found")

    return residence_times


def analyze_congestion_episodes(log_entries):
    """HIGH #2: Analyze congestion episode durations."""
    print("\n" + "="*60)
    print("HIGH #2: CONGESTION EPISODE DURATION ANALYSIS")
    print("="*60)

    durations = []

    for entry in log_entries.get('CONGESTION_EPISODE_END', []):
        # Format: endTime,durationNs,queueSize
        parts = entry.split(',')
        if len(parts) >= 2:
            try:
                duration_ns = int(parts[1])
                durations.append(duration_ns)
            except ValueError:
                continue

    # Also count episodes from statistics
    episode_count = len(log_entries.get('CONGESTION_EPISODE_START', []))

    if durations:
        durations = np.array(durations)
        print(f"Total congestion episodes: {episode_count}")
        print(f"Episodes with duration data: {len(durations)}")
        print(f"Average duration: {np.mean(durations)/1e6:.3f} ms")
        print(f"Median duration: {np.median(durations)/1e6:.3f} ms")
        print(f"Max duration: {np.max(durations)/1e6:.3f} ms")
        print(f"Total time in congestion: {np.sum(durations)/1e9:.3f} seconds")
    else:
        print(f"Total congestion episodes (from start events): {episode_count}")
        print("No duration data available")

    return durations


def analyze_bursty_flow_fct(log_entries, fct_file=None):
    """HIGH #3: Compare FCT for bursty vs normal flows."""
    print("\n" + "="*60)
    print("HIGH #3: BURSTY FLOW FCT TRACKING")
    print("="*60)

    # Get bursty flow IDs
    bursty_flows = set()
    for entry in log_entries.get('BURSTY_FLOW_IDENTIFIED', []):
        # Format: flowId,timestamp
        parts = entry.split(',')
        if len(parts) >= 1:
            try:
                flow_id = int(parts[0])
                bursty_flows.add(flow_id)
            except ValueError:
                continue

    print(f"Total bursty flows identified: {len(bursty_flows)}")

    # If FCT file provided, compare FCT
    if fct_file and os.path.exists(fct_file):
        bursty_fcts = []
        normal_fcts = []

        with open(fct_file, 'r') as f:
            for line in f:
                parts = line.strip().split(',')
                if len(parts) >= 4:
                    try:
                        flow_id = int(parts[0])
                        completed = parts[3].strip().lower()
                        if completed == 'true':
                            fct_ns = int(parts[2])
                            if flow_id in bursty_flows:
                                bursty_fcts.append(fct_ns)
                            else:
                                normal_fcts.append(fct_ns)
                    except ValueError:
                        continue

        if bursty_fcts and normal_fcts:
            bursty_fcts = np.array(bursty_fcts)
            normal_fcts = np.array(normal_fcts)

            print(f"\nBursty flows FCT ({len(bursty_fcts)} flows):")
            print(f"  Average: {np.mean(bursty_fcts)/1e6:.3f} ms")
            print(f"  Median: {np.median(bursty_fcts)/1e6:.3f} ms")

            print(f"\nNormal flows FCT ({len(normal_fcts)} flows):")
            print(f"  Average: {np.mean(normal_fcts)/1e6:.3f} ms")
            print(f"  Median: {np.median(normal_fcts)/1e6:.3f} ms")

            # Calculate impact
            if len(bursty_fcts) > 0:
                bursty_penalty = np.mean(bursty_fcts) / np.mean(normal_fcts)
                print(f"\nBursty flows FCT is {bursty_penalty:.2f}x normal flows (expected > 1 due to penalty)")

    return bursty_flows


def analyze_drops_by_flow(log_entries):
    """HIGH #4: Analyze packet drops by flow."""
    print("\n" + "="*60)
    print("HIGH #4: PACKET DROP BY FLOW ANALYSIS")
    print("="*60)

    drops_per_flow = defaultdict(int)

    for entry in log_entries.get('SPPIFO_DROP_FLOW', []):
        # Format: flowId,timestamp,queueIndex
        parts = entry.split(',')
        if len(parts) >= 1:
            try:
                flow_id = int(parts[0])
                drops_per_flow[flow_id] += 1
            except ValueError:
                continue

    if drops_per_flow:
        total_drops = sum(drops_per_flow.values())
        flows_with_drops = len(drops_per_flow)

        print(f"Total drops: {total_drops}")
        print(f"Flows experiencing drops: {flows_with_drops}")
        print(f"Average drops per affected flow: {total_drops/flows_with_drops:.2f}")

        # Top 10 flows with most drops
        sorted_flows = sorted(drops_per_flow.items(), key=lambda x: x[1], reverse=True)[:10]
        print("\nTop 10 flows by drops:")
        for flow_id, drops in sorted_flows:
            print(f"  Flow {flow_id}: {drops} drops")
    else:
        print("No per-flow drop data found")

    return drops_per_flow


def analyze_cms_accuracy(log_entries):
    """HIGH #5: Analyze Count-Min Sketch accuracy."""
    print("\n" + "="*60)
    print("HIGH #5: COUNT-MIN SKETCH ACCURACY")
    print("="*60)

    errors = []
    relative_errors = []

    for entry in log_entries.get('CMS_ACCURACY', []):
        # Format: flowId,trueCount,estimatedCount,absError,timestamp
        parts = entry.split(',')
        if len(parts) >= 4:
            try:
                true_count = int(parts[1])
                estimated = int(parts[2])
                abs_error = int(parts[3])
                errors.append(abs_error)
                if true_count > 0:
                    relative_errors.append(abs_error / true_count)
            except ValueError:
                continue

    # Window-level accuracy
    window_errors = []
    window_rel_errors = []

    for entry in log_entries.get('CMS_ACCURACY_WINDOW', []):
        # Format: flowsCompared,avgError,relativeError,timestamp
        parts = entry.split(',')
        if len(parts) >= 3:
            try:
                avg_error = float(parts[1])
                rel_error = float(parts[2])
                window_errors.append(avg_error)
                window_rel_errors.append(rel_error)
            except ValueError:
                continue

    if errors:
        errors = np.array(errors)
        relative_errors = np.array(relative_errors)

        print(f"Per-flow measurements: {len(errors)}")
        print(f"Average absolute error: {np.mean(errors):.2f} packets")
        print(f"Average relative error: {np.mean(relative_errors)*100:.2f}%")
        print(f"Median relative error: {np.median(relative_errors)*100:.2f}%")
        print(f"Max absolute error: {np.max(errors)} packets")

    if window_errors:
        print(f"\nPer-window measurements: {len(window_errors)}")
        print(f"Average window error: {np.mean(window_errors):.2f}")
        print(f"Average window relative error: {np.mean(window_rel_errors)*100:.2f}%")

    if not errors and not window_errors:
        print("No CMS accuracy data found")

    return errors, relative_errors


def analyze_rank_distribution(log_entries):
    """MED #6: Analyze rank/priority distribution."""
    print("\n" + "="*60)
    print("MED #6: RANK/PRIORITY DISTRIBUTION")
    print("="*60)

    # Look for final metric summary
    for entry in log_entries.get('FINAL_METRIC_RANK_DISTRIBUTION', []):
        print(f"Summary: {entry}")

    # Analyze penalty events
    penalties = []
    for entry in log_entries.get('MIDST_PENALTY', []):
        # Format: timestamp,flowId,baseRank,penaltyAmount,finalRank
        parts = entry.split(',')
        if len(parts) >= 5:
            try:
                base_rank = int(parts[2])
                penalty = int(parts[3])
                final_rank = int(parts[4])
                penalties.append((base_rank, penalty, final_rank))
            except ValueError:
                continue

    if penalties:
        base_ranks = [p[0] for p in penalties]
        penalty_amounts = [p[1] for p in penalties]
        final_ranks = [p[2] for p in penalties]

        print(f"Penalty events: {len(penalties)}")
        print(f"Average base rank: {np.mean(base_ranks):.2f}")
        print(f"Average penalty amount: {np.mean(penalty_amounts):.2f}")
        print(f"Average final rank: {np.mean(final_ranks):.2f}")
    else:
        print("No detailed penalty data found")


def analyze_per_queue_dequeue(log_entries, statistics):
    """MED #7: Analyze per-queue dequeue distribution."""
    print("\n" + "="*60)
    print("MED #7: PER-QUEUE DEQUEUE COUNT")
    print("="*60)

    # Get from statistics file
    dequeue_counts = {}
    for key, value in statistics.items():
        if key.startswith('SPPIFO_QUEUE_') and key.endswith('_DEQUEUED'):
            queue_idx = key.replace('SPPIFO_QUEUE_', '').replace('_DEQUEUED', '')
            dequeue_counts[int(queue_idx)] = value

    if dequeue_counts:
        total = sum(dequeue_counts.values())
        print(f"Total dequeued packets: {total}")
        print("\nPer-queue distribution:")
        for q in sorted(dequeue_counts.keys()):
            count = dequeue_counts[q]
            pct = 100 * count / total if total > 0 else 0
            print(f"  Queue {q}: {count} ({pct:.2f}%)")
    else:
        print("No per-queue dequeue data found in statistics")


def analyze_penalty_duration(log_entries):
    """MED #8: Analyze penalty duration per flow."""
    print("\n" + "="*60)
    print("MED #8: PENALTY DURATION PER FLOW")
    print("="*60)

    durations = []

    for entry in log_entries.get('FLOW_PENALTY_END', []):
        # Format: flowId,durationNs,timestamp
        parts = entry.split(',')
        if len(parts) >= 2:
            try:
                duration_ns = int(parts[1])
                durations.append(duration_ns)
            except ValueError:
                continue

    if durations:
        durations = np.array(durations)
        print(f"Flows with penalty duration data: {len(durations)}")
        print(f"Average penalty duration: {np.mean(durations)/1e6:.3f} ms")
        print(f"Median penalty duration: {np.median(durations)/1e6:.3f} ms")
        print(f"Max penalty duration: {np.max(durations)/1e6:.3f} ms")
        print(f"Total penalty time: {np.sum(durations)/1e9:.3f} seconds")
    else:
        print("No penalty duration data found")

    return durations


def analyze_bytes_during_congestion(log_entries, statistics):
    """MED #9: Analyze bytes during congestion."""
    print("\n" + "="*60)
    print("MED #9: BYTES DURING CONGESTION")
    print("="*60)

    bytes_per_episode = []

    for entry in log_entries.get('BYTES_DURING_CONGESTION_EPISODE', []):
        # Format: bytes,timestamp
        parts = entry.split(',')
        if len(parts) >= 1:
            try:
                bytes_count = int(parts[0])
                bytes_per_episode.append(bytes_count)
            except ValueError:
                continue

    if bytes_per_episode:
        bytes_per_episode = np.array(bytes_per_episode)
        print(f"Congestion episodes: {len(bytes_per_episode)}")
        print(f"Average bytes per episode: {np.mean(bytes_per_episode)/1e3:.2f} KB")
        print(f"Total bytes during congestion: {np.sum(bytes_per_episode)/1e6:.2f} MB")
    else:
        print("No bytes-during-congestion data found")


def analyze_concurrent_penalized(log_entries):
    """MED #10: Analyze concurrent penalized flows."""
    print("\n" + "="*60)
    print("MED #10: CONCURRENT PENALIZED FLOWS")
    print("="*60)

    concurrent_counts = []

    for entry in log_entries.get('CONCURRENT_PENALIZED_FLOWS', []):
        # Format: count,timestamp
        parts = entry.split(',')
        if len(parts) >= 1:
            try:
                count = int(parts[0])
                concurrent_counts.append(count)
            except ValueError:
                continue

    max_concurrent = []
    for entry in log_entries.get('MAX_CONCURRENT_PENALIZED', []):
        parts = entry.split(',')
        if len(parts) >= 1:
            try:
                max_concurrent.append(int(parts[0]))
            except ValueError:
                continue

    if concurrent_counts:
        print(f"Concurrent count samples: {len(concurrent_counts)}")
        print(f"Average concurrent penalized: {np.mean(concurrent_counts):.2f}")
        print(f"Max observed concurrent: {max(concurrent_counts)}")

    if max_concurrent:
        print(f"Final max concurrent penalized: {max(max_concurrent)}")

    if not concurrent_counts and not max_concurrent:
        print("No concurrent penalized flow data found")


def analyze_sketch_resets(log_entries, statistics):
    """LOW #11: Analyze sketch reset count."""
    print("\n" + "="*60)
    print("LOW #11: SKETCH RESET COUNT")
    print("="*60)

    reset_count = statistics.get('MIDST_SKETCH_RESETS', 0)
    print(f"Total sketch resets: {reset_count}")

    # Parse detailed reset data
    reset_details = []
    for entry in log_entries.get('SKETCH_RESET', []):
        # Format: resetCount,sinReset,soutReset,timestamp
        parts = entry.split(',')
        if len(parts) >= 3:
            try:
                sin_reset = int(parts[1])
                sout_reset = int(parts[2])
                reset_details.append((sin_reset, sout_reset))
            except ValueError:
                continue

    if reset_details:
        sin_resets = [r[0] for r in reset_details]
        sout_resets = [r[1] for r in reset_details]
        print(f"Average sin entries reset per window: {np.mean(sin_resets):.2f}")
        print(f"Average sout entries reset per window: {np.mean(sout_resets):.2f}")


def analyze_hash_collisions(log_entries):
    """LOW #12: Analyze hash collision estimate."""
    print("\n" + "="*60)
    print("LOW #12: HASH COLLISION ESTIMATE")
    print("="*60)

    collision_data = []

    for entry in log_entries.get('CMS_HASH_COLLISIONS', []):
        # Format: collisions,totalOps,collisionRate,timestamp
        parts = entry.split(',')
        if len(parts) >= 3:
            try:
                collisions = int(parts[0])
                total_ops = int(parts[1])
                rate = float(parts[2])
                collision_data.append((collisions, total_ops, rate))
            except ValueError:
                continue

    if collision_data:
        total_collisions = sum(c[0] for c in collision_data)
        total_ops = sum(c[1] for c in collision_data)
        rates = [c[2] for c in collision_data]

        print(f"Total collisions detected: {total_collisions}")
        print(f"Total hash operations: {total_ops}")
        print(f"Overall collision rate: {100*total_collisions/total_ops:.2f}%" if total_ops > 0 else "N/A")
        print(f"Average per-window collision rate: {100*np.mean(rates):.2f}%")
    else:
        print("No hash collision data found")


def analyze_rtt_samples(log_entries):
    """LOW #14: Analyze RTT samples."""
    print("\n" + "="*60)
    print("LOW #14: RTT SAMPLES")
    print("="*60)

    rtt_samples = []
    smoothed_rtts = []

    for entry in log_entries.get('TCP_RTT_SAMPLE', []):
        # Format: flowId,rawRtt,smoothedRtt,timestamp
        parts = entry.split(',')
        if len(parts) >= 3:
            try:
                raw_rtt = int(parts[1])
                smoothed_rtt = int(parts[2])
                rtt_samples.append(raw_rtt)
                smoothed_rtts.append(smoothed_rtt)
            except ValueError:
                continue

    if rtt_samples:
        rtt_samples = np.array(rtt_samples)
        smoothed_rtts = np.array(smoothed_rtts)

        print(f"Total RTT samples: {len(rtt_samples)}")
        print(f"Average raw RTT: {np.mean(rtt_samples)/1e6:.3f} ms")
        print(f"Median raw RTT: {np.median(rtt_samples)/1e6:.3f} ms")
        print(f"Average smoothed RTT: {np.mean(smoothed_rtts)/1e6:.3f} ms")
        print(f"99th percentile RTT: {np.percentile(rtt_samples, 99)/1e6:.3f} ms")
    else:
        print("No RTT sample data found")

    return rtt_samples


def analyze_cwnd_evolution(log_entries):
    """LOW #15: Analyze congestion window evolution."""
    print("\n" + "="*60)
    print("LOW #15: CONGESTION WINDOW EVOLUTION")
    print("="*60)

    cwnd_events = defaultdict(list)  # event_type -> list of cwnd values

    for entry in log_entries.get('TCP_CWND_CHANGE', []):
        # Format: flowId,cwnd,ssthresh,eventType,timestamp
        parts = entry.split(',')
        if len(parts) >= 4:
            try:
                cwnd = int(parts[1])
                event_type = parts[3]
                cwnd_events[event_type].append(cwnd)
            except ValueError:
                continue

    if cwnd_events:
        print("Congestion window change events by type:")
        for event_type in sorted(cwnd_events.keys()):
            values = cwnd_events[event_type]
            print(f"  {event_type}: {len(values)} events, "
                  f"avg cwnd={np.mean(values)/1e3:.1f}KB")

        all_cwnd = [v for values in cwnd_events.values() for v in values]
        if all_cwnd:
            print(f"\nOverall cwnd statistics:")
            print(f"  Average: {np.mean(all_cwnd)/1e3:.2f} KB")
            print(f"  Min: {np.min(all_cwnd)/1e3:.2f} KB")
            print(f"  Max: {np.max(all_cwnd)/1e3:.2f} KB")
    else:
        print("No cwnd change data found")

    return cwnd_events


def parse_statistics_file(filepath):
    """Parse statistics.log file."""
    stats = {}

    if not os.path.exists(filepath):
        return stats

    with open(filepath, 'r') as f:
        for line in f:
            line = line.strip()
            if ':' in line:
                # Format: KEY: VALUE
                parts = line.split(':')
                if len(parts) >= 2:
                    key = parts[0].strip()
                    try:
                        value = int(parts[1].strip())
                        stats[key] = value
                    except ValueError:
                        continue

    return stats


def main():
    parser = argparse.ArgumentParser(description='Analyze all 15 MIDST metrics')
    parser.add_argument('--midst-dir', default='temp/thesis_demo/phase4/midst_sppifo',
                        help='Directory containing MIDST simulation results')
    parser.add_argument('--baseline-dir', default='temp/thesis_demo/phase4/baseline_no_midst',
                        help='Directory containing baseline simulation results')
    parser.add_argument('--output-dir', default='analysis/metrics_analysis',
                        help='Directory to save analysis outputs')

    args = parser.parse_args()

    print("="*70)
    print("COMPREHENSIVE ANALYSIS OF ALL 15 MIDST METRICS")
    print("="*70)

    # Find log files
    midst_log_file = os.path.join(args.midst_dir, 'logs.txt')
    midst_stats_file = os.path.join(args.midst_dir, 'statistics.log')
    midst_fct_file = os.path.join(args.midst_dir, 'flow_completion.csv')

    print(f"\nLooking for MIDST log file: {midst_log_file}")
    print(f"Looking for MIDST stats file: {midst_stats_file}")

    # Parse files
    log_entries = parse_log_file(midst_log_file)
    statistics = parse_statistics_file(midst_stats_file)

    print(f"\nFound {len(log_entries)} log entry types")
    print(f"Found {len(statistics)} statistics entries")

    if log_entries:
        print("\nLog entry types found:")
        for log_type, entries in sorted(log_entries.items()):
            print(f"  {log_type}: {len(entries)} entries")

    # Run all analyses
    print("\n" + "="*70)
    print("RUNNING ALL 15 METRIC ANALYSES")
    print("="*70)

    # HIGH PRIORITY METRICS
    analyze_queue_residence_time(log_entries)
    analyze_congestion_episodes(log_entries)
    analyze_bursty_flow_fct(log_entries, midst_fct_file)
    analyze_drops_by_flow(log_entries)
    analyze_cms_accuracy(log_entries)

    # MEDIUM PRIORITY METRICS
    analyze_rank_distribution(log_entries)
    analyze_per_queue_dequeue(log_entries, statistics)
    analyze_penalty_duration(log_entries)
    analyze_bytes_during_congestion(log_entries, statistics)
    analyze_concurrent_penalized(log_entries)

    # LOW PRIORITY METRICS
    analyze_sketch_resets(log_entries, statistics)
    analyze_hash_collisions(log_entries)
    # #13 (Queue Overflow) is in statistics as PACKETS_DROPPED_QUEUE_REJECT
    print("\n" + "="*60)
    print("LOW #13: QUEUE OVERFLOW EVENTS")
    print("="*60)
    print(f"Queue reject drops: {statistics.get('PACKETS_DROPPED_QUEUE_REJECT', 0)}")
    print(f"SPPIFO total drops: {statistics.get('SPPIFO_DROPS_TOTAL', 0)}")

    analyze_rtt_samples(log_entries)
    analyze_cwnd_evolution(log_entries)

    print("\n" + "="*70)
    print("ANALYSIS COMPLETE")
    print("="*70)
    print("\nTo generate visualizations, run with --plot flag (requires matplotlib)")


if __name__ == '__main__':
    main()
