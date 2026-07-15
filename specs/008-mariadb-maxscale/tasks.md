---
description: "Task list for MariaDB MaxScale Deployment"
---

# Tasks: MariaDB MaxScale Deployment

**Input**: Design documents from `/specs/008-mariadb-maxscale/`

**Prerequisites**: plan.md (required), spec.md (required for user stories)

**Tests**: The project ships no automated test suite; validation is by exercising the running
stack. No test tasks are included.

**Organization**: Tasks are grouped by user story so each story can be implemented and verified
independently. These tasks reverse-engineer the change delivered in commit 6ec1571.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Exact file paths are given relative to the repository root

## Path Conventions

- Repository root holds `docker-compose.yml`, `.env.mariadb`, and the `mariadb/`, `maxscale/`,
  `gridgain/`, and `src/main/resources/` directories referenced below.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Establish images, credentials, and the shared network the new topology relies on.

- [X] T001 Add MariaDB Enterprise/MaxScale image and credential variables to `.env.mariadb`
  (`MARIADB_SERVER_IMAGE`, `MAXSCALE_IMAGE`, `MARIADB_ROOT_PASSWORD`, `MARIADB_APP_PASSWORD`).
- [X] T002 Declare explicit `gridgain` and `gridgain-cc` Docker networks in `docker-compose.yml`
  and attach existing services to the `gridgain` network.
- [X] T003 [P] Note the `docker.mariadb.com` registry-login precondition for the MariaDB
  profile in `README.md`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Stand up the three-node Galera cluster that every downstream story depends on.

**⚠️ CRITICAL**: MaxScale, the app repointing, and CDC cannot work until the cluster forms.

- [X] T004 [P] Create `mariadb/db1.cnf` with `server_id=101`, row-based binlog settings, and
  Galera `wsrep` config (`wsrep_node_name=db1`, `gcomm://db1,db2,db3`, `rsync` SST).
- [X] T005 [P] Create `mariadb/db2.cnf` (`server_id=102`, `wsrep_node_name=db2`), otherwise
  matching the shared Galera cluster settings.
- [X] T006 [P] Create `mariadb/db3.cnf` (`server_id=103`, `wsrep_node_name=db3`), otherwise
  matching the shared Galera cluster settings.
- [X] T007 Replace the single `mariadb` service in `docker-compose.yml` with `db1`, `db2`,
  `db3` services: mount each node's `.cnf` at `/etc/my.cnf.d/60-galera.cnf`, add TCP
  healthchecks, and give each its own data volume.
- [X] T008 Bootstrap the cluster: give `db1` `--wsrep-new-cluster`, make `db2`/`db3` depend on
  `db1` being healthy in `docker-compose.yml`.
- [X] T009 Replace the `mariadb-data` volume with `db1-data`, `db2-data`, `db3-data` in the
  `volumes:` section of `docker-compose.yml`.

**Checkpoint**: The three-node Galera cluster forms and passes healthchecks under the `mariadb`
profile.

---

## Phase 3: User Story 1 - Application processes payments through MaxScale (Priority: P1) 🎯 MVP

**Goal**: Front the cluster with MaxScale and repoint the application and reset tooling at it so
payments are processed and archived through the MaxScale SQL listener.

**Independent Test**: Start with `.env.mariadb`, run the simulator, and confirm terminal
payments are archived and queryable through `localhost:4006`.

### Implementation for User Story 1

- [X] T010 [US1] Create `maxscale/maxscale.cnf` defining `[maxscale]` admin settings, the three
  `[db1]/[db2]/[db3]` servers, the `[rw-service]` `readwritesplit` service, and the
  `[rw-listener]` MariaDBClient listener on port 3306.
- [X] T011 [US1] Provision MaxScale users in `mariadb/init/003_create_maxscale_users.sql`:
  `maxscale_service` with the auth-lookup `SELECT` grants on `mysql.*`, `SHOW DATABASES`, and
  `SET USER` (the monitor user is completed in US3/T017).
- [X] T012 [US1] Add the `maxscale1` service to `docker-compose.yml`: mount `maxscale.cnf`,
  depend on all three DB healthchecks, publish `4006:3306` and `8989:8989`, add a
  `/v1/servers` healthcheck and `restart: on-failure`.
- [X] T013 [US1] Repoint the application in `src/main/resources/application.yml` to
  `jdbc:mariadb://maxscale1:3306/payments_app` and the parameterized app password.
- [X] T014 [US1] Repoint the app service env in `.env.mariadb`
  (`DEMO_EXTERNAL_DB_JDBC_URL` → `maxscale1`, `DEMO_EXTERNAL_DB_PASSWORD`).
- [X] T015 [US1] Update `gridgain/clear-demo-data.sh` to clear archive tables through host
  `maxscale1` using a DB client service (`db1`) with `--protocol=TCP --ssl=0`, replacing the
  single-`mariadb`-container path.

**Checkpoint**: The application connects through MaxScale, seeds data, and archives terminal
payments to the cluster; the reset script clears archives through MaxScale.

---

