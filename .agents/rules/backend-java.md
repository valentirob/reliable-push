---
trigger: glob
globs: "backend/**,harness/**"
description: Java / Spring Boot conventions for the backend and harness.
---

# Backend conventions

- Java 21, Spring Boot 4.x, Maven wrapper. Virtual threads enabled.
- Package by module: `notifications`, `devices`, `tracking`, `fallback`, `apps`, `shared`. Modules talk through public services/interfaces, not each other's repositories.
- API contract first: update `backend/src/main/resources/openapi.yaml`, then code.
- Persistence: PostgreSQL + Flyway + `JdbcClient` (no ORM) (`V<n>__<desc>.sql`). Never edit an applied migration.
- Fallback scheduling: claim due rows with `SELECT ... FOR UPDATE SKIP LOCKED`; state changes are conditional updates (`WHERE state = 'PENDING'`).
- Tests: JUnit 5 + AssertJ for units; Testcontainers PostgreSQL and WireMock for integration (`*IT.java`). No mocking of the database.
- Config via `@ConfigurationProperties` with validation; everything must run with `api`, `worker` or both profiles.
- Container: non-root user, works with OpenShift arbitrary UIDs.

## Logging (spec §9.1)
- Log4j 2 via `spring-boot-starter-log4j2` (exclude Logback). Profiles: `json` = ECS JSON (`JsonTemplateLayout` + `EcsLayout.json`), `local` = pattern layout.
- Every HTTP request emits `api.request`; every business and trigger step emits its catalogue `event.action` (see spec §9.1). Add new events to the catalogue in the spec first.
- Correlation id in `ThreadContext` as `trace.id`; worker sets one per claimed row plus `rp.tracking_id`.
- Never log keys, tokens, secrets, raw user ids (use `rp.user_hash`) or message content. Tests assert this.

## Lightweight (spec §9.2)
- Allowed: Spring Boot web, `JdbcClient`, Actuator/Micrometer, Flyway, PostgreSQL driver, Log4j 2, Bean Validation, `nimbus-jose-jwt`, Bucket4j core.
- Not allowed: JPA/Hibernate, Spring Security, Spring Cloud, Lombok, MapStruct, reactive stack, brokers. Auth = small servlet filters.
- `client-java/`: JDK only, zero runtime dependencies.
