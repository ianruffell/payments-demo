# Implementation Plan: Flow Throughput Charts and Reset Helper

**Branch**: `004-flow-throughput-charts` | **Date**: 2026-05-20 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-flow-throughput-charts/spec.md`

## Summary

Extend the transaction-flow view with per-stage throughput. The flow service computes a
60-second, one-second-resolution throughput series per stage (filtered to each stage's
relevant payments) and attaches it to each `TransactionFlowStep`; `flow.js` renders an inline
SVG line/area chart plus a headline throughput figure on every stage card, styled in
`styles.css`. A stop-guard in `PaymentSimulator` prevents queued work from creating payments
after the simulator is stopped, and a new `gridgain/clear-demo-data.sh` helper stops the
simulator and clears the transient GridGain tables (optionally the MariaDB archive) so the
demo is repeatable between runs.

## Technical Context

**Language/Version**: Java 11+ (Spring Boot); browser JavaScript (no build step); POSIX sh

**Primary Dependencies**: Spring Boot, GridGain 8 (Apache Ignite thin/SQL), Docker Compose,
sqlline (GridGain), mariadb client, curl

**Storage**: GridGain SQL tables (`Payment`, `LedgerEntry`, `MerchantPaymentAttempt`) as the
in-flight cache; MariaDB archive tables (`PAYMENT_ARCHIVE`, `LEDGER_ENTRY_ARCHIVE`) as system
of record

**Testing**: Manual verification through `/flow.html` and the reset script (project ships no
automated test suite)

**Target Platform**: Docker Compose reference stack; Spring Boot processor on
`http://localhost:8080`

**Project Type**: Web service with static frontend plus operational shell tooling

**Performance Goals**: Throughput series bucketed at one-second resolution over a rolling
60-second window; charts render inline from the existing flow snapshot with no extra data
round trips

**Constraints**: Reuse the payment history already read by the flow service (no new query
source); charts are static SVG with no external chart library; reset helper must not touch the
external database archive by default

**Scale/Scope**: Four flow stages, each with a 60-point series; one reset script; one
simulator guard; three touched Java/JS files plus CSS

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. External Database Is the System of Record**: PASS. Throughput is derived from in-flight
  cache data only. The reset helper leaves the MariaDB archive tables untouched by default and
  clears them only on explicit opt-in (`CLEAR_ARCHIVE=true`), respecting the archive as the
  durable copy.
- **II. Change Data Capture, Not Dual Writes**: PASS. No new writes to reference data; no dual
  writes introduced. Reset deletes are administrative teardown, not application data flow.
- **III. Cache-First Hot Path, Asynchronous Archival**: PASS. Throughput is computed from the
  already-loaded payment history; no synchronous external round trips added to the hot path.
- **IV. Pluggable Infrastructure Behind Configuration**: PASS. The reset helper parameterizes
  service names, connection strings, and binaries via environment variables with
  compose-oriented defaults rather than hard-coding topology.
- **V. Observable, Demonstrable Behavior**: PASS (reinforced). Per-stage throughput is now
  visible live on the flow view, and the operator can reset to a clean state between runs.
- **VI. Reproducible One-Command Local Stack**: PASS (reinforced). The reset helper makes the
  one-command stack repeatable between demo runs, and the frontend remains a build-free static
  asset.

No deviations. Complexity Tracking not required.

## Project Structure

### Documentation (this feature)

```text
specs/004-flow-throughput-charts/
├── plan.md              # This file
├── spec.md              # Feature specification
└── tasks.md             # Task breakdown
```

### Source Code (repository root)

```text
src/main/java/com/example/paymentsdemo/
├── dto/
│   └── TransactionFlowStep.java        # + throughputSeries field
├── service/
│   └── TransactionFlowService.java     # throughputSeries() helper + per-stage series wiring
└── simulator/
    └── PaymentSimulator.java           # stop-guard in generatePayment()

src/main/resources/static/
├── flow.js                             # per-stage chart + headline rendering, seriesLinePath()
└── styles.css                          # .flow-step__throughput chart styling, layout tweaks

gridgain/
└── clear-demo-data.sh                  # new reset helper (stop simulator, clear tables)

.gitignore                              # ignore BOOT-INF/, superset/, screenshot.png
```

**Structure Decision**: This is the existing single Spring Boot web-service module with a
static frontend, plus operational shell tooling under `gridgain/`. The change touches the DTO
and service layers (backend throughput derivation), the static assets (chart rendering), the
simulator (stop-guard), and adds one shell script — no new modules or layers are introduced.

## Complexity Tracking

> No constitution violations; this section is intentionally empty.
