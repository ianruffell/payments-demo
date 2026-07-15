# Feature Specification: Real-Time AI Fraud Detection with Customer Context

**Feature Branch**: `011-ai-fraud-detection`

**Created**: 2026-07-15

**Status**: Draft

**Input**: User description: "add a new spec which will provide realtime AI driven fraud detection in the current payment pipeline before it is sent to the merchant. Maintain a context for each customer which keeps a profile of the person and purchase history that can be used as input to the fraud detection process. If a payment doesn't pass a threshold for the fraud detection it should be rejected. The customer context should only be held in the GridGain database and should be updated after each payment to keep it current."

## User Scenarios & Testing *(mandatory)*

Today the pre-merchant screening stage applies a simple, stateless fraud score (amount, risk tier, a few merchant checks). This feature replaces that gate with a **real-time, AI-driven fraud decision that is personalized per customer**. Each customer has a **context** — a profile plus rolling purchase history — held **only in GridGain**. **Every customer is given an initial context when the demo starts**, seeded from their account so the model has meaningful input from the very first payment rather than starting cold. When a payment is authorized, the fraud model scores it against that customer's context *before* the payment is dispatched to the merchant; if it fails the fraud threshold it is rejected at screening and never reaches the merchant. After every payment the customer context is updated in GridGain so the next decision reflects the latest behavior. The context is deliberately cache-only: it is derived behavioral state, never written to the external system of record, and rebuilds itself from subsequent payments if the cache is cleared.

### User Story 1 - Block fraudulent payments before the merchant (Priority: P1)

As the payment processor, when a payment is authorized I score it with the AI fraud model at the screening stage. If the fraud score does not pass the configured threshold, the payment is rejected there and then — marked declined with a fraud reason — and is never dispatched to the merchant. Payments that pass proceed to merchant review exactly as before.

**Why this priority**: This is the core capability and the whole point of the feature — stopping fraud in real time before it leaves the processor. It is independently demonstrable: drive a clearly anomalous payment and confirm it is declined pre-merchant.

**Independent Test**: With the simulator running, submit a payment that is anomalous for its customer (e.g. an amount far above their norm, or a burst of rapid payments); confirm it is DECLINED with a fraud reason at screening, no merchant dispatch occurs, and the transaction-flow "declined before merchant" count rises.

**Acceptance Scenarios**:

1. **Given** a customer with an established context, **When** a payment scores at or beyond the fraud threshold, **Then** the payment is set to DECLINED with a fraud decline reason and is NOT dispatched to any merchant.
2. **Given** a customer with an established context, **When** a payment scores within the acceptable range, **Then** the payment proceeds to PENDING_MERCHANT and is dispatched, unchanged from today.
3. **Given** a rejected payment, **When** the dashboard/transaction-flow view is read, **Then** the rejection is reflected as a decline-before-merchant with the fraud reason.

---

### User Story 2 - Personalized scoring from a per-customer context (Priority: P1)

As the fraud model, I take the customer's context — their profile (risk tier, home currency/country, typical and variability of spend, account tenure) and rolling purchase history (recent amounts, merchants, categories, velocity, recent declines) — as input, alongside the current payment's features. This lets the decision reflect deviation from *that customer's* normal behavior rather than a one-size-fits-all rule.

**Why this priority**: Personalization is what makes the detection "AI-driven" and materially better than the existing stateless score; without the context the feature has no distinguishing value.

**Independent Test**: Establish a customer whose history is consistently small local purchases; submit a large foreign-currency payment for that customer and confirm it scores higher (and is more likely to be rejected) than the same payment would for a customer whose history already contains large foreign purchases.

**Acceptance Scenarios**:

1. **Given** a customer context exists, **When** a payment is scored, **Then** the model's input includes the customer's profile and purchase-history features drawn from that context.
2. **Given** two customers with different histories, **When** an identical payment is scored for each, **Then** the fraud scores differ according to how anomalous the payment is for each customer.
3. **Given** a brand-new customer with no context yet (cold start), **When** a payment is scored, **Then** the model uses a defined baseline and the payment is still decided without error.

---

### User Story 3 - Every customer starts with an initial context, kept current after each payment (Priority: P2)

