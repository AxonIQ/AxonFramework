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
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Comment;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
 * A serializer that a Spring {@code @Bean} method receives as a parameter used for nothing else becomes the converter
 * Axon Framework 5 Spring Boot provides in its place: the {@code "eventSerializer"} qualifier an
 * {@code EventConverter}, {@code "messageSerializer"} a {@code MessageConverter}, and {@code "serializer"} or no
 * qualifier a {@code GeneralConverter}, the one converter type Spring resolves without ambiguity. Which serializer that
 * parameter was is configured elsewhere, so that statement keeps a marker to check the converter against the deadlines
 * already stored.
 * <p>
 * It inserts {@code unitOfWorkFactory(..)} right after {@code builder()} in a Java builder chain when a factory is in
 * reach: {@code configuration.getComponent(UnitOfWorkFactory.class)} on an Axon Framework 4 {@code Configuration}
 * lambda parameter, method parameter or preceding local, which Axon Framework 5's {@code Configuration} answers too,
 * or, in a Spring {@code @Bean} method, a {@code UnitOfWorkFactory} parameter it adds to the method, which Spring
 * resolves from the Axon configuration.
 * <p>
 * Which {@code UnitOfWorkFactory} to use elsewhere, and which converter reads what any other serializer stored,
 * cannot be inferred at the call site, so the recipe leaves a {@code // TODO(axon4to5):} marker on the statement that
 * builds the manager for those. Builder calls of other components with the same method names are left alone. The
 * recipe is idempotent: a statement that already carries a marker is not marked again.
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
    private static final Set<String> REMOVED_ARGUMENT_TYPE_FQNS = Set.of(
            "org.axonframework.deadline.DeadlineManagerSpanFactory",
            "org.axonframework.tracing.SpanFactory",
            "org.axonframework.common.transaction.TransactionManager"
    );
    private static final String SERIALIZER_METHOD = "serializer";
    private static final String CONVERTER_METHOD = "converter";
    private static final String UNIT_OF_WORK_FACTORY_METHOD = "unitOfWorkFactory";
    private static final String UNIT_OF_WORK_FACTORY_PARAMETER_KEY = "unitOfWorkFactoryParameter";

    private static final String CONFIGURATION_FQN = "org.axonframework.config.Configuration";
    private static final String UNIT_OF_WORK_FACTORY_FQN =
            "org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory";
    private static final String SPRING_BEAN_FQN = "org.springframework.context.annotation.Bean";

    private static final String CONVERTER_PARAMETERS_KEY = "converterParameters";
    private static final String SERIALIZER_FQN = "org.axonframework.serialization.Serializer";
    private static final String QUALIFIER_FQN = "org.springframework.beans.factory.annotation.Qualifier";
    private static final String GENERAL_CONVERTER_FQN = "org.axonframework.conversion.GeneralConverter";
    private static final String MESSAGE_CONVERTER_FQN = "org.axonframework.messaging.core.conversion.MessageConverter";
    private static final String EVENT_CONVERTER_FQN =
            "org.axonframework.messaging.eventhandling.conversion.EventConverter";

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
    private static final String STORED_DEADLINES_MARKER =
            "TODO(axon4to5): check that this converter reads the deadlines already stored";
    private static final String STORED_DEADLINES_TODO = " " + STORED_DEADLINES_MARKER
            + ". They were written by the Axon Framework 4 serializer this parameter received; configure the"
            + " converter to match that serializer, so pending deadlines stay readable.";
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
                + "Framework 4 deadline managers. Replaces `serializer(JacksonSerializer.defaultSerializer())` with "
                + "`converter(new JacksonConverter())`, and `serializer(..)` on a Spring `@Bean` method parameter "
                + "with `converter(..)` on the matching `EventConverter`, `MessageConverter` or `GeneralConverter` "
                + "parameter, marking that statement to check the converter against the stored deadlines. Adds the "
                + "required `unitOfWorkFactory(..)`, looked up "
                + "on an Axon Framework 4 `Configuration` in scope or, in a Spring `@Bean` method, taken from a "
                + "`UnitOfWorkFactory` parameter it adds. Leaves a `// TODO(axon4to5):` marker for a "
                + "`unitOfWorkFactory(..)` with neither in reach, and for a `converter(..)` it cannot infer. "
                + "Idempotent.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<ExecutionContext>() {
            /**
             * The converter(..) calls that replaced a serializer(..) call, whose statements get a marker.
             */
            private final Set<UUID> convertedSerializerCalls = new HashSet<>();

            @Override
            public J visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J visited = super.visitMethodDeclaration(method, ctx);
                if (!(visited instanceof J.MethodDeclaration declaration)) {
                    return visited;
                }
                Map<String, String[]> converters = getCursor().pollMessage(CONVERTER_PARAMETERS_KEY);
                if (converters != null) {
                    for (Map.Entry<String, String[]> converter : converters.entrySet()) {
                        declaration = MethodParameters.replace(declaration, converter.getKey(),
                                                               converter.getValue()[0], converter.getValue()[1]);
                    }
                }
                String parameter = getCursor().pollMessage(UNIT_OF_WORK_FACTORY_PARAMETER_KEY);
                if (parameter != null) {
                    maybeAddImport(UNIT_OF_WORK_FACTORY_FQN, null, false);
                    declaration = MethodParameters.append(declaration, false, UNIT_OF_WORK_FACTORY_FQN, parameter);
                }
                return declaration;
            }

            @Override
            public @Nullable J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J visited = super.visitMethodInvocation(method, ctx);
                if (!(visited instanceof J.MethodInvocation invocation)) {
                    return visited;
                }
                J migrated = migrateCall(invocation);
                if (migrated instanceof J.MethodInvocation chain && isChainTop(method)) {
                    return addUnitOfWorkFactory(chain);
                }
                return migrated;
            }

            private @Nullable J migrateCall(J.MethodInvocation invocation) {
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
                return visited.withStatements(ListUtils.map(
                        visited.getStatements(), statement -> markBuilders(statement, convertedSerializerCalls)));
            }

            private @Nullable J removeCall(J.MethodInvocation invocation) {
                // The span factory types are not ported, so an import left unused would no longer compile.
                REMOVED_ARGUMENT_TYPE_FQNS.forEach(this::maybeRemoveImport);
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
                    return serializer instanceof J.Identifier parameter
                            ? convertBeanParameter(invocation, parameter)
                            : invocation;
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

            /**
             * Replaces {@code serializer(serializer)}, where {@code serializer} is a Spring {@code @Bean} method
             * parameter used for nothing else, with {@code converter(..)} on the parameter of the converter that
             * succeeds the qualified serializer, asking {@link #visitMethodDeclaration} to retype the parameter. Any
             * other serializer is left to the marker.
             */
            private J.MethodInvocation convertBeanParameter(J.MethodInvocation invocation, J.Identifier argument) {
                J.MethodDeclaration declaration = getCursor().firstEnclosing(J.MethodDeclaration.class);
                if (declaration == null
                        || getCursor().firstEnclosing(J.CompilationUnit.class) == null
                        || !isSpringBean(declaration)
                        || identifierCount(declaration, argument.getSimpleName()) != 2) {
                    return invocation;
                }
                J.VariableDeclarations parameter = serializerParameter(declaration, argument.getSimpleName());
                String[] converter = parameter == null ? null : converterSucceeding(parameter);
                if (converter == null) {
                    return invocation;
                }
                Cursor methodCursor = getCursor().dropParentUntil(J.MethodDeclaration.class::isInstance);
                Map<String, String[]> converters =
                        methodCursor.computeMessageIfAbsent(CONVERTER_PARAMETERS_KEY, key -> new HashMap<>());
                String[] replacement = converters.computeIfAbsent(
                        argument.getSimpleName(),
                        name -> new String[]{converter[0], unusedName(declaration, converter[1])}
                );
                maybeAddImport(replacement[0], null, false);
                maybeRemoveImport(SERIALIZER_FQN);
                maybeRemoveImport(QUALIFIER_FQN);
                J.Identifier reference = new J.Identifier(Tree.randomId(), argument.getPrefix(), Markers.EMPTY,
                                                          List.of(), replacement[1],
                                                          JavaType.ShallowClass.build(replacement[0]), null);
                J.MethodInvocation converted =
                        invocation.withName(invocation.getName().withSimpleName(CONVERTER_METHOD))
                                  .withArguments(List.of(reference));
                convertedSerializerCalls.add(converted.getId());
                return converted;
            }

            /**
             * Whether the given call is the last call of its chain, so the chain is complete when it is visited.
             */
            private boolean isChainTop(J.MethodInvocation original) {
                Object parent = getCursor().getParentTreeCursor().getValue();
                return !(parent instanceof J.MethodInvocation outer
                        && outer.getSelect() != null
                        && outer.getSelect().getId().equals(original.getId()));
            }

            /**
             * Inserts {@code unitOfWorkFactory(..)} right after {@code <Manager>.builder()} in a Java builder chain
             * lacking it, when a factory is in reach. Without one, {@link #markBuilders(Statement)} marks the chain.
             */
            private J.MethodInvocation addUnitOfWorkFactory(J.MethodInvocation chain) {
                if (rootDeadlineManager(chain) == null
                        || chainedMethodNames(chain).contains(UNIT_OF_WORK_FACTORY_METHOD)
                        || getCursor().firstEnclosing(J.CompilationUnit.class) == null) {
                    return chain;
                }
                Expression factory = unitOfWorkFactoryInScope();
                return factory == null ? chain : insertAfterBuilder(chain, factory);
            }

            /**
             * Finds the {@code UnitOfWorkFactory} the builder can use, from the innermost scope outwards: an Axon
             * Framework 4 {@code Configuration} that is a lambda or method parameter, or a local declared before
             * the statement holding the builder; otherwise, in a Spring {@code @Bean} method, a
             * {@code UnitOfWorkFactory} parameter, added when missing. A field, or a class boundary, ends the search,
             * as the recipe cannot tell whether a field is set by the time the builder runs.
             */
            private @Nullable Expression unitOfWorkFactoryInScope() {
                J child = null;
                for (Iterator<Object> path = getCursor().getPath(); path.hasNext(); ) {
                    Object value = path.next();
                    if (!(value instanceof J tree)) {
                        continue;
                    }
                    if (tree instanceof J.Lambda lambda) {
                        J.Identifier configuration = configurationVariable(lambda.getParameters().getParameters());
                        if (configuration != null) {
                            return componentLookup(configuration);
                        }
                    } else if (tree instanceof J.Block block && child != null) {
                        J.Identifier configuration = configurationDeclaredBefore(block, child);
                        if (configuration != null) {
                            return componentLookup(configuration);
                        }
                    } else if (tree instanceof J.MethodDeclaration declaration) {
                        J.Identifier configuration = configurationVariable(declaration.getParameters());
                        if (configuration != null) {
                            return componentLookup(configuration);
                        }
                        return isSpringBean(declaration) ? beanParameter(declaration) : null;
                    } else if (tree instanceof J.ClassDeclaration
                            || tree instanceof J.NewClass created && created.getBody() == child) {
                        return null;
                    }
                    child = tree;
                }
                return null;
            }

            private Expression componentLookup(J.Identifier configuration) {
                maybeAddImport(UNIT_OF_WORK_FACTORY_FQN, null, false);
                return JavaTemplate.builder("#{any(" + CONFIGURATION_FQN + ")}.getComponent(UnitOfWorkFactory.class)")
                                   .imports(UNIT_OF_WORK_FACTORY_FQN)
                                   .javaParser(JavaParser.fromJavaVersion()
                                                         .classpath(JavaParser.runtimeClasspath()))
                                   .build()
                                   .apply(new Cursor(getCursor(), configuration),
                                          configuration.getCoordinates().replace(),
                                          configuration);
            }

            /**
             * Returns the {@code UnitOfWorkFactory} parameter of the given Spring {@code @Bean} method, asking
             * {@link #visitMethodDeclaration} to add one, under a name no other variable of the method uses, when
             * it has none. All builders of the method share the parameter.
             */
            private J.Identifier beanParameter(J.MethodDeclaration declaration) {
                for (Statement parameter : declaration.getParameters()) {
                    if (parameter instanceof J.VariableDeclarations variables
                            && variables.getTypeExpression() != null
                            && isUnitOfWorkFactoryType(variables.getTypeExpression())) {
                        return parameterReference(variables.getVariables().getFirst().getSimpleName());
                    }
                }
                Cursor methodCursor = getCursor().dropParentUntil(J.MethodDeclaration.class::isInstance);
                String name = methodCursor.getMessage(UNIT_OF_WORK_FACTORY_PARAMETER_KEY);
                if (name == null) {
                    name = unusedName(declaration, UNIT_OF_WORK_FACTORY_METHOD);
                    methodCursor.putMessage(UNIT_OF_WORK_FACTORY_PARAMETER_KEY, name);
                }
                maybeAddImport(UNIT_OF_WORK_FACTORY_FQN, null, false);
                return parameterReference(name);
            }
        };
    }

    private static J.@Nullable Identifier configurationVariable(List<? extends J> declarations) {
        J.Identifier found = null;
        for (J declaration : declarations) {
            if (declaration instanceof J.VariableDeclarations variables) {
                for (J.VariableDeclarations.NamedVariable variable : variables.getVariables()) {
                    if (TypeUtils.isOfClassType(variable.getType(), CONFIGURATION_FQN)) {
                        found = variable.getName();
                    }
                }
            }
        }
        return found == null ? null : found.withId(Tree.randomId()).withPrefix(Space.EMPTY);
    }

    /**
     * Returns the last {@code Configuration} local declared in the given block before the given statement, unless it
     * is assigned again, which would keep a lambda holding the builder from capturing it.
     */
    private static J.@Nullable Identifier configurationDeclaredBefore(J.Block block, J child) {
        List<Statement> preceding = new ArrayList<>();
        for (Statement statement : block.getStatements()) {
            if (statement.getId().equals(child.getId())) {
                J.Identifier configuration = configurationVariable(preceding);
                return configuration == null || isAssigned(block, configuration.getSimpleName())
                        ? null
                        : configuration;
            }
            preceding.add(statement);
        }
        return null;
    }

    private static boolean isAssigned(J.Block block, String name) {
        boolean[] assigned = {false};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Assignment visitAssignment(J.Assignment assignment, Integer p) {
                if (assignment.getVariable() instanceof J.Identifier variable
                        && name.equals(variable.getSimpleName())) {
                    assigned[0] = true;
                }
                return super.visitAssignment(assignment, p);
            }
        }.visit(block, 0);
        return assigned[0];
    }

    private static boolean isSpringBean(J.MethodDeclaration declaration) {
        return declaration.getLeadingAnnotations().stream().anyMatch(annotation -> {
            String type = resolvedName(annotation.getType());
            return type != null ? SPRING_BEAN_FQN.equals(type) : "Bean".equals(annotation.getSimpleName());
        });
    }

    private static boolean isUnitOfWorkFactoryType(TypeTree typeExpression) {
        String type = resolvedName(typeExpression.getType());
        if (type != null) {
            return UNIT_OF_WORK_FACTORY_FQN.equals(type);
        }
        return typeExpression instanceof J.Identifier identifier
                ? "UnitOfWorkFactory".equals(identifier.getSimpleName())
                : typeExpression instanceof J.FieldAccess fieldAccess
                && "UnitOfWorkFactory".equals(fieldAccess.getSimpleName());
    }

    private static String unusedName(J.MethodDeclaration declaration, String base) {
        Set<String> names = new HashSet<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
                names.add(identifier.getSimpleName());
                return identifier;
            }
        }.visit(declaration, 0);
        String name = base;
        for (int suffix = 2; names.contains(name); suffix++) {
            name = base + suffix;
        }
        return name;
    }

    /**
     * Counts the identifiers of the given name in the method, including its parameter declarations, so a parameter
     * that is declared and used once counts two. A shadowing variable or a field of that name counts as well; the name
     * of an invoked method, such as {@code serializer(..)} itself, does not.
     */
    private static int identifierCount(J.MethodDeclaration declaration, String name) {
        int[] count = {0};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
                boolean invokedMethod = getCursor().getParentTreeCursor().getValue() instanceof J.MethodInvocation call
                        && call.getName() == identifier;
                if (!invokedMethod && name.equals(identifier.getSimpleName())) {
                    count[0]++;
                }
                return identifier;
            }
        }.visit(declaration, 0);
        return count[0];
    }

    private static J.@Nullable VariableDeclarations serializerParameter(J.MethodDeclaration declaration,
                                                                       String name) {
        for (Statement parameter : declaration.getParameters()) {
            if (parameter instanceof J.VariableDeclarations variables
                    && variables.getVariables().size() == 1
                    && name.equals(variables.getVariables().getFirst().getSimpleName())
                    && variables.getTypeExpression() != null
                    && isSerializerType(variables.getTypeExpression())) {
                return variables;
            }
        }
        return null;
    }

    private static boolean isSerializerType(TypeTree typeExpression) {
        String type = resolvedName(typeExpression.getType());
        if (type != null) {
            return SERIALIZER_FQN.equals(type);
        }
        return typeExpression instanceof J.Identifier identifier
                ? "Serializer".equals(identifier.getSimpleName())
                : typeExpression instanceof J.FieldAccess fieldAccess
                && "Serializer".equals(fieldAccess.getSimpleName());
    }

    /**
     * Returns the fully qualified type and the base name of the parameter for the converter that succeeds the
     * serializer bean the given parameter receives, or {@code null} when the parameter carries an annotation other
     * than a {@code @Qualifier} naming one of the serializer beans of Axon Framework 4 Spring Boot. Without a
     * qualifier, the parameter received the primary, general serializer.
     */
    private static String @Nullable [] converterSucceeding(J.VariableDeclarations parameter) {
        List<J.Annotation> annotations = parameter.getLeadingAnnotations();
        if (annotations.isEmpty()) {
            return new String[]{GENERAL_CONVERTER_FQN, "generalConverter"};
        }
        if (annotations.size() != 1) {
            return null;
        }
        String qualifier = qualifierValue(annotations.getFirst());
        if (qualifier == null) {
            return null;
        }
        return switch (qualifier) {
            case "serializer" -> new String[]{GENERAL_CONVERTER_FQN, "generalConverter"};
            case "messageSerializer" -> new String[]{MESSAGE_CONVERTER_FQN, "messageConverter"};
            case "eventSerializer" -> new String[]{EVENT_CONVERTER_FQN, "eventConverter"};
            default -> null;
        };
    }

    private static @Nullable String qualifierValue(J.Annotation annotation) {
        String type = resolvedName(annotation.getType());
        boolean qualifier = type != null
                ? QUALIFIER_FQN.equals(type)
                : "Qualifier".equals(annotation.getSimpleName());
        if (!qualifier || annotation.getArguments() == null || annotation.getArguments().size() != 1) {
            return null;
        }
        Expression argument = annotation.getArguments().getFirst();
        if (argument instanceof J.Assignment assignment
                && assignment.getVariable() instanceof J.Identifier name
                && "value".equals(name.getSimpleName())) {
            argument = assignment.getAssignment();
        }
        return argument instanceof J.Literal literal && literal.getValue() instanceof String value ? value : null;
    }

    private static J.Identifier parameterReference(String name) {
        return new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, List.of(), name,
                                JavaType.ShallowClass.build(UNIT_OF_WORK_FACTORY_FQN), null);
    }

    /**
     * Wraps the {@code <Manager>.builder()} call of the given chain in {@code .unitOfWorkFactory(factory)}, laid out
     * like the call that follows it.
     */
    private static J.MethodInvocation insertAfterBuilder(J.MethodInvocation invocation, Expression factory) {
        if (isBuilderCall(invocation)) {
            return unitOfWorkFactoryCall(invocation, Space.EMPTY, factory);
        }
        JRightPadded<Expression> select = invocation.getPadding().getSelect();
        if (select == null || !(select.getElement() instanceof J.MethodInvocation inner)) {
            return invocation;
        }
        J.MethodInvocation replaced = isBuilderCall(inner)
                ? unitOfWorkFactoryCall(inner, select.getAfter(), factory)
                : insertAfterBuilder(inner, factory);
        return invocation.getPadding().withSelect(select.withElement(replaced));
    }

    private static boolean isBuilderCall(J.MethodInvocation invocation) {
        return "builder".equals(invocation.getSimpleName())
                && invocation.getSelect() != null
                && managerOfTypeReference(invocation.getSelect()) != null;
    }

    private static J.MethodInvocation unitOfWorkFactoryCall(J.MethodInvocation builder, Space before,
                                                            Expression factory) {
        J.Identifier name = new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, List.of(),
                                             UNIT_OF_WORK_FACTORY_METHOD, null, null);
        JContainer<Expression> arguments = JContainer.build(
                Space.EMPTY, List.of(JRightPadded.build(factory.withPrefix(Space.EMPTY))), Markers.EMPTY);
        return new J.MethodInvocation(Tree.randomId(), builder.getPrefix(), Markers.EMPTY,
                                      new JRightPadded<>(builder.withPrefix(Space.EMPTY), before, Markers.EMPTY),
                                      null, name, arguments, null);
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
     * their own statements. A statement holding a {@code converter(..)} call that replaced a {@code serializer(..)}
     * call is marked to check the converter against the deadlines stored before.
     */
    private static Statement markBuilders(Statement statement, Set<UUID> convertedSerializerCalls) {
        Set<String> markers = new HashSet<>();
        Statement[] marked = {statement};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Block visitBlock(J.Block block, Integer p) {
                return block;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation, Integer p) {
                Expression call = invocation;
                while (call instanceof J.MethodInvocation chained) {
                    if (convertedSerializerCalls.contains(chained.getId())
                            && markers.add(STORED_DEADLINES_MARKER)) {
                        marked[0] = mark(marked[0], STORED_DEADLINES_MARKER, STORED_DEADLINES_TODO);
                    }
                    call = chained.getSelect();
                }
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
