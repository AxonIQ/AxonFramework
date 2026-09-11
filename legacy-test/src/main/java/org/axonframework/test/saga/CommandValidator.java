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

package org.axonframework.test.saga;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.test.AxonAssertionError;
import org.axonframework.test.matchers.FieldFilter;
import org.axonframework.test.matchers.Matchers;
import org.axonframework.test.util.DescriptionUtils;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.StringDescription;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Helper class for the validation of the commands a Saga dispatched.
 * <p>
 * {@code axon-test} carries a validator of the same name, with the same logic and, today, the same failure messages.
 * This is a copy rather than a reuse because the two answer to different contracts: that one is free to reword its
 * failures as the Axon Framework 5 fixture evolves, while an Axon Framework 4 test suite asserting on the old wording
 * has to keep reading it. Sharing would leave that fidelity resting on a coincidence.
 *
 * @author Allard Buijze
 * @since 1.1
 */
class CommandValidator {

    private final Supplier<List<CommandMessage>> dispatchedCommands;
    private final FieldFilter fieldFilter;

    /**
     * Initializes the validator over the commands supplied by the given {@code dispatchedCommands}.
     *
     * @param dispatchedCommands supplies the commands dispatched during the "when" phase
     * @param fieldFilter        the filter describing the fields to include in a comparison
     */
    CommandValidator(Supplier<List<CommandMessage>> dispatchedCommands, FieldFilter fieldFilter) {
        this.dispatchedCommands = Objects.requireNonNull(dispatchedCommands, "The dispatchedCommands may not be null.");
        this.fieldFilter = Objects.requireNonNull(fieldFilter, "The fieldFilter may not be null.");
    }

    /**
     * Asserts that the given {@code expected} commands have been dispatched, in the given sequence.
     * <p>
     * Each element is either a {@link CommandMessage}, in which case payload and metadata are both compared, or a
     * payload.
     *
     * @param expected the commands expected to have been dispatched
     */
    void assertDispatchedEqualTo(Object... expected) {
        List<CommandMessage> actual = dispatchedCommands.get();
        if (actual.size() != expected.length) {
            throw new AxonAssertionError(String.format(
                    "Got wrong number of commands dispatched.\nExpected <%s>,\n but got <%s>.",
                    expected.length, actual.size()
            ));
        }

        Iterator<CommandMessage> actualIterator = actual.iterator();
        int counter = 0;
        for (Object expectedItem : expected) {
            CommandMessage actualItem = actualIterator.next();
            if (expectedItem instanceof CommandMessage expectedMessage) {
                if (!expectedMessage.payloadType().equals(actualItem.payloadType())) {
                    throw new AxonAssertionError(String.format(
                            "Unexpected payload type of command at position %s (0-based).\n"
                                    + "Expected <%s>,\n but got <%s>.",
                            counter, expectedMessage.payloadType(), actualItem.payloadType()
                    ));
                }
                assertCommandEquality(counter, expectedMessage.payload(), actualItem.payload());
                if (!expectedMessage.metadata().equals(actualItem.metadata())) {
                    throw new AxonAssertionError(String.format(
                            "Unexpected metadata of command at position %s (0-based).\n"
                                    + "Expected <%s>,\n but got <%s>.",
                            counter, expectedMessage.metadata(), actualItem.metadata()
                    ));
                }
            } else {
                assertCommandEquality(counter, expectedItem, actualItem.payload());
            }
            counter++;
        }
    }

    /**
     * Asserts that commands matching the given {@code matcher} have been dispatched.
     *
     * @param matcher the matcher validating the dispatched commands
     */
    void assertDispatchedMatching(Matcher<?> matcher) {
        List<CommandMessage> actual = dispatchedCommands.get();
        if (!matcher.matches(actual)) {
            Description expectedDescription = new StringDescription();
            Description actualDescription = new StringDescription();
            matcher.describeTo(expectedDescription);
            DescriptionUtils.describe(actual, actualDescription);
            throw new AxonAssertionError(String.format(
                    "Incorrect dispatched command.\nExpected <%s>,\n but got <%s>.",
                    expectedDescription, actualDescription
            ));
        }
    }

    private void assertCommandEquality(int commandIndex, Object expected, Object actual) {
        if (expected.equals(actual)) {
            return;
        }
        if (!expected.getClass().equals(actual.getClass())) {
            throw new AxonAssertionError(String.format(
                    "Wrong command type at index %s (0-based).\nExpected <%s>,\n but got <%s>.",
                    commandIndex, expected.getClass().getSimpleName(), actual.getClass().getSimpleName()
            ));
        }
        Matcher<Object> matcher = Matchers.deepEquals(expected, fieldFilter);
        if (!matcher.matches(actual)) {
            throw new AxonAssertionError(String.format(
                    "Unexpected command at index %s (0-based).\nExpected <%s>,\n but got <%s>.",
                    commandIndex, expected, actual
            ));
        }
    }
}
