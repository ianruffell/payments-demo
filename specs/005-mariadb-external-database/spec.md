# Feature Specification: MariaDB External Database as System of Record

**Feature Branch**: `005-mariadb-external-database`

**Created**: 2026-05-21

**Status**: Delivered

**Input**: Reverse-engineered from commit 1e261f9 — "Add MariaDB external database option". This specification also consolidates the external-database system-of-record architecture — CDC projection into the cache, asynchronous archival and eviction, and role-separated runtime profiles — so that architecture has a single owning spec.

## User Scenarios & Testing *(mandatory)*

This increment establishes an external **MariaDB** database as the durable system of record for the demo. Reference data (`accounts`, `merchants`) and completed (terminal) payments live in MariaDB; GridGain is the live cache for in-flight work and a projection target for reference data. The database is selected through configuration (`demo.external-db.type=mariadb` plus the `.env.mariadb` file), reference data reaches the cache through Change Data Capture rather than application dual writes, terminal payments are archived back to MariaDB and then evicted from the cache, and the application runs as role-separated Spring profiles so only the processor role talks to the database directly. The stories below describe what an operator can now do and observe.

### User Story 1 - MariaDB is the durable system of record (Priority: P1)

An operator brings the whole stack up with a single Docker Compose command and the `.env.mariadb` file. MariaDB becomes the durable store for reference data and archived payments, GridGain holds in-flight transactions, and every existing demo behavior (dashboard, simulator, merchant outage, fraud threshold) works against this topology. On a fresh, empty database the application seeds its own reference data on startup.

**Why this priority**: This is the headline capability. Without an external durable store the demo has no system of record and nothing else in this increment has a home. It is also the smallest independently demonstrable slice: a fresh checkout plus one command.

**Independent Test**: Run `docker compose --env-file .env.mariadb up --build`, confirm the MariaDB container and the application start, the `ACCOUNTS`/`MERCHANTS`/`PAYMENT_ARCHIVE`/`LEDGER_ENTRY_ARCHIVE` tables are created, seed data loads, and the dashboard at `http://localhost:8080` shows live traffic.

**Acceptance Scenarios**:

1. **Given** a clean checkout, **When** the operator starts the stack with `--env-file .env.mariadb`, **Then** the MariaDB container starts, `demo.external-db.type` resolves to `mariadb`, and the demo runs end to end.
2. **Given** MariaDB with empty stores, **When** the application starts, **Then** the reference and archive tables are created with MariaDB column types and 100k accounts and 5 merchants are seeded.
3. **Given** the stores already contain readable seed data, **When** the application restarts, **Then** it skips re-seeding; if existing data is unreadable it resets the caches and reloads.
4. **Given** MariaDB is selected, **When** the operator uses the dashboard levers (start/stop simulator, merchant outage, fraud threshold), **Then** the effects are observed live.

---

### User Story 2 - Reference data reaches the cache via CDC, not dual writes (Priority: P2)

The operator expects changes to reference data in MariaDB to appear in the GridGain reference cache automatically. A Debezium MariaDB source connector captures `ACCOUNTS` and `MERCHANTS` changes into Kafka topics, and a dedicated reference-cache sink consumes those topics into the GridGain caches. The application never writes reference data to both stores itself.

**Why this priority**: This is the mechanism that keeps the cache consistent with the system of record. It is essential to the architecture but can be exercised after the stack is already up and seeded, so it sits behind US1.

**Independent Test**: With the stack running, change a merchant's active flag directly in MariaDB and confirm the change appears in the GridGain reference cache and on the dashboard, with no application dual write involved.

**Acceptance Scenarios**:

1. **Given** the stack is running, **When** it starts, **Then** the MariaDB Debezium connector (`paymentsdemo-mariadb-reference`) is registered against a binlog-enabled MariaDB and the reference-cache sink consumes the `ACCOUNTS`/`MERCHANTS` change topics into GridGain.
2. **Given** a row is updated in `MERCHANTS` in MariaDB, **When** the change is captured, **Then** the corresponding GridGain reference-cache entry is updated without any application-level write to the cache.
3. **Given** a CDC tombstone (null-value) record arrives on a topic, **When** the sink processes it, **Then** the record is skipped without error.
4. **Given** the reference-cache sink role, **When** it starts, **Then** it runs with no web layer and no JDBC data source, consuming only from Kafka.

---

### User Story 3 - Terminal payments archived to MariaDB, then evicted from cache (Priority: P2)

The operator watches payments move through the pipeline and settle without the cache growing unbounded. When a payment reaches a terminal state it is archived asynchronously back to MariaDB and then evicted from the GridGain cache once the durable write succeeds. The dashboard and transaction-flow views merge the live cache with the MariaDB archive so eviction is invisible to the viewer.

