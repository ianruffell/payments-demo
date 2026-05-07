# GridGain Payments Demo

Small real-time card-transaction demo built with Java, Spring Boot, an external GridGain cluster, and a browser dashboard.

## What it models

- `accounts`: balance, currency, status, risk tier
- `merchants`: category, country, per-transaction max, daily limit, active flag
- `payments`: authorize, capture, refund lifecycle plus fraud score and decline reason
- `ledger_entries`: immutable debit and credit records for holds, captures, and refunds

## What it shows

1. Connect to an external GridGain cluster and load `100k` accounts and `10k` merchants on startup when the caches are empty.
2. Start a simulator to generate authorize traffic, automatic captures, and occasional refunds.
3. Watch live throughput, approval rate, declines by reason, top merchants, and suspicious payments at `http://localhost:8080`.
4. Trigger a merchant outage or lower the fraud threshold from the dashboard and watch the metrics change immediately.
5. Optionally treat MySQL as the external system of record and stream its changes into GridGain through Debezium CDC.

## Version note

The build is pinned to `GridGain 8.9.30`. The public GridGain Maven repository lists `8.9.30` published on `2026-02-17`, but I could not verify a public `8.9.32` artifact. If you have an internal repository with `8.9.32`, update `gridgain.version` in [pom.xml](/Users/iruffell/workspace/payments-demo/pom.xml).

## Run

GridGain 8 on Java 11+ requires several JVM `--add-opens` flags. Start your GridGain cluster first, then run the app with the cluster discovery addresses configured in [application.yml](/Users/iruffell/workspace/payments-demo/src/main/resources/application.yml) or via Spring properties:

```bash
export JAVA_TOOL_OPTIONS="--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.math=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.time=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/jdk.internal.access=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/sun.security.x509=ALL-UNNAMED -Dio.netty.tryReflectionSetAccessible=true"
export DEMO_GRIDGAIN_DISCOVERY_ADDRESSES_0=10.0.0.11:47500..47509
export DEMO_GRIDGAIN_DISCOVERY_ADDRESSES_1=10.0.0.12:47500..47509
mvn spring-boot:run
```

Then open `http://localhost:8080`.

If MySQL is your source of record, disable the demo seeding path so the app does not create overlapping reference data:

```bash
export DEMO_SEED_ENABLED=false
```

## External MySQL + Debezium

The repo now includes a starter MySQL schema and Debezium source connector config under [mysql/](</Users/iruffell/workspace/payments-demo/mysql>). The schema mirrors the GridGain-backed record types:

- `accounts`
- `merchants`
- `payments`
- `ledger_entries`

Typical flow:

1. Start Kafka, MySQL, and Kafka Connect with [mysql/docker.sh](/Users/iruffell/workspace/payments-demo/mysql/docker.sh). The script also loads [mysql/schema.sql](/Users/iruffell/workspace/payments-demo/mysql/schema.sql) into the `payments_demo` database.
2. Register the Debezium source connector with [register-source-connector.sh](/Users/iruffell/workspace/payments-demo/mysql/register-source-connector.sh), which applies [mysql/mysql-payments-source.json](/Users/iruffell/workspace/payments-demo/mysql/mysql-payments-source.json) to Kafka Connect.
3. Point your GridGain sink at the `accounts`, `merchants`, `payments`, and `ledger_entries` topics.

The source connector unwraps the Debezium envelope and routes topics to the cache names used by the demo. The current Spring Boot app still writes directly to GridGain for its REST workflows, so use the MySQL path for authoritative external writes if you want CDC to be the sole ingestion path.

## Main APIs

- `POST /api/payments/authorize`
- `POST /api/payments/{paymentId}/capture`
- `POST /api/payments/{paymentId}/refund`
- `GET /api/dashboard`
- `POST /api/simulator/start?ratePerSecond=120`
- `POST /api/simulator/stop`
- `POST /api/admin/merchants/{merchantId}/status?active=false`
- `POST /api/admin/fraud-threshold?value=68`

## Example authorize request

```bash
curl -X POST http://localhost:8080/api/payments/authorize \
  -H 'Content-Type: application/json' \
  -d '{
    "paymentId":"PAY-DEMO-0001",
    "accountId":"ACC-000001",
    "merchantId":"MER-00001",
    "amountMinor":2599,
    "currency":"GBP"
  }'
```

## Demo SQL

These are the same kinds of queries surfaced in the dashboard service:

```sql
SELECT declineReason, COUNT(*)
FROM Payment
WHERE createdAtEpochMs >= ?
  AND status = 'DECLINED'
GROUP BY declineReason
ORDER BY COUNT(*) DESC;

SELECT merchantId, COUNT(*), SUM(amountMinor)
FROM Payment
WHERE createdAtEpochMs >= ?
GROUP BY merchantId
ORDER BY COUNT(*) DESC
LIMIT 5;
```
