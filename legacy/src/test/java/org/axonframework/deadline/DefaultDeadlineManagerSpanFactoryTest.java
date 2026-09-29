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

package org.axonframework.deadline;

import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.messaging.tracing.Span;
import org.axonframework.messaging.tracing.SpanScope;
import org.axonframework.messaging.tracing.support.TestSpanFactory;
import org.axonframework.messaging.tracing.support.TestSpanFactory.TestSpanType;
import org.junit.jupiter.api.*;

/**
 * Test class validating the {@link DefaultDeadlineManagerSpanFactory}.
 * <p>
 * Asserts the span name, kind, and attributes each factory method produces, both with the default attribute keys and
 * with the keys overridden through the builder.
 *
 * @author Mitchell Herrijgers
 */
class DefaultDeadlineManagerSpanFactoryTest {

    private static final String DEADLINE_NAME = "myDeadline";
    private static final String DEADLINE_ID = "myDeadlineId";
    private static final String CUSTOM_ATTRIBUTE = "this-is-a-stupidly-long-attribute-name";

    private TestSpanFactory spanFactory;

    @BeforeEach
    void setUp() {
        spanFactory = new TestSpanFactory();
    }

    /**
     * Starts and immediately completes the given {@code span}, so the span is recorded as completed and can be
     * asserted on through the {@link TestSpanFactory} verification methods.
     */
    private static void complete(Span span) {
        try (SpanScope unused = span.start()) {
            // Completing the span is all this test needs; there is no operation to run inside it.
        }
    }

    private DeadlineManagerSpanFactory testSubject() {
        return DefaultDeadlineManagerSpanFactory.builder()
                                                .spanFactory(spanFactory)
                                                .build();
    }

    private static DeadlineMessage deadlineMessage() {
        return new GenericDeadlineMessage(DEADLINE_NAME, new MessageType("deadline"), "payload");
    }

    @Nested
    class CreateScheduleSpan {

        @Test
        void createsDispatchSpanCarryingTheDeadlineIdByDefault() {
            // given
            DeadlineMessage deadlineMessage = deadlineMessage();

            // when
            complete(testSubject().createScheduleSpan(DEADLINE_NAME, DEADLINE_ID, deadlineMessage));

            // then
            spanFactory.verifySpanCompleted("DeadlineManager.scheduleDeadline(myDeadline)", deadlineMessage);
            spanFactory.verifySpanHasType("DeadlineManager.scheduleDeadline(myDeadline)", TestSpanType.DISPATCH);
            spanFactory.verifySpanHasAttributeValue("DeadlineManager.scheduleDeadline(myDeadline)",
                                                    "axon.deadlineId",
                                                    DEADLINE_ID);
        }

        @Test
        void usesTheDeadlineIdAttributeConfiguredOnTheBuilder() {
            // given
            DeadlineManagerSpanFactory testSubject =
                    DefaultDeadlineManagerSpanFactory.builder()
                                                     .spanFactory(spanFactory)
                                                     .deadlineIdAttribute(CUSTOM_ATTRIBUTE)
                                                     .build();

            // when
            complete(testSubject.createScheduleSpan(DEADLINE_NAME, DEADLINE_ID, deadlineMessage()));

            // then
            spanFactory.verifySpanHasAttributeValue("DeadlineManager.scheduleDeadline(myDeadline)",
                                                    CUSTOM_ATTRIBUTE,
                                                    DEADLINE_ID);
            spanFactory.verifySpanHasNoAttribute("DeadlineManager.scheduleDeadline(myDeadline)", "axon.deadlineId");
        }
    }

    @Nested
    class CreateCancelScheduleSpan {

        @Test
        void createsInternalSpanCarryingTheDeadlineIdByDefault() {
            // when
            complete(testSubject().createCancelScheduleSpan(DEADLINE_NAME, DEADLINE_ID));

            // then
            spanFactory.verifySpanCompleted("DeadlineManager.cancelDeadline(myDeadline)");
            spanFactory.verifySpanHasType("DeadlineManager.cancelDeadline(myDeadline)", TestSpanType.INTERNAL);
            spanFactory.verifySpanHasAttributeValue("DeadlineManager.cancelDeadline(myDeadline)",
                                                    "axon.deadlineId",
                                                    DEADLINE_ID);
        }

        @Test
        void usesTheDeadlineIdAttributeConfiguredOnTheBuilder() {
            // given
            DeadlineManagerSpanFactory testSubject =
                    DefaultDeadlineManagerSpanFactory.builder()
                                                     .spanFactory(spanFactory)
                                                     .deadlineIdAttribute(CUSTOM_ATTRIBUTE)
                                                     .build();

            // when
            complete(testSubject.createCancelScheduleSpan(DEADLINE_NAME, DEADLINE_ID));

            // then
            spanFactory.verifySpanHasAttributeValue("DeadlineManager.cancelDeadline(myDeadline)",
                                                    CUSTOM_ATTRIBUTE,
                                                    DEADLINE_ID);
            spanFactory.verifySpanHasNoAttribute("DeadlineManager.cancelDeadline(myDeadline)", "axon.deadlineId");
        }
    }

