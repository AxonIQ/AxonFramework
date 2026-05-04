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

package org.axonframework.migration;

import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.properties.PropertiesIsoVisitor;
import org.openrewrite.properties.tree.Properties;
import org.openrewrite.yaml.YamlIsoVisitor;
import org.openrewrite.yaml.tree.Yaml;

import java.util.regex.Pattern;

/**
 * Spring-Boot-only advisory recipe for the AF4 → AF5 sequencing-policy migration.
 * <p>
 * In AF4, sequencing for an event-processing group was configured via the Spring property
 * {@code axon.eventhandling.processors.<group>.sequencing-policy}. AF5 moves that decision
 * onto the event handler class itself, via {@code @SequencingPolicy(...)} from
 * {@code org.axonframework.messaging.core.annotation}. The property therefore becomes
 * obsolete — but the per-construct migration skill drives the source-side annotation move,
 * so deleting the property mechanically here would race the skill (and risk silently
 * dropping configuration before the handler class catches up).
 * <p>
 * Instead, this recipe inserts an advisory comment immediately above any matching property
 * entry in {@code application.properties} / {@code application.yml} (including
 * profile-specific files via the standard recipe-tooling glob). The comment surfaces the
 * obsolescence to the developer (and to the LLM-driven skill that runs next) without
 * touching the value. The recipe is idempotent — it skips entries whose preceding comment
 * already carries the marker.
 *
 * @author Axon Framework Team
 * @since 5.2.0
 */
public class AnnotateObsoleteSequencingPolicyProperty extends Recipe {

    private static final String COMMENT_MARKER = "AF5 migration: move to @SequencingPolicy on the handler class";
    private static final String COMMENT_LINE = "# TODO " + COMMENT_MARKER + "; this property is obsolete.";

    private static final Pattern PROPERTIES_KEY_PATTERN = Pattern.compile(
            "^axon\\.eventhandling\\.processors\\.[^.]+\\.sequencing-policy$"
    );

    @Override
    public String getDisplayName() {
        return "Annotate obsolete `axon.eventhandling.processors.<group>.sequencing-policy` properties";
    }

