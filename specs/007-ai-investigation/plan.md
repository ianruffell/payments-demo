# Implementation Plan: AI Investigation — Semantic Payment Search

**Branch**: `007-ai-investigation` | **Date**: 2026-06-09 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/007-ai-investigation/spec.md`

## Summary

Add an "AI Investigation" capability that lets an analyst search payments by meaning rather
than by exact field values. Payment activity is turned into short natural-language summaries,
each summary is converted into a fixed-length embedding vector by a self-contained local
embedding model, and the vectors are stored in a MariaDB vector-indexed table. A new
`SemanticInvestigationService` embeds the analyst's query the same way and asks MariaDB for
the nearest payments by cosine distance, returning them ranked with a relevance percentage.
The capability is exposed through a `SemanticInvestigationController` and a new
`investigation.html` / `investigation.js` page. Indexing hooks into the existing archival
flow (archived payments are indexed just before eviction) and each search also re-indexes the
most recent in-flight payments from the cache. Because vector search is a MariaDB feature, the
whole capability degrades to an explicit "unavailable" response when that support is absent.
The commit also ships architecture documentation.

## Technical Context

**Language/Version**: Java 11+ (Spring Boot), static HTML/CSS/JS (no build step).

**Primary Dependencies**: Spring Boot (Web, Validation, JDBC/`JdbcTemplate`), GridGain 8
Ignite client (cache SQL fields queries over the `PAYMENTS`, `MERCHANTS`, `ACCOUNTS` caches),
MariaDB JDBC driver. The embedding model is hand-rolled in `SemanticEmbeddingService` with no
external ML dependency or network call.

**Storage**: MariaDB `PAYMENT_SEMANTIC_INDEX` table with descriptive columns plus an
`EMBEDDING VECTOR(64)` column and a cosine `VECTOR INDEX (EMBEDDING) M=8 DISTANCE=cosine`.
Embeddings are written via `VEC_FromText(?)` and queried via `VEC_DISTANCE_COSINE(...)`
ordered by distance. GridGain remains the live cache and system-of-record archival remains in
the external database, unchanged.

**Testing**: No automated test suite (per constitution); validated by exercising the
`/api/investigation/semantic` endpoint and the investigation page against a running MariaDB
stack.

**Target Platform**: Docker Compose stack on a developer machine; Spring Boot processor on
`http://localhost:8080`, page at `/investigation.html`.

**Project Type**: Web service (Spring Boot backend) with a static browser frontend.

**Performance Goals**: Interactive search latency for a single analyst; each search
re-indexes at most 250 recent active payments and returns at most 20 results. This is a demo
path, not a production hot path.

**Constraints**: Vector search requires the MariaDB backend (11.8-class vector support); on
any other backend the feature MUST report itself unavailable without indexing or querying.
The embedding is fixed at 64 dimensions and cosine-normalized so it matches the vector column
and index definition. Indexing on the archival path must not block eviction beyond a single
best-effort upsert, and indexing failures must be swallowed with a warning.

**Scale/Scope**: Demo-scale — hundreds of indexed payments during a session; single-operator
usage. Scope is limited to what this commit introduced: the semantic service/controller/DTOs,
the embedding service, the MariaDB index and repository methods, the investigation page, the
archival and seed-data hooks, plus architecture docs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. External Database Is the System of Record** — PASS. The semantic index lives in the
  external MariaDB database, not in GridGain; the cache remains a live view and the durable
  copy of index rows survives a cache restart. The archival contract (evict only after the
  durable write succeeds) is preserved; indexing is inserted before eviction.
- **II. Change Data Capture, Not Dual Writes** — PASS (with note). Reference data still
  reaches the cache via CDC. The semantic index is a derived, single-owner analytical
  projection written only by the processor from data it already owns (archived payments and
  its own cache snapshot); it is not a second authoritative copy of reference data, so it does
  not introduce a divergent dual write of CDC-managed tables.
- **III. Cache-First Hot Path, Asynchronous Archival** — PASS. No synchronous external-DB
  round trip is added to the authorize/capture/refund hot path. Indexing happens on the
  existing asynchronous archival worker and during on-demand searches, never inline with
  payment processing.
- **IV. Pluggable Infrastructure Behind Configuration** — PASS. The capability keys off
  MariaDB's vector-index support: when it is available the feature is enabled, otherwise it
  reports itself unavailable without affecting the rest of the demo.
- **V. Observable, Demonstrable Behavior** — PASS. The whole feature is visible through the
  new investigation page and the `/api/investigation/semantic` endpoint; results, index count,
  availability, and messages are all surfaced in the UI without reading logs.
- **VI. Reproducible One-Command Local Stack** — PASS. The index schema is created on demand
  and self-heals; demo-data reset clears it; the stack still comes up from one compose command
  with the chosen env file. Metabase database init is added to the MariaDB init scripts.

No violations requiring justification.

## Project Structure

### Documentation (this feature)

```text
specs/007-ai-investigation/
├── plan.md              # This file
├── spec.md              # Feature specification
└── tasks.md             # Task breakdown
```

### Source Code (repository root)

```text
src/main/java/com/example/paymentsdemo/
├── api/
│   └── SemanticInvestigationController.java      # POST /api/investigation/semantic (processor-role only)
├── dto/
│   ├── SemanticInvestigationRequest.java         # query + limit, normalizedLimit() default 8, clamp 1..20
│   ├── SemanticInvestigationResponse.java        # available, message, query, embeddingModel, indexedPayments, results
│   └── SemanticInvestigationResult.java          # per-payment ranked match incl. distance + relevancePercent
└── service/
    ├── SemanticEmbeddingService.java             # 64-dim local hash embedding, model "local-demo-semantic-hash-v1"
    ├── SemanticInvestigationService.java         # orchestrates embed → index refresh → MariaDB vector search
    ├── SemanticPaymentIndexEntry.java            # record written to the index (summary + embedding JSON)
    ├── JdbcSystemOfRecordRepository.java         # (modified) index DDL, upsert, VEC_DISTANCE_COSINE search, count, reset
    ├── SystemOfRecordRepository.java             # (modified) adds semantic-index methods to the interface
    ├── CompletedPaymentArchiveService.java       # (modified) index archived payment before eviction
    └── SeedDataLoader.java                        # (modified) category-specific merchant names for meaningful summaries

src/main/resources/static/
├── investigation.html                            # new AI Investigation page + nav link
├── investigation.js                              # query form, POST, ranked result rendering
├── styles.css                                    # (modified) investigation panel / result styles
├── index.html, flow.html                         # (modified) nav link to investigation page
└── favicon.svg                                   # (new) shared favicon

docs/
├── high-level-architecture.svg                   # (new) architecture diagram
└── payments-demo-diagrams.drawio                 # (new) editable source diagrams

mariadb/
├── init/002_create_metabase_database.sql         # (new) Metabase database + user for analytics
└── mariadb-source-connector.json                 # (modified) DDL-history hardening for the source connector

gridgain/
└── ignite-server-config.xml                      # (modified) node hostnames aligned to gg8-node1..3

docker-compose.yml                                 # (modified) alignment with node naming
```

**Structure Decision**: Single Spring Boot web service with a static frontend, matching the
existing project layout. New backend code follows the established `api` / `dto` / `service`
package split; the MariaDB-specific persistence lives in the existing
`JdbcSystemOfRecordRepository` behind the `SystemOfRecordRepository` interface so other
backends inherit no-op / unavailable behavior. The frontend adds one page alongside the
existing dashboard and flow pages, reusing the shared stylesheet and navigation pattern.

## Complexity Tracking

No constitutional violations require justification; this section is intentionally empty.
