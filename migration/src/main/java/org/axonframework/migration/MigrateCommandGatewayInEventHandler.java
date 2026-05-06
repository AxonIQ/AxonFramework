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
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Migrates an Axon Framework 4 event-handling component that dispatches commands via a class-level
 * {@code CommandGateway} field to the AF5 in-handler pattern: a method-parameter {@code CommandDispatcher}.
 * <p>
 * Decision rule from {@code CommandDispatcher}'s Javadoc — gateway for top-of-chain entry points (controllers,
 * runners), dispatcher inside another message handler. Inside an {@code @EventHandler} the active
 * {@code ProcessingContext} makes {@code CommandDispatcher} the right tool, and AF5's dispatcher is auto-injected
 * into handler methods by the {@code CommandDispatcherParameterResolverFactory}. This recipe automates that
 * mechanical part of the migration:
 * <ol>
 *   <li>Inside every {@code @EventHandler} method that calls {@code <field>.send(...)} or
 *       {@code <field>.sendAndWait(...)} on a class field of type {@code CommandGateway}, the call is rewritten
 *       to {@code commandDispatcher.send(...)} (the {@code AndWait} suffix is dropped — AF5's dispatcher only
 *       exposes the async {@code send} family).</li>
 *   <li>A {@code CommandDispatcher commandDispatcher} parameter is appended to the method signature when the
 *       method does not already declare one.</li>
 *   <li>For methods whose return type is {@code void} and whose body matches one of two supported shapes —
 *       a single dispatch expression statement, or a single try/catch where each branch ends in a dispatch
 *       expression statement — the return type is widened to {@code CompletableFuture<?>}, the dispatch
 *       expression is converted to {@code return ... .getResultMessage();}, and {@code java.util.concurrent.CompletableFuture}
 *       is imported. Methods with non-{@code void} return types (e.g. {@code Mono<?>}, custom types) are left
 *       alone — the call rewrite still applies, but the surrounding adaptation is left to the human.</li>
 *   <li>Once every {@code @EventHandler} in the class has been visited, the recipe checks whether the
 *       {@code CommandGateway} field is still referenced anywhere. If it is not, the field declaration, the
 *       corresponding constructor parameter, and any matching {@code this.field = field;} assignment are
 *       removed — and the now-unused {@code CommandGateway} import is dropped.</li>
 * </ol>
 * The recipe matches AF5 fully-qualified type names because it is intended to run after the {@code Axon4ToAxon5Messaging}
 * package renames in the same composite. Anything that does not match the supported shapes (loops, multi-dispatch
 * branches, conditional dispatch, reactive return types) is left untouched: the build will still surface those
 * call sites as compile errors so the human can address them.
 *
 * @author Axon Framework Team
 * @since 5.2.0
 */
public class MigrateCommandGatewayInEventHandler extends Recipe {

    private static final String EVENT_HANDLER_AF5_FQN = "org.axonframework.messaging.eventhandling.annotation.EventHandler";
    private static final String COMMAND_GATEWAY_AF5_FQN = "org.axonframework.messaging.commandhandling.gateway.CommandGateway";
    private static final String COMMAND_DISPATCHER_FQN = "org.axonframework.messaging.commandhandling.gateway.CommandDispatcher";
    private static final String COMPLETABLE_FUTURE_FQN = "java.util.concurrent.CompletableFuture";

    private static final String DISPATCHER_PARAM_NAME = "commandDispatcher";

    @Override
    public String getDisplayName() {
        return "Replace class-level CommandGateway with method-parameter CommandDispatcher in @EventHandler methods";
    }

