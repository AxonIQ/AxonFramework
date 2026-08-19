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

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.Set;

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
