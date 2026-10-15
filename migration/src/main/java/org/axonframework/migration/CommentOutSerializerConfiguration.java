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
import org.openrewrite.java.tree.Comment;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Comments out Axon Framework 4 serializer configuration under a {@code // TODO(axon4to5):} marker, as Axon Framework
 * 5 replaces the {@code Serializer} with a {@code Converter}.
 * <p>
 * Axon Framework 5 has no {@code Serializer}, nor a {@code JacksonSerializer}, {@code XStreamSerializer} or other
 * implementation of it, so code configuring one does not compile once migrated. The recipe does not port it to a
 * converter either: the serializer's settings, such as the visibility or default typing of a
 * {@code JacksonSerializer}'s {@code ObjectMapper}, decide how Axon Framework 5 reads what Axon Framework 4 stored. A
 * Saga field without accessors, written by a serializer with field visibility, reads back as {@code null} when those
 * settings are dropped. The marker asks to define a converter with the equivalent settings and check it against the
 * stored data. The recipe comments out, keeping the original source text after {@code //}:
 * <ul>
 *     <li>a method returning an Axon Framework 4 serializer type that is a Spring {@code @Bean} method or builds a
 *     serializer, annotations included;</li>
 *     <li>any other statement building a {@code JacksonSerializer}, {@code Jackson3Serializer},
 *     {@code XStreamSerializer}, {@code JavaSerializer} or {@code AvroSerializer}, through {@code builder()},
 *     {@code defaultSerializer()} or a constructor, such as a field, a local variable or a {@code Configurer} call.
 *     A {@code return} statement is not commented out on its own, as that leaves a result missing: the method it
 *     returns from is commented out, or the statement holding the lambda it returns from.</li>
 * </ul>
 * It removes the imports that only the commented-out code used, so the rename of the
 * {@code org.axonframework.serialization} package that follows does not import types Axon Framework 5 does not have.
 * An enum constant building a serializer cannot be commented out on its own and only gets the marker. A statement that
 * {@link MigrateDeadlineManagerBuilders} already marked to replace {@code serializer(..)} is left to that marker. The
 * recipe matches the Axon Framework 4 types, so it runs before the {@code org.axonframework.serialization} package is
 * renamed. It changes Java sources only and is idempotent: commented-out code builds no serializer.
 *
 * @author Stefan Dragisic
 * @since 5.4.1
 */
public class CommentOutSerializerConfiguration extends Recipe {

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

    private static final String TODO_KEY = "TODO(axon4to5): Axon Framework 5 uses a Converter instead of a Serializer.";
    private static final String COMMENTED_OUT_TODO = " " + TODO_KEY
            + " Define a Converter, for example a JacksonConverter bean, with the settings of the serializer"
            + " configuration commented out below, such as ObjectMapper visibility or default typing. Check that it"
            + " reads the sagas, tokens and deadlines Axon Framework 4 stored.";
    private static final StatementTodoMarker.Todo SERIALIZER_TODO = new StatementTodoMarker.Todo(
            TODO_KEY,
            " " + TODO_KEY + " Compare this serializer's configuration, such as ObjectMapper visibility or default"
                    + " typing, with your Converter configuration: sagas, tokens and deadlines stored by Axon"
                    + " Framework 4 are read with it."
    );
    private static final String TARGETS = "serializerConfigurationTargets";

    @Override
    public String getDisplayName() {
        return "Comment out Axon Framework 4 serializer configuration";
    }

    @Override
    public String getDescription() {
        return "Comments out Axon Framework 4 serializer configuration under a `// TODO(axon4to5):` marker: a method "
                + "returning a `Serializer` that is a Spring `@Bean` method or builds one, and any other statement "
                + "building a `JacksonSerializer`, `Jackson3Serializer`, `XStreamSerializer`, `JavaSerializer` or "
                + "`AvroSerializer`. Removes the imports no other code uses, as Axon Framework 5 has none of these "
                + "types. Axon Framework 5 reads what Axon Framework 4 stored with a `Converter`, so the marker asks "
                + "to define one with the equivalent settings. Idempotent.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public boolean isAcceptable(SourceFile sourceFile, ExecutionContext ctx) {
                return sourceFile instanceof J.CompilationUnit;
            }

            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit compilationUnit, ExecutionContext ctx) {
                Targets targets = Targets.in(compilationUnit);
                if (targets.isEmpty()) {
                    return compilationUnit;
                }
                getCursor().putMessage(TARGETS, targets);
                return super.visitCompilationUnit(compilationUnit, ctx);
            }

            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                // Comments out before visiting the statements, so a target inside a commented-out one is left alone.
                Targets targets = getCursor().getNearestMessage(TARGETS);
                return super.visitBlock(targets == null ? block : commentOut(block, targets), ctx);
            }

            private J.Block commentOut(J.Block block, Targets targets) {
                List<JRightPadded<Statement>> kept = new ArrayList<>();
                Space pending = null;
                boolean changed = false;
                for (JRightPadded<Statement> padded : block.getPadding().getStatements()) {
                    Statement statement = padded.getElement();
                    if (targets.commentOut().contains(statement.getId())) {
                        Space commented = commentedOut(padded, getCursor());
                        pending = pending == null ? commented : followedBy(pending, commented);
                        typesUsedBy(statement).forEach(this::maybeRemoveImport);
                        changed = true;
                        continue;
                    }
                    Statement updated = statement;
                    if (pending != null) {
                        updated = updated.withPrefix(followedBy(pending, updated.getPrefix()));
                        pending = null;
                    }
                    if (targets.mark().contains(statement.getId())) {
                        updated = StatementTodoMarker.mark(updated, SERIALIZER_TODO);
                    }
                    changed |= updated != statement;
                    kept.add(padded.withElement(updated));
                }
                if (!changed) {
                    return block;
                }
                J.Block result = block.getPadding().withStatements(kept);
                return pending == null ? result : result.withEnd(followedBy(pending, result.getEnd()));
            }
        };
    }

    /**
     * The statements of a compilation unit to comment out and to mark, by id.
     *
     * @param commentOut the ids of the statements to comment out
     * @param mark       the ids of the statements to mark, which cannot be commented out on their own
     */
    private record Targets(Set<UUID> commentOut, Set<UUID> mark) {

        static Targets in(J.CompilationUnit compilationUnit) {
            Targets targets = new Targets(new HashSet<>(), new HashSet<>());
            new JavaIsoVisitor<Targets>() {
                @Override
                public J preVisit(J tree, Targets found) {
                    if (tree instanceof J.MethodDeclaration method && isSerializerMethod(method)) {
                        // Commented out as a whole, with the serializers it builds.
                        found.commentOut().add(method.getId());
                        stopAfterPreVisit();
                    } else if (isSerializerBuild(tree)) {
                        found.add(getCursor());
                    }
                    return tree;
                }
            }.visit(compilationUnit, targets);
            return targets;
        }

        boolean isEmpty() {
            return commentOut.isEmpty() && mark.isEmpty();
        }

        /**
         * Adds the statement holding the serializer build at the given cursor.
         */
        private void add(Cursor build) {
            Cursor statement = blockStatement(build);
            if (statement == null
                    || hasComment(statement.getValue(), MigrateDeadlineManagerBuilders.SERIALIZER_MARKER)) {
                return;
            }
            while (statement != null && statement.getValue() instanceof J.Return) {
                Cursor function = statement.dropParentUntil(value -> value instanceof J.MethodDeclaration
                        || value instanceof J.Lambda
                        || Cursor.ROOT_VALUE.equals(value));
                statement = function.getValue() instanceof J.Lambda ? blockStatement(function)
                        : function.getValue() instanceof J.MethodDeclaration ? function : null;
            }
            if (statement == null) {
                return;
            }
            J target = statement.getValue();
            Object blockOwner = statement.getParentTreeCursor().getParentTreeCursor().getValue();
            boolean classMember = blockOwner instanceof J.ClassDeclaration || blockOwner instanceof J.NewClass;
            boolean commentable = !classMember
                    || target instanceof J.MethodDeclaration
                    || target instanceof J.VariableDeclarations;
            (commentable ? commentOut : mark).add(target.getId());
        }
    }

    private static boolean isSerializerMethod(J.MethodDeclaration method) {
        return refersTo(method.getReturnTypeExpression(), SERIALIZER_TYPE_FQNS)
                && (isSpringBean(method) || (method.getBody() != null && containsSerializerBuild(method.getBody())));
    }

    private static boolean isSerializerBuild(J tree) {
        return switch (tree) {
            case J.MethodInvocation invocation -> FACTORY_METHODS.contains(invocation.getSimpleName())
                    && refersTo(invocation.getSelect(), SERIALIZER_IMPLEMENTATION_FQNS);
            case J.NewClass newClass -> refersTo(newClass.getClazz(), SERIALIZER_IMPLEMENTATION_FQNS);
            default -> false;
        };
    }

    private static boolean containsSerializerBuild(J tree) {
        return new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J preVisit(J nested, AtomicBoolean found) {
                if (isSerializerBuild(nested)) {
                    found.set(true);
                    stopAfterPreVisit();
                }
                return nested;
            }
        }.reduce(tree, new AtomicBoolean()).get();
    }

    /**
     * Returns the cursor of the block statement holding the element at the given cursor, or {@code null} when no block
     * holds it.
     */
    private static @Nullable Cursor blockStatement(Cursor cursor) {
        for (Cursor current = cursor; current != null; current = current.getParent()) {
            if (current.getValue() instanceof Statement
                    && current.getParentTreeCursor().getValue() instanceof J.Block) {
                return current;
            }
        }
        return null;
    }

    /**
     * Returns the given statement as line comments: the TODO marker, followed by the statement's source text, each
     * line after {@code //} on the statement's indent. The statement's own prefix comments stay above the marker. The
     * suffix of the last comment is the whitespace before whatever follows, set by {@link #followedBy(Space, Space)}.
     */
    private static Space commentedOut(JRightPadded<Statement> padded, Cursor cursor) {
        Space prefix = padded.getElement().getPrefix();
        String indent = StatementTodoMarker.indentOf(prefix);
        List<Comment> comments = new ArrayList<>(prefix.getComments());
        comments.add(new TextComment(false, COMMENTED_OUT_TODO, "\n" + indent, Markers.EMPTY));
        for (String line : source(padded, cursor).split("\n", -1)) {
            String code = line.startsWith(indent) ? line.substring(indent.length()) : line;
            comments.add(new TextComment(false, code.isBlank() ? "" : " " + code, "\n" + indent, Markers.EMPTY));
        }
        return Space.build(prefix.getWhitespace(), comments);
    }

    /**
     * Prints the given statement without its prefix, ending with the semicolon its block prints after it, if any. The
     * lines after the first keep their indent in the source file.
     */
    private static String source(JRightPadded<Statement> padded, Cursor cursor) {
        J.Block holder = new J.Block(Tree.randomId(), Space.EMPTY, Markers.EMPTY, JRightPadded.build(false),
                                     List.of(padded.withElement(padded.getElement().withPrefix(Space.EMPTY))),
                                     Space.EMPTY);
        String printed = holder.print(cursor);
        return printed.substring(1, printed.length() - 1);
    }

    /**
     * Returns the commented-out code followed by the given space, which preceded the element after that code.
     */
    private static Space followedBy(Space commentedOut, Space next) {
        List<Comment> comments = new ArrayList<>(commentedOut.getComments());
        comments.set(comments.size() - 1, comments.getLast().withSuffix(next.getWhitespace()));
        comments.addAll(next.getComments());
        return Space.build(commentedOut.getWhitespace(), comments);
    }

    /**
     * Returns the fully qualified names of the resolved types the given statement refers to, whose imports may be
     * unused once it is commented out.
     */
    private static Set<String> typesUsedBy(Statement statement) {
        return new JavaIsoVisitor<Set<String>>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Set<String> types) {
                String type = resolvedName(identifier.getType());
                if (type != null) {
                    types.add(type);
                }
                return super.visitIdentifier(identifier, types);
            }
        }.reduce(statement, new HashSet<>());
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
