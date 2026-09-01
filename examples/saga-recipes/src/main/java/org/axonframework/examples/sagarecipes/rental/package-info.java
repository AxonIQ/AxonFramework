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
 * The rental context: bikes, and who is holding one.
 * <p>
 * Nothing in this package knows that payments exist, in the same way that the payment context knows nothing about
 * bikes. The two never import each other, and the same ArchUnit test guards both directions. Waiting for payment
 * before confirming a rental is the process's business, not this context's.
 * <p>
 * <b>There is no rental entity.</b> Commands target a bike, and the rental is the process. That is deliberate: giving
 * the rental an identity of its own would let the process address its commands at a single entity, leaving it with
 * nothing to remember, and this module would then demonstrate a solution to a problem it had just designed away.
 * Keeping {@code ApproveRequest(bikeId, renter)} keeps the process's job real, because {@code bikeId} and
 * {@code renter} are exactly what no entity here owns.
 * <p>
 * <b>Worth noticing before reaching for a process at all:</b> if you can give the process an identity of its own, you
 * may not need one. That refactoring is legitimate and often the right answer. The recipes given in this project cover
 * the case where it is not available to you.
 * <p>
 * Three tags are written, and the third is the interesting one:
 * <ul>
 *     <li>{@link org.axonframework.examples.sagarecipes.rental.RentalTags#BIKE_ID}, which every slice sources on.</li>
 *     <li>{@link org.axonframework.examples.sagarecipes.rental.RentalTags#RENTAL_ID}, carried on every event so the
 *     process can correlate. Only the first event needs it to correlate, but tagging all of them costs nothing now
 *     and cannot be added to an existing stream later.</li>
 *     <li>{@link org.axonframework.examples.sagarecipes.rental.RentalTags#RENTER}, which buys a rule no
 *     single-entity model can express: a renter may hold at most one bike at a time. The {@code requestbike} slice
 *     enforces it by sourcing across two tags at once, and that cross-entity invariant is the reason the Dynamic
 *     Consistency Boundary exists.</li>
 * </ul>
 * Each command lives in its own {@code write/} folder with its handler and its own decision model, so a slice sources
 * exactly what its rule needs rather than sharing one aggregate-shaped model with the others.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
package org.axonframework.examples.sagarecipes.rental;
