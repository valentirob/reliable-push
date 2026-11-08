-- Sidecar integration revision (spec 2026-11-08-sidecar-integration-revision-design, sections 2.2 and 2.3).

-- New final state: a notification registered for a user without an active device is stored closed.
alter table notification drop constraint notification_state_check;
alter table notification
    add constraint notification_state_check check (state in (
        'PENDING', 'DELIVERED', 'READ', 'DISPATCHING',
        'FALLBACK_TRIGGERED', 'FALLBACK_FAILED', 'FALLBACK_EXPIRED', 'CANCELLED', 'NO_DEVICE_REGISTERED'));

-- Expiry of the newest device token issued (register and refresh set it). Rows from before this migration get the
-- default validity (90 days) counted from their last activity. Written as hours: an interval in days added to a
-- timestamptz follows the session time zone's daylight saving changes, hours do not.
alter table device add column token_expires_at timestamptz;
update device set token_expires_at = last_seen_at + interval '2160 hours';
alter table device alter column token_expires_at set not null;

-- Daily rollup: the notifications stored as NO_DEVICE_REGISTERED (kept out of the rate denominators).
alter table daily_stats add column no_device_registered int not null default 0;
