# ADR-003: Oracle primary, with SQL Server portability

- **Status:** accepted
- **Milestone:** M6

## Context

Drug-safety databases in industry commonly run on Oracle (Argus Safety is the best-known example), and
many hospital and pharmacy systems run on SQL Server. PharmaVigil should run on Oracle and prove its JPA
layer is not tied to one vendor. Tests must run against the real engines, not an in-memory stand-in whose
locking and types differ.

## Options considered

1. **Oracle only.** Simplest; portability unproven.
2. **One generic schema and Hibernate `ddl-auto=update`.** Portable on paper, but schema drift is
   invisible and production DDL is uncontrolled.
3. **Flyway migrations per vendor + Hibernate `validate` + the same test suite on both engines.**

## Decision

Option 3.
- Flyway runs `db/migration/{vendor}` (`oracle/`, `sqlserver/`); the two files mirror each other line by
  line (identity columns, `BOOLEAN`/`BIT`, `TIMESTAMP(6)`/`DATETIME2(6)`, `CLOB`/`VARCHAR(MAX)`, UTF-8
  collation for free text on SQL Server).
- Hibernate runs with `ddl-auto=validate`, so every entity mapping is checked against the real schema on
  both engines at startup. `Instant` is stored as plain `TIMESTAMP` (UTC) on both.
- Integration tests use Testcontainers: Oracle Database Free (`gvenzl/oracle-free:23-slim-faststart`) by
  default and SQL Server 2022 with `-Pmssql`. CI runs both.
- Stock safety uses portable JPA pessimistic locks (`SELECT … FOR UPDATE` on Oracle, `UPDLOCK, HOLDLOCK,
  ROWLOCK` on SQL Server) plus a `CHECK (quantity >= 0)` constraint as a second guard.

## Consequences

- Each schema change is written twice; a missed mirror fails `validate` on that engine's CI job.
- The concurrency test (50 parallel dispenses never oversell) runs on both engines, so locking behaviour is
  verified, not assumed.
- Oracle Free needs about 70 seconds to start on a small runner; the container start-up timeout is five
  minutes and one container is shared per test JVM.
