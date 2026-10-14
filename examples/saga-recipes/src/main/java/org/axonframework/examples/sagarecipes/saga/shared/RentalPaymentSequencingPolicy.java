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

import org.axonframework.examples.sagarecipes.payment.event.PaymentCancelled;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.examples.sagarecipes.rental.event.BikeInUse;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.rental.event.RequestRejected;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.sequencing.ExtractionSequencingPolicy;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;

import java.util.Map;

/**
 * Sequences every event of one rental payment process behind a single identifier.
 * <p>
 * A pooled streaming processor may handle many rentals at once, but the events of one rental must be handled in
 * order. Without this, a {@code PaymentPrepared} could still be in flight when the {@code PaymentConfirmed} that
 * supersedes it is already being processed.
 * <p>
 * Rental events keep the correlation in {@code rentalId} and payment events keep it in {@code paymentReference}, so
 * no single {@code PropertySequencingPolicy} spans both. Three details make this table what it is:
 * <ul>
 *     <li>Every extractor yields the raw {@link String}. Returning {@code RentalId} from one side and
 *     {@code PaymentReference} from the other would never compare equal, the two contexts would land in separate
 *     sequences, and the policy would quietly do nothing.</li>
 *     <li>Each entry names a concrete payload type. Targeting a shared supertype would work in memory, where the
 *     payload is already an object, and fail against a real event store, where it arrives as bytes that a converter
 *     cannot turn into an interface.</li>
 *     <li>{@code BikeRegistered} is absent. It has no rental identifier yet and the saga never handles it, so it
 *     correctly carries no sequencing requirement.</li>
 * </ul>
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public class RentalPaymentSequencingPolicy extends QualifiedNameRoutingSequencingPolicy {

    /**
     * Initializes the policy. A no-argument constructor is required for use through
     * {@link org.axonframework.messaging.core.annotation.SequencingPolicy @SequencingPolicy}.
     */
    public RentalPaymentSequencingPolicy() {
        super(routingTable());
    }

    private static Map<QualifiedName, SequencingPolicy<? super Message>> routingTable() {
        return Map.of(
                new QualifiedName(BikeRequested.class),
                new ExtractionSequencingPolicy<>(BikeRequested.class, event -> event.rentalId().raw()),
                new QualifiedName(BikeInUse.class),
                new ExtractionSequencingPolicy<>(BikeInUse.class, event -> event.rentalId().raw()),
                new QualifiedName(RequestRejected.class),
                new ExtractionSequencingPolicy<>(RequestRejected.class, event -> event.rentalId().raw()),
                new QualifiedName(PaymentPrepared.class),
                new ExtractionSequencingPolicy<>(PaymentPrepared.class, event -> event.paymentReference().raw()),
                new QualifiedName(PaymentConfirmed.class),
                new ExtractionSequencingPolicy<>(PaymentConfirmed.class, event -> event.paymentReference().raw()),
                new QualifiedName(PaymentRejected.class),
                new ExtractionSequencingPolicy<>(PaymentRejected.class, event -> event.paymentReference().raw()),
                new QualifiedName(PaymentCancelled.class),
                new ExtractionSequencingPolicy<>(PaymentCancelled.class, event -> event.paymentReference().raw())
        );
    }
}
