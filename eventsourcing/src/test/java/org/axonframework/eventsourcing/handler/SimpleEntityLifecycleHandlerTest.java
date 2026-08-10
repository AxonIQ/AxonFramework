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

package org.axonframework.eventsourcing.handler;

import org.axonframework.eventsourcing.CriteriaResolver;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.eventstore.AnnotationBasedTagResolver;
import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.eventsourcing.eventstore.ConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.EventStoreTransaction;
import org.axonframework.eventsourcing.eventstore.StorageEngineBackedEventStore;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.repository.ManagedEntity;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link SimpleEntityLifecycleHandler}.
 * <p>
 * Covers two concerns: its support for separate sourcing and append {@link CriteriaResolver CriteriaResolvers}, and
 * the live {@link SimpleEntityLifecycleHandler#subscribe(ManagedEntity, ProcessingContext) subscribe} path, where
 * events appended within the same {@link ProcessingContext} must only evolve a loaded entity when they match that
 * entity's sourcing {@link EventCriteria} (tags) - mirroring the filtering already performed while
 * {@link SimpleEntityLifecycleHandler#source(Object, ProcessingContext) sourcing}. Without that filter, two entities
 * of the same type loaded in one unit of work would both react to an event that belongs to only one of them (the
 * "speedometer moved between bikes" scenario).
 *
 * @author Mateusz Nowak
 */
class SimpleEntityLifecycleHandlerTest {

    private static final String ACCOUNT_ID = "account-1";

    private final StorageEngineBackedEventStore eventStore = new StorageEngineBackedEventStore(
            new InMemoryEventStorageEngine(),
            new SimpleEventBus(),
            new AnnotationBasedTagResolver()
    );

    private final InitializingEntityEvolver<String, String> evolver = new InitializingEntityEvolver<>(
            (id, event, ctx) -> id + "()",
            (entity, event, ctx) -> entity + "-" + event.payload()
    );

    @Nested
    class WhenSubscribed {

        private record BikeAdded(@EventTag(key = "RentableBike") String bikeId) {}

        private record SpeedometerMounted(@EventTag(key = "RentableBike") String bikeId, String speedometerId) {}

        private record SpeedometerRemoved(@EventTag(key = "RentableBike") String bikeId, String speedometerId) {}

        private record Bike(String id, String mountedSpeedometerId) {}

        private final SimpleEntityLifecycleHandler<String, Bike> handler = new SimpleEntityLifecycleHandler<>(
                eventStore,
                (id, ctx) -> EventCriteria.havingTags(Tag.of("RentableBike", id)),
                new AnnotationBasedTagResolver(),
                new InitializingEntityEvolver<>(
                        (id, msg, ctx) -> new Bike(id, null),
                        (entity, event, ctx) -> switch (event.payload()) {
                            case SpeedometerMounted m -> new Bike(entity.id(), m.speedometerId());
                            case SpeedometerRemoved ignored -> new Bike(entity.id(), null);
                            default -> entity;
                        }
                )
        );

        @Test
        void liveEventForAnotherEntityIsNotApplied() {
            // given
            publish(new BikeAdded("bike1"), new SpeedometerMounted("bike1", "speedo1"));
            ProcessingContext pc = new StubProcessingContext();
            Bike initial = handler.source("bike1", pc).join();
            AtomicReference<Bike> stateRef = new AtomicReference<>(initial);
            handler.subscribe(managedEntity("bike1", stateRef), pc);

            // when -- an event tagged for bike2 is appended within the same context
            eventStore.transaction(pc).appendEvent(new GenericEventMessage(
                    new MessageType(SpeedometerRemoved.class),
                    new SpeedometerRemoved("bike2", "speedo2")
            ));

            // then -- bike1 must be untouched, it does not carry bike2's tag
            assertThat(stateRef.get()).isEqualTo(initial);
            assertThat(stateRef.get().mountedSpeedometerId()).isEqualTo("speedo1");
        }

        @Test
        void liveEventForThisEntityIsApplied() {
            // given
            publish(new BikeAdded("bike1"), new SpeedometerMounted("bike1", "speedo1"));
            ProcessingContext pc = new StubProcessingContext();
            Bike initial = handler.source("bike1", pc).join();
            AtomicReference<Bike> stateRef = new AtomicReference<>(initial);
            handler.subscribe(managedEntity("bike1", stateRef), pc);

            // when -- an event tagged for bike1 is appended within the same context
            eventStore.transaction(pc).appendEvent(new GenericEventMessage(
                    new MessageType(SpeedometerRemoved.class),
                    new SpeedometerRemoved("bike1", "speedo1")
            ));

            // then -- bike1 evolves, the event carries its tag
            assertThat(stateRef.get().mountedSpeedometerId()).isNull();
        }

        private ManagedEntity<String, Bike> managedEntity(String id, AtomicReference<Bike> stateRef) {
            return new ManagedEntity<>() {
                @Override
                public String identifier() {
                    return id;
                }

                @Override
                public Bike entity() {
                    return stateRef.get();
                }

                @Override
                public Bike applyStateChange(UnaryOperator<Bike> change) {
                    return stateRef.updateAndGet(change);
                }
            };
        }
    }

    @Nested
    class ResolverInvocationCount {

        @Test
        void singularConstructorInvokesResolverExactlyOnce() {
            AtomicInteger resolveCalls = new AtomicInteger();
            CriteriaResolver<String> resolver = (id, ctx) -> {
                resolveCalls.incrementAndGet();
                return EventCriteria.havingTags(Tag.of("account", id));
            };
            SimpleEntityLifecycleHandler<String, String> handler = new SimpleEntityLifecycleHandler<>(
                    eventStore, resolver, new AnnotationBasedTagResolver(), evolver
            );

            source(handler);

            assertThat(resolveCalls.get()).isEqualTo(1);
        }

        @Test
        void twoResolverConstructorInvokesEachResolverExactlyOnce() {
            AtomicInteger sourcingCalls = new AtomicInteger();
            AtomicInteger appendCalls = new AtomicInteger();
            CriteriaResolver<String> sourcingResolver = (id, ctx) -> {
                sourcingCalls.incrementAndGet();
                return EventCriteria.havingTags(Tag.of("account", id));
            };
            CriteriaResolver<String> appendResolver = (id, ctx) -> {
                appendCalls.incrementAndGet();
                return EventCriteria.havingTags(Tag.of("append-only", id));
            };
            SimpleEntityLifecycleHandler<String, String> handler = new SimpleEntityLifecycleHandler<>(
                    eventStore, sourcingResolver, appendResolver, new AnnotationBasedTagResolver(), evolver
            );

            source(handler);

            assertThat(sourcingCalls.get()).isEqualTo(1);
            assertThat(appendCalls.get()).isEqualTo(1);
        }
    }

    @Nested
    class AsymmetricAppendCriteria {

        private record AccountCreated(@EventTag(key = "account") String id) {}

        @Test
        void appendCriteriaResolverNarrowsTheResultingAppendCondition() {
            publish(new AccountCreated(ACCOUNT_ID));

            EventCriteria sourcingCriteria = EventCriteria.havingTags(Tag.of("account", ACCOUNT_ID));
            EventCriteria appendCriteria = EventCriteria.havingTags(Tag.of("narrow", ACCOUNT_ID));
            SimpleEntityLifecycleHandler<String, String> handler = new SimpleEntityLifecycleHandler<>(
                    eventStore,
                    (id, ctx) -> sourcingCriteria,
                    (id, ctx) -> appendCriteria,
                    new AnnotationBasedTagResolver(),
                    evolver
            );

            var resolvedCondition = new AtomicReference<AppendCondition>();
            UnitOfWork uow = UnitOfWorkTestUtils.aUnitOfWork();
            uow.executeWithResult(pc -> {
                handler.source(ACCOUNT_ID, pc).join();
                EventStoreTransaction transaction = eventStore.transaction(pc);
                transaction.overrideAppendCondition(condition -> {
                    resolvedCondition.set(condition);
                    return condition;
                });
                transaction.appendEvent(new GenericEventMessage(new MessageType(String.class), "irrelevant"));
                return CompletableFuture.completedFuture(null);
            }).join();

            assertThat(resolvedCondition.get().criteria()).isEqualTo(appendCriteria);
        }

        @Test
        void symmetricConfigurationLeavesAppendConditionEqualToSourcingCriteria() {
            publish(new AccountCreated(ACCOUNT_ID));

            EventCriteria criteria = EventCriteria.havingTags(Tag.of("account", ACCOUNT_ID));
            SimpleEntityLifecycleHandler<String, String> handler = new SimpleEntityLifecycleHandler<>(
                    eventStore, (id, ctx) -> criteria, new AnnotationBasedTagResolver(), evolver
            );

            var resolvedCondition = new AtomicReference<AppendCondition>();
            UnitOfWork uow = UnitOfWorkTestUtils.aUnitOfWork();
            uow.executeWithResult(pc -> {
                handler.source(ACCOUNT_ID, pc).join();
                EventStoreTransaction transaction = eventStore.transaction(pc);
                transaction.overrideAppendCondition(condition -> {
                    resolvedCondition.set(condition);
                    return condition;
                });
                transaction.appendEvent(new GenericEventMessage(new MessageType(String.class), "irrelevant"));
                return CompletableFuture.completedFuture(null);
            }).join();

            assertThat(resolvedCondition.get().criteria()).isEqualTo(criteria);
            assertThat(resolvedCondition.get().consistencyMarker()).isNotEqualTo(ConsistencyMarker.ORIGIN);
        }
    }

    private void publish(Object... events) {
        eventStore.publish(
                null,
                Arrays.stream(events)
                      .map(e -> (EventMessage) new GenericEventMessage(new MessageType(e.getClass()), e))
                      .toList()
        ).join();
    }

    private String source(SimpleEntityLifecycleHandler<String, String> handler) {
        UnitOfWork uow = UnitOfWorkTestUtils.aUnitOfWork();
        return uow.executeWithResult(pc -> handler.source(ACCOUNT_ID, pc)).join();
    }
}
