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
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.RentalTags;

/**
 * The process finished.
 * <p>
 * This is the Axon Framework 5 equivalent of {@code @EndSaga}, and the difference is instructive: ending is a fact
 * the process records rather than a lifecycle callback the framework performs. Once written, every handler
 * short-circuits on it.
 * <p>
 * It also leaves an audit trail the other recipes cannot produce. A repository row is deleted when the process ends
 * and derived state was never written at all, so neither can answer "how did this rental turn out, and when".
 *
 * @param rentalId the rental whose process finished
 * @param outcome  how it turned out
 * @author Axon Framework
 * @since 5.4.0
 */
public record RentalPaymentProcessCompleted(
        @EventTag(key = RentalTags.RENTAL_ID) RentalId rentalId,
        Outcome outcome
) {

    /**
     * How a rental payment process ended.
     */
    public enum Outcome {

        /**
         * The payment arrived and the bike was handed over.
         */
        APPROVED,

        /**
         * The payment did not arrive, was refused, or was called off, and the bike was released.
         */
        REJECTED
    }
}
