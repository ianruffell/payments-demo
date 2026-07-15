# GridGain Payments Demo Constitution

This constitution captures the durable principles that the GridGain Payments Demo has
followed across its increments. It was reverse-engineered from the delivered codebase and
its commit history, so each principle reflects a decision the project actually made rather
than an aspiration. Feature specifications under `specs/` are expected to honor these
principles unless a spec explicitly records a justified deviation.

## Core Principles

### I. External Database Is the System of Record

Reference data (`accounts`, `merchants`) and terminal payments live in an external
relational database (MariaDB). GridGain is a live cache for in-flight work, never
the durable source of truth. Any feature that persists state must be explicit about where
the authoritative copy lives and how it survives a full cache restart.

### II. Change Data Capture, Not Dual Writes

Reference data reaches the cache through Change Data Capture (Debezium → Kafka → GridGain
sink), not through application code writing to two stores. Features that need cached
projections of external data extend the CDC pipeline; they do not introduce application-level
dual writes that can silently diverge.

### III. Cache-First Hot Path, Asynchronous Archival

In-flight payments are authorized, reviewed, captured, and refunded against GridGain for
low-latency throughput. Terminal payments are archived back to the external database
asynchronously and only evicted from the cache once the durable write succeeds. Features on
the payment hot path must not add synchronous external-database round trips.

### IV. Pluggable Infrastructure Behind Configuration

The external database, the CDC connector, and the deployment topology are selected by
configuration (`demo.external-db.type`, Spring profiles, env files, compose overlays), not by
forking the code. Adding a new backend or topology means adding a configuration path and the
adapters it needs, while leaving existing paths working unchanged.

### V. Observable, Demonstrable Behavior

The demo exists to be watched. Every meaningful behavior is visible through the dashboard,
the transaction-flow view, or an HTTP endpoint, and operator levers (start/stop simulator,
merchant outage, fraud threshold) take effect immediately. A feature is not done until its
effect can be observed live without reading logs.

### VI. Reproducible One-Command Local Stack

The whole system comes up from Docker Compose with a single command and a chosen env file,
seeding its own data (100k accounts, 10 merchants) when the stores are empty. Features must
preserve this: a fresh checkout plus one compose command is the definition of "runs".

## Technology Constraints

- Backend: Java 11+, Spring Boot, Maven.
- Cache/compute: GridGain 8 (three-node cluster), requiring the documented JVM
  `--add-opens` flags off-compose.
- Streaming: Kafka + Kafka Connect with Debezium source connectors.
- External database: a MariaDB cluster (MaxScale-fronted), configured by env file.
- Frontend: static HTML/CSS/JS served by Spring Boot; no build step.
- Deployment: Docker Compose is the reference runtime; compose overlays add optional
  components (Control Center, MaxScale).

## Development Workflow

- Each increment is a self-contained, independently runnable slice recorded as a numbered
  spec under `specs/`.
- Configuration-selected backends must both remain runnable after any change that touches
  shared code.
- Seed data and reset tooling (`clear-demo-data.sh`) keep the demo repeatable between runs.
- User-visible behavior is validated by exercising it through the UI or an endpoint, since
  the project ships no automated test suite.

## Governance

This constitution documents how the project has actually been built and supersedes ad-hoc
practice. Amendments happen when a new increment establishes a durable pattern that changes
or extends a principle above; such changes are recorded in a spec and reflected here. Specs
that deviate from a principle must state the deviation and why it was warranted.

**Version**: 1.0.0 | **Ratified**: 2026-07-15 | **Last Amended**: 2026-07-15
