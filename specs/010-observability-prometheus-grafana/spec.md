# Feature Specification: Prometheus & Grafana Observability

**Feature Branch**: `010-observability-prometheus-grafana`

**Created**: 2026-07-15

**Status**: Draft

**Input**: User description: "add a spec for Prometheus & Grafana to show metrics for all infrastructure (MariaDB, GridGain & Debezium) as well as the various stages in the payments flow, create a dashboard for each"

## User Scenarios & Testing *(mandatory)*

The demo is visually rich at the application level (the dashboard and transaction-flow view) but has no metrics layer for the infrastructure or for the payment pipeline over time. This feature adds Prometheus for metrics collection and Grafana for visualization, with a **dedicated dashboard for each area**: the payments flow, MariaDB, GridGain, and Debezium/CDC. Dashboards are provisioned as code so they appear automatically when the stack starts — no manual Grafana setup. This is an additive observability layer; it does not change payment behavior.

### User Story 1 - Watch the payments flow as metrics over time (Priority: P1)

An operator opens the **Payments Flow** dashboard in Grafana and sees the pipeline the transaction-flow view models — Received → Initial Screening → Merchant Review → Settlement — as time-series: throughput per stage, in-flight counts, approval vs decline rate, decline reasons, merchant timeouts, and capture/refund rates. Unlike the live flow page, these are historical trends the operator can scrub through.

**Why this priority**: The payments flow is the demo's signature story; showing it as metrics over time is the headline value of adding observability and the most differentiated dashboard. It is independently demonstrable once the app exposes metrics and the pipeline is up.

**Independent Test**: Start the stack with the simulator running, open the Payments Flow dashboard, and confirm per-stage throughput and outcome panels populate and update as traffic flows; trigger a merchant outage and confirm the merchant-timeout/decline panels react.

**Acceptance Scenarios**:

1. **Given** the app exposes metrics and Prometheus is scraping it, **When** the operator opens the Payments Flow dashboard, **Then** each stage (received, screening, merchant review, settlement) shows throughput and current in-flight counts.
2. **Given** the simulator is running, **When** payments are authorized, captured, and refunded, **Then** the settlement panels show authorized/captured/refunded rates over time.
3. **Given** a merchant outage is triggered from the app, **When** requests time out or are declined, **Then** the merchant-timeout and decline-reason panels rise accordingly.
4. **Given** the fraud threshold is changed, **When** the operator views the fraud panel, **Then** the current threshold and the pass/decline mix reflect the change.

---

### User Story 2 - Monitor MariaDB health (Priority: P2)

An operator opens the **MariaDB** dashboard and sees the health of the system-of-record database and its MaxScale front: connections, query throughput, InnoDB buffer usage, Galera cluster size and state, replication health, and slow queries.

**Why this priority**: MariaDB is the durable system of record; its health is essential to trust the demo, but it is secondary to seeing the payment pipeline itself.

**Independent Test**: Open the MariaDB dashboard and confirm cluster size, connections, and query-rate panels populate; stop one database node and confirm the cluster-size/health panel reflects the change.

**Acceptance Scenarios**:

1. **Given** a MariaDB metrics exporter is scraped, **When** the operator opens the MariaDB dashboard, **Then** connections, queries per second, and InnoDB buffer-pool panels populate.
2. **Given** the Galera cluster is running, **When** the operator views the cluster panel, **Then** the number of cluster members and their state are shown.
3. **Given** one database node is stopped while quorum holds, **When** the operator views the dashboard, **Then** the cluster-size panel drops and a health indicator flags the degraded state.

---

### User Story 3 - Monitor GridGain cache and cluster (Priority: P2)

An operator opens the **GridGain** dashboard and sees the cache tier: cluster node count, per-cache entry counts (accounts, merchants, payments, ledger entries), cache hit/miss behavior, heap and off-heap memory, and transaction rates.

**Why this priority**: GridGain is the live hot-path cache; its size and health explain the demo's throughput and eviction behavior, but it is secondary to the payment-flow view.

**Independent Test**: Open the GridGain dashboard and confirm node count and per-cache entry-count panels populate; run the simulator and confirm the payments cache entry count rises and then falls as terminal payments are evicted.

**Acceptance Scenarios**:

