#!/usr/bin/env bash

set -euo pipefail

for container in gridgain-node-1 gridgain-node-2 gridgain-node-3; do
    docker rm -f "${container}" >/dev/null 2>&1 || true
done
