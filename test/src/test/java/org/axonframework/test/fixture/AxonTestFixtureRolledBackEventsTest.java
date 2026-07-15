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

package org.axonframework.test.fixture;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Validates that the {@link AxonTestFixture} does not report events that were appended in a unit of work that rolled
 * back. When a command handler appends an event and subsequently throws, the event is never persisted or published, so
 * the fixture must not report it either.
 */
class AxonTestFixtureRolledBackEventsTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        EventSourcingConfigurer configurer = EventSourcingConfigurer.create()
                .registerEntity(EventSourcedEntityModule.autodetected(String.class, RentableBike.class));
        fixture = AxonTestFixture.with(configurer);
    }

    @Test
    void eventsAppendedBeforeCommandHandlerExceptionAreNotReported() {
        // given - an existing bike
        fixture.given()
               .events(new RentableBikeWasAdded("bike1"))
               // when - the handler appends ChainWasExchanged and then throws
               .when()
               .command(new ExchangeChainCommand("bike1"))
               // then - the appended event was rolled back, so no events may be reported
               .then()
               .exception(IllegalStateException.class)
               .noEvents();
    }

    @Test
    void eventsAppendedBySuccessfulCommandHandlerAreReported() {
        // given - no prior events
        fixture.given()
               .noPriorActivity()
               // when - the handler appends RentableBikeWasAdded and completes normally
               .when()
               .command(new AddNewBikeCommand("bike1"))
               // then - the committed event is reported
               .then()
               .events(new RentableBikeWasAdded("bike1"));
    }

    public record AddNewBikeCommand(@TargetEntityId String bikeId) {
    }

    public record ExchangeChainCommand(@TargetEntityId String bikeId) {
    }

    public record RentableBikeWasAdded(@EventTag(key = "RentableBike") String bikeId) {
    }

    public record ChainWasExchanged(@EventTag(key = "RentableBike") String bikeId) {
    }

    @EventSourcedEntity(tagKey = "RentableBike")
    public static class RentableBike {

        @SuppressWarnings("unused")
        private String bikeId;

        @EntityCreator
        RentableBike() {
        }

        @CommandHandler
        public static RentableBike on(AddNewBikeCommand command, EventAppender eventAppender) {
            eventAppender.append(new RentableBikeWasAdded(command.bikeId()));
            return new RentableBike();
        }

        @CommandHandler
        public void handle(ExchangeChainCommand command, EventAppender eventAppender) {
            eventAppender.append(new ChainWasExchanged(command.bikeId()));
            throw new IllegalStateException("simulating a business rule violation after appending");
        }

        @EventSourcingHandler
        void on(RentableBikeWasAdded event) {
            this.bikeId = event.bikeId();
        }

        @EventSourcingHandler
        void on(ChainWasExchanged event) {
            this.bikeId = event.bikeId();
        }
    }
}
