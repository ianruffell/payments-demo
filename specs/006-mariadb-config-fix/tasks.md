---
description: "Task list for MariaDB external-database configuration fix"
---

# Tasks: MariaDB External-Database Configuration Fix

**Input**: Design documents from `/specs/006-mariadb-config-fix/`

**Prerequisites**: plan.md (required), spec.md (required for user stories)

**Tests**: No automated test suite exists for this project; validation is by starting the stack and observing behavior. No test tasks are included.

**Organization**: This is a single-file configuration fix, so all work belongs to the one user story (US1).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1)
- Include exact file paths in descriptions

## Path Conventions

- Single Spring Boot service; the only file touched is `src/main/resources/application.yml` at the repository root.

---

## Phase 1: User Story 1 - MariaDB profile connects and processes payments out of the box (Priority: P1) 🎯 MVP

**Goal**: Correct the checked-in defaults in `application.yml` so the MariaDB external-database profile connects and runs without overrides.

**Independent Test**: Start the stack with the MariaDB env file and confirm MariaDB connectivity, connector creation, and payment processing on the dashboard with no configuration overrides.

- [X] T001 [US1] Identify the incorrect, stale defaults in the `demo.external-db` block of `src/main/resources/application.yml` (`type`, `jdbc-url`, `username`) and in the `reference-cache` block (`schema-name`, `connector-name`) that do not match the MariaDB backend.
- [X] T002 [US1] Set `demo.external-db.type` to `mariadb`, `jdbc-url` to `jdbc:mariadb://mariadb:3306/payments_app`, and `username` to `payments_app` in `src/main/resources/application.yml` (leave the `payments_app` password unchanged).
- [X] T003 [US1] Set `demo.external-db.reference-cache.schema-name` to `payments_app` and `connector-name` to `paymentsdemo-mariadb-reference` in `src/main/resources/application.yml`.
- [X] T004 [US1] Confirm no unrelated settings changed (poll interval, batch size, Kafka bootstrap servers, sink group id, topic prefix, accounts/merchants topics) by reviewing the diff of `src/main/resources/application.yml`.
- [X] T005 [US1] Verify the MariaDB profile: start the stack with the MariaDB env file, confirm the app connects to MariaDB, the `paymentsdemo-mariadb-reference` connector streams `ACCOUNTS`/`MERCHANTS` into the cache, and payments process on the dashboard without overrides.

**Checkpoint**: MariaDB profile runs out of the box with the corrected defaults; no overrides required.

---

## Dependencies & Execution Order

- T001 → T002/T003 (identify before editing).
- T002 and T003 edit the same file, so run them sequentially; T004 reviews their combined diff.
- T005 (runtime verification) runs after the edits are in place.

## Notes

- Scope is deliberately minimal: one file, six changed lines. Do not expand scope.
- The fix only corrects stale default values; it changes no code, schema, or topology.
- Commit after the edit is verified against the running MariaDB profile.
