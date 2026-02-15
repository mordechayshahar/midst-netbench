# Visualization Outputs

Key figures from MIDST thesis experiments analyzing fat-tree topology performance.

## Main Results Figures

These figures summarize the key findings across all schedulers and the MIDST enhancement:

- **01_fct_cdf_comparison.png** - Flow Completion Time CDF overlays comparing baseline vs MIDST-enhanced variants
- **02_p99_improvement_bars.png** - P99 latency improvement percentage for each scheduler
- **03_tail_latency_breakdown.png** - Tail latency (P50, P95, P99) comparison across schedulers
- **04_queue_residence_cdf.png** - Queue residence time distributions
- **05_retransmission_breakdown.png** - Packet retransmission analysis across scenarios
- **06_bursty_classification.png** - Performance split by bursty vs non-bursty flows
- **07_fanout_amplification.png** - Flow fanout and amplification effects
- **08_slowdown_comparison.png** - Per-flow slowdown metrics
- **09_fairness_comparison.png** - Fairness metrics (Jain index) across schedulers
- **10_executive_summary.png** - Executive summary dashboard

## Article Figures

Publication-ready figures from the thesis, including:
- **fig1_topology.png** - Fat-tree topology diagram
- **fig2_midst_architecture.png** - MIDST architectural overview
- **fig3_metrics_comparison.png** - Comprehensive metrics table
- **fig4_fct_cdf.png** - FCT CDF with annotations
- **fig5_fct_by_size.png** - FCT breakdown by flow size
- **fig6_summary_dashboard.png** - Summary performance dashboard

## E44-E49 Plots

Specialized plots for each experiment pair:

### PACKS (E44-E45)
- Baseline (E44) vs MIDST-enhanced (E45) comparisons

### AIFO (E46-E47)
- Baseline (E46) vs MIDST-enhanced (E47) comparisons

### SP-PIFO (E48-E49)
- Baseline (E48) vs MIDST-enhanced (E49) comparisons

### Cross-Scheduler
- Comparison across all three scheduler families

## Figure Generation

To regenerate figures from raw experiment results:

```bash
cd ../analysis
python generate_article_figures.py
python create_cdf.py
```

All figures are in PNG format optimized for both screen display and printing.
