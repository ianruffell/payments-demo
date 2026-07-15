---
description: "Task list for containerized asynchronous merchant processing"
---

# Tasks: Containerized Asynchronous Merchant Processing

**Input**: Design documents from `/specs/002-containerized-async-merchant/`

**Prerequisites**: plan.md (required), spec.md (required for user stories)

**Tests**: The project ships no automated test suite (per constitution); validation is by
driving HTTP endpoints and observing the dashboard. No test tasks are included.

**Organization**: Tasks are grouped by user story. This is a reverse-engineered task list for an
already-delivered increment; paths are the real files touched by commit 90165ee.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3, US4)
- Include exact file paths in descriptions

## Path Conventions

Single Maven module. Java under `src/main/java/com/example/paymentsdemo/`, resources under
`src/main/resources/`, container and infra files at the repository root and under `gridgain/`
and `mysql/`.

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Build tooling and dependency changes shared by every story.

- [X] T001 Bump `<gridgain.version>` to `8.9.32`, add `org.gridgain:gridgain-core`,
  `org.gridgain:gridgain-ultimate`, and `org.apache.kafka:kafka-clients`, and pin the Spring
  Boot plugin `mainClass` to `com.example.paymentsdemo.PaymentsDemoApplication` in `pom.xml`.
- [X] T002 [P] Add `Dockerfile` (`eclipse-temurin:17-jre`, copy the built jar, bake GridGain
  `--add-opens` and `IGNITE_NO_SHUTDOWN_HOOK` into `JAVA_TOOL_OPTIONS`, expose 8080).
- [X] T003 [P] Add `.dockerignore` to keep the build context lean and ship only the prebuilt
  `target/gridgain-payments-demo-0.0.1-SNAPSHOT.jar`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core cache and cluster-connection changes every story depends on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T004 Add `MERCHANT_PAYMENT_ATTEMPTS = "merchant_payment_attempts"` to
  `src/main/java/com/example/paymentsdemo/service/CacheNames.java`.
- [X] T005 Extract cache definitions into
  `src/main/java/com/example/paymentsdemo/config/CacheConfigurations.java` and include the new
  merchant payment attempts cache (transactional, partitioned, 1 backup, indexed types).
- [X] T006 Convert `src/main/java/com/example/paymentsdemo/config/GridGainConfig.java` from an
  embedded server node with data-storage/persistence config to GridGain client mode:
  configurable instance name and discovery addresses, optional license URL via
  `GridGainConfiguration` plugin, create caches with `getOrCreateCache(...)`, gate with
  `@Profile("!merchant-simulator")`.
- [X] T007 Set cluster discovery addresses, seeding config, merchant service URL pattern, and
  `demo.processor.callback-url` / `merchant-timeout-ms` in
  `src/main/resources/application.yml`; add `src/main/resources/ignite-config.xml` for the
  compose GridGain nodes.

**Checkpoint**: Processor connects to an external cluster and creates all caches including
merchant payment attempts.

---

## Phase 3: User Story 1 - Authorize a payment through an asynchronous merchant (Priority: P1) 🎯 MVP

**Goal**: Turn authorization into a request/callback flow: accept, dispatch, settle on result.

**Independent Test**: `POST /api/payments/authorize` returns `202 Accepted` with status
`PENDING_MERCHANT`; the payment later becomes `AUTHORIZED` (funds held) or `DECLINED` on the
merchant callback.

### Implementation for User Story 1

- [X] T008 [P] [US1] Add `PENDING_MERCHANT` and `TIMED_OUT` to
  `src/main/java/com/example/paymentsdemo/domain/PaymentStatus.java`.
- [X] T009 [P] [US1] Add the `serviceUrl` field, constructor arg, and accessors to
  `src/main/java/com/example/paymentsdemo/domain/Merchant.java`.
- [X] T010 [P] [US1] Add `src/main/java/com/example/paymentsdemo/domain/MerchantRequestStatus.java`
  enum (`PENDING`, `APPROVED`, `DECLINED`, `TIMED_OUT`, `LATE_RESPONSE`, `DISPATCH_FAILED`).
- [X] T011 [P] [US1] Add `src/main/java/com/example/paymentsdemo/domain/MerchantPaymentAttempt.java`
  cached record (payment/merchant IDs, status, merchant URL, callback URL, requested/deadline/
  responded timestamps, merchant reference, message) with `@QuerySqlField` indexes.
- [X] T012 [P] [US1] Add `src/main/java/com/example/paymentsdemo/dto/MerchantAuthorizationRequest.java`
  and `src/main/java/com/example/paymentsdemo/dto/MerchantAuthorizationResult.java` records.
- [X] T013 [US1] Add `src/main/java/com/example/paymentsdemo/service/MerchantDispatchService.java`
  (`@Profile("!merchant-simulator")`) that async-POSTs a `MerchantAuthorizationRequest` to the
  merchant `serviceUrl` via `HttpClient`, logging (not blocking on) failures. Depends on T009,
  T011, T012.
