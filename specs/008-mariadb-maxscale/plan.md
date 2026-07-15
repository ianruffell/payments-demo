# Implementation Plan: MariaDB MaxScale Deployment

**Branch**: `008-mariadb-maxscale` | **Date**: 2026-07-08 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/008-mariadb-maxscale/spec.md`

**Note**: This plan reverse-engineers the technical approach actually taken in commit
6ec1571; it documents the delivered design rather than proposing a new one.

## Summary

Replace the single-node MariaDB backend of the `mariadb` compose profile with a three-node
MariaDB Galera cluster (`db1`, `db2`, `db3`) fronted by two MariaDB MaxScale instances. Each
node is configured from a per-node `.cnf` file carrying its `wsrep`/`server_id` identity;
MaxScale is configured from a single `maxscale/maxscale.cnf` that defines the three servers, a
`galeramon` monitor, a `readwritesplit` service, and a MariaDB client listener. MaxScale
service and monitor users are provisioned via a new init script. The application, the Debezium
connector, and the reset script are repointed from the old `mariadb` host to the MaxScale host
`maxscale1`. SQL listeners are published on host ports 4006/4007 and the admin interfaces on
8989/8990. Credentials move into `.env.mariadb`, Metabase is dropped from the profile, and a
shared explicit `gridgain` network is declared.

## Technical Context

**Language/Version**: Java 11+ (Spring Boot) for the processor; no application Java code
changed in this increment — the change is deployment/configuration only.

**Primary Dependencies**: MariaDB Enterprise server image (`docker.mariadb.com/enterprise-server`),
MariaDB MaxScale image (`docker.mariadb.com/maxscale`), Galera (`libgalera_enterprise_smm.so`),
Debezium MySQL source connector, Kafka/Kafka Connect, GridGain 8.

**Storage**: External MariaDB Galera cluster is the system of record for `payments_app`
(reference data + terminal payments). Three named Docker volumes: `db1-data`, `db2-data`,
`db3-data` (replacing the former single `mariadb-data`).

**Testing**: No automated test suite (per constitution). Validation is by exercising the demo
through the dashboard/endpoints and by querying the cluster through the MaxScale listener.

**Target Platform**: Docker Compose local stack (Linux containers) on a developer machine.

**Project Type**: Multi-service demo application configured via env files and compose overlays;
this increment touches infrastructure config and one Spring resource file.

**Performance Goals**: Preserve low-latency cache-first payment processing; MaxScale routing
and Galera replication must not introduce synchronous external-DB work on the hot path (archival
remains asynchronous). Galera monitor interval is 2s for fast failover detection.

**Constraints**: Single-command bring-up must be preserved. MaxScale must not serve a listener
against an unformed cluster (healthcheck + `restart: on-failure`). Requires
`docker.mariadb.com` registry credentials.

**Scale/Scope**: Local demo scale — 100k accounts, 10 merchants, three DB nodes, two MaxScale
instances. Scope is limited to the MariaDB profile's topology and the wiring that points at it.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. External Database Is the System of Record** — PASS. The Galera cluster is still the
  durable system of record for reference data and terminal payments; GridGain remains the
  in-flight cache. Making the backend a cluster strengthens the durability of the source of
  truth without changing the roles.
- **II. Change Data Capture, Not Dual Writes** — PASS. Reference data still reaches the cache
  through Debezium → Kafka → GridGain. The only change is the connector's `database.hostname`,
  now `maxscale1`; no application-level dual write is introduced.
- **III. Cache-First Hot Path, Asynchronous Archival** — PASS. The payment hot path still runs
  against GridGain; archival to MariaDB stays asynchronous. Routing through MaxScale does not
  add synchronous external-DB round trips to authorization/capture/refund.
- **IV. Pluggable Infrastructure Behind Configuration** — PASS. The new topology is selected
  entirely by configuration: per-node `.cnf` files, `maxscale.cnf`, `.env.mariadb`, the
  `mariadb` compose profile, and `application.yml`. The change is additive configuration, not a
  code fork.
- **V. Observable, Demonstrable Behavior** — PASS. The MaxScale admin interface (8989/8990)
  adds visibility into routing/cluster state, and existing dashboard/endpoint observability is
  unchanged.
- **VI. Reproducible One-Command Local Stack** — PASS (with a noted precondition). The stack
  still comes up from one `docker compose --env-file .env.mariadb up` command and seeds its own
  data. The added precondition — logging in to `docker.mariadb.com` for Enterprise images — is
  documented in the README.

No principle requires a deviation; the Complexity Tracking section is therefore not required
but is filled to justify the two-instance MaxScale and node count.

## Project Structure

### Documentation (this feature)

```text
specs/008-mariadb-maxscale/
├── plan.md              # This file
├── spec.md              # Feature specification
└── tasks.md             # Task breakdown
```

### Source Code (repository root)

```text
docker-compose.yml                            # db1/db2/db3, maxscale1/maxscale2 services;
                                              #   gridgain + gridgain-cc networks; volumes
.env.mariadb                                  # image + credential vars; JDBC URL → maxscale1

mariadb/
├── db1.cnf                                   # node 1 wsrep/server_id config (NEW)
├── db2.cnf                                   # node 2 wsrep/server_id config (NEW)
├── db3.cnf                                   # node 3 wsrep/server_id config (NEW)
├── init/
│   ├── 001_create_debezium_user.sql          # dbzuser password updated
│   ├── 002_create_metabase_database.sql      # REMOVED (Metabase dropped)
│   └── 003_create_maxscale_users.sql         # MaxScale monitor + service users (NEW)
├── mariadb-source-connector.json             # database.hostname → maxscale1
└── register-mariadb-connector.sh             # (unchanged) connector registration

maxscale/
└── maxscale.cnf                              # servers, galeramon, rw-service, rw-listener (NEW)

gridgain/
└── clear-demo-data.sh                        # clears archive through maxscale1 via db1 client

src/main/resources/
└── application.yml                           # external-db jdbc-url/password → maxscale1
```

**Structure Decision**: This is an infrastructure/configuration increment on the existing
multi-service demo. The concrete directories above are the real ones touched by commit 6ec1571:
`mariadb/` gains three per-node config files and a MaxScale-users init script (and loses the
Metabase init script); a new top-level `maxscale/` holds the routing config; `docker-compose.yml`,
`.env.mariadb`, `gridgain/clear-demo-data.sh`, and `src/main/resources/application.yml` are
repointed at MaxScale. No new source tree or module is introduced.

## Complexity Tracking

> Filled to justify topology choices that go beyond the minimum a "MariaDB backend" needs.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Three DB nodes instead of one | Demonstrate a highly available MariaDB backend and MaxScale routing/failover — the point of the increment | A single node cannot show clustering, monitoring, or route-around-failure behavior |
| Two MaxScale instances (4006/8989 and 4007/8990) | Show more than one SQL entry point / admin endpoint and provide an alternate listener | One instance would work for the app, but a second demonstrates that the routing tier itself is not a single hard-wired endpoint |
| MaxScale service + monitor users with explicit grants | MaxScale authentication and `galeramon` require specific privileges (auth-table `SELECT`, `SET USER`, `REPLICA MONITOR`) | Reusing the app or root user would over-grant and would not model a real MaxScale deployment |
