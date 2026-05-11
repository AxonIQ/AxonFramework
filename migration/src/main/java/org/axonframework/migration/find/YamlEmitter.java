/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.migration.find;

import java.util.Collection;

/**
 * Hand-rolled YAML emitter producing the {@code .axon4-explore/components/aggregate@*.yaml}
 * documents read by the {@code axon4-explore} skill. Internal to the migration module —
 * not a general YAML library. Only the concrete shape used by {@link AggregateRecord} is
 * supported: scalar strings, booleans, nulls, lists of strings, and one nested map for
 * the {@code features} block.
 * <p>
 * No dependency on Jackson or SnakeYAML so the migration recipe artifact stays lean.
 *
 * @since 5.2.0
 */
final class YamlEmitter {

    private YamlEmitter() {
    }

    static String toYaml(AggregateRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append("component_type: aggregate\n");
        sb.append("class_name: ").append(scalar(r.simpleName)).append('\n');
        sb.append("package: ").append(scalar(r.packageName)).append('\n');
        sb.append("fqn: ").append(scalar(r.fqn)).append('\n');
        sb.append("language: ").append(scalar(r.language)).append('\n');
        sb.append("file_path: ").append(scalar(r.sourcePath)).append('\n');
        sb.append("discovery:");
        appendStringList(sb, r.discovery);
        sb.append("config_style: ").append(scalar(r.configStyle)).append('\n');
        sb.append("persistence: ").append(scalar(r.persistence)).append('\n');
        sb.append("features:\n");
        sb.append("  multi_entity: ").append(r.multiEntity).append('\n');
        sb.append("  deadline: ").append(r.deadline).append('\n');
        sb.append("  create_new: ").append(r.createNew).append('\n');
        sb.append("  polymorphic: ").append(r.polymorphic).append('\n');
        sb.append("  polymorphic_role: ").append(scalar(r.polymorphicRole)).append('\n');
        sb.append("  polymorphic_parent: ").append(scalar(r.polymorphicParent)).append('\n');
        sb.append("  has_snapshot_trigger: ").append(r.hasSnapshotTrigger).append('\n');
        sb.append("  has_cache: ").append(r.hasCache).append('\n');
        sb.append("  has_revision: ").append(r.hasRevision).append('\n');
        sb.append("  has_aggregate_version: ").append(r.hasAggregateVersion).append('\n');
        sb.append("  has_creation_policy: ").append(r.hasCreationPolicy).append('\n');
        sb.append("  jpa_entity: ").append(r.jpaEntity).append('\n');
        sb.append("commands:");
        appendStringList(sb, r.commands);
        sb.append("events:");
        appendStringList(sb, r.events);
        sb.append("imports:");
        appendStringList(sb, r.imports);
        sb.append("notes:");
        appendStringList(sb, r.notes);
        return sb.toString();
    }

    private static void appendStringList(StringBuilder sb, Collection<String> items) {
        if (items.isEmpty()) {
            sb.append(" []\n");
            return;
        }
        sb.append('\n');
        for (String item : items) {
            sb.append("  - ").append(scalar(item)).append('\n');
        }
    }

    /**
     * Emits a YAML scalar. {@code null} maps to the literal {@code null}; otherwise the
     * value is double-quoted whenever it could parse as something other than a plain string —
     * starts with a YAML indicator ({@code @ # & * ! | > ' " % - ? : , [ ] { }}), is empty,
     * is a YAML reserved word ({@code true|false|null|yes|no}), or contains a colon followed
     * by whitespace (which YAML interprets as a key/value separator). This is intentionally
     * conservative — false positives produce extra quotes, never invalid YAML.
     */
    private static String scalar(String s) {
        if (s == null) {
            return "null";
        }
        if (s.isEmpty() || needsQuoting(s)) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return s;
    }

    private static boolean needsQuoting(String s) {
        char first = s.charAt(0);
        if ("@#&*!|>'\"%-?:,[]{}".indexOf(first) >= 0) {
            return true;
        }
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")
                || s.equalsIgnoreCase("null") || s.equalsIgnoreCase("yes")
                || s.equalsIgnoreCase("no")) {
            return true;
        }
        for (int i = 0; i < s.length() - 1; i++) {
            if (s.charAt(i) == ':' && Character.isWhitespace(s.charAt(i + 1))) {
                return true;
            }
        }
        return s.contains("\n") || s.contains("\t");
    }
}
