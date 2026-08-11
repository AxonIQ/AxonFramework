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

import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.AnnotatedHandlerInspector;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.modelling.EntityEvolver;
import org.axonframework.modelling.EntityEvolvingComponent;
import org.axonframework.modelling.StateEvolvingException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Implementation of the {@link EntityEvolvingComponent} that applies state changes through
 * {@link EventHandler}(-meta)-annotated methods using the
 * {@link AnnotatedHandlerInspector}.
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
    private final Map<Class<?>, Map<QualifiedName, List<MessageHandlingMember<? super E>>>> handlersByEventName;

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
             AnnotatedHandlerInspector.inspectType(entityType,
                                                   ClasspathParameterResolverFactory.forClass(entityType),
                                                   ClasspathHandlerDefinition.forClass(entityType)),
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
        this.handlersByEventName = indexHandlersByEventName(
                requireNonNull(messageTypeResolver, "The Message Type Resolver must not be null.")
        );
    }

    @Override
    public E evolve(E entity,
                    EventMessage event,
                    ProcessingContext context) {
        try {
            var listenerType = entity.getClass();

            var handlers = handlersByEventName.getOrDefault(listenerType, Map.of())
                                              .getOrDefault(event.type().qualifiedName(), List.of());

            E evolvedEntity = entity;
            for (var handler : handlers) {
                var convertedEvent = event.withConvertedPayload(handler.payloadType(), converter);
                if (!handler.canHandle(convertedEvent, context)) {
                    continue;
                }
                var interceptor = inspector.chainedInterceptor(listenerType);
                var result = interceptor.handle(convertedEvent, context, entity, handler)
                                        .first()
                                        .asCompletableFuture()
                                        .join();
                evolvedEntity = entityFromStreamResultOrUpdatedExisting(result, entity);
            }

            return evolvedEntity;
        } catch (Exception e) {
            throw new StateEvolvingException(
                    "Failed to apply event [" + event.type() + "] in order to evolve [" + entity.getClass() + "] state",
                    e
            );
        }
    }

    private Map<Class<?>, Map<QualifiedName, List<MessageHandlingMember<? super E>>>> indexHandlersByEventName(
            MessageTypeResolver messageTypeResolver
    ) {
        return inspector.getAllHandlers().entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> indexHandlers(entry.getValue(), messageTypeResolver)
                        ));
    }

    private Map<QualifiedName, List<MessageHandlingMember<? super E>>> indexHandlers(
            Collection<MessageHandlingMember<? super E>> handlers,
            MessageTypeResolver messageTypeResolver
    ) {
        return handlers.stream()
                       .filter(handler -> handler.canHandleMessageType(EventMessage.class))
                       .collect(Collectors.collectingAndThen(
                               Collectors.groupingBy(
                                       handler -> messageTypeResolver.resolveOrThrow(handler.payloadType())
                                                                             .qualifiedName(),
                                       Collectors.toUnmodifiableList()
                               ),
                               Map::copyOf
                       ));
    }

    private E entityFromStreamResultOrUpdatedExisting(MessageStream.Entry<?> potentialEntityFromStream, E existing) {
        if (potentialEntityFromStream != null) {
            var resultPayload = potentialEntityFromStream.message().payload();
            if (resultPayload != null && entityType.isAssignableFrom(resultPayload.getClass())) {
                //noinspection unchecked
                return (E) entityType.cast(resultPayload);
            }
        }
        return existing;
    }

    @Override
    public Set<QualifiedName> supportedEvents() {
        return inspector.getHandlers(entityType).stream()
                        .filter(Objects::nonNull)
                        .map(MessageHandlingMember::payloadType)
                        .map(QualifiedName::new)
                        .collect(Collectors.toSet());
    }
}
