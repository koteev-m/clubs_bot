# Observability SLO (production)

## SLI/SLO контракт

| Домен | SLI | SLO | Error budget |
|---|---|---|---|
| Webhook ack | p95 `webhook_ack_latency` | ≤ 500ms за 28 дней | 1% времени выше порога |
| Webhook processing | p99 `webhook_process_latency` | ≤ 3s за 28 дней | 1% |
| Webhook dedup | доля `webhook_dedup_count` к ingress | ≤ 5% за 24ч (warning), ≤ 15% (critical) | 5% |
| Webhook queue lag | `webhook_queue_oldest_age` | ≤ 120s (p95) | 2% |
| Check-in API | p95 `ui_checkin_scan_duration_ms_seconds` | ≤ 1.5s | 2% |
| Payments API | p95 `payments_*_duration_seconds` | ≤ 700ms warning / 1.5s critical | 1% |
| Payments errors | `payments_errors_total` без validation | ≤ 3% за 10м | 3% |
| Outbox lag | `outbox_queue_oldest_age` | ≤ 300s | 2% |
| Outbox depth | `outbox_queue_depth` | не монотонно растёт > 15м | N/A (операционный) |

## Примечания

- `/api/*` использует единый JSON error envelope через `JsonErrorPages`.
- `CancellationException` не конвертируется в 500 и пробрасывается вверх.
- Исходящие ops/support сообщения проходят централизованный masking (`phone`, `qrSecret`, `initData`, `idempotency-key`, token-like поля).
- Алерты по webhook/outbox lag и latency добавлены в Prometheus rules.
