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

package org.axonframework.examples.sagarecipes.saga.shared;

import org.axonframework.examples.sagarecipes.payment.Amount;

/**
 * What a rental costs.
 * <p>
 * A flat rate, as in the bike rental sample application. Pricing lives with the saga because deciding how much to ask
 * for is part of running the process: the rental context does not know it is paid for, and the payment context is
 * told the amount rather than working it out.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public final class RentalPricing {

    /**
     * The flat price of a rental.
     */
    public static final Amount PRICE = Amount.of(10);

    private RentalPricing() {
        // Utility class, not meant to be instantiated.
    }
}
