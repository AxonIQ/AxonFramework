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
import org.axonframework.messaging.core.MessageType;
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
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessorConfiguration;
import org.axonframework.modelling.saga.AnnotatedSagaManager;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.AssociationValues;
import org.axonframework.modelling.saga.EndSaga;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.SagaLifecycle;
import org.axonframework.modelling.saga.StartSaga;
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
 * Shows that an {@code axon-legacy} saga is processed correctly behind either event processor: that the write its
 * repository schedules stays inside the caller's transaction, and that a saga spanning two segments is handled by the
 * one segment that owns it.
 * <p>
 * The component under test is a real {@link AnnotatedSagaManager} over an annotated saga type, so these are
 * guarantees about the component an application actually registers rather than about a stand-in that reached the
 * repository on its behalf. The manager derives the saga identifier itself, which is why the store is asserted on the
 * association values rather than on an identifier the test chose.
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
 * Either way the repository is reached from a phase below {@code WRITE_SAGA}, so it can register the write, the write
 * runs after the handler mutated the saga, and it runs before the transaction commits.
 * <p>
 * Axon Framework 4 achieved the same ordering with a nested unit of work whose prepare-commit ran immediately. Axon
 * Framework 5 has no nesting, so the ordering is expressed as a phase instead.
 *
 * @author Mateusz Nowak
 */
class SagaEventProcessingIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final AssociationValue ORDER_1 = new AssociationValue("orderId", "order-1");

    private SagaStore<Object> sagaStore;
    private List<String> storeAndTransactionOrder;
    private UnitOfWorkFactory unitOfWorkFactory;
    private EventHandlingComponent sagaComponent;

    @BeforeEach
    void setUp() {
        storeAndTransactionOrder = new CopyOnWriteArrayList<>();
        sagaStore = new RecordingSagaStore(new InMemorySagaStore(), storeAndTransactionOrder);
        AnnotatedSagaRepository<OrderSaga> repository = AnnotatedSagaRepository.<OrderSaga>builder()
                                                                              .sagaType(OrderSaga.class)
                                                                              .sagaStore(sagaStore)
                                                                              .build();
        unitOfWorkFactory = new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE);

        sagaComponent = AnnotatedSagaManager.<OrderSaga>builder()
                                            .sagaRepository(repository)
                                            .sagaType(OrderSaga.class)
                                            .sagaFactory(OrderSaga::new)
                                            .build();
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
                   .untilAsserted(() -> assertThat(sagaStore.findSagas(OrderSaga.class, ORDER_1)).hasSize(1));
        }
    }

    /**
     * Segment ownership only exists once two work packages run against the same store, so it is out of reach of a unit
     * test. It is also the part Axon Framework 5 changed most: admission moved from a check that ignored the
     * {@code Segment} to a sequence identifier that decides it, while ownership stayed with the saga identifier.
     * <p>
     * The events here are chosen so the two association values of one saga hash into <b>different</b> segments, which
     * is the case where a sequence identifier derived from the event would send the follow-up event to a segment that
     * does not own the saga, and the owning segment would never be offered it.
     */
    @Nested
    class AcrossTwoSegments {

        private PooledStreamingEventProcessor processor;
        private AsyncInMemoryStreamableEventSource eventSource;
        private ScheduledExecutorService coordinatorExecutor;
        private ScheduledExecutorService workerExecutor;

        @BeforeEach
        void createEventSource() {
            eventSource = new AsyncInMemoryStreamableEventSource(true, true);
        }

        /**
         * Starts the processor once the events are already on the stream. A work package processes its segment's
         * events in order, so a saga is created by the event that starts it before the follow-up event reaches the
         * same segment, whichever segment ends up owning it.
         */
        private void startProcessor() {
            coordinatorExecutor = Executors.newScheduledThreadPool(1);
            workerExecutor = Executors.newScheduledThreadPool(4);
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
                            .initialSegmentCount(2)
            );
            FutureUtils.joinAndUnwrap(processor.start(), TIMEOUT);
        }

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
        void aSagaCreatedInOneSegmentStillReceivesAnEventAssociatedByAnotherValue() {
            // given an order whose two association values belong to different segments
            String orderId = anOrderWhoseAssociationValuesSplitAcrossSegments();
            AssociationValue shipment = new AssociationValue("shipmentId", shipmentIdFor(orderId));
            eventSource.publishMessage(orderPlaced(orderId));

            // when the follow-up event carries only the second association value
            eventSource.publishMessage(orderShipped(shipmentIdFor(orderId)));
            startProcessor();

            // then the segment owning the saga handled it, exactly once, although the association value it arrived
            // under belongs to the other segment
            await().atMost(TIMEOUT)
                   .untilAsserted(() -> assertThat(sagaFor(shipment).getShippedCount()).isEqualTo(1));
        }

        @Test
        void twoSagasWithDifferentAssociationValuesLandInDifferentSegments() {
            // given two orders claimed by different segments
            Segment[] segments = Segment.ROOT_SEGMENT.split();
            String firstOrderId = anOrderClaimedBy(segments[0]);
            String secondOrderId = anOrderClaimedBy(segments[1]);

            // when
            eventSource.publishMessage(orderPlaced(firstOrderId));
            eventSource.publishMessage(orderPlaced(secondOrderId));
            startProcessor();

            // then each is created once, by the single segment matching its initial association value
            await().atMost(TIMEOUT).untilAsserted(() -> {
                assertThat(sagaStore.findSagas(OrderSaga.class, orderAssociation(firstOrderId))).hasSize(1);
                assertThat(sagaStore.findSagas(OrderSaga.class, orderAssociation(secondOrderId))).hasSize(1);
            });
            assertThat(segments[0].matches(sagaIdFor(orderAssociation(firstOrderId)))).isTrue();
            assertThat(segments[1].matches(sagaIdFor(orderAssociation(secondOrderId)))).isTrue();
        }

        @Test
        void theSagaEndsAndIsDeletedFromTheStore() {
            // given a stored saga
            String orderId = anOrderWhoseAssociationValuesSplitAcrossSegments();
            AssociationValue shipment = new AssociationValue("shipmentId", shipmentIdFor(orderId));
            eventSource.publishMessage(orderPlaced(orderId));

            // when the ending event arrives, again under the association value of the other segment
            eventSource.publishMessage(orderCompleted(shipmentIdFor(orderId)));
            startProcessor();

            // then
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(sagaStore.findSagas(OrderSaga.class, shipment))
                    .isEmpty());
        }

        private OrderSaga sagaFor(AssociationValue associationValue) {
            Set<String> sagaIds = sagaStore.findSagas(OrderSaga.class, associationValue);
            assertThat(sagaIds).hasSize(1);
            return sagaStore.loadSaga(OrderSaga.class, sagaIds.iterator().next()).saga();
        }

        private String sagaIdFor(AssociationValue associationValue) {
            return sagaStore.findSagas(OrderSaga.class, associationValue).iterator().next();
        }
    }

    private static AssociationValue orderAssociation(String orderId) {
        return new AssociationValue("orderId", orderId);
    }

    /**
     * Finds an order whose {@code orderId} and {@code shipmentId} association values hash into different segments,
     * which is what makes the follow-up event a genuine test of cross-segment delivery rather than a coincidence.
     */
    private static String anOrderWhoseAssociationValuesSplitAcrossSegments() {
        Segment first = Segment.ROOT_SEGMENT.split()[0];
        for (int i = 0; i < 10_000; i++) {
            String orderId = "order-" + i;
            AssociationValue shipment = new AssociationValue("shipmentId", shipmentIdFor(orderId));
            if (first.matches(orderAssociation(orderId)) != first.matches(shipment)) {
                return orderId;
            }
        }
        throw new IllegalStateException("No order found whose association values land in different segments.");
    }

    private static String anOrderClaimedBy(Segment segment) {
        for (int i = 0; i < 10_000; i++) {
            String orderId = "order-" + i;
            if (segment.matches(orderAssociation(orderId))) {
                return orderId;
            }
        }
        throw new IllegalStateException("No order found whose association value lands in segment " + segment);
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
            assertThat(sagaStore.findSagas(OrderSaga.class, ORDER_1)).hasSize(1);
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
            assertThat(sagaStore.findSagas(OrderSaga.class, ORDER_1)).hasSize(1);
        }

        @Test
        void theSagaIsWrittenBeforeTheTransactionOfThePublishingContextCommits() {
            // given a transactional unit of work, recorded into the same list the saga store writes to
            List<String> order = storeAndTransactionOrder;
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
            assertThat(sagaStore.findSagas(OrderSaga.class, ORDER_1)).isEmpty();
        }
    }

    private static EventMessage orderPlaced(String orderId) {
        return new GenericEventMessage(new MessageType(OrderPlaced.class), new OrderPlaced(orderId));
    }

    public record OrderPlaced(String orderId) {

    }

    private static EventMessage orderShipped(String shipmentId) {
        return new GenericEventMessage(new MessageType(OrderShipped.class), new OrderShipped(shipmentId));
    }

    private static EventMessage orderCompleted(String shipmentId) {
        return new GenericEventMessage(new MessageType(OrderCompleted.class), new OrderCompleted(shipmentId));
    }

    private static String shipmentIdFor(String orderId) {
        return "shipment-of-" + orderId;
    }

    public record OrderShipped(String shipmentId) {

    }

    public record OrderCompleted(String shipmentId) {

    }

    /**
     * An ordinary Axon Framework 4 saga: a starting handler associated by a property of the event, which then
     * associates itself with a second value, as a saga tracking a longer process does. The manager derives the saga
     * identifier itself, so the store is asserted on the association values rather than on a caller-chosen id.
     */
    @SuppressWarnings("unused")
    public static class OrderSaga {

        private int shippedCount = 0;

        @StartSaga
        @SagaEventHandler(associationProperty = "orderId")
        public void on(OrderPlaced event, SagaLifecycle lifecycle) {
            lifecycle.associateWith("shipmentId", shipmentIdFor(event.orderId()));
        }

        @SagaEventHandler(associationProperty = "shipmentId")
        public void on(OrderShipped event) {
            shippedCount++;
        }

        @EndSaga
        @SagaEventHandler(associationProperty = "shipmentId")
        public void on(OrderCompleted event) {
            // Ending the saga is the whole point here.
        }

        public int getShippedCount() {
            return shippedCount;
        }
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
