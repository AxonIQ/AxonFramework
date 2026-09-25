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
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.Markers;
import org.openrewrite.SourceFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Axon Framework 5's {@code EventGateway.publish(..)} takes the {@code ProcessingContext} as its first argument.
 * A {@code @CommandHandler}, {@code @EventHandler} or {@code @QueryHandler} method that publishes through an
 * {@code EventGateway} field gets a {@code ProcessingContext context} parameter, and every
 * {@code gateway.publish(a, b)} in it becomes {@code gateway.publish(context, a, b)}. An existing
 * {@code ProcessingContext} parameter is reused. Java sources only.
 */
public class MigrateEventGatewayPublishInHandlers extends Recipe {

    private static final String EVENT_GATEWAY_AF4_FQN = "org.axonframework.eventhandling.gateway.EventGateway";
    private static final String EVENT_GATEWAY_AF5_FQN = "org.axonframework.messaging.eventhandling.gateway.EventGateway";
    private static final String PROCESSING_CONTEXT_FQN = "org.axonframework.messaging.core.unitofwork.ProcessingContext";
    private static final Set<String> HANDLER_ANNOTATIONS = Set.of("CommandHandler", "EventHandler", "QueryHandler");

    @Override
    public String getDisplayName() {
        return "Pass the ProcessingContext into EventGateway.publish(..) inside message handlers";
    }

    @Override
    public String getDescription() {
        return "Adds a `ProcessingContext` parameter to `@CommandHandler`, `@EventHandler` and `@QueryHandler` "
                + "methods that publish through an `EventGateway` field, and passes it as the first argument of "
                + "every `publish(..)` call, as Axon Framework 5 requires. Java sources only.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                if (method.getBody() == null || !isHandler(method) || isKotlinSource()) {
                    return super.visitMethodDeclaration(method, ctx);
                }
                J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (enclosing == null) {
                    return super.visitMethodDeclaration(method, ctx);
                }
                Set<String> gateways = gatewayFieldNames(enclosing);
                if (gateways.isEmpty() || !publishesThrough(method, gateways)) {
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
                        if (!isPublishThrough(visited, gateways) || startsWithContext(visited, contextName)) {
                            return visited;
                        }
                        J.Identifier context = new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                                                                Collections.emptyList(), contextName, null, null);
                        List<Expression> arguments = visited.getArguments();
                        List<Expression> extended = new ArrayList<>();
                        extended.add(context);
                        if (!(arguments.size() == 1 && arguments.get(0) instanceof J.Empty)) {
                            for (int i = 0; i < arguments.size(); i++) {
                                Expression argument = arguments.get(i);
                                extended.add(i == 0 ? argument.withPrefix(Space.format(" ")) : argument);
                            }
                        }
                        return visited.withArguments(extended);
                    }
                }.visitNonNull(migrated, ctx, getCursor().getParentOrThrow());

                maybeAddImport(PROCESSING_CONTEXT_FQN, false);
                return migrated;
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
                Set<String> names = new java.util.HashSet<>();
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
                List<Expression> arguments = invocation.getArguments();
                if (arguments.isEmpty() || arguments.get(0) instanceof J.Empty) {
                    return false;
                }
                Expression first = arguments.get(0);
                if (first instanceof J.Identifier identifier && identifier.getSimpleName().equals(contextName)) {
                    return true;
                }
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(first.getType());
                return type != null && type.getFullyQualifiedName().equals(PROCESSING_CONTEXT_FQN);
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
                List<Object> templateArguments = new ArrayList<>();
                StringBuilder template = new StringBuilder();
                List<Statement> existing = method.getParameters();
                boolean hasExisting = !(existing.size() == 1 && existing.get(0) instanceof J.Empty);
                if (hasExisting) {
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
        };
    }
}
