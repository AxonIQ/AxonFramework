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

package org.axonframework.examples.sagarecipes.saga;

import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.saga.eventsourced.event.RentalPaymentProcessCompleted;
import org.axonframework.examples.sagarecipes.saga.eventsourced.event.RentalPaymentRequested;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.eventhandling.EventMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Assertions shared by the recipe contract test.
 * <p>
 * Everything here filters before asserting, for one reason: the recording command bus accumulates across every test
 * sharing a Spring context, and it is only reset by the {@code when} phase, which these event-driven scenarios do not
 * use. Asserting on the exact recorded list would therefore make each test depend on how many ran before it. Since
 * every test works with freshly generated identifiers, filtering to its own messages is both precise and stable.
 *
 * @author Mateusz Nowak
 */
public final class SagaRecipeAssertions {

    private static final QualifiedName PAYMENT_PREPARED = new QualifiedName(PaymentPrepared.class);
    private static final QualifiedName RENTAL_PAYMENT_REQUESTED = new QualifiedName(RentalPaymentRequested.class);

    /**
     * Asserts that the process dispatched the given command.
     *
     * @param commands every command recorded so far
     * @param expected the command the process was supposed to send
     */
    public static void assertDispatched(List<CommandMessage> commands, Object expected) {
        assertThat(payloadsOf(commands))
                .describedAs("the process should have dispatched %s", expected)
                .contains(expected);
    }

    /**
     * Asserts that the process dispatched nothing resembling the given command.
     *
     * @param commands   every command recorded so far
     * @param unexpected the command the process was supposed to withhold
     */
    public static void assertNotDispatched(List<CommandMessage> commands, Object unexpected) {
        assertThat(payloadsOf(commands))
                .describedAs("the process should not have dispatched %s", unexpected)
                .doesNotContain(unexpected);
    }

    /**
     * Asserts that exactly one payment was prepared for the given reference.
     * <p>
     * Filtering by qualified name before converting mirrors how the saga's own routing works, and keeps this usable
     * against any recorded event.
     *
     * @param events    every event recorded so far
     * @param reference the reference the payment should have been prepared under
     */
    public static void assertSinglePaymentPrepared(List<EventMessage> events, PaymentReference reference) {
        var prepared = events.stream()
                             .filter(event -> PAYMENT_PREPARED.equals(event.type().qualifiedName()))
                             .map(event -> event.payloadAs(PaymentPrepared.class))
                             .filter(event -> reference.equals(event.paymentReference()))
                             .toList();

        assertThat(prepared)
                .describedAs("a redelivered trigger must not create a second payment for the same reference")
                .hasSize(1);
    }

    /**
     * Asserts that the process appended an event of its own for the given rental.
     * <p>
     * Only the recipes that record their own facts can satisfy this, which is exactly why it lives outside the shared
     * contract.
     *
     * @param events    every event recorded so far
     * @param eventType the process event that should have been appended
     * @param rentalId  the rental it should concern
     */
    public static void assertProcessEventAppended(
            List<EventMessage> events,
            Class<?> eventType,
            RentalId rentalId
    ) {
        var expected = new QualifiedName(eventType);
        var appended = events.stream()
                             .filter(event -> expected.equals(event.type().qualifiedName()))
                             .filter(event -> rentalIdOf(event).equals(rentalId))
                             .toList();

        assertThat(appended)
                .describedAs("the process should have recorded a %s for %s", eventType.getSimpleName(), rentalId)
                .hasSize(1);
    }

    private static RentalId rentalIdOf(EventMessage event) {
        if (RENTAL_PAYMENT_REQUESTED.equals(event.type().qualifiedName())) {
            return event.payloadAs(RentalPaymentRequested.class).rentalId();
        }
        return event.payloadAs(RentalPaymentProcessCompleted.class).rentalId();
    }

    private static List<Object> payloadsOf(List<CommandMessage> commands) {
        return commands.stream().map(CommandMessage::payload).toList();
    }

    private SagaRecipeAssertions() {
        // Utility class, not meant to be instantiated.
    }
}
