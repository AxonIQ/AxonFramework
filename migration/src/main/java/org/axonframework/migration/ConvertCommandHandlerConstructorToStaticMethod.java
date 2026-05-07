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
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Rewrites Axon Framework 4 {@code @CommandHandler} constructors into static {@code handle}
 * methods, the AF5 idiom for "creational" command handlers.
 * <p>
 * In AF4, an aggregate's create-command was typically dispatched into a constructor annotated
 * with {@code @CommandHandler}. AF5 narrows the {@code @CommandHandler} target to
 * {@code METHOD} / {@code ANNOTATION_TYPE} only, so a literal AF4-style constructor handler
 * fails to compile. The AF5-equivalent shape (see {@code @EntityCreator} javadoc, "Immutable
 * entity" example) is a {@code static} method on the entity that publishes the creation event;
 * the entity itself is then created by an {@code @EntityCreator} factory invoked when the
 * event is sourced.
 * <p>
 * The transformation applied per matching constructor:
 * <pre>
 * &#64;CommandHandler                                    &#64;CommandHandler
 * public Payment(PreparePaymentCommand cmd,    →    public static void handle(PreparePaymentCommand cmd,
 *                EventAppender appender) {                                EventAppender appender) {
 *     appender.append(...);                              appender.append(...);
 * }                                                  }
 * </pre>
 * Both AF4 ({@code org.axonframework.commandhandling.CommandHandler}) and AF5
 * ({@code org.axonframework.messaging.commandhandling.annotation.CommandHandler}) FQNs are
 * matched, so the recipe is order-independent with respect to the package-rename recipes in
 * {@code Axon4ToAxon5Messaging}.
 * <p>
 * For mutable entities whose {@code @EntityCreator} can construct the instance without an
 * event payload (e.g. a no-arg constructor or one that only takes {@code @InjectEntityId}),
 * the entity exists when the create-command arrives and an instance method would also work;
 * this recipe always emits a {@code static} method because that shape is correct for both the
 * mutable and the immutable entity patterns.
 *
 * @author Axon Framework Team
 * @since 5.2.0
 */
public class ConvertCommandHandlerConstructorToStaticMethod extends Recipe {

    private static final String COMMAND_HANDLER_AF4 = "org.axonframework.commandhandling.CommandHandler";
    private static final String COMMAND_HANDLER_AF5 = "org.axonframework.messaging.commandhandling.annotation.CommandHandler";
    private static final String NEW_METHOD_NAME = "handle";

    @Override
    public String getDisplayName() {
        return "Convert @CommandHandler constructors to static handle methods";
    }

    @Override
    public String getDescription() {
        return "Rewrites Axon Framework 4 `@CommandHandler` constructors into `public static "
                + "void handle(...)` methods, matching the AF5 contract where `@CommandHandler` "
                + "no longer targets constructors. Parameter list and method body are preserved.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<>() {

            @Override
            public boolean isAcceptable(SourceFile sourceFile, ExecutionContext ctx) {
                // Java-only. Kotlin sources need a different output shape (companion object +
                // @JvmStatic — there is no `static` modifier in Kotlin) and are handled by the
                // dedicated ConvertCommandHandlerConstructorToCompanionObject recipe.
                return sourceFile instanceof J.CompilationUnit;
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method,
                                                              ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);
                if (!m.isConstructor() || !hasCommandHandlerAnnotation(m)) {
                    return m;
                }
                return rewriteToStaticHandleMethod(m);
            }

            private boolean hasCommandHandlerAnnotation(J.MethodDeclaration m) {
                for (J.Annotation ann : m.getLeadingAnnotations()) {
                    if (TypeUtils.isOfClassType(ann.getType(), COMMAND_HANDLER_AF4)
                            || TypeUtils.isOfClassType(ann.getType(), COMMAND_HANDLER_AF5)) {
                        return true;
                    }
                    if (ann.getAnnotationType() instanceof J.Identifier
                            && "CommandHandler".equals(
                                    ((J.Identifier) ann.getAnnotationType()).getSimpleName())) {
                        return true;
                    }
                }
                return false;
            }

            private J.MethodDeclaration rewriteToStaticHandleMethod(J.MethodDeclaration m) {
                // Insert `static` after the existing modifiers (typically right after `public`).
                List<J.Modifier> modifiers = new ArrayList<>(m.getModifiers());
                boolean alreadyStatic = false;
                for (J.Modifier mod : modifiers) {
                    if (mod.getType() == J.Modifier.Type.Static) {
                        alreadyStatic = true;
                        break;
                    }
                }
                if (!alreadyStatic) {
                    modifiers.add(new J.Modifier(
                            Tree.randomId(),
                            Space.format(" "),
                            Markers.EMPTY,
                            null,
                            J.Modifier.Type.Static,
                            Collections.emptyList()));
                }

                // The constructor's name is the class name and carries the spacing that the
                // method's signature currently uses between modifiers and name. Promote that
                // spacing onto the new `void` return type so the printed result remains
                // `public static void handle(...)`; the method name itself slots in with a
                // single leading space.
                Space nameOriginalPrefix = m.getName().getPrefix();
                J.Identifier voidReturnType = new J.Identifier(
                        Tree.randomId(),
                        nameOriginalPrefix,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        "void",
                        JavaType.Primitive.Void,
                        null);

                J.Identifier newName = m.getName()
                        .withSimpleName(NEW_METHOD_NAME)
                        .withPrefix(Space.format(" "));

                return m.withModifiers(modifiers)
                        .withReturnTypeExpression(voidReturnType)
                        .withName(newName);
            }
        };
    }
}
