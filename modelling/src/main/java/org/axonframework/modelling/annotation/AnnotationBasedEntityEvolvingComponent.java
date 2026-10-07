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

package org.axonframework.modelling.annotation;

import org.axonframework.common.StringUtils;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.AnnotatedHandlerInspector;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.annotation.EventHandlingMember;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.modelling.EntityEvolver;
import org.axonframework.modelling.EntityEvolvingComponent;
import org.axonframework.modelling.StateEvolvingException;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Implementation of the {@link EntityEvolvingComponent} that applies state changes through
 * {@link EventHandler}(-meta)-annotated methods using the
 * {@link AnnotatedHandlerInspector}.
 * <p>
 * During construction, this component eagerly resolves the event names of all inspected handlers and builds an
 * immutable routing index. This shifts annotation inspection and message type resolution to initialization, increasing
 * startup work and memory usage in proportion to the number of handlers. In return, event evolution performs direct
 * lookups without reflection, message type resolution, cache mutation, or a scan of all handlers. Resolution failures
 * are also reported during initialization instead of on the first matching event.
 *
 * @param <E> The entity type to evolve.
 * @author Mateusz Nowak
 * @see AnnotatedHandlerInspector
 * @since 5.0.0
 */
public class AnnotationBasedEntityEvolvingComponent<E> implements EntityEvolvingComponent<E> {

    private final Class<E> entityType;
    private final AnnotatedHandlerInspector<E> inspector;
    private final EventConverter converter;
    private final Map<Class<?>, Map<QualifiedName, List<EvolvingHandler<E>>>> handlersByEntityType;
    private final boolean hasStaticHandlers;

    /**
     * Initialize a new annotation-based {@link EntityEvolver}.
     *
     * @param entityType          The type of entity this instance will handle state changes for.
     * @param converter           The converter to use for converting event payloads to the handler's expected type.
     * @param messageTypeResolver The resolver to use for resolving the event message type.
     * @deprecated in favor of
     * {@link #AnnotationBasedEntityEvolvingComponent(Class, EventConverter, MessageTypeResolver,
     * ParameterResolverFactory, HandlerDefinition)} since this allows for clean customization of the
     * {@link ParameterResolverFactory} and {@link HandlerDefinition}, which this constructor does not
     */
    @Deprecated(since = "5.3.2", forRemoval = true)
    public AnnotationBasedEntityEvolvingComponent(Class<E> entityType,
                                                  EventConverter converter,
                                                  MessageTypeResolver messageTypeResolver) {
        this(entityType,
             AnnotatedHandlerInspector.inspectType(
                     entityType,
                     messageTypeResolver,
                     ClasspathParameterResolverFactory.forClass(entityType),
                     ClasspathHandlerDefinition.forClass(entityType)
             ),
             converter,
             messageTypeResolver);
    }

    /**
     * Initialize a new annotation-based {@link EntityEvolver}.
     *
     * @param entityType               the type of entity this instance will handle state changes for
     * @param converter                the converter to use for converting event payloads to the handler's expected
     *                                 type
     * @param messageTypeResolver      the resolver to use for resolving the event message type
     * @param parameterResolverFactory the resolver factory to use during detection of the annotated model
     * @param handlerDefinition        the handler definition used to create concrete handlers of the annotated model
     */
    public AnnotationBasedEntityEvolvingComponent(Class<E> entityType,
                                                  EventConverter converter,
                                                  MessageTypeResolver messageTypeResolver,
                                                  ParameterResolverFactory parameterResolverFactory,
                                                  HandlerDefinition handlerDefinition) {
        this(
                entityType,
                AnnotatedHandlerInspector.inspectType(
                        entityType,
                        messageTypeResolver,
                        parameterResolverFactory,
                        handlerDefinition
                ),
                converter,
                messageTypeResolver
        );
    }

    /**
     * Initialize a new annotation-based {@link EntityEvolver}.
     *
     * @param entityType          The type of entity this instance will handle state changes for.
     * @param inspector           The inspector to use to find the annotated handlers on the entity.
     * @param converter           The converter to use for converting event payloads to the handler's expected type.
     * @param messageTypeResolver The resolver to use for resolving the event message type.
     */
    public AnnotationBasedEntityEvolvingComponent(Class<E> entityType,
                                                  AnnotatedHandlerInspector<E> inspector,
                                                  EventConverter converter,
                                                  MessageTypeResolver messageTypeResolver
    ) {
        this.entityType = requireNonNull(entityType, "The entity type must not be null.");
        this.inspector = requireNonNull(inspector, "The Annotated Handler Inspector must not be null.");
        this.converter = requireNonNull(converter, "The Converter must not be null.");
        this.handlersByEntityType = indexHandlersByEntityType(
                requireNonNull(messageTypeResolver, "The Message Type Resolver must not be null.")
        );
        this.hasStaticHandlers = handlersByEntityType.values().stream()
                                                     .flatMap(handlers -> handlers.values().stream())
                                                     .flatMap(List::stream)
                                                     .anyMatch(EvolvingHandler::isStatic);
    }

