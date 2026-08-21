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

package org.axonframework.examples.sagarecipes.saga.repository;

import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.event.BikeRegistered;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.saga.SagaRecipeContractTest;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the shared contract against the recipe that keeps a table of its own, and adds the one thing only this recipe
 * can be asked: whether the row it writes says what it should.
 */
@AxonSpringBootTest(properties = "saga.recipe=repository")
class RepositorySagaRecipeTest extends SagaRecipeContractTest {

    @Autowired
    private PaymentSagaStateRepository repository;

    /**
     * Recipe-specific, so it lives here rather than in the shared contract. The row must record the bike and the
     * renter, because nothing else in the system can tell the process which bike to approve later.
     */
    @Test
    void givenBikeRequested_thenTheProcessRemembersTheBikeAndRenter() {
        // given
        var bikeId = BikeId.random();
        var rentalId = RentalId.random();

        // when
        fixture.given()
               .events(new BikeRegistered(bikeId, "city", "Vilnius"),
                       new BikeRequested(bikeId, renter, rentalId));

        // then
        await().atMost(Duration.ofSeconds(5))
               .untilAsserted(() -> assertThat(repository.findById(rentalId.raw()))
                       .hasValueSatisfying(process -> {
                           assertThat(process.bikeId()).isEqualTo(bikeId);
                           assertThat(process.renter()).isEqualTo(renter);
                           assertThat(process.paymentRequested()).isTrue();
                       }));
    }
}
