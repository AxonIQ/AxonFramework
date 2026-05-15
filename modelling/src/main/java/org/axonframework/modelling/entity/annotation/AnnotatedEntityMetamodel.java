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

package org.axonframework.modelling.entity.annotation;

import org.axonframework.common.Assert;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.ReflectionUtils;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.conversion.ConversionException;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandlingMember;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.AnnotatedHandlerInspector;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.modelling.annotation.AnnotationBasedEntityEvolvingComponent;
import org.axonframework.modelling.entity.ConcreteEntityMetamodel;
import org.axonframework.modelling.entity.EntityMetamodel;
import org.axonframework.modelling.entity.EntityMetamodelBuilder;
import org.axonframework.modelling.entity.PolymorphicEntityMetamodel;
import org.axonframework.modelling.entity.child.EntityChildMetamodel;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static org.axonframework.common.ReflectionUtils.collectMethodsAndFields;
import static org.axonframework.common.ReflectionUtils.collectSealedHierarchyIfSealed;
import static org.axonframework.common.annotation.AnnotationUtils.isAnnotatedWith;
import static org.axonframework.messaging.core.annotation.AnnotatedHandlerInspector.inspectType;

/**
 * An {@link EntityMetamodel} implementation that uses reflection to inspect the entity. It will detect annotated
 * command- and event-handling methods, as well as child entities annotated with {@link EntityMember}. Everything that
 * is discovered is then registered to a delegate {@link EntityMetamodel}, so that essentially a declared metamodel is
 * built of which it's structure is clearly defined.
 * <p>
 * Besides normal {@link EntityMetamodel} operations, this metamodel also provides a means to
 * {@link #getExpectedRepresentation(QualifiedName) get the expected representation} of a command or event handler based
 * on the {@link QualifiedName} of the message type. This is useful for determining the payload type of a command or
 * event handler when multiple handlers are present for the same message type.
 * <p>
 * NOTE: This class is a complete rewrite of the pre-5.0.0
 * {@code org.axonframework.modelling.command.inspection.AnnotatedAggregateMetaModelFactory}. Both scan the class for
 * annotated methods and fields, but the AnnotatedEntityModel dropped aggregate versioning (conflict resolution), no
 * longer required an id in the entity, and creates a declarative metamodel instead of relying on reflection at
 * runtime.
 *
 * @param <E> The type of entity this metamodel describes.
 * @author Mitchell Herrijgers
 * @author Allard Buijze
 * @since 3.1.0
 */
public class AnnotatedEntityMetamodel<E> implements EntityMetamodel<E>, DescribableComponent {

    private static final Logger logger = LoggerFactory.getLogger(AnnotatedEntityMetamodel.class);

    private final Class<E> entityType;
    private final EntityMetamodel<E> delegateMetamodel;
    private final ParameterResolverFactory parameterResolverFactory;
    private final MessageTypeResolver messageTypeResolver;
    private final MessageConverter messageConverter;
    private final EventConverter eventConverter;
    private final Map<QualifiedName, Class<?>> payloadTypes = new HashMap<>();
    private final List<AnnotatedEntityMetamodel<?>> concreteMetamodels = new LinkedList<>();
    private final List<AnnotatedEntityMetamodel<?>> childMetamodels = new LinkedList<>();
    private final List<QualifiedName> commandsToSkip;

    /**
     * Instantiate an annotated {@link EntityMetamodel} of a concrete entity type.
     *
     * @param entityType               The concrete entity type this metamodel describes.
     * @param parameterResolverFactory The {@link ParameterResolverFactory} to use for resolving parameters.
     * @param messageTypeResolver      The {@link MessageTypeResolver} to use for resolving message types from payload
     *                                 classes.
     * @param messageConverter         The converter used to convert the {@link CommandMessage#payload()} to the desired
     *                                 format.
     * @param eventConverter           The converter used to convert the {@link EventMessage#payload()} to the desired
     *                                 format.
     * @param <E>                      The type of entity this metamodel describes.
     * @return An annotated {@link EntityMetamodel} backed by a {@link ConcreteEntityMetamodel} for the given entity
     * type.
     */
    public static <E> AnnotatedEntityMetamodel<E> forConcreteType(
            Class<E> entityType,
            ParameterResolverFactory parameterResolverFactory,
            MessageTypeResolver messageTypeResolver,
            MessageConverter messageConverter,
            EventConverter eventConverter
    ) {
        return new AnnotatedEntityMetamodel<>(entityType,
                                              Set.of(),
                                              parameterResolverFactory,
                                              messageTypeResolver,
                                              messageConverter,
                                              eventConverter,
                                              List.of());
    }

