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
import org.axonframework.eventsourcing.annotation.Snapshotting;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.snapshot.inmemory.InMemorySnapshotStore;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Probes the live event-routing path of {@code SnapshottingEntityLifecycleHandler}: the snapshotting sibling of the
 * variant verified through {@code SimpleEntityLifecycleHandler} in {@link LiveEntityEvolutionRoutingTest}'s shape.
 * <p>
 * The entity carries {@code @Snapshotting}, and a {@link SnapshotStore} is registered, so the declarative builder's
 * {@code OptionalPhase.snapshotPolicy(...)} route (taken by the annotated module) wires the snapshotting lifecycle
 * handler instead of the simple one. A wiring-proof test asserts a snapshot is actually written -- only the
 * snapshotting handler writes snapshots, so a silent fallback to the simple handler turns that test red.
 * <p>
 * The probes assert that an event appended live in a unit of work evolves exactly the co-loaded entities whose
 * {@link EventCriteria} it matches, including an entity that was restored from a snapshot rather than sourced from
 * its creation event.
 */
class SnapshottingLiveEntityEvolutionRoutingTest {

    /**
     * FQCN of the Axon Server configuration enhancer, disabled by name so the run keeps the in-memory defaults; the
     * {@code disableEnhancer(String)} overload is a no-op when the connector is absent from the classpath.
     */
    private static final String AXON_SERVER_ENHANCER_FQCN =
            "io.axoniq.framework.axonserver.connector.configuration.AxonServerConfigurationEnhancer";

    private AxonConfiguration configuration;
    private SnapshotStore snapshotStore;

    @BeforeEach
    void resetReplayCounters() {
        Counter.CREATION_REPLAYS.clear();
    }

    @AfterEach
    void shutDown() {
        if (configuration != null) {
            configuration.shutdown();
        }
    }

