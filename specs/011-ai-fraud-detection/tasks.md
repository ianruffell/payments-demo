---

description: "Task list for real-time AI fraud detection with customer context (forward-looking; not yet implemented)"
---

# Tasks: Real-Time AI Fraud Detection with Customer Context

**Input**: Design documents from `/specs/011-ai-fraud-detection/`

**Prerequisites**: plan.md (required), spec.md (required for user stories)

**Tests**: This project ships no automated test suite; validation is by exercising the pipeline and inspecting the dashboard, flow view, and the GridGain context cache. No test tasks are included.

**Status**: These tasks are proposed and NOT yet implemented — all boxes are unchecked.

**Organization**: Tasks are grouped by user story so each is independently demonstrable. Paths are relative to the repository root.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1–US5)

## Path Conventions

- Backend Java: `src/main/java/com/example/paymentsdemo/`
- Config: `src/main/resources/application.yml`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Add the context cache and configuration surface every story depends on.

- [ ] T001 Add `CUSTOMER_CONTEXT = "customer_context"` to `service/CacheNames.java`.
- [ ] T002 Define the `customer_context` cache (partitioned, backups per demo norm) in `config/CacheConfigurations.java`.
- [ ] T003 Add `demo.fraud.ai.{threshold, history-size, fail-policy, model}` to `src/main/resources/application.yml` with demo defaults (fail-policy: fail-open).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The context model, model interface, and services every story builds on.

**⚠️ CRITICAL**: No user story work can proceed until this phase is complete.

- [ ] T004 Add `domain/CustomerContext.java`: profile (risk tier, home currency/country, tenure, typical spend + variability), a bounded rolling history (amount, merchant, category, currency, outcome, timestamp), derived aggregates (avg/percentile spend, per-window velocity, distinct merchants, recent-decline/refund counts, last-seen).
- [ ] T005 Add `fraud/FraudFeatures.java`: the feature vector assembled from a `CustomerContext` + the current payment (amount-vs-typical, new-merchant/new-country flags, velocity, time-of-day, recent-decline signal, legacy `FraudService` score).
- [ ] T006 Add `fraud/FraudModel.java` (interface: `FraudDecision score(FraudFeatures)`) and `fraud/LocalFraudModel.java` (self-contained deterministic scorer producing a fraud score + verdict).
- [ ] T007 Add `service/CustomerContextService.java`: read/create/update the `customer_context` GridGain cache; enforce the bounded history; expose cold-start baseline. GridGain only — no external-DB access.

**Checkpoint**: Cache, config, context model, and pluggable model exist.

---

## Phase 3: User Story 1 - Block fraudulent payments before the merchant (Priority: P1) 🎯 MVP

**Goal**: Score at the pre-merchant gate and reject failing payments before dispatch.

**Independent Test**: Submit an anomalous payment; confirm DECLINED with a fraud reason, no merchant dispatch, and the flow "declined before merchant" count rises.

- [ ] T008 [US1] Add `service/FraudDetectionService.java`: read context (via `CustomerContextService`), build `FraudFeatures`, score via `FraudModel`, and return a decision vs `demo.fraud.ai.threshold`; apply the configured fail policy on error.
- [ ] T009 [US1] In `service/PaymentService.authorize()`, replace the stateless fraud check in the pre-merchant gate with `FraudDetectionService.decide(...)`: on reject set DECLINED + fraud decline reason (e.g. `AI_FRAUD_BLOCK`) and skip dispatch; on pass proceed to `PENDING_MERCHANT` unchanged.
- [ ] T010 [US1] Verify: drive anomalous vs normal payments and confirm reject-before-merchant vs normal dispatch, and that the transaction-flow view reflects the fraud declines.

**Checkpoint**: Fraudulent payments are blocked at screening.

---

## Phase 4: User Story 2 - Personalized scoring from per-customer context (Priority: P1)

**Goal**: The score reflects deviation from the specific customer's history.

**Independent Test**: Same payment scores differently for customers with different histories; a large foreign payment scores higher for a small-local-spend customer.