    /**
     * Instantiate an annotated {@link EntityMetamodel} of a polymorphic sealed entity type. At least one concrete type
     * must exist, as this metamodel is meant to describe a polymorphic entity type with multiple concrete
     * implementations.
     *
     * @param entityType               The polymorphic sealed entity type this metamodel describes.
     * @param parameterResolverFactory The {@link ParameterResolverFactory} to use for resolving parameters.
     * @param messageTypeResolver      The {@link MessageTypeResolver} to use for resolving message types from payload
     *                                 classes.
     * @param messageConverter         The converter used to convert the {@link CommandMessage#payload()} to the desired
     *                                 format.
     * @param eventConverter           The event converter used to convert the {@link EventMessage#payload()} to the
     *                                 desired format.
     * @param <E>                      The type of the polymorphic entity.
     * @return An annotated {@link EntityMetamodel} backed by a {@link PolymorphicEntityMetamodel} for the given entity
     * type.
     * @see AnnotatedEntityMetamodel#forPolymorphicType(Class, Set, ParameterResolverFactory, MessageTypeResolver,
     * MessageConverter, EventConverter)
     */
    public static <E> AnnotatedEntityMetamodel<E> forPolymorphicSealedType(
            Class<E> entityType,
            ParameterResolverFactory parameterResolverFactory,
            MessageTypeResolver messageTypeResolver,
            MessageConverter messageConverter,
            EventConverter eventConverter
    ) {
        Assert.isTrue(entityType.isSealed(), () -> "The entity type [" + entityType + "] is not sealed.");
        return forPolymorphicType(entityType,
                                  collectSealedHierarchyIfSealed(entityType),
                                  parameterResolverFactory,
                                  messageTypeResolver,
                                  messageConverter, eventConverter
        );
    }

    /**
     * Instantiate an annotated {@link EntityMetamodel} of a polymorphic entity type. At least one concrete type must be
     * supplied, as this metamodel is meant to describe a polymorphic entity type with multiple concrete
     * implementations.
     *
     * @param entityType               The polymorphic entity type this metamodel describes.
     * @param concreteTypes            The concrete types of the polymorphic entity type.
     * @param parameterResolverFactory The {@link ParameterResolverFactory} to use for resolving parameters.
     * @param messageTypeResolver      The {@link MessageTypeResolver} to use for resolving message types from payload
     *                                 classes.
     * @param messageConverter         The converter used to convert the {@link CommandMessage#payload()} to the desired
     *                                 format.
     * @param eventConverter           The event converter used to convert the {@link EventMessage#payload()} to the
     *                                 desired format.
     * @param <E>                      The type of the polymorphic entity.
     * @return An annotated {@link EntityMetamodel} backed by a {@link PolymorphicEntityMetamodel} for the given entity
     * type.
     */
    public static <E> AnnotatedEntityMetamodel<E> forPolymorphicType(
            Class<E> entityType,
            Set<Class<? extends E>> concreteTypes,
            ParameterResolverFactory parameterResolverFactory,
            MessageTypeResolver messageTypeResolver,
            MessageConverter messageConverter,
            EventConverter eventConverter
    ) {
        requireNonNull(concreteTypes, "The concreteTypes may not be null.");
        Assert.isTrue(!concreteTypes.isEmpty(),
                      () -> "The concreteTypes set must not be empty for a polymorphic entity type.");
        return new AnnotatedEntityMetamodel<>(entityType,
                                              concreteTypes,
                                              parameterResolverFactory,
                                              messageTypeResolver,
                                              messageConverter,
                                              eventConverter,
                                              List.of());
    }

