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
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.Comment;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.axonframework.migration.SagaClasses.isAnnotation;
import static org.axonframework.migration.SagaClasses.resolvedName;

/**
 * Passes the aggregate scope explicitly to the {@code DeadlineManager} calls of an Axon Framework 4 aggregate that
 * relied on the scope of the running handler.
 * <p>
 * Axon Framework 4 ran every aggregate handler inside the aggregate's scope, so {@code schedule(trigger, name)},
 * {@code schedule(trigger, name, payload)} and {@code cancelAllWithinScope(name)} described the scope themselves, as
 * {@code new AggregateScopeDescriptor(type, identifier)}. The Axoniq Framework legacy module keeps these overloads,
 * but only a Saga handler runs inside a scope in Axon Framework 5: called from an entity handler they throw an
 * {@code IllegalStateException}. The recipe rewrites them to the overloads taking a {@code ScopeDescriptor}, passing
 * the descriptor Axon Framework 4 built:
 * <ul>
 *     <li>the type is the declared type of the class the aggregate's repository was configured for: the {@code type}
 *     of its {@code @Aggregate} or {@code @AggregateRoot} annotation, or its simple name when it declares none. Axon
 *     Framework 4 used that type for every subtype of a polymorphic aggregate. As the configuration is not always in
 *     the sources, the recipe approximates that class: the topmost class of the hierarchy that a configuration call
 *     in the sources registers ({@code configureAggregate(..)}, {@code AggregateConfigurer.defaultConfiguration(..)}
 *     or {@code jpaMappedConfiguration(..)}, or a repository {@code builder(..)}); else the topmost annotated class,
 *     which Spring registers as the root; else the class itself, unless it is abstract;</li>
 *     <li>the identifier is the member annotated with {@code @AggregateIdentifier}, or with {@code @EntityId} or a
 *     persistence {@code @Id} on an annotated aggregate.</li>
 * </ul>
 * Keeping Axon Framework 4's type matters beyond the call itself: {@code cancelAllWithinScope} only cancels deadlines
 * whose scope equals the given one, including deadlines a not yet upgraded node scheduled.
 * <p>
 * The identifier is read when the call is made. Axon Framework 4 resolved it lazily, when the unit of work prepared
 * its commit, but the legacy module runs a call made outside a Saga immediately, so a lazy identifier would be read
 * at the same moment.
 * <p>
 * Where the recipe cannot build the descriptor, it leaves a {@code // TODO(axon4to5):} marker on the statement: in
 * a constructor or static method, which has no aggregate instance to read the identifier from (a creation command
 * handler becomes a static method in Axon Framework 5), in a {@code @CreationPolicy} handler that may create the
 * aggregate, whose identifier is only set once it applied its first event, in a member entity of an aggregate, and
 * in an aggregate whose identifier or type the recipe cannot find. Calls with an explicit scope, {@code cancelSchedule} and
 * {@code cancelAll}, Sagas, and classes that are neither an aggregate nor one of its entities are left alone. The
 * recipe is idempotent.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public class AddAggregateScopeToDeadlineCalls extends ScanningRecipe<AddAggregateScopeToDeadlineCalls.Accumulator> {

    private static final String DEADLINE_MANAGER_FQN = "org.axonframework.deadline.DeadlineManager";
    private static final String SCOPE_DESCRIPTOR_FQN = "org.axonframework.messaging.ScopeDescriptor";
    private static final String AGGREGATE_SCOPE_DESCRIPTOR_FQN =
            "org.axonframework.modelling.command.AggregateScopeDescriptor";

    private static final String AGGREGATE_STEREOTYPE_FQN = "org.axonframework.spring.stereotype.Aggregate";
    private static final String AGGREGATE_ROOT_FQN = "org.axonframework.modelling.command.AggregateRoot";
    private static final String AGGREGATE_IDENTIFIER_FQN = "org.axonframework.modelling.command.AggregateIdentifier";
    private static final String ENTITY_ID_FQN = "org.axonframework.modelling.command.EntityId";
    private static final String AGGREGATE_MEMBER_FQN = "org.axonframework.modelling.command.AggregateMember";
    private static final String CREATION_POLICY_FQN = "org.axonframework.modelling.command.CreationPolicy";
    /**
     * The Axon Framework 4 methods taking the class an aggregate's repository is configured for: the aggregate
     * configuration factories, {@code Configurer#configureAggregate(Class)} and the repository builders.
     */
    private static final Set<String> ROOT_CONFIGURING_METHODS =
            Set.of("configureAggregate", "defaultConfiguration", "jpaMappedConfiguration", "builder");
    private static final Set<String> PERSISTENCE_ID_FQNS = Set.of("jakarta.persistence.Id", "javax.persistence.Id");
    private static final Set<String> AGGREGATE_HANDLER_FQNS = Set.of(
            "org.axonframework.commandhandling.CommandHandler",
            "org.axonframework.eventsourcing.EventSourcingHandler",
            "org.axonframework.deadline.annotation.DeadlineHandler",
            "org.axonframework.modelling.command.CommandHandlerInterceptor"
    );
    private static final Set<String> AGGREGATE_HANDLER_NAMES =
            Set.of("CommandHandler", "EventSourcingHandler", "DeadlineHandler", "CommandHandlerInterceptor");

    private static final String STUBS_SCOPE_DESCRIPTOR = """
            package org.axonframework.messaging;
            public interface ScopeDescriptor {
            }
            """;
    private static final String STUBS_AGGREGATE_SCOPE_DESCRIPTOR = """
            package org.axonframework.modelling.command;
            public class AggregateScopeDescriptor implements org.axonframework.messaging.ScopeDescriptor {
                public AggregateScopeDescriptor(String type, Object identifier) {
                }
            }
            """;
    private static final String STUBS_DEADLINE_MANAGER = """
            package org.axonframework.deadline;
            public interface DeadlineManager {
                String schedule(java.time.Instant triggerDateTime, String deadlineName, Object messageOrPayload,
                                org.axonframework.messaging.ScopeDescriptor deadlineScope);
                String schedule(java.time.Duration triggerDuration, String deadlineName, Object messageOrPayload,
                                org.axonframework.messaging.ScopeDescriptor deadlineScope);
                void cancelAllWithinScope(String deadlineName, org.axonframework.messaging.ScopeDescriptor scope);
            }
            """;

    private static final String MARKER = "TODO(axon4to5): pass the aggregate scope to this DeadlineManager call";
    private static final String NO_SCOPE = ". Axon Framework 5 runs no aggregate scope around an entity handler, so"
            + " this call fails without one";
    private static final String NO_INSTANCE_REASON = ", and this handler has no aggregate instance to read the"
            + " identifier from. Take it from the command.";
    private static final String CREATION_REASON = ", and this handler may create the aggregate, so its identifier"
            + " may not be set yet. Take it from the command.";
    private static final String MEMBER_REASON = ". Axon Framework 4 used the scope of the aggregate this entity"
            + " belongs to.";
    private static final String UNKNOWN_REASON = ", and the recipe found no aggregate identifier or type here.";
    private static final String UNKNOWN_TYPE = "<aggregate type>";
    private static final String MARKS_MESSAGE = "axon4to5.aggregateScopeMarks";

    /**
     * What the scanning phase learns about the source classes.
     */
    public static class Accumulator {

        final SagaClasses sagaClasses = new SagaClasses();
        final Map<String, ClassInfo> classes = new HashMap<>();
        final Map<String, String> memberEntityOwners = new HashMap<>();
        final Set<String> configuredRoots = new HashSet<>();
    }

    /**
     * The aggregate details of one source class.
     *
     * @param declaredType the type expression of its {@code @Aggregate} or {@code @AggregateRoot}, or {@code null}
     *                     when the class carries neither
     * @param identifier   its identifier member, or {@code null}
     * @param superclass   the fully qualified name of its superclass, or {@code null}
     * @param simpleName   its simple name
     * @param isAbstract   whether the class is abstract
     * @param hasHandlers  whether it declares an aggregate handler method
     */
    record ClassInfo(@Nullable String declaredType,
                     @Nullable Identifier identifier,
                     @Nullable String superclass,
                     String simpleName,
                     boolean isAbstract,
                     boolean hasHandlers) {

    }

    /**
     * The member holding an aggregate's identifier.
     *
     * @param name                the field or method name
     * @param method              whether it is a method
     * @param aggregateIdentifier whether it carries {@code @AggregateIdentifier}, which identifies the aggregate
     *                            itself, rather than {@code @EntityId} or a persistence {@code @Id}
     * @param isPrivate           whether it is private
     */
    record Identifier(String name, boolean method, boolean aggregateIdentifier, boolean isPrivate) {

    }

    @Override
    public String getDisplayName() {
        return "Pass the aggregate scope to `DeadlineManager` calls in aggregates";
    }

    @Override
    public String getDescription() {
        return "Axon Framework 4 ran aggregate handlers inside the aggregate's scope, which the `DeadlineManager` "
                + "overloads without a `ScopeDescriptor` used. Axon Framework 5 runs no scope around an entity "
                + "handler, so these calls fail. Rewrites them in aggregates to pass "
                + "`new AggregateScopeDescriptor(<type>, <identifier>)`, the scope Axon Framework 4 described. "
                + "Leaves a `// TODO(axon4to5):` marker where the identifier is not available: in constructors, "
                + "static methods, `@CreationPolicy` handlers that may create the aggregate, and member entities. "
                + "Idempotent.";
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new JavaIsoVisitor<>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDeclaration,
                                                             ExecutionContext ctx) {
                acc.sagaClasses.collect(classDeclaration);
                JavaType.FullyQualified type = classDeclaration.getType();
                if (type != null && !(type instanceof JavaType.Unknown)) {
                    JavaType.FullyQualified supertype = type.getSupertype();
                    acc.classes.put(type.getFullyQualifiedName(), new ClassInfo(
                            declaredType(classDeclaration, getCursor()),
                            identifier(classDeclaration),
                            supertype == null ? null : supertype.getFullyQualifiedName(),
                            classDeclaration.getSimpleName(),
                            classDeclaration.hasModifier(J.Modifier.Type.Abstract),
                            hasAggregateHandler(classDeclaration)
                    ));
                }
                collectMemberEntityTypes(classDeclaration, acc.memberEntityOwners);
                return super.visitClassDeclaration(classDeclaration, ctx);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation, ExecutionContext ctx) {
                String root = configuredRoot(invocation);
                if (root != null) {
                    acc.configuredRoots.add(root);
                }
                return super.visitMethodInvocation(invocation, ctx);
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new JavaIsoVisitor<>() {
            @Override
            public boolean isAcceptable(SourceFile sourceFile, ExecutionContext ctx) {
                return sourceFile instanceof J.CompilationUnit;
            }

            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block visited = super.visitBlock(block, ctx);
                Map<UUID, String> marks = getCursor().getMessage(MARKS_MESSAGE);
                if (marks == null) {
                    return visited;
                }
                return visited.withStatements(ListUtils.map(visited.getStatements(), statement -> {
                    String todo = marks.get(statement.getId());
                    return todo == null ? statement : mark(statement, todo);
                }));
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation, ExecutionContext ctx) {
                J.MethodInvocation visited = super.visitMethodInvocation(invocation, ctx);
                if (!isScopelessDeadlineCall(visited)) {
                    return visited;
                }
                J.ClassDeclaration enclosingClass = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (enclosingClass == null
                        || enclosingClass.getType() == null
                        || acc.sagaClasses.isSaga(enclosingClass)) {
                    return visited;
                }
                String className = enclosingClass.getType().getFullyQualifiedName();
                Aggregate aggregate = Aggregate.of(className, acc);
                if (!aggregate.recognised) {
                    return visited;
                }
                String type = aggregate.type;
                J.MethodDeclaration enclosingMethod = getCursor().firstEnclosing(J.MethodDeclaration.class);
                if (enclosingMethod == null
                        || enclosingMethod.isConstructor()
                        || enclosingMethod.hasModifier(J.Modifier.Type.Static)) {
                    return markStatement(visited, todo(type, NO_INSTANCE_REASON));
                }
                if (mayCreateAggregate(enclosingMethod)) {
                    return markStatement(visited, todo(type, CREATION_REASON));
                }
                String owner = acc.memberEntityOwners.get(className);
                if (owner != null) {
                    return markStatement(visited, todo(Aggregate.of(owner, acc).type, MEMBER_REASON));
                }
                if (type == null || aggregate.identifier == null) {
                    return markStatement(visited, todo(type, UNKNOWN_REASON));
                }
                String identifier = identifierExpression(aggregate.identifier, enclosingMethod);
                maybeAddImport(AGGREGATE_SCOPE_DESCRIPTOR_FQN, null, false);
                return withScope(visited, "new AggregateScopeDescriptor(" + type + ", " + identifier + ")");
            }

            /**
             * Records the marker on the statement holding the call, so it lands above that statement instead of
             * inside an expression.
             */
            private J.MethodInvocation markStatement(J.MethodInvocation invocation, String todo) {
                Iterator<Cursor> path = getCursor().getPathAsCursors();
                J child = invocation;
                while (path.hasNext()) {
                    Cursor cursor = path.next();
                    if (cursor.getValue() instanceof J.Block) {
                        cursor.<Map<UUID, String>>computeMessageIfAbsent(MARKS_MESSAGE, k -> new HashMap<>())
                              .put(child.getId(), todo);
                        return invocation;
                    }
                    if (cursor.getValue() instanceof J element) {
                        child = element;
                    }
                }
                return invocation;
            }

            private J.MethodInvocation withScope(J.MethodInvocation invocation, String scope) {
                List<Expression> arguments = invocation.getArguments();
                String select = "#{any(" + DEADLINE_MANAGER_FQN + ")}";
                String code;
                Object[] parameters;
                if ("cancelAllWithinScope".equals(invocation.getSimpleName())) {
                    code = select + ".cancelAllWithinScope(#{any(java.lang.String)}, " + scope + ")";
                    parameters = new Object[]{invocation.getSelect(), arguments.getFirst()};
                } else if (arguments.size() == 2) {
                    code = select + ".schedule(#{any()}, #{any(java.lang.String)}, null, " + scope + ")";
                    parameters = new Object[]{invocation.getSelect(), arguments.get(0), arguments.get(1)};
                } else {
                    code = select + ".schedule(#{any()}, #{any(java.lang.String)}, #{any()}, " + scope + ")";
                    parameters = new Object[]{
                            invocation.getSelect(), arguments.get(0), arguments.get(1), arguments.get(2)
                    };
                }
                return JavaTemplate.builder(code)
                                   .contextSensitive()
                                   .imports(AGGREGATE_SCOPE_DESCRIPTOR_FQN)
                                   .javaParser(JavaParser.fromJavaVersion()
                                                         .classpath(JavaParser.runtimeClasspath())
                                                         .dependsOn(STUBS_SCOPE_DESCRIPTOR,
                                                                    STUBS_AGGREGATE_SCOPE_DESCRIPTOR,
                                                                    STUBS_DEADLINE_MANAGER))
                                   .build()
                                   .apply(getCursor(), invocation.getCoordinates().replace(), parameters);
            }
        };
    }

    /**
     * Returns whether the call is a {@code DeadlineManager} call that relies on the current scope: a
     * {@code schedule} or {@code cancelAllWithinScope} overload without a {@code ScopeDescriptor} parameter.
     */
    private static boolean isScopelessDeadlineCall(J.MethodInvocation invocation) {
        String name = invocation.getSimpleName();
        if (!"schedule".equals(name) && !"cancelAllWithinScope".equals(name) || invocation.getSelect() == null) {
            return false;
        }
        JavaType.Method method = invocation.getMethodType();
        if (method == null || !TypeUtils.isAssignableTo(DEADLINE_MANAGER_FQN, method.getDeclaringType())) {
            return false;
        }
        boolean scoped = method.getParameterTypes().stream()
                               .anyMatch(parameter -> TypeUtils.isAssignableTo(SCOPE_DESCRIPTOR_FQN, parameter));
        int arguments = (int) invocation.getArguments().stream().filter(arg -> !(arg instanceof J.Empty)).count();
        return !scoped && ("schedule".equals(name) ? arguments == 2 || arguments == 3 : arguments == 1);
    }

    /**
     * Returns whether the method is a {@code @CreationPolicy} handler that may run on a new aggregate instance, whose
     * identifier is only set once the handler applied its first event. Axon Framework 4 described the scope with a
     * lazily resolved identifier and ran the {@code DeadlineManager} call when the unit of work prepared its commit,
     * after that event. The legacy module runs a call made outside a Saga immediately, so the identifier would be
     * read, and stored with the deadline, before it is set.
     */
    private static boolean mayCreateAggregate(J.MethodDeclaration method) {
        return method.getLeadingAnnotations().stream()
                     .filter(annotation -> isAnnotation(annotation, CREATION_POLICY_FQN, "CreationPolicy"))
                     .anyMatch(annotation -> {
                         List<Expression> arguments = annotation.getArguments();
                         if (arguments == null || arguments.isEmpty() || arguments.getFirst() instanceof J.Empty) {
                             // NEVER is the default policy.
                             return false;
                         }
                         Expression policy = arguments.getFirst() instanceof J.Assignment assignment
                                 ? assignment.getAssignment()
                                 : arguments.getFirst();
                         String name = policy instanceof J.FieldAccess fieldAccess
                                 ? fieldAccess.getSimpleName()
                                 : policy instanceof J.Identifier identifier ? identifier.getSimpleName() : null;
                         return !"NEVER".equals(name);
                     });
    }

    private static String todo(@Nullable String type, String reason) {
        return " " + MARKER + ", as in new AggregateScopeDescriptor(" + (type == null ? UNKNOWN_TYPE : type)
                + ", <aggregate identifier>)" + NO_SCOPE + reason;
    }

    /**
     * Returns the expression reading the identifier, qualified with {@code this} when the method declares a variable
     * of the same name.
     */
    private static String identifierExpression(Identifier identifier, J.MethodDeclaration method) {
        if (identifier.method()) {
            return identifier.name() + "()";
        }
        Set<String> declared = new HashSet<>();
        new JavaIsoVisitor<Set<String>>() {
            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable variable,
                                                                      Set<String> names) {
                names.add(variable.getSimpleName());
                return super.visitVariable(variable, names);
            }
        }.visit(method, declared);
        return declared.contains(identifier.name()) ? "this." + identifier.name() : identifier.name();
    }

    /**
     * Returns the aggregate class the call configures a repository for, when it is an Axon Framework 4 configuration
     * call taking that class as a class literal, or {@code null} otherwise.
     */
    private static @Nullable String configuredRoot(J.MethodInvocation invocation) {
        JavaType.Method method = invocation.getMethodType();
        if (!ROOT_CONFIGURING_METHODS.contains(invocation.getSimpleName())
                || method == null
                || !method.getDeclaringType().getFullyQualifiedName().startsWith("org.axonframework.")
                || invocation.getArguments().isEmpty()
                || !(invocation.getArguments().getFirst() instanceof J.FieldAccess classLiteral)
                || !"class".equals(classLiteral.getSimpleName())) {
            return null;
        }
        return resolvedName(classLiteral.getTarget().getType());
    }

    private static @Nullable String declaredType(J.ClassDeclaration classDeclaration, Cursor cursor) {
        for (J.Annotation annotation : classDeclaration.getLeadingAnnotations()) {
            if (isAnnotation(annotation, AGGREGATE_STEREOTYPE_FQN, "Aggregate")
                    || isAnnotation(annotation, AGGREGATE_ROOT_FQN, "AggregateRoot")) {
                Expression type = typeAttribute(annotation);
                if (type == null || type instanceof J.Literal literal && "".equals(literal.getValue())) {
                    return quoted(classDeclaration.getSimpleName());
                }
                return type instanceof J.Literal literal && literal.getValue() instanceof String value
                        ? quoted(value)
                        : type.printTrimmed(cursor);
            }
        }
        return null;
    }

    private static @Nullable Expression typeAttribute(J.Annotation annotation) {
        if (annotation.getArguments() == null) {
            return null;
        }
        for (Expression argument : annotation.getArguments()) {
            if (argument instanceof J.Assignment assignment
                    && assignment.getVariable() instanceof J.Identifier identifier
                    && "type".equals(identifier.getSimpleName())) {
                return assignment.getAssignment();
            }
        }
        return null;
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Finds the identifier member the way Axon Framework 4 did: a member annotated with {@code @EntityId}, which
     * {@code @AggregateIdentifier} is meta-annotated with, or else one annotated with a persistence {@code @Id}.
     */
    private static @Nullable Identifier identifier(J.ClassDeclaration classDeclaration) {
        Identifier persistenceId = null;
        for (Statement statement : classDeclaration.getBody().getStatements()) {
            List<J.Annotation> annotations;
            String name;
            boolean method;
            boolean isPrivate;
            if (statement instanceof J.VariableDeclarations field && field.getVariables().size() == 1) {
                annotations = field.getLeadingAnnotations();
                name = field.getVariables().getFirst().getSimpleName();
                method = false;
                isPrivate = field.hasModifier(J.Modifier.Type.Private);
            } else if (statement instanceof J.MethodDeclaration declaration
                    && declaration.getParameters().stream().allMatch(J.Empty.class::isInstance)) {
                annotations = declaration.getLeadingAnnotations();
                name = declaration.getSimpleName();
                method = true;
                isPrivate = declaration.hasModifier(J.Modifier.Type.Private);
            } else {
                continue;
            }
            for (J.Annotation annotation : annotations) {
                if (isAnnotation(annotation, AGGREGATE_IDENTIFIER_FQN, "AggregateIdentifier")) {
                    return new Identifier(name, method, true, isPrivate);
                }
                if (isAnnotation(annotation, ENTITY_ID_FQN, "EntityId")) {
                    return new Identifier(name, method, false, isPrivate);
                }
                String type = resolvedName(annotation.getType());
                if (persistenceId == null && type != null && PERSISTENCE_ID_FQNS.contains(type)) {
                    persistenceId = new Identifier(name, method, false, isPrivate);
                }
            }
        }
        return persistenceId;
    }

    private static boolean hasAggregateHandler(J.ClassDeclaration classDeclaration) {
        return classDeclaration.getBody().getStatements().stream()
                               .filter(J.MethodDeclaration.class::isInstance)
                               .map(J.MethodDeclaration.class::cast)
                               .flatMap(method -> method.getLeadingAnnotations().stream())
                               .anyMatch(annotation -> {
                                   String type = resolvedName(annotation.getType());
                                   return type != null
                                           ? AGGREGATE_HANDLER_FQNS.contains(type)
                                           : AGGREGATE_HANDLER_NAMES.contains(annotation.getSimpleName());
                               });
    }

    /**
     * Records the entity types of the {@code @AggregateMember} fields with the class declaring them: the field type
     * itself, the element type of a collection, or the value type of a map.
     */
    private static void collectMemberEntityTypes(J.ClassDeclaration classDeclaration,
                                                 Map<String, String> memberEntityOwners) {
        JavaType.FullyQualified owner = classDeclaration.getType();
        if (owner == null) {
            return;
        }
        for (Statement statement : classDeclaration.getBody().getStatements()) {
            if (!(statement instanceof J.VariableDeclarations field)
                    || field.getLeadingAnnotations().stream()
                            .noneMatch(annotation -> isAnnotation(annotation,
                                                                  AGGREGATE_MEMBER_FQN,
                                                                  "AggregateMember"))) {
                continue;
            }
            JavaType type = field.getType();
            if (type instanceof JavaType.Parameterized parameterized
                    && !parameterized.getTypeParameters().isEmpty()) {
                type = parameterized.getTypeParameters().getLast();
            }
            String name = resolvedName(type);
            if (name != null) {
                memberEntityOwners.put(name, owner.getFullyQualifiedName());
            }
        }
    }

    /**
     * Prepends the TODO line comment to the element's prefix, keeping the element on its original indent below it.
     */
    private static <T extends J> T mark(T element, String todo) {
        Space prefix = element.getPrefix();
        for (Comment comment : prefix.getComments()) {
            if (comment instanceof TextComment text && text.getText().contains(MARKER)) {
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

    /**
     * The aggregate a class belongs to, resolved over its source superclasses.
     *
     * @param recognised whether the class is part of an aggregate: it, or a superclass, is an annotated aggregate,
     *                   declares an identifier or an aggregate handler, or it is a member entity
     * @param type       the aggregate type expression Axon Framework 4 described its scope with, or {@code null}
     *                   when unknown
     * @param identifier the identifier member reachable from the class, or {@code null}
     */
    private record Aggregate(boolean recognised, @Nullable String type, @Nullable Identifier identifier) {

        static Aggregate of(String className, Accumulator acc) {
            ClassInfo self = acc.classes.get(className);
            if (self == null) {
                return new Aggregate(false, null, null);
            }
            boolean recognised = acc.memberEntityOwners.containsKey(className);
            String declaredType = null;
            String configuredType = null;
            Identifier identifier = null;
            boolean annotated = false;
            Set<String> visited = new HashSet<>();
            ClassInfo current = self;
            String currentName = className;
            while (current != null && visited.add(currentName)) {
                recognised |= current.declaredType() != null || current.identifier() != null || current.hasHandlers();
                if (current.declaredType() != null) {
                    // Spring registers the topmost annotated class as the root of a polymorphic aggregate.
                    declaredType = current.declaredType();
                    annotated = true;
                }
                if (acc.configuredRoots.contains(currentName)) {
                    // A configuration naming the root explicitly decides over the annotations.
                    configuredType = current.declaredType() != null
                            ? current.declaredType()
                            : quoted(current.simpleName());
                }
                if (identifier == null && current.identifier() != null
                        && (current == self || !current.identifier().isPrivate())) {
                    identifier = current.identifier();
                }
                currentName = current.superclass();
                current = currentName == null ? null : acc.classes.get(currentName);
            }
            if (identifier != null && !identifier.aggregateIdentifier() && !annotated) {
                // An @EntityId or @Id outside an annotated aggregate most likely identifies a member entity.
                identifier = null;
            }
            String type = configuredType != null
                    ? configuredType
                    : declaredType != null ? declaredType : self.isAbstract() ? null : quoted(self.simpleName());
            return new Aggregate(recognised, type, identifier);
        }
    }
}