As the processor, when the demo starts I seed an initial context in GridGain for every customer — a baseline profile derived from their account (risk tier, home currency/country, tenure, and a starting typical-spend estimate) plus empty/neutral history — so the fraud model has personalized input from a customer's very first payment. Thereafter, after each payment is decided I update that customer's context — appending to the rolling purchase history and refreshing the derived aggregates (typical spend, velocity counters, distinct merchants, recent-decline count, last-seen time) — so the next decision reflects the most recent activity.

**Why this priority**: Without seeded context, every customer's first payment would be a cold-start guess; seeding makes the personalization meaningful from the first transaction. Keeping the context current afterward prevents it from going stale. Both are essential but layered on the scoring itself.

**Independent Test**: On a fresh start, inspect the customer-context cache and confirm every seeded customer already has a baseline context before any traffic; then submit several payments for one customer and confirm the history and aggregates change after each (including declines) while staying bounded.

**Acceptance Scenarios**:

1. **Given** a fresh demo start with seeded accounts, **When** seeding completes, **Then** every seeded customer has an initial context in GridGain derived from their account, before any payment is processed.
2. **Given** a payment has been decided (approved-to-merchant or rejected), **When** the update runs, **Then** the customer's context in GridGain reflects the new payment (history entry + refreshed aggregates + last-seen timestamp).
3. **Given** the rolling history is at its configured size limit, **When** a new payment is added, **Then** the oldest history entry is evicted so the context stays bounded.
4. **Given** a payment was rejected for fraud, **When** the context updates, **Then** the recent-decline signal for that customer increases so subsequent scoring can react to it.

---

### User Story 4 - Context lives only in GridGain and is rebuildable (Priority: P2)

As an operator, I rely on the customer context being held exclusively in the GridGain cache — never written to the external system-of-record database. If the context cache is cleared or a fresh cluster starts empty, scoring continues (using cold-start baselines) and each customer's context rebuilds from their subsequent payments.

**Why this priority**: This is an explicit constraint of the feature and a deliberate architectural choice (context is derived behavioral state, not authoritative data); it must be verifiable.

**Independent Test**: Clear the customer-context cache while the demo runs; confirm no error, scoring continues, and contexts repopulate as new payments arrive; confirm the external database contains no customer-context tables or rows.

**Acceptance Scenarios**:

1. **Given** the demo is running, **When** the external database is inspected, **Then** it contains no customer-context data — the context exists only in GridGain.
2. **Given** the customer-context cache is emptied, **When** payments continue, **Then** scoring falls back to cold-start baselines and contexts rebuild from subsequent payments without error.
3. **Given** the archival/CDC paths, **When** they run, **Then** they do not read from or write the customer context (it is outside the system-of-record and CDC flows).

---

### User Story 5 - Configurable threshold and observable decisions (Priority: P3)

As an operator, I can configure the fraud threshold, and I can observe fraud decisions — how many payments are rejected for fraud and how that changes when I move the threshold — through the existing dashboard/flow views and metrics.

**Why this priority**: Operability and demonstrability; valuable but the feature functions with defaults.

**Independent Test**: Lower the fraud threshold and confirm the rate of pre-merchant fraud rejections rises in the dashboard/flow view (and in the observability metrics if deployed).

**Acceptance Scenarios**:

1. **Given** a configured fraud threshold, **When** the operator changes it, **Then** subsequent scoring uses the new threshold without a code change.
2. **Given** payments are flowing, **When** fraud rejections occur, **Then** they are visible as decline-before-merchant with a fraud reason in the dashboard and transaction-flow view.

---

### User Story 6 - Fraud activity dashboard page (Priority: P3)

As an analyst, I open a dedicated Fraud Detection page in the demo UI that summarizes fraud activity — how many payments have been screened, how many were blocked, the block rate, the current threshold — and shows a live table of the payments that were blocked as fraudulent, with the details that explain each block (customer, merchant, amount, fraud score, the contributing signals, and when it happened).

**Why this priority**: A purpose-built view makes the AI gate demonstrable at a glance; it is valuable for showing the feature but the gate itself works without it, so it is the lowest priority.

**Independent Test**: Open the Fraud Detection page while traffic flows (with the threshold low enough to produce blocks); confirm the summary counts update and the blocked-payments table lists the rejected payments with their scores and signals.

**Acceptance Scenarios**:

