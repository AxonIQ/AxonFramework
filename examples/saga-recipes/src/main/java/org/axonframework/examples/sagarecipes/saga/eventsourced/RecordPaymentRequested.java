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

package org.axonframework.examples.sagarecipes.saga.eventsourced;

import org.axonframework.examples.sagarecipes.payment.Amount;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * Tells the process to write down that it asked for payment.
 * <p>
 * A command that exists only to record a fact is a fair thing to be suspicious of. It is here because Event Modelling
 * asks that every event be produced by a command, which keeps the process's own write on the model as a write slice
 * rather than as a side effect hidden inside an event handler. The alternative is
 * {@code saga.eventsourced}, which appends directly and is shorter.
 *
 * @param rentalId the rental being paid for
 * @param bikeId   the bike it concerns
 * @param renter   who is renting
 * @param amount   what was asked for
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public record RecordPaymentRequested(
        @TargetEntityId RentalId rentalId,
        BikeId bikeId,
        String renter,
        Amount amount
) {

}
