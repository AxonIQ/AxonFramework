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

package org.axonframework.deadline;

import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.ScopeAware;
import org.axonframework.messaging.core.ScopeAwareProvider;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.command.AggregateScopeDescriptor;
import org.axonframework.modelling.saga.SagaScopeDescriptor;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Validates that a deadline carries the scope it was scheduled against all the way to expiry, so an aggregate-scoped
 * deadline is routed to an aggregate and a saga-scoped one to a saga.
 * <p>
 * A {@link DeadlineManager} stores an {@link AggregateScopeDescriptor} or a {@link SagaScopeDescriptor} alongside the
 * schedule and resolves the owning {@link ScopeAware} component through a {@link ScopeAwareProvider} once the deadline
 * fires. These tests pin that the two scope flavors stay distinguishable, and that a component declining a descriptor
 * through {@link ScopeAware#canResolve(ScopeDescriptor)} is never handed the message.
 *
 * @author Mateusz Nowak
 */
class DeadlineScopeRoutingTest {

    private static final String DEADLINE_NAME = "scopedDeadline";
    private static final String IDENTIFIER = "target-id";

    private RecordingScopeAware aggregateComponent;
    private RecordingScopeAware sagaComponent;
    private SimpleDeadlineManager deadlineManager;

    @BeforeEach
    void setUp() {
        aggregateComponent = new RecordingScopeAware(AggregateScopeDescriptor.class::isInstance);
        sagaComponent = new RecordingScopeAware(SagaScopeDescriptor.class::isInstance);
        deadlineManager = SimpleDeadlineManager.builder()
                                              .scopeAwareProvider(new BothComponentsProvider(aggregateComponent,
                                                                                             sagaComponent))
                                              .build();
    }

    @AfterEach
    void tearDown() {
        deadlineManager.shutdown();
    }

    @Nested
    class Routing {

        @Test
        void aggregateScopedDeadlineOnlyReachesTheAggregateAwareComponent() {
            // given a deadline scoped to an aggregate
            ScopeDescriptor scope = new AggregateScopeDescriptor("MyAggregate", IDENTIFIER);

            // when it expires
            deadlineManager.schedule(Duration.ofMillis(50), DEADLINE_NAME, "payload", scope);

            // then the aggregate-aware component handles it, carrying the aggregate scope
            await().atMost(Duration.ofSeconds(5)).until(() -> !aggregateComponent.received.isEmpty());
            assertThat(aggregateComponent.received).singleElement()
                                                   .isInstanceOf(AggregateScopeDescriptor.class)
                                                   .isEqualTo(scope);
            assertThat(sagaComponent.received).isEmpty();
        }

        @Test
        void sagaScopedDeadlineOnlyReachesTheSagaAwareComponent() {
            // given a deadline scoped to a saga
            ScopeDescriptor scope = new SagaScopeDescriptor("MySaga", IDENTIFIER);

            // when it expires
            deadlineManager.schedule(Duration.ofMillis(50), DEADLINE_NAME, "payload", scope);

            // then the saga-aware component handles it, carrying the saga scope
            await().atMost(Duration.ofSeconds(5)).until(() -> !sagaComponent.received.isEmpty());
            assertThat(sagaComponent.received).singleElement()
                                              .isInstanceOf(SagaScopeDescriptor.class)
                                              .isEqualTo(scope);
            assertThat(aggregateComponent.received).isEmpty();
        }

        @Test
        void deadlinesScheduledForBothScopesAreEachRoutedToTheirOwnComponent() {
            // given the same type and identifier used for an aggregate and for a saga
            ScopeDescriptor aggregateScope = new AggregateScopeDescriptor("Shared", IDENTIFIER);
            ScopeDescriptor sagaScope = new SagaScopeDescriptor("Shared", IDENTIFIER);

            // when both deadlines expire
            deadlineManager.schedule(Duration.ofMillis(50), DEADLINE_NAME, "payload", aggregateScope);
            deadlineManager.schedule(Duration.ofMillis(50), DEADLINE_NAME, "payload", sagaScope);

            // then neither component sees the other's deadline, despite the matching type and identifier
            await().atMost(Duration.ofSeconds(5))
                   .until(() -> !aggregateComponent.received.isEmpty() && !sagaComponent.received.isEmpty());
            assertThat(aggregateComponent.received).containsExactly(aggregateScope);
            assertThat(sagaComponent.received).containsExactly(sagaScope);
        }

        @Test
        void deadlineIsNotDeliveredWhenNoComponentResolvesItsScope() {
            // given a scope neither component claims
            ScopeDescriptor unclaimedScope = () -> "an unclaimed scope";

            // when the deadline expires
            deadlineManager.schedule(Duration.ofMillis(50), DEADLINE_NAME, "payload", unclaimedScope);

            // then it is silently dropped rather than handed to an unrelated component
            await().pollDelay(Duration.ofMillis(500))
                   .atMost(Duration.ofSeconds(5))
                   .untilAsserted(() -> {
                       assertThat(aggregateComponent.received).isEmpty();
                       assertThat(sagaComponent.received).isEmpty();
                   });
        }
    }

    @Nested
    class Distinguishing {

        @Test
        void aggregateAndSagaScopesNeverMatchEachOtherDespiteEqualTypeAndIdentifier() {
            // given
            AggregateScopeDescriptor aggregateScope = new AggregateScopeDescriptor("Shared", IDENTIFIER);
            SagaScopeDescriptor sagaScope = new SagaScopeDescriptor("Shared", IDENTIFIER);

            // then
            assertThat(aggregateScope).isNotEqualTo(sagaScope);
            assertThat(sagaScope).isNotEqualTo(aggregateScope);
        }

        @Test
        void scopeDescriptionNamesTheFlavorItDescribes() {
            // given
            AggregateScopeDescriptor aggregateScope = new AggregateScopeDescriptor("MyAggregate", IDENTIFIER);
            SagaScopeDescriptor sagaScope = new SagaScopeDescriptor("MySaga", IDENTIFIER);

            // then
            assertThat(aggregateScope.scopeDescription())
                    .isEqualTo("AggregateScopeDescriptor for type [MyAggregate] and identifier [target-id]");
            assertThat(sagaScope.scopeDescription())
                    .isEqualTo("SagaScopeDescriptor for type [MySaga] and identifier [target-id]");
        }

        @Test
        void aggregateScopeResolvesALazilySuppliedIdentifier() {
            // given an identifier that is not known yet when the scope is created
            AggregateScopeDescriptor testSubject =
                    new AggregateScopeDescriptor("MyAggregate", () -> IDENTIFIER);

            // then it is resolved on first access, and matches an eagerly built descriptor
            assertThat(testSubject.getIdentifier()).isEqualTo(IDENTIFIER);
            assertThat(testSubject).isEqualTo(new AggregateScopeDescriptor("MyAggregate", IDENTIFIER));
        }
    }

    /**
     * Offers both components to the {@link DeadlineManager}, leaving the choice between them to each component's
     * {@link ScopeAware#canResolve(ScopeDescriptor)}, exactly as the configuration-backed provider does.
     */
    private record BothComponentsProvider(ScopeAware aggregateComponent, ScopeAware sagaComponent)
            implements ScopeAwareProvider {

        @Override
        public Stream<ScopeAware> provideScopeAwareStream(ScopeDescriptor scopeDescriptor) {
            return Stream.of(aggregateComponent, sagaComponent);
        }
    }

    /**
     * A {@link ScopeAware} component that claims only the scopes matching its {@code resolves} predicate, and records
     * every descriptor it is actually sent a message for.
     */
    private static final class RecordingScopeAware implements ScopeAware {

        private final Predicate<ScopeDescriptor> resolves;
        private final List<ScopeDescriptor> received = new CopyOnWriteArrayList<>();

        private RecordingScopeAware(Predicate<ScopeDescriptor> resolves) {
            this.resolves = resolves;
        }

        @Override
        public void send(Message message, ProcessingContext context, ScopeDescriptor scopeDescription) {
            received.add(scopeDescription);
        }

        @Override
        public boolean canResolve(ScopeDescriptor scopeDescription) {
            return resolves.test(scopeDescription);
        }
    }
}
