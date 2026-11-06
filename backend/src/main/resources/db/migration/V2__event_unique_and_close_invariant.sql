-- One event per (notification, type): duplicates become a no-op through INSERT ... ON CONFLICT DO NOTHING.
drop index notification_event_notification_type;
alter table notification_event
    add constraint notification_event_once unique (notification_id, type);

-- A closed row never waits for the fallback worker: closed_at set implies due_at cleared.
update notification set due_at = null where closed_at is not null and due_at is not null;
alter table notification
    add constraint notification_closed_not_due check (closed_at is null or due_at is null);
