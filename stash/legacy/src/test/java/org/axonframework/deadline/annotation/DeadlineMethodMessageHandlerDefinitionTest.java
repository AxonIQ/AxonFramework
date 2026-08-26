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

package org.axonframework.deadline.annotation;

import org.axonframework.deadline.GenericDeadlineMessage;
import org.axonframework.messaging.eventhandling.annotation.AnnotationEventHandlerAdapter;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.junit.jupiter.api.*;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;

@Disabled("TODO #3065 - Currently broken because the DeadlineMethodMessageHandlerDefinition is not service loaded")
class DeadlineMethodMessageHandlerDefinitionTest {

    private AnnotationEventHandlerAdapter handlerAdapter;
    private Listener listener;

    @BeforeEach
    void setUp() {
        listener = new Listener();
        handlerAdapter = new AnnotationEventHandlerAdapter(listener, new ClassBasedMessageTypeResolver());
    }

    @Test
    void deadlineManagerIsEvaluatedBeforeGenericEventHandler() throws Exception {
        GenericDeadlineMessage event = new GenericDeadlineMessage(
                "someDeadline", new MessageType("deadline"), "test"
        );
        handlerAdapter.handleSync(event, StubProcessingContext.forMessage(event));

        assertThat("Deadline handler is invoked", listener.deadlineCounter.get() == 1);
        assertThat("Event handler was not invoked", listener.eventCounter.get() == 0);
    }

    @Test
    void namedDeadlineManagerIsEvaluatedBeforeGenericOne() throws Exception {
        GenericDeadlineMessage event = new GenericDeadlineMessage(
                "specificDeadline", new MessageType("deadline"), "test"
        );
        handlerAdapter.handleSync(event, StubProcessingContext.forMessage(event));

        assertThat("Generic Deadline handler was not invoked", listener.deadlineCounter.get() == 0);
        assertThat("Specific Deadline handler was invoked", listener.specificDeadlineCounter.get() == 1);
    }


    @SuppressWarnings("unused")
    private static class Listener {

        private final AtomicInteger eventCounter = new AtomicInteger();
        private final AtomicInteger deadlineCounter = new AtomicInteger();
        private final AtomicInteger specificDeadlineCounter = new AtomicInteger();

        @EventHandler
        public void handleA(String event) {
            eventCounter.incrementAndGet();
        }

        @DeadlineHandler
        public void handleDeadline(String event) {
            deadlineCounter.incrementAndGet();
        }

        @DeadlineHandler(deadlineName = "specificDeadline")
        public void handleSpecificDeadline(String event) {
            specificDeadlineCounter.incrementAndGet();
        }
    }
}