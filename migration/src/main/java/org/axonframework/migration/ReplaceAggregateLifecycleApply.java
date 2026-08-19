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
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.kotlin.tree.K;

import java.util.ArrayList;
import java.util.List;

/**
 * Replaces calls to {@code AggregateLifecycle.apply(...)} with calls to {@code eventAppender.append(...)} on an
 * injected {@code EventAppender} parameter, matching the AF5 entity migration pattern documented at
 * {@code docs/reference-guide/.../paths/aggregates/index.adoc}.
 * <p>
 * For every method that contains at least one {@code apply(...)} call resolved to AF4
 * {@code org.axonframework.modelling.command.AggregateLifecycle.apply} (or its post-{@code Axon4ToAxon5Modelling}
 * location {@code org.axonframework.modelling.entity.AggregateLifecycle.apply}), this recipe:
 * <ol>
 *   <li>injects an {@code EventAppender eventAppender} parameter into the method declaration if one is not already
 *       present;</li>
 *   <li>rewrites every matching {@code apply(X)} call inside that method to
 *       {@code eventAppender.append(X)};</li>
 *   <li>adds the {@code import org.axonframework.messaging.eventhandling.gateway.EventAppender;} import.</li>
 * </ol>
 * Idempotent: methods that no longer call {@code apply} are left alone, and the
 * static-import cleanup is handled by {@code maybeRemoveImport}.
 * <p>
 * Constructors are explicitly skipped. AF5 constructors are for entity instantiation; they
 * have no associated command, so the framework provides no {@code EventAppender} for them
 * to inject. AF4 constructors that emitted a creation event via {@code apply(...)} need a
 * non-mechanical rewrite (the creation event belongs on the static factory method produced
 * by {@code ConvertCommandHandlerConstructorToStaticMethod}, not on the constructor) — the
 * leftover {@code apply(...)} call is left untouched so the developer can decide.
 *
 * @author Mateusz Nowak
 * @since 5.1.1
 */
public class ReplaceAggregateLifecycleApply extends Recipe {

    private static final String EVENT_APPENDER_FQN = "org.axonframework.messaging.eventhandling.gateway.EventAppender";
    private static final String AF4_AGGREGATE_LIFECYCLE_FQN = "org.axonframework.modelling.command.AggregateLifecycle";
    private static final String AF5_AGGREGATE_LIFECYCLE_FQN = "org.axonframework.modelling.entity.AggregateLifecycle";

    /**
     * Matches {@code apply(Object)} on either the AF4 or post-{@code Axon4ToAxon5Modelling} location of
     * {@code AggregateLifecycle}. The AF4 method has overloads (with metadata, with sequence) — we restrict to the
     * single-arg form that AF5 {@code EventAppender#append(Object)} matches one-to-one.
     */
    private static final MethodMatcher APPLY_AF4 = new MethodMatcher(AF4_AGGREGATE_LIFECYCLE_FQN + " apply(..)");
    private static final MethodMatcher APPLY_AF5 = new MethodMatcher(AF5_AGGREGATE_LIFECYCLE_FQN + " apply(..)");

    @Override
    public String getDisplayName() {
        return "Replace AggregateLifecycle.apply(...) with EventAppender.append(...)";
    }

