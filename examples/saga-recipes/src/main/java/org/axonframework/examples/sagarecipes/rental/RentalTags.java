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

package org.axonframework.examples.sagarecipes.rental;

/**
 * Tag keys used by the rental context.
 * <p>
 * Tags are what a Dynamic Consistency Boundary selects on. Every rental event carries all three, which lets each
 * slice pick the narrowest boundary its rule needs: {@link #BIKE_ID} for "is this bike available",
 * {@link #RENTER} for "does this renter already hold a bike", and {@link #RENTAL_ID} to correlate a single request
 * across its whole lifetime.
 * <p>
 * {@link #RENTER} costs nothing to add now and cannot be added retroactively to an existing event stream, which is
 * why it is present from the start even though only one slice currently uses it.
 *
 * @author Axon Framework
 * @since 5.3.0
 */
public final class RentalTags {

    /**
     * Identifies the bike a rental event concerns.
     */
    public static final String BIKE_ID = "bikeId";

    /**
     * Identifies the individual rental request a rental event belongs to.
     */
    public static final String RENTAL_ID = "rentalId";

    /**
     * Identifies the person renting. Selecting on this key spans every bike that renter has ever touched.
     */
    public static final String RENTER = "renter";

    private RentalTags() {
        // Utility class, not meant to be instantiated.
    }
}