    @Override
    public String getDescription() {
        return "Inside every `@EventHandler` method in the class, rewrites calls of the form "
                + "`commandGateway.send(...)` / `commandGateway.sendAndWait(...)` (where `commandGateway` is a "
                + "class-level `CommandGateway` field) to `commandDispatcher.send(...)` on a method-level "
                + "`CommandDispatcher` parameter — adding the parameter when missing. For `void` handlers whose "
                + "body is a single dispatch expression or a single try/catch with one dispatch per branch, the "
                + "return type is widened to `CompletableFuture<?>` and the dispatch is converted to "
                + "`return ... .getResultMessage();`. Once no other references to the gateway field remain, the "
                + "field, its constructor parameter, and the matching assignment are removed.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                String gatewayFieldName = findCommandGatewayField(classDecl);
                boolean hasGatewayWork = gatewayFieldName != null
                        && classHasEventHandlerCallingGateway(classDecl, gatewayFieldName);
                boolean hasDispatcherOnlyWork = classHasVoidEventHandlerWithDispatcher(classDecl);
                if (!hasGatewayWork && !hasDispatcherOnlyWork) {
                    return super.visitClassDeclaration(classDecl, ctx);
                }
                if (hasGatewayWork) {
                    getCursor().putMessage("axon.gatewayFieldName", gatewayFieldName);
                }

                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                // After visiting all methods, drop the now-orphan gateway field, ctor parameter, assignment.
                if (hasGatewayWork && !isFieldStillReferenced(cd, gatewayFieldName)) {
                    cd = removeGatewayField(cd, gatewayFieldName);
                    cd = removeGatewayFromConstructors(cd, gatewayFieldName);
                    maybeRemoveImport(COMMAND_GATEWAY_AF5_FQN);
                }
                return cd;
            }

