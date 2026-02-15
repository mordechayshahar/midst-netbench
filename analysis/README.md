# Analysis Scripts

Python scripts for processing and analyzing MIDST experiment results.

## Key Scripts

### CDF Generation
- **create_cdf.py** - Generate Flow Completion Time (FCT) cumulative distribution functions
- **multi_create_cdf.py** - Batch CDF generation for multiple experiments

### Comprehensive Analysis
- **analyze_all_15_metrics.py** - Compute 15 different performance metrics (FCT, latency, queue depth, etc.)
- **analyze_enhanced_metrics.py** - Extended metric analysis with MIDST-specific measures
- **analyze.py** - Basic analysis of experiment outputs
- **analyze_1s.py** - 1-second window analysis for time-series breakdown

### Specialized Analysis
- **deep_research_metrics.py** - In-depth metric extraction and comparison (flow size analysis, retransmissions, fairness)
- **generate_bursty_story.py** - Analyze flows by burstiness classification
- **generate_article_figures.py** - Generate publication-ready figures

### Utilities
- **getstatistics.py** - Extract statistical summaries

## Usage

Typical workflow for analyzing E44-E49 experiments:

```bash
# 1. Generate CDFs for flow completion times
python create_cdf.py --input E44_results.csv --output E44_cdf.png

# 2. Analyze all metrics
python analyze_all_15_metrics.py --config E44_packs_baseline.properties

# 3. Compare bursty vs non-bursty flows
python generate_bursty_story.py --results E44_results.csv

# 4. Generate publication-ready figures
python generate_article_figures.py --experiments E44 E45 E46 E47 E48 E49
```

## Output

Scripts generate:
- PNG/PDF visualization files (CDFs, bar charts, heatmaps)
- CSV summary tables with statistics
- JSON metric files for further processing

See `../figures/` for example outputs.
