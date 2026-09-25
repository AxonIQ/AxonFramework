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
import org.openrewrite.marker.Markers;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Migrates Axon Framework 4's static {@code SagaLifecycle} access to the context-scoped Axon Framework 5 legacy API.
 * <p>
 * Every {@code @SagaEventHandler} that invokes {@code SagaLifecycle.associateWith(...)},
 * {@code SagaLifecycle.removeAssociationWith(...)}, {@code SagaLifecycle.end()}, or
 * {@code SagaLifecycle.associationValues()} receives a {@code SagaLifecycle} method parameter. Calls are redirected
 * to that parameter. Axon Framework 5 injects the active Saga lifecycle into annotated handler parameters.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public class MigrateSagaLifecycle extends Recipe {

    private static final String SAGA_EVENT_HANDLER_FQN = "org.axonframework.modelling.saga.SagaEventHandler";
    private static final String SAGA_LIFECYCLE_FQN = "org.axonframework.modelling.saga.SagaLifecycle";
    private static final Set<String> LIFECYCLE_METHODS = Set.of(
            "associateWith", "removeAssociationWith", "end", "associationValues"
    );

    @Override
    public String getDisplayName() {
        return "Inject SagaLifecycle into legacy Saga event handlers";
    }

    @Override
    public String getDescription() {
        return "Replaces static Axon Framework 4 `SagaLifecycle` calls in `@SagaEventHandler` methods with calls on "
                + "an injected `SagaLifecycle` method parameter.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                if (!isSagaEventHandler(method) || method.getBody() == null || !containsLifecycleCall(method)) {
                    return super.visitMethodDeclaration(method, ctx);
                }

                String existingParameter = lifecycleParameterName(method);
                String parameterName = existingParameter == null ? availableParameterName(method) : existingParameter;
                J.MethodDeclaration migrated = existingParameter == null
                        ? addLifecycleParameter(method, parameterName)
                        : method;

                migrated = (J.MethodDeclaration) new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation,
                                                                    ExecutionContext executionContext) {
                        J.MethodInvocation visited = super.visitMethodInvocation(invocation, executionContext);
                        if (!isLifecycleCall(visited)) {
                            return visited;
                        }
                        Space prefix = visited.getSelect() == null
                                ? Space.EMPTY
                                : visited.getSelect().getPrefix();
                        J.Identifier lifecycle = new J.Identifier(
                                Tree.randomId(),
                                prefix,
                                Markers.EMPTY,
                                Collections.emptyList(),
                                parameterName,
                                null,
                                null
                        );
                        return visited.withSelect(lifecycle);
                    }
                }.visitNonNull(migrated, ctx, getCursor().getParentOrThrow());

                maybeAddImport(SAGA_LIFECYCLE_FQN, false);
                for (String methodName : LIFECYCLE_METHODS) {
                    maybeRemoveImport(SAGA_LIFECYCLE_FQN + "." + methodName);
                }
                return migrated;
            }

            private boolean isSagaEventHandler(J.MethodDeclaration method) {
                for (J.Annotation annotation : method.getLeadingAnnotations()) {
                    JavaType.FullyQualified type = TypeUtils.asFullyQualified(annotation.getType());
                    if (type != null && SAGA_EVENT_HANDLER_FQN.equals(type.getFullyQualifiedName())) {
                        return true;
                    }
                    if (annotation.getSimpleName().equals("SagaEventHandler")) {
                        return true;
                    }
                }
                return false;
            }

            private boolean containsLifecycleCall(J.MethodDeclaration method) {
                boolean[] found = {false};
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation,
                                                                    ExecutionContext executionContext) {
                        if (isLifecycleCall(invocation)) {
                            found[0] = true;
                        }
                        return found[0] ? invocation : super.visitMethodInvocation(invocation, executionContext);
                    }
                }.visit(method.getBody(), new InMemoryExecutionContext());
                return found[0];
            }

            private boolean isLifecycleCall(J.MethodInvocation invocation) {
                if (!LIFECYCLE_METHODS.contains(invocation.getSimpleName())) {
                    return false;
                }
                Expression select = invocation.getSelect();
                if (select instanceof J.Identifier) {
                    return ((J.Identifier) select).getSimpleName().equals("SagaLifecycle");
                }
                if (select instanceof J.FieldAccess) {
                    return select.toString().equals(SAGA_LIFECYCLE_FQN);
                }
                if (select != null) {
                    return false;
                }
                JavaType.Method methodType = invocation.getMethodType();
                if (methodType != null
                        && TypeUtils.isOfClassType(methodType.getDeclaringType(), SAGA_LIFECYCLE_FQN)) {
                    return true;
                }
                // A call without a select can be an AF4 static import. Type attribution handles the normal case;
                // the fallback covers partially-attributed migration sources that retain the static import.
                return select == null && hasStaticLifecycleImport(invocation.getSimpleName());
            }

            private boolean hasStaticLifecycleImport(String methodName) {
                J.CompilationUnit compilationUnit = getCursor().firstEnclosing(J.CompilationUnit.class);
                if (compilationUnit == null) {
                    return false;
                }
                String exact = SAGA_LIFECYCLE_FQN + "." + methodName;
                for (J.Import anImport : compilationUnit.getImports()) {
                    if (!anImport.isStatic()) {
                        continue;
                    }
                    String imported = anImport.getQualid().toString();
                    if (imported.equals(exact) || imported.equals(SAGA_LIFECYCLE_FQN + ".*")) {
                        return true;
                    }
                }
                return false;
            }

            private String lifecycleParameterName(J.MethodDeclaration method) {
                for (Statement parameter : method.getParameters()) {
                    if (!(parameter instanceof J.VariableDeclarations)) {
                        continue;
                    }
                    J.VariableDeclarations declarations = (J.VariableDeclarations) parameter;
                    if (declarations.getTypeExpression() == null || declarations.getVariables().isEmpty()) {
                        continue;
                    }
                    JavaType.FullyQualified type = TypeUtils.asFullyQualified(
                            declarations.getTypeExpression().getType()
                    );
                    if ((type != null && SAGA_LIFECYCLE_FQN.equals(type.getFullyQualifiedName()))
                            || declarations.getTypeExpression().toString().equals("SagaLifecycle")) {
                        return declarations.getVariables().get(0).getSimpleName();
                    }
                }
                return null;
            }

            private String availableParameterName(J.MethodDeclaration method) {
                String candidate = "sagaLifecycle";
                int suffix = 1;
                while (hasParameterNamed(method, candidate)) {
                    candidate = "sagaLifecycle" + suffix++;
                }
                return candidate;
            }

            private boolean hasParameterNamed(J.MethodDeclaration method, String name) {
                for (Statement parameter : method.getParameters()) {
                    if (parameter instanceof J.VariableDeclarations) {
                        J.VariableDeclarations declarations = (J.VariableDeclarations) parameter;
                        if (!declarations.getVariables().isEmpty()
                                && declarations.getVariables().get(0).getSimpleName().equals(name)) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private J.MethodDeclaration addLifecycleParameter(J.MethodDeclaration method, String parameterName) {
                if (isKotlinSource()) {
                    return addKotlinLifecycleParameter(method, parameterName);
                }
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
                        templateArguments.add(existing.get(i).print(getCursor()));
                    }
                    template.append(", ");
                }
                template.append("SagaLifecycle ").append(parameterName);
                return JavaTemplate.builder(template.toString())
                        .imports(SAGA_LIFECYCLE_FQN)
                        .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                        .build()
                        .apply(getCursor(), method.getCoordinates().replaceParameters(), templateArguments.toArray());
            }

            private J.MethodDeclaration addKotlinLifecycleParameter(J.MethodDeclaration method, String parameterName) {
                return addKotlinParameter(method, parameterName + ": SagaLifecycle", SAGA_LIFECYCLE_FQN);
            }

            private J.MethodDeclaration addKotlinParameter(J.MethodDeclaration method,
                                                            String newParameter,
                                                            String parameterType) {
                List<Statement> existing = method.getParameters();
                boolean hasExisting = !(existing.size() == 1 && existing.get(0) instanceof J.Empty);
                StringBuilder parameters = new StringBuilder();
                if (hasExisting) {
                    for (int i = 0; i < existing.size(); i++) {
                        if (i > 0) {
                            parameters.append(", ");
                        }
                        parameters.append(existing.get(i).print(getCursor()).trim());
                    }
                    parameters.append(", ");
                }
                parameters.append(newParameter);

                String snippet = "package _temp\n\nimport " + parameterType + "\n\nfun _f(" + parameters + ") {}\n";
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
                K.CompilationUnit compilationUnit = (K.CompilationUnit) parsed.get(0);
                for (Statement statement : compilationUnit.getStatements()) {
                    if (statement instanceof J.MethodDeclaration) {
                        return method.getPadding().withParameters(
                                ((J.MethodDeclaration) statement).getPadding().getParameters()
                        );
                    }
                }
                return method;
            }

            private boolean isKotlinSource() {
                return getCursor().firstEnclosing(SourceFile.class) instanceof K.CompilationUnit;
            }
        };
    }
}
