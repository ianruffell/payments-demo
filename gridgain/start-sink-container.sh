#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
NETWORK_NAME="${NETWORK_NAME:-payments-demo-cdc}"
CONTAINER_NAME="${SINK_CONTAINER_NAME:-payments-demo-sink}"
MAVEN_IMAGE="${SINK_MAVEN_IMAGE:-maven:3.9-eclipse-temurin-21}"
MAVEN_CONFIG_DIR="${MAVEN_CONFIG_DIR:-${HOME}/.m2}"

docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true

docker run -d \
  --name "${CONTAINER_NAME}" \
  --network "${NETWORK_NAME}" \
  --user "$(id -u):$(id -g)" \
  --add-host kafka:host-gateway \
  -e HOME=/tmp \
  -e MAVEN_CONFIG=/tmp/.m2 \
  -e SINK_KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  -e SINK_GRIDGAIN_DISCOVERY_ADDRESSES=gridgain-node-1:47500,gridgain-node-2:47500,gridgain-node-3:47500 \
  -v "${ROOT_DIR}:/workspace" \
  -v "${MAVEN_CONFIG_DIR}:/tmp/.m2" \
  -w /workspace \
  "${MAVEN_IMAGE}" \
  ./gridgain/start-sink.sh >/dev/null

echo "Started sink container ${CONTAINER_NAME}"
