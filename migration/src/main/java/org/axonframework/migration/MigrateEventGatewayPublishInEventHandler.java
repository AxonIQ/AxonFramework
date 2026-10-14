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
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.Markers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Axon Framework 5's {@code EventGateway.publish(..)} takes the {@code ProcessingContext} as its first argument.
 * A {@code @CommandHandler}, {@code @EventHandler} or {@code @QueryHandler} method that publishes through an
 * {@code EventGateway} field gets a {@code ProcessingContext context} parameter, and every
 * {@code gateway.publish(a, b)} in it becomes {@code gateway.publish(context, a, b)}. An existing
 * {@code ProcessingContext} parameter is reused.
 * <p>
 * Private helper methods that a handler calls, directly or through another helper, and that publish through the
 * gateway get the same parameter, and each call site passes the context on. Other methods are left untouched, as
 * their callers have no context to pass. Java and Kotlin sources are supported.
 *
 * @author Stefan Dragisic
 * @since 5.4.0
 */
public class MigrateEventGatewayPublishInEventHandler extends Recipe {

    private static final String EVENT_GATEWAY_AF4_FQN = "org.axonframework.eventhandling.gateway.EventGateway";
    private static final String EVENT_GATEWAY_AF5_FQN = "org.axonframework.messaging.eventhandling.gateway.EventGateway";
    private static final String PROCESSING_CONTEXT_FQN = "org.axonframework.messaging.core.unitofwork.ProcessingContext";
    private static final Set<String> HANDLER_ANNOTATIONS = Set.of("CommandHandler", "EventHandler", "QueryHandler");
    private static final String MIGRATION_KEY = "axon.eventGatewayPublishMigration";

    /**
     * What one class needs: its gateway fields, the signatures of the methods receiving the context, the call keys
     * of those methods, and the call keys of methods that already declared a context before the migration.
     */
    private record Migration(Set<String> gateways, Set<String> signatures, Set<String> callKeys,
                             Set<String> hadContext) {

        private static final Migration NONE = new Migration(Set.of(), Set.of(), Set.of(), Set.of());
    }

    @Override
    public String getDisplayName() {
        return "Pass the ProcessingContext into EventGateway.publish(..) inside message handlers";
    }