1. **Given** the demo UI, **When** the operator opens the Fraud Detection page, **Then** it shows a summary of fraud activity (payments screened, payments blocked, block rate, current threshold, suspicious count) and refreshes live.
2. **Given** payments have been blocked as fraudulent, **When** the operator views the page, **Then** a table lists the most recent blocked payments with customer, merchant, amount, fraud score, contributing signals, and timestamp.
3. **Given** no payments have been blocked yet, **When** the operator views the page, **Then** the summary reads zero and the table shows an empty state without error.
4. **Given** the other demo pages, **When** the operator uses the page navigation, **Then** the Fraud Detection page is reachable as a first-class page alongside Dashboard, Transaction Flow, and AI Investigation.

---

### Edge Cases

- **Cold start (no context)**: Seeded customers already have an initial context at startup. A customer with no context — e.g. an account added after seeding, or any customer after the context cache is cleared without a restart — is scored against a defined baseline profile and still decided; the context is created from that first payment.
- **Model or context unavailable**: If the scoring model errors or the context read fails, the gate follows a configured fail policy (default: fail-open — allow the payment to proceed to normal merchant review rather than block a legitimate customer — while flagging the event). Fail-closed is a configurable alternative.
- **Hot-path latency**: Scoring runs synchronously on the authorize path, so it must be low-latency (in-process/local model, bounded work) and must not add a synchronous external-database call.
- **High-velocity bursts**: Rapid repeated payments for one customer must be reflected in velocity signals promptly, even within the same short window.
- **Context cache eviction/expiry**: If GridGain evicts a context entry under memory pressure, the customer is treated as cold-start on next payment; no error.
- **Context size growth**: Rolling history must be bounded (fixed window/size) so per-customer context memory stays predictable across millions of accounts.
- **Refunds/captures**: Only the authorize decision is gated; later capture/refund stages are unchanged, though refunds may update the context as negative history.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST score every authorization with an AI fraud-detection model at the screening stage, before the payment is dispatched to a merchant.
- **FR-002**: The system MUST reject (mark DECLINED with a fraud decline reason) any payment whose fraud score fails the configured threshold, and MUST NOT dispatch a rejected payment to any merchant.
- **FR-003**: Payments that pass the threshold MUST continue to merchant review unchanged from current behavior.
- **FR-004**: The fraud model's input MUST include the customer's context — a profile (e.g. risk tier, home currency/country, typical spend and its variability, account tenure) and rolling purchase-history features (recent amounts, merchants/categories, velocity, recent declines).
- **FR-005**: The system MUST maintain a per-customer context and MUST hold it exclusively in the GridGain cache; it MUST NOT persist customer context to the external system-of-record database, nor route it through the CDC pipeline.
- **FR-006**: The system MUST update the customer's context in GridGain after every payment decision, refreshing the rolling history and derived aggregates (including a recent-decline signal for rejected payments) and a last-seen timestamp.
- **FR-007**: The rolling purchase history MUST be bounded to a configured size/window so per-customer context memory is predictable.
- **FR-008**: A first-seen customer (no context) MUST be scored using a defined cold-start baseline, and a context MUST be created from that first payment.
- **FR-015**: At demo startup, when customer/account data is seeded, the system MUST seed an initial context in GridGain for every seeded customer, derived from their account (risk tier, home currency/country, tenure, starting typical-spend estimate) with empty/neutral history, so scoring is personalized from a customer's first payment. Seeded context MUST be written to GridGain only, never to the external system of record.
- **FR-016**: The demo UI MUST provide a Fraud Detection page, reachable from the shared page navigation, that displays a live summary of fraud activity: payments screened, payments blocked, block rate, current threshold, and suspicious count.
- **FR-017**: The Fraud Detection page MUST show a live table of the most recent payments blocked as fraudulent, including customer, merchant, amount, fraud score, contributing signals, and timestamp, backed by an API endpoint; it MUST render an empty state cleanly when there are no blocked payments.
- **FR-009**: If the model or context is unavailable, the gate MUST apply a configured fail policy (default fail-open, with fail-closed configurable) and MUST NOT crash the authorize path.
- **FR-010**: Fraud scoring MUST run on the authorize hot path without adding a synchronous external-database call; context reads and writes MUST go to GridGain only.
- **FR-011**: The fraud threshold MUST be configurable at runtime without a code change.
- **FR-012**: Fraud rejections MUST be surfaced as decline-before-merchant with a fraud reason in the dashboard and transaction-flow views (and available to the observability metrics).
- **FR-013**: If the customer-context cache is cleared or starts empty, the system MUST continue scoring (cold-start) and rebuild contexts from subsequent payments without error.
- **FR-014**: The fraud model MUST be pluggable behind a stable interface so the demo's local model can be replaced with an external inference service without changing the pipeline.

