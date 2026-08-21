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

package org.axonframework.examples.sagarecipes.saga.eventsourced.event;

import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.examples.sagarecipes.payment.Amount;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.RentalTags;

/**
 * The process asked for payment.
 * <p>
 * An event about the process itself, not about renting or paying. It exists so the process can remember what it did
 * without a table, and it carries the bike and the renter because no entity in either context is keyed by a rental
 * and could be asked later.
 * <p>
 * That the process records its own facts is what makes this recipe work where the derived-state recipe cannot: it
 * needs nothing from the payment context beyond being told that something happened.
 *
 * @param rentalId the rental being paid for
 * @param bikeId   the bike it concerns
 * @param renter   who is renting
 * @param amount   what was asked for
 * @author Axon Framework
 * @since 5.4.0
 */
public record RentalPaymentRequested(
        @EventTag(key = RentalTags.RENTAL_ID) RentalId rentalId,
        BikeId bikeId,
        String renter,
        Amount amount
) {

}
