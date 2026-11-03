-- Fallback worker lease (spec 2): set by every claim (= next_attempt_at at claim time), cleared when the worker
-- persists an outcome (retry scheduled or row closed). A row claimed while lease_until is still set lost its previous
-- holder's lease (crash or too slow): logged as fallback.reclaimed. Outcome updates are fenced on the holder's
-- lease_until, so a stale holder can never overwrite the outcome of the worker that re-claimed the row.
alter table notification add column lease_until timestamptz null;

-- Only a claimed row holds a lease.
alter table notification
    add constraint notification_lease_only_dispatching check (lease_until is null or state = 'DISPATCHING');
