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

import java.util.function.BiFunction;

import static java.util.Objects.requireNonNull;

/**
 * An {@link EntityEvolver} implementation that converts the {@link EventMessage#payload()} to the given
 * {@code payloadType} to evolve an entity with.
 * <p>
 * Will throw a {@link ClassCastException} if the {@code payloadType} does not match.
 *
 * @param <P> The payload type of the event to apply.
 * @param <E> The entity type to evolve.
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public class PayloadBasedEntityEvolver<P, E> implements EntityEvolver<E> {

    private final Class<P> payloadType;
    private final BiFunction<E, P, E> evolver;

    /**
     * Constructs a {@code PayloadConvertingEntityEvolver}, converting the {@link EventMessage#payload()} to the
     * given {@code payloadType}, after which it invokes the given {@code evolver}.
     * <p>
     * If the {@link EventMessage#payload()} cannot be converted to the requested {@code payloadType}, a
     * {@link ClassCastException} is thrown.
     *
     * @param payloadType The payload type to check against.
     * @param evolver     The function to evolve the entity with.
     */
    public PayloadBasedEntityEvolver(Class<P> payloadType,
                                     BiFunction<E, P, E> evolver) {
        this.payloadType = requireNonNull(payloadType, "The payload type must not be null.");
        this.evolver = requireNonNull(evolver, "The evolver must not be null.");
    }

    @Override
    public E evolve(E entity,
                    EventMessage event,
                    ProcessingContext context) {
        P payload = payloadType.cast(requireNonNull(event, "The event must not be null.").payload());
        return evolver.apply(requireNonNull(entity, "The entity must not be null."), payload);
    }
}
