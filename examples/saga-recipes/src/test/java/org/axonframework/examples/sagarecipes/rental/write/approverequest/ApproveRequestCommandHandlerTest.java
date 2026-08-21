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

package org.axonframework.examples.sagarecipes.rental.write.approverequest;

import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.event.BikeInUse;
import org.axonframework.examples.sagarecipes.rental.event.BikeRegistered;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.rental.event.RequestRejected;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

/**
 * The saga sends this command from an event handler, so every one of these cases is a redelivery the process must
 * survive without a second {@code BikeInUse}.
 */
@AxonSpringBootTest
class ApproveRequestCommandHandlerTest {

    @Autowired
    private AxonTestFixture fixture;

    /**
     * Unique per test. The renter is a tag, so reusing a fixed name would let one test's events satisfy or violate
     * another test's rules: the Spring context, and with it the event store, is shared across the whole run.
     */
    private final String renter = "renter-" + UUID.randomUUID();
    private final String otherRenter = "renter-" + UUID.randomUUID();

    @Test
    void givenPendingRequest_whenApproveRequest_thenBikeInUse() {
        // given
        var bikeId = BikeId.random();
        var rentalId = RentalId.random();

        // when / then the rental identifier comes from the stream, since the command does not carry it
        fixture.given()
               .events(new BikeRegistered(bikeId, "city", "Vilnius"),
                       new BikeRequested(bikeId, renter, rentalId))
               .when()
               .command(new ApproveRequest(bikeId, renter))
               .then()
               .success()
               .events(new BikeInUse(bikeId, renter, rentalId));
    }

    @Nested
    class Idempotency {

        @Test
        void givenRequestAlreadyApproved_whenApproveRequestAgain_thenNoEventsAndSuccess() {
            // given
            var bikeId = BikeId.random();
            var rentalId = RentalId.random();

            // when / then
            fixture.given()
                   .events(new BikeRegistered(bikeId, "city", "Vilnius"),
                           new BikeRequested(bikeId, renter, rentalId),
                           new BikeInUse(bikeId, renter, rentalId))
                   .when()
                   .command(new ApproveRequest(bikeId, renter))
                   .then()
                   .success()
                   .noEvents();
        }

        @Test
        void givenRequestAlreadyRejected_whenApproveRequest_thenNoEventsAndSuccess() {
            // given the timeout rejected the request before the confirmation arrived
            var bikeId = BikeId.random();
            var rentalId = RentalId.random();

            // when / then
            fixture.given()
                   .events(new BikeRegistered(bikeId, "city", "Vilnius"),
                           new BikeRequested(bikeId, renter, rentalId),
                           new RequestRejected(bikeId, renter, rentalId))
                   .when()
                   .command(new ApproveRequest(bikeId, renter))
                   .then()
                   .success()
                   .noEvents();
        }

        @Test
        void givenBikeReservedByAnotherRenter_whenApproveRequest_thenNoEventsAndSuccess() {
            // given the bike moved on to a different renter
            var bikeId = BikeId.random();

            // when / then a stale approval must not hand this renter someone else's bike
            fixture.given()
                   .events(new BikeRegistered(bikeId, "city", "Vilnius"),
                           new BikeRequested(bikeId, otherRenter, RentalId.random()))
                   .when()
                   .command(new ApproveRequest(bikeId, renter))
                   .then()
                   .success()
                   .noEvents();
        }

        /**
         * Nothing was ever requested for this bike, so the entity does not exist. This is the case that used to fail
         * with {@code EntityNotFoundException} before the injected entity was marked nullable.
         */
        @Test
        void givenNeverRequestedBike_whenApproveRequest_thenNoEventsAndSuccess() {
            // given
            var bikeId = BikeId.random();

            // when / then
            fixture.given()
                   .events(new BikeRegistered(bikeId, "city", "Vilnius"))
                   .when()
                   .command(new ApproveRequest(bikeId, renter))
                   .then()
                   .success()
                   .noEvents();
        }
    }
}
