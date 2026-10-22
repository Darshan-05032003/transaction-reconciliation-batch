#!/usr/bin/env bash
# Asks the application's health endpoint and exits 0 when it reports UP, 1 otherwise.
# Meant for cron, monitoring and container health checks.
set -euo pipefail

SCRIPT_NAME="$(basename "${BASH_SOURCE[0]}")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly SCRIPT_NAME SCRIPT_DIR PROJECT_ROOT
readonly LOG_DIR="${PROJECT_ROOT}/logs"
readonly LOG_FILE="${LOG_DIR}/check_health.log"

readonly EXIT_UP=0
readonly EXIT_DOWN=1
readonly EXIT_USAGE=2

usage() {
  cat <<USAGE
Usage: ${SCRIPT_NAME} [-H <host>] [-t <timeout>] [-h]

Calls <host>/actuator/health and reports whether the application is UP.

Options:
  -H  base URL of the application (default: \$RECON_API_URL or http://localhost:8083)
  -t  seconds to wait for the answer (default: 5)
  -h  show this help

Exit codes:
  ${EXIT_UP}  the application reports UP
  ${EXIT_DOWN}  the application is unreachable or reports anything else
  ${EXIT_USAGE}  invalid arguments
USAGE
}

log() {
  local level="$1"
  shift
  printf '%s [%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "${level}" "$*" | tee -a "${LOG_FILE}"
}

die_usage() {
  printf 'Error: %s\n\n' "$1" >&2
  usage >&2
  exit "${EXIT_USAGE}"
}

health_status() {
  local json="$1"
  if command -v jq >/dev/null 2>&1; then
    jq -r '.status // empty' <<< "${json}"
  else
    sed -nE 's/.*"status":[[:space:]]*"([A-Z_]+)".*/\1/p' <<< "${json}" | head -n 1
  fi
}

HOST="${RECON_API_URL:-http://localhost:8083}"
TIMEOUT=5

while getopts ":H:t:h" option; do
  case "${option}" in
    H) HOST="${OPTARG}" ;;
    t) TIMEOUT="${OPTARG}" ;;
    h) usage; exit "${EXIT_UP}" ;;
    :) die_usage "option -${OPTARG} requires a value" ;;
    *) die_usage "unknown option -${OPTARG}" ;;
  esac
done
shift $((OPTIND - 1))
[[ $# -eq 0 ]] || die_usage "unexpected argument: $1"
[[ "${TIMEOUT}" =~ ^[1-9][0-9]*$ ]] || die_usage "timeout must be a positive integer: ${TIMEOUT}"
HOST="${HOST%/}"

mkdir -p "${LOG_DIR}"
if ! command -v curl >/dev/null 2>&1; then
  log ERROR "curl was not found on PATH"
  exit "${EXIT_DOWN}"
fi

BODY_FILE="$(mktemp)"
trap 'rm -f "${BODY_FILE}"' EXIT
if ! HTTP_STATUS="$(curl -sS -o "${BODY_FILE}" -w '%{http_code}' --max-time "${TIMEOUT}" "${HOST}/actuator/health" 2>>"${LOG_FILE}")"; then
  log ERROR "DOWN: ${HOST} is unreachable"
  exit "${EXIT_DOWN}"
fi
STATUS="$(health_status "$(cat "${BODY_FILE}")")"
if [[ "${HTTP_STATUS}" == "200" && "${STATUS}" == "UP" ]]; then
  log INFO "UP: ${HOST} (HTTP ${HTTP_STATUS})"
  exit "${EXIT_UP}"
fi
log ERROR "DOWN: ${HOST} answered HTTP ${HTTP_STATUS} with status '${STATUS:-none}'"
exit "${EXIT_DOWN}"
