#!/usr/bin/env bash
# Measures how long ingest and reconcile take for different chunk sizes and grid sizes.
# For every chunk size the script starts the application with that RECON_CHUNK_SIZE, then
# for every repetition and row count it generates a fresh data set, ingests it through the
# API and reconciles it once per grid size. Every job is recorded as one CSV row with the
# durations and counts the application itself reported, so the numbers can be re-derived.
set -euo pipefail

SCRIPT_NAME="$(basename "${BASH_SOURCE[0]}")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly SCRIPT_NAME SCRIPT_DIR PROJECT_ROOT
readonly LOG_DIR="${PROJECT_ROOT}/logs"
readonly LOG_FILE="${LOG_DIR}/benchmark.log"
readonly JAR_GLOB="${PROJECT_ROOT}/target/transaction-reconciliation-batch-*.jar"
readonly POLL_SECONDS=1
readonly STARTUP_TIMEOUT_SECONDS=120
readonly CSV_HEADER="recorded_at,rows,chunk_size,grid_size,repetition,seed,phase,execution_id,status,exit_code,duration_ms,step_name,step_ms,read_count,write_count,filter_count,skip_count,commit_count,matched,breaks,expected_breaks,app_vmhwm_kb"

readonly EXIT_OK=0
readonly EXIT_USAGE=2
readonly EXIT_PREREQ=3
readonly EXIT_APP_START=4
readonly EXIT_GENERATE=5
readonly EXIT_JOB=6
readonly EXIT_TIMEOUT=7

