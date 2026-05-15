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

import org.axonframework.messaging.eventhandling.DomainEventMessage;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericDomainEventMessage;
import org.axonframework.eventsourcing.eventstore.EventStoreException;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.modelling.command.CommandTargetResolver;
import org.axonframework.test.AxonAssertionError;
import org.axonframework.test.FixtureExecutionException;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.axonframework.test.matchers.Matchers.exactSequenceOf;
import static org.axonframework.test.matchers.Matchers.matches;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating an annotated aggregate instance.
 *
 * @author Allard Buijze
 */
class FixtureAnnotatedTest {

    private static final String AGGREGATE_ID = "aggregateId";

    private FixtureConfiguration<AnnotatedAggregate> fixture;

    @BeforeEach
    void setUp() {
        fixture = new AggregateTestFixture<>(AnnotatedAggregate.class);
    }

    @AfterEach
    void tearDown() {
        if (CurrentUnitOfWork.isStarted()) {
            fail("A unit of work is still running");
        }
    }

    @Test
    @Disabled("TODO #3073 - Revisit Aggregate Test Fixture")
    void nullIdentifierIsRejected() {
        AxonAssertionError error = assertThrows(AxonAssertionError.class, () ->
                fixture.given(new MyEvent(null, 0))
                       .when(new TestCommand("test"))
                       .expectEvents(new MyEvent("test", 1))
                       .expectSuccessfulHandlerExecution()
        );

        assertTrue(
                error.getMessage().contains("IncompatibleAggregateException"),
                "Expected test to fail with IncompatibleAggregateException"
        );
    }

    @Test
    @Disabled("TODO #3068 - Revisit Aggregate Modelling")
    void eventsCarryCorrectTimestamp() {
        fixture.givenCurrentTime(Instant.EPOCH)
               .andGiven(new MyEvent("AggregateId", 1), new MyEvent("AggregateId", 2))
               .andGivenCommands(new TestCommand("AggregateId"))
               .when(new TestCommand("AggregateId"))
               .expectEventsMatching(new TypeSafeMatcher<List<EventMessage>>() {
                   @Override
                   protected boolean matchesSafely(List<EventMessage> item) {
                       return item.stream().allMatch(i -> Instant.EPOCH.equals(i.timestamp()));
                   }

                   @Override
                   public void describeTo(Description description) {
                       description.appendText("list with all events with timestamp at epoch");
                   }
               });
//        assertTrue(
//                fixture.getEventStore()
//                       .readEvents("AggregateId")
//                       .asStream()
//                       .allMatch(i -> Instant.EPOCH.equals(i.timestamp()))
//        );
//        assertEquals(1, fixture.getEventStore().readEvents("AggregateId")
//                               .asStream()
//                               .map(EventMessage::timestamp)
//                               .distinct()
//                               .count());
    }

    @Test
    void clockStandsStillDuringExecution() {
        fixture.given(new MyEvent("AggregateId", 1), new MyEvent("AggregateId", 2))
               .when(new TestCommand("AggregateId"));

//        assertEquals(1, fixture.getEventStore().readEvents("AggregateId")
//                               .asStream()
//                               .map(EventMessage::timestamp)
//                               .distinct()
//                               .count());
    }

    @Test
    @Disabled("TODO #3073 - Revisit Aggregate Test Fixture")
    void aggregateCommandHandlersOverwrittenByCustomHandlers() {
        final AtomicBoolean invoked = new AtomicBoolean(false);
        fixture.registerCommandHandler(CreateAggregateCommand.class, (commandMessage, ctx) -> {
            invoked.set(true);
            return null;
        });

        fixture.given().when(new CreateAggregateCommand()).expectEvents();
        assertTrue(invoked.get(), "");
    }

    @Test
    void aggregateIdentifier_ServerGeneratedIdentifier() {
        fixture.registerInjectableResource(new HardToCreateResource());
        fixture.given()
               .when(new CreateAggregateCommand());
    }

    @Test
    @Disabled("TODO #3068 - Revisit Aggregate Modelling")
    void unavailableResourcesCausesFailure() {
        TestExecutor<AnnotatedAggregate> given = fixture.given();
        assertThrows(FixtureExecutionException.class, () -> given.when(new CreateAggregateCommand()));
    }

