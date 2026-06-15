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

package org.axonframework.modelling.command.inspection;

import org.axonframework.commandhandling.CommandMessageHandlingMember;
import org.axonframework.common.IdentifierValidator;
import org.axonframework.common.ReflectionUtils;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.messaging.Message;
import org.axonframework.messaging.annotation.AnnotatedHandlerInspector;
import org.axonframework.messaging.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.annotation.HandlerDefinition;
import org.axonframework.messaging.annotation.MessageHandlerInvocationException;
import org.axonframework.messaging.annotation.MessageHandlingMember;
import org.axonframework.messaging.annotation.ParameterResolverFactory;
import org.axonframework.modelling.command.AggregateVersion;
import org.axonframework.modelling.command.EntityId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static org.axonframework.common.ListUtils.distinct;
import static org.axonframework.common.ReflectionUtils.NOT_RECURSIVE;
import static org.axonframework.common.annotation.AnnotationUtils.findAnnotationAttributes;

/**
 * AggregateMetaModelFactory implementation that uses annotations on the target aggregate's members to build up the
 * meta-model of the aggregate.
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @since 3.1
 */
public class AnnotatedAggregateMetaModelFactory implements AggregateMetaModelFactory {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final Map<Class<?>, AnnotatedAggregateModel> registry;
    private final ParameterResolverFactory parameterResolverFactory;
    private final HandlerDefinition handlerDefinition;

    /**
     * Shorthand to create a factory instance and inspect the model for the given {@code aggregateType}.
     *
     * @param aggregateType The class of the aggregate to create the model for
     * @param <T>           The type of aggregate described in the model
     * @return The model describing the structure of the aggregate
     */
    public static <T> AggregateModel<T> inspectAggregate(Class<T> aggregateType) {
        return new AnnotatedAggregateMetaModelFactory().createModel(aggregateType);
    }

    /**
     * Shorthand to create a factory instance and inspect the model for the given {@code aggregateType} and its {@code
     * subtypes}.
     *
     * @param aggregateType The class of the aggregate to create the model for
     * @param subtypes      Subtypes of this aggregate class
     * @param <T>           The type of aggregate described in the model
     * @return The model describing the structure of the aggregate
     */
    public static <T> AggregateModel<T> inspectAggregate(Class<T> aggregateType, Set<Class<? extends T>> subtypes) {
        return new AnnotatedAggregateMetaModelFactory().createModel(aggregateType, subtypes);
    }

    /**
     * Shorthand to create a factory instance and inspect the model for the given {@code aggregateType}, using given
     * {@code parameterResolverFactory} to resolve parameter values for annotated handlers.
     *
     * @param aggregateType            the class of the aggregate to create the model for
     * @param parameterResolverFactory to resolve parameter values of annotated handlers with
     * @param <T>                      the type of aggregate described in the model
     * @return the model describing the structure of the aggregate
     */
    public static <T> AggregateModel<T> inspectAggregate(Class<T> aggregateType,
                                                         ParameterResolverFactory parameterResolverFactory) {
        return new AnnotatedAggregateMetaModelFactory(parameterResolverFactory).createModel(aggregateType);
    }

    /**
     * Shorthand to create a factory instance and inspect the model for the given {@code aggregateType}, using given
     * {@code parameterResolverFactory} to resolve parameter values for annotated handlers and {@code handlerDefinition}
     * to create concrete handlers.
     *
     * @param aggregateType            The class of the aggregate to create the model for
     * @param parameterResolverFactory to resolve parameter values of annotated handlers with
     * @param handlerDefinition        The handler definition used to create concrete handlers
     * @param <T>                      The type of aggregate described in the model
     * @return The model describing the structure of the aggregate
     */
    public static <T> AggregateModel<T> inspectAggregate(Class<T> aggregateType,
                                                         ParameterResolverFactory parameterResolverFactory,
                                                         HandlerDefinition handlerDefinition) {
        return new AnnotatedAggregateMetaModelFactory(parameterResolverFactory, handlerDefinition)
                .createModel(aggregateType);
    }

