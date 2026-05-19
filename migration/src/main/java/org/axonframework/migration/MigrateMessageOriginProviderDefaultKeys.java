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
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.List;

/**
 * Replaces no-args {@code new MessageOriginProvider()} with
 * {@code new MessageOriginProvider("traceId", "correlationId")} to preserve the Axon Framework 4
 * default metadata key names after migrating to AF5.
 * <p>
 * In AF4, {@code MessageOriginProvider} put two keys into message metadata:
 * <ul>
 *   <li>{@code "correlationId"} — the <em>current</em> message's own identifier (i.e. the direct
 *       cause of the next message). In AF5 this concept is called "causation".</li>
 *   <li>{@code "traceId"} — the identifier of the <em>originating</em> message (propagated
 *       unchanged through the whole chain). In AF5 this concept is called "correlation".</li>
 * </ul>
 * AF5 renamed the keys to better reflect their semantics:
 * <ul>
 *   <li>AF4 {@code "traceId"} → AF5 default {@code "correlationId"}</li>
 *   <li>AF4 {@code "correlationId"} → AF5 default {@code "causationId"}</li>
 * </ul>
 * A no-args {@code new MessageOriginProvider()} in AF5 therefore writes metadata under the
 * <em>new</em> key names. Downstream systems that still read the old AF4 keys
 * ({@code "correlationId"} and {@code "traceId"}) would silently stop receiving those values.
 * This recipe pins the constructor to
 * {@code new MessageOriginProvider("traceId", "correlationId")} so that the provider continues
 * to write metadata under the AF4-compatible key names.
 * <p>
 * Idempotent — constructor calls that already supply explicit arguments are left unchanged.
 * Handles both the AF4 FQN ({@code org.axonframework.messaging.correlation.MessageOriginProvider})
 * and the AF5 FQN ({@code org.axonframework.messaging.core.correlation.MessageOriginProvider}),
 * so it is order-independent with respect to the package-rename recipe in
 * {@code Axon4ToAxon5Messaging}.
 *
 * @author Mateusz Nowak
 * @since 5.1.1
 */
public class MigrateMessageOriginProviderDefaultKeys extends Recipe {

    private static final String MOP_AF4 =
            "org.axonframework.messaging.correlation.MessageOriginProvider";
    private static final String MOP_AF5 =
            "org.axonframework.messaging.core.correlation.MessageOriginProvider";

    @Override
    public String getDisplayName() {
        return "Pin MessageOriginProvider to Axon Framework 4 default metadata keys";
    }

    @Override
    public String getDescription() {
        return "Replaces no-args `new MessageOriginProvider()` with "
                + "`new MessageOriginProvider(\"traceId\", \"correlationId\")` to preserve the "
                + "Axon Framework 4 metadata key names. AF5 renamed the keys: AF4's `traceId` "
                + "(originating message, propagated) became AF5's default `correlationId`, and "
                + "AF4's `correlationId` (current message) became AF5's default `causationId`. "
                + "This recipe pins the AF4-compatible key names so downstream consumers are not broken.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<>() {

            @Override
            public boolean isAcceptable(SourceFile sourceFile, ExecutionContext ctx) {
                return sourceFile instanceof J.CompilationUnit;
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                J.NewClass nc = super.visitNewClass(newClass, ctx);
                if (!isMessageOriginProviderNoArgs(nc)) {
                    return nc;
                }
                // Replace the empty argument list with explicit AF4-compatible key names.
                // AF5 constructor: MessageOriginProvider(String correlationKey, String causationKey)
                // To emit AF4 key names in metadata we pass:
                //   correlationKey = "traceId"     (AF4's "traceId" → now called "correlationKey" in AF5)
                //   causationKey   = "correlationId" (AF4's "correlationId" → now called "causationKey" in AF5)
                // The first argument gets no leading space; the second gets a single space so
                // the output reads `new MessageOriginProvider("traceId", "correlationId")`.
                J.Literal traceIdAsCorrelation = new J.Literal(
                        Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                        "traceId", "\"traceId\"", null, JavaType.Primitive.String);
                J.Literal correlationIdAsCausation = new J.Literal(
                        Tree.randomId(), Space.format(" "), Markers.EMPTY,
                        "correlationId", "\"correlationId\"", null, JavaType.Primitive.String);
                List<Expression> newArgs = new ArrayList<>(2);
                newArgs.add(traceIdAsCorrelation);
                newArgs.add(correlationIdAsCausation);
                return nc.withArguments(newArgs);
            }

            private boolean isMessageOriginProviderNoArgs(J.NewClass nc) {
                List<Expression> args = nc.getArguments();
                boolean noArgs = args == null
                        || args.isEmpty()
                        || (args.size() == 1 && args.get(0) instanceof J.Empty);
                if (!noArgs) {
                    return false;
                }
                if (TypeUtils.isOfClassType(nc.getType(), MOP_AF4)
                        || TypeUtils.isOfClassType(nc.getType(), MOP_AF5)) {
                    return true;
                }
                // Fallback for sources whose imports haven't been resolved yet (e.g., when this
                // recipe runs before the package-rename recipe within the same cycle).
                if (nc.getClazz() instanceof J.Identifier) {
                    return "MessageOriginProvider".equals(
                            ((J.Identifier) nc.getClazz()).getSimpleName());
                }
                return false;
            }
        };
    }
}
