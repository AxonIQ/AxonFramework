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

package org.axonframework.examples.sagarecipes.rental.write.approverequest;

import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * Confirms a rental request once its payment has been paid.
 * <p>
 * Keeps the Axon Framework 4 signature: the command targets the bike and names the renter. Because no entity is
 * keyed by the rental itself, the sender has to know both, which is exactly the state a saga exists to hold.
 *
 * @param bikeId the bike whose reservation is being confirmed
 * @param renter who reserved it
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public record ApproveRequest(@TargetEntityId BikeId bikeId, String renter) {

}
