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

package org.axonframework.examples.sagarecipes.saga.repository;

import org.axonframework.examples.sagarecipes.payment.write.preparepayment.PreparePayment;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.event.BikeRegistered;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageDispatchInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.test.fixture.AxonTestFixture;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves the claim this recipe is built on: the row it writes and the tracking token commit together, so a step that
 * cannot finish leaves nothing behind.
 * <p>
 * Every command this process sends is idempotent by design, which means none of them can fail on their own. The
 * failure therefore has to be injected, and a {@link MessageDispatchInterceptor} is the least invasive place to do it:
 * it rejects the command before any of the payment context runs, so nothing but the saga's own unit of work is
 * involved in what the assertion observes.
 * <p>
 * Reading the outcome is unambiguous because the handler guards on the row it wrote. If the write survived the failed
 * step, the redelivered event finds {@code paymentRequested} already set, reports success, lets the token advance and
 * leaves the row in place forever -- a process wedged having never asked for payment. If the write rolled back, no row
 * ever appears no matter how many times the event is redelivered. So a present row means broken atomicity and an
 * absent row means the transaction did its job.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@AxonSpringBootTest(properties = {
        "saga.recipe=repository",
        // A rejected batch is retried only after the coordinator's next attempt to claim a token, which by default
        // is five seconds away. This test is the only one that waits for a retry, and at the default it spends
        // almost all its time asleep. The key "..default" applies to every processor in this context.
        "axon.eventhandling.processors[..default].token-claim-interval=200"
})
class ProcessStateRollbackTest {

    /**
     * Marks the one rental whose payment must not go through. Scoping the failure this narrowly keeps the interceptor
     * from disturbing anything else that shares this context.
     */
    private static final String DOOMED_RENTAL = "doomed-" + UUID.randomUUID();

    @Autowired
    private AxonTestFixture fixture;

    @Autowired
    private PaymentSagaStateRepository repository;

    @Autowired
    private RejectedPayments rejectedPayments;

    @Test
    void givenPreparePaymentAlwaysFails_whenBikeRequested_thenTheProcessStoresNothing() {
        // given a rental whose PreparePayment the interceptor will reject
        var bikeId = BikeId.random();
        var rentalId = RentalId.of(DOOMED_RENTAL);

        // when
        fixture.given()
               .events(new BikeRegistered(bikeId, "city", "Vilnius"),
                       new BikeRequested(bikeId, "renter-" + UUID.randomUUID(), rentalId));

        // then the process did run and did ask for payment, so what follows is about a rejected step rather than
        // about a saga that never woke up
        await().alias("the process asking for payment")
               .atMost(Duration.ofSeconds(10))
               .until(() -> rejectedPayments.count() >= 1);

        // and the rejected step committed nothing. No waiting is needed to know that: the row was written inside
        // the unit of work, so it was never visible to a reader outside it and never will be.
        assertThat(repository.findById(rentalId.raw())).isEmpty();

        // and the event kept coming back, which it can only do while the tracking token stays where it was
        await().alias("the event being redelivered")
               .atMost(Duration.ofSeconds(10))
               .until(() -> rejectedPayments.count() >= 2);

        // and a second attempt, which had every chance to leave something behind, also stored nothing
        assertThat(repository.findById(rentalId.raw())).isEmpty();
    }

    /**
     * Counts the rejections, so the test can tell a rolled-back write apart from a process that never ran.
     */
    static class RejectedPayments {

        private final AtomicInteger count = new AtomicInteger();

        int count() {
            return count.get();
        }

        void record() {
            count.incrementAndGet();
        }
    }

    /**
     * Present only in this test class, which gives it a Spring context of its own and keeps the injected failure away
     * from the recipe's other tests.
     */
    @TestConfiguration
    static class FailingPaymentConfiguration {

        @Bean
        RejectedPayments rejectedPayments() {
            return new RejectedPayments();
        }

        @Bean
        MessageDispatchInterceptor<CommandMessage> failPreparePaymentForDoomedRental(RejectedPayments rejected) {
            return new MessageDispatchInterceptor<>() {
                @Override
                public MessageStream<?> interceptOnDispatch(
                        CommandMessage message,
                        @Nullable ProcessingContext context,
                        MessageDispatchInterceptorChain<CommandMessage> chain
                ) {
                    if (message.payload() instanceof PreparePayment command
                            && command.paymentReference().raw().equals(DOOMED_RENTAL)) {
                        rejected.record();
                        return MessageStream.failed(new IllegalStateException("Payment provider unreachable"));
                    }
                    return chain.proceed(message, context);
                }
            };
        }
    }
}
