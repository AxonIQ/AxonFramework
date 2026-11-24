#!/usr/bin/env bash
#
# Copyright (c) 2010-2026. Axon Framework
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Convenience wrapper around the "terminal 1" subscription curl call from ../README.md's manual
# subscription-query walkthrough; use the README if you also need the matching rename command.
set -euo pipefail

portal_url="${PORTAL_URL:-http://localhost:8080}"
course_id="${COURSE_ID:-axon-5}"

curl --no-buffer --fail --request GET \
  --header 'Accept: text/event-stream' \
  "${portal_url}/courses/${course_id}/subscription"
