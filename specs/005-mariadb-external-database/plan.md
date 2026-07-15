# Implementation Plan: MariaDB External Database as System of Record

**Branch**: `005-mariadb-external-database` | **Date**: 2026-05-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-mariadb-external-database/spec.md`

**Note**: This plan reverse-engineers the technical approach actually taken in commit 1e261f9 and consolidates the external-database system-of-record architecture (CDC sink, asynchronous archival and eviction, role profiles) that the demo relies on. It records what was built, not a forward-looking proposal.

## Summary

Establish an external MariaDB database as the durable system of record. Reference data (`accounts`, `merchants`) and completed payments persist in MariaDB (`payments_app`); GridGain is the live cache and a CDC projection target. The application connects through a configuration-driven data source (`demo.external-db.*`) selected by the `.env.mariadb` file. Reference data flows into GridGain through a Debezium MariaDB source connector, Kafka, and a dedicated reference-cache sink — never through application dual writes. Terminal payments are archived asynchronously back to MariaDB and evicted from the cache after the durable write succeeds, while the dashboard and transaction-flow views merge live cache rows with archived rows so eviction is invisible. The application runs as role-separated Spring profiles (`processor`, `payment-initiator`, `merchant-simulator`, `reference-cache-sink`), with only the processor holding a data source.

## Technical Context

**Language/Version**: Java 17 (GridGain 8 requires the documented `--add-opens` flags off-compose).

**Primary Dependencies**: Spring Boot; Spring JDBC (`JdbcTemplate`, `DataSourceBuilder`); GridGain 8 (Ignite API); Kafka clients + Kafka Connect with the Debezium MariaDB (`MySqlConnector`) source connector; MariaDB JDBC driver (`mariadb-java-client`).

**Storage**: External MariaDB database as the system of record (`payments_app`: `ACCOUNTS`, `MERCHANTS`, `PAYMENT_ARCHIVE`, `LEDGER_ENTRY_ARCHIVE`). GridGain is the live in-flight cache and reference-data projection target.

**Testing**: No automated test suite. Validation is by exercising the dashboard, transaction-flow view, and HTTP endpoints, per the constitution.

**Target Platform**: Docker Compose reference runtime on a developer machine.

**Project Type**: Web service (Spring Boot backend + static HTML/CSS/JS dashboard, no build step) with a Debezium → Kafka → GridGain CDC pipeline and an asynchronous archive path.

**Performance Goals**: Cache-first hot path — payment authorize/capture/refund run against GridGain with no synchronous external-database round trips. Seeding loads 100k accounts and the configured merchants in batched upserts (accounts in batches of 1000, merchants in 250).

**Constraints**: Reference data must reach the cache via CDC only (no dual writes). Durability of terminal payments must not block the hot path. The stack must come up from one compose command plus `.env.mariadb`.

**Scale/Scope**: 100k accounts and 5 merchants seeded per run; one shared CDC sink; one Debezium connector definition; four runtime roles.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. External Database Is the System of Record** — PASS (directly established). MariaDB is the durable source of truth for reference data and completed payments; GridGain is a cache. No authoritative state lives only in GridGain.
- **II. Change Data Capture, Not Dual Writes** — PASS (directly established). Reference data flows Debezium → Kafka → reference-cache sink into GridGain. The application never writes reference data to both stores.
- **III. Cache-First Hot Path, Asynchronous Archival** — PASS (directly established). Authorize/capture/refund run against the cache; terminal payments archive asynchronously to MariaDB and evict only after the durable write succeeds.
- **IV. Pluggable Infrastructure Behind Configuration** — PASS. The database, its connector, and the runtime role are selected by configuration (`demo.external-db.*`, `.env.mariadb`, Spring profiles, compose profiles) rather than by code changes.
- **V. Observable, Demonstrable Behavior** — PASS. The dashboard, flow view, and operator levers work against the topology; archival/eviction is invisible because the views merge live and archived rows.
- **VI. Reproducible One-Command Local Stack** — PASS. `docker compose --env-file .env.mariadb up --build` builds from source and self-seeds when empty. The Dockerfile is a multi-stage build so the image builds from a clean checkout.

No deviations. Complexity Tracking below records the one abstraction introduced.

## Project Structure

### Documentation (this feature)

```text
specs/005-mariadb-external-database/
├── plan.md              # This file
├── spec.md              # Feature specification
└── tasks.md             # Reverse-engineered, dependency-ordered task list
```

### Source Code (repository root)

```text
.
├── .env.mariadb                                 # MariaDB compose profile + DEMO_EXTERNAL_DB_* values
├── .dockerignore                                # Dropped pre-built-jar exception (image builds from source)
├── Dockerfile                                   # Multi-stage Maven build stage added
├── docker-compose.yml                           # mariadb service, Debezium connector job, reference-cache sink, roles
├── pom.xml                                      # mariadb-java-client dependency
├── README.md                                    # MariaDB run instructions
├── gridgain/
│   └── clear-demo-data.sh                       # Clears transient GridGain state; optional archive clearing
├── mariadb/
│   ├── init/001_create_debezium_user.sql        # dbzuser + replication grants
│   ├── mariadb-source-connector.json            # Debezium MySqlConnector for MariaDB
│   └── register-mariadb-connector.sh            # PUT connector config to Kafka Connect
└── src/main/
    ├── java/com/example/paymentsdemo/
    │   ├── config/
    │   │   └── ExternalDatabaseJdbcConfig.java   # Builds the MariaDB data source from demo.external-db.*
    │   ├── service/
    │   │   ├── SystemOfRecordRepository.java     # Backend-neutral repository interface
    │   │   ├── JdbcSystemOfRecordRepository.java # JDBC implementation (MariaDB DDL/upserts/CDC enablement)
    │   │   ├── ReferenceCacheSinkService.java    # CDC consumer → GridGain reference caches (profile: reference-cache-sink)
    │   │   ├── CompletedPaymentArchiveService.java  # Async archive-then-evict; reads demo.external-db.archive.*
    │   │   ├── DashboardService.java             # Merges live cache + MariaDB archive
    │   │   ├── TransactionFlowService.java       # Merges live cache + MariaDB archive
    │   │   ├── MerchantAdminService.java         # setMerchantActive via the repository
    │   │   ├── PaymentService.java               # Hot path against the cache
    │   │   └── SeedDataLoader.java               # Seeds reference data into the system of record
    │   └── api/                                  # Controllers gated with !reference-cache-sink
    │       ├── AdminController.java
    │       ├── DashboardController.java
    │       ├── MerchantResultController.java
    │       ├── PaymentController.java
    │       ├── SimulatorController.java
    │       └── TransactionFlowController.java
    └── resources/
        ├── application.yml                       # demo.external-db.* (type: mariadb) + role/profile config
        └── application-reference-cache-sink.yml  # sink profile (no web, no DataSource autoconfig)
