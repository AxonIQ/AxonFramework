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

package org.axonframework.integrationtests.modelling.saga;

import org.axonframework.common.FutureUtils;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.TransactionalUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.transaction.Transaction;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.messaging.eventhandling.AsyncInMemoryStreamableEventSource;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessorConfiguration;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.AssociationValues;
import org.axonframework.modelling.saga.repository.AnnotatedSagaRepository;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.jspecify.annotations.Nullable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Shows that an {@code axon-legacy} saga repository works behind either event processor, and that the write it
 * schedules stays inside the caller's transaction in both cases.
 * <p>
 * {@link AnnotatedSagaRepository} writes the saga in the {@link AnnotatedSagaRepository#WRITE_SAGA} phase of the
 * {@link org.axonframework.messaging.core.unitofwork.ProcessingContext} it was called in. That phase is ordered above
 * {@code PREPARE_COMMIT} and below {@code COMMIT}, which is what makes the two processors equivalent here:
 * <ul>
 *     <li>A {@link PooledStreamingEventProcessor} creates a unit of work per batch and invokes its components during
 *     {@code INVOCATION}.</li>
 *     <li>A {@link SubscribingEventProcessor} handles events in the context they were published with, and
 *     {@link SimpleEventBus} delivers those during {@code PREPARE_COMMIT} of that context.</li>
 * </ul>
 * Either way the repository is reached from a phase below {@code SAGA_WRITE}, so it can register the write, the write
 * runs after the handler mutated the saga, and it runs before the transaction commits.
 * <p>
 * Axon Framework 4 achieved the same ordering with a nested unit of work whose prepare-commit ran immediately. Axon
 * Framework 5 has no nesting, so the ordering is expressed as a phase instead.
 *
 * @author Mateusz Nowak
 */
class SagaRepositoryEventProcessorIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final QualifiedName ORDER_PLACED = new QualifiedName(OrderPlaced.class);
    private static final AssociationValue ORDER_1 = new AssociationValue("orderId", "order-1");

    private SagaStore<Object> sagaStore;
    private AnnotatedSagaRepository<Object> repository;
    private UnitOfWorkFactory unitOfWorkFactory;
    private EventHandlingComponent sagaComponent;

    @BeforeEach
    void setUp() {
        sagaStore = new InMemorySagaStore();
        repository = AnnotatedSagaRepository.builder()
                                            .sagaType(Object.class)
                                            .sagaStore(sagaStore)
                                            .build();
        unitOfWorkFactory = new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE);

        // Stands in for the saga event handling component that will replace AnnotatedSagaManager: it does the one
        // thing that component has to do, which is reach the repository with the context it was invoked in.
        sagaComponent = SimpleEventHandlingComponent.create("saga-component")
                                                    .subscribe(ORDER_PLACED, (event, context) -> {
                                                        repository.createInstance(
                                                                          ((OrderPlaced) event.payload()).orderId(),
                                                                          Object::new,
                                                                          context
                                                                  )
                                                                  .getAssociationValues()
                                                                  .add(ORDER_1);
                                                        return MessageStream.empty();
                                                    });
    }

    @Nested
    class BehindAPooledStreamingEventProcessor {

        private PooledStreamingEventProcessor processor;
        private ScheduledExecutorService coordinatorExecutor;
        private ScheduledExecutorService workerExecutor;

        @AfterEach
        void tearDown() {
            if (processor != null) {
                FutureUtils.joinAndUnwrap(processor.shutdown(), TIMEOUT);
            }
            if (coordinatorExecutor != null) {
                coordinatorExecutor.shutdownNow();
            }
            if (workerExecutor != null) {
                workerExecutor.shutdownNow();
            }
        }

        @Test
        void theSagaIsStored() {
            // given an event waiting in the stream
            AsyncInMemoryStreamableEventSource eventSource = new AsyncInMemoryStreamableEventSource(true, true);
            eventSource.publishMessage(orderPlaced("order-1"));

            coordinatorExecutor = Executors.newScheduledThreadPool(1);
            workerExecutor = Executors.newScheduledThreadPool(2);
            processor = new PooledStreamingEventProcessor(
                    "saga-processor",
                    List.of(sagaComponent),
                    new PooledStreamingEventProcessorConfiguration(
                            new EventProcessorConfiguration("saga-processor", null)
                    )
                            .eventSource(eventSource)
                            .unitOfWorkFactory(unitOfWorkFactory)
                            .tokenStore(new InMemoryTokenStore())
                            .coordinatorExecutor(coordinatorExecutor)
                            .workerExecutor(workerExecutor)
                            .initialSegmentCount(1)
            );

            // when
            FutureUtils.joinAndUnwrap(processor.start(), TIMEOUT);

            // then the saga was created and written: the processor's own unit of work invoked the component during
            // INVOCATION, well below the phase the repository writes in
            await().atMost(TIMEOUT)
                   .untilAsserted(() -> assertThat(sagaStore.findSagas(Object.class, ORDER_1))
                           .containsExactly("order-1"));
        }
    }

    @Nested
    class BehindASubscribingEventProcessor {

        private SimpleEventBus eventBus;
        private SubscribingEventProcessor processor;

        @BeforeEach
        void startProcessor() {
            eventBus = new SimpleEventBus();
            processor = new SubscribingEventProcessor(
                    "saga-processor",
                    List.of(sagaComponent),
                    new SubscribingEventProcessorConfiguration(
                            new EventProcessorConfiguration("saga-processor", null)
                    )
                            .eventSource(eventBus)
                            .unitOfWorkFactory(unitOfWorkFactory)
            );
            FutureUtils.joinAndUnwrap(processor.start(), TIMEOUT);
        }

        @AfterEach
        void tearDown() {
            if (processor != null) {
                FutureUtils.joinAndUnwrap(processor.shutdown(), TIMEOUT);
            }
        }

        @Test
        void anEventPublishedWithoutAContextIsHandledInItsOwnUnitOfWork() {
            // given / when an event published outside any processing context
            FutureUtils.joinAndUnwrap(eventBus.publish(null, List.of(orderPlaced("order-1"))), TIMEOUT);

            // then the processor created a unit of work of its own, so the repository could write as usual
            assertThat(sagaStore.findSagas(Object.class, ORDER_1)).containsExactly("order-1");
        }

        @Test
        void anEventPublishedWithAContextIsDeliveredDuringPrepareCommitAndTheSagaIsStillStored() {
            // given a unit of work that publishes the event, as a command handler would
            UnitOfWork publishingUnitOfWork = unitOfWorkFactory.create();
            publishingUnitOfWork.onInvocation(
                    context -> eventBus.publish(context, List.of(orderPlaced("order-1")))
            );

            // when SimpleEventBus delivers it during PREPARE_COMMIT of that same context, so the repository is
            // reached from inside that phase
            FutureUtils.joinAndUnwrap(publishingUnitOfWork.execute(), TIMEOUT);

            // then the saga was written anyway: the phase it registers for is ordered above the one it was called from
            assertThat(sagaStore.findSagas(Object.class, ORDER_1)).containsExactly("order-1");
        }

        @Test
        void theSagaIsWrittenBeforeTheTransactionOfThePublishingContextCommits() {
            // given a transactional unit of work, and a store that records when it is written relative to the commit
            List<String> order = new CopyOnWriteArrayList<>();
            sagaStore = new RecordingSagaStore(new InMemorySagaStore(), order);
            repository = AnnotatedSagaRepository.builder()
                                                .sagaType(Object.class)
                                                .sagaStore(sagaStore)
                                                .build();
            UnitOfWork publishingUnitOfWork = new TransactionalUnitOfWorkFactory(
                    new RecordingTransactionManager(order),
                    new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE)
            ).create();
            publishingUnitOfWork.onInvocation(
                    context -> eventBus.publish(context, List.of(orderPlaced("order-1")))
            );

            // when
            FutureUtils.joinAndUnwrap(publishingUnitOfWork.execute(), TIMEOUT);

            // then the write is part of that transaction rather than something that outlives it
            assertThat(order).containsExactly("transaction-started", "saga-inserted", "transaction-committed");
        }

        @Test
        void aFailureInThePublishingContextLeavesTheSagaUnwritten() {
            // given a unit of work that fails after the saga was handled but before the repository writes
            UnitOfWork publishingUnitOfWork = unitOfWorkFactory.create();
            publishingUnitOfWork.onInvocation(
                    context -> eventBus.publish(context, List.of(orderPlaced("order-1")))
            );
            publishingUnitOfWork.runOnPrepareCommit(context -> {
                throw new IllegalStateException("something else in this context failed");
            });

            // when
            assertThatThrownBy(() -> FutureUtils.joinAndUnwrap(publishingUnitOfWork.execute(), TIMEOUT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("something else in this context failed");

            // then the saga write never ran, so it cannot outlive the context that produced it
            assertThat(sagaStore.findSagas(Object.class, ORDER_1)).isEmpty();
        }
    }

    private static EventMessage orderPlaced(String orderId) {
        return new GenericEventMessage(new MessageType(OrderPlaced.class), new OrderPlaced(orderId));
    }

    public record OrderPlaced(String orderId) {

    }

    private record RecordingTransactionManager(List<String> order) implements TransactionManager {

        @Override
        public Transaction startTransaction() {
            order.add("transaction-started");
            return new Transaction() {
                @Override
                public void commit() {
                    order.add("transaction-committed");
                }

                @Override
                public void rollback() {
                    order.add("transaction-rolled-back");
                }
            };
        }
    }

    private record RecordingSagaStore(SagaStore<Object> delegate, List<String> order) implements SagaStore<Object> {

        @Override
        public Set<String> findSagas(Class<?> sagaType, AssociationValue associationValue) {
            return delegate.findSagas(sagaType, associationValue);
        }

        @Nullable
        @Override
        public <S> Entry<S> loadSaga(Class<S> sagaType, String sagaIdentifier) {
            return delegate.loadSaga(sagaType, sagaIdentifier);
        }

        @Override
        public void deleteSaga(Class<?> sagaType, String sagaIdentifier, Set<AssociationValue> associationValues) {
            order.add("saga-deleted");
            delegate.deleteSaga(sagaType, sagaIdentifier, associationValues);
        }

        @Override
        public void insertSaga(Class<?> sagaType, String sagaIdentifier, Object saga,
                               Set<AssociationValue> associationValues) {
            order.add("saga-inserted");
            delegate.insertSaga(sagaType, sagaIdentifier, saga, associationValues);
        }

        @Override
        public void updateSaga(Class<?> sagaType, String sagaIdentifier, Object saga,
                               AssociationValues associationValues) {
            order.add("saga-updated");
            delegate.updateSaga(sagaType, sagaIdentifier, saga, associationValues);
        }
    }
}
