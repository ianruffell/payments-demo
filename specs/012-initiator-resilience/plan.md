# Implementation Plan: Payment Initiator Ticker Resilience

**Branch**: `012-initiator-resilience` | **Date**: 2026-07-15 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/012-initiator-resilience/spec.md`

## Summary

Guard the payment initiator's periodic tick so an uncaught exception can no longer cancel the `scheduleAtFixedRate` task. A single try/catch around the tick body in `PaymentSimulator.tick()` makes the ticker survive transient GridGain errors (e.g. during a topology change when the processor/GridGain pods roll), so the payment flow recovers on its own instead of requiring a manual initiator restart. Root-cause, one-method change; no behavior change in the happy path.

## Technical Context

**Language/Version**: Java 17 / Spring Boot (payment-initiator role).

**Primary Dependencies**: `PaymentSimulator` (profile `payment-initiator`), its `ScheduledExecutorService` ticker.

**Storage**: None. No data-model or config change.

**Testing**: No automated test suite; validated by rolling pods and observing the generated-payments counter and dashboard throughput.

**Target Platform**: The initiator role, on Docker Compose and Kubernetes alike.

**Constraints**: Must not change generation behavior, rate control, or start/stop semantics.

## Constitution Check

- **I–IV**: Unaffected — no change to the system of record, CDC, hot path, or configuration surface.
- **V. Observable, Demonstrable Behavior** — PASS (reinforced): the demo no longer silently stalls; failures are logged and the ticker recovers.
- **VI. Reproducible One-Command Local Stack** — PASS (reinforced): an unattended stack keeps running through routine pod churn.

No deviations.

## Project Structure

```text
src/main/java/com/example/paymentsdemo/simulator/
└── PaymentSimulator.java   # CHANGED: wrap tick() body in try/catch so the scheduled task never dies
```

## Key Technical Decisions (as implemented)

- **Guard the tick, don't add a liveness probe**: `scheduleAtFixedRate` cancels the task on an uncaught throwable, so the fix is to catch everything inside `tick()` and log. This heals in place (no pod restart) and makes a Kubernetes liveness-restart safety net unnecessary. A liveness probe was considered and rejected as heavier and slower (pod restart vs. next-second retry).
- **Retry cadence unchanged**: the ticker keeps its 1-second period; a failed tick simply logs and the next tick proceeds.