    @Override
    public String getDescription() {
        return "Inserts a one-line `# TODO AF5 migration: ...` comment above any "
                + "`axon.eventhandling.processors.<group>.sequencing-policy` entry in "
                + "`application.properties` / `application.yml`. AF5 moves the decision onto the "
                + "handler class via `@SequencingPolicy`; deleting the property here would race the "
                + "per-construct skill that drives the source-side annotation, so this recipe only "
                + "annotates. Idempotent.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new TreeVisitor<org.openrewrite.Tree, ExecutionContext>() {
            @Override
            public org.openrewrite.Tree visit(org.openrewrite.Tree tree, ExecutionContext ctx) {
                if (tree instanceof Properties.File) {
                    return new PropertiesEntryVisitor().visit(tree, ctx);
                }
                if (tree instanceof Yaml.Documents) {
                    return new YamlEntryVisitor().visit(tree, ctx);
                }
                return tree;
            }
        };
    }

    private static class PropertiesEntryVisitor extends PropertiesIsoVisitor<ExecutionContext> {
        @Override
        public Properties.File visitFile(Properties.File file, ExecutionContext ctx) {
            Properties.File f = super.visitFile(file, ctx);
            java.util.List<Properties.Content> source = f.getContent();
            java.util.List<Properties.Content> rebuilt = null;
            for (int i = 0; i < source.size(); i++) {
                Properties.Content c = source.get(i);
                boolean needsMarker = c instanceof Properties.Entry
                        && PROPERTIES_KEY_PATTERN.matcher(((Properties.Entry) c).getKey()).matches()
                        && !precededByMarkerComment(source, i);
                if (needsMarker) {
                    if (rebuilt == null) {
                        rebuilt = new java.util.ArrayList<>(source.subList(0, i));
                    }
                    // The new comment steals the entry's original leading whitespace so it lands
                    // exactly where the entry used to start. The entry then gets a fresh "\n"
                    // prefix so it renders on the line below the comment.
                    rebuilt.add(buildMarkerComment(c.getPrefix()));
                    rebuilt.add(((Properties.Entry) c).withPrefix("\n"));
                } else if (rebuilt != null) {
                    rebuilt.add(c);
                }
            }
            return rebuilt == null ? f : f.withContent(rebuilt);
        }

        private boolean precededByMarkerComment(java.util.List<Properties.Content> content, int entryIndex) {
            if (entryIndex == 0) {
                return false;
            }
            Properties.Content previous = content.get(entryIndex - 1);
            return previous instanceof Properties.Comment
                    && ((Properties.Comment) previous).getMessage().contains(COMMENT_MARKER);
        }

        private Properties.Comment buildMarkerComment(String entryPrefix) {
            // The new comment node steals the entry's leading newline+indent so it lands on its own
            // line. The entry then keeps its original prefix — the comment node's trailing newline
            // (which is implicit in Properties LST printing) brings us back to the entry's column.
            return new Properties.Comment(
                    org.openrewrite.Tree.randomId(),
                    entryPrefix,
                    org.openrewrite.marker.Markers.EMPTY,
                    Properties.Comment.Delimiter.HASH_TAG,
                    " TODO " + COMMENT_MARKER + "; this property is obsolete."
            );
        }
    }

    private static class YamlEntryVisitor extends YamlIsoVisitor<ExecutionContext> {
        @Override
        public Yaml.Mapping.Entry visitMappingEntry(Yaml.Mapping.Entry entry, ExecutionContext ctx) {
            Yaml.Mapping.Entry e = super.visitMappingEntry(entry, ctx);
            if (!"sequencing-policy".equals(e.getKey().getValue())) {
                return e;
            }
            if (!parentPathMatches(getCursor())) {
                return e;
            }
            if (e.getPrefix().contains(COMMENT_MARKER)) {
                return e;
            }
            return e.withPrefix(prependComment(e.getPrefix()));
        }

        /**
         * Walks the cursor parents to confirm the entry is nested under
         * {@code axon → eventhandling → processors → <any group>}. Each YAML mapping
         * entry's parent is the {@link Yaml.Mapping}; the mapping's parent is the
         * containing {@link Yaml.Mapping.Entry}. The first parent entry is the
         * {@code <group>} name (anything); the remaining three must match exactly.
         */
        private boolean parentPathMatches(Cursor cursor) {
            String[] expectedAfterGroup = {"processors", "eventhandling", "axon"};
            Cursor c = cursor.getParent();
            int parentEntriesSeen = 0;
            while (c != null) {
                if (c.getValue() instanceof Yaml.Mapping.Entry) {
                    Yaml.Mapping.Entry me = c.getValue();
                    if (parentEntriesSeen == 0) {
                        // <group> placeholder — any key.
                    } else {
                        int idx = parentEntriesSeen - 1;
                        if (idx >= expectedAfterGroup.length
                                || !expectedAfterGroup[idx].equals(me.getKey().getValue())) {
                            return false;
                        }
                    }
                    parentEntriesSeen++;
                    if (parentEntriesSeen == expectedAfterGroup.length + 1) {
                        return true;
                    }
                }
                c = c.getParent();
            }
            return false;
        }
    }

    private static String prependComment(String existingPrefix) {
        // existingPrefix typically contains the newline + indentation that precedes the entry.
        // Insert the comment as its own line, preserving that newline + indentation so the
        // entry stays where it was.
        int lastNewline = existingPrefix.lastIndexOf('\n');
        String indent = lastNewline >= 0 ? existingPrefix.substring(lastNewline + 1) : "";
        String leading = lastNewline >= 0 ? existingPrefix.substring(0, lastNewline + 1) : "";
        return leading + indent + COMMENT_LINE + "\n" + indent;
    }
}
