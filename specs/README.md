# GridGain Payments Demo — Specifications

This directory reverse-engineers the [payments-demo](https://github.com/ianruffell/payments-demo)
project into GitHub Spec Kit feature specifications. **Specs 001–008 each map to one commit**,
derived from that commit's actual diff and post-commit source and scoped to only what the commit
introduced or changed. Each folder contains `spec.md` (what/why), `plan.md` (how), and `tasks.md`
(the dependency-ordered work that would reproduce it).

The reverse-engineered specs document already-delivered work, so they carry **Status: Delivered**
with the commit's author date as their `Created` date. **Forward-looking specs** (new work not yet
built) carry **Status: Draft** and are listed separately below. The durable principles they all
share are recorded in [`../.specify/memory/constitution.md`](../.specify/memory/constitution.md).

> **Note on numbering:** Spec numbers are sequential with no gaps and do not necessarily match
> chronological commit order. The `Commit` column below is the source of truth for provenance.

## Spec → commit map

| Spec | Commit | Date | Commit subject |
|------|--------|------|----------------|
| [001-gridgain-payments-baseline](001-gridgain-payments-baseline/) | `879e8d9` | 2026-04-17 | Add GridGain payments demo |
| [002-containerized-async-merchant](002-containerized-async-merchant/) | `90165ee` | 2026-05-07 | containerize async merchant processing demo |
| [003-transaction-flow-dashboard](003-transaction-flow-dashboard/) | `2dba980` | 2026-05-08 | add transaction flow dashboard and async simulator controls |
| [004-flow-throughput-charts](004-flow-throughput-charts/) | `2e55a29` | 2026-05-20 | Add flow throughput charts and reset helper |
| [005-mariadb-external-database](005-mariadb-external-database/) | `1e261f9` | 2026-05-21 | Add MariaDB external database option |
| [006-mariadb-config-fix](006-mariadb-config-fix/) | `dd598e0` | 2026-05-21 | fix: configure MariaDB as the external database type |
| [007-ai-investigation](007-ai-investigation/) | `2b646b8` | 2026-06-09 | add AI Investigation features |
| [008-mariadb-maxscale](008-mariadb-maxscale/) | `6ec1571` | 2026-07-08 | Add MariaDB MaxScale deployment |

## Forward-looking specs

New work not tied to an existing commit. Status: Draft.

| Spec | Status | Summary |
|------|--------|---------|
| [009-kubernetes-deployment](009-kubernetes-deployment/) | Draft | Deploy the whole stack on Kubernetes (StatefulSets, Services, ConfigMaps/Secrets, a connector-registration Job) as an alternative to Docker Compose |
| [010-observability-prometheus-grafana](010-observability-prometheus-grafana/) | Draft | Prometheus + Grafana metrics for MariaDB, GridGain, and Debezium plus the payments-flow stages, with a dedicated dashboard for each |
| [011-ai-fraud-detection](011-ai-fraud-detection/) | Draft | Real-time AI fraud gate before merchant dispatch, driven by a per-customer context (profile + purchase history) held only in GridGain and updated after each payment |
| [012-initiator-resilience](012-initiator-resilience/) | Delivered | Guard the payment initiator's ticker so a transient error during a pod roll can't wedge it — the payment flow self-heals instead of needing a manual restart |

### Not specified

Some commits are intentionally not represented as specs:

| Commit | Date | Reason |
|--------|------|--------|
| `41b88fc` | 2026-05-08 | "remove unused mysql debezium pieces" — cleanup increment, excluded |
| `a4c5ab5` | 2026-05-11 | External-database CDC + system-of-record foundation — its architecture is consolidated into spec 005 rather than given a separate spec |
| `5de59a5` | 2026-06-09 | Merge pull request #2 — no unique changes |
| `f189ced` | 2026-07-08 | Merge branch 'maxscale' — no unique changes |
| `772bed6` | 2026-07-08 | "Add Control Center compose file" — excluded |

## The arc, in one paragraph

The project starts as an **embedded, in-memory GridGain** payments engine with a browser
dashboard (001), is then **containerized and split into an asynchronous merchant-processing**
topology (002) with a **live transaction-flow view** (003). Observability and repeatability
improve with **throughput charts and a reset helper** (004). An external **MariaDB database
becomes the durable system of record** — reference data projected into GridGain via CDC,
terminal payments archived asynchronously and evicted from the cache (005) — with a
**follow-up fix** to the MariaDB defaults (006). **AI Investigation** adds semantic payment
search over MariaDB vector search (007). Finally the MariaDB backend is hardened into a
**MaxScale-fronted cluster** (008). Forward-looking specs then propose deploying the same stack on
**Kubernetes** (009), adding a **Prometheus + Grafana observability layer** with a dashboard per
infrastructure tier and for the payments flow (010), and a **real-time AI fraud gate** that scores
each payment against a per-customer context held in GridGain before it reaches the merchant (011).

## Regenerating or extending

- The source at the final commit is checked out under [`../payments-demo/`](../payments-demo/)
  for reference.
- To continue the workflow on any spec with the installed Spec Kit skills: `/speckit-plan`,
  `/speckit-tasks`, `/speckit-analyze`, then `/speckit-implement`.
