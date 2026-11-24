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

package org.axonframework.modelling;

import org.axonframework.common.ReflectionUtils;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import static java.util.Objects.requireNonNull;

/**
 * Implementation of {@link EntityEvolvingComponent} that evolves a given {@code entity} of type {@code E}
 * by searching a specific {@link EntityEvolver} based on the {@link QualifiedName} in the
 * {@link EventMessage#type() event's type}.
 *
 * @param <E> The entity type to evolve.
 * @author Mitchell Herrijgers
 * @author Steven van Beelen
 * @since 5.0.0
 */
public class SimpleEntityEvolvingComponent<E> implements EntityEvolvingComponent<E>, DescribableComponent {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final Map<QualifiedName, EntityEvolver<E>> entityEvolvers;
    private final Set<QualifiedName> supportedEvents;

    /**
     * Constructs a {@code SimpleEntityEvolvingComponent} that evolves an entity of type {@code e} through
     * the given {@code entityEvolvers}.
     *
     * @param entityEvolvers The map of {@link EntityEvolver} instance to {@link QualifiedName} to evolve an entity
     *                       through.
     */
    public SimpleEntityEvolvingComponent(Map<QualifiedName, EntityEvolver<E>> entityEvolvers) {
        this.entityEvolvers = Map.copyOf(
                requireNonNull(entityEvolvers, "The entity evolvers cannot be null.")
        );
        this.supportedEvents = Set.copyOf(this.entityEvolvers.keySet());
    }

    /**
     * Creates a {@code SimpleEntityEvolvingComponent} that evolves an entity of type {@code E} for every concrete
     * subtype of the given sealed type {@code S}.
     * <p>
     * The component registers one {@link EntityEvolver} per concrete subtype, under the name the given
     * {@code messageTypeResolver} resolves for that subtype. An event is routed by its name alone: its payload,
     * for example a JSON map, is converted to the subtype registered for that name through the given
     * {@code eventConverter} before the {@code evolver} is invoked. The hierarchy is read from the Java type once, when
     * this method is called.
     * <p>
     * Example usage:
     * <pre>{@code
     * EventSourcedEntityModule
     *         .declarative(String.class, Account.class)
     *         .messagingModel((configuration, model) -> model
     *                 .entityEvolver(SimpleEntityEvolvingComponent.forSealedType(
     *                         AccountEvent.class,
     *                         Account::evolve,
     *                         configuration.getComponent(MessageTypeResolver.class),
     *                         configuration.getComponent(EventConverter.class)
     *                 ))
     *                 .build());
     * }</pre>
     *
     * @param sealedType          the sealed type whose concrete subtypes the {@code evolver} handles
     * @param evolver             the function evolving the entity for an event of one of the subtypes, typically
     *                            switching over them
     * @param messageTypeResolver the resolver naming each subtype
     * @param eventConverter      the converter turning an event payload into the subtype registered for its name
     * @param <E>                 the entity type to evolve
     * @param <S>                 the sealed event type
     * @return a component evolving the entity for every concrete subtype of {@code sealedType}
     * @throws IllegalArgumentException when {@code sealedType} is not sealed, permits a subtype that is neither
     *                                  sealed, final nor an enum, or has two subtypes resolving to the same name
     */
    public static <E, S> SimpleEntityEvolvingComponent<E> forSealedType(
            Class<S> sealedType,
            BiFunction<E, ? super S, E> evolver,
            MessageTypeResolver messageTypeResolver,
            EventConverter eventConverter
    ) {
        requireNonNull(sealedType, "The sealed type cannot be null.");
        requireNonNull(evolver, "The evolver cannot be null.");
        requireNonNull(messageTypeResolver, "The message type resolver cannot be null.");
        requireNonNull(eventConverter, "The event converter cannot be null.");
        List<Class<? extends S>> subtypes = ReflectionUtils.collectConcreteSealedTypes(sealedType);
        if (subtypes.isEmpty()) {
            throw new IllegalArgumentException("The type [" + sealedType.getName() + "] is not a sealed type.");
        }
        Map<QualifiedName, EntityEvolver<E>> evolvers = new HashMap<>();
        Map<QualifiedName, Class<?>> subtypeByName = new HashMap<>();
        for (Class<? extends S> subtype : subtypes) {
            QualifiedName name = messageTypeResolver.resolveOrThrow(subtype).qualifiedName();
            Class<?> clashing = subtypeByName.put(name, subtype);
            if (clashing != null) {
                throw new IllegalArgumentException(
                        "The subtypes [" + clashing.getName() + "] and [" + subtype.getName() + "] of the sealed type ["
                                + sealedType.getName() + "] both resolve to the name [" + name.fullName() + "]."
                                + " Give each type a distinct name, for example with @Event(name = ...)."
                );
            }
            evolvers.put(name, (entity, event, context) -> evolver.apply(
                    entity, event.payloadAs(subtype, eventConverter)
            ));
        }
        return new SimpleEntityEvolvingComponent<>(evolvers);
    }

    @Override
    public E evolve(E entity,
                    EventMessage event,
                    ProcessingContext context) {
        QualifiedName eventName = event.type().qualifiedName();
        EntityEvolver<E> entityEvolver = entityEvolvers.get(eventName);

        if (entityEvolver == null) {
            logger.debug(
                    "Returning the entity as-is since we could not find an entity evolver for named event [{}].",
                    eventName
            );
            return entity;
        }
        return entityEvolver.evolve(entity, event, context);
    }

    @Override
    public Set<QualifiedName> supportedEvents() {
        return supportedEvents;
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("delegates", entityEvolvers);
    }
}
