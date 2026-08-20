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

/**
 * Composite identifier for the decision model of the request-bike slice.
 * <p>
 * Requesting a bike has to satisfy two rules at once: the bike must be free, and the renter must not already hold a
 * bike. Neither a bike nor a renter alone is a big enough consistency boundary, so this slice sources both and
 * appends against the union of the two. That is the whole point of a Dynamic Consistency Boundary: the boundary is
 * chosen per decision instead of being fixed by an aggregate.
 *
 * @param bikeId the bike being requested
 * @param renter the person requesting it
 * @author Axon Framework
 * @since 5.3.0
 */
public record RentalRequestId(BikeId bikeId, String renter) {

}
