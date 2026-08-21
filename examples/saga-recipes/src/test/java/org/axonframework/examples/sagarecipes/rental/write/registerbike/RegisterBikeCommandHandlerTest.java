package org.axonframework.examples.sagarecipes.rental.write.registerbike;

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

class RegisterBikeCommandHandlerTest {
    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        var entity = EventSourcedEntityModule.autodetected(BikeId.class, RegisterBikeCommandHandler.Bike.class);
        var commands = CommandHandlingModule.named("register-bike-test").commandHandlers()
                .autodetectedCommandHandlingComponent(configuration -> new RegisterBikeCommandHandler());
        fixture = AxonTestFixture.with(EventSourcingConfigurer.create().registerEntity(entity)
                                                              .registerCommandHandlingModule(commands));
    }

    @AfterEach void tearDown() { fixture.stop(); }

    @Test
    void givenNoBike_whenRegisterBike_thenBikeRegistered() {
        BikeId bikeId = BikeId.random();

        fixture.given().noPriorActivity()
               .when().command(new RegisterBike(bikeId, "city", "Vilnius"))
               .then().success().events(new BikeRegistered(bikeId, "city", "Vilnius"));
    }

    @Nested
    class Idempotency {
        @Test
        void givenBikeRegistered_whenRegisterAgain_thenNoEvent() {
            BikeId bikeId = BikeId.random();
            fixture.given().events(new BikeRegistered(bikeId, "city", "Vilnius"))
                   .when().command(new RegisterBike(bikeId, "city", "Vilnius"))
                   .then().success().noEvents();
        }
    }
}
