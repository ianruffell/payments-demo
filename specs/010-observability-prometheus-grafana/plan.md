# Implementation Plan: Prometheus & Grafana Observability

**Branch**: `010-observability-prometheus-grafana` | **Date**: 2026-07-15 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/010-observability-prometheus-grafana/spec.md`

**Note**: This is a forward-looking plan for work not yet built (Status: Draft). It proposes how to add a metrics/observability layer without changing payment behavior.

## Summary

Add Prometheus and Grafana to the Docker Compose reference runtime, with a dedicated dashboard for each area: payments flow, MariaDB, GridGain, and Debezium/CDC. The application gains a Micrometer/Actuator Prometheus endpoint and custom flow metrics keyed to the existing stage/outcome model (Received → Initial Screening → Merchant Review → Settlement). Infrastructure that has no native Prometheus endpoint is bridged: MariaDB via `mysqld_exporter` (with the Galera/wsrep collector), GridGain via a JMX exporter agent (or the Ignite metric exporter), and Debezium/Kafka Connect via a `jmx_exporter` agent on the Connect worker. Prometheus scrapes all targets; Grafana is provisioned as code with the Prometheus data source and all four dashboards so they load on startup. The same application endpoints and exporters carry over to the Kubernetes deployment (spec 009) via its own scrape config, which is out of scope here.

## Technical Context

**Language/Version**: Prometheus + Grafana (current stable images); application change in Java 17 / Spring Boot (Micrometer + Spring Boot Actuator); exporter images/agents (`prom/mysqld-exporter`, `jmx_exporter` java agent).

**Primary Dependencies**: Spring Boot Actuator + `micrometer-registry-prometheus`; `prom/mysqld-exporter`; `jmx_exporter` (GridGain nodes and Kafka Connect); Prometheus; Grafana with file-based provisioning.

**Storage**: Prometheus local TSDB (bounded retention, e.g. a few hours for the demo) on a container volume; Grafana provisioning is read-only from repository files.

**Testing**: No automated test suite. Validation is by exercising the demo, checking Prometheus targets are up, and confirming the four dashboards populate.

**Target Platform**: Docker Compose reference runtime; the metrics endpoints and exporters also apply to the Kubernetes deployment (spec 009).

**Project Type**: Observability addition to an existing Spring Boot web service plus infrastructure services; the only application code change is instrumentation.

**Performance Goals**: Instrumentation is non-blocking (Micrometer meters are updated in-process, scraped on pull); no synchronous work is added to the payment hot path.

**Constraints**: Flow metrics must use bounded labels (stage/outcome/decline-reason), not per-entity ids. Dashboards must be provisioned as code. The one-command bring-up must still stand up the whole demo plus observability.

**Scale/Scope**: 1 Prometheus, 1 Grafana, 4 dashboards; exporters for MariaDB nodes, GridGain nodes, and the Connect worker; one instrumented application endpoint.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. External Database Is the System of Record** — PASS. Observability is read-only; MariaDB remains the system of record.
- **II. Change Data Capture, Not Dual Writes** — PASS. Debezium is observed, not altered; no new writes are introduced.
- **III. Cache-First Hot Path, Asynchronous Archival** — PASS. Metrics are updated in-process and scraped by pull; no synchronous external call is added to authorize/capture/refund.
- **IV. Pluggable Infrastructure Behind Configuration** — PASS. Prometheus scrape config, Grafana provisioning, and exporter wiring are configuration/compose additions, not code forks. The single app change (a metrics endpoint) is enabled via dependency + Actuator config.
- **V. Observable, Demonstrable Behavior** — PASS (directly reinforced). This deepens the demo's observability with historical, infrastructure-wide metrics visible without reading logs.
- **VI. Reproducible One-Command Local Stack** — PASS. Dashboards and the data source are provisioned as code so the observability layer comes up with the stack and needs no manual Grafana setup.

No deviations.

## Project Structure

### Documentation (this feature)

```text
specs/010-observability-prometheus-grafana/
├── plan.md              # This file
├── spec.md              # Feature specification
└── tasks.md             # Dependency-ordered task list
```

### Source Code (repository root)

```text
.
├── docker-compose.yml                            # CHANGED: add prometheus, grafana, exporters + agents
├── pom.xml                                       # CHANGED: add actuator + micrometer-registry-prometheus
├── observability/                                # NEW
│   ├── prometheus/
│   │   └── prometheus.yml                         # scrape jobs: app, mysqld_exporter(s), gridgain-jmx, connect-jmx
│   ├── grafana/
│   │   └── provisioning/
│   │       ├── datasources/prometheus.yml         # Prometheus data source (as code)
│   │       └── dashboards/
│   │           ├── dashboards.yml                 # dashboard provider (points at the json dir)
│   │           ├── payments-flow.json             # Dashboard 1
│   │           ├── mariadb.json                   # Dashboard 2
│   │           ├── gridgain.json                  # Dashboard 3
│   │           └── debezium-cdc.json              # Dashboard 4
│   └── jmx/
│       ├── gridgain-jmx-exporter.yml              # JMX → Prometheus mapping for Ignite/GridGain MBeans
│       └── connect-jmx-exporter.yml               # JMX → Prometheus mapping for Debezium/Connect MBeans
└── src/main/
    ├── java/com/example/paymentsdemo/
    │   ├── config/MetricsConfig.java              # NEW: MeterRegistry customization / common tags
    │   └── service/PaymentFlowMetrics.java        # NEW: registers/updates per-stage flow meters
    └── resources/
        └── application.yml                        # CHANGED: expose actuator prometheus endpoint
