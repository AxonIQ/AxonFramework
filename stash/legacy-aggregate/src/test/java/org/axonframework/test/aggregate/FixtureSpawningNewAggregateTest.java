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

package org.axonframework.test.aggregate;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.modelling.command.AggregateCreationPolicy;
import org.axonframework.modelling.command.AggregateIdentifier;
import org.axonframework.modelling.command.CreationPolicy;
import org.axonframework.modelling.command.Repository;
import org.axonframework.modelling.command.RepositoryProvider;
import org.axonframework.modelling.command.inspection.AggregateModel;
import org.axonframework.modelling.command.inspection.AnnotatedAggregateMetaModelFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.mockito.junit.jupiter.*;

import java.util.Objects;

import static org.axonframework.modelling.command.AggregateLifecycle.apply;
import static org.axonframework.modelling.command.AggregateLifecycle.createNew;
import static org.mockito.Mockito.*;

/**
 * Fixture tests for spawning new aggregate functionality.
 *
 * @author Milan Savic
 */
@ExtendWith(MockitoExtension.class)
class FixtureSpawningNewAggregateTest {

    private FixtureConfiguration<Aggregate1> fixture;

    @BeforeEach
    void setUp() {
        fixture = new AggregateTestFixture<>(Aggregate1.class);
    }

    @Test
    @Disabled("TODO #3195 - Migration Module")
    void fixtureWithoutRepositoryProviderInjected() {
        fixture.givenNoPriorActivity()
               .when(new CreateAggregate1Command("id", "aggregate2Id", false))
               .expectEvents(new Aggregate2CreatedEvent("aggregate2Id"), new Aggregate1CreatedEvent("id"))
               .expectSuccessfulHandlerExecution();
    }

    @Test
    @Disabled("TODO #3195 - Migration Module")
    void fixtureShouldResolveResourcesInSpawnedAggregates() {
        fixture.registerInjectableResource(new Resource());
        fixture.givenNoPriorActivity()
               .when(new CreateAggregate1Command("id", "aggregate2Id", true))
               .expectEvents(new Aggregate2CreatedWithResourceEvent("aggregate2Id"), new Aggregate1CreatedEvent("id"))
               .expectSuccessfulHandlerExecution();
    }

    @SuppressWarnings("unchecked")
    @Test
    @Disabled("TODO #3195 - Migration Module")
    void fixtureWithRepositoryProviderInjected() throws Exception {
        RepositoryProvider repositoryProvider = mock(RepositoryProvider.class);
        Repository<Aggregate2> aggregate2Repository = mock(Repository.class);
        AggregateModel<Aggregate2> aggregate2Model = AnnotatedAggregateMetaModelFactory
                .inspectAggregate(Aggregate2.class);

//        when(aggregate2Repository.newInstance(any())).thenAnswer(invocation ->
//                                                                         EventSourcedAggregate
//                                                                                 .initialize((Callable<Aggregate2>) invocation
//                                                                                                     .getArguments()[0],
//                                                                                             aggregate2Model,
//                                                                                             fixture.getEventStore(),
//                                                                                             repositoryProvider));

        when(repositoryProvider.repositoryFor(Aggregate2.class)).thenReturn(aggregate2Repository);

        fixture.registerRepositoryProvider(repositoryProvider)
               .givenNoPriorActivity()
               .when(new CreateAggregate1Command("id", "aggregate2Id", false))
               .expectEvents(new Aggregate2CreatedEvent("aggregate2Id"), new Aggregate1CreatedEvent("id"))
               .expectSuccessfulHandlerExecution();

        verify(repositoryProvider).repositoryFor(Aggregate2.class);
        verify(aggregate2Repository).newInstance(any());
    }

    private static class CreateAggregate1Command {

        private final String id;
        private final String aggregate2Id;
        private final boolean requireResource;

        private CreateAggregate1Command(String id, String aggregate2Id, boolean requireResource) {
            this.id = id;
            this.aggregate2Id = aggregate2Id;
            this.requireResource = requireResource;
        }

        public String getId() {
            return id;
        }

        String getAggregate2Id() {
            return aggregate2Id;
        }

        public boolean isRequireResource() {
            return requireResource;
        }
    }

    private static class Aggregate1CreatedEvent {

        private final String id;

        private Aggregate1CreatedEvent(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Aggregate1CreatedEvent that = (Aggregate1CreatedEvent) o;
            return Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    private static class Aggregate2CreatedEvent {

        private final String id;

        private Aggregate2CreatedEvent(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Aggregate2CreatedEvent that = (Aggregate2CreatedEvent) o;
            return Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    private static class Aggregate2CreatedWithResourceEvent {

        private final String id;

        private Aggregate2CreatedWithResourceEvent(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Aggregate2CreatedWithResourceEvent that = (Aggregate2CreatedWithResourceEvent) o;
            return Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    @SuppressWarnings("unused")
    private static class Aggregate1 {

        @AggregateIdentifier
        private String id;

        public Aggregate1() {
        }

        @CommandHandler
        @CreationPolicy(AggregateCreationPolicy.ALWAYS)
        public void handle(CreateAggregate1Command command) throws Exception {
            apply(new Aggregate1CreatedEvent(command.getId()));
            createNew(Aggregate2.class, () -> new Aggregate2(command.getAggregate2Id(), command.isRequireResource()));
        }

        @EventSourcingHandler
        public void on(Aggregate1CreatedEvent event) {
            this.id = event.getId();
        }
    }

    @SuppressWarnings("unused")
    private static class Aggregate2 {

        @AggregateIdentifier
        private String id;
        private String state;

        public Aggregate2() {
        }

        Aggregate2(String id, boolean requireResource) {
            if (requireResource) {
                apply(new Aggregate2CreatedWithResourceEvent(id));
            } else {
                apply(new Aggregate2CreatedEvent(id));
            }
        }

        @EventSourcingHandler
        public void on(Aggregate2CreatedEvent event) {
            this.id = event.getId();
        }

        @EventSourcingHandler
        public void on(Aggregate2CreatedWithResourceEvent event, Resource resource) {
            this.id = event.getId();
        }
    }

    private static class Resource {

    }
}
