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
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.LegacyMessageSupportingContext;
import org.axonframework.test.AxonAssertionError;
import org.axonframework.test.matchers.AllFieldsFilter;
import org.axonframework.test.matchers.Matchers;
import org.axonframework.common.util.StubDomainEvent;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class EventValidatorTest {

    private EventValidator testSubject;

    @BeforeEach
    void setUp() {
        testSubject = new EventValidator(null, AllFieldsFilter.instance());
    }

    @Test
    void assertPublishedEventsWithNoEventsMatcherIfNoEventWasPublished() {
        testSubject.assertPublishedEventsMatching(Matchers.noEvents());
    }

    private static <P> EventMessage asEventMessage(P event) {
        return new GenericEventMessage(
                new GenericMessage(new MessageType(event.getClass()), event)
        );
    }

    @Test
    void assertPublishedEventsIfNoEventWasPublished() {
        testSubject.assertPublishedEvents();
    }

    @Test
    void assertPublishedEventsWithNoEventsMatcherThrowsAssertionErrorIfEventWasPublished() {
        EventMessage eventMessage = asEventMessage(new StubDomainEvent());
        testSubject.handleSync(eventMessage, new LegacyMessageSupportingContext(eventMessage));

        assertThrows(AxonAssertionError.class, () -> testSubject.assertPublishedEventsMatching(Matchers.noEvents()));
    }

    @Test
    void assertPublishedEventsThrowsAssertionErrorIfEventWasPublished() {
        EventMessage eventMessage = asEventMessage(new StubDomainEvent());
        testSubject.handleSync(eventMessage, new LegacyMessageSupportingContext(eventMessage));

        assertThrows(AxonAssertionError.class, testSubject::assertPublishedEvents);
    }

    @Test
    void assertPublishedEventsForEventMessages() {
        EventMessage eventMessage = asEventMessage(new StubDomainEvent());
        testSubject.handleSync(eventMessage, new LegacyMessageSupportingContext(eventMessage));

        testSubject.assertPublishedEvents(eventMessage);
    }
}
