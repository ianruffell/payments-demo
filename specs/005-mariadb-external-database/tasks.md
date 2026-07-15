---

description: "Task list for MariaDB external database as system of record (reverse-engineered from commit 1e261f9)"
---

# Tasks: MariaDB External Database as System of Record

**Input**: Design documents from `/specs/005-mariadb-external-database/`

**Prerequisites**: plan.md (required), spec.md (required for user stories)

**Tests**: This project ships no automated test suite; behavior is validated by exercising the dashboard and HTTP endpoints. No test tasks are included, matching the delivered increment.

**Organization**: Tasks are grouped by user story. This is a reverse-engineered breakdown that consolidates the external-database system-of-record architecture; every task references real repository paths.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3, US4)
- Paths are relative to the repository root.

## Path Conventions

- Backend Java sources: `src/main/java/com/example/paymentsdemo/`
- Spring config: `src/main/resources/`
- CDC assets: `mariadb/`
- Deployment: `Dockerfile`, `docker-compose.yml`, `.env.mariadb`, `gridgain/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Bring in the MariaDB driver and make the image build from source so the stack runs from a clean checkout.

- [X] T001 Add the MariaDB JDBC driver dependency (`org.mariadb.jdbc:mariadb-java-client`) in `pom.xml`.
- [X] T002 [P] Convert `Dockerfile` to a multi-stage build: add a `maven:3.9.9-eclipse-temurin-17` build stage that packages the jar, then copy it into the `eclipse-temurin:17-jre` runtime stage.
- [X] T003 [P] Remove the pre-built-jar exception from `.dockerignore` now that the image builds from source.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Introduce the configuration surface, data source, and repository contract every user story depends on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T004 Define the `demo.external-db.*` configuration namespace in `src/main/resources/application.yml` (`type: mariadb`, `jdbc-url`, `username`, `password`, `archive.{poll-interval-ms,captured-retention-ms}`, `cdc.{kafka-bootstrap-servers,sink-group-id,topic-prefix,schema-name,connector-name,accounts-topic,merchants-topic}`).
- [X] T005 Create `src/main/java/com/example/paymentsdemo/config/ExternalDatabaseJdbcConfig.java` that builds the MariaDB data source from `demo.external-db.{jdbc-url,username,password}` using the MariaDB driver.
- [X] T006 Create the backend-neutral `SystemOfRecordRepository` interface in `src/main/java/com/example/paymentsdemo/service/SystemOfRecordRepository.java` declaring schema init, counts, readability check, reset, account/merchant upserts and loads, account/merchant lookups, `setMerchantActive`, archived daily total, recent archived payments, archived-payment lookup, `archiveCompletedPayment`, and `enableReferenceTableCdc`.

**Checkpoint**: The data source, config namespace, and repository contract exist — user stories can proceed.

---

## Phase 3: User Story 1 - MariaDB is the durable system of record (Priority: P1) 🎯 MVP

**Goal**: Bring the stack up on MariaDB with one env file and one compose command, creating the schema and seeding reference data on an empty database.

**Independent Test**: Run `docker compose --env-file .env.mariadb up --build`; confirm the tables are created, seed data loads, and the dashboard shows live traffic.

- [X] T007 [US1] Implement `src/main/java/com/example/paymentsdemo/service/JdbcSystemOfRecordRepository.java` (`implements SystemOfRecordRepository`) with a `ddl(String)` helper emitting MariaDB column types (`VARCHAR`/`BIGINT`/`TINYINT`/`DECIMAL(10,4)`) and routing the four `CREATE TABLE` statements through it.
- [X] T008 [US1] Add `accountUpsertSql()`/`merchantUpsertSql()` returning MariaDB `INSERT ... ON DUPLICATE KEY UPDATE` and use them in the batch upserts; widen `isAlreadyExists(...)` to recognize MariaDB error 1050 / SQLState 42S01.
- [X] T009 [US1] Wire `SeedDataLoader.java` to `SystemOfRecordRepository` so an empty MariaDB is seeded with 100k accounts and the configured merchants on startup; skip re-seeding when readable data exists and reset+reload when it is unreadable.
- [X] T010 [P] [US1] Add `.env.mariadb` at the repo root setting the MariaDB compose profile, `DEMO_EXTERNAL_DB_TYPE=mariadb`, JDBC URL (`jdbc:mariadb://mariadb:3306/payments_app`), credentials, and CDC schema/connector/topic names.
- [X] T011 [US1] In `docker-compose.yml`, add the `mariadb` service with `--server-id/--log-bin/--binlog-format=ROW/--binlog-row-image=FULL`, a healthcheck, port `3306`, and the `mariadb-data` volume; pass `DEMO_EXTERNAL_DB_*` to the processor from the env file.
- [X] T012 [P] [US1] Update `README.md` to document `docker compose --env-file .env.mariadb up --build` and the MariaDB topology.

