#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NETWORK_NAME="payments-demo-cdc"
KAFKA_CONTAINER="payments-demo-kafka"
MYSQL_CONTAINER="payments-demo-mysql"
CONNECT_CONTAINER="payments-demo-connect"

docker network inspect "${NETWORK_NAME}" >/dev/null 2>&1 || docker network create "${NETWORK_NAME}" >/dev/null

for container in "${KAFKA_CONTAINER}" "${MYSQL_CONTAINER}" "${CONNECT_CONTAINER}"; do
    docker rm -f "${container}" >/dev/null 2>&1 || true
done

docker run -d --rm \
  --network "${NETWORK_NAME}" \
  --name "${KAFKA_CONTAINER}" \
  --hostname "${KAFKA_CONTAINER}" \
  -p 9092:9092 \
  -e CLUSTER_ID=payments_demo_kafka_cluster \
  -e NODE_ID=1 \
  -e NODE_ROLE=combined \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@"${KAFKA_CONTAINER}":9093 \
  -e KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://"${KAFKA_CONTAINER}":9092 \
  quay.io/debezium/kafka:3.5

docker run -d --rm \
  --network "${NETWORK_NAME}" \
  --name "${MYSQL_CONTAINER}" \
  -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=debezium \
  -e MYSQL_USER=debezium \
  -e MYSQL_PASSWORD=debezium \
  -e MYSQL_DATABASE=payments_demo \
  quay.io/debezium/example-mysql:3.5

docker run -d --rm \
  --network "${NETWORK_NAME}" \
  --name "${CONNECT_CONTAINER}" \
  -p 8083:8083 \
  -e BOOTSTRAP_SERVERS="${KAFKA_CONTAINER}:9092" \
  -e GROUP_ID=1 \
  -e CONFIG_STORAGE_TOPIC=my_connect_configs \
  -e OFFSET_STORAGE_TOPIC=my_connect_offsets \
  -e STATUS_STORAGE_TOPIC=my_connect_statuses \
  -e KEY_CONVERTER=org.apache.kafka.connect.json.JsonConverter \
  -e VALUE_CONVERTER=org.apache.kafka.connect.json.JsonConverter \
  -e KEY_CONVERTER_SCHEMAS_ENABLE=false \
  -e VALUE_CONVERTER_SCHEMAS_ENABLE=false \
  quay.io/debezium/connect:3.5

for _ in $(seq 1 30); do
    if docker exec "${MYSQL_CONTAINER}" mysql -uroot -pdebezium -e "SELECT 1" >/dev/null 2>&1; then
        break
    fi
    sleep 2
done

docker exec -i "${MYSQL_CONTAINER}" mysql -uroot -pdebezium < "${SCRIPT_DIR}/schema.sql"

echo "CDC stack is up."
echo "MySQL schema loaded into payments_demo."
echo "Register the Debezium connector with: ${SCRIPT_DIR}/register-source-connector.sh"
echo "Kafka Connect endpoint: http://localhost:8083"