    /**
     * Shorthand to create a factory instance and inspect the model for the given {@code aggregateType} and its {@code
     * subytpes}, using given {@code parameterResolverFactory} to resolve parameter values for annotated handlers and
     * {@code handlerDefinition} to create concrete handlers.
     *
     * @param aggregateType            The class of the aggregate to create the model for
     * @param parameterResolverFactory to resolve parameter values of annotated handlers with
     * @param handlerDefinition        The handler definition used to create concrete handlers
     * @param subtypes                 Subtypes of this aggregate class
     * @param <T>                      The type of aggregate described in the model
     * @return The model describing the structure of the aggregate
     */
    public static <T> AggregateModel<T> inspectAggregate(Class<T> aggregateType,
                                                         ParameterResolverFactory parameterResolverFactory,
                                                         HandlerDefinition handlerDefinition,
                                                         Set<Class<? extends T>> subtypes) {
        return new AnnotatedAggregateMetaModelFactory(parameterResolverFactory, handlerDefinition)
                .createModel(aggregateType, subtypes);
    }

    /**
     * Initializes an instance which uses the default, classpath based, ParameterResolverFactory to detect parameters
     * for annotated handlers.
     */
    public AnnotatedAggregateMetaModelFactory() {
        this(ClasspathParameterResolverFactory.forClassLoader(Thread.currentThread().getContextClassLoader()));
    }

    /**
     * Initializes an instance which uses the given {@code parameterResolverFactory} to detect parameters for annotated
     * handlers.
     *
     * @param parameterResolverFactory to resolve parameter values of annotated handlers with
     */
    public AnnotatedAggregateMetaModelFactory(ParameterResolverFactory parameterResolverFactory) {
        this(parameterResolverFactory,
             ClasspathHandlerDefinition.forClassLoader(Thread.currentThread().getContextClassLoader()));
    }

    /**
     * Initializes an instance which uses the given {@code parameterResolverFactory} to detect parameters for annotated
     * handlers and {@code handlerDefinition} to create concrete handlers.
     *
     * @param parameterResolverFactory to resolve parameter values of annotated handlers with
     * @param handlerDefinition        The handler definition used to create concrete handlers
     */
    public AnnotatedAggregateMetaModelFactory(ParameterResolverFactory parameterResolverFactory,
                                              HandlerDefinition handlerDefinition) {
        this.parameterResolverFactory = parameterResolverFactory;
        this.handlerDefinition = handlerDefinition;
        registry = new ConcurrentHashMap<>();
    }

    @Override
    public <T> AnnotatedAggregateModel<T> createModel(Class<? extends T> aggregateType,
                                                      Set<Class<? extends T>> subtypes) {
        if (!registry.containsKey(aggregateType)) {
            AnnotatedHandlerInspector<T> inspector = AnnotatedHandlerInspector.inspectType(aggregateType,
                                                                                           parameterResolverFactory,
                                                                                           handlerDefinition,
                                                                                           subtypes);
            AnnotatedAggregateModel<T> model = new AnnotatedAggregateModel<>(aggregateType, inspector);
            // Add the newly created inspector to the registry first to prevent a StackOverflowError:
            // another call to createInspector with the same inspectedType will return this instance of the inspector.
            registry.put(aggregateType, model);
            model.initialize();
        }
        //noinspection unchecked
        return registry.get(aggregateType).whenReadSafe();
    }

    private class AnnotatedAggregateModel<T> implements AggregateModel<T> {

        private static final String JAKARTA_PERSISTENCE_ID = "jakarta.persistence.Id";
        private static final String JAVAX_PERSISTENCE_ID = "javax.persistence.Id";

        private final Class<? extends T> inspectedType;
        private final Map<Class<?>, List<ChildEntity<T>>> children;
        private final AnnotatedHandlerInspector<T> handlerInspector;
        private final Map<Class<?>, List<MessageHandlingMember<? super T>>> allCommandHandlerInterceptors;
        private final Map<Class<?>, List<MessageHandlingMember<? super T>>> allCommandHandlers;
        private final Map<Class<?>, List<MessageHandlingMember<? super T>>> allEventHandlers;

        private final Map<String, Class<?>> types;
        private final Map<Class<?>, String> declaredTypes;
        private Member identifierMember;
        private Member versionMember;
        private String routingKey;
        private final ThreadLocal<Boolean> initializing = new ThreadLocal<>();
        private volatile boolean initialized;

        public AnnotatedAggregateModel(Class<? extends T> aggregateType,
                                       AnnotatedHandlerInspector<T> handlerInspector) {
            this.inspectedType = aggregateType;
            this.types = new HashMap<>();
            this.declaredTypes = new HashMap<>();
            this.allCommandHandlerInterceptors = new HashMap<>();
            this.allCommandHandlers = new HashMap<>();
            this.allEventHandlers = new HashMap<>();
            this.children = new HashMap<>();
            this.handlerInspector = handlerInspector;
        }

