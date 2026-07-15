# Feature Specification: Kubernetes Deployment

**Feature Branch**: `009-kubernetes-deployment`

**Created**: 2026-07-15

**Status**: Draft

**Input**: User description: "add a new spec that deploys this project in Kubernetes rather than docker-compose"

## User Scenarios & Testing *(mandatory)*

Today the whole demo runs from a single Docker Compose command. This feature adds Kubernetes as an alternative deployment target: the same components (GridGain cluster, MariaDB cluster fronted by MaxScale, Kafka + Kafka Connect with the Debezium connector, and the application roles — processor, payment initiator, merchant simulators, reference-cache sink) run as Kubernetes workloads on any conformant cluster. This is a deployment-substrate change only; no application behavior changes. The stories below describe what an operator can do with the Kubernetes deployment.

### User Story 1 - Deploy the whole stack to Kubernetes with one command (Priority: P1)

An operator applies a single set of manifests to a Kubernetes cluster and the entire demo comes up: every component is scheduled as a workload, the components find each other, and the payments dashboard becomes reachable from outside the cluster. On a fresh, empty database the application seeds its own reference data, exactly as it does under Compose.

**Why this priority**: This is the headline capability — without a working one-command deploy there is no feature. It is also the smallest demonstrable slice: apply the manifests to a clean cluster and open the dashboard.

**Independent Test**: On a clean cluster, run the documented single apply command, wait for all workloads to become ready, and open the dashboard through its external address; confirm live payment traffic appears.

**Acceptance Scenarios**:

1. **Given** a conformant Kubernetes cluster and access to the required container images, **When** the operator applies the manifests, **Then** all workloads reach a ready state and the dashboard is reachable from outside the cluster.
2. **Given** an empty MariaDB, **When** the processor starts, **Then** it creates the schema and seeds reference data before serving the dashboard.
3. **Given** the deployment is running, **When** the operator uses the dashboard levers (start/stop simulator, merchant outage, fraud threshold), **Then** the effects are observed live, exactly as under Compose.
4. **Given** the operator wants to remove the demo, **When** they delete the namespace (or the applied resources), **Then** all workloads and their storage are torn down cleanly.

---

### User Story 2 - Stateful components survive pod restarts (Priority: P2)

The operator relies on the durable data — MariaDB (system of record), Kafka (CDC transport), and the GridGain cluster — surviving individual pod restarts and rescheduling. Stateful components run with stable network identities and persistent storage so a restarted pod rejoins with its data intact.

**Why this priority**: A demo that loses its system of record or its Kafka log on the first pod restart is not credible. Durability is essential but layered on the deploy working (US1).

**Independent Test**: Delete one MariaDB pod and one GridGain pod, let them reschedule, and confirm each rejoins its cluster with prior data and the demo continues processing.

**Acceptance Scenarios**:

1. **Given** the stateful components are running, **When** a MariaDB pod is deleted, **Then** it is recreated with the same identity and reattaches its persistent volume, rejoining the cluster without data loss.
2. **Given** the Kafka broker pod restarts, **When** it comes back, **Then** it retains its topic log on persistent storage and CDC resumes.
3. **Given** a GridGain node pod restarts, **When** it comes back, **Then** it rejoins the existing cluster rather than forming a new one.

---

### User Story 3 - GridGain forms one cluster via Kubernetes-native discovery (Priority: P2)

The operator expects the GridGain nodes to discover each other and form a single cluster without the static, hostname-based discovery addresses used under Compose. Discovery uses a Kubernetes-native mechanism so the cluster forms regardless of pod IPs.

**Why this priority**: GridGain's Compose discovery relies on fixed service hostnames and ports that do not translate directly to Kubernetes pod networking; without an adapted discovery mechanism the cache tier will not form a cluster, so this is a distinct, necessary slice.

