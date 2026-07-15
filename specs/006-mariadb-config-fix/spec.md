# Feature Specification: MariaDB External-Database Configuration Fix

**Feature Branch**: `006-mariadb-config-fix`

**Created**: 2026-05-21

**Status**: Delivered

**Input**: Reverse-engineered from commit dd598e0 — "fix: update application.yml to configure MariaDB as the external database type with correct connection properties and CDC connector settings"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - MariaDB profile connects and processes payments out of the box (Priority: P1)

As an operator running the demo with the MariaDB external-database profile, when I start the stack I want the application's default configuration to already point at MariaDB with the correct connection properties and matching CDC connector settings, so the demo connects to MariaDB and processes payments without me having to override any values.

**Why this priority**: This is the whole point of the change. A previous increment established MariaDB as the external database, but the checked-in application defaults still carried stale values that did not match the MariaDB backend. That mismatch meant the documented MariaDB run did not work without manual overrides. Correcting the defaults is what makes the demo usable as advertised out of the box.

**Independent Test**: Start the stack with the MariaDB env file and confirm the application connects to MariaDB, seeds reference data, the Debezium/CDC connector for MariaDB is created and streams `ACCOUNTS`/`MERCHANTS` changes into the cache, and payments flow through the dashboard — all without editing configuration or passing extra properties.

**Acceptance Scenarios**:

1. **Given** a fresh checkout and the MariaDB profile, **When** the operator starts the stack and inspects the effective configuration, **Then** `demo.external-db.type` is `mariadb`, the JDBC URL is `jdbc:mariadb://mariadb:3306/payments_app`, and the username is `payments_app`.
2. **Given** the MariaDB profile is running, **When** the CDC sink and Debezium connector initialize, **Then** the reference-cache schema name is `payments_app` and the connector name is `paymentsdemo-mariadb-reference`, matching the MariaDB backend.
3. **Given** the MariaDB profile is running, **When** the operator opens the dashboard and drives payment traffic, **Then** payments are authorized, captured, and archived to MariaDB as the system of record without any configuration overrides.

---

### Edge Cases

- If an operator supplies their own values through Spring properties or environment variables (for example a MaxScale host such as `jdbc:mariadb://maxscale1:3306/payments_app`), those overrides continue to take precedence over the corrected defaults.
- The fix touches only the default values; it does not change the configuration surface, so an operator who was already passing a full set of overrides sees identical behavior.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The application default configuration MUST set `demo.external-db.type` to `mariadb`.
- **FR-002**: The application default configuration MUST set the external-database JDBC URL to `jdbc:mariadb://mariadb:3306/payments_app`.
- **FR-003**: The application default configuration MUST set the external-database username to `payments_app` (with the existing `payments_app` password unchanged).
- **FR-004**: The reference-cache CDC settings MUST set the schema name to `payments_app`, consistent with the MariaDB database.
- **FR-005**: The reference-cache CDC settings MUST set the Debezium connector name to `paymentsdemo-mariadb-reference`, so the configured connector matches the registered MariaDB source connector.
- **FR-006**: All other `demo.external-db` and CDC settings (poll interval, batch size, Kafka bootstrap servers, sink group id, topic prefix, accounts/merchants topics) MUST remain unchanged by this fix.
- **FR-007**: Operator-supplied overrides via Spring properties or environment variables MUST continue to take precedence over these defaults.

### Key Entities *(include if feature involves data)*

- **External database configuration (`demo.external-db`)**: The settings that select and connect to the system-of-record relational database — type, JDBC URL, credentials, and archival tuning.
- **Reference-cache CDC settings (`demo.external-db.reference-cache`)**: The Debezium/Kafka connector settings that project `accounts` and `merchants` changes into the cache — schema name, connector name, and Kafka topics.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Starting the demo with the MariaDB env file connects to MariaDB using the checked-in defaults, with zero configuration overrides required.
- **SC-002**: The MariaDB Debezium connector named `paymentsdemo-mariadb-reference` is created and streams `ACCOUNTS` and `MERCHANTS` changes into the cache on the MariaDB profile.
- **SC-003**: With the corrected defaults, the MariaDB profile seeds reference data and processes authorize/capture/refund traffic visible on the dashboard.
- **SC-004**: No `demo.external-db` or CDC setting other than the five corrected values changes, so the fix has no unintended side effects on the running stack.

## Assumptions

- The stale values previously present in the checked-in defaults were a leftover that did not match the MariaDB backend; the intended default configuration for the demo is MariaDB.
- MariaDB is reachable at host `mariadb` on port `3306` with database `payments_app` in the reference compose topology; operators using a MaxScale-fronted cluster override the host through Spring properties.
- The fix is limited to correcting default values in `application.yml`; it changes no code, schema, or topology.
- The project ships no automated test suite, so validation is by starting the stack and observing behavior through the dashboard and endpoints.
