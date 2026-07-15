# Feature Specification: AI Investigation — Semantic Payment Search

**Feature Branch**: `007-ai-investigation`

**Created**: 2026-06-09

**Status**: Delivered

**Input**: Reverse-engineered from commit 2b646b8 — "add AI Investigation features"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Analyst searches for semantically similar payments (Priority: P1)

A fraud/operations analyst opens the AI Investigation page, types a natural-language
description of the kind of activity they are worried about (for example "suspicious
high-value travel declines"), and submits it. The system returns a ranked list of the
payments whose meaning is closest to that description, most relevant first, each with a
plain-language summary and a relevance score.

**Why this priority**: This is the entire point of the increment. Without semantic search
returning ranked matches, none of the supporting machinery (indexing, the page, the model)
delivers any user value. It is the smallest slice that can be demonstrated end to end.

**Independent Test**: With the MariaDB profile running and the simulator having produced
some traffic, POST a query to `/api/investigation/semantic` (or use the investigation page)
and confirm a ranked list of payments comes back ordered by similarity, each carrying a
summary, status, merchant, amount, fraud score, distance, and relevance percentage.

**Acceptance Scenarios**:

1. **Given** the MariaDB profile is running and payments have been indexed, **When** the
   analyst submits the query "suspicious high-value travel declines", **Then** the response
   is marked available and returns up to the requested number of payment results ordered by
   ascending vector distance (highest relevance first).
2. **Given** a returned result, **When** the analyst reads it, **Then** it shows a
   human-readable summary of the payment plus its status, source (active or archived),
   merchant, amount, fraud score, vector distance, and a relevance percentage between 0 and
   100.
3. **Given** the analyst does not specify a result limit, **When** the query runs, **Then**
   the system returns at most 8 results; and **When** a limit is supplied, **Then** it is
   clamped to between 1 and 20.

---

### User Story 2 - Payments become searchable as they flow through the system (Priority: P2)

For search to be useful, payments must be indexed. As payments reach a terminal state and
are archived to the external database they are added to the semantic index, and each search
also refreshes the index with a snapshot of the most recent in-flight payments held in the
cache. This keeps both live and historical activity discoverable.

**Why this priority**: Search over an empty index returns nothing. Indexing is required for
US1 to show meaningful results, but it is a distinct, separately observable capability
(the index count grows, archived and active payments both appear), so it is called out on
its own.

**Independent Test**: Start the simulator, let payments archive, run a search, and confirm
the reported "indexed payments" count is non-zero and results include both `ARCHIVED` and
`ACTIVE` sourced entries.

**Acceptance Scenarios**:

1. **Given** a payment reaches a terminal state and is archived, **When** archival
   completes, **Then** the payment is upserted into the semantic index before it is evicted
   from the cache, tagged with source `ARCHIVED`.
2. **Given** in-flight payments exist in the cache, **When** a search runs, **Then** the
   most recent active payments (up to 250) are re-indexed with source `ACTIVE` so the search
   reflects current activity.
3. **Given** the same payment is indexed more than once, **When** it is upserted again,
   **Then** its existing index row and embedding are updated in place rather than duplicated.

---

### User Story 3 - Analyst is told clearly when semantic search is not available (Priority: P3)

Semantic search depends on the MariaDB vector-search backend. When the demo is running on a
backend that does not support it, or when no payments have been indexed yet, the analyst
sees a clear message explaining the situation instead of an error or an empty screen.

**Why this priority**: Semantic search depends on MariaDB's vector-index support, which may
not be present in every environment. Graceful, explanatory degradation protects the demo
experience but is secondary to the core search working.

**Independent Test**: Run the demo against a MariaDB build without vector-index support (or
with the feature disabled) and submit a query; confirm the response is marked unavailable with
a message explaining the requirement, and the page shows that message rather than failing.

**Acceptance Scenarios**:

1. **Given** the demo is running without vector-index support available, **When** the analyst
   submits a query, **Then** the response is marked unavailable and carries a message stating
   semantic investigation requires a MariaDB build with vector search.
2. **Given** MariaDB is active but no payments have been indexed, **When** the analyst
   searches, **Then** the response is available with an empty result list and a message
   prompting the analyst to start the simulator and search again.
3. **Given** the underlying vector search fails at runtime, **When** the analyst searches,
   **Then** the response is marked unavailable with a message describing the failure rather
   than surfacing an unhandled error.

---

### Edge Cases

- **No similar results / empty index**: search succeeds but returns zero results with a
  message prompting the analyst to generate traffic first; the page renders an empty-state
  card, not an error.
- **Backend without vector-index support**: search short-circuits to an unavailable
  response; nothing is indexed and no query is executed.
- **Blank or oversized query**: a blank query is rejected by validation; queries are limited
  to 240 characters.
- **Embedding of meaningless input**: if the query normalizes to no usable tokens, the
  embedding falls back to a valid unit vector so search still runs without error.
- **Merchant or account missing from cache when summarizing**: the summary substitutes
  "unknown" descriptors rather than failing to index the payment.
- **Index table missing or not visible**: the index table is created on demand and
  re-created if it cannot be seen, so a fresh MariaDB deployment self-heals its schema.
- **Reset of demo data**: clearing demo data also clears the semantic index on MariaDB.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST expose an endpoint that accepts a natural-language investigation
  query and returns payments ranked by semantic similarity to that query.
- **FR-002**: System MUST validate the query as non-blank and no longer than 240 characters.
- **FR-003**: System MUST default the number of returned results to 8 when no limit is
  supplied, and clamp any supplied limit to between 1 and 20.
- **FR-004**: System MUST convert the query text and each payment summary into a fixed-length
  numeric embedding using a single, named embedding model.
- **FR-005**: System MUST rank results by vector distance (cosine) in ascending order so the
  most similar payments appear first.
- **FR-006**: System MUST return, for each result, the payment identifier, a plain-language
  summary, status, originating merchant, amount, currency, fraud score, suspicious flag,
  decline reason, creation time, source (active or archived), the raw vector distance, and a
  relevance percentage bounded to 0–100.
- **FR-007**: System MUST index each payment into the semantic index when it is archived to
  the external database, before it is evicted from the cache.
- **FR-008**: System MUST refresh the semantic index with a snapshot of the most recent
  in-flight payments (up to 250) held in the cache each time a search is performed.
- **FR-009**: System MUST upsert index entries so repeated indexing of the same payment
  updates the existing row and embedding rather than creating duplicates.
- **FR-010**: System MUST generate each payment's summary from its own attributes plus its
  merchant and account context, and MUST substitute "unknown" descriptors when that context
  is unavailable.
- **FR-011**: System MUST perform vector search only on the MariaDB backend and MUST return
  an explicit "unavailable" response, without attempting to index or query, on any other
  backend.
- **FR-012**: System MUST return an available response with an empty result set and an
  explanatory message when the index contains no payments.
- **FR-013**: System MUST catch runtime failures during search and return an unavailable
  response describing the failure instead of an unhandled error.
- **FR-014**: System MUST report the count of currently indexed payments and the embedding
  model name alongside search results.
- **FR-015**: System MUST create the semantic index schema on demand on MariaDB, verify it
  is visible, and re-create it if it is not, so a fresh deployment needs no manual schema
  step.
- **FR-016**: System MUST clear the semantic index when demo data is reset on MariaDB.
- **FR-017**: System MUST provide a dedicated browser page, linked from the existing page
  navigation, where an analyst can enter a query and view ranked results with relevance
  scores.
- **FR-018**: System MUST restrict the investigation endpoint and service to the processor
  application role, excluding the simulator, initiator, and cache-sink roles.

### Key Entities *(include if feature involves data)*

- **SemanticInvestigationRequest**: the analyst's search input — a natural-language `query`
  and an optional result `limit`; normalizes the limit to a default of 8 and a clamped range
  of 1–20.
- **SemanticInvestigationResponse**: the outcome of a search — whether semantic search was
  `available`, a human-readable `message`, the echoed `query`, the `embeddingModel` name, the
  count of `indexedPayments`, and the ordered list of `results`.
- **SemanticInvestigationResult**: one ranked payment match — identifier, summary, status,
  merchant, amount and currency, fraud score, suspicious flag, decline reason, creation time,
  source (ACTIVE/ARCHIVED), the vector `distance`, and a `relevancePercent`.
- **SemanticPaymentIndexEntry**: the record written to the index for a payment — its
  descriptive attributes, the source (ACTIVE/ARCHIVED), and the JSON-encoded embedding
  vector derived from its summary.
- **Embedding**: a fixed-length (64-dimension), cosine-normalized numeric vector produced by
  the named embedding model from a piece of text; stored per payment and compared against the
  query's embedding to measure semantic similarity.
- **Payment semantic index**: the MariaDB-resident store of indexed payments, keyed by
  payment id, holding the summary, descriptive columns, and a vector column with a cosine
  vector index used for similarity search.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On the MariaDB profile with simulator traffic, an analyst submitting a query
  receives a ranked list of payments ordered most-relevant-first in a single request.
- **SC-002**: Every returned result carries a relevance percentage between 0 and 100 and a
  plain-language summary describing the payment.
- **SC-003**: A query with no explicit limit returns at most 8 results; a query requesting
  more than 20 returns at most 20.
- **SC-004**: After the simulator runs, the reported indexed-payment count is greater than
  zero and includes both archived and active payments.
- **SC-005**: On a non-MariaDB backend, 100% of queries return an explicit unavailable
  message pointing to the MariaDB profile, with no unhandled error.
- **SC-006**: Searching before any traffic exists returns an available, empty response with a
  prompt to start the simulator, not an error.
- **SC-007**: The behavior is demonstrable end to end from the investigation page without
  reading server logs.

## Assumptions

- The analyst is an internal demo operator with access to the running stack, not an
  authenticated end user; the endpoint has no per-user authentication.
- The embedding model is a self-contained, deterministic local model shipped with the app;
  no external embedding API or network call is involved.
- Vector search requires a MariaDB (11.8-class) build with vector column and cosine
  vector-index support; a build without that support is expected to report the feature as
  unavailable.
- The existing cache holds recent in-flight payments and reference data (merchants,
  accounts) needed to build meaningful summaries.
- Indexing the most recent 250 active payments per search is sufficient to reflect current
  activity for demo purposes.
- Merchant seed data uses category-specific, human-readable names so that generated
  summaries and semantic matches are meaningful.