- [X] T014 [US1] Rework `authorize(...)` in
  `src/main/java/com/example/paymentsdemo/service/PaymentService.java`: persist approved-path
  payments as `PENDING_MERCHANT`, create and store a `PENDING` attempt with a deadline
  (`now + merchant-timeout-ms`), commit, then dispatch; decline immediately (no dispatch) on
  local validation failure, including `MERCHANT_UNAVAILABLE` when the merchant has no service
  URL; inject callback URL and timeout via `@Value`; gate with `@Profile("!merchant-simulator")`.
  Depends on T004–T013.
- [X] T015 [US1] Add `processMerchantResult(...)` to
  `src/main/java/com/example/paymentsdemo/service/PaymentService.java`: look up payment and
  attempt, reject merchant-ID mismatch, on approval run `validateApprovedMerchantResponse(...)`
  and `authorizeApprovedPayment(...)` (set `AUTHORIZED`, debit balance, write `AUTH_HOLD`
  ledger entry), on decline set `DECLINED` with reason. Depends on T014.
- [X] T016 [US1] Add `src/main/java/com/example/paymentsdemo/api/MerchantResultController.java`
  (`POST /api/merchant-results`, `@Profile("!merchant-simulator")`) delegating to
  `processMerchantResult`. Depends on T015.
- [X] T017 [US1] Update `src/main/java/com/example/paymentsdemo/api/PaymentController.java` to
  return `202 Accepted` when the payment is `PENDING_MERCHANT` (else `201 Created`) and gate
  with `@Profile("!merchant-simulator")`. Depends on T014.
- [X] T018 [US1] Rewrite `merchantDailyTotal(...)` in `PaymentService.java` from a `ScanQuery`
  to a `SqlFieldsQuery` summing amounts for `AUTHORIZED`/`CAPTURED`/`REFUNDED`, used by
  approval-time re-validation. Depends on T015.

**Checkpoint**: Async authorize + merchant callback settlement works end to end.

---

## Phase 4: User Story 2 - Handle merchant timeouts and late responses (Priority: P2)

**Goal**: Ensure no payment stays pending forever and late/stale results never override outcomes.

**Independent Test**: A slow or stopped merchant causes the payment to become `TIMED_OUT`
(reason `MERCHANT_TIMEOUT`) within the timeout window without operator action.

### Implementation for User Story 2

- [X] T019 [US2] Add `markTimedOutPayments()` (SQL query over `MerchantPaymentAttempt` for
  `PENDING` attempts past deadline), `markTimedOutPayment(...)`, and `markTimedOut(...)` to
  `src/main/java/com/example/paymentsdemo/service/PaymentService.java` (set payment `TIMED_OUT`
  + `MERCHANT_TIMEOUT`, attempt `TIMED_OUT`). Depends on T015.
- [X] T020 [US2] In `processMerchantResult(...)`, handle already-settled payments (ignore) and
  responses arriving after the deadline (record attempt as `LATE_RESPONSE`, keep the timed-out
  outcome) in `PaymentService.java`. Depends on T019.
- [X] T021 [US2] Add `src/main/java/com/example/paymentsdemo/service/MerchantTimeoutMonitor.java`
  (`@Profile("!merchant-simulator")`) that schedules `markTimedOutPayments` every second.
  Depends on T019.

**Checkpoint**: Timeouts resolve automatically; late and duplicate results are handled safely.

---

## Phase 5: User Story 3 - Bring up the whole stack with one command (Priority: P3)

**Goal**: One `docker compose up --build` starts the cluster, processor, and merchant simulators,
with the simulator role isolated by profile and seeding wiring merchants to their simulators.

**Independent Test**: From a clean checkout, compose brings up the cluster, processor on
`:8080`, and merchant simulator containers; the dashboard loads with seeded data.

### Implementation for User Story 3

- [X] T022 [P] [US3] Add `src/main/java/com/example/paymentsdemo/service/MerchantSimulatorService.java`
  (`@Profile("merchant-simulator")`): accept a `MerchantAuthorizationRequest`, reject wrong
  merchant IDs, schedule a delayed callback with configurable delay/timeout probability,
  approval rate, and decline-above threshold. Depends on T012.
- [X] T023 [P] [US3] Add `src/main/java/com/example/paymentsdemo/api/MerchantSimulatorController.java`
  (`POST /api/merchant/payments`, `@Profile("merchant-simulator")`). Depends on T022.
- [X] T024 [US3] Update `src/main/java/com/example/paymentsdemo/service/SeedDataLoader.java`:
  `@Profile("!merchant-simulator")`, a `demo.seed.enabled` toggle, 4 merchants, per-merchant
  `serviceUrl` from `demo.seed.merchant-service-url-pattern`, seed-only-when-empty, and
  reset-and-reload when existing data is unreadable by this version. Depends on T009.
- [X] T025 [P] [US3] Gate the remaining processor-only beans with `@Profile("!merchant-simulator")`:
  `api/AdminController.java`, `api/DashboardController.java`, `api/SimulatorController.java`,
  `service/FraudService.java`, `service/MerchantAdminService.java`,
  `service/DashboardService.java` (also exclude `PENDING_MERCHANT` from suspicious and rank top
  merchants by amount then count in `DashboardService.java`).
