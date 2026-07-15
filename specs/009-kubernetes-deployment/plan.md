# Implementation Plan: Kubernetes Deployment

**Branch**: `009-kubernetes-deployment` | **Date**: 2026-07-15 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/009-kubernetes-deployment/spec.md`

**Note**: This is a forward-looking plan for work not yet built (Status: Draft). It proposes how to deploy the existing demo on Kubernetes without changing application behavior.

## Summary

Add a Kubernetes deployment for the demo as an alternative to Docker Compose. The existing application image and Spring profiles are reused unchanged; the compose topology is translated into Kubernetes workloads. Stateful tiers (GridGain, MariaDB/MaxScale, Kafka/ZooKeeper) become StatefulSets with headless Services and PersistentVolumeClaims; stateless roles (processor, payment initiator, merchant simulators, reference-cache sink) and Kafka Connect become Deployments; the Debezium connector is registered by a Kubernetes Job that waits on Connect readiness. GridGain discovery is switched from static hostname addresses to the Kubernetes IP finder via a headless Service. Configuration moves to ConfigMaps, credentials to Secrets, and the registry pull to an image-pull Secret. The dashboard is exposed through an Ingress (with a port-forward fallback). Manifests are organized with Kustomize so `kubectl apply -k` is the single deploy command.

## Technical Context

**Language/Version**: Kubernetes manifests (YAML) targeting a current stable API (apps/v1, batch/v1, networking.k8s.io/v1); Kustomize for organization. Application unchanged (Java 17 / Spring Boot image).

**Primary Dependencies**: Kubernetes cluster (kind/minikube for local validation); a default StorageClass; an ingress controller or load-balancer for external access; the GridGain Kubernetes IP finder (`ignite-kubernetes` / `TcpDiscoveryKubernetesIpFinder`); the existing application image; the MariaDB Enterprise, MaxScale, Confluent Kafka/ZooKeeper, and Debezium Connect images.

**Storage**: PersistentVolumeClaims (via `volumeClaimTemplates` on StatefulSets) for MariaDB nodes, Kafka, and GridGain. MariaDB remains the system of record; GridGain remains the live cache.

**Testing**: No automated test suite. Validation is by `kubectl` inspection (pod/cluster state) plus exercising the dashboard and endpoints, per the constitution.

**Target Platform**: Any conformant Kubernetes cluster; validated locally on kind/minikube.

**Project Type**: Deployment/infrastructure addition to an existing Spring Boot web service; no change to source layout beyond GridGain discovery configuration.

**Performance Goals**: Preserve the cache-first hot path — no new synchronous external-database calls introduced by the substrate change.

**Constraints**: Reuse the application image unchanged (only GridGain discovery config may change). Tolerate dependency start-order via probes and retrying Jobs. Single-command deploy must be preserved. Authenticated registry access via image-pull Secret.

**Scale/Scope**: ~One namespace; 3 GridGain nodes; 3 MariaDB nodes + 2 MaxScale; ZooKeeper + Kafka + Connect; 4 application roles (one scalable); 1 connector Job; the supporting ConfigMaps/Secrets/Services/Ingress/PVCs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. External Database Is the System of Record** — PASS. MariaDB remains the durable system of record as a StatefulSet with persistent storage; GridGain stays the cache.
- **II. Change Data Capture, Not Dual Writes** — PASS. Reference data still flows Debezium → Kafka → reference-cache sink; only the hosting substrate changes. The connector is registered by a Job instead of a compose bootstrap container.
- **III. Cache-First Hot Path, Asynchronous Archival** — PASS. Application behavior is unchanged; archival and eviction are unaffected by the deployment change.
- **IV. Pluggable Infrastructure Behind Configuration** — PASS. Kubernetes is added as a new deployment target through manifests and configuration (ConfigMaps/Secrets), not by forking application code. The single application-facing change — GridGain discovery — is itself configuration-driven (IP finder selected by profile/property).
- **V. Observable, Demonstrable Behavior** — PASS. The dashboard, flow view, and operator levers remain the demonstration surface, exposed via Ingress.
- **VI. Reproducible One-Command Local Stack** — PASS WITH NOTE (extension). The constitution names Docker Compose as the reference runtime. This feature does not replace Compose; it adds a second reproducible one-command path (`kubectl apply -k`). The extension is recorded in Complexity Tracking.

No blocking violations. One noted extension to Principle VI.

## Project Structure

### Documentation (this feature)

```text
specs/009-kubernetes-deployment/
├── plan.md              # This file
├── spec.md              # Feature specification
└── tasks.md             # Dependency-ordered task list
```

### Source Code (repository root)

```text
.
├── k8s/                                          # NEW: Kubernetes manifests (Kustomize)
│   ├── kustomization.yaml                        # Single entrypoint: kubectl apply -k k8s/
│   ├── namespace.yaml                            # Demo namespace
│   ├── config/
│   │   ├── app-config.yaml                       # ConfigMap: application settings (demo.external-db.*, cdc.*)
│   │   ├── maxscale-config.yaml                  # ConfigMap: maxscale.cnf
│   │   ├── mariadb-config.yaml                   # ConfigMap: db node .cnf files
│   │   ├── connector-config.yaml                # ConfigMap: mariadb-source-connector.json
│   │   ├── secrets.yaml                          # Secret: DB + dbzuser credentials (templated/sealed)
│   │   └── registry-pull-secret.yaml            # Secret: docker.mariadb.com image pull
│   ├── gridgain/
│   │   ├── statefulset.yaml                      # 3 nodes + volumeClaimTemplates
│   │   ├── service-headless.yaml                # Headless Service for K8s IP finder discovery
│   │   └── rbac.yaml                             # ServiceAccount/Role/RoleBinding for IP finder pod lookup
│   ├── mariadb/
│   │   ├── statefulset.yaml                      # db1..db3 Galera nodes + PVCs
│   │   ├── service-headless.yaml
│   │   ├── maxscale-deployment.yaml             # MaxScale (2 replicas)
│   │   └── maxscale-service.yaml               # SQL listener + admin endpoints
│   ├── kafka/
│   │   ├── zookeeper-statefulset.yaml
│   │   ├── kafka-statefulset.yaml
│   │   ├── services.yaml
│   │   ├── connect-deployment.yaml             # Kafka Connect (Debezium)
│   │   └── connector-register-job.yaml         # Job: register paymentsdemo-mariadb-reference
│   ├── app/
│   │   ├── processor-deployment.yaml            # dashboard (processor profile)
│   │   ├── processor-service.yaml
│   │   ├── initiator-deployment.yaml
│   │   ├── merchant-simulator-deployment.yaml   # scalable via replicas
│   │   └── reference-cache-sink-deployment.yaml
│   └── ingress.yaml                              # Dashboard ingress (+ documented port-forward fallback)
└── src/main/resources/
    └── application-kubernetes.yml                # NEW: profile enabling GridGain K8s IP finder discovery
