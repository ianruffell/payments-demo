# Implementation Plan: GridGain Payments Demo Baseline

**Branch**: `001-gridgain-payments-baseline` | **Date**: 2026-04-17 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-gridgain-payments-baseline/spec.md`

**Note**: This plan is reverse-engineered from commit 879e8d9 — "Add GridGain payments
demo" — and describes the technical approach actually taken in that commit.

## Summary

Deliver a self-contained real-time payments demo as a single Spring Boot service with an
embedded GridGain node. A `PaymentService` runs the authorize / capture / refund hot path as
GridGain transactions over four caches (`accounts`, `merchants`, `payments`,
`ledger_entries`), with a `FraudService` scoring each authorization and a runtime-adjustable
fraud threshold. A `SeedDataLoader` populates accounts and merchants on first startup via a
data streamer, a `PaymentSimulator` generates realistic authorize/capture/refund traffic at a
configurable rate, and a `DashboardService` aggregates recent payments (via SQL-fields
queries and in-memory rollups) into a snapshot rendered by a static HTML/JS dashboard.
REST controllers expose payments, simulator, admin (merchant status, fraud threshold), and
the dashboard snapshot.

## Technical Context

**Language/Version**: Java 17 (project targets Java 11+; this commit compiles at 17)

**Primary Dependencies**: Spring Boot 3.3.4 (`spring-boot-starter-web`,
`spring-boot-starter-validation`); GridGain 8.9.30 (`ignite-core`, `ignite-indexing`,
`ignite-spring`); Jackson

**Storage**: GridGain (Apache Ignite) in-memory caches — `accounts`, `merchants`,
`payments`, `ledger_entries`; embedded single node, persistence disabled at this stage. No
external relational database or CDC pipeline in this commit.

**Testing**: No automated test suite; behavior validated by exercising REST endpoints and
the dashboard (only `spring-boot-starter-test` is present, unused).

**Target Platform**: Local JVM / server; dashboard served at `http://localhost:8080`.

**Project Type**: Single Spring Boot web service with a static browser frontend (no build
step for the frontend).

**Performance Goals**: Sustain simulator-driven authorize traffic (default 120 payments/sec)
with the dashboard refreshing once per second; low-latency cache-first payment operations.

**Constraints**: Live state held only in GridGain caches; payment operations must be atomic
across caches (pessimistic, repeatable-read transactions); operator levers (simulator,
merchant status, fraud threshold) must take effect immediately.

**Scale/Scope**: Default seed of 100,000 accounts and 10,000 merchants (configurable);
metrics computed over rolling one- and five-minute windows.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. External Database Is the System of Record**: DEVIATION (justified for the baseline).
  This commit runs GridGain embedded and in-memory only; there is no external system of
  record yet, so live state does not survive a full cache restart. This is the initial slice
  that stands up the payment engine and observability; introducing the external database and
  making GridGain a cache of durable data is deferred to a later increment. Recorded in
  Complexity Tracking.
- **II. Change Data Capture, Not Dual Writes**: PASS (not yet applicable). No external store
  exists in this commit, so there are no dual writes and no CDC pipeline to build; the
  principle is honored vacuously and left intact for the increment that adds the external
  database.
- **III. Cache-First Hot Path, Asynchronous Archival**: PASS on the hot path. Authorize,
  capture, and refund run entirely against GridGain with no synchronous external round trips.
  Asynchronous archival of terminal payments is not present because there is no external store
  yet; no archival regression is introduced.
- **IV. Pluggable Infrastructure Behind Configuration**: PASS. Seed sizes, simulator rate,
  target decline rate, fraud threshold, and GridGain persistence are all driven by
  `application.yml` / Spring properties rather than code forks.
- **V. Observable, Demonstrable Behavior**: PASS. Every behavior is visible through the
  dashboard or an HTTP endpoint, and the operator levers (start/stop simulator, merchant
  outage, fraud threshold) take effect immediately and show up on the next refresh.
- **VI. Reproducible One-Command Local Stack**: PARTIAL. The service seeds its own data on an
  empty start and runs from a single `mvn spring-boot:run` with an embedded GridGain node; the
  Docker Compose multi-service stack described in the project vision is not part of this
  commit. The reproducible, self-seeding local-run property is preserved.

## Project Structure

### Documentation (this feature)

```text
specs/001-gridgain-payments-baseline/
├── plan.md              # This file
├── spec.md              # Feature specification
└── tasks.md             # Task breakdown
```

### Source Code (repository root)

```text
pom.xml
src/main/java/com/example/paymentsdemo/
├── PaymentsDemoApplication.java          # Spring Boot entry point
├── api/
│   ├── PaymentController.java            # POST authorize/capture/refund
│   ├── DashboardController.java          # GET /api/dashboard snapshot
│   ├── SimulatorController.java          # POST start/stop, GET status
│   └── AdminController.java              # POST merchant status, fraud threshold
├── config/
│   └── GridGainConfig.java               # Embedded Ignite node + cache config
├── domain/
│   ├── Account.java / AccountStatus.java / RiskTier.java
│   ├── Merchant.java
│   ├── Payment.java / PaymentStatus.java
│   └── LedgerEntry.java / LedgerDirection.java
├── dto/
│   ├── AuthorizePaymentRequest.java      # Validated authorize input
│   ├── PaymentResponse.java
│   ├── DashboardSnapshot.java
│   ├── DeclineReasonCount.java / MerchantVolume.java
│   ├── RecentSuspiciousPayment.java
│   ├── SimulatorStatusResponse.java / ThroughputPoint.java
│   └── (dashboard projection records)
├── service/
│   ├── CacheNames.java                   # Cache name constants
│   ├── PaymentService.java               # Authorize/capture/refund transactions
│   ├── FraudService.java                 # Scoring + runtime threshold
│   ├── DashboardService.java             # Snapshot aggregation
│   ├── MerchantAdminService.java         # Merchant active flag
│   ├── PaymentOperationResult.java
│   └── SeedDataLoader.java               # Startup seeding via data streamer
└── simulator/
    └── PaymentSimulator.java             # Scheduled traffic generator

src/main/resources/
├── application.yml                       # Ports, seed sizes, simulator, fraud config
└── static/
    ├── index.html                        # Dashboard markup
    ├── app.js                            # Snapshot fetch + render + controls
    └── styles.css                        # Dashboard styling
```

**Structure Decision**: A single Spring Boot web-service module (Maven, Java) with a
no-build static frontend under `src/main/resources/static`. Code is organized by
responsibility — `domain` (cache-stored model), `dto` (API and dashboard projections),
`service` (payment engine, fraud, dashboard, admin, seeding), `simulator` (traffic), `api`
(REST controllers), and `config` (embedded GridGain). GridGain is embedded in-process so the
whole demo is one runnable artifact.

## Complexity Tracking

> Fill ONLY if Constitution Check has violations that must be justified

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| No external system of record; GridGain holds live state in-memory (deviates from Principle I) | Baseline increment stands up the payment engine, fraud scoring, simulator, and dashboard as one runnable artifact so the core behavior can be demonstrated before the external-database and CDC layers are introduced | Wiring an external MariaDB store plus a Debezium → Kafka → GridGain sink into the first commit would multiply moving parts and infrastructure before any payment behavior exists to observe; the durable store is deferred to a later increment that extends this baseline |
