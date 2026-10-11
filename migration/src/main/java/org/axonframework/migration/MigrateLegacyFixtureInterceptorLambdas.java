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
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Rewrites the Axon Framework 4 interceptor lambdas registered on the legacy test fixtures to the Axon Framework 5
 * interceptor signatures, which the fixtures ported by {@code axoniq-legacy-test} take.
 * <p>
 * Axon Framework 4 dispatch interceptors were a function over a message batch,
 * {@code messages -> (index, message) -> changed}, and handler interceptors received the unit of work,
 * {@code (unitOfWork, chain) -> chain.proceed()}, changing the message through {@code unitOfWork.transformMessage(..)}.
 * Axon Framework 5 interceptors receive the single message, the processing context and the chain, and continue by
 * handing the (possibly changed) message to the chain. The recipe rewrites the lambdas passed to:
 * <ul>
 *     <li>{@code SagaTestFixture} and the Saga {@code FixtureConfiguration}:
 *     {@code registerDeadlineDispatchInterceptor(..)}, {@code registerDeadlineHandlerInterceptor(..)} and
 *     {@code registerEventHandlerInterceptor(..)};</li>
 *     <li>{@code StubDeadlineManager}: {@code registerDispatchInterceptor(..)} and
 *     {@code registerHandlerInterceptor(..)}.</li>
 * </ul>
 * Three shapes are rewritten, each to {@code (message, context, chain) -> chain.proceed(..., context)}:
 * <ul>
 *     <li>{@code messages -> (i, m) -> expression}, passing the expression with {@code m} renamed;</li>
 *     <li>{@code (unitOfWork, chain) -> chain.proceed()}, passing the message unchanged;</li>
 *     <li>{@code (unitOfWork, chain) -> { unitOfWork.transformMessage(m -> expression); return chain.proceed(); }},
 *     passing the expression with {@code m} renamed.</li>
 * </ul>
 * Any other interceptor written in place (a lambda of another shape, one whose expression still uses the batch, index,
 * unit of work or chain, a method reference, or an anonymous class) gets a {@code // TODO(axon4to5):} marker on its
 * statement describing the Axon Framework 5 signature. An interceptor passed as a variable or as an instance of a
 * named class is left alone: the class is migrated by {@link MigrateMessageInterceptorSignatures}. Lambda parameter
 * names that would clash with a name already in scope get a numeric suffix. Java sources only. Idempotent: a lambda
 * already taking three parameters is in the Axon Framework 5 shape and is left alone.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public class MigrateLegacyFixtureInterceptorLambdas extends Recipe {

    private static final String SAGA_FIXTURE_CONFIGURATION_FQN = "org.axonframework.test.saga.FixtureConfiguration";
    private static final String SAGA_TEST_FIXTURE_FQN = "org.axonframework.test.saga.SagaTestFixture";
    private static final String STUB_DEADLINE_MANAGER_FQN = "org.axonframework.test.deadline.StubDeadlineManager";

    private static final Map<String, Role> SAGA_FIXTURE_METHODS = Map.of(
            "registerDeadlineDispatchInterceptor", Role.DISPATCH,
            "registerDeadlineHandlerInterceptor", Role.HANDLER,
            "registerEventHandlerInterceptor", Role.HANDLER
    );
    private static final Map<String, Role> STUB_DEADLINE_MANAGER_METHODS = Map.of(
            "registerDispatchInterceptor", Role.DISPATCH,
            "registerHandlerInterceptor", Role.HANDLER
    );
    /**
     * Method names specific enough to the Saga fixture to recognise them when the receiver's type is not resolved.
     */
    private static final Set<String> UNRESOLVED_FALLBACK_METHODS =
            Set.of("registerDeadlineDispatchInterceptor", "registerDeadlineHandlerInterceptor");

    private static final StatementTodoMarker.Todo DISPATCH_TODO = new StatementTodoMarker.Todo(
            "TODO(axon4to5): rewrite this dispatch interceptor",
            " TODO(axon4to5): rewrite this dispatch interceptor as"
                    + " (message, context, chain) -> chain.proceed(message, context)."
                    + " Axon Framework 5 interceptors receive one message and hand it on through the chain,"
                    + " instead of returning a function over a message batch."
    );
    private static final StatementTodoMarker.Todo HANDLER_TODO = new StatementTodoMarker.Todo(
            "TODO(axon4to5): rewrite this handler interceptor",
            " TODO(axon4to5): rewrite this handler interceptor as"
                    + " (message, context, chain) -> chain.proceed(message, context)."
                    + " Axon Framework 5 has no unit of work to transform the message on;"
                    + " pass the changed message to chain.proceed(..) instead."
    );

    private enum Role {
        DISPATCH, HANDLER
    }

    @Override
    public String getDisplayName() {
        return "Migrate interceptor lambdas on the legacy test fixtures";
    }

    @Override
    public String getDescription() {
        return "Rewrites Axon Framework 4 interceptor lambdas registered on `SagaTestFixture` "
                + "(`registerDeadlineDispatchInterceptor`, `registerDeadlineHandlerInterceptor`, "
                + "`registerEventHandlerInterceptor`) and on `StubDeadlineManager` (`registerDispatchInterceptor`, "
                + "`registerHandlerInterceptor`) to the Axon Framework 5 `(message, context, chain) -> "
                + "chain.proceed(message, context)` shape. Covers `messages -> (i, m) -> expression`, "
                + "`(unitOfWork, chain) -> chain.proceed()` and a `unitOfWork.transformMessage(..)` followed by "
                + "`chain.proceed()`. Leaves a `// TODO(axon4to5):` marker on any other interceptor written in place. "
                + "Java sources only. Idempotent.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public boolean isAcceptable(SourceFile sourceFile, ExecutionContext ctx) {
                return sourceFile instanceof J.CompilationUnit;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation, ExecutionContext ctx) {
                J.MethodInvocation visited = super.visitMethodInvocation(invocation, ctx);
                Role role = roleOf(visited);
                if (role == null || visited.getArguments().size() != 1
                        || !(visited.getArguments().getFirst() instanceof J.Lambda lambda)) {
                    return visited;
                }
                Set<String> namesInScope = namesInScope(getCursor());
                J.Lambda rewritten = role == Role.DISPATCH
                        ? rewriteDispatch(lambda, namesInScope)
                        : rewriteHandler(lambda, namesInScope);
                return rewritten == null ? visited : visited.withArguments(List.of(rewritten));
            }

            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                return StatementTodoMarker.markStatements(super.visitBlock(block, ctx),
                                                          MigrateLegacyFixtureInterceptorLambdas::todoFor);
            }
        };
    }

    private static @Nullable Role roleOf(J.MethodInvocation invocation) {
        String name = invocation.getSimpleName();
        JavaType.Method methodType = invocation.getMethodType();
        if (methodType == null || methodType.getDeclaringType() instanceof JavaType.Unknown) {
            return UNRESOLVED_FALLBACK_METHODS.contains(name) ? SAGA_FIXTURE_METHODS.get(name) : null;
        }
        String declaringType = methodType.getDeclaringType().getFullyQualifiedName();
        if (SAGA_FIXTURE_CONFIGURATION_FQN.equals(declaringType) || SAGA_TEST_FIXTURE_FQN.equals(declaringType)) {
            return SAGA_FIXTURE_METHODS.get(name);
        }
        if (STUB_DEADLINE_MANAGER_FQN.equals(declaringType)) {
            return STUB_DEADLINE_MANAGER_METHODS.get(name);
        }
        return null;
    }

    private static StatementTodoMarker.@Nullable Todo todoFor(J.MethodInvocation invocation) {
        Role role = roleOf(invocation);
        if (role == null || invocation.getArguments().size() != 1) {
            return null;
        }
        Expression argument = invocation.getArguments().getFirst();
        boolean writtenInPlace = (argument instanceof J.Lambda lambda
                && lambda.getParameters().getParameters().size() != 3)
                || argument instanceof J.MemberReference
                || (argument instanceof J.NewClass newClass && newClass.getBody() != null);
        if (!writtenInPlace) {
            return null;
        }
        return role == Role.DISPATCH ? DISPATCH_TODO : HANDLER_TODO;
    }

    /**
     * Rewrites {@code messages -> (i, m) -> expression}, or the same with a block body returning the expression.
     */
    private static J.@Nullable Lambda rewriteDispatch(J.Lambda outer, Set<String> namesInScope) {
        List<String> outerParameters = parameterNames(outer);
        if (outerParameters.size() != 1 || !(outer.getBody() instanceof J.Lambda inner)) {
            return null;
        }
        List<String> innerParameters = parameterNames(inner);
        Expression expression = returnedExpression(inner.getBody());
        if (innerParameters.size() != 2 || expression == null) {
            return null;
        }
        Set<String> references = referencedNames(expression);
        if (references.contains(outerParameters.getFirst()) || references.contains(innerParameters.getFirst())) {
            // The expression depends on the batch or the index, which Axon Framework 5 no longer hands over.
            return null;
        }
        return interceptorLambda(outer, expression, innerParameters.get(1), namesInScope);
    }

    /**
     * Rewrites {@code (unitOfWork, chain) -> chain.proceed()} and
     * {@code (unitOfWork, chain) -> { unitOfWork.transformMessage(m -> expression); return chain.proceed(); }}.
     */
    private static J.@Nullable Lambda rewriteHandler(J.Lambda lambda, Set<String> namesInScope) {
        List<String> parameters = parameterNames(lambda);
        if (parameters.size() != 2) {
            return null;
        }
        String unitOfWork = parameters.get(0);
        String chain = parameters.get(1);
        if (isProceed(returnedExpression(lambda.getBody()), chain)) {
            return interceptorLambda(lambda, null, null, namesInScope);
        }
        if (!(lambda.getBody() instanceof J.Block block) || block.getStatements().size() != 2
                || !(block.getStatements().get(0) instanceof J.MethodInvocation transform)
                || !(block.getStatements().get(1) instanceof J.Return returned)
                || !isProceed(returned.getExpression(), chain)
                || !"transformMessage".equals(transform.getSimpleName())
                || !isIdentifier(transform.getSelect(), unitOfWork)
                || transform.getArguments().size() != 1
                || !(transform.getArguments().getFirst() instanceof J.Lambda transformer)) {
            return null;
        }
        List<String> transformerParameters = parameterNames(transformer);
        Expression expression = returnedExpression(transformer.getBody());
        if (transformerParameters.size() != 1 || expression == null) {
            return null;
        }
        Set<String> references = referencedNames(expression);
        if (references.contains(unitOfWork) || references.contains(chain)) {
            return null;
        }
        return interceptorLambda(lambda, expression, transformerParameters.getFirst(), namesInScope);
    }

    private static boolean isProceed(@Nullable Expression expression, String chain) {
        return expression instanceof J.MethodInvocation invocation
                && "proceed".equals(invocation.getSimpleName())
                && isIdentifier(invocation.getSelect(), chain)
                && invocation.getArguments().stream().allMatch(J.Empty.class::isInstance);
    }

    private static boolean isIdentifier(@Nullable Expression expression, String name) {
        return expression instanceof J.Identifier identifier && name.equals(identifier.getSimpleName());
    }

    /**
     * The expression a lambda body evaluates to: the body itself, or the expression of a block holding a single
     * {@code return} statement.
     */
    private static @Nullable Expression returnedExpression(J body) {
        if (body instanceof Expression expression) {
            return expression;
        }
        if (body instanceof J.Block block && block.getStatements().size() == 1
                && block.getStatements().getFirst() instanceof J.Return returned) {
            return returned.getExpression();
        }
        return null;
    }

    /**
     * Builds {@code (message, context, chain) -> chain.proceed(passed, context)} from the {@code original} lambda,
     * where {@code passed} is the given {@code expression} with {@code messageParameter} renamed, or the message
     * itself when there is no expression.
     */
    private static J.Lambda interceptorLambda(J.Lambda original,
                                              @Nullable Expression expression,
                                              @Nullable String messageParameter,
                                              Set<String> namesInScope) {
        Set<String> taken = new HashSet<>(namesInScope);
        if (expression != null) {
            taken.addAll(referencedNames(expression));
            taken.remove(messageParameter);
        }
        String message = available("message", taken);
        taken.add(message);
        String context = available("context", taken);
        taken.add(context);
        String chain = available("chain", taken);

        Expression passed = expression == null
                ? identifier(message, Space.EMPTY)
                : rename(expression, messageParameter, message).withPrefix(Space.EMPTY);
        J.MethodInvocation proceed = new J.MethodInvocation(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                JRightPadded.build(identifier(chain, Space.EMPTY)),
                null,
                identifier("proceed", Space.EMPTY),
                JContainer.build(Space.EMPTY,
                                 List.of(JRightPadded.build(passed),
                                         JRightPadded.build(identifier(context, Space.SINGLE_SPACE))),
                                 Markers.EMPTY),
                null
        );

        J.VariableDeclarations template = (J.VariableDeclarations) original.getParameters().getParameters().getFirst();
        List<JRightPadded<J>> parameters = new ArrayList<>();
        for (String name : List.of(message, context, chain)) {
            parameters.add(JRightPadded.build(parameter(template, name, parameters.isEmpty())));
        }
        return original.withParameters(original.getParameters()
                                               .withParenthesized(true)
                                               .getPadding()
                                               .withParameters(parameters))
                       .withArrow(Space.SINGLE_SPACE)
                       .withBody(proceed.withPrefix(Space.SINGLE_SPACE));
    }

    private static J parameter(J.VariableDeclarations template, String name, boolean first) {
        J.VariableDeclarations.NamedVariable variable = template.getVariables().getFirst();
        J.VariableDeclarations.NamedVariable renamed = variable
                .withName(identifier(name, Space.EMPTY))
                .withVariableType(null)
                .withPrefix(Space.EMPTY);
        return template.withId(Tree.randomId())
                       .withLeadingAnnotations(Collections.emptyList())
                       .withModifiers(Collections.emptyList())
                       .withTypeExpression(null)
                       .withVariables(List.of(renamed))
                       .withPrefix(first ? Space.EMPTY : Space.SINGLE_SPACE);
    }

    private static J.Identifier identifier(String name, Space prefix) {
        return new J.Identifier(UUID.randomUUID(), prefix, Markers.EMPTY, Collections.emptyList(), name, null, null);
    }

    private static String available(String base, Set<String> taken) {
        String candidate = base;
        for (int suffix = 1; taken.contains(candidate); suffix++) {
            candidate = base + suffix;
        }
        return candidate;
    }

    /**
     * Renames the variable references named {@code from} in the given {@code expression}, keeping their types so the
     * method renames of the later Axon Framework 5 recipes still match the calls made on them.
     */
    private static Expression rename(Expression expression, @Nullable String from, String to) {
        if (from == null || from.equals(to)) {
            return expression;
        }
        return (Expression) new JavaIsoVisitor<Integer>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
                if (!from.equals(identifier.getSimpleName()) || !isVariableReference(getCursor())) {
                    return identifier;
                }
                JavaType.Variable fieldType = identifier.getFieldType();
                return identifier.withSimpleName(to).withFieldType(fieldType == null ? null : fieldType.withName(to));
            }
        }.visitNonNull(expression, 0);
    }

    /**
     * The names an expression uses or declares as variables, leaving out method names and the member names of field
     * accesses.
     */
    private static Set<String> referencedNames(J tree) {
        Set<String> names = new HashSet<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
                if (isVariableReference(getCursor())) {
                    names.add(identifier.getSimpleName());
                }
                return identifier;
            }
        }.visit(tree, 0);
        return names;
    }

    private static boolean isVariableReference(Cursor identifierCursor) {
        Object parent = identifierCursor.getParentTreeCursor().getValue();
        J.Identifier identifier = identifierCursor.getValue();
        if (parent instanceof J.MethodInvocation invocation && invocation.getName() == identifier) {
            return false;
        }
        return !(parent instanceof J.FieldAccess fieldAccess && fieldAccess.getName() == identifier);
    }

    /**
     * The local variable and parameter names visible where the interceptor is registered: the locals of each enclosing
     * block, the variables of enclosing loops, catch clauses and try resources, the parameters of enclosing lambdas,
     * and the parameters of the enclosing method. A new lambda parameter may not redeclare any of them; fields may be
     * shadowed, so the walk stops at the enclosing method or class.
     */
    private static Set<String> namesInScope(Cursor invocationCursor) {
        Set<String> names = new HashSet<>();
        for (Cursor cursor = invocationCursor.getParentTreeCursor();
             !(cursor.getValue() instanceof SourceFile);
             cursor = cursor.getParentTreeCursor()) {
            Object value = cursor.getValue();
            if (value instanceof J.ClassDeclaration || value instanceof J.NewClass) {
                break;
            }
            if (value instanceof J.MethodDeclaration method) {
                method.getParameters().forEach(parameter -> addDeclaredNames(parameter, names));
                break;
            }
            if (value instanceof J.Block block) {
                block.getStatements().forEach(statement -> addDeclaredNames(statement, names));
            } else if (value instanceof J.Lambda lambda) {
                names.addAll(parameterNames(lambda));
            } else if (value instanceof J.ForLoop loop) {
                loop.getControl().getInit().forEach(init -> addDeclaredNames(init, names));
            } else if (value instanceof J.ForEachLoop loop) {
                addDeclaredNames(loop.getControl().getVariable(), names);
            } else if (value instanceof J.Try.Catch caught) {
                addDeclaredNames(caught.getParameter().getTree(), names);
            } else if (value instanceof J.Try tried && tried.getResources() != null) {
                tried.getResources().forEach(resource -> addDeclaredNames(resource.getVariableDeclarations(), names));
            }
        }
        return names;
    }

    private static void addDeclaredNames(J tree, Set<String> names) {
        if (tree instanceof J.VariableDeclarations declarations) {
            declarations.getVariables().forEach(variable -> names.add(variable.getSimpleName()));
        }
    }

    private static List<String> parameterNames(J.Lambda lambda) {
        return lambda.getParameters().getParameters().stream()
                     .filter(J.VariableDeclarations.class::isInstance)
                     .map(J.VariableDeclarations.class::cast)
                     .flatMap(declarations -> declarations.getVariables().stream())
                     .map(J.VariableDeclarations.NamedVariable::getSimpleName)
                     .toList();
    }
}
