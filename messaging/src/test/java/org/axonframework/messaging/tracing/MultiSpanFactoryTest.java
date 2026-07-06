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

import org.axonframework.messaging.tracing.support.TestSpanFactory;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultiSpanFactoryTest {

    private TestSpanFactory first;
    private TestSpanFactory second;
    private MultiSpanFactory testSubject;

    private final Message message = EventTestUtils.asEventMessage("payload");

    @BeforeEach
    void setUp() {
        first = new TestSpanFactory();
        second = new TestSpanFactory();
        testSubject = new MultiSpanFactory(List.of(first, second));
    }

    @Test
    void startingAndClosingFansOutToEveryDelegate() {
        // when
        SpanScope scope = testSubject.createDispatchSpan("op", message, null).start();

        // then
        first.verifySpanActive("op");
        second.verifySpanActive("op");

        // when
        scope.close();

        // then
        first.verifySpanCompleted("op");
        second.verifySpanCompleted("op");
    }

    @Test
    void addAttributeFansOutToEveryDelegate() {
        // given
        Span span = testSubject.createHandlerSpan("op", message, null);
        span.start();

        // when
        span.addAttribute("key", "value");

        // then
        first.verifySpanHasAttributeValue("op", "key", "value");
        second.verifySpanHasAttributeValue("op", "key", "value");
    }

    @Test
    void emptyDelegateListIsRejected() {
        // when / then
        assertThatThrownBy(() -> new MultiSpanFactory(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void spanPropagateContextFansOutToEveryDelegate() {
        // given
        Span span = testSubject.createDispatchSpan("op", message, null);
        span.start();

        // when
        Message result = span.propagateContext(message);

        // then
        assertThat(result).isNotNull();
        first.verifySpanPropagated("op", message);
        second.verifySpanPropagated("op", message);
    }
}