**Independent Test**: Deploy the GridGain workload, then query cluster topology (or the dashboard's cache behavior) and confirm all nodes are members of one cluster.

**Acceptance Scenarios**:

1. **Given** the GridGain nodes are scheduled, **When** they start, **Then** they discover each other through the Kubernetes-native discovery mechanism and form a single cluster of the expected size.
2. **Given** the processor and reference-cache sink connect as clients, **When** they start, **Then** they join the same cluster and read/write the shared caches.

---

### User Story 4 - CDC connector is registered automatically after Kafka Connect is ready (Priority: P2)

The operator expects reference-data Change Data Capture to be wired up without manual steps. A bootstrap workload waits for Kafka Connect to be ready and registers the Debezium MariaDB connector, after which reference-data changes flow into GridGain.

**Why this priority**: CDC is core to the architecture (reference data reaches the cache via CDC, not dual writes). It depends on Kafka, Connect, and MariaDB being up, so it is sequenced after the stateful tier but is independently observable.

**Independent Test**: After deploy, change a merchant's active flag directly in MariaDB and confirm the change appears in the GridGain cache and on the dashboard, with the connector having been registered automatically.

**Acceptance Scenarios**:

1. **Given** Kafka Connect is ready, **When** the bootstrap workload runs, **Then** the Debezium MariaDB connector (`paymentsdemo-mariadb-reference`) is registered and reaches running state.
2. **Given** the connector is running, **When** a reference row changes in MariaDB, **Then** the change is captured to Kafka and projected into GridGain by the reference-cache sink, with no application dual write.
3. **Given** the bootstrap workload runs before Connect is ready, **When** it cannot reach Connect, **Then** it retries until registration succeeds rather than failing the deploy.

---

### User Story 5 - Configuration, secrets, and scale via Kubernetes primitives (Priority: P3)

The operator manages configuration through ConfigMaps, credentials through Secrets, and merchant-simulator load through a replica count — using standard Kubernetes primitives rather than Compose env files.

**Why this priority**: This is the ergonomics layer that makes the deployment idiomatic and operable, but the demo can run with defaults before it is polished, so it is the lowest priority.

**Independent Test**: Change the merchant-simulator replica count and confirm the number of simulator pods changes and dashboard traffic scales accordingly; change a non-secret config value via its ConfigMap and confirm a rolled pod picks it up.

**Acceptance Scenarios**:

1. **Given** the deployment, **When** the operator scales the merchant-simulator workload, **Then** the simulator pod count changes and the generated traffic scales without editing images.
2. **Given** database and CDC credentials, **When** the workloads start, **Then** they read credentials from Secrets rather than from literals baked into manifests.
3. **Given** the MariaDB/MaxScale images require registry authentication, **When** pods are scheduled, **Then** they pull using an image-pull Secret configured for that registry.
4. **Given** non-secret application settings, **When** they are changed in a ConfigMap and the workload is rolled, **Then** the new values take effect.

---

### Edge Cases

- **Startup ordering**: Components that depend on others (Connect on Kafka, the connector Job on Connect, the processor on MariaDB) must tolerate dependencies not yet being ready — via readiness probes, init containers, or retrying Jobs — rather than crash-looping the whole deploy.
- **Image pull authentication**: The MariaDB and MaxScale images come from an authenticated registry; without a valid image-pull Secret those pods stay in an image-pull error state, which must be surfaced clearly.
- **Storage class availability**: Persistent volume claims depend on a default (or named) StorageClass; on a cluster without one, stateful pods stay Pending until storage is provided.
- **GridGain split-brain on rescheduling**: If discovery is misconfigured, rescheduled nodes could form a separate cluster; discovery must be resilient to changing pod IPs.
- **External access model**: The dashboard's external reachability depends on the cluster's ingress/load-balancer support; on a bare cluster without an ingress controller, a fallback access method (e.g. port-forward or NodePort) must be documented.
- **Namespace teardown**: Deleting the namespace must release persistent volumes according to their reclaim policy so a redeploy starts clean.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST provide Kubernetes manifests that deploy all demo components — GridGain cluster, MariaDB cluster, MaxScale, ZooKeeper, Kafka, Kafka Connect (Debezium), the processor, the payment initiator, the merchant simulators, and the reference-cache sink — as Kubernetes workloads.
- **FR-002**: The system MUST bring the whole stack up from a single documented apply command against a conformant cluster, with no manual per-component steps.
- **FR-003**: Stateful components (MariaDB nodes, Kafka, GridGain nodes) MUST run with stable network identities and persistent storage so restarts and rescheduling do not lose data.
- **FR-004**: GridGain nodes MUST discover each other and form a single cluster using a Kubernetes-native discovery mechanism rather than static hostname/port discovery addresses.
- **FR-005**: The Debezium MariaDB connector MUST be registered automatically by a bootstrap workload that waits for Kafka Connect readiness and retries until registration succeeds.
- **FR-006**: Reference data MUST continue to reach the GridGain cache only through CDC (Debezium → Kafka → reference-cache sink), never through application dual writes.
- **FR-007**: The payment hot path MUST remain cache-first with asynchronous archival to MariaDB; the deployment substrate MUST NOT introduce synchronous external-database calls on the hot path.
- **FR-008**: The processor MUST create the schema and seed reference data on an empty MariaDB during startup, matching the Compose behavior.
- **FR-009**: The payments dashboard MUST be reachable from outside the cluster through a documented access method (ingress, load balancer, or a documented fallback).
- **FR-010**: Inter-component communication MUST use Kubernetes Services for stable addressing; StatefulSets MUST use headless Services where stable per-pod DNS is required.
- **FR-011**: Non-secret configuration MUST be supplied via ConfigMaps and credentials via Secrets; no credential may be hard-coded into a workload manifest.
- **FR-012**: Pulling images from the authenticated MariaDB/MaxScale registry MUST use an image-pull Secret.
- **FR-013**: The merchant-simulator workload MUST be scalable by replica count without image or code changes.
- **FR-014**: Components with dependencies MUST tolerate not-yet-ready dependencies through readiness/liveness probes and retrying bootstrap Jobs, rather than failing the overall deploy.
- **FR-015**: The deployment MUST be removable cleanly (e.g. by deleting the namespace), releasing persistent volumes per their reclaim policy.
- **FR-016**: The application container image and configuration surface MUST be reused unchanged from the existing build; the only application-facing change permitted is GridGain discovery configuration for Kubernetes.
- **FR-017**: All previously observable behavior (dashboard metrics, transaction-flow view, simulator controls, merchant outage, fraud threshold, AI investigation) MUST work identically under Kubernetes.

### Key Entities *(include if feature involves data)*

- **Namespace**: The isolation boundary that holds all demo resources and enables clean teardown.
- **GridGain workload**: A StatefulSet of cache/compute nodes with a headless Service for discovery and persistent storage per node.
- **MariaDB workload**: A StatefulSet of database nodes (the Galera cluster) with per-node persistent volumes, fronted by a MaxScale workload exposing the SQL listener endpoints.
- **Kafka + ZooKeeper workloads**: StatefulSets providing the CDC transport with persistent logs, plus a Kafka Connect Deployment hosting the Debezium runtime.
- **Application workloads**: Deployments for the processor (dashboard), payment initiator, merchant simulators (scalable), and reference-cache sink — the same image run under different Spring profiles.
- **Connector bootstrap Job**: A Job that registers the Debezium MariaDB connector once Kafka Connect is ready.
- **ConfigMaps**: Non-secret configuration — MaxScale config, MariaDB node configs, the connector definition, and application settings.
- **Secrets**: Database and CDC credentials plus the registry image-pull Secret.
- **Services / Ingress**: ClusterIP and headless Services for inter-component addressing, and an ingress/load-balancer (or documented fallback) exposing the dashboard.
- **PersistentVolumeClaims**: Backing storage for MariaDB, Kafka, and GridGain, provisioned from a StorageClass.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A single documented apply command brings the entire demo to a ready state on a clean conformant cluster, with the dashboard reachable from outside the cluster.
- **SC-002**: Deleting a MariaDB pod and a GridGain node pod does not lose data or split the cluster: both rejoin with prior state and the demo keeps processing.
- **SC-003**: The GridGain nodes form exactly one cluster of the configured size after deploy.
- **SC-004**: A reference-data change made directly in MariaDB appears in the GridGain cache (and dashboard) via CDC, with the connector having been registered automatically.
- **SC-005**: Scaling the merchant-simulator workload changes the number of simulator pods and the observed traffic, with no image or code change.
- **SC-006**: Deleting the namespace removes all workloads and releases their volumes, so a fresh redeploy starts clean.
- **SC-007**: Every dashboard, flow-view, and investigation behavior available under Compose is observable under Kubernetes, with no application code change beyond GridGain discovery configuration.

## Assumptions

- Target is any conformant Kubernetes cluster (validated locally on kind/minikube), with a default StorageClass and, for external dashboard access, an ingress controller or load-balancer support; a port-forward fallback is acceptable where those are absent.
- The existing application container image is reused unchanged; components run the same Spring profiles (`processor`, `payment-initiator`, `merchant-simulator`, `reference-cache-sink`) as under Compose.
- The external database remains MariaDB fronted by MaxScale, consistent with the current deployment; GridGain remains the live cache and never the system of record.
- Access to the authenticated MariaDB/MaxScale registry (`docker.mariadb.com`) is available and provided to the cluster via an image-pull Secret.
- The demo ships no automated test suite; the Kubernetes deployment is validated by exercising the dashboard and endpoints and by observing pod/cluster state with `kubectl`.
- Docker Compose remains the primary reference runtime; this feature adds Kubernetes as a second, equivalent one-command deployment target rather than replacing Compose.
