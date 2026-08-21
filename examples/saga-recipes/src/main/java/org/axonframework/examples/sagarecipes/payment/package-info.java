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
 * A generic payment context. It can be paid for anything.
 * <p>
 * Nothing in this package knows that bikes exist. There is no rental identifier, no rental type, no rental tag and
 * no import from the rental context. What the caller gets instead is an opaque
 * {@link org.axonframework.examples.sagarecipes.payment.PaymentReference} that this context stores and echoes back on
 * every event without ever interpreting it. Only the saga knows that a payment reference happens to be a rental
 * identifier, and that is precisely the saga's job: to be the single component that knows both sides. An ArchUnit
 * test enforces the rule, because it is the one design property this module cannot afford to lose by accident.
 * <p>
 * Two identifiers appear here, and they are not interchangeable:
 * <ul>
 *     <li>{@link org.axonframework.examples.sagarecipes.payment.PaymentId} is this context's own identity for a
 *     payment. It mints the value itself. Whoever is paying uses it: {@code ConfirmPayment}, {@code RejectPayment}.</li>
 *     <li>{@link org.axonframework.examples.sagarecipes.payment.PaymentReference} is the caller's key. Whoever
 *     ordered the payment uses it: {@code PreparePayment}, {@code CancelPayment}.</li>
 * </ul>
 * Compare a bank transfer: the bank assigns its own transaction identifier, while you type your invoice number into
 * the reference field. The bank never parses your invoice number, it just prints it back on your statement. Every
 * real payment system carries both, Stripe as {@code pi_...} plus {@code client_reference_id}, ISO 20022 as
 * {@code TxId} plus {@code EndToEndId}, and Axon Framework 4's own bike-rental demo as {@code paymentId} plus
 * {@code paymentReference}.
 * <p>
 * The decision model for preparing and cancelling is keyed by the reference rather than by the payment identifier.
 * That single choice is what makes {@code PreparePayment} idempotent by construction: one payment per reference, with
 * the Dynamic Consistency Boundary append condition rejecting a second concurrent attempt, rather than a guard that
 * has to be remembered. It also means the saga can address an existing payment with a key it already knows, so it
 * never has to store the generated payment identifier the way an Axon Framework 4 saga did.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
package org.axonframework.examples.sagarecipes.payment;
