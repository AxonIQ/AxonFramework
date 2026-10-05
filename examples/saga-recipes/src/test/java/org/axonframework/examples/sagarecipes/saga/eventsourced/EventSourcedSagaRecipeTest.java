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

package org.axonframework.examples.sagarecipes.saga.eventsourced;

import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.event.BikeRegistered;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.saga.SagaRecipeAssertions;
import org.axonframework.examples.sagarecipes.saga.SagaRecipeContractTest;
import org.axonframework.examples.sagarecipes.saga.eventsourced.event.RentalPaymentRequested;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * Runs the shared contract against the recipe that records its own events, and adds the one thing only this recipe
 * can be asked: whether the process wrote down what it did.
 */
@AxonSpringBootTest(properties = "saga.recipe=eventsourced")
class EventSourcedSagaRecipeTest extends SagaRecipeContractTest {

    /**
     * Recipe-specific: the audit trail is the whole reason to prefer this recipe over the derived-state one, so it is
     * worth asserting that it actually appears.
     */
    @Test
    void givenBikeRequestedThenTheProcessRecordsThatItAskedForPayment() {
        // given
        var bikeId = BikeId.random();
        var rentalId = RentalId.random();

        // when / then
        fixture.given()
               .events(new BikeRegistered(bikeId, "city", "Vilnius"),
                       new BikeRequested(bikeId, renter, rentalId))
               .then()
               .await(result -> result.eventsSatisfy(
                       events -> SagaRecipeAssertions.assertProcessEventAppended(
                               events, RentalPaymentRequested.class, rentalId
                       )
               ), Duration.ofSeconds(5));
    }
}