        private void initialize() {
            initializing.set(Boolean.TRUE);
            inspectFieldsAndMethods();
            inspectAggregateTypes();
            prepareHandlers();
            initialized = true;
            initializing.remove();
        }

        private void prepareHandlers() {
            for (Map.Entry<Class<?>, SortedSet<MessageHandlingMember<? super T>>> handlersPerType
                    : handlerInspector.getAllHandlers().entrySet()) {
                Class<?> type = handlersPerType.getKey();
                for (MessageHandlingMember<? super T> handler : handlersPerType.getValue()) {
                    if (handler.unwrap(CommandMessageHandlingMember.class).isPresent()) {
                        if (Modifier.isAbstract(type.getModifiers()) && handler.unwrap(Constructor.class).isPresent()) {
                            throw new AggregateModellingException(format(
                                    "An abstract aggregate %s cannot have @CommandHandler on constructor.", type
                            ));
                        }
                        addHandler(allCommandHandlers, type, handler);
                    } else {
                        addHandler(allEventHandlers, type, handler);
                    }
                }
            }

            for (Map.Entry<Class<?>, SortedSet<MessageHandlingMember<? super T>>> interceptorsPerType
                    : handlerInspector.getAllInterceptors().entrySet()) {
                Class<?> type = interceptorsPerType.getKey();
                for (MessageHandlingMember<? super T> handler : interceptorsPerType.getValue()) {
                    addHandler(allCommandHandlerInterceptors, type, handler);
                }
            }
            prepareChildEntityCommandHandlers();
            validateCommandHandlers();
        }

        /**
         * For every discovered type of the aggregate hierarchy, check whether there are
         * {@link ChildEntity ChildEntitys} present. If they are not present on the type's level, move to that class'
         * superclass (if possible) and check whether it has any {@code ChildEntity} instances registered. If this is
         * the case, add them to the {@link Class} type being validated. Doing so ensures that each level in the
         * hierarchy knows of all it's entities' command handlers and its parent their entity command handlers.
         */
        private void prepareChildEntityCommandHandlers() {
            for (Class<?> aggregateType : types.values()) {
                Class<?> type = aggregateType;
                List<ChildEntity<T>> childrenPerType = new ArrayList<>(children.getOrDefault(type, Collections.emptyList()));
                while (!type.equals(Object.class) && type.getSuperclass() != null) {
                    type = type.getSuperclass();
                    childrenPerType.addAll(new ArrayList<>(children.getOrDefault(type, Collections.emptyList())));
                }

                for (ChildEntity<T> child : childrenPerType) {
                    child.commandHandlers().forEach(
                            childCommandHandler -> addHandler(allCommandHandlers, aggregateType, childCommandHandler)
                    );
                }
            }
        }

        private void addHandler(Map<Class<?>, List<MessageHandlingMember<? super T>>> handlers, Class<?> type,
                                MessageHandlingMember<? super T> handler) {
            handlers.computeIfAbsent(type, t -> new ArrayList<>())
                    .add(handler);
        }

        /**
         * In polymorphic aggregate hierarchy there must not be more than one creational (factory) command handler (of
         * the same command name) in more than one aggregate.
         */
        private void validateCommandHandlers() {
            List<List<MessageHandlingMember<? super T>>> handlers = new ArrayList<>(allCommandHandlers.values());
            for (int i = 0; i < handlers.size() - 1; i++) {
                List<CommandMessageHandlingMember<? super T>> factoryCommands1 = factoryCommands(handlers.get(i));
                List<CommandMessageHandlingMember<? super T>> factoryCommands2 = factoryCommands(handlers.get(i + 1));
                for (CommandMessageHandlingMember<? super T> handler1 : factoryCommands1) {
                    for (CommandMessageHandlingMember<? super T> handler2 : factoryCommands2) {
                        String commandName1 = handler1.commandName();
                        String commandName2 = handler2.commandName();
                        if (commandName1.equals(commandName2)) {
                            Class<?> declaringClass1 = handler1.declaringClass();
                            Class<?> declaringClass2 = handler2.declaringClass();
                            if (!declaringClass1.equals(declaringClass2)) {
                                throw new AggregateModellingException(format(
                                        "Aggregates %s and %s have the same creation @CommandHandler %s",
                                        declaringClass1,
                                        declaringClass2,
                                        commandName1));
                            }
                        }
                    }
                }
            }
        }

