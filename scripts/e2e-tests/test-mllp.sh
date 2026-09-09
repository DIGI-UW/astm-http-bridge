#!/bin/bash
set -euo pipefail

echo "=== E2E Test: Saved HL7 Connection ==="

WIREMOCK_URL="${WIREMOCK_URL:-http://localhost:8080}"
BRIDGE_URL="${BRIDGE_URL:-http://localhost:8443}"
BRIDGE_MLLP_PORT="${BRIDGE_MLLP_PORT:-2575}"
: "${BRIDGE_CONNECTION_ID:?Activate a saved HL7 server connection and set BRIDGE_CONNECTION_ID first}"
BRIDGE_AUTH=()
if [ -n "${BRIDGE_PASSWORD:-}" ]; then
    BRIDGE_AUTH=(-u "${BRIDGE_USERNAME:-bridge}:${BRIDGE_PASSWORD}")
fi

CONNECTION=$(curl --fail --silent --show-error "${BRIDGE_AUTH[@]}" "${BRIDGE_URL}/api/connections/${BRIDGE_CONNECTION_ID}")
echo "${CONNECTION}" | jq -e --argjson port "${BRIDGE_MLLP_PORT}" '
  .actualRuntimeState == "ACTIVE" and
  .activeRuntimeRef.configRevision == .configRevision and
  any(.fields[]; .key == "port" and .currentValue == $port)
' > /dev/null
ANALYZER_ID=$(echo "${CONNECTION}" | jq -er '.clientAnalyzerId')

# Use a unique message so the test need not delete another test's request log.
MESSAGE_ID="saved-hl7-$(date +%s)-$$"
HL7_MSG=$(printf 'MSH|^~\\&|SPOOF|OTHER|OpenELIS|LAB|20260909120000||ORU^R01|%s|P|2.5.1\rPID|1||PAT001\rOBR|1||%s|PANEL\rOBX|1|NM|TEST^Patient||2|unit\r' "${MESSAGE_ID}" "${MESSAGE_ID}")
ACK=$(printf '\x0b%s\x1c\x0d' "${HL7_MSG}" | nc -w 5 localhost "${BRIDGE_MLLP_PORT}")
if [[ "${ACK}" != *"MSA|AA|${MESSAGE_ID}"* ]]; then
    echo "FAIL: the saved listener did not acknowledge successful delivery"
    exit 1
fi

# The ACK follows forwarding, so no fixed processing delay is needed.
REQUESTS=$(curl --fail --silent --show-error "${WIREMOCK_URL}/__admin/requests")
echo "${REQUESTS}" | jq -e --arg source "connection:${BRIDGE_CONNECTION_ID}" \
  --arg analyzer "${ANALYZER_ID}" --arg message "${MESSAGE_ID}" '
  any(.requests[];
    (.request.headers["X-Source-Id"] == $source) and
    (.request.headers["X-Analyzer-Id"] == $analyzer) and
    (.request.body | contains($message)))
' > /dev/null
echo "PASS: saved connection identity delivered through its own HL7 listener"
