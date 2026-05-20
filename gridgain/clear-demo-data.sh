#!/bin/sh

set -eu

DOCKER_BIN="${DOCKER_BIN:-docker}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.yml}"
ORACLE_SERVICE="${ORACLE_SERVICE:-oracle-db}"
GRIDGAIN_SERVICE="${GRIDGAIN_SERVICE:-gg8-node1}"
APP_BASE_URL="${APP_BASE_URL:-http://localhost:8080}"
ORACLE_CONNECT="${ORACLE_CONNECT:-PAYMENTS_APP/payments_app@//localhost:1521/FREEPDB1}"
SQLLINE_BIN="${SQLLINE_BIN:-/opt/gridgain/bin/sqlline.sh}"
SQLLINE_URL="${SQLLINE_URL:-jdbc:ignite:thin://127.0.0.1:10800}"
CLEAR_ARCHIVE="${CLEAR_ARCHIVE:-false}"

compose() {
    "${DOCKER_BIN}" compose -f "${COMPOSE_FILE}" "$@"
}

require_service() {
    service_name="$1"

    if ! compose ps --status running --services | grep -Fx "${service_name}" >/dev/null 2>&1; then
        echo "Required service is not running: ${service_name}" >&2
        exit 1
    fi
}

stop_simulator() {
    if command -v curl >/dev/null 2>&1; then
        if curl -fsS -X POST "${APP_BASE_URL}/api/simulator/stop" >/dev/null 2>&1; then
            echo "Stopped simulator via ${APP_BASE_URL}/api/simulator/stop"
            return
        fi
    fi

    echo "Simulator stop endpoint unavailable, continuing with data reset."
}

clear_oracle() {
    if [ "${CLEAR_ARCHIVE}" != "true" ]; then
        echo "Leaving Oracle system-of-record tables unchanged."
        return
    fi

    echo "Clearing Oracle archive tables in ${ORACLE_SERVICE}..."
    compose exec -T "${ORACLE_SERVICE}" sh -lc "cat <<'SQL' | sqlplus -s '${ORACLE_CONNECT}'
WHENEVER SQLERROR EXIT SQL.SQLCODE
DELETE FROM LEDGER_ENTRY_ARCHIVE;
DELETE FROM PAYMENT_ARCHIVE;
COMMIT;
EXIT;
SQL"
}

clear_gridgain() {
    echo "Clearing transient GridGain SQL tables in ${GRIDGAIN_SERVICE}..."
    compose exec -T "${GRIDGAIN_SERVICE}" sh -lc "cat <<'SQL' | '${SQLLINE_BIN}' -u '${SQLLINE_URL}' --silent=true --verbose=false
DELETE FROM MerchantPaymentAttempt;
DELETE FROM LedgerEntry;
DELETE FROM Payment;
!quit
SQL"
}

require_service "${ORACLE_SERVICE}"
require_service "${GRIDGAIN_SERVICE}"

stop_simulator
clear_oracle
clear_gridgain

echo "Oracle and GridGain demo data cleared."
