#!/bin/sh
set -eu

CONNECT_URL="http://debezium-connect:8083/connectors/paymentsdemo-oracle-reference/config"

until curl -fsS http://debezium-connect:8083/connectors >/dev/null 2>&1; do
  sleep 5
done

while true; do
  http_code="$(curl -sS -o /tmp/oracle-connector-response.json -w '%{http_code}' \
    -X PUT \
    -H 'Content-Type: application/json' \
    --data @/config/oracle-source-connector.json \
    "${CONNECT_URL}" || true)"

  if [ "${http_code}" = "200" ] || [ "${http_code}" = "201" ]; then
    cat /tmp/oracle-connector-response.json
    exit 0
  fi

  sleep 5
done
