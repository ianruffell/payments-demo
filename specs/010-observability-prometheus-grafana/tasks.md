---

description: "Task list for Prometheus & Grafana observability (forward-looking; not yet implemented)"
---

# Tasks: Prometheus & Grafana Observability

**Input**: Design documents from `/specs/010-observability-prometheus-grafana/`

**Prerequisites**: plan.md (required), spec.md (required for user stories)

**Tests**: This project ships no automated test suite; validation is by checking Prometheus targets and confirming the dashboards populate. No test tasks are included.

**Status**: These tasks are proposed and NOT yet implemented — all boxes are unchecked.

**Organization**: Tasks are grouped by user story so each dashboard can be delivered and demonstrated independently. Paths are relative to the repository root.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1–US5)

## Path Conventions

- Observability config: `observability/` (Prometheus, Grafana provisioning, JMX mappings)
- Application code/config: `src/main/`
- Deployment: `docker-compose.yml`, `pom.xml`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Stand up the Prometheus + Grafana pipeline that every dashboard depends on.

- [ ] T001 Add `prometheus` and `grafana` services to `docker-compose.yml` with volumes for Prometheus TSDB and mounts for `observability/prometheus/` and `observability/grafana/provisioning/`; document ports.
- [ ] T002 Add `observability/prometheus/prometheus.yml` with a demo scrape interval, bounded retention, and (initially empty) scrape-job placeholders for the app and each exporter.
- [ ] T003 [P] Add `observability/grafana/provisioning/datasources/prometheus.yml` configuring Prometheus as the default data source.
- [ ] T004 [P] Add `observability/grafana/provisioning/dashboards/dashboards.yml` (dashboard provider) pointing at the mounted JSON dashboard directory.

**Checkpoint**: Prometheus and Grafana come up together; Grafana has the Prometheus data source with no manual setup.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Instrument the application, since the Payments Flow dashboard (P1) depends on it.

**⚠️ CRITICAL**: US1 cannot be delivered until this is complete.

- [ ] T005 Add `spring-boot-starter-actuator` and `micrometer-registry-prometheus` to `pom.xml`.
- [ ] T006 Expose the Prometheus endpoint in `src/main/resources/application.yml` (`management.endpoints.web.exposure.include: prometheus,health`) and add `src/main/java/com/example/paymentsdemo/config/MetricsConfig.java` for common tags.
- [ ] T007 Add `src/main/java/com/example/paymentsdemo/service/PaymentFlowMetrics.java` registering per-stage meters — counters for received / screening (passed, declined-before-merchant) / merchant (approved, declined, timed-out) / settlement (authorized, captured, refunded), in-flight gauges per stage, a decline-reason counter (bounded label), and a fraud-threshold gauge — updated from the existing stage/outcome model; ensure labels are bounded (no per-payment/merchant ids).
- [ ] T008 Add the `app` scrape job (targeting `/actuator/prometheus`) to `observability/prometheus/prometheus.yml`.

**Checkpoint**: The app exposes flow metrics and Prometheus scrapes them.

---

## Phase 3: User Story 1 - Payments Flow dashboard (Priority: P1) 🎯 MVP

**Goal**: A Grafana dashboard showing the payment pipeline as time-series (throughput, in-flight, outcomes) per stage.

**Independent Test**: With the simulator running, open the Payments Flow dashboard and confirm per-stage throughput and outcome panels populate; trigger a merchant outage and confirm the timeout/decline panels react.

- [ ] T009 [US1] Add `observability/grafana/provisioning/dashboards/payments-flow.json` with rows per stage: Received (throughput/in-flight), Initial Screening (passed vs declined-before-merchant), Merchant Review (awaiting/approved/declined/timed-out), Settlement (authorized/captured/refunded), plus approval-vs-decline rate, decline-reasons breakdown, and current fraud threshold.
- [ ] T010 [US1] Verify: run the simulator, confirm panels populate within a scrape interval; trigger a merchant outage and a fraud-threshold change and confirm the relevant panels react.

**Checkpoint**: The signature Payments Flow dashboard is live and reactive.

---

## Phase 4: User Story 2 - MariaDB dashboard (Priority: P2)

**Goal**: A dashboard for MariaDB/MaxScale health.

**Independent Test**: Open the MariaDB dashboard and confirm connections, QPS, and cluster panels populate; stop one node and confirm the cluster-size panel drops.

- [ ] T011 [P] [US2] Add a `mysqld_exporter` service (per database node, Galera/`wsrep` collector enabled) to `docker-compose.yml` with the exporter credentials via a Secret/env.
- [ ] T012 [US2] Add the `mariadb` scrape job(s) to `observability/prometheus/prometheus.yml`.
- [ ] T013 [US2] Add `observability/grafana/provisioning/dashboards/mariadb.json` with panels for connections, queries/sec, InnoDB buffer pool, Galera cluster size/state, replication health, and slow queries.
- [ ] T014 [US2] Verify: panels populate; stop one DB node (quorum retained) and confirm the cluster-size/health panel reflects the change.

**Checkpoint**: MariaDB health is observable.

---

## Phase 5: User Story 3 - GridGain dashboard (Priority: P2)

