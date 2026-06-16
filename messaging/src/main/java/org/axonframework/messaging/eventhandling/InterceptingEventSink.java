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

package org.axonframework.messaging.eventhandling;

import org.jspecify.annotations.Nullable;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.DecoratorDefinition;
import org.axonframework.messaging.core.DefaultMessageDispatchInterceptorChain;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Decorator around the {@link EventSink} intercepting all {@link EventMessage events} before they are
 * {@link #publish(ProcessingContext, List) published} with {@link MessageDispatchInterceptor dispatch interceptors}.
 * <p>
 * This {@code InterceptingEventSink} is typically registered as a
 * {@link ComponentRegistry#registerDecorator(DecoratorDefinition) decorator} and automatically kicks in whenever
 * {@code MessageDispatchInterceptors} are present.
 *
 * @author Steven van Beelen
 * @author John Hendrikx
 * @since 5.0.0
 */
@Internal
public class InterceptingEventSink implements EventSink {

    /**
     * The order in which the {@link InterceptingEventSink} is applied as a
     * {@link ComponentRegistry#registerDecorator(DecoratorDefinition) decorator} to the {@link EventSink}.
     * <p>
     * As such, any decorator with a lower value will be applied to the delegate, and any higher value will be applied
     * to the {@code InterceptingEventSink} itself. Using the same value can either lead to application of the decorator
     * to the delegate or the {@code InterceptingEventSink}, depending on the order of registration.
     * <p>
     * The order of the {@code InterceptingEventSink} is set to {@code Integer.MIN_VALUE + 100} to ensure it is applied
     * very early in the configuration process, but not the earliest to allow for other decorators to be applied.
     */
    public static final int DECORATION_ORDER = Integer.MIN_VALUE + 100;

    private final EventSink delegate;
    private final List<MessageDispatchInterceptor<? super EventMessage>> interceptors;
    private final InterceptingPublisher interceptingPublisher;

    /**
     * Constructs a {@code InterceptingEventSink}, delegating publishing to the given {@code delegate}.
     * <p>
     * The given {@code interceptors} are invoked before {@link #publish(ProcessingContext, List) publishing} is done by
     * the given {@code delegate}.
     *
     * @param delegate     The delegate {@code EventSink} that will handle all dispatching and handling logic.
     * @param interceptors The interceptors to invoke before publishing an event.
     */
    public InterceptingEventSink(EventSink delegate,
                                 List<MessageDispatchInterceptor<? super EventMessage>> interceptors) {
        this.delegate = Objects.requireNonNull(delegate, "The EventSink may not be null.");
        this.interceptors = Objects.requireNonNull(interceptors, "The dispatch interception must not be null.");
        this.interceptingPublisher = new InterceptingPublisher();
    }

    @Override
    public CompletableFuture<Void> publish(@Nullable ProcessingContext context,
                                           List<? extends EventMessage> events) {
        return interceptingPublisher.interceptAndPublish(events, context);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("dispatchInterceptors", interceptors);
    }

    private class InterceptingPublisher {

        private final DefaultMessageDispatchInterceptorChain<? super EventMessage> interceptorChain;

        private InterceptingPublisher() {
            this.interceptorChain = new DefaultMessageDispatchInterceptorChain<>(interceptors);
        }

        private <M extends EventMessage> CompletableFuture<Void> interceptAndPublish(
                List<M> events,
                @Nullable ProcessingContext context
        ) {

            /*
             * Captures events in a batch. A fast path for when there
             * is only a single event here is not possible because the
             * interceptor may produce more than one event.
             */

            return MessageStream.fromIterable(events)
                .flatMap(entry -> interceptorChain.proceed(entry.message(), context).<M>cast())
                .collect(ArrayList<M>::new, List::add)
                .thenCompose(interceptedEvents -> delegate.publish(context, interceptedEvents));
        }
    }
}
