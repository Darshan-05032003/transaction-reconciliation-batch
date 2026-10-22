#!/usr/bin/env bash
# Runs one statement file through the API: ingest, wait, reconcile, wait, print the run
# summary. Exits non-zero when anything did not complete, when the file was a duplicate or
# was rejected, or when the run produced more breaks than the threshold allows.
set -euo pipefail

SCRIPT_NAME="$(basename "${BASH_SOURCE[0]}")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly SCRIPT_NAME SCRIPT_DIR PROJECT_ROOT
readonly LOG_DIR="${PROJECT_ROOT}/logs"
readonly LOG_FILE="${LOG_DIR}/run_recon.log"
readonly POLL_SECONDS=2

readonly EXIT_OK=0
readonly EXIT_USAGE=2
readonly EXIT_PREREQ=3
readonly EXIT_API=4
readonly EXIT_JOB_FAILED=5
readonly EXIT_TIMEOUT=6
readonly EXIT_DUPLICATE=7
readonly EXIT_REJECTED=8
readonly EXIT_BREAKS=9

usage() {
  cat <<USAGE
Usage: ${SCRIPT_NAME} -f <file> [-u <user>] [-p <password>] [-H <host>] [-t <timeout>] [-b <break-threshold>] [-h]

Ingests a statement file from the inbox through the API, reconciles it and prints the
run summary. A path is reduced to its file name; the application resolves it in its inbox.

Options:
  -f  statement file in the inbox (required)
  -u  API user with the OPERATOR role (default: \$RECON_API_USER or operator)
  -p  API password (default: \$RECON_API_PASSWORD; prefer the variable, -p is visible in ps)
  -H  base URL of the application (default: \$RECON_API_URL or http://localhost:8083)
  -t  seconds to wait for each job before giving up (default: 600)
  -b  maximum number of breaks that still counts as success (default: no limit)
  -h  show this help

Exit codes:
  ${EXIT_OK}  reconciled, breaks within the threshold
  ${EXIT_USAGE}  invalid arguments
  ${EXIT_PREREQ}  missing prerequisite (curl)
  ${EXIT_API}  the API refused a request or could not be reached
  ${EXIT_JOB_FAILED}  a job ended FAILED or STOPPED
  ${EXIT_TIMEOUT}  a job did not finish within the timeout
  ${EXIT_DUPLICATE}  the file was already ingested (DUPLICATE)
  ${EXIT_REJECTED}  the file failed its control totals (REJECTED)
  ${EXIT_BREAKS}  the run has more breaks than the threshold
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

# json_field <json> <field>: a top-level scalar; jq, then python3, then a sed fallback.
json_field() {
  local json="$1" field="$2"
  if command -v jq >/dev/null 2>&1; then
    jq -r --arg f "${field}" '.[$f] // empty' <<< "${json}"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -I -c 'import json, sys
value = json.load(sys.stdin).get(sys.argv[1])
print("" if value is None else value)' "${field}" <<< "${json}"
  else
    sed -nE "s/.*\"${field}\":[[:space:]]*\"?([^,\"}]*)\"?.*/\1/p" <<< "${json}" | head -n 1
  fi
}

# api_call <method> <path> [json-body]: sets HTTP_STATUS and HTTP_BODY. Credentials go
# through curl's config on stdin, so the password never appears on a command line.
api_call() {
  local method="$1" path="$2" body="${3:-}"
  local args=(-sS -o "${BODY_FILE}" -w '%{http_code}' -X "${method}" -K - -H 'Accept: application/json')
  if [[ -n "${body}" ]]; then
    args+=(-H 'Content-Type: application/json' --data "${body}")
  fi
  HTTP_STATUS="$(curl "${args[@]}" "${HOST}${path}" <<< "user = \"${API_USER}:${API_PASSWORD}\"")" || {
    log ERROR "Could not reach ${HOST}${path}"
    exit "${EXIT_API}"
  }
  HTTP_BODY="$(cat "${BODY_FILE}")"
}

require_status() {
  local expected="$1" what="$2"
  if [[ "${HTTP_STATUS}" != "${expected}" ]]; then
    log ERROR "${what} answered HTTP ${HTTP_STATUS}: $(json_field "${HTTP_BODY}" detail)"
    exit "${EXIT_API}"
  fi
}

# wait_for_execution <execution-id>: polls until the execution is finished; sets
# EXECUTION_STATUS, EXECUTION_EXIT_CODE and EXECUTION_JSON.
wait_for_execution() {
  local execution_id="$1"
  local deadline=$((SECONDS + TIMEOUT))
  while true; do
    api_call GET "/api/v1/jobs/executions/${execution_id}"
    require_status 200 "Execution ${execution_id}"
    EXECUTION_STATUS="$(json_field "${HTTP_BODY}" status)"
    case "${EXECUTION_STATUS}" in
      COMPLETED|FAILED|STOPPED|ABANDONED)
        EXECUTION_EXIT_CODE="$(json_field "${HTTP_BODY}" exitCode)"
        EXECUTION_JSON="${HTTP_BODY}"
        return 0
        ;;
    esac
    if (( SECONDS >= deadline )); then
      log ERROR "Execution ${execution_id} is still ${EXECUTION_STATUS} after ${TIMEOUT}s"
      exit "${EXIT_TIMEOUT}"
    fi
    sleep "${POLL_SECONDS}"
  done
}