            /**
             * Pre-flight check: only engage when at least one `@EventHandler` method in the class actually
             * dispatches through the gateway field. Otherwise this recipe must be a no-op so it does not
             * disturb classes that hold a {@code CommandGateway} for a non-handler reason (REST controllers,
             * runners, etc.).
             */
            private boolean classHasEventHandlerCallingGateway(J.ClassDeclaration cd, String gatewayName) {
                for (Statement s : cd.getBody().getStatements()) {
                    if (!(s instanceof J.MethodDeclaration)) {
                        continue;
                    }
                    J.MethodDeclaration m = (J.MethodDeclaration) s;
                    if (!isAnnotatedAsEventHandler(m) || m.getBody() == null) {
                        continue;
                    }
                    boolean[] found = {false};
                    new JavaIsoVisitor<ExecutionContext>() {
                        @Override
                        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi,
                                                                        ExecutionContext c) {
                            if (isGatewayCall(mi, gatewayName)
                                    && ("send".equals(mi.getSimpleName())
                                            || "sendAndWait".equals(mi.getSimpleName()))) {
                                found[0] = true;
                            }
                            return super.visitMethodInvocation(mi, c);
                        }
                    }.visit(m.getBody(), new org.openrewrite.InMemoryExecutionContext());
                    if (found[0]) {
                        return true;
                    }
                }
                return false;
            }

            /**
             * Detects the second supported migration shape: a `@EventHandler` method that already declares a
             * {@code CommandDispatcher} method-parameter (typically because a per-class skill ran first), but
             * still returns {@code void} — so the dispatcher result is silently discarded. This recipe should
             * widen the return type to {@code CompletableFuture<?>} and surface the dispatch result.
             */
            private boolean classHasVoidEventHandlerWithDispatcher(J.ClassDeclaration cd) {
                for (Statement s : cd.getBody().getStatements()) {
                    if (!(s instanceof J.MethodDeclaration)) {
                        continue;
                    }
                    J.MethodDeclaration m = (J.MethodDeclaration) s;
                    if (!isAnnotatedAsEventHandler(m) || m.getBody() == null) {
                        continue;
                    }
                    String dispatcherParam = existingDispatcherParameterName(m);
                    if (dispatcherParam == null || !isVoidReturn(m)) {
                        continue;
                    }
                    if (methodHasDispatchCall(m, dispatcherParam)) {
                        return true;
                    }
                }
                return false;
            }

            private boolean methodHasDispatchCall(J.MethodDeclaration md, String dispatcherName) {
                boolean[] found = {false};
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi,
                                                                    ExecutionContext c) {
                        if ("send".equals(mi.getSimpleName())
                                && mi.getSelect() instanceof J.Identifier
                                && ((J.Identifier) mi.getSelect()).getSimpleName().equals(dispatcherName)) {
                            found[0] = true;
                        }
                        return super.visitMethodInvocation(mi, c);
                    }
                }.visit(md.getBody(), new org.openrewrite.InMemoryExecutionContext());
                return found[0];
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                if (!isAnnotatedAsEventHandler(method)) {
                    return super.visitMethodDeclaration(method, ctx);
                }
                String gatewayFieldName = getCursor().getNearestMessage("axon.gatewayFieldName");
                String dispatcherParamName = existingDispatcherParameterName(method);

                boolean callsGateway = gatewayFieldName != null
                        && methodReferencesIdentifier(method, gatewayFieldName);
                boolean dispatcherOnlyVoid = !callsGateway
                        && dispatcherParamName != null
                        && isVoidReturn(method)
                        && methodHasDispatchCall(method, dispatcherParamName);
                if (!callsGateway && !dispatcherOnlyVoid) {
                    return super.visitMethodDeclaration(method, ctx);
                }

                final String paramName = dispatcherParamName != null ? dispatcherParamName : DISPATCHER_PARAM_NAME;

                J.MethodDeclaration md = method;
                if (callsGateway && dispatcherParamName == null) {
                    md = addCommandDispatcherParameter(md, ctx);
                }

                if (callsGateway) {
                    // Rewrite the call sites: <gatewayField>.send / sendAndWait(...) → commandDispatcher.send(...)
                    final String gatewayName = gatewayFieldName;
                    md = (J.MethodDeclaration) new JavaIsoVisitor<ExecutionContext>() {
                        @Override
                        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, ExecutionContext c) {
                            J.MethodInvocation invocation = super.visitMethodInvocation(mi, c);
                            if (!isGatewayCall(invocation, gatewayName)) {
                                return invocation;
                            }
                            String name = invocation.getSimpleName();
                            if (!"send".equals(name) && !"sendAndWait".equals(name)) {
                                return invocation;
                            }
                            return invocation
                                    .withSelect(
                                            new J.Identifier(
                                                    org.openrewrite.Tree.randomId(),
                                                    invocation.getSelect() == null
                                                            ? org.openrewrite.java.tree.Space.EMPTY
                                                            : invocation.getSelect().getPrefix(),
                                                    org.openrewrite.marker.Markers.EMPTY,
                                                    java.util.Collections.emptyList(),
                                                    paramName,
                                                    null,
                                                    null
                                            )
                                    )
                                    .withName(invocation.getName().withSimpleName("send"));
                        }
                    }.visitNonNull(md, ctx, getCursor().getParentOrThrow());
                }

                // For void handlers with a supported body shape, widen return type and convert dispatch to a return.
                if (isVoidReturn(md) && bodyShapeIsConvertible(md, paramName)) {
                    md = changeReturnTypeToCompletableFuture(md, ctx);
                    md = wrapDispatchesInReturn(md, paramName, ctx);
                    maybeAddImport(COMPLETABLE_FUTURE_FQN, false);
                }

                if (callsGateway) {
                    maybeAddImport(COMMAND_DISPATCHER_FQN, false);
                }
                return md;
            }

            // ── Detection helpers ────────────────────────────────────────────────

            private String findCommandGatewayField(J.ClassDeclaration cd) {
                for (Statement s : cd.getBody().getStatements()) {
                    if (!(s instanceof J.VariableDeclarations)) {
                        continue;
                    }
                    J.VariableDeclarations vd = (J.VariableDeclarations) s;
                    if (vd.getTypeExpression() == null || vd.getVariables().isEmpty()) {
                        continue;
                    }
                    JavaType.FullyQualified type = TypeUtils.asFullyQualified(vd.getTypeExpression().getType());
                    if (type != null && COMMAND_GATEWAY_AF5_FQN.equals(type.getFullyQualifiedName())) {
                        return vd.getVariables().get(0).getSimpleName();
                    }
                    // Fallback: type binding is missing (typical on test inputs without classpath)
                    // — fall back to the simple type name. Both `CommandGateway` and the AF5 FQN form
                    // are accepted.
                    String typeText = vd.getTypeExpression().toString();
                    if ("CommandGateway".equals(typeText) || COMMAND_GATEWAY_AF5_FQN.equals(typeText)) {
                        return vd.getVariables().get(0).getSimpleName();
                    }
                }
                return null;
            }

            private boolean isAnnotatedAsEventHandler(J.MethodDeclaration md) {
                for (J.Annotation a : md.getLeadingAnnotations()) {
                    JavaType.FullyQualified t = TypeUtils.asFullyQualified(a.getType());
                    if (t != null && EVENT_HANDLER_AF5_FQN.equals(t.getFullyQualifiedName())) {
                        return true;
                    }
                    // Fallback by simple name — type binding can be missing in tests / mid-rename cycles.
                    if (a.getSimpleName().equals("EventHandler")) {
                        return true;
                    }
                }
                return false;
            }

            private boolean methodReferencesIdentifier(J.MethodDeclaration md, String name) {
                if (md.getBody() == null) {
                    return false;
                }
                boolean[] found = {false};
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.Identifier visitIdentifier(J.Identifier id, ExecutionContext c) {
                        if (id.getSimpleName().equals(name)) {
                            found[0] = true;
                        }
                        return id;
                    }
                }.visit(md.getBody(), new org.openrewrite.InMemoryExecutionContext());
                return found[0];
            }

            private boolean isGatewayCall(J.MethodInvocation mi, String gatewayName) {
                Expression select = mi.getSelect();
                if (select instanceof J.Identifier) {
                    return ((J.Identifier) select).getSimpleName().equals(gatewayName);
                }
                if (select instanceof J.FieldAccess) {
                    J.FieldAccess fa = (J.FieldAccess) select;
                    return fa.getName().getSimpleName().equals(gatewayName)
                            && fa.getTarget() instanceof J.Identifier
                            && ((J.Identifier) fa.getTarget()).getSimpleName().equals("this");
                }
                return false;
            }

            private String existingDispatcherParameterName(J.MethodDeclaration md) {
                for (Statement p : md.getParameters()) {
                    if (!(p instanceof J.VariableDeclarations)) {
                        continue;
                    }
                    J.VariableDeclarations vd = (J.VariableDeclarations) p;
                    if (vd.getTypeExpression() == null) {
                        continue;
                    }
                    JavaType.FullyQualified t = TypeUtils.asFullyQualified(vd.getTypeExpression().getType());
                    if (t != null && COMMAND_DISPATCHER_FQN.equals(t.getFullyQualifiedName())
                            && !vd.getVariables().isEmpty()) {
                        return vd.getVariables().get(0).getSimpleName();
                    }
                    // Fallback by simple-name — type binding can be missing on test inputs.
                    if (vd.getTypeExpression().toString().equals("CommandDispatcher")
                            && !vd.getVariables().isEmpty()) {
                        return vd.getVariables().get(0).getSimpleName();
                    }
                }
                return null;
            }

            // ── Method-level transformations ─────────────────────────────────────

            private J.MethodDeclaration addCommandDispatcherParameter(J.MethodDeclaration md, ExecutionContext ctx) {
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
                    template.append(", CommandDispatcher ").append(DISPATCHER_PARAM_NAME);
                } else {
                    template.append("CommandDispatcher ").append(DISPATCHER_PARAM_NAME);
                }
                return JavaTemplate.builder(template.toString())
                        .imports(COMMAND_DISPATCHER_FQN)
                        .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                        .build()
                        .apply(getCursor(), md.getCoordinates().replaceParameters(), templateArgs.toArray());
            }

            private boolean isVoidReturn(J.MethodDeclaration md) {
                if (md.getReturnTypeExpression() == null) {
                    return false;
                }
                JavaType type = md.getReturnTypeExpression().getType();
                if (type instanceof JavaType.Primitive
                        && ((JavaType.Primitive) type).equals(JavaType.Primitive.Void)) {
                    return true;
                }
                // Fallback by simple name — covers test inputs where types aren't fully bound.
                return "void".equals(md.getReturnTypeExpression().toString());
            }

            /**
             * Returns true when the method body is one of the supported shapes for return-wrap conversion:
             * (a) the last statement is a dispatch expression-statement, or
             * (b) the body is a single try-statement whose try-block ends in a dispatch expression-statement,
             *     and every catch-block also ends in one, or
             * (c) the last statement is a guard {@code if (cond) { ...dispatch }} with no else (or empty else),
             *     where the then-block ends in a dispatch — converted to {@code return}-the-dispatch plus a
             *     trailing {@code return CompletableFuture.completedFuture(null);}.
             */
            private boolean bodyShapeIsConvertible(J.MethodDeclaration md, String dispatcherName) {
                if (md.getBody() == null) {
                    return false;
                }
                List<Statement> stmts = md.getBody().getStatements();
                if (stmts.isEmpty()) {
                    return false;
                }
                if (stmts.size() == 1 && stmts.get(0) instanceof J.Try) {
                    return tryShapeIsConvertible((J.Try) stmts.get(0), dispatcherName);
                }
                Statement last = stmts.get(stmts.size() - 1);
                if (isDispatchExpressionStatement(last, dispatcherName)) {
                    return true;
                }
                if (last instanceof J.If) {
                    return ifShapeIsConvertible((J.If) last, dispatcherName);
                }
                return false;
            }

            private boolean ifShapeIsConvertible(J.If ifStmt, String dispatcherName) {
                if (ifStmt.getElsePart() != null) {
                    Statement elseBody = ifStmt.getElsePart().getBody();
                    if (elseBody instanceof J.Block && !((J.Block) elseBody).getStatements().isEmpty()) {
                        return false;
                    }
                    if (!(elseBody instanceof J.Block)) {
                        return false;
                    }
                }
                Statement thenPart = ifStmt.getThenPart();
                if (!(thenPart instanceof J.Block)) {
                    return false;
                }
                List<Statement> thenStmts = ((J.Block) thenPart).getStatements();
                return !thenStmts.isEmpty()
                        && isDispatchExpressionStatement(thenStmts.get(thenStmts.size() - 1), dispatcherName);
            }

            private boolean tryShapeIsConvertible(J.Try tryStmt, String dispatcherName) {
                List<Statement> tryStmts = tryStmt.getBody().getStatements();
                if (tryStmts.isEmpty()
                        || !isDispatchExpressionStatement(tryStmts.get(tryStmts.size() - 1), dispatcherName)) {
                    return false;
                }
                if (tryStmt.getCatches() == null || tryStmt.getCatches().isEmpty()) {
                    return false;
                }
                for (J.Try.Catch c : tryStmt.getCatches()) {
                    List<Statement> cs = c.getBody().getStatements();
                    if (cs.isEmpty()
                            || !isDispatchExpressionStatement(cs.get(cs.size() - 1), dispatcherName)) {
                        return false;
                    }
                }
                return true;
            }

            private boolean isDispatchExpressionStatement(Statement s, String dispatcherName) {
                if (!(s instanceof J.MethodInvocation)) {
                    return false;
                }
                J.MethodInvocation mi = (J.MethodInvocation) s;
                if (!"send".equals(mi.getSimpleName())) {
                    return false;
                }
                Expression select = mi.getSelect();
                return select instanceof J.Identifier
                        && ((J.Identifier) select).getSimpleName().equals(dispatcherName);
            }

            private J.MethodDeclaration changeReturnTypeToCompletableFuture(J.MethodDeclaration md, ExecutionContext ctx) {
                if (md.getReturnTypeExpression() == null) {
                    return md;
                }
                // CoordinateBuilder.MethodDeclaration has no replaceReturnType() — go through the expression
                // node's own coordinates instead.
                // JavaTemplate parses an isolated `CompletableFuture<?>` snippet as an expression
                // (the `<` becomes binary, the `?` ternary), so replacing the return type via a
                // template fails with a ClassCastException. Hand-build the J.ParameterizedType
                // instead — same prefix as the existing return type so whitespace is preserved,
                // and a freshly-imported `CompletableFuture` qualifier.
                maybeAddImport(COMPLETABLE_FUTURE_FQN, false);
                return md.withReturnTypeExpression(
                        buildCompletableFutureWildcardType((Expression) md.getReturnTypeExpression()));
            }

            private J.ParameterizedType buildCompletableFutureWildcardType(Expression existing) {
                JavaType.Class cfType = JavaType.ShallowClass.build(COMPLETABLE_FUTURE_FQN);
                // Inner J.Identifier holds its own (empty) prefix; the ParameterizedType wrapper
                // owns the leading whitespace so the return type rendering does not double up
                // the indentation.
                J.Identifier nameId = new J.Identifier(
                        org.openrewrite.Tree.randomId(),
                        org.openrewrite.java.tree.Space.EMPTY,
                        org.openrewrite.marker.Markers.EMPTY,
                        java.util.Collections.emptyList(),
                        "CompletableFuture",
                        cfType,
                        null);
                J.Wildcard wildcard = new J.Wildcard(
                        org.openrewrite.Tree.randomId(),
                        org.openrewrite.java.tree.Space.EMPTY,
                        org.openrewrite.marker.Markers.EMPTY,
                        null,
                        null);
                org.openrewrite.java.tree.JContainer<Expression> typeParams = org.openrewrite.java.tree.JContainer.build(
                        org.openrewrite.java.tree.Space.EMPTY,
                        java.util.Collections.singletonList(
                                org.openrewrite.java.tree.JRightPadded.build((Expression) wildcard)
                        ),
                        org.openrewrite.marker.Markers.EMPTY);
                return new J.ParameterizedType(
                        org.openrewrite.Tree.randomId(),
                        existing.getPrefix(),
                        org.openrewrite.marker.Markers.EMPTY,
                        nameId,
                        typeParams,
                        new JavaType.Parameterized(null, cfType, java.util.Collections.singletonList(JavaType.Unknown.getInstance())));
            }

            /**
             * Replaces every dispatch expression-statement that lives at the tail of an applicable block
             * (method body, try-block, catch-block, then-block of a guard {@code if}) with a
             * {@code return <expr>.getResultMessage();} statement. If the body's last statement is a
             * guard-{@code if} whose then-block now ends in a return, the visitor also appends a
             * {@code return CompletableFuture.completedFuture(null);} so the void-returning false branch
             * still produces a future.
             */
            private J.MethodDeclaration wrapDispatchesInReturn(J.MethodDeclaration md, String dispatcherName,
                                                                ExecutionContext ctx) {
                return (J.MethodDeclaration) new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.Block visitBlock(J.Block block, ExecutionContext c) {
                        J.Block b = super.visitBlock(block, c);
                        List<Statement> stmts = b.getStatements();
                        if (stmts.isEmpty()) {
                            return b;
                        }
                        Statement last = stmts.get(stmts.size() - 1);
                        if (!isDispatchExpressionStatement(last, dispatcherName)) {
                            return b;
                        }
                        J.MethodInvocation call = (J.MethodInvocation) last;
                        // Re-prefix the inner call to a single space so it renders flush after `return`.
                        // The outer J.Return owns the statement-leading whitespace (newline + indent).
                        J.MethodInvocation rewrappedCall = call.withPrefix(
                                org.openrewrite.java.tree.Space.format(" "));
                        J.MethodInvocation withGetResult = new J.MethodInvocation(
                                org.openrewrite.Tree.randomId(),
                                org.openrewrite.java.tree.Space.EMPTY,
                                org.openrewrite.marker.Markers.EMPTY,
                                org.openrewrite.java.tree.JRightPadded.build((Expression) rewrappedCall),
                                null,
                                new J.Identifier(
                                        org.openrewrite.Tree.randomId(),
                                        org.openrewrite.java.tree.Space.EMPTY,
                                        org.openrewrite.marker.Markers.EMPTY,
                                        java.util.Collections.emptyList(),
                                        "getResultMessage",
                                        null,
                                        null),
                                org.openrewrite.java.tree.JContainer.empty(),
                                null);
                        J.Return ret = new J.Return(
                                org.openrewrite.Tree.randomId(),
                                last.getPrefix(),
                                org.openrewrite.marker.Markers.EMPTY,
                                withGetResult);
                        List<Statement> updated = new ArrayList<>(stmts);
                        updated.set(updated.size() - 1, ret);
                        return b.withStatements(updated);
                    }

                    @Override
                    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration meth,
                                                                      ExecutionContext c) {
                        J.MethodDeclaration m = super.visitMethodDeclaration(meth, c);
                        if (m.getBody() == null) {
                            return m;
                        }
                        List<Statement> bodyStmts = m.getBody().getStatements();
                        if (bodyStmts.isEmpty() || !(bodyStmts.get(bodyStmts.size() - 1) instanceof J.If)) {
                            return m;
                        }
                        J.If guardIf = (J.If) bodyStmts.get(bodyStmts.size() - 1);
                        if (!(guardIf.getThenPart() instanceof J.Block)) {
                            return m;
                        }
                        List<Statement> thenStmts = ((J.Block) guardIf.getThenPart()).getStatements();
                        if (thenStmts.isEmpty() || !(thenStmts.get(thenStmts.size() - 1) instanceof J.Return)) {
                            return m;
                        }
                        // The dispatch in the then-branch was just wrapped in `return`. The else-less false
                        // branch now falls off the end without producing a future. Append a synthetic
                        // `return CompletableFuture.completedFuture(null);` so the widened return type is
                        // fully covered.
                        List<Statement> appended = new ArrayList<>(bodyStmts);
                        appended.add(buildCompletedFutureReturn(guardIf.getPrefix()));
                        return m.withBody(m.getBody().withStatements(appended));
                    }
                }.visitNonNull(md, ctx, getCursor().getParentOrThrow());
            }

            /**
             * Hand-builds {@code return CompletableFuture.completedFuture(null);}. Going through
             * {@link JavaTemplate} here drops the freshly-widened return-type expression — the apply
             * works against the original (cursor-bound) method declaration, not the in-flight {@code md}
             * variable. Constructing the J.Return directly preserves whatever return-type rewrite the
             * caller has already applied. The supplied {@code prefix} is the leading whitespace from the
             * preceding guard-{@code if}, which keeps the new return aligned with the rest of the body.
             */
            private J.Return buildCompletedFutureReturn(org.openrewrite.java.tree.Space prefix) {
                JavaType.Class cfType = JavaType.ShallowClass.build(COMPLETABLE_FUTURE_FQN);
                J.Identifier cfIdentifier = new J.Identifier(
                        org.openrewrite.Tree.randomId(),
                        org.openrewrite.java.tree.Space.EMPTY,
                        org.openrewrite.marker.Markers.EMPTY,
                        java.util.Collections.emptyList(),
                        "CompletableFuture",
                        cfType,
                        null);
                J.Identifier completedFutureName = new J.Identifier(
                        org.openrewrite.Tree.randomId(),
                        org.openrewrite.java.tree.Space.EMPTY,
                        org.openrewrite.marker.Markers.EMPTY,
                        java.util.Collections.emptyList(),
                        "completedFuture",
                        null,
                        null);
                J.Literal nullLiteral = new J.Literal(
                        org.openrewrite.Tree.randomId(),
                        org.openrewrite.java.tree.Space.EMPTY,
                        org.openrewrite.marker.Markers.EMPTY,
                        null,
                        "null",
                        null,
                        JavaType.Primitive.Null);
                org.openrewrite.java.tree.JContainer<Expression> args =
                        org.openrewrite.java.tree.JContainer.build(
                                org.openrewrite.java.tree.Space.EMPTY,
                                java.util.Collections.singletonList(
                                        org.openrewrite.java.tree.JRightPadded.<Expression>build(nullLiteral)),
                                org.openrewrite.marker.Markers.EMPTY);
                J.MethodInvocation call = new J.MethodInvocation(
                        org.openrewrite.Tree.randomId(),
                        org.openrewrite.java.tree.Space.format(" "),
                        org.openrewrite.marker.Markers.EMPTY,
                        org.openrewrite.java.tree.JRightPadded.<Expression>build(cfIdentifier),
                        null,
                        completedFutureName,
                        args,
                        null);
                return new J.Return(
                        org.openrewrite.Tree.randomId(),
                        prefix,
                        org.openrewrite.marker.Markers.EMPTY,
                        call);
            }

            // ── Class-level cleanup of the now-unused gateway field ──────────────

            private boolean isFieldStillReferenced(J.ClassDeclaration cd, String fieldName) {
                boolean[] referenced = {false};
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations vd,
                                                                            ExecutionContext c) {
                        // Skip the field declaration itself; we only care about references.
                        if (isClassLevelGatewayField(vd, fieldName)) {
                            return vd;
                        }
                        return super.visitVariableDeclarations(vd, c);
                    }

                    @Override
                    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration m, ExecutionContext c) {
                        if (isCanonicalGatewayConstructor(m, fieldName)) {
                            // Skip the constructor that exists solely to set this.field = field;
                            return m;
                        }
                        return super.visitMethodDeclaration(m, c);
                    }

                    @Override
                    public J.Identifier visitIdentifier(J.Identifier id, ExecutionContext c) {
                        if (id.getSimpleName().equals(fieldName)) {
                            referenced[0] = true;
                        }
                        return id;
                    }
                }.visit(cd, new org.openrewrite.InMemoryExecutionContext());
                return referenced[0];
            }

            private boolean isClassLevelGatewayField(J.VariableDeclarations vd, String fieldName) {
                if (vd.getTypeExpression() == null || vd.getVariables().isEmpty()) {
                    return false;
                }
                if (!vd.getVariables().get(0).getSimpleName().equals(fieldName)) {
                    return false;
                }
                JavaType.FullyQualified t = TypeUtils.asFullyQualified(vd.getTypeExpression().getType());
                if (t != null && COMMAND_GATEWAY_AF5_FQN.equals(t.getFullyQualifiedName())) {
                    return true;
                }
                return vd.getTypeExpression().toString().equals("CommandGateway");
            }

            /**
             * A constructor that — after we strip its body — would just assign {@code this.field = field;} from a
             * single {@code CommandGateway} parameter. Used to filter that constructor out of the
             * "field still referenced?" scan, since the references inside it are exactly the ones we are about to
             * remove anyway.
             */
            private boolean isCanonicalGatewayConstructor(J.MethodDeclaration m, String fieldName) {
                if (m.getReturnTypeExpression() != null || m.getBody() == null) {
                    return false;
                }
                List<Statement> params = m.getParameters();
                boolean hasGatewayParam = params.stream().anyMatch(s -> {
                    if (!(s instanceof J.VariableDeclarations)) return false;
                    J.VariableDeclarations vd = (J.VariableDeclarations) s;
                    if (vd.getTypeExpression() == null) return false;
                    JavaType.FullyQualified t = TypeUtils.asFullyQualified(vd.getTypeExpression().getType());
                    boolean typeOk = (t != null && COMMAND_GATEWAY_AF5_FQN.equals(t.getFullyQualifiedName()))
                            || vd.getTypeExpression().toString().equals("CommandGateway");
                    return typeOk && !vd.getVariables().isEmpty()
                            && vd.getVariables().get(0).getSimpleName().equals(fieldName);
                });
                return hasGatewayParam;
            }

            private J.ClassDeclaration removeGatewayField(J.ClassDeclaration cd, String fieldName) {
                List<Statement> remaining = new ArrayList<>();
                for (Statement s : cd.getBody().getStatements()) {
                    if (s instanceof J.VariableDeclarations
                            && isClassLevelGatewayField((J.VariableDeclarations) s, fieldName)) {
                        continue;
                    }
                    remaining.add(s);
                }
                return cd.withBody(cd.getBody().withStatements(remaining));
            }

            private J.ClassDeclaration removeGatewayFromConstructors(J.ClassDeclaration cd, String fieldName) {
                List<Statement> rebuilt = new ArrayList<>();
                for (Statement s : cd.getBody().getStatements()) {
                    if (!(s instanceof J.MethodDeclaration)) {
                        rebuilt.add(s);
                        continue;
                    }
                    J.MethodDeclaration m = (J.MethodDeclaration) s;
                    if (m.getReturnTypeExpression() != null) {
                        rebuilt.add(s);
                        continue;
                    }
                    // Strip the gateway parameter.
                    List<Statement> newParams = new ArrayList<>();
                    boolean removedAny = false;
                    for (Statement p : m.getParameters()) {
                        if (p instanceof J.VariableDeclarations) {
                            J.VariableDeclarations vd = (J.VariableDeclarations) p;
                            if (!vd.getVariables().isEmpty()
                                    && vd.getVariables().get(0).getSimpleName().equals(fieldName)
                                    && (TypeUtils.asFullyQualified(vd.getTypeExpression() == null
                                                                          ? null
                                                                          : vd.getTypeExpression().getType()) != null
                                                && COMMAND_GATEWAY_AF5_FQN.equals(
                                                        TypeUtils.asFullyQualified(
                                                                vd.getTypeExpression().getType())
                                                                .getFullyQualifiedName())
                                        || vd.getTypeExpression() != null
                                                && vd.getTypeExpression().toString().equals("CommandGateway"))) {
                                removedAny = true;
                                continue;
                            }
                        }
                        newParams.add(p);
                    }
                    if (!removedAny) {
                        rebuilt.add(s);
                        continue;
                    }
                    if (newParams.isEmpty()) {
                        // The gateway was the constructor's sole parameter — drop the constructor entirely
                        // so Spring can use the default no-arg one.
                        continue;
                    }
                    m = m.withParameters(newParams);
                    // Strip the matching this.field = field assignment.
                    if (m.getBody() != null) {
                        List<Statement> body = new ArrayList<>();
                        for (Statement bs : m.getBody().getStatements()) {
                            if (isFieldAssignment(bs, fieldName)) {
                                continue;
                            }
                            body.add(bs);
                        }
                        m = m.withBody(m.getBody().withStatements(body));
                    }
                    rebuilt.add(m);
                }
                return cd.withBody(cd.getBody().withStatements(rebuilt));
            }

            private boolean isFieldAssignment(Statement s, String fieldName) {
                if (!(s instanceof J.Assignment)) {
                    return false;
                }
                J.Assignment a = (J.Assignment) s;
                Expression variable = a.getVariable();
                if (variable instanceof J.FieldAccess) {
                    J.FieldAccess fa = (J.FieldAccess) variable;
                    return fa.getName().getSimpleName().equals(fieldName);
                }
                return variable instanceof J.Identifier
                        && ((J.Identifier) variable).getSimpleName().equals(fieldName);
            }
        };
    }
}
