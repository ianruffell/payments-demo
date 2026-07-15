# Implementation Plan: MariaDB External-Database Configuration Fix

**Branch**: `006-mariadb-config-fix` | **Date**: 2026-05-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-mariadb-config-fix/spec.md`

## Summary

A previous increment established MariaDB as the external database, but the default values checked into `src/main/resources/application.yml` still carried stale values that did not match the MariaDB backend. This is a configuration correction: update the `demo.external-db` connection defaults (type, JDBC URL, username) and the `demo.external-db.reference-cache` CDC settings (schema name, connector name) so they describe MariaDB. The technical approach is a single-file edit to the default configuration — no code, schema, or topology changes — so that the documented MariaDB run works out of the box while operator-supplied overrides still win.

## Technical Context

**Language/Version**: Java 11+ (Spring Boot); configuration is YAML

**Primary Dependencies**: Spring Boot property binding (`demo.*`), Debezium/Kafka Connect, GridGain 8 CDC sink

**Storage**: External relational database as system of record — MariaDB (`payments_app`), now correctly described by the checked-in defaults

**Testing**: No automated test suite; validated by starting the stack and observing the dashboard and endpoints

**Target Platform**: Docker Compose reference runtime (Linux containers)

**Project Type**: Single Spring Boot service with static dashboard; this change touches configuration only

**Performance Goals**: Not applicable — configuration values only, no hot-path change

**Constraints**: Must not alter unrelated settings; operator overrides via Spring properties / environment variables must continue to take precedence

**Scale/Scope**: One file, six changed lines in `application.yml`

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **IV. Pluggable Infrastructure Behind Configuration**: PASS — the fix works entirely through the configuration surface the constitution prescribes (`demo.external-db.type`, JDBC URL, connector settings). It corrects a default so the MariaDB configuration resolves cleanly; it does not fork code.
- **VI. Reproducible One-Command Local Stack**: PASS — the change is what makes the single MariaDB compose command actually reproducible from a fresh checkout, removing the need for manual configuration overrides.
- **I. External Database Is the System of Record**: PASS — MariaDB remains the durable system of record; only its connection defaults are corrected.
- **II. Change Data Capture, Not Dual Writes**: PASS — the CDC connector name and schema are aligned to MariaDB; the CDC-only projection model is unchanged.
- **III / V**: Not affected — no hot-path or observable-behavior changes beyond enabling the MariaDB profile to run as designed.

No violations; Complexity Tracking is empty.

## Project Structure

### Documentation (this feature)

```text
specs/006-mariadb-config-fix/
├── plan.md              # This file
├── spec.md              # Feature specification
└── tasks.md             # Task list
```

### Source Code (repository root)

```text
src/main/resources/application.yml   # demo.external-db defaults + reference-cache CDC settings
```

**Structure Decision**: This is a configuration-only correction. The single touched file is `src/main/resources/application.yml`; no source directories, modules, or tests are added or restructured.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No constitutional violations. This section is intentionally empty.
