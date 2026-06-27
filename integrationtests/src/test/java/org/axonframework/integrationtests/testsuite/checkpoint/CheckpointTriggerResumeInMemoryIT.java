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

package org.axonframework.integrationtests.testsuite.checkpoint;

import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.integrationtests.testsuite.infrastructure.InMemoryTestInfrastructure;
import org.axonframework.integrationtests.testsuite.infrastructure.TestInfrastructure;
import org.axonframework.integrationtests.testsuite.student.AbstractStudentIT;
import org.axonframework.integrationtests.testsuite.student.events.StudentEnrolledEvent;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.checkpoint.CheckpointTrigger;
import org.axonframework.messaging.eventhandling.processing.streaming.checkpoint.Checkpointing;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test proving a self-checkpointing handler that obtains its {@link CheckpointTrigger} through a handler
 * <em>parameter</em> (resolved by the {@code CheckpointTriggerParameterResolverFactory}) survives a crash: it confirms
 * its work durable behind the batch-end position, is killed mid-flight, and on restart resumes from the last
 * checkpointed token -- idempotently reprocessing only the uncheckpointed window.
 * <p>
 * This is the parameter-based counterpart of {@link CheckpointResumeInMemoryIT}, which retains the trigger handed to it
 * through {@link Checkpointing#onSegmentClaimed(Segment, CheckpointTrigger)} instead.
 *
 * @author Allard Buijze
 */
public class CheckpointTriggerResumeInMemoryIT extends AbstractStudentIT {

    private static final String PROCESSOR_NAME = "async-projection";
    private static final TestInfrastructure INFRASTRUCTURE = new InMemoryTestInfrastructure();

    private final ResumableProjection projection = new ResumableProjection();

    @Override
    protected TestInfrastructure testInfrastructure() {
        return INFRASTRUCTURE;
    }

    @Test
    void killedProjectionUsingTheTriggerParameterResumesFromTheCheckpoint() {
        // given -- a fully-deferred projection that confirms only the first three events durable
        projection.confirmLimit = 3;
        startApp();
        EventProcessor processor = processor();

        // when -- five events are enrolled and all handled
        String studentId = UUID.randomUUID().toString();
        List<String> courses = List.of("c0", "c1", "c2", "c3", "c4");
        courses.forEach(course -> studentEnrolledToCourse(studentId, course));

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(projection.handledSinceReset).hasSize(5);
            assertThat(projection.durableCourses).containsExactlyInAnyOrder("c0", "c1", "c2");
        });

        // when -- killed with c3/c4 handled but not checkpointed, then restarted
        projection.handledSinceReset.clear();
        projection.confirmLimit = 5;
        processor.shutdown().orTimeout(10, TimeUnit.SECONDS).join();
        processor.start().orTimeout(10, TimeUnit.SECONDS).join();

        // then -- it resumes from the checkpoint (c2), reprocessing only the uncheckpointed window c3, c4
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(
                () -> assertThat(projection.handledSinceReset).containsExactly("c3", "c4")
        );
        assertThat(projection.durableCourses).containsExactlyInAnyOrder("c0", "c1", "c2", "c3", "c4");
    }

    private EventProcessor processor() {
        Map<String, EventProcessor> processors = startedConfiguration.getComponents(EventProcessor.class);
        assertThat(processors).containsKey(PROCESSOR_NAME);
        return processors.get(PROCESSOR_NAME);
    }

    @Override
    protected EventSourcingConfigurer testSuiteConfigurer(EventSourcingConfigurer configurer) {
        var module = EventProcessorModule
                .pooledStreaming(PROCESSOR_NAME)
                .eventHandlingComponents(components -> components.autodetected("projection", cfg -> projection))
                .customized((cfg, c) -> c.initialSegmentCount(1));
        return configurer.messaging(
                messaging -> messaging.eventProcessing(ep -> ep.pooledStreaming(ps -> ps.processor(module)))
        );
    }

    /**
     * A fully-deferred projection that obtains its {@link CheckpointTrigger} through a handler <em>parameter</em> (the
     * parameter resolver) rather than retaining it from {@link #onSegmentClaimed}. It confirms only the first
     * {@link #confirmLimit} courses durable; events beyond that lag behind the batch-end and must be reprocessed on
     * restart.
     */
    static class ResumableProjection implements Checkpointing {

        private final List<String> handledSinceReset = new CopyOnWriteArrayList<>();
        private final Set<String> durableCourses = ConcurrentHashMap.newKeySet();
        private volatile TrackingToken durableToken;
        private volatile int confirmLimit = Integer.MAX_VALUE;

        @EventHandler
        void on(StudentEnrolledEvent event, TrackingToken token, CheckpointTrigger checkpoint) {
            handledSinceReset.add(event.courseId());
            if (durableCourses.size() < confirmLimit) {
                durableCourses.add(event.courseId());
                durableToken = token;
                checkpoint.requestCheckpoint(token);
            }
        }

        // The trigger is obtained through the handler parameter, and onCheckpointAdvanced reports the durable
        // high-water (ignoring requested); onSegmentClaimed and onSegmentReleased use the Checkpointing defaults.
        @Override
        public CompletableFuture<TrackingToken> onCheckpointAdvanced(@NonNull Segment segment,
                                                                     @NonNull TrackingToken requested) {
            return CompletableFuture.completedFuture(durableToken);
        }
    }
}
