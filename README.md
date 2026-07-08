# GridGain Payments Demo

Small real-time card-transaction demo built with Java, Spring Boot, Oracle or MariaDB, Debezium, GridGain, and a browser dashboard.

## What it models

- `accounts`: balance, currency, status, risk tier
- `merchants`: category, country, per-transaction max, daily limit, active flag
- `payments`: authorize, capture, refund lifecycle plus fraud score and decline reason
- `ledger_entries`: immutable debit and credit records for holds, captures, and refunds

## What it shows

1. Treat an external database, Oracle or MariaDB, as the system of record for reference data and completed payments.
2. Use Debezium to project `accounts` and `merchants` changes into GridGain, which acts as the live cache for in-flight transactions.
3. Keep active payments in GridGain while they are moving through auth, merchant review, capture, and refund handling.
4. Archive terminal payments back to the external database asynchronously, then evict them from GridGain once the external write succeeds.
5. Load `100k` accounts and `10` merchants on startup when the stores are empty.
6. Start a simulator to generate authorize traffic, automatic captures, and occasional refunds.
7. Watch live throughput, approval rate, declines by reason, top merchants, and suspicious payments at `http://localhost:8080`.
8. Trigger a merchant outage or lower the fraud threshold from the dashboard and watch the metrics change immediately.
9. Use the AI Investigation page to search for semantically similar payments with MariaDB vector search.

## Run

The local stack is Docker Compose. Choose the external database with the matching env file:

```bash
docker compose --env-file .env.oracle up --build
```

or:

```bash
docker compose --env-file .env.mariadb up --build
```

To include GridGain Control Center, add the Control Center compose file:

```bash
docker compose --env-file .env.oracle -f docker-compose.yml -f docker-compose.control-center.yml up --build
```

or:

```bash
docker compose --env-file .env.mariadb -f docker-compose.yml -f docker-compose.control-center.yml up --build
```

That starts:

- Oracle Free on `localhost:1521` with `FREEPDB1`, or a three-node MariaDB Galera cluster with MaxScale SQL listeners on `localhost:4006` and `localhost:4007`
- MaxScale admin/UI on `http://localhost:8989` and `http://localhost:8990` when running the MariaDB profile
- Kafka and Kafka Connect with the matching Debezium connector
- A three-node GridGain cluster
- The Spring Boot processor on `http://localhost:8080`
- Ten merchant simulator containers
- The payment initiator and the external DB-to-GridGain CDC sink

Then open `http://localhost:8080`, `http://localhost:8080/flow.html`, or `http://localhost:8080/investigation.html`. If you included Control Center, open `http://localhost:8008`.

## Run Without Compose

GridGain 8 on Java 11+ requires several JVM `--add-opens` flags. Start your GridGain cluster first, then run the app with the cluster discovery addresses configured in [application.yml](/Users/iruffell/workspace/payments-demo/src/main/resources/application.yml) or via Spring properties:

```bash
export JAVA_TOOL_OPTIONS="--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.math=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.time=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/jdk.internal.access=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/sun.security.x509=ALL-UNNAMED -Dio.netty.tryReflectionSetAccessible=true"
export DEMO_GRIDGAIN_DISCOVERY_ADDRESSES_0=10.0.0.11:47500..47509
export DEMO_GRIDGAIN_DISCOVERY_ADDRESSES_1=10.0.0.12:47500..47509
mvn spring-boot:run
```

For the MariaDB profile, the compose stack uses MariaDB Enterprise images from `docker.mariadb.com`. Log in to that registry before starting it if Docker does not already have credentials.

For a full local Debezium stack in this mode, you also need one external database configured through Spring properties:

- Oracle reachable at `jdbc:oracle:thin:@//oracle-db:1521/FREEPDB1` with `demo.external-db.type=oracle`, or MariaDB reachable through MaxScale at `jdbc:mariadb://maxscale1:3306/payments_app` with `demo.external-db.type=mariadb`
- Kafka reachable at `kafka:9092`
- A Kafka Connect worker with the matching Debezium connector; Oracle also requires `ojdbc11.jar`

## Main APIs

- `POST /api/payments/authorize`
- `POST /api/payments/{paymentId}/capture`
- `POST /api/payments/{paymentId}/refund`
- `GET /api/dashboard`
- `POST /api/simulator/start?ratePerSecond=120`
- `POST /api/simulator/stop`
- `POST /api/admin/merchants/{merchantId}/status?active=false`
- `POST /api/admin/fraud-threshold?value=68`
- `POST /api/investigation/semantic`

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
