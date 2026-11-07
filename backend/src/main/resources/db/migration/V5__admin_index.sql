-- Ops dashboard window queries (spec 2026-11-06-ops-dashboard-design, section 3). Built online: Flyway runs a lone
-- CONCURRENTLY statement outside a transaction, so writes to notification continue during an upgrade. Keep this
-- file to this one statement (a transactional statement next to it would make Flyway fail the migration).
create index concurrently if not exists notification_app_created on notification (app_id, created_at);