**Checkpoint**: The demo comes up on MariaDB, creates its schema, and seeds reference data.

---

## Phase 4: User Story 2 - Reference data reaches the cache via CDC (Priority: P2)

**Goal**: Capture `ACCOUNTS`/`MERCHANTS` changes from MariaDB into GridGain through Debezium and a reference-cache sink, with no application dual writes.

**Independent Test**: Change a merchant's active flag directly in MariaDB and confirm it reaches the GridGain reference cache and dashboard.

- [X] T013 [P] [US2] Add `mariadb/mariadb-source-connector.json` defining a Debezium `MySqlConnector` (`topic.prefix=paymentsdemo`, host/port/user, `database.include.list=payments_app`, `table.include.list` for `ACCOUNTS`/`MERCHANTS`, schema-history topic, `snapshot.mode=initial`).
- [X] T014 [P] [US2] Add `mariadb/register-mariadb-connector.sh` that waits for Kafka Connect and PUTs the config to `.../connectors/paymentsdemo-mariadb-reference/config` until it succeeds.
- [X] T015 [P] [US2] Add `mariadb/init/001_create_debezium_user.sql` creating `dbzuser` with SELECT/RELOAD/SHOW DATABASES/REPLICATION SLAVE/REPLICATION CLIENT grants.
- [X] T016 [US2] Implement `src/main/java/com/example/paymentsdemo/service/ReferenceCacheSinkService.java` (profile `reference-cache-sink`) that consumes the `ACCOUNTS`/`MERCHANTS` topics from `demo.external-db.cdc.*` into the GridGain reference caches and guards against null-value (tombstone) records.
- [X] T017 [US2] Add `src/main/resources/application-reference-cache-sink.yml` excluding `DataSourceAutoConfiguration` and setting `web-application-type: none`.
- [X] T018 [US2] In `docker-compose.yml`, add the `debezium-register-mariadb` job (mounting the connector script/config) and the `payments-reference-cache-sink` container (`SPRING_PROFILES_ACTIVE=reference-cache-sink`, `DEMO_EXTERNAL_DB_CDC_*` env).

**Checkpoint**: A reference-data change in MariaDB reaches the GridGain cache via CDC.

---

## Phase 5: User Story 3 - Archive terminal payments to MariaDB, then evict (Priority: P2)

**Goal**: Archive terminal payments asynchronously to MariaDB and evict them from the cache after the durable write, keeping views complete.

**Independent Test**: Run the simulator, let payments settle, and confirm terminal payments appear in `PAYMENT_ARCHIVE`, evict from the cache after their retention window, and still appear in dashboard totals.

- [X] T019 [US3] Implement archive reads/writes in `JdbcSystemOfRecordRepository` (`archiveCompletedPayment`, archived daily total, recent archived payments, archived-payment lookup), writing the payment and its ledger entries and applying the balance debit in the same transaction.
- [X] T020 [US3] Implement `CompletedPaymentArchiveService.java` to poll on `demo.external-db.archive.poll-interval-ms`, archive terminal payments after `captured-retention-ms`, evict from the cache only after a successful write, and retry on failure.
- [X] T021 [P] [US3] Update `DashboardService.java` to merge live cache rows with archived rows in MariaDB so totals remain complete after eviction.
- [X] T022 [P] [US3] Update `TransactionFlowService.java` to merge live cache rows with archived rows so the flow view stays complete after eviction.
- [X] T023 [P] [US3] Wire `PaymentService.java` and `MerchantAdminService.java` to `SystemOfRecordRepository` (hot path stays cache-only; `setMerchantActive` goes through the repository).