**Why this priority**: Asynchronous archival keeps the hot path fast (no synchronous database round trip during authorize/capture/refund) while guaranteeing durability. It is a distinct, independently observable slice layered on the durable store from US1.

**Independent Test**: Run the simulator, let payments settle, and confirm terminal payments appear in `PAYMENT_ARCHIVE`, disappear from the in-flight cache after their retention window, and still appear in the dashboard totals (served from the archive).

**Acceptance Scenarios**:

1. **Given** a captured payment, **When** the archive service polls, **Then** after the captured-retention window the payment and its ledger entries are written to MariaDB and the payment is evicted from the cache.
2. **Given** a payment is archived, **When** the durable write to MariaDB fails, **Then** the payment is NOT evicted from the cache and archival is retried on a later poll.
3. **Given** archived payments exist only in MariaDB, **When** the dashboard or transaction-flow view is requested, **Then** it merges live cache rows with archived rows so counts and history remain complete.
4. **Given** a payment is archived, **When** it debits the account balance, **Then** the balance update is applied in the same transaction as the archive write.

---

### User Story 4 - Role-separated runtime via Spring profiles (Priority: P3)

The operator runs the same application image in several roles selected by Spring profile — the processor (the only role with a database connection), the payment initiator, the merchant simulators, and the reference-cache sink. Non-processor roles start without a data source so they do not require database credentials or drivers.

**Why this priority**: Role separation is how the demo distributes work across containers and keeps the system of record behind a single role. It is valuable structure but sits behind the functional stories, since the demo can be reasoned about as one logical system first.

**Independent Test**: Start the stack and confirm each role container comes up under its profile, that only the processor connects to MariaDB, and that the reference-cache-sink and simulator containers run without a JDBC data source.

**Acceptance Scenarios**:

1. **Given** the compose stack, **When** it starts, **Then** the processor, payment-initiator, merchant-simulator, and reference-cache-sink roles each run under their Spring profile.
2. **Given** the reference-cache-sink role, **When** it starts, **Then** JDBC autoconfiguration is disabled and it holds no database connection.
3. **Given** the processor role, **When** it starts, **Then** it is the only role that opens a MariaDB data source and issues schema/seed/archive SQL.

---

### Edge Cases

- **Database unavailable at startup**: If MariaDB is not yet reachable, the processor role retries connecting rather than crashing the stack; seeding and schema creation proceed once the database is ready.
- **Archive write failure**: A failed durable write leaves the payment in the cache (not evicted) and is retried on the next poll, so a terminal payment is never lost.
- **CDC not registered**: If the Debezium connector is not registered, no reference-change topics are produced; the sink simply receives nothing rather than the application silently dual-writing.
- **Tombstone records**: Null-value CDC records are ignored by the sink.
- **Re-run on populated stores**: If the stores already contain readable seed data the loader skips re-seeding; if existing data is unreadable it resets caches and reloads.
- **Repeated DDL**: On restart, "object already exists" outcomes (MariaDB error 1050 / SQLState 42S01) are recognized so startup continues without error.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST use an external MariaDB database as the durable system of record for reference data (`accounts`, `merchants`) and completed payments; GridGain MUST remain a cache and never the authoritative store.
- **FR-002**: The external database connection MUST be resolved from configuration (`demo.external-db.type=mariadb`, `demo.external-db.jdbc-url`, `username`, `password`) and a matching `.env.mariadb` file that sets the compose profile and these values.
- **FR-003**: On startup against empty stores the processor MUST create the reference and archive tables using MariaDB-valid column types (e.g. `VARCHAR`, `BIGINT`, `TINYINT`, `DECIMAL`) and seed 100k accounts and the configured number of merchants.
- **FR-004**: Reference-data upserts MUST use MariaDB's `INSERT ... ON DUPLICATE KEY UPDATE` idiom, and repeated DDL MUST recognize the MariaDB "table already exists" outcome (error 1050 / SQLState 42S01) so repeated startups do not fail.
- **FR-005**: Reference data (`accounts`, `merchants`) MUST reach the GridGain cache only through Change Data Capture (Debezium → Kafka → reference-cache sink), never through application-level dual writes.
- **FR-006**: The MariaDB deployment MUST run with row-based binary logging enabled and a dedicated replication user (`dbzuser`) so the Debezium connector can capture changes.
- **FR-007**: The system MUST register a Debezium MariaDB source connector (`paymentsdemo-mariadb-reference`, Debezium `MySqlConnector`) that captures `payments_app.ACCOUNTS` and `payments_app.MERCHANTS` into topics under the `paymentsdemo` prefix.
- **FR-008**: A reference-cache sink MUST consume the `ACCOUNTS`/`MERCHANTS` change topics into the GridGain reference caches, and MUST ignore CDC tombstone (null-value) records without error.
- **FR-009**: The reference-cache sink role MUST run with no web layer and with JDBC data-source autoconfiguration disabled, consuming only from Kafka.
- **FR-010**: Terminal payments MUST be archived asynchronously back to MariaDB on a poll interval (`demo.external-db.archive.poll-interval-ms`), after a captured-retention window (`demo.external-db.archive.captured-retention-ms`), and MUST be evicted from the GridGain cache only after the durable write succeeds.
- **FR-011**: If a durable archive write fails, the payment MUST remain in the cache and archival MUST be retried on a later poll.
- **FR-012**: When a payment is archived, any resulting account-balance debit MUST be applied in the same transaction as the archive write.
- **FR-013**: The dashboard and transaction-flow views MUST merge live cache rows with archived rows in MariaDB so that eviction does not remove payments from totals or history.
- **FR-014**: The payment hot path (authorize/capture/refund) MUST NOT add synchronous external-database round trips; durability is achieved via the asynchronous archive path.
- **FR-015**: The application MUST run as role-separated Spring profiles — `processor`, `payment-initiator`, `merchant-simulator`, and `reference-cache-sink` — where only the processor role opens a MariaDB data source.
- **FR-016**: The demo-data reset tooling (`clear-demo-data.sh`) MUST clear the transient GridGain state and, when requested, the MariaDB archive tables.
- **FR-017**: The whole stack MUST come up from a single Docker Compose command plus `.env.mariadb`, building from source and self-seeding when the stores are empty.
- **FR-018**: Every previously observable behavior (dashboard metrics, transaction-flow view, simulator, merchant outage, fraud threshold) MUST continue to work against the MariaDB topology.

