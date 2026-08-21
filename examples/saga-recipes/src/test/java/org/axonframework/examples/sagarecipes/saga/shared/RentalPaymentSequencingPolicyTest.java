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

package org.axonframework.examples.sagarecipes.saga.shared;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.examples.sagarecipes.payment.Amount;
import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.event.PaymentCancelled;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.event.BikeInUse;
import org.axonframework.examples.sagarecipes.rental.event.BikeRegistered;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.extension.springboot.test.AxonSpringBootTest;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The policy exists so that every event of one rental is handled in order. These cases pin the property that makes
 * that work: a rental event and a payment event belonging to the same process must resolve to sequence identifiers
 * that are equal.
 * <p>
 * Every message here is built from a serialized payload rather than a plain object. That is not incidental.
 * {@code GenericMessage} short-circuits {@code payloadAs} when the requested type is already assignable from the
 * payload, so a test using objects would never invoke the converter and would pass even if conversion were
 * impossible in production, where events arrive from the store as bytes.
 */
@AxonSpringBootTest
class RentalPaymentSequencingPolicyTest {

    private final RentalPaymentSequencingPolicy policy = new RentalPaymentSequencingPolicy();

    @Autowired
    private AxonConfiguration configuration;

    @Test
    void givenRentalAndPaymentEventsOfOneProcess_thenSequenceIdentifiersAreEqual() {
        // given the two contexts keep the correlation under different property names
        var rentalId = RentalId.random();
        var reference = RentalPaymentReference.forRental(rentalId);
        var requested = new BikeRequested(BikeId.random(), "alice", rentalId);
        var confirmed = new PaymentConfirmed(PaymentId.random(), reference);

        // when
        var fromRental = sequenceIdentifierFor(requested);
        var fromPayment = sequenceIdentifierFor(confirmed);

        // then both sides must land in the same sequence, or the policy silently does nothing
        assertThat(fromRental).isPresent().isEqualTo(fromPayment);
    }

    @Test
    void givenEveryHandledEventType_thenAllResolveToTheSameSequenceIdentifier() {
        // given one rental seen through all the event types the saga reacts to
        var rentalId = RentalId.random();
        var bikeId = BikeId.random();
        var reference = RentalPaymentReference.forRental(rentalId);
        var paymentId = PaymentId.random();
        var expected = Optional.of(rentalId.raw());

        // when / then
        assertThat(sequenceIdentifierFor(new BikeRequested(bikeId, "alice", rentalId))).isEqualTo(expected);
        assertThat(sequenceIdentifierFor(new BikeInUse(bikeId, "alice", rentalId))).isEqualTo(expected);
        assertThat(sequenceIdentifierFor(new PaymentPrepared(paymentId, Amount.of(10), reference))).isEqualTo(expected);
        assertThat(sequenceIdentifierFor(new PaymentConfirmed(paymentId, reference))).isEqualTo(expected);
        assertThat(sequenceIdentifierFor(new PaymentCancelled(paymentId, reference))).isEqualTo(expected);
    }

    @Test
    void givenDifferentRentals_thenSequenceIdentifiersDiffer() {
        // given two unrelated rentals, which must be free to be handled concurrently
        var first = new BikeRequested(BikeId.random(), "alice", RentalId.random());
        var second = new BikeRequested(BikeId.random(), "bob", RentalId.random());

        // when / then
        assertThat(sequenceIdentifierFor(first)).isNotEqualTo(sequenceIdentifierFor(second));
    }

    /**
     * An event the saga does not handle carries no sequencing requirement, which leaves the processor free to handle
     * it however it likes.
     */
    @Test
    void givenUnmappedEvent_thenNoSequenceIdentifier() {
        // given a bike registration, which happens before any rental exists

        // when / then
        assertThat(sequenceIdentifierFor(new BikeRegistered(BikeId.random(), "city", "Vilnius"))).isEmpty();
    }

    /**
     * Builds the message the way the event store delivers one: a {@code byte[]} payload plus a converter, never a
     * ready-made object.
     */
    private Optional<Object> sequenceIdentifierFor(Object payload) {
        var converter = configuration.getComponent(EventConverter.class);
        var type = configuration.getComponent(MessageTypeResolver.class).resolveOrThrow(payload);
        Message serialized = new GenericEventMessage(type, converter.convert(payload, byte[].class))
                .withConverter(converter);

        return configuration.getComponent(UnitOfWorkFactory.class)
                            .create()
                            .executeWithResult(context -> CompletableFuture.completedFuture(
                                    policy.sequenceIdentifierFor(serialized, context)))
                            .join();
    }
}
