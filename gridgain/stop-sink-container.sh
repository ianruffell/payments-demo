#!/usr/bin/env bash

set -euo pipefail

docker rm -f "${SINK_CONTAINER_NAME:-payments-demo-sink}" >/dev/null 2>&1 || true
