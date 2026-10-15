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
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Comment;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.Flag;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JLeftPadded;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.axonframework.migration.SagaClasses.isAnnotation;
import static org.axonframework.migration.SagaClasses.resolvedName;

/**
 * Replaces the Axon Framework 4 {@code @DeadlineHandler} of an aggregate with the Axon Framework 4
 * {@code @CommandHandler} that receives the deadline once it fires.
 * <p>
 * On the Axoniq Framework legacy module, a deadline scheduled for an aggregate scope fires as a command named
 * {@code <aggregateType>.<deadlineName>}, carrying the deadline's payload, or the deadline name when it has none. The
 * aggregate type is the one Axon Framework 4 stored with the deadline: the {@code type} of the aggregate's
 * {@code @AggregateRoot} or {@code @Aggregate} annotation, or its simple class name otherwise. The recipe replaces
 * a handler with a {@code deadlineName} with {@code @CommandHandler(commandName = "<aggregateType>.<deadlineName>")},
 * keeping its parameters and any {@code payloadType}. A handler without a payload keeps receiving the deadline
 * without one, whether or not the deadline carries a payload, as the command name alone routes it. A deadline name
 * given as a constant is resolved over all sources.
 * <p>
 * A handler without a {@code deadlineName} received every deadline with its payload type in Axon Framework 4. The
 * recipe replaces it with a {@code @CommandHandler} without a {@code commandName}, which receives the command named
 * after that payload type. The legacy module dispatches a deadline under that name when no handler receives the
 * command named after the deadline, logging a warning, so the handler keeps receiving every deadline with its payload
 * type. A {@code // TODO(axon4to5):} marker asks to name the command after the deadline instead.
 * <p>
 * The recipe uses the Axon Framework 4 {@code @CommandHandler} on purpose: the Axon Framework 5 upgrade that runs
 * afterwards moves it to the Axon Framework 5 package and migrates the handler like any other aggregate command
 * handler. The deadline payload class is left untouched, since the target entity is resolved from the deadline's
 * aggregate scope.
 * <p>
 * Where the recipe cannot name the command, it leaves a {@code // TODO(axon4to5):} marker and keeps the
 * {@code @DeadlineHandler}:
 * <ul>
 *     <li>a handler that names neither a deadline nor a payload type handled every deadline of the aggregate, and a
 *     handler without a deadline name whose payload type is {@code Object}, an interface or an abstract class
 *     handled every deadline with a payload of that type;</li>
 *     <li>a deadline name or aggregate type given as an expression that is no literal or resolvable constant;</li>
 *     <li>a handler in a subclass of an annotated aggregate class: Axon Framework 4 stored the type of the
 *     polymorphic aggregate's root, which the subclass does not declare;</li>
 *     <li>a handler of a member entity: Axon Framework 4 delivered a fired deadline to the aggregate root and to every
 *     member entity, while a command reaches an entity in a collection only through a routing key.</li>
 * </ul>
 * It also marks two handlers of one class that receive the same command, which Axon Framework 4 told apart by payload
 * type, and a {@code DeadlineMessage} parameter, which does not match the command message that now arrives.
 * <p>
 * The recipe rewrites Java and Kotlin aggregates. Saga classes, which keep their {@code @DeadlineHandler} on the
 * legacy module, are left alone. The recipe is idempotent.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public class MigrateAggregateDeadlineHandler extends ScanningRecipe<MigrateAggregateDeadlineHandler.Accumulator> {

    private static final String DEADLINE_HANDLER_FQN = "org.axonframework.deadline.annotation.DeadlineHandler";
    private static final String COMMAND_HANDLER_AF4_FQN = "org.axonframework.commandhandling.CommandHandler";
    private static final String AGGREGATE_ROOT_FQN = "org.axonframework.modelling.command.AggregateRoot";
    private static final String SPRING_AGGREGATE_FQN = "org.axonframework.spring.stereotype.Aggregate";
    private static final Set<String> AGGREGATE_ANNOTATION_FQNS = Set.of(AGGREGATE_ROOT_FQN, SPRING_AGGREGATE_FQN);
    private static final Set<String> DEADLINE_MESSAGE_FQNS = Set.of(
            "org.axonframework.deadline.DeadlineMessage",
            "org.axonframework.deadline.GenericDeadlineMessage"
    );
    private static final Set<String> DEADLINE_MESSAGE_NAMES = Set.of("DeadlineMessage", "GenericDeadlineMessage");
    /**
     * Simple names of the Axon Framework 4 types a handler parameter resolves to other than the payload, used only
     * when a parameter type could not be resolved.
     */
    private static final Set<String> NON_PAYLOAD_TYPE_NAMES = Set.of(
            "DeadlineMessage", "GenericDeadlineMessage", "Message", "MetaData", "ScopeDescriptor", "UnitOfWork",
            "DeadlineManager", "CommandGateway", "EventGateway", "QueryGateway", "CommandBus", "EventBus", "QueryBus"
    );
    private static final String OBJECT_FQN = "java.lang.Object";
    private static final String KOTLIN_ANY_FQN = "kotlin.Any";

    private static final String REPLACE_MARKER = "TODO(axon4to5): replace this @DeadlineHandler with a @CommandHandler";
    private static final String COMMAND_NAME = "(commandName = \"<aggregate type>.<deadline name>\")";
    private static final String FIRED_AS_COMMAND = " A fired aggregate deadline now arrives as a command named"
            + " <aggregate type>.<deadline name>, with the aggregate type Axon Framework 4 stored: the type of its"
            + " @AggregateRoot or @Aggregate annotation, or its simple class name.";
    private static final String MATCHES_ALL_TODO = " " + REPLACE_MARKER + COMMAND_NAME + " per deadline it handles."
            + " It names neither a deadline nor a payload type, so it handled every deadline of the aggregate."
            + FIRED_AS_COMMAND;
    private static final String GENERIC_PAYLOAD_TODO = " " + REPLACE_MARKER + COMMAND_NAME
            + " per deadline it handles. It names no deadline, and its payload type is no concrete class, so it handled"
            + " every deadline with a payload of that type." + FIRED_AS_COMMAND;
    private static final String NAME_COMMAND_MARKER =
            "TODO(axon4to5): the original @DeadlineHandler did not name the deadline explicitly.";
    private static final String NAME_COMMAND_TODO = " " + NAME_COMMAND_MARKER
            + " Name the deadline this @CommandHandler handles with a @CommandHandler" + COMMAND_NAME
            + " after the name this deadline was scheduled with. Until you name the deadline, the legacy deadline"
            + " support will fire the deadline named after its payload type with a warning.";
    private static final String UNRESOLVED_NAME_TODO = " " + REPLACE_MARKER + COMMAND_NAME + "."
            + " The recipe could not resolve its deadline name or aggregate type." + FIRED_AS_COMMAND;
    private static final String POLYMORPHIC_TODO = " " + REPLACE_MARKER + COMMAND_NAME + ", with the aggregate type"
            + " of the root of this polymorphic aggregate, which Axon Framework 4 stored with the deadline."
            + FIRED_AS_COMMAND;
    private static final String SHARED_COMMAND_MARKER =
            "TODO(axon4to5): merge the handlers of this deadline into one";
    private static final String SHARED_COMMAND_TODO = " " + SHARED_COMMAND_MARKER + " handler."
            + " Another handler of this class receives the same deadline under the same command name, but you can only"
            + " declare one handler per command name, so the application will fail to start until they are merged.";
    private static final String MEMBER_TODO = " " + REPLACE_MARKER + " on the aggregate root, or one this entity"
            + " receives through its routing key. Axon Framework 4 delivered a fired deadline to the aggregate root and"
            + " to every member entity, while the command it now arrives as reaches an entity in a collection only"
            + " through a routing key.";
    private static final String DEADLINE_MESSAGE_MARKER = "TODO(axon4to5): replace the DeadlineMessage parameter";
    private static final String DEADLINE_MESSAGE_TODO = " " + DEADLINE_MESSAGE_MARKER
            + " with the payload or metadata this handler needs."
            + " A fired aggregate deadline now arrives as a command message,"
            + " which the DeadlineMessage parameter does not match.";

    @Override
    public String getDisplayName() {
        return "Replace aggregate `@DeadlineHandler` methods with `@CommandHandler` methods";
    }

    @Override
    public String getDescription() {
        return "On the Axoniq Framework legacy module, a deadline scheduled for an aggregate fires as a command "
                + "named `<aggregateType>.<deadlineName>`. Replaces the `@DeadlineHandler` of an aggregate with the "
                + "Axon Framework 4 `@CommandHandler(commandName = \"<aggregateType>.<deadlineName>\")` that receives "
                + "that command, with the aggregate type of its `@AggregateRoot` or `@Aggregate` annotation, or its "
                + "simple class name. A handler without a deadline name becomes a `@CommandHandler` without a "
                + "command name, receiving the command named after its payload type, with a `// TODO(axon4to5):` "
                + "marker to name it after the deadline. Leaves a `// TODO(axon4to5):` marker where the recipe "
                + "cannot name the command, on the "
                + "handlers of member entities and of polymorphic aggregate subclasses, where two handlers now "
                + "receive the same command, and on `DeadlineMessage` parameters. Saga `@DeadlineHandler` methods "
                + "are left alone. Works on Java and Kotlin aggregates. Idempotent.";
    }

    /**
     * What the scanning phase learns about the source classes.
     */
    public static class Accumulator {

        final SagaClasses sagaClasses = new SagaClasses();
        final MemberEntities memberEntities = new MemberEntities();
        final Map<String, String> constants = new HashMap<>();
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
                acc.memberEntities.collect(classDeclaration);
                return super.visitClassDeclaration(classDeclaration, ctx);
            }

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations declarations,
                                                                    ExecutionContext ctx) {
                for (J.VariableDeclarations.NamedVariable variable : declarations.getVariables()) {
                    JavaType.Variable field = variable.getVariableType();
                    if (field != null && field.hasFlags(Flag.Final)
                            && field.getOwner() instanceof JavaType.FullyQualified owner
                            && variable.getInitializer() instanceof J.Literal literal
                            && literal.getValue() instanceof String value) {
                        acc.constants.put(constantKey(owner, field.getName()), value);
                    }
                }
                return super.visitVariableDeclarations(declarations, ctx);
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        Constants constants = new Constants(acc.constants);
        return new JavaIsoVisitor<>() {
            @Override
            public boolean isAcceptable(SourceFile sourceFile, ExecutionContext ctx) {
                return sourceFile instanceof JavaSourceFile;
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDeclaration,
                                                             ExecutionContext ctx) {
                // Nested classes are handled by the recursive call, before their enclosing class.
                J.ClassDeclaration visited = super.visitClassDeclaration(classDeclaration, ctx);
                if (acc.sagaClasses.isSaga(visited)) {
                    return visited;
                }
                JavaType.FullyQualified type = visited.getType();
                String className = type == null || type instanceof JavaType.Unknown
                        ? null
                        : type.getFullyQualifiedName();
                boolean memberEntity = className != null && acc.memberEntities.ownerOf(className) != null;
                AggregateType aggregateType = aggregateTypeOf(visited, constants);
                Map<J.MethodDeclaration, Migration> migrations = new HashMap<>();
                for (Statement statement : visited.getBody().getStatements()) {
                    if (statement instanceof J.MethodDeclaration method) {
                        J.Annotation deadlineHandler = annotation(method, DEADLINE_HANDLER_FQN, "DeadlineHandler");
                        if (deadlineHandler != null) {
                            Migration migration = memberEntity
                                    ? Migration.mark(MEMBER_TODO)
                                    : migrationOf(method, deadlineHandler, aggregateType, constants);
                            migrations.put(method, migration);
                        }
                    }
                }
                if (migrations.isEmpty()) {
                    return visited;
                }
                Map<String, Integer> handlersPerCommand = new HashMap<>();
                migrations.values().stream()
                          .filter(migration -> migration.commandName != null)
                          .forEach(migration -> handlersPerCommand.merge(migration.commandName, 1, Integer::sum));
                List<Statement> statements = ListUtils.map(visited.getBody().getStatements(), statement -> {
                    if (!(statement instanceof J.MethodDeclaration method) || !migrations.containsKey(method)) {
                        return statement;
                    }
                    Migration migration = migrations.get(method);
                    J.MethodDeclaration migrated = migration.apply(method);
                    if (migration.commandName != null
                            && handlersPerCommand.getOrDefault(migration.commandName, 0) > 1) {
                        migrated = mark(migrated, SHARED_COMMAND_MARKER, SHARED_COMMAND_TODO);
                    }
                    return migrated;
                });
                if (migrations.values().stream().anyMatch(migration -> migration.replacement != null)) {
                    maybeAddImport(COMMAND_HANDLER_AF4_FQN, null, false);
                    maybeRemoveImport(DEADLINE_HANDLER_FQN);
                }
                return visited.withBody(visited.getBody().withStatements(statements));
            }
        };
    }

    /**
     * Returns the key a deadline name expression is resolved with: the value of a string literal, or the field of a
     * constant, or {@code null} for any other expression.
     */
    private static @Nullable String nameKey(@Nullable Expression expression) {
        if (expression instanceof J.Literal literal && literal.getValue() instanceof String value) {
            return "=" + value;
        }
        JavaType.Variable field = expression instanceof J.Identifier identifier
                ? identifier.getFieldType()
                : expression instanceof J.FieldAccess fieldAccess ? fieldAccess.getName().getFieldType() : null;
        return field != null && field.getOwner() instanceof JavaType.FullyQualified owner
                ? constantKey(owner, field.getName())
                : null;
    }

    private static String constantKey(JavaType.FullyQualified owner, String name) {
        return owner.getFullyQualifiedName() + "#" + name;
    }

    /**
     * The string constants found in the sources, which deadline names and aggregate types given as a constant are
     * resolved with.
     *
     * @param values the values of the string constants, by field
     */
    private record Constants(Map<String, String> values) {

        /**
         * Returns the value of a string literal or of a constant found in the sources, or {@code null} otherwise.
         */
        @Nullable String resolve(@Nullable Expression expression) {
            String nameKey = nameKey(expression);
            if (nameKey == null) {
                return null;
            }
            return nameKey.startsWith("=") ? nameKey.substring(1) : values.get(nameKey);
        }
    }

    /**
     * The aggregate type Axon Framework 4 stored with the deadlines of a class.
     *
     * @param name the aggregate type, or {@code null} when the recipe cannot tell
     * @param todo the marker for the handlers of the class when {@code name} is {@code null}
     */
    private record AggregateType(@Nullable String name, @Nullable String todo) {

    }

    /**
     * Returns the aggregate type Axon Framework 4 stored with a deadline of the given class: the {@code type} of its
     * {@code @AggregateRoot} or {@code @Aggregate} annotation, or its simple class name otherwise. A subclass of an
     * annotated class belongs to a polymorphic aggregate, whose root type it does not declare.
     */
    private static AggregateType aggregateTypeOf(J.ClassDeclaration classDeclaration, Constants constants) {
        JavaType.FullyQualified type = classDeclaration.getType();
        for (JavaType.FullyQualified supertype = type == null ? null : type.getSupertype();
             supertype != null && !OBJECT_FQN.equals(supertype.getFullyQualifiedName());
             supertype = supertype.getSupertype()) {
            boolean annotated = supertype.getAnnotations().stream()
                                         .anyMatch(annotation -> AGGREGATE_ANNOTATION_FQNS.contains(
                                                 annotation.getFullyQualifiedName()
                                         ));
            if (annotated) {
                return new AggregateType(null, POLYMORPHIC_TODO);
            }
        }
        for (J.Annotation annotation : classDeclaration.getLeadingAnnotations()) {
            if (isAnnotation(annotation, AGGREGATE_ROOT_FQN, "AggregateRoot")
                    || isAnnotation(annotation, SPRING_AGGREGATE_FQN, "Aggregate")) {
                Expression declaredType = attribute(annotation, "type");
                if (declaredType != null && !isEmptyLiteral(declaredType)) {
                    String name = constants.resolve(declaredType);
                    return name == null
                            ? new AggregateType(null, UNRESOLVED_NAME_TODO)
                            : new AggregateType(name, null);
                }
            }
        }
        return new AggregateType(classDeclaration.getSimpleName(), null);
    }

    /**
     * Decides how a {@code @DeadlineHandler} method is migrated, following how Axon Framework 4 matched a deadline: a
     * handler with a {@code deadlineName} only received deadlines of that name, and a handler only received payloads
     * assignable to its payload parameter, or to its {@code payloadType} when it had no payload parameter.
     */
    private static Migration migrationOf(J.MethodDeclaration method,
                                         J.Annotation deadlineHandler,
                                         AggregateType aggregateType,
                                         Constants constants) {
        if (aggregateType.name() == null) {
            return Migration.mark(requireNonNull(aggregateType.todo()));
        }
        boolean deadlineMessageParameter = hasDeadlineMessageParameter(method);
        Expression deadlineNameAttribute = attribute(deadlineHandler, "deadlineName");
        JavaType payloadType = payloadTypeOf(method, deadlineHandler);
        String deadlineName;
        if (deadlineNameAttribute != null && !isEmptyLiteral(deadlineNameAttribute)) {
            deadlineName = constants.resolve(deadlineNameAttribute);
            if (deadlineName == null) {
                return Migration.mark(UNRESOLVED_NAME_TODO);
            }
        } else {
            if (payloadType == null) {
                return Migration.mark(MATCHES_ALL_TODO);
            }
            if (isGenericPayloadType(payloadType)) {
                return Migration.mark(GENERIC_PAYLOAD_TODO);
            }
            // Named after its payload type, the handler receives every deadline with that payload, as it did before.
            return Migration.replace(withCommandName(deadlineHandler, null),
                                     resolvedName(payloadType),
                                     deadlineMessageParameter,
                                     NAME_COMMAND_TODO);
        }
        String commandName = aggregateType.name() + "." + deadlineName;
        return Migration.replace(withCommandName(deadlineHandler, commandName),
                                 commandName,
                                 deadlineMessageParameter,
                                 null);
    }

    /**
     * Returns the payload type a handler matched in Axon Framework 4: the type of its payload parameter, or its
     * {@code payloadType} when it has none, or {@code null} when it names neither, or names {@code Object} as its
     * {@code payloadType}, matching any payload.
     */
    private static @Nullable JavaType payloadTypeOf(J.MethodDeclaration method, J.Annotation deadlineHandler) {
        J.VariableDeclarations payloadParameter = payloadParameter(method);
        if (payloadParameter != null) {
            JavaType type = payloadParameter.getType();
            boolean unresolvedObject = resolvedName(type) == null
                    && payloadParameter.getTypeExpression() != null
                    && Set.of("Object", "Any").contains(simpleName(payloadParameter.getTypeExpression()));
            return unresolvedObject ? JavaType.ShallowClass.build(OBJECT_FQN) : type;
        }
        JavaType declaredPayloadType = classLiteralType(attribute(deadlineHandler, "payloadType"));
        return declaredPayloadType == null || isObject(declaredPayloadType) ? null : declaredPayloadType;
    }

    /**
     * Returns the first parameter when Axon Framework 4 resolved it as the payload: its type is not an Axon Framework
     * type, and no Axon Framework annotation such as {@code @MetaDataValue} resolves it from elsewhere.
     */
    private static J.@Nullable VariableDeclarations payloadParameter(J.MethodDeclaration method) {
        List<Statement> parameters = method.getParameters();
        if (parameters.isEmpty() || !(parameters.getFirst() instanceof J.VariableDeclarations first)) {
            return null;
        }
        boolean resolvedElsewhere = first.getLeadingAnnotations().stream().anyMatch(annotation -> {
            String type = resolvedName(annotation.getType());
            return type == null || type.startsWith("org.axonframework.");
        });
        if (resolvedElsewhere) {
            return null;
        }
        String type = resolvedName(first.getType());
        if (type != null) {
            return type.startsWith("org.axonframework.") ? null : first;
        }
        return first.getTypeExpression() instanceof J.Identifier identifier
                && NON_PAYLOAD_TYPE_NAMES.contains(identifier.getSimpleName()) ? null : first;
    }

    private static boolean hasDeadlineMessageParameter(J.MethodDeclaration method) {
        return method.getParameters().stream()
                     .filter(J.VariableDeclarations.class::isInstance)
                     .map(J.VariableDeclarations.class::cast)
                     .anyMatch(parameter -> {
                         String type = resolvedName(parameter.getType());
                         if (type != null) {
                             return DEADLINE_MESSAGE_FQNS.contains(type);
                         }
                         return parameter.getTypeExpression() != null
                                 && DEADLINE_MESSAGE_NAMES.contains(simpleName(parameter.getTypeExpression()));
                     });
    }

    /**
     * Returns whether a payload of this type may be of several classes: {@code Object}, an interface or an abstract
     * class.
     */
    private static boolean isGenericPayloadType(@Nullable JavaType type) {
        if (isObject(type)) {
            return true;
        }
        JavaType.Class clazz = TypeUtils.asClass(type);
        return clazz != null
                && (clazz.getKind() == JavaType.FullyQualified.Kind.Interface || clazz.hasFlags(Flag.Abstract));
    }

    private static boolean isObject(@Nullable JavaType type) {
        String name = resolvedName(type);
        // An unresolved class literal is kept as a shallow type named after its simple name.
        return OBJECT_FQN.equals(name) || KOTLIN_ANY_FQN.equals(name) || "Object".equals(name) || "Any".equals(name);
    }

    private static J.@Nullable Annotation annotation(J.MethodDeclaration method,
                                                     String fullyQualifiedName,
                                                     String simpleName) {
        return method.getLeadingAnnotations().stream()
                     .filter(annotation -> isAnnotation(annotation, fullyQualifiedName, simpleName))
                     .findFirst()
                     .orElse(null);
    }

    private static @Nullable Expression attribute(J.Annotation annotation, String name) {
        if (annotation.getArguments() == null) {
            return null;
        }
        for (Expression argument : annotation.getArguments()) {
            if (argument instanceof J.Assignment assignment
                    && assignment.getVariable() instanceof J.Identifier identifier
                    && name.equals(identifier.getSimpleName())) {
                return assignment.getAssignment();
            }
        }
        return null;
    }

    /**
     * Returns the type of a Java class literal ({@code Type.class}) or a Kotlin class reference ({@code Type::class}),
     * or {@code null} for any other expression.
     */
    private static @Nullable JavaType classLiteralType(@Nullable Expression expression) {
        J target;
        if (expression instanceof J.FieldAccess fieldAccess && "class".equals(fieldAccess.getSimpleName())) {
            target = fieldAccess.getTarget();
        } else if (expression instanceof J.MemberReference reference
                && "class".equals(reference.getReference().getSimpleName())) {
            target = reference.getContaining();
        } else {
            return null;
        }
        JavaType type = target instanceof Expression typeExpression ? typeExpression.getType() : null;
        if (type == null || type instanceof JavaType.Unknown) {
            // Keep the payload type when it is unresolved, so it still names the deadlines it matched.
            return JavaType.ShallowClass.build(simpleName(target));
        }
        return type;
    }

    private static boolean isEmptyLiteral(Expression expression) {
        return expression instanceof J.Literal literal && "".equals(literal.getValue());
    }

    private static String simpleName(J typeExpression) {
        if (typeExpression instanceof J.Identifier identifier) {
            return identifier.getSimpleName();
        }
        if (typeExpression instanceof J.FieldAccess fieldAccess) {
            return fieldAccess.getSimpleName();
        }
        if (typeExpression instanceof J.ParameterizedType parameterized) {
            return simpleName(parameterized.getClazz());
        }
        return String.valueOf(typeExpression);
    }

    /**
     * Returns the annotation with its {@code deadlineName} replaced by a leading {@code commandName} of the given
     * value, or removed when the value is {@code null}, keeping any other attribute, such as {@code payloadType}.
     */
    private static J.Annotation withCommandName(J.Annotation annotation, @Nullable String commandName) {
        List<Expression> arguments = new ArrayList<>();
        if (commandName != null) {
            J.Literal value = new J.Literal(
                    Tree.randomId(), Space.format(" "), Markers.EMPTY, commandName, "\"" + commandName + "\"", null,
                    JavaType.Primitive.String
            );
            arguments.add(new J.Assignment(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, List.of(), "commandName", null, null),
                    new JLeftPadded<>(Space.format(" "), value, Markers.EMPTY),
                    null
            ));
        }
        if (annotation.getArguments() != null) {
            for (Expression argument : annotation.getArguments()) {
                boolean deadlineName = argument instanceof J.Assignment assignment
                        && assignment.getVariable() instanceof J.Identifier identifier
                        && "deadlineName".equals(identifier.getSimpleName());
                if (!(argument instanceof J.Empty) && !deadlineName) {
                    arguments.add(argument.withPrefix(arguments.isEmpty() ? Space.EMPTY : Space.format(" ")));
                }
            }
        }
        return annotation.withArguments(arguments.isEmpty() ? null : arguments);
    }

    private static <T> T requireNonNull(@Nullable T value) {
        if (value == null) {
            throw new IllegalStateException("An aggregate type without a name carries a marker.");
        }
        return value;
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

    /**
     * How one {@code @DeadlineHandler} method is migrated: either its annotation is replaced, or it only gets a
     * marker.
     *
     * @param replacement              the annotation replacing {@code @DeadlineHandler}, still to be renamed, or
     *                                 {@code null} when only a marker is left
     * @param commandName              the name of the command the migrated handler receives, or {@code null} when
     *                                 only a marker is left or the payload type it is named after is unresolved
     * @param deadlineMessageParameter whether the method has a {@code DeadlineMessage} parameter
     * @param todo                     the marker for a method that is not migrated, or for a migrated one still to
     *                                 be named after its deadline, or {@code null}
     */
    private record Migration(J.@Nullable Annotation replacement,
                             @Nullable String commandName,
                             boolean deadlineMessageParameter,
                             @Nullable String todo) {

        static Migration replace(J.Annotation replacement,
                                 @Nullable String commandName,
                                 boolean deadlineMessageParameter,
                                 @Nullable String todo) {
            return new Migration(replacement, commandName, deadlineMessageParameter, todo);
        }

        static Migration mark(String todo) {
            return new Migration(null, null, false, todo);
        }

        J.MethodDeclaration apply(J.MethodDeclaration method) {
            if (replacement == null) {
                return MigrateAggregateDeadlineHandler.mark(method, REPLACE_MARKER, requireTodo());
            }
            J.Annotation commandHandler = replacement.withAnnotationType(new J.Identifier(
                    Tree.randomId(),
                    replacement.getAnnotationType().getPrefix(),
                    Markers.EMPTY,
                    List.of(),
                    "CommandHandler",
                    JavaType.ShallowClass.build(COMMAND_HANDLER_AF4_FQN),
                    null
            ));
            J.MethodDeclaration migrated = method.withLeadingAnnotations(ListUtils.map(
                    method.getLeadingAnnotations(),
                    annotation -> isAnnotation(annotation, DEADLINE_HANDLER_FQN, "DeadlineHandler")
                            ? commandHandler
                            : annotation
            ));
            if (todo != null) {
                migrated = MigrateAggregateDeadlineHandler.mark(migrated, NAME_COMMAND_MARKER, todo);
            }
            return deadlineMessageParameter
                    ? MigrateAggregateDeadlineHandler.mark(migrated, DEADLINE_MESSAGE_MARKER, DEADLINE_MESSAGE_TODO)
                    : migrated;
        }

        private String requireTodo() {
            if (todo == null) {
                throw new IllegalStateException("A migration without a replacement carries a marker.");
            }
            return todo;
        }
    }
}
