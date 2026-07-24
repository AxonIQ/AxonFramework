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

package org.axonframework.messaging.commandhandling.tracing;

import org.axonframework.messaging.tracing.support.TestSpanFactory;
import org.axonframework.messaging.tracing.support.TestSpanFactory.TestSpanType;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TracingCommandBusTest {

    private static final String DISPATCH_SPAN = "CommandBus.dispatch MyCommand";
    private static final String HANDLE_SPAN = "CommandBus.handle MyCommand";

    private TestSpanFactory spanFactory;
    private RecordingCommandBus delegate;
    private TracingCommandBus testSubject;

    private final CommandMessage command =
            new GenericCommandMessage(new MessageType("MyCommand"), "the-payload");
    private final CommandResultMessage result =
            new GenericCommandResultMessage(new MessageType("Result"), "ok");

    @BeforeEach
    void setUp() {
        spanFactory = new TestSpanFactory();
        delegate = new RecordingCommandBus();
        testSubject = new TracingCommandBus(delegate, spanFactory);
    }

    @Nested
    class Dispatch {

        @Test
        void opensCompletesAndPropagatesADispatchSpan() {
            // given
            delegate.dispatchResult = CompletableFuture.completedFuture(result);

            // when
            testSubject.dispatch(command, null).orTimeout(2, TimeUnit.SECONDS).join();

            // then
            spanFactory.verifySpanCompleted(DISPATCH_SPAN);
            spanFactory.verifySpanHasType(DISPATCH_SPAN, TestSpanType.DISPATCH);
            spanFactory.verifySpanPropagated(DISPATCH_SPAN, command);
        }

        @Test
        void recordsExceptionWhenDispatchFails() {
            // given
            delegate.dispatchResult = CompletableFuture.failedFuture(new IllegalStateException("boom"));

            // when
            CompletableFuture<CommandResultMessage> dispatched = testSubject.dispatch(command, null);

            // then
            assertThatThrownBy(() -> dispatched.orTimeout(2, TimeUnit.SECONDS).join())
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            spanFactory.verifySpanCompleted(DISPATCH_SPAN);
            spanFactory.verifySpanHasException(DISPATCH_SPAN, IllegalStateException.class);
        }
    }

    @Nested
    class Handle {

        @Test
        void wrapsSubscribedHandlerToOpenAHandlerSpan() {
            // given
            testSubject.subscribe(new QualifiedName("MyCommand"),
                                  (cmd, context) -> MessageStream.just(result));
            CommandHandler wrapped = delegate.subscribedHandler.get();

            // when
            wrapped.handle(command, new StubProcessingContext());

            // then
            assertThat(wrapped).isNotNull();
            spanFactory.verifySpanActive(HANDLE_SPAN);
            spanFactory.verifySpanHasType(HANDLE_SPAN, TestSpanType.HANDLER);
        }
    }

    @Nested
    class Introspection {

        @Test
        void describesItselfAsAWrapperOfTheDelegate() {
            // given
            RecordingComponentDescriptor descriptor = new RecordingComponentDescriptor();

            // when
            testSubject.describeTo(descriptor);

            // then
            assertThat(descriptor.wrapped).isSameAs(delegate);
        }
    }

    /**
     * Minimal {@link CommandBus} stub recording the subscribed handler and returning a configurable dispatch result.
     */
    private static final class RecordingCommandBus implements CommandBus {

        private final AtomicReference<CommandHandler> subscribedHandler = new AtomicReference<>();
        private CompletableFuture<CommandResultMessage> dispatchResult = new CompletableFuture<>();

        @Override
        public CompletableFuture<CommandResultMessage> dispatch(CommandMessage command,
                                                                @Nullable ProcessingContext processingContext) {
            return dispatchResult;
        }

        @Override
        public CommandBus subscribe(QualifiedName name, CommandHandler commandHandler) {
            subscribedHandler.set(commandHandler);
            return this;
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
            // not relevant to these tests
        }
    }

    /**
     * Captures the single {@code describeWrapperOf} target for introspection assertions.
     */
    private static final class RecordingComponentDescriptor implements ComponentDescriptor {

        private @Nullable Object wrapped;

        @Override
        public void describeWrapperOf(Object delegate) {
            this.wrapped = delegate;
        }

        @Override
        public void describeProperty(String name, @Nullable Object object) {
        }

        @Override
        public void describeProperty(String name, @Nullable Collection<?> collection) {
        }

        @Override
        public void describeProperty(String name, @Nullable Map<?, ?> map) {
        }

        @Override
        public void describeProperty(String name, @Nullable String value) {
        }

        @Override
        public void describeProperty(String name, @Nullable Long value) {
        }

        @Override
        public void describeProperty(String name, @Nullable Boolean value) {
        }
    }
}
