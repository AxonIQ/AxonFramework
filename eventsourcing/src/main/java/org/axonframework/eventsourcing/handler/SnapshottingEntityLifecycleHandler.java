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
import org.axonframework.conversion.Converter;
import org.axonframework.eventsourcing.CriteriaResolver;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.EventStoreTransaction;
import org.axonframework.eventsourcing.eventstore.Position;
import org.axonframework.eventsourcing.eventstore.SnapshotEventMessage;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.eventsourcing.eventstore.SourcingStrategy;
import org.axonframework.eventsourcing.snapshot.api.EvolutionResult;
import org.axonframework.eventsourcing.snapshot.api.Snapshot;
import org.axonframework.eventsourcing.snapshot.api.SnapshotPolicy;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.repository.ManagedEntity;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * An {@link EntityLifecycleHandler} implementation that reconstructs an entity from the
 * {@link EventStore}, using snapshots to improve efficiency when available.
 * <p>
 * When sourcing an entity, this handler will use a previously stored snapshot as a starting point
 * if one is available and compatible. If no suitable snapshot is found, the entity is reconstructed
 * by replaying its full event stream.
 * <p>
 * All events are applied in order using the configured {@link InitializingEntityEvolver} to rebuild
 * the entity's state.
 * <p>
 * After reconstruction, a new snapshot may be stored in the {@link SnapshotStore}, depending on the
 * configured {@link SnapshotPolicy}. This policy determines whether snapshot creation is desirable
 * based on the characteristics of the sourcing operation or the events encountered.
 * <p>
 * If an existing snapshot cannot be used, the handler will automatically fall back to full
 * reconstruction to ensure the entity can always be loaded.
 *
 * @param <I> the type of the entity identifier
 * @param <E> the type of the entity
 * @since 5.1.0
 * @author John Hendrikx
 */
@Internal
public class SnapshottingEntityLifecycleHandler<I, E> implements EntityLifecycleHandler<I, E> {
    private static final Logger LOGGER = LoggerFactory.getLogger(SnapshottingEntityLifecycleHandler.class);

    private final EventStore eventStore;
    private final CriteriaResolver<I> criteriaResolver;
    private final MessageType messageType;
    private final SnapshotPolicy snapshotPolicy;
    private final Class<?> entityType;
    private final Converter converter;
    private final InitializingEntityEvolver<I, E> evolver;
    private final SnapshotStore snapshotStore;

    /**
     * Constructs a new instance.
     *
     * @param eventStore the {@link EventStore} used to source events, cannot be {@code null}
     * @param criteriaResolver the resolver to use to create the {@link EventCriteria} for sourcing, cannot be {@code null}
     * @param evolver the {@link InitializingEntityEvolver} used to initialize and evolve the entity, cannot be {@code null}
     * @param snapshotPolicy the {@link SnapshotPolicy}, cannot be {@code null}
     * @param messageType the {@link MessageType}, cannot be {@code null}
     * @param converter the {@link Converter} to use to decode snapshots, cannot be {@code null}
     * @param entityType the type a snapshot should decode to, cannot be {@code null}
     * @param snapshotStore the {@link SnapshotStore} to use for storing snapshots, cannot be {@code null}
     * @throws NullPointerException when a non-null argument was {@code null}
     */
    public SnapshottingEntityLifecycleHandler(
        EventStore eventStore,
        CriteriaResolver<I> criteriaResolver,
        InitializingEntityEvolver<I, E> evolver,
        SnapshotPolicy snapshotPolicy,
        MessageType messageType,
        Converter converter,
        Class<?> entityType,
        SnapshotStore snapshotStore
    ) {
        this.eventStore = Objects.requireNonNull(eventStore, "The eventStore parameter must not be null.");
        this.criteriaResolver = Objects.requireNonNull(criteriaResolver, "The criteriaResolver parameter must not be null.");
        this.evolver = Objects.requireNonNull(evolver, "The evolver parameter must not be null.");
        this.snapshotPolicy = Objects.requireNonNull(snapshotPolicy, "The snapshotPolicy parameter must not be null.");
        this.messageType = Objects.requireNonNull(messageType, "The messageType parameter must not be null.");
        this.converter = Objects.requireNonNull(converter, "The converter parameter must not be null.");
        this.entityType = Objects.requireNonNull(entityType, "The entityType parameter must not be null.");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "The snapshotStore parameter must not be null.");
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
        long startTime = System.currentTimeMillis();
        EventCriteria criteria = criteriaResolver.resolve(identifier, pc);
        EventStoreTransaction transaction = eventStore.transaction(pc);
        SourcingCondition condition = SourcingCondition.conditionFor(
            new SourcingStrategy.Snapshot(messageType, identifier),
            criteria
        );
        AtomicReference<Position> positionRef = new AtomicReference<>();
        AtomicInteger evolutionCount = new AtomicInteger();
        AtomicBoolean snapshotTriggered = new AtomicBoolean();
        MessageStream<? extends EventMessage> source = transaction.source(condition, positionRef::set);

