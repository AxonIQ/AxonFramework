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
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
    void runExecutesTheGivenBlock() {
        // given
        AtomicBoolean executed = new AtomicBoolean(false);

        // when
        testSubject.createInternalSpan("op", null).run(() -> executed.set(true));

        // then
        assertThat(executed).isTrue();
    }

    @Test
    void runSupplierReturnsTheSuppliedValue() {
        // when
        String result = testSubject.createHandlerSpan("op", message, null).runSupplier(() -> "value");

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
}
