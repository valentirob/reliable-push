-- Fallback circuit breaker (spec 2026-11-07-fallback-circuit-breaker-design, section 3).

create table fallback_circuit (
    app_id       text        primary key,
    state        text        not null check (state in ('CLOSED', 'OPEN', 'FORCED')),
    since        timestamptz not null,
    forced_until timestamptz null,
    forced_by    text        null,       -- admin key id, never the key
    suppressed   bigint      not null default 0   -- fallbacks closed without a call since `since`
);

create table fallback_circuit_event (
    id            bigserial   primary key,
    app_id        text        not null,
    at            timestamptz not null,
    transition    text        not null check (transition in ('OPENED', 'CLOSED', 'FORCED', 'FORCE_ENDED')),
    reason        text        not null,  -- tracking_missing, delivered_resumed, forced, force_expired
    due           bigint      null,      -- what the detector saw at that moment
    ratio         numeric     null,      -- delivered share of the cohort
    suppressed    bigint      null,      -- on CLOSED / FORCED: how many fallbacks the open period suppressed
    admin_key_id  text        null
);
create index fallback_circuit_event_app_at on fallback_circuit_event (app_id, at desc);