        @SuppressWarnings("unchecked")
        private List<CommandMessageHandlingMember<? super T>> factoryCommands(
                List<MessageHandlingMember<? super T>> handlers) {
            return handlers.stream()
                           .map(h -> h.unwrap(CommandMessageHandlingMember.class))
                           .filter(Optional::isPresent)
                           .map(Optional::get)
                           .filter(CommandMessageHandlingMember::isFactoryHandler)
                           .map(h -> (CommandMessageHandlingMember<? super T>) h)
                           .collect(toList());
        }

        private void inspectAggregateTypes() {
            for (Class<?> type : handlerInspector.getAllHandlers().keySet()) {
                String declaredType = AggregateTypeUtils.declaredTypeOf(type);
                types.put(declaredType, type);
                declaredTypes.put(type, declaredType);
            }
        }

        private void inspectFieldsAndMethods() {
            ServiceLoader<ChildEntityDefinition> childEntityDefinitions =
                    ServiceLoader.load(ChildEntityDefinition.class, inspectedType.getClassLoader());
            List<Member> entityIdMembers = new ArrayList<>();
            List<Member> persistenceIdMembers = new ArrayList<>();
            List<Member> aggregateVersionMembers = new ArrayList<>();
            for (Class<?> handledType : handlerInspector.getAllInspectedTypes()) {
                // Navigate fields for Axon related annotations
                for (Field field : ReflectionUtils.fieldsOf(handledType, NOT_RECURSIVE)) {
                    createChildDefinitions(childEntityDefinitions, handledType, field);
                    findAnnotationAttributes(field, EntityId.class).ifPresent(attributes -> entityIdMembers.add(field));
                    findAnnotationAttributes(field, JAKARTA_PERSISTENCE_ID).ifPresent(
                            attributes -> persistenceIdMembers.add(field)
                    );
                    findAnnotationAttributes(field, JAVAX_PERSISTENCE_ID).ifPresent(
                            attributes -> persistenceIdMembers.add(field)
                    );
                    findAnnotationAttributes(field, AggregateVersion.class).ifPresent(
                            attributes -> aggregateVersionMembers.add(field)
                    );
                }
                // Navigate methods for Axon related annotations
                for (Method method : ReflectionUtils.methodsOf(handledType, NOT_RECURSIVE)) {
                    createChildDefinitions(childEntityDefinitions, handledType, method);
                    findAnnotationAttributes(method, EntityId.class).ifPresent(attributes -> {
                        assertValidValueProvidingMethod(method, EntityId.class.getSimpleName());
                        entityIdMembers.add(method);
                    });
                    findAnnotationAttributes(method, JAKARTA_PERSISTENCE_ID).ifPresent(attributes -> {
                        assertValidValueProvidingMethod(method, JAKARTA_PERSISTENCE_ID);
                        persistenceIdMembers.add(method);
                    });
                    findAnnotationAttributes(method, JAVAX_PERSISTENCE_ID).ifPresent(attributes -> {
                        assertValidValueProvidingMethod(method, JAVAX_PERSISTENCE_ID);
                        persistenceIdMembers.add(method);
                    });
                    findAnnotationAttributes(method, AggregateVersion.class).ifPresent(attributes -> {
                        assertValidValueProvidingMethod(method, AggregateVersion.class.getSimpleName());
                        aggregateVersionMembers.add(method);
                    });
                }
            }

            findIdentifierMember(distinct(entityIdMembers), distinct(persistenceIdMembers))
                    .ifPresent(this::setIdentifierAndRoutingKey);
            setVersionMember(aggregateVersionMembers);
            assertIdentifierValidity(identifierMember);
        }

        private void createChildDefinitions(ServiceLoader<ChildEntityDefinition> childEntityDefinitions,
                                            Class<?> type,
                                            Member entityMember) {
            childEntityDefinitions.forEach(
                    definition -> definition.createChildDefinition(entityMember, this)
                                            .ifPresent(
                                                    child -> children.computeIfAbsent(type, t -> new ArrayList<>())
                                                                     .add(child)
                                            )
            );
        }

