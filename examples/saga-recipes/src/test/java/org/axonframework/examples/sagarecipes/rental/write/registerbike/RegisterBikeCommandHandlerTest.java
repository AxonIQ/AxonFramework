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

package org.axonframework.examples.sagarecipes.rental.write.registerbike;

import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.event.BikeRegistered;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@AxonSpringBootTest
class RegisterBikeCommandHandlerTest {

    @Autowired
    private AxonTestFixture fixture;

    /**
     * The create case: no entity exists yet, so the handler receives {@code null}. Without nullability support on the
     * injected entity this would fail rather than register the bike.
     */
    @Test
    void givenNoBike_whenRegisterBike_thenBikeRegistered() {
        // given
        var bikeId = BikeId.random();

        // when / then
        fixture.given()
               .noPriorActivity()
               .when()
               .command(new RegisterBike(bikeId, "city", "Vilnius"))
               .then()
               .success()
               .events(new BikeRegistered(bikeId, "city", "Vilnius"));
    }

    @Test
    void givenRegisteredBike_whenRegisterBikeAgain_thenNoEventsAndSuccess() {
        // given
        var bikeId = BikeId.random();

        // when / then
        fixture.given()
               .events(new BikeRegistered(bikeId, "city", "Vilnius"))
               .when()
               .command(new RegisterBike(bikeId, "city", "Vilnius"))
               .then()
               .success()
               .noEvents();
    }
}
