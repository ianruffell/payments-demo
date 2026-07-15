# Implementation Plan: Real-Time AI Fraud Detection with Customer Context

**Branch**: `011-ai-fraud-detection` | **Date**: 2026-07-15 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/011-ai-fraud-detection/spec.md`

**Note**: Forward-looking plan for work not yet built (Status: Draft). It describes how to add a context-aware AI fraud gate to the existing authorize path without changing the durable data model.

## Summary

Insert an AI, per-customer fraud decision at the existing pre-merchant screening gate in `PaymentService.authorize()` — the point that today calls `FraudService.score()` / `validateAuthorization()` before setting `PENDING_MERCHANT` and dispatching. A new `CustomerContext` cache in GridGain holds each customer's profile and bounded rolling purchase history. On authorize, a `FraudDetectionService` reads the customer's context, assembles a feature vector from the context plus the current payment, scores it with a pluggable model (a self-contained local model for the demo), and returns a verdict against a runtime-configurable threshold. Failing payments are declined at screening with a fraud reason and never dispatched; passing payments proceed unchanged. At startup, when `SeedDataLoader` seeds accounts, it also seeds an initial `CustomerContext` into GridGain for every account (baseline profile derived from the account, empty history) so scoring is personalized from a customer's first payment. After every decision, `CustomerContextService` updates the customer's context in GridGain (history + aggregates + last-seen). The context is held **only** in GridGain — never persisted to the external system of record and never routed through CDC — and rebuilds from activity if the cache is cleared.

## Technical Context

**Language/Version**: Java 17 / Spring Boot (existing processor). GridGain 8 (Ignite API) for the context cache.

**Primary Dependencies**: Existing `PaymentService`, `FraudService`, `MerchantDispatchService`, GridGain caches (`CacheNames`). New: `FraudDetectionService`, `CustomerContextService`, a `customer_context` cache, and a pluggable `FraudModel` interface with a local implementation.

**Storage**: `customer_context` GridGain cache (cache-only, not durable). No change to the external system of record; no new MariaDB tables.

**Testing**: No automated test suite. Validation by exercising authorize traffic and inspecting the dashboard, transaction-flow view, and the `customer_context` cache.

**Target Platform**: The Spring Boot processor role (where `PaymentService` runs). Works identically under Docker Compose and the Kubernetes deployment.

**Performance Goals**: Scoring is synchronous on the authorize hot path, so it must be low-latency: an in-process local model, a single GridGain context read, and a single context write — no synchronous external-database call.

**Constraints**: Context reads/writes go to GridGain only. Rolling history is bounded. The model is pluggable behind an interface. The threshold is runtime-configurable. Model/context failure must not crash authorize (configurable fail policy).

**Scale/Scope**: One new cache, two new services, one model interface + local implementation, a decline reason, and configuration. Context sized to be viable across the seeded account population.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. External Database Is the System of Record** — PASS WITH NOTE. The customer context is deliberately **not** stored in the external system of record; it lives only in GridGain. This is an intentional, scoped exception: the context is *derived behavioral state*, not authoritative data — no money, payments, or reference records live there, and it rebuilds from activity if lost. Recorded in Complexity Tracking. All authoritative data (payments, ledger, reference) remains in MariaDB as before.
- **II. Change Data Capture, Not Dual Writes** — PASS. The context is neither reference data nor CDC-sourced; it is computed in-process and never dual-written. CDC is untouched.
- **III. Cache-First Hot Path, Asynchronous Archival** — PASS. The gate reads/writes the context in GridGain and scores in-process; no synchronous external-database call is added to authorize. Terminal-payment archival is unchanged.
- **IV. Pluggable Infrastructure Behind Configuration** — PASS. The model is behind an interface (local ↔ external inference), and the threshold + fail policy are configuration.
- **V. Observable, Demonstrable Behavior** — PASS. Fraud rejections surface as decline-before-merchant with a fraud reason in the dashboard/flow view and feed the observability metrics.
- **VI. Reproducible One-Command Local Stack** — PASS. Config-driven and self-seeding (contexts build from traffic); no new manual steps.

One noted exception to Principle I (context is cache-only by design).

## Project Structure

### Documentation (this feature)

```text
specs/011-ai-fraud-detection/
├── plan.md              # This file
├── spec.md              # Feature specification
└── tasks.md             # Dependency-ordered task list
```

### Source Code (repository root)

```text
src/main/
├── java/com/example/paymentsdemo/
│   ├── service/
│   │   ├── CacheNames.java                    # CHANGED: add CUSTOMER_CONTEXT = "customer_context"
│   │   ├── CustomerContextService.java        # NEW: read/update the GridGain context cache (cache-only)
│   │   ├── FraudDetectionService.java         # NEW: build features, score via FraudModel, decide vs threshold
│   │   ├── FraudService.java                  # REUSED: existing heuristic retained as one input feature
│   │   ├── SeedDataLoader.java                # CHANGED: seed an initial CustomerContext per account at startup
│   │   ├── PaymentService.java                # CHANGED: call FraudDetectionService at the pre-merchant gate;
│   │   │                                       #   update context after the decision
│   │   └── MerchantDispatchService.java       # UNCHANGED: only reached when the gate passes
│   ├── fraud/
│   │   ├── FraudModel.java                     # NEW: pluggable scoring interface
│   │   ├── LocalFraudModel.java                # NEW: self-contained deterministic demo model
│   │   └── FraudFeatures.java                  # NEW: feature vector assembled from context + payment
│   ├── service/FraudMonitorService.java        # NEW: fraud activity counters + recent-blocked ring buffer
│   ├── api/FraudController.java                 # NEW: GET /api/fraud/summary, GET /api/fraud/blocked
│   ├── dto/
│   │   ├── FraudSummaryResponse.java           # NEW: screened/blocked/suspicious/rate/threshold
│   │   └── BlockedPaymentView.java             # NEW: one blocked-payment row for the table
│   ├── domain/
│   │   └── CustomerContext.java               # NEW: profile + bounded rolling history + aggregates
│   └── config/
│       └── CacheConfigurations.java           # CHANGED: define the customer_context cache
└── resources/
    ├── application.yml                         # CHANGED: demo.fraud.ai.{threshold,history-size,fail-policy,model}
    └── static/
        ├── fraud.html                          # NEW: Fraud Detection page (summary + blocked table)
        ├── fraud.js                            # NEW: polls the fraud API and renders
        ├── index.html / flow.html / investigation.html  # CHANGED: nav link to the new page
        └── styles.css                          # CHANGED (if needed): minor table styling
