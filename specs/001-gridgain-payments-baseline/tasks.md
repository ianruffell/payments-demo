---
description: "Task list for GridGain Payments Demo Baseline"
---

# Tasks: GridGain Payments Demo Baseline

**Input**: Design documents from `/specs/001-gridgain-payments-baseline/`

**Prerequisites**: plan.md (required), spec.md (required for user stories)

**Tests**: This project ships no automated test suite; behavior is validated by exercising
the REST endpoints and the dashboard. No test tasks are included.

**Organization**: Tasks are grouped by user story so each story can be implemented and
demonstrated independently, on top of the shared foundation.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1–US4)
- Include exact file paths in descriptions

## Path Conventions

- Single Spring Boot module: Java under `src/main/java/com/example/paymentsdemo/`,
  resources under `src/main/resources/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project skeleton and build

- [X] T001 Create the Maven project `pom.xml` with the Spring Boot 3.3.4 parent, Java 17, the web and validation starters, the GridGain 8.9.30 dependencies (`ignite-core`, `ignite-indexing`, `ignite-spring`) plus the GridGain external Maven repository, and the Spring Boot Maven plugin.
- [X] T002 [P] Add `.gitignore` for `target/`, `ignite/`, IDE files, and OS artifacts.
- [X] T003 [P] Create the Spring Boot entry point `src/main/java/com/example/paymentsdemo/PaymentsDemoApplication.java`.
- [X] T004 [P] Create `src/main/resources/application.yml` with server port 8080 and `demo.*` properties for seed sizes, simulator rate, target decline rate, and fraud threshold.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Domain model, cache configuration, and seed data that every user story depends on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T005 [P] Create domain enums in `src/main/java/com/example/paymentsdemo/domain/`: `AccountStatus.java`, `RiskTier.java`, `PaymentStatus.java`, `LedgerDirection.java`.
- [X] T006 [P] Create `domain/Account.java` (accountId, customerName, availableBalanceMinor, currency, status, riskTier) with `@QuerySqlField` annotations and `Serializable`.
- [X] T007 [P] Create `domain/Merchant.java` (merchantId, name, category, country, active, maxAmountMinor, dailyLimitMinor) with `@QuerySqlField` annotations.
- [X] T008 [P] Create `domain/Payment.java` (paymentId, accountId, merchantId, amountMinor, currency, status, created/updated/captured/refunded timestamps, declineReason, fraudScore, suspicious) with indexed `@QuerySqlField` annotations.
- [X] T009 [P] Create `domain/LedgerEntry.java` (entryId, paymentId, accountId, merchantId, direction, amountMinor, currency, entryType, createdAtEpochMs).
- [X] T010 Create `service/CacheNames.java` with constants for the `accounts`, `merchants`, `payments`, and `ledger_entries` caches (depends on T005–T009 for the types they name).
- [X] T011 Create `config/GridGainConfig.java`: start an embedded Ignite node (instance name, TCP VM discovery on 127.0.0.1, data region with configurable persistence, WAL settings), activate the cluster, and register the four partitioned transactional caches with indexed types; close the node on shutdown (depends on T006–T010).
- [X] T012 Create `service/SeedDataLoader.java` as an `ApplicationRunner` that, only when the accounts and merchants caches are empty, streams the configured number of accounts (random balances, currencies, weighted statuses and risk tiers) and merchants (random categories, countries, limits) via `IgniteDataStreamer` (depends on T006, T007, T010, T011).

**Checkpoint**: Embedded GridGain comes up, caches exist, and a fresh start seeds accounts and merchants.

---

## Phase 3: User Story 1 - Process a card payment through its lifecycle (Priority: P1) 🎯 MVP

**Goal**: Authorize, capture, and refund payments transactionally over REST with fraud scoring, validation, and ledger entries.

**Independent Test**: Start the app, POST an authorize request for a seeded account/merchant, then POST capture and refund; verify statuses, balance changes, and ledger entries.

### Implementation for User Story 1

- [X] T013 [P] [US1] Create `dto/AuthorizePaymentRequest.java` with validation (`@NotBlank` ids/currency, `@Min(1)` amount).
- [X] T014 [P] [US1] Create `service/PaymentOperationResult.java` (payment, message, duplicate flag).
- [X] T015 [P] [US1] Create `dto/PaymentResponse.java` with a factory that projects a `Payment` plus message and duplicate flag.
- [X] T016 [US1] Create `service/FraudService.java`: compute a fraud score from amount, account risk tier, merchant category/country, and a random component; expose `isFraudulent`/`isSuspicious` against a runtime-adjustable threshold seeded from `demo.fraud.threshold` (depends on T006, T007).
- [X] T017 [US1] Create `service/PaymentService.authorize`: within a pessimistic repeatable-read transaction, short-circuit duplicate payment ids, look up account/merchant (404 if unknown), score fraud, run the validation chain (account active, merchant active, currency match, merchant max, merchant daily total via scan query, sufficient funds, fraud threshold), store the payment as AUTHORIZED or DECLINED with reason, and on approval debit the balance and write an `AUTH_HOLD` ledger entry (depends on T011, T013, T014, T016).
- [X] T018 [US1] Add `PaymentService.capture`: transactionally move an AUTHORIZED payment to CAPTURED (409 otherwise), set captured time, and write a `CAPTURE` ledger entry (depends on T017).
- [X] T019 [US1] Add `PaymentService.refund`: transactionally move a CAPTURED payment to REFUNDED (409 otherwise), credit the account balance back, set refunded time, and write a `REFUND` ledger entry (depends on T017).
- [X] T020 [US1] Create `api/PaymentController.java` exposing `POST /api/payments/authorize` (201), `POST /api/payments/{paymentId}/capture`, and `POST /api/payments/{paymentId}/refund`, returning `PaymentResponse` (depends on T015, T017–T019).

**Checkpoint**: The payment engine is fully usable over REST, independent of dashboard, simulator, and admin.

---

## Phase 4: User Story 2 - Watch live payment activity on a dashboard (Priority: P2)

**Goal**: Aggregate recent payment activity into a snapshot and render it in a self-refreshing browser dashboard.

**Independent Test**: With payments present, GET `/api/dashboard` and confirm the aggregated metrics; open the dashboard root and confirm it populates and refreshes.

### Implementation for User Story 2

- [X] T021 [P] [US2] Create the dashboard projection records in `dto/`: `DeclineReasonCount.java`, `MerchantVolume.java`, `RecentSuspiciousPayment.java`, `ThroughputPoint.java`, and `SimulatorStatusResponse.java`.
- [X] T022 [US2] Create `dto/DashboardSnapshot.java` aggregating throughput, approval rate, status counts, declines, top merchants, suspicious payments, throughput series, simulator status, and fraud threshold (depends on T021).
- [X] T023 [US2] Create `service/DashboardService.java`: query recent payments over the five-minute window with an Ignite SQL-fields query, then compute last-minute throughput, approval rate, status mix, declines by reason, top-five merchants (resolving names from the merchants cache), up-to-ten newest suspicious payments, and a 60-second throughput series (depends on T011, T016, T022; reads simulator status from US3's `PaymentSimulator`).
- [X] T024 [US2] Create `api/DashboardController.java` exposing `GET /api/dashboard` (depends on T023).
- [X] T025 [P] [US2] Create `src/main/resources/static/index.html`: hero, control panels, stat cards, throughput/status visuals, and tables for declines, top merchants, and suspicious payments.
- [X] T026 [P] [US2] Create `src/main/resources/static/app.js`: fetch the snapshot, render metrics/bars/pills/tables, and poll once per second.
- [X] T027 [P] [US2] Create `src/main/resources/static/styles.css` for the dashboard layout and components.

**Checkpoint**: Live activity is observable in the browser and via the snapshot endpoint.

---

## Phase 5: User Story 3 - Generate realistic traffic with the simulator (Priority: P3)

**Goal**: Continuously generate authorize traffic with automatic captures and occasional refunds at a controllable rate.

**Independent Test**: POST start with a rate, watch generated-payment counts and dashboard throughput rise, then POST stop and confirm generation halts.

### Implementation for User Story 3

- [X] T028 [US3] Create `simulator/PaymentSimulator.java`: a scheduled per-second ticker that, when running, submits the configured rate of authorize requests via `PaymentService`, builds approved and (at the target decline rate) deliberately-declined requests from random seeded accounts/merchants, schedules automatic capture of most approvals and occasional refunds on worker/delayed executors, and tracks running state, rate, and generated count (depends on T012, T017–T019).
- [X] T029 [US3] Create `api/SimulatorController.java` exposing `POST /api/simulator/start?ratePerSecond=`, `POST /api/simulator/stop`, and `GET /api/simulator` returning `SimulatorStatusResponse` (depends on T028, T021).

**Checkpoint**: The demo is self-driving and feeds the dashboard without manual calls.

---

## Phase 6: User Story 4 - Steer the demo with operator controls (Priority: P4)

**Goal**: Trigger merchant outages and adjust the fraud threshold at runtime, with immediate effect.

**Independent Test**: Deactivate a merchant and confirm `MERCHANT_INACTIVE` declines; lower the fraud threshold and confirm rising `FRAUD_SCORE_EXCEEDED` declines; reverse both.

### Implementation for User Story 4

- [X] T030 [US4] Create `service/MerchantAdminService.java` to set a merchant's active flag in the merchants cache, rejecting unknown merchant ids with 404 (depends on T007, T011).
- [X] T031 [US4] Create `api/AdminController.java` exposing `POST /api/admin/merchants/{merchantId}/status?active=` and `POST /api/admin/fraud-threshold?value=`, delegating to `MerchantAdminService` and `FraudService` (depends on T016, T030).
- [X] T032 [US4] Wire the dashboard controls in `src/main/resources/static/app.js` and `index.html` to the simulator, fraud-threshold, and merchant-status endpoints so operator actions apply immediately (depends on T026, T029, T031).

**Checkpoint**: All four user stories are independently demonstrable.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately.
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS all user stories.
- **User Stories (Phases 3–6)**: All depend on Foundational completion.
  - US1 (P1) has no dependency on other stories.
  - US2 (P2) reads simulator status from US3's `PaymentSimulator`; the snapshot otherwise works from US1 data.
  - US3 (P3) depends on US1's `PaymentService`.
  - US4 (P4) depends on US1's `FraudService` and the foundation; its dashboard wiring depends on US2 and US3 controls.

### Within Each User Story

- DTOs/enums before the services that use them.
- Services before the controllers that expose them.
- Static dashboard markup/scripts before wiring operator controls to endpoints.

### Parallel Opportunities

- Setup: T002, T003, T004 in parallel after T001.
- Foundational: T005–T009 (domain types) in parallel; T010–T012 sequential after them.
- US1: T013, T014, T015 in parallel; then T016 → T017 → T018/T019 → T020.
- US2: T021 and the static assets T025/T026/T027 in parallel; T022 → T023 → T024.

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1 (Setup) and Phase 2 (Foundational).
2. Complete Phase 3 (US1): the transactional authorize/capture/refund engine.
3. Validate US1 by driving the REST endpoints against seeded data.

### Incremental Delivery

1. Foundation ready → US1 (payment engine, MVP) → US2 (dashboard) → US3 (simulator) → US4 (operator controls).
2. Each story adds observable value without breaking the previous ones.

---

## Notes

- [P] tasks touch different files with no ordering dependency.
- No automated tests: validate each story by exercising its endpoints and the dashboard.
- All live state is in GridGain caches; there is no external store or CDC in this baseline.
- Tasks are marked [X] because this file reverse-engineers an already-delivered commit.
