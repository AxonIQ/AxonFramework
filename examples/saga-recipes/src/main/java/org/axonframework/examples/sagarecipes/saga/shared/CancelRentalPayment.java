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

import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * Asks the rental payment process to give up waiting for payment.
 * <p>
 * This is how a timeout is expressed without a deadline manager. Rather than scheduling a callback into the saga's own
 * future, the timeout becomes an ordinary command that anyone may send at any moment: the scheduled sweep in {@code saga.deadline}, an operator, a test, or a REST call.
 * <p>
 * Turning a deadline into a command has a pleasant side effect. A scheduled callback is invisible and can only be
 * triggered by waiting, whereas a command can be sent deliberately, which is what makes the timeout path testable
 * without any ability to manipulate time.
 * <p>
 * It is addressed to the process rather than to the payment context, because whether giving up is still appropriate
 * is the process's judgement to make. The payment context checks again anyway, and that second check is the one that
 * actually holds under a race.
 *
 * @param rentalId the rental whose payment should be given up on
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public record CancelRentalPayment(@TargetEntityId RentalId rentalId) {

}
