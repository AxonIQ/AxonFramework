#!/usr/bin/env bash
# Convenience wrapper around the "terminal 1" subscription curl call from ../README.md's manual
# subscription-query walkthrough; use the README if you also need the matching rename command.
set -euo pipefail

portal_url="${PORTAL_URL:-http://localhost:8080}"
course_id="${COURSE_ID:-axon-5}"

curl --no-buffer --fail --request GET \
  --header 'Accept: text/event-stream' \
  "${portal_url}/courses/${course_id}/subscription"
