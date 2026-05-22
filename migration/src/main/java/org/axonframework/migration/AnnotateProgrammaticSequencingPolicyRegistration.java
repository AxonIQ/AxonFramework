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

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.marker.Markers;

/**
 * Java/Kotlin advisory recipe for AF4 {@code EventProcessingConfigurer#registerSequencingPolicy(...)}
 * calls.
 * <p>
 * AF5 dropped the declarative-API registration of sequencing policies. The decision now lives on
 * the event-handler class via {@code @SequencingPolicy} from
 * {@code org.axonframework.messaging.core.annotation}. Rewriting the configurer call into the
 * annotation move is non-mechanical: the policy {@code Function<Configuration, SequencingPolicy>}
 * may produce any policy at runtime and the recipe cannot infer which handler class should carry
 * the annotation. Deleting the call mechanically would silently lose configuration, so this
 * recipe annotates the call site with a {@code // TODO(axon4to5):} comment that names the
 * replacement API.
 * <p>
 * Idempotent — the comment carries a stable marker substring, and the visitor skips invocations
 * whose immediate prefix already contains it.
 *
 * @author Mateusz Nowak
 * @since 5.1.1
 */
public class AnnotateProgrammaticSequencingPolicyRegistration extends Recipe {

    private static final String AF4_CONFIGURER =
            "org.axonframework.config.EventProcessingConfigurer";
    private static final String AF4_MODULE_CONFIGURER =
            "org.axonframework.config.EventProcessingModule";

    /**
     * AF4 has the method on the configurer SPI and on the module that implements it; match both
     * so the recipe fires regardless of whether the calling code holds the interface or the impl.
     * Any-argument matcher (`..`) is intentional — both the {@code (String, Function)} and the
     * default-policy {@code (Function)} overload should be flagged.
     */
    private static final MethodMatcher SPI_MATCHER =
            new MethodMatcher(AF4_CONFIGURER + " registerSequencingPolicy(..)");
    private static final MethodMatcher IMPL_MATCHER =
            new MethodMatcher(AF4_MODULE_CONFIGURER + " registerSequencingPolicy(..)");

    private static final String COMMENT_MARKER =
            "TODO(axon4to5): declarative sequencing-policy registration is no longer supported";
    private static final String COMMENT_TEXT = " " + COMMENT_MARKER
            + ". Move the policy onto the event handler class via @SequencingPolicy "
            + "(org.axonframework.messaging.core.annotation.SequencingPolicy).";

    @Override
    public String getDisplayName() {
        return "Annotate obsolete `registerSequencingPolicy(...)` calls with a migration TODO";
    }

    @Override
    public String getDescription() {
        return "Prepends a `// TODO(axon4to5):` comment above any "
                + "`EventProcessingConfigurer#registerSequencingPolicy(...)` invocation. AF5 moves "
                + "the decision onto the handler class via `@SequencingPolicy`; rewriting the "
                + "lambda call into an annotation move requires knowing which handler class "
                + "should carry the annotation, which the recipe cannot infer. Idempotent.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            /**
             * Statement-level visitor: the TODO comment must attach to whatever statement
             * encloses the invocation (e.g. a `J.MethodInvocation` standalone statement or an
             * expression statement wrapping it). Walking at the method-invocation level would
             * land the comment inline, which the rewrite engine often prints in awkward positions.
             */
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation,
                                                             ExecutionContext ctx) {
                J.MethodInvocation mi = super.visitMethodInvocation(invocation, ctx);
                if (!isTargetCall(mi)) {
                    return mi;
                }
                // Prefer attaching the comment to the enclosing statement so it renders on its
                // own line above the call rather than getting inlined between operands.
                J enclosingStmt = getCursor().firstEnclosing(org.openrewrite.java.tree.Statement.class);
                if (enclosingStmt instanceof J.MethodInvocation
                        && enclosingStmt == mi) {
                    if (hasMarkerComment(mi.getPrefix())) {
                        return mi;
                    }
                    return mi.withPrefix(prependLineComment(mi.getPrefix()));
                }
                // Fall-through: leave to the statement-level visitor below (visitStatement) to
                // place the comment on the surrounding J node. visitMethodInvocation alone covers
                // the simplest case where the invocation IS the statement.
                return mi;
            }

            private boolean isTargetCall(J.MethodInvocation mi) {
                return SPI_MATCHER.matches(mi) || IMPL_MATCHER.matches(mi);
            }

            private boolean hasMarkerComment(Space prefix) {
                for (org.openrewrite.java.tree.Comment c : prefix.getComments()) {
                    if (c instanceof TextComment
                            && ((TextComment) c).getText().contains(COMMENT_MARKER)) {
                        return true;
                    }
                }
                return false;
            }

            /**
             * Prepend a {@code //}-style line comment to {@code prefix}, preserving the existing
             * indentation so the call below the comment lines up with its original column.
             */
            private Space prependLineComment(Space prefix) {
                String leading = prefix.getWhitespace();
                String indent = leading.contains("\n")
                        ? leading.substring(leading.lastIndexOf('\n') + 1)
                        : "";
                String suffix = "\n" + indent;
                TextComment todo = new TextComment(false, COMMENT_TEXT, suffix, Markers.EMPTY);
                return prefix.withComments(ListUtils.concat(prefix.getComments(), todo));
            }
        };
    }
}
