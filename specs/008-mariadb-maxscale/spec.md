# Feature Specification: MariaDB MaxScale Deployment

**Feature Branch**: `008-mariadb-maxscale`

**Created**: 2026-07-08

**Status**: Delivered

**Input**: Reverse-engineered from commit 6ec1571 — "Add MariaDB MaxScale deployment"

## User Scenarios & Testing *(mandatory)*

<!--
  IMPORTANT: User stories should be PRIORITIZED as user journeys ordered by importance.
  Each user story/journey must be INDEPENDENTLY TESTABLE - meaning if you implement just ONE of them,
  you should still have a viable MVP (Minimum Viable Product) that delivers value.
-->

Prior increments ran the MariaDB profile as a single `mariadb:11.8` container exposed
directly on `localhost:3306`, with the application and the Debezium connector both pointing
straight at that one container. This increment replaces that single node with a three-node
MariaDB Galera cluster fronted by MariaDB MaxScale: the application, the CDC connector, and
the reset tooling now reach MariaDB through MaxScale's SQL listeners rather than a bare
database container. The stories below cover only what this increment changed; the rest of the
demo pipeline is unchanged context.

### User Story 1 - Application processes payments through MaxScale (Priority: P1)

An operator brings up the demo with the MariaDB env file. The payment processor connects to
MariaDB not directly but through a MaxScale SQL listener, and the read/write-split router
forwards its statements to the healthy nodes of a three-node Galera cluster. Reference data
seeds, terminal payments archive, and the reset tooling all flow through MaxScale.

**Why this priority**: This is the core of the increment. If the application cannot open its
JDBC connection through MaxScale and persist terminal payments to the cluster, the MariaDB
profile is broken and no other behavior matters.

**Independent Test**: Start the stack with `.env.mariadb`, run the simulator, and confirm
payments reach terminal state and are archived — verified by querying the `payments_app`
database through the MaxScale listener on `localhost:4006` and seeing archived rows, with the
application's configured JDBC URL pointing at `maxscale1:3306`.

**Acceptance Scenarios**:

1. **Given** the MariaDB profile is up and the three DB nodes are healthy, **When** the
   application starts, **Then** it connects using
   `jdbc:mariadb://maxscale1:3306/payments_app` and seeds 100k accounts and 10 merchants into
   the cluster.
2. **Given** the simulator is generating traffic, **When** payments reach a terminal state,
   **Then** they are archived to the `payments_app` database through MaxScale and become
   visible on any cluster node.
3. **Given** the operator runs `gridgain/clear-demo-data.sh` with `DEMO_EXTERNAL_DB_TYPE=mariadb`,
   **When** the archive-clearing step executes, **Then** it connects through host `maxscale1`
   and deletes rows from `LEDGER_ENTRY_ARCHIVE` and `PAYMENT_ARCHIVE`.

---

### User Story 2 - CDC continues to project reference data via MaxScale (Priority: P2)

The Debezium MySQL source connector reads reference-data changes (`ACCOUNTS`, `MERCHANTS`)
from MariaDB. With MaxScale in front of the cluster, the connector now points at the MaxScale
listener host instead of a single container, and continues to publish change events to Kafka
for the GridGain sink.

**Why this priority**: The demo's cache-population pipeline depends on CDC. Repointing the
connector at MaxScale must not break the existing Debezium → Kafka → GridGain flow, but it is
secondary to the application itself being able to run against the cluster.

**Independent Test**: With the MariaDB profile up, register the connector and confirm change
events for `payments_app.ACCOUNTS` and `payments_app.MERCHANTS` appear on the expected Kafka
topics, using the connector configuration whose `database.hostname` is `maxscale1`.

**Acceptance Scenarios**:

1. **Given** the DB nodes are healthy and MaxScale is up, **When** the MariaDB connector is
   registered, **Then** it connects as `dbzuser` through `maxscale1:3306` and begins capturing
   changes from `payments_app.ACCOUNTS` and `payments_app.MERCHANTS`.
2. **Given** reference data is seeded, **When** a row in `ACCOUNTS` or `MERCHANTS` changes,
   **Then** a corresponding change event is published to the configured Kafka topic.

---

### User Story 3 - Operator reaches the MaxScale admin interface (Priority: P3)

An operator wants to see cluster and routing state. Two MaxScale instances expose a REST/UI
admin interface, reachable on the host so the operator can inspect server and service status
without shelling into a container.

