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

package org.axonframework.messaging.eventhandling.processing.streaming.pooled;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Abstract integration test suite for {@link PooledStreamingEventProcessor}.
 * <p>
 * Each test method creates its own processor instance via {@link #buildProcessor()}, started in {@link #initProcessor()}
 * and stopped in {@link #tearDownProcessor()}. Using a unique processor name per test (e.g., UUID-based) naturally
 * isolates token store state without requiring context or schema resets between tests.
 *
 * @since 5.1.1
 */
public abstract class PooledStreamingEventProcessorTestSuite {

    private PooledStreamingEventProcessor processor;

    /**
     * Builds a fresh, not-yet-started {@link PooledStreamingEventProcessor} for the current test.
     * <p>
     * Implementations should use a unique processor name per call (e.g., via {@code UUID.randomUUID()}) to ensure token
     * store isolation between tests without requiring any cleanup.
     *
     * @return a configured but not started processor
     */
    protected abstract PooledStreamingEventProcessor buildProcessor();

    /**
     * Hook invoked after the processor is stopped in {@link #tearDownProcessor()}.
     * <p>
     * Override to release resources created alongside the processor (e.g., executor services).
     */
    protected void afterProcessorShutdown() {}

    @BeforeEach
    void initProcessor() throws Exception {
        processor = buildProcessor();
        processor.start().get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDownProcessor() throws Exception {
        if (processor != null) {
            processor.shutdown().get(10, TimeUnit.SECONDS);
        }
        afterProcessorShutdown();
    }

    protected PooledStreamingEventProcessor processor() {
        return processor;
    }

    @Nested
    class WhenSplittingSegments {

        @Test
        void splitSegment_returnsTrue_andBothNewSegmentsBecomeClaimed() throws Exception {
            // given
            Awaitility.await()
                      .atMost(10, TimeUnit.SECONDS)
                      .until(() -> processor().processingStatus().containsKey(0));

            // when
            boolean splitResult = processor().splitSegment(0).get(15, TimeUnit.SECONDS);
            assertTrue(splitResult,
                       "splitSegment(0) should return true and do not throw UnableToClaimTokenException");

            // then
            Awaitility.await()
                      .atMost(10, TimeUnit.SECONDS)
                      .until(() -> processor().processingStatus().size() == 2);
            assertTrue(processor().processingStatus().containsKey(0),
                       "Segment 0 (re-initialized with new mask) must be active after split");
            assertTrue(processor().processingStatus().containsKey(1),
                       "Segment 1 (sibling) must be active after split");
        }
    }

    @Nested
    class WhenMergingSegments {

        @Test
        void mergeSegment_afterSplit_returnsTrue_andMergedSegmentBecomesActive() throws Exception {
            // given — start with segment 0 active, then split to get two segments
            Awaitility.await()
                      .atMost(10, TimeUnit.SECONDS)
                      .until(() -> processor().processingStatus().containsKey(0));

            boolean splitResult = processor().splitSegment(0).get(15, TimeUnit.SECONDS);
            assertTrue(splitResult, "splitSegment(0) should return true before merging");

            Awaitility.await()
                      .atMost(15, TimeUnit.SECONDS)
                      .until(() -> processor().processingStatus().size() == 2);

            // when
            boolean mergeResult = processor().mergeSegment(0).get(15, TimeUnit.SECONDS);
            assertTrue(mergeResult,
                       "mergeSegment(0) should return true and do not throw UnableToClaimTokenException");

            // then
            Awaitility.await()
                      .atMost(30, TimeUnit.SECONDS)
                      .until(() -> processor().processingStatus().size() == 1);
            assertTrue(processor().processingStatus().containsKey(0),
                       "Segment 0 (merged) must be active after merge");
        }
    }
}