- [X] T026 [P] [US3] Update `simulator/PaymentSimulator.java` and the static frontend
  (`src/main/resources/static/app.js`, `index.html`, `styles.css`) for the async/new-status
  flow.
- [X] T027 [US3] Add `docker-compose.yml` (three `gridgain/ultimate:8.9.32` nodes, Control
  Center backend/frontend, four `merchant-0000N` simulator containers with per-merchant env,
  the `payments-demo-app` processor with discovery + license env and port 8080) and
  `gridgain-license.xml`. Depends on T022–T026.
- [X] T028 [P] [US3] Add off-compose GridGain scripts and node config:
  `gridgain/ignite-server-config.xml`, `gridgain/start-cluster.sh`, `gridgain/stop-cluster.sh`,
  and `src/main/resources/control-center.conf`.

**Checkpoint**: Full stack starts from one command; simulator containers isolated by profile.

---

## Phase 6: User Story 4 - Ingest an external source of record via CDC (Priority: P4)

**Goal**: Optional MySQL → Debezium → Kafka → GridGain sink ingestion path.

**Independent Test**: With the CDC stack up and the connector registered, a MySQL row change
appears as the matching GridGain cache entry.

### Implementation for User Story 4

- [X] T029 [P] [US4] Add the MySQL source schema mirroring the record types in
  `mysql/schema.sql` (accounts, merchants, payments, ledger_entries with keys/constraints).
- [X] T030 [P] [US4] Add the Debezium MySQL source connector config
  `mysql/mysql-payments-source.json` (unwrap + regex-route topics to cache names) and
  `mysql/register-source-connector.sh` to PUT it into Kafka Connect.
- [X] T031 [P] [US4] Add `mysql/docker.sh` to stand up Kafka, MySQL, and Kafka Connect off
  compose using the Debezium images and load the schema.
- [X] T032 [US4] Add `src/main/java/com/example/paymentsdemo/cdc/KafkaToGridGainSinkApplication.java`
  (`@Profile("cdc-sink")`, `WebApplicationType.NONE`, GridGain client) consuming the
  `accounts`/`merchants`/`payments`/`ledger_entries` topics and upserting/deleting cache
  entries. Depends on T004, T005.
- [X] T033 [P] [US4] Add sink run scripts `gridgain/start-sink.sh`,
  `gridgain/start-sink-container.sh`, and `gridgain/stop-sink-container.sh` (main-class override
  plus GridGain `--add-opens` flags). Depends on T032.

**Checkpoint**: Optional CDC ingestion projects MySQL changes into the cache.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T034 [P] Update `README.md` for the external GridGain cluster, one-command compose stack,
  the async merchant flow, and the optional MySQL + Debezium CDC path.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies.
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS all user stories.
- **User Story 1 (Phase 3)**: Depends on Foundational. The MVP.
- **User Story 2 (Phase 4)**: Depends on US1 (extends `PaymentService` settlement logic).
- **User Story 3 (Phase 5)**: Depends on Foundational; consumes the US1 DTOs/flow but the
  simulator role and compose stack are independently runnable.
- **User Story 4 (Phase 6)**: Depends on Foundational (cache set); independent of US1–US3.
- **Polish (Phase 7)**: After the stories it documents are complete.

### User Story Dependencies

- **US1 (P1)**: Foundation only.
- **US2 (P2)**: Builds on US1 settlement code.
- **US3 (P3)**: Foundation + US1 DTOs; simulator and compose otherwise standalone.
- **US4 (P4)**: Foundation only; fully optional and independent.

### Within Each User Story

- New enums/records/domain before services; services before controllers; compose file after the
  beans it wires together.

### Parallel Opportunities

- Setup: T002 and T003 in parallel.
- US1: T008–T012 (distinct new files) in parallel before the `PaymentService` work.
- US3: T022/T023, T025, T026, T028 touch distinct files and can proceed in parallel around the
  compose assembly (T027).
- US4: T029–T031 and T033 are distinct files runnable in parallel around the sink app (T032).

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1 (Setup) and Phase 2 (Foundational): client-mode GridGain + attempt cache.
2. Complete Phase 3 (US1): async authorize + merchant callback settlement.
3. Validate by authorizing against a running merchant simulator and watching the status move
   from `PENDING_MERCHANT` to `AUTHORIZED`/`DECLINED`.

### Incremental Delivery

1. Foundation → US1 (async authorization MVP).
2. US2 → automatic timeout and late-response handling.
3. US3 → one-command containerized stack with merchant simulators.
4. US4 → optional MySQL + Debezium CDC ingestion.

---

## Notes

- [P] tasks = different files, no dependencies.
- [Story] label maps each task to its user story for traceability.
- All tasks are marked complete because this list reverse-engineers a delivered commit.
- The processor, merchant simulator, and CDC sink are one jar selected by Spring profile
  (`!merchant-simulator`, `merchant-simulator`, `cdc-sink`).
- No automated tests: validate by exercising HTTP endpoints and the dashboard.