**Checkpoint**: Terminal payments durably archive to MariaDB and evict from the cache without disappearing from the views.

---

## Phase 6: User Story 4 - Role-separated runtime via Spring profiles (Priority: P3)

**Goal**: Run the same image as processor / payment-initiator / merchant-simulator / reference-cache-sink, with only the processor holding a data source.

**Independent Test**: Start the stack and confirm each role runs under its profile and only the processor connects to MariaDB.

- [X] T024 [P] Gate the six controllers in `src/main/java/com/example/paymentsdemo/api/` with `!reference-cache-sink` so they do not load in the sink role.
- [X] T025 [P] Gate the processor-only services (`FraudService.java`, `MerchantDispatchService.java`, `MerchantTimeoutMonitor.java`, `SimulatorGatewayService.java`) with `!reference-cache-sink`.
- [X] T026 In `docker-compose.yml`, define the `payment-initiator` and `merchant-simulator` role containers by `SPRING_PROFILES_ACTIVE`, ensuring only the processor receives `DEMO_EXTERNAL_DB_*` connection env.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Reset tooling.

- [X] T027 Update `gridgain/clear-demo-data.sh` to stop the simulator and clear the transient GridGain state, with optional clearing of the MariaDB archive tables (`PAYMENT_ARCHIVE`, `LEDGER_ENTRY_ARCHIVE`) via the `mariadb` client.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately.
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS all user stories (data source, config namespace, repository contract).
- **User Stories (Phase 3–6)**: All depend on Foundational completion.
  - US1 (durable system of record + seeding) is the MVP and lands first.
  - US2 (CDC reference-cache sink) projects reference data into the cache.
  - US3 (async archival + eviction) makes terminal payments durable without slowing the hot path.
  - US4 (role separation) distributes the roles across containers.
- **Polish (Phase 7)**: Depends on the schema/archive tables existing; otherwise independent.

### Within Each User Story

- Config/data source before the classes that consume it.
- Repository implementation before the services that depend on the interface.
- Connector assets before the compose jobs that mount them.

### Parallel Opportunities

- T002 and T003 (Dockerfile / .dockerignore) run in parallel.
- Env file T010 and README T012 are parallel within US1.
- MariaDB CDC assets T013/T014/T015 are parallel within US2.
- View-merge updates T021/T022 and the wiring in T023 are parallel (distinct files) within US3.
- The profile-gating sweeps T024 and T025 are parallel.

---

## Parallel Example: User Story 2

```bash
# The MariaDB CDC assets are independent files and can be authored in parallel:
Task: "Add mariadb/mariadb-source-connector.json"
Task: "Add mariadb/register-mariadb-connector.sh"
Task: "Add mariadb/init/001_create_debezium_user.sql"
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Complete Phase 1: Setup (driver dependency, self-building image).
2. Complete Phase 2: Foundational (data source, config namespace, repository contract).
3. Complete Phase 3: User Story 1 (JDBC repository, seeding, MariaDB compose service, env file).
4. **STOP and VALIDATE**: Bring the stack up with `.env.mariadb` and confirm the schema is created and seed data loads.

### Incremental Delivery

1. Setup + Foundational → data source and repository contract in place.
2. Add US1 → MariaDB is the durable system of record and self-seeds (MVP).
3. Add US2 → reference data flows into the cache via CDC.
4. Add US3 → terminal payments archive durably and evict from the cache.
5. Add US4 → the roles run as separate profiles.
6. Polish → reset tooling.

### Notes

- [P] tasks touch different files with no ordering dependency.
- The payment hot path must stay cache-only; durability is achieved via the asynchronous archive path (constitution Principle III).
- Reference data must reach the cache via CDC only — never application dual writes (Principle II).
- Validation is manual: exercise the dashboard/endpoints, since the project ships no automated tests.
