#!/usr/bin/env sh

set -eu

BASE_URL="${1:-http://localhost/devsite/}"

case "${BASE_URL}" in
  */) ;;
  *) BASE_URL="${BASE_URL}/" ;;
esac

if ! command -v curl >/dev/null 2>&1; then
  echo "ERROR: curl is required" >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT INT TERM

FAILURES=0

request() {
  method="${1}"
  path="${2}"
  body_data="${3:-}"
  url="${BASE_URL}${path}"
  headers_file="${TMP_DIR}/headers.txt"
  body_file="${TMP_DIR}/body.txt"

  if [ "${method}" = "POST" ]; then
    curl -sS \
      -X POST \
      -H "Content-Type: application/x-www-form-urlencoded" \
      -d "${body_data}" \
      -D "${headers_file}" \
      -o "${body_file}" \
      "${url}"
  else
    curl -sS \
      -D "${headers_file}" \
      -o "${body_file}" \
      "${url}"
  fi

  LAST_STATUS="$(awk 'NR == 1 { print $2 }' "${headers_file}")"
  LAST_BODY="$(tr -d '\r' < "${body_file}")"
}

pass() {
  echo "PASS: ${1}"
}

fail() {
  echo "FAIL: ${1}"
  FAILURES=$((FAILURES + 1))
}

assert_status() {
  expected="${1}"
  label="${2}"
  if [ "${LAST_STATUS}" = "${expected}" ]; then
    pass "${label} (status ${LAST_STATUS})"
  else
    fail "${label} (expected status ${expected}, got ${LAST_STATUS})"
  fi
}

assert_body_contains() {
  needle="${1}"
  label="${2}"
  case "${LAST_BODY}" in
    *"${needle}"*)
      pass "${label}"
      ;;
    *)
      fail "${label} (missing '${needle}')"
      ;;
  esac
}

echo "Running auth smoke checks against ${BASE_URL}"
echo

request "GET" "data/auth"
assert_status "200" "GET /data/auth"
assert_body_contains "oid-base-url" "GET /data/auth contains oid-base-url"
assert_body_contains "client-id" "GET /data/auth contains client-id"

request "GET" "login"
assert_status "200" "GET /login"
assert_body_contains "oidLogin" "GET /login contains login button markup"

request "GET" "store-id-token?id-token=invalid"
assert_status "401" "GET /store-id-token invalid token"
assert_body_contains "Not valid" "GET /store-id-token invalid body"

request "POST" "store-id-token?id-token=invalid" "id-token=invalid"
assert_status "401" "POST /store-id-token invalid token"
assert_body_contains "Not valid" "POST /store-id-token invalid body"

echo
if [ "${FAILURES}" -eq 0 ]; then
  echo "Auth smoke checks passed."
  exit 0
fi

echo "Auth smoke checks failed: ${FAILURES}"
exit 1
