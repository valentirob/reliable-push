-- reliable-push initial schema (spec section 3).

create table notification (
    id                uuid        primary key,          -- trackingId
    app_id            text        not null,
    user_id           text        not null,
    external_id       text        null,                 -- sender's own id, unique per app when present
    policy            text        not null,
    timeout_minutes   int         not null,
    fallback_payload  jsonb       not null,             -- opaque to us; size bounded in the API
    state             text        not null,
    created_at        timestamptz not null,
    due_at            timestamptz null,
    delivered_at      timestamptz null,
    read_at           timestamptz null,
    fallback_attempts int         not null default 0,
    next_attempt_at   timestamptz null,
    closed_at         timestamptz null,
    constraint notification_policy_check check (policy in ('NOT_DELIVERED', 'NOT_READ')),
    constraint notification_state_check check (state in (
        'PENDING', 'DELIVERED', 'READ', 'DISPATCHING',
        'FALLBACK_TRIGGERED', 'FALLBACK_FAILED', 'FALLBACK_EXPIRED', 'CANCELLED'))
);

-- Claim query, branch 1: state in (PENDING, DELIVERED) and due_at <= now()
create index notification_due on notification (due_at) where due_at is not null;
-- Claim query, branch 2: state = DISPATCHING and next_attempt_at <= now()
create index notification_retry on notification (next_attempt_at) where state = 'DISPATCHING';
create unique index notification_app_external_id on notification (app_id, external_id)
    where external_id is not null;
-- Listing per user
create index notification_app_user_created on notification (app_id, user_id, created_at desc);
-- Retention job
create index notification_closed on notification (closed_at) where closed_at is not null;

create table device (
    id            uuid        primary key,
    app_id        text        not null,
    user_id       text        not null,
    platform      text        not null,
    token_version int         not null,
    created_at    timestamptz not null,
    last_seen_at  timestamptz not null,
    revoked_at    timestamptz null,
    constraint device_platform_check check (platform in ('IOS', 'ANDROID'))
);

create index device_app_user on device (app_id, user_id);

create table notification_event (
    id              bigserial   primary key,
    notification_id uuid        not null references notification (id) on delete cascade,
    -- kept (set to null) when a device is purged, so event history survives
    device_id       uuid        null references device (id) on delete set null,
    type            text        not null,
    occurred_at     timestamptz not null,
    received_at     timestamptz not null,
    late            boolean     not null default false,
    constraint notification_event_type_check check (type in ('DELIVERED', 'READ'))
);

create index notification_event_notification_type on notification_event (notification_id, type);
