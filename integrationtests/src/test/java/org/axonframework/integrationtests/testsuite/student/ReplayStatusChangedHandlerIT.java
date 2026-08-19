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

import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.integrationtests.testsuite.student.events.StudentEnrolledEvent;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.replay.ReplayStatus;
import org.axonframework.messaging.eventhandling.replay.ReplayStatusChanged;
import org.axonframework.messaging.eventhandling.replay.ResetContext;
import org.axonframework.messaging.eventhandling.replay.annotation.ReplayStatusChangedHandler;
import org.axonframework.messaging.eventhandling.replay.annotation.ResetHandler;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Test class validating that
 * {@link org.axonframework.messaging.eventhandling.replay.ReplayStatusChangedHandler ReplayStatusChangeHandlers} are
 * invoked when a {@link org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken} is about to
 * {@link
 * org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken#concludesReplay(TrackingToken)
 * finish}.
 *
 * @author Simon Zambrovski
 * @author Stefan Dragisic
 * @author Steven van Beelen
 * @since 5.1.0
 */
public abstract class ReplayStatusChangedHandlerIT extends AbstractStudentIT {

    private static final String PSEP_NAME = "replayStatusChangeHandler";

    static AtomicInteger eventHandlerInvocations;
    static AtomicBoolean resetHandlerInvoked;
    static AtomicReference<ReplayStatus> replayStatusReference;
    static CountDownLatch statusChangedInvoked;

    @BeforeEach
    void setUp() {
        eventHandlerInvocations = new AtomicInteger(0);
        resetHandlerInvoked = new AtomicBoolean(false);
        replayStatusReference = new AtomicReference<>(null);
        statusChangedInvoked = new CountDownLatch(0);
        purgeData();
    }

    @Test
    void resettingPsepTriggersReplayStatusChangeHandlersWhenFinishingTheReplay() {
        // given...
        startApp();
        Map<String, EventProcessor> eventProcessors = startedConfiguration.getComponents(EventProcessor.class);
        assertThat(eventProcessors).containsKey(PSEP_NAME);
        PooledStreamingEventProcessor psep = (PooledStreamingEventProcessor) eventProcessors.get(PSEP_NAME);
        int startEventCount = 4;
        int finalEventCount = 104;

        // when publishing some events...
        var studentId = UUID.randomUUID().toString();
        studentEnrolledToCourse(studentId, "my-courseId-1");
        studentEnrolledToCourse(studentId, "my-courseId-2");
        studentEnrolledToCourse(studentId, "my-courseId-3");
        studentEnrolledToCourse(studentId, "my-courseId-4");

        // then no replay or reset logic has been invoked...
        await().atMost(Duration.ofMillis(5000))
               .untilAsserted(() -> assertThat(eventHandlerInvocations).hasValue(4));
        assertThat(resetHandlerInvoked).isFalse();
        assertThat(replayStatusReference).hasValue(null);

        // when shutdown and reset...
        eventHandlerInvocations.set(0);
        psep.shutdown()
            .thenCompose(ignored -> psep.resetTokens())
            .join();

        // then the replay status switches to replay...
        assertThat(replayStatusReference).hasValue(ReplayStatus.REPLAY);

        // when we publish more events and start...
        for (int i = startEventCount + 1; i <= finalEventCount; i++) {
            studentEnrolledToCourse(studentId, "my-courseId-" + i);
        }
        psep.start().join();

        // then we expect the reset handler and first set of events to be handled...
        assertThat(resetHandlerInvoked).isTrue();
        await().atMost(Duration.ofMillis(5000))
               .untilAsserted(() -> assertThat(eventHandlerInvocations).hasValue(startEventCount));
        // ...once handled and blocked by a latch, the switch from REPLAY to REGULAR should happen
        assertThat(replayStatusReference).hasValue(ReplayStatus.REGULAR);
        // ...now we can unlock the latch to handle the remaining events
        statusChangedInvoked.countDown();
        await().atMost(Duration.ofMillis(5000))
               .untilAsserted(() -> assertThat(eventHandlerInvocations).hasValue(finalEventCount));
    }

    @SuppressWarnings("unused")
    static class Projector {

        @EventHandler
        public void on(StudentEnrolledEvent event, TrackingToken token) throws InterruptedException {
            if (statusChangedInvoked.await(500, TimeUnit.MILLISECONDS)) {
                eventHandlerInvocations.incrementAndGet();
            }
        }

        @ResetHandler
        public void reset(ResetContext resetContext) {
            resetHandlerInvoked.set(true);
        }

        @ReplayStatusChangedHandler
        public void on(ReplayStatusChanged context) {
            replayStatusReference.set(context.status());
            if (context.status() == ReplayStatus.REGULAR) {
                statusChangedInvoked = new CountDownLatch(1);
            }
        }
    }

    @Override
    protected EventSourcingConfigurer testSuiteConfigurer(EventSourcingConfigurer configurer) {
        return configureProcessorWithAnnotatedEventHandlingComponent(configurer);
    }

    private static EventSourcingConfigurer configureProcessorWithAnnotatedEventHandlingComponent(
            EventSourcingConfigurer configurer
    ) {
        var studentRegisteredCoursesProcessor = EventProcessorModule
                .pooledStreaming(PSEP_NAME)
                .eventHandlingComponents(components -> components.autodetected(
                        cfg -> new Projector()
                ))
                .customized(
                        (config, psep) -> psep.initialSegmentCount(2)
                                              .batchSize(3)
                );
        return configurer.messaging(
                messaging -> messaging.eventProcessing(
                        ep -> ep.pooledStreaming(
                                ps -> ps.processor(studentRegisteredCoursesProcessor)
                        )
                )
        );
    }
}