    private void start(Object commandHandlingService) {
        var commandModule = CommandHandlingModule.named("probe")
                                                 .commandHandlers()
                                                 .autodetectedCommandHandlingComponent(c -> commandHandlingService)
                                                 .build();
        snapshotStore = new InMemorySnapshotStore();
        configuration = EventSourcingConfigurer.create()
                                               .registerEntity(EventSourcedEntityModule.autodetected(String.class,
                                                                                                     Counter.class))
                                               .registerCommandHandlingModule(commandModule)
                                               .componentRegistry(cr -> cr.disableEnhancer(AXON_SERVER_ENHANCER_FQCN))
                                               .componentRegistry(cr -> cr.registerComponent(SnapshotStore.class,
                                                                                             c -> snapshotStore))
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

    private Counter reload(String id) {
        UnitOfWork uow = configuration.getComponent(UnitOfWorkFactory.class).create();
        return uow.executeWithResult(context -> context.component(StateManager.class)
                                                       .repository(Counter.class, String.class)
                                                       .load(id, context)
                                                       .thenApply(ManagedEntity::entity))
                  .orTimeout(30, TimeUnit.SECONDS)
                  .join();
    }

    private void awaitSnapshotFor(String id) {
        await().atMost(Duration.ofSeconds(5))
               .untilAsserted(() -> assertThat(
                       snapshotStore.load(new QualifiedName(Counter.class), id, null).get(1, TimeUnit.SECONDS)
               ).isNotNull());
    }

    // ========== Counter domain: identity-tagged events, snapshot after two evolutions ==========

    record CounterCreated(@EventTag(key = "Counter") String counterId) {
    }

    record Bumped(@EventTag(key = "Counter") String counterId) {
    }

    record BumpTwo(String idA, String idB, List<String> bumps) {
    }

    record BumpThree(String idA, String idB, String idC, List<String> bumps) {
    }

    @EventSourcedEntity
    @Snapshotting(afterEvents = 2)
    public static class Counter {

        /**
         * Counts, per counter id, how often the creation event was replayed by a sourcing operation. A load that
         * starts from a snapshot never replays the creation event, so a zero here after a load proves the load was
         * snapshot-restored.
         */
        static final Map<String, AtomicInteger> CREATION_REPLAYS = new ConcurrentHashMap<>();

        private int count;

        @EntityCreator
        public Counter() {
        }

        int count() {
            return count;
        }

        @EventSourcingHandler
        void on(CounterCreated event) {
            CREATION_REPLAYS.computeIfAbsent(event.counterId(), k -> new AtomicInteger()).incrementAndGet();
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

        @CommandHandler
        void handle(BumpSelf command,
                    @InjectEntity(idProperty = "counterId") Counter counter,
                    EventAppender appender) {
            appender.append(new Bumped(command.counterId()));
            liveObserved.put(command.counterId(), counter.count());
        }
    }

    record BumpSelf(String counterId) {
    }

    @Nested
    class SnapshottingHandlerIsWired {

        @Test
        void sourcingPastTheEventThresholdStoresASnapshot() {
            // given -- a counter with three events, one above the afterEvents = 2 threshold
            start(new CounterService());
            publish(new CounterCreated("s1"));
            publish(new Bumped("s1"));
            publish(new Bumped("s1"));

            // when -- a load sources all three events
            assertThat(reload("s1").count()).isEqualTo(2);

            // then -- only the snapshotting lifecycle handler stores snapshots; a silent fallback to the
            // simple handler would leave the store empty for ever
            awaitSnapshotFor("s1");
        }
    }

    @Nested
    class LiveAppendEvolvesOnlyTheMatchingEntity {

        @Test
        void appendTaggedForSecondEntityLeavesFirstUnevolved() {
            // given -- two counters of the same type co-loaded in one unit of work
            CounterService service = new CounterService();
            start(service);
            publish(new CounterCreated("c1"));
            publish(new CounterCreated("c2"));

            // when -- one event tagged for c2 only is appended
            send(new BumpTwo("c1", "c2", List.of("c2")));

            // then -- live in-memory state: c1 untouched, c2 evolved
            assertThat(service.liveObserved).containsEntry("c1", 0).containsEntry("c2", 1);
            // then -- resourced state agrees with the live state
            assertThat(reload("c1").count()).isEqualTo(0);
            assertThat(reload("c2").count()).isEqualTo(1);
        }

        @Test
        void appendTaggedForFirstEntityLeavesSecondUnevolved() {
            // given
            CounterService service = new CounterService();
            start(service);
            publish(new CounterCreated("c1"));
            publish(new CounterCreated("c2"));

            // when -- the inverse: one event tagged for c1 only
            send(new BumpTwo("c1", "c2", List.of("c1")));

            // then
            assertThat(service.liveObserved).containsEntry("c1", 1).containsEntry("c2", 0);
            assertThat(reload("c1").count()).isEqualTo(1);
            assertThat(reload("c2").count()).isEqualTo(0);
        }

        @Test
        void interleavedAppendsOverThreeEntitiesEachLandOnTheirOwnEntity() {
            // given -- three counters co-loaded
            CounterService service = new CounterService();
            start(service);
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
            assertThat(reload("a").count()).isEqualTo(2);
            assertThat(reload("b").count()).isEqualTo(0);
            assertThat(reload("c").count()).isEqualTo(1);
        }
    }

    @Nested
    class SnapshotRestoredEntityDoesNotAbsorbForeignAppends {

        @Test
        void liveAppendTargetedAtTheOtherEntityLeavesTheSnapshotRestoredEntityUnevolved() {
            // given -- c1 has three events (snapshot threshold is 2), c2 exists; a load of c1 triggers
            // the snapshot policy, and the snapshot is awaited so the next load restores from it
            CounterService service = new CounterService();
            start(service);
            publish(new CounterCreated("c1"));
            publish(new Bumped("c1"));
            publish(new Bumped("c1"));
            publish(new CounterCreated("c2"));
            assertThat(reload("c1").count()).isEqualTo(2);
            awaitSnapshotFor("c1");
            Counter.CREATION_REPLAYS.clear();

            // when -- c1 (snapshot-restored) and c2 co-loaded in one unit of work, append tagged for c2 only
            send(new BumpTwo("c1", "c2", List.of("c2")));

            // then -- c1 was actually restored from the snapshot: its creation event was not replayed
            assertThat(Counter.CREATION_REPLAYS).doesNotContainKey("c1");
            assertThat(Counter.CREATION_REPLAYS.get("c2").get()).isEqualTo(1);
            // then -- the snapshot-restored in-memory c1 did not absorb the c2-targeted append
            assertThat(service.liveObserved).containsEntry("c1", 2).containsEntry("c2", 1);
            // then -- fresh reloads agree
            assertThat(reload("c1").count()).isEqualTo(2);
            assertThat(reload("c2").count()).isEqualTo(1);
        }

    }

    /**
     * <b>These tests assert the corruption, not the guarantee.</b> They are expected-gap tests for finding F-33:
     * {@code SnapshottingEntityLifecycleHandler#storeSnapshot} hands the live entity reference to the
     * {@link SnapshotStore}, and {@code convertSnapshotPayload} returns the stored payload reference as-is when it is
     * instance-compatible. With a mutable entity (void {@code @EventSourcingHandler}s evolve in place) and the
     * reference-keeping {@link InMemorySnapshotStore}, every live evolution of a snapshotted or snapshot-restored
     * entity silently rewrites the stored snapshot's payload while its position stays put. The next load then applies
     * the same event twice: once baked into the corrupted snapshot, once replayed from the store.
     * <p>
     * The tests pass while the aliasing exists and turn red -- reloads dropping back to the true event-derived count
     * -- as soon as the snapshot payload is defensively copied or converted on store or on load. A failure here is
     * the good news.
     */
    @Nested
    class SnapshotPayloadAliasingDoubleAppliesEvents {

        @Test
        void liveEvolutionOfASnapshotRestoredEntityCorruptsTheStoredSnapshot() {
            // given -- c1 snapshotted at count 2; c2 exists
            CounterService service = new CounterService();
            start(service);
            publish(new CounterCreated("c1"));
            publish(new Bumped("c1"));
            publish(new Bumped("c1"));
            publish(new CounterCreated("c2"));
            assertThat(reload("c1").count()).isEqualTo(2);
            awaitSnapshotFor("c1");
            Counter.CREATION_REPLAYS.clear();

            // when -- c1 is restored FROM the snapshot (creation event not replayed) and its own live append
            // evolves the restored instance in place
            send(new BumpTwo("c1", "c2", List.of("c1")));

            // then -- routing was correct: c1 evolved to 3, c2 untouched
            assertThat(Counter.CREATION_REPLAYS).doesNotContainKey("c1");
            assertThat(service.liveObserved).containsEntry("c1", 3).containsEntry("c2", 0);
            assertThat(reload("c2").count()).isEqualTo(0);

            // then -- THE GAP: the restored instance IS the stored snapshot's payload, so the in-place evolution
            // rewrote the snapshot to count 3 at the old position; the reload replays the new Bumped on top of it.
            // The true event-derived count is 3 (three Bumped events); 4 is one event applied twice.
            assertThat(reload("c1").count())
                    .as("expected-gap F-33: reload double-applies the live append; 3 when fixed")
                    .isEqualTo(4);
        }

        @Test
        void liveAppendInTheUnitOfWorkThatStoredTheSnapshotCorruptsItWithoutAnyRestore() {
            // given -- u1 has three events, one above the threshold, and has never been snapshotted
            CounterService service = new CounterService();
            start(service);
            publish(new CounterCreated("u1"));
            publish(new Bumped("u1"));
            publish(new Bumped("u1"));

            // when -- one command: sourcing u1 (three evolutions) stores a snapshot of the live instance at
            // count 2, then the handler's own append evolves that same instance in place to 3
            send(new BumpSelf("u1"));
            // the live path applied the append synchronously, so the handler observed 3
            assertThat(service.liveObserved).containsEntry("u1", 3);

            // then -- THE GAP: the stored snapshot's payload was mutated to 3 after being stored at the position
            // of the second Bumped, so a reload replays the third Bumped on top of it. True count is 3; 4 is one
            // event applied twice, and no snapshot restore was ever involved.
            assertThat(reload("u1").count())
                    .as("expected-gap F-33: reload double-applies the same-unit-of-work append; 3 when fixed")
                    .isEqualTo(4);
        }
    }
}
