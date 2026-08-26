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

package org.axonframework.examples.sagarecipes.saga;

import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.event.PaymentCancelled;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.examples.sagarecipes.payment.write.cancelpayment.CancelPayment;
import org.axonframework.examples.sagarecipes.payment.write.preparepayment.PreparePayment;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.event.BikeRegistered;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.rental.event.RequestRejected;
import org.axonframework.examples.sagarecipes.rental.write.approverequest.ApproveRequest;
import org.axonframework.examples.sagarecipes.rental.write.rejectrequest.RejectRequest;
import org.axonframework.examples.sagarecipes.saga.shared.CancelRentalPayment;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPricing;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

/**
 * The behaviour every recipe must produce, written once and run against each of them.
 * <p>
 * This is the point of the module. Saying that four implementations of a saga are interchangeable is easy; running
 * the same scenarios against all of them and watching them agree is what makes it true. A recipe that cannot satisfy
 * one of these cases is a finding about the recipe, not a reason to soften the test.
 * <p>
 * The discipline that makes it shareable: assertions may only concern <b>commands and events crossing the rental and
 * payment boundaries</b>. Nothing about repositories, entities or process events belongs here, because those are
 * exactly what the recipes disagree about. Recipe-specific expectations go in the subclass.
 * <p>
 * The scenarios mirror the bike rental sample application's own {@code PaymentSagaTest} method for method, so the
 * migration guide can put them side by side, with two cases that suite never had: a redelivered trigger and a timeout
 * that arrives too late.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public abstract class SagaRecipeContractTest {

    /**
     * Pooled streaming processors run on their own threads, so every assertion here has to be given time to happen.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    protected AxonTestFixture fixture;

    /**
     * Unique per test: the renter is a tag, and the event store is shared across the whole run.
     */
    protected final String renter = "renter-" + UUID.randomUUID();

    /**
     * The bike rental sample application calls this {@code shouldStartSagaOnBikeRequested}.
     */
    @Test
    void givenBikeRequested_thenPaymentIsPrepared() {
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
    void givenPaymentConfirmed_thenRequestApproved() {
        // given a bike was requested and its payment prepared
        var bikeId = BikeId.random();
        var rentalId = RentalId.random();
        var reference = RentalPaymentReference.forRental(rentalId);
        var paymentId = PaymentId.random();

        // when / then the process has to recall the bike and renter from somewhere, which is what differs per recipe
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
    void givenPaymentRejected_thenRequestRejected() {
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

    /**
     * The payment was called off, which is how a timeout reaches the rental side.
     */
    @Test
    void givenPaymentCancelled_thenRequestRejected() {
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
                       new PaymentCancelled(paymentId, reference))
               .then()
               .await(result -> result.commandsSatisfy(
                       commands -> SagaRecipeAssertions.assertDispatched(commands, new RejectRequest(bikeId, renter))
               ), TIMEOUT);
    }

    /**
     * The bike rental sample application calls this {@code shouldEndSagaWhenRequestIsRejected}, and cancels a
     * deadline. Here the process has to actively call off the payment, or it would stay outstanding forever.
     */
    @Test
    void givenRequestRejectedOnOtherGrounds_thenPaymentIsCancelled() {
        // given a request turned down for a reason of the rental context's own
        var bikeId = BikeId.random();
        var rentalId = RentalId.random();
        var reference = RentalPaymentReference.forRental(rentalId);

        // when / then
        fixture.given()
               .events(new BikeRegistered(bikeId, "city", "Vilnius"),
                       new BikeRequested(bikeId, renter, rentalId),
                       new PaymentPrepared(PaymentId.random(), RentalPricing.PRICE, reference),
                       new RequestRejected(bikeId, renter, rentalId))
               .then()
               .await(result -> result.commandsSatisfy(
                       commands -> SagaRecipeAssertions.assertDispatched(commands, new CancelPayment(reference))
               ), TIMEOUT);
    }

    /**
     * Not in the bike rental sample application's test suite, and the case its saga would have got wrong: it minted
     * a payment identifier unconditionally, so a redelivered trigger created a second payment for the same rental.
     */
    @Test
    void givenPaymentAlreadyPrepared_whenBikeRequestedIsRedelivered_thenNoSecondPreparePayment() {
        // given the payment for this rental already exists
        var bikeId = BikeId.random();
        var rentalId = RentalId.random();
        var reference = RentalPaymentReference.forRental(rentalId);

        // when the trigger arrives a second time
        // then exactly one payment exists for this reference, whatever the process decided to dispatch
        fixture.given()
               .events(new BikeRegistered(bikeId, "city", "Vilnius"),
                       new BikeRequested(bikeId, renter, rentalId),
                       new PaymentPrepared(PaymentId.random(), RentalPricing.PRICE, reference),
                       new BikeRequested(bikeId, renter, rentalId))
               .then()
               .await(result -> result.eventsSatisfy(events -> SagaRecipeAssertions.assertSinglePaymentPrepared(
                       events, reference
               )), TIMEOUT);
    }

    /**
     * The bike rental sample application calls this {@code shouldRejectPaymentWhenNotConfirmedIn30Seconds}, and
     * drives it from a scheduled deadline.
     * <p>
     * The process's side of a timeout: it is asked to give up, and the bike ends up released. What decides that the
     * moment has come is not the process's concern, which is why nothing here mentions a clock or a sweep. The
     * component that notices overdue payments is tested on its own, in {@code PaymentsAwaitingConfirmationTest}.
     */
    @Test
    void givenPaymentNotConfirmed_whenAskedToGiveUp_thenRequestRejected() {
        // given a payment was asked for and never arrived
        var bikeId = BikeId.random();
        var rentalId = RentalId.random();
        var reference = RentalPaymentReference.forRental(rentalId);

        // The payment is not published here: the process asks for it, and the payment context creates it. That
        // matters, because waiting for PreparePayment is what tells us the process has noticed this rental. The
        // recipes that record their own progress do so asynchronously, and asking one to give up before it has
        // noticed would find nothing to give up on.
        fixture.given()
               .events(new BikeRegistered(bikeId, "city", "Vilnius"),
                       new BikeRequested(bikeId, renter, rentalId))
               .then()
               .await(result -> result.commandsSatisfy(commands -> SagaRecipeAssertions.assertDispatched(
                       commands, new PreparePayment(reference, RentalPricing.PRICE)
               )), TIMEOUT)
               .and()
               // when
               .when()
               .command(new CancelRentalPayment(rentalId))
               // then
               .then()
               .success()
               .await(result -> result.commandsSatisfy(
                       commands -> SagaRecipeAssertions.assertDispatched(commands, new RejectRequest(bikeId, renter))
               ), TIMEOUT);
    }
}
