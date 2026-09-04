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

package sagas.verticalslices;

import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.core.sequencing.PropertySequencingPolicy;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.springframework.stereotype.Component;
import sagas.shared.RentalPaymentApi.BikeRequested;
import sagas.shared.RentalPaymentApi.PreparePayment;

import java.util.concurrent.CompletableFuture;

import static sagas.shared.RentalPaymentApi.PRICE;
import static sagas.shared.RentalPaymentApi.paymentReferenceFor;

// tag::stateless[]
@Component
@SequencingPolicy(type = PropertySequencingPolicy.class, parameters = "rentalId") // <1>
public class WhenBikeRequestedThenPreparePayment {

    @EventHandler
    CompletableFuture<?> react(BikeRequested event, CommandDispatcher dispatcher) {
        return dispatcher.send(new PreparePayment(paymentReferenceFor(event.rentalId()), PRICE))
                         .getResultMessage(); // <2>
    }
}
// end::stateless[]
