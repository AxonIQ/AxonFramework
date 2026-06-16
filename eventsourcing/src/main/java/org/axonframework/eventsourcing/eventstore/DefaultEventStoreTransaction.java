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

package org.axonframework.eventsourcing.eventstore;

import org.axonframework.common.ObjectUtils;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine.AppendTransaction;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.Context.ResourceKey;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.entity.EntityMetamodel;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static java.util.Objects.requireNonNullElse;
import static org.axonframework.common.ObjectUtils.getOrDefault;

/**
 * The default {@link EventStoreTransaction}.
 * <p>
 * While {@link #source(SourcingCondition) sourcing} it will map the {@link SourcingCondition} into an
 * {@link AppendCondition} for {@link #appendEvent(EventMessage) appending}, taking into account several sourcing
 * invocation might have occurred in the same {@link ProcessingContext}. During
 * {@link #appendEvent(EventMessage) appending} it will pass along a collection of {@link EventMessage events} to an
 * {@link EventStorageEngine} is part of the prepare commit phase of the {@link ProcessingContext}.
 *
 * @author Steven van Beelen
 * @author John Hendrikx
 * @since 5.0.0
 */
public class DefaultEventStoreTransaction implements EventStoreTransaction {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final EventStorageEngine eventStorageEngine;
    private final ProcessingContext processingContext;
    private final Function<EventMessage, TaggedEventMessage<?>> eventTagger;

    private final List<Consumer<EventMessage>> callbacks = new CopyOnWriteArrayList<>();

    private final ResourceKey<AppendCondition> appendConditionKey = ResourceKey.withLabel("appendCondition");
    private final ResourceKey<List<TaggedEventMessage<?>>> eventQueueKey = ResourceKey.withLabel("eventQueue");
    private final ResourceKey<ConsistencyMarker> appendPositionKey = ResourceKey.withLabel("appendPosition");
    private final ResourceKey<Boolean> prepareCommitExecuted = ResourceKey.withLabel("prepareCommitExecuted");
    private final ResourceKey<UnaryOperator<AppendCondition>> conditionOverrideKey = ResourceKey.withLabel("conditionOverride");

    /**
     * Constructs a {@code DefaultEventStoreTransaction} using the given {@code eventStorageEngine} to
     * {@link #appendEvent(EventMessage) append events} originating from the given {@code context}.
     *
     * @param eventStorageEngine The {@link EventStorageEngine} used to {@link #appendEvent(EventMessage) append events}
     *                           with.
     * @param processingContext  The {@link ProcessingContext} from which to
     *                           {@link #appendEvent(EventMessage) append events} and attach resources to.
     * @param eventTagger        A function that will process each {@link EventMessage} to attach
     *                           {@link Tag Tags}, before it is added to the
     *                           transaction.
     */
    public DefaultEventStoreTransaction(EventStorageEngine eventStorageEngine,
                                        ProcessingContext processingContext,
                                        Function<EventMessage, TaggedEventMessage<?>> eventTagger) {
        this.eventStorageEngine = eventStorageEngine;
        this.processingContext = processingContext;
        this.eventTagger = eventTagger;
    }

    @Override
    public MessageStream<? extends EventMessage> source(SourcingCondition condition) {
        return source(condition, null);
    }

    @Override
    public MessageStream<? extends EventMessage> source(
        SourcingCondition condition,
        @Nullable Consumer<Position> resumePositionCallback
    ) {
        var appendCondition = processingContext.updateResource(
                appendConditionKey,
                ac -> ac == null
                        ? AppendCondition.withCriteria(condition.criteria())
                        : ac.orCriteria(condition.criteria())
        );

        MessageStream<EventMessage> source = eventStorageEngine.source(condition);
        AtomicReference<ConsistencyMarker> markerReference = new AtomicReference<>(appendCondition.consistencyMarker());

        return source.onNext(entry -> {
                         ConsistencyMarker marker = entry.getResource(ConsistencyMarker.RESOURCE_KEY);

                         if (marker != null) {
                             markerReference.set(marker);
                         }
                     })
                     .filter(entry -> entry.getResource(ConsistencyMarker.RESOURCE_KEY) == null)
                     .onComplete(() -> {
                         ConsistencyMarker marker = markerReference.get();

                         if (!Boolean.TRUE.equals(processingContext.getResource(prepareCommitExecuted))) {
                             updateAppendPosition(marker);
                         }

                         if (resumePositionCallback != null) {
                             resumePositionCallback.accept(marker.position());
                         }
                     });
    }

    /**
     * When reading is complete, we choose the lowest, non-ORIGIN appendPosition as our next appendPosition when reading
     * multiple times, the lowest consistency marker that we received from those streams (usually the first), is the
     * safest one to use.
     */
    private void updateAppendPosition(ConsistencyMarker marker) {
        processingContext.updateResource(
                appendPositionKey,
                current -> {
                    if (current == null || current == ConsistencyMarker.ORIGIN) {
                        // This is the first time we are sourcing events, as such will be the correct ConsistencyMarker.
                        return marker;
                    }
                    // We received a stream of events, while we already sourced before. The lowest of the two is the safest to use.
                    return current.lowerBound(marker);
                });
    }

