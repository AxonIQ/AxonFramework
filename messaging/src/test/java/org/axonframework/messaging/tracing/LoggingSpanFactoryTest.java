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

package org.axonframework.messaging.tracing;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.test.appender.ListAppender;
import org.apache.logging.log4j.core.test.junit.LoggerContextSource;
import org.apache.logging.log4j.core.test.junit.Named;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for {@link LoggingSpanFactory}. The factory only logs span lifecycle events, yet it must still honour the basic
 * {@link SpanFactory} contract: every factory method returns a non-null {@link Span}, the span can be started and its
 * {@link SpanScope} closed without error (with or without a {@link ProcessingContext}), attribute and exception
 * recording are fluent, and context propagation leaves the message untouched.
 *
 * @author Mateusz Nowak
 */
class LoggingSpanFactoryTest {

    private final LoggingSpanFactory testSubject = LoggingSpanFactory.INSTANCE;
    private final Message message = EventTestUtils.asEventMessage("payload");

    @Nested
    class SpanCreation {

        @Test
        void everyFactoryMethodReturnsANonNullSpan() {
            // when / then
            assertThat(testSubject.createRootSpan("op", null)).isNotNull();
            assertThat(testSubject.createDispatchSpan("op", message, null)).isNotNull();
            assertThat(testSubject.createHandlerSpan("op", message, null)).isNotNull();
            assertThat(testSubject.createLinkedHandlerSpan("op", message, message, null)).isNotNull();
            assertThat(testSubject.createInternalSpan("op", null)).isNotNull();
        }
    }

    @Nested
    class SpanLifecycle {

        @Test
        void scopeReportsItsMonotonicClosedState() {
            SpanScope scope = testSubject.createInternalSpan("op", null).start();

            assertThat(scope.isClosed()).isFalse();
            scope.close();
            assertThat(scope.isClosed()).isTrue();
            scope.close();
            assertThat(scope.isClosed()).isTrue();
        }

        @Test
        void rootSpanCanBeStartedRecordExceptionAndClose() {
            // given
            Span span = testSubject.createRootSpan("op", null);

            // when / then
            assertThatCode(() -> {
                try (SpanScope ignored = span.start()) {
                    span.recordException(new RuntimeException("boom"));
                }
            }).doesNotThrowAnyException();
        }

        @Test
        void dispatchSpanCanBeStartedAndClosed() {
            // given
            Span span = testSubject.createDispatchSpan("op", message, null);

            // when / then
            assertThatCode(() -> {
                try (SpanScope ignored = span.start()) {
                    span.recordException(new RuntimeException("boom"));
                }
            }).doesNotThrowAnyException();
        }

        @Test
        void handlerSpanCanBeStartedAndClosed() {
            // given
            Span span = testSubject.createHandlerSpan("op", message, null);

            // when / then
            assertThatCode(() -> {
                try (SpanScope ignored = span.start()) {
                    span.recordException(new RuntimeException("boom"));
                }
            }).doesNotThrowAnyException();
        }

        @Test
        void internalSpanCanBeStartedAndClosed() {
            // given
            Span span = testSubject.createInternalSpan("op", null);

            // when / then
            assertThatCode(() -> {
                try (SpanScope ignored = span.start()) {
                    span.recordException(new RuntimeException("boom"));
                }
            }).doesNotThrowAnyException();
        }

        @Test
        void internalSpanCanBeStartedAndClosedWithAnActiveProcessingContext() {
            // given a ProcessingContext is present -- the logging factory ignores it but must remain well-behaved
            ProcessingContext context = new StubProcessingContext();
            Span span = testSubject.createInternalSpan("op", context);

            // when / then
            assertThatCode(() -> {
                try (SpanScope ignored = span.start()) {
                    span.recordException(new RuntimeException("boom"));
                }
            }).doesNotThrowAnyException();
        }

        @Test
        void dispatchSpanCanBeStartedAndClosedWithAnActiveProcessingContext() {
            // given
            ProcessingContext context = new StubProcessingContext();
            Span span = testSubject.createDispatchSpan("op", message, context);

            // when / then
            assertThatCode(() -> {
                try (SpanScope ignored = span.start()) {
                    span.recordException(new RuntimeException("boom"));
                }
            }).doesNotThrowAnyException();
        }
    }

    @Nested
    @LoggerContextSource("log4j2-tracing-list-appender.xml")
    class LoggedContent {

        private ListAppender appender;

        @BeforeEach
        void clearAppender(@Named("TracingTestAppender") ListAppender appender) {
            this.appender = appender;
            appender.clear();
        }

        private List<LogEvent> events() {
            return appender.getEvents();
        }

        @Test
        void startAndEndAreLoggedAtInfoLevel() {
            // given
            Span span = testSubject.createInternalSpan("op", null);

            // when
            span.start().close();

            // then both the start and the end line are logged at INFO (not DEBUG)
            assertThat(events()).hasSize(2);
            assertThat(events()).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.INFO));
            assertThat(events().get(0).getMessage().getFormattedMessage()).contains("Internal span started");
            assertThat(events().get(1).getMessage().getFormattedMessage()).contains("Span ended");
        }

        @Test
        void handlerSpanStartLogsMessageTypeAndIdentifier() {
            // given
            Span span = testSubject.createHandlerSpan("op", message, null);

            // when
            span.start().close();

            // then the message's type and identifier are part of the start log (AF4 line format, AF5 MessageType)
            String startLog = events().get(0).getMessage().getFormattedMessage();
            assertThat(startLog).contains("Handler span started")
                                .contains(message.type().toString())
                                .contains(message.identifier());
        }

        @Test
        void addAttributeIsLoggedAtDebug() {
            // given a started span
            Span span = testSubject.createInternalSpan("op", null);
            SpanScope scope = span.start();

            // when an attribute is added (improvement over AF4: the logging factory renders attributes at DEBUG)
            span.addAttribute("key", "value");
            scope.close();

            // then a DEBUG line carrying the attribute was logged, alongside the INFO start/end lines
            assertThat(events()).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getMessage().getFormattedMessage()).contains("attribute").contains("key=value");
            });
        }

        @Test
        void dispatchSpanWhileHandlingLogsTheInFlightMessageFromTheProcessingContext() {
            // given a processing context that carries a message currently being handled
            Message handledMessage = EventTestUtils.asEventMessage("handled");
            ProcessingContext context = Message.addToContext(new StubProcessingContext(), handledMessage);
            Span span = testSubject.createDispatchSpan("op", message, context);

            // when
            span.start().close();

            // then the start log cross-references the in-flight (handled) message
            String startLog = events().get(0).getMessage().getFormattedMessage();
            assertThat(startLog).contains("Dispatch span started")
                                .contains(message.identifier())
                                .contains("while handling message")
                                .contains(handledMessage.identifier());
        }
    }

    @Nested
    class FluentApiAndPropagation {

        @Test
        void addAttributeAndRecordExceptionReturnTheSameSpan() {
            // given
            Span span = testSubject.createInternalSpan("op", null);

            // when / then
            assertThat(span.addAttribute("key", "value")).isSameAs(span);
            assertThat(span.recordException(new RuntimeException("boom"))).isSameAs(span);
        }

        @Test
        void propagateContextReturnsTheSameMessageInstance() {
            // when
            Message result = testSubject.createDispatchSpan("op", message, null).propagateContext(message);

            // then
            assertThat(result).isSameAs(message);
        }
    }
}
