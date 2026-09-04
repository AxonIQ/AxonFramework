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

package org.axonframework.modelling.saga.repository;

import org.axonframework.common.FutureUtils;
import org.axonframework.common.IdentifierFactory;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptorMemberChain;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.modelling.saga.AnnotatedSaga;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.Saga;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;

import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class AnnotatedSagaRepositoryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private AnnotatedSagaRepository<Object> testSubject;
    private SagaStore store;

    private UnitOfWorkFactory unitOfWorkFactory;

    @BeforeEach
    void setUp() {
        this.unitOfWorkFactory = new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE);
        this.store = spy(new InMemorySagaStore());
        this.testSubject = AnnotatedSagaRepository.builder().sagaType(Object.class).sagaStore(store).build();
    }

    @AfterEach
    void tearDown() {
        CountingInterceptors.counter.set(0);
    }

    @Test
    void loadedFromUnitOfWorkAfterCreate() {
        UnitOfWork unitOfWork = unitOfWorkFactory.create();
        unitOfWork.runOnInvocation(context -> {
            Saga<Object> saga = testSubject.createInstance(IdentifierFactory.getInstance().generateIdentifier(),
                                                           Object::new,
                                                           context);
            saga.getAssociationValues().add(new AssociationValue("test", "value"));

            Saga<Object> saga2 = testSubject.load(saga.getSagaIdentifier(), context);

            assertSame(saga, saga2);
        });

        FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);

        verify(store, never()).loadSaga(any(), any());
        verify(store, never()).updateSaga(any(), any(), any(), any());
        verify(store).insertSaga(eq(Object.class), any(), any(), any());
    }

    @Test
    void loadedFromBranchedContextAfterCreate() {
        UnitOfWork unitOfWork = unitOfWorkFactory.create();
        unitOfWork.runOnInvocation(context -> {
            Saga<Object> saga = testSubject.createInstance(IdentifierFactory.getInstance().generateIdentifier(),
                                                           Object::new,
                                                           context);
            saga.getAssociationValues().add(new AssociationValue("test", "value"));

            // A branched context is where Axon Framework 4 would have nested a unit of work: a deeper scope within
            // the same processing session. Its lifecycle registrations and resources reach the same root.
            ProcessingContext branch = context.withResource(Context.ResourceKey.withLabel("branch"), "deeper");
            Saga<Object> saga2 = testSubject.load(saga.getSagaIdentifier(), branch);

            assertSame(saga, saga2);
        });

        FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);

        verify(store, never()).loadSaga(any(), any());
        verify(store, never()).updateSaga(any(), any(), any(), any());
        verify(store).insertSaga(eq(Object.class), any(), any(), anySet());
    }

    /**
     * Ported from Axon Framework 4's {@code loadedFromNestedUnitOfWorkAfterCreateAndStore}, and the one place in this
     * repository where the outcome differs from Axon Framework 4. The difference is in how often the Saga is written,
     * not in what ends up stored.
     * <p>
     * Axon Framework 4 had a nested unit of work: {@code DefaultUnitOfWork.startAndGet(null)} called while
     * another unit of work was on the {@code CurrentUnitOfWork} thread-local adopted that one as its parent. The child
     * had its own phase timeline and its own resources, and committing it ran its {@code PREPARE_COMMIT} and
     * {@code COMMIT} immediately rather than waiting for the parent; only its {@code AFTER_COMMIT} was deferred up the
     * ancestor chain, and its rollback was wired to the parent's. That is what the original test built at
     * prepare-commit time, and it produced two writes:
     * <ol>
     *     <li>the repository's own action, registered when the Saga was created, inserted it with the one association
     *     value it had at that point;</li>
     *     <li>the nested unit of work then loaded the Saga again. Because the unsaved-Saga bookkeeping lives in the
     *     unit of work's resources, and the child had a fresh set, the repository treated it as unsaved and scheduled
     *     a second write on the child. Committing the child ran it right away, as an update.</li>
     * </ol>
     * Axon Framework 5 has no nesting. A {@code ProcessingContext} is one flat phase timeline, and a deeper scope
     * (see {@link #loadedFromBranchedContextAfterCreate()}) shares its resources with the root rather than starting
     * fresh ones. Two consequences meet here. The second {@code load} finds the identifier still in the unsaved set,
     * so it schedules nothing extra, and the single write it did schedule is ordered after every
     * {@code PREPARE_COMMIT} action, so by the time it runs the Saga already carries both association values. One
     * insert, no update, and the same stored result Axon Framework 4 arrived at in two steps.
     */
    @Test
    void loadingAgainAfterCreateWritesTheSagaOnceWithEverythingItAccumulated() {
        UnitOfWork unitOfWork = unitOfWorkFactory.create();
        unitOfWork.runOnInvocation(context -> {
            Saga<Object> saga = testSubject.createInstance(IdentifierFactory.getInstance().generateIdentifier(),
                                                           Object::new,
                                                           context);
            saga.getAssociationValues().add(new AssociationValue("test", "value"));
            // Stands in for the nested unit of work Axon Framework 4 opened here: a second look at the same Saga,
            // from a later point in the same processing session.
            context.runOnPrepareCommit(c -> {
                Saga<Object> saga1 = testSubject.load(saga.getSagaIdentifier(), c);
                saga1.getAssociationValues().add(new AssociationValue("second", "value"));
            });
        });

        FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);

        Set<AssociationValue> associationValues = new HashSet<>();
        associationValues.add(new AssociationValue("test", "value"));
        associationValues.add(new AssociationValue("second", "value"));
        verify(store).insertSaga(eq(Object.class), any(), any(), eq(associationValues));
        verify(store, never()).updateSaga(any(), any(), any(), any());
        verifyNoMoreInteractions(store);
    }

    @Test
    void theSagaIsWrittenAfterEveryPrepareCommitActionAndBeforeTheContextCommits() {
        // given a context whose other work is spread across the phases around the write
        List<String> order = new ArrayList<>();
        UnitOfWork unitOfWork = unitOfWorkFactory.create();
        unitOfWork.runOnInvocation(context -> {
            testSubject.createInstance("write-phase", Object::new, context);
            order.add("invocation");
        });
        unitOfWork.runOnPrepareCommit(context -> order.add("prepare-commit"));
        unitOfWork.runOnCommit(context -> order.add("commit"));
        doAnswer(invocation -> {
            order.add("saga-inserted");
            return null;
        }).when(store).insertSaga(any(), any(), any(), any());

        // when
        FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);

        // then the write sees a Saga the handler is done with, and is still covered by whatever commits at COMMIT
        assertIterableEquals(List.of("invocation", "prepare-commit", "saga-inserted", "commit"), order);
    }

    @Test
    void loadedFromUnitOfWorkAfterPreviousLoad() {
        UnitOfWork preparingUnitOfWork = unitOfWorkFactory.create();
        AtomicReference<String> preparedSagaId = new AtomicReference<>();
        preparingUnitOfWork.runOnInvocation(
                context -> preparedSagaId.set(
                        testSubject.createInstance(IdentifierFactory.getInstance().generateIdentifier(),
                                                   Object::new,
                                                   context).getSagaIdentifier())
        );
        FutureUtils.joinAndUnwrap(preparingUnitOfWork.execute(), TIMEOUT);
        reset(store);

        UnitOfWork unitOfWork = unitOfWorkFactory.create();
        unitOfWork.runOnInvocation(context -> {
            Saga<Object> saga = testSubject.load(preparedSagaId.get(), context);
            saga.getAssociationValues().add(new AssociationValue("test", "value"));

            Saga<Object> saga2 = testSubject.load(preparedSagaId.get(), context);

            assertSame(saga, saga2);
            verify(store).loadSaga(eq(Object.class), any());
            verify(store, never()).updateSaga(eq(Object.class), any(), any(), any());
        });

        FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);

        verify(store).updateSaga(eq(Object.class), any(), any(), any());
        verify(store, never()).insertSaga(eq(Object.class), any(), any(), any());
    }

    @Test
    void sagaAssociationsVisibleInOtherThreadsBeforeSagaIsCommitted() throws Exception {
        String sagaId = "sagaId";
        AssociationValue associationValue = new AssociationValue("test", "value");
        CountDownLatch sagaCreated = new CountDownLatch(1);
        CountDownLatch letTheOtherProcessFinish = new CountDownLatch(1);

        Thread otherProcess = new Thread(() -> {
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            unitOfWork.runOnInvocation(context -> {
                testSubject.createInstance(sagaId, Object::new, context)
                           .getAssociationValues()
                           .add(associationValue);
                sagaCreated.countDown();
                awaitOrFail(letTheOtherProcessFinish);
            });
            FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);
        });
        otherProcess.start();

        try {
            assertTrue(sagaCreated.await(5, TimeUnit.SECONDS));

            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            Set<String> found = FutureUtils.joinAndUnwrap(
                    unitOfWork.executeWithResult(context -> CompletableFuture.completedFuture(
                            testSubject.find(associationValue, context)
                    )),
                    TIMEOUT
            );

            assertEquals(singleton(sagaId), found);
        } finally {
            letTheOtherProcessFinish.countDown();
            otherProcess.join(Duration.ofMillis(50));
        }
    }

    @Test
    void ifInterceptorSetThatOneShouldBeUsed() {
        AnnotatedSagaRepository<TestSaga> sagaRepository = AnnotatedSagaRepository.<TestSaga>builder()
                                                                                  .sagaType(TestSaga.class)
                                                                                  .sagaStore(store)
                                                                                  .interceptorMemberChain(
                                                                                          CountingInterceptors.instance())
                                                                                  .build();
        UnitOfWork unitOfWork = unitOfWorkFactory.create();
        unitOfWork.runOnInvocation(context -> {
            Saga<TestSaga> saga = sagaRepository.createInstance(IdentifierFactory.getInstance().generateIdentifier(),
                                                                TestSaga::new,
                                                                context);
            EventMessage message = EventTestUtils.asEventMessage(new Object());
            saga.handle(message, context);
        });

        FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);

        assertEquals(1, CountingInterceptors.counter.get());
    }

    /**
     * Behaviour inherited from Axon Framework 4 that is odd enough to be mistaken for a bug. It is pinned here so that
     * a future reader can see it is deliberate rather than overlooked.
     */
    @Nested
    class InheritedAxonFramework4Behaviour {

        @Test
        void aNewSagaThatEndsBeforeTheContextCommitsIsNeverInserted() {
            // given a saga created and ended within one processing context
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            unitOfWork.runOnInvocation(context -> {
                AnnotatedSaga<Object> saga = (AnnotatedSaga<Object>) testSubject.createInstance(
                        "ended-before-commit", Object::new, context
                );
                saga.associateWith(new AssociationValue("test", "value"));
                saga.end();
            });

            // when
            FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);

            // then nothing was written: the repository only inserts a saga that is still active
            verify(store, never()).insertSaga(any(), any(), any(), any());
            verify(store, never()).deleteSaga(any(), any(), any());
        }

        @Test
        void aLoadedSagaThatEndsIsDeleted() {
            // given a stored saga
            UnitOfWork creatingUnitOfWork = unitOfWorkFactory.create();
            creatingUnitOfWork.runOnInvocation(context -> testSubject.createInstance("to-be-ended",
                                                                                     Object::new,
                                                                                     context));
            FutureUtils.joinAndUnwrap(creatingUnitOfWork.execute(), TIMEOUT);

            // when it is loaded and ended in a later context
            UnitOfWork endingUnitOfWork = unitOfWorkFactory.create();
            endingUnitOfWork.runOnInvocation(context -> {
                AnnotatedSaga<Object> saga = (AnnotatedSaga<Object>) testSubject.load("to-be-ended", context);
                saga.end();
            });
            FutureUtils.joinAndUnwrap(endingUnitOfWork.execute(), TIMEOUT);

            // then it is deleted, unlike the created-and-ended saga above, which was never written at all
            verify(store).deleteSaga(eq(Object.class), eq("to-be-ended"), any());
        }

        @Test
        void anEndedNewSagaStaysInTheUnsavedSagaSetOfItsContext() {
            // given a saga created and ended within one processing context, and one that stays active
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            Set<String> unsavedAfterPrepareCommit = new HashSet<>();
            unitOfWork.runOnInvocation(context -> {
                AnnotatedSaga<Object> ended = (AnnotatedSaga<Object>) testSubject.createInstance("ended",
                                                                                                 Object::new,
                                                                                                 context);
                ended.end();
                testSubject.createInstance("active", Object::new, context);
                context.runOnCommit(c -> unsavedAfterPrepareCommit.addAll(testSubject.unsavedSagaResource(c)));
            });

            // when
            FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);

            // then only the active one was cleared. doCreateInstance removes the identifier inside its
            // "if (saga.isActive())" branch, where doLoad removes it unconditionally, so an ended new saga keeps
            // its entry for the rest of the context.
            assertEquals(singleton("ended"), unsavedAfterPrepareCommit);
        }

        @Test
        void loadingAnUnknownSagaAsksTheStoreEveryTime() {
            // given a context in which an unknown identifier is loaded twice
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            unitOfWork.runOnInvocation(context -> {
                assertNull(testSubject.load("no-such-saga", context));
                assertNull(testSubject.load("no-such-saga", context));
            });

            // when
            FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);

            // then the miss is not remembered: computeIfAbsent does not store a null, so each call hits the store
            verify(store, times(2)).loadSaga(eq(Object.class), eq("no-such-saga"));
        }

        @Test
        void deletingASagaPassesTheAssociationsItStillHasAndTheOnesItLost() {
            // given a stored saga associated with "first"
            AssociationValue first = new AssociationValue("test", "first");
            AssociationValue second = new AssociationValue("test", "second");
            UnitOfWork creatingUnitOfWork = unitOfWorkFactory.create();
            creatingUnitOfWork.runOnInvocation(context -> {
                AnnotatedSaga<Object> saga = (AnnotatedSaga<Object>) testSubject.createInstance("re-associated",
                                                                                                 Object::new,
                                                                                                 context);
                saga.associateWith(first);
            });
            FutureUtils.joinAndUnwrap(creatingUnitOfWork.execute(), TIMEOUT);

            // when it swaps its association and ends
            UnitOfWork endingUnitOfWork = unitOfWorkFactory.create();
            endingUnitOfWork.runOnInvocation(context -> {
                AnnotatedSaga<Object> saga = (AnnotatedSaga<Object>) testSubject.load("re-associated", context);
                saga.removeAssociationWith(first);
                saga.associateWith(second);
                saga.end();
            });
            FutureUtils.joinAndUnwrap(endingUnitOfWork.execute(), TIMEOUT);

            // then the delete cleans up both, so the removed association cannot outlive the saga
            ArgumentCaptor<Set<AssociationValue>> deleted = ArgumentCaptor.forClass(Set.class);
            verify(store).deleteSaga(eq(Object.class), eq("re-associated"), deleted.capture());
            assertEquals(Set.of(first, second), deleted.getValue());
        }

        @Test
        void findReportsASagaThatIsBothManagedAndStoredOnlyOnce() {
            // given three stored sagas sharing an association value, created out of order
            AssociationValue shared = new AssociationValue("test", "shared");
            UnitOfWork creatingUnitOfWork = unitOfWorkFactory.create();
            creatingUnitOfWork.runOnInvocation(context -> {
                for (String identifier : new String[]{"c", "a", "b"}) {
                    AnnotatedSaga<Object> saga = (AnnotatedSaga<Object>) testSubject.createInstance(identifier,
                                                                                                    Object::new,
                                                                                                    context);
                    saga.associateWith(shared);
                }
            });
            FutureUtils.joinAndUnwrap(creatingUnitOfWork.execute(), TIMEOUT);

            // when one of them is also managed in the current context, so it is both in the map and in the store
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            Set<String> found = FutureUtils.joinAndUnwrap(
                    unitOfWork.executeWithResult(context -> {
                        testSubject.load("b", context);
                        return CompletableFuture.completedFuture(testSubject.find(shared, context));
                    }),
                    TIMEOUT
            );

            // then it is reported once rather than twice, and the identifiers come back in the order of the TreeSet
            // the repository merges them into
            assertIterableEquals(List.of("a", "b", "c"), found);
        }

        @Test
        void aFailingSagaFactoryIsReportedAsASagaCreationException() {
            // given a factory that fails
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            IllegalArgumentException failure = new IllegalArgumentException("no saga for you");

            // when creating an instance with it
            unitOfWork.runOnInvocation(context -> {
                SagaCreationException thrown = assertThrows(
                        SagaCreationException.class,
                        () -> testSubject.createInstance("never-created", () -> {
                            throw failure;
                        }, context)
                );

                // then the original failure is wrapped rather than propagated
                assertSame(failure, thrown.getCause());
            });

            FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);
        }
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the other process");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static class CountingInterceptors implements MessageHandlerInterceptorMemberChain<TestSaga> {

        static AtomicInteger counter = new AtomicInteger(0);

        private static MessageHandlerInterceptorMemberChain<TestSaga> instance() {
            return new CountingInterceptors();
        }

        @Override
        public MessageStream<?> handle(@NonNull Message message,
                                       @NonNull ProcessingContext context,
                                       @NonNull TestSaga target,
                                       @NonNull MessageHandlingMember<? super TestSaga> handler) {
            counter.incrementAndGet();
            return handler.handle(message, context, target);
        }
    }

    private static class TestSaga {

        @EventHandler
        public void on(Object o) {

        }
    }
}
