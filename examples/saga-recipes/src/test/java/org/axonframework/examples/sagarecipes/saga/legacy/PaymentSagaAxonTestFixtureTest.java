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

import org.axonframework.deadline.DeadlineManager;
import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.examples.sagarecipes.payment.write.preparepayment.PreparePayment;
import org.axonframework.examples.sagarecipes.payment.write.rejectpayment.RejectPayment;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.event.BikeRegistered;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.rental.event.RequestRejected;
import org.axonframework.examples.sagarecipes.rental.write.approverequest.ApproveRequest;
import org.axonframework.examples.sagarecipes.rental.write.rejectrequest.RejectRequest;
import org.axonframework.examples.sagarecipes.saga.SagaRecipeAssertions;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPricing;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.axonframework.messaging.ScopeDescriptor;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * The ported Axon Framework 4 Saga, driven the way the Axon Framework 5 recipes are driven: through the whole running
 * application rather than through a Saga fixture.
 * <p>
 * {@code SagaRecipeContractTest} is deliberately not inherited. Four of its seven scenarios describe behaviour the
 * original Saga never had, since it relied on a payment timeout for all of them: calling an outstanding payment off
 * when the request is rejected, handling {@code PaymentCancelled}, answering {@code CancelRentalPayment}, and not
 * minting a second payment for a redelivered trigger. What is asserted below is the part of the contract this port
 * genuinely satisfies, which is also the part a reader migrating a Saga can expect to keep working on day one.
 * <p>
 * The payment timeout is checked here too, through the real {@code DeadlineManager} bean the recipe wires, but not by
 * waiting. The Saga's 30 seconds belong to its production behaviour, and {@code AxonTestFixture} cannot advance time.
 * The bean is therefore spied on rather than replaced: every call still reaches the real
 * {@code SimpleDeadlineManager}, and the test reads off it what the Saga scheduled and cancelled, and in which scope.
 * To see a deadline delivered, the test schedules one of its own into the scope the Saga scheduled in, with a delay
 * of a quarter of a second. That exercises everything the Saga's own 30 second deadline would: the scheduler thread,
 * the unit of work, finding the Saga instance in its store, and resolving the handler's parameters. Only the delay
 * differs. The scenarios with a time-travelling fixture, including the retry of a failed payment request, are in
 * {@code PaymentSagaTest}.
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
     * The real deadline manager, observed. Calls pass through to it.
     */
    @MockitoSpyBean
    private DeadlineManager deadlineManager;

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

    @Nested
    class PaymentTimeout {

        @Test
        void isScheduledWhenPaymentIsPrepared() {
            // given
            var bikeId = BikeId.random();
            var rentalId = RentalId.random();
            var paymentId = PaymentId.random();
            var reference = RentalPaymentReference.forRental(rentalId);

            // when
            fixture.given()
                   .events(new BikeRegistered(bikeId, "city", "Vilnius"),
                           new BikeRequested(bikeId, renter, rentalId),
                           new PaymentPrepared(paymentId, RentalPricing.PRICE, reference));

            // then the production timeout of 30 seconds is scheduled with the payment's identifier
            verify(deadlineManager, timeout(TIMEOUT.toMillis()))
                    .schedule(eq(Duration.ofSeconds(30)), eq("cancelPayment"), eq(paymentId.raw()), any());
        }

        @Test
        void rejectsPaymentWhenTheDeadlineFires() {
            // given a payment waiting to be paid, whose timeout the Saga has scheduled
            var bikeId = BikeId.random();
            var rentalId = RentalId.random();
            var paymentId = PaymentId.random();
            var reference = RentalPaymentReference.forRental(rentalId);
            var given = fixture.given()
                               .events(new BikeRegistered(bikeId, "city", "Vilnius"),
                                       new BikeRequested(bikeId, renter, rentalId),
                                       new PaymentPrepared(paymentId, RentalPricing.PRICE, reference));
            ScopeDescriptor sagaScope = scopeOfDeadlineScheduledFor(paymentId);

            // when the deadline fires, which is not waited for 30 seconds
            deadlineManager.schedule(Duration.ofMillis(250), "cancelPayment", paymentId.raw(), sagaScope);

            // then the Saga rejects the payment
            given.then()
                 .await(result -> result.commandsSatisfy(
                         commands -> SagaRecipeAssertions.assertDispatched(commands, new RejectPayment(paymentId))
                 ), TIMEOUT);
        }

        @Test
        void isCancelledWithinTheScopeItWasScheduledInWhenRequestIsRejected() {
            // given a payment waiting to be paid, whose timeout the Saga has scheduled
            var bikeId = BikeId.random();
            var rentalId = RentalId.random();
            var paymentId = PaymentId.random();
            var reference = RentalPaymentReference.forRental(rentalId);
            var given = fixture.given()
                               .events(new BikeRegistered(bikeId, "city", "Vilnius"),
                                       new BikeRequested(bikeId, renter, rentalId),
                                       new PaymentPrepared(paymentId, RentalPricing.PRICE, reference));
            ScopeDescriptor sagaScope = scopeOfDeadlineScheduledFor(paymentId);

            // when the rental context turns the request down
            given.events(new RequestRejected(bikeId, renter, rentalId));

            // then the timeout is cancelled in that very Saga's scope
            verify(deadlineManager, timeout(TIMEOUT.toMillis())).cancelAllWithinScope("cancelPayment", sagaScope);
        }

        private ScopeDescriptor scopeOfDeadlineScheduledFor(PaymentId paymentId) {
            var scope = ArgumentCaptor.forClass(ScopeDescriptor.class);
            verify(deadlineManager, timeout(TIMEOUT.toMillis()))
                    .schedule(eq(Duration.ofSeconds(30)), eq("cancelPayment"), eq(paymentId.raw()), scope.capture());
            assertThat(scope.getValue()).isNotNull();
            return scope.getValue();
        }
    }
}
