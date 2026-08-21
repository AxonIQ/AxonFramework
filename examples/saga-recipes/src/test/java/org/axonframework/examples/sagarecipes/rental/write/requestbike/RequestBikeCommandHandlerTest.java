package org.axonframework.examples.sagarecipes.rental.write.requestbike;

import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.event.BikeInUse;
import org.axonframework.examples.sagarecipes.rental.event.BikeRegistered;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.rental.event.BikeReturned;
import org.axonframework.examples.sagarecipes.rental.event.RequestRejected;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RequestBikeCommandHandlerTest {
    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        var entity = EventSourcedEntityModule.autodetected(
                RentalRequestId.class, RequestBikeCommandHandler.State.class);
        var commands = CommandHandlingModule.named("request-bike-test").commandHandlers()
                .autodetectedCommandHandlingComponent(configuration -> new RequestBikeCommandHandler());
        fixture = AxonTestFixture.with(EventSourcingConfigurer.create().registerEntity(entity)
                                                              .registerCommandHandlingModule(commands));
    }

    @AfterEach
    void tearDown() {
        fixture.stop();
    }

    @Nested
    class OneBikePerRenter {
        @Test
        void givenRenterHoldsAnotherBike_whenRequestBike_thenRejected() {
            BikeId bikeA = BikeId.random();
            BikeId bikeB = BikeId.random();

            fixture.given().events(new BikeRegistered(bikeA, "city", "Vilnius"),
                                   new BikeRegistered(bikeB, "city", "Vilnius"),
                                   new BikeRequested(bikeA, "renter", RentalId.random()))
                   .when().command(new RequestBike(bikeB, "renter", RentalId.random()))
                   .then().exception(IllegalStateException.class, "Renter already holds a bike");
        }

        @Test
        void givenRenterReturnedPreviousBike_whenRequestBike_thenAccepted() {
            BikeId bikeA = BikeId.random();
            BikeId bikeB = BikeId.random();
            RentalId rentalA = RentalId.random();
            RentalId rentalB = RentalId.random();

            fixture.given().events(new BikeRegistered(bikeA, "city", "Vilnius"),
                                   new BikeRegistered(bikeB, "city", "Vilnius"),
                                   new BikeRequested(bikeA, "renter", rentalA),
                                   new BikeInUse(bikeA, "renter", rentalA),
                                   new BikeReturned(bikeA, rentalA, "renter", "Vilnius"))
                   .when().command(new RequestBike(bikeB, "renter", rentalB))
                   .then().success().events(new BikeRequested(bikeB, "renter", rentalB));
        }

        @Test
        void givenPreviousRequestRejected_whenRequestBike_thenAccepted() {
            BikeId bikeA = BikeId.random();
            BikeId bikeB = BikeId.random();
            RentalId rentalA = RentalId.random();
            RentalId rentalB = RentalId.random();

            fixture.given().events(new BikeRegistered(bikeA, "city", "Vilnius"),
                                   new BikeRegistered(bikeB, "city", "Vilnius"),
                                   new BikeRequested(bikeA, "renter", rentalA),
                                   new RequestRejected(bikeA, rentalA, "renter"))
                   .when().command(new RequestBike(bikeB, "renter", rentalB))
                   .then().success().events(new BikeRequested(bikeB, "renter", rentalB));
        }

        @Test
        void givenAnotherRenterHoldsTheBike_whenRequestBike_thenRejected() {
            BikeId bike = BikeId.random();

            fixture.given().events(new BikeRegistered(bike, "city", "Vilnius"),
                                   new BikeRequested(bike, "first-renter", RentalId.random()))
                   .when().command(new RequestBike(bike, "second-renter", RentalId.random()))
                   .then().exception(IllegalStateException.class, "Bike is not available");
        }

        @Test
        void givenSameRentalRequestedAgain_whenRequestBike_thenNoEvent() {
            BikeId bike = BikeId.random();
            RentalId rental = RentalId.random();

            fixture.given().events(new BikeRegistered(bike, "city", "Vilnius"),
                                   new BikeRequested(bike, "renter", rental))
                   .when().command(new RequestBike(bike, "renter", rental))
                   .then().success().noEvents();
        }
    }
}