```

**Structure Decision**: Single Spring Boot web-service project. The external-database integration is layered in via one JDBC config, one repository interface plus its JDBC implementation, the reference-cache sink, and configuration/compose/env assets — matching the existing package structure (`config`, `service`, `api`) rather than introducing new modules.

## Key Technical Decisions (as implemented)

- **Configuration-driven data source**: `ExternalDatabaseJdbcConfig` builds the MariaDB data source from `demo.external-db.{jdbc-url,username,password}`; the `.env.mariadb` file supplies these plus `COMPOSE_PROFILES` and `DEMO_EXTERNAL_DB_TYPE`.
- **One repository contract, JDBC implementation**: A `SystemOfRecordRepository` interface lets every caller (payment, dashboard, seed, archive, admin, transaction-flow services) depend on a neutral contract; `JdbcSystemOfRecordRepository` is the single place that issues SQL. DDL uses MariaDB column types (`VARCHAR`/`BIGINT`/`TINYINT`/`DECIMAL`); upserts use `INSERT ... ON DUPLICATE KEY UPDATE`; "already exists" detection recognizes MariaDB error 1050 / SQLState 42S01.
- **CDC in, not dual writes**: MariaDB runs with `--log-bin`, `--binlog-format=ROW`, `--binlog-row-image=FULL`, and an init script grants replication privileges to `dbzuser`. The Debezium `MySqlConnector` captures `payments_app.ACCOUNTS`/`MERCHANTS` into `paymentsdemo.*` topics; `ReferenceCacheSinkService` (profile `reference-cache-sink`) consumes them into GridGain and guards against null-value (tombstone) records. `application-reference-cache-sink.yml` disables the web layer and JDBC autoconfiguration for the sink process.
- **Asynchronous archival and eviction**: `CompletedPaymentArchiveService` polls on `demo.external-db.archive.poll-interval-ms`, archives terminal payments after `captured-retention-ms`, applies the balance debit in the same transaction as the archive write, and evicts from the cache only after the durable write succeeds. Dashboard and transaction-flow services merge live cache rows with archived rows so eviction is invisible.
- **Role separation by profile**: The same image runs as `processor` (the only role with a data source), `payment-initiator`, `merchant-simulator`, and `reference-cache-sink`; controllers and processor-only services are gated with `!reference-cache-sink`.
- **Build from source in-image**: The Dockerfile uses a `maven:3.9.9-eclipse-temurin-17` build stage that packages the jar, and `.dockerignore` drops its pre-built-jar exception, so the image is self-building from a clean checkout.

## Complexity Tracking

> Recorded for transparency; the Constitution Check passed with no violations.

| Decision | Why Needed | Simpler Alternative Rejected Because |
|----------|------------|--------------------------------------|
| `SystemOfRecordRepository` interface extracted from the concrete JDBC class | Lets every caller depend on a neutral contract while a single JDBC implementation localizes all persistence SQL | Scattering SQL and connection handling across the service layer would spread database awareness through the whole application and violate Principle IV |
| Separate `reference-cache-sink` process with no web layer or data source | The CDC sink must consume Kafka independently of the request-serving processor and must not open a database connection | Running the sink inside the processor would couple cache projection to request handling and require the sink role to carry database credentials it does not need |
