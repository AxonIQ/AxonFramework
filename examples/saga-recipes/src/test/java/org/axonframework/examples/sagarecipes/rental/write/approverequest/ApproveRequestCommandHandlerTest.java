package org.axonframework.examples.sagarecipes.rental.write.approverequest;

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

class ApproveRequestCommandHandlerTest {
    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        var entity = EventSourcedEntityModule.autodetected(BikeId.class, ApproveRequestCommandHandler.Bike.class);
        var commands = CommandHandlingModule.named("approve-request-test").commandHandlers()
                .autodetectedCommandHandlingComponent(configuration -> new ApproveRequestCommandHandler());
        fixture = AxonTestFixture.with(EventSourcingConfigurer.create().registerEntity(entity)
                                                              .registerCommandHandlingModule(commands));
    }

    @AfterEach
    void tearDown() {
        fixture.stop();
    }

    @Nested
    class Idempotency {
        @Test
        void givenRequestAlreadyApproved_whenApproveAgain_thenNoEvent() {
            BikeId bikeId = BikeId.random();
            RentalId rentalId = RentalId.random();

            fixture.given().events(new BikeRequested(bikeId, "renter", rentalId),
                                   new BikeInUse(bikeId, "renter", rentalId))
                   .when().command(new ApproveRequest(bikeId, "renter"))
                   .then().success().noEvents();
        }
    }
}