    @Override
    public String getDescription() {
        return "Replaces every `AggregateLifecycle.apply(X)` (statically imported or via the class) with "
                + "`eventAppender.append(X)`, injecting an `EventAppender eventAppender` parameter into the enclosing "
                + "method when one is not already declared. Drops the static `apply` import once no usages remain.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                // Constructors are never the right injection target. In AF5 the entity is being
                // constructed — there is no command being handled, so the framework has no
                // EventAppender to provide. Any AF4 constructor that emitted a creation event via
                // `AggregateLifecycle.apply(...)` is a non-mechanical migration: the creation
                // event belongs on the static command-handler that produces the entity (see
                // ConvertCommandHandlerConstructorToStaticMethod / ToCompanionObject), not on the
                // constructor itself. Leaving the constructor alone surfaces the leftover
                // `apply(...)` call to the developer instead of producing a parameter list that
                // the runtime can't satisfy — and it sidesteps the JavaTemplate.replaceParameters
                // path that would otherwise try to render Kotlin parameter syntax (`name: Type`)
                // as Java.
                //
                // J.MethodDeclaration#isConstructor() relies solely on a null return-type
                // expression, which Kotlin functions without an explicit return type also
                // satisfy. Pair the check with a class-name match so genuine Kotlin functions
                // (e.g. `fun on(cmd: ...)`) still get the EventAppender injection.
                J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (method.isConstructor()
                        && enclosing != null
                        && method.getSimpleName().equals(enclosing.getSimpleName())) {
                    return super.visitMethodDeclaration(method, ctx);
                }
                if (!containsApplyCall(method, ctx)) {
                    return super.visitMethodDeclaration(method, ctx);
                }

                String parameterName = existingEventAppenderParameter(method);
                final String appenderName = parameterName != null ? parameterName : "eventAppender";

                J.MethodDeclaration md = method;
                if (parameterName == null) {
                    md = addEventAppenderParameter(md, ctx);
                }

                // Now visit the body to swap apply(X) → eventAppender.append(X). We rebuild the method declaration
                // with the rewritten body so the cursor sees the freshly-added parameter.
                J.MethodDeclaration finalMd = md;
                J.MethodDeclaration result = (J.MethodDeclaration) new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, ExecutionContext c) {
                        J.MethodInvocation invocation = super.visitMethodInvocation(mi, c);
                        if (!APPLY_AF4.matches(invocation) && !APPLY_AF5.matches(invocation)) {
                            return invocation;
                        }
                        if (invocation.getArguments().isEmpty()
                                || invocation.getArguments().get(0) instanceof J.Empty) {
                            return invocation;
                        }
                        // Use the first argument as the event payload; AggregateLifecycle.apply(Object) maps cleanly
                        // to EventAppender.append(Object). Multi-arg AF4 overloads (apply(Object, MetaData),
                        // apply(Object, long)) are out of scope for the automatic rewrite — flag the caller via a
                        // comment-free passthrough so the build error surfaces.
                        if (invocation.getArguments().size() > 1) {
                            return invocation;
                        }
                        return JavaTemplate.builder("#{}.append(#{any()})")
                                .imports(EVENT_APPENDER_FQN)
                                .javaParser(JavaParser.fromJavaVersion()
                                        .classpath(JavaParser.runtimeClasspath()))
                                .build()
                                .apply(getCursor(),
                                       invocation.getCoordinates().replace(),
                                       appenderName,
                                       invocation.getArguments().get(0));
                    }
                }.visitNonNull(finalMd, ctx, getCursor().getParentOrThrow());

                maybeAddImport(EVENT_APPENDER_FQN, false);
                maybeRemoveImport(AF4_AGGREGATE_LIFECYCLE_FQN + ".apply");
                maybeRemoveImport(AF5_AGGREGATE_LIFECYCLE_FQN + ".apply");
                maybeRemoveImport(AF4_AGGREGATE_LIFECYCLE_FQN);
                maybeRemoveImport(AF5_AGGREGATE_LIFECYCLE_FQN);
                return result;
            }

            private boolean containsApplyCall(J.MethodDeclaration md, ExecutionContext ctx) {
                if (md.getBody() == null) {
                    return false;
                }
                boolean[] found = {false};
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, ExecutionContext c) {
                        if (APPLY_AF4.matches(mi) || APPLY_AF5.matches(mi)) {
                            found[0] = true;
                        }
                        return super.visitMethodInvocation(mi, c);
                    }
                }.visit(md.getBody(), ctx);
                return found[0];
            }

            /**
             * @return the name of the existing {@code EventAppender} parameter, or {@code null} if none.
             */
            private String existingEventAppenderParameter(J.MethodDeclaration md) {
                for (Statement p : md.getParameters()) {
                    if (!(p instanceof J.VariableDeclarations)) {
                        continue;
                    }
                    J.VariableDeclarations vd = (J.VariableDeclarations) p;
                    if (vd.getTypeExpression() == null) {
                        continue;
                    }
                    JavaType.FullyQualified type = TypeUtils.asFullyQualified(vd.getTypeExpression().getType());
                    if (type != null && EVENT_APPENDER_FQN.equals(type.getFullyQualifiedName())
                            && !vd.getVariables().isEmpty()) {
                        return vd.getVariables().get(0).getSimpleName();
                    }
                }
                return null;
            }

            private J.MethodDeclaration addEventAppenderParameter(J.MethodDeclaration md, ExecutionContext c) {
                if (isKotlinSource()) {
                    return addEventAppenderParameterKotlin(md);
                }
                List<Object> templateArgs = new ArrayList<>();
                StringBuilder template = new StringBuilder();
                List<Statement> existing = md.getParameters();
                boolean hadExisting = !(existing.size() == 1 && existing.get(0) instanceof J.Empty);
                if (hadExisting) {
                    for (int i = 0; i < existing.size(); i++) {
                        if (i > 0) {
                            template.append(", ");
                        }
                        template.append("#{}");
                        templateArgs.add(existing.get(i).print(getCursor()));
                    }
                    template.append(", EventAppender eventAppender");
                } else {
                    template.append("EventAppender eventAppender");
                }
                return JavaTemplate.builder(template.toString())
                        .imports(EVENT_APPENDER_FQN)
                        .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                        .build()
                        .apply(getCursor(), md.getCoordinates().replaceParameters(), templateArgs.toArray());
            }

            /**
             * Kotlin path for {@link #addEventAppenderParameter}. JavaTemplate's
             * {@code replaceParameters} renders the existing parameters into a Java placeholder,
             * which fails on Kotlin's {@code name: Type} syntax. To sidestep that we sub-parse a
             * synthetic Kotlin function whose parameter list is the original one with
             * {@code eventAppender: EventAppender} appended, then graft the parsed parameter list
             * back onto the original method. The sub-parser produces correctly-shaped Kotlin LST
             * nodes (with the right markers and {@code name: Type} ordering) without us having to
             * synthesize them by hand.
             */
            private J.MethodDeclaration addEventAppenderParameterKotlin(J.MethodDeclaration md) {
                List<Statement> existing = md.getParameters();
                boolean hadExisting = !(existing.size() == 1 && existing.get(0) instanceof J.Empty);
                StringBuilder paramsSrc = new StringBuilder();
                if (hadExisting) {
                    for (int i = 0; i < existing.size(); i++) {
                        if (i > 0) {
                            paramsSrc.append(", ");
                        }
                        paramsSrc.append(existing.get(i).print(getCursor()).trim());
                    }
                    paramsSrc.append(", ");
                }
                // Reference EventAppender by its simple name and bring it in via a snippet
                // import so the parsed parameter LST renders as `eventAppender: EventAppender`
                // rather than fully qualified. The host file gets the same import added below
                // via `maybeAddImport`.
                paramsSrc.append("eventAppender: EventAppender");

                String snippet = "package _temp\n\n"
                        + "import " + EVENT_APPENDER_FQN + "\n\n"
                        + "fun _f(" + paramsSrc + ") {}\n";
                List<SourceFile> parsed;
                try {
                    parsed = KotlinParser.builder().build().parse(snippet)
                            .filter(s -> s instanceof K.CompilationUnit)
                            .toList();
                } catch (RuntimeException ex) {
                    return md;
                }
                if (parsed.isEmpty()) {
                    return md;
                }
                K.CompilationUnit cu = (K.CompilationUnit) parsed.get(0);
                for (Statement topLevel : cu.getStatements()) {
                    if (topLevel instanceof J.MethodDeclaration) {
                        J.MethodDeclaration parsedFun = (J.MethodDeclaration) topLevel;
                        return md.getPadding().withParameters(
                                parsedFun.getPadding().getParameters());
                    }
                }
                return md;
            }

            private boolean isKotlinSource() {
                return getCursor().firstEnclosing(SourceFile.class) instanceof K.CompilationUnit;
            }
        };
    }
}
