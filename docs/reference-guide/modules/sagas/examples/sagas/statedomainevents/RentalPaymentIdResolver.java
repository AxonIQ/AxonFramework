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

package sagas.statedomainevents;

import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.modelling.EntityIdResolutionException;
import org.axonframework.modelling.EntityIdResolver;
import sagas.shared.RentalPaymentApi.BikeInUse;
import sagas.shared.RentalPaymentApi.BikeRequested;
import sagas.shared.RentalPaymentApi.PaymentCancelled;
import sagas.shared.RentalPaymentApi.PaymentConfirmed;
import sagas.shared.RentalPaymentApi.PaymentPrepared;
import sagas.shared.RentalPaymentApi.PaymentRejected;
import sagas.shared.RentalPaymentApi.RequestRejected;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static sagas.shared.RentalPaymentApi.rentalIdFor;

/**
 * Works out which process an event belongs to when the two contexts name their correlation differently.
 */
// tag::resolver[]
public class RentalPaymentIdResolver implements EntityIdResolver<String> {

    private static final Map<QualifiedName, BiFunction<Message, EventConverter, String>> RESOLVERS = Map.of(
            new QualifiedName(BikeRequested.class),
            (message, converter) -> message.payloadAs(BikeRequested.class, converter).rentalId(),
            new QualifiedName(BikeInUse.class),
            (message, converter) -> message.payloadAs(BikeInUse.class, converter).rentalId(),
            new QualifiedName(RequestRejected.class),
            (message, converter) -> message.payloadAs(RequestRejected.class, converter).rentalId(),
            new QualifiedName(PaymentPrepared.class),
            (message, converter) -> rentalIdFor(message.payloadAs(PaymentPrepared.class, converter)
                                                       .paymentReference()),
            new QualifiedName(PaymentConfirmed.class),
            (message, converter) -> rentalIdFor(message.payloadAs(PaymentConfirmed.class, converter)
                                                       .paymentReference()),
            new QualifiedName(PaymentRejected.class),
            (message, converter) -> rentalIdFor(message.payloadAs(PaymentRejected.class, converter)
                                                       .paymentReference()),
            new QualifiedName(PaymentCancelled.class),
            (message, converter) -> rentalIdFor(message.payloadAs(PaymentCancelled.class, converter)
                                                       .paymentReference())
    );

    @Override
    public String resolve(Message message, ProcessingContext context) throws EntityIdResolutionException {
        var resolver = RESOLVERS.get(message.type().qualifiedName()); // <1>
        if (resolver == null) {
            throw new EntityIdResolutionException(message.payloadType(), List.of());
        }
        return resolver.apply(message, context.component(EventConverter.class)); // <2>
    }
}
// end::resolver[]
