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

package org.axonframework.examples.sagarecipes.rental.write.returnbike;

import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.event.BikeInUse;
import org.axonframework.examples.sagarecipes.rental.event.BikeRegistered;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.rental.event.BikeReturned;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

/**
 * Returning is the one command here that a person sends rather than an event processor, so a bike that is not out on
 * a rental is a genuine mistake and is reported as one. Idempotency is required where delivery is at-least-once, not
 * everywhere.
 */
@AxonSpringBootTest
class ReturnBikeCommandHandlerTest {

    @Autowired
    private AxonTestFixture fixture;

    /**
     * Unique per test. The renter is a tag, so reusing a fixed name would let one test's events satisfy or violate
     * another test's rules: the Spring context, and with it the event store, is shared across the whole run.
     */
    private final String renter = "renter-" + UUID.randomUUID();

    @Test
    void givenBikeInUse_whenReturnBike_thenBikeReturned() {
        // given
        var bikeId = BikeId.random();
        var rentalId = RentalId.random();

        // when / then
        fixture.given()
               .events(new BikeRegistered(bikeId, "city", "Vilnius"),
                       new BikeRequested(bikeId, renter, rentalId),
                       new BikeInUse(bikeId, renter, rentalId))
               .when()
               .command(new ReturnBike(bikeId, "Kaunas"))
               .then()
               .success()
               .events(new BikeReturned(bikeId, renter, rentalId, "Kaunas"));
    }

    @Test
    void givenBikeAlreadyReturned_whenReturnBike_thenRejected() {
        // given
        var bikeId = BikeId.random();
        var rentalId = RentalId.random();

        // when / then
        fixture.given()
               .events(new BikeRegistered(bikeId, "city", "Vilnius"),
                       new BikeRequested(bikeId, renter, rentalId),
                       new BikeInUse(bikeId, renter, rentalId),
                       new BikeReturned(bikeId, renter, rentalId, "Kaunas"))
               .when()
               .command(new ReturnBike(bikeId, "Kaunas"))
               .then()
               .exception(IllegalStateException.class, "Bike is not in use");
    }

    /**
     * The bike was never taken out, so no entity exists. Before the injected entity was marked nullable this failed
     * with {@code EntityNotFoundException} instead of the intended message.
     */
    @Test
    void givenBikeNeverInUse_whenReturnBike_thenRejected() {
        // given
        var bikeId = BikeId.random();

        // when / then
        fixture.given()
               .events(new BikeRegistered(bikeId, "city", "Vilnius"))
               .when()
               .command(new ReturnBike(bikeId, "Kaunas"))
               .then()
               .exception(IllegalStateException.class, "Bike is not in use");
    }
}
