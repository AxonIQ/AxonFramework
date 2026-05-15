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

import org.axonframework.common.FutureUtils;
import org.axonframework.common.Registration;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine.AppendTransaction;
import org.axonframework.messaging.core.Context.ResourceKey;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * An {@link EventStore} implementation which uses an {@link EventStorageEngine} as its storage solution.
 *
 * @author Allard Buijze
 * @author Rene de Waele
 * @author Steven van Beelen
 * @since 3.0.0
 */
public class StorageEngineBackedEventStore implements EventStore {

    private final EventStorageEngine eventStorageEngine;
    private final EventBus eventBus;
    private final TagResolver tagResolver;

    private final ResourceKey<EventStoreTransaction> eventStoreTransactionKey;

    /**
     * Constructs a {@code SimpleEventStore} using the given {@code eventStorageEngine} to start
     * {@link #transaction(ProcessingContext) transactions} and {@link #open(StreamingCondition, ProcessingContext) open event streams}
     * with.
     *
     * @param eventStorageEngine The {@link EventStorageEngine} used to start
     *                           {@link #transaction(ProcessingContext) transactions} and
     *                           {@link #open(StreamingCondition, ProcessingContext) open event streams} with.
     * @param eventBus           The {@link EventBus} used to publish events to the subscribers of the event store.
     * @param tagResolver        The {@link TagResolver} used to resolve tags during appending events in the
     *                           {@link EventStoreTransaction}.
     */
    public StorageEngineBackedEventStore(
            EventStorageEngine eventStorageEngine,
            EventBus eventBus,
            TagResolver tagResolver
    ) {
        this.eventStorageEngine = eventStorageEngine;
        this.eventBus = eventBus;
        this.tagResolver = tagResolver;

        this.eventStoreTransactionKey = ResourceKey.withLabel("eventStoreTransaction");
    }

    @Override
    public EventStoreTransaction transaction(ProcessingContext processingContext) {
        return processingContext.computeResourceIfAbsent(
                eventStoreTransactionKey,
                () -> {
                    var eventStoreTransaction = new DefaultEventStoreTransaction(eventStorageEngine, processingContext, this::tagEvents);
                    eventStoreTransaction.onAppend(events -> eventBus.publish(processingContext, events).join());
                    return eventStoreTransaction;
                }
        );
    }

    @Override
    public CompletableFuture<Void> publish(@Nullable ProcessingContext context,
                                           List<? extends EventMessage> events) {
        if (context == null) {
            AppendCondition none = AppendCondition.none();
            List<TaggedEventMessage<?>> taggedEvents = new ArrayList<>();
            for (EventMessage event : events) {
                taggedEvents.add(tagEvents(event));
            }
            return eventStorageEngine.appendEvents(none, context, taggedEvents)
                                     .thenApply(StorageEngineBackedEventStore::castTransaction)
                                     .thenCompose(tx -> tx.commit().thenApply(v -> tx.afterCommit(v)))
                                     .thenApply(marker -> null)
                                     .thenCompose(r -> eventBus.publish(context, events));
        } else {
            // Return a completed future since we have an active context.
            // The user will wait within the context's lifecycle anyhow.
            appendToTransaction(context, events);
            return FutureUtils.emptyCompletedFuture();
        }
    }

    private TaggedEventMessage<EventMessage> tagEvents(EventMessage event) {
        return new GenericTaggedEventMessage<>(event, tagResolver.resolve(event));
    }

    private void appendToTransaction(ProcessingContext context,
                                     List<? extends EventMessage> events) {
        EventStoreTransaction transaction = transaction(context);
        for (EventMessage event : events) {
            transaction.appendEvent(event);
        }
    }

    @Override
    public CompletableFuture<TrackingToken> firstToken(@Nullable ProcessingContext context) {
        return eventStorageEngine.firstToken();
    }

    @Override
    public CompletableFuture<TrackingToken> latestToken(@Nullable ProcessingContext context) {
        return eventStorageEngine.latestToken();
    }

    @Override
    public CompletableFuture<TrackingToken> tokenAt(Instant at, @Nullable ProcessingContext context) {
        return eventStorageEngine.tokenAt(at);
    }

    @Override
    public MessageStream<EventMessage> open(StreamingCondition condition,
                                            @Nullable ProcessingContext context) {
        return eventStorageEngine.stream(condition);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("eventStorageEngine", eventStorageEngine);
        descriptor.describeProperty("eventBus", eventBus);
        descriptor.describeProperty("tagResolver", tagResolver);
    }

    @SuppressWarnings("unchecked")
    private static AppendTransaction<Object> castTransaction(AppendTransaction<?> at) {
        return (AppendTransaction<Object>) at;
    }

    @Override
    public Registration subscribe(
            BiFunction<List<? extends EventMessage>, ProcessingContext, CompletableFuture<?>> eventsBatchConsumer) {
        return eventBus.subscribe(eventsBatchConsumer);
    }
}
