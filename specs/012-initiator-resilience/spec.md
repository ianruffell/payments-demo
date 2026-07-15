# Feature Specification: Payment Initiator Ticker Resilience

**Feature Branch**: `012-initiator-resilience`

**Created**: 2026-07-15

**Status**: Delivered

**Input**: User description: "the payment flow stops after pod rolls and needs a manual initiator restart — make it self-heal so the flow recovers on its own."

## User Scenarios & Testing *(mandatory)*

The payment initiator drives the simulator from a once-per-second ticker scheduled with `scheduleAtFixedRate`. That scheduler permanently cancels the task if a single run throws an uncaught exception. A transient GridGain error during a cluster topology change (for example when the processor or GridGain pods are rolled) can throw inside a tick, silently killing the ticker: the initiator still reports `running = true` but generates no payments, and the whole demo flow stops until an operator manually restarts the initiator. This feature makes the ticker survive transient errors so the flow recovers on its own.

### User Story 1 - Payment flow survives a pod roll (Priority: P1)

As an operator, when I roll the processor or GridGain pods while the simulator is running, the payment flow keeps generating payments (or resumes on its own within seconds) without me restarting the initiator.

**Why this priority**: It is the whole point of the fix — an unattended demo should not silently stall after routine pod churn.

**Independent Test**: With the simulator running and traffic flowing, roll the processor (or GridGain) pod; confirm the initiator's generated-payments counter keeps advancing and dashboard throughput returns to a healthy level without a manual initiator restart.

**Acceptance Scenarios**:

1. **Given** the simulator is running, **When** a tick encounters a transient error (e.g. a GridGain read during a topology change), **Then** the error is logged and the ticker continues on the next second rather than stopping permanently.
2. **Given** the processor or GridGain pods are rolled while the simulator runs, **When** the topology settles, **Then** the initiator resumes generating payments without operator intervention.
3. **Given** the simulator is running, **When** the initiator reports `running = true`, **Then** the generated-payments counter continues to advance over time.

---

### Edge Cases

- **Repeated transient errors**: If ticks keep failing (e.g. GridGain is down for a while), each failure is logged and the ticker keeps retrying every second; it recovers automatically once the dependency is healthy again.
- **Stopped simulator**: A stopped simulator (`running = false`) still ticks (to drain pending merchant callbacks) but generates nothing — unchanged behavior.
- **Worker-task errors**: Per-payment generation runs on a worker pool; an error there was already isolated from the ticker and remains so.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The payment initiator's periodic ticker MUST NOT terminate when a single tick throws; it MUST catch and log the error and continue on the next scheduled interval.
- **FR-002**: The initiator MUST resume generating payments on its own after a transient dependency error or a cluster topology change, without an operator restart.
- **FR-003**: The fix MUST NOT change normal generation behavior, rate control, or the start/stop semantics of the simulator.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After rolling the processor (or GridGain) pod with the simulator running, the initiator's generated-payments counter advances again without a manual restart.
- **SC-002**: A simulated transient tick error is logged and the ticker keeps running (subsequent ticks still fire).
- **SC-003**: Steady-state throughput and generation rate are unchanged from before the fix.

## Assumptions

- The wedge is caused by `scheduleAtFixedRate` cancelling the ticker task on an uncaught exception; guarding the tick body is the root-cause fix and makes a Kubernetes liveness-restart safety net unnecessary.
- Transient GridGain errors during topology changes are expected and recoverable; retrying each second is acceptable for a demo.
- The project ships no automated test suite; validation is by rolling pods and observing the initiator counter and dashboard throughput.
