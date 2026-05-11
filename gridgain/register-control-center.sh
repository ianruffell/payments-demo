#!/bin/sh

set -eu

MGMT_BIN="${GRIDGAIN_MANAGEMENT_BIN:-/opt/gridgain/bin/management.sh}"
MGMT_HOST="${GRIDGAIN_MANAGEMENT_HOST:-gg8-node1}"
MGMT_PORT="${GRIDGAIN_MANAGEMENT_PORT:-11211}"
CONTROL_CENTER_URI="${GRIDGAIN_CONTROL_CENTER_URI:-http://control-center-backend:3000}"
MAX_ATTEMPTS="${GRIDGAIN_BOOTSTRAP_MAX_ATTEMPTS:-90}"
SLEEP_SECONDS="${GRIDGAIN_BOOTSTRAP_SLEEP_SECONDS:-2}"

status_file="/tmp/control-center-status.txt"

attempt=0
echo "Waiting for GridGain management endpoint at ${MGMT_HOST}:${MGMT_PORT}..."
until "${MGMT_BIN}" --host "${MGMT_HOST}" --port "${MGMT_PORT}" --status >"${status_file}" 2>&1; do
    attempt=$((attempt + 1))
    if [ "${attempt}" -ge "${MAX_ATTEMPTS}" ]; then
        cat "${status_file}" >&2 || true
        echo "Timed out waiting for GridGain management endpoint." >&2
        exit 1
    fi
    sleep "${SLEEP_SECONDS}"
done

echo "Configuring Control Center URI ${CONTROL_CENTER_URI}..."
"${MGMT_BIN}" --host "${MGMT_HOST}" --port "${MGMT_PORT}" --uri "${CONTROL_CENTER_URI}" --yes

attempt=0
echo "Waiting for GridGain to connect to Control Center..."
while true; do
    if "${MGMT_BIN}" --host "${MGMT_HOST}" --port "${MGMT_PORT}" --status >"${status_file}" 2>&1; then
        if grep -q "Connection status: connected" "${status_file}" && grep -q "${CONTROL_CENTER_URI}" "${status_file}"; then
            cat "${status_file}"
            break
        fi
    fi

    attempt=$((attempt + 1))
    if [ "${attempt}" -ge "${MAX_ATTEMPTS}" ]; then
        cat "${status_file}" >&2 || true
        echo "Timed out waiting for GridGain to connect to Control Center." >&2
        exit 1
    fi
    sleep "${SLEEP_SECONDS}"
done

echo "Generating one-time Control Center token..."
"${MGMT_BIN}" --host "${MGMT_HOST}" --port "${MGMT_PORT}" --token
