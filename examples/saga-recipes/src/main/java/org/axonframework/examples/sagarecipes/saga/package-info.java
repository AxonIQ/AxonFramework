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

/**
 * The rental payment process: everything that knows both about renting and about paying.
 * <p>
 * Neither context imports the other, and neither imports this package. Only code here may reach into both, which is
 * what makes this the saga. Its single piece of privileged knowledge is that a rental identifier and a payment
 * reference are the same value, expressed in
 * {@link org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference}.
 * <p>
 * The same process is implemented several times over. Exactly one implementation is active at a time, chosen with the
 * {@code saga.recipe} property, because otherwise every one of them would dispatch the same commands. A shared
 * contract test runs the identical scenarios against each, which is what turns "these are interchangeable" from a
 * claim into something the build checks.
 * <p>
 * What every implementation has to get right, whatever state it keeps:
 * <ol>
 *     <li><b>Commands it sends must be idempotent.</b> An event processor delivers at least once, so every command
 *     the process dispatches will sometimes arrive twice. The rental and payment contexts absorb that by appending
 *     nothing when a request or payment has already settled.</li>
 *     <li><b>Progress must not be recorded before the command succeeds.</b> There is no transaction spanning
 *     "dispatch a command" and "write my state". Recording first and dispatching second is the bug that wedges a
 *     process permanently: the dispatch fails, the tracking token does not advance, the event is redelivered, and the
 *     recorded progress now makes the process skip the work it never did.</li>
 *     <li><b>The handler must return its {@code CompletableFuture}.</b> That is what makes the processor wait for the
 *     command and leave the token where it is on failure. Dropping the {@code return} turns the whole thing into
 *     fire-and-forget, silently, and is what replaces Axon Framework 4's {@code retryPayment} deadline.</li>
 * </ol>
 * <p>
 * Note what is <em>not</em> in this list. Ending the process is not a rule but a choice, and each recipe makes it
 * differently: delete a row, append a completion event, or simply let a predicate over existing events answer the
 * question. Axon Framework 4 had one answer, {@code @EndSaga}, because it had one way of holding state.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
package org.axonframework.examples.sagarecipes.saga;
