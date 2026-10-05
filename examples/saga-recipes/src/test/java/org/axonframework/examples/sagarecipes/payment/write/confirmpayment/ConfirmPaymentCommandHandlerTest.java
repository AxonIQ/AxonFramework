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

package org.axonframework.examples.sagarecipes.payment.write.confirmpayment;

import org.axonframework.examples.sagarecipes.payment.Amount;
import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.event.PaymentCancelled;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@AxonSpringBootTest
class ConfirmPaymentCommandHandlerTest {

    private static final Amount PRICE = Amount.of(10);

    @Autowired
    private AxonTestFixture fixture;

    @Test
    void givenPreparedPaymentWhenConfirmPaymentThenPaymentConfirmed() {
        // given
        var reference = PaymentReference.of("rental-20");
        var paymentId = PaymentId.random();

        // when / then the reference is echoed from the stream, so the caller can recognise its own affair
        fixture.given()
               .events(new PaymentPrepared(paymentId, PRICE, reference))
               .when()
               .command(new ConfirmPayment(paymentId))
               .then()
               .success()
               .events(new PaymentConfirmed(paymentId, reference));
    }

    @Test
    void givenNoPaymentWhenConfirmPaymentThenRejected() {
        // given no payment was ever prepared

        // when / then
        fixture.given()
               .noPriorActivity()
               .when()
               .command(new ConfirmPayment(PaymentId.random()))
               .then()
               .exception(IllegalStateException.class, "Payment does not exist");
    }

    @Nested
    class Idempotency {

        @Test
        void givenPaymentAlreadyConfirmedWhenConfirmPaymentAgainThenNoEventsAndSuccess() {
            // given
            var reference = PaymentReference.of("rental-21");
            var paymentId = PaymentId.random();

            // when / then
            fixture.given()
                   .events(new PaymentPrepared(paymentId, PRICE, reference),
                           new PaymentConfirmed(paymentId, reference))
                   .when()
                   .command(new ConfirmPayment(paymentId))
                   .then()
                   .success()
                   .noEvents();
        }

        @Test
        void givenPaymentAlreadyCancelledWhenConfirmPaymentThenIgnoredSilently() {
            // given the timeout won the race
            var reference = PaymentReference.of("rental-22");
            var paymentId = PaymentId.random();

            // when / then a payment settles once, whichever side got there first
            fixture.given()
                   .events(new PaymentPrepared(paymentId, PRICE, reference),
                           new PaymentCancelled(paymentId, reference))
                   .when()
                   .command(new ConfirmPayment(paymentId))
                   .then()
                   .success()
                   .noEvents();
        }
    }
}
