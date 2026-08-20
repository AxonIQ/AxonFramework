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
import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * Asks for a bike on behalf of a renter.
 * <p>
 * The caller supplies the {@code rentalId}. Axon Framework 4 minted it inside the handler, which made a retried
 * request produce a second reservation; supplying it makes this command idempotent.
 *
 * @param bikeId   the bike being requested
 * @param renter   who is requesting it
 * @param rentalId identifies this request, chosen by the caller
 * @author Axon Framework
 * @since 5.3.0
 */
public record RequestBike(BikeId bikeId, String renter, RentalId rentalId) {

    /**
     * Routes this command to the composite decision model spanning the bike and the renter.
     *
     * @return the identifier of the decision model this command targets
     */
    @TargetEntityId
    public RentalRequestId rentalRequestId() {
        return new RentalRequestId(bikeId, renter);
    }
}
