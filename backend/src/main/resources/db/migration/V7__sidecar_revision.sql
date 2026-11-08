-- Sidecar integration revision (spec 2026-11-08-sidecar-integration-revision-design, sections 2.2 and 2.3).

-- New final state: a notification registered for a user without an active device is stored closed.
alter table notification drop constraint notification_state_check;
alter table notification
    add constraint notification_state_check check (state in (
        'PENDING', 'DELIVERED', 'READ', 'DISPATCHING',
        'FALLBACK_TRIGGERED', 'FALLBACK_FAILED', 'FALLBACK_EXPIRED', 'CANCELLED', 'NO_DEVICE_REGISTERED'));

-- Expiry of the newest device token issued (register and refresh set it). Rows from before this migration have no
-- record of their token's exp: each gets a conservative upper bound, its last activity plus 10 years (above any
-- configurable device-token-validity), so no existing device is wrongly treated as inactive and its user's
-- notifications stay tracked (an error toward tracking, never toward a lost one). The device's next register or
-- refresh writes the real exp. Written as hours: an interval in days added to a timestamptz follows the session time
-- zone's daylight saving changes, hours do not.
alter table device add column token_expires_at timestamptz;
update device set token_expires_at = last_seen_at + interval '87600 hours';
alter table device alter column token_expires_at set not null;

-- Daily rollup: the notifications stored as NO_DEVICE_REGISTERED (kept out of the rate denominators).
alter table daily_stats add column no_device_registered int not null default 0;
