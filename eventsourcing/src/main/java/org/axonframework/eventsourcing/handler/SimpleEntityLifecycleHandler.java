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

package org.axonframework.eventsourcing.handler;

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.eventsourcing.CriteriaResolver;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.EventStoreTransaction;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.repository.ManagedEntity;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * A simple implementation of {@link EntityLifecycleHandler} that reconstructs an entity
 * by sourcing events directly from an {@link EventStore}.
 * <p>
 * This implementation retrieves all events for a given identifier according to the
 * {@link CriteriaResolver} and applies them in order to construct or evolve the entity
 * using the provided {@link InitializingEntityEvolver}.
 * <p>
 * This is a straightforward, non-snapshotting sourcing strategy and is suitable
 * when all events must be applied sequentially from the event store.
 *
 * @param <I> the type of the entity identifier
 * @param <E> the type of the entity
 * @since 5.1.0
 * @author John Hendrikx
 */
@Internal
public class SimpleEntityLifecycleHandler<I, E> implements EntityLifecycleHandler<I, E> {

    private final EventStore eventStore;
    private final CriteriaResolver<I> criteriaResolver;
    private final InitializingEntityEvolver<I, E> evolver;

    /**
     * Constructs a new instance.
     *
     * @param eventStore the {@link EventStore} from which events are sourced, cannot be {@code null}
     * @param criteriaResolver the resolver to use to create the {@link EventCriteria} for sourcing, cannot be {@code null}
     * @param evolver the {@link InitializingEntityEvolver} used to initialize and evolve the entity, cannot be {@code null}
     * @throws NullPointerException when any argument is {@code null}
     */
    public SimpleEntityLifecycleHandler(EventStore eventStore, CriteriaResolver<I> criteriaResolver, InitializingEntityEvolver<I, E> evolver) {
        this.eventStore = Objects.requireNonNull(eventStore, "The eventStore parameter must not be null.");
        this.criteriaResolver = Objects.requireNonNull(criteriaResolver, "The criteriaResolver parameter must not be null.");
        this.evolver = Objects.requireNonNull(evolver, "The evolver parameter must not be null.");
    }

    @Override
    public E initialize(I identifier, @Nullable EventMessage firstEventMessage, ProcessingContext context) {
        return evolver.initialize(identifier, firstEventMessage, context);
    }

    @Override
    public void subscribe(ManagedEntity<I, E> entity, ProcessingContext context) {
        eventStore.transaction(context)
            .onAppend(event -> entity.applyStateChange(e -> evolver.evolve(
                entity.identifier(),
                entity.entity(),
                event,
                context
            )));
    }

    @Override
    public CompletableFuture<E> source(I identifier, ProcessingContext pc) {
        EventCriteria criteria = criteriaResolver.resolve(identifier, pc);
        EventStoreTransaction transaction = eventStore.transaction(pc);
        MessageStream<? extends EventMessage> source = transaction.source(SourcingCondition.conditionFor(criteria));

        return source.reduce(
            (E) null,
            (entity, entry) -> evolver.evolve(identifier, entity, entry.message(), pc)
        );
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("eventStore", eventStore);
        descriptor.describeProperty("criteriaResolver", criteriaResolver);
        descriptor.describeProperty("evolver", evolver);
    }
}