    /**
     * Instantiate an annotated {@link EntityMetamodel} of an entity type. If the supplied {@code concreteTypes} is not
     * empty, the entity type is considered polymorphic and will be a {@link PolymorphicEntityMetamodel}. If the entity
     * type is sealed, all concrete types in the sealed hierarchy will be automatically discovered and their event
     * handlers will be registered. If no concrete types are supplied, the entity type is considered concrete and will
     * be a {@link ConcreteEntityMetamodel}.
     *
     * @param entityType               The concrete entity type this metamodel describes.
     * @param parameterResolverFactory The {@link ParameterResolverFactory} to use for resolving parameters.
     * @param messageTypeResolver      The {@link MessageTypeResolver} to use for resolving message types from payload
     *                                 classes.
     * @param concreteTypes            The concrete types of the polymorphic entity type.
     * @param eventConverter           The converter used to convert the {@link EventMessage#payload()} to the desired
     *                                 format.
     * @param commandsToSkip           The commands to skip when initializing the metamodel. This is useful to prevent
     *                                 concrete implementations from registering commands that are already registered by
     *                                 the abstract entity type, as this will lead to problems.
     */
    private AnnotatedEntityMetamodel(
            Class<E> entityType,
            Set<Class<? extends E>> concreteTypes,
            ParameterResolverFactory parameterResolverFactory,
            MessageTypeResolver messageTypeResolver,
            MessageConverter messageConverter,
            EventConverter eventConverter,
            List<QualifiedName> commandsToSkip
    ) {
        this.commandsToSkip = requireNonNull(commandsToSkip, "The commandsToSkip may not be null.");
        this.entityType = requireNonNull(entityType, "The entityType may not be null.");
        this.parameterResolverFactory = requireNonNull(parameterResolverFactory,
                                                       "The parameterResolverFactory may not be null.");
        this.messageTypeResolver = requireNonNull(messageTypeResolver, "The messageTypeResolver may not be null.");
        this.messageConverter = requireNonNull(messageConverter, "The MessageConverter may not be null.");
        this.eventConverter = requireNonNull(eventConverter, "The EventConverter may not be null.");
        requireNonNull(concreteTypes, "The concreteTypes may not be null.");
        if (!concreteTypes.isEmpty()) {
            this.delegateMetamodel = initializePolymorphicMetamodel(entityType, concreteTypes);
        } else {
            this.delegateMetamodel = initializeConcreteModel(entityType);
        }
    }

    private EntityMetamodel<E> initializeConcreteModel(Class<E> entityType) {
        EntityMetamodelBuilder<E> builder = EntityMetamodel.forEntityType(entityType);
        AnnotatedHandlerInspector<E> inspected = inspectType(entityType, parameterResolverFactory);
        builder.entityEvolver(new AnnotationBasedEntityEvolvingComponent<>(entityType,
                                                                           inspected,
                                                                           eventConverter,
                                                                           messageTypeResolver));
        initializeDetectedHandlers(builder, inspected);
        initializeChildren(builder);
        return builder.build();
    }

    private EntityMetamodel<E> initializePolymorphicMetamodel(Class<E> entityType,
                                                              Set<Class<? extends E>> concreteTypes) {
        // #3784: inspection of concrete (sub) types does not work with EntityMembers, that's why we need to
        // differentiate here.
        var hasMemberEntities = !ReflectionUtils.collectMatchingMethodsAndFields(entityType, isAnnotatedWith(EntityMember.class))
                                                .isEmpty() || concreteTypes.stream()
                                                                           .anyMatch(concreteType -> !ReflectionUtils.collectMatchingMethodsAndFields(
                                                                                                                             concreteType,
                                                                                                                             isAnnotatedWith(EntityMember.class))
                                                                                                                     .isEmpty());
        AnnotatedHandlerInspector<E> inspected = inspectType(
                entityType,
                parameterResolverFactory,
                ClasspathHandlerDefinition.forClass(entityType),
                hasMemberEntities ? Collections.emptySet() : concreteTypes
        );

        var builder = PolymorphicEntityMetamodel.forSuperType(entityType);
        builder.entityEvolver(new AnnotationBasedEntityEvolvingComponent<>(entityType,
                                                                           inspected,
                                                                           eventConverter,
                                                                           messageTypeResolver));
        initializeChildren(builder);
        // Commands that are present on the parent entity should not be registered again on the concrete
        // types. So we tell concrete types to skip these commands.
        LinkedList<QualifiedName> registeredCommands = initializeDetectedHandlers(builder, inspected);
        concreteTypes.forEach(concreteType -> {
            AnnotatedEntityMetamodel<? extends E> createdConcreteEntityModel = new AnnotatedEntityMetamodel<>(
                    concreteType, Set.of(), parameterResolverFactory, messageTypeResolver,
                    messageConverter, eventConverter, registeredCommands
            );
            concreteMetamodels.add(createdConcreteEntityModel);
            builder.addConcreteType(createdConcreteEntityModel);
        });
        return builder.build();
    }