```

**Structure Decision**: Observability configuration lives under a new top-level `observability/` directory (Prometheus config, Grafana provisioning, JMX exporter mappings), mounted into the respective containers by compose. Application instrumentation follows the existing package layout (`config`, `service`). Grafana file-based provisioning keeps dashboards and the data source as versioned code.

## Key Technical Decisions (proposed)

- **App instrumentation via Micrometer/Actuator**: Add `spring-boot-starter-actuator` + `micrometer-registry-prometheus`, expose `/actuator/prometheus`, and register custom meters in `PaymentFlowMetrics` keyed to the stage/outcome model already computed by `TransactionFlowService` — counters for received/screening/merchant/settlement transitions, gauges for in-flight per stage, and a gauge for the current fraud threshold. Labels are bounded (`stage`, `outcome`, `decline_reason`), never per payment/merchant id.
- **MariaDB via mysqld_exporter**: One `mysqld_exporter` per database node (or scraping through a read endpoint), with the Galera/`wsrep` status collector enabled for cluster membership/state; MaxScale metrics included if its exporter is available.
- **GridGain via JMX exporter**: Attach the `jmx_exporter` java agent to the GridGain nodes (or enable the Ignite Prometheus/OpenCensus metric exporter) and map cluster/cache/memory/transaction MBeans to Prometheus metrics; per-cache entry counts for `accounts`/`merchants`/`payments`/`ledger_entries`.
- **Debezium via JMX exporter on Connect**: Attach the `jmx_exporter` agent to the Kafka Connect worker to expose Debezium connector/task MBeans (state, snapshot, `MilliSecondsBehindSource`, events) and Connect worker health.
- **Grafana as code**: File provisioning wires the Prometheus data source and a dashboard provider that loads the four JSON dashboards from the mounted directory, so nothing is configured by hand and dashboard edits are versioned.
- **Prometheus scrape config**: Static scrape jobs for the app and each exporter with a demo-appropriate interval and bounded retention, documented so dashboard rate windows match.
- **Compose-first, K8s-ready**: Delivered on Compose; the same `/actuator/prometheus` endpoint and JMX exporters are reused by the Kubernetes deployment's scrape configuration (spec 009) without further app changes.

## Complexity Tracking

> Recorded for transparency; the Constitution Check passed with no violations.

| Decision | Why Needed | Simpler Alternative Rejected Because |
|----------|------------|--------------------------------------|
| JMX exporter agents for GridGain and Debezium/Connect | Neither exposes a native Prometheus endpoint by default; JMX is the supported metrics surface | Writing bespoke metric shims in the app would duplicate infrastructure internals the exporters already expose reliably |
| One application code change (metrics endpoint + flow meters) | Per-stage flow metrics over time cannot come from infrastructure exporters; they must be emitted by the processor | Deriving flow metrics by scraping the existing HTML/JSON flow endpoint would be brittle and add cardinality/parsing risk |
| Dashboards provisioned as JSON files rather than built in the UI | Keeps the demo reproducible and the dashboards versioned (Principle VI) | Hand-built dashboards would not survive a fresh start and would break the one-command guarantee |