        private void assertValidValueProvidingMethod(Method method, String annotationName) {
            if (method.getParameterCount() != 0) {
                throw new AggregateModellingException(format(
                        "Aggregate [%s] has an [%s] annotated method [%s] with parameters, "
                                + "whilst none are allowed on such a method.",
                        inspectedType, annotationName, method
                ));
            }
            if (method.getReturnType() == Void.TYPE) {
                throw new AggregateModellingException(format(
                        "Aggregate [%s] has an [%s] annotated method [%s] with void return type, "
                                + "whilst a return value is required for such a method.",
                        inspectedType, annotationName, method
                ));
            }
        }

        private Optional<Member> findIdentifierMember(List<Member> entityIdMembers,
                                                      List<Member> persistenceIdMembers) {
            if (entityIdMembers.size() > 1) {
                throw new AggregateModellingException(format(
                        "Aggregate [%s] has more than one identifier member, while only a single member is allowed.",
                        inspectedType
                ));
            }
            if (!entityIdMembers.isEmpty()) {
                return Optional.of(entityIdMembers.get(0));
            } else if (!persistenceIdMembers.isEmpty()) {
                return Optional.of(persistenceIdMembers.get(0));
            }
            return Optional.empty();
        }

        private void setIdentifierAndRoutingKey(Member identifier) {
            identifierMember = identifier;
            routingKey = findRoutingKey((AccessibleObject) identifier).orElseGet(
                    () -> getMemberIdentifierName(identifier)
            );
        }

        private Optional<String> findRoutingKey(AccessibleObject accessibleObject) {
            return AnnotationUtils.<String>findAnnotationAttribute(accessibleObject, EntityId.class, "routingKey")
                    .filter(key -> !"".equals(key));
        }

        /**
         * Return the given {@code identifierMember}'s {@link Member#getName()}. If the given {@code identifierMember}
         * is of type {@link Method} and it resembles a regular getter method, the {@code "get"} will be stripped off.
         *
         * @param identifierMember the {@link Member} to retrieve the name for
         * @return the identifier name tied to the given {@code identifierMember}
         */
        private String getMemberIdentifierName(Member identifierMember) {
            String identifierName = identifierMember.getName();
            return identifierMember instanceof Method && isGetterByConvention(identifierName)
                    ? stripGetterConvention(identifierName)
                    : identifierName;
        }

        private boolean isGetterByConvention(String identifierName) {
            return identifierName.startsWith("get")
                    && identifierName.length() >= 4
                    && Character.isUpperCase(identifierName.charAt(3));
        }

        private String stripGetterConvention(String identifierName) {
            return identifierName.substring(3, 4).toLowerCase() + identifierName.substring(4);
        }

        private void setVersionMember(List<Member> versionMembers) {
            if (versionMembers.isEmpty()) {
                logger.debug("No @AggregateVersion annotated Member found.");
                return;
            }
            if (versionMembers.size() > 1) {
                String versionMembersString = versionMembers.stream()
                                                            .map(Member::getName)
                                                            .collect(Collectors.joining(", "));
                throw new AggregateModellingException(format(
                        "Aggregate [%s] has two or more @AggregateVersion annotated members, "
                                + "whilst only a single member is allowed.\n "
                                + "The following version members have been found: %s",
                        inspectedType, versionMembersString
                ));
            }

            logger.debug(
                    "@AggregateVersion annotated Member [{}] has been found and set as the [{}] Aggregate Version.",
                    versionMembers.get(0).getName(), inspectedType
            );
            this.versionMember = versionMembers.get(0);
        }

        private void assertIdentifierValidity(Member identifier) {
            if (identifier != null) {
                final Class<?> idClazz = ReflectionUtils.getMemberValueType(identifier);
                if (!IdentifierValidator.getInstance().isValidIdentifier(idClazz)) {
                    throw new AggregateModellingException(format(
                            "Aggregate identifier type [%s] should override Object.toString()",
                            idClazz.getName()
                    ));
                }
            }
        }

        @SuppressWarnings("unchecked")
        private AnnotatedAggregateModel<T> runtimeModelOf(T target) {
            return modelOf((Class<? extends T>) target.getClass());
        }

        @Override
        public Map<Class<?>, List<MessageHandlingMember<? super T>>> allCommandHandlers() {
            return Collections.unmodifiableMap(allCommandHandlers);
        }

        @Override
        public Stream<MessageHandlingMember<? super T>> commandHandlers(Class<? extends T> subtype) {
            return handlers(allCommandHandlers, subtype);
        }