    @Override
    public String getDescription() {
        return "Adds a `ProcessingContext` parameter to `@CommandHandler`, `@EventHandler` and `@QueryHandler` "
                + "methods that publish through an `EventGateway` field, and to the private helper methods they "
                + "call that publish, and passes it as the first argument of every `publish(..)` call, as Axon "
                + "Framework 5 requires.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDeclaration,
                                                             ExecutionContext ctx) {
                // Always set, so a nested class never picks up the migration of its enclosing class.
                getCursor().putMessage(MIGRATION_KEY, migrationFor(classDeclaration));
                return super.visitClassDeclaration(classDeclaration, ctx);
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                Migration migration = getCursor().getNearestMessage(MIGRATION_KEY);
                if (migration == null || method.getBody() == null
                        || !migration.signatures().contains(signatureOf(method))) {
                    return super.visitMethodDeclaration(method, ctx);
                }

                String existing = processingContextParameterName(method);
                String contextName = existing != null ? existing : availableParameterName(method, "context");
                J.MethodDeclaration migrated = existing != null ? method : addContextParameter(method, contextName);

                migrated = (J.MethodDeclaration) new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation,
                                                                    ExecutionContext executionContext) {
                        J.MethodInvocation visited = super.visitMethodInvocation(invocation, executionContext);
                        if (isPublishThrough(visited, migration.gateways())) {
                            return startsWithContext(visited, contextName)
                                    ? visited
                                    : withContextFirst(visited, contextName);
                        }
                        if (isHelperCall(visited, migration)) {
                            return withContextLast(visited, contextName);
                        }
                        return visited;
                    }
                }.visitNonNull(migrated, ctx, getCursor().getParentOrThrow());

                maybeAddImport(PROCESSING_CONTEXT_FQN, false);
                return migrated;
            }

            private Migration migrationFor(J.ClassDeclaration classDeclaration) {
                Set<String> gateways = gatewayFieldNames(classDeclaration);
                if (gateways.isEmpty()) {
                    return Migration.NONE;
                }
                Set<String> signatures = methodsNeedingContext(classDeclaration, gateways);
                if (signatures.isEmpty()) {
                    return Migration.NONE;
                }
                return new Migration(gateways, signatures, callKeysOf(classDeclaration, signatures),
                                     methodsWithContextParameter(classDeclaration));
            }

            /**
             * The handlers that publish, directly or through private helpers, plus those helpers. A helper only
             * reachable from other methods is left out, as its caller has no context to pass.
             */
            private Set<String> methodsNeedingContext(J.ClassDeclaration classDeclaration, Set<String> gateways) {
                List<J.MethodDeclaration> eligible = new ArrayList<>();
                for (Statement statement : classDeclaration.getBody().getStatements()) {
                    if (statement instanceof J.MethodDeclaration method && method.getBody() != null
                            && (isHandler(method) || method.hasModifier(J.Modifier.Type.Private))) {
                        eligible.add(method);
                    }
                }

                Set<String> publishing = new LinkedHashSet<>();
                for (J.MethodDeclaration method : eligible) {
                    if (publishesThrough(method, gateways)) {
                        publishing.add(signatureOf(method));
                    }
                }
                boolean changed = true;
                while (changed) {
                    changed = false;
                    Set<String> callKeys = callKeysOf(classDeclaration, publishing);
                    for (J.MethodDeclaration method : eligible) {
                        if (!publishing.contains(signatureOf(method)) && callsAnyOf(method, callKeys)) {
                            publishing.add(signatureOf(method));
                            changed = true;
                        }
                    }
                }

                Set<String> reachable = new LinkedHashSet<>();
                Deque<J.MethodDeclaration> pending = new ArrayDeque<>();
                for (J.MethodDeclaration method : eligible) {
                    if (isHandler(method) && publishing.contains(signatureOf(method))) {
                        reachable.add(signatureOf(method));
                        pending.add(method);
                    }
                }
                while (!pending.isEmpty()) {
                    J.MethodDeclaration caller = pending.poll();
                    for (J.MethodDeclaration helper : eligible) {
                        String signature = signatureOf(helper);
                        if (publishing.contains(signature) && !reachable.contains(signature)
                                && callsAnyOf(caller, Set.of(callKeyOf(helper)))) {
                            reachable.add(signature);
                            pending.add(helper);
                        }
                    }
                }
                return reachable;
            }

            private boolean isHandler(J.MethodDeclaration method) {
                for (J.Annotation annotation : method.getLeadingAnnotations()) {
                    if (HANDLER_ANNOTATIONS.contains(annotation.getSimpleName())) {
                        return true;
                    }
                }
                return false;
            }

            private boolean isKotlinSource() {
                return getCursor().firstEnclosing(SourceFile.class) instanceof K.CompilationUnit;
            }

            private Set<String> gatewayFieldNames(J.ClassDeclaration classDeclaration) {
                Set<String> names = new HashSet<>();
                for (Statement statement : classDeclaration.getBody().getStatements()) {
                    if (statement instanceof J.VariableDeclarations declarations && isGatewayType(declarations)) {
                        for (J.VariableDeclarations.NamedVariable variable : declarations.getVariables()) {
                            names.add(variable.getSimpleName());
                        }
                    }
                }
                return names;
            }

            private boolean isGatewayType(J.VariableDeclarations declarations) {
                if (declarations.getTypeExpression() == null) {
                    return false;
                }
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(declarations.getTypeExpression().getType());
                if (type != null) {
                    String name = type.getFullyQualifiedName();
                    return name.equals(EVENT_GATEWAY_AF4_FQN) || name.equals(EVENT_GATEWAY_AF5_FQN);
                }
                String source = declarations.getTypeExpression().toString();
                return source.equals("EventGateway")
                        || source.equals(EVENT_GATEWAY_AF4_FQN)
                        || source.equals(EVENT_GATEWAY_AF5_FQN);
            }

            private boolean publishesThrough(J.MethodDeclaration method, Set<String> gateways) {
                boolean[] found = {false};
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation,
                                                                    ExecutionContext executionContext) {
                        if (isPublishThrough(invocation, gateways)) {
                            found[0] = true;
                        }
                        return found[0] ? invocation : super.visitMethodInvocation(invocation, executionContext);
                    }
                }.visit(method.getBody(), new InMemoryExecutionContext());
                return found[0];
            }

            private boolean isPublishThrough(J.MethodInvocation invocation, Set<String> gateways) {
                if (!invocation.getSimpleName().equals("publish")) {
                    return false;
                }
                Expression select = invocation.getSelect();
                if (select instanceof J.Identifier identifier) {
                    return gateways.contains(identifier.getSimpleName());
                }
                if (select instanceof J.FieldAccess access) {
                    return gateways.contains(access.getName().getSimpleName())
                            && access.getTarget() instanceof J.Identifier target
                            && target.getSimpleName().equals("this");
                }
                return false;
            }

            private boolean startsWithContext(J.MethodInvocation invocation, String contextName) {
                if (argumentCount(invocation) == 0) {
                    return false;
                }
                Expression first = invocation.getArguments().get(0);
                if (first instanceof J.Identifier identifier && identifier.getSimpleName().equals(contextName)) {
                    return true;
                }
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(first.getType());
                return type != null && type.getFullyQualifiedName().equals(PROCESSING_CONTEXT_FQN);
            }

            private J.MethodInvocation withContextFirst(J.MethodInvocation invocation, String contextName) {
                List<Expression> extended = new ArrayList<>();
                extended.add(contextIdentifier(contextName, Space.EMPTY));
                if (argumentCount(invocation) > 0) {
                    List<Expression> arguments = invocation.getArguments();
                    for (int i = 0; i < arguments.size(); i++) {
                        Expression argument = arguments.get(i);
                        extended.add(i == 0 ? argument.withPrefix(Space.format(" ")) : argument);
                    }
                }
                return invocation.withArguments(extended);
            }

            private J.MethodInvocation withContextLast(J.MethodInvocation invocation, String contextName) {
                if (argumentCount(invocation) == 0) {
                    return invocation.withArguments(
                            Collections.singletonList(contextIdentifier(contextName, Space.EMPTY)));
                }
                List<Expression> extended = new ArrayList<>(invocation.getArguments());
                extended.add(contextIdentifier(contextName, Space.format(" ")));
                return invocation.withArguments(extended);
            }

            private J.Identifier contextIdentifier(String contextName, Space prefix) {
                return new J.Identifier(Tree.randomId(), prefix, Markers.EMPTY, Collections.emptyList(),
                                        contextName, null, null);
            }

            /** A call to a helper that gets the context from this recipe, so its call sites must pass it. */
            private boolean isHelperCall(J.MethodInvocation invocation, Migration migration) {
                if (!isOwnMethodCall(invocation)) {
                    return false;
                }
                String key = callKeyOf(invocation.getSimpleName(), argumentCount(invocation));
                return migration.callKeys().contains(key) && !migration.hadContext().contains(key);
            }

            /** A call to a method of this class: no select, or {@code this}. */
            private boolean isOwnMethodCall(J.MethodInvocation invocation) {
                Expression select = invocation.getSelect();
                return select == null
                        || (select instanceof J.Identifier identifier && identifier.getSimpleName().equals("this"));
            }

            private boolean callsAnyOf(J.MethodDeclaration method, Set<String> callKeys) {
                if (method.getBody() == null || callKeys.isEmpty()) {
                    return false;
                }
                boolean[] found = {false};
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation,
                                                                    ExecutionContext executionContext) {
                        if (isOwnMethodCall(invocation)
                                && callKeys.contains(callKeyOf(invocation.getSimpleName(), argumentCount(invocation)))) {
                            found[0] = true;
                        }
                        return found[0] ? invocation : super.visitMethodInvocation(invocation, executionContext);
                    }
                }.visit(method.getBody(), new InMemoryExecutionContext());
                return found[0];
            }

            /** Method name plus parameter types: overloads are tracked one by one. */
            private String signatureOf(J.MethodDeclaration method) {
                StringBuilder signature = new StringBuilder(method.getSimpleName()).append('(');
                for (Statement parameter : method.getParameters()) {
                    if (parameter instanceof J.VariableDeclarations declarations
                            && declarations.getTypeExpression() != null) {
                        signature.append(declarations.getTypeExpression().toString()).append(',');
                    }
                }
                return signature.append(')').toString();
            }

            /** Name plus parameter count, the shape a call site exposes without type attribution. */
            private String callKeyOf(String name, int arity) {
                return name + "/" + arity;
            }

            private String callKeyOf(J.MethodDeclaration method) {
                return callKeyOf(method.getSimpleName(), parameterCount(method));
            }

            private Set<String> callKeysOf(J.ClassDeclaration classDeclaration, Set<String> signatures) {
                Set<String> keys = new HashSet<>();
                for (Statement statement : classDeclaration.getBody().getStatements()) {
                    if (statement instanceof J.MethodDeclaration method && signatures.contains(signatureOf(method))) {
                        keys.add(callKeyOf(method));
                    }
                }
                return keys;
            }

            private Set<String> methodsWithContextParameter(J.ClassDeclaration classDeclaration) {
                Set<String> keys = new HashSet<>();
                for (Statement statement : classDeclaration.getBody().getStatements()) {
                    if (statement instanceof J.MethodDeclaration method
                            && processingContextParameterName(method) != null) {
                        keys.add(callKeyOf(method));
                    }
                }
                return keys;
            }

            private int parameterCount(J.MethodDeclaration method) {
                List<Statement> parameters = method.getParameters();
                return parameters.size() == 1 && parameters.get(0) instanceof J.Empty ? 0 : parameters.size();
            }

            private int argumentCount(J.MethodInvocation invocation) {
                List<Expression> arguments = invocation.getArguments();
                return arguments.size() == 1 && arguments.get(0) instanceof J.Empty ? 0 : arguments.size();
            }

            private String processingContextParameterName(J.MethodDeclaration method) {
                for (Statement parameter : method.getParameters()) {
                    if (parameter instanceof J.VariableDeclarations declarations
                            && declarations.getTypeExpression() != null
                            && !declarations.getVariables().isEmpty()) {
                        String source = declarations.getTypeExpression().toString();
                        JavaType.FullyQualified type = TypeUtils.asFullyQualified(declarations.getTypeExpression().getType());
                        if (source.equals("ProcessingContext") || source.equals(PROCESSING_CONTEXT_FQN)
                                || (type != null && type.getFullyQualifiedName().equals(PROCESSING_CONTEXT_FQN))) {
                            return declarations.getVariables().get(0).getSimpleName();
                        }
                    }
                }
                return null;
            }

            private String availableParameterName(J.MethodDeclaration method, String baseName) {
                String candidate = baseName;
                int suffix = 1;
                while (hasParameterNamed(method, candidate)) {
                    candidate = baseName + suffix++;
                }
                return candidate;
            }

            private boolean hasParameterNamed(J.MethodDeclaration method, String name) {
                for (Statement parameter : method.getParameters()) {
                    if (parameter instanceof J.VariableDeclarations declarations) {
                        for (J.VariableDeclarations.NamedVariable variable : declarations.getVariables()) {
                            if (variable.getSimpleName().equals(name)) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }

            private J.MethodDeclaration addContextParameter(J.MethodDeclaration method, String parameterName) {
                if (isKotlinSource()) {
                    return addKotlinContextParameter(method, parameterName);
                }
                List<Object> templateArguments = new ArrayList<>();
                StringBuilder template = new StringBuilder();
                List<Statement> existing = method.getParameters();
                if (parameterCount(method) > 0) {
                    for (int i = 0; i < existing.size(); i++) {
                        if (i > 0) {
                            template.append(", ");
                        }
                        template.append("#{}");
                        templateArguments.add(existing.get(i).print(getCursor()).trim());
                    }
                    template.append(", ");
                }
                template.append("ProcessingContext ").append(parameterName);
                return JavaTemplate.builder(template.toString())
                                   .imports(PROCESSING_CONTEXT_FQN)
                                   .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                                   .build()
                                   .apply(getCursor(), method.getCoordinates().replaceParameters(), templateArguments.toArray());
            }

            /** Kotlin has no JavaTemplate: parse a stub function with the new parameter list and take its parameters. */
            private J.MethodDeclaration addKotlinContextParameter(J.MethodDeclaration method, String parameterName) {
                StringBuilder parameters = new StringBuilder();
                if (parameterCount(method) > 0) {
                    for (Statement parameter : method.getParameters()) {
                        parameters.append(parameter.print(getCursor()).trim()).append(", ");
                    }
                }
                parameters.append(parameterName).append(": ProcessingContext");

                String snippet = "package _temp\n\nimport " + PROCESSING_CONTEXT_FQN + "\n\nfun _f("
                        + parameters + ") {}\n";
                List<SourceFile> parsed;
                try {
                    parsed = KotlinParser.builder().build().parse(snippet)
                                         .filter(source -> source instanceof K.CompilationUnit)
                                         .toList();
                } catch (RuntimeException exception) {
                    return method;
                }
                if (parsed.isEmpty()) {
                    return method;
                }
                for (Statement statement : ((K.CompilationUnit) parsed.get(0)).getStatements()) {
                    if (statement instanceof J.MethodDeclaration stub) {
                        return method.getPadding().withParameters(stub.getPadding().getParameters());
                    }
                }
                return method;
            }
        };
    }
}