### Key Entities *(include if feature involves data)*

- **CustomerContext (GridGain cache, e.g. `customer_context`, keyed by account/customer id)**: The cache-only behavioral record for a customer. Profile fields: risk tier, home currency/country, account tenure, typical spend and variability. Rolling history: bounded list of recent payments (amount, merchant, category, currency, outcome, timestamp). Derived aggregates: average/percentile spend, per-window velocity counts, distinct-merchant count, recent-decline and recent-refund counts, last-seen timestamp. **Seeded at startup** from the customer's account with a baseline profile and empty history, then updated after each payment. Never written to the external database.
- **Fraud feature vector**: The model input assembled per payment from the CustomerContext plus the current payment (amount vs typical, new-merchant/new-country flags, velocity, time-of-day, recent-decline signal, etc.).
- **Fraud decision**: The model output — a fraud score/probability plus a pass/reject verdict against the threshold and the decline reason used when rejected.
- **Fraud model (pluggable)**: The scorer behind a stable interface; the demo ships a self-contained local model, swappable for an external inference endpoint.
- **Fraud threshold + fail policy (config)**: Runtime-configurable threshold and the fail-open/fail-closed behavior for model/context unavailability.
- **Fraud activity summary + blocked-payment view**: The aggregate fraud counters (screened, blocked, suspicious, block rate, current threshold) and the recent-blocked-payment records (customer, merchant, amount, score, signals, timestamp) surfaced by the Fraud Detection page and its API.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A payment that is clearly anomalous for its customer is declined at screening with a fraud reason and is never dispatched to a merchant.
- **SC-002**: An identical payment produces different fraud scores for two customers with materially different histories.
- **SC-003**: After each payment, the affected customer's GridGain context reflects the new payment (history + aggregates + last-seen), and the history stays within its configured bound.
- **SC-004**: The external system-of-record database contains no customer-context data at any time.
- **SC-005**: Clearing the customer-context cache does not error; scoring continues and contexts rebuild from subsequent payments.
- **SC-006**: Lowering the fraud threshold measurably increases the pre-merchant fraud-rejection rate, visible in the dashboard/flow view.
- **SC-007**: Enabling the AI fraud gate adds no synchronous external-database call to the authorize path; authorize latency stays within the demo's existing envelope.
- **SC-008**: On a fresh start, every seeded customer has an initial context in GridGain before any traffic is processed, so a customer's first payment is scored with personalized (non-cold-start) input.
- **SC-009**: The Fraud Detection page loads from the demo UI, updates its summary live as traffic flows, and lists blocked payments with their fraud scores and signals; with no blocks it shows a clean empty state.

## Assumptions

- The customer/account identifier already present on a payment is the context key; "customer" maps to the existing account.
- Initial context is seeded alongside the existing account seed at startup (the same step that loads accounts/merchants), so "when the simulation starts" every account already has a baseline context. Seeding writes to the GridGain context cache only, derived from the seeded account data.
- The demo ships a self-contained, deterministic local fraud model (consistent with the project's existing local-model approach) so it runs offline; the interface allows swapping in a real ML/LLM inference service later.
- "Does not pass the threshold" means the payment is rejected when its fraud score crosses the configured risk threshold; the exact scale/direction is an implementation detail behind the threshold config.
- Default model/context-unavailable behavior is fail-open (do not block legitimate customers on infrastructure errors); operators can switch to fail-closed by configuration.
- The context is intentionally not durable: it is derived behavioral state that rebuilds from activity, so holding it only in GridGain is acceptable and by design.
- This feature supersedes the existing stateless screening fraud check at the pre-merchant gate; the current fraud score may be retained as one input feature.
- The project ships no automated test suite; behavior is validated by exercising the pipeline and inspecting the dashboard, flow view, and GridGain context cache.