    @Nullable
    @Override
    public E evolve(@Nullable E entity,
                    EventMessage event,
                    ProcessingContext context) {
        if (entity == null && !hasStaticHandlers) {
            throw new NullPointerException(
                    "Cannot evolve an absent [" + entityType.getName() + "] entity without static event sourcing handlers."
            );
        }
        // With a null entity the concrete type is unknown, so static (create-from-null) handlers are routed by the
        // declared entity type, mirroring how creational command handlers are registered on the super type.
        Class<?> listenerType = entity != null ? entity.getClass() : entityType;
        try {
            var handlers = handlersByEntityType.getOrDefault(listenerType, Map.of())
                                               .getOrDefault(event.type().qualifiedName(), List.of());

            E evolvedEntity = entity;
            for (var evolvingHandler : handlers) {
                // An existing entity is handed to every handler as it was before the event, so each handler sees
                // the same state. Only while the entity is absent does a handler see what an earlier handler created.
                E target = entity != null ? entity : evolvedEntity;
                if (target == null && !evolvingHandler.isStatic()) {
                    // An instance handler cannot run without an instance to invoke it on.
                    continue;
                }
                var handler = evolvingHandler.member();
                var convertedEvent = event.withConvertedPayload(handler.payloadType(), converter);
                var contextWithEntity = ActiveEntity.set(context, target);
                if (!handler.canHandle(convertedEvent, contextWithEntity)) {
                    continue;
                }
                var interceptor = inspector.chainedInterceptor(listenerType);
                var result = interceptor.handle(convertedEvent, contextWithEntity, target, handler)
                                        .first()
                                        .asCompletableFuture()
                                        .join();
                evolvedEntity = nextState(result, target, evolvingHandler);
            }

            return evolvedEntity;
        } catch (StateEvolvingException e) {
            throw e;
        } catch (Exception e) {
            throw new StateEvolvingException(
                    "Failed to apply event [" + event.type() + "] in order to evolve [" + listenerType + "] state",
                    e
            );
        }
    }

    private Map<Class<?>, Map<QualifiedName, List<EvolvingHandler<E>>>> indexHandlersByEntityType(
            MessageTypeResolver messageTypeResolver
    ) {
        return inspector.getAllHandlers().entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> indexHandlersByEventName(entry.getValue(), messageTypeResolver)
                        ));
    }

    private Map<QualifiedName, List<EvolvingHandler<E>>> indexHandlersByEventName(
            Collection<MessageHandlingMember<? super E>> handlers,
            MessageTypeResolver messageTypeResolver
    ) {
        return handlers.stream()
                       .filter(handler -> handler.canHandleMessageType(EventMessage.class))
                       .collect(Collectors.collectingAndThen(
                               Collectors.groupingBy(
                                       handler -> eventName(handler, messageTypeResolver),
                                       Collectors.mapping(this::toEvolvingHandler, Collectors.toUnmodifiableList())
                               ),
                               Map::copyOf
                       ));
    }

    private EvolvingHandler<E> toEvolvingHandler(MessageHandlingMember<? super E> handler) {
        Optional<Method> method = handler.unwrap(Method.class);
        boolean isStatic = method.map(m -> Modifier.isStatic(m.getModifiers())).orElse(false);
        boolean returnsEntity = method.map(m -> entityType.isAssignableFrom(m.getReturnType())).orElse(false);
        return new EvolvingHandler<>(handler, isStatic, returnsEntity);
    }

    private QualifiedName eventName(MessageHandlingMember<? super E> handler,
                                    MessageTypeResolver messageTypeResolver) {
        return handler.unwrap(EventHandlingMember.class)
                      .map(EventHandlingMember::eventName)
                      .filter(StringUtils::nonEmpty)
                      .map(QualifiedName::new)
                      .orElseGet(() -> messageTypeResolver.resolveOrThrow(handler.payloadType()).qualifiedName());
    }

    @Nullable
    private E nextState(MessageStream.@Nullable Entry<?> potentialEntityFromStream,
                        @Nullable E existing,
                        EvolvingHandler<E> handler) {
        if (potentialEntityFromStream != null) {
            var resultPayload = potentialEntityFromStream.message().payload();
            if (resultPayload != null && entityType.isAssignableFrom(resultPayload.getClass())) {
                //noinspection unchecked
                return (E) entityType.cast(resultPayload);
            }
        }
        // A static handler declaring the entity as its return type returned null (an empty stream). While the entity
        // does not exist yet, this is a legitimate "decline to create" outcome. Once the entity exists, however, it
        // may not be removed by returning null: model end-of-life as a terminal state instead.
        if (handler.isStatic() && handler.returnsEntity()) {
            if (existing != null) {
                throw new StateEvolvingException(
                        "A static event sourcing handler returned null for an existing [" + entityType.getName()
                                + "] entity. An entity that exists cannot be removed by returning null; "
                                + "model end-of-life as a terminal state instead.");
            }
            return null;
        }
        return existing;
    }

    @Override
    public Set<QualifiedName> supportedEvents() {
        return handlersByEntityType.values().stream()
                                  .flatMap(handlers -> handlers.keySet().stream())
                                  .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * A {@link MessageHandlingMember} together with the reflective facts the evolve loop needs, determined once at
     * construction rather than on every event.
     *
     * @param member        the handler to invoke
     * @param isStatic      whether the handler is a {@code static} method, which can run while the entity is
     *                      {@code null}
     * @param returnsEntity whether the handler declares the entity type as its return type, so that a {@code null}
     *                      result means the handler returned {@code null} rather than nothing
     * @param <E>           the entity type
     */
    private record EvolvingHandler<E>(MessageHandlingMember<? super E> member, boolean isStatic, boolean returnsEntity) {

    }
}
