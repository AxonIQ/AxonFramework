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

package org.axonframework.eventsourcing;

import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.EventStoreTransaction;
import org.axonframework.eventsourcing.handler.InitializingEntityEvolver;
import org.axonframework.eventsourcing.handler.SimpleEntityLifecycleHandler;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.repository.ManagedEntity;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.messaging.eventhandling.EventTestUtils.createEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test class validating the {@link EventSourcingRepository}.
 *
 * @author Allard Buijze
 * @author John Hendrikx
 */
@ExtendWith(MockitoExtension.class)
class EventSourcingRepositoryIT {
    private EventStore eventStore = mock();
    private EventStoreTransaction eventStoreTransaction = mock();
    private EventSourcedEntityFactory<String, String> factory;

    private EventSourcingRepository<String, String> testSubject;
    private List<EventMessage> eventsToLoad = new ArrayList<>(List.of(createEvent(0), createEvent(1)));

    @BeforeEach
    void setUp() {
        when(eventStore.transaction(any())).thenReturn(eventStoreTransaction);

        factory = (id, event, ctx) -> {
            if (event != null) {
                return id + "(" + event.payload() + ")";
            }
            return id + "()";
        };

        testSubject = new EventSourcingRepository<>(
            String.class,
            String.class,
            new SimpleEntityLifecycleHandler<>(
                eventStore,
                (id, context) -> EventCriteria.havingAnyTag(),
                new InitializingEntityEvolver<>(
                    (id, event, context) -> factory.create(id, event, context),
                    (entity, event, context) -> entity + "-" + event.payload()
                )
            )
        );

        MessageStream<? extends EventMessage> stream = MessageStream.fromIterable(eventsToLoad);

        doReturn(stream).when(eventStoreTransaction).source(any());
    }

    @Test
    void loadEventSourcedEntity() {
        ProcessingContext processingContext = new StubProcessingContext();

        ManagedEntity<String, String> result = testSubject.load("test", processingContext).join();

        assertEquals("test(0)-0-1", result.entity());

        verify(eventStore, times(2)).transaction(processingContext);
        verify(eventStoreTransaction).onAppend(any());
    }

    @Test
    void persistNewEntityRegistersItToListenToEvents() {
        ProcessingContext processingContext = new StubProcessingContext();

        ManagedEntity<String, String> result = testSubject.persist("id", "entity", processingContext);

        verify(eventStoreTransaction).onAppend(any());
        assertEquals("entity", result.entity());
        assertEquals("id", result.identifier());
    }

    @Test
    void persistAlreadyPersistedEntityDoesNotRegisterItToListenToEvents() {
        ProcessingContext processingContext = new StubProcessingContext();

        ManagedEntity<String, String> first = testSubject.persist("id", "entity", processingContext);
        ManagedEntity<String, String> second = testSubject.persist("id", "entity", processingContext);

        verify(eventStoreTransaction).onAppend(any());
        assertSame(first, second);
        assertEquals("entity", first.entity());
        assertEquals("id", first.identifier());
    }

    @Test
    void assigningEntityToOtherProcessingContextInExactFormat() throws Exception {
        ProcessingContext processingContext = new StubProcessingContext();
        ProcessingContext processingContext2 = new StubProcessingContext();

        ManagedEntity<String, String> result = testSubject.load("test", processingContext).get();

        // Attaches entity of correct internal type:
        testSubject.attach(result, processingContext2);

        verify(eventStoreTransaction, times(2)).onAppend(any());
    }

    @Test
    void assigningEntityToOtherProcessingContextInOtherFormat() throws Exception {
        ProcessingContext processingContext = new StubProcessingContext();
        ProcessingContext processingContext2 = new StubProcessingContext();

        ManagedEntity<String, String> result = testSubject.load("test", processingContext).get();

        // Attaches entity of incorrect internal type (which will then be recreated):
        testSubject.attach(new ManagedEntity<>() {
            @Override
            public String identifier() {
                return result.identifier();
            }

            @Override
            public String entity() {
                return result.entity();
            }

            @Override
            public String applyStateChange(@NonNull UnaryOperator<String> change) {
                fail("This should not have been invoked");
                return "ERROR";
            }
        }, processingContext2);

        verify(eventStoreTransaction, times(2)).onAppend(any());
    }

    @Test
    void updateLoadedEventSourcedEntity() {
        ProcessingContext processingContext = new StubProcessingContext();

        ManagedEntity<String, String> result = testSubject.load("test", processingContext).join();

        assertEquals("test(0)-0-1", result.entity());

        verify(eventStore, times(2)).transaction(processingContext);
        verify(eventStoreTransaction).onAppend(any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<EventMessage>> callback = ArgumentCaptor.forClass(Consumer.class);
        verify(eventStoreTransaction).onAppend(callback.capture());

        callback.getValue().accept(new GenericEventMessage(new MessageType("event"), "live"));
        assertEquals("test(0)-0-1-live", result.entity());
    }

    @Test
    void loadOrCreateShouldLoadWhenEventsAreReturned() {
        ProcessingContext processingContext = new StubProcessingContext();

        CompletableFuture<ManagedEntity<String, String>> result =
                testSubject.load("test", processingContext);

        assertEquals("test(0)-0-1", result.join().entity());
    }

    @Test
    void loadOrCreateThrowsExceptionWhenEventStreamIsEmptyAndNullEntityIsCreated() {
        ProcessingContext processingContext = new StubProcessingContext();

        eventsToLoad.clear();

        factory = (id, event, ctx) -> {
            if (event != null) {
                return id + "(" + event.payload() + ")";
            }
            return null; // Simulating a null entity creation
        };

        assertThatThrownBy(() -> testSubject.loadOrCreate("test", processingContext).join())
            .isInstanceOf(CompletionException.class)
            .cause()
            .isInstanceOf(EntityMissingAfterLoadOrCreateException.class);
    }

    @Test
    void loadThrowsExceptionIfNullEntityIsReturnedAfterFirstEvent() {
        ProcessingContext processingContext = new StubProcessingContext();
        factory = (id, event, ctx) -> {
            return null; // Simulating a null entity creation
        };

        assertThatThrownBy(() -> testSubject.load("test", processingContext).join())
            .isInstanceOf(CompletionException.class)
            .cause()
            .isInstanceOf(EntityMissingAfterFirstEventException.class);
    }

    @Test
    void loadShouldReturnNullEntityWhenNoEventsAreReturned() {
        StubProcessingContext processingContext = new StubProcessingContext();

        eventsToLoad.clear();

        ManagedEntity<String, String> loaded = testSubject.load("test", processingContext).join();

        assertNull(loaded.entity());

        verify(eventStore, times(2)).transaction(processingContext);
        verify(eventStoreTransaction).onAppend(any());
    }

    @Test
    void loadOrCreateShouldReturnNoEventMessageConstructorEntityWhenNoEventsAreReturned() {
        ProcessingContext processingContext = new StubProcessingContext();

        eventsToLoad.clear();

        ManagedEntity<String, String> loaded = testSubject.loadOrCreate("test", processingContext).join();

        assertEquals("test()", loaded.entity());

        verify(eventStore, times(2)).transaction(processingContext);
        verify(eventStoreTransaction).onAppend(any());
    }
}