---

description: "Task list for deploying the demo on Kubernetes (forward-looking; not yet implemented)"
---

# Tasks: Kubernetes Deployment

**Input**: Design documents from `/specs/009-kubernetes-deployment/`

**Prerequisites**: plan.md (required), spec.md (required for user stories)

**Tests**: This project ships no automated test suite; validation is by `kubectl` inspection plus exercising the dashboard and endpoints. No test tasks are included.

**Status**: These tasks are proposed and NOT yet implemented — all boxes are unchecked.

**Organization**: Tasks are grouped by user story so each is independently deployable and testable. Paths are relative to the repository root.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1–US5)

## Path Conventions

- Kubernetes manifests: `k8s/` (Kustomize base, grouped by tier)
- Application config: `src/main/resources/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Establish the namespace, Kustomize entrypoint, config, and secrets every workload depends on.

- [ ] T001 Create `k8s/namespace.yaml` and `k8s/kustomization.yaml` referencing all resource files so `kubectl apply -k k8s/` is the single deploy command.
- [ ] T002 [P] Add `k8s/config/app-config.yaml` (ConfigMap) with the application settings (`demo.external-db.*`, `demo.external-db.cdc.*`, GridGain and role settings) mirroring the Compose defaults.
- [ ] T003 [P] Add `k8s/config/maxscale-config.yaml` and `k8s/config/mariadb-config.yaml` (ConfigMaps) carrying `maxscale.cnf` and the db node `.cnf` files.
- [ ] T004 [P] Add `k8s/config/connector-config.yaml` (ConfigMap) carrying `mariadb-source-connector.json`.
- [ ] T005 [P] Add `k8s/config/secrets.yaml` (Secret) for MariaDB + `dbzuser` credentials and `k8s/config/registry-pull-secret.yaml` (image-pull Secret) for `docker.mariadb.com`; document how operators supply real values.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The one application-facing change — Kubernetes-native GridGain discovery — that the cache tier depends on.

**⚠️ CRITICAL**: US1 and US3 cannot succeed until this is complete.

- [ ] T006 Add `src/main/resources/application-kubernetes.yml` selecting the GridGain `TcpDiscoveryKubernetesIpFinder` (namespace + headless service name), and confirm the `kubernetes` profile is activated on the GridGain and client workloads.
- [ ] T007 Add `k8s/gridgain/rbac.yaml` (ServiceAccount + Role + RoleBinding) granting the GridGain pods permission to list the endpoints the IP finder queries.

**Checkpoint**: Discovery configuration and RBAC exist — the cache tier can form a cluster once deployed.

---

## Phase 3: User Story 1 - One-command deploy, dashboard reachable (Priority: P1) 🎯 MVP

**Goal**: Bring the whole stack up with `kubectl apply -k k8s/` and reach the dashboard from outside the cluster.

**Independent Test**: Apply to a clean cluster, wait for readiness, open the dashboard through its external address, and confirm live traffic.

- [ ] T008 [US1] Add `k8s/mariadb/statefulset.yaml` (db1..db3 with `volumeClaimTemplates`), `k8s/mariadb/service-headless.yaml`, `k8s/mariadb/maxscale-deployment.yaml`, and `k8s/mariadb/maxscale-service.yaml` exposing the SQL listener and admin endpoints, with readiness probes.
- [ ] T009 [P] [US1] Add `k8s/kafka/zookeeper-statefulset.yaml`, `k8s/kafka/kafka-statefulset.yaml`, and `k8s/kafka/services.yaml` with persistent logs and readiness probes.
- [ ] T010 [P] [US1] Add `k8s/kafka/connect-deployment.yaml` (Kafka Connect with the Debezium plugin) and its Service.
- [ ] T011 [US1] Add `k8s/gridgain/statefulset.yaml` (3 nodes + `volumeClaimTemplates`, `kubernetes` profile, the RBAC ServiceAccount) and `k8s/gridgain/service-headless.yaml`.
- [ ] T012 [US1] Add `k8s/app/processor-deployment.yaml` (processor profile, reads app ConfigMap + Secrets, joins GridGain as client) and `k8s/app/processor-service.yaml`.
- [ ] T013 [P] [US1] Add `k8s/app/initiator-deployment.yaml`, `k8s/app/merchant-simulator-deployment.yaml`, and `k8s/app/reference-cache-sink-deployment.yaml`, each set by `SPRING_PROFILES_ACTIVE`.
- [ ] T014 [US1] Add `k8s/ingress.yaml` exposing the processor Service; document the `kubectl port-forward` fallback in `README.md`.
- [ ] T015 [US1] Verify end to end: apply to a clean cluster, wait for readiness, confirm schema creation + seeding on empty MariaDB and live dashboard traffic.

**Checkpoint**: The demo deploys with one command and the dashboard is reachable.

---

## Phase 4: User Story 2 - Stateful components survive pod restarts (Priority: P2)

**Goal**: Ensure MariaDB, Kafka, and GridGain retain data and identity across pod restarts.

**Independent Test**: Delete a MariaDB pod and a GridGain pod; confirm each rejoins with prior data and the demo continues.

- [ ] T016 [US2] Confirm `volumeClaimTemplates` and reclaim policy on the MariaDB, Kafka, and GridGain StatefulSets bind stable PVCs; set appropriate `StorageClass`/size and document the default-StorageClass requirement.
- [ ] T017 [US2] Add liveness/readiness probes and `podManagementPolicy`/update strategy so a restarted stateful pod rejoins rather than reinitializing.
- [ ] T018 [US2] Verify: delete one MariaDB pod and one GridGain node pod, let them reschedule, and confirm data is intact and clusters are not split.

**Checkpoint**: Stateful tiers survive restarts without data loss.

---

## Phase 5: User Story 3 - GridGain forms one cluster via K8s discovery (Priority: P2)

**Goal**: The GridGain nodes discover each other through the Kubernetes IP finder and form a single cluster.

**Independent Test**: Query cluster topology (or observe cache behavior) and confirm all nodes are in one cluster.

- [ ] T019 [US3] Validate the `TcpDiscoveryKubernetesIpFinder` config (T006) against the headless Service (T011) and RBAC (T007); confirm nodes resolve peers by endpoint lookup.
- [ ] T020 [US3] Verify: after deploy, the GridGain cluster reports the configured node count as one cluster, and the processor/sink clients join it.

**Checkpoint**: A single GridGain cluster forms regardless of pod IPs.

---

## Phase 6: User Story 4 - CDC connector registered automatically (Priority: P2)

**Goal**: A Job registers the Debezium MariaDB connector after Connect is ready, restoring CDC reference-data flow.

**Independent Test**: Change a merchant flag in MariaDB and confirm it reaches the GridGain cache/dashboard.

- [ ] T021 [US4] Add `k8s/kafka/connector-register-job.yaml` (batch/v1, `backoffLimit`/retry) that waits for Kafka Connect readiness and PUTs the connector config from the connector ConfigMap to register `paymentsdemo-mariadb-reference`.
- [ ] T022 [US4] Verify: the Job reaches success, the connector is running, and a reference-row change in MariaDB projects into GridGain via the reference-cache sink with no dual write.

**Checkpoint**: CDC is wired automatically at deploy time.

---

## Phase 7: User Story 5 - Config, secrets, and scale via K8s primitives (Priority: P3)

**Goal**: Idiomatic configuration, credentials, and simulator scaling.

**Independent Test**: Scale the simulator workload and change a ConfigMap value; confirm both take effect.

- [ ] T023 [P] [US5] Ensure every workload reads non-secret settings from ConfigMaps and credentials from Secrets, with no literal credentials in manifests.
- [ ] T024 [P] [US5] Confirm the merchant-simulator Deployment scales by `replicas` and document the scale command; confirm the image-pull Secret is referenced by the MariaDB/MaxScale workloads.
- [ ] T025 [US5] Verify: scale simulators up/down and observe pod count and dashboard traffic change; roll a workload after a ConfigMap edit and confirm the new value applies.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Documentation and clean teardown.

- [ ] T026 [P] Update `README.md` with a Kubernetes section: prerequisites (cluster, StorageClass, ingress/port-forward, registry pull secret), the `kubectl apply -k k8s/` command, and access instructions.
- [ ] T027 Verify clean teardown: delete the namespace and confirm all workloads are removed and volumes are released per reclaim policy, so a redeploy starts clean.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately.
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS US1/US3 (GridGain discovery + RBAC).
- **User Stories (Phase 3–7)**:
  - US1 (one-command deploy) is the MVP and lands first; it stands up every tier.
  - US2 (persistence) hardens the StatefulSets US1 introduced.
  - US3 (discovery) validates the cache tier forms one cluster.
  - US4 (connector Job) restores CDC wiring.
  - US5 (config/scale ergonomics) polishes operability.
- **Polish (Phase 8)**: Docs and teardown after the stack works.

### Parallel Opportunities

- Config/secret ConfigMaps T002–T005 are independent files.
- Within US1, the Kafka tier (T009/T010) and the stateless app roles (T013) are parallel with the MariaDB tier (T008).
- The ergonomics checks T023/T024 are parallel.

---

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1 Setup (namespace, Kustomize, config, secrets).
2. Phase 2 Foundational (GridGain K8s discovery + RBAC).
3. Phase 3 US1 (all tiers + ingress) → `kubectl apply -k k8s/` brings up the dashboard.
4. **STOP and VALIDATE** on a clean local cluster (kind/minikube).

### Incremental Delivery

1. Setup + Foundational → discovery and config in place.
2. US1 → whole stack deploys with one command (MVP).
3. US2 → stateful tiers survive restarts.
4. US3 → GridGain forms one cluster reliably.
5. US4 → CDC connector auto-registered.
6. US5 → config/secrets/scale ergonomics.
7. Polish → docs + clean teardown.

### Notes

- Reuse the existing application image unchanged; the only source addition is the `kubernetes` Spring profile for GridGain discovery.
- Preserve the cache-first hot path and CDC-only reference projection (constitution Principles II and III).
- Compose remains the primary reference runtime; this adds a second one-command path (`kubectl apply -k k8s/`).
- Validation is manual via `kubectl` and the dashboard, since the project ships no automated tests.
