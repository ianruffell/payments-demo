# Feature Specification: Containerized Asynchronous Merchant Processing

**Feature Branch**: `002-containerized-async-merchant`

**Created**: 2026-05-07

**Status**: Delivered

**Input**: Reverse-engineered from commit 90165ee — "containerize async merchant processing demo"

## User Scenarios & Testing *(mandatory)*

This increment changed the payment authorization model from a single-node embedded cache
that decided authorizations synchronously into a multi-container stack in which the
processor dispatches each authorization to an external merchant service and waits for an
asynchronous result. The prior baseline authorized or declined a payment inline during the
`POST /api/payments/authorize` call and ran GridGain embedded inside the application process.
The scenarios below describe only what this commit introduced on top of that baseline.

### User Story 1 - Authorize a payment through an asynchronous merchant (Priority: P1)

A payment is submitted for authorization. Instead of being decided immediately, the
processor accepts it, records it as awaiting a merchant decision, and dispatches the
authorization request to the target merchant's service. The merchant service processes the
request on its own schedule and later calls back with an approve/decline result, at which
point funds are held (on approval) or the payment is declined with a reason.

**Why this priority**: This is the headline behavior change of the increment. Without it the
demo cannot show the request/callback lifecycle, and every other story in this feature exists
to support or make this behavior observable and reproducible.

**Independent Test**: With the stack running, `POST /api/payments/authorize` with a valid
account, merchant, currency, and amount. The response is `202 Accepted` with status
`PENDING_MERCHANT`. Within a few seconds the payment transitions to `AUTHORIZED` (funds held,
ledger `AUTH_HOLD` entry created) or `DECLINED` (with a reason), visible via the dashboard.

**Acceptance Scenarios**:

1. **Given** an active account and an active merchant with a service URL, **When** a valid
   authorization is submitted, **Then** the response is `202 Accepted`, the payment status is
   `PENDING_MERCHANT`, a merchant attempt is recorded as `PENDING`, and no funds are held yet.
2. **Given** a payment awaiting a merchant decision, **When** the merchant service calls back
   approving it and re-validation passes, **Then** the payment becomes `AUTHORIZED`, the
   account's available balance is reduced by the amount, an `AUTH_HOLD` ledger debit is
   written, and the attempt is marked `APPROVED`.
3. **Given** a payment awaiting a merchant decision, **When** the merchant service calls back
   declining it, **Then** the payment becomes `DECLINED` with the merchant's reason, the
   attempt is marked `DECLINED`, and no funds are held.
4. **Given** an authorization request that fails local validation (inactive merchant, currency
   mismatch, fraud threshold, or a merchant with no service URL), **When** it is submitted,
   **Then** the payment is `DECLINED` immediately with `201 Created` and is never dispatched.
5. **Given** a merchant approval callback, **When** re-validation at approval time fails
   (account no longer active, merchant max amount or daily limit exceeded, or insufficient
   funds), **Then** the payment is `DECLINED` with the corresponding reason even though the
   merchant approved it.

---

### User Story 2 - Handle merchant timeouts and late responses (Priority: P2)

Some merchant services respond slowly or not at all. The processor must not leave a payment
awaiting a decision forever. A background monitor times out payments whose merchant deadline
has passed, and any merchant response that arrives after the deadline is recorded without
changing the already-settled outcome.

**Why this priority**: Timeouts are the primary resilience behavior of the async model and a
key thing the demo shows. It builds directly on P1 but is independently observable by watching
slow or unreachable merchants resolve to `TIMED_OUT`.

