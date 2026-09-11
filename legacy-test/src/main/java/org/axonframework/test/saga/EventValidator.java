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

import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.test.AxonAssertionError;
import org.axonframework.test.matchers.FieldFilter;
import org.axonframework.test.util.DescriptionUtils;
import org.hamcrest.Matcher;
import org.hamcrest.StringDescription;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static java.lang.String.format;
import static org.axonframework.test.matchers.Matchers.deepEquals;
import static org.axonframework.test.matchers.Matchers.exactSequenceOf;
import static org.axonframework.test.matchers.Matchers.payloadsMatching;

/**
 * Helper class for the validation of the events a Saga published.
 * <p>
 * Reads the events recorded during the "when" phase, which excludes the event the "when" phase published itself, so
 * what is left is what the Saga produced. That is the set Axon Framework 4 asserted on, and the failure messages here
 * are its messages.
 *
 * @author Allard Buijze
 * @since 1.1
 */
class EventValidator {

    private final Supplier<List<EventMessage>> publishedEvents;
    private final FieldFilter fieldFilter;

    /**
     * Initialize the validator to validate the events supplied by the given {@code publishedEvents}.
     *
     * @param publishedEvents supplies the events published during the "when" phase
     * @param fieldFilter     the filter describing the fields to include in a comparison
     */
    EventValidator(Supplier<List<EventMessage>> publishedEvents, FieldFilter fieldFilter) {
        this.publishedEvents = Objects.requireNonNull(publishedEvents, "The publishedEvents may not be null.");
        this.fieldFilter = Objects.requireNonNull(fieldFilter, "The fieldFilter may not be null.");
    }

    /**
     * Asserts that events have been published matching the given {@code matcher}.
     *
     * @param matcher The matcher that will validate the actual events
     */
    void assertPublishedEventsMatching(Matcher<?> matcher) {
        List<EventMessage> actual = publishedEvents.get();
        if (!matcher.matches(actual)) {
            StringDescription expectedDescription = new StringDescription();
            StringDescription actualDescription = new StringDescription();
            matcher.describeTo(expectedDescription);
            DescriptionUtils.describe(actual, actualDescription);
            throw new AxonAssertionError(format("Published events did not match.\nExpected <%s>,\n but got <%s>\n",
                                                expectedDescription, actualDescription));
        }
    }

    /**
     * Assert that the given {@code expected} events have been published.
     * <p>
     * Each element is either an {@link EventMessage} or a payload; a message is unwrapped to its payload, so only
     * payloads are compared and metadata is not, as in Axon Framework 4.
     *
     * @param expected the events that must have been published
     */
    void assertPublishedEvents(Object... expected) {
        List<EventMessage> actual = publishedEvents.get();
        if (actual.size() != expected.length) {
            throw new AxonAssertionError(format(
                    "Got wrong number of events published.\nExpected <%s>,\n but got <%s>.",
                    expected.length, actual.size()
            ));
        }
        assertPublishedEventsMatching(payloadsMatching(exactSequenceOf(equalToMatchers(expected))));
    }

    @SuppressWarnings("unchecked")
    private Matcher<Object>[] equalToMatchers(Object[] expected) {
        List<Matcher<?>> matchers = new ArrayList<>(expected.length);
        for (Object event : expected) {
            matchers.add(deepEquals(unwrapEvent(event), fieldFilter));
        }
        return matchers.toArray(new Matcher[0]);
    }

    /**
     * Unwraps the given {@code event} if it is an {@link EventMessage}. Otherwise returns it as is.
     *
     * @param event either an {@link EventMessage} or the payload of one
     * @return the payload of the given {@code event}, or the {@code event} itself
     */
    private static Object unwrapEvent(Object event) {
        return event instanceof EventMessage message ? message.payload() : event;
    }
}