        @Override
        public Stream<Class<?>> types() {
            return handlerInspector.getAllHandlers()
                                   .keySet()
                                   .stream();
        }

        @Override
        public <C> AnnotatedAggregateModel<C> modelOf(Class<? extends C> childEntityType) {
            // using empty list subtypes because this model is already in the registry, so it doesn't matter
            return AnnotatedAggregateMetaModelFactory.this.createModel(childEntityType, Collections.emptySet());
        }

        @Override
        public Class<? extends T> entityClass() {
            return inspectedType;
        }

        @Override
        public void publish(EventMessage<?> message, T target) {
            if (target != null) {
                runtimeModelOf(target).doPublish(message, target);
            }
        }

        private void doPublish(EventMessage<?> message, T target) {
            getHandler(message, target.getClass()).ifPresent(h -> {
                try {
                    handlerInspector.chainedInterceptor(target.getClass())
                                    .handle(message, target, h);
                } catch (Exception e) {
                    throw new MessageHandlerInvocationException(
                            format("Error handling event of type [%s] in aggregate", message.getPayloadType()), e);
                }
            });
            children.values().stream()
                    .flatMap(Collection::stream)
                    .forEach(childEntity -> childEntity.publish(message, target));
        }

        @Override
        public String type() {
            return declaredTypes.get(inspectedType);
        }

        @Override
        public Optional<Class<?>> type(String declaredType) {
            return Optional.ofNullable(types.getOrDefault(declaredType, null));
        }

        @Override
        public Optional<String> declaredType(Class<?> type) {
            return Optional.ofNullable(declaredTypes.getOrDefault(type, null));
        }

        @Override
        public Long getVersion(T target) {
            return versionMember != null ? ReflectionUtils.<Long>getMemberValue(versionMember, target) : null;
        }

        @Override
        public Map<Class<?>, List<MessageHandlingMember<? super T>>> allCommandHandlerInterceptors() {
            return Collections.unmodifiableMap(allCommandHandlerInterceptors);
        }

        @Override
        public Stream<MessageHandlingMember<? super T>> commandHandlerInterceptors(Class<? extends T> subtype) {
            return handlers(allCommandHandlerInterceptors, subtype);
        }

        /**
         * Returns the {@link MessageHandlingMember} that is capable of handling the given {@code message}. If no member
         * is found an empty optional is returned.
         *
         * @param message     the message to find a handler for
         * @param targetClass the target class that handler should be executed on
         * @return the handler of the message if present on the model
         */
        protected Optional<MessageHandlingMember<? super T>> getHandler(Message<?> message, Class<?> targetClass) {
            return handlers(allEventHandlers, targetClass)
                    .filter(handler -> handler.canHandle(message))
                    .findFirst();
        }

        @Override
        public Map<Class<?>, List<MessageHandlingMember<? super T>>> allEventHandlers() {
            return Collections.unmodifiableMap(allEventHandlers);
        }

        // Backwards compatibility - if you don't specify a designated child,
        //  you should at least get handlers of its first registered parent (if any).
        private Stream<MessageHandlingMember<? super T>> handlers(
                Map<Class<?>, List<MessageHandlingMember<? super T>>> handlers, Class<?> subtype
        ) {
            Class<?> type = subtype;
            while (!handlers.containsKey(type) && !Objects.equals(type, Object.class) && type.getSuperclass() != null) {
                type = type.getSuperclass();
            }
            return handlers.getOrDefault(type, Collections.emptyList()).stream();
        }

        @Override
        public Object getIdentifier(T target) {
            return identifierMember != null ? ReflectionUtils.getMemberValue(identifierMember, target) : null;
        }

        @Override
        public String routingKey() {
            return routingKey;
        }

        /**
         * Returns this instance when it is safe to read from. This is either if the current thread is already
         * initializing this instance, or when the instance is fully initialized.
         *
         * @return this instance, as soon as it is safe for reading
         */
        private AnnotatedAggregateModel<T> whenReadSafe() {
            if (Boolean.TRUE.equals(initializing.get())) {
                // This thread is initializing. To prevent deadlocks, we should return this instance immediately
                return this;
            }
            while (!initialized) {
                // another thread is initializing, it shouldn't take long...
                Thread.yield();
            }
            // we're safe to go
            return this;
        }
    }
}