```

**Structure Decision**: Additive to the existing processor. The gate hooks the current authorize decision point rather than adding a new stage; the model lives behind a `FraudModel` interface in a new `fraud` package; the context is a new GridGain cache and a service that owns it. No new modules, no schema changes.

## Key Technical Decisions (proposed)

- **Hook the existing gate, don't add a stage**: `authorize()` already decides dispatch-vs-decline before `PENDING_MERCHANT`. Replace the stateless `fraudService.isFraudulent(score)` check there with `fraudDetectionService.decide(payment, account, merchant, context)`. A reject sets DECLINED + a fraud decline reason (e.g. `AI_FRAUD_BLOCK`) and skips dispatch; a pass continues to `PENDING_MERCHANT` unchanged. This keeps the transaction-flow model (declined-before-merchant) intact.
- **Context cache in GridGain only**: `customer_context` cache keyed by account id, value `CustomerContext` (profile + bounded `ArrayDeque`-style history + derived aggregates). Configured in `CacheConfigurations`. Never referenced by the system-of-record repository, the archive service, or the CDC sink — enforcing "GridGain only."
- **Read-score-decide-update on the hot path**: one context read (GridGain), assemble `FraudFeatures` (amount vs typical, new-merchant/new-country, velocity, recent-decline signal, time-of-day, plus the legacy `FraudService` score as a feature), score via `FraudModel`, compare to threshold. After the payment outcome is set, write the updated context back (append history, refresh aggregates, bump recent-decline on rejects, set last-seen). All GridGain; no external-DB call.
- **Pluggable model**: `FraudModel` interface with `LocalFraudModel` (deterministic anomaly/deviation scoring so the demo is offline and repeatable). An external inference implementation can be dropped in behind the same interface and config without touching `PaymentService`.
- **Seed context at startup**: `SeedDataLoader` already loads accounts/merchants when the stores are empty; extend it to also write an initial `CustomerContext` per account into the `customer_context` cache (baseline profile from the account — risk tier, currency/country, tenure, a starting typical-spend estimate — with empty history). Seeding writes to GridGain only. This makes every customer's first simulated payment score with real context instead of cold-start.
- **Cold start & fail policy**: a customer with no context (added after seeding, or after a cache clear without restart) → baseline profile, decision proceeds, context created. Model/context error → configured fail policy (default fail-open: proceed to merchant review; fail-closed optional), never crashing authorize.
- **Bounded context**: history capped at `demo.fraud.ai.history-size`; aggregates recomputed/rolled incrementally so per-customer memory is predictable at scale.
- **Observability**: rejections reuse the decline-before-merchant path, so existing dashboard/flow panels and the spec-010 flow metrics reflect AI fraud blocks with no extra wiring.
- **Fraud dashboard page**: a `FraudMonitorService` keeps in-memory counters (screened/blocked/suspicious) and a bounded ring buffer of recent blocked payments (with the contributing signals, which are not persisted on `Payment`); `PaymentService` records each decision into it. `FraudController` exposes `GET /api/fraud/summary` and `GET /api/fraud/blocked`, and a static `fraud.html`/`fraud.js` page polls them. In-memory is acceptable for a live demo view (resets on restart); no schema change and nothing new persisted.

## Complexity Tracking

> Recorded because of the noted exception to Principle I.

| Decision | Why Needed | Simpler / Stricter Alternative Rejected Because |
|----------|------------|--------------------------------------------------|
| Customer context held only in GridGain, never in the system of record | The feature requires a low-latency, continuously-updated behavioral profile on the authorize hot path; persisting it to MariaDB would add write load and a durability contract it doesn't need | Storing context in the external DB would put non-authoritative, high-churn behavioral state into the system of record and add a synchronous or CDC write to the hot path, violating the cache-first principle for no benefit — the context is rebuildable from activity |
| Model behind a `FraudModel` interface with a local demo implementation | Keeps the demo offline/deterministic while allowing a real ML/LLM inference service later | Hard-coding a single scorer would block swapping in a real model and couple the pipeline to one implementation |
