# Feature Specification: Flow Throughput Charts and Reset Helper

**Feature Branch**: `004-flow-throughput-charts`

**Created**: 2026-05-20

**Status**: Delivered

**Input**: Reverse-engineered from commit 2e55a29 — "Add flow throughput charts and reset helper"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See per-stage throughput on the flow view (Priority: P1)

An operator watching the transaction-flow view (`/flow.html`) wants to see not just how
many transactions have passed through each stage, but how fast each stage is currently
moving. Each stage card shows a compact throughput line chart over the recent window plus a
single headline throughput figure, so the operator can spot bursts and stalls at a glance
without reading logs.

**Why this priority**: This is the primary observable value of the commit. It directly
extends the existing flow view — the demo's key "watch it live" surface — with real-time
per-stage throughput. It is independently valuable even without the reset helper.

**Independent Test**: Start the stack, start the simulator, open `/flow.html`, and confirm
each of the four stage cards (Created, Screening, Merchant, Settlement) renders a line chart
and a throughput headline that changes as traffic flows.

**Acceptance Scenarios**:

1. **Given** the simulator is generating traffic, **When** the operator opens `/flow.html`,
   **Then** each stage card displays a throughput line chart and a headline throughput value
   labelled in tx/s.
2. **Given** traffic is flowing, **When** the operator watches a non-settlement stage,
   **Then** its headline shows the most recent one-second throughput and the chart's rightmost
   point reflects it.
3. **Given** traffic is flowing, **When** the operator watches the Settlement stage,
   **Then** its headline shows the average per-second throughput across the window (labelled
   "Settlement throughput") rather than only the latest second.
4. **Given** the merchant stage, **When** throughput is computed, **Then** only payments that
   actually reached the merchant are counted; **and** for settlement only authorized, captured,
   or refunded payments are counted.

---

### User Story 2 - Reset demo data between runs (Priority: P2)

An operator who has just finished a demo run wants to return the stack to a clean state so
the next run starts from zero. A single reset helper script stops the simulator, clears the
transient GridGain SQL tables, and optionally clears the MariaDB archive tables, so the demo
is repeatable without tearing down and rebuilding the whole stack.

**Why this priority**: Supporting tooling that makes the primary feature (and the demo as a
whole) repeatable. Valuable but secondary to the charts themselves.

**Independent Test**: With the stack running, execute `gridgain/clear-demo-data.sh` and
confirm the simulator stops and the GridGain `Payment`, `LedgerEntry`, and
`MerchantPaymentAttempt` tables are emptied; re-open the dashboard/flow view and confirm
counts reset to zero.

**Acceptance Scenarios**:

1. **Given** the stack is running, **When** the operator runs `clear-demo-data.sh`, **Then**
   the simulator is stopped via `POST /api/simulator/stop` before any data is deleted.
2. **Given** the required services are running, **When** the script runs, **Then** the
   transient GridGain tables (`MerchantPaymentAttempt`, `LedgerEntry`, `Payment`) are cleared.
3. **Given** `CLEAR_ARCHIVE=true`, **When** the script runs, **Then** the MariaDB archive
   tables (`LEDGER_ENTRY_ARCHIVE`, `PAYMENT_ARCHIVE`) are also cleared; otherwise they are
   left unchanged.
4. **Given** a required service is not running, **When** the script runs, **Then** it fails
   fast with a clear message and does not attempt to clear data.

---

### Edge Cases

- **No traffic / empty window**: When no payments fall in the throughput window, each chart
  renders a flat baseline and the headline shows 0 tx/s rather than erroring.
- **Single data point**: The chart still renders sensibly when the series has one usable
  point (no division-by-zero in the line geometry).
- **Simulator stragglers after stop**: Payment-generation tasks already queued when the
  operator stops the simulator must not create new payments after the stop, so a reset
  produces a genuinely clean state.
- **Reset with unavailable simulator endpoint**: If the simulator stop endpoint cannot be
  reached, the script reports this and continues with the data reset rather than aborting.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The transaction-flow snapshot MUST include, for each stage, a time series of
  per-second throughput points covering the recent window.
- **FR-002**: Each throughput point MUST identify its one-second bucket and the count of
  payments attributed to that bucket.
- **FR-003**: Throughput for the created and screening stages MUST count all payments in the
  window; the merchant stage MUST count only payments that reached the merchant; the
  settlement stage MUST count only authorized, captured, or refunded payments.
- **FR-004**: The flow view MUST render a per-stage throughput line chart (with a filled area)
  and a headline throughput value labelled in tx/s for every stage.
- **FR-005**: Non-settlement stages MUST display the latest one-second throughput as the
  headline; the settlement stage MUST display the window average and use the label
  "Settlement throughput".
- **FR-006**: The chart MUST render without error when the series is empty or has a single
  point, showing a flat baseline and a zero headline.
- **FR-007**: The simulator MUST NOT generate a payment once it has been stopped, including
  for work already queued at the moment of stopping.
- **FR-008**: A reset helper script MUST stop the simulator and clear the transient GridGain
  tables (`Payment`, `LedgerEntry`, `MerchantPaymentAttempt`) so the demo can be re-run from a
  clean state.
- **FR-009**: The reset helper MUST leave the external-database (MariaDB) archive tables
  unchanged by default and clear them only when explicitly opted in.
- **FR-010**: The reset helper MUST verify required services are running before deleting data
  and fail fast with a clear message otherwise.

### Key Entities *(include if feature involves data)*

- **TransactionFlowStep**: Existing per-stage flow record; now additionally carries a
  throughput series (list of throughput points) alongside its title, description, total, and
  stage states.
- **ThroughputPoint**: A single per-second sample — the one-second bucket (epoch second) and
  the count of payments in that bucket.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All four flow stages display a throughput chart and headline within one refresh
  of opening `/flow.html` while the simulator is running.
- **SC-002**: Each throughput chart covers a rolling 60-second window at one-second
  resolution.
- **SC-003**: With no traffic, every stage headline reads 0 tx/s and no chart rendering error
  occurs.
- **SC-004**: Running the reset helper leaves the transient GridGain tables empty, verifiable
  by the flow/dashboard counts returning to zero.
- **SC-005**: After the simulator is stopped, zero additional payments are created, so a reset
  immediately following a stop yields a fully clean state.

## Assumptions

- The reset helper is run against the Docker Compose reference stack, with service names and
  connection details overridable via environment variables (defaults target the compose
  services).
- The archive-clearing branch targets the external-database (MariaDB) archive tables and runs
  only when explicitly opted in via `CLEAR_ARCHIVE=true`.
- Throughput is derived from the same in-flight payment history already read by the flow
  service; no new data source is introduced.
- The flow view continues to be served as static assets by Spring Boot with no build step.
