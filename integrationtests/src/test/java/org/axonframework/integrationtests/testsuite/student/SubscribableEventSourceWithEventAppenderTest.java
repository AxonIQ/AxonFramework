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

package org.axonframework.integrationtests.testsuite.student;

import org.axonframework.common.Registration;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.integrationtests.testsuite.student.events.StudentEnrolledEvent;
import org.axonframework.integrationtests.testsuite.student.events.StudentNameChangedEvent;
import org.axonframework.messaging.core.SubscribableEventSource;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

/**
 * Integration test validating that events published via {@link EventAppender} are received by subscribers to
 * {@link SubscribableEventSource}.
 * <p>
 * This test demonstrates the interaction between event publishing (using {@link EventAppender}) and event consumption
 * (subscribing to {@link SubscribableEventSource}).
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
public abstract class SubscribableEventSourceWithEventAppenderTest extends AbstractStudentIT {

    @Test
    void whenEventPublishedViaEventAppenderThenSubscriberReceivesEvent() {
        // given
        startApp();

        var receivedEvents = new CopyOnWriteArrayList<EventMessage>();
        SubscribableEventSource eventSource = startedConfiguration.getComponent(SubscribableEventSource.class);
        Registration subscription = eventSource.subscribe((events, processingContext) -> {
            receivedEvents.addAll(events);
            return CompletableFuture.completedFuture(null);
        });

        // when
        var studentId = UUID.randomUUID().toString();
        var courseId = "course-123";
        publishEventViaEventAppender(new StudentEnrolledEvent(studentId, courseId));

        // then
        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   assertThat(receivedEvents).hasSize(1);
                   assertThat(receivedEvents.get(0).payload())
                           .isInstanceOf(StudentEnrolledEvent.class);
                   StudentEnrolledEvent event = (StudentEnrolledEvent) receivedEvents.get(0).payload();
                   assertThat(event.studentId()).isEqualTo(studentId);
                   assertThat(event.courseId()).isEqualTo(courseId);
               });

        subscription.cancel();
    }

    @Test
    void whenMultipleEventsPublishedThenSubscriberReceivesAllEvents() {
        // given
        startApp();

        var receivedEvents = new CopyOnWriteArrayList<EventMessage>();
        SubscribableEventSource eventSource = startedConfiguration.getComponent(SubscribableEventSource.class);
        Registration subscription = eventSource.subscribe((events, processingContext) -> {
            receivedEvents.addAll(events);
            return CompletableFuture.completedFuture(null);
        });

        // when
        var studentId = UUID.randomUUID().toString();
        publishEventViaEventAppender(
                new StudentEnrolledEvent(studentId, "course-1"),
                new StudentEnrolledEvent(studentId, "course-2"),
                new StudentNameChangedEvent(studentId, "John Doe")
        );

        // then
        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   assertThat(receivedEvents).hasSize(3);
                   assertThat(receivedEvents.get(0).payload()).isInstanceOf(StudentEnrolledEvent.class);
                   assertThat(receivedEvents.get(1).payload()).isInstanceOf(StudentEnrolledEvent.class);
                   assertThat(receivedEvents.get(2).payload()).isInstanceOf(StudentNameChangedEvent.class);
               });

        subscription.cancel();
    }

    @Test
    void whenMultipleSubscribersThenAllReceiveEvents() {
        // given
        startApp();

        var subscriber1Events = new CopyOnWriteArrayList<EventMessage>();
        var subscriber2Events = new CopyOnWriteArrayList<EventMessage>();

        SubscribableEventSource eventSource = startedConfiguration.getComponent(SubscribableEventSource.class);
        Registration subscription1 = eventSource.subscribe((events, processingContext) -> {
            subscriber1Events.addAll(events);
            return CompletableFuture.completedFuture(null);
        });
        Registration subscription2 = eventSource.subscribe((events, processingContext) -> {
            subscriber2Events.addAll(events);
            return CompletableFuture.completedFuture(null);
        });

        // when
        var studentId = UUID.randomUUID().toString();
        publishEventViaEventAppender(new StudentEnrolledEvent(studentId, "course-123"));

        // then
        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   assertThat(subscriber1Events).hasSize(1);
                   assertThat(subscriber2Events).hasSize(1);
                   assertThat(subscriber1Events.get(0).payload())
                           .isEqualTo(subscriber2Events.get(0).payload());
               });

        subscription1.cancel();
        subscription2.cancel();
    }

    @Test
    void whenSubscriberUnsubscribesThenNoLongerReceivesEvents() {
        // given
        startApp();

        var receivedEvents = new CopyOnWriteArrayList<EventMessage>();
        SubscribableEventSource eventSource = startedConfiguration.getComponent(SubscribableEventSource.class);
        Registration subscription = eventSource.subscribe((events, processingContext) -> {
            receivedEvents.addAll(events);
            return CompletableFuture.completedFuture(null);
        });

        var studentId = UUID.randomUUID().toString();
        publishEventViaEventAppender(new StudentEnrolledEvent(studentId, "course-1"));

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(receivedEvents).hasSize(1));

        // when
        subscription.cancel();
        publishEventViaEventAppender(new StudentEnrolledEvent(studentId, "course-2"));

        // then
        await().pollDelay(1, TimeUnit.SECONDS)
               .atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(receivedEvents).hasSize(1)); // Still only 1 event
    }

    private void publishEventViaEventAppender(Object... events) {
        UnitOfWork uow = unitOfWorkFactory.create();
        uow.runOnInvocation(context -> {
            EventAppender appender = EventAppender.forContext(context);
            appender.append(events);
        });
        uow.execute().join();
    }
}
