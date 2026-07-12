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

package org.axonframework.messaging.tracing.support;

import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.tracing.Span;
import org.axonframework.messaging.tracing.SpanScope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSpanFactoryTest {

    private final TestSpanFactory testSubject = new TestSpanFactory();

    @Test
    void linkedHandlerSpanRecordsPropagatedParentAndLink() {
        // given
        Message handledMessage = new GenericMessage(new MessageType("Update"), "update");
        Message linkedMessage = new GenericMessage(new MessageType("Query"), "query");
        propagateFrom("Emitter", handledMessage);
        propagateFrom("Subscription", linkedMessage);

        // when
        testSubject.createLinkedHandlerSpan("Delivery", handledMessage, linkedMessage, null)
                   .branch(null, ignored -> null);

        // then
        testSubject.verifySpanHasParent("Delivery", "Emitter");
        testSubject.verifySpanHasLink("Delivery", "Subscription");
    }

    @Test
    void verifySpanCountDistinguishesDuplicateSpansUnderTheSameName() {
        // given two spans opened under the same name
        testSubject.createInternalSpan("repeated", null).branch(null, ignored -> null);
        testSubject.createInternalSpan("repeated", null).branch(null, ignored -> null);

        // then the exact count is asserted, so a duplicate-span regression cannot hide behind a findFirst match
        testSubject.verifySpanCount("repeated", 2);
        assertThatThrownBy(() -> testSubject.verifySpanCount("repeated", 1)).isInstanceOf(AssertionError.class);
    }

    @Test
    void verifyNoSpanWithNamePrefixCatchesDifferentlySuffixedSpans() {
        // given a span whose name shares a prefix but ends in a different message type
        testSubject.createInternalSpan("Connector.queryUpdate Result", null).branch(null, ignored -> null);

        // then the prefix check catches what an exact-name check would miss
        testSubject.verifyNoSpan("Connector.queryUpdate MyUpdate");
        assertThatThrownBy(() -> testSubject.verifyNoSpanWithNamePrefix("Connector.queryUpdate"))
                .isInstanceOf(AssertionError.class);
        testSubject.verifyNoSpanWithNamePrefix("Connector.otherOperation");
    }

    @Test
    void verifyContextCarriesScopeOfIdentifiesTheExactSpanCarriedOnTheContext() {
        // given a context branched with one span's scope while another span exists
        testSubject.createInternalSpan("other", null).start();
        Span carried = testSubject.createInternalSpan("carried", null);
        ProcessingContext context = SpanScope.addToContext(new StubProcessingContext(), carried.start());

        // then the scope on the context is attributed to the right span, not just any active span
        testSubject.verifyContextCarriesScopeOf("carried", context);
        assertThatThrownBy(() -> testSubject.verifyContextCarriesScopeOf("other", context))
                .isInstanceOf(AssertionError.class);
    }

    private void propagateFrom(String spanName, Message message) {
        Span span = testSubject.createDispatchSpan(spanName, message, null);
        try (SpanScope ignored = span.start()) {
            span.propagateContext(message);
        }
    }
}
