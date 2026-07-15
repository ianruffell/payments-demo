---
description: "Task list for the transaction flow dashboard & async simulator controls increment"
---

# Tasks: Transaction Flow Dashboard & Async Simulator Controls

**Input**: Design documents from `/specs/003-transaction-flow-dashboard/`

**Prerequisites**: plan.md (required), spec.md (required for user stories)

**Tests**: This project ships no automated test suite; behavior is validated by exercising the
endpoint, the flow page, and the simulator controls live. No test tasks are included.

**Organization**: Tasks are grouped by user story. These tasks are reverse-engineered from
delivered commit 2dba980 and reflect the work as actually landed.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- File paths are relative to the repository root.

## Path Conventions

- Backend: `src/main/java/com/example/paymentsdemo/`
- Config/frontend: `src/main/resources/`
- Orchestration: `docker-compose.yml`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Configuration and profile groundwork the rest of the increment builds on.

- [X] T001 Add `demo.initiator.base-url` and `demo.processor.base-url`, and update
  `demo.fraud.threshold` (84.0) and `demo.simulator.target-decline-rate` (0.02) in
  `src/main/resources/application.yml`.
- [X] T002 Introduce the `payment-initiator` profile: widen every processor-side bean's profile
  from `!merchant-simulator` to `!merchant-simulator & !payment-initiator` across
  `api/AdminController.java`, `api/DashboardController.java`, `api/MerchantResultController.java`,
  `api/PaymentController.java`, `service/FraudService.java`, `service/MerchantAdminService.java`,
  `service/MerchantDispatchService.java`, `service/MerchantTimeoutMonitor.java`,
  `service/PaymentService.java`, and `service/SeedDataLoader.java`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Data shapes shared by the flow feature. No user story rendering can begin until
the snapshot DTOs exist.

**⚠️ CRITICAL**: The flow endpoint, service, and page all depend on these records.

- [X] T003 [P] Create `dto/TransactionFlowStageState.java` (record: `label`, `count`, `accent`).
- [X] T004 [P] Create `dto/TransactionFlowStep.java` (record: `id`, `title`, `description`,
  `total`, `states`).
- [X] T005 [P] Create `dto/TransactionFlowConnection.java` (record: `fromStepId`, `toStepId`,
  `label`, `count`).
- [X] T006 Create `dto/TransactionFlowSnapshot.java` (record: `generatedAtEpochMs`,
  `windowSeconds`, `totalTransactions`, `inFlightTransactions`, `approvalRateLastFiveMinutes`,
  `steps`, `connections`) — depends on T003–T005.

**Checkpoint**: Snapshot data model compiles; flow service and page work can begin.

---

## Phase 3: User Story 1 - Watch payments move through the pipeline live (Priority: P1) 🎯 MVP

**Goal**: A live, self-refreshing process map of where recent payments sit and how they flow.

**Independent Test**: With payments in the cache, open `http://localhost:8080/flow.html` (or
`GET /api/dashboard/transaction-flow`); confirm the four tiles and four stages populate with
counts consistent with cache contents and refresh about once per second.

### Implementation for User Story 1

- [X] T007 [US1] Implement `service/TransactionFlowService.java`: fixed 300s window; query
  `PAYMENTS` (`SELECT paymentId, status ... WHERE createdAtEpochMs >= ?`) and
  `MERCHANT_PAYMENT_ATTEMPTS` (`SELECT paymentId ... WHERE requestedAtEpochMs >= ?`); classify
  each payment by status and merchant-attempt presence into screening/merchant/settlement
  counts; compute approval rate; assemble the four steps and three connections into a
  `TransactionFlowSnapshot`. Depends on Phase 2.
- [X] T008 [US1] Implement `api/TransactionFlowController.java`: `GET
  /api/dashboard/transaction-flow` under profile `!merchant-simulator & !payment-initiator`,
  returning `transactionFlowService.snapshot()`. Depends on T007.
- [X] T009 [P] [US1] Create `src/main/resources/static/flow.html`: hero, four stat tiles
  (transactions/5m, in flight, approval rate, refresh window), the flow map container, and the
  "How to Read It" panel; link `styles.css` and `flow.js`.
- [X] T010 [P] [US1] Create `src/main/resources/static/flow.js`: fetch the snapshot, render tiles,
  build stage cards with accent-colored outcome pills, build animated connectors (pulse count
  and duration scaled from connection count), and re-fetch every 1s with visible failure
  messaging on refresh/initial-load errors.
- [X] T011 [US1] Add flow visualization styles to `src/main/resources/static/styles.css`:
  `.flow-map`, `.flow-step` and outcome pill accents, `.flow-connector`/`.flow-connector__track`,
  `.flow-pulse`, and the `stream-x`/`stream-y` keyframes (including the responsive layout).

**Checkpoint**: Transaction-flow map is fully functional and demonstrable on its own.

---

## Phase 4: User Story 2 - Drive load without blocking the processor (Priority: P2)

**Goal**: Move the traffic generator out-of-process behind an HTTP gateway while keeping the
dashboard's simulator controls unchanged.

**Independent Test**: With processor and payment-initiator running, POST
`/api/simulator/start?ratePerSecond=120` to the processor, watch throughput rise, POST
`/api/simulator/stop`, and confirm status flips to not-running.

### Implementation for User Story 2

- [X] T012 [US2] Rework `simulator/PaymentSimulator.java` to the `payment-initiator` profile:
  replace in-process `PaymentService` calls with an `HttpClient` + `ObjectMapper` that POST to the
  processor's `/api/payments/authorize`, `/api/payments/{id}/capture`, and
  `/api/payments/{id}/refund`; inject `demo.processor.base-url`; update log/text wording to
  "payment initiator".
