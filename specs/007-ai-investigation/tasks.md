---
description: "Task list for AI Investigation — semantic payment search"
---

# Tasks: AI Investigation — Semantic Payment Search

**Input**: Design documents from `/specs/007-ai-investigation/`

**Prerequisites**: plan.md (required), spec.md (required for user stories)

**Tests**: The project ships no automated test suite; behavior is validated by exercising the
endpoint and the investigation page against a running MariaDB stack. No test tasks are
included.

**Organization**: Tasks are grouped by user story so each story can be implemented and
verified independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Exact file paths are included in each task

## Path Conventions

Single Spring Boot web service with a static frontend. Backend under
`src/main/java/com/example/paymentsdemo/`, frontend under `src/main/resources/static/`,
infrastructure at the repository root and under `mariadb/`, `gridgain/`, `docs/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Prepare the local stack and supporting assets the feature relies on.

- [X] T001 [P] Add Metabase database and user init script for the MariaDB stack in
  `mariadb/init/002_create_metabase_database.sql`.
- [X] T002 [P] Harden the MariaDB Debezium source connector DDL-history handling
  (`schema.history.internal.skip.unparseable.ddl`, `store.only.captured.tables.ddl`) in
  `mariadb/mariadb-source-connector.json`.
- [X] T003 [P] Align GridGain node hostnames to `gg8-node1..3` in
  `gridgain/ignite-server-config.xml` and reconcile `docker-compose.yml`.
- [X] T004 [P] Ignore aider working files in `.gitignore`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core building blocks every user story depends on — the embedding model, the
DTOs/entities, and the repository contract.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T005 [P] Implement the self-contained embedding model in
  `src/main/java/com/example/paymentsdemo/service/SemanticEmbeddingService.java`: 64
  dimensions, named model `local-demo-semantic-hash-v1`, concept-weight + hashed-token
  features, cosine normalization, and `vectorJson(text)` producing a JSON vector string.
- [X] T006 [P] Define the request DTO with validation and limit normalization in
  `src/main/java/com/example/paymentsdemo/dto/SemanticInvestigationRequest.java`
  (`@NotBlank`, `@Size(max=240)`, `normalizedLimit()` default 8, clamp 1..20).
- [X] T007 [P] Define the response DTO in
  `src/main/java/com/example/paymentsdemo/dto/SemanticInvestigationResponse.java`
  (`available`, `message`, `query`, `embeddingModel`, `indexedPayments`, `results`).
- [X] T008 [P] Define the per-result DTO in
  `src/main/java/com/example/paymentsdemo/dto/SemanticInvestigationResult.java` including
  `distance` and `relevancePercent`.
- [X] T009 [P] Define the index-entry record in
  `src/main/java/com/example/paymentsdemo/service/SemanticPaymentIndexEntry.java` (payment
  attributes, `source`, `embeddingJson`).
- [X] T010 Extend the repository contract in
  `src/main/java/com/example/paymentsdemo/service/SystemOfRecordRepository.java` with
  `supportsSemanticInvestigation()`, `upsertPaymentSemanticIndex(...)`,
  `searchSimilarPayments(...)`, and `semanticPaymentIndexCount()` (depends on T008, T009).

**Checkpoint**: Model, DTOs, entity, and repository contract exist — user stories can begin.

---

## Phase 3: User Story 1 - Analyst searches for semantically similar payments (Priority: P1) 🎯 MVP

**Goal**: An analyst can submit a natural-language query and receive payments ranked by
semantic similarity, each with a summary and relevance score.

**Independent Test**: On the MariaDB profile with indexed payments, POST to
`/api/investigation/semantic` (or use the page) and confirm a ranked list ordered by
ascending distance with relevance percentages comes back.

- [X] T011 [US1] Implement MariaDB vector search in
  `src/main/java/com/example/paymentsdemo/service/JdbcSystemOfRecordRepository.java`:
  `searchSimilarPayments(embeddingJson, limit)` using `VEC_DISTANCE_COSINE(embedding,
  VEC_FromText(?))`, `ORDER BY distance`, `LIMIT ?`, mapping rows into
  `SemanticInvestigationResult` and computing `relevancePercent = clamp((1-distance)*100, 0,
  100)` (depends on T010).
- [X] T012 [US1] Implement `semanticPaymentIndexCount()` and `supportsSemanticInvestigation()`
  (MariaDB-only) in the same repository file (depends on T010).
- [X] T013 [US1] Implement the orchestration entry point `investigate(request)` in
  `src/main/java/com/example/paymentsdemo/service/SemanticInvestigationService.java`: embed the
  query, run the search, populate the response with model name, indexed count, message, and
  results (depends on T005, T007, T008, T011, T012).
- [X] T014 [US1] Expose the endpoint in
  `src/main/java/com/example/paymentsdemo/api/SemanticInvestigationController.java` —
  `POST /api/investigation/semantic`, `@Valid` request body, restricted to the processor role
  via the profile expression (depends on T006, T013).
- [X] T015 [P] [US1] Build the investigation page
  `src/main/resources/static/investigation.html`: hero, page-menu nav link, query textarea,
  search button, status/meta text, and results container.
- [X] T016 [P] [US1] Implement page behavior in
  `src/main/resources/static/investigation.js`: POST the query, render ranked results
  (payment id, status/source, relevance %, summary, merchant, amount, fraud, distance), and
  handle empty/error states.
- [X] T017 [P] [US1] Add investigation panel and result styling in
  `src/main/resources/static/styles.css`.
- [X] T018 [P] [US1] Add the "AI Investigation" nav link to
  `src/main/resources/static/index.html` and `src/main/resources/static/flow.html`, and add
  `src/main/resources/static/favicon.svg`.

**Checkpoint**: End-to-end search works from the page against an already-populated index.

---

## Phase 4: User Story 2 - Payments become searchable as they flow (Priority: P2)

**Goal**: Archived payments and recent in-flight payments are indexed so search has data.

**Independent Test**: Run the simulator, let payments archive, search, and confirm the
indexed count is non-zero with both `ARCHIVED` and `ACTIVE` results present.

- [X] T019 [US2] Implement the MariaDB upsert `upsertPaymentSemanticIndex(entry)` in
  `src/main/java/com/example/paymentsdemo/service/JdbcSystemOfRecordRepository.java` using
  `INSERT ... VALUES (..., VEC_FromText(?)) ON DUPLICATE KEY UPDATE ...` so re-indexing updates
  in place (depends on T010).
- [X] T020 [US2] Implement summary generation and snapshot indexing in
  `src/main/java/com/example/paymentsdemo/service/SemanticInvestigationService.java`:
  `indexArchivedPayment(payment)` and `refreshRecentActivePaymentIndex()` (top 250 from the
  `PAYMENTS` cache via `SqlFieldsQuery`), building summaries from payment + merchant + account
  context with "unknown" fallbacks, tagged `ARCHIVED` / `ACTIVE` (depends on T005, T009, T019).
- [X] T021 [US2] Call `refreshRecentActivePaymentIndex()` at the start of `investigate(...)`
  so each search reflects current activity (same file; depends on T013, T020).
- [X] T022 [US2] Hook archival indexing into
  `src/main/java/com/example/paymentsdemo/service/CompletedPaymentArchiveService.java`: inject
  `SemanticInvestigationService` and call `indexArchivedPayment(payment)` before evicting the
  payment from GridGain (depends on T020).
- [X] T023 [P] [US2] Give merchants category-specific, human-readable seed names so summaries
  and matches are meaningful, in
  `src/main/java/com/example/paymentsdemo/service/SeedDataLoader.java` (merchant profiles,
  `normalizeGenericMerchantNames()`).

**Checkpoint**: Index grows from live and archived traffic; US1 search now returns real data.

---

## Phase 5: User Story 3 - Clear behavior when search is unavailable (Priority: P3)

**Goal**: A backend without vector-index support, empty indexes, and runtime failures produce
clear, non-error responses in the API and the page.

**Independent Test**: Run against a MariaDB build without vector-index support and search;
confirm an unavailable response explaining the requirement and a page that shows the message
instead of failing.

- [X] T024 [US3] In `SemanticInvestigationService.investigate(...)`, short-circuit to an
  unavailable response when `supportsSemanticInvestigation()` is false, return an available
  empty response with a "start the simulator" prompt when there are no results, and wrap the
  search in a try/catch that returns an unavailable response describing the failure
  (`src/main/java/com/example/paymentsdemo/service/SemanticInvestigationService.java`; depends
  on T013).
- [X] T025 [US3] Guard indexing paths so they are no-ops on non-MariaDB backends and swallow
  per-payment indexing failures with a warning, in
  `src/main/java/com/example/paymentsdemo/service/SemanticInvestigationService.java` and
  `src/main/java/com/example/paymentsdemo/service/JdbcSystemOfRecordRepository.java` (depends
  on T019, T020).
- [X] T026 [US3] Create/self-heal the index schema on demand on MariaDB
  (`ensureSemanticIndexTable()`, existence check, recreate) and clear the index on demo-data
  reset, in
  `src/main/java/com/example/paymentsdemo/service/JdbcSystemOfRecordRepository.java` (depends
  on T010).
- [X] T027 [P] [US3] Render unavailable and empty states in
  `src/main/resources/static/investigation.js` (availability-aware meta text and empty-state
  card) (depends on T016).

**Checkpoint**: All three stories work; the demo degrades gracefully off MariaDB.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Documentation and optional operational tooling shipped with the increment.

- [X] T028 [P] Add the high-level architecture diagram in `docs/high-level-architecture.svg`.
- [X] T029 [P] Add the editable diagram source in `docs/payments-demo-diagrams.drawio`.
- [X] T030 [P] Update `README.md`: document the AI Investigation page and the
  `POST /api/investigation/semantic` endpoint.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: Independent; can start immediately.
- **Foundational (Phase 2)**: Blocks all user stories. T010 depends on T008/T009.
- **User Story 1 (Phase 3)**: Depends on Phase 2. Delivers the searchable MVP.
- **User Story 2 (Phase 4)**: Depends on Phase 2; makes US1 return real data. T021 depends on
  US1's `investigate(...)` (T013).
- **User Story 3 (Phase 5)**: Depends on Phase 2 and the code paths from US1/US2 it hardens.
- **Polish (Phase 6)**: Documentation/overlay; independent of the Java build.

### User Story Dependencies

- **US1 (P1)**: Independently testable against a pre-populated index; the core value slice.
- **US2 (P2)**: Independently testable via index growth; feeds US1 with data.
- **US3 (P3)**: Independently testable via backend choice and empty/failure conditions.

### Within Each User Story

- Repository/data methods before the service that calls them.
- Service before the controller/endpoint.
- Backend endpoint before, or in parallel with, the page that calls it.

### Parallel Opportunities

- All of Phase 1 (T001–T004) runs in parallel.
- Foundational DTOs/model/entity (T005–T009) run in parallel; T010 follows.
- Within US1, the frontend tasks (T015–T018) run in parallel with, and after, the backend
  chain (T011→T012→T013→T014).
- US2's seed-data change (T023) is independent of its indexing chain.
- All Phase 6 documentation tasks run in parallel.

---

## Implementation Strategy

### MVP First (User Story 1)

1. Complete Phase 1 (Setup) and Phase 2 (Foundational).
2. Complete Phase 3 (US1) and validate search against a manually-seeded index.
3. Demo the ranked-search experience on the investigation page.

### Incremental Delivery

1. Setup + Foundational → building blocks ready.
2. US1 → searchable MVP (page + endpoint + MariaDB vector search).
3. US2 → automatic indexing from live and archived traffic makes search useful.
4. US3 → graceful unavailability, empty-state, self-healing schema, and reset handling.
5. Polish → architecture docs.

---

## Notes

- [P] tasks touch different files with no ordering dependency.
- Vector search is MariaDB-only by design; every indexing and search path must no-op or report
  unavailable on other backends.
- The embedding model is deterministic and self-contained — no external API or network call.
- No automated tests: validate by exercising `/api/investigation/semantic` and the
  investigation page on a running MariaDB stack.
- All tasks are marked complete because this plan reverse-engineers an already-delivered
  increment (commit 2b646b8).