1. **Given** a GridGain metrics exporter is scraped, **When** the operator opens the GridGain dashboard, **Then** the cluster node count and per-cache entry counts populate.
2. **Given** the simulator is running, **When** payments move through the cache, **Then** the payments-cache entry-count panel rises with in-flight load and falls as terminal payments are archived and evicted.
3. **Given** the cluster is under load, **When** the operator views the memory panels, **Then** heap/off-heap usage and transaction rate are shown.

---

### User Story 4 - Monitor Debezium CDC (Priority: P2)

An operator opens the **Debezium / CDC** dashboard and sees the change-data-capture pipeline: connector and task state, snapshot progress, streaming lag (how far behind the source the connector is), events emitted, and Kafka Connect worker health.

**Why this priority**: CDC is how reference data reaches the cache; visibility into connector state and lag is important for trusting the projection, but secondary to the payment flow.

**Independent Test**: Open the Debezium dashboard and confirm the connector shows running with its task; change reference rows in MariaDB and confirm the events-emitted and lag panels react.

**Acceptance Scenarios**:

1. **Given** the Debezium connector metrics are scraped, **When** the operator opens the CDC dashboard, **Then** connector state, task state, and snapshot-completed status are shown.
2. **Given** reference rows change in MariaDB, **When** the connector streams them, **Then** the events-emitted panel increases and the streaming-lag panel reflects catch-up.
3. **Given** the connector is stopped or failed, **When** the operator views the dashboard, **Then** the connector-state panel clearly shows the non-running state.

---

### User Story 5 - Dashboards provisioned as code, one-command bring-up (Priority: P3)

An operator brings up the stack and the Prometheus data source and all four dashboards are already present in Grafana — no manual data-source configuration or dashboard import. The observability configuration lives in the repository.

**Why this priority**: As-code provisioning is what keeps the demo reproducible and turnkey, but the dashboards are usable even if provisioning were manual, so it is the lowest priority.

**Independent Test**: On a clean start, open Grafana without logging in to configure anything and confirm the Prometheus data source and all four dashboards are present and populated.

**Acceptance Scenarios**:

1. **Given** a clean start, **When** Grafana comes up, **Then** the Prometheus data source is already configured from repository files.
2. **Given** a clean start, **When** the operator opens Grafana, **Then** the Payments Flow, MariaDB, GridGain, and Debezium dashboards are all present without manual import.
3. **Given** a dashboard JSON is changed in the repository, **When** the stack is restarted, **Then** the updated dashboard is loaded.

---

### Edge Cases

- **Target down**: If an exporter or the app is unreachable, its panels show gaps/no-data and Prometheus marks the target down rather than failing other scrapes.
- **App not yet instrumented**: The application currently exposes no metrics endpoint; the payments-flow dashboard depends on adding one, so it must degrade to empty panels until instrumentation is present.
- **GridGain metrics access**: GridGain metrics are exposed over JMX/metric-exporter rather than a native Prometheus endpoint, so a bridge (exporter/agent) is required; without it the GridGain dashboard has no data.
- **Metric cardinality**: Per-merchant or per-payment labels could explode cardinality; flow metrics must be aggregated by stage/outcome, not by individual entity id.
- **Retention window**: Prometheus local retention bounds how far back dashboards can scrub; the default retention must be documented.
- **Clock/scrape interval**: Rates depend on the scrape interval; panels must use rate windows compatible with the configured interval.
- **Security**: Grafana ships with default credentials for the demo; this is acceptable for a local demo but must be called out.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST run Prometheus to scrape metrics from the application and from infrastructure exporters for MariaDB, GridGain, and Debezium/Kafka Connect.
- **FR-002**: The system MUST run Grafana with Prometheus preconfigured as a data source via repository provisioning files (no manual setup).
- **FR-003**: The system MUST provide four Grafana dashboards — Payments Flow, MariaDB, GridGain, and Debezium/CDC — provisioned as code so they load automatically on startup.
- **FR-004**: The application MUST expose a Prometheus-compatible metrics endpoint and MUST publish payments-flow metrics for each stage: received, initial screening (passed / declined-before-merchant), merchant review (awaiting / approved / declined / timed-out), and settlement (authorized / captured / refunded).
- **FR-005**: Payments-flow metrics MUST include per-stage throughput, current in-flight counts, approval-vs-decline rate, decline reasons (aggregated by reason, not by entity), merchant-timeout rate, and the current fraud threshold.
- **FR-006**: MariaDB metrics MUST include connections, query throughput, InnoDB buffer usage, Galera cluster membership and state, replication health, and slow-query counts.
- **FR-007**: GridGain metrics MUST include cluster node count, per-cache entry counts (accounts, merchants, payments, ledger entries), cache hit/miss behavior, heap and off-heap memory usage, and transaction rate.
- **FR-008**: Debezium/CDC metrics MUST include connector and task state, snapshot-completed status, streaming lag behind the source, events emitted, and Kafka Connect worker health.
- **FR-009**: Metrics collection MUST NOT alter payment behavior or add synchronous work to the payment hot path; instrumentation must be non-blocking.
- **FR-010**: Flow metrics MUST be aggregated by stage and outcome (bounded label sets) to avoid unbounded metric cardinality from per-payment or per-merchant labels.
- **FR-011**: The observability stack MUST start together with the demo from the standard one-command bring-up, and dashboards MUST be usable without additional configuration steps.
- **FR-012**: When a scrape target is unavailable, the affected panels MUST show no-data/gaps while other dashboards continue to function.
- **FR-013**: Prometheus retention and scrape interval MUST be configured and documented so dashboard rate windows are consistent.
- **FR-014**: Grafana access details (URL and demo credentials) MUST be documented, and the use of default demo credentials MUST be called out.

