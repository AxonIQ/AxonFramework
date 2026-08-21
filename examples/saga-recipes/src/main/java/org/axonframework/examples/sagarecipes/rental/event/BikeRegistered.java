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
import org.axonframework.examples.sagarecipes.rental.RentalTags;

/**
 * A bike became part of the fleet and is available for rent.
 * <p>
 * This is the only rental event without a rental identifier: no request exists yet. That is why the saga never
 * handles it, and why it is absent from the saga's sequencing policy.
 *
 * @param bikeId   the bike that was registered
 * @param bikeType the kind of bike, for example {@code city} or {@code mountain}
 * @param location where the bike was placed
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public record BikeRegistered(
        @EventTag(key = RentalTags.BIKE_ID) BikeId bikeId,
        String bikeType,
        String location
) {

}
