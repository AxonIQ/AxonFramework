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
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Unwraps Axon Framework 4 {@code ResponseTypes} wrappers on AF5-shape (typed-payload, two-argument)
 * {@link org.openrewrite.java.tree.J.MethodInvocation} calls against {@code QueryGateway}.
 * <p>
 * AF5 dropped the {@code ResponseType} SPI: the gateway now accepts a plain {@code Class<R>} for
 * single-response queries and exposes a separate {@code queryMany(...)} for multi-response queries.
 * For call sites that already pass an object payload — i.e. the AF5 shape
 * {@code queryGateway.query(payload, ResponseTypes.instanceOf(R.class))} — the wrapper can be
 * removed mechanically:
 * <ul>
 *   <li>{@code query(payload, instanceOf(R.class))} → {@code query(payload, R.class)}</li>
 *   <li>{@code query(payload, optionalInstanceOf(R.class))} → {@code query(payload, R.class)}
 *       — the future resolves to {@code null} when absent in AF5; behaviour-preserving rewrite.</li>
 *   <li>{@code query(payload, multipleInstancesOf(R.class))} → {@code queryMany(payload, R.class)}
 *       — the only paired method-name change.</li>
 * </ul>
 * <p>
 * <strong>Deliberately conservative scope.</strong> The recipe never fires on the three-argument
 * AF4 form {@code query(String name, Object payload, ResponseType)} — that form has no AF5 overload,
 * and silently rewriting it would (a) produce code that does not compile and (b) destroy the
 * fingerprint that the LLM-driven {@code axon4-to-axon5-querygateway} skill needs to recognise the
 * AF4 string-named pattern (so it can introduce typed query message classes). For the same reason
 * the recipe skips {@code subscriptionQuery(...)} entirely — even the typed-payload form needs a
 * structural rewrite of the surrounding {@code SubscriptionQueryResult.initialResult/updates/close}
 * ceremony, which the skill must drive. {@code streamingQuery(...)} already takes a plain
 * {@code Class<R>} in AF4, so there is no wrapper to unwrap.
 * <p>
 * Imports for {@code org.axonframework.messaging.responsetypes.ResponseType} and
 * {@code …ResponseTypes} (including {@code static …ResponseTypes.*}) are removed only after the
 * file has no remaining references to those types — projects that mix the two-argument AF5 shape
 * with still-untouched three-argument AF4 sites keep their imports until the per-construct skill
 * finishes the migration.
 *
 * @author Mateusz Nowak
 * @since 5.1.1
 */
public class Axon4ToAxon5QueryResponseTypes extends Recipe {

    private static final String QUERY_GATEWAY_AF4_FQN = "org.axonframework.queryhandling.QueryGateway";
    private static final String QUERY_GATEWAY_AF5_FQN = "org.axonframework.messaging.queryhandling.gateway.QueryGateway";

    private static final String RESPONSE_TYPE_FQN = "org.axonframework.messaging.responsetypes.ResponseType";
    private static final String RESPONSE_TYPES_FQN = "org.axonframework.messaging.responsetypes.ResponseTypes";

    private static final String INSTANCE_OF = "instanceOf";
    private static final String OPTIONAL_INSTANCE_OF = "optionalInstanceOf";
    private static final String MULTIPLE_INSTANCES_OF = "multipleInstancesOf";

    @Override
    public String getDisplayName() {
        return "Unwrap ResponseTypes wrappers on AF5-shape QueryGateway.query(...) calls";
    }

    @Override
    public String getDescription() {
        return "On two-argument `queryGateway.query(payload, ResponseType)` calls, unwraps the AF4 "
                + "`ResponseTypes.instanceOf(...)` / `optionalInstanceOf(...)` / `multipleInstancesOf(...)` "
                + "wrapper to the plain `Class<R>` form AF5 expects, and renames "
                + "`query(payload, multipleInstancesOf(R.class))` to `queryMany(payload, R.class)`. "
                + "Three-argument `query(String, Object, ...)` forms, `subscriptionQuery(...)`, and "
                + "`streamingQuery(...)` are left untouched so the per-construct migration skill keeps the "
                + "AF4 fingerprints it needs for design decisions. Removes `ResponseType` / `ResponseTypes` "
                + "imports only when no references remain.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation mi = super.visitMethodInvocation(method, ctx);

                if (!"query".equals(mi.getSimpleName())) {
                    return mi;
                }
                List<Expression> args = mi.getArguments();
                if (args.size() != 2) {
                    return mi;
                }
                if (!isQueryGatewayCall(mi)) {
                    return mi;
                }

                Expression secondArg = args.get(1);
                if (!(secondArg instanceof J.MethodInvocation)) {
                    return mi;
                }
                J.MethodInvocation wrapper = (J.MethodInvocation) secondArg;
                if (!isResponseTypesStaticCall(wrapper)) {
                    return mi;
                }
                List<Expression> wrapperArgs = wrapper.getArguments();
                if (wrapperArgs.size() != 1) {
                    return mi;
                }

                String wrapperName = wrapper.getSimpleName();
                Expression unwrapped = wrapperArgs.get(0).withPrefix(secondArg.getPrefix());

                List<Expression> newArgs = new ArrayList<>(args);
                newArgs.set(1, unwrapped);

                J.MethodInvocation rewritten = mi.withArguments(newArgs);

                if (MULTIPLE_INSTANCES_OF.equals(wrapperName)) {
                    rewritten = rewritten.withName(rewritten.getName().withSimpleName("queryMany"));
                } else if (!INSTANCE_OF.equals(wrapperName) && !OPTIONAL_INSTANCE_OF.equals(wrapperName)) {
                    return mi;
                }

                maybeRemoveImport(RESPONSE_TYPES_FQN);
                maybeRemoveImport(RESPONSE_TYPE_FQN);
                maybeRemoveImport(RESPONSE_TYPES_FQN + "." + INSTANCE_OF);
                maybeRemoveImport(RESPONSE_TYPES_FQN + "." + OPTIONAL_INSTANCE_OF);
                maybeRemoveImport(RESPONSE_TYPES_FQN + "." + MULTIPLE_INSTANCES_OF);

                return rewritten;
            }

            private boolean isQueryGatewayCall(J.MethodInvocation mi) {
                Expression select = mi.getSelect();
                if (select == null) {
                    return false;
                }
                JavaType.FullyQualified selectType = TypeUtils.asFullyQualified(select.getType());
                if (selectType != null) {
                    String fqn = selectType.getFullyQualifiedName();
                    return QUERY_GATEWAY_AF4_FQN.equals(fqn) || QUERY_GATEWAY_AF5_FQN.equals(fqn);
                }
                // Type binding may be missing on synthetic test inputs — fall back to the declared
                // type expression on the receiving identifier when possible. This is intentionally
                // permissive; a misfire here is bounded by the simple-name + arg-count gating above.
                return select instanceof J.Identifier;
            }

            private boolean isResponseTypesStaticCall(J.MethodInvocation wrapper) {
                String name = wrapper.getSimpleName();
                if (!INSTANCE_OF.equals(name)
                        && !OPTIONAL_INSTANCE_OF.equals(name)
                        && !MULTIPLE_INSTANCES_OF.equals(name)) {
                    return false;
                }
                JavaType.Method declaring = wrapper.getMethodType();
                if (declaring != null && declaring.getDeclaringType() != null) {
                    return RESPONSE_TYPES_FQN.equals(declaring.getDeclaringType().getFullyQualifiedName());
                }
                Expression select = wrapper.getSelect();
                if (select instanceof J.Identifier) {
                    JavaType.FullyQualified selectType = TypeUtils.asFullyQualified(select.getType());
                    if (selectType != null) {
                        return RESPONSE_TYPES_FQN.equals(selectType.getFullyQualifiedName());
                    }
                    return "ResponseTypes".equals(((J.Identifier) select).getSimpleName());
                }
                if (select instanceof J.FieldAccess) {
                    return ((J.FieldAccess) select).getName().getSimpleName().equals("ResponseTypes");
                }
                // Static import — no select. Match by simple name; the simple-name + arg-count gating
                // on the outer query call still bounds misfires.
                return select == null;
            }
        };
    }
}
