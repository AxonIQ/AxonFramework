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

package org.axonframework.examples.sagarecipes.saga.legacy;

import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.examples.sagarecipes.payment.write.preparepayment.PreparePayment;
import org.axonframework.examples.sagarecipes.payment.write.rejectpayment.RejectPayment;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.rental.event.RequestRejected;
import org.axonframework.examples.sagarecipes.rental.write.approverequest.ApproveRequest;
import org.axonframework.examples.sagarecipes.rental.write.rejectrequest.RejectRequest;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPricing;
import org.axonframework.test.saga.SagaTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * The bike rental sample application's own {@code PaymentSagaTest}, kept as it was written.
 * <p>
 * This is the other half of the port: the Axon Framework 4 saga runs on the Axon Framework 4 fixture, so a reader
 * migrating a suite can see how much of it survives untouched. Only the domain types differ from the original, since
 * this module models the two contexts as records rather than as one shared package of aggregates.
 * <p>
 * The scenarios that depend on a scheduled deadline stay here, disabled, rather than being rewritten. Rewriting them
 * would hide exactly what a migration has to deal with.
 *
 * @author Mateusz Nowak
 */
class PaymentSagaTest {

    private final BikeId bikeId = BikeId.random();
    private final RentalId rentalId = RentalId.random();
    private final PaymentReference reference = RentalPaymentReference.forRental(rentalId);
    private final String renter = "allard";

    private SagaTestFixture<PaymentSaga> fixture;

    @BeforeEach
    void setUp() {
        fixture = new SagaTestFixture<>(PaymentSaga.class);
    }

    /**
     * The fixture runs a started configuration holding an event processor, which has to be stopped again.
     */
    @AfterEach
    void tearDown() {
        fixture.stop();
    }

    @Test
    void shouldStartSagaOnBikeRequested() {
        // given / when / then
        fixture.givenNoPriorActivity()
               .whenPublishingA(new BikeRequested(bikeId, renter, rentalId))
               .expectDispatchedCommands(new PreparePayment(reference, RentalPricing.PRICE))
               .expectActiveSagas(1);
    }

    @Test
    void shouldAcceptRequestOnPaymentConfirmed() {
        // given / when / then
        fixture.givenAPublished(new BikeRequested(bikeId, renter, rentalId))
               .whenPublishingA(new PaymentConfirmed(PaymentId.random(), reference))
               .expectDispatchedCommands(new ApproveRequest(bikeId, renter))
               .expectActiveSagas(0);
    }

    @Test
    void shouldRejectRequestOnPaymentRejected() {
        // given / when / then
        fixture.givenAPublished(new BikeRequested(bikeId, renter, rentalId))
               .whenPublishingA(new PaymentRejected(PaymentId.random(), reference))
               .expectDispatchedCommands(new RejectRequest(bikeId, renter));
    }

    @Test
    void shouldEndSagaWhenRequestIsRejected() {
        // given / when / then
        fixture.givenAPublished(new BikeRequested(bikeId, renter, rentalId))
               .whenPublishingA(new RequestRejected(bikeId, renter, rentalId))
               .expectActiveSagas(0);
    }

    @Disabled("#5006: SagaTestFixture.whenTimeElapses(..) throws UnsupportedOperationException until deadlines "
                      + "are ported into axon-legacy")
    @Test
    void shouldRejectPaymentWhenNotConfirmedIn30Seconds() {
        // given
        PaymentId paymentId = PaymentId.random();

        // when / then
        fixture.givenAPublished(new BikeRequested(bikeId, renter, rentalId))
               .andThenAPublished(new PaymentPrepared(paymentId, RentalPricing.PRICE, reference))
               .whenTimeElapses(Duration.ofSeconds(30))
               .expectDispatchedCommands(new RejectPayment(paymentId));
    }
}