        BiFunction<E, MessageStream.Entry<? extends EventMessage>, E> accumulator =
            (entity, entry) -> switch (entry.message()) {
                case SnapshotEventMessage sem -> convertSnapshotPayload(identifier, sem.payload());
                case EventMessage em -> {
                    E evolvedEntity = evolver.evolve(identifier, entity, em, pc);

                    evolutionCount.incrementAndGet();

                    if (!snapshotTriggered.get() && snapshotPolicy.shouldSnapshot(em)) {
                        snapshotTriggered.set(true);
                    }

                    yield evolvedEntity;
                }
            };

        return source
            .reduce((E) null, accumulator)
            .exceptionallyCompose(e -> switch (e) {
                case SnapshotConversionException sle ->
                    transaction.source(SourcingCondition.conditionFor(Position.START, criteria), positionRef::set)
                        .reduce((E) null, accumulator);
                default -> CompletableFuture.failedFuture(e);
            })
            .thenApply(entity -> {
                if (evolutionCount.get() > 0) {
                    Duration sourcingTime = Duration.ofMillis(Math.max(0, System.currentTimeMillis() - startTime));

                    // Snapshot is made when specifically triggered by an event, or based on the statistics:
                    if (snapshotTriggered.get() || snapshotPolicy.shouldSnapshot(new EvolutionResult(evolutionCount.get(), sourcingTime))) {
                        storeSnapshot(identifier, entity, positionRef.get());
                    }
                }

                return entity;
            });
    }

    private void storeSnapshot(I identifier, E entity, Position position) {
        Snapshot newSnapshot = new Snapshot(position, messageType.version(), entity, GenericEventMessage.clock.instant(), Map.of());

        snapshotStore.store(messageType.qualifiedName(), identifier, newSnapshot)
            .whenComplete((voidResult, ex) -> {
                if (ex != null) {
                    LOGGER.warn("Snapshotting failed for {} with identifier {}", messageType, identifier, ex);
                }
            });
    }

    private E convertSnapshotPayload(I identifier, Snapshot snapshot) {
        try {
            if (snapshot.version().equals(messageType.version())) {
                @SuppressWarnings("unchecked")
                E entity = (E) (entityType.isInstance(snapshot.payload())
                    ? snapshot.payload()
                    : snapshot.payload(converter.convert(snapshot.payload(), entityType)).payload()
                );

                return entity;
            }

            // Version mismatched, ignore this snapshot (until there is version handling support)
            LOGGER.info("Unsupported snapshot version. Snapshot of {} for identifier {} had unsupported version: {}", messageType, identifier, snapshot.version());
        }
        catch (Exception e) {
            LOGGER.warn("Snapshot loading failed, falling back to full reconstruction for: {} ({})", messageType, identifier, e);
        }

        throw new SnapshotConversionException();
    }

    private static class SnapshotConversionException extends RuntimeException {}

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("eventStore", eventStore);
        descriptor.describeProperty("criteriaResolver", criteriaResolver);
        descriptor.describeProperty("evolver", evolver);
        descriptor.describeProperty("messageType", messageType);
        descriptor.describeProperty("entityType", entityType);
        descriptor.describeProperty("converter", converter);
        descriptor.describeProperty("snapshotPolicy", snapshotPolicy);
        descriptor.describeProperty("snapshotStore", snapshotStore);
    }
}
