---
trigger: glob
globs: "backend/**,harness/**"
description: Java / Spring Boot conventions for the backend and harness.
---

# Backend conventions

- Java 21, Spring Boot 3.x, Maven wrapper. Virtual threads enabled.
- Package by module: `notifications`, `devices`, `tracking`, `fallback`, `apps`, `shared`. Modules talk through public services/interfaces, not each other's repositories.
- API contract first: update `backend/src/main/resources/openapi.yaml`, then code.
- Persistence: PostgreSQL + Flyway (`V<n>__<desc>.sql`). Never edit an applied migration.
- Fallback scheduling: claim due rows with `SELECT ... FOR UPDATE SKIP LOCKED`; state changes are conditional updates (`WHERE state = 'PENDING'`).
- Tests: JUnit 5 + AssertJ for units; Testcontainers PostgreSQL and WireMock for integration (`*IT.java`). No mocking of the database.
- Config via `@ConfigurationProperties` with validation; everything must run with `api`, `worker` or both profiles.
- Container: non-root user, works with OpenShift arbitrary UIDs.
