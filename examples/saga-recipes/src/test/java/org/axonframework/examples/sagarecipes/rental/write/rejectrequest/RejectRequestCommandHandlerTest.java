package org.axonframework.examples.sagarecipes.rental.write.rejectrequest;

import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.event.BikeInUse;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RejectRequestCommandHandlerTest {
    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        var entity = EventSourcedEntityModule.autodetected(BikeId.class, RejectRequestCommandHandler.Bike.class);
        var commands = CommandHandlingModule.named("reject-request-test").commandHandlers()
                .autodetectedCommandHandlingComponent(configuration -> new RejectRequestCommandHandler());
        fixture = AxonTestFixture.with(EventSourcingConfigurer.create().registerEntity(entity)
                                                              .registerCommandHandlingModule(commands));
    }

    @AfterEach void tearDown() { fixture.stop(); }

    @Nested
    class Idempotency {
        @Test
        void givenRequestApproved_whenReject_thenNoEvent() {
            BikeId bikeId = BikeId.random();
            RentalId rentalId = RentalId.random();
            fixture.given().events(new BikeRequested(bikeId, "renter", rentalId),
                                   new BikeInUse(bikeId, "renter", rentalId))
                   .when().command(new RejectRequest(bikeId, "renter"))
                   .then().success().noEvents();
        }
    }
}
