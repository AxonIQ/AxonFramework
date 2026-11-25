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

package org.axonframework.eventsourcing.eventstore;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.test.appender.ListAppender;
import org.axonframework.eventsourcing.eventstore.AppendObservation.Outcome;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link LoggingAppendObserver}.
 *
 * @author Alexey Afanasyev
 */
class LoggingAppendObserverTest {

    private static final String SENSITIVE_PAYLOAD = "sensitive-payload";
    private static final Tag TAG = Tag.of("courseId", "42");

    private final LoggingAppendObserver testSubject = new LoggingAppendObserver();
    private final EventMessage event = EventTestUtils.asEventMessage(SENSITIVE_PAYLOAD);
    private final AppendCondition condition = AppendCondition.withCriteria(EventCriteria.havingTags(TAG));

    private ListAppender appender;
    private Logger observerLogger;
    private Level previousLevel;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender("LoggingAppendObserverTestAppender");
        appender.start();
        observerLogger = (Logger) LogManager.getLogger(LoggingAppendObserver.class);
        previousLevel = observerLogger.getLevel();
        // Attaching the first appender may refresh the logger configuration, so only set the level afterward
        observerLogger.addAppender(appender);
        observerLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachAppender() {
        observerLogger.removeAppender(appender);
        observerLogger.setLevel(previousLevel);
        appender.stop();
    }

    @Nested
    class WhenDebugIsEnabled {

        @Test
        void logsACommittedAppendWithItsConsistencyMarker() {
            // given
            AppendObservation observation = observationOf(new Outcome.Committed(new GlobalIndexConsistencyMarker(7)));

            // when
            testSubject.observe(observation);

            // then
            assertThat(loggedMessage())
                    .startsWith("Append committed: ")
                    .contains(new GlobalIndexConsistencyMarker(7).toString());
        }

        @Test
        void logsARejectedAppendWithTheRejectionMessage() {
            // given
            AppendEventsTransactionRejectedException rejection =
                    AppendEventsTransactionRejectedException.conflictingEventsDetected(ConsistencyMarker.ORIGIN);

            // when
            testSubject.observe(observationOf(new Outcome.Rejected(rejection)));

            // then
            assertThat(loggedMessage())
                    .startsWith("Append rejected: ")
                    .contains(rejection.getMessage());
        }

        @Test
        void logsTheConflictingEventOfARejectedAppendByTypeAndIdentifierOnly() {
            // given
            EventMessage conflictingEvent = EventTestUtils.asEventMessage("conflicting-" + SENSITIVE_PAYLOAD);
            AppendEventsTransactionRejectedException rejection =
                    AppendEventsTransactionRejectedException.conflictingEventsDetected(ConsistencyMarker.ORIGIN)
                                                            .withConflictingEvent(conflictingEvent);

            // when
            testSubject.observe(observationOf(new Outcome.Rejected(rejection)));

            // then
            assertThat(loggedMessage())
                    .contains(conflictingEvent.identifier())
                    .doesNotContain(SENSITIVE_PAYLOAD);
        }

        @Test
        void logsAFailedAppendWithItsCause() {
            // given
            IllegalStateException failure = new IllegalStateException("storage unavailable");

            // when
            testSubject.observe(observationOf(new Outcome.Failed(failure)));

            // then the cause is logged with its stack trace rather than as part of the message
            assertThat(loggedMessage()).startsWith("Append failed: ");
            assertThat(appender.getEvents().getFirst().getThrown()).isSameAs(failure);
        }

        @Test
        void logsARolledBackAppend() {
            // when
            testSubject.observe(observationOf(new Outcome.RolledBack()));

            // then
            assertThat(loggedMessage()).startsWith("Append rolled back: ");
        }

        @Test
        void logsTheTypeIdentifierAndTagsOfEachEvent() {
            // when
            testSubject.observe(observationOf(new Outcome.RolledBack()));

            // then
            assertThat(loggedMessage())
                    .contains(event.type().toString())
                    .contains(event.identifier())
                    .contains("courseId=42");
        }

        @Test
        void neverLogsThePayloadOfAnEvent() {
            // when
            testSubject.observe(observationOf(new Outcome.Committed(ConsistencyMarker.ORIGIN)));

            // then
            assertThat(loggedMessage()).doesNotContain(SENSITIVE_PAYLOAD);
        }

        @Test
        void logsAtDebugLevel() {
            // when
            testSubject.observe(observationOf(new Outcome.RolledBack()));

            // then
            assertThat(appender.getEvents()).extracting(LogEvent::getLevel).containsExactly(Level.DEBUG);
        }
    }

    @Nested
    class WhenDebugIsDisabled {

        @BeforeEach
        void disableDebug() {
            observerLogger.setLevel(Level.INFO);
        }

        @Test
        void logsNothing() {
            // when
            testSubject.observe(observationOf(new Outcome.Committed(ConsistencyMarker.ORIGIN)));

            // then
            assertThat(appender.getEvents()).isEmpty();
        }
    }

    private AppendObservation observationOf(Outcome outcome) {
        return new AppendObservation(condition,
                                     List.of(new GenericTaggedEventMessage<>(event, Set.of(TAG))),
                                     null,
                                     outcome);
    }

    private String loggedMessage() {
        assertThat(appender.getEvents()).hasSize(1);
        return appender.getEvents().getFirst().getMessage().getFormattedMessage();
    }
}
