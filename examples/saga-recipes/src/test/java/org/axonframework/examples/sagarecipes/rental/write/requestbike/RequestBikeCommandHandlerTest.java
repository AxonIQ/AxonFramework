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

package org.axonframework.examples.sagarecipes.rental.write.requestbike;

import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.event.BikeInUse;
import org.axonframework.examples.sagarecipes.rental.event.BikeRegistered;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.rental.event.BikeReturned;
import org.axonframework.examples.sagarecipes.rental.event.RequestRejected;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

@AxonSpringBootTest
class RequestBikeCommandHandlerTest {

    @Autowired
    private AxonTestFixture fixture;

    /**
     * Unique per test. The renter is a tag, so reusing a fixed name would let one test's events satisfy or violate
     * another test's rules: the Spring context, and with it the event store, is shared across the whole run.
     */
    private final String renter = "renter-" + UUID.randomUUID();
    private final String otherRenter = "renter-" + UUID.randomUUID();

    @Test
    void givenAvailableBike_whenRequestBike_thenBikeRequested() {
        // given
        var bikeId = BikeId.random();
        var rentalId = RentalId.random();

        // when / then
        fixture.given()
               .events(new BikeRegistered(bikeId, "city", "Vilnius"))
               .when()
               .command(new RequestBike(bikeId, renter, rentalId))
               .then()
               .success()
               .events(new BikeRequested(bikeId, renter, rentalId));
    }

    @Test
    void givenUnregisteredBike_whenRequestBike_thenRejected() {
        // given no BikeRegistered at all
        var bikeId = BikeId.random();

        // when / then
        fixture.given()
               .noPriorActivity()
               .when()
               .command(new RequestBike(bikeId, renter, RentalId.random()))
               .then()
               .exception(IllegalStateException.class, "Bike is not registered");
    }

    @Nested
    class Idempotency {

        @Test
        void givenSameRequestAlreadyHandled_whenRequestBikeAgain_thenNoEventsAndSuccess() {
            // given the exact same rental was already requested
            var bikeId = BikeId.random();
            var rentalId = RentalId.random();

            // when / then a redelivered command must not reserve the bike a second time
            fixture.given()
                   .events(new BikeRegistered(bikeId, "city", "Vilnius"),
                           new BikeRequested(bikeId, renter, rentalId))
                   .when()
                   .command(new RequestBike(bikeId, renter, rentalId))
                   .then()
                   .success()
                   .noEvents();
        }
    }

    /**
     * The consistency boundary of this slice spans two tags at once: the bike and the renter. These cases pin the
     * rule that neither tag alone could enforce.
     */
    @Nested
    class OneBikePerRenter {

        @Test
        void givenRenterHoldsAnotherBike_whenRequestBike_thenRejected() {
            // given the renter already reserved a different bike
            var bikeA = BikeId.random();
            var bikeB = BikeId.random();

            // when / then
            fixture.given()
                   .events(new BikeRegistered(bikeA, "city", "Vilnius"),
                           new BikeRegistered(bikeB, "city", "Vilnius"),
                           new BikeRequested(bikeA, renter, RentalId.random()))
                   .when()
                   .command(new RequestBike(bikeB, renter, RentalId.random()))
                   .then()
                   .exception(IllegalStateException.class, "Renter already holds a bike");
        }

        @Test
        void givenRenterReturnedPreviousBike_whenRequestBike_thenAccepted() {
            // given the renter's previous rental completed
            var bikeA = BikeId.random();
            var bikeB = BikeId.random();
            var previousRental = RentalId.random();
            var newRental = RentalId.random();

            // when / then
            fixture.given()
                   .events(new BikeRegistered(bikeA, "city", "Vilnius"),
                           new BikeRegistered(bikeB, "city", "Vilnius"),
                           new BikeRequested(bikeA, renter, previousRental),
                           new BikeInUse(bikeA, renter, previousRental),
                           new BikeReturned(bikeA, renter, previousRental, "Vilnius"))
                   .when()
                   .command(new RequestBike(bikeB, renter, newRental))
                   .then()
                   .success()
                   .events(new BikeRequested(bikeB, renter, newRental));
        }

        @Test
        void givenPreviousRequestRejected_whenRequestBike_thenAccepted() {
            // given the renter's previous request was turned down
            var bikeA = BikeId.random();
            var bikeB = BikeId.random();
            var rejectedRental = RentalId.random();
            var newRental = RentalId.random();

            // when / then
            fixture.given()
                   .events(new BikeRegistered(bikeA, "city", "Vilnius"),
                           new BikeRegistered(bikeB, "city", "Vilnius"),
                           new BikeRequested(bikeA, renter, rejectedRental),
                           new RequestRejected(bikeA, renter, rejectedRental))
                   .when()
                   .command(new RequestBike(bikeB, renter, newRental))
                   .then()
                   .success()
                   .events(new BikeRequested(bikeB, renter, newRental));
        }

        @Test
        void givenAnotherRenterHoldsTheBike_whenRequestBike_thenRejected() {
            // given somebody else already reserved this bike
            var bikeId = BikeId.random();

            // when / then
            fixture.given()
                   .events(new BikeRegistered(bikeId, "city", "Vilnius"),
                           new BikeRequested(bikeId, otherRenter, RentalId.random()))
                   .when()
                   .command(new RequestBike(bikeId, renter, RentalId.random()))
                   .then()
                   .exception(IllegalStateException.class, "Bike is already rented");
        }
    }
}
