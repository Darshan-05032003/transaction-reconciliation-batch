#!/usr/bin/env bash
# Compresses archive day folders older than N days into <date>.tar.gz and removes the
# folder only after the archive has been listed and every file found in it. Safe to run
# again: folders that are already archived are skipped, a half-written archive is redone.
set -euo pipefail

SCRIPT_NAME="$(basename "${BASH_SOURCE[0]}")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly SCRIPT_NAME SCRIPT_DIR PROJECT_ROOT
readonly LOG_DIR="${PROJECT_ROOT}/logs"
readonly LOG_FILE="${LOG_DIR}/archive_old.log"

readonly EXIT_OK=0
readonly EXIT_USAGE=2
readonly EXIT_PREREQ=3
readonly EXIT_VERIFY=5

usage() {
  cat <<USAGE
Usage: ${SCRIPT_NAME} -d <days> [-a <archive-dir>] [-n] [-h]

Replaces every <archive-dir>/<yyyy-mm-dd>/ folder whose date is more than <days> days
ago with <archive-dir>/<yyyy-mm-dd>.tar.gz. The folder is deleted only after the archive
lists cleanly and contains every file of the folder.

Options:
  -d  keep folders of the last <days> days unpacked (required, 0 archives everything before today)
  -a  archive folder (default: \$RECON_ARCHIVE_DIR or data/archive)
  -n  dry run: report what would be archived, change nothing
  -h  show this help

Exit codes:
  ${EXIT_OK}  success (including nothing to do)
  ${EXIT_USAGE}  invalid arguments
  ${EXIT_PREREQ}  missing prerequisite (tar, gzip) or archive folder
  ${EXIT_VERIFY}  at least one archive could not be verified; its folder was kept
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

# verify_archive <archive> <day>: the archive must list cleanly and contain every regular
# file of the day folder.
verify_archive() {
  local archive="$1" day="$2"
  local listing
  if ! listing="$(tar -tzf "${archive}" 2>>"${LOG_FILE}")"; then
    log ERROR "${archive} cannot be listed"
    return 1
  fi
  local path relative
  while IFS= read -r -d '' path; do
    relative="${path#"${ARCHIVE_DIR}/"}"
    if ! grep -qxF -- "${relative}" <<< "${listing}"; then
      log ERROR "${archive} is missing ${relative}"
      return 1
    fi
  done < <(find "${ARCHIVE_DIR}/${day}" -type f -print0)
  return 0
}

archive_day() {
  local day="$1"
  local folder="${ARCHIVE_DIR}/${day}"
  local archive="${ARCHIVE_DIR}/${day}.tar.gz"
  local file_count
  file_count="$(find "${folder}" -type f | wc -l)"

  if [[ -f "${archive}" ]] && verify_archive "${archive}" "${day}"; then
    log INFO "${archive} already exists and verifies; removing ${folder}"
    [[ "${DRY_RUN}" == true ]] || rm -rf "${folder}"
    return 0
  fi
  if [[ "${DRY_RUN}" == true ]]; then
    log INFO "Would archive ${folder} (${file_count} files) to ${archive}"
    return 0
  fi

  log INFO "Archiving ${folder} (${file_count} files) to ${archive}"
  rm -f "${archive}.part"
  tar -czf "${archive}.part" -C "${ARCHIVE_DIR}" "${day}"
  if ! verify_archive "${archive}.part" "${day}"; then
    rm -f "${archive}.part"
    log ERROR "Verification failed, ${folder} was kept"
    return 1
  fi
  mv -f "${archive}.part" "${archive}"
  rm -rf "${folder}"
  log INFO "Archived ${day}: $(du -h "${archive}" | cut -f1) in ${archive}"
}

DAYS=""
ARCHIVE_DIR="${RECON_ARCHIVE_DIR:-data/archive}"
DRY_RUN=false

while getopts ":d:a:nh" option; do
  case "${option}" in
    d) DAYS="${OPTARG}" ;;
    a) ARCHIVE_DIR="${OPTARG}" ;;
    n) DRY_RUN=true ;;
    h) usage; exit "${EXIT_OK}" ;;
    :) die_usage "option -${OPTARG} requires a value" ;;
    *) die_usage "unknown option -${OPTARG}" ;;
  esac
done
shift $((OPTIND - 1))
[[ $# -eq 0 ]] || die_usage "unexpected argument: $1"

[[ -n "${DAYS}" ]] || die_usage "-d <days> is required"
[[ "${DAYS}" =~ ^[0-9]+$ ]] || die_usage "days must be a non-negative integer: ${DAYS}"
[[ "${ARCHIVE_DIR}" == /* ]] || ARCHIVE_DIR="${PROJECT_ROOT}/${ARCHIVE_DIR}"
ARCHIVE_DIR="${ARCHIVE_DIR%/}"

mkdir -p "${LOG_DIR}"
for tool in tar gzip; do
  if ! command -v "${tool}" >/dev/null 2>&1; then
    log ERROR "${tool} was not found on PATH"
    exit "${EXIT_PREREQ}"
  fi
done
if [[ ! -d "${ARCHIVE_DIR}" ]]; then
  log ERROR "Archive folder does not exist: ${ARCHIVE_DIR}"
  exit "${EXIT_PREREQ}"
fi

CUTOFF="$(date -d "-${DAYS} days" '+%Y-%m-%d')"
log INFO "Archiving day folders before ${CUTOFF} in ${ARCHIVE_DIR}$([[ "${DRY_RUN}" == true ]] && printf ' (dry run)')"

archived=0
failures=0
# Day folders sort by name, which for ISO dates is chronological.
while IFS= read -r folder; do
  day="$(basename "${folder}")"
  [[ "${day}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || continue
  [[ "${day}" < "${CUTOFF}" ]] || continue
  if archive_day "${day}"; then
    archived=$((archived + 1))
  else
    failures=$((failures + 1))
  fi
done < <(find "${ARCHIVE_DIR}" -mindepth 1 -maxdepth 1 -type d | sort)

log INFO "Done: ${archived} folder(s) archived, ${failures} failure(s)"
if (( failures > 0 )); then
  exit "${EXIT_VERIFY}"
fi
exit "${EXIT_OK}"
