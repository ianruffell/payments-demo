#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${ROOT_DIR}"

export SINK_KAFKA_BOOTSTRAP_SERVERS="${SINK_KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
export SINK_GRIDGAIN_DISCOVERY_ADDRESSES="${SINK_GRIDGAIN_DISCOVERY_ADDRESSES:-127.0.0.1:47500,127.0.0.1:47501,127.0.0.1:47502}"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.math=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.time=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/jdk.internal.access=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/sun.security.x509=ALL-UNNAMED -Dio.netty.tryReflectionSetAccessible=true"

exec mvn -q -DskipTests spring-boot:run \
  -Dspring-boot.run.main-class=com.example.paymentsdemo.cdc.KafkaToGridGainSinkApplication
