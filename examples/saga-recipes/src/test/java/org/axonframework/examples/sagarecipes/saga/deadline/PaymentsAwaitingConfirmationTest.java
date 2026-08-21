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

package org.axonframework.examples.sagarecipes.saga.deadline;

import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.event.PaymentCancelled;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPricing;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The to-do list on its own, without any recipe active.
 * <p>
 * What is being pinned here is the property the whole pattern rests on: rows come and go purely as a function of the
 * events, never as a side effect of sweeping. That is what makes the list unable to drift from the event stream, and
 * it is why the sweeper can be a pure reader.
 * <p>
 * The scheduled trigger itself is deliberately untested. It is one line delegating to a method that takes the moment
 * to judge against as an argument, and testing it would mean testing Spring.
 */
@AxonSpringBootTest
class PaymentsAwaitingConfirmationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private AxonTestFixture fixture;

    @Autowired
    private PendingPaymentRepository pending;

    @Test
    void givenPaymentPrepared_thenItJoinsTheToDoList() {
        // given
        var reference = someReference();

        // when / then
        fixture.given()
               .events(new PaymentPrepared(PaymentId.random(), RentalPricing.PRICE, reference));

        awaitListed(reference);
    }

    /**
     * Every way a payment can settle takes it off the list. Missing one would leave the sweeper cancelling a payment
     * forever, which is survivable only because cancelling is idempotent, and is exactly the sort of bug that hides.
     */
    @Nested
    class SettlingRemovesItFromTheList {

        @Test
        void givenPaymentConfirmed_thenItLeavesTheToDoList() {
            // given
            var reference = someReference();
            var paymentId = PaymentId.random();

            // when / then
            fixture.given()
                   .events(new PaymentPrepared(paymentId, RentalPricing.PRICE, reference));
            awaitListed(reference);

            fixture.given()
                   .events(new PaymentConfirmed(paymentId, reference));
            awaitNotListed(reference);
        }

        @Test
        void givenPaymentRejected_thenItLeavesTheToDoList() {
            // given
            var reference = someReference();
            var paymentId = PaymentId.random();

            // when / then
            fixture.given()
                   .events(new PaymentPrepared(paymentId, RentalPricing.PRICE, reference));
            awaitListed(reference);

            fixture.given()
                   .events(new PaymentRejected(paymentId, reference));
            awaitNotListed(reference);
        }

        @Test
        void givenPaymentCancelled_thenItLeavesTheToDoList() {
            // given
            var reference = someReference();
            var paymentId = PaymentId.random();

            // when / then
            fixture.given()
                   .events(new PaymentPrepared(paymentId, RentalPricing.PRICE, reference));
            awaitListed(reference);

            fixture.given()
                   .events(new PaymentCancelled(paymentId, reference));
            awaitNotListed(reference);
        }
    }

    private static PaymentReference someReference() {
        return PaymentReference.of("rental-" + UUID.randomUUID());
    }

    private void awaitListed(PaymentReference reference) {
        await().atMost(TIMEOUT)
               .untilAsserted(() -> assertThat(pending.findById(reference.raw()))
                       .describedAs("an outstanding payment should be on the to-do list")
                       .isPresent());
    }

    private void awaitNotListed(PaymentReference reference) {
        await().atMost(TIMEOUT)
               .untilAsserted(() -> assertThat(pending.findById(reference.raw()))
                       .describedAs("a settled payment should have left the to-do list")
                       .isEmpty());
    }
}