**Goal**: A dashboard for the GridGain cache/cluster.

**Independent Test**: Open the GridGain dashboard and confirm node count and per-cache entry counts populate; run the simulator and watch the payments-cache count rise and fall.

- [ ] T015 [P] [US3] Attach the `jmx_exporter` java agent to the GridGain nodes (or enable the Ignite metric exporter) and add `observability/jmx/gridgain-jmx-exporter.yml` mapping cluster/cache/memory/transaction MBeans; wire the agent in `docker-compose.yml`.
- [ ] T016 [US3] Add the `gridgain` scrape job to `observability/prometheus/prometheus.yml`.
- [ ] T017 [US3] Add `observability/grafana/provisioning/dashboards/gridgain.json` with panels for cluster node count, per-cache entry counts (accounts/merchants/payments/ledger_entries), cache hit/miss, heap/off-heap memory, and transaction rate.
- [ ] T018 [US3] Verify: node count and cache panels populate; under simulator load the payments-cache entry count rises and falls as terminal payments are evicted.

**Checkpoint**: The GridGain cache tier is observable.

---

## Phase 6: User Story 4 - Debezium / CDC dashboard (Priority: P2)

**Goal**: A dashboard for the Debezium connector and Kafka Connect worker.

**Independent Test**: Open the Debezium dashboard and confirm connector/task state; change reference rows in MariaDB and confirm events-emitted and lag panels react.

- [ ] T019 [P] [US4] Attach the `jmx_exporter` java agent to the Kafka Connect worker and add `observability/jmx/connect-jmx-exporter.yml` mapping Debezium connector/task and Connect worker MBeans; wire the agent in `docker-compose.yml`.
- [ ] T020 [US4] Add the `debezium-connect` scrape job to `observability/prometheus/prometheus.yml`.
- [ ] T021 [US4] Add `observability/grafana/provisioning/dashboards/debezium-cdc.json` with panels for connector state, task state, snapshot-completed, streaming lag (`MilliSecondsBehindSource`), events emitted, and Connect worker health.
- [ ] T022 [US4] Verify: connector shows running; change reference rows in MariaDB and confirm events-emitted/lag panels react; stop the connector and confirm the state panel shows non-running.

**Checkpoint**: The CDC pipeline is observable.

---

## Phase 7: User Story 5 - As-code provisioning & one-command bring-up (Priority: P3)

**Goal**: Everything comes up with the stack, no manual Grafana setup, dashboards versioned.

**Independent Test**: Clean start; open Grafana and confirm the data source and all four dashboards are present and populated without any manual action.

- [ ] T023 [US5] Confirm the dashboard provider loads all four JSON dashboards from the mounted directory and the data source is preconfigured on a clean start.
- [ ] T024 [US5] Verify a dashboard JSON edit is picked up on restart (dashboards-as-code round trip).

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Documentation and guardrails.

- [ ] T025 [P] Update `README.md` with an Observability section: Grafana URL + demo credentials (called out as demo-only), Prometheus URL, the four dashboards, retention/scrape-interval notes.
- [ ] T026 Review metric cardinality and hot-path impact: confirm flow meters use bounded labels only and that instrumentation adds no synchronous work to authorize/capture/refund.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately.
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS US1 (app instrumentation).
- **User Stories**:
  - US1 (Payments Flow) is the MVP; depends on Foundational.
  - US2/US3/US4 (MariaDB / GridGain / Debezium) each depend only on Setup (their exporter + scrape job + dashboard) and are independent of one another — deliverable in parallel.
  - US5 (as-code provisioning) validates the whole provisioning story once dashboards exist.
- **Polish (Phase 8)**: Docs and cardinality/hot-path review after dashboards work.

### Parallel Opportunities

- Datasource/provider provisioning T003/T004 are parallel.
- The three infrastructure stories (US2, US3, US4) are mutually independent — their exporter tasks T011/T015/T019 and dashboards T013/T017/T021 can proceed in parallel once Setup is done.

---

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1 Setup (Prometheus + Grafana + provisioning skeleton).
2. Phase 2 Foundational (instrument the app).
3. Phase 3 US1 (Payments Flow dashboard) → the signature observability view.
4. **STOP and VALIDATE** with the simulator running.

### Incremental Delivery

1. Setup + Foundational → pipeline and app metrics in place.
2. US1 → Payments Flow dashboard (MVP).
3. US2/US3/US4 → MariaDB, GridGain, Debezium dashboards (parallelizable).
4. US5 → confirm everything is provisioned as code and turnkey.
5. Polish → docs + cardinality/hot-path review.

### Notes

- Instrumentation is the only application change; it reuses the stage/outcome model already computed for the transaction-flow view.
- Keep flow-metric labels bounded (stage/outcome/decline-reason) to avoid cardinality blow-up (FR-010).
- Metrics must stay off the hot path (pull-based scrape of in-process meters).
- Compose is the delivery target here; the same endpoints/exporters feed the Kubernetes deployment (spec 009) via its own scrape config.
- Validation is manual via Prometheus targets and the Grafana dashboards, since the project ships no automated tests.
