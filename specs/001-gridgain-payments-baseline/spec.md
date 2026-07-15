# Feature Specification: GridGain Payments Demo Baseline

**Feature Branch**: `001-gridgain-payments-baseline`

**Created**: 2026-04-17

**Status**: Delivered

**Input**: Reverse-engineered from commit 879e8d9 — "Add GridGain payments demo"

## User Scenarios & Testing *(mandatory)*

This baseline delivers a self-contained, real-time card-transaction demo: a payment
engine (authorize / capture / refund) with fraud scoring and double-entry ledger records
held in GridGain caches, a startup seed loader, a traffic simulator, a live operations
dashboard, and operator levers that take effect immediately. The stories below are ordered
so that each one, once built on the shared foundation, delivers independently observable
value.

### User Story 1 - Process a card payment through its lifecycle (Priority: P1)

A payments integrator submits a card authorization over REST, then captures it, and — where
needed — refunds it. Each authorization is validated against account and merchant rules,
scored for fraud, and either approved (holding funds and writing a ledger entry) or declined
with a reason. Captures and refunds move the payment through its remaining states and record
matching ledger entries.

**Why this priority**: This is the core engine. Without it there is no payment to observe,
simulate, or administer; every other story reads or drives the data it produces.

**Independent Test**: Start the app (which seeds accounts and merchants), then POST an
authorize request for a seeded account and merchant, then POST capture and refund for the
returned payment id. The API responses and ledger entries confirm the full lifecycle without
any dashboard, simulator, or admin control.

**Acceptance Scenarios**:

1. **Given** an active seeded account with sufficient balance and an active merchant, **When** a client POSTs a valid authorize request in the account's currency and under the merchant limits, **Then** the payment is stored as `AUTHORIZED`, the account's available balance is reduced by the amount, and a `DEBIT` `AUTH_HOLD` ledger entry is written.
2. **Given** an authorize request whose amount exceeds the account's available balance, **When** it is submitted, **Then** the payment is stored as `DECLINED` with reason `INSUFFICIENT_FUNDS`, no funds are held, and no hold ledger entry is written.
3. **Given** an authorize request targeting an inactive merchant, a suspended account, a mismatched currency, an amount over the merchant per-transaction max, or a merchant that has reached its daily limit, **When** it is submitted, **Then** the payment is `DECLINED` with the corresponding reason (`MERCHANT_INACTIVE`, `ACCOUNT_NOT_ACTIVE`, `CURRENCY_MISMATCH`, `MERCHANT_MAX_AMOUNT_EXCEEDED`, `MERCHANT_DAILY_LIMIT_EXCEEDED`).
4. **Given** an authorize request whose computed fraud score is at or above the current fraud threshold, **When** it is submitted, **Then** the payment is `DECLINED` with reason `FRAUD_SCORE_EXCEEDED`.
5. **Given** an `AUTHORIZED` payment, **When** a client POSTs capture, **Then** the payment becomes `CAPTURED`, a capture timestamp is recorded, and a `DEBIT` `CAPTURE` ledger entry is written.
6. **Given** a `CAPTURED` payment, **When** a client POSTs refund, **Then** the payment becomes `REFUNDED`, the account balance is credited back by the amount, and a `CREDIT` `REFUND` ledger entry is written.
7. **Given** a payment that is not in the required state, **When** capture is attempted on a non-authorized payment or refund on a non-captured payment, **Then** the request is rejected with a conflict and the payment state is unchanged.
8. **Given** a payment id that has already been authorized, **When** the same id is authorized again, **Then** the existing payment is returned and flagged as a duplicate rather than creating a second payment.

---

### User Story 2 - Watch live payment activity on a dashboard (Priority: P2)

An operator opens the browser dashboard and watches real-time throughput, approval rate,
status mix, declines by reason, top merchants by volume, and recent suspicious payments,
all refreshed continuously from a single snapshot endpoint.

**Why this priority**: The demo exists to be watched; making the engine's behavior visible
is the primary value once payments can be processed. It depends only on payment data being
present.

**Independent Test**: With payments present (from manual calls or the simulator), open the
dashboard at the app root and confirm the metrics and tables populate and refresh roughly
every second; equivalently, GET the dashboard snapshot endpoint and verify it returns the
aggregated figures.

**Acceptance Scenarios**:

1. **Given** payments created within the last five minutes, **When** the dashboard snapshot is requested, **Then** it returns throughput for the last minute, approval rate over five minutes, a status-count mix, declines grouped by reason (most frequent first), the top five merchants by transaction count, up to ten recent suspicious payments (newest first), a 60-second throughput series, current simulator status, and the current fraud threshold.
2. **Given** the dashboard page is open, **When** time passes, **Then** the metrics, throughput bars, status pills, and tables update on a recurring interval without a manual page reload.
3. **Given** no payments exist in the window, **When** the dashboard renders, **Then** it shows zeroed metrics and empty-state rows rather than errors.

---

### User Story 3 - Generate realistic traffic with the simulator (Priority: P3)