### Key Entities *(include if feature involves data)*

- **MariaDB system of record (`payments_app` schema)**: The durable store holding `ACCOUNTS`, `MERCHANTS`, `PAYMENT_ARCHIVE`, and `LEDGER_ENTRY_ARCHIVE`. Reference tables are the CDC source; archive tables receive terminal payments and their ledger entries.
- **`demo.external-db.*` configuration namespace**: `type` (`mariadb`), `jdbc-url`, `username`, `password`, `archive.{poll-interval-ms, captured-retention-ms}`, and `cdc.{kafka-bootstrap-servers, sink-group-id, topic-prefix, schema-name, connector-name, accounts-topic, merchants-topic}`.
- **System-of-record repository**: A backend-neutral repository contract (`SystemOfRecordRepository`) with a JDBC implementation covering schema init, seed upserts, reference lookups, archived-payment reads/writes, `setMerchantActive`, and reference-table CDC enablement.
- **Reference-cache sink**: A CDC consumer (profile `reference-cache-sink`) that reads the `ACCOUNTS`/`MERCHANTS` change topics and writes into the GridGain reference caches, running with no web layer and no data source.
- **Completed-payment archive service**: A scheduled service that archives terminal payments to MariaDB after the retention window and evicts them from the cache once the write succeeds.
- **MariaDB Debezium assets**: The MariaDB source-connector definition (`mariadb/mariadb-source-connector.json`), its registration script, and the init SQL that creates the `dbzuser` replication user.
- **Runtime roles (Spring profiles)**: `processor` (DB-connected), `payment-initiator`, `merchant-simulator`, `reference-cache-sink`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a fresh MariaDB with empty stores, `docker compose --env-file .env.mariadb up --build` brings the demo up end to end, creating all four tables and seeding 100k accounts and the configured merchants with no manual steps.
- **SC-002**: A reference-data change made directly in MariaDB appears in the GridGain cache (and dashboard) via CDC, with no application dual write involved.
- **SC-003**: Terminal payments appear in `PAYMENT_ARCHIVE`, are evicted from the in-flight cache after their retention window, and remain visible in dashboard totals served from the archive.
- **SC-004**: The payment hot path issues no synchronous external-database call; measured authorize/capture/refund latency is unaffected by archival.
- **SC-005**: Only the processor role holds a MariaDB connection; the reference-cache-sink and simulator roles start and run without a data source.
- **SC-006**: Repeated startups against already-seeded stores do not fail on DDL or seeding.

## Assumptions

- Operators run the reference stack via Docker Compose with `.env.mariadb`; running off-compose requires setting the equivalent `demo.external-db.*` Spring properties by hand.
- The MariaDB service uses the images/credentials configured in compose and is reachable at `mariadb:3306` within the compose network for this increment.
- GridGain remains the live cache and is never the durable source of truth; MariaDB is always the system of record.
- The demo ships no automated test suite; behavior is validated by exercising the UI and HTTP endpoints.
- Reference tables and CDC topics use the logical names `ACCOUNTS` and `MERCHANTS`; archive tables are `PAYMENT_ARCHIVE` and `LEDGER_ENTRY_ARCHIVE`.