    @Test
    @Disabled("TODO #3068 - Revisit Aggregate Modelling")
    void aggregateIdentifier_IdentifierAutomaticallyDeducted() {
        fixture.given(new MyEvent("AggregateId", 1), new MyEvent("AggregateId", 2))
               .when(new TestCommand("AggregateId"))
               .expectEvents(new MyEvent("AggregateId", 3))
               .expectState(Assertions::assertNotNull);

//        DomainEventStream events = fixture.getEventStore().readEvents("AggregateId");
//        for (int t = 0; t < 3; t++) {
//            assertTrue(events.hasNext());
//            DomainEventMessage next = events.next();
//            assertEquals("AggregateId", next.getAggregateIdentifier());
//            assertEquals(t, next.getSequenceNumber());
//        }
    }

    @Test
    @Disabled("TODO #3068 - Revisit Aggregate Modelling")
    void fixtureGivenCommands_ResourcesNotAvailable() {
        assertThrows(
                FixtureExecutionException.class, () -> fixture.givenCommands(new CreateAggregateCommand(AGGREGATE_ID))
        );
    }

    @Test
    @Disabled("TODO #3068 - Revisit Aggregate Modelling")
    void fixtureGivenCommands_ResourcesAvailable() {
        fixture.registerInjectableResource(new HardToCreateResource());
        fixture.givenCommands(new CreateAggregateCommand(AGGREGATE_ID),
                              new TestCommand(AGGREGATE_ID),
                              new TestCommand(AGGREGATE_ID),
                              new TestCommand(AGGREGATE_ID))
               .when(new TestCommand(AGGREGATE_ID))
               .expectEvents(new MyEvent(AGGREGATE_ID, 4));
    }

    @Test
    @Disabled("TODO #3068 - Revisit Aggregate Modelling")
    void aggregateIdentifier_CustomTargetResolver() {
        CommandTargetResolver mockCommandTargetResolver = mock(CommandTargetResolver.class);
        when(mockCommandTargetResolver.resolveTarget(any()))
                .thenReturn(AGGREGATE_ID);

        fixture.registerCommandTargetResolver(mockCommandTargetResolver);
        fixture.registerInjectableResource(new HardToCreateResource());
        fixture.givenCommands(new CreateAggregateCommand(AGGREGATE_ID))
               .when(new TestCommand(AGGREGATE_ID))
               .expectEvents(new MyEvent(AGGREGATE_ID, 1));

        verify(mockCommandTargetResolver).resolveTarget(any());
    }

    @Test
    void aggregate_InjectCustomResourceAfterCreatingAnnotatedHandler() {
        // a 'when' will cause command handlers to be registered.
        fixture.registerInjectableResource(new HardToCreateResource());
        fixture.given()
               .when(new CreateAggregateCommand("AggregateId"));

        assertThrows(FixtureExecutionException.class, () -> fixture.registerInjectableResource("I am injectable"));
    }

    @Test
    void fixtureGeneratesExceptionOnWrongEvents_DifferentAggregateIdentifiers() {
        DomainEventMessage testEventOne = new GenericDomainEventMessage(
                "test", UUID.randomUUID().toString(), 0, new MessageType("event"),
                new StubDomainEvent()
        );
        DomainEventMessage testEventTwo = new GenericDomainEventMessage(
                "test", UUID.randomUUID().toString(), 0, new MessageType("event"),
                new StubDomainEvent()
        );

//        assertThrows(EventStoreException.class, () -> fixture.getEventStore().publish(testEventOne, testEventTwo));
    }

    @Test
    void fixtureGeneratesExceptionOnWrongEvents_WrongSequence() {
        String identifier = UUID.randomUUID().toString();
        DomainEventMessage testEventOne = new GenericDomainEventMessage(
                "test", identifier, 0, new MessageType("event"), new StubDomainEvent()
        );
        DomainEventMessage testEventTwo = new GenericDomainEventMessage(
                "test", identifier, 2, new MessageType("event"), new StubDomainEvent()
        );

//        assertThrows(EventStoreException.class, () -> fixture.getEventStore().publish(testEventOne, testEventTwo));
    }

    @Test
    @Disabled("TODO #3068 - Revisit Aggregate Modelling")
    void fixture_AggregateDeleted() {
        fixture.given(new MyEvent(AGGREGATE_ID, 5))
               .when(new DeleteCommand(AGGREGATE_ID, false))
               .expectEvents(new MyAggregateDeletedEvent(false));
    }

