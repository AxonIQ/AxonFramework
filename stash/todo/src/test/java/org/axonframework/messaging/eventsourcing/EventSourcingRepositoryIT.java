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

package org.axonframework.messaging.eventsourcing;

import org.axonframework.messaging.eventhandling.DomainEventMessage;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.eventstore.AnnotationBasedTagResolver;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.StorageEngineBackedEventStore;
import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.unitofwork.LegacyDefaultUnitOfWork;
import org.axonframework.messaging.unitofwork.LegacyUnitOfWork;
import org.axonframework.modelling.ConcurrencyException;
import org.axonframework.modelling.command.Aggregate;
import org.axonframework.modelling.command.AggregateIdentifier;
import org.axonframework.modelling.command.AggregateLifecycle;
import org.axonframework.common.util.StubDomainEvent;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * @author Allard Buijze
 */
public class EventSourcingRepositoryIT implements Thread.UncaughtExceptionHandler {

    private static final int CONCURRENT_MODIFIERS = 10;
    private LegacyEventSourcingRepository<SimpleAggregateRoot> repository;
    private String aggregateIdentifier;
    private EventStore eventStore;
    private List<Throwable> uncaughtExceptions = new CopyOnWriteArrayList<>();
    private List<Thread> startedThreads = new ArrayList<>();

    @Test
    @Timeout(value = 6)
    @Disabled
    void pessimisticLocking() throws Throwable {
        initializeRepository();
        long lastSequenceNumber = executeConcurrentModifications(CONCURRENT_MODIFIERS);

        // with pessimistic locking, all modifications are guaranteed successful
        // note: sequence number 20 means there are 21 events. This includes the one from the setup
        assertEquals(2 * CONCURRENT_MODIFIERS, lastSequenceNumber);
        assertEquals(CONCURRENT_MODIFIERS, getSuccessfulModifications());
    }

    private int getSuccessfulModifications() {
        return CONCURRENT_MODIFIERS - uncaughtExceptions.size();
    }

    private void initializeRepository() throws Exception {
        EventStorageEngine eventStorageEngine = new InMemoryEventStorageEngine();
        TagResolver tagResolver = new AnnotationBasedTagResolver();
        EventBus eventBus = new SimpleEventBus();
        eventStore = new StorageEngineBackedEventStore(eventStorageEngine, eventBus, tagResolver);
        repository = LegacyEventSourcingRepository.builder(SimpleAggregateRoot.class)
                                                  .aggregateFactory(new SimpleAggregateFactory())
//                                                  .eventStore(eventStore)
                                                  .build();
        EventBus mockEventBus = mock(EventBus.class);

        LegacyUnitOfWork<?> uow = LegacyDefaultUnitOfWork.startAndGet(null);
        Aggregate<SimpleAggregateRoot> aggregate = repository.newInstance(SimpleAggregateRoot::new);
        uow.commit();

        reset(mockEventBus);
        aggregateIdentifier = aggregate.invoke(SimpleAggregateRoot::getIdentifier);
    }

    private long executeConcurrentModifications(final int concurrentModifiers) throws Throwable {
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch threadsDone = new CountDownLatch(concurrentModifiers);
        for (int t = 0; t < concurrentModifiers; t++) {
            prepareAggregateModifier(startSignal, threadsDone, repository, aggregateIdentifier);
        }
        startSignal.countDown();
        if (!threadsDone.await(30, TimeUnit.SECONDS)) {
            printDiagnosticInformation();
            fail("Thread found to be alive after timeout. It might be hanging");
        }
        for (Throwable e : uncaughtExceptions) {
            if (!(e instanceof ConcurrencyException)) {
                throw e;
            }
        }

//        DomainEventStream committedEvents = eventStore.readEvents(aggregateIdentifier);
        long lastSequenceNumber = -1;
//        while (committedEvents.hasNext()) {
//            DomainEventMessage nextEvent = committedEvents.next();
//            assertEquals(++lastSequenceNumber,
//                         nextEvent.getSequenceNumber(),
//                    "Events are not stored sequentially. Most likely due to unlocked concurrent access.");
//        }
        return lastSequenceNumber;
    }

    private void printDiagnosticInformation() {
        for (Thread t : startedThreads) {
            System.out.print("## Thread [" + t.getName() + "] did not properly shut down during Locking test. ##");
            if (t.getState() != Thread.State.TERMINATED) {
                for (StackTraceElement ste : t.getStackTrace()) {
                    System.out.println(" - " + ste.toString());
                }
            }
            System.out.println();
        }
    }

    private Thread prepareAggregateModifier(final CountDownLatch awaitFor, final CountDownLatch reportDone,
                                            final LegacyEventSourcingRepository<SimpleAggregateRoot> repository,
                                            final String aggregateIdentifier) {
        Thread t = new Thread(() -> {
            try {
                awaitFor.await();
                LegacyUnitOfWork<?> uow = LegacyDefaultUnitOfWork.startAndGet(null);
                Aggregate<SimpleAggregateRoot> aggregate = repository.load(aggregateIdentifier);
                aggregate.execute(SimpleAggregateRoot::doOperation);
                aggregate.execute(SimpleAggregateRoot::doOperation);
                uow.commit();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                reportDone.countDown();
            }
        });
        t.setUncaughtExceptionHandler(this);
        startedThreads.add(t);
        t.start();
        return t;
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        uncaughtExceptions.add(e);
    }

    private static class SimpleAggregateRoot {

        @AggregateIdentifier
        private String identifier;

        private SimpleAggregateRoot() {
            identifier = UUID.randomUUID().toString();
            AggregateLifecycle.apply(new StubDomainEvent());
        }

        private SimpleAggregateRoot(String identifier) {
            this.identifier = identifier;
        }

        private void doOperation() {
            AggregateLifecycle.apply(new StubDomainEvent());
        }

        @EventSourcingHandler
        protected void handle(EventMessage event) {
            identifier = ((DomainEventMessage) event).getAggregateIdentifier();
        }

        public String getIdentifier() {
            return identifier;
        }
    }

    private static class SimpleAggregateFactory extends AbstractAggregateFactory<SimpleAggregateRoot> {

        SimpleAggregateFactory() {
            super(SimpleAggregateRoot.class);
        }

        @Override
        public @NonNull SimpleAggregateRoot doCreateAggregate(@NonNull String aggregateIdentifier,
                                                              @NonNull DomainEventMessage firstEvent) {
            return new SimpleAggregateRoot(aggregateIdentifier);
        }
    }
}
