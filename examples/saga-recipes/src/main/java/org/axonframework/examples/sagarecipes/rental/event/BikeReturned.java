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
 * The renter brought the bike back. It is available again, and the renter may request another one.
 *
 * @param bikeId   the bike that was returned
 * @param renter   who returned it
 * @param rentalId the request being closed out
 * @param location where the bike was left
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public record BikeReturned(
        @EventTag(key = RentalTags.BIKE_ID) BikeId bikeId,
        @EventTag(key = RentalTags.RENTER) String renter,
        @EventTag(key = RentalTags.RENTAL_ID) RentalId rentalId,
        String location
) {

}
