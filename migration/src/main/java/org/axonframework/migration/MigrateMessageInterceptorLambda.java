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
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

import java.util.List;

/**
 * Migrates lambda-based implementations of
 * {@link org.axonframework.messaging.core.MessageHandlerInterceptor} from the two-parameter AF4
 * shape ({@code (unitOfWork, interceptorChain) -> …}) to the three-parameter AF5 shape
 * ({@code (message, processingContext, interceptorChain) -> …}).
 * <p>
 * In addition to the parameter list, the recipe rewrites every no-argument
 * {@code interceptorChain.proceed()} call in the lambda body to
 * {@code interceptorChain.proceed(message, processingContext)} so the lambda compiles against the
 * new {@code MessageHandlerInterceptorChain#proceed(Message, ProcessingContext)} signature. The
 * AF4 {@code unitOfWork} parameter is dropped; references to it inside the body remain and become
 * compile errors so the developer notices the per-site rewrite that is required.
 * <p>
 * The recipe operates on lambdas whose declared type is {@code MessageHandlerInterceptor<…>}. The
 * common case is a local variable declaration or field initializer such as
 * {@snippet :
 * MessageHandlerInterceptor<CommandMessage<?>> interceptor = (uow, chain) -> { ... };
 * }
 * The class-based implementation form is handled by
 * {@link MigrateMessageInterceptorSignatures} — that recipe rewrites the method-level signature
 * and the same chain-name resolution applies there; this recipe is its lambda counterpart.
 * <p>
 * <b>Must run after</b> {@code Axon4ToAxon5Messaging}'s {@code ChangeType} steps so that the
 * variable's declared type already references the AF5 FQN.
 *
 * @author Mateusz Nowak
 * @since 5.1.1
 */
public class MigrateMessageInterceptorLambda extends Recipe {

    private static final String AF5_HANDLER_INTERCEPTOR =
            "org.axonframework.messaging.core.MessageHandlerInterceptor";
    private static final String TODO_TEXT =
            " TODO(axon4to5): review lambda body — the dropped `unitOfWork` parameter has no AF5"
                    + " equivalent; references to it must be replaced with calls on `message` /"
                    + " `processingContext`.";
    private static final String IDEMPOTENCY_MARKER =
            "TODO(axon4to5): review lambda body";

    @Override
    public String getDisplayName() {
        return "Migrate `MessageHandlerInterceptor` lambda signature to AF5";
    }

    @Override
    public String getDescription() {
        return "Rewrites two-parameter `(unitOfWork, interceptorChain) -> ...` AF4 lambdas bound "
                + "to `MessageHandlerInterceptor<...>` variables into the three-parameter AF5 "
                + "shape `(message, processingContext, interceptorChain) -> ...`, and rewrites "
                + "`interceptorChain.proceed()` calls inside the body to "
                + "`interceptorChain.proceed(message, processingContext)`. The `unitOfWork` "
                + "parameter is dropped; remaining references inside the body become compile "
                + "errors, surfacing per-site rewrites that the developer must apply. "
                + "Idempotent — lambdas already on the three-parameter shape are skipped.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVar,
                                                                      ExecutionContext ctx) {
                J.VariableDeclarations vd = super.visitVariableDeclarations(multiVar, ctx);
                if (!isMessageHandlerInterceptorType(vd.getType())) {
                    return vd;
                }
                // Apply per-variable: a single declaration can declare multiple variables, each
                // with its own initializer.
                List<J.VariableDeclarations.NamedVariable> rewrittenVars = ListUtils.map(vd.getVariables(),
                        nv -> {
                            if (!(nv.getInitializer() instanceof J.Lambda)) {
                                return nv;
                            }
                            J.Lambda rewritten = rewriteLambdaIfApplicable((J.Lambda) nv.getInitializer());
                            return rewritten == nv.getInitializer() ? nv : nv.withInitializer(rewritten);
                        });
                return rewrittenVars == vd.getVariables() ? vd : vd.withVariables(rewrittenVars);
            }

