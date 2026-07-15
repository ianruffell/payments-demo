# Feature Specification: Transaction Flow Dashboard & Async Simulator Controls

**Feature Branch**: `003-transaction-flow-dashboard`

**Created**: 2026-05-08

**Status**: Delivered

**Input**: Reverse-engineered from commit 2dba980 — "add transaction flow dashboard and async simulator controls"

## User Scenarios & Testing *(mandatory)*

<!--
  User stories are prioritized as independently demonstrable slices of the delivered
  increment. Each can be watched live in a browser or exercised over HTTP on its own.
-->

### User Story 1 - Watch payments move through the pipeline live (Priority: P1)

An operator opens the transaction-flow page and sees a live process map of where recent
payments currently sit: how many entered the processor, how many were forwarded to
merchants versus declined up front, the live merchant-decision outcomes (awaiting,
approved, declined, timed out), and where approved payments have settled (authorized,
captured, refunded). Animated connectors between stages show how much traffic is flowing
forward. Headline tiles summarize transactions in the last five minutes, how many are in
flight, and the recent approval rate. The whole view refreshes on its own so the operator
can narrate what is happening without touching anything.

**Why this priority**: This is the headline capability of the increment and the reason the
feature exists — it makes the payment pipeline observable at a glance. It delivers standalone
value even if nothing else in the commit is exercised, because the flow view reads any
payments already present in the cache.

**Independent Test**: With payments present in the GridGain caches, open
`http://localhost:8080/flow.html` (or call `GET /api/dashboard/transaction-flow`) and confirm
the four headline tiles populate, the four stage columns show non-zero counts consistent with
cache contents, and the counts refresh roughly once per second without a page reload.

**Acceptance Scenarios**:

1. **Given** payments exist in the cache from the last five minutes, **When** the operator
   opens the transaction-flow page, **Then** the map renders four stages (Received, Initial
   Screening, Merchant Review, Settlement State) with counts that reconcile to the cached
   payment statuses.
2. **Given** the transaction-flow page is open, **When** one second elapses, **Then** the
   snapshot is re-fetched and the tiles, stage counts, connector counts, and "Updated"
   timestamp change to reflect the newest cache state.