usage() {
  cat <<USAGE
Usage: ${SCRIPT_NAME} [-n <rows,...>] [-c <chunk,...>] [-g <grid,...>] [-r <repetitions>] [-d <date>]
                    [-s <seed>] [-o <output-dir>] [-j <jar>] [-t <timeout>] [-h]

For every chunk size: starts the application with RECON_CHUNK_SIZE set to it, and for every
repetition and row count generates a fresh data set (a new seed each time), ingests it
through the API and reconciles it once per grid size (the second and later grid sizes
re-run the same file with rerun=true). Appends one row per job to <output-dir>/benchmark_<timestamp>.csv.

Options:
  -n  row counts, comma separated (default: 100000,1000000)
  -c  chunk sizes, comma separated (default: 100,500,2000)
  -g  grid sizes, comma separated; the application's thread pool is sized to the largest (default: 1,2,4)
  -r  repetitions per combination (default: 2)
  -d  statement date of the generated data (default: 2026-10-01)
  -s  first seed; every generated data set uses the next integer (default: 7000)
  -o  output folder for the CSV (default: data/benchmark)
  -j  application jar (default: target/transaction-reconciliation-batch-*.jar)
  -t  seconds to wait for one job (default: 3600)
  -h  show this help

Environment:
  DB_URL, DB_USERNAME, DB_PASSWORD   database connection, same variables as the application
  RECON_API_USER, RECON_API_PASSWORD operator user the started application is seeded with (password required)
  RECON_SKIP_LIMIT                   skip limit for the started application (default: 2% of the largest row count)
  SERVER_PORT                        port the application is started on (default: 8083)
  JAVA_OPTS                          extra JVM options for the application and the generator, for example -Xmx256m

Requirements: java 21+, curl, jq, PostgreSQL reachable at DB_URL, and no application already
listening on the port. The generated files stay in the inbox and the database keeps the
rows, so start from a fresh database when comparing runs.

Exit codes:
  ${EXIT_OK}  every job completed
  ${EXIT_USAGE}  invalid arguments
  ${EXIT_PREREQ}  missing prerequisite (java, curl, jq, jar)
  ${EXIT_APP_START}  the application did not start or the port is in use
  ${EXIT_GENERATE}  the generator failed
  ${EXIT_JOB}  a job did not complete or the API refused a request
  ${EXIT_TIMEOUT}  a job did not finish within the timeout
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

# split_list <name> <comma-separated positive integers>: fills the named array.
split_list() {
  local -n target="$1"
  local raw="$2" item
  IFS=',' read -r -a target <<< "${raw}"
  [[ ${#target[@]} -gt 0 ]] || die_usage "a list of numbers is required, got '${raw}'"
  for item in "${target[@]}"; do
    [[ "${item}" =~ ^[1-9][0-9]*$ ]] || die_usage "not a positive integer: '${item}' in '${raw}'"
  done
}

find_jar() {
  local candidates=() candidate
  for candidate in ${JAR_GLOB}; do
    [[ -f "${candidate}" && "${candidate}" != *.original ]] && candidates+=("${candidate}")
  done
  if [[ ${#candidates[@]} -ne 1 ]]; then
    log ERROR "Expected exactly one application jar under target/ (build it with ./mvnw -DskipTests package), found ${#candidates[@]}; pass one with -j"
    exit "${EXIT_PREREQ}"
  fi
  JAR="${candidates[0]}"
}

check_prerequisites() {
  local tool
  for tool in java curl jq; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
      log ERROR "${tool} was not found on PATH"
      exit "${EXIT_PREREQ}"
    fi
  done
  [[ -n "${JAR}" ]] || find_jar
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
    exit "${EXIT_JOB}"
  }
  HTTP_BODY="$(cat "${BODY_FILE}")"
}

require_status() {
  local expected="$1" what="$2"
  if [[ "${HTTP_STATUS}" != "${expected}" ]]; then
    log ERROR "${what} answered HTTP ${HTTP_STATUS}: $(jq -r '.detail // empty' <<< "${HTTP_BODY}")"
    exit "${EXIT_JOB}"
  fi
}

app_is_listening() {
  curl -sS -o /dev/null --max-time 2 "${HOST}/actuator/health" 2>/dev/null
}

# start_app <chunk-size>: starts the application with the chunk size under test and waits
# until it answers on /actuator/health. The pool is sized to the largest grid size.
start_app() {
  local chunk="$1"
  local app_log="${LOG_DIR}/benchmark_app_chunk${chunk}.log"
  local java_opts=()
  read -r -a java_opts <<< "${JAVA_OPTS:-}"
  if app_is_listening; then
    log ERROR "Something already answers on ${HOST}; stop it or set SERVER_PORT"
    exit "${EXIT_APP_START}"
  fi
  log INFO "Starting the application with chunk size ${chunk}, grid size ${MAX_GRID}, skip limit ${SKIP_LIMIT} (log: ${app_log})"
  (
    cd "${PROJECT_ROOT}"
    RECON_CHUNK_SIZE="${chunk}" RECON_GRID_SIZE="${MAX_GRID}" RECON_SKIP_LIMIT="${SKIP_LIMIT}" RECON_POLL_CRON='-' SERVER_PORT="${PORT}" \
    RECON_DEV_OPERATOR_USERNAME="${API_USER}" RECON_DEV_OPERATOR_PASSWORD="${API_PASSWORD}" \
      exec java "${java_opts[@]}" -jar "${JAR}" --spring.profiles.active=dev
  ) >"${app_log}" 2>&1 &
  APP_PID=$!
  local waited=0
  until app_is_listening; do
    if ! kill -0 "${APP_PID}" 2>/dev/null; then
      log ERROR "The application exited during start-up, see ${app_log}"
      APP_PID=""
      exit "${EXIT_APP_START}"
    fi
    if (( waited >= STARTUP_TIMEOUT_SECONDS )); then
      log ERROR "The application did not answer within ${STARTUP_TIMEOUT_SECONDS}s, see ${app_log}"
      exit "${EXIT_APP_START}"
    fi
    sleep 1
    waited=$((waited + 1))
  done
  log INFO "Application pid ${APP_PID} is up after ${waited}s"
}

stop_app() {
  if [[ -n "${APP_PID}" ]] && kill -0 "${APP_PID}" 2>/dev/null; then
    log INFO "Stopping application pid ${APP_PID}"
    kill "${APP_PID}"
    wait "${APP_PID}" 2>/dev/null || true
  fi
  APP_PID=""
}

app_vmhwm_kb() {
  if [[ -n "${APP_PID}" && -r "/proc/${APP_PID}/status" ]]; then
    awk '/^VmHWM:/ {print $2}' "/proc/${APP_PID}/status"
  else
    printf ''
  fi
}

# millis_between <iso-start> <iso-end>: difference in milliseconds, empty when unknown.
millis_between() {
  local start="$1" end="$2"
  if [[ -z "${start}" || -z "${end}" || "${start}" == null || "${end}" == null ]]; then
    printf ''
    return
  fi
  local start_ms end_ms
  start_ms="$(date -d "${start}" '+%s%3N')"
  end_ms="$(date -d "${end}" '+%s%3N')"
  printf '%d' $((end_ms - start_ms))
}

# generate <rows> <seed> <chunk>: runs the generator; sets DATA_FILE, EXPECTED_BREAKS and GENERATE_MS.
generate() {
  local rows="$1" seed="$2" chunk="$3"
  local java_opts=() started finished manifest
  read -r -a java_opts <<< "${JAVA_OPTS:-}"
  log INFO "Generating ${rows} rows with seed ${seed}"
  started="$(date '+%s%3N')"
  if ! (
    cd "${PROJECT_ROOT}"
    RECON_CHUNK_SIZE="${chunk}" RECON_POLL_CRON='-' \
      java "${java_opts[@]}" -jar "${JAR}" --spring.profiles.active=generate \
        --date="${DATE}" --rows="${rows}" --seed="${seed}"
  ) >>"${LOG_DIR}/benchmark_generate.log" 2>&1; then
    log ERROR "Generation failed, see ${LOG_DIR}/benchmark_generate.log"
    exit "${EXIT_GENERATE}"
  fi
  finished="$(date '+%s%3N')"
  GENERATE_MS=$((finished - started))
  manifest="$(find "${INBOX_DIR}" -maxdepth 1 -name "statement_*_${DATE}.manifest.json" -printf '%T@ %p\n' \
    | sort -nr | head -n 1 | cut -d' ' -f2-)"
  if [[ -z "${manifest}" ]]; then
    log ERROR "No manifest found under ${INBOX_DIR} after generation"
    exit "${EXIT_GENERATE}"
  fi
  DATA_FILE="$(jq -r '.fileName' "${manifest}")"
  EXPECTED_BREAKS="$(jq -r '.expectedBreaks | add' "${manifest}")"
  log INFO "Generated ${DATA_FILE} in ${GENERATE_MS} ms (JVM start included); the manifest expects ${EXPECTED_BREAKS} breaks"
}

# wait_for_execution <execution-id>: polls until the execution is finished; sets EXECUTION_JSON.
wait_for_execution() {
  local execution_id="$1" status
  local deadline=$((SECONDS + TIMEOUT))
  while true; do
    api_call GET "/api/v1/jobs/executions/${execution_id}"
    require_status 200 "Execution ${execution_id}"
    status="$(jq -r '.status' <<< "${HTTP_BODY}")"
    case "${status}" in
      COMPLETED|FAILED|STOPPED|ABANDONED)
        EXECUTION_JSON="${HTTP_BODY}"
        return 0
        ;;
    esac
    if (( SECONDS >= deadline )); then
      log ERROR "Execution ${execution_id} is still ${status} after ${TIMEOUT}s"
      exit "${EXIT_TIMEOUT}"
    fi
    sleep "${POLL_SECONDS}"
  done
}

# require_completed <execution-json> <what>: a job that did not end COMPLETED/COMPLETED stops the benchmark.
require_completed() {
  local json="$1" what="$2" status exit_code
  status="$(jq -r '.status' <<< "${json}")"
  exit_code="$(jq -r '.exitCode' <<< "${json}")"
  if [[ "${status}" != COMPLETED || "${exit_code}" != COMPLETED ]]; then
    log ERROR "${what} ended ${status}/${exit_code}: $(jq -r '.exitDescription // empty' <<< "${json}")"
    exit "${EXIT_JOB}"
  fi
}

# record <rows> <chunk> <grid> <repetition> <seed> <phase> <execution-json> <step-name> <matched> <breaks> <expected-breaks>
record() {
  local rows="$1" chunk="$2" grid="$3" repetition="$4" seed="$5" phase="$6" json="$7" step_name="$8"
  local matched="$9" breaks="${10}" expected_breaks="${11}"
  local step_json step_ms
  step_json="$(jq -c --arg name "${step_name}" '.steps[] | select(.name == $name)' <<< "${json}")"
  step_ms="$(millis_between "$(jq -r '.startTime' <<< "${step_json}")" "$(jq -r '.endTime' <<< "${step_json}")")"
  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "$(date '+%Y-%m-%dT%H:%M:%S%z')" "${rows}" "${chunk}" "${grid}" "${repetition}" "${seed}" "${phase}" \
    "$(jq -r '.executionId' <<< "${json}")" "$(jq -r '.status' <<< "${json}")" "$(jq -r '.exitCode' <<< "${json}")" \
    "$(jq -r '.durationMs' <<< "${json}")" "${step_name}" "${step_ms}" \
    "$(jq -r '.readCount' <<< "${step_json}")" "$(jq -r '.writeCount' <<< "${step_json}")" \
    "$(jq -r '.filterCount' <<< "${step_json}")" "$(jq -r '.skipCount' <<< "${step_json}")" \
    "$(jq -r '.commitCount' <<< "${step_json}")" "${matched}" "${breaks}" "${expected_breaks}" "$(app_vmhwm_kb)" \
    >> "${CSV_FILE}"
}

record_generate() {
  local rows="$1" chunk="$2" repetition="$3" seed="$4"
  printf '%s,%s,%s,,%s,%s,generate,,COMPLETED,,%s,,,,,,,,,,%s,\n' \
    "$(date '+%Y-%m-%dT%H:%M:%S%z')" "${rows}" "${chunk}" "${repetition}" "${seed}" "${GENERATE_MS}" "${EXPECTED_BREAKS}" \
    >> "${CSV_FILE}"
}

# ingest <rows> <chunk> <repetition> <seed>: sets STATEMENT_FILE_ID.
ingest() {
  local rows="$1" chunk="$2" repetition="$3" seed="$4" execution_id
  api_call POST "/api/v1/jobs/ingest" "{\"filePath\": \"${DATA_FILE}\"}"
  require_status 202 "Ingest launch"
  execution_id="$(jq -r '.executionId' <<< "${HTTP_BODY}")"
  wait_for_execution "${execution_id}"
  require_completed "${EXECUTION_JSON}" "ingestStatementJob execution ${execution_id}"
  STATEMENT_FILE_ID="$(jq -r '.statementFileId' <<< "${EXECUTION_JSON}")"
  record "${rows}" "${chunk}" "" "${repetition}" "${seed}" ingest "${EXECUTION_JSON}" loadStatementLines "" "" ""
  log INFO "ingest rows=${rows} chunk=${chunk} rep=${repetition}: execution ${execution_id} took $(jq -r '.durationMs' <<< "${EXECUTION_JSON}") ms, statement_file ${STATEMENT_FILE_ID}"
}

# reconcile <rows> <chunk> <grid> <repetition> <seed> <rerun true|false>
reconcile() {
  local rows="$1" chunk="$2" grid="$3" repetition="$4" seed="$5" rerun="$6"
  local execution_id run_id matched breaks
  api_call POST "/api/v1/jobs/reconcile/${STATEMENT_FILE_ID}?gridSize=${grid}&rerun=${rerun}"
  require_status 202 "Reconcile launch"
  execution_id="$(jq -r '.executionId' <<< "${HTTP_BODY}")"
  wait_for_execution "${execution_id}"
  require_completed "${EXECUTION_JSON}" "reconcileJob execution ${execution_id}"
  run_id="$(jq -r '.reconRunId' <<< "${EXECUTION_JSON}")"
  api_call GET "/api/v1/recon-runs/${run_id}/summary"
  require_status 200 "Run summary"
  matched="$(jq -r '.matchedCount' <<< "${HTTP_BODY}")"
  breaks="$(jq -r '.breakCount' <<< "${HTTP_BODY}")"
  record "${rows}" "${chunk}" "${grid}" "${repetition}" "${seed}" reconcile "${EXECUTION_JSON}" matchCandidates \
    "${matched}" "${breaks}" "${EXPECTED_BREAKS}"
  log INFO "reconcile rows=${rows} chunk=${chunk} grid=${grid} rep=${repetition}: execution ${execution_id} took $(jq -r '.durationMs' <<< "${EXECUTION_JSON}") ms, run ${run_id} matched ${matched} breaks ${breaks}"
  if [[ "${breaks}" != "${EXPECTED_BREAKS}" ]]; then
    log WARN "Run ${run_id} found ${breaks} breaks but the manifest expected ${EXPECTED_BREAKS}"
  fi
}

# print_summary: one line per phase, rows, chunk and grid with the job durations of every repetition.
print_summary() {
  log INFO "Results written to ${CSV_FILE}"
  {
    printf 'phase,rows,chunk_size,grid_size,duration_ms per repetition\n'
    awk -F',' 'NR > 1 && ($7 == "ingest" || $7 == "reconcile") {
                 key = $7 "," $2 "," $3 "," $4
                 values[key] = (key in values) ? values[key] " / " $11 : $11
               }
               END { for (key in values) print key "," values[key] }' "${CSV_FILE}" | sort -t',' -k1,1 -k2,2n -k3,3n -k4,4n
  } | tee -a "${LOG_FILE}"
}

ROWS_RAW="100000,1000000"
CHUNKS_RAW="100,500,2000"
GRIDS_RAW="1,2,4"
REPETITIONS=2
DATE="2026-10-01"
SEED=7000
OUTPUT_DIR="${PROJECT_ROOT}/data/benchmark"
JAR=""
TIMEOUT=3600

while getopts ":n:c:g:r:d:s:o:j:t:h" option; do
  case "${option}" in
    n) ROWS_RAW="${OPTARG}" ;;
    c) CHUNKS_RAW="${OPTARG}" ;;
    g) GRIDS_RAW="${OPTARG}" ;;
    r) REPETITIONS="${OPTARG}" ;;
    d) DATE="${OPTARG}" ;;
    s) SEED="${OPTARG}" ;;
    o) OUTPUT_DIR="${OPTARG}" ;;
    j) JAR="${OPTARG}" ;;
    t) TIMEOUT="${OPTARG}" ;;
    h) usage; exit "${EXIT_OK}" ;;
    :) die_usage "option -${OPTARG} requires a value" ;;
    *) die_usage "unknown option -${OPTARG}" ;;
  esac
