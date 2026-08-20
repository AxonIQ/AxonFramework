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

package org.axonframework.examples.sagarecipes.rental.write.rejectrequest;

import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * Turns down a rental request and releases the bike.
 * <p>
 * Keeps the Axon Framework 4 signature. Sent by the saga when the payment is rejected or cancelled.
 *
 * @param bikeId the bike to release
 * @param renter who had reserved it
 * @author Axon Framework
 * @since 5.3.0
 */
public record RejectRequest(@TargetEntityId BikeId bikeId, String renter) {

}
