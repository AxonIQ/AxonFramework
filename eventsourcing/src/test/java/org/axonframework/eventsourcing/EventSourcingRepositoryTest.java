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
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.eventsourcing.handler.EntityLifecycleHandler;
import org.axonframework.eventsourcing.handler.InitializingEntityEvolver;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.repository.EntityNotFoundException;
import org.axonframework.modelling.repository.ManagedEntity;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.mockito.*;
import org.mockito.junit.jupiter.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.messaging.eventhandling.EventTestUtils.createEvent;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link EventSourcingRepository}.
 *
 * @author Allard Buijze
 * @author John Hendrikx
 */
@ExtendWith(MockitoExtension.class)
class EventSourcingRepositoryTest {

    private static final Set<Tag> TEST_TAGS = Set.of(new Tag("aggregateId", "id"));

    private EventStore eventStore = mock();
    private EventStoreTransaction eventStoreTransaction = mock();
    private EntityLifecycleHandler<String, String> handler = mock();
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
                handler
        );

        InitializingEntityEvolver<String, String> evolver = new InitializingEntityEvolver<>(
            (id, event, context) -> factory.create(id, event, context),
            (entity, event, context) -> entity + "-" + event.payload()
        );

        // Simulate event evolution:
        when(handler.source(eq("test"), any())).thenAnswer(invocation -> {
            if (invocation.getArgument(0) instanceof String id
                && invocation.getArgument(1) instanceof ProcessingContext pc
            ) {
                return CompletableFuture.supplyAsync(() -> {
                    String result = null;

                    for (EventMessage event : eventsToLoad) {
                        String evolved = evolver.evolve(id, result, event, pc);

                        result = evolved;
                    }

                    return result;
                });
            }

            throw new AssertionError("Unexpected invocation: " + invocation);
        });
    }

    @Test
    void loadEventSourcedEntity() {
        ProcessingContext processingContext = new StubProcessingContext();

        ManagedEntity<String, String> result = testSubject.load("test", processingContext).join();

        assertEquals("test(0)-0-1", result.entity());

        verify(handler).source("test", processingContext);
    }

    @Test
    void persistNewEntityRegistersItToListenToEvents() {
        ProcessingContext processingContext = new StubProcessingContext();

        ManagedEntity<String, String> result = testSubject.persist("id", "entity", processingContext);

        verify(handler).subscribe(result, processingContext);

        assertEquals("entity", result.entity());
        assertEquals("id", result.identifier());
    }

    @Test
    void persistAlreadyPersistedEntityDoesNotRegisterItToListenToEvents() {
        ProcessingContext processingContext = new StubProcessingContext();

        ManagedEntity<String, String> first = testSubject.persist("id", "entity", processingContext);
        ManagedEntity<String, String> second = testSubject.persist("id", "entity", processingContext);

        assertSame(first, second);

        verify(handler).subscribe(first, processingContext);

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

        verify(handler).subscribe(result, processingContext);
        verify(handler).subscribe(result, processingContext2);
    }

    @Test
    void assigningEntityToOtherProcessingContextInOtherFormat() throws Exception {
        ProcessingContext processingContext = new StubProcessingContext();
        ProcessingContext processingContext2 = new StubProcessingContext();

        ManagedEntity<String, String> result = testSubject.load("test", processingContext).get();

        ManagedEntity<String, String> externalManagedEntity = new ManagedEntity<>() {
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
        };

        // Attaches entity of incorrect internal type (which will then be recreated):
        ManagedEntity<String, String> internalManagedEntity = testSubject.attach(externalManagedEntity, processingContext2);

        verify(handler).subscribe(result, processingContext);
        verify(handler).subscribe(internalManagedEntity, processingContext2);
    }

    @Test
    void loadOrCreateShouldLoadWhenEventsAreReturned() {
        ProcessingContext processingContext = new StubProcessingContext();

        CompletableFuture<ManagedEntity<String, String>> result =
                testSubject.load("test", processingContext);

        assertEquals("test(0)-0-1", result.join().entity());
    }

    @Test
    void loadShouldReturnNullEntityWhenNoEventsAreReturned() {
        StubProcessingContext processingContext = new StubProcessingContext();

        when(handler.source(eq("test"), eq(processingContext))).thenReturn(CompletableFuture.completedFuture(null));

        doReturn(MessageStream.empty())
                .when(eventStoreTransaction)
                .source(argThat(EventSourcingRepositoryTest::conditionPredicate), any());

        ManagedEntity<String, String> loaded = testSubject.load("test", processingContext).join();

        assertNull(loaded.entity());
    }

    @Test
    void loadOrCreateShouldReturnNoEventMessageConstructorEntityWhenNoEventsAreReturned() {
        ProcessingContext processingContext = new StubProcessingContext();

        eventsToLoad = List.of();

        when(handler.initialize("test", processingContext)).thenReturn("test()");

        ManagedEntity<String, String> loaded = testSubject.loadOrCreate("test", processingContext).join();

        assertEquals("test()", loaded.entity());
    }

    @Test
    void loadOrCreateAllowsSubsequentResolutionToObserveEntityCreatedWithinSameUnitOfWork() {
        ProcessingContext processingContext = new StubProcessingContext();
        eventsToLoad = List.of();

        when(handler.initialize("test", processingContext)).thenThrow(new EntityNotFoundException("test"));

        CompletableFuture<ManagedEntity<String, String>> firstResult =
                testSubject.loadOrCreate("test", processingContext);

        ExecutionException firstException = assertThrows(ExecutionException.class, firstResult::get);
        assertInstanceOf(EntityNotFoundException.class, firstException.getCause());

        // Even though initialize() failed, the entity must still be subscribed for live updates - this is what
        // allows a same-unit-of-work creation to be observed by a later resolution instead of a poisoned result.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ManagedEntity<String, String>> entityCaptor = ArgumentCaptor.forClass(ManagedEntity.class);
        verify(handler).subscribe(entityCaptor.capture(), eq(processingContext));
        ManagedEntity<String, String> subscribedEntity = entityCaptor.getValue();

        // Simulate a creation event being appended and applied via the onAppend callback a real
        // EntityLifecycleHandler would have registered through subscribe(...).
        subscribedEntity.applyStateChange(current -> "test(created)");

        ManagedEntity<String, String> secondResult = testSubject.loadOrCreate("test", processingContext).join();

        assertEquals("test(created)", secondResult.entity());
    }

    @Nested
    class LoadAndLoadOrCreateWithinOneProcessingContext {

        private final ProcessingContext processingContext = new StubProcessingContext();

        @BeforeEach
        void noEventsForTheIdentifier() {
            eventsToLoad = List.of();
        }

        @Test
        void loadOrCreateAfterNotFoundLoadCreatesThroughTheLifecycleHandler() {
            // given a load reporting the entity as missing, without invoking initialize(...)
            when(handler.initialize("test", processingContext)).thenReturn("test()");

            ManagedEntity<String, String> loaded = testSubject.load("test", processingContext).join();
            assertThat(loaded.entity()).isNull();

            // when loadOrCreate follows for the same identifier in the same context
            ManagedEntity<String, String> created = testSubject.loadOrCreate("test", processingContext).join();

            // then the entity was created, on the very same managed instance the earlier load handed out
            assertThat(created.entity()).isEqualTo("test()");
            assertThat(created).isSameAs(loaded);
            assertThat(loaded.entity()).isEqualTo("test()");
        }

        @Test
        void loadOrCreateAfterNotFoundLoadFailsWhenTheEntityRequiresAFirstEvent() {
            // given an entity that cannot be constructed from its identifier alone
            when(handler.initialize("test", processingContext)).thenThrow(new EntityNotFoundException("test"));

            ManagedEntity<String, String> loaded = testSubject.load("test", processingContext).join();
            assertThat(loaded.entity()).isNull();

            // when loadOrCreate follows for the same identifier in the same context
            CompletableFuture<ManagedEntity<String, String>> result =
                    testSubject.loadOrCreate("test", processingContext);

            // then it fails, leaving the managed instance available for the first appended event to evolve
            assertThatThrownBy(result::join).hasCauseInstanceOf(EntityNotFoundException.class);
            assertThat(loaded.entity()).isNull();
        }

        @Test
        void loadAfterLoadOrCreateObservesTheCreatedEntity() {
            // given loadOrCreate created the entity
            when(handler.initialize("test", processingContext)).thenReturn("test()");

            ManagedEntity<String, String> created = testSubject.loadOrCreate("test", processingContext).join();

            // when load follows for the same identifier in the same context
            ManagedEntity<String, String> loaded = testSubject.load("test", processingContext).join();

            // then both calls describe the same managed entity
            assertThat(loaded).isSameAs(created);
            assertThat(loaded.entity()).isEqualTo("test()");
        }

        @Test
        void repeatedLoadOrCreateCreatesTheEntityOnlyOnce() {
            // given a first loadOrCreate creating the entity
            when(handler.initialize("test", processingContext)).thenReturn("test()");

            ManagedEntity<String, String> first = testSubject.loadOrCreate("test", processingContext).join();

            // when loadOrCreate is invoked again for the same identifier in the same context
            ManagedEntity<String, String> second = testSubject.loadOrCreate("test", processingContext).join();

            // then the cached entity is returned without creating a second instance
            assertThat(second).isSameAs(first);
            assertThat(second.entity()).isEqualTo("test()");
            verify(handler, times(1)).initialize("test", processingContext);
        }

        @Test
        void loadOfMissingEntityDoesNotInvokeTheLifecycleHandlerInitialization() {
            // when loading an identifier without events
            ManagedEntity<String, String> loaded = testSubject.load("test", processingContext).join();

            // then the managed entity holds no state and no creation was attempted
            assertThat(loaded.entity()).isNull();
            verify(handler, never()).initialize(any(), any());
        }
    }

    private static boolean conditionPredicate(SourcingCondition condition) {
        return condition.matches(new QualifiedName("ignored"), TEST_TAGS);
    }
}