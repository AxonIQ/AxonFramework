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
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Rewrites single-argument {@code SequencingPolicy} lambdas to the AF5 two-argument shape with an
 * {@link java.util.Optional} return.
 * <p>
 * The {@code SequencingPolicy} functional interface changed in two ways between AF4 and AF5:
 * <ul>
 *   <li>The single method took {@code Object getSequenceIdentifierFor(T event)} (one argument, nullable
 *       {@code Object} return) and now takes
 *       {@code Optional<Object> sequenceIdentifierFor(M message, ProcessingContext context)} (two arguments,
 *       {@code Optional<Object>} return).</li>
 *   <li>The {@code <T>} type parameter was renamed to {@code <M extends Message>}.</li>
 * </ul>
 * Source written for AF4 typically looks like {@code e -> e.getMetaData().get(KEY)}; that does not satisfy
 * the AF5 functional interface (wrong arity, wrong return type), and the user must rewrite both the
 * parameter list and the return.
 * <p>
 * This recipe handles the common shape — a single-parameter lambda with an expression body whose target
 * type is {@code SequencingPolicy} — and rewrites it to {@code (msg, ctx) -> Optional.ofNullable(body)},
 * preserving the original parameter name and using {@code Optional.ofNullable} to retain the AF4 contract
 * that {@code null} indicates "no sequencing requirement". Lambdas with block bodies, multiple parameters,
 * or anonymous inner classes implementing {@code SequencingPolicy} are left alone for manual rewriting —
 * the structural variety is too broad for a safe automatic transformation.
 *
 * @author Axon Framework Team
 * @since 5.2.0
 */
public class MigrateSequencingPolicyLambda extends Recipe {

    private static final String AF4_SEQUENCING_POLICY_FQN = "org.axonframework.eventhandling.async.SequencingPolicy";
    private static final String AF5_SEQUENCING_POLICY_FQN = "org.axonframework.messaging.core.sequencing.SequencingPolicy";
    private static final String OPTIONAL_FQN = "java.util.Optional";

    @Override
    public String getDisplayName() {
        return "Migrate SequencingPolicy lambdas to the AF5 two-arg, Optional-returning shape";
    }

    @Override
    public String getDescription() {
        return "Rewrites single-argument `SequencingPolicy` lambdas (`e -> body`) to the AF5 shape "
                + "`(e, ctx) -> Optional.ofNullable(body)`. The AF5 `SequencingPolicy.sequenceIdentifierFor` "
                + "method takes both a message and a `ProcessingContext`, and returns `Optional<Object>` "
                + "instead of a nullable `Object`. Adds the `java.util.Optional` import. Leaves block-body "
                + "lambdas, multi-parameter lambdas, and anonymous inner classes alone — those need "
                + "manual rewriting since the AF4 method name (`getSequenceIdentifierFor`) and the AF5 "
                + "method name (`sequenceIdentifierFor`) differ.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.Lambda visitLambda(J.Lambda lambda, ExecutionContext ctx) {
                J.Lambda l = super.visitLambda(lambda, ctx);

                if (!isSequencingPolicy(l.getType())) {
                    return l;
                }
                if (l.getParameters().getParameters().size() != 1) {
                    return l;
                }
                if (!(l.getBody() instanceof Expression)) {
                    return l;
                }
                if (!(l.getParameters().getParameters().get(0) instanceof J.VariableDeclarations)) {
                    return l;
                }
                J.VariableDeclarations originalParam = (J.VariableDeclarations) l.getParameters().getParameters().get(0);
                if (originalParam.getVariables().isEmpty()) {
                    return l;
                }
                String originalParamName = originalParam.getVariables().get(0).getSimpleName();
                Expression originalBody = (Expression) l.getBody();

                // Rewrite the entire lambda. The template hardcodes a fresh name `ctx` for the new
                // ProcessingContext parameter; the original parameter's identifier is preserved by
                // splicing it into the template literal. Using `Optional.ofNullable` (not `Optional.of`)
                // keeps the AF4 contract that a null return means "no sequencing requirement".
                String template = "(" + originalParamName + ", ctx) -> Optional.ofNullable(#{any()})";

                maybeAddImport(OPTIONAL_FQN, null, false);
                // contextSensitive() — required when templating a lambda. Without it,
                // OpenRewrite cannot type-attribute the resulting lambda against the
                // surrounding functional-interface target type.
                return JavaTemplate.builder(template)
                        .contextSensitive()
                        .imports(OPTIONAL_FQN)
                        .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                        .build()
                        .apply(getCursor(), l.getCoordinates().replace(), originalBody);
            }

            private boolean isSequencingPolicy(JavaType type) {
                if (type == null) {
                    return false;
                }
                JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
                if (fq == null) {
                    return false;
                }
                String fqn = fq.getFullyQualifiedName();
                return AF4_SEQUENCING_POLICY_FQN.equals(fqn) || AF5_SEQUENCING_POLICY_FQN.equals(fqn);
            }
        };
    }
}
