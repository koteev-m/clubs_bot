{
  "title": "Payments Observability",
  "timezone": "browser",
  "panels": [
    {
      "type": "graph",
      "title": "Payments SLO Error Rate",
      "description": "TODO: refine thresholds for acceptable error budgets.",
      "targets": [
        {
          "expr": "sum(rate(payments_errors_total{path=~\"finalize|cancel|refund\",kind!=\"validation\"}[5m])) / sum(rate({__name__=~\"payments_(finalize|cancel|refund)_duration_seconds_count\"}[5m]))"
        }
      ]
    },
    {
      "type": "graph",
      "title": "Payments P95 Latency",
      "description": "TODO: adjust percentile histograms if needed.",
      "targets": [
        {
          "expr": "histogram_quantile(0.95, sum(rate({__name__=~\"payments_(finalize|cancel|refund)_duration_seconds_bucket\"}[5m])) by (le,path))",
          "legendFormat": "{{path}}"
        }
      ]
    },
    {
      "type": "graph",
      "title": "Idempotent Hit Rate",
      "description": "TODO: tune alerting threshold for anomaly detection.",
      "targets": [
        {
          "expr": "sum(rate(payments_idempotent_hit_total[5m])) by (path) / sum(rate({__name__=~\"payments_(finalize|cancel|refund)_duration_seconds_count\"}[5m])) by (path)",
          "legendFormat": "{{path}}"
        }
      ]
    },
    {
      "type": "graph",
      "title": "Refund Remainder (Debug)",
      "description": "TODO: enable only when PAYMENTS_DEBUG_GAUGES is active.",
      "targets": [
        {
          "expr": "payments_refund_remainder{bookingId=~\".*\"}"
        }
      ]
    }
  ],
  "time": {
    "from": "now-6h",
    "to": "now"
  }
}
