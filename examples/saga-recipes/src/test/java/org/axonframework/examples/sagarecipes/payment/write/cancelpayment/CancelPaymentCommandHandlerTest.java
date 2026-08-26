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

package org.axonframework.examples.sagarecipes.payment.write.cancelpayment;

import org.axonframework.examples.sagarecipes.payment.Amount;
import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.event.PaymentCancelled;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Cancelling is the deadline replacement, so the interesting cases are all the ones where the timeout arrives too
 * late to matter.
 */
@AxonSpringBootTest
class CancelPaymentCommandHandlerTest {

    private static final Amount PRICE = Amount.of(10);

    @Autowired
    private AxonTestFixture fixture;

    @Test
    void givenPreparedPaymentWhenCancelPaymentThenPaymentCancelled() {
        // given a payment nobody has paid yet
        var reference = PaymentReference.of("rental-10");
        var paymentId = PaymentId.random();

        // when / then the payment identifier is read from the stream, not supplied by the caller
        fixture.given()
               .events(new PaymentPrepared(paymentId, PRICE, reference))
               .when()
               .command(new CancelPayment(reference))
               .then()
               .success()
               .events(new PaymentCancelled(paymentId, reference));
    }

    @Nested
    class ArrivingTooLate {

        @Test
        void givenPaymentAlreadyConfirmedWhenCancelPaymentThenIgnoredSilently() {
            // given the payment was paid before the timeout fired
            var reference = PaymentReference.of("rental-11");
            var paymentId = PaymentId.random();

            // when / then a cancellation that loses the race must not undo a confirmation
            fixture.given()
                   .events(new PaymentPrepared(paymentId, PRICE, reference),
                           new PaymentConfirmed(paymentId, reference))
                   .when()
                   .command(new CancelPayment(reference))
                   .then()
                   .success()
                   .noEvents();
        }

        @Test
        void givenPaymentAlreadyRejectedWhenCancelPaymentThenIgnoredSilently() {
            // given
            var reference = PaymentReference.of("rental-12");
            var paymentId = PaymentId.random();

            // when / then
            fixture.given()
                   .events(new PaymentPrepared(paymentId, PRICE, reference),
                           new PaymentRejected(paymentId, reference))
                   .when()
                   .command(new CancelPayment(reference))
                   .then()
                   .success()
                   .noEvents();
        }

        @Test
        void givenPaymentAlreadyCancelledWhenCancelPaymentAgainThenIgnoredSilently() {
            // given the sweep already cancelled this payment on an earlier pass
            var reference = PaymentReference.of("rental-13");
            var paymentId = PaymentId.random();

            // when / then re-dispatching a cancellation has to stay harmless, because the sweeper repeats
            fixture.given()
                   .events(new PaymentPrepared(paymentId, PRICE, reference),
                           new PaymentCancelled(paymentId, reference))
                   .when()
                   .command(new CancelPayment(reference))
                   .then()
                   .success()
                   .noEvents();
        }

        @Test
        void givenNoPaymentAtAllWhenCancelPaymentThenIgnoredSilently() {
            // given preparing the payment never got as far as an event

            // when / then the caller wanted nothing outstanding, and nothing is outstanding
            fixture.given()
                   .noPriorActivity()
                   .when()
                   .command(new CancelPayment(PaymentReference.of("rental-14")))
                   .then()
                   .success()
                   .noEvents();
        }
    }
}