### Key Entities *(include if feature involves data)*

- **Prometheus**: The metrics collector, with a scrape configuration listing the application and each infrastructure exporter as targets, plus a retention setting.
- **Grafana**: The visualization tool, provisioned with a Prometheus data source and four dashboards from repository files.
- **Application metrics endpoint**: A Prometheus-format endpoint on the processor exposing JVM metrics plus the custom payments-flow metrics.
- **MariaDB exporter**: A metrics bridge for the MariaDB nodes (and MaxScale front) exposing server, InnoDB, and Galera/wsrep status.
- **GridGain metrics bridge**: A JMX/metric exporter on the GridGain nodes exposing cluster, cache, memory, and transaction metrics.
- **Debezium/Connect exporter**: A JMX metrics bridge on the Kafka Connect worker exposing Debezium connector/task and Connect worker metrics.
- **Dashboards (4)**: Payments Flow, MariaDB, GridGain, Debezium/CDC — each a versioned dashboard definition in the repository.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a clean start with the simulator running, the Payments Flow dashboard shows populated per-stage throughput and outcome panels within one scrape interval of traffic starting.
- **SC-002**: All four dashboards (Payments Flow, MariaDB, GridGain, Debezium) are present in Grafana on first open, with no manual data-source or dashboard configuration.
- **SC-003**: Triggering a merchant outage is visible on the Payments Flow dashboard (rising merchant-timeout/decline panels) within one scrape interval.
- **SC-004**: The GridGain dashboard shows the payments-cache entry count rising under load and falling as terminal payments are evicted.
- **SC-005**: Stopping one MariaDB node (quorum retained) is reflected in the MariaDB dashboard's cluster-size/health panel.
- **SC-006**: Changing reference data in MariaDB is reflected in the Debezium dashboard's events-emitted and lag panels.
- **SC-007**: Enabling the observability layer introduces no measurable change to payment authorize/capture/refund latency.

## Assumptions

- The observability stack is added to the Docker Compose reference runtime (Prometheus, Grafana, and the exporters as services); the same metrics endpoints and exporters apply to the Kubernetes deployment (spec 009) via its own scrape configuration, which is out of scope here.
- The application gains a metrics endpoint and custom flow instrumentation; this is the one application change required, and it reuses the stage/outcome model already computed for the transaction-flow view.
- GridGain and Debezium/Kafka Connect metrics are exposed over JMX (or a metric-exporter) and bridged to Prometheus by an exporter/agent, since neither offers a native Prometheus endpoint by default.
- MariaDB metrics are collected per node (Galera/wsrep status included); MaxScale metrics are included if its exporter is available.
- Grafana uses default demo credentials suitable for a local demo only.
- The project ships no automated test suite; the observability layer is validated by exercising the demo and observing the dashboards and Prometheus targets.