**Why this priority**: Observability of the routing layer is valuable for demoing and
troubleshooting, but the demo still functions if the operator never opens the admin UI, so it
ranks below the data-path stories.

**Independent Test**: With the MariaDB profile up, open `http://localhost:8989` (and
`http://localhost:8990` for the second instance) and confirm the MaxScale admin interface
responds and lists the three servers.

**Acceptance Scenarios**:

1. **Given** the MariaDB profile is up, **When** the operator opens `http://localhost:8989`,
   **Then** the MaxScale admin interface for the first instance responds.
2. **Given** the MariaDB profile is up, **When** the operator opens `http://localhost:8990`,
   **Then** the MaxScale admin interface for the second instance responds.
3. **Given** MaxScale is running, **When** its healthcheck queries `/v1/servers`, **Then** the
   monitored servers `db1`, `db2`, and `db3` are reported.

---

### Edge Cases

- **A single DB node is down**: With three Galera nodes behind the read/write-split router and
  the `galeramon` monitor polling every 2 seconds, MaxScale routes around a failed node so the
  application keeps processing payments as long as the cluster retains quorum.
- **First-node bootstrap**: `db1` starts the cluster (`--wsrep-new-cluster`) while `db2` and
  `db3` join it; the application-facing services wait for the DB nodes' healthchecks before
  starting, so the app never connects before the cluster is ready.
- **MaxScale started before the cluster is healthy**: Each MaxScale instance depends on all
  three DB healthchecks and has its own healthcheck plus `restart: on-failure`, so it retries
  rather than serving a listener against an unformed cluster.
- **Reset tooling and TLS**: The clear-demo-data script connects through MaxScale with
  `--protocol=TCP --ssl=0` from a DB client container, so it works against the listener rather
  than a local socket on a single container.
- **Second MaxScale instance**: A second listener (`localhost:4007`, admin `8990`) provides an
  alternate entry point; the application uses the first instance, but the second remains
  available.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The MariaDB profile MUST run the backend as a three-node MariaDB Galera cluster
  (`db1`, `db2`, `db3`) instead of a single MariaDB container.
- **FR-002**: Each DB node MUST be configured from its own per-node config file
  (`mariadb/db1.cnf`, `mariadb/db2.cnf`, `mariadb/db3.cnf`) carrying a unique `server_id`,
  `wsrep_node_name`, and `wsrep_node_address`, and a shared cluster name and
  `gcomm://db1,db2,db3` address.
- **FR-003**: The DB nodes MUST retain row-based binary logging (`log_bin`, `binlog_format=ROW`,
  `binlog_row_image=FULL`) so the existing Debezium CDC pipeline continues to work.
- **FR-004**: One node (`db1`) MUST bootstrap the cluster with `--wsrep-new-cluster`; the other
  nodes MUST join after `db1` is healthy.
- **FR-005**: The stack MUST run MariaDB MaxScale in front of the cluster, exposing a
  read/write-split service over a MariaDB SQL listener that routes across `db1`, `db2`, `db3`.
- **FR-006**: MaxScale MUST monitor the cluster with the Galera monitor (`galeramon`) using a
  dedicated monitor user, polling at a short interval so node failures are detected quickly.
- **FR-007**: The stack MUST expose two SQL listener entry points on the host —
  `localhost:4006` (via `maxscale1`) and `localhost:4007` (via `maxscale2`).
- **FR-008**: The stack MUST expose the MaxScale admin interface on the host at
  `localhost:8989` (`maxscale1`) and `localhost:8990` (`maxscale2`).
- **FR-009**: MaxScale service users MUST be provisioned in the database via an init script
  (`mariadb/init/003_create_maxscale_users.sql`): a monitor user with replica-monitor
  privilege and a service user with the authentication-related `SELECT` grants on `mysql.*`,
  `SHOW DATABASES`, and `SET USER`.
- **FR-010**: The application MUST connect to MariaDB through MaxScale using
  `jdbc:mariadb://maxscale1:3306/payments_app` rather than directly to a database container.
- **FR-011**: The Debezium MariaDB source connector MUST connect through MaxScale
  (`database.hostname=maxscale1`) rather than to a single database container.
- **FR-012**: The reset tooling (`gridgain/clear-demo-data.sh`) MUST clear archive tables
  through the MaxScale host (`maxscale1`) using a DB client service, rather than executing
  against a single `mariadb` container.
