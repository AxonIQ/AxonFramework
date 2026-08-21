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
 * @author Axon Framework
 * @since 5.4.0
 */
public final class SagaRecipeAssertions {

    private static final QualifiedName PAYMENT_PREPARED = new QualifiedName(PaymentPrepared.class);

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

    private static List<Object> payloadsOf(List<CommandMessage> commands) {
        return commands.stream().map(CommandMessage::payload).toList();
    }

    private SagaRecipeAssertions() {
        // Utility class, not meant to be instantiated.
    }
}
