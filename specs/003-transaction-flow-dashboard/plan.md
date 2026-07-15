# Implementation Plan: Transaction Flow Dashboard & Async Simulator Controls

**Branch**: `003-transaction-flow-dashboard` | **Date**: 2026-05-08 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-transaction-flow-dashboard/spec.md`

**Note**: This plan is reverse-engineered from delivered commit 2dba980. It records the
technical approach actually taken, not a forward-looking proposal.

## Summary

Add a live transaction-flow dashboard that visualizes recent payments moving through the
pipeline (received → initial screening → merchant review → settlement), backed by a new
snapshot service and DTOs exposed over a read-only REST endpoint and rendered by a static
page that auto-refreshes once per second. In the same increment, decouple the load generator:
the `PaymentSimulator` moves into its own `payment-initiator` Spring profile/container and
drives the processor over HTTP, while the processor's existing simulator control endpoints
delegate to it through a new `SimulatorGatewayService`. Supporting changes raise seeded
merchant daily limits so simulated approvals are not gated by limit exhaustion, and tune the
default fraud threshold and decline rate.

## Technical Context

**Language/Version**: Java 11+ (records used for DTOs), Spring Boot

**Primary Dependencies**: Spring Web (REST controllers, profiles), Apache Ignite / GridGain 8
client API (`SqlFieldsQuery` over caches), Jackson `ObjectMapper`, JDK `java.net.http.HttpClient`;
frontend is static HTML/CSS/vanilla JS (`fetch`, no build step)

**Storage**: GridGain caches on the hot path (`PAYMENTS`, `MERCHANT_PAYMENT_ATTEMPTS`,
`MERCHANTS`, `ACCOUNTS`); external MariaDB remains the system of record (unchanged by
this increment)

**Testing**: No automated test suite (per constitution); behavior validated by exercising the
`GET /api/dashboard/transaction-flow` endpoint, the `/flow.html` page, and the simulator
start/stop controls live

**Target Platform**: Linux containers via Docker Compose; browser dashboard at
`http://localhost:8080`

**Project Type**: Web service (Spring Boot backend) with a static browser frontend, plus a
sibling traffic-generator process selected by Spring profile

**Performance Goals**: Flow view refreshes about once per second; snapshot computed from
single-pass SQL scans over the trailing five-minute window without external-DB round trips

**Constraints**: Flow snapshot must stay on the cache hot path (no synchronous external-DB
access); simulator status must degrade gracefully when the initiator is unreachable; whole
stack must still start from one compose command

**Scale/Scope**: Demo scale — 100k seeded accounts, up to 10 merchants; simulator default rate
120 payments/second; four pipeline stages and three connectors in the flow map

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. External Database Is the System of Record** — PASS. The flow snapshot reads only the
  GridGain caches; it introduces no new durable store and does not change where terminal
  payments are archived.
- **II. Change Data Capture, Not Dual Writes** — PASS. No new projection of external data and no
  application dual writes are introduced; the snapshot is a read-side aggregation of existing
  cached payment/attempt data.
- **III. Cache-First Hot Path, Asynchronous Archival** — PASS. The snapshot is built from cache
  SQL queries with no synchronous external-DB calls. The simulator becomes fully asynchronous
  and out-of-process, calling the processor's public APIs over HTTP rather than blocking any
  hot-path service.
- **IV. Pluggable Infrastructure Behind Configuration** — PASS. The traffic generator, its
  control endpoint, and the processor-side gateway are toggled by Spring profile
  (`payment-initiator` vs `!merchant-simulator & !payment-initiator`); base URLs and the merchant
  daily-limit floor are configuration values, not forks.
- **V. Observable, Demonstrable Behavior** — PASS (and directly advances this principle). The new
  transaction-flow view makes pipeline movement observable live, and the simulator start/stop
  lever keeps taking effect immediately through the gateway.
- **VI. Reproducible One-Command Local Stack** — PASS. A `payments-demo-initiator` service is
  added to `docker-compose.yml`; the stack still comes up with one compose command and a chosen
  env file, and seeding still runs.

No deviations requiring justification. Complexity Tracking below is not required.

## Project Structure

### Documentation (this feature)

```text
specs/003-transaction-flow-dashboard/
├── plan.md              # This file
├── spec.md              # Feature specification
└── tasks.md             # Task breakdown
```

### Source Code (repository root)

```text
src/main/java/com/example/paymentsdemo/
├── api/
│   ├── TransactionFlowController.java     # NEW: GET /api/dashboard/transaction-flow
│   ├── PaymentInitiatorController.java    # NEW: /api/simulator start/stop/status (payment-initiator profile)
│   ├── SimulatorController.java           # CHANGED: delegates to SimulatorGatewayService
│   ├── AdminController.java               # CHANGED: profile excludes payment-initiator
│   ├── DashboardController.java           # CHANGED: profile excludes payment-initiator
│   ├── MerchantResultController.java      # CHANGED: profile excludes payment-initiator
│   └── PaymentController.java             # CHANGED: profile excludes payment-initiator
├── dto/
│   ├── TransactionFlowSnapshot.java       # NEW: full snapshot record
│   ├── TransactionFlowStep.java           # NEW: one pipeline stage
│   ├── TransactionFlowStageState.java     # NEW: one outcome within a stage
│   └── TransactionFlowConnection.java     # NEW: directed link between stages
├── service/
│   ├── TransactionFlowService.java        # NEW: builds snapshot from cache SQL queries
│   ├── SimulatorGatewayService.java       # NEW: HTTP proxy from processor to initiator
│   ├── DashboardService.java              # CHANGED: uses SimulatorGatewayService for status
│   ├── SeedDataLoader.java                # CHANGED: normalizeMerchantLimits() + floor config
│   ├── FraudService.java                  # CHANGED: default threshold 72.0 -> 84.0; profile
│   └── (Merchant*/Payment* services)      # CHANGED: profile excludes payment-initiator
└── simulator/
    └── PaymentSimulator.java              # CHANGED: payment-initiator profile; HTTP to processor

src/main/resources/
├── application.yml                        # CHANGED: initiator.base-url, processor.base-url,
│                                          #          fraud threshold 84.0, decline rate 0.02
└── static/
    ├── flow.html                          # NEW: transaction-flow page
    ├── flow.js                            # NEW: fetch + render + 1s refresh loop
    ├── styles.css                         # CHANGED: flow map, connectors, pulses, nav chips
    └── index.html                         # CHANGED: nav chips, threshold defaults

docker-compose.yml                         # CHANGED: payments-demo-initiator service
```

**Structure Decision**: Single Spring Boot service repository with a static frontend, matching
the existing project layout. The new flow feature follows the established
controller → service → DTO layering. The load generator is not a new module but the existing
`PaymentSimulator` re-homed under the `payment-initiator` profile and run as a sibling container;
profile annotations partition which beans load in the processor versus the initiator.

## Complexity Tracking

> No constitution violations; this section is intentionally empty.
