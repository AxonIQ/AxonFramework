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

package org.axonframework.examples.sagarecipes.rental.event;

import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.RentalTags;

/**
 * A renter asked for a bike. The bike is now reserved, but the rental is not yet confirmed.
 * <p>
 * This event starts the rental payment process. It is also the only place the pairing of {@code bikeId} and
 * {@code renter} is recorded, which is exactly the state a saga has to keep in order to send
 * {@code ApproveRequest} later.
 *
 * @param bikeId   the bike being requested
 * @param renter   who is requesting it
 * @param rentalId identifies this request for the rest of its lifetime
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public record BikeRequested(
        @EventTag(key = RentalTags.BIKE_ID) BikeId bikeId,
        @EventTag(key = RentalTags.RENTER) String renter,
        @EventTag(key = RentalTags.RENTAL_ID) RentalId rentalId
) {

}
