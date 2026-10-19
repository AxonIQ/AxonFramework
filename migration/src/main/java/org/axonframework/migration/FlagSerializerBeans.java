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
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

/**
 * Flags Spring {@code @Bean} methods that register an Axon Framework 4 {@code Serializer} with a
 * {@code // TODO(axon4to5):} comment.
 * <p>
 * AF5 replaced the {@code Serializer} API with the {@code Converter} API, so such a bean no longer compiles. A
 * {@code Serializer} cannot be swapped for a {@code Converter} mechanically (for example, {@code XStreamSerializer} has
 * no replacement), hence the comment points to the serializer migration path instead of rewriting the bean.
 * <p>
 * A method is flagged when it is annotated with {@code @Bean} and its declared return type is assignable to the AF4
 * {@code org.axonframework.serialization.Serializer} or to the same type after the package rename to
 * {@code org.axonframework.conversion}. The latter keeps the recipe working regardless of whether it runs before or
 * after the package rename. Serializers of other libraries, such as Jackson's {@code JsonSerializer} or Kafka's
 * {@code Serializer}, are never flagged. Only when the return type could not be resolved at all does the recipe fall
 * back to the simple name {@code Serializer}.
 *
 * @author Mateusz Nowak
 * @since 5.1.1
 */
public class FlagSerializerBeans extends Recipe {

    private static final String SERIALIZER_AF4 = "org.axonframework.serialization.Serializer";
    private static final String SERIALIZER_AF5_INTERMEDIATE = "org.axonframework.conversion.Serializer";
    private static final String BEAN_FQN = "org.springframework.context.annotation.Bean";

    private static final String TODO_MARKER = "TODO(axon4to5): Replace this Serializer bean with a Converter.";
    private static final String TODO_TEXT =
            " " + TODO_MARKER + " AF5 no longer has a Serializer. See "
                    + "docs/reference-guide/modules/migration/pages/paths/serializers.adoc";

    @Override
    public String getDisplayName() {
        return "Flag Serializer beans for replacement with a Converter";
    }

    @Override
    public String getDescription() {
        return "Prepends a `TODO(axon4to5):` comment to every Spring `@Bean` method that returns an Axon Framework 4 "
                + "`Serializer`. AF5 replaced the `Serializer` API with the `Converter` API, so the bean has to be "
                + "replaced by a `Converter`. The comment points to the serializer migration path.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<>() {

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);
                if (!hasBeanAnnotation(md) || !returnsSerializer(md) || isAlreadyFlagged(md)) {
                    return md;
                }
                Space prefix = md.getPrefix();
                String leading = prefix.getWhitespace();
                String indent = leading.contains("\n") ? leading.substring(leading.lastIndexOf('\n') + 1) : "";
                TextComment todo = new TextComment(false, TODO_TEXT, "\n" + indent, Markers.EMPTY);
                return md.withPrefix(prefix.withComments(ListUtils.concat(prefix.getComments(), todo)));
            }
        };
    }

    private static boolean hasBeanAnnotation(J.MethodDeclaration method) {
        for (J.Annotation ann : method.getLeadingAnnotations()) {
            if (TypeUtils.isOfClassType(ann.getType(), BEAN_FQN)) {
                return true;
            }
            if (ann.getAnnotationType() instanceof J.Identifier id && "Bean".equals(id.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean returnsSerializer(J.MethodDeclaration method) {
        if (method.getReturnTypeExpression() == null) {
            return false;
        }
        JavaType returnType = method.getReturnTypeExpression().getType();
        if (returnType == null || returnType instanceof JavaType.Unknown) {
            return method.getReturnTypeExpression() instanceof J.Identifier id
                    && "Serializer".equals(id.getSimpleName());
        }
        return TypeUtils.isAssignableTo(SERIALIZER_AF4, returnType)
                || TypeUtils.isAssignableTo(SERIALIZER_AF5_INTERMEDIATE, returnType);
    }

    private static boolean isAlreadyFlagged(J.MethodDeclaration method) {
        return method.getPrefix()
                     .getComments()
                     .stream()
                     .anyMatch(c -> c instanceof TextComment tc && tc.getText().contains(TODO_MARKER));
    }
}
