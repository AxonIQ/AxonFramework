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
 * Replaces the Axon Framework 4 {@code @DeadlineHandler} of an aggregate, or of one of its member entities, with the
 * Axon Framework 4 {@code @CommandHandler} that receives the deadline once it fires.
 * <p>
 * On the Axoniq Framework legacy module, a deadline scheduled for an aggregate scope fires as a command: a deadline
 * with a payload becomes a command named after its payload class, and a deadline without a payload becomes a command
 * named after the deadline, carrying the deadline name as its payload. The recipe maps every handler onto the command
 * that reaches it:
 * <ul>
 *     <li>a handler with a payload parameter becomes a plain {@code @CommandHandler}. The {@code deadlineName}
 *     attribute is dropped, as the command is routed by its payload type only;</li>
 *     <li>a handler without a payload parameter but with a {@code payloadType} keeps that {@code payloadType};</li>
 *     <li>a handler without a payload parameter and with a {@code deadlineName} becomes
 *     {@code @CommandHandler(commandName = <deadlineName>)}, which accepts the deadline name payload.</li>
 * </ul>
 * The recipe uses the Axon Framework 4 {@code @CommandHandler} on purpose: the Axon Framework 5 upgrade that runs
 * afterwards moves it to the Axon Framework 5 package and migrates the handler like any other aggregate command
 * handler. The deadline payload class is left untouched, since the target entity is resolved from the deadline's
 * aggregate scope.
 * <p>
 * Where the deadline cannot reach the handler as a command, the recipe leaves a {@code // TODO(axon4to5):} marker:
 * <ul>
 *     <li>a handler that names neither a deadline nor a payload type handled every deadline of the aggregate, and an
 *     {@code Object}, interface or abstract payload parameter names no command. These keep their
 *     {@code @DeadlineHandler};</li>
 *     <li>two handlers of one class that receive the same payload type, which Axon Framework 4 told apart by deadline
 *     name or message type, now receive the same command;</li>
 *     <li>a {@code DeadlineMessage} parameter does not match the command message that now arrives.</li>
 * </ul>
 * Saga classes, which keep their {@code @DeadlineHandler} on the legacy module, are left alone. The recipe is
 * idempotent.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public class MigrateAggregateDeadlineHandler extends ScanningRecipe<SagaClasses> {

    private static final String DEADLINE_HANDLER_FQN = "org.axonframework.deadline.annotation.DeadlineHandler";
    private static final String COMMAND_HANDLER_AF4_FQN = "org.axonframework.commandhandling.CommandHandler";
    private static final String COMMAND_HANDLER_AF5_FQN =
            "org.axonframework.messaging.commandhandling.annotation.CommandHandler";
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

    private static final String REPLACE_MARKER = "TODO(axon4to5): replace this @DeadlineHandler with a @CommandHandler";
    private static final String FIRED_AS_COMMAND = " A fired aggregate deadline now arrives as a command named"
            + " after its payload class, or after the deadline name when it has no payload.";
    private static final String MATCHES_ALL_TODO = " " + REPLACE_MARKER + " per deadline it handles."
            + " It names neither a deadline nor a payload type, so it handled every deadline of the aggregate."
            + FIRED_AS_COMMAND;
    private static final String GENERIC_PAYLOAD_TODO = " " + REPLACE_MARKER + " for a concrete payload class."
            + " Its payload type names no command." + FIRED_AS_COMMAND;
    private static final String SHARED_PAYLOAD_MARKER =
            "TODO(axon4to5): give each deadline handled here a payload class of its own.";
    private static final String SHARED_PAYLOAD_TODO = " " + SHARED_PAYLOAD_MARKER
            + " Another handler of this class receives the same payload type, which Axon Framework 4 told apart by"
            + " deadline name or message type." + FIRED_AS_COMMAND;
    private static final String DEADLINE_MESSAGE_MARKER = "TODO(axon4to5): replace the DeadlineMessage parameter";
    private static final String DEADLINE_MESSAGE_TODO = " " + DEADLINE_MESSAGE_MARKER
            + " with the payload or metadata this handler needs."
            + " A fired aggregate deadline now arrives as a command message, which this parameter does not match.";

    @Override
    public String getDisplayName() {
        return "Replace aggregate `@DeadlineHandler` methods with `@CommandHandler` methods";
    }

    @Override
    public String getDescription() {
        return "On the Axoniq Framework legacy module, a deadline scheduled for an aggregate fires as a command, "
                + "named after its payload class, or after the deadline name when it has no payload. Replaces the "
                + "`@DeadlineHandler` of an aggregate, or of a member entity, with the Axon Framework 4 "
                + "`@CommandHandler` that receives that command: a handler with a payload parameter becomes a plain "
                + "`@CommandHandler`, and a handler without one becomes `@CommandHandler(commandName = "
                + "<deadlineName>)`. Leaves a `// TODO(axon4to5):` marker where no command can reach the handler, "
                + "where two handlers now receive the same command, and on `DeadlineMessage` parameters. Saga "
                + "`@DeadlineHandler` methods are left alone. Idempotent.";
    }

    @Override
    public SagaClasses getInitialValue(ExecutionContext ctx) {
        return new SagaClasses();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(SagaClasses sagaClasses) {
        return new JavaIsoVisitor<>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDeclaration,
                                                             ExecutionContext ctx) {
                sagaClasses.collect(classDeclaration);
                return super.visitClassDeclaration(classDeclaration, ctx);
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(SagaClasses sagaClasses) {
        return new JavaIsoVisitor<>() {
            @Override
            public boolean isAcceptable(SourceFile sourceFile, ExecutionContext ctx) {
                return sourceFile instanceof J.CompilationUnit;
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDeclaration,
                                                             ExecutionContext ctx) {
                // Nested classes are handled by the recursive call, before their enclosing class.
                J.ClassDeclaration visited = super.visitClassDeclaration(classDeclaration, ctx);
                if (sagaClasses.isSaga(visited)) {
                    return visited;
                }
                Map<J.MethodDeclaration, Migration> migrations = new HashMap<>();
                Map<String, Integer> handlersPerPayloadType = new HashMap<>();
                for (Statement statement : visited.getBody().getStatements()) {
                    if (!(statement instanceof J.MethodDeclaration method)) {
                        continue;
                    }
                    J.Annotation deadlineHandler = annotation(method, DEADLINE_HANDLER_FQN, "DeadlineHandler");
                    if (deadlineHandler != null) {
                        Migration migration = migrationOf(method, deadlineHandler);
                        migrations.put(method, migration);
                        if (migration.payloadType != null) {
                            handlersPerPayloadType.merge(migration.payloadType, 1, Integer::sum);
                        }
                    } else {
                        String commandPayloadType = commandPayloadType(method);
                        if (commandPayloadType != null) {
                            handlersPerPayloadType.merge(commandPayloadType, 1, Integer::sum);
                        }
                    }
                }
                if (migrations.isEmpty()) {
                    return visited;
                }
                List<Statement> statements = ListUtils.map(visited.getBody().getStatements(), statement -> {
                    if (!(statement instanceof J.MethodDeclaration method) || !migrations.containsKey(method)) {
                        return statement;
                    }
                    Migration migration = migrations.get(method);
                    J.MethodDeclaration migrated = migration.apply(method);
                    if (migration.payloadType != null
                            && handlersPerPayloadType.getOrDefault(migration.payloadType, 0) > 1) {
                        migrated = mark(migrated, SHARED_PAYLOAD_MARKER, SHARED_PAYLOAD_TODO);
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
     * Decides how a {@code @DeadlineHandler} method is migrated, following how Axon Framework 4 matched a deadline: a
     * handler with a {@code deadlineName} only received deadlines of that name, and a handler only received payloads
     * assignable to its payload parameter, or to its {@code payloadType} when it had no payload parameter.
     */
    private static Migration migrationOf(J.MethodDeclaration method, J.Annotation deadlineHandler) {
        Expression deadlineName = attribute(deadlineHandler, "deadlineName");
        Expression payloadTypeAttribute = attribute(deadlineHandler, "payloadType");
        J.VariableDeclarations payloadParameter = payloadParameter(method);
        boolean deadlineMessageParameter = hasDeadlineMessageParameter(method);
        if (payloadParameter != null) {
            JavaType type = payloadParameter.getType();
            boolean unresolvedObject = resolvedName(type) == null
                    && payloadParameter.getTypeExpression() != null
                    && "Object".equals(simpleName(payloadParameter.getTypeExpression()));
            if (unresolvedObject || isGenericPayloadType(type)) {
                return Migration.mark(GENERIC_PAYLOAD_TODO);
            }
            return Migration.replace(withoutAttribute(deadlineHandler, "deadlineName"),
                                     payloadTypeKey(type, payloadParameter.getTypeExpression()),
                                     deadlineMessageParameter);
        }
        JavaType declaredPayloadType = classLiteralType(payloadTypeAttribute);
        if (declaredPayloadType != null && !isObject(declaredPayloadType)) {
            if (isGenericPayloadType(declaredPayloadType)) {
                return Migration.mark(GENERIC_PAYLOAD_TODO);
            }
            return Migration.replace(withoutAttribute(deadlineHandler, "deadlineName"),
                                     payloadTypeKey(declaredPayloadType, null),
                                     deadlineMessageParameter);
        }
        if (deadlineName != null && !isEmptyLiteral(deadlineName)) {
            return Migration.replace(asCommandName(withoutAttribute(deadlineHandler, "payloadType")),
                                     null,
                                     deadlineMessageParameter);
        }
        return Migration.mark(MATCHES_ALL_TODO);
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
     * Returns whether a payload of this type names no command: {@code Object}, an interface or an abstract class,
     * while the command of a fired deadline is named after the concrete payload class.
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
        return OBJECT_FQN.equals(name) || "Object".equals(name);
    }

    private static String payloadTypeKey(@Nullable JavaType type, @Nullable J typeExpression) {
        String resolved = resolvedName(type);
        if (resolved != null) {
            return resolved;
        }
        return typeExpression != null ? simpleName(typeExpression) : String.valueOf(type);
    }

    /**
     * Returns the payload type of an existing {@code @CommandHandler} without a {@code commandName}, which receives the
     * commands named after that type, or {@code null} for any other method.
     */
    private static @Nullable String commandPayloadType(J.MethodDeclaration method) {
        J.Annotation commandHandler = annotation(method, COMMAND_HANDLER_AF4_FQN, "CommandHandler");
        if (commandHandler == null) {
            commandHandler = annotation(method, COMMAND_HANDLER_AF5_FQN, "CommandHandler");
        }
        if (commandHandler == null || attribute(commandHandler, "commandName") != null) {
            return null;
        }
        J.VariableDeclarations payloadParameter = payloadParameter(method);
        return payloadParameter == null
                ? null
                : payloadTypeKey(payloadParameter.getType(), payloadParameter.getTypeExpression());
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

    private static @Nullable JavaType classLiteralType(@Nullable Expression expression) {
        if (expression instanceof J.FieldAccess fieldAccess && "class".equals(fieldAccess.getSimpleName())) {
            JavaType type = fieldAccess.getTarget().getType();
            if (type == null || type instanceof JavaType.Unknown) {
                // Keep the payload type when it is unresolved, so the handler is still migrated.
                return JavaType.ShallowClass.build(simpleName(fieldAccess.getTarget()));
            }
            return type;
        }
        return null;
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

    private static J.Annotation withoutAttribute(J.Annotation annotation, String name) {
        if (annotation.getArguments() == null) {
            return annotation;
        }
        List<Expression> kept = new ArrayList<>();
        for (Expression argument : annotation.getArguments()) {
            if (argument instanceof J.Empty) {
                continue;
            }
            if (argument instanceof J.Assignment assignment
                    && assignment.getVariable() instanceof J.Identifier identifier
                    && name.equals(identifier.getSimpleName())) {
                continue;
            }
            kept.add(argument);
        }
        if (kept.isEmpty()) {
            return annotation.withArguments(null);
        }
        kept.set(0, kept.getFirst().withPrefix(Space.EMPTY));
        return annotation.withArguments(kept);
    }

    private static J.Annotation asCommandName(J.Annotation annotation) {
        return annotation.withArguments(ListUtils.map(annotation.getArguments(), argument -> {
            if (argument instanceof J.Assignment assignment
                    && assignment.getVariable() instanceof J.Identifier identifier
                    && "deadlineName".equals(identifier.getSimpleName())) {
                return assignment.withVariable(identifier.withSimpleName("commandName")
                                                         .withType(null)
                                                         .withFieldType(null));
            }
            return argument;
        }));
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
     * @param payloadType              the payload type the migrated handler receives commands for, or {@code null}
     *                                 when it receives a command named after the deadline
     * @param deadlineMessageParameter whether the method has a {@code DeadlineMessage} parameter
     * @param todo                     the marker for a method that is not migrated, or {@code null}
     */
    private record Migration(J.@Nullable Annotation replacement,
                             @Nullable String payloadType,
                             boolean deadlineMessageParameter,
                             @Nullable String todo) {

        static Migration replace(J.Annotation replacement,
                                 @Nullable String payloadType,
                                 boolean deadlineMessageParameter) {
            return new Migration(replacement, payloadType, deadlineMessageParameter, null);
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
