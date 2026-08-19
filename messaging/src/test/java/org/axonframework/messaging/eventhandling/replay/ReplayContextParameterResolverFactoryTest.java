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

package org.axonframework.messaging.eventhandling.replay;

import org.axonframework.conversion.ConversionException;
import org.axonframework.conversion.Converter;
import org.axonframework.conversion.DelegatingGeneralConverter;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.conversion.PassThroughConverter;
import org.axonframework.conversion.TestConverter;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.annotation.AnnotatedEventHandlingComponent;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.replay.annotation.ReplayContext;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that the {@link ReplayContext} parameter annotation correctly resolves the reset context from a
 * {@link ReplayToken}.
 * <p>
 * Tests validate:
 * <ul>
 *   <li>{@code @ReplayContext} resolves to the reset context during replay</li>
 *   <li>{@code @ReplayContext} resolves to {@code null} outside of replay</li>
 *   <li>Handlers receive unwrapped tracking tokens (not {@link ReplayToken})</li>
 * </ul>
 */
class ReplayContextParameterResolverFactoryTest {

    private static final GlobalSequenceTrackingToken regularToken = new GlobalSequenceTrackingToken(1L);
    private static final MyResetContext resetContext = new MyResetContext(asList(2L, 3L));

    private SomeHandler handler;
    private Converter converter;
    private EventHandlingComponent testSubject;

    @BeforeEach
    void setUp() {
        handler = new SomeHandler();
        converter = TestConverter.JACKSON.getConverter();
        testSubject = new AnnotatedEventHandlingComponent<>(
                handler,
                ClasspathParameterResolverFactory.forClass(SomeHandler.class),
                ClasspathHandlerDefinition.forClass(SomeHandler.class),
                new AnnotationMessageTypeResolver(),
                new DelegatingEventConverter(converter)
        );
    }

    @Test
    void replayContextIsResolvedDuringReplay() {
        // given
        var event = longEvent(2L);
        var replayToken = createTrackingToken(2L, true);

        // when
        testSubject.handle(event, contextWithToken(event, replayToken));

        // then
        assertThat(handler.receivedLongs).containsExactly(2L);
        assertThat(handler.eventsMatchingResetContextFilter).containsExactly(2L);
        assertThat(handler.receivedResetContexts).containsExactly(resetContext);
    }

    @Test
    void replayContextIsNullOutsideReplay() {
        // given
        var event = longEvent(4L);

        // when
        testSubject.handle(event, contextWithToken(event, regularToken));

        // then
        assertThat(handler.receivedLongs).containsExactly(4L);
        assertThat(handler.eventsMatchingResetContextFilter).isEmpty();
        assertThat(handler.receivedResetContexts).containsExactly((MyResetContext) null);
    }

    @Test
    void resolvesIfMatching() {
        // given/when - ALL events are during replay (resetContext is available for all)
        // The handler filters based on whether the position is in resetContext.sequences [2, 3]
        testSubject.handle(longEvent(1L), contextWithToken(longEvent(1L), createTrackingToken(1L, true)));
        testSubject.handle(longEvent(2L), contextWithToken(longEvent(2L), createTrackingToken(2L, true)));
        testSubject.handle(longEvent(3L), contextWithToken(longEvent(3L), createTrackingToken(3L, true)));
        testSubject.handle(longEvent(4L), contextWithToken(longEvent(4L), createTrackingToken(4L, true)));

        // then - all events received
        assertThat(handler.receivedLongs).containsExactly(1L, 2L, 3L, 4L);
        // Only events at positions 2 and 3 match the reset context's filter (sequences = [2, 3])
        assertThat(handler.eventsMatchingResetContextFilter).containsExactly(2L, 3L);
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void replayContextIsConvertedFromMapToRecord() {
        // given
        Map<String, Object> mapContext = Map.of("sequences", List.of(2L, 3L));
        var handler = new SomeHandler();
        var testSubject = new AnnotatedEventHandlingComponent<>(
                handler,
                ClasspathParameterResolverFactory.forClass(SomeHandler.class),
                ClasspathHandlerDefinition.forClass(SomeHandler.class),
                new AnnotationMessageTypeResolver(),
                new DelegatingEventConverter(PassThroughConverter.INSTANCE)
        );
        var event = new GenericEventMessage(new MessageType(Long.class), 2L);
        var replayToken = ReplayToken.createReplayToken(
                new GlobalSequenceTrackingToken(3L),
                new GlobalSequenceTrackingToken(2L),
                converter.convert(mapContext, byte[].class)
        );

        // when
        testSubject.handle(event, contextWithToken(event, replayToken));

        // then
        assertThat(handler.receivedLongs).containsExactly(2L);
        assertThat(handler.receivedResetContexts).hasSize(1);
        assertThat(handler.receivedResetContexts.getFirst()).isNotNull();
        assertThat(handler.receivedResetContexts.getFirst().sequences()).containsExactly(2L, 3L);
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void conversionExceptionIsThrownWhenConversionFails() {
        // given
        String incompatibleContext = "incompatible-string-context";
        var replayToken = ReplayToken.createReplayToken(
                new GlobalSequenceTrackingToken(3L),
                new GlobalSequenceTrackingToken(2L),
                converter.convert(incompatibleContext, byte[].class)
        );

        // when/then
        assertThatThrownBy(() -> ReplayToken.replayContext(replayToken, MyResetContext.class, converter))
                .isInstanceOf(ConversionException.class);
    }

    private EventMessage longEvent(Long value) {
        return new GenericEventMessage(new MessageType(Long.class), value);
    }

    @SuppressWarnings({"SameParameterValue", "DataFlowIssue"})
    private TrackingToken createTrackingToken(Long position, boolean replay) {
        if (replay) {
            return ReplayToken.createReplayToken(
                    new GlobalSequenceTrackingToken(position + 1),
                    new GlobalSequenceTrackingToken(position),
                    converter.convert(resetContext, byte[].class)
            );
        }
        return new GlobalSequenceTrackingToken(position);
    }

    @SuppressWarnings("AccessStaticViaInstance")
    private ProcessingContext contextWithToken(EventMessage event, TrackingToken token) {
        // Register a General-, Message-, and EventConverter to recreate the normal Converter usage in Axon Framework
        return StubProcessingContext.withComponent(GeneralConverter.class, new DelegatingGeneralConverter(converter))
                                    .withComponent(MessageConverter.class, new DelegatingMessageConverter(converter))
                                    .withComponent(EventConverter.class, new DelegatingEventConverter(converter))
                                    .withMessage(event)
                                    .withResource(TrackingToken.RESOURCE_KEY, token);
    }

    private static class SomeHandler {

        private final List<Long> receivedLongs = new ArrayList<>();
        private final List<Long> eventsMatchingResetContextFilter = new ArrayList<>();
        private final List<MyResetContext> receivedResetContexts = new ArrayList<>();

        @EventHandler
        public void handle(Long event, TrackingToken token, @ReplayContext MyResetContext resetContext) {
            assertFalse(token instanceof ReplayToken);
            receivedLongs.add(event);
            receivedResetContexts.add(resetContext);

            long position = token.position().orElse(0);
            boolean positionMatchesFilter = resetContext != null && resetContext.sequences.contains(position);
            if (positionMatchesFilter) {
                eventsMatchingResetContextFilter.add(event);
            }
        }
    }

    private record MyResetContext(List<Long> sequences) {

    }
}
