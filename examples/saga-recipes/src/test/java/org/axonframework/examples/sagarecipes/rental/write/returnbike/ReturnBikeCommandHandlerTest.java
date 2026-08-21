package org.axonframework.examples.sagarecipes.rental.write.returnbike;

import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.event.BikeRegistered;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReturnBikeCommandHandlerTest {
    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        var entity = EventSourcedEntityModule.autodetected(BikeId.class, ReturnBikeCommandHandler.Bike.class);
        var commands = CommandHandlingModule.named("return-bike-test").commandHandlers()
                .autodetectedCommandHandlingComponent(configuration -> new ReturnBikeCommandHandler());
        fixture = AxonTestFixture.with(EventSourcingConfigurer.create().registerEntity(entity)
                                                              .registerCommandHandlingModule(commands));
    }

    @AfterEach void tearDown() { fixture.stop(); }

    @Nested
    class Idempotency {
        @Test
        void givenBikeAvailable_whenReturn_thenNoEvent() {
            BikeId bikeId = BikeId.random();
            fixture.given().events(new BikeRegistered(bikeId, "city", "Vilnius"))
                   .when().command(new ReturnBike(bikeId, "Vilnius"))
                   .then().success().noEvents();
        }
    }
}