done
shift $((OPTIND - 1))
[[ $# -eq 0 ]] || die_usage "unexpected argument: $1"

split_list ROWS_LIST "${ROWS_RAW}"
split_list CHUNK_LIST "${CHUNKS_RAW}"
split_list GRID_LIST "${GRIDS_RAW}"
[[ "${REPETITIONS}" =~ ^[1-9][0-9]*$ ]] || die_usage "repetitions must be a positive integer: ${REPETITIONS}"
[[ "${DATE}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die_usage "date must be in yyyy-mm-dd format: ${DATE}"
[[ "$(date -d "${DATE}" '+%Y-%m-%d' 2>/dev/null || true)" == "${DATE}" ]] || die_usage "not a valid calendar date: ${DATE}"
[[ "${SEED}" =~ ^-?[0-9]+$ ]] || die_usage "seed must be an integer: ${SEED}"
[[ "${TIMEOUT}" =~ ^[1-9][0-9]*$ ]] || die_usage "timeout must be a positive integer: ${TIMEOUT}"
if [[ -n "${JAR}" && ! -f "${JAR}" ]]; then
  die_usage "jar not found: ${JAR}"
fi
API_USER="${RECON_API_USER:-operator}"
API_PASSWORD="${RECON_API_PASSWORD:-}"
[[ -n "${API_PASSWORD}" ]] || die_usage "RECON_API_PASSWORD is required; the started application is seeded with it"
PORT="${SERVER_PORT:-8083}"
HOST="http://localhost:${PORT}"
MAX_GRID=1
for grid in "${GRID_LIST[@]}"; do
  (( grid > MAX_GRID )) && MAX_GRID="${grid}"
done
MAX_ROWS=1
for rows in "${ROWS_LIST[@]}"; do
  (( rows > MAX_ROWS )) && MAX_ROWS="${rows}"
done
# Generated files contain about one malformed line per hundred ledger rows, so the default
# skip limit of 100 would fail every large ingest; 2% of the largest row count leaves room.
SKIP_LIMIT="${RECON_SKIP_LIMIT:-$(( MAX_ROWS / 50 > 100 ? MAX_ROWS / 50 : 100 ))}"
INBOX_DIR="${PROJECT_ROOT}/${RECON_INBOX_DIR:-data/inbox}"
[[ "${RECON_INBOX_DIR:-}" == /* ]] && INBOX_DIR="${RECON_INBOX_DIR}"

mkdir -p "${LOG_DIR}" "${OUTPUT_DIR}"
check_prerequisites
CSV_FILE="${OUTPUT_DIR}/benchmark_$(date '+%Y%m%d_%H%M%S').csv"
printf '%s\n' "${CSV_HEADER}" > "${CSV_FILE}"
BODY_FILE="$(mktemp)"
APP_PID=""
trap 'stop_app; rm -f "${BODY_FILE}"' EXIT

log INFO "Benchmark: rows ${ROWS_LIST[*]}; chunk sizes ${CHUNK_LIST[*]}; grid sizes ${GRID_LIST[*]}; ${REPETITIONS} repetition(s); jar ${JAR}"
log INFO "Database: ${DB_URL:-jdbc:postgresql://localhost:5434/recon (default)}; JAVA_OPTS: ${JAVA_OPTS:-none}"

for chunk in "${CHUNK_LIST[@]}"; do
  start_app "${chunk}"
  for repetition in $(seq 1 "${REPETITIONS}"); do
    for rows in "${ROWS_LIST[@]}"; do
      generate "${rows}" "${SEED}" "${chunk}"
      record_generate "${rows}" "${chunk}" "${repetition}" "${SEED}"
      ingest "${rows}" "${chunk}" "${repetition}" "${SEED}"
      rerun=false
      for grid in "${GRID_LIST[@]}"; do
        reconcile "${rows}" "${chunk}" "${grid}" "${repetition}" "${SEED}" "${rerun}"
        rerun=true
      done
      SEED=$((SEED + 1))
    done
  done
  stop_app
done

print_summary
exit "${EXIT_OK}"
