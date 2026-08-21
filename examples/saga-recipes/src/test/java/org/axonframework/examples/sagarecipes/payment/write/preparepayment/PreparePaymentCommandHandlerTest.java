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

package org.axonframework.examples.sagarecipes.payment.write.preparepayment;

import org.axonframework.examples.sagarecipes.payment.Amount;
import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@AxonSpringBootTest
class PreparePaymentCommandHandlerTest {

    private static final Amount PRICE = Amount.of(10);

    @Autowired
    private AxonTestFixture fixture;

    @Test
    void givenNoPayment_whenPreparePayment_thenPaymentPrepared() {
        // given
        var reference = PaymentReference.of("rental-1");

        // when / then the payment identifier is minted here, so only the other fields can be asserted directly
        fixture.given()
               .noPriorActivity()
               .when()
               .command(new PreparePayment(reference, PRICE))
               .then()
               .success()
               .eventsSatisfy(events -> assertThat(payloadsOf(events))
                       .singleElement()
                       .satisfies(prepared -> {
                           assertThat(prepared.paymentReference()).isEqualTo(reference);
                           assertThat(prepared.amount()).isEqualTo(PRICE);
                           assertThat(prepared.paymentId()).isNotNull();
                       }));
    }

    @Nested
    class Idempotency {

        /**
         * The saga sends this command from an event handler, so a redelivered {@code BikeRequested} produces a second
         * {@code PreparePayment}. Axon Framework 4 minted a payment identifier unconditionally here and would have
         * created a second payment for the same rental.
         */
        @Test
        void givenPaymentAlreadyPrepared_whenPreparePaymentForSameReference_thenNoEventsAndSuccess() {
            // given a payment already exists for this reference
            var reference = PaymentReference.of("rental-2");

            // when / then
            fixture.given()
                   .events(new PaymentPrepared(PaymentId.random(), PRICE, reference))
                   .when()
                   .command(new PreparePayment(reference, PRICE))
                   .then()
                   .success()
                   .noEvents();
        }

        @Test
        void givenPaymentForAnotherReference_whenPreparePayment_thenStillPrepared() {
            // given a payment exists, but under a different reference
            var otherReference = PaymentReference.of("rental-3");
            var reference = PaymentReference.of("rental-4");

            // when / then the boundary is the reference, so an unrelated payment must not block this one
            fixture.given()
                   .events(new PaymentPrepared(PaymentId.random(), PRICE, otherReference))
                   .when()
                   .command(new PreparePayment(reference, PRICE))
                   .then()
                   .success()
                   .eventsSatisfy(events -> assertThat(payloadsOf(events))
                           .singleElement()
                           .satisfies(prepared -> assertThat(prepared.paymentReference()).isEqualTo(reference)));
        }
    }

    private static List<PaymentPrepared> payloadsOf(List<EventMessage> events) {
        return events.stream().map(event -> event.payloadAs(PaymentPrepared.class)).toList();
    }
}
