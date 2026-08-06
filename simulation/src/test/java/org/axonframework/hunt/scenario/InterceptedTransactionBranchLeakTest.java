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

package org.axonframework.hunt.scenario;

import org.axonframework.eventsourcing.eventstore.ConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.InterceptingEventStore;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.eventsourcing.eventstore.StorageEngineBackedEventStore;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.core.ApplicationContext;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.SimpleEntry;
import org.axonframework.messaging.core.correlation.MessageOriginProvider;
import org.axonframework.messaging.core.interception.CorrelationDataInterceptor;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.interception.InterceptingEventHandlingComponent;
import org.axonframework.messaging.eventhandling.processing.ProcessorEventHandlingComponents;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether an event appended while handling the second message of a shared batch is stamped with that message's own
 * correlation data, or with the first message's.
 * <p>
 * The framework documents the rule plainly on {@code ProcessingContext#computeResourceIfAbsent}: never cache a
 * resource whose construction closes over the {@code ProcessingContext}, unless the key is one every possible branch
 * overrides -- "the first branch to call this method gets its instance cached on the shared root, and every sibling
 * branch that calls afterward receives that same stale instance back - silently operating against the wrong branch".
 * The rule was added when the same defect was removed from {@code CommandDispatcher.forContext},
 * {@code EventAppender.forContext} and {@code QueryUpdateEmitter.forContext}.
 * <p>
 * {@code InterceptingEventStore.transaction(ProcessingContext)} still does exactly what the rule forbids: it caches
 * an {@code InterceptingEventStoreTransaction} -- a wrapper that stores the context it was created with and runs
 * every {@code appendEvent} through the dispatch interceptor chain <b>with that captured context</b> -- under a
 * resource key no per-message branch overrides. In a batch, the first message's handler creates the wrapper on the
 * shared batch root; every later message's {@code appendEvent} is intercepted with the first message's branch, so
 * {@code CorrelationDataInterceptor} stamps the later messages' events with the first message's
 * {@code correlationId}/{@code causationId} -- corrupted causation persisted in business metadata.
 * <p>
 * <b>The transaction arm asserts the leak, not the guarantee.</b> It is an expected-gap test: it passes while
 * {@code InterceptingEventStore} caches a context-bound wrapper and turns red the moment {@code appendEvent} is
 * intercepted with the context the append was actually made from, which is what closes the gap. A failure there is
 * the good news. The publish arm asserts the guarantee and must stay green on both sides of the fix: since the
 * forContext caches were removed, {@code EventSink.publish} intercepts with the context the caller passed in, and
 * the differential between the two arms pins the defect to the cached transaction wrapper alone.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class InterceptedTransactionBranchLeakTest {

    private static final String INPUT_NAME = "hunt.branchleak.Input";
    private static final String DERIVED_NAME = "hunt.branchleak.Derived";
    private static final String CAUSATION = MessageOriginProvider.DEFAULT_CAUSATION_KEY;
    private static final String CORRELATION = MessageOriginProvider.DEFAULT_CORRELATION_KEY;

    /**
     * The probe resolves no components: everything it needs is constructed directly.
     */
    private static final ApplicationContext NO_COMPONENTS = new ApplicationContext() {
        @Override
        public <C> C component(Class<C> type, @Nullable String name) {
            throw new UnsupportedOperationException(
                    "The branch-leak probe provides no component of type [" + type.getName() + "].");
        }
    };

    @Nested
    class AppendingThroughTheEventStoreTransaction {

        @Test
        void secondEventsAppendIsStampedWithFirstEventsCorrelationData() {
            // given a default-shaped store chain: engine -> store -> intercepting store with correlation stamping
            InMemoryEventStorageEngine engine = new InMemoryEventStorageEngine();
            EventStore plainStore = new StorageEngineBackedEventStore(
                    engine, new SimpleEventBus(), event -> Set.of(new Tag("hunt", "probe")));
            EventStore store = new InterceptingEventStore(
                    plainStore, List.of(new CorrelationDataInterceptor<>(new MessageOriginProvider())));

            // and a handler that appends a derived event through the event store transaction,
            // as an event-sourced entity's lifecycle handler does
            EventMessage one = input("one");
            EventMessage two = input("two");
            Map<String, Map<String, String>> stamped = runBatch(
                    List.of(one, two),
                    (event, ctx) -> store.transaction(ctx)
                                         .appendEvent(derived(event)),
                    engine);

            // then the first message's append is stamped correctly, because its own branch built the cached wrapper
            assertThat(stamped.get("derived-one"))
                    .as("metadata of the event appended while handling batch message 1")
                    .containsEntry(CAUSATION, one.identifier())
                    .containsEntry(CORRELATION, one.identifier());
            // and the second message's append is stamped with the FIRST message's identifiers: the cached wrapper
            // closed over message 1's branch, and message 2's computeResourceIfAbsent fell through to the shared
            // root and got it back. This assertion pins the defect; when InterceptingEventStore stops caching a
            // context-bound wrapper it flips red, and the causation below must then equal two.identifier().
            System.out.println("BRANCH LEAK derived-two causationId=" + stamped.get("derived-two").get(CAUSATION)
                                       + " message-1 id=" + one.identifier()
                                       + " message-2 id=" + two.identifier());
            assertThat(stamped.get("derived-two"))
                    .as("metadata of the event appended while handling batch message 2 (expected-gap: "
                                + "carries message 1's identifiers while the wrapper cache is in place)")
                    .containsEntry(CAUSATION, one.identifier())
                    .containsEntry(CORRELATION, one.identifier());
        }
    }

    @Nested
    class AppendingThroughThePublishPath {

        /**
         * The control arm: the publish path ({@code EventSink.publish}, which is what
         * {@code EventAppender.forContext} uses since the forContext caches were removed) intercepts with the context
         * the caller passed in, so the same batch stamps every derived event correctly. The differential pins the
         * defect to the cached transaction wrapper, not to the interceptor or the batch pipeline.
         */
        @Test
        void secondEventsPublishCarriesItsOwnCausation() {
            // given the identical store chain
            InMemoryEventStorageEngine engine = new InMemoryEventStorageEngine();
            EventStore plainStore = new StorageEngineBackedEventStore(
                    engine, new SimpleEventBus(), event -> Set.of(new Tag("hunt", "probe")));
            EventStore store = new InterceptingEventStore(
                    plainStore, List.of(new CorrelationDataInterceptor<>(new MessageOriginProvider())));

            // and a handler that publishes its derived event instead of appending it to the transaction
            EventMessage one = input("one");
            EventMessage two = input("two");
            Map<String, Map<String, String>> stamped = runBatch(
                    List.of(one, two),
                    (event, ctx) -> store.publish(ctx, List.of(derived(event)))
                                         .orTimeout(30, TimeUnit.SECONDS)
                                         .join(),
                    engine);

            // then each derived event carries the identifiers of the event whose handling published it
            assertThat(stamped.get("derived-one"))
                    .containsEntry(CAUSATION, one.identifier())
                    .containsEntry(CORRELATION, one.identifier());
            assertThat(stamped.get("derived-two"))
                    .containsEntry(CAUSATION, two.identifier())
                    .containsEntry(CORRELATION, two.identifier());
        }
    }

    /**
     * Handles the given events as one batch in one unit of work, the shape a pooled streaming processor produces:
     * one shared root context, one per-event branch per entry (the entry's tracking token, plus the
     * {@code CorrelationDataInterceptor}'s per-message correlation branch, applied by the same
     * {@code InterceptingEventHandlingComponent} the processor modules install). Returns the metadata of every event
     * the storage engine holds afterward, keyed by payload.
     */
    private static Map<String, Map<String, String>> runBatch(
            List<EventMessage> inputs,
            BiConsumer<EventMessage, org.axonframework.messaging.core.unitofwork.ProcessingContext> appender,
            InMemoryEventStorageEngine engine
    ) {
        SimpleEventHandlingComponent inner = SimpleEventHandlingComponent.create("branch-leak-probe");
        inner.subscribe(new QualifiedName(INPUT_NAME), (event, ctx) -> {
            appender.accept(event, ctx);
            return MessageStream.empty();
        });
        var handling = new InterceptingEventHandlingComponent(
                List.of(new CorrelationDataInterceptor<>(new MessageOriginProvider())), inner);
        var components = new ProcessorEventHandlingComponents(List.of(handling));

        List<MessageStream.Entry<? extends EventMessage>> entries = new ArrayList<>();
        long position = 0;
        for (EventMessage event : inputs) {
            entries.add(new SimpleEntry<>(event)
                                .withResource(TrackingToken.RESOURCE_KEY, new GlobalSequenceTrackingToken(++position)));
        }

        new SimpleUnitOfWorkFactory(NO_COMPONENTS)
                .create()
                .executeWithResult(context -> components.handle(entries, context).asCompletableFuture())
                .orTimeout(30, TimeUnit.SECONDS)
                .join();

        return storedMetadataByPayload(engine);
    }

    private static Map<String, Map<String, String>> storedMetadataByPayload(InMemoryEventStorageEngine engine) {
        Map<String, Map<String, String>> byPayload = new HashMap<>();
        MessageStream<EventMessage> stream =
                engine.source(SourcingCondition.conditionFor(EventCriteria.havingAnyTag()), null);
        try {
            for (var entry = stream.next(); entry.isPresent(); entry = stream.next()) {
                if (entry.get().getResource(ConsistencyMarker.RESOURCE_KEY) != null) {
                    continue;
                }
                EventMessage message = entry.get().message();
                byPayload.put(String.valueOf(message.payload()), new HashMap<>(message.metadata()));
            }
        } finally {
            stream.close();
        }
        return byPayload;
    }

    private static EventMessage input(String payload) {
        return new GenericEventMessage(new MessageType(INPUT_NAME), payload);
    }

    private static EventMessage derived(EventMessage cause) {
        return new GenericEventMessage(new MessageType(DERIVED_NAME), "derived-" + cause.payload());
    }
}
