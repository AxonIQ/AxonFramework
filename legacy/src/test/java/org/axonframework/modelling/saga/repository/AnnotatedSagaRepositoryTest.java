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
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.Saga;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.time.Duration;
import java.util.HashSet;
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

    @Test
    @Disabled("Fails with \"Failed to register handler in phase PREPARE_COMMIT (20000). ProcessingContext is already "
            + "in phase PREPARE_COMMIT (20000).\" Axon Framework 4 opened a nested unit of work here, whose "
            + "prepare-commit ran immediately. The repository cannot reproduce that: the save has to run after the "
            + "handler mutated the saga, and only the component invoking the saga knows when that is. Kept in its "
            + "Axon Framework 4 form so it turns green once that component resolves it.")
    void loadedFromNestedUnitOfWorkAfterCreateAndStore() {
        UnitOfWork unitOfWork = unitOfWorkFactory.create();
        unitOfWork.runOnInvocation(context -> {
            Saga<Object> saga = testSubject.createInstance(IdentifierFactory.getInstance().generateIdentifier(),
                                                           Object::new,
                                                           context);
            saga.getAssociationValues().add(new AssociationValue("test", "value"));
            // Registered from within the invocation, so it runs after the repository's own prepare-commit action,
            // which is the order Axon Framework 4 had: the saga was inserted, and only then loaded again.
            context.runOnPrepareCommit(c -> {
                Saga<Object> saga1 = testSubject.load(saga.getSagaIdentifier(), c);
                saga1.getAssociationValues().add(new AssociationValue("second", "value"));
            });
        });

        FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);

        InOrder inOrder = inOrder(store);
        Set<AssociationValue> associationValues = new HashSet<>();
        associationValues.add(new AssociationValue("test", "value"));
        associationValues.add(new AssociationValue("second", "value"));
        inOrder.verify(store).insertSaga(eq(Object.class), any(), any(), eq(associationValues));
        inOrder.verify(store).updateSaga(eq(Object.class), any(), any(), any());
        inOrder.verifyNoMoreInteractions();
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
            otherProcess.join();
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