- [ ] T011 [US2] Ensure `FraudDetectionService`/`LocalFraudModel` derive key signals from the context (amount vs the customer's typical spend, new-merchant/new-country relative to history, velocity) so scores are personalized.
- [ ] T012 [US2] Implement cold-start: a first-seen customer is scored against a defined baseline profile and still decided without error.
- [ ] T013 [US2] Verify: construct two customers with contrasting histories and confirm an identical payment yields materially different scores.

**Checkpoint**: Scoring is personalized and cold-start-safe.

---

## Phase 5: User Story 3 - Seed initial context at startup, keep it current after each payment (Priority: P2)

**Goal**: Every customer starts with a baseline context in GridGain at startup, and that context stays current after every decision.

**Independent Test**: On a fresh start, confirm every seeded customer has a baseline context before any traffic; then submit several payments for one customer and confirm the context history/aggregates change after each (including declines) and stay bounded.

- [ ] T014 [US3] In `SeedDataLoader`, when accounts are seeded (empty stores), also seed an initial `CustomerContext` per account into the `customer_context` cache — baseline profile from the account (risk tier, home currency/country, tenure, starting typical-spend estimate) with empty history. Batch the writes as the account seed does; write to GridGain only.
- [ ] T015 [US3] In `PaymentService.authorize()` (after the outcome is set), call `CustomerContextService.update(...)` to append the payment to history and refresh aggregates + last-seen; bump the recent-decline signal on rejects.
- [ ] T016 [US3] Enforce the bounded history (evict oldest beyond `history-size`) and roll aggregates incrementally.
- [ ] T017 [US3] Verify: on a fresh start confirm seeded contexts exist before traffic; then inspect the `customer_context` cache across several payments for one customer and confirm currency + bounding + decline signal.

**Checkpoint**: Contexts are seeded at startup, then kept fresh and bounded.

---

## Phase 6: User Story 4 - Context is GridGain-only and rebuildable (Priority: P2)

**Goal**: Guarantee the context never touches the system of record and rebuilds if cleared.

**Independent Test**: Clear the context cache mid-run (no error, scoring continues, contexts rebuild); confirm the external DB has no context data.

- [ ] T018 [US4] Confirm no path writes/reads customer context to/from the external database: `SystemOfRecordRepository`/archive/CDC sink are untouched; `CustomerContextService` and the seed step use only GridGain.
- [ ] T019 [US4] Verify: empty the `customer_context` cache during traffic and confirm cold-start fallback + rebuild with no error; inspect MariaDB and confirm no customer-context tables/rows.

**Checkpoint**: Context is provably cache-only and self-healing.

---

## Phase 7: User Story 5 - Configurable threshold & observable decisions (Priority: P3)

**Goal**: Runtime-tunable threshold; fraud rejections visible in the existing views/metrics.

**Independent Test**: Lower the threshold and confirm the pre-merchant fraud-rejection rate rises in the dashboard/flow view.

- [ ] T020 [US5] Make the threshold runtime-adjustable (reuse the existing admin fraud-threshold control or add an equivalent for `demo.fraud.ai.threshold`).
- [ ] T021 [US5] Confirm fraud rejections surface as decline-before-merchant with the fraud reason in the dashboard and flow view (and in the spec-010 flow metrics if the observability layer is deployed).
- [ ] T022 [US5] Verify: adjust the threshold and observe the rejection rate change.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T023 [P] Update `README.md`: document the AI fraud gate, the customer-context cache (GridGain-only, seeded at startup), the `demo.fraud.ai.*` settings, and the fail policy.
- [ ] T024 Review hot-path impact: confirm scoring adds no synchronous external-database call and that authorize latency stays within the existing envelope; confirm per-customer context memory is bounded at scale, and that seeding contexts does not materially slow startup.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)** → **Foundational (Phase 2)** blocks all stories.
- **US1** (block at gate) is the MVP; **US2** (personalization) makes the score meaningful; both are P1 and share the scoring service.
- **US3** (seed initial context + update after each payment) depends on the context service; **US4** (GridGain-only/rebuild) validates the storage constraint; **US5** (threshold/observability) is polish.
- **Polish (Phase 8)** after the gate works.

### Parallel Opportunities

- T005 (features) and T006 (model interface + local model) are largely parallel once `CustomerContext` (T004) exists.
- Verification tasks (T010/T013/T017/T019/T022) are independent per story.

---

## Implementation Strategy

### MVP First (User Stories 1 + 2)

1. Phase 1 Setup (cache, config).
2. Phase 2 Foundational (context model, features, model, context service).
3. US1 + US2 → context-aware AI gate that blocks fraud before the merchant.
4. **STOP and VALIDATE** with anomalous vs normal payments.

### Incremental Delivery

1. Setup + Foundational → building blocks.
2. US1/US2 → the personalized fraud gate (MVP).
3. US3 → context kept current after each payment.
4. US4 → prove GridGain-only + rebuild.
5. US5 → tunable threshold + observability.
6. Polish → docs + hot-path/memory review.

### Notes

- Hook the existing pre-merchant decision point in `authorize()`; do not add a new pipeline stage.
- Context is GridGain-only by design (constitution Principle I noted exception); never persist it to the system of record or route it through CDC.
- Keep scoring on the hot path fast (local model, one GridGain read + one write, no external-DB call).
- Validation is manual, since the project ships no automated tests.