3. **Given** a payment was dispatched to a merchant and later declined, **When** the snapshot
   is computed, **Then** it is counted under "Declined" in Merchant Review (not "Declined
   before merchant"), because it appears in the merchant-attempt records.
4. **Given** no payments exist in the trailing five-minute window, **When** the snapshot is
   requested, **Then** every count is zero and the approval rate reads `0.0%` without error.

---

### User Story 2 - Drive load without blocking the processor (Priority: P2)

An operator starts and stops simulated authorize/capture/refund traffic from the dashboard.
The traffic generator runs as a separate process (the payment initiator) that calls the
processor's public payment APIs over HTTP, rather than living inside the processor and calling
its services in-process. The dashboard's simulator controls transparently forward start,
stop, and status requests to that separate initiator so the operator experience is unchanged
while the load generator is decoupled from the processor.

**Why this priority**: It changes how load is generated — decoupling the generator into its
own container — which supports demonstrating throughput independently of the processor. It is
valuable but secondary to the flow visualization, and it reuses the existing simulator control
surface, so the operator-facing change is minimal.

**Independent Test**: With the processor and a separate payment-initiator process running, POST
`/api/simulator/start?ratePerSecond=120` to the processor, confirm the response reports
`running=true` at the requested rate, watch dashboard throughput climb, then POST
`/api/simulator/stop` and confirm traffic ceases and status reports `running=false`.

**Acceptance Scenarios**:

1. **Given** the payment initiator is running, **When** the operator posts to
   `/api/simulator/start` on the processor, **Then** the processor forwards the request to the
   initiator and returns the initiator's status showing the simulator running at the requested
   rate.
2. **Given** the simulator is running, **When** the operator posts to `/api/simulator/stop` on
   the processor, **Then** the initiator stops generating traffic and the returned status shows
   `running=false`.
3. **Given** the initiator generates an authorized payment, **When** the payment is authorized,
   **Then** the initiator drives the follow-on capture (and occasional refund) by calling the
   processor's public payment endpoints over HTTP rather than in-process service calls.
4. **Given** the payment initiator is temporarily unreachable, **When** the dashboard requests
   simulator status, **Then** the processor returns a safe fallback status (`running=false`,
   rate `0`, generated `0`) instead of failing the dashboard load.

---

### User Story 3 - Move between the two views (Priority: P3)

An operator switches between the metrics dashboard and the transaction-flow map using
navigation chips present on both pages, with the current page highlighted.

**Why this priority**: A small navigation affordance that ties the new page to the existing
dashboard. It is convenience, not core function, so it is lowest priority.

**Independent Test**: Open `http://localhost:8080/`, confirm two navigation chips ("Dashboard",
"Transaction Flow") appear with "Dashboard" active, click "Transaction Flow", and confirm the
flow page loads with its chip now active.

**Acceptance Scenarios**:

1. **Given** the operator is on the dashboard, **When** they view the hero area, **Then** two
   navigation chips are shown and the "Dashboard" chip is styled as active.
2. **Given** the operator is on the transaction-flow page, **When** they click "Dashboard",
   **Then** the metrics dashboard loads and its chip becomes active.

---

### Edge Cases

- **Empty window**: When no payments fall inside the trailing five-minute window, all stage and
  connector counts are zero and the approval rate is `0.0%`; the page still renders every stage.
- **Declined-before-merchant vs merchant-declined**: A `DECLINED` payment is attributed to
  "Declined before merchant" only when it has no merchant-attempt record; otherwise it is a
  merchant decline. This keeps the screening and merchant-review columns internally consistent.
- **In-flight counting**: "In Flight" reflects payments still awaiting a merchant decision
  (`PENDING_MERCHANT`); timed-out payments are surfaced separately under Merchant Review.
- **Connector visual scaling**: The number and speed of the animated pulses on a connector are
  derived from its count (more traffic → more, faster pulses; zero traffic → no pulses),
  bounded so the animation stays legible.
- **Snapshot refresh failure**: If a periodic snapshot fetch fails, the page shows "Flow refresh
  failed"; if the very first load fails, it shows "Initial flow load failed" — the page does not
  silently freeze.
- **Initiator start/stop failure**: A failed start or stop call against the initiator surfaces
  an error (it is not swallowed), whereas a failed status call degrades gracefully to the
  fallback status so the dashboard keeps rendering.
- **Merchant capacity**: Simulated traffic could otherwise be declined by low per-merchant daily
  limits; seeded merchant daily limits are raised to a configured floor so approvals reflect
  fraud and validation logic rather than incidental limit exhaustion.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST expose a read-only endpoint `GET /api/dashboard/transaction-flow` that
  returns a point-in-time snapshot of the transaction pipeline.
- **FR-002**: The snapshot MUST be computed over a fixed trailing window of 300 seconds
  (five minutes) of payment activity read from the GridGain caches.
- **FR-003**: The snapshot MUST report headline aggregates: total transactions in the window,
  in-flight (awaiting-merchant) transactions, and the approval rate over the window rounded to
  one decimal place.
- **FR-004**: The snapshot MUST describe four ordered pipeline stages — Received, Initial
  Screening, Merchant Review, and Settlement State — each carrying a title, description, stage
  total, and a list of named outcome states with counts and a visual accent.
- **FR-005**: Initial Screening MUST split window traffic into "Sent to merchant" (payments with
  a merchant-attempt record) and "Declined before merchant" (declined payments without one).
- **FR-006**: Merchant Review MUST report awaiting-response, approved-path, declined, and
  timed-out counts derived from payment status and merchant-attempt presence.
- **FR-007**: Settlement State MUST break the approved path into authorized, captured, and
  refunded counts.
- **FR-008**: The snapshot MUST include ordered connections between consecutive stages
  (received→screening, screening→merchant, merchant→settlement) each with a label and a forward
  traffic count.
- **FR-009**: System MUST serve a transaction-flow page (`flow.html`) that renders the snapshot
  as a stage map with per-stage outcome pills and animated inter-stage connectors.
- **FR-010**: The transaction-flow page MUST re-fetch the snapshot approximately once per second
  and update the view in place without a full page reload.
- **FR-011**: The transaction-flow page MUST display the snapshot generation time and MUST show a
  visible failure indicator if a refresh or the initial load fails.
- **FR-012**: Simulator start/stop/status control MUST continue to be available on the processor
  at `/api/simulator/start`, `/api/simulator/stop`, and `GET /api/simulator`, returning simulator
  running state, rate per second, and generated-payment count.
- **FR-013**: The processor MUST delegate simulator control to a separate payment-initiator
  process over HTTP rather than running the traffic generator in-process.
- **FR-014**: The payment-initiator process MUST generate authorize traffic and drive follow-on
  capture and occasional refund by calling the processor's public payment endpoints over HTTP.
- **FR-015**: When the payment initiator is unreachable, a simulator status request MUST return a
  safe fallback status (not running, rate 0, generated 0); a failed start or stop MUST surface an
  error.
- **FR-016**: The traffic generator, its control endpoint, and the processor-side gateway MUST be
  activated by Spring profile so the initiator and processor load disjoint sets of beans
  (`payment-initiator` vs. `!merchant-simulator & !payment-initiator`).
- **FR-017**: The local stack MUST include a dedicated payment-initiator container wired to the
  GridGain cluster and pointed at the processor base URL.
- **FR-018**: On seed/startup the processor MUST raise any seeded merchant daily limit below a
  configured floor up to that floor, so simulated approvals are not gated by incidental daily
  limit exhaustion.
- **FR-019**: Both the dashboard and the transaction-flow page MUST provide navigation chips
  linking to each other with the current page marked active.

### Key Entities *(include if feature involves data)*

- **TransactionFlowSnapshot**: The full point-in-time view returned by the endpoint — generation
  timestamp, window length in seconds, total and in-flight transaction counts, window approval
  rate, and the ordered lists of steps and connections.
- **TransactionFlowStep**: One pipeline stage — stable id, display title, description, stage
  total, and the list of outcome states within it.
- **TransactionFlowStageState**: One outcome within a stage — a label, a count, and a visual
  accent (brand/success/warning/danger/muted) driving how the pill is colored.
- **TransactionFlowConnection**: A directed link between two consecutive steps — source step id,
  target step id, a label, and the count of transactions flowing forward across it.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An operator can see the current state of the payment pipeline within five seconds
  of opening the transaction-flow page, with no manual refresh.
- **SC-002**: The flow view updates on its own approximately once per second, so changes in
  traffic appear within about two seconds of occurring.
- **SC-003**: Stage and connector counts in the snapshot reconcile to the payment statuses held
  in the cache for the trailing five-minute window (screening + merchant + settlement counts are
  mutually consistent).
- **SC-004**: Starting the simulator from the dashboard produces visibly rising throughput, and
  stopping it returns throughput toward zero, with status reflecting the change on the next
  refresh.
- **SC-005**: The dashboard continues to load and render simulator status even when the payment
  initiator is unavailable.
- **SC-006**: The whole stack, including the separate payment-initiator, still comes up from a
  single Docker Compose command with a chosen env file.

## Assumptions

- The GridGain `PAYMENTS` and `MERCHANT_PAYMENT_ATTEMPTS` caches are queryable via SQL and hold
  the recent payments and merchant-attempt records the snapshot reads.
- Prior increments already established payment authorize/capture/refund handling, the merchant
  dispatch flow, and the metrics dashboard; this increment adds the flow view and reshapes how
  the simulator is hosted, and reuses that existing behavior as context.
- A five-minute trailing window is an acceptable fixed horizon for the demo's flow view.
- The payment initiator and the processor share the same GridGain cluster and reach each other by
  the compose service hostnames configured via `demo.initiator.base-url` and
  `demo.processor.base-url`.
- Static HTML/CSS/JS served directly by Spring Boot with no build step remains the frontend
  approach.