            @Override
            public J.Assignment visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                J.Assignment a = super.visitAssignment(assignment, ctx);
                if (!(a.getAssignment() instanceof J.Lambda)) {
                    return a;
                }
                if (!isMessageHandlerInterceptorType(
                        a.getVariable().getType())) {
                    return a;
                }
                J.Lambda rewritten = rewriteLambdaIfApplicable((J.Lambda) a.getAssignment());
                return rewritten == a.getAssignment() ? a : a.withAssignment(rewritten);
            }

            /**
             * @return {@code true} when {@code type} is the AF5 {@code MessageHandlerInterceptor}
             * (possibly with generic type arguments). The {@link MigrateMessageInterceptorSignatures}
             * companion recipe and {@code Axon4ToAxon5Messaging} together ensure the AF4 FQN has
             * been renamed by the time this visitor runs, so we match only the AF5 name.
             */
            private boolean isMessageHandlerInterceptorType(JavaType type) {
                JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
                if (fq == null) {
                    return false;
                }
                return AF5_HANDLER_INTERCEPTOR.equals(fq.getFullyQualifiedName());
            }

            /**
             * Rewrites a two-parameter AF4 lambda into the three-parameter AF5 shape. Returns the
             * original lambda when it already has three parameters (idempotency) or when the
             * parameter list cannot be safely interpreted.
             */
            private J.Lambda rewriteLambdaIfApplicable(J.Lambda lambda) {
                List<J> params = lambda.getParameters().getParameters();
                if (params.size() == 3 || alreadyMarked(lambda.getPrefix())) {
                    return lambda;
                }
                if (params.size() != 2) {
                    return lambda;
                }
                String chainParamName = parameterName(params.get(1));
                if (chainParamName == null) {
                    return lambda;
                }
                // Build a stub lambda with the desired three-parameter shape, then graft its
                // parameter list onto the original. JavaTemplate is overkill for this: the
                // existing params are inferred-typed lambda placeholders (e.g. `(uow, chain)`),
                // so we mirror that shape with three inferred identifiers.
                J.Lambda stub = parseStubLambda(chainParamName);
                if (stub == null) {
                    return lambda;
                }
                // Rewrite `chain.proceed()` calls in the body to `chain.proceed(message, context)`.
                J body = rewriteBody(lambda.getBody(), chainParamName);
                J.Lambda result = lambda
                        .withParameters(stub.getParameters())
                        .withBody(body);
                return prependTodoComment(result);
            }

            private J rewriteBody(J body, String chainParamName) {
                return new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation,
                                                                     ExecutionContext c) {
                        J.MethodInvocation mi = super.visitMethodInvocation(invocation, c);
                        if (!"proceed".equals(mi.getSimpleName())) {
                            return mi;
                        }
                        // The select must be the chain parameter name. Other `.proceed()` calls
                        // (rare but possible) are not touched.
                        if (!(mi.getSelect() instanceof J.Identifier)) {
                            return mi;
                        }
                        if (!chainParamName.equals(
                                ((J.Identifier) mi.getSelect()).getSimpleName())) {
                            return mi;
                        }
                        if (!mi.getArguments().isEmpty()
                                && !(mi.getArguments().get(0) instanceof J.Empty)) {
                            // Already has arguments (e.g. user pre-migrated this call) — leave it.
                            return mi;
                        }
                        return JavaTemplate.builder("#{}.proceed(message, processingContext)")
                                .javaParser(JavaParser.fromJavaVersion()
                                        .classpath(JavaParser.runtimeClasspath()))
                                .build()
                                .apply(getCursor(),
                                       mi.getCoordinates().replace(),
                                       chainParamName);
                    }
                }.visitNonNull(body, new org.openrewrite.InMemoryExecutionContext(),
                               getCursor().getParentOrThrow());
            }

            /**
             * Parses a synthetic three-parameter lambda whose chain parameter shares the original
             * lambda's chain-parameter name, returns the parsed {@link J.Lambda}, or {@code null}
             * when the parser produces an unexpected shape.
             */
            private J.Lambda parseStubLambda(String chainParamName) {
                String src = "class _Stub {\n"
                        + "    Object f = (message, processingContext, " + chainParamName + ") -> null;\n"
                        + "}\n";
                List<org.openrewrite.SourceFile> parsed = JavaParser.fromJavaVersion()
                        .classpath(JavaParser.runtimeClasspath())
                        .build()
                        .parseInputs(java.util.Collections.singletonList(
                                org.openrewrite.Parser.Input.fromString(
                                        java.nio.file.Paths.get("_Stub.java"), src)),
                                     java.nio.file.Paths.get("."),
                                     new org.openrewrite.InMemoryExecutionContext())
                        .collect(java.util.stream.Collectors.toList());
                if (parsed.isEmpty() || !(parsed.get(0) instanceof J.CompilationUnit)) {
                    return null;
                }
                J.CompilationUnit cu = (J.CompilationUnit) parsed.get(0);
                List<Statement> stmts = cu.getClasses().get(0).getBody().getStatements();
                for (Statement s : stmts) {
                    if (!(s instanceof J.VariableDeclarations)) {
                        continue;
                    }
                    J.VariableDeclarations vd = (J.VariableDeclarations) s;
                    if (vd.getVariables().isEmpty()) {
                        continue;
                    }
                    if (vd.getVariables().get(0).getInitializer() instanceof J.Lambda) {
                        return (J.Lambda) vd.getVariables().get(0).getInitializer();
                    }
                }
                return null;
            }

            private String parameterName(J param) {
                if (param instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) param;
                    if (!vd.getVariables().isEmpty()) {
                        return vd.getVariables().get(0).getSimpleName();
                    }
                }
                return null;
            }

            private boolean alreadyMarked(Space prefix) {
                for (org.openrewrite.java.tree.Comment c : prefix.getComments()) {
                    if (c instanceof TextComment
                            && ((TextComment) c).getText().contains(IDEMPOTENCY_MARKER)) {
                        return true;
                    }
                }
                return false;
            }

            private J.Lambda prependTodoComment(J.Lambda lambda) {
                Space prefix = lambda.getPrefix();
                if (alreadyMarked(prefix)) {
                    return lambda;
                }
                TextComment todo = new TextComment(false, TODO_TEXT, " ", Markers.EMPTY);
                return lambda.withPrefix(
                        prefix.withComments(ListUtils.concat(prefix.getComments(), todo)));
            }
        };
    }
}
