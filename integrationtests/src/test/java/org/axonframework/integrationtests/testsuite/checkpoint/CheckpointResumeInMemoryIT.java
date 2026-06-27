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
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.checkpoint.CheckpointTrigger;
import org.axonframework.messaging.eventhandling.processing.streaming.checkpoint.Checkpointing;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;

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
 * Integration test for the self-checkpointing event-handler protocol: an asynchronous projection that confirms its work
 * durable behind the batch-end position is killed mid-flight and, on restart, resumes from the last
 * <em>checkpointed</em> token (not the batch-end), idempotently reprocessing the window that was handled but never
 * checkpointed.
 * <p>
 * Here the projection acquires its {@link CheckpointTrigger} by <em>retaining</em> the one handed to it through
 * {@link Checkpointing#onSegmentClaimed(Segment, CheckpointTrigger)}, keyed by {@link Segment}. This is the
 * counterpart of {@link CheckpointTriggerResumeInMemoryIT}, which proves the identical resume guarantee for a
 * projection that instead obtains the trigger through a handler-method <em>parameter</em> (the
 * {@code CheckpointTriggerParameterResolverFactory}). Both acquisition paths are public API, so both are exercised
 * end-to-end.
 * <p>
 * This is the end-to-end realisation of the guarantee unit-tested by {@code WorkPackageCheckpointTest}: the stored
 * token never runs past what a component made durable.
 *
 * @author Allard Buijze
 */
public class CheckpointResumeInMemoryIT extends AbstractStudentIT {

    private static final String PROCESSOR_NAME = "async-projection";
    private static final TestInfrastructure INFRASTRUCTURE = new InMemoryTestInfrastructure();

    private final ResumableProjection projection = new ResumableProjection();

    @Override
    protected TestInfrastructure testInfrastructure() {
        return INFRASTRUCTURE;
    }

    @Test
    void killedProjectionResumesFromTheCheckpointAndReprocessesTheUncheckpointedWindow() {
        // given -- a fully-deferred projection that will confirm only the first three events durable
        projection.confirmLimit = 3;
        startApp();
        EventProcessor processor = processor();

        // when -- five events are enrolled and all handled
        String studentId = UUID.randomUUID().toString();
        List<String> courses = List.of("c0", "c1", "c2", "c3", "c4");
        courses.forEach(course -> studentEnrolledToCourse(studentId, course));

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(projection.handledSinceReset).hasSize(5);
            // the projection confirmed durability for the first three events only (c3 and c4 lag behind batch-end)
            assertThat(projection.durableCourses).containsExactlyInAnyOrder("c0", "c1", "c2");
        });

        // when -- the processor is killed (shut down) with c3/c4 handled but not yet checkpointed, then restarted
        projection.handledSinceReset.clear();
        projection.confirmLimit = 5;
        processor.shutdown().orTimeout(10, TimeUnit.SECONDS).join();
        processor.start().orTimeout(10, TimeUnit.SECONDS).join();

        // then -- it resumes from the checkpoint (c2), reprocessing ONLY the uncheckpointed window c3, c4.
        // Had the stored token been the batch-end (c4), nothing would be reprocessed; a full replay would re-handle c0-c2.
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
     * A fully-deferred projection: it confirms only the first {@link #confirmLimit} distinct courses durable,
     * requesting a checkpoint once each is confirmed. Events handled beyond that limit (c3, c4) lag behind the
     * batch-end position and must be reprocessed on restart. The same instance survives a processor restart (as a
     * configured component); {@link #handledSinceReset} is cleared by the test to isolate the reprocessed window.
     */
    static class ResumableProjection implements Checkpointing {

        private final List<String> handledSinceReset = new CopyOnWriteArrayList<>();
        private final Set<String> durableCourses = ConcurrentHashMap.newKeySet();
        private final Map<Segment, CheckpointTrigger> triggers = new ConcurrentHashMap<>();
        private volatile TrackingToken durableToken;
        private volatile int confirmLimit = Integer.MAX_VALUE;

        @EventHandler
        void on(StudentEnrolledEvent event, TrackingToken token, ProcessingContext context) {
            Segment segment = Segment.fromContext(context).orElseThrow();
            handledSinceReset.add(event.courseId());
            if (durableCourses.size() < confirmLimit) {
                // Confirm this event durable and advance the checkpoint to its position.
                durableCourses.add(event.courseId());
                durableToken = token;
                triggers.get(segment).requestCheckpoint(token);
            }
        }

        @Override
        public void onSegmentClaimed(@NonNull Segment segment, @NonNull CheckpointTrigger trigger) {
            triggers.put(segment, trigger);
        }

        // onCheckpointAdvanced reports the durable high-water (ignoring requested); the default onSegmentReleased
        // delegates here, so release also reports the durable position rather than forcing upTo.
        @Override
        @NonNull
        public CompletableFuture<TrackingToken> onCheckpointAdvanced(@NonNull Segment segment,
                                                                     @NonNull TrackingToken requested) {
            return CompletableFuture.completedFuture(durableToken);
        }
    }
}
