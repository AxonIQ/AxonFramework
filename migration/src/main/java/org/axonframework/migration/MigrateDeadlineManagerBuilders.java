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
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Comment;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Migrates the builder calls of the Axon Framework 4 deadline managers to the builders of the Axoniq Framework legacy
 * module.
 * <p>
 * The legacy {@code SimpleDeadlineManager}, {@code QuartzDeadlineManager}, {@code JobRunrDeadlineManager} and
 * {@code DbSchedulerDeadlineManager} keep their Axon Framework 4 builders, except for three parameters that have no
 * Axon Framework 5 counterpart:
 * <ul>
 *     <li>{@code spanFactory(..)} is removed, as tracing comes from a decorator around every configured
 *     {@code DeadlineManager};</li>
 *     <li>{@code transactionManager(..)} is removed, as a fired deadline runs in a unit of work from the required
 *     {@code unitOfWorkFactory(..)}, which provides the transaction;</li>
 *     <li>{@code serializer(..)} is replaced by the required {@code converter(..)} on the three persistent
 *     managers.</li>
 * </ul>
 * The recipe removes the first two calls, and replaces {@code serializer(..)} with {@code converter(..)} when the
 * serializer is a default {@code JacksonSerializer}, whose stored deadlines a default {@code JacksonConverter} reads.
 * Which {@code UnitOfWorkFactory} to use, and which converter reads what any other serializer stored, cannot be
 * inferred at the call site, so the recipe leaves a {@code // TODO(axon4to5):} marker on the statement that builds
 * the manager for those. Builder calls of other components with the same method names are left alone. The recipe is
 * idempotent: a statement that already carries a marker is not marked again.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public class MigrateDeadlineManagerBuilders extends Recipe {

    private static final String SIMPLE_DEADLINE_MANAGER = "SimpleDeadlineManager";
    private static final String QUARTZ_DEADLINE_MANAGER = "QuartzDeadlineManager";
    private static final Map<String, String> DEADLINE_MANAGER_FQNS = Map.of(
            SIMPLE_DEADLINE_MANAGER, "org.axonframework.deadline.SimpleDeadlineManager",
            QUARTZ_DEADLINE_MANAGER, "org.axonframework.deadline.quartz.QuartzDeadlineManager",
            "JobRunrDeadlineManager", "org.axonframework.deadline.jobrunr.JobRunrDeadlineManager",
            "DbSchedulerDeadlineManager", "org.axonframework.deadline.dbscheduler.DbSchedulerDeadlineManager"
    );
    private static final Set<String> REMOVED_METHODS = Set.of("spanFactory", "transactionManager");
    private static final String SERIALIZER_METHOD = "serializer";
    private static final String CONVERTER_METHOD = "converter";
    private static final String UNIT_OF_WORK_FACTORY_METHOD = "unitOfWorkFactory";

    private static final String JACKSON_SERIALIZER_FQN = "org.axonframework.serialization.json.JacksonSerializer";
    private static final String JACKSON_CONVERTER_FQN = "org.axonframework.conversion.jackson.JacksonConverter";

    private static final String UNIT_OF_WORK_FACTORY_MARKER = "TODO(axon4to5): add unitOfWorkFactory(";
    private static final String UNIT_OF_WORK_FACTORY_TODO = " " + UNIT_OF_WORK_FACTORY_MARKER
            + "..) to this deadline manager builder, for example configuration.getComponent(UnitOfWorkFactory.class)"
            + " or the UnitOfWorkFactory Spring bean. The legacy deadline managers require it in place of"
            + " transactionManager(..): a fired deadline runs in a unit of work, and transaction, of that factory.";
    private static final String SERIALIZER_MARKER = "TODO(axon4to5): replace serializer(..) with converter(";
    private static final String SERIALIZER_TODO = " " + SERIALIZER_MARKER
            + "..), passing the Converter matching the Axon Framework 4 serializer, so stored deadlines stay"
            + " readable: general serializer -> GeneralConverter, message serializer -> MessageConverter, event"
            + " serializer -> EventConverter, a JacksonSerializer with a customized ObjectMapper -> a JacksonConverter"
            + " configured the same way, XStreamSerializer -> XStreamConverter allowing your payload and scope types.";
    private static final String CONVERTER_MARKER = "TODO(axon4to5): add converter(";
    private static final String QUARTZ_CONVERTER_TODO = " " + CONVERTER_MARKER
            + "..) to this deadline manager builder. Axon Framework 4 defaulted the Quartz deadline manager to an"
            + " XStreamSerializer; pass an XStreamConverter allowing your payload and scope types to read the jobs it"
            + " stored.";
    private static final String CONVERTER_TODO = " " + CONVERTER_MARKER
            + "..) to this deadline manager builder, passing the Converter that reads the stored deadlines.";

    @Override
    public String getDisplayName() {
        return "Migrate legacy deadline manager builders";
    }

    @Override
    public String getDescription() {
        return "Removes the `spanFactory(..)` and `transactionManager(..)` calls from the builders of the Axon "
                + "Framework 4 deadline managers, and replaces `serializer(JacksonSerializer.defaultSerializer())` "
                + "with `converter(new JacksonConverter())`. Leaves a `// TODO(axon4to5):` marker for the required "
                + "`unitOfWorkFactory(..)`, and for a `converter(..)` it cannot infer. Idempotent.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<ExecutionContext>() {
            @Override
            public @Nullable J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J visited = super.visitMethodInvocation(method, ctx);
                if (!(visited instanceof J.MethodInvocation invocation)) {
                    return visited;
                }
                String name = invocation.getSimpleName();
                boolean removed = REMOVED_METHODS.contains(name);
                if (!removed && !SERIALIZER_METHOD.equals(name)) {
                    return invocation;
                }
                String manager = deadlineManagerOf(invocation);
                if (manager == null) {
                    return invocation;
                }
                if (removed) {
                    return removeCall(invocation);
                }
                return migrateSerializer(invocation, manager);
            }

            @Override
            public J visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block visited = (J.Block) super.visitBlock(block, ctx);
                return visited.withStatements(ListUtils.map(visited.getStatements(),
                                                            MigrateDeadlineManagerBuilders::markBuilders));
            }

            private @Nullable J removeCall(J.MethodInvocation invocation) {
                if (getCursor().getParentTreeCursor().getValue() instanceof J.Block) {
                    // A call on a builder held in a variable is a statement of its own.
                    return null;
                }
                Expression select = invocation.getSelect();
                return select == null ? invocation : select.withPrefix(invocation.getPrefix());
            }

            private J.MethodInvocation migrateSerializer(J.MethodInvocation invocation, String manager) {
                if (SIMPLE_DEADLINE_MANAGER.equals(manager) || invocation.getArguments().size() != 1) {
                    return invocation;
                }
                Expression serializer = invocation.getArguments().getFirst();
                if (!isDefaultJacksonSerializer(serializer)) {
                    return invocation;
                }
                Expression converter = JavaTemplate.builder("new JacksonConverter()")
                                                   .imports(JACKSON_CONVERTER_FQN)
                                                   .javaParser(JavaParser.fromJavaVersion()
                                                                         .classpath(JavaParser.runtimeClasspath()))
                                                   .build()
                                                   .apply(new Cursor(getCursor(), serializer),
                                                          serializer.getCoordinates().replace());
                maybeAddImport(JACKSON_CONVERTER_FQN, null, false);
                maybeRemoveImport(JACKSON_SERIALIZER_FQN);
                return invocation.withName(invocation.getName().withSimpleName(CONVERTER_METHOD))
                                 .withArguments(List.of(converter.withPrefix(serializer.getPrefix())));
            }
        };
    }

    /**
     * Returns the simple name of the deadline manager whose builder the given call is made on, or {@code null} when
     * it is not a deadline manager builder call. Without Quartz, JobRunr or db-scheduler on the classpath, a call
     * after one taking their types does not resolve, so the chain is followed down to {@code <Manager>.builder()}.
     */
    private static @Nullable String deadlineManagerOf(J.MethodInvocation invocation) {
        JavaType.Method methodType = invocation.getMethodType();
        if (methodType != null) {
            String manager = managerOfBuilderType(methodType.getDeclaringType().getFullyQualifiedName());
            if (manager != null) {
                return manager;
            }
        }
        String manager = rootDeadlineManager(invocation);
        if (manager != null) {
            return manager;
        }
        Expression receiver = invocation.getSelect();
        while (receiver instanceof J.MethodInvocation chained) {
            receiver = chained.getSelect();
        }
        String receiverType = receiver == null ? null : resolvedName(receiver.getType());
        return receiverType == null ? null : managerOfBuilderType(receiverType);
    }

    /**
     * Returns the simple name of the deadline manager when the call chain of the given invocation starts with
     * {@code <Manager>.builder()}, or {@code null} otherwise.
     */
    private static @Nullable String rootDeadlineManager(J.MethodInvocation invocation) {
        Expression current = invocation;
        while (current instanceof J.MethodInvocation chained) {
            if ("builder".equals(chained.getSimpleName()) && chained.getSelect() != null) {
                String manager = managerOfTypeReference(chained.getSelect());
                if (manager != null) {
                    return manager;
                }
            }
            current = chained.getSelect();
        }
        return null;
    }

    private static @Nullable String managerOfBuilderType(String fullyQualifiedName) {
        String normalized = fullyQualifiedName.replace('$', '.');
        for (Map.Entry<String, String> manager : DEADLINE_MANAGER_FQNS.entrySet()) {
            if (normalized.equals(manager.getValue() + ".Builder")) {
                return manager.getKey();
            }
        }
        return null;
    }

    private static @Nullable String managerOfTypeReference(Expression typeReference) {
        String type = resolvedName(typeReference.getType());
        if (type != null) {
            for (Map.Entry<String, String> manager : DEADLINE_MANAGER_FQNS.entrySet()) {
                if (type.equals(manager.getValue())) {
                    return manager.getKey();
                }
            }
            return null;
        }
        String simpleName = typeReference instanceof J.Identifier identifier ? identifier.getSimpleName()
                : typeReference instanceof J.FieldAccess fieldAccess ? fieldAccess.getSimpleName() : null;
        return simpleName != null && DEADLINE_MANAGER_FQNS.containsKey(simpleName) ? simpleName : null;
    }

    /**
     * Matches {@code JacksonSerializer.defaultSerializer()} and {@code JacksonSerializer.builder().build()}: a
     * {@code JacksonSerializer} with its default {@code ObjectMapper}, whose stored deadlines a default
     * {@code JacksonConverter} reads.
     */
    private static boolean isDefaultJacksonSerializer(Expression serializer) {
        if (!(serializer instanceof J.MethodInvocation invocation) || hasArguments(invocation)) {
            return false;
        }
        if ("defaultSerializer".equals(invocation.getSimpleName())) {
            return isJacksonSerializerType(invocation.getSelect());
        }
        return "build".equals(invocation.getSimpleName())
                && invocation.getSelect() instanceof J.MethodInvocation builder
                && "builder".equals(builder.getSimpleName())
                && !hasArguments(builder)
                && isJacksonSerializerType(builder.getSelect());
    }

    private static boolean hasArguments(J.MethodInvocation invocation) {
        return invocation.getArguments().stream().anyMatch(argument -> !(argument instanceof J.Empty));
    }

    private static boolean isJacksonSerializerType(@Nullable Expression typeReference) {
        if (typeReference == null) {
            return false;
        }
        String type = resolvedName(typeReference.getType());
        if (type != null) {
            return JACKSON_SERIALIZER_FQN.equals(type);
        }
        return typeReference instanceof J.Identifier identifier
                ? "JacksonSerializer".equals(identifier.getSimpleName())
                : typeReference instanceof J.FieldAccess fieldAccess
                && "JacksonSerializer".equals(fieldAccess.getSimpleName());
    }

    /**
     * Marks the statement for each deadline manager builder chain in it, not looking into nested blocks, which mark
     * their own statements.
     */
    private static Statement markBuilders(Statement statement) {
        Set<String> markers = new HashSet<>();
        Statement[] marked = {statement};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Block visitBlock(J.Block block, Integer p) {
                return block;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation, Integer p) {
                String manager = rootDeadlineManager(invocation);
                if (manager == null) {
                    if (SERIALIZER_METHOD.equals(invocation.getSimpleName())
                            && deadlineManagerOf(invocation) != null
                            && markers.add(SERIALIZER_MARKER)) {
                        // A serializer(..) call on a builder held in a variable.
                        marked[0] = mark(marked[0], SERIALIZER_MARKER, SERIALIZER_TODO);
                    }
                    return super.visitMethodInvocation(invocation, p);
                }
                Set<String> calls = chainedMethodNames(invocation);
                if (!calls.contains(UNIT_OF_WORK_FACTORY_METHOD) && markers.add(UNIT_OF_WORK_FACTORY_MARKER)) {
                    marked[0] = mark(marked[0], UNIT_OF_WORK_FACTORY_MARKER, UNIT_OF_WORK_FACTORY_TODO);
                }
                if (calls.size() == 1) {
                    // A builder held in a variable gets its converter in a later statement.
                    return invocation;
                }
                if (!SIMPLE_DEADLINE_MANAGER.equals(manager) && !calls.contains(CONVERTER_METHOD)) {
                    if (calls.contains(SERIALIZER_METHOD)) {
                        if (markers.add(SERIALIZER_MARKER)) {
                            marked[0] = mark(marked[0], SERIALIZER_MARKER, SERIALIZER_TODO);
                        }
                    } else if (markers.add(CONVERTER_MARKER)) {
                        marked[0] = mark(marked[0],
                                         CONVERTER_MARKER,
                                         QUARTZ_DEADLINE_MANAGER.equals(manager)
                                                 ? QUARTZ_CONVERTER_TODO
                                                 : CONVERTER_TODO);
                    }
                }
                return invocation;
            }
        }.visit(statement, 0);
        return marked[0];
    }

    private static Set<String> chainedMethodNames(J.MethodInvocation invocation) {
        Set<String> names = new HashSet<>();
        Expression current = invocation;
        while (current instanceof J.MethodInvocation chained) {
            names.add(chained.getSimpleName());
            current = chained.getSelect();
        }
        return names;
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

    /**
     * Prepends the TODO line comment to the element's prefix, keeping the element on its original indent below it.
     */
    private static <T extends J> T mark(T element, String marker, String todo) {
        Space prefix = element.getPrefix();
        for (Comment comment : prefix.getComments()) {
            if (comment instanceof TextComment text && text.getText().contains(marker)) {
                return element;
            }
        }
        String leading = prefix.getComments().isEmpty()
                ? prefix.getWhitespace()
                : prefix.getComments().getLast().getSuffix();
        String indent = leading.contains("\n") ? leading.substring(leading.lastIndexOf('\n') + 1) : "";
        TextComment comment = new TextComment(false, todo, "\n" + indent, Markers.EMPTY);
        return element.withPrefix(prefix.withComments(ListUtils.concat(prefix.getComments(), comment)));
    }
}
