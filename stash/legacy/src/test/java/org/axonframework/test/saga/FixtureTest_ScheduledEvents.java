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

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.UUID;

/**
 * Test class validating the event scheduler operations of the {@link SagaTestFixture}.
 *
 * @author Steven van Beelen
 */
class FixtureTest_ScheduledEvents {

    private static final String IDENTIFIER = UUID.randomUUID().toString();
    private static final Duration TRIGGER_DURATION_MINUTES = Duration.ofMinutes(10);
    private static final String EVENT_TO_SCHEDULE = "scheduled-event";

    private FixtureConfiguration testSubject;

    @BeforeEach
    void setUp() {
        testSubject = new SagaTestFixture<>(EventSchedulingSaga.class);
    }

//    @Test
//    void expectScheduledEvent() {
//        testSubject.givenNoPriorActivity()
//                   .whenPublishingA(new TriggerSagaStartEvent(IDENTIFIER))
//                   .expectScheduledEvent(TRIGGER_DURATION_MINUTES, EVENT_TO_SCHEDULE);
//    }
//
//    @Test
//    void expectScheduledEventOfType() {
//        testSubject.givenNoPriorActivity()
//                   .whenPublishingA(new TriggerSagaStartEvent(IDENTIFIER))
//                   .expectScheduledEventOfType(TRIGGER_DURATION_MINUTES, String.class);
//    }
//
//    @Test
//    void expectScheduledEventDurationAdjustedByElapsedTime() {
//        Duration elapsedTime = Duration.ofMinutes(1);
//
//        testSubject.givenAggregate(IDENTIFIER)
//                   .published(new TriggerSagaStartEvent(IDENTIFIER))
//                   .whenTimeElapses(elapsedTime)
//                   .expectScheduledEvent(TRIGGER_DURATION_MINUTES.minus(elapsedTime), EVENT_TO_SCHEDULE)
//                   .expectNoScheduledDeadlines();
//    }
//
//    @Test
//    void noExpectScheduledEvent() {
//        testSubject.givenAggregate(IDENTIFIER)
//                   .published(new TriggerSagaStartEvent(IDENTIFIER))
//                   .whenAggregate(IDENTIFIER)
//                   .publishes(new CancelScheduledTokenEvent(IDENTIFIER))
//                   .expectNoScheduledEvent(TRIGGER_DURATION_MINUTES, EVENT_TO_SCHEDULE);
//    }
//
//    @Test
//    void noExpectScheduledEventOfType() {
//        testSubject.givenAggregate(IDENTIFIER)
//                   .published(new TriggerSagaStartEvent(IDENTIFIER))
//                   .whenAggregate(IDENTIFIER)
//                   .publishes(new CancelScheduledTokenEvent(IDENTIFIER))
//                   .expectNoScheduledEventOfType(TRIGGER_DURATION_MINUTES, String.class);
//    }

    private static class CancelScheduledTokenEvent {

        @SuppressWarnings("unused")
        private String identifier;

        public CancelScheduledTokenEvent(String identifier) {
            this.identifier = identifier;
        }

        public String getIdentifier() {
            return identifier;
        }
    }

    @SuppressWarnings("unused")
    public static class EventSchedulingSaga {

//        @Inject
//        private transient EventScheduler scheduler;
//        private ScheduleToken scheduleToken;

//        @StartSaga
//        @SagaEventHandler(associationProperty = "identifier")
//        public void on(TriggerSagaStartEvent event, @Timestamp Instant timestamp) {
//            scheduleToken = scheduler.schedule(timestamp.plus(TRIGGER_DURATION_MINUTES), EVENT_TO_SCHEDULE);
//        }
//
//        @SagaEventHandler(associationProperty = "identifier")
//        public void on(CancelScheduledTokenEvent event) {
//            scheduler.cancelSchedule(scheduleToken);
//        }
    }
}
