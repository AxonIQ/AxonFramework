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

import org.axonframework.common.FutureUtils;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.progress.SegmentProgressStrategyFactory;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Exercises the published {@link SegmentProgressStrategyTestSupport} harness with the default
 * {@link org.axonframework.messaging.eventhandling.processing.streaming.pooled.progress.TokenStoringProgressStrategy},
 * keeping the support base verified in-repo. Out-of-module strategy suites (consuming the messaging test-jar) rely on
 * exactly the bridge operations driven here.
 */
class SegmentProgressStrategyHarnessTest extends SegmentProgressStrategyTestSupport {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void tokenStoringStrategyDrivenThroughTheHarnessStoresEachBatchEndToken() {
        // given
        WorkPackageHarness harness = harness(SegmentProgressStrategyFactory.tokenStoring());
        harness.onSegmentClaimed();

        // when -- three single-event batches are handled
        harness.scheduleEvent(eventAt(1L));
        harness.scheduleEvent(eventAt(2L));
        harness.scheduleEvent(eventAt(3L));

        // then -- all events are processed with the segment in context, and the stored token reaches the batch end
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(harness.batchProcessor().processed()).hasSize(3));
        assertThat(harness.batchProcessor().segmentInContext()).isEqualTo(segment());
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(storedPosition()).isEqualTo(3L));

        // when -- the segment is released and the package aborted, as the coordinator would on shutdown
        FutureUtils.joinAndUnwrap(harness.release());
        FutureUtils.joinAndUnwrap(harness.abort(null));

        // then -- the released position is the already-stored batch end
        assertThat(storedPosition()).isEqualTo(3L);
    }

    private long storedPosition() {
        TrackingToken token = FutureUtils.joinAndUnwrap(
                tokenStore().fetchToken(PROCESSOR_NAME, Segment.ROOT_SEGMENT.getSegmentId(), null)
        );
        return token == null ? -1L : token.position().orElse(-1L);
    }
}
