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
import org.axonframework.messaging.eventhandling.GenericDomainEventMessage;
import org.axonframework.messaging.eventsourcing.AggregateFactory;
import org.axonframework.messaging.eventsourcing.IncompatibleAggregateException;
import org.axonframework.eventsourcing.eventstore.EventStoreException;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.test.FixtureExecutionException;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * @author Allard Buijze
 */
class FixtureGenericTest {

    private FixtureConfiguration<StandardAggregate> fixture;
    private AggregateFactory<StandardAggregate> mockAggregateFactory;

    @BeforeEach
    void setUp() {
        fixture = new AggregateTestFixture<>(StandardAggregate.class);
        fixture.setReportIllegalStateChange(false);
        mockAggregateFactory = mock(AggregateFactory.class);
        when(mockAggregateFactory.getAggregateType()).thenReturn(StandardAggregate.class);
        when(mockAggregateFactory.createAggregateRoot(isA(String.class), isA(DomainEventMessage.class)))
                .thenReturn(new StandardAggregate("id1"));
    }

    @AfterEach
    void tearDown() {
        while (CurrentUnitOfWork.isStarted()) {
            fail("Test failed to close Unit of Work!!");
            CurrentUnitOfWork.get().rollback();
        }
    }

    @Test
    @Disabled("TODO #3073 - Revisit Aggregate Test Fixture")
    void configureCustomAggregateFactory() {
        fixture.registerAggregateFactory(mockAggregateFactory);
        fixture.registerAnnotatedCommandHandler(new MyCommandHandler(fixture.getRepository(), fixture.getEventBus()));

        fixture.given(new MyEvent("id1", 1))
               .when(new TestCommand("id1"));

        verify(mockAggregateFactory).createAggregateRoot(eq("id1"), isA(DomainEventMessage.class));
    }

    @Test
    void configurationOfRequiredCustomAggregateFactoryNotProvided_FailureOnGiven() {
        assertThrows(IncompatibleAggregateException.class, () -> fixture.given(new MyEvent("id1", 1)));
    }

    @Test
    void configurationOfRequiredCustomAggregateFactoryNotProvided_FailureOnGetRepository() {
        assertThrows(IncompatibleAggregateException.class, fixture::getRepository);
    }

    @Test
    void aggregateIdentifier_ServerGeneratedIdentifier() {
        fixture.registerAggregateFactory(mockAggregateFactory);
        fixture.registerAnnotatedCommandHandler(new MyCommandHandler(fixture.getRepository(), fixture.getEventBus()));
        fixture.givenNoPriorActivity()
               .when(new CreateAggregateCommand());
    }

    @Test
    @Disabled("TODO #3195 - Migration Module")
    void storingExistingAggregateGeneratesException() {
        fixture.registerAggregateFactory(mockAggregateFactory);
        fixture.registerAnnotatedCommandHandler(new MyCommandHandler(fixture.getRepository(), fixture.getEventBus()));
        fixture.given(new MyEvent("aggregateId", 1))
               .when(new CreateAggregateCommand("aggregateId"))
               .expectException(EventStoreException.class)
               .expectNoEvents();
    }

    @Test
    void injectResources_CommandHandlerAlreadyRegistered() {
        fixture.registerAggregateFactory(mockAggregateFactory);
        fixture.registerAnnotatedCommandHandler(new MyCommandHandler(fixture.getRepository(), fixture.getEventBus()));
        assertThrows(FixtureExecutionException.class, () -> fixture.registerInjectableResource("I am injectable"));
    }

    @Test
    @Disabled("TODO #3195 - Migration Module")
    void aggregateIdentifier_IdentifierAutomaticallyDeducted() {
        fixture.registerAggregateFactory(mockAggregateFactory);
        fixture.registerAnnotatedCommandHandler(new MyCommandHandler(fixture.getRepository(), fixture.getEventBus()));
        fixture.given(new MyEvent("AggregateId", 1), new MyEvent("AggregateId", 2))
               .when(new TestCommand("AggregateId"))
               .expectEvents(new MyEvent("AggregateId", 3));

//        DomainEventStream events = fixture.getEventStore().readEvents("AggregateId");
//        for (int t = 0; t < 3; t++) {
//            assertTrue(events.hasNext());
//            DomainEventMessage next = events.next();
//            assertEquals("AggregateId", next.getAggregateIdentifier());
//            assertEquals(t, next.getSequenceNumber());
//        }
    }

    @Test
    @Disabled("TODO #3073 - Revisit Aggregate Test Fixture")
    void readAggregate_WrongIdentifier() {
        fixture.registerAggregateFactory(mockAggregateFactory);
        fixture.registerAnnotatedCommandHandler(new MyCommandHandler(fixture.getRepository(), fixture.getEventBus()));
        TestExecutor<StandardAggregate> exec = fixture.given(new MyEvent("AggregateId", 1));

        AssertionError error = assertThrows(AssertionError.class, () -> exec.when(new TestCommand("OtherIdentifier")));
        assertTrue(error.getMessage().contains("OtherIdentifier"), "Wrong message. Was: " + error.getMessage());
        assertTrue(error.getMessage().contains("AggregateId"), "Wrong message. Was: " + error.getMessage());
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

    private static class StubDomainEvent {

        public StubDomainEvent() {
        }
    }
}
