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
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The deadline replacement, on its own.
 * <p>
 * Two halves, tested separately because they fail for different reasons. The projection records what is outstanding,
 * and the property being pinned there is that rows come and go purely as a function of events, never as a side effect
 * of sweeping: that is what stops the list drifting from the event stream, and what lets the sweeper be a pure reader.
 * The sweep then notices what has waited too long.
 * <p>
 * How a recipe reacts to being asked to give up is not tested here. That is the process's behaviour, and it belongs in
 * the shared contract every recipe satisfies. A recipe is nevertheless active below, for the mundane reason that the
 * sweep dispatches a command and a command needs a handler.
 * <p>
 * The scheduled trigger itself is deliberately untested. It is one line delegating to a method that takes the moment
 * to judge against as an argument, and testing it would mean testing Spring.
 */
@AxonSpringBootTest(properties = {
        // A recipe has to be active, for the mundane reason that the sweep dispatches a command and a command needs a
        // handler. Which recipe is immaterial.
        "saga.recipe=automations",
        // Sweeping is global: it acts on every overdue payment it finds, including ones other tests are relying on.
        // A property set nothing else uses gives this class a Spring context, and therefore an event store, of its own.
        "saga.deadline.sweep-interval=PT2H"
})
class PaymentsAwaitingConfirmationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private AxonTestFixture fixture;

    @Autowired
    private PendingPaymentRepository pending;

    @Autowired
    private PaymentsAwaitingConfirmation sweeper;

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

    /**
     * What the component is for. The projection above only records outstanding work; these cases cover noticing that
     * a piece of it has waited too long.
     * <p>
     * Nothing sleeps and no clock is faked. The moment to judge against is an argument, which is the entire reason
     * {@code cancelOverduePayments} is a method rather than logic inside the scheduled trigger.
     */
    @Nested
    class Sweeping {

        @Test
        void givenOverduePayment_whenSweeping_thenItIsCalledOffAndLeavesTheList() {
            // given a payment that has been outstanding for a while
            var reference = someReference();
            fixture.given()
                   .events(new PaymentPrepared(PaymentId.random(), RentalPricing.PRICE, reference));
            awaitListed(reference);

            // when judged from far enough in the future
            sweeper.cancelOverduePayments(Instant.now().plus(Duration.ofHours(1)));

            // then the payment was called off, which is what takes it off the list again
            awaitNotListed(reference);
        }

        @Test
        void givenPaymentWithinItsTimeout_whenSweeping_thenItIsLeftAlone() {
            // given a payment prepared just now
            var reference = someReference();
            fixture.given()
                   .events(new PaymentPrepared(PaymentId.random(), RentalPricing.PRICE, reference));
            awaitListed(reference);

            // when judged from the present
            sweeper.cancelOverduePayments(Instant.now());

            // then it is still waiting, because its timeout has not elapsed
            assertThat(pending.findById(reference.raw()))
                    .describedAs("a payment inside its timeout must not be swept")
                    .isPresent();
        }

        /**
         * The sweep writes nothing and the command it sends is idempotent, so running it repeatedly has to be
         * harmless. The scheduled trigger relies on that, and so does every instance in a cluster.
         */
        @Test
        void givenAlreadySweptPayment_whenSweepingAgain_thenNothingBreaks() {
            // given a payment already called off by an earlier pass
            var reference = someReference();
            fixture.given()
                   .events(new PaymentPrepared(PaymentId.random(), RentalPricing.PRICE, reference));
            awaitListed(reference);
            sweeper.cancelOverduePayments(Instant.now().plus(Duration.ofHours(1)));
            awaitNotListed(reference);

            // when / then a second pass finds nothing to do and says so quietly
            sweeper.cancelOverduePayments(Instant.now().plus(Duration.ofHours(1)));
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
