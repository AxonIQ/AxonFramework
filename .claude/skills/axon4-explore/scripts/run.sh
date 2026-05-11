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

# Thin wrapper that runs the OpenRewrite recipe `ExploreAxon4Aggregates` against the project
# at $1 (default: current directory). Emits .axon4-explore/components/aggregate@<fqn>.yaml
# files plus a CSV data table under target/rewrite/datatables/<timestamp>/.
#
# Prerequisites:
#   - Maven (mvn or mvnw) on PATH or in the project root.
#   - org.axonframework:axon-migration:5.1.1-SNAPSHOT in ~/.m2 (publish from
#     AxonFramework5 with `./mvnw -pl migration install -DskipTests`).
#   - Project must compile under the active JDK so OpenRewrite can parse its sources.
#
# Flags:
#   -Drewrite.exportDatatables=true is mandatory — without it the data tables are computed
#   in memory but never written to disk.
#   :run is used instead of :dryRun because :dryRun also skips writing data tables in
#   plugin v6.18.0. Safe here: the recipe is search-only and never returns source mutations.

set -euo pipefail

PROJECT_DIR="${1:-$(pwd)}"
PLUGIN_VERSION="${REWRITE_PLUGIN_VERSION:-6.18.0}"
RECIPE_VERSION="${AXON_MIGRATION_VERSION:-5.1.1-SNAPSHOT}"

if [[ ! -d "$PROJECT_DIR" ]]; then
    echo "axon4-explore: project directory not found: $PROJECT_DIR" >&2
    exit 2
fi

echo "axon4-explore: scanning $PROJECT_DIR"

cd "$PROJECT_DIR"

# Prefer ./mvnw if the project ships one; fall back to mvn on PATH.
if [[ -x "./mvnw" ]]; then
    MVN="./mvnw"
elif command -v mvn >/dev/null 2>&1; then
    MVN="mvn"
else
    echo "axon4-explore: neither ./mvnw nor mvn found in PATH" >&2
    exit 2
fi

"$MVN" "org.openrewrite.maven:rewrite-maven-plugin:${PLUGIN_VERSION}:run" \
    "-Drewrite.activeRecipes=org.axonframework.migration.find.ExploreAxon4Aggregates" \
    "-Drewrite.recipeArtifactCoordinates=org.axonframework:axon-migration:${RECIPE_VERSION}" \
    "-Drewrite.exportDatatables=true"

OUT_DIR="$PROJECT_DIR/.axon4-explore/components"
if [[ -d "$OUT_DIR" ]]; then
    echo
    echo "axon4-explore: aggregates found:"
    ls -1 "$OUT_DIR" | sed 's/^/  - /'
    echo
    echo "axon4-explore: read individual YAMLs from $OUT_DIR/"
else
    echo "axon4-explore: no aggregates detected (no .axon4-explore/components/ directory created)"
fi
