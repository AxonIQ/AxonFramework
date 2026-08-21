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
 * The rental payment process, taken apart.
 * <p>
 * The other recipes keep the process in one class. This one asks whether it needs to be a thing at all, and answers
 * no: what looked like a saga is a handful of independent reactions, each a slice of its own in the Event Modelling
 * sense of "when this happens, do that".
 * <p>
 * Each slice is a to-do list. The important observation is how few of them need somewhere to keep it:
 * <ul>
 *     <li>{@code whenbikerequestedthenpreparepayment} and {@code whenrequestrejectedthencancelpayment} are
 *     completely stateless. The trigger event carries everything the command needs, so the processor's tracking
 *     token <em>is</em> the to-do list: everything before it is done, everything after it is not.</li>
 *     <li>{@code whencancelrentalpaymentthencancelpayment} is stateless for a different reason. It could check
 *     whether cancelling is still worthwhile, but the payment context checks anyway, and that check is the one that
 *     holds under a race. Asking twice would only duplicate a decision that is not this slice's to make.</li>
 *     <li>The three slices triggered by payment events cannot be stateless. A payment event carries only the
 *     reference, and approving or rejecting a request needs the bike and the renter, so each keeps a small lookup of
 *     its own. Slices are independent, so none of them shares another's.</li>
 * </ul>
 * <p>
 * The transactional profile is the best of any recipe: every slice has exactly one effect. The lookups are reads, not
 * writes, so there is no second thing that could fall out of step with the tracking token, and the ordering rule the
 * other recipes have to observe simply does not apply.
 * <p>
 * <b>Each slice gets an event processor of its own</b>, because a handler is assigned to a processor named after its
 * package unless something says otherwise, and every slice has a package of its own. That default happens to be the
 * right answer here, and it buys something the other recipes cannot have.
 * <p>
 * A processor that sees one event type needs no custom sequencing policy. The other recipes handle five event types
 * from two contexts in one component, and those contexts name their correlation differently -- rental events carry a
 * {@code rentalId}, payment events carry a {@code paymentReference} -- so no single property spans them and
 * {@link org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentSequencingPolicy} has to route on
 * {@code QualifiedName} to bridge the two vocabularies. Here each slice knows exactly one event type, so the built-in
 * {@link org.axonframework.messaging.core.sequencing.PropertySequencingPolicy} does the job with a property name and
 * nothing else. Slice finely enough and the custom infrastructure disappears.
 * <p>
 * The cost is that nothing orders one slice against another. Two reactions to the same rental can run at once, and
 * whether that is safe is decided downstream rather than here: exactly one of confirmed, rejected or cancelled is
 * ever recorded for a payment, and the rental context guards its own commands and appends under a consistency
 * condition. Both of those would have to hold anyway, since neither context assumes a single reader.
 * <p>
 * What this costs is a whole view of the process. No single file describes the rental payment flow any more; it lives
 * in the arrangement of six slices, and only an event model shows it as one thing. That is a real trade, and whether
 * it is worth making depends on whether the process is something people reason about as a unit.
 * <p>
 * Note that ending does not arise here. There is nothing to end, because there was never anything running: each slice
 * reacts and stops.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
package org.axonframework.examples.sagarecipes.saga.automations;
