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

package org.axonframework.eventsourcing.eventstore;

import org.jspecify.annotations.Nullable;
import org.axonframework.common.Registration;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.DecoratorDefinition;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.InterceptingEventBus;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.DefaultMessageDispatchInterceptorChain;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Decorator around the {@link EventStore} intercepting all {@link EventMessage events} before they are
 * {@link EventStoreTransaction#appendEvent(EventMessage) appended} or
 * {@link #publish(ProcessingContext, List) published} with {@link MessageDispatchInterceptor dispatch interceptors}.
 * <p>
 * This {@code InterceptingEventStore} is typically registered as a
 * {@link ComponentRegistry#registerDecorator(DecoratorDefinition) decorator} and automatically kicks in whenever
 * {@code MessageDispatchInterceptors} are present.
 *
 * @author Steven van Beelen
 * @since 5.0.0
 */
@Internal
public class InterceptingEventStore implements EventStore {

    /**
     * The order in which the {@link InterceptingEventStore} is applied as a
     * {@link ComponentRegistry#registerDecorator(DecoratorDefinition) decorator} to the {@link EventStore}.
     * <p>
     * As such, any decorator with a lower value will be applied to the delegate, and any higher value will be applied
     * to the {@code InterceptingEventStore} itself. Using the same value can either lead to application of the
     * decorator to the delegate or the {@code InterceptingEventStore}, depending on the order of registration.
     * <p>
     * The order of the {@code InterceptingEventStore} is set to {@code Integer.MIN_VALUE + 50} to ensure it is applied
     * very early in the configuration process, but not the earliest to allow for other decorators to be applied.
     */
    public static final int DECORATION_ORDER = Integer.MIN_VALUE + 50;

    private final EventStore delegate;
    private final List<MessageDispatchInterceptor<? super EventMessage>> interceptors;
    private final InterceptingAppender interceptingAppender;
    private final InterceptingEventBus delegateBus;

    private final Context.ResourceKey<EventStoreTransaction> delegateTransactionKey;
    private final Context.ResourceKey<EventStoreTransaction> interceptingTransactionKey;

    /**
     * Constructs a {@code InterceptingEventStore}, delegating all operation to the given {@code delegate}.
     * <p>
     * The given {@code interceptors} are invoked before {@link EventStoreTransaction#appendEvent(EventMessage)} or
     * {@link #publish(ProcessingContext, List) publishing} is done by the given {@code delegate}.
     *
     * @param delegate     The delegate {@code EventSink} that will handle all dispatching and handling logic.
     * @param interceptors The interceptors to invoke before appending and publishing an event.
     */
    @Internal
    public InterceptingEventStore(EventStore delegate,
                                  List<MessageDispatchInterceptor<? super EventMessage>> interceptors) {
        this.delegateTransactionKey = Context.ResourceKey.withLabel("delegateTransaction");
        this.interceptingTransactionKey = Context.ResourceKey.withLabel("interceptingTransaction");

        this.delegate = Objects.requireNonNull(delegate, "The EventStore may not be null.");
        this.interceptors = Objects.requireNonNull(interceptors, "The dispatch interceptors must not be null.");
        this.interceptingAppender =
                new InterceptingAppender(interceptors, context -> context.getResource(delegateTransactionKey));
        this.delegateBus = new InterceptingEventBus(delegate, interceptors);
    }

    @Override
    public EventStoreTransaction transaction(ProcessingContext processingContext) {
        // Set the delegate transaction to ensure the InterceptingAppender can reach the correct EventStoreTransaction.
        EventStoreTransaction delegateTransaction = getAndSetDelegateTransaction(processingContext);
        // Set the intercepting transaction to ensure subsequent transaction operation receive the same intercepting transaction.
        return processingContext.computeResourceIfAbsent(
                interceptingTransactionKey,
                () -> new InterceptingEventStoreTransaction(processingContext, delegateTransaction)
        );
    }

    /**
     * Gets the {@link EventStoreTransaction} from given {@code context}, if present.
     * <p>
     * If not present, we invoked the delegate {@link EventStore#transaction(ProcessingContext)} operation and set it
     * afterward. This is deliberately not done in a
     * {@link ProcessingContext#computeResourceIfAbsent(Context.ResourceKey, Supplier)}, as the delegate
     * {@link EventStore} typically uses the {@code computeResourceIfAbsent}. Hence, we are optionally faced with
     * concurrency exceptions by using {@code computeResourceIfAbsent} here.
     * <p>
     * Although this solution may lead to an override of the delegate {@code EventStoreTransaction} in the resources,
     * given that the {@code EventStore#transaction} method describes it adds the transaction to the context, we may
     * assume <b>it</b> is thread safe. Hence, implementations will ensure that not using
     * {@code computeResourceIfAbsent} is safe.
     * <p>
     * Lastly, note that we are making this loop to ensure the {@code InterceptingEventStore} does not have to create a
     * {@link DefaultMessageDispatchInterceptorChain} for every new transaction!
     *
     * @param context The context to retrieve the delegate {@code EventStoreTransaction} from. When not present, it will
     *                be added to this context.
     * @return The {@code EventStoreTransaction} from the delegate {@link EventStore}.
     */
    private EventStoreTransaction getAndSetDelegateTransaction(ProcessingContext context) {
        EventStoreTransaction delegateTransaction;
        if (context.containsResource(delegateTransactionKey)) {
            delegateTransaction = context.getResource(delegateTransactionKey);
        } else {
            delegateTransaction = delegate.transaction(context);
            context.putResourceIfAbsent(delegateTransactionKey, delegateTransaction);
        }
        return delegateTransaction;
    }

    @Override
    public CompletableFuture<Void> publish(@Nullable ProcessingContext context,
                                           List<? extends EventMessage> events) {
        return delegateBus.publish(context, events);
    }

    @Override
    public MessageStream<EventMessage> open(StreamingCondition condition,
                                            @Nullable ProcessingContext context) {
        return delegate.open(condition, context);
    }

    @Override
    public CompletableFuture<TrackingToken> firstToken(@Nullable ProcessingContext context) {
        return delegate.firstToken(context);
    }

    @Override
    public CompletableFuture<TrackingToken> latestToken(@Nullable ProcessingContext context) {
        return delegate.latestToken(context);
    }

    @Override
    public CompletableFuture<TrackingToken> tokenAt(Instant at, @Nullable ProcessingContext context) {
        return delegate.tokenAt(at, context);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("dispatchInterceptors", interceptors);
        descriptor.describeProperty("delegateBus", delegateBus);
    }

    @Override
    public Registration subscribe(
            BiFunction<List<? extends EventMessage>, ProcessingContext, CompletableFuture<?>> eventsBatchConsumer
    ) {
        return delegate.subscribe(eventsBatchConsumer);
    }

    private class InterceptingEventStoreTransaction implements EventStoreTransaction {

        private final ProcessingContext context;
        private final EventStoreTransaction delegate;

        private InterceptingEventStoreTransaction(ProcessingContext context,
                                                  EventStoreTransaction delegate) {
            this.delegate = delegate;
            this.context = context;
        }

        @Override
        public MessageStream<? extends EventMessage> source(
            SourcingCondition condition,
            @Nullable Consumer<Position> resumePositionCallback
        ) {
            return delegate.source(condition, resumePositionCallback);
        }

        @Override
        public void appendEvent(EventMessage eventMessage) {
            interceptingAppender.interceptAndAppend(eventMessage, context);
        }

        @Override
        public void overrideAppendCondition(UnaryOperator<AppendCondition> conditionOverride) {
            delegate.overrideAppendCondition(conditionOverride);
        }

        @Override
        public void onAppend(Consumer<EventMessage> callback) {
            delegate.onAppend(callback);
        }

        @Override
        public ConsistencyMarker appendPosition() {
            return delegate.appendPosition();
        }
    }

    private static class InterceptingAppender {

        private final DefaultMessageDispatchInterceptorChain<? super EventMessage> interceptorChain;

        private InterceptingAppender(List<MessageDispatchInterceptor<? super EventMessage>> interceptors,
                                     Function<ProcessingContext, EventStoreTransaction> transactionProvider) {
            this.interceptorChain = new DefaultMessageDispatchInterceptorChain<>(interceptors, (event, context) -> {
                transactionProvider.apply(context).appendEvent(event);
                return MessageStream.empty();
            });
        }

        private void interceptAndAppend(EventMessage event,
                                        @Nullable ProcessingContext context) {
            interceptorChain.proceed(event, context)
                            .ignoreEntries()
                            .asCompletableFuture()
                            .join();
        }
    }
}
