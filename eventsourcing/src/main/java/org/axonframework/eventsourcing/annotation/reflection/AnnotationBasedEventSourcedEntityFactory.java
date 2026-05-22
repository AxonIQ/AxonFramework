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

package org.axonframework.eventsourcing.annotation.reflection;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.ObjectUtils;
import org.axonframework.common.ReflectionUtils;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.annotation.PayloadParameterResolver;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.modelling.repository.EntityNotFoundException;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Reflection-based implementation of the {@link EventSourcedEntityFactory} interface. This factory will look for
 * {@link EntityCreator}-annotated constructors and static methods on the entity type and its supertypes to find a
 * suitable constructor or static method to create an entity instance.
 * <p>
 * This class implements the requirements as per the {@link EntityCreator} annotation. This class is thread-safe.
 *
 * @param <E>  The type of entity to create.
 * @param <ID> The type of identifier used by the entity.
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public class AnnotationBasedEventSourcedEntityFactory<E, ID> implements EventSourcedEntityFactory<ID, E> {

    private final Context.ResourceKey<ID> ID_KEY = Context.ResourceKey.withLabel("EventSourcedEntityFactory.id");

    private final Class<E> entityType;
    private final Set<Class<? extends E>> types;
    private final Class<ID> idType;
    private final List<ScannedEntityCreator> creators = new ArrayList<>();
    private final IdTypeParameterResolver idTypeParameterResolver = new IdTypeParameterResolver();
    private final ParameterResolverFactory resolverFactory;
    private final MessageTypeResolver messageTypeResolver;
    private final EventConverter converter;

    /**
     * Instantiate an annotation-based {@link EventSourcedEntityFactory} for the given concrete {@code entityType}. When
     * using a polymorphic entity type, you can use the
     * {@link #AnnotationBasedEventSourcedEntityFactory(Class, Class, Set, ParameterResolverFactory,
     * MessageTypeResolver, EventConverter)}, so that all subtypes of the entity type will be scanned for static methods
     * and constructors.
     *
     * @param entityType               The type of the entity to create. Must be concrete.
     * @param idType                   The type of the identifier used by the entity.
     * @param parameterResolverFactory The factory to use to resolve parameters.
     * @param messageTypeResolver      The factory to use to resolve the payload type.
     * @param converter                The converter to use for converting event payloads to the handler's expected
     *                                 type.
     */
    public AnnotationBasedEventSourcedEntityFactory(Class<E> entityType,
                                                    Class<ID> idType,
                                                    ParameterResolverFactory parameterResolverFactory,
                                                    MessageTypeResolver messageTypeResolver,
                                                    EventConverter converter
    ) {
        this(entityType, idType, Collections.emptySet(), parameterResolverFactory, messageTypeResolver, converter);
    }

    /**
     * Instantiate a reflection-based {@link EventSourcedEntityFactory} for the given super {@code entityType}, with the
     * given {@code subTypes}. The {@code subTypes} must be concrete types that extend the {@code entityType}. The
     * factory will look for static methods and constructors on the {@code subTypes} and their supertypes to find a
     * suitable constructor or static method to create an entity instance.
     *
     * @param entityType               The type of the entity to create. Can be abstract.
     * @param idType                   The type of the identifier used by the entity.
     * @param subTypes                 The concrete types that extend the {@code entityType}.
     * @param parameterResolverFactory The factory to use to resolve parameters.
     * @param messageTypeResolver      The factory to use to resolve the payload type.
     * @param converter                The converter to use for converting event payloads to the handler's expected
     *                                 type.
     */
    public AnnotationBasedEventSourcedEntityFactory(Class<E> entityType,
                                                    Class<ID> idType,
                                                    Set<Class<? extends E>> subTypes,
                                                    ParameterResolverFactory parameterResolverFactory,
                                                    MessageTypeResolver messageTypeResolver,
                                                    EventConverter converter
    ) {
        this.entityType = Objects.requireNonNull(entityType, "The entityType must not be null.");
        this.types = new HashSet<>(subTypes);
        types.add(entityType);
        this.idType = Objects.requireNonNull(idType, "The idType must not be null.");

        this.resolverFactory = Objects.requireNonNull(parameterResolverFactory,
                                                      "The parameterResolverFactory must not be null.");
        this.messageTypeResolver = Objects.requireNonNull(messageTypeResolver,
                                                          "The messageTypeResolver must not be null.");
        this.converter = Objects.requireNonNull(converter, "The converter must not be null.");

        initialize();
    }

    private void initialize() {
        scanMethods();
        scanConstructors();
        validate();
    }

    private void scanConstructors() {
        types.stream()
             .flatMap(type -> Arrays.stream(type.getDeclaredConstructors()))
             .filter(constructor -> AnnotationUtils.isAnnotationPresent(constructor, EntityCreator.class))
             .distinct()
             .forEach(this::addEntityCreatorExecutable);
    }

    private void scanMethods() {
        types.stream()
             .flatMap(type -> StreamSupport.stream(ReflectionUtils.methodsOf(type).spliterator(), false))
             .filter(method -> AnnotationUtils.isAnnotationPresent(method, EntityCreator.class))
             .distinct()
             .forEach(this::addEntityCreatorMethod);
    }

    private void validate() {
        if (creators.isEmpty()) {
            throw new AxonConfigurationException(
                    "No @EntityCreator present on entity of type [%s]. Can not initialize AnnotationBasedEventSourcedEntityFactory.".formatted(
                            entityType.getName()));
        }
    }

    private void addEntityCreatorMethod(Method method) {
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new AxonConfigurationException("Method-based @EntityCreator must be static. Found method: %s".formatted(
                    method));
        }
        if (!this.entityType.isAssignableFrom(method.getReturnType())) {
            throw new AxonConfigurationException(
                    "Method-based @EntityCreator must return the entity type or a subtype. Found method: [%s]".formatted(
                            method));
        }
        addEntityCreatorExecutable(method);
    }

    private void addEntityCreatorExecutable(Executable executable) {
        String[] payloadQualifiedNamesAttribute = AnnotationUtils
                .findAnnotationAttribute(executable, EntityCreator.class, "payloadQualifiedNames")
                .map(o -> (String[]) o)
                // This would have been caught by the validation
                .orElseThrow();

        List<QualifiedName> payloadQualifiedNames = Arrays
                .stream(payloadQualifiedNamesAttribute)
                .map(QualifiedName::new)
                .collect(Collectors.toList());
        ParameterResolver<?>[] parameterResolvers = new ParameterResolver[executable.getParameterCount()];
        boolean hasMessageParameter = false;
        Class<?> concreteIdType = null;
        Class<?> expectedPayloadRepresentation = null;

        for (int i = 0; i < executable.getParameterCount(); i++) {
            Class<?> parameterType = executable.getParameterTypes()[i];

            // Check the parameter type for the ID type and assign our special IdTypeParameterResolver
            if (AnnotationUtils.isAnnotationPresent(executable.getParameters()[i], InjectEntityId.class)) {
                if (concreteIdType != null && !concreteIdType.isAssignableFrom(parameterType)) {
                    throw new AxonConfigurationException(
                            "The @InjectEntityId annotation can only be used on a single parameter of type [%s] or a subtype. Found [%s] on parameter %d of method [%s]"
                                    .formatted(concreteIdType, parameterType.getName(), i, executable));
                }
                parameterResolvers[i] = idTypeParameterResolver;
                concreteIdType = idType;
                continue;
            }
            if (Message.class.isAssignableFrom(parameterType)) {
                hasMessageParameter = true;
            }

            ParameterResolver<?> instance = resolverFactory.createInstance(executable, executable.getParameters(), i);
            if (instance == null) {
                throw new AxonConfigurationException(
                        "Could not resolve parameter [%d] of [%s]. No suitable ParameterResolver found for type [%s]"
                                .formatted(i, executable, parameterType.getName()));
            }
            if (instance instanceof PayloadParameterResolver payloadParameterResolver) {
                if (expectedPayloadRepresentation != null) {
                    throw new AxonConfigurationException("The method [%s] has multiple payload parameters".formatted(
                            executable));
                }
                expectedPayloadRepresentation = payloadParameterResolver.supportedPayloadType();
            }
            parameterResolvers[i] = instance;
        }

        if (payloadQualifiedNames.isEmpty()) {
            // Let's find if we have a PayloadParameterResolver
            Arrays.stream(parameterResolvers)
                  .filter(p -> p instanceof PayloadParameterResolver)
                  .findFirst()
                  .map(prr -> messageTypeResolver.resolveOrThrow(prr.supportedPayloadType()))
                  .ifPresent(messageType -> payloadQualifiedNames.add(messageType.qualifiedName()));
        }

        creators.add(new ScannedEntityCreator(executable,
                                              parameterResolvers,
                                              payloadQualifiedNames,
                                              concreteIdType,
                                              expectedPayloadRepresentation,
                                              hasMessageParameter));
    }

    private Set<ScannedEntityCreator> getMethodsCompatibleWithIdAndNoMessage(ID id) {
        return creators.stream()
                       .filter(method -> method.supportsId(id))
                       .filter(ScannedEntityCreator::isWithoutPayload)
                       .collect(Collectors.toSet());
    }

    private Set<ScannedEntityCreator> getMethodsCompatibleWithIdAndMessage(ID id, EventMessage eventMessage) {
        return creators.stream()
                       .filter(creator -> creator.supportsId(id))
                       .filter(creator -> creator.hasPayload(eventMessage.type().qualifiedName()))
                       .collect(Collectors.toSet());
    }

    private ScannedEntityCreator findMostSpecificMethod(ID id,
                                                        @Nullable EventMessage eventMessage,
                                                        ProcessingContext context) {
        Set<ScannedEntityCreator> compatibleCreators;

        // If we have an EventMessage, methods taking the payload type of the event message have precedence
        if (eventMessage != null) {
            compatibleCreators = getMethodsCompatibleWithIdAndMessage(id, eventMessage);
            if (compatibleCreators.isEmpty()) {
                compatibleCreators = getMethodsCompatibleWithIdAndNoMessage(id);
            }
        } else {
            compatibleCreators = getMethodsCompatibleWithIdAndNoMessage(id);
        }
        if (compatibleCreators.isEmpty()) {
            if (hasEventBasedCreatorsOnly()) {
                // If there are only event-based creators, but no eventMessage, that means we were not able to find the entity
                throw new EntityNotFoundException(id);
            }
            StringBuilder message = new StringBuilder(
                    "No suitable @EntityCreator found for id: [%s] and event message [%s]. Candidates were:"
                            .formatted(id, ObjectUtils.getOrDefault(eventMessage, Message::type, "none")));
            creators.forEach(creator -> message.append("\n - ").append(creator));
            throw new AxonConfigurationException(message.toString());
        }
        Set<ScannedEntityCreator> matchingCreators = compatibleCreators
                .stream()
                .filter(e -> {
                    var convertedContext = e.mapContextWithMessageIfNecessary(context);
                    return e.parametersMatch(convertedContext);
                })
                .collect(Collectors.toSet());
        if (matchingCreators.isEmpty()) {
            // Create a message explaining which parameters could not be resolved of which candidate.
            StringBuilder message = new StringBuilder(
                    "No @EntityCreator matched for entity id: [%s] and event message [%s]. Candidates were:\n".formatted(
                            id,
                            eventMessage));
            for (ScannedEntityCreator compatibleCreator : compatibleCreators) {
                List<Integer> unresolvableParameterIndices = compatibleCreator
                        .getUnresolvableParameterIndices(context);
                message.append(" - [%s] could not resolve parameters indices: %s\n".formatted(compatibleCreator,
                                                                                              unresolvableParameterIndices));
            }
            message.append(
                    "\n\nPlease ensure that the parameters can be resolved by the ParameterResolverFactory implementations on the classpath.");
            throw new AxonConfigurationException(message.toString());
        }
        return matchingCreators.stream()
                               .max(Comparator.comparingInt(ScannedEntityCreator::getParameterCount))
                               .orElseThrow();
    }

    /**
     * Returns {@code true} if all the uncovered {@code ScannedEntityCreators} are without a payload, suggesting the
     * event payload is never used. Returns {@code false} otherwise.
     *
     * @return {@code true} if all the uncovered {@code ScannedEntityCreators} are without a payload, suggesting the
     * event payload is never used. Returns {@code false} otherwise
     */
    private boolean hasEventBasedCreatorsOnly() {
        return creators.stream()
                       .noneMatch(ScannedEntityCreator::isWithoutPayload);
    }

    @Nullable
    @Override
    public E create(ID id, @Nullable EventMessage firstEventMessage, ProcessingContext context) {
        ProcessingContext preparedContext = context.withResource(ID_KEY, id);
        if (firstEventMessage != null) {
            preparedContext = Message.addToContext(preparedContext, firstEventMessage);
        }
        return findMostSpecificMethod(id, firstEventMessage, preparedContext)
                .invoke(id, preparedContext);
    }

    /**
     * Represents a scanned factory method, ready to be invoked. This class is immutable and thread-safe.
     */
    private class ScannedEntityCreator {

        private final Executable executable;
        private final ParameterResolver<?>[] parameterResolvers;
        private final List<QualifiedName> payloadQualifiedNames;
        private final @Nullable Class<?> concreteIdType;
        private final @Nullable Class<?> expectedPayloadRepresentation;
        private final boolean hasMessageParameter;

        private ScannedEntityCreator(
                Executable executable,
                ParameterResolver<?>[] parameterResolvers,
                List<QualifiedName> payloadQualifiedNames,
                @Nullable Class<?> concreteIdType,
                @Nullable Class<?> expectedPayloadRepresentation,
                boolean hasMessageParameter
        ) {
            ReflectionUtils.ensureAccessible(executable);
            this.executable = executable;
            this.parameterResolvers = parameterResolvers;
            this.payloadQualifiedNames = payloadQualifiedNames;
            this.concreteIdType = concreteIdType;
            this.expectedPayloadRepresentation = expectedPayloadRepresentation;
            this.hasMessageParameter = hasMessageParameter;
        }

        private E invoke(ID id, ProcessingContext context) {
            ProcessingContext contextWithId = context.withResource(ID_KEY, id);
            ProcessingContext convertedContext = mapContextWithMessageIfNecessary(contextWithId);

            CompletableFuture<?>[] resolvedParams =
                    Arrays.stream(parameterResolvers)
                          .map(resolver -> tryResolveParameterValue(resolver, convertedContext))
                          .toArray(CompletableFuture[]::new);

            if (isNoArgOrIdBasedCreatorWithoutFirstEvent(context.getResource(Message.RESOURCE_KEY))) {
                throw new EntityNotFoundException(id);
            }

            return CompletableFuture.allOf(resolvedParams)
                                    .thenApply(v -> Arrays.stream(resolvedParams)
                                                          .map(CompletableFuture::resultNow)
                                                          .toArray())
                                    .thenApply(this::constructEntityWithArguments)
                                    .join();
        }

        private CompletableFuture<?> tryResolveParameterValue(
                ParameterResolver<?> parameterResolver,
                ProcessingContext context
        ) {
            try {
                return parameterResolver.resolveParameterValue(context);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        /**
         * Returns {@code true} when this {@link EntityCreator} annotated {@link Constructor} has no parameters beyond
         * {@link InjectEntityId}-annotated ones, and no {@code firstEventMessage} is present, indicating that the entity
         * has never been created by an event and therefore does not exist.
         * <p>
         * Concretely, this returns {@code true} when all three conditions hold:
         * <ul>
         *   <li>the underlying {@link Executable} is a {@link Constructor}</li>
         *   <li>every parameter resolver is an {@link IdTypeParameterResolver} (covers both zero-arg constructors and
         *       constructors whose only parameters are {@link InjectEntityId}-annotated)</li>
         *   <li>{@code firstEventMessage} is {@code null}</li>
         * </ul>
         *
         * @param firstEventMessage the first {@link EventMessage}, if any, for the entity that is about to be
         *                          constructed
         * @return {@code true} when the constructor requires no event to produce an entity but no event was supplied,
         * meaning the entity does not exist yet
         */
        private boolean isNoArgOrIdBasedCreatorWithoutFirstEvent(@Nullable Message firstEventMessage) {
            return executable instanceof Constructor
                    && firstEventMessage == null
                    && Arrays.stream(parameterResolvers).allMatch(r -> r == idTypeParameterResolver);
        }

        private boolean supportsId(ID id) {
            return concreteIdType == null || concreteIdType.isAssignableFrom(id.getClass());
        }

        private int getParameterCount() {
            return parameterResolvers.length;
        }

        private boolean parametersMatch(ProcessingContext processingContext) {
            return Arrays.stream(parameterResolvers)
                         .allMatch(f -> f.matches(processingContext));
        }

        private boolean isWithoutPayload() {
            return payloadQualifiedNames.isEmpty() && !hasMessageParameter;
        }

        private boolean hasPayload(QualifiedName qualifiedName) {
            if (payloadQualifiedNames.isEmpty()) {
                // If we have no payload qualified names, we can only match if the method has a message parameter
                return hasMessageParameter;
            }
            return payloadQualifiedNames.contains(qualifiedName);
        }

        @SuppressWarnings("unchecked")
        private E constructEntityWithArguments(Object[] args) {
            try {
                return (E) switch (executable) {
                    case Constructor<?> c -> c.newInstance(args);
                    case Method method -> method.invoke(null, args);
                };
            } catch (Exception e) {
                throw new AxonConfigurationException("Failed to invoke entity initializer", e);
            }
        }

        private List<Integer> getUnresolvableParameterIndices(ProcessingContext processingContext) {
            return StreamSupport.stream(Arrays.spliterator(parameterResolvers), false)
                                .filter(p -> !p.matches(processingContext))
                                .map(p -> Arrays.asList(parameterResolvers).indexOf(p))
                                .collect(Collectors.toList());
        }

        @Override
        public String toString() {
            return ReflectionUtils.getMemberGenericString(executable);
        }

        public ProcessingContext mapContextWithMessageIfNecessary(ProcessingContext context) {
            Message eventMessage = Message.fromContext(context);
            if (eventMessage != null && expectedPayloadRepresentation != null) {
                var convertedEvent = eventMessage.withConvertedPayload(expectedPayloadRepresentation, converter);
                return Message.addToContext(context, convertedEvent);
            }
            return context;
        }
    }

    /**
     * Internal parameter resolver for the ID parameter on a {@link EntityCreator}. Will get the {@code ID_KEY} resource
     * from the context and return it as the parameter value.
     */
    private class IdTypeParameterResolver implements ParameterResolver<ID> {

        @Override
        public CompletableFuture<ID> resolveParameterValue(ProcessingContext processingContext) {
            return CompletableFuture.completedFuture(processingContext.getResource(ID_KEY));
        }

        @Override
        public boolean matches(ProcessingContext processingContext) {
            return processingContext.containsResource(ID_KEY);
        }
    }
}
