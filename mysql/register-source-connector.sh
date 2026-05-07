#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"
CONNECTOR_NAME="${CONNECTOR_NAME:-payments-demo-mysql-source}"

curl -sS \
  -X PUT \
  -H "Accept: application/json" \
  -H "Content-Type: application/json" \
  "${CONNECT_URL}/connectors/${CONNECTOR_NAME}/config" \
  --data @"${SCRIPT_DIR}/mysql-payments-source.json"

echo
