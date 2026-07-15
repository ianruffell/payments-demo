---

description: "Task list for flow throughput charts and reset helper"
---

# Tasks: Flow Throughput Charts and Reset Helper

**Input**: Design documents from `/specs/004-flow-throughput-charts/`

**Prerequisites**: plan.md (required), spec.md (required for user stories)

**Tests**: No automated tests. The project validates user-visible behavior by exercising it
through the UI and endpoints; verification tasks below describe manual checks.

**Organization**: Tasks are grouped by user story so each can be implemented and verified
independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2)
- Include exact file paths in descriptions

## Path Conventions

Single Spring Boot web-service module at repository root: backend under
`src/main/java/com/example/paymentsdemo/`, static frontend under
`src/main/resources/static/`, operational tooling under `gridgain/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Repository hygiene for the new artifacts introduced by this increment.

- [X] T001 Extend `.gitignore` to ignore `BOOT-INF/`, `superset/`, and `screenshot.png` so
  build and capture artifacts stay out of version control.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The DTO shape that both the throughput derivation and the chart rendering depend
on.

**⚠️ CRITICAL**: User Story 1 cannot be completed until this is done.

- [X] T002 Add a `throughputSeries` field (list of `ThroughputPoint`) to the
  `TransactionFlowStep` record in
  `src/main/java/com/example/paymentsdemo/dto/TransactionFlowStep.java`, reusing the existing
  `ThroughputPoint` DTO.

**Checkpoint**: Flow step DTO carries throughput; server and client work can proceed.

---

## Phase 3: User Story 1 - See per-stage throughput on the flow view (Priority: P1) 🎯 MVP

**Goal**: Every flow stage shows a live throughput line chart and a headline tx/s figure.

**Independent Test**: Start the stack and simulator, open `/flow.html`, confirm all four
stage cards render a chart and a throughput headline that changes with traffic.

### Implementation for User Story 1

- [X] T003 [US1] Add a `throughputSeries(payments, now, filter)` helper to
  `src/main/java/com/example/paymentsdemo/service/TransactionFlowService.java` that buckets the
  in-flight payment history into a rolling 60-second, one-second-resolution series
  (`THROUGHPUT_WINDOW_SECONDS = 60`), initializing empty buckets to zero.
- [X] T004 [US1] Wire a per-stage throughput series into each `TransactionFlowStep` built in
  `TransactionFlowService.java`: created and screening count all payments; merchant filters on
  `PaymentHistoryRow::merchantAttempted`; settlement filters on status AUTHORIZED, CAPTURED, or
  REFUNDED. (depends on T002, T003)
- [X] T005 [P] [US1] Render the chart and headline per stage in
  `src/main/resources/static/flow.js`: read `step.throughputSeries`, compute the latest-second
  value for non-settlement stages and the window average for settlement (label "Settlement
  throughput"), and inject the throughput meta block and inline SVG (area + line) into the
  stage card. (depends on T002)
- [X] T006 [P] [US1] Add a `seriesLinePath(points, maxValue, width, height, inset)` helper in
  `flow.js` that maps the series to an SVG path, handling the empty and single-point cases
  without division-by-zero. (depends on T002)
- [X] T007 [P] [US1] Style the throughput chart in
  `src/main/resources/static/styles.css`: add `.flow-step__throughput`,
  `-meta`, `-chart`, `-area`, and `-line` rules, and align flow-map/flow-step/flow-connector
  items to the top so cards with charts lay out cleanly.
- [X] T008 [US1] Verify on `/flow.html` with the simulator running that all four stages show a
  chart and headline, empty windows show 0 tx/s with a flat baseline, and settlement uses the
  averaged value. (depends on T004, T005, T006, T007)

**Checkpoint**: Per-stage throughput charts are live on the flow view — MVP complete.

---

## Phase 4: User Story 2 - Reset demo data between runs (Priority: P2)

**Goal**: A single helper returns the stack to a clean state so the demo can be re-run.

**Independent Test**: With the stack running, execute `gridgain/clear-demo-data.sh` and
confirm the simulator stops, the transient GridGain tables empty, and flow/dashboard counts
return to zero.

### Implementation for User Story 2

- [X] T009 [US2] Guard `generatePayment()` in
  `src/main/java/com/example/paymentsdemo/simulator/PaymentSimulator.java` with an early return
  when `running` is false, so payment tasks already queued at stop do not create payments after
  a stop.
- [X] T010 [P] [US2] Add `gridgain/clear-demo-data.sh`: parameterize docker/compose, service
  names, connection strings, and binaries via environment variables with compose defaults;
  require the MariaDB and GridGain services to be running before proceeding.
- [X] T011 [US2] In `clear-demo-data.sh`, stop the simulator via
  `POST /api/simulator/stop` (continuing with a message if the endpoint is unavailable) before
  any deletion. (depends on T010)
- [X] T012 [US2] In `clear-demo-data.sh`, clear the transient GridGain tables
  (`MerchantPaymentAttempt`, `LedgerEntry`, `Payment`) via sqlline, and clear the MariaDB archive
  tables (`LEDGER_ENTRY_ARCHIVE`, `PAYMENT_ARCHIVE`) only when `CLEAR_ARCHIVE=true`. (depends on
  T010)
- [X] T013 [US2] Make the script executable and verify end to end: run it against the running
  stack, confirm the simulator stops, GridGain counts reset to zero, and the MariaDB archive is
  untouched by default. (depends on T009, T011, T012)

**Checkpoint**: The demo is repeatable between runs from a clean state.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies.
- **Foundational (Phase 2)**: Blocks User Story 1 (DTO shape).
- **User Story 1 (Phase 3)**: Depends on Phase 2.
- **User Story 2 (Phase 4)**: Independent of US1; depends only on the existing simulator and
  stack. Can proceed in parallel with US1 once Setup is done.

### User Story Dependencies

- **User Story 1 (P1)**: Requires the `TransactionFlowStep` DTO change (T002).
- **User Story 2 (P2)**: Fully independent of US1; touches the simulator and a new script only.

### Within User Story 1

- T003 (helper) before T004 (wiring). T005/T006/T007 depend only on the DTO (T002) and can run
  in parallel with the server-side wiring. T008 verifies after all are in place.

### Within User Story 2

- T010 (script scaffold) before T011/T012 (script sections). T009 (simulator guard) is
  independent and can be done in parallel. T013 verifies after all are in place.

### Parallel Opportunities

- T005, T006, T007 (frontend/CSS) can run in parallel with T003/T004 (backend) once T002 lands.
- T009 (simulator guard) and T010 (script) can run in parallel.
- US1 and US2 can be worked by different people after Phase 1.

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1 (Setup) and Phase 2 (DTO foundation).
2. Complete Phase 3 (throughput charts).
3. Verify on `/flow.html` — this alone delivers the headline value of the increment.

### Incremental Delivery

1. Setup + Foundational → DTO ready.
2. Add User Story 1 → verify live charts → demo (MVP).
3. Add User Story 2 → verify reset → demo repeatable.

---

## Notes

- [P] tasks = different files, no dependencies.
- [Story] label maps each task to its user story for traceability.
- No automated tests; verification is by exercising the flow view and the reset script.
- Commit after each logical group.
