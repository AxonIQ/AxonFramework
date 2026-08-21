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

package org.axonframework.examples.sagarecipes.saga.eventsourced;

import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.saga.eventsourced.event.RentalPaymentProcessCompleted.Outcome;
import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * Tells the process to write down that it finished.
 *
 * @param rentalId the rental whose process finished
 * @param outcome  how it turned out
 * @author Axon Framework
 * @since 5.4.0
 */
public record RecordProcessCompleted(@TargetEntityId RentalId rentalId, Outcome outcome) {

}