    private LinkedList<QualifiedName> initializeDetectedHandlers(
            EntityMetamodelBuilder<E> builder, AnnotatedHandlerInspector<E> inspected
    ) {
        LinkedList<QualifiedName> registeredCommands = new LinkedList<>();
        Stream.concat(inspected.getUniqueHandlers(entityType, CommandMessage.class).stream(),
                      inspected.getUniqueHandlers(entityType, EventMessage.class).stream())
              .filter(h -> h.unwrap(Method.class).map(m -> !Modifier.isAbstract(m.getModifiers())).orElse(false))
              .forEach(handler -> {
                     QualifiedName qualifiedName = messageTypeResolver.resolveOrThrow(handler.payloadType())
                                                                      .qualifiedName();
                     if (commandsToSkip.contains(qualifiedName)) {
                         logger.debug(
                                 "Skipping registration of command handler for [{}] on [{}] (already registered by parent)",
                                 qualifiedName,
                                 entityType);
                         return;
                     }
                     addPayloadTypeFromHandler(qualifiedName, handler);
                     addCommandHandlerToModel(builder, handler, qualifiedName, registeredCommands);
                 });
        return registeredCommands;
    }

    private void addCommandHandlerToModel(EntityMetamodelBuilder<E> builder,
                                          MessageHandlingMember<? super E> handler,
                                          QualifiedName qualifiedName,
                                          LinkedList<QualifiedName> registeredCommands
    ) {
        if (!(handler instanceof CommandHandlingMember<? super E> commandMember)) {
            return;
        }
        registeredCommands.add(qualifiedName);
        if (commandMember.isFactoryHandler()) {
            logger.debug("Registered creational command handler for [{}] on [{}]", qualifiedName, entityType);
            builder.creationalCommandHandler(qualifiedName, ((command, context) -> handler
                    .handle(command, context, null)
                    .<CommandResultMessage>mapMessage(GenericCommandResultMessage::new)
                    .first()));
        } else {
            logger.debug("Registered instance command handler for [{}] on [{}]", qualifiedName, entityType);
            builder.instanceCommandHandler(qualifiedName, ((command, entity, context) -> handler
                    .handle(command, context, entity)
                    .<CommandResultMessage>mapMessage(GenericCommandResultMessage::new)
                    .first()));
        }
    }

    private void addPayloadTypeFromHandler(QualifiedName qualifiedName, MessageHandlingMember<?> handler) {
        if (payloadTypes.containsKey(qualifiedName) && !payloadTypes.get(qualifiedName).equals(handler.payloadType())) {
            throw new AxonConfigurationException(
                    "The scanned message handler methods expect different payload types for the same message type. Message of qualified name ["
                            + qualifiedName + "] declares both [" + payloadTypes.get(qualifiedName) + "] and ["
                            + handler.payloadType() + "] as wanted representations");
        }
        logger.debug("Discovered payload type [{}] for message type [{}] on entity [{}]",
                     handler.payloadType().getName(),
                     qualifiedName,
                     entityType);
        payloadTypes.put(qualifiedName, handler.payloadType());
    }

    /**
     * Returns the {@link Class} of the expected representation for handlers of the given {@code qualifiedName}.
     *
     * @param qualifiedName The {@link QualifiedName} of the handler to look for.
     * @return The {@link Class} of the expected representation for handlers of the given {@code qualifiedName}, or
     * {@code null} if no such representation is found.
     */
    @Nullable
    public Class<?> getExpectedRepresentation(QualifiedName qualifiedName) {
        if (payloadTypes.containsKey(qualifiedName)) {
            return payloadTypes.get(qualifiedName);
        }
        for (AnnotatedEntityMetamodel<?> concreteMetamodel : concreteMetamodels) {
            Class<?> payloadType = concreteMetamodel.getExpectedRepresentation(qualifiedName);
            if (payloadType != null) {
                return payloadType;
            }
        }
        for (AnnotatedEntityMetamodel<?> child : childMetamodels) {
            Class<?> payloadType = child.getExpectedRepresentation(qualifiedName);
            if (payloadType != null) {
                return payloadType;
            }
        }
        return null;
    }

    private void initializeChildren(EntityMetamodelBuilder<E> builder) {
        ServiceLoader<EntityChildModelDefinition> childEntityDefinitions =
                ServiceLoader.load(EntityChildModelDefinition.class, entityType.getClassLoader());

        collectMethodsAndFields(entityType).forEach(
                it -> createOptionalChildForMember(builder, it, childEntityDefinitions)
        );
    }

    private void createOptionalChildForMember(EntityMetamodelBuilder<E> builder,
                                              Member field,
                                              ServiceLoader<EntityChildModelDefinition> childEntityDefinitions) {
        List<EntityChildMetamodel<Object, E>> createdChildModels = childEntityDefinitions
                .stream()
                .map(ServiceLoader.Provider::get)
                .map(d -> d.createChildDefinition(entityType, this::createChildEntityModel, field))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
        if (createdChildModels.size() > 1) {
            throw new IllegalStateException("Multiple child entity definitions found for member [" + field
                                                    + "] of entity type [" + entityType + "]. Please ensure only one "
                                                    + "definition is present for this member. Found definitions: "
                                                    + createdChildModels);
        }
        if (createdChildModels.size() == 1) {
            var child = createdChildModels.getFirst();
            if (child.entityMetamodel() instanceof AnnotatedEntityMetamodel<?> annotatedChild) {
                this.childMetamodels.add(annotatedChild);
            }
            logger.debug("Discovered child entity [{}] for member [{}] on entity [{}]",
                         child.entityMetamodel().entityType().getName(),
                         field.getName(),
                         entityType);
            builder.addChild(child);
        }
    }

