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

package org.axonframework.examples.sagarecipes.payment;

/**
 * How much is to be paid.
 * <p>
 * Kept as a whole number of currency units, matching the flat price the Axon Framework 4 demo used. Money modelling
 * is not what this example is about.
 *
 * @param value the amount to pay, always positive
 * @author Axon Framework
 * @since 5.4.0
 */
public record Amount(int value) {

    /**
     * Compact constructor rejecting non-positive amounts.
     */
    public Amount {
        if (value <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }

    /**
     * Creates an amount from the given {@code value}.
     *
     * @param value the amount to pay
     * @return an amount wrapping {@code value}
     */
    public static Amount of(int value) {
        return new Amount(value);
    }
}
