#!/usr/bin/env bash
set -euo pipefail

portal_url="${PORTAL_URL:-http://localhost:8080}"
course_id="${COURSE_ID:-axon-5}"

curl --no-buffer --fail --request GET \
  --header 'Accept: text/event-stream' \
  "${portal_url}/courses/${course_id}/subscription"
