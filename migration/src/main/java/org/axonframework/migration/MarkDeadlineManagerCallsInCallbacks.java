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
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Leaves a {@code // TODO(axon4to5):} marker on the Saga statements that use a {@code DeadlineManager} inside an
 * asynchronous callback, which no longer works on the Axoniq Framework legacy module.
 * <p>
 * Axon Framework 4 offered one global {@code DeadlineManager}, so a Saga could schedule or cancel a deadline from any
 * thread, for example once a command result arrived. The legacy module binds the {@code DeadlineManager} a Saga handler
 * receives to that handler's processing context, and runs its calls when that context commits. A call from a callback
 * that runs after the handler returned throws an {@link IllegalStateException}, which a {@code whenComplete(..)}
 * callback or a Reactor subscriber drops, so the deadline is silently never scheduled or cancelled.
 * <p>
 * In a Saga, being a class with the Spring {@code @Saga} stereotype or a {@code @SagaEventHandler} method, the recipe
 * marks each statement that:
 * <ul>
 *     <li>passes a lambda, method reference or anonymous class that uses the {@code DeadlineManager} to a method of
 *     {@link java.util.concurrent.CompletionStage}, such as {@code whenComplete(..)}, or of a Reactor {@code Mono} or
 *     {@code Flux};</li>
 *     <li>ends a Reactor chain in {@code subscribe(..)} and passes the {@code DeadlineManager} on to another call in
 *     the arguments of one of the chain's operators, such as {@code flatMap(helper(deadlineManager))}.</li>
 * </ul>
 * Using the {@code DeadlineManager} means calling one of its methods, or passing it on to another method or
 * constructor. The fix, waiting for the result inside the handler before calling the {@code DeadlineManager}, depends
 * on the application, so the code is kept as is. The marker goes on the enclosing statement, so it lands above the
 * chain holding the callback. Runs on the Axon Framework 4 {@code DeadlineManager} type, which the legacy module keeps
 * in its package. Java sources only. Idempotent.
 *
 * @author Stefan Dragisic
 * @since 5.4.1
 */
public class MarkDeadlineManagerCallsInCallbacks extends Recipe {

    private static final String DEADLINE_MANAGER_FQN = "org.axonframework.deadline.DeadlineManager";
    private static final String DEADLINE_MANAGER_NAME = "DeadlineManager";
    private static final String COMPLETION_STAGE_FQN = "java.util.concurrent.CompletionStage";
    private static final String MONO_FQN = "reactor.core.publisher.Mono";
    private static final String FLUX_FQN = "reactor.core.publisher.Flux";
    private static final String SAGA_MESSAGE = "axon.markDeadlineManagerCallsInCallbacks.saga";

    private static final StatementTodoMarker.Todo CALLBACK_TODO = new StatementTodoMarker.Todo(
            "TODO(axon4to5): use the DeadlineManager in the handler, not in a callback",
            " TODO(axon4to5): use the DeadlineManager in the handler, not in a callback. The DeadlineManager"
                    + " parameter is bound to the handler: a call from a callback that runs after the handler"
                    + " returned fails, and the deadline is never scheduled or cancelled. Wait for the result in the"
                    + " handler, for example with .orTimeout(..).join() or .block(..), and call the DeadlineManager"
                    + " there."
    );

    @Override
    public String getDisplayName() {
        return "Mark DeadlineManager calls in Saga callbacks";
    }

    @Override
    public String getDescription() {
        return "Leaves a `// TODO(axon4to5):` marker, keeping the code in place, on Saga statements that use the "
                + "`DeadlineManager` in an asynchronous callback: a lambda, method reference or anonymous class "
                + "passed to a `CompletionStage`, `Mono` or `Flux` method, or an operator argument of a Reactor "
                + "chain ending in `subscribe(..)`. The legacy module binds the `DeadlineManager` to the handler, so "
                + "a call that runs after the handler returned fails and the deadline is lost. Java sources only. "
                + "Idempotent.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public boolean isAcceptable(SourceFile sourceFile, ExecutionContext ctx) {
                return sourceFile instanceof J.CompilationUnit;
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDeclaration,
                                                             ExecutionContext ctx) {
                getCursor().putMessage(SAGA_MESSAGE, SagaClasses.declaresSaga(classDeclaration));
                return super.visitClassDeclaration(classDeclaration, ctx);
            }

            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block visited = super.visitBlock(block, ctx);
                J.MethodDeclaration method = getCursor().firstEnclosing(J.MethodDeclaration.class);
                if (method == null || !getCursor().getNearestMessage(SAGA_MESSAGE, false)) {
                    return visited;
                }
                Set<String> parameters = deadlineManagerParameters(method);
                return StatementTodoMarker.markStatements(visited, invocation -> todoFor(invocation, parameters));
            }
        };
    }

    private static StatementTodoMarker.@Nullable Todo todoFor(J.MethodInvocation invocation, Set<String> parameters) {
        if (!isAsync(invocation)) {
            return null;
        }
        for (Expression argument : invocation.getArguments()) {
            if (isCallback(argument) && usesDeadlineManager(argument, parameters, true)) {
                return CALLBACK_TODO;
            }
        }
        return isReactor(invocation) && "subscribe".equals(invocation.getSimpleName())
                && passesDeadlineManagerToOperator(invocation, parameters)
                ? CALLBACK_TODO
                : null;
    }

    /**
     * Whether an operator of the Reactor chain ending in the given {@code subscribe(..)} call passes the
     * {@code DeadlineManager} on in its arguments. Such an argument, typically a helper returning the function the
     * operator applies, may hand the {@code DeadlineManager} to code that runs once the chain emits. A direct
     * {@code DeadlineManager} call in an argument runs while the chain is assembled, inside the handler, so it is fine.
     */
    private static boolean passesDeadlineManagerToOperator(J.MethodInvocation subscribe, Set<String> parameters) {
        for (Expression link = subscribe; link instanceof J.MethodInvocation operator; link = operator.getSelect()) {
            if (isReactor(operator) && operator.getArguments().stream()
                                               .anyMatch(argument -> usesDeadlineManager(argument, parameters,
                                                                                         false))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCallback(Expression argument) {
        return argument instanceof J.Lambda
                || argument instanceof J.MemberReference
                || (argument instanceof J.NewClass newClass && newClass.getBody() != null);
    }

    private static boolean isAsync(J.MethodInvocation invocation) {
        return isDeclaredOn(invocation, COMPLETION_STAGE_FQN) || isReactor(invocation);
    }

    private static boolean isReactor(J.MethodInvocation invocation) {
        return isDeclaredOn(invocation, MONO_FQN) || isDeclaredOn(invocation, FLUX_FQN);
    }

    /**
     * Whether the invocation is a method of the given type or one of its subtypes, judged by the type declaring the
     * method, or by the type of its target when the method itself could not be resolved.
     */
    private static boolean isDeclaredOn(J.MethodInvocation invocation, String typeFqn) {
        JavaType.Method methodType = invocation.getMethodType();
        if (methodType != null) {
            return TypeUtils.isAssignableTo(typeFqn, methodType.getDeclaringType());
        }
        Expression select = invocation.getSelect();
        return select != null && TypeUtils.isAssignableTo(typeFqn, select.getType());
    }

    /**
     * Whether the tree passes the {@code DeadlineManager} on to a method or constructor, or, when {@code calls} is set,
     * also calls one of its methods or refers to one of them.
     */
    private static boolean usesDeadlineManager(Tree tree, Set<String> parameters, boolean calls) {
        boolean[] found = {false};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation, Integer p) {
                if ((calls && isDeadlineManagerCall(invocation, parameters))
                        || passesOn(invocation.getArguments(), parameters)) {
                    found[0] = true;
                    return invocation;
                }
                return super.visitMethodInvocation(invocation, p);
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, Integer p) {
                if (passesOn(newClass.getArguments(), parameters)) {
                    found[0] = true;
                    return newClass;
                }
                return super.visitNewClass(newClass, p);
            }

            @Override
            public J.MemberReference visitMemberReference(J.MemberReference reference, Integer p) {
                if (calls && isDeadlineManager(reference.getContaining(), parameters)) {
                    found[0] = true;
                    return reference;
                }
                return super.visitMemberReference(reference, p);
            }
        }.visit(tree, 0);
        return found[0];
    }

    private static boolean isDeadlineManagerCall(J.MethodInvocation invocation, Set<String> parameters) {
        JavaType.Method methodType = invocation.getMethodType();
        if (methodType != null) {
            return TypeUtils.isAssignableTo(DEADLINE_MANAGER_FQN, methodType.getDeclaringType());
        }
        return invocation.getSelect() != null && isDeadlineManager(invocation.getSelect(), parameters);
    }

    private static boolean passesOn(List<Expression> arguments, Set<String> parameters) {
        return arguments.stream().anyMatch(argument -> isDeadlineManager(argument, parameters));
    }

    /**
     * Whether the expression refers to a {@code DeadlineManager} variable. An identifier without a resolved type is
     * matched by name against the {@code DeadlineManager} parameters of the enclosing method, which covers the
     * arguments {@link MoveSagaFieldToHandlerParameter} adds when it passes the parameter on to helper methods.
     */
    private static boolean isDeadlineManager(Expression expression, Set<String> parameters) {
        if (!(expression instanceof J.Identifier) && !(expression instanceof J.FieldAccess)) {
            return false;
        }
        JavaType type = expression.getType();
        if (type != null && !(type instanceof JavaType.Unknown)) {
            return TypeUtils.isAssignableTo(DEADLINE_MANAGER_FQN, type);
        }
        return expression instanceof J.Identifier identifier && parameters.contains(identifier.getSimpleName());
    }

    /**
     * Returns the names of the method's {@code DeadlineManager} parameters, matched by simple type name only when the
     * parameter type could not be resolved.
     */
    private static Set<String> deadlineManagerParameters(J.MethodDeclaration method) {
        Set<String> names = new HashSet<>();
        for (Statement parameter : method.getParameters()) {
            if (parameter instanceof J.VariableDeclarations declaration && isDeadlineManagerType(declaration)) {
                declaration.getVariables().forEach(variable -> names.add(variable.getSimpleName()));
            }
        }
        return names;
    }

    private static boolean isDeadlineManagerType(J.VariableDeclarations declaration) {
        TypeTree typeExpression = declaration.getTypeExpression();
        JavaType type = declaration.getType();
        if (type != null && !(type instanceof JavaType.Unknown)) {
            return TypeUtils.isAssignableTo(DEADLINE_MANAGER_FQN, type);
        }
        String simpleName = typeExpression instanceof J.Identifier identifier
                ? identifier.getSimpleName()
                : typeExpression instanceof J.FieldAccess fieldAccess ? fieldAccess.getSimpleName() : null;
        return DEADLINE_MANAGER_NAME.equals(simpleName);
    }
}