**Independent Test**: Submit an authorization to a merchant configured with a high timeout
probability (or stop that merchant's container). After the merchant timeout window (~10s) the
payment status becomes `TIMED_OUT` with reason `MERCHANT_TIMEOUT` without any manual action.

**Acceptance Scenarios**:

1. **Given** a payment `PENDING_MERCHANT` whose deadline has passed with no response, **When**
   the timeout monitor next runs, **Then** the payment becomes `TIMED_OUT` with reason
   `MERCHANT_TIMEOUT` and the attempt is marked `TIMED_OUT`.
2. **Given** a payment already marked `TIMED_OUT`, **When** the merchant response finally
   arrives, **Then** the payment outcome is unchanged and the attempt is re-labeled
   `LATE_RESPONSE`.
3. **Given** a payment that has already reached a terminal outcome, **When** a duplicate or
   stale merchant result is received, **Then** it is ignored and the payment is unchanged.

---

### User Story 3 - Bring up the whole stack with one command (Priority: P3)

An operator runs a single Docker Compose command and gets the entire demo: a three-node
external GridGain cluster, the Spring Boot processor connected as a thin/client node, and a
set of merchant simulator containers that receive dispatched authorizations and call back with
results. Reference data is seeded automatically when the caches are empty.

**Why this priority**: Reproducibility is required for anyone to run P1/P2 at all, but the
behavior it enables (async authorization) is what delivers the value, so it sits below them.

**Independent Test**: From a clean checkout, `docker compose up --build` brings up the GridGain
cluster, the processor on `http://localhost:8080`, and the merchant simulator containers; the
dashboard loads and seeded accounts and merchants are present.

**Acceptance Scenarios**:

1. **Given** a clean checkout, **When** the compose stack is started, **Then** a three-node
   GridGain cluster, the processor, and the merchant simulator containers all start and the
   processor connects to the cluster as a client and creates its caches.
2. **Given** empty caches, **When** the processor starts with seeding enabled, **Then** it
   loads the configured accounts and merchants, assigning each merchant a service URL that
   points at its simulator container.
3. **Given** the `merchant-simulator` Spring profile, **When** a container starts, **Then** it
   exposes only the merchant endpoint and does not run the processor, dashboard, seeding, or
   GridGain client.

---

### User Story 4 - Ingest an external source of record via CDC (Priority: P4)

Optionally, an operator treats MySQL as the external system of record: a MySQL schema mirrors
the demo's record types, a Debezium source connector streams row changes into Kafka, and a
standalone Kafka-to-GridGain sink application projects those changes into the cache. This lets
the demo show CDC as the ingestion path rather than application seeding.

**Why this priority**: It is an optional, off-compose path that layers onto the same cache and
does not affect the core async authorization demo, so it is the lowest priority slice.

**Independent Test**: Start the CDC stack (Kafka, MySQL, Kafka Connect), load the schema,
register the Debezium source connector, and run the sink app; inserting an `accounts` or
`merchants` row in MySQL results in the corresponding entry appearing in the GridGain cache.

**Acceptance Scenarios**:

1. **Given** the CDC stack is running and the source connector is registered, **When** a row
   is inserted or updated in a source table, **Then** the sink application upserts the matching
   entry into the corresponding GridGain cache.
2. **Given** a delete event on a source table, **When** the sink consumes it, **Then** the
   matching entry is removed from the cache.
3. **Given** the CDC path is used as the source of record, **When** seeding is disabled, **Then**
   the processor does not create overlapping reference data.

### Edge Cases

- **Merchant unreachable / container down**: dispatch is fire-and-forget; a failed dispatch is
  logged and the payment stays `PENDING_MERCHANT` until the timeout monitor marks it
  `TIMED_OUT`.
- **Merchant with no service URL**: authorization is declined immediately with
  `MERCHANT_UNAVAILABLE` and never dispatched.
- **Merchant approves but state changed**: re-validation at approval time can still decline for
  `ACCOUNT_NOT_ACTIVE`, `MERCHANT_INACTIVE`, `MERCHANT_MAX_AMOUNT_EXCEEDED`,
  `MERCHANT_DAILY_LIMIT_EXCEEDED`, or `INSUFFICIENT_FUNDS`.
- **Late response after timeout**: recorded as `LATE_RESPONSE`; the timed-out outcome stands.
- **Duplicate / mismatched callback**: results for already-settled payments are ignored; a
  result whose merchant ID does not match the recorded attempt is rejected as a bad request.
- **Wrong-merchant dispatch**: a simulator rejects a request whose merchant ID does not match
  its own configured identity.
- **Stale/unreadable seed data**: if existing cache data is not readable by this version (for
  example missing the new merchant service URL), the demo caches are reset and reloaded.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The processor MUST accept a valid authorization request without deciding it
  inline, persist the payment as `PENDING_MERCHANT`, and return `202 Accepted`.
- **FR-002**: The processor MUST record a merchant payment attempt for each dispatched payment,
  capturing the target merchant URL, callback URL, request time, and a deadline.
- **FR-003**: The processor MUST dispatch the authorization asynchronously to the merchant's
  service URL and MUST NOT block the authorization response on the merchant call.
- **FR-004**: The processor MUST expose a callback endpoint that accepts an asynchronous
  merchant authorization result and settles the corresponding payment.
- **FR-005**: On a merchant approval that still passes re-validation, the processor MUST set the
  payment to `AUTHORIZED`, reduce the account's available balance by the amount, and write an
  `AUTH_HOLD` ledger debit.
- **FR-006**: On a merchant decline, the processor MUST set the payment to `DECLINED` with the
  merchant-supplied reason (falling back to `MERCHANT_DECLINED`).
- **FR-007**: The processor MUST re-validate account status, merchant status, merchant max
  amount, merchant daily limit, and available funds at approval time, and decline if any check
  fails.
- **FR-008**: The processor MUST decline immediately (without dispatch) when local validation
  fails, including declining with `MERCHANT_UNAVAILABLE` when the merchant has no service URL.
- **FR-009**: A background monitor MUST periodically mark payments still `PENDING_MERCHANT`
  past their deadline as `TIMED_OUT` with reason `MERCHANT_TIMEOUT`.
- **FR-010**: A merchant result arriving after the deadline MUST NOT override the settled
  outcome; its attempt MUST be recorded as `LATE_RESPONSE`.
- **FR-011**: The processor MUST ignore merchant results for payments that are no longer
  awaiting a decision, and MUST reject results whose merchant ID does not match the attempt.
- **FR-012**: A merchant simulator MUST accept an authorization request, respond immediately
  that it was accepted for async processing, and later POST an approve/decline result to the
  callback URL after a simulated delay.
- **FR-013**: A merchant simulator MUST decline amounts above its configured threshold, apply a
  configurable approval rate, and occasionally simulate a timeout by delaying beyond the
  processor's deadline, all driven by per-merchant configuration.
- **FR-014**: A merchant simulator MUST reject a request whose merchant ID does not match its
  own configured identity.
- **FR-015**: The processor MUST run as a GridGain client connecting to an external multi-node
  cluster (no embedded server node, no local persistence storage config), creating its caches
  on connect.
- **FR-016**: The cache set MUST include a merchant payment attempt cache in addition to the
  existing accounts, merchants, payments, and ledger caches.
- **FR-017**: Seeding MUST be toggleable, seed only when caches are empty, assign each seeded
  merchant a service URL derived from a configurable pattern, and reset-and-reload caches whose
  existing data is not readable by this version.
- **FR-018**: The dashboard MUST exclude `PENDING_MERCHANT` payments from suspicious results and
  MUST rank top merchants by amount then count.
- **FR-019**: The entire runtime stack (GridGain cluster, processor, merchant simulators, and
  optional Control Center) MUST come up from a single Docker Compose command.
- **FR-020**: A merchant simulator container MUST run under a dedicated Spring profile that
  disables the processor, dashboard, seeding, timeout monitor, and GridGain client.
- **FR-021**: The project MUST provide a MySQL source schema, a Debezium source connector
  configuration, and scripts to stand up Kafka, MySQL, and Kafka Connect off-compose.
- **FR-022**: A standalone Kafka-to-GridGain sink application MUST consume the source topics and
  upsert or delete the matching cache entries, connecting to the cluster as a client.
- **FR-023**: Off-compose helper scripts MUST be provided to start/stop the GridGain cluster and
  to run the CDC sink, applying the required GridGain JVM `--add-opens` flags.

### Key Entities *(include if feature involves data)*

- **MerchantPaymentAttempt**: New cached record tracking one dispatch of a payment to a
  merchant. Keyed by payment ID; holds merchant ID, request status, merchant URL, callback URL,
  requested-at, deadline, responded-at, merchant reference, and a human-readable message. Lives
  in the new `merchant_payment_attempts` cache.
- **MerchantRequestStatus**: New enumeration of an attempt's lifecycle: `PENDING`, `APPROVED`,
  `DECLINED`, `TIMED_OUT`, `LATE_RESPONSE`, `DISPATCH_FAILED`.
- **MerchantAuthorizationRequest**: New request payload sent to the merchant service — payment
  ID, account ID, merchant ID, amount, currency, created-at, deadline, and callback URL.
- **MerchantAuthorizationResult**: New callback payload from the merchant — payment ID, merchant
  ID, approved flag, reason, merchant reference, and responded-at.
- **PaymentStatus (changed)**: Adds `PENDING_MERCHANT` (awaiting merchant decision) and
  `TIMED_OUT` (deadline passed) to the existing `AUTHORIZED`, `CAPTURED`, `DECLINED`,
  `REFUNDED`.
- **Merchant (changed)**: Adds a `serviceUrl` used to dispatch authorizations; seeded from a
  configurable per-merchant URL pattern.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A fresh checkout reaches a running dashboard and seeded reference data with a
  single Docker Compose command, with no manual GridGain, database, or per-service steps.
- **SC-002**: `POST /api/payments/authorize` returns `202 Accepted` with status
  `PENDING_MERCHANT` and does not block on the merchant, returning within normal request
  latency rather than the merchant's processing delay.
- **SC-003**: Every dispatched payment reaches exactly one terminal outcome — `AUTHORIZED`,
  `DECLINED`, or `TIMED_OUT` — with no payment left `PENDING_MERCHANT` beyond the timeout
  window.
- **SC-004**: A merchant that never responds resolves to `TIMED_OUT` within the configured
  timeout window (~10 seconds) automatically, without operator action.
- **SC-005**: A merchant response arriving after its payment has timed out never changes the
  payment outcome and is recorded as a late response.
- **SC-006**: Funds are held (balance reduced, `AUTH_HOLD` ledger entry) only for payments that
  are both merchant-approved and pass approval-time re-validation.
- **SC-007**: With the optional CDC path, a row change in the MySQL source appears as the
  matching cache entry via the sink, and enabling it lets the operator disable seeding to avoid
  overlapping data.

## Assumptions

- The demo operator runs Docker Compose locally and has access to the GridGain images and, for
  the CDC path, the Debezium images; a GridGain license file is present at the repository root.
- The `merchant-simulator` Spring profile identifies a simulator container; the default
  (no simulator profile) identifies the processor and CDC sink roles.
- Each seeded merchant maps to exactly one simulator container reachable at a URL derived from
  the configured pattern (for example `http://merchant-00001:8080/api/merchant/payments`).
- The default merchant timeout is 10 seconds and merchant simulators occasionally exceed it on
  purpose to demonstrate timeout handling.
- The MySQL + Debezium CDC path is an optional, off-compose ingestion path; the compose stack
  itself runs the GridGain cluster, processor, and merchant simulators.
- The project ships no automated test suite; behavior is validated by exercising the HTTP
  endpoints and watching the dashboard, consistent with the project constitution.
