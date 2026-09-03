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
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
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
import org.axonframework.modelling.saga.repository.AnnotatedSagaRepository;
import org.axonframework.modelling.saga.repository.SagaCreationException;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

/**
 * Shows which event processor an {@code axon-legacy} saga repository can run behind, and why the answer is not "any of
 * them".
 * <p>
 * {@link AnnotatedSagaRepository} writes the saga during the
 * {@link org.axonframework.messaging.core.unitofwork.ProcessingLifecycle.DefaultPhases#PREPARE_COMMIT PREPARE_COMMIT}
 * phase of the {@link org.axonframework.messaging.core.unitofwork.ProcessingContext} it was called in, which is what
 * puts that write in the caller's transaction. Registering an action for a phase that is already running is rejected,
 * so where the repository is called from decides whether it can work at all:
 * <ul>
 *     <li>A {@link PooledStreamingEventProcessor} creates a unit of work per batch and invokes its components during
 *     {@code INVOCATION}, so the repository can still register for {@code PREPARE_COMMIT}.</li>
 *     <li>A {@link SubscribingEventProcessor} handles events in the context they were published with, and
 *     {@link SimpleEventBus} delivers those during {@code PREPARE_COMMIT} of that context. The repository is then
 *     called from inside the phase it needs, and fails.</li>
 * </ul>
 * Axon Framework 4 solved this with a nested unit of work, which ran its own prepare-commit immediately. Axon
 * Framework 5 has no nesting, and the repository cannot save the saga itself at that point either -- the write has to
 * happen after the handler mutated it, and only the component invoking the saga knows when that is. Until that
 * component exists, the subscribing case is a known gap; the disabled
 * {@code AnnotatedSagaRepositoryTest#loadedFromNestedUnitOfWorkAfterCreateAndStore} states the same thing at unit
 * level.
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
            // INVOCATION, leaving PREPARE_COMMIT free for the repository to write in
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
        void anEventPublishedWithAContextIsDeliveredDuringPrepareCommitAndTheSagaCannotBeSaved() {
            // given a unit of work that publishes the event, as a command handler would
            UnitOfWork publishingUnitOfWork = unitOfWorkFactory.create();
            publishingUnitOfWork.onInvocation(
                    context -> eventBus.publish(context, List.of(orderPlaced("order-1")))
            );

            // when SimpleEventBus delivers it during PREPARE_COMMIT of that same context, the repository is asked to
            // register a PREPARE_COMMIT action while that phase is running. It surfaces wrapped, because
            // doCreateInstance wraps anything its body throws, which is Axon Framework 4 behaviour in its own right.
            assertThatThrownBy(() -> FutureUtils.joinAndUnwrap(publishingUnitOfWork.execute(), TIMEOUT))
                    .isInstanceOf(SagaCreationException.class)
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PREPARE_COMMIT");

            // then nothing was stored, and the publishing unit of work failed with it. This is the gap: Axon
            // Framework 4 saved the saga here through a nested unit of work.
            assertThat(sagaStore.findSagas(Object.class, ORDER_1)).isEmpty();
        }
    }

    private static EventMessage orderPlaced(String orderId) {
        return new GenericEventMessage(new MessageType(OrderPlaced.class), new OrderPlaced(orderId));
    }

    public record OrderPlaced(String orderId) {

    }
}
