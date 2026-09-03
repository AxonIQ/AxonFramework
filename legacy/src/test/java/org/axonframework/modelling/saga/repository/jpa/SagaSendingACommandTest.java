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

package org.axonframework.modelling.saga.repository.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.axonframework.common.FutureUtils;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.common.jpa.SimpleEntityManagerProvider;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandPriorityCalculator;
import org.axonframework.messaging.commandhandling.SimpleCommandBus;
import org.axonframework.messaging.commandhandling.annotation.AnnotationRoutingStrategy;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.commandhandling.gateway.DefaultCommandGateway;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.TransactionalUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.EntityManagerTransactionManager;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.modelling.saga.AnnotatedSaga;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.repository.AnnotatedSagaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the thing a saga does most: send a command, and stay consistent with the processing that triggered it.
 * <p>
 * Axon Framework 4 nested the command's unit of work inside the saga's. Axon Framework 5's
 * {@link SimpleCommandBus} creates its own instead, which is the same shape a distributed command bus has, where the
 * handler runs in another JVM and cannot share the sender's unit of work at all. What this test holds onto is the part
 * that belongs to the saga repository: the saga's own state follows the processing context it was created in, in both
 * directions.
 * <p>
 * The saga reaches the {@link CommandGateway} through a field, which is how an Axon Framework 4 saga got it from a
 * {@code ResourceInjector}, and hands it the {@link ProcessingContext} its handler was invoked with.
 *
 * @author Mateusz Nowak
 */
class SagaSendingACommandTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final AssociationValue ORDER_1 = new AssociationValue("orderId", "order-1");

    private EntityManagerFactory entityManagerFactory;
    private EntityManager entityManager;
    private JpaSagaStore sagaStore;
    private UnitOfWorkFactory unitOfWorkFactory;
    private CommandGateway commandGateway;
    private AnnotatedSagaRepository<CommandSendingSaga> repository;

    @BeforeEach
    void setUp() {
        entityManagerFactory = Persistence.createEntityManagerFactory("jpaSagaStorePersistenceUnit");
        entityManager = entityManagerFactory.createEntityManager();
        EntityManagerProvider entityManagerProvider = new SimpleEntityManagerProvider(entityManager);

        sagaStore = JpaSagaStore.builder()
                                .entityManagerProvider(entityManagerProvider)
                                .converter(new JacksonConverter())
                                .build();

        unitOfWorkFactory = new TransactionalUnitOfWorkFactory(
                new EntityManagerTransactionManager(entityManagerProvider),
                new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE)
        );

        // The command handler writes through the same store, so its work is observable in the same table.
        CommandBus commandBus = new SimpleCommandBus(unitOfWorkFactory).subscribe(
                new QualifiedName(RecordTheShipment.class),
                (command, context) -> {
                    sagaStore.insertSaga(CommandSendingSaga.class,
                                         "written-by-the-command-handler",
                                         new CommandSendingSaga(),
                                         Set.of());
                    return MessageStream.empty().cast();
                }
        );
        commandGateway = new DefaultCommandGateway(commandBus,
                                                   new ClassBasedMessageTypeResolver(),
                                                   CommandPriorityCalculator.defaultCalculator(),
                                                   new AnnotationRoutingStrategy());

        repository = AnnotatedSagaRepository.<CommandSendingSaga>builder()
                                           .sagaType(CommandSendingSaga.class)
                                           .sagaStore(sagaStore)
                                           .build();
    }

    @AfterEach
    void tearDown() {
        entityManager.close();
        entityManagerFactory.close();
    }

    @Nested
    class WhenTheProcessingSucceeds {

        @Test
        void theSagaIsPersistedAndTheCommandWasHandled() {
            // given a saga that sends a command while handling an event
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            unitOfWork.runOnInvocation(context -> handleOrderPlaced(context, "saga-1"));

            // when
            FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);

            // then both the saga and the command's write are there
            assertThat(committedSagaIds()).contains("saga-1", "written-by-the-command-handler");
            assertThat(committedAssociationSagaIds(ORDER_1)).containsExactly("saga-1");
        }
    }

    @Nested
    class WhenTheProcessingFails {

        @Test
        void theSagaIsNotPersisted() {
            // given the same saga, in a unit of work that fails after the repository wrote it. The failure is
            // registered from within the invocation, so it lands behind the repository's own prepare-commit action
            // and therefore runs after the saga was written but before the transaction is committed.
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            unitOfWork.runOnInvocation(context -> {
                handleOrderPlaced(context, "saga-1");
                context.runOnPrepareCommit(c -> {
                    throw new IllegalStateException("failing after the saga was written");
                });
            });

            // when
            assertThatThrownBy(() -> FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("failing after the saga was written");

            // then the saga did not survive it, which it could only fail to do if the repository wrote inside the
            // unit of work's transaction. Nothing is asserted about the command's write: it ran in a unit of work of
            // its own, so whether it unwinds is decided by the transaction manager's propagation rather than by
            // anything the saga repository does.
            assertThat(committedSagaIds()).doesNotContain("saga-1");
            assertThat(committedAssociationSagaIds(ORDER_1)).isEmpty();
        }
    }

    private void handleOrderPlaced(ProcessingContext context, String sagaId) {
        AnnotatedSaga<CommandSendingSaga> saga =
                (AnnotatedSaga<CommandSendingSaga>) repository.createInstance(
                        sagaId, () -> new CommandSendingSaga(commandGateway), context
                );
        saga.associateWith(ORDER_1);

        EventMessage event = EventTestUtils.asEventMessage(new OrderPlaced("order-1"));
        FutureUtils.joinAndUnwrap(saga.handle(event, context).asCompletableFuture(), TIMEOUT);
    }

    /**
     * Reads through a persistence context that took no part in the unit of work, so only committed state is visible.
     */
    private List<String> committedSagaIds() {
        EntityManager reader = entityManagerFactory.createEntityManager();
        try {
            return reader.createQuery("SELECT se.sagaId FROM SagaEntry se ORDER BY se.sagaId", String.class)
                         .getResultList();
        } finally {
            reader.close();
        }
    }

    private List<String> committedAssociationSagaIds(AssociationValue associationValue) {
        EntityManager reader = entityManagerFactory.createEntityManager();
        try {
            return reader.createQuery("SELECT ae.sagaId FROM AssociationValueEntry ae "
                                              + "WHERE ae.associationKey = :key AND ae.associationValue = :value",
                                      String.class)
                         .setParameter("key", associationValue.getKey())
                         .setParameter("value", associationValue.getValue())
                         .getResultList();
        } finally {
            reader.close();
        }
    }

    /**
     * Holds its {@link CommandGateway} in a field, as an Axon Framework 4 saga did after a {@code ResourceInjector}
     * had filled it in.
     */
    public static class CommandSendingSaga {

        private transient CommandGateway commandGateway;

        public CommandSendingSaga() {
            // Present so the saga converts with any Jackson generation, as the store requires.
        }

        CommandSendingSaga(CommandGateway commandGateway) {
            this.commandGateway = commandGateway;
        }

        @SagaEventHandler(associationProperty = "orderId")
        public void on(OrderPlaced event, ProcessingContext context) {
            commandGateway.send(new RecordTheShipment(event.orderId()), context);
        }
    }

    public record OrderPlaced(String orderId) {

    }

    public record RecordTheShipment(String orderId) {

    }
}