```

**Structure Decision**: Raw Kubernetes manifests organized with Kustomize under `k8s/`, grouped by tier. Kustomize (over a templating tool) keeps the manifests transparent and applies with one built-in command; a Helm chart is noted as an alternative in Complexity Tracking. The only source change is a new Spring profile (`kubernetes`) that selects the GridGain Kubernetes IP finder.

## Key Technical Decisions (proposed)

- **StatefulSets for stateful tiers**: GridGain, MariaDB (Galera), Kafka, and ZooKeeper run as StatefulSets with `volumeClaimTemplates` and headless Services, giving stable pod DNS and per-pod persistent volumes so restarts rejoin with data.
- **GridGain discovery via Kubernetes IP finder**: Replace static `discovery-addresses` with `TcpDiscoveryKubernetesIpFinder` pointed at the GridGain headless Service, selected by a `kubernetes` Spring profile. This is the single application-facing change and requires an RBAC role so nodes can list endpoints.
- **Connector registration as a Job**: A `batch/v1` Job (with `backoffLimit`/retry) waits for Kafka Connect readiness and PUTs the Debezium MariaDB connector config, replacing the compose bootstrap container.
- **Deployments for stateless roles**: The processor, payment initiator, merchant simulators, and reference-cache sink are Deployments differentiated by `SPRING_PROFILES_ACTIVE`; merchant simulators scale by replica count.
- **Config and secrets as first-class objects**: ConfigMaps carry MaxScale/MariaDB configs, the connector definition, and application settings; Secrets carry credentials; an image-pull Secret authenticates to `docker.mariadb.com`.
- **External access via Ingress with fallback**: The processor Service is fronted by an Ingress; where no ingress controller/load-balancer exists, `kubectl port-forward` is documented as the fallback.
- **Ordering via probes, not sequencing**: Readiness/liveness probes plus the retrying connector Job replace compose `depends_on`, so the deploy is resilient to start order.

## Complexity Tracking

> Recorded for transparency; the Constitution Check passed with one noted extension.

| Decision | Why Needed | Simpler Alternative Rejected Because |
|----------|------------|--------------------------------------|
| Adds a second deployment target alongside Compose (noted extension to Principle VI) | Kubernetes is the requested target; Compose stays the primary reference runtime | Replacing Compose would lose the simplest local path and break the constitution's reference runtime |
| GridGain Kubernetes IP finder + RBAC (one source/profile change) | Static hostname discovery does not translate to Kubernetes pod networking | Hard-coding pod IPs is impossible with dynamic scheduling; a headless-Service DNS hack is less robust than the supported IP finder |
| Kustomize raw manifests rather than a Helm chart | Transparent, reviewable YAML with a one-command built-in apply and no extra tooling | Helm adds templating and a release lifecycle heavier than a demo needs; noted as a future option if parameterization grows |
