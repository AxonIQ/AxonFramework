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
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Comment;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.java.tree.TypeUtils;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Leaves a {@code // TODO(axon4to5):} marker on Axon Framework 4 serializer configuration, which Axon Framework 5
 * replaces with a {@code Converter}.
 * <p>
 * The migration does not port a serializer's configuration, such as the visibility or default typing of a
 * {@code JacksonSerializer}'s {@code ObjectMapper}, to a converter. Dropping that configuration changes how Axon
 * Framework 5 reads what Axon Framework 4 stored: a Saga field without accessors, written by a serializer with field
 * visibility, reads back as {@code null}. The marker asks to compare the serializer configuration with the converter
 * configuration instead. It goes on:
 * <ul>
 *     <li>a method returning an Axon Framework 4 serializer type that is a Spring {@code @Bean} method or builds a
 *     serializer, once per method;</li>
 *     <li>any other statement building a {@code JacksonSerializer}, {@code Jackson3Serializer},
 *     {@code XStreamSerializer}, {@code JavaSerializer} or {@code AvroSerializer}, through {@code builder()},
 *     {@code defaultSerializer()} or a constructor.</li>
 * </ul>
 * A statement that {@link MigrateDeadlineManagerBuilders} already marked to replace {@code serializer(..)} is not
 * marked again. The recipe matches the Axon Framework 4 types, so it runs before the
 * {@code org.axonframework.serialization} package is renamed. It is idempotent: an element that already carries the
 * marker is left alone.
 *
 * @author Stefan Dragisic
 * @since 5.4.1
 */
public class MarkSerializerConfiguration extends Recipe {

    private static final String SERIALIZER_FQN = "org.axonframework.serialization.Serializer";
    private static final Set<String> SERIALIZER_IMPLEMENTATION_FQNS = Set.of(
            "org.axonframework.serialization.JavaSerializer",
            "org.axonframework.serialization.json.JacksonSerializer",
            "org.axonframework.serialization.jackson3.Jackson3Serializer",
            "org.axonframework.serialization.xml.XStreamSerializer",
            "org.axonframework.serialization.avro.AvroSerializer"
    );
    private static final Set<String> SERIALIZER_TYPE_FQNS = Stream.concat(
            Stream.of(SERIALIZER_FQN, "org.axonframework.serialization.AbstractXStreamSerializer"),
            SERIALIZER_IMPLEMENTATION_FQNS.stream()
    ).collect(Collectors.toUnmodifiableSet());
    private static final Set<String> FACTORY_METHODS = Set.of("builder", "defaultSerializer");
    private static final String SPRING_BEAN_FQN = "org.springframework.context.annotation.Bean";

    private static final StatementTodoMarker.Todo SERIALIZER_TODO = new StatementTodoMarker.Todo(
            "TODO(axon4to5): Axon Framework 5 uses a Converter instead of a Serializer.",
            " TODO(axon4to5): Axon Framework 5 uses a Converter instead of a Serializer. Compare this serializer's"
                    + " configuration, such as ObjectMapper visibility or default typing, with your Converter"
                    + " configuration: sagas, tokens and deadlines stored by Axon Framework 4 are read with it."
    );

    @Override
    public String getDisplayName() {
        return "Mark Axon Framework 4 serializer configuration";
    }

    @Override
    public String getDescription() {
        return "Leaves a `// TODO(axon4to5):` marker on Axon Framework 4 serializer configuration: a method returning "
                + "a `Serializer` that is a Spring `@Bean` method or builds one, and any other statement building a "
                + "`JacksonSerializer`, `Jackson3Serializer`, `XStreamSerializer`, `JavaSerializer` or "
                + "`AvroSerializer`. Axon Framework 5 reads what Axon Framework 4 stored with a `Converter`, so the "
                + "converter configuration must match the serializer configuration. Idempotent.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration visited = super.visitMethodDeclaration(method, ctx);
                return isSerializerMethod(visited) ? StatementTodoMarker.mark(visited, SERIALIZER_TODO) : visited;
            }

            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block visited = super.visitBlock(block, ctx);
                J.MethodDeclaration method = getCursor().firstEnclosing(J.MethodDeclaration.class);
                if (method != null && isSerializerMethod(method)) {
                    // The method carries the one marker for the serializers it builds.
                    return visited;
                }
                return visited.withStatements(
                        ListUtils.map(visited.getStatements(), MarkSerializerConfiguration::markStatement));
            }
        };
    }

    private static boolean isSerializerMethod(J.MethodDeclaration method) {
        return refersTo(method.getReturnTypeExpression(), SERIALIZER_TYPE_FQNS)
                && (isSpringBean(method) || (method.getBody() != null && buildsSerializer(method.getBody(), true)));
    }

    /**
     * Marks a statement building a serializer, not looking into nested blocks, which mark their own statements.
     */
    private static Statement markStatement(Statement statement) {
        if (!buildsSerializer(statement, false)
                || hasComment(statement, MigrateDeadlineManagerBuilders.SERIALIZER_MARKER)) {
            return statement;
        }
        return StatementTodoMarker.mark(statement, SERIALIZER_TODO);
    }

    private static boolean buildsSerializer(J tree, boolean intoNestedBlocks) {
        boolean[] found = {false};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Block visitBlock(J.Block block, Integer p) {
                return intoNestedBlocks ? super.visitBlock(block, p) : block;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation, Integer p) {
                if (FACTORY_METHODS.contains(invocation.getSimpleName())
                        && refersTo(invocation.getSelect(), SERIALIZER_IMPLEMENTATION_FQNS)) {
                    found[0] = true;
                }
                return super.visitMethodInvocation(invocation, p);
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, Integer p) {
                if (refersTo(newClass.getClazz(), SERIALIZER_IMPLEMENTATION_FQNS)) {
                    found[0] = true;
                }
                return super.visitNewClass(newClass, p);
            }
        }.visit(tree, 0);
        return found[0];
    }

    private static boolean isSpringBean(J.MethodDeclaration method) {
        return method.getLeadingAnnotations().stream().anyMatch(annotation -> {
            String type = resolvedName(annotation.getType());
            return type != null ? SPRING_BEAN_FQN.equals(type) : "Bean".equals(annotation.getSimpleName());
        });
    }

    /**
     * Whether the given type reference names one of the given types, falling back to the simple name only when the
     * type could not be resolved.
     */
    private static boolean refersTo(@Nullable J reference, Set<String> fullyQualifiedNames) {
        String simpleName = switch (reference) {
            case J.Identifier identifier -> identifier.getSimpleName();
            case J.FieldAccess fieldAccess -> fieldAccess.getSimpleName();
            case null, default -> null;
        };
        if (simpleName == null) {
            return false;
        }
        String type = resolvedName(((Expression) reference).getType());
        return type != null
                ? fullyQualifiedNames.contains(type)
                : fullyQualifiedNames.stream().anyMatch(name -> name.endsWith("." + simpleName));
    }

    private static boolean hasComment(J element, String marker) {
        for (Comment comment : element.getPrefix().getComments()) {
            if (comment instanceof TextComment text && text.getText().contains(marker)) {
                return true;
            }
        }
        return false;
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
