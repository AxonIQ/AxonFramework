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
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.MultiParameterResolverFactory;
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
    private final Map<Class<?>, Map<QualifiedName, List<MessageHandlingMember<? super E>>>> handlersByEntityType;

    /**
     * Initialize a new annotation-based {@link EntityEvolver}.
     *
     * @param entityType          The type of entity this instance will handle state changes for.
     * @param converter           The converter to use for converting event payloads to the handler's expected type.
     * @param messageTypeResolver The resolver to use for resolving the event message type.
     */
    public AnnotationBasedEntityEvolvingComponent(Class<E> entityType,
                                                  EventConverter converter,
                                                  MessageTypeResolver messageTypeResolver) {
        this(entityType,
             AnnotatedHandlerInspector.inspectType(
                     entityType,
                     messageTypeResolver,
                     MultiParameterResolverFactory.ordered(
                             new StaticEventSourcingHandlerParameterResolverFactory(),
                             ClasspathParameterResolverFactory.forClass(entityType)
                     ),
                     ClasspathHandlerDefinition.forClass(entityType)
             ),
             converter,
             messageTypeResolver);
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
    }

    @Nullable
    @Override
    public E evolve(@Nullable E entity,
                    EventMessage event,
                    ProcessingContext context) {
        // With a null entity the concrete type is unknown, so static (create-from-null) handlers are routed by the
        // declared entity type, mirroring how creational command handlers are registered on the super type.
        Class<?> listenerType = entity != null ? entity.getClass() : entityType;
        try {
            var handlers = handlersByEntityType.getOrDefault(listenerType, Map.of())
                                               .getOrDefault(event.type().qualifiedName(), List.of());

            E evolvedEntity = entity;
            for (var handler : handlers) {
                boolean staticHandler = isStaticHandler(handler);
                if (evolvedEntity == null && !staticHandler) {
                    // An instance handler cannot run without an instance to invoke it on.
                    continue;
                }
                var convertedEvent = event.withConvertedPayload(handler.payloadType(), converter);
                var contextWithEntity = ActiveEntity.set(context, evolvedEntity);
                if (!handler.canHandle(convertedEvent, contextWithEntity)) {
                    continue;
                }
                var interceptor = inspector.chainedInterceptor(listenerType);
                var result = interceptor.handle(convertedEvent, contextWithEntity, evolvedEntity, handler)
                                        .first()
                                        .asCompletableFuture()
                                        .join();
                evolvedEntity = nextState(result, evolvedEntity, handler, staticHandler);
            }

            return evolvedEntity;
        } catch (Exception e) {
            throw new StateEvolvingException(
                    "Failed to apply event [" + event.type() + "] in order to evolve [" + listenerType + "] state",
                    e
            );
        }
    }

    private Map<Class<?>, Map<QualifiedName, List<MessageHandlingMember<? super E>>>> indexHandlersByEntityType(
            MessageTypeResolver messageTypeResolver
    ) {
        return inspector.getAllHandlers().entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> indexHandlersByEventName(entry.getValue(), messageTypeResolver)
                        ));
    }

    private Map<QualifiedName, List<MessageHandlingMember<? super E>>> indexHandlersByEventName(
            Collection<MessageHandlingMember<? super E>> handlers,
            MessageTypeResolver messageTypeResolver
    ) {
        return handlers.stream()
                       .filter(handler -> handler.canHandleMessageType(EventMessage.class))
                       .collect(Collectors.collectingAndThen(
                               Collectors.groupingBy(
                                       handler -> eventName(handler, messageTypeResolver),
                                       Collectors.toUnmodifiableList()
                               ),
                               Map::copyOf
                       ));
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
                        MessageHandlingMember<? super E> handler,
                        boolean staticHandler) {
        if (potentialEntityFromStream != null) {
            var resultPayload = potentialEntityFromStream.message().payload();
            if (resultPayload != null && entityType.isAssignableFrom(resultPayload.getClass())) {
                //noinspection unchecked
                return (E) entityType.cast(resultPayload);
            }
        }
        // A static handler declaring the entity as its return type may deliberately return null to decline creation
        // or to remove (tombstone) the entity. A null return surfaces as an empty stream, so we rely on the return
        // type to distinguish it from a void (mutating) instance handler.
        if (staticHandler && handlerReturnsEntity(handler)) {
            return null;
        }
        return existing;
    }

    private boolean isStaticHandler(MessageHandlingMember<? super E> handler) {
        return handler.unwrap(Method.class)
                      .map(method -> Modifier.isStatic(method.getModifiers()))
                      .orElse(false);
    }

    private boolean handlerReturnsEntity(MessageHandlingMember<? super E> handler) {
        return handler.unwrap(Method.class)
                      .map(method -> entityType.isAssignableFrom(method.getReturnType()))
                      .orElse(false);
    }

    @Override
    public Set<QualifiedName> supportedEvents() {
        return handlersByEntityType.values().stream()
                                  .flatMap(handlers -> handlers.keySet().stream())
                                  .collect(Collectors.toUnmodifiableSet());
    }
}