## Phase 4: User Story 2 - CDC continues to project reference data via MaxScale (Priority: P2)

**Goal**: Keep the Debezium reference-data pipeline working with MaxScale in front of the
cluster.

**Independent Test**: Register the connector and confirm `ACCOUNTS`/`MERCHANTS` change events
reach the expected Kafka topics with `database.hostname=maxscale1`.

### Implementation for User Story 2

- [X] T016 [P] [US2] Repoint the Debezium connector in
  `mariadb/mariadb-source-connector.json` to `database.hostname=maxscale1` and update the
  `dbzuser` password.
- [X] T017 [P] [US2] Update `mariadb/init/001_create_debezium_user.sql` to the new `dbzuser`
  password consistent with the connector config.
- [X] T018 [US2] Update the connector-registrar service in `docker-compose.yml` to wait on
  `maxscale1` being healthy (and the app being started) before registering.

**Checkpoint**: CDC captures reference-data changes through MaxScale and GridGain still
receives projections.

---

## Phase 5: User Story 3 - Operator reaches the MaxScale admin interface (Priority: P3)

**Goal**: Expose MaxScale monitoring and a second admin/listener entry point on the host.

**Independent Test**: Open `http://localhost:8989` and `http://localhost:8990` and confirm both
admin interfaces respond and list `db1`, `db2`, `db3`.

### Implementation for User Story 3

- [X] T019 [US3] Complete the `galera-monitor` and its `maxscale_monitor` user: add the
  `[galera-monitor]` `galeramon` section (2s interval) in `maxscale/maxscale.cnf` and the
  `maxscale_monitor` user with `REPLICA MONITOR` in `mariadb/init/003_create_maxscale_users.sql`.
- [X] T020 [US3] Add the second `maxscale2` service to `docker-compose.yml`: mount the same
  `maxscale.cnf`, depend on the DB healthchecks, publish `4007:3306` and `8990:8989`, add its
  healthcheck and `restart: on-failure`.

**Checkpoint**: Both MaxScale admin interfaces are reachable from the host and report the three
monitored servers.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Remove obsolete components and update user-facing docs for the new topology.

- [X] T021 [P] Remove the Metabase service from `docker-compose.yml` and delete
  `mariadb/init/002_create_metabase_database.sql`.
- [X] T022 [P] Update `README.md` to describe the three-node Galera cluster, MaxScale SQL
  listeners (`4006`/`4007`), the admin/UI ports (`8989`/`8990`), and the MaxScale-fronted JDBC
  URL.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately.
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS all user stories (the cluster must form
  before anything can route to it).
- **User Story 1 (Phase 3)**: Depends on Foundational. This is the MVP.
- **User Story 2 (Phase 4)**: Depends on Foundational; benefits from US1's MaxScale service but
  is independently testable once MaxScale is up.
- **User Story 3 (Phase 5)**: Depends on Foundational and reuses the MaxScale config from US1.
- **Polish (Phase 6)**: Depends on the profile being reshaped (Phases 2–5).

### User Story Dependencies

- **US1 (P1)**: Needs the cluster (Phase 2). No dependency on US2/US3.
- **US2 (P2)**: Needs the cluster and a running MaxScale host; independently testable.
- **US3 (P3)**: Needs the cluster; extends the MaxScale config; independently testable.

### Within Each User Story

- Config files before the compose services that mount them.
- Cluster healthy before MaxScale services start.
- Story complete and verifiable before moving to the next priority.

### Parallel Opportunities

- T003 can run alongside T001/T002 (different files).
- T004, T005, T006 (the three per-node `.cnf` files) are fully parallel.
- T016 and T017 are parallel (connector JSON vs. init SQL).
- T021 and T022 (Metabase removal vs. README) are parallel.

---

## Parallel Example: Phase 2 Foundational

```bash
# Create the three per-node Galera configs together:
Task: "Create mariadb/db1.cnf (server_id=101, wsrep_node_name=db1)"
Task: "Create mariadb/db2.cnf (server_id=102, wsrep_node_name=db2)"
Task: "Create mariadb/db3.cnf (server_id=103, wsrep_node_name=db3)"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (images, credentials, network).
2. Complete Phase 2: Foundational (three-node Galera cluster forms).
3. Complete Phase 3: User Story 1 (MaxScale in front; app + reset repointed).
4. **STOP and VALIDATE**: Start with `.env.mariadb`, run the simulator, confirm payments archive
   through `localhost:4006`.
5. Demo the MariaDB profile end to end.

### Incremental Delivery

1. Setup + Foundational → cluster ready.
2. Add US1 → app processes payments through MaxScale → demo (MVP).
3. Add US2 → CDC flows through MaxScale → verify Kafka topics.
4. Add US3 → admin interfaces + second instance reachable → verify UIs.
5. Polish → drop Metabase, update README.

---

## Notes

- [P] tasks = different files, no dependencies.
- [Story] label maps each task to its user story for traceability.
- No automated tests exist; verify each story by exercising the running stack (constitution
  principle V/development workflow).
- Preserve the one-command bring-up (`docker compose --env-file .env.mariadb up --build`).
