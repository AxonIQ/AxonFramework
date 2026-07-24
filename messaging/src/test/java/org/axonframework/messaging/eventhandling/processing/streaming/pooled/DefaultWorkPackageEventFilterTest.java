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

import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.processing.ProcessorEventHandlingComponents;
import org.axonframework.messaging.eventhandling.processing.errorhandling.PropagatingErrorHandler;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class for {@link DefaultWorkPackageEventFilter}.
 *
 * @author Stefan Dragisic
 */
class DefaultWorkPackageEventFilterTest {

    private static final QualifiedName STRING_EVENT_NAME = new QualifiedName(String.class);
    private static final List<Segment> ALL_SEGMENTS = List.of(
            new Segment(0, 3), new Segment(1, 3), new Segment(2, 3), new Segment(3, 3)
    );

    private static EventHandlingComponent componentWithSequenceIdentifier(QualifiedName supportedEvent,
                                                                          Object sequenceIdentifier) {
        return SimpleEventHandlingComponent
                .create("test-component", (event, context) -> Optional.of(sequenceIdentifier))
                .subscribe(supportedEvent, (event, context) -> MessageStream.empty());
    }

    private static DefaultWorkPackageEventFilter testSubjectWith(EventHandlingComponent... components) {
        return new DefaultWorkPackageEventFilter(
                "test-processor",
                new ProcessorEventHandlingComponents(List.of(components)),
                PropagatingErrorHandler.instance()
        );
    }

    @Test
    void broadcastSequenceIdentifierMatchesEverySegment() throws Exception {
        //given
        DefaultWorkPackageEventFilter testSubject = testSubjectWith(
                componentWithSequenceIdentifier(STRING_EVENT_NAME, SequencingPolicy.BROADCAST)
        );
        EventMessage testEvent = EventTestUtils.asEventMessage("test-payload");

        for (Segment segment : ALL_SEGMENTS) {
            //when
            boolean result = testSubject.canHandle(testEvent, new StubProcessingContext(), segment);

            //then
            assertThat(result).as("segment [%s]", segment).isTrue();
        }
    }

    @Test
    void regularSequenceIdentifierMatchesOnlyTheSegmentItHashesInto() throws Exception {
        //given
        Object sequenceIdentifier = "sample-identifier";
        DefaultWorkPackageEventFilter testSubject = testSubjectWith(
                componentWithSequenceIdentifier(STRING_EVENT_NAME, sequenceIdentifier)
        );
        EventMessage testEvent = EventTestUtils.asEventMessage("test-payload");

        for (Segment segment : ALL_SEGMENTS) {
            //when
            boolean result = testSubject.canHandle(testEvent, new StubProcessingContext(), segment);

            //then
            assertThat(result).as("segment [%s]", segment)
                              .isEqualTo(segment.matches(Objects.hashCode(sequenceIdentifier)));
        }
    }

    @Test
    void broadcastFromOneComponentMatchesEverySegmentDespiteRegularIdentifierFromAnotherComponent() throws Exception {
        //given
        DefaultWorkPackageEventFilter testSubject = testSubjectWith(
                componentWithSequenceIdentifier(STRING_EVENT_NAME, "sample-identifier"),
                componentWithSequenceIdentifier(STRING_EVENT_NAME, SequencingPolicy.BROADCAST)
        );
        EventMessage testEvent = EventTestUtils.asEventMessage("test-payload");

        for (Segment segment : ALL_SEGMENTS) {
            //when
            boolean result = testSubject.canHandle(testEvent, new StubProcessingContext(), segment);

            //then
            assertThat(result).as("segment [%s]", segment).isTrue();
        }
    }

    @Test
    void unsupportedEventIsFilteredOutEvenWithBroadcastSequenceIdentifier() throws Exception {
        //given
        QualifiedName unsupportedEventName = new QualifiedName("some.other.Event");
        DefaultWorkPackageEventFilter testSubject = testSubjectWith(
                componentWithSequenceIdentifier(unsupportedEventName, SequencingPolicy.BROADCAST)
        );
        EventMessage testEvent = EventTestUtils.asEventMessage("test-payload");

        for (Segment segment : ALL_SEGMENTS) {
            //when
            boolean result = testSubject.canHandle(testEvent, new StubProcessingContext(), segment);

            //then
            assertThat(result).as("segment [%s]", segment).isFalse();
        }
    }
}
