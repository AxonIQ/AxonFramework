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

package sagas.testing;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Test;
import sagas.shared.RentalPaymentApi.ApproveRequest;
import sagas.shared.RentalPaymentApi.BikeRequested;
import sagas.shared.RentalPaymentApi.PaymentConfirmed;
import sagas.shared.RentalPaymentApi.PaymentPrepared;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static sagas.shared.RentalPaymentApi.PRICE;
import static sagas.shared.RentalPaymentApi.paymentReferenceFor;

/**
 * Testing a process with {@link AxonTestFixture}, needing no process-specific support.
 */
class RentalPaymentProcessTest {

    private AxonTestFixture fixture;

    private String bikeId;
    private String renter;
    private String rentalId;
    private String paymentId;

    // tag::event-driven[]
    @Test
    void givenPaymentConfirmed_whenNothing_thenRequestApproved() {
        fixture.given()
               .events(new BikeRequested(bikeId, renter, rentalId),
                       new PaymentPrepared(paymentId, PRICE, paymentReferenceFor(rentalId)),
                       new PaymentConfirmed(paymentId, paymentReferenceFor(rentalId)))
               .then()
               .await(result -> result.commandsSatisfy( // <1>
                       commands -> assertThat(payloadsOf(commands)).contains(new ApproveRequest(bikeId, renter))
               ), Duration.ofSeconds(5));
    }
    // end::event-driven[]

    private static List<Object> payloadsOf(List<CommandMessage> commands) {
        return commands.stream().map(command -> (Object) command.payload()).toList();
    }
}
