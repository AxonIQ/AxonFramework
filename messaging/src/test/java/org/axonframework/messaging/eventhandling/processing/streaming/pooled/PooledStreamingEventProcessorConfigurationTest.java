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

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.SegmentChangeListener;
import org.junit.jupiter.api.*;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.common.FutureUtils.joinAndUnwrap;

class PooledStreamingEventProcessorConfigurationTest {

    @Test
    void addSegmentChangeListenerAddsListenersWithoutOverriding() {
        // given
        PooledStreamingEventProcessorConfiguration testSubject = new PooledStreamingEventProcessorConfiguration(
                new EventProcessorConfiguration("processorName", null)
        );
        AtomicInteger releaseInvocations = new AtomicInteger();
        testSubject.addSegmentChangeListener(SegmentChangeListener.runOnRelease(
                segment -> releaseInvocations.incrementAndGet()
        ));
        testSubject.addSegmentChangeListener(SegmentChangeListener.runOnRelease(
                segment -> releaseInvocations.incrementAndGet()
        ));

        // when
        joinAndUnwrap(testSubject.segmentChangeListener().onSegmentReleased(Segment.ROOT_SEGMENT));

        // then
        assertThat(releaseInvocations).hasValue(2);
    }

    @Nested
    class SequencingPolicyTest {

        private final PooledStreamingEventProcessorConfiguration testSubject = new PooledStreamingEventProcessorConfiguration(
                new EventProcessorConfiguration("processorName", null)
        );

        @Test
        void defaultsToNull() {
            assertThat(testSubject.sequencingPolicy()).isNull();
        }

        @Test
        void sequencingPolicySetsAndReturnsTheGivenPolicy() {
            // given
            SequencingPolicy<EventMessage> policy = (event, context) -> Optional.of("seq-id");

            // when
            PooledStreamingEventProcessorConfiguration result = testSubject.sequencingPolicy(policy);

            // then
            assertThat(result).isSameAs(testSubject);
            assertThat(testSubject.sequencingPolicy()).isSameAs(policy);
        }

        @Test
        void sequencingPolicyRejectsNull() {
            //noinspection DataFlowIssue
            assertThatThrownBy(() -> testSubject.sequencingPolicy(null))
                    .isInstanceOf(AxonConfigurationException.class);
        }
    }
}
