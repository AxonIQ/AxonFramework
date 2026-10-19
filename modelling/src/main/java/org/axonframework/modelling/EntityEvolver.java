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

import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

/**
 * Functional interface describing how to evolve a given {@code entity} of type {@code e} based on a given
 * {@link EventMessage}.
 * <p>
 * The {@code entity} may be {@code null}, representing an entity that does not exist (yet). An implementation may create
 * the entity from the given {@code event} (returning a non-null instance), leave it absent (returning {@code null}), or
 * evolve an existing instance. Likewise, the returned state may be {@code null} to indicate the entity does not exist
 * after applying the {@code event}. Implementations that do not support this functional, create-or-remove style are
 * free to reject a {@code null} {@code entity}.
 *
 * @param <E> The entity type to evolve.
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @author Steven van Beelen
 * @since 5.0.0
 */
@FunctionalInterface
public interface EntityEvolver<E> {

    /**
     * Evolve the given {@code entity} by applying the given {@code event} to it.
     *
     * @param event   The event that might adjust the {@code entity}.
     * @param entity  The current entity to evolve with the given {@code event}, or {@code null} when the entity does
     *                not exist yet.
     * @param context The context within which to evolve the {@code entity} by the given {@code event}.
     * @return The evolved {@code entity} based on the given {@code event}, the same {@code entity} when nothing
     * happened, or {@code null} when the entity does not exist after applying the {@code event}.
     */
    @Nullable
    E evolve(@Nullable E entity,
             EventMessage event,
             ProcessingContext context);
}