    @Test
    @Disabled("TODO #3073 - Revisit Aggregate Test Fixture")
    void fixtureDetectsStateChangeOutsideOfHandler_AggregateDeleted() {
        TestExecutor<AnnotatedAggregate> exec = fixture.given(new MyEvent(AGGREGATE_ID, 5));
        AssertionError error =
                assertThrows(AssertionError.class, () -> exec.when(new DeleteCommand(AGGREGATE_ID, true)));
        assertTrue(error.getMessage().contains("considered deleted"), "Wrong message: " + error.getMessage());
    }

    @Test
    @Disabled("TODO #3068 - Revisit Aggregate Modelling")
    void andGiven() {
        fixture.registerInjectableResource(new HardToCreateResource());
        fixture.givenCommands(new CreateAggregateCommand(AGGREGATE_ID))
               .andGiven(new MyEvent(AGGREGATE_ID, 1))
               .when(new TestCommand(AGGREGATE_ID))
               .expectEvents(new MyEvent(AGGREGATE_ID, 2));
    }

    @Test
    @Disabled("TODO #3068 - Revisit Aggregate Modelling")
    void andGivenCommands() {
        fixture.given(new MyEvent(AGGREGATE_ID, 1))
               .andGivenCommands(new TestCommand(AGGREGATE_ID))
               .when(new TestCommand(AGGREGATE_ID))
               .expectEvents(new MyEvent(AGGREGATE_ID, 3));
    }

    @Test
    @Disabled("TODO #3068 - Revisit Aggregate Modelling")
    void multipleAndGivenCommands() {
        fixture.given(new MyEvent(AGGREGATE_ID, 1))
               .andGivenCommands(new TestCommand(AGGREGATE_ID))
               .andGivenCommands(new TestCommand(AGGREGATE_ID))
               .when(new TestCommand(AGGREGATE_ID))
               .expectEvents(new MyEvent(AGGREGATE_ID, 4));
    }

    @Test
    @Disabled("TODO #3068 - Revisit Aggregate Modelling")
    void givenNoPriorActivityAndPublishingNoEvents() {
        fixture.registerInjectableResource(new HardToCreateResource());
        fixture.givenNoPriorActivity()
               .when(new CreateAggregateCommand(AGGREGATE_ID, CreateAggregateCommand.SHOULD_NOT_PUBLISH))
               .expectNoEvents()
               .expectSuccessfulHandlerExecution();
    }

    @Test
    @Disabled("TODO #3068 - Revisit Aggregate Modelling")
    void givenFollowedUpByAndGivenCurrentTimeWorksAsExcepted() {
        String testAggregateId = "1337";

        fixture.given(new MyEvent(testAggregateId, 1), new MyEvent(testAggregateId, 2))
               .andGivenCurrentTime(Instant.EPOCH)
               .when(new TestCommand(testAggregateId))
               .expectEventsMatching(exactSequenceOf(matches(
                       eventMessage -> eventMessage.timestamp() == Instant.EPOCH
                               && MyEvent.class.equals(eventMessage.payloadType())
               )));

//        assertEquals(3, fixture.getEventStore()
//                               .readEvents(testAggregateId)
//                               .asStream().count());
    }

    @Test
    @Disabled("TODO #3068 - Revisit Aggregate Modelling")
    void fixtureThrowsEventStoreExceptionWhenHandlingAggregateConstructingCommandWhileThereAreGivenEvents() {
        fixture.registerInjectableResource(new HardToCreateResource());
        fixture.given(new MyEvent(AGGREGATE_ID, 0))
               .when(new CreateAggregateCommand(AGGREGATE_ID))
               .expectException(EventStoreException.class)
               .expectNoEvents();
    }

    @Test
    @Disabled("TODO #3068 - Revisit Aggregate Modelling")
    void fixtureThrowsEventStoreExceptionWhenHandlingAggregateConstructingCommandWhileThereAreGivenCommand() {
        fixture.registerInjectableResource(new HardToCreateResource());
        fixture.givenCommands(new CreateAggregateCommand(AGGREGATE_ID))
               .when(new CreateAggregateCommand(AGGREGATE_ID))
               .expectException(EventStoreException.class)
               .expectNoEvents();
    }

    private static class StubDomainEvent {

        public StubDomainEvent() {
        }
    }
}
