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

import org.axonframework.messaging.tracing.Span;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Delegating {@link CommandBus} decorator that opens a tracing span around command dispatch and command handling.
 * <p>
 * On {@link #dispatch(CommandMessage, ProcessingContext) dispatch} a dispatch span is opened, the active tracing
 * context is propagated onto the command's metadata (so a remote handler can continue the trace), and the span is
 * ended when the dispatch future completes. Each subscribed {@link CommandHandler} is wrapped so that handling opens a
 * handler span -- parented on the dispatch span via the propagated context -- bound to the handling
 * {@link ProcessingContext}'s lifecycle.
 * <p>
 * This decorator is registered by {@code MessagingTracingConfigurationEnhancer}; it is never instantiated directly by
 * applications.
 *
 * @author Mateusz Nowak
 * @author Allard Buijze
 * @since 5.0.0
 */
@Internal
public final class TracingCommandBus implements CommandBus {

    /** Prefix for the command-dispatch span ({@code "CommandBus.dispatchCommand <name>"}). */
    public static final String DISPATCH_SPAN = "CommandBus.dispatchCommand";

    /** Prefix for the command-handle span ({@code "CommandBus.handleCommand <name>"}). */
    public static final String HANDLE_SPAN = "CommandBus.handleCommand";

    private final CommandBus delegate;
    private final SpanFactory spanFactory;

    /**
     * Initializes a tracing {@link CommandBus} wrapping the given {@code delegate}, obtaining spans from the given
     * {@code spanFactory}.
     *
     * @param delegate    the command bus to delegate to
     * @param spanFactory the factory producing the tracing spans
     */
    public TracingCommandBus(CommandBus delegate, SpanFactory spanFactory) {
        this.delegate = Objects.requireNonNull(delegate, "delegate may not be null");
        this.spanFactory = Objects.requireNonNull(spanFactory, "spanFactory may not be null");
    }

    @Override
    public CompletableFuture<CommandResultMessage> dispatch(CommandMessage command,
                                                            @Nullable ProcessingContext processingContext) {
        Span span = spanFactory.createDispatchSpan(
                DISPATCH_SPAN + " " + command.type().qualifiedName().name(), command, processingContext
        );
        return span.runSupplierAsync(
                () -> delegate.dispatch(span.propagateContext(command), processingContext)
        );
    }

    @Override
    public CommandBus subscribe(QualifiedName name, CommandHandler commandHandler) {
        delegate.subscribe(name, new TracingCommandHandler(commandHandler, spanFactory));
        return this;
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("spanFactory", spanFactory);
    }

    /**
     * Wraps a {@link CommandHandler} to open a handler span around its invocation, bound to the handling context's
     * lifecycle.
     */
    private static final class TracingCommandHandler implements CommandHandler {

        private final CommandHandler delegate;
        private final SpanFactory spanFactory;

        private TracingCommandHandler(CommandHandler delegate, SpanFactory spanFactory) {
            this.delegate = delegate;
            this.spanFactory = spanFactory;
        }

        @Override
        public MessageStream.Single<CommandResultMessage> handle(CommandMessage command, ProcessingContext context) {
            spanFactory.createHandlerSpan(HANDLE_SPAN + " " + command.type().qualifiedName().name(), command, context)
                       .start(context);
            return delegate.handle(command, context);
        }
    }
}
