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
import org.openrewrite.Parser;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.RemoveImport;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Migrates the method signatures of {@link org.axonframework.messaging.core.MessageHandlerInterceptor}
 * and {@link org.axonframework.messaging.core.MessageDispatchInterceptor} implementations to their
 * Axon Framework 5 shape, while leaving the method body untouched.
 *
 * <p>The interceptor migration cannot be fully mechanized — the body's call sites
 * ({@code interceptorChain.proceed()}, {@code unitOfWork.onCommit(...)}, {@code unitOfWork.onRollback(...)},
 * etc.) change shape and lifecycle-phase semantics together, and several AF4 hooks (notably
 * {@code onRollback}) have no one-to-one AF5 equivalent. Rewriting them blindly would silently land
 * semantically-wrong code in non-trivial interceptors.
 *
 * <p>The pragmatic split this recipe takes:
 * <ul>
 *   <li><b>Signature</b> — fully rewritten. The AF4 method
 *       {@code Object handle(UnitOfWork<? extends T> uow, InterceptorChain chain) throws Exception}
 *       becomes
 *       {@code MessageStream<?> interceptOnHandle(M message, ProcessingContext context,
 *       MessageHandlerInterceptorChain<M> chain)}. Same idea for {@code MessageDispatchInterceptor}:
 *       {@code BiFunction<Integer, T, T> handle(List<? extends T> messages)} becomes
 *       {@code MessageStream<?> interceptOnDispatch(M message, ProcessingContext context,
 *       MessageDispatchInterceptorChain<M> chain)}.</li>
 *   <li><b>Body</b> — left as-is. References to the dropped {@code unitOfWork} /
 *       {@code interceptorChain} / {@code messages} parameters become compile errors, which is
 *       exactly the desired feedback: every line that needs review surfaces at compile time
 *       rather than via a silent rewrite.</li>
 *   <li><b>Class-level TODO</b> — a {@code // TODO(axon4to5):} comment is prepended to the class with a
 *       pointer to {@code docs/reference-guide/modules/migration/pages/paths/interceptors.adoc}
 *       so a follow-up pass (LLM or human) knows where to look.</li>
 * </ul>
 *
 * <p>The {@code M} type is read from the {@code implements MessageHandlerInterceptor<M>} (or
 * {@code MessageDispatchInterceptor<M>}) clause on the enclosing class. If the implements clause
 * is raw (no type argument), the new parameter falls back to {@code Message}.
 *
 * <p>Idempotent — methods already on the AF5 names ({@code interceptOnHandle} /
 * {@code interceptOnDispatch}) are skipped, and the class-level TODO is only added once.
 *
 * <p><b>Must run after</b> {@code Axon4ToAxon5Messaging}'s {@code ChangeType} steps so that the
 * implements clause already references the AF5 FQNs.
 *
 * @author Mateusz Nowak
 * @since 5.1.1
 */
public class MigrateMessageInterceptorSignatures extends Recipe {

    private static final String AF5_HANDLER_INTERCEPTOR =
            "org.axonframework.messaging.core.MessageHandlerInterceptor";
    private static final String AF5_DISPATCH_INTERCEPTOR =
            "org.axonframework.messaging.core.MessageDispatchInterceptor";
    private static final String AF5_HANDLER_CHAIN =
            "org.axonframework.messaging.core.MessageHandlerInterceptorChain";
    private static final String AF5_DISPATCH_CHAIN =
            "org.axonframework.messaging.core.MessageDispatchInterceptorChain";
    private static final String AF5_PROCESSING_CONTEXT =
            "org.axonframework.messaging.core.unitofwork.ProcessingContext";
    private static final String AF5_MESSAGE_STREAM =
            "org.axonframework.messaging.core.MessageStream";
    private static final String AF5_MESSAGE_FALLBACK =
            "org.axonframework.messaging.core.Message";

    private static final String TODO_TEXT =
            " TODO(axon4to5): migrate the body of this interceptor to the AF5 API — "
                    + "the signature has been rewritten but the body still references the AF4 "
                    + "`unitOfWork` / `interceptorChain` / `messages` parameters. Replace those with "
                    + "calls on `message`, `context`, `chain`. See "
                    + "docs/reference-guide/modules/migration/pages/paths/interceptors.adoc";

    private static final String IDEMPOTENCY_MARKER = "TODO(axon4to5): migrate the body of this interceptor";

    @Override
    public String getDisplayName() {
        return "Migrate MessageHandlerInterceptor / MessageDispatchInterceptor signatures to AF5";
    }

    @Override
    public String getDescription() {
        return "Rewrites the method signatures of `MessageHandlerInterceptor` and "
                + "`MessageDispatchInterceptor` implementations to their AF5 shape: "
                + "`handle(UnitOfWork, InterceptorChain) -> Object` becomes "
                + "`interceptOnHandle(M, ProcessingContext, MessageHandlerInterceptorChain<M>) -> "
                + "MessageStream<?>` (and similarly for the dispatch interceptor). The method "
                + "**body is left untouched** — the dropped `unitOfWork` / `interceptorChain` "
                + "references become compile errors, surfacing every call site that needs review. "
                + "A class-level `// TODO(axon4to5):` comment points to the migration path doc. The "
                + "message type `M` is read from the `implements` clause; raw implementations fall "
                + "back to `Message`. Runs after the AF4 -> AF5 FQN renames.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd, ExecutionContext ctx) {
                J.ClassDeclaration classDecl = super.visitClassDeclaration(cd, ctx);
                InterceptorRole role = roleOf(classDecl);
                if (role == null) {
                    return classDecl;
                }
                String messageType = messageTypeArg(classDecl, role);

                // Two passes: rewrite the matching `handle` method, then add the class-level TODO.
                // Both are guarded by idempotency checks so re-running the recipe is a no-op.
                J.ClassDeclaration rewritten = (J.ClassDeclaration) new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration md,
                                                                       ExecutionContext c) {
                        J.MethodDeclaration method = super.visitMethodDeclaration(md, c);
                        if (!"handle".equals(method.getSimpleName())) {
                            return method;
                        }
                        if (role == InterceptorRole.HANDLER && !hasAf4HandlerShape(method)) {
                            return method;
                        }
                        if (role == InterceptorRole.DISPATCH && !hasAf4DispatchShape(method)) {
                            return method;
                        }
                        return role == InterceptorRole.HANDLER
                                ? rewriteHandlerSignature(method, messageType)
                                : rewriteDispatchSignature(method, messageType);
                    }
                }.visit(classDecl, ctx, getCursor().getParentOrThrow());

                if (rewritten == classDecl || alreadyMarked(rewritten)) {
                    return rewritten;
                }
                return prependTodoComment(rewritten);
            }

            private J.MethodDeclaration rewriteHandlerSignature(J.MethodDeclaration method,
                                                                  String messageType) {
                String paramsTemplate = messageType + " message, ProcessingContext context, "
                        + "MessageHandlerInterceptorChain<" + messageType + "> chain";
                return rewriteSignature(method,
                                        paramsTemplate,
                                        "interceptOnHandle",
                                        AF5_HANDLER_CHAIN);
            }

            private J.MethodDeclaration rewriteDispatchSignature(J.MethodDeclaration method,
                                                                   String messageType) {
                String paramsTemplate = messageType + " message, ProcessingContext context, "
                        + "MessageDispatchInterceptorChain<" + messageType + "> chain";
                return rewriteSignature(method,
                                        paramsTemplate,
                                        "interceptOnDispatch",
                                        AF5_DISPATCH_CHAIN);
            }

            private J.MethodDeclaration rewriteSignature(J.MethodDeclaration method,
                                                          String paramsTemplate,
                                                          String newName,
                                                          String chainFqn) {
                // Build a stub class containing the desired method signature with an empty body,
                // parse it, and graft the stub's return-type / name / parameter LST nodes onto
                // the original method (preserving the original body, modifiers, and leading
                // annotations).
                //
                // Why this and not JavaTemplate? OpenRewrite's `J.MethodDeclaration.Coordinates`
                // doesn't expose a `replaceReturnType()` API, and `replaceMethod()` /
                // `getCoordinates().replace()` route through the enclosing class declaration —
                // the apply call returns a `J.ClassDeclaration`, not a `J.MethodDeclaration`,
                // which breaks the visitor's contract. Sub-parsing a stub keeps the
                // signature-swap surgical and yields a `J.MethodDeclaration` we can `with*`
                // directly.
                J.MethodDeclaration stub = parseSignatureStub(paramsTemplate, newName, chainFqn);
                if (stub == null) {
                    return method;
                }
                maybeAddImport(AF5_MESSAGE_STREAM, null, false);
                maybeAddImport(AF5_PROCESSING_CONTEXT, null, false);
                maybeAddImport(chainFqn, null, false);
                // The body still references `unitOfWork` as an unresolved identifier (compile
                // error — desired feedback), but its LST type binding keeps OpenRewrite's
                // conservative `maybeRemoveImport` from stripping the now-unused `UnitOfWork`
                // import. Force-remove it via `RemoveImport(force=true)` so the post-rewrite
                // file imports only what the AF5 signature needs; the lingering `uow` reference
                // in the body is the only thing the user must rewrite by hand.
                doAfterVisit(new RemoveImport<>(
                        "org.axonframework.messaging.core.unitofwork.UnitOfWork", true));

                // The stub's return type ships with no leading whitespace; the surrounding modifier
                // list (`public`) expects a trailing-space-then-type rendering. Inject a single
                // space so the printed output reads `public MessageStream<?>` rather than the
                // jammed `publicMessageStream<?>`.
                TypeTree newReturn = stub.getReturnTypeExpression();
                if (newReturn != null && newReturn.getPrefix().getWhitespace().isEmpty()) {
                    newReturn = newReturn.withPrefix(Space.format(" "));
                }
                return method
                        .withReturnTypeExpression(newReturn)
                        .withName(stub.getName())
                        .withParameters(stub.getParameters())
                        .withThrows(null);
            }

            /**
             * Sub-parses a minimal class containing just the new method signature with an empty
             * body, then returns the parsed {@link J.MethodDeclaration}. The stub deliberately
             * carries no annotations or modifiers — those are preserved from the original method.
             * Returns {@code null} if the parser produces an unexpected shape (very rare; would
             * indicate a malformed {@code paramsTemplate}).
             */
            private J.MethodDeclaration parseSignatureStub(String paramsTemplate,
                                                            String newName,
                                                            String chainFqn) {
                String src = "import " + AF5_MESSAGE_STREAM + ";\n"
                        + "import " + AF5_PROCESSING_CONTEXT + ";\n"
                        + "import " + chainFqn + ";\n"
                        + "class _Stub {\n"
                        + "    MessageStream<?> " + newName + "(" + paramsTemplate + ") { }\n"
                        + "}\n";
                List<SourceFile> parsed = JavaParser.fromJavaVersion()
                        .classpath(JavaParser.runtimeClasspath())
                        .build()
                        .parseInputs(java.util.Collections.singletonList(
                                Parser.Input.fromString(java.nio.file.Paths.get("_Stub.java"), src)),
                                java.nio.file.Paths.get("."),
                                new org.openrewrite.InMemoryExecutionContext())
                        .collect(Collectors.toList());
                if (parsed.isEmpty() || !(parsed.get(0) instanceof J.CompilationUnit)) {
                    return null;
                }
                J.CompilationUnit cu = (J.CompilationUnit) parsed.get(0);
                if (cu.getClasses().isEmpty()) {
                    return null;
                }
                List<Statement> stmts = cu.getClasses().get(0).getBody().getStatements();
                for (Statement s : stmts) {
                    if (s instanceof J.MethodDeclaration) {
                        return (J.MethodDeclaration) s;
                    }
                }
                return null;
            }

            private boolean hasAf4HandlerShape(J.MethodDeclaration method) {
                // AF4 handler signature: `Object handle(UnitOfWork<? extends T> uow, InterceptorChain chain)`.
                // After `Axon4ToAxon5Messaging` runs, `InterceptorChain` has been renamed to
                // `MessageHandlerInterceptorChain` and `UnitOfWork` to its AF5 FQN, but the param
                // count and method name (`handle`) remain — match on those rather than on parameter
                // types so we work both pre- and post-FQN-rename.
                return method.getParameters().size() == 2
                        && !(method.getParameters().get(0) instanceof J.Empty);
            }

            private boolean hasAf4DispatchShape(J.MethodDeclaration method) {
                // AF4 dispatch signature: `BiFunction<Integer, T, T> handle(List<? extends T> messages)`.
                // A single-arg `handle` is the unique AF4 dispatch shape — match on that.
                return method.getParameters().size() == 1
                        && !(method.getParameters().get(0) instanceof J.Empty);
            }

            private InterceptorRole roleOf(J.ClassDeclaration classDecl) {
                List<TypeTree> impls = classDecl.getImplements();
                if (impls == null) {
                    return null;
                }
                for (TypeTree impl : impls) {
                    JavaType.FullyQualified fq = TypeUtils.asFullyQualified(impl.getType());
                    if (fq == null) {
                        continue;
                    }
                    if (AF5_HANDLER_INTERCEPTOR.equals(fq.getFullyQualifiedName())) {
                        return InterceptorRole.HANDLER;
                    }
                    if (AF5_DISPATCH_INTERCEPTOR.equals(fq.getFullyQualifiedName())) {
                        return InterceptorRole.DISPATCH;
                    }
                }
                return null;
            }

            /**
             * Reads the message type argument {@code <M>} from the matching
             * {@code implements MessageHandlerInterceptor<M>} clause. Falls back to {@code Message}
             * when the implements clause is raw or the argument has no resolvable FQN.
             */
            private String messageTypeArg(J.ClassDeclaration classDecl, InterceptorRole role) {
                List<TypeTree> impls = classDecl.getImplements();
                if (impls == null) {
                    return "Message";
                }
                String wantFqn = role == InterceptorRole.HANDLER
                        ? AF5_HANDLER_INTERCEPTOR
                        : AF5_DISPATCH_INTERCEPTOR;
                for (TypeTree impl : impls) {
                    JavaType.FullyQualified fq = TypeUtils.asFullyQualified(impl.getType());
                    if (fq == null || !wantFqn.equals(fq.getFullyQualifiedName())) {
                        continue;
                    }
                    if (impl instanceof J.ParameterizedType) {
                        J.ParameterizedType pt = (J.ParameterizedType) impl;
                        if (pt.getTypeParameters() != null && !pt.getTypeParameters().isEmpty()) {
                            return pt.getTypeParameters().get(0).print(getCursor()).trim();
                        }
                    }
                }
                // Raw implements clause — fall back to the base Message type and add its import.
                maybeAddImport(AF5_MESSAGE_FALLBACK, null, false);
                return "Message";
            }

            private boolean alreadyMarked(J.ClassDeclaration classDecl) {
                Space prefix = classDecl.getPrefix();
                for (org.openrewrite.java.tree.Comment c : prefix.getComments()) {
                    if (c instanceof TextComment
                            && ((TextComment) c).getText().contains(IDEMPOTENCY_MARKER)) {
                        return true;
                    }
                }
                return false;
            }

            /**
             * Prepends the TODO line comment to the class declaration's prefix. Mirrors the layout
             * convention used by {@link ConfigureEventSourcedAnnotation}'s
             * {@code prependLineComment}: the comment carries a trailing newline-plus-indent so the
             * class keyword that follows keeps its original indentation.
             */
            private J.ClassDeclaration prependTodoComment(J.ClassDeclaration classDecl) {
                Space prefix = classDecl.getPrefix();
                String leading = prefix.getWhitespace();
                String indent = leading.contains("\n")
                        ? leading.substring(leading.lastIndexOf('\n') + 1)
                        : "";
                String suffix = "\n" + indent;
                TextComment todo = new TextComment(false, TODO_TEXT, suffix, Markers.EMPTY);
                return classDecl.withPrefix(
                        prefix.withComments(ListUtils.concat(prefix.getComments(), todo)));
            }
        };
    }

    private enum InterceptorRole {
        HANDLER, DISPATCH
    }
}
