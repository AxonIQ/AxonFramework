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
import org.axonframework.messaging.eventhandling.processing.streaming.checkpoint.Checkpointing;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test for a <em>mixed-mode</em> pooled streaming event processor: a {@link Checkpointing} component shares
 * the processor with an ordinary event-handling component. Because not <em>every</em> component is self-checkpointing,
 * the processor runs in auto-checkpointing mode: the stored {@link TrackingToken} advances at the batch-end position
 * every batch and the checkpointing component is driven to cover it -- it cannot defer the stored token.
 * <p>
 * This is the end-to-end counterpart to the fully-deferred {@link CheckpointResumeInMemoryIT}: there, a lone
 * checkpointing component defers the token and reprocesses the uncheckpointed window on restart; here, the co-located
 * ordinary component forces auto mode, so on restart the processor resumes from the batch-end and replays nothing.
 *
 * @author Allard Buijze
 */
public class MixedModeCheckpointingInMemoryIT extends AbstractStudentIT {

    private static final String PROCESSOR_NAME = "mixed-mode";
    private static final TestInfrastructure INFRASTRUCTURE = new InMemoryTestInfrastructure();

    private final CoveringCheckpointingProjection checkpointing = new CoveringCheckpointingProjection();
    private final OrdinaryProjection ordinary = new OrdinaryProjection();

    @Override
    protected TestInfrastructure testInfrastructure() {
        return INFRASTRUCTURE;
    }

    @Test
    void inAutoModeTheTokenAdvancesAtBatchEndSoRestartReplaysNothing() {
        // given -- a checkpointing component co-located with an ordinary one (so the processor runs in auto mode)
        startApp();
        EventProcessor processor = processor();

        // when -- five events are enrolled and handled by BOTH components
        String studentId = UUID.randomUUID().toString();
        List<String> courses = List.of("c0", "c1", "c2", "c3", "c4");
        courses.forEach(course -> studentEnrolledToCourse(studentId, course));

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(checkpointing.handledSinceReset).hasSize(5);
            assertThat(ordinary.handledSinceReset).hasSize(5);
        });

        // when -- the processor is restarted
        checkpointing.handledSinceReset.clear();
        ordinary.handledSinceReset.clear();
        processor.shutdown().orTimeout(10, TimeUnit.SECONDS).join();
        processor.start().orTimeout(10, TimeUnit.SECONDS).join();

        // then -- auto mode stored the batch-end token every batch, so the token resumes at c4: a NEW event c5 is the
        // only thing handled, and nothing from c0-c4 is replayed. (In fully-deferred mode the checkpointing component
        // could have left the token behind, forcing a replay -- see CheckpointResumeInMemoryIT.)
        studentEnrolledToCourse(studentId, "c5");
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(checkpointing.handledSinceReset).containsExactly("c5");
            assertThat(ordinary.handledSinceReset).containsExactly("c5");
        });
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
                .eventHandlingComponents(components -> components
                        .autodetected("checkpointing", cfg -> checkpointing)
                        .autodetected("ordinary", cfg -> ordinary))
                .customized((cfg, c) -> c.initialSegmentCount(1));
        return configurer.messaging(
                messaging -> messaging.eventProcessing(ep -> ep.pooledStreaming(ps -> ps.processor(module)))
        );
    }

    /**
     * A {@link Checkpointing} component that always reports the requested position as durable (it keeps up
     * synchronously). In auto mode it is driven to cover the batch-end token every batch; it does not request
     * checkpoints of its own.
     */
    static class CoveringCheckpointingProjection implements Checkpointing {

        private final List<String> handledSinceReset = new CopyOnWriteArrayList<>();

        @EventHandler
        void on(StudentEnrolledEvent event) {
            handledSinceReset.add(event.courseId());
        }

        @Override
        public CompletableFuture<TrackingToken> onCheckpointAdvanced(@NonNull Segment segment,
                                                                     @NonNull TrackingToken requested) {
            return CompletableFuture.completedFuture(requested);
        }
    }

    /**
     * An ordinary (non-checkpointing) event-handling component. Its presence is what makes the processor run in
     * auto-checkpointing mode.
     */
    static class OrdinaryProjection {

        private final List<String> handledSinceReset = new CopyOnWriteArrayList<>();

        @EventHandler
        void on(StudentEnrolledEvent event) {
            handledSinceReset.add(event.courseId());
        }
    }
}
