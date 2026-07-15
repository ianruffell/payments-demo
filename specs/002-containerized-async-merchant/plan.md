# Implementation Plan: Containerized Asynchronous Merchant Processing

**Branch**: `002-containerized-async-merchant` | **Date**: 2026-05-07 | **Spec**: [spec.md](./spec.md)

**Input**: Reverse-engineered from commit 90165ee — "containerize async merchant processing demo"

**Note**: This plan documents the technical approach actually taken in the delivered commit,
reconstructed from the diff against parent `879e8d9`.

## Summary

This increment moved the demo from a single embedded-GridGain process that authorized payments
synchronously to a multi-container stack that authorizes payments asynchronously through
external merchant services. Three things changed together: (1) GridGain became an external
three-node cluster with the Spring Boot app joining as a client; (2) authorization became a
request/callback flow where the processor dispatches to a merchant service, tracks a
`MerchantPaymentAttempt`, and settles the payment when a result or timeout arrives; and (3) the
whole stack — cluster, processor, merchant simulators, optional Control Center — comes up from
one Docker Compose command, with an optional MySQL + Debezium CDC ingestion path feeding a
standalone Kafka-to-GridGain sink application.

## Technical Context

**Language/Version**: Java 17 (build), Spring Boot; merchant simulators and processor run on the
`eclipse-temurin:17-jre` image.

**Primary Dependencies**: GridGain/Ignite 8.9.32 (ignite-core, ignite-indexing, gridgain-core,
gridgain-ultimate), Spring Boot (web + validation), Jackson, `kafka-clients` (new, for the CDC
sink), Java built-in `HttpClient` for async merchant dispatch/callback.

**Storage**: External three-node GridGain cluster (`gridgain/ultimate:8.9.32`) holding accounts,
merchants, payments, ledger entries, and the new merchant payment attempts cache. Optional
external MySQL (`quay.io/debezium/example-mysql`) as system of record for the CDC path.

**Testing**: No automated test suite (per constitution); validated by driving HTTP endpoints and
observing the dashboard.

**Target Platform**: Docker Compose on a local developer machine; Linux containers.

**Project Type**: Containerized web service (Spring Boot processor + REST/dashboard) plus
sidecar merchant simulator containers and a standalone CDC sink application, all from one code
base selected by Spring profile.

**Performance Goals**: Authorization endpoint returns `202 Accepted` immediately (does not block
on the merchant); merchant timeout monitor runs every second; default merchant deadline 10s.

**Constraints**: GridGain 8 on Java requires the documented `--add-opens` JVM flags (baked into
the Dockerfile and the off-compose scripts). The processor runs in GridGain client mode only;
no embedded server node and no local persistence data-region configuration remain.

**Scale/Scope**: Seeds 100,000 accounts and 4 merchants (down from 10,000) so each merchant maps
to one simulator container; four merchant simulator containers in the delivered compose file.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. External Database Is the System of Record**: PASS (advanced). This commit removes the
  embedded persistence data-region config and introduces the MySQL source schema plus the CDC
  sink, making the external DB the authoritative store and GridGain a live client cache.
- **II. Change Data Capture, Not Dual Writes**: PASS. Reference data reaches the cache through
  Debezium → Kafka → the new `KafkaToGridGainSinkApplication`, not through app-level dual
  writes; the README explicitly notes the CDC path is the authoritative ingestion path and
  seeding can be disabled to avoid overlap.
- **III. Cache-First Hot Path, Asynchronous Archival**: PASS. Authorization, merchant review,
  and fund holds happen against GridGain with no synchronous external-DB round trip; the
  merchant call is dispatched asynchronously and settled via callback/timeout.
- **IV. Pluggable Infrastructure Behind Configuration**: PASS. Roles (processor, merchant
  simulator, CDC sink) are selected by Spring profile; discovery addresses, seeding, merchant
  URL pattern, callback URL, and timeout are configuration, not forks.
- **V. Observable, Demonstrable Behavior**: PASS. New states (`PENDING_MERCHANT`, `TIMED_OUT`)
  and the attempt lifecycle surface through the payment records and dashboard; the operator
  levers (start/stop a merchant container, watch timeouts) take effect live.