    /**
     * This is the {@link AnnotatedEntityMetamodelFactory} method to create a child {@code AnnotatedEntityMetamodel} for
     * the given {@code clazz}, while using the same resources as its parent metamodel (this instance).
     *
     * @param clazz The class of the child entity to create a metamodel for.
     * @param <C>   The type of the child entity to create a metamodel for.
     * @return An {@code AnnotatedEntityMetamodel} for the given {@code clazz}, using the same
     * {@link ParameterResolverFactory} and {@link MessageTypeResolver} as this instance.
     */
    private <C> AnnotatedEntityMetamodel<C> createChildEntityModel(Class<C> clazz) {
        logger.debug("Creating child entity metamodel for class: {}", clazz);
        return new AnnotatedEntityMetamodel<>(clazz,
                                              Set.of(),
                                              parameterResolverFactory,
                                              messageTypeResolver,
                                              messageConverter,
                                              eventConverter,
                                              List.of());
    }

    @Override
    public Set<QualifiedName> supportedCommands() {
        return Collections.unmodifiableSet(delegateMetamodel.supportedCommands());
    }

    @Override
    public Set<QualifiedName> supportedCreationalCommands() {
        return Collections.unmodifiableSet(delegateMetamodel.supportedCreationalCommands());
    }

    @Override
    public Set<QualifiedName> supportedInstanceCommands() {
        return Collections.unmodifiableSet(delegateMetamodel.supportedInstanceCommands());
    }

    @Override
    public MessageStream.Single<CommandResultMessage> handleCreate(CommandMessage message,
                                                                   ProcessingContext context) {
        MessageType type = message.type();
        if (logger.isDebugEnabled()) {
            logger.debug("Handling creation command: {} for type: {}", type, entityType());
        }
        Class<?> expectedRepresentation = getExpectedRepresentation(type.qualifiedName());
        if (expectedRepresentation == null) {
            // Should not happen, since how does a command reach the model without a handler for it.
            throw new ConversionException(String.format(
                    "Cannot convert command [%s] for handling since entity [%s] has no handler for this command type.",
                    type, entityType()
            ));
        }
        CommandMessage convertedMessage = message.withConvertedPayload(expectedRepresentation, messageConverter);
        return delegateMetamodel.handleCreate(convertedMessage, context);
    }

    @Override
        public MessageStream.Single<CommandResultMessage> handleInstance(CommandMessage message,
                                                                     E entity,
                                                                     ProcessingContext context) {
        MessageType type = message.type();
        if (logger.isDebugEnabled()) {
            logger.debug("Handling instance command: {} for entity: {} of type: {}",
                         type, entity, entityType());
        }
        Class<?> expectedRepresentation = getExpectedRepresentation(type.qualifiedName());
        if (expectedRepresentation == null) {
            // Should not happen, since how does a command reach the model without a handler for it.
            throw new ConversionException(String.format(
                    "Cannot convert command [%s] for handling since entity [%s] has no handler for this command type.",
                    type, entityType()
            ));
        }
        CommandMessage convertedMessage = message.withConvertedPayload(expectedRepresentation, messageConverter);
        return delegateMetamodel.handleInstance(convertedMessage, entity, context);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        logger.debug("Describing entity metamodel to descriptor for entity type: {}", entityType());
        descriptor.describeWrapperOf(delegateMetamodel);
        descriptor.describeProperty("entityType", entityType());
    }

    @Override
    public E evolve(E entity, EventMessage event, ProcessingContext context) {
        logger.debug("Evolving entity: {} with event: {} for entity type: {}", entity, event.type(), entityType());
        return delegateMetamodel.evolve(entity, event, context);
    }

    @Override
    public Class<E> entityType() {
        return entityType;
    }

    /**
     * Returns the {@link MessageConverter} configured in this {@link EntityMetamodel} implementation.
     *
     * @return The {@link MessageConverter} configured in this {@link EntityMetamodel} implementation.
     */
    MessageConverter messageConverter() {
        return messageConverter;
    }
}