- [X] T013 [US2] Create `api/PaymentInitiatorController.java` under profile `payment-initiator`:
  `POST /api/simulator/start`, `POST /api/simulator/stop`, `GET /api/simulator`, returning
  `SimulatorStatusResponse` from the local `PaymentSimulator`. Depends on T012.
- [X] T014 [US2] Create `service/SimulatorGatewayService.java` under profile
  `!merchant-simulator & !payment-initiator`: HTTP proxy to the initiator for start/stop/status;
  throw on failed start/stop, return fallback status (`false, 0, 0`) on failed/unreachable status;
  inject `demo.initiator.base-url`.
- [X] T015 [US2] Update `api/SimulatorController.java` to delegate start/stop/status to
  `SimulatorGatewayService` instead of `PaymentSimulator`. Depends on T014.
- [X] T016 [US2] Update `service/DashboardService.java` to source simulator status from
  `SimulatorGatewayService` instead of the in-process `PaymentSimulator`. Depends on T014.
- [X] T017 [US2] Add `normalizeMerchantLimits()` to `service/SeedDataLoader.java` with a
  configurable floor (`demo.seed.merchant-min-daily-limit-minor`), raising any seeded merchant's
  daily limit up to that floor so simulated approvals are not gated by limit exhaustion.
- [X] T018 [US2] Add the `payments-demo-initiator` service to `docker-compose.yml`
  (`SPRING_PROFILES_ACTIVE=payment-initiator`, GridGain discovery addresses, license volume,
  `DEMO_PROCESSOR_BASE_URL`, dependencies on GridGain nodes, merchants, and the app).

**Checkpoint**: Simulator runs as a separate process driven through the gateway; dashboard
controls and status behave as before and degrade gracefully when the initiator is down.

---

## Phase 5: User Story 3 - Move between the two views (Priority: P3)

**Goal**: Navigation chips linking the dashboard and the transaction-flow page.

**Independent Test**: Open `/`, confirm two chips with "Dashboard" active, click "Transaction
Flow", and confirm the flow page loads with its chip active.

### Implementation for User Story 3

- [X] T019 [US3] Add the `hero-split` layout and `hero-actions` navigation chips (Dashboard /
  Transaction Flow) to `src/main/resources/static/index.html`, marking "Dashboard" active and
  aligning the fraud-threshold default readouts to 84.
- [X] T020 [P] [US3] Add `.hero-split`, `.hero-actions`, and `.nav-chip` (plus `.active`) styles
  to `src/main/resources/static/styles.css`. (The matching chips on `flow.html` are delivered in
  T009.)

**Checkpoint**: Operators can move between both views with the current page highlighted.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final validation of the delivered increment.

- [X] T021 Validate the one-command stack: bring the stack up with an env file and confirm the
  `payments-demo-initiator` container starts and reaches the processor.
- [X] T022 Walk the acceptance scenarios live: flow map populates and refreshes; screening vs
  merchant decline attribution is correct; empty window renders zeros; simulator start/stop moves
  throughput; status falls back safely when the initiator is stopped.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately.
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS User Story 1 (and is used by the endpoint).
- **User Story 1 (Phase 3)**: Depends on Foundational; otherwise independent.
- **User Story 2 (Phase 4)**: Depends only on Setup (profiles/config); independent of US1.
- **User Story 3 (Phase 5)**: Depends only on Setup; independent of US1 and US2.
- **Polish (Phase 6)**: Depends on the user stories being in place.

### User Story Dependencies

- **US1 (P1)**: Needs the Phase 2 DTOs. Delivers the flow map as a standalone MVP.
- **US2 (P2)**: Needs the Phase 1 profile/config work. Independently testable via the simulator
  endpoints; does not depend on US1.
- **US3 (P3)**: Small navigation slice; independent of US1/US2 beyond the shared stylesheet.

### Within Each User Story

- DTOs (Phase 2) before the service; service before the controller; backend before/with the page.
- `SimulatorGatewayService` (T014) before its consumers (T015, T016).
- `PaymentSimulator` rework (T012) before the initiator controller (T013).

### Parallel Opportunities

- Phase 2: T003, T004, T005 can run in parallel (separate DTO files); T006 waits on them.
- US1: T009 and T010 (static assets) can run in parallel with each other and alongside the
  backend service/controller work.
- US3: T020 (styles) can run in parallel with unrelated files.

---

## Parallel Example: Foundational DTOs

```bash
# Launch the three independent DTO records together:
Task: "Create dto/TransactionFlowStageState.java"
Task: "Create dto/TransactionFlowStep.java"
Task: "Create dto/TransactionFlowConnection.java"
# Then create dto/TransactionFlowSnapshot.java once the above compile.
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1 Setup and Phase 2 Foundational DTOs.
2. Complete Phase 3: the flow service, endpoint, page, and styles.
3. **STOP and VALIDATE**: open `/flow.html`, confirm the map reconciles to cache contents and
   refreshes — this alone is a demonstrable increment.

### Incremental Delivery

1. Setup + Foundational → data model ready.
2. Add US1 → validate live → demo the flow map (MVP).
3. Add US2 → validate simulator start/stop through the gateway and graceful fallback.
4. Add US3 → validate cross-page navigation.
5. Run Phase 6 stack and acceptance validation.

---

## Notes

- [P] tasks touch different files with no ordering dependency.
- All tasks are marked complete because they reverse-engineer an already-delivered commit
  (2dba980).
- The project has no automated tests; validate by exercising the endpoint, the flow page, and the
  simulator controls, per the constitution.
- The flow snapshot stays on the GridGain cache hot path — no synchronous external-DB calls.
