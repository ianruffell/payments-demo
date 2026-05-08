#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NETWORK_NAME="${NETWORK_NAME:-payments-demo-network}"
IMAGE="${GRIDGAIN_IMAGE:-gridgain/community:8.9.30}"
CONFIG_FILE="${SCRIPT_DIR}/ignite-server-config.xml"
OPTION_LIBS="${GRIDGAIN_OPTION_LIBS:-ignite-log4j,ignite-spring,ignite-indexing,ignite-opencensus,control-center-agent}"
JVM_OPTS_VALUE="${GRIDGAIN_JVM_OPTS:-}"
if [[ -n "${CONTROL_CENTER_AGENT_URIS:-}" ]]; then
    JVM_OPTS_VALUE="${JVM_OPTS_VALUE} -Dcontrol.center.agent.uris=${CONTROL_CENTER_AGENT_URIS}"
fi
GRIDGAIN_CONTAINERS=(gridgain-node-1 gridgain-node-2 gridgain-node-3)
HOST_DISCOVERY_PORTS=(47500 47501 47502)
HOST_COMMUNICATION_PORTS=(47100 47101 47102)
HOST_CLIENT_PORTS=(10800 10801 10802)

docker network inspect "${NETWORK_NAME}" >/dev/null 2>&1 || docker network create "${NETWORK_NAME}" >/dev/null

for index in "${!GRIDGAIN_CONTAINERS[@]}"; do
    container="${GRIDGAIN_CONTAINERS[$index]}"
    docker rm -f "${container}" >/dev/null 2>&1 || true

    docker run -d --rm \
      --network "${NETWORK_NAME}" \
      --name "${container}" \
      --hostname "${container}" \
      -p "${HOST_DISCOVERY_PORTS[$index]}":47500 \
      -p "${HOST_COMMUNICATION_PORTS[$index]}":47100 \
      -p "${HOST_CLIENT_PORTS[$index]}":10800 \
      -v "${CONFIG_FILE}:/ignite-config.xml:ro" \
      -e CONFIG_URI=/ignite-config.xml \
      -e OPTION_LIBS="${OPTION_LIBS}" \
      -e JVM_OPTS="${JVM_OPTS_VALUE}" \
      "${IMAGE}" >/dev/null
done

echo "Started GridGain containers:"
printf ' - %s\n' "${GRIDGAIN_CONTAINERS[@]}"
echo "Discovery from host: 127.0.0.1:47500,127.0.0.1:47501,127.0.0.1:47502"
echo "Thin client ports: 10800,10801,10802"
echo "Enabled modules: ${OPTION_LIBS}"
if [[ -n "${CONTROL_CENTER_AGENT_URIS:-}" ]]; then
    echo "Control Center URIs: ${CONTROL_CENTER_AGENT_URIS}"
fi
