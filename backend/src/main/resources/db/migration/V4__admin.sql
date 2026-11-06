-- Ops dashboard (spec 2026-11-06-ops-dashboard-design, sections 3.1 and 3.2).

-- One row per webhook call the fallback worker makes, written in the transaction of the fenced outcome update.
create table fallback_attempt (
    id              bigserial   primary key,
    notification_id uuid        not null references notification (id) on delete cascade,
    attempt         int         not null,
    started_at      timestamptz not null,
    duration_ms     int         not null,
    outcome         text        not null,
    http_status     int         null,
    reason          text        null,
    constraint fallback_attempt_outcome_check check (outcome in ('SUCCESS', 'RETRYABLE', 'FINAL')),
    constraint fallback_attempt_once unique (notification_id, attempt)
);

-- Per-app, per-UTC-day counters and delivery percentiles, recomputed by the daily rollup job.
create table daily_stats (
    day                date   not null,
    app_id             text   not null,
    registered         int    not null,
    cancelled          int    not null,
    delivered          int    not null,
    read               int    not null,
    delivered_ios      int    not null,
    delivered_android  int    not null,
    fallback_triggered int    not null,
    fallback_failed    int    not null,
    fallback_expired   int    not null,
    delivery_p50_ms    bigint null,
    delivery_p95_ms    bigint null,
    computed_at        timestamptz not null,
    primary key (day, app_id)
);