A demonstrator starts a built-in simulator that continuously generates authorize traffic at
a chosen rate, automatically captures most approved payments, and occasionally refunds
captured ones, producing a realistic and observable stream of activity, then stops it on
demand.

**Why this priority**: It makes the demo self-driving so the dashboard has live movement
without manual API calls, but it is not required for the engine or dashboard to function.

**Independent Test**: POST to start the simulator at a given rate, observe generated-payment
counts and dashboard throughput climb, then POST stop and confirm generation halts while the
running flag and counters reflect the change.

**Acceptance Scenarios**:

1. **Given** the simulator is stopped, **When** a client starts it with a rate per second, **Then** it begins submitting that many authorize requests per second and the reported status shows running with that rate.
2. **Given** the simulator is running, **When** it produces an approved authorization, **Then** most approved payments are captured after a short delay and a small fraction of captured payments are later refunded.
3. **Given** a configured target decline rate, **When** the simulator runs, **Then** a portion of generated payments deliberately trigger declines (currency mismatch, insufficient funds, suspended account, or over-limit) so declines appear in the metrics.
4. **Given** the simulator is running, **When** a client stops it, **Then** new payment generation ceases and the reported status shows not running.

---

### User Story 4 - Steer the demo with operator controls (Priority: P4)

An operator uses dashboard controls to trigger a merchant outage (deactivate/reactivate a
merchant) and to raise or lower the fraud threshold, and sees the effect on the metrics
immediately.

**Why this priority**: These levers make the demo interactive and tell the observability
story, but the system runs and is observable without them.

**Independent Test**: Deactivate a merchant and confirm subsequent authorizations to it are
declined as `MERCHANT_INACTIVE`; lower the fraud threshold and confirm the decline rate and
`FRAUD_SCORE_EXCEEDED` count rise on the next dashboard refresh; then reverse both.

**Acceptance Scenarios**:

1. **Given** an active merchant receiving traffic, **When** an operator deactivates it, **Then** new authorizations to that merchant are declined with `MERCHANT_INACTIVE` and the change is visible on the next dashboard refresh.
2. **Given** a deactivated merchant, **When** an operator reactivates it, **Then** authorizations to it can succeed again.
3. **Given** a running stream of payments, **When** an operator lowers the fraud threshold, **Then** more payments are declined for `FRAUD_SCORE_EXCEEDED` and flagged suspicious, and the dashboard's displayed threshold updates immediately.
4. **Given** an unknown merchant id, **When** an operator tries to change its status, **Then** the request is rejected as not found.

---

### Edge Cases

- Duplicate authorize with an already-used payment id returns the existing payment marked as duplicate instead of double-charging.
- Capture on a payment that is not `AUTHORIZED`, or refund on a payment that is not `CAPTURED`, is rejected as a conflict.
- Authorize referencing an unknown account id or merchant id is rejected as not found.
- A declined authorization holds no funds and writes no ledger entry, so balances are untouched.
- Merchant daily-limit checks only count non-declined payments created since the start of the current UTC day.
- The simulator's capture/refund follow-ups are best-effort: state races (for example a payment already moved on) are swallowed so simulated traffic never halts.
- With no payments in the five-minute window, the dashboard reports zeroed metrics and empty-state table rows.
- On a fresh start the seed loader runs only when both the accounts and merchants caches are empty, so restarts do not duplicate seed data.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST expose a REST endpoint to authorize a payment from a payment id, account id, merchant id, amount in minor units, and currency, rejecting requests with a blank id/account/merchant/currency or a non-positive amount.
- **FR-002**: System MUST validate each authorization against account status (must be active), merchant status (must be active), currency match between account and request, merchant per-transaction maximum, merchant daily limit, and available account balance, and MUST decline with a specific reason code when a check fails.
- **FR-003**: System MUST compute a fraud score for each authorization from the amount, the account's risk tier, and the merchant's category and country (with a random component), and MUST decline with `FRAUD_SCORE_EXCEEDED` when the score is at or above the current fraud threshold.
- **FR-004**: System MUST flag a payment as suspicious when its fraud score is at or above a suspicion band derived from the threshold, independently of whether the payment was approved or declined.
- **FR-005**: On approval, System MUST store the payment as `AUTHORIZED`, reduce the account's available balance by the amount, and write a `DEBIT` ledger entry of type `AUTH_HOLD`.
- **FR-006**: System MUST expose a REST endpoint to capture an `AUTHORIZED` payment, moving it to `CAPTURED`, recording a capture time, and writing a `DEBIT` `CAPTURE` ledger entry; capture MUST be rejected for any other state.
- **FR-007**: System MUST expose a REST endpoint to refund a `CAPTURED` payment, moving it to `REFUNDED`, crediting the account balance back by the amount, recording a refund time, and writing a `CREDIT` `REFUND` ledger entry; refund MUST be rejected for any other state.
- **FR-008**: System MUST treat authorize as idempotent on payment id: a repeat of an existing id returns the existing payment marked as a duplicate without creating a second payment or re-applying balance changes.
- **FR-009**: System MUST apply each authorize, capture, and refund as an atomic transaction across the payments, accounts, and ledger stores so balances and ledger entries stay consistent.
- **FR-010**: System MUST seed reference data on startup when the stores are empty — a large population of accounts (with names, balances, currencies, statuses, and risk tiers) and a population of merchants (with names, categories, countries, active flags, per-transaction maximums, and daily limits) — and MUST skip seeding when data already exists.
- **FR-011**: System MUST provide a simulator that, when started at a given rate per second, continuously generates authorize traffic, automatically captures most approved payments after a short delay, and occasionally refunds captured payments, targeting a configurable decline rate.
- **FR-012**: System MUST expose REST endpoints to start the simulator (with a rate parameter), stop it, and report its status (running flag, current rate, count of generated payments).
- **FR-013**: System MUST expose a dashboard snapshot endpoint returning last-minute throughput, five-minute approval rate, status-count mix, declines grouped by reason, top merchants by volume, recent suspicious payments, a per-second throughput series for the last 60 seconds, simulator status, and the current fraud threshold.
- **FR-014**: System MUST serve a static browser dashboard that renders the snapshot and refreshes it on a recurring interval, and MUST expose operator controls for starting/stopping the simulator, applying a fraud threshold, and deactivating/reactivating a merchant.
- **FR-015**: System MUST expose a REST endpoint to set a merchant's active flag, rejecting unknown merchant ids, and a REST endpoint to set the fraud threshold, both taking effect immediately for subsequent authorizations.
- **FR-016**: System MUST hold all live payment state in GridGain caches (accounts, merchants, payments, ledger entries) and MUST support SQL-style aggregate queries over payments for the dashboard metrics.

