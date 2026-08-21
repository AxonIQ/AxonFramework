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

package org.axonframework.examples.sagarecipes.rental.write.requestbike;

import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;

/**
 * Composite identifier for the decision model of the request-bike slice.
 * <p>
 * Requesting a bike has to satisfy three things at once: the bike must be free, the renter must not already hold a
 * bike, and this exact request must not have been handled before. No single one of a bike, a renter or a rental is a
 * big enough consistency boundary, so this slice sources all three and appends against their union. That is the
 * whole point of a Dynamic Consistency Boundary: the boundary is chosen per decision instead of being fixed by an
 * aggregate.
 * <p>
 * Only {@code rentalId} identifies a rental. The other two components widen the boundary rather than narrow the
 * identity: the same renter may rent the same bike any number of times, and every one of those requests carries its
 * own {@code rentalId}.
 *
 * @param bikeId   the bike being requested
 * @param renter   the person requesting it
 * @param rentalId identifies this individual request
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public record RentalRequestId(BikeId bikeId, String renter, RentalId rentalId) {

}
