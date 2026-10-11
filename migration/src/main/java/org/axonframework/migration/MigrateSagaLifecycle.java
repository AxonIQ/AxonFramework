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

import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.marker.Markers;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.Comment;
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
 * <p>
 * The legacy module injects that parameter into {@code @SagaEventHandler} methods only, so other Saga methods calling
 * the static {@code SagaLifecycle} need a different shape. A {@code @DeadlineHandler} whose only lifecycle call is a
 * closing {@code SagaLifecycle.end()} statement is annotated with {@code @EndSaga} instead, which ends the Saga once
 * the handler completes, exactly like that last statement did. Every other static call outside a
 * {@code @SagaEventHandler} of a Saga, such as a conditional end, an association change in a deadline handler, or a
 * call in a private helper, is kept and marked with a {@code // TODO(axon4to5):} comment for a manual migration.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public class MigrateSagaLifecycle extends Recipe {

    private static final String SAGA_EVENT_HANDLER_FQN = "org.axonframework.modelling.saga.SagaEventHandler";
    private static final String SAGA_LIFECYCLE_FQN = "org.axonframework.modelling.saga.SagaLifecycle";
    private static final String SAGA_STEREOTYPE_FQN = "org.axonframework.spring.stereotype.Saga";
    private static final String DEADLINE_HANDLER_FQN = "org.axonframework.deadline.annotation.DeadlineHandler";
    private static final String END_SAGA_FQN = "org.axonframework.modelling.saga.EndSaga";
    private static final StatementTodoMarker.Todo STATIC_LIFECYCLE_TODO = new StatementTodoMarker.Todo(
            "TODO(axon4to5): replace this static SagaLifecycle call",
            " TODO(axon4to5): replace this static SagaLifecycle call. axoniq-legacy has no static SagaLifecycle"
                    + " methods and injects a SagaLifecycle parameter into @SagaEventHandler methods only. End the"
                    + " Saga unconditionally with @EndSaga on the handler, or declare a ProcessingContext handler"
                    + " parameter and call SagaLifecycle.forContext(processingContext)."
    );
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
                + "an injected `SagaLifecycle` method parameter. A Saga `@DeadlineHandler` ending with an "
                + "unconditional `SagaLifecycle.end()` gets `@EndSaga` instead. Other static calls outside a "
                + "`@SagaEventHandler` of a Saga are marked with a `// TODO(axon4to5):` comment, because the legacy "
                + "module injects `SagaLifecycle` into `@SagaEventHandler` methods only.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                if (method.getBody() == null || lifecycleCallCount(method) == 0) {
                    return super.visitMethodDeclaration(method, ctx);
                }
                if (!isSagaEventHandler(method)) {
                    return isDeclaredInSaga()
                            ? migrateOutsideSagaEventHandler(method)
                            : super.visitMethodDeclaration(method, ctx);
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

            private int lifecycleCallCount(J.MethodDeclaration method) {
                int[] count = {0};
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation,
                                                                    ExecutionContext executionContext) {
                        if (isLifecycleCall(invocation)) {
                            count[0]++;
                        }
                        return super.visitMethodInvocation(invocation, executionContext);
                    }
                }.visit(method.getBody(), new InMemoryExecutionContext());
                return count[0];
            }

            /**
             * Migrates a Saga method that is not a {@code @SagaEventHandler}, so the legacy module will not inject a
             * {@code SagaLifecycle} into it. Only the closing unconditional end of a deadline handler has a 1:1
             * replacement: {@code @EndSaga}, which Axon Framework 4 and the legacy module both apply to any Saga
             * handler and which ends the Saga after the handler completes, just like a last statement does.
             */
            private J.MethodDeclaration migrateOutsideSagaEventHandler(J.MethodDeclaration method) {
                if (isAnnotatedWith(method, DEADLINE_HANDLER_FQN, "DeadlineHandler")
                        && lifecycleCallCount(method) == 1
                        && endsWithLifecycleEnd(method)) {
                    return replaceClosingEndWithAnnotation(method);
                }
                return (J.MethodDeclaration) new JavaIsoVisitor<Integer>() {
                    @Override
                    public J.Block visitBlock(J.Block block, Integer p) {
                        return StatementTodoMarker.markStatements(
                                super.visitBlock(block, p),
                                invocation -> isLifecycleCall(invocation) ? STATIC_LIFECYCLE_TODO : null
                        );
                    }
                }.visitNonNull(method, 0, getCursor().getParentOrThrow());
            }

            private boolean endsWithLifecycleEnd(J.MethodDeclaration method) {
                List<Statement> statements = requireBody(method).getStatements();
                return !statements.isEmpty()
                        && statements.get(statements.size() - 1) instanceof J.MethodInvocation last
                        && "end".equals(last.getSimpleName())
                        && last.getArguments().stream().allMatch(J.Empty.class::isInstance)
                        && isLifecycleCall(last);
            }

            private J.MethodDeclaration replaceClosingEndWithAnnotation(J.MethodDeclaration method) {
                J.Block body = requireBody(method);
                List<Statement> statements = body.getStatements();
                Statement end = statements.get(statements.size() - 1);
                J.Block withoutEnd = body.withStatements(new ArrayList<>(statements.subList(0, statements.size() - 1)))
                                         .withEnd(keepComments(end.getPrefix(), body.getEnd()));
                J.MethodDeclaration migrated = method.withBody(withoutEnd);
                if (!isAnnotatedWith(migrated, END_SAGA_FQN, "EndSaga")) {
                    migrated = prependAnnotation(migrated, endSagaAnnotation());
                    maybeAddImport(END_SAGA_FQN, null, false);
                }
                maybeRemoveImport(SAGA_LIFECYCLE_FQN);
                maybeRemoveImport(SAGA_LIFECYCLE_FQN + ".end");
                return migrated;
            }

            private J.Block requireBody(J.MethodDeclaration method) {
                J.Block body = method.getBody();
                if (body == null) {
                    throw new IllegalStateException("Expected a method with a body: " + method.getSimpleName());
                }
                return body;
            }

            /**
             * Moves the comments above a removed statement in front of the closing brace, so removing the statement
             * never drops a user's comment.
             */
            private Space keepComments(Space removedPrefix, Space blockEnd) {
                List<Comment> comments = removedPrefix.getComments();
                if (comments.isEmpty()) {
                    return blockEnd;
                }
                List<Comment> moved = ListUtils.mapLast(comments, last -> last.withSuffix(blockEnd.getWhitespace()));
                return Space.build(removedPrefix.getWhitespace(), ListUtils.concatAll(moved, blockEnd.getComments()));
            }

            /**
             * Builds the annotation directly instead of through a template, so the same tree serves Java and Kotlin
             * sources. The identifier carries the {@code EndSaga} type for later visitors matching on types.
             */
            private J.Annotation endSagaAnnotation() {
                J.Identifier name = new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        "EndSaga",
                        JavaType.ShallowClass.build(END_SAGA_FQN),
                        null
                );
                return new J.Annotation(Tree.randomId(), Space.EMPTY, Markers.EMPTY, name, null);
            }

            /**
             * Puts the annotation first, on the line of the current first annotation, and moves that annotation to
             * the next line. A {@code @DeadlineHandler} always has a leading annotation to take the place of.
             */
            private J.MethodDeclaration prependAnnotation(J.MethodDeclaration method, J.Annotation annotation) {
                List<J.Annotation> annotations = method.getLeadingAnnotations();
                J.Annotation first = annotations.get(0);
                String whitespace = method.getPrefix().getWhitespace();
                String indent = whitespace.contains("\n")
                        ? whitespace.substring(whitespace.lastIndexOf('\n') + 1)
                        : "";
                return method.withLeadingAnnotations(ListUtils.concat(
                        annotation.withPrefix(first.getPrefix()),
                        ListUtils.mapFirst(annotations, a -> a.withPrefix(Space.format("\n" + indent)))
                ));
            }

            private boolean isDeclaredInSaga() {
                J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (enclosing == null) {
                    return false;
                }
                if (enclosing.getLeadingAnnotations().stream()
                             .anyMatch(annotation -> isAnnotation(annotation, SAGA_STEREOTYPE_FQN, "Saga"))) {
                    return true;
                }
                return enclosing.getBody().getStatements().stream()
                                .filter(J.MethodDeclaration.class::isInstance)
                                .map(J.MethodDeclaration.class::cast)
                                .anyMatch(this::isSagaEventHandler);
            }

            private boolean isAnnotatedWith(J.MethodDeclaration method, String fullyQualifiedName, String simpleName) {
                return method.getLeadingAnnotations().stream()
                             .anyMatch(annotation -> isAnnotation(annotation, fullyQualifiedName, simpleName));
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
                        // trim: the printed parameter carries its own leading space, the template adds ", "
                        templateArguments.add(existing.get(i).print(getCursor()).trim());
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

    private static boolean isAnnotation(J.Annotation annotation, String fullyQualifiedName, String simpleName) {
        String type = resolvedName(annotation.getType());
        return type != null ? fullyQualifiedName.equals(type) : simpleName.equals(annotation.getSimpleName());
    }

    /**
     * Returns the fully qualified name of a resolved type, or {@code null} when the type could not be resolved. The
     * callers fall back to the simple name only in that case, so a user type that happens to share a simple name with
     * an Axon type is not mistaken for it.
     */
    private static @Nullable String resolvedName(@Nullable JavaType javaType) {
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(javaType);
        return type == null || type instanceof JavaType.Unknown ? null : type.getFullyQualifiedName();
    }
}
