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

package org.axonframework.hunt.scenario;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventhandling.gateway.EventGateway;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.repository.ManagedEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Probes the live event-routing path of entity lifecycle handlers: when a command handler co-loads several
 * event-sourced entities in one unit of work, an event appended in that unit of work must evolve exactly the
 * entities whose {@link EventCriteria} it matches -- the same filter initial sourcing applies -- and the state an
 * entity holds in memory after the append must equal the state a fresh reload from the store produces.
 * <p>
 * Targets the fix for cross-contamination between co-loaded entities of the same type (live appends previously
 * evolved every loaded entity sharing an event-sourcing handler, ignoring tags), the live-path filter now added in
 * {@code SimpleEntityLifecycleHandler#subscribe}, and the per-unit-of-work tag-resolution cache keyed by event
 * identifier in {@code TagResolver#resolve(EventMessage, ProcessingContext)}.
 */
class LiveEntityEvolutionRoutingTest {

    /**
     * FQCN of the Axon Server configuration enhancer, disabled by name so the run keeps the in-memory defaults; the
     * {@code disableEnhancer(String)} overload is a no-op when the connector is absent from the classpath.
     */
    private static final String AXON_SERVER_ENHANCER_FQCN =
            "io.axoniq.framework.axonserver.connector.configuration.AxonServerConfigurationEnhancer";

    private AxonConfiguration configuration;

    @AfterEach
    void shutDown() {
        if (configuration != null) {
            configuration.shutdown();
        }
    }

    private void start(EventSourcedEntityModule<String, ?> entity, Object commandHandlingService) {
        var commandModule = CommandHandlingModule.named("probe")
                                                 .commandHandlers()
                                                 .autodetectedCommandHandlingComponent(c -> commandHandlingService)
                                                 .build();
        configuration = EventSourcingConfigurer.create()
                                               .registerEntity(entity)
                                               .registerCommandHandlingModule(commandModule)
                                               .componentRegistry(cr -> cr.disableEnhancer(AXON_SERVER_ENHANCER_FQCN))
                                               .start();
    }

    private void send(Object command) {
        configuration.getComponent(CommandGateway.class)
                     .send(command)
                     .getResultMessage()
                     .orTimeout(30, TimeUnit.SECONDS)
                     .join();
    }

    private void publish(Object payload) {
        UnitOfWork uow = configuration.getComponent(UnitOfWorkFactory.class).create();
        var eventMessage = new GenericEventMessage(new MessageType(payload.getClass()), payload);
        uow.runOnInvocation(context -> context.component(EventGateway.class).publish(context, eventMessage));
        uow.execute().orTimeout(30, TimeUnit.SECONDS).join();
    }

    private <E> E reload(Class<E> entityType, String id) {
        UnitOfWork uow = configuration.getComponent(UnitOfWorkFactory.class).create();
        return uow.executeWithResult(context -> context.component(StateManager.class)
                                                       .repository(entityType, String.class)
                                                       .load(id, context)
                                                       .thenApply(ManagedEntity::entity))
                  .orTimeout(30, TimeUnit.SECONDS)
                  .join();
    }

    // ========== Counter domain: identity-tagged events over co-loaded same-type entities ==========

    record CounterCreated(@EventTag(key = "Counter") String counterId) {
    }

    record Bumped(@EventTag(key = "Counter") String counterId) {
    }

    record BumpTwo(String idA, String idB, List<String> bumps) {
    }

    record BumpThree(String idA, String idB, String idC, List<String> bumps) {
    }

    @EventSourcedEntity
    public static class Counter {

        private int count;

        @EntityCreator
        public Counter() {
        }

        int count() {
            return count;
        }

        @EventSourcingHandler
        void on(CounterCreated event) {
            // creation only; count stays 0
        }

        @EventSourcingHandler
        void on(Bumped event) {
            count++;
        }

        @EventCriteriaBuilder
        private static EventCriteria resolve(String id) {
            return EventCriteria.havingTags("Counter", id);
        }
    }

    /**
     * Appends one {@link Bumped} per requested target while two (or three) {@link Counter Counters} are loaded, then
     * records each loaded counter's in-memory count as observed immediately after the appends -- the live path, before
     * any commit or resourcing.
     */
    public static class CounterService {

        final Map<String, Integer> liveObserved = new ConcurrentHashMap<>();

        @CommandHandler
        void handle(BumpTwo command,
                    @InjectEntity(idProperty = "idA") Counter a,
                    @InjectEntity(idProperty = "idB") Counter b,
                    EventAppender appender) {
            command.bumps().forEach(target -> appender.append(new Bumped(target)));
            liveObserved.put(command.idA(), a.count());
            liveObserved.put(command.idB(), b.count());
        }

        @CommandHandler
        void handle(BumpThree command,
                    @InjectEntity(idProperty = "idA") Counter a,
                    @InjectEntity(idProperty = "idB") Counter b,
                    @InjectEntity(idProperty = "idC") Counter c,
                    EventAppender appender) {
            command.bumps().forEach(target -> appender.append(new Bumped(target)));
            liveObserved.put(command.idA(), a.count());
            liveObserved.put(command.idB(), b.count());
            liveObserved.put(command.idC(), c.count());
        }
    }

    @Nested
    class LiveAppendEvolvesOnlyTheMatchingEntity {

        @Test
        void appendTaggedForSecondEntityLeavesFirstUnevolved() {
            // given -- two counters of the same type co-loaded in one unit of work
            CounterService service = new CounterService();
            start(EventSourcedEntityModule.autodetected(String.class, Counter.class), service);
            publish(new CounterCreated("c1"));
            publish(new CounterCreated("c2"));

            // when -- one event tagged for c2 only is appended
            send(new BumpTwo("c1", "c2", List.of("c2")));

            // then -- live in-memory state: c1 untouched, c2 evolved
            assertThat(service.liveObserved).containsEntry("c1", 0).containsEntry("c2", 1);
            // then -- resourced state agrees with the live state
            assertThat(reload(Counter.class, "c1").count()).isEqualTo(0);
            assertThat(reload(Counter.class, "c2").count()).isEqualTo(1);
        }

        @Test
        void appendTaggedForFirstEntityLeavesSecondUnevolved() {
            // given
            CounterService service = new CounterService();
            start(EventSourcedEntityModule.autodetected(String.class, Counter.class), service);
            publish(new CounterCreated("c1"));
            publish(new CounterCreated("c2"));

            // when -- the inverse: one event tagged for c1 only
            send(new BumpTwo("c1", "c2", List.of("c1")));

            // then
            assertThat(service.liveObserved).containsEntry("c1", 1).containsEntry("c2", 0);
            assertThat(reload(Counter.class, "c1").count()).isEqualTo(1);
            assertThat(reload(Counter.class, "c2").count()).isEqualTo(0);
        }

        @Test
        void interleavedAppendsOverThreeEntitiesEachLandOnTheirOwnEntity() {
            // given -- three counters co-loaded
            CounterService service = new CounterService();
            start(EventSourcedEntityModule.autodetected(String.class, Counter.class), service);
            publish(new CounterCreated("a"));
            publish(new CounterCreated("b"));
            publish(new CounterCreated("c"));

            // when -- interleaved appends: a, c, a
            send(new BumpThree("a", "b", "c", List.of("a", "c", "a")));

            // then -- live counts per entity match the per-target append counts
            assertThat(service.liveObserved)
                    .containsEntry("a", 2)
                    .containsEntry("b", 0)
                    .containsEntry("c", 1);
            assertThat(reload(Counter.class, "a").count()).isEqualTo(2);
            assertThat(reload(Counter.class, "b").count()).isEqualTo(0);
            assertThat(reload(Counter.class, "c").count()).isEqualTo(1);
        }
    }

    @Nested
    class TagResolutionCachePerUnitOfWork {

        @Test
        void samePayloadTypeWithDifferentTagValuesInOneUnitOfWorkRoutesEachAppendByItsOwnTags() {
            // given -- two counters co-loaded; the appended events share a payload type but differ in tag value
            CounterService service = new CounterService();
            start(EventSourcedEntityModule.autodetected(String.class, Counter.class), service);
            publish(new CounterCreated("c1"));
            publish(new CounterCreated("c2"));

            // when -- Bumped("c1") then Bumped("c2") in the same unit of work
            send(new BumpTwo("c1", "c2", List.of("c1", "c2")));

            // then -- a poisoned cache would give the second append the first append's tags (c1=2, c2=0)
            assertThat(service.liveObserved).containsEntry("c1", 1).containsEntry("c2", 1);
            assertThat(reload(Counter.class, "c1").count()).isEqualTo(1);
            assertThat(reload(Counter.class, "c2").count()).isEqualTo(1);
        }
    }

    // ========== Gauge domain: OR-criteria entity, event matching only the non-identity branch ==========

    record GaugeCreated(@EventTag(key = "Gauge") String gaugeId) {
    }

    record GlobalAdjusted(@EventTag(key = "broadcast") String scope) {
    }

    record AdjustGlobally(String gaugeId) {
    }

    @EventSourcedEntity
    public static class Gauge {

        private int adjustments;

        @EntityCreator
        public Gauge() {
        }

        int adjustments() {
            return adjustments;
        }

        @EventSourcingHandler
        void on(GaugeCreated event) {
            // creation only; adjustments stays 0
        }

        @EventSourcingHandler
        void on(GlobalAdjusted event) {
            adjustments++;
        }

        @EventCriteriaBuilder
        private static EventCriteria resolve(String id) {
            return EventCriteria.havingTags("Gauge", id)
                                .or(EventCriteria.havingTags("broadcast", "all"));
        }
    }

    public static class GaugeService {

        final Map<String, Integer> liveObserved = new ConcurrentHashMap<>();

        @CommandHandler
        void handle(AdjustGlobally command,
                    @InjectEntity(idProperty = "gaugeId") Gauge gauge,
                    EventAppender appender) {
            appender.append(new GlobalAdjusted("all"));
            liveObserved.put(command.gaugeId(), gauge.adjustments());
        }
    }

    @Nested
    class LivePathFilterAgreesWithSourcingPathFilter {

        @Test
        void eventMatchingOnlyTheNonIdentityCriteriaBranchEvolvesLiveExactlyAsSourcingDelivers() {
            // given -- a gauge whose criteria is (Gauge=id OR broadcast=all), loaded via its identity tag
            GaugeService service = new GaugeService();
            start(EventSourcedEntityModule.autodetected(String.class, Gauge.class), service);
            publish(new GaugeCreated("g1"));

            // when -- an event carrying only the broadcast tag is appended in the gauge's unit of work
            send(new AdjustGlobally("g1"));

            // then -- fresh resourcing delivers the broadcast event, so the live path must have evolved too;
            // live=0 with reload=1 is the stale-in-memory divergence between the two filters
            int resourced = reload(Gauge.class, "g1").adjustments();
            assertThat(resourced).isEqualTo(1);
            assertThat(service.liveObserved).containsEntry("g1", resourced);
        }
    }
}