    @Override
    public void appendEvent(EventMessage eventMessage) {
        List<TaggedEventMessage<?>> eventQueue = processingContext.computeResourceIfAbsent(
                eventQueueKey,
                () -> {
                    attachAppendEventsStep();
                    return new CopyOnWriteArrayList<>();
                }
        );
        TaggedEventMessage<?> taggedEvent = eventTagger.apply(eventMessage);
        eventQueue.add(taggedEvent);
        Set<Tag> tags = taggedEvent.tags();
        if (appendingWithoutSourcing() && !tags.isEmpty()) {
            // No AppendCondition is present, but the event contains tags.
            // Tags make no sense without an AppendCondition, so let's create an ORIGIN call
            processingContext.computeResourceIfAbsent(
                    appendConditionKey,
                    () -> new DefaultAppendCondition(ConsistencyMarker.ORIGIN, EventCriteria.havingTags(tags))
            );
        }
        callbacks.forEach(callback -> callback.accept(eventMessage));
    }

    /**
* Returns {@code true} if the {@link EntityMetamodel#CREATE_WITHOUT_LOAD} is set to {@code true}.
     * <p>
     * Typically, the {@code DefaultEventStoreTransaction} constructs an {@link AppendCondition} based on a
     * {@link SourcingCondition}, thus it creates the {@code AppendCondition} as a result from <b>loading</b> an entity.
     * Whenever a creational command is <b>not</b> instructed to load an entity (e.g. because no
     * {@link org.axonframework.modelling.annotation.TargetEntityId} is used), the use of an {@code AppendCondition} is
     * still required in some cases.
     * <p>
     * One of these is when {@link EntityMetamodel#handleCreate(CommandMessage, ProcessingContext)} is invoked on the
     * {@link EntityMetamodel} without loading first. The {@code CREATE_WITHOUT_LOAD} {@link ResourceKey} signals these
     * scenarios.
     *
     * @return {@code true} if {@link EntityMetamodel#CREATE_WITHOUT_LOAD} is {@code true}, {@code false} otherwise
     */
    private boolean appendingWithoutSourcing() {
        return ObjectUtils.getOrDefault(processingContext.getResource(EntityMetamodel.CREATE_WITHOUT_LOAD), false);
    }

    private void attachAppendEventsStep() {
        processingContext.onPrepareCommit(
                context -> {
                    AppendCondition appendCondition = resolveAppendCondition(context);

                    context.putResource(prepareCommitExecuted, true);

                    List<TaggedEventMessage<?>> eventQueue =
                            Objects.requireNonNullElse(context.getResource(eventQueueKey), Collections.emptyList());

                    return eventStorageEngine.appendEvents(appendCondition, processingContext, eventQueue)
                                             .thenApply(DefaultEventStoreTransaction::castTransaction)
                                             .thenAccept(tx -> {
                                                 processingContext.onCommit(c -> tx.commit()
                                                     .thenAccept(v -> processingContext.onAfterCommit(c2 -> doAfterCommit(c2, tx, v)))
                                                 );
                                                 processingContext.onError((c, p, e) -> tx.rollback());
                                             });
                }
        );
    }

    /**
     * Resolves the final {@link AppendCondition} for this transaction by combining the sourcing-derived condition with
     * the marker obtained during reading, and then applying any user-provided
     * {@link #overrideAppendCondition(UnaryOperator) override}.
     */
    private AppendCondition resolveAppendCondition(ProcessingContext context) {
        AppendCondition resolved = context.updateResource(appendConditionKey, current -> {
            if (current == null || AppendCondition.none().equals(current)) {
                return AppendCondition.none();
            }
            return current.withMarker(getOrDefault(context.getResource(appendPositionKey),
                                                   current.consistencyMarker()));
        });

        UnaryOperator<AppendCondition> override = context.getResource(conditionOverrideKey);
        if (override == null) {
            return resolved;
        }

        // A null return from the override is treated as AppendCondition.none() (no conflict detection).
        AppendCondition overridden = getOrDefault(override.apply(resolved), AppendCondition.none());
        logger.debug("AppendCondition overridden from [{}] to [{}]", resolved, overridden);
        return overridden;
    }

    private <R> CompletableFuture<ConsistencyMarker> doAfterCommit(ProcessingContext context,
                                                                   EventStorageEngine.AppendTransaction<R> tx,
                                                                   R commitResult) {
        return tx.afterCommit(commitResult)
                 .whenComplete((position, exception) -> {
                     if (position != null) {
                         context.updateResource(
                                 appendPositionKey,
                                 other -> position.upperBound(requireNonNullElse(other, ConsistencyMarker.ORIGIN)));
                     }
                 });
    }

    @Override
    public void overrideAppendCondition(UnaryOperator<AppendCondition> conditionOverride) {
        Objects.requireNonNull(conditionOverride, "The conditionOverride cannot be null");
        processingContext.updateResource(conditionOverrideKey, previous ->
                previous == null ? conditionOverride : ac -> conditionOverride.apply(previous.apply(ac))
        );
    }

    @Override
    public void onAppend(Consumer<EventMessage> callback) {
        callbacks.add(callback);
    }

    @Override
    public ConsistencyMarker appendPosition() {
        return getOrDefault(processingContext.getResource(appendPositionKey), ConsistencyMarker.ORIGIN);
    }

    @SuppressWarnings("unchecked")
    private static AppendTransaction<Object> castTransaction(AppendTransaction<?> at) {
        return (AppendTransaction<Object>) at;
    }
}
