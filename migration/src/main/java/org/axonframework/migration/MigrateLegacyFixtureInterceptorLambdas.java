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
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
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
 * These shapes are rewritten, each to {@code (message, context, chain) -> chain.proceed(..., context)}:
 * <ul>
 *     <li>{@code messages -> (i, m) -> expression}, passing the expression with {@code m} renamed;</li>
 *     <li>{@code messages -> (i, m) -> { statements; return expression; }}, keeping the statements and passing the
 *     returned expression, with {@code m} renamed, when the block has no other {@code return};</li>
 *     <li>{@code (unitOfWork, chain) -> chain.proceed()}, passing the message unchanged;</li>
 *     <li>{@code (unitOfWork, chain) -> { statements; return chain.proceed(); }}, keeping the statements and passing
 *     the message unchanged, when the statements use the unit of work only to read {@code unitOfWork.getMessage()},
 *     which becomes the message, and neither use the chain nor {@code return};</li>
 *     <li>{@code (unitOfWork, chain) -> { unitOfWork.transformMessage(m -> expression); return chain.proceed(); }},
 *     passing the expression with {@code m} renamed.</li>
 * </ul>
 * Any other interceptor written in place (a lambda of another shape, one that still uses the batch, index, unit of
 * work or chain otherwise, a method reference, or an anonymous class) gets a {@code // TODO(axon4to5):} marker on its
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
                + "chain.proceed(message, context)` shape. Covers `messages -> (i, m) -> expression`, also with a "
                + "block body ending in `return expression`, `(unitOfWork, chain) -> chain.proceed()`, also after "
                + "statements that only read `unitOfWork.getMessage()`, and a `unitOfWork.transformMessage(..)` "
                + "followed by `chain.proceed()`. Leaves a `// TODO(axon4to5):` marker on any other interceptor written in place. "
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
     * Rewrites {@code messages -> (i, m) -> expression}, or the same with a block body ending in
     * {@code return expression;}.
     */
    private static J.@Nullable Lambda rewriteDispatch(J.Lambda outer, Set<String> namesInScope) {
        List<String> outerParameters = parameterNames(outer);
        if (outerParameters.size() != 1 || !(outer.getBody() instanceof J.Lambda inner)) {
            return null;
        }
        List<String> innerParameters = parameterNames(inner);
        if (innerParameters.size() != 2) {
            return null;
        }
        Set<String> references = referencedNames(inner.getBody());
        if (references.contains(outerParameters.getFirst()) || references.contains(innerParameters.getFirst())) {
            // The body depends on the batch or the index, which Axon Framework 5 no longer hands over.
            return null;
        }
        String messageParameter = innerParameters.get(1);
        Expression expression = returnedExpression(inner.getBody());
        if (expression != null) {
            return interceptorLambda(outer, null, expression, messageParameter, null, namesInScope);
        }
        if (!(inner.getBody() instanceof J.Block block) || !endsWithOnlyReturn(block)) {
            return null;
        }
        List<Statement> statements = block.getStatements();
        Expression returned = ((J.Return) statements.getLast()).getExpression();
        if (returned == null) {
            return null;
        }
        return interceptorLambda(outer, block.withStatements(statements.subList(0, statements.size() - 1)),
                                 returned, messageParameter, null, namesInScope);
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
            return interceptorLambda(lambda, null, null, null, null, namesInScope);
        }
        J.Lambda readingMessage = rewriteHandlerReadingMessage(lambda, unitOfWork, chain, namesInScope);
        if (readingMessage != null) {
            return readingMessage;
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
        return interceptorLambda(lambda, null, expression, transformerParameters.getFirst(), null, namesInScope);
    }

    /**
     * Rewrites {@code (unitOfWork, chain) -> { statements; return chain.proceed(); }} when the statements only read
     * the message through {@code unitOfWork.getMessage()}, which the Axon Framework 5 interceptor receives directly.
     */
    private static J.@Nullable Lambda rewriteHandlerReadingMessage(J.Lambda lambda,
                                                                  String unitOfWork,
                                                                  String chain,
                                                                  Set<String> namesInScope) {
        if (!(lambda.getBody() instanceof J.Block block) || block.getStatements().size() < 2
                || !endsWithOnlyReturn(block)
                || !isProceed(((J.Return) block.getStatements().getLast()).getExpression(), chain)) {
            return null;
        }
        J.Block leading = block.withStatements(block.getStatements().subList(0, block.getStatements().size() - 1));
        Set<String> references = referencedNames(leading);
        if (references.contains(chain)) {
            return null;
        }
        int[] messageReads = {0};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation, Integer p) {
                if (isMessageRead(invocation, unitOfWork)) {
                    messageReads[0]++;
                    return invocation;
                }
                return super.visitMethodInvocation(invocation, p);
            }
        }.visit(leading, 0);
        if (countReferences(leading, unitOfWork) != messageReads[0]) {
            // The unit of work is used for more than reading the message, which Axon Framework 5 has no equivalent for.
            return null;
        }
        return interceptorLambda(lambda, leading, null, null, unitOfWork, namesInScope);
    }

    private static boolean isMessageRead(J.MethodInvocation invocation, String unitOfWork) {
        return "getMessage".equals(invocation.getSimpleName())
                && isIdentifier(invocation.getSelect(), unitOfWork)
                && invocation.getArguments().stream().allMatch(J.Empty.class::isInstance);
    }

    /**
     * Whether the block ends with a {@code return} and has no other {@code return} outside nested lambdas and classes,
     * so its statements always run up to that last one.
     */
    private static boolean endsWithOnlyReturn(J.Block block) {
        if (block.getStatements().isEmpty() || !(block.getStatements().getLast() instanceof J.Return)) {
            return false;
        }
        int[] returns = {0};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Return visitReturn(J.Return returned, Integer p) {
                returns[0]++;
                return super.visitReturn(returned, p);
            }

            @Override
            public J.Lambda visitLambda(J.Lambda nested, Integer p) {
                return nested;
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, Integer p) {
                return newClass;
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDeclaration, Integer p) {
                return classDeclaration;
            }
        }.visit(block, 0);
        return returns[0] == 1;
    }

    private static int countReferences(J tree, String name) {
        int[] count = {0};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
                if (name.equals(identifier.getSimpleName()) && isVariableReference(getCursor())) {
                    count[0]++;
                }
                return identifier;
            }
        }.visit(tree, 0);
        return count[0];
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
     * itself when there is no expression. With {@code leading} statements, the body becomes a block running them
     * first and returning that call; there, {@code messageParameter} is renamed too, and the
     * {@code unitOfWork.getMessage()} reads become the message.
     */
    private static J.Lambda interceptorLambda(J.Lambda original,
                                              J.@Nullable Block leading,
                                              @Nullable Expression expression,
                                              @Nullable String messageParameter,
                                              @Nullable String unitOfWork,
                                              Set<String> namesInScope) {
        Set<String> taken = new HashSet<>(namesInScope);
        if (expression != null) {
            taken.addAll(referencedNames(expression));
        }
        if (leading != null) {
            taken.addAll(referencedNames(leading));
        }
        taken.remove(messageParameter);
        taken.remove(unitOfWork);
        String message = available("message", taken);
        taken.add(message);
        String context = available("context", taken);
        taken.add(context);
        String chain = available("chain", taken);

        Expression passed = expression == null
                ? identifier(message, Space.EMPTY)
                : (Expression) rename(expression, messageParameter, message).withPrefix(Space.EMPTY);
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
        J body = proceed.withPrefix(Space.SINGLE_SPACE);
        if (leading != null) {
            J.Block statements = (J.Block) rename(readMessageDirectly(leading, unitOfWork, message),
                                                  messageParameter, message);
            J.Return returned = new J.Return(Tree.randomId(),
                                             returnPrefix(statements),
                                             Markers.EMPTY,
                                             proceed.withPrefix(Space.SINGLE_SPACE));
            List<Statement> withReturn = new ArrayList<>(statements.getStatements());
            withReturn.add(returned);
            body = statements.withStatements(withReturn).withPrefix(Space.SINGLE_SPACE);
        }
        return original.withParameters(original.getParameters()
                                               .withParenthesized(true)
                                               .getPadding()
                                               .withParameters(parameters))
                       .withArrow(Space.SINGLE_SPACE)
                       .withBody(body);
    }

    /**
     * Replaces the {@code unitOfWork.getMessage()} reads in the given block with the message parameter, typed as the
     * read was so the later Axon Framework 5 recipes still match the calls made on it.
     */
    private static J.Block readMessageDirectly(J.Block block, @Nullable String unitOfWork, String message) {
        if (unitOfWork == null) {
            return block;
        }
        return (J.Block) new JavaVisitor<Integer>() {
            @Override
            public J visitMethodInvocation(J.MethodInvocation invocation, Integer p) {
                if (isMessageRead(invocation, unitOfWork)) {
                    return new J.Identifier(Tree.randomId(), invocation.getPrefix(), Markers.EMPTY,
                                            Collections.emptyList(), message, invocation.getType(), null);
                }
                return super.visitMethodInvocation(invocation, p);
            }
        }.visitNonNull(block, 0);
    }

    /**
     * The prefix of a statement appended to the given block: the prefix of its last statement, so it lines up.
     */
    private static Space returnPrefix(J.Block block) {
        return block.getStatements().isEmpty() ? Space.format("\n") : block.getStatements().getLast().getPrefix();
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
     * Renames the variable references named {@code from} in the given {@code tree}, keeping their types so the
     * method renames of the later Axon Framework 5 recipes still match the calls made on them.
     */
    private static J rename(J tree, @Nullable String from, String to) {
        if (from == null || from.equals(to)) {
            return tree;
        }
        return new JavaIsoVisitor<Integer>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
                if (!from.equals(identifier.getSimpleName()) || !isVariableReference(getCursor())) {
                    return identifier;
                }
                JavaType.Variable fieldType = identifier.getFieldType();
                return identifier.withSimpleName(to).withFieldType(fieldType == null ? null : fieldType.withName(to));
            }
        }.visitNonNull(tree, 0);
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