- **VI. Reproducible One-Command Local Stack**: PASS. `docker compose up --build` starts the
  cluster, processor, and merchant simulators and self-seeds when caches are empty.

No violations requiring justification. Complexity Tracking omitted.

## Project Structure

### Documentation (this feature)

```text
specs/002-containerized-async-merchant/
├── spec.md              # Feature specification (this increment)
├── plan.md              # This file
└── tasks.md             # Dependency-ordered, reverse-engineered task list
```

### Source Code (repository root)

```text
Dockerfile                                   # temurin 17-jre image; bakes GridGain --add-opens flags
.dockerignore                                # keeps build context lean; ships the prebuilt jar only
docker-compose.yml                           # 3 GridGain nodes, Control Center, 4 merchant sims, processor
gridgain-license.xml                         # GridGain Ultimate license mounted into nodes and processor

gridgain/
├── ignite-server-config.xml                 # off-compose GridGain server node config
├── start-cluster.sh                          # start 3-node cluster off-compose
├── stop-cluster.sh                           # stop the cluster
├── start-sink.sh                             # run the CDC sink app (main class override + --add-opens)
├── start-sink-container.sh                   # run the sink inside a Maven container
└── stop-sink-container.sh                    # stop the sink container

mysql/
├── schema.sql                                # accounts, merchants, payments, ledger_entries source schema
├── docker.sh                                 # bring up Kafka + MySQL + Kafka Connect (Debezium images)
├── mysql-payments-source.json               # Debezium MySQL source connector config
└── register-source-connector.sh            # PUT the connector config into Kafka Connect

src/main/resources/
├── application.yml                           # discovery addresses, seed toggle, merchant URL pattern, processor callback/timeout
├── ignite-config.xml                         # GridGain node config used by the compose cluster
└── control-center.conf                       # nginx routing for Control Center frontend

src/main/java/com/example/paymentsdemo/
├── cdc/KafkaToGridGainSinkApplication.java   # NEW standalone sink app (profile cdc-sink, no web)
├── config/CacheConfigurations.java           # NEW extracted cache defs incl. merchant_payment_attempts
├── config/GridGainConfig.java                # embedded server node -> client mode + license plugin
├── domain/Merchant.java                       # adds serviceUrl
├── domain/MerchantPaymentAttempt.java        # NEW cached attempt record
├── domain/MerchantRequestStatus.java         # NEW attempt-status enum
├── domain/PaymentStatus.java                  # adds PENDING_MERCHANT, TIMED_OUT
├── dto/MerchantAuthorizationRequest.java     # NEW dispatch payload
├── dto/MerchantAuthorizationResult.java      # NEW callback payload
├── service/CacheNames.java                    # adds MERCHANT_PAYMENT_ATTEMPTS
├── service/MerchantDispatchService.java      # NEW async dispatch to merchant service (processor role)
├── service/MerchantSimulatorService.java     # NEW simulator: accept + delayed callback (simulator role)
├── service/MerchantTimeoutMonitor.java       # NEW 1s scheduler -> markTimedOutPayments
├── service/PaymentService.java                # async authorize + processMerchantResult + timeout logic
├── service/SeedDataLoader.java                # seed toggle, 4 merchants, serviceUrl, reset-if-unreadable
├── service/DashboardService.java              # exclude PENDING_MERCHANT; rank merchants by amount
├── api/MerchantResultController.java         # NEW POST /api/merchant-results (processor role)
├── api/MerchantSimulatorController.java       # NEW POST /api/merchant/payments (simulator role)
├── api/PaymentController.java                 # 202 Accepted when PENDING_MERCHANT; profile-gated
└── api/{Admin,Dashboard,Simulator}Controller.java  # profile-gated to !merchant-simulator
```

**Structure Decision**: Single Maven module, no new source tree. The processor, the merchant
simulator, and the CDC sink are the same jar run under different Spring profiles
(`!merchant-simulator` for the processor/sink beans, `merchant-simulator` for the simulator,
`cdc-sink` for the standalone sink main class). Infrastructure lives at the repository root
(`Dockerfile`, `docker-compose.yml`, `gridgain/`, `mysql/`) and in `src/main/resources`.

## Complexity Tracking

No constitution violations; this section is intentionally empty.