### Key Entities *(include if feature involves data)*

- **Account**: A customer funding source. Attributes: account id (indexed), customer name, available balance in minor units, currency, status (`ACTIVE`, `SUSPENDED`, `CLOSED`), risk tier (`LOW`, `MEDIUM`, `HIGH`). Balance decreases on authorization hold and increases on refund.
- **Merchant**: A payee. Attributes: merchant id (indexed), name, category (indexed), country (indexed), active flag, per-transaction maximum in minor units, daily limit in minor units. Certain categories and countries raise fraud scores; the active flag is the operator outage lever.
- **Payment**: A card transaction through its lifecycle. Attributes: payment id (indexed), account id, merchant id, amount in minor units, currency, status (`AUTHORIZED`, `CAPTURED`, `DECLINED`, `REFUNDED`), created/updated/captured/refunded timestamps, decline reason (indexed, when declined), fraud score, suspicious flag (indexed).
- **LedgerEntry**: An immutable accounting record for a payment event. Attributes: entry id, payment id, account id, merchant id, direction (`DEBIT`, `CREDIT`), amount in minor units, currency, entry type (`AUTH_HOLD`, `CAPTURE`, `REFUND`), created timestamp. Entries are written but never mutated.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A fresh start followed by one valid authorize call returns an `AUTHORIZED` payment and a corresponding hold ledger entry, with the account balance reduced by exactly the authorized amount.
- **SC-002**: Each of the six decline reasons can be produced on demand by a correspondingly crafted authorize request, and each declined payment leaves account balances unchanged.
- **SC-003**: A payment can be driven authorize → capture → refund, ending in `REFUNDED` with the account balance restored to its pre-authorization value and exactly three ledger entries (hold, capture, refund).
- **SC-004**: With the simulator running at the default rate, the dashboard shows non-zero throughput and approval rate and refreshes at roughly one-second intervals.
- **SC-005**: Deactivating a merchant causes its subsequent authorizations to be declined as `MERCHANT_INACTIVE`, observable on the dashboard within one refresh cycle; reactivating restores approvals.
- **SC-006**: Lowering the fraud threshold measurably increases the count of `FRAUD_SCORE_EXCEEDED` declines and suspicious payments on the next dashboard refresh, with no code change or restart.
- **SC-007**: Restarting the application with populated caches does not re-run seeding or duplicate accounts or merchants.

## Assumptions

- This baseline runs GridGain in embedded mode with a single local discovery address and in-memory storage (persistence disabled); the external system-of-record database and the Debezium CDC pipeline described in the project's longer-term vision are out of scope for this commit.
- Live payment, account, merchant, and ledger state lives entirely in GridGain caches at this stage; there is no external archival of terminal payments yet.
- The dashboard is a single page (`index.html`); the transaction-flow and AI-investigation views referenced elsewhere in the project are not part of this commit.
- Amounts are integers in minor currency units (for example pennies); the dashboard formats them as GBP for display.
- No authentication is applied to the REST endpoints or dashboard; the demo is intended for local, trusted use.
- The project ships no automated test suite; behavior is validated by exercising endpoints and the dashboard directly.
- Default seed sizes and tuning (account/merchant counts, simulator rate, target decline rate, fraud threshold) are supplied by configuration and can be overridden without code changes.
