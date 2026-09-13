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
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.examples.sagarecipes.payment.write.preparepayment.PreparePayment;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.event.BikeRegistered;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.rental.write.approverequest.ApproveRequest;
import org.axonframework.examples.sagarecipes.rental.write.rejectrequest.RejectRequest;
import org.axonframework.examples.sagarecipes.saga.SagaRecipeAssertions;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPricing;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

/**
 * The ported Axon Framework 4 Saga, driven the way the Axon Framework 5 recipes are driven: through the whole running
 * application rather than through a Saga fixture.
 * <p>
 * {@code SagaRecipeContractTest} is deliberately not inherited. Four of its seven scenarios describe behaviour the
 * original Saga never had, since it relied on a payment timeout for all of them: calling an outstanding payment off
 * when the request is rejected, handling {@code PaymentCancelled}, answering {@code CancelRentalPayment}, and not
 * minting a second payment for a redelivered trigger. What is asserted below is the part of the contract this port
 * genuinely satisfies, which is also the part a reader migrating a Saga can expect to keep working on day one.
 *
 * @author Mateusz Nowak
 */
@AxonSpringBootTest(properties = "saga.recipe=legacy")
class PaymentSagaAxonTestFixtureTest {

    /**
     * The Saga runs on a pooled streaming processor of its own, so every assertion has to be given time to happen.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private AxonTestFixture fixture;

    /**
     * Unique per test: the renter is a tag, and the event store is shared across the whole run.
     */
    private final String renter = "renter-" + UUID.randomUUID();

    /**
     * The bike rental sample application calls this {@code shouldStartSagaOnBikeRequested}.
     */
    @Test
    void givenBikeRequestedThenPaymentIsPrepared() {
        // given
        var bikeId = BikeId.random();
        var rentalId = RentalId.random();

        // when / then
        fixture.given()
               .events(new BikeRegistered(bikeId, "city", "Vilnius"),
                       new BikeRequested(bikeId, renter, rentalId))
               .then()
               .await(result -> result.commandsSatisfy(commands -> SagaRecipeAssertions.assertDispatched(
                       commands,
                       new PreparePayment(RentalPaymentReference.forRental(rentalId), RentalPricing.PRICE)
               )), TIMEOUT);
    }

    /**
     * The bike rental sample application calls this {@code shouldAcceptRequestOnPaymentConfirmed}.
     */
    @Test
    void givenPaymentConfirmedThenRequestApproved() {
        // given a bike was requested and its payment prepared
        var bikeId = BikeId.random();
        var rentalId = RentalId.random();
        var reference = RentalPaymentReference.forRental(rentalId);
        var paymentId = PaymentId.random();

        // when / then the bike and the renter come back out of the Saga's own fields
        fixture.given()
               .events(new BikeRegistered(bikeId, "city", "Vilnius"),
                       new BikeRequested(bikeId, renter, rentalId),
                       new PaymentPrepared(paymentId, RentalPricing.PRICE, reference),
                       new PaymentConfirmed(paymentId, reference))
               .then()
               .await(result -> result.commandsSatisfy(
                       commands -> SagaRecipeAssertions.assertDispatched(commands, new ApproveRequest(bikeId, renter))
               ), TIMEOUT);
    }

    /**
     * The bike rental sample application calls this {@code shouldRejectRequestOnPaymentRejected}.
     */
    @Test
    void givenPaymentRejectedThenRequestRejected() {
        // given
        var bikeId = BikeId.random();
        var rentalId = RentalId.random();
        var reference = RentalPaymentReference.forRental(rentalId);
        var paymentId = PaymentId.random();

        // when / then
        fixture.given()
               .events(new BikeRegistered(bikeId, "city", "Vilnius"),
                       new BikeRequested(bikeId, renter, rentalId),
                       new PaymentPrepared(paymentId, RentalPricing.PRICE, reference),
                       new PaymentRejected(paymentId, reference))
               .then()
               .await(result -> result.commandsSatisfy(
                       commands -> SagaRecipeAssertions.assertDispatched(commands, new RejectRequest(bikeId, renter))
               ), TIMEOUT);
    }
}
