#!/usr/bin/env bash
# Fetch the Prometheus JMX exporter java agent used by the GridGain and Kafka Connect
# services (spec 010). Run this once before starting the observability overlay:
#
#   ./observability/jmx/download-agents.sh
#   docker compose --env-file .env.mariadb -f docker-compose.yml -f docker-compose.observability.yml up --build
#
# The agent jar is mounted into the GridGain and Debezium Connect containers and attached
# via -javaagent (see docker-compose.observability.yml).
set -euo pipefail

VERSION="${JMX_EXPORTER_VERSION:-1.0.1}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${DIR}/jmx_prometheus_javaagent.jar"
URL="https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/${VERSION}/jmx_prometheus_javaagent-${VERSION}.jar"

if [[ -f "${JAR}" ]]; then
  echo "JMX exporter agent already present at ${JAR}"
  exit 0
fi

echo "Downloading jmx_prometheus_javaagent ${VERSION} ..."
curl -fsSL "${URL}" -o "${JAR}"
echo "Saved ${JAR}"
