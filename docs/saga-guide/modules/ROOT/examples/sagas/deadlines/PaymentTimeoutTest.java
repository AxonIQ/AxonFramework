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

package sagas.deadlines;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Test;
import sagas.shared.RentalPaymentApi.BikeRequested;
import sagas.shared.RentalPaymentApi.CancelRentalPayment;
import sagas.shared.RentalPaymentApi.PaymentPrepared;
import sagas.shared.RentalPaymentApi.PreparePayment;
import sagas.shared.RentalPaymentApi.RejectRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static sagas.shared.RentalPaymentApi.PRICE;
import static sagas.shared.RentalPaymentApi.paymentReferenceFor;

/**
 * The two halves of testing a timeout: the sweep, and the process asked to give up.
 */
class PaymentTimeoutTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private AxonTestFixture fixture;
    private PaymentsAwaitingConfirmation sweeper;
    private PendingPaymentRepository pending;

    private String bikeId;
    private String renter;
    private String rentalId;

    // tag::sweep-test[]
    @Test
    void givenOverduePayment_whenSweeping_thenItIsCalledOffAndLeavesTheList() {
        // given a payment that has been outstanding for a while
        var reference = someReference();
        fixture.given()
               .events(new PaymentPrepared(UUID.randomUUID().toString(), PRICE, reference));
        awaitListed(reference);

        // when judged from far enough in the future
        sweeper.cancelOverduePayments(Instant.now().plus(Duration.ofHours(1))); // <1>

        // then the payment was called off, which is what takes it off the list again
        awaitNotListed(reference);
    }
    // end::sweep-test[]

    // tag::process-test[]
    @Test
    void givenPaymentNotConfirmed_whenAskedToGiveUp_thenRequestRejected() {
        // given a payment was asked for and never arrived
        fixture.given()
               .events(new BikeRequested(bikeId, renter, rentalId))
               .then()
               .await(result -> result.commandsSatisfy(commands -> assertThat(payloadsOf(commands))
                       .contains(new PreparePayment(paymentReferenceFor(rentalId), PRICE))
               ), TIMEOUT) // <1>
               .and()
               .when()
               .command(new CancelRentalPayment(rentalId)) // <2>
               .then()
               .success()
               .await(result -> result.commandsSatisfy(commands -> assertThat(payloadsOf(commands))
                       .contains(new RejectRequest(bikeId, renter))
               ), TIMEOUT);
    }
    // end::process-test[]

    private String someReference() {
        return paymentReferenceFor(UUID.randomUUID().toString());
    }

    private void awaitListed(String reference) {
        await().atMost(TIMEOUT).until(() -> listed(reference));
    }

    private void awaitNotListed(String reference) {
        await().atMost(TIMEOUT).until(() -> !listed(reference));
    }

    private boolean listed(String reference) {
        return pending.findByPreparedAtBefore(Instant.now().plus(Duration.ofDays(1)))
                      .stream()
                      .anyMatch(payment -> payment.paymentReference().equals(reference));
    }

    private static List<Object> payloadsOf(List<CommandMessage> commands) {
        return commands.stream().map(command -> (Object) command.payload()).toList();
    }
}