print_summary() {
  local json="$1"
  if command -v jq >/dev/null 2>&1; then
    jq -r '"run \(.reconRunId) for \(.fileName) (statement date \(.statementDate)): \(.status)",
           "  matched:    \(.matchedCount)",
           "  breaks:     \(.breakCount)",
           "  match rate: \(.matchRatePercent)%",
           (.breaksByType | to_entries[] | "  \(.key): \(.value)")' <<< "${json}" | tee -a "${LOG_FILE}"
  else
    log INFO "run $(json_field "${json}" reconRunId): matched $(json_field "${json}" matchedCount), breaks $(json_field "${json}" breakCount), match rate $(json_field "${json}" matchRatePercent)%"
    printf '%s\n' "${json}" | tee -a "${LOG_FILE}"
  fi
}

FILE=""
API_USER="${RECON_API_USER:-operator}"
API_PASSWORD="${RECON_API_PASSWORD:-}"
HOST="${RECON_API_URL:-http://localhost:8083}"
TIMEOUT=600
BREAK_THRESHOLD=""

while getopts ":f:u:p:H:t:b:h" option; do
  case "${option}" in
    f) FILE="${OPTARG}" ;;
    u) API_USER="${OPTARG}" ;;
    p) API_PASSWORD="${OPTARG}" ;;
    H) HOST="${OPTARG}" ;;
    t) TIMEOUT="${OPTARG}" ;;
    b) BREAK_THRESHOLD="${OPTARG}" ;;
    h) usage; exit "${EXIT_OK}" ;;
    :) die_usage "option -${OPTARG} requires a value" ;;
    *) die_usage "unknown option -${OPTARG}" ;;
  esac
done
shift $((OPTIND - 1))
[[ $# -eq 0 ]] || die_usage "unexpected argument: $1"

[[ -n "${FILE}" ]] || die_usage "-f <file> is required"
[[ -n "${API_PASSWORD}" ]] || die_usage "an API password is required (-p or RECON_API_PASSWORD)"
[[ "${TIMEOUT}" =~ ^[1-9][0-9]*$ ]] || die_usage "timeout must be a positive integer: ${TIMEOUT}"
[[ -z "${BREAK_THRESHOLD}" || "${BREAK_THRESHOLD}" =~ ^[0-9]+$ ]] || die_usage "break threshold must be a non-negative integer: ${BREAK_THRESHOLD}"
HOST="${HOST%/}"
FILE_NAME="$(basename "${FILE}")"

mkdir -p "${LOG_DIR}"
if ! command -v curl >/dev/null 2>&1; then
  log ERROR "curl was not found on PATH"
  exit "${EXIT_PREREQ}"
fi
BODY_FILE="$(mktemp)"
trap 'rm -f "${BODY_FILE}"' EXIT

log INFO "Ingesting ${FILE_NAME} through ${HOST} as ${API_USER}"
api_call POST "/api/v1/jobs/ingest" "{\"filePath\": \"${FILE_NAME}\"}"
require_status 202 "Ingest launch"
INGEST_EXECUTION_ID="$(json_field "${HTTP_BODY}" executionId)"
log INFO "ingestStatementJob started as execution ${INGEST_EXECUTION_ID}"

wait_for_execution "${INGEST_EXECUTION_ID}"
log INFO "ingestStatementJob execution ${INGEST_EXECUTION_ID} ended ${EXECUTION_STATUS} with exit code ${EXECUTION_EXIT_CODE}"
case "${EXECUTION_STATUS}/${EXECUTION_EXIT_CODE}" in
  COMPLETED/COMPLETED) ;;
  COMPLETED/DUPLICATE)
    log WARN "${FILE_NAME} was already ingested; nothing was loaded"
    exit "${EXIT_DUPLICATE}"
    ;;
  COMPLETED/REJECTED)
    log ERROR "${FILE_NAME} was rejected: control totals do not match the file"
    exit "${EXIT_REJECTED}"
    ;;
  *)
    log ERROR "Ingest did not complete: $(json_field "${EXECUTION_JSON}" exitDescription)"
    exit "${EXIT_JOB_FAILED}"
    ;;
esac
STATEMENT_FILE_ID="$(json_field "${EXECUTION_JSON}" statementFileId)"

log INFO "Reconciling statement_file ${STATEMENT_FILE_ID}"
api_call POST "/api/v1/jobs/reconcile/${STATEMENT_FILE_ID}"
require_status 202 "Reconcile launch"
RECONCILE_EXECUTION_ID="$(json_field "${HTTP_BODY}" executionId)"
log INFO "reconcileJob started as execution ${RECONCILE_EXECUTION_ID}"

wait_for_execution "${RECONCILE_EXECUTION_ID}"
log INFO "reconcileJob execution ${RECONCILE_EXECUTION_ID} ended ${EXECUTION_STATUS} with exit code ${EXECUTION_EXIT_CODE}"
if [[ "${EXECUTION_STATUS}" != "COMPLETED" ]]; then
  log ERROR "Reconciliation did not complete: $(json_field "${EXECUTION_JSON}" exitDescription)"
  log ERROR "Fix the cause, then restart with: curl -u ${API_USER} -X POST ${HOST}/api/v1/jobs/executions/${RECONCILE_EXECUTION_ID}/restart"
  exit "${EXIT_JOB_FAILED}"
fi
RECON_RUN_ID="$(json_field "${EXECUTION_JSON}" reconRunId)"

api_call GET "/api/v1/recon-runs/${RECON_RUN_ID}/summary"
require_status 200 "Run summary"
print_summary "${HTTP_BODY}"
BREAK_COUNT="$(json_field "${HTTP_BODY}" breakCount)"

if [[ -n "${BREAK_THRESHOLD}" ]] && (( BREAK_COUNT > BREAK_THRESHOLD )); then
  log ERROR "Run ${RECON_RUN_ID} has ${BREAK_COUNT} breaks, above the threshold of ${BREAK_THRESHOLD}"
  exit "${EXIT_BREAKS}"
fi
log INFO "Run ${RECON_RUN_ID} finished with ${BREAK_COUNT} breaks"
exit "${EXIT_OK}"
