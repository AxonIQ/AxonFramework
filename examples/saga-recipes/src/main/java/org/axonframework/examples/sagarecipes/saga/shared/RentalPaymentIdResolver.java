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
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.event.BikeInUse;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.rental.event.RequestRejected;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.modelling.EntityIdResolutionException;
import org.axonframework.modelling.EntityIdResolver;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Works out which rental payment process an event belongs to.
 * <p>
 * A saga reacts to events from two contexts that name their correlation differently: rental events carry a
 * {@code rentalId}, payment events carry a {@code paymentReference}. Neither the property-name shortcut of
 * {@code @InjectEntity(idProperty = ...)} nor the default {@code @TargetEntityId} lookup can span that, so the saga
 * resolves the identifier itself. Being the only component that can is, once again, the point of the saga.
 * <p>
 * Routing on the {@link QualifiedName} rather than trying types in turn is the same choice, for the same reasons, as
 * in {@link RentalPaymentSequencingPolicy}: the name is available without touching the payload, and each event is
 * converted exactly once, to its own concrete type. Converting to a shared supertype would work in memory and fail
 * against a real event store, where the payload arrives as bytes.
 *
 * @author Axon Framework
 * @since 5.4.0
 */
public class RentalPaymentIdResolver implements EntityIdResolver<RentalId> {

    private static final Map<QualifiedName, BiFunction<Message, EventConverter, RentalId>> RESOLVERS = Map.of(
            new QualifiedName(BikeRequested.class),
            (message, converter) -> message.payloadAs(BikeRequested.class, converter).rentalId(),
            new QualifiedName(BikeInUse.class),
            (message, converter) -> message.payloadAs(BikeInUse.class, converter).rentalId(),
            new QualifiedName(RequestRejected.class),
            (message, converter) -> message.payloadAs(RequestRejected.class, converter).rentalId(),
            new QualifiedName(PaymentPrepared.class),
            (message, converter) -> RentalPaymentReference.toRental(
                    message.payloadAs(PaymentPrepared.class, converter).paymentReference()),
            new QualifiedName(PaymentConfirmed.class),
            (message, converter) -> RentalPaymentReference.toRental(
                    message.payloadAs(PaymentConfirmed.class, converter).paymentReference()),
            new QualifiedName(PaymentRejected.class),
            (message, converter) -> RentalPaymentReference.toRental(
                    message.payloadAs(PaymentRejected.class, converter).paymentReference()),
            new QualifiedName(PaymentCancelled.class),
            (message, converter) -> RentalPaymentReference.toRental(
                    message.payloadAs(PaymentCancelled.class, converter).paymentReference())
    );

    @Override
    public RentalId resolve(Message message, ProcessingContext context) throws EntityIdResolutionException {
        var resolver = RESOLVERS.get(message.type().qualifiedName());
        if (resolver == null) {
            // Only reachable if a handler subscribes to an event this resolver was never taught about.
            throw new EntityIdResolutionException(message.payloadType(), List.of());
        }
        return resolver.apply(message, context.component(EventConverter.class));
    }
}