    @Nested
    class CreateCancelAllSpan {

        @Test
        void createsInternalSpanWithoutAnyDeadlineAttributes() {
            // when
            complete(testSubject().createCancelAllSpan(DEADLINE_NAME));

            // then
            spanFactory.verifySpanCompleted("DeadlineManager.cancelAllDeadlines(myDeadline)");
            spanFactory.verifySpanHasType("DeadlineManager.cancelAllDeadlines(myDeadline)", TestSpanType.INTERNAL);
            spanFactory.verifySpanHasNoAttribute("DeadlineManager.cancelAllDeadlines(myDeadline)", "axon.deadlineId");
        }
    }

    @Nested
    class CreateCancelAllWithinScopeSpan {

        @Test
        void createsInternalSpanCarryingTheScopeDescriptionByDefault() {
            // given
            ScopeDescriptor scopeDescriptor = new TestScopeDescriptor("myType", "myIdentifier");

            // when
            complete(testSubject().createCancelAllWithinScopeSpan(DEADLINE_NAME, scopeDescriptor));

            // then
            spanFactory.verifySpanCompleted("DeadlineManager.cancelAllWithinScope(myDeadline)");
            spanFactory.verifySpanHasType("DeadlineManager.cancelAllWithinScope(myDeadline)", TestSpanType.INTERNAL);
            spanFactory.verifySpanHasAttributeValue("DeadlineManager.cancelAllWithinScope(myDeadline)",
                                                    "axon.deadlineScope",
                                                    scopeDescriptor.scopeDescription());
        }

        @Test
        void usesTheScopeAttributeConfiguredOnTheBuilder() {
            // given
            ScopeDescriptor scopeDescriptor = new TestScopeDescriptor("myType", "myIdentifier");
            DeadlineManagerSpanFactory testSubject =
                    DefaultDeadlineManagerSpanFactory.builder()
                                                     .spanFactory(spanFactory)
                                                     .scopeAttribute(CUSTOM_ATTRIBUTE)
                                                     .build();

            // when
            complete(testSubject.createCancelAllWithinScopeSpan(DEADLINE_NAME, scopeDescriptor));

            // then
            spanFactory.verifySpanHasAttributeValue("DeadlineManager.cancelAllWithinScope(myDeadline)",
                                                    CUSTOM_ATTRIBUTE,
                                                    scopeDescriptor.scopeDescription());
            spanFactory.verifySpanHasNoAttribute("DeadlineManager.cancelAllWithinScope(myDeadline)",
                                                 "axon.deadlineScope");
        }
    }

    @Nested
    class CreateExecuteSpan {

        @Test
        void createsDisconnectedHandlerSpanCarryingTheDeadlineIdByDefault() {
            // given a deadline fires long after it was scheduled, so its span starts a trace of its own
            DeadlineMessage deadlineMessage = deadlineMessage();

            // when
            complete(testSubject().createExecuteSpan(DEADLINE_NAME, DEADLINE_ID, deadlineMessage));

            // then
            spanFactory.verifySpanCompleted("DeadlineManager.executeDeadline(myDeadline)", deadlineMessage);
            spanFactory.verifySpanHasType("DeadlineManager.executeDeadline(myDeadline)",
                                          TestSpanType.DISCONNECTED_HANDLER);
            spanFactory.verifySpanHasAttributeValue("DeadlineManager.executeDeadline(myDeadline)",
                                                    "axon.deadlineId",
                                                    DEADLINE_ID);
        }

        @Test
        void usesTheDeadlineIdAttributeConfiguredOnTheBuilder() {
            // given
            DeadlineManagerSpanFactory testSubject =
                    DefaultDeadlineManagerSpanFactory.builder()
                                                     .spanFactory(spanFactory)
                                                     .deadlineIdAttribute(CUSTOM_ATTRIBUTE)
                                                     .build();

            // when
            complete(testSubject.createExecuteSpan(DEADLINE_NAME, DEADLINE_ID, deadlineMessage()));

            // then
            spanFactory.verifySpanHasAttributeValue("DeadlineManager.executeDeadline(myDeadline)",
                                                    CUSTOM_ATTRIBUTE,
                                                    DEADLINE_ID);
            spanFactory.verifySpanHasNoAttribute("DeadlineManager.executeDeadline(myDeadline)", "axon.deadlineId");
        }
    }
}
