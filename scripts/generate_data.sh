#!/usr/bin/env bash
# Generates reconciliation test data for one statement date: N ledger transactions in
# PostgreSQL plus the matching statement file (with injected breaks) and a manifest JSON
# in the inbox folder. Wraps the application's "generate" profile.
set -euo pipefail

SCRIPT_NAME="$(basename "${BASH_SOURCE[0]}")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly SCRIPT_NAME SCRIPT_DIR PROJECT_ROOT
readonly LOG_DIR="${PROJECT_ROOT}/logs"
readonly LOG_FILE="${LOG_DIR}/generate_data.log"
readonly JAR_GLOB="${PROJECT_ROOT}/target/transaction-reconciliation-batch-*.jar"
readonly MIN_JAVA_VERSION=21

readonly EXIT_OK=0
readonly EXIT_USAGE=2
readonly EXIT_PREREQ=3
readonly EXIT_BUILD=4
readonly EXIT_GENERATE=5

usage() {
  cat <<USAGE
Usage: ${SCRIPT_NAME} -d <yyyy-mm-dd> -n <rows> [-s <seed>] [-j <jar>] [-h]

Generates <rows> ledger transactions for the given date and writes the matching
statement file with deliberate breaks, plus a manifest with the exact break counts.

Options:
  -d  statement date and booking date, ISO format (required)
  -n  number of ledger rows to create, at least 1 (required)
  -s  random seed; the same seed always produces the same data (default: 42)
  -j  application jar to run (default: target/transaction-reconciliation-batch-*.jar,
      built with ./mvnw package if it does not exist)
  -h  show this help

Environment:
  DB_URL, DB_USERNAME, DB_PASSWORD   database connection, same variables as the application
  RECON_INBOX_DIR                    output folder (default: data/inbox)
  JAVA_OPTS                          extra JVM options, for example -Xmx256m

Exit codes:
  ${EXIT_OK}  success
  ${EXIT_USAGE}  invalid arguments
  ${EXIT_PREREQ}  missing prerequisite (java ${MIN_JAVA_VERSION}+)
  ${EXIT_BUILD}  building the jar failed
  ${EXIT_GENERATE}  the generator failed
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

check_java() {
  if ! command -v java >/dev/null 2>&1; then
    log ERROR "java was not found on PATH"
    exit "${EXIT_PREREQ}"
  fi
  local version
  version="$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2}')"
  if [[ -z "${version}" || "${version%%.*}" -lt "${MIN_JAVA_VERSION}" ]]; then
    log ERROR "java ${MIN_JAVA_VERSION} or newer is required, found '${version:-unknown}'"
    exit "${EXIT_PREREQ}"
  fi
}

find_or_build_jar() {
  local candidates=()
  local candidate
  for candidate in ${JAR_GLOB}; do
    [[ -f "${candidate}" && "${candidate}" != *.original ]] && candidates+=("${candidate}")
  done
  if [[ ${#candidates[@]} -eq 0 ]]; then
    log INFO "No application jar found, building it with Maven (tests skipped)"
    if ! (cd "${PROJECT_ROOT}" && ./mvnw -B -q -DskipTests package) 2>&1 | tee -a "${LOG_FILE}"; then
      log ERROR "Maven build failed"
      exit "${EXIT_BUILD}"
    fi
    for candidate in ${JAR_GLOB}; do
      [[ -f "${candidate}" && "${candidate}" != *.original ]] && candidates+=("${candidate}")
    done
  fi
  if [[ ${#candidates[@]} -ne 1 ]]; then
    log ERROR "Expected exactly one application jar under target/, found ${#candidates[@]}; pass one with -j"
    exit "${EXIT_BUILD}"
  fi
  JAR="${candidates[0]}"
}

print_manifest() {
  local inbox="${PROJECT_ROOT}/${RECON_INBOX_DIR:-data/inbox}"
  [[ "${RECON_INBOX_DIR:-}" == /* ]] && inbox="${RECON_INBOX_DIR}"
  local manifest
  manifest="$(find "${inbox}" -maxdepth 1 -name "statement_*_${DATE}.manifest.json" -printf '%T@ %p\n' 2>/dev/null \
    | sort -nr | head -n 1 | cut -d' ' -f2-)"
  if [[ -n "${manifest}" ]]; then
    log INFO "Manifest: ${manifest}"
    cat "${manifest}"
  fi
}

DATE=""
ROWS=""
SEED="42"
JAR=""

while getopts ":d:n:s:j:h" option; do
  case "${option}" in
    d) DATE="${OPTARG}" ;;
    n) ROWS="${OPTARG}" ;;
    s) SEED="${OPTARG}" ;;
    j) JAR="${OPTARG}" ;;
    h) usage; exit "${EXIT_OK}" ;;
    :) die_usage "option -${OPTARG} requires a value" ;;
    *) die_usage "unknown option -${OPTARG}" ;;
  esac
done
shift $((OPTIND - 1))
[[ $# -eq 0 ]] || die_usage "unexpected argument: $1"

[[ -n "${DATE}" ]] || die_usage "-d <yyyy-mm-dd> is required"
[[ "${DATE}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die_usage "date must be in yyyy-mm-dd format: ${DATE}"
[[ "$(date -d "${DATE}" '+%Y-%m-%d' 2>/dev/null || true)" == "${DATE}" ]] || die_usage "not a valid calendar date: ${DATE}"
[[ -n "${ROWS}" ]] || die_usage "-n <rows> is required"
[[ "${ROWS}" =~ ^[1-9][0-9]*$ ]] || die_usage "rows must be a positive integer: ${ROWS}"
[[ "${SEED}" =~ ^-?[0-9]+$ ]] || die_usage "seed must be an integer: ${SEED}"
if [[ -n "${JAR}" && ! -f "${JAR}" ]]; then
  die_usage "jar not found: ${JAR}"
fi

mkdir -p "${LOG_DIR}"
check_java
[[ -n "${JAR}" ]] || find_or_build_jar

read -r -a java_opts <<< "${JAVA_OPTS:-}"
log INFO "Generating ${ROWS} rows for ${DATE} with seed ${SEED} using ${JAR}"
log INFO "Database: ${DB_URL:-jdbc:postgresql://localhost:5434/recon (default)}"

cd "${PROJECT_ROOT}"
if ! java "${java_opts[@]}" -jar "${JAR}" \
    --spring.profiles.active=generate \
    --date="${DATE}" --rows="${ROWS}" --seed="${SEED}" 2>&1 | tee -a "${LOG_FILE}"; then
  log ERROR "Generation failed, see ${LOG_FILE}"
  exit "${EXIT_GENERATE}"
fi

log INFO "Generation finished"
print_manifest
exit "${EXIT_OK}"