- **FR-013**: Credentials MUST be parameterized through `.env.mariadb`
  (`MARIADB_ROOT_PASSWORD`, `MARIADB_APP_PASSWORD`, image variables) and applied consistently
  across the compose services, the application config, and the init scripts.
- **FR-014**: The MariaDB profile MUST use the MariaDB Enterprise server and MaxScale images
  from `docker.mariadb.com`, selectable via `MARIADB_SERVER_IMAGE` and `MAXSCALE_IMAGE`.
- **FR-015**: Application-facing services in the MariaDB profile (the CDC connector registrar)
  MUST wait for MaxScale to be healthy before starting.
- **FR-016**: The Metabase service and its MariaDB database/user init script
  (`002_create_metabase_database.sql`) MUST be removed from the MariaDB profile, since the
  profile no longer ships Metabase.
- **FR-017**: The whole MariaDB-profile stack MUST still come up from a single Docker Compose
  command with the MariaDB env file, preserving the reproducible one-command local stack.

### Key Entities *(include if feature involves data)*

- **DB node (`db1`, `db2`, `db3`)**: A MariaDB Enterprise server participating in a Galera
  cluster named `mariadb-galera`. Each has a unique `server_id` (101/102/103), a per-node
  config file, its own data volume (`db1-data`/`db2-data`/`db3-data`), and a TCP healthcheck.
- **MaxScale instance (`maxscale1`, `maxscale2`)**: A routing proxy configured from
  `maxscale/maxscale.cnf`. Exposes a read/write-split service (`rw-service`) and a MariaDB
  client listener (`rw-listener`, container port 3306), plus an admin interface on 8989.
- **SQL listener ports**: Host `4006` → `maxscale1:3306`, host `4007` → `maxscale2:3306`.
- **Admin/UI ports**: Host `8989` → `maxscale1:8989`, host `8990` → `maxscale2:8989`.
- **MaxScale monitor user (`maxscale_monitor`)**: Database user granted `REPLICA MONITOR`,
  used by the `galera-monitor`.
- **MaxScale service user (`maxscale_service`)**: Database user granted the authentication
  lookup `SELECT` privileges on `mysql.*` plus `SHOW DATABASES` and `SET USER`, used by
  `rw-service` for client authentication.
- **Galera cluster config**: `wsrep` settings (provider library, cluster name/address, node
  name/address, `rsync` SST) plus InnoDB defaults and `innodb_autoinc_lock_mode=2`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Starting the stack with `.env.mariadb` brings up three healthy DB nodes and at
  least one healthy MaxScale instance, and the application processes payments to terminal state
  through MaxScale in a single compose command.
- **SC-002**: The MaxScale admin interface is reachable from the host at `http://localhost:8989`
  and `http://localhost:8990`, and reports the three monitored servers.
- **SC-003**: The application's terminal payments are durably archived to the `payments_app`
  database and remain queryable through the MaxScale SQL listener on `localhost:4006`.
- **SC-004**: With one DB node stopped while cluster quorum is retained, the application
  continues to process and archive payments through MaxScale without configuration changes.
- **SC-005**: The Debezium connector, registered against `maxscale1`, captures `ACCOUNTS` and
  `MERCHANTS` changes to Kafka, so GridGain still receives reference-data projections.
- **SC-006**: The whole stack still comes up from a single `docker compose --env-file
  .env.mariadb up --build` command, now provisioning the three-node cluster and MaxScale
  without extra manual steps.

## Assumptions

- The operator has Docker credentials for `docker.mariadb.com` (the profile uses MariaDB
  Enterprise images), as noted in the README.
- Galera quorum semantics apply: routing around a failed node assumes a majority of nodes
  remain up; a full cluster restart or loss of quorum is out of scope for this increment.
- The application uses only the first MaxScale instance (`maxscale1`); the second instance is
  provided as an additional entry point and is not load-balanced to by the app.
- The MaxScale admin GUI runs without TLS (`admin_secure_gui = false`) because this is a local
  demo, not a production deployment.
- Existing GridGain, Kafka, Debezium, merchant, initiator, and dashboard behavior is unchanged
  except for the shared `gridgain` Docker network now declared explicitly.
- Metabase is no longer part of the MariaDB profile and its removal is intentional.
