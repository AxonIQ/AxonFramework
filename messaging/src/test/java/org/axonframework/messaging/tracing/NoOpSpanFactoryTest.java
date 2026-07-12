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

import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoOpSpanFactoryTest {

    private final NoOpSpanFactory testSubject = NoOpSpanFactory.INSTANCE;
    private final Message message = EventTestUtils.asEventMessage("payload");

    @Test
    void createdSpansCanBeStartedAndClosedWithoutError() {
        // given / when
        Span span = testSubject.createDispatchSpan("op", message, null);

        // then
        assertThatCode(() -> {
            SpanScope scope = span.start();
            scope.close();
        }).doesNotThrowAnyException();
    }

    @Test
    void eachScopeReportsItsOwnMonotonicClosedState() {
        Span span = testSubject.createInternalSpan("op", null);
        SpanScope first = span.start();
        SpanScope second = span.start();

        assertThat(first.isClosed()).isFalse();
        assertThat(second.isClosed()).isFalse();
        first.close();
        assertThat(first.isClosed()).isTrue();
        assertThat(second.isClosed()).isFalse();
    }

    @Test
    void branchExecutesTheGivenBlock() {
        // given
        AtomicBoolean executed = new AtomicBoolean(false);

        // when
        testSubject.createInternalSpan("op", null).branch(null, ignored -> {
            executed.set(true);
            return null;
        });

        // then
        assertThat(executed).isTrue();
    }

    @Test
    void branchReturnsTheOperationsValue() {
        // when
        String result = testSubject.createHandlerSpan("op", message, null).branch(null, ignored -> "value");

        // then
        assertThat(result).isEqualTo("value");
    }

    @Test
    void spanPropagateContextReturnsTheSameMessageInstance() {
        // when
        Message result = testSubject.createDispatchSpan("op", message, null).propagateContext(message);

        // then
        assertThat(result).isSameAs(message);
    }

    @Test
    void addAttributeAndRecordExceptionAreNoOpsReturningTheSpan() {
        // given
        Span span = testSubject.createInternalSpan("op", null);

        // when / then
        assertThat(span.addAttribute("k", "v")).isSameAs(span);
        assertThat(span.recordException(new RuntimeException())).isSameAs(span);
    }

    @Nested
    class ScopeBoundOperations {

        @Test
        void withinReturnsTheOperationsValue() {
            // given
            SpanScope scope = testSubject.createInternalSpan("op", null).start();

            // when
            String result = scope.within(() -> "value");

            // then
            assertThat(result).isEqualTo("value");
            scope.close();
        }

        @Test
        void withinPropagatesTheOperationsExceptionUnchanged() {
            // given
            SpanScope scope = testSubject.createInternalSpan("op", null).start();
            RuntimeException failure = new RuntimeException("boom");

            // when / then
            assertThatThrownBy(() -> scope.within(() -> {
                throw failure;
            })).isSameAs(failure);
            scope.close();
        }
    }
}
