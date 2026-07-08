#!/bin/sh

set -eu

DOCKER_BIN="${DOCKER_BIN:-docker}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.yml}"
EXTERNAL_DB_TYPE="${DEMO_EXTERNAL_DB_TYPE:-${EXTERNAL_DB_TYPE:-oracle}}"
ORACLE_SERVICE="${ORACLE_SERVICE:-oracle-db}"
MARIADB_CLIENT_SERVICE="${MARIADB_CLIENT_SERVICE:-${MARIADB_SERVICE:-db1}}"
MARIADB_HOST="${MARIADB_HOST:-maxscale1}"
GRIDGAIN_SERVICE="${GRIDGAIN_SERVICE:-gg8-node1}"
APP_BASE_URL="${APP_BASE_URL:-http://localhost:8080}"
ORACLE_CONNECT="${ORACLE_CONNECT:-PAYMENTS_APP/payments_app@//localhost:1521/FREEPDB1}"
MARIADB_DATABASE="${MARIADB_DATABASE:-payments_app}"
MARIADB_USERNAME="${MARIADB_USERNAME:-payments_app}"
MARIADB_PASSWORD="${MARIADB_PASSWORD:-PaymentsApp123!}"
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

external_db_service() {
    case "${EXTERNAL_DB_TYPE}" in
        oracle)
            echo "${ORACLE_SERVICE}"
            ;;
        mariadb)
            echo "${MARIADB_CLIENT_SERVICE}"
            ;;
        *)
            echo "Unsupported EXTERNAL_DB_TYPE: ${EXTERNAL_DB_TYPE}" >&2
            exit 1
            ;;
    esac
}

clear_external_archive() {
    if [ "${CLEAR_ARCHIVE}" != "true" ]; then
        echo "Leaving external system-of-record tables unchanged."
        return
    fi

    case "${EXTERNAL_DB_TYPE}" in
        oracle)
            echo "Clearing Oracle archive tables in ${ORACLE_SERVICE}..."
            compose exec -T "${ORACLE_SERVICE}" sh -lc "cat <<'SQL' | sqlplus -s '${ORACLE_CONNECT}'
WHENEVER SQLERROR EXIT SQL.SQLCODE
DELETE FROM LEDGER_ENTRY_ARCHIVE;
DELETE FROM PAYMENT_ARCHIVE;
COMMIT;
EXIT;
SQL"
            ;;
        mariadb)
            echo "Clearing MariaDB archive tables through ${MARIADB_HOST} using ${MARIADB_CLIENT_SERVICE}..."
            compose exec -T "${MARIADB_CLIENT_SERVICE}" sh -lc "cat <<'SQL' | mariadb --protocol=TCP --ssl=0 -h'${MARIADB_HOST}' -u'${MARIADB_USERNAME}' -p'${MARIADB_PASSWORD}' '${MARIADB_DATABASE}'
DELETE FROM LEDGER_ENTRY_ARCHIVE;
DELETE FROM PAYMENT_ARCHIVE;
SQL"
            ;;
        *)
            echo "Unsupported EXTERNAL_DB_TYPE: ${EXTERNAL_DB_TYPE}" >&2
            exit 1
            ;;
    esac
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

require_service "$(external_db_service)"
require_service "${GRIDGAIN_SERVICE}"

stop_simulator
clear_external_archive
clear_gridgain

echo "${EXTERNAL_DB_TYPE} and GridGain demo data cleared."
