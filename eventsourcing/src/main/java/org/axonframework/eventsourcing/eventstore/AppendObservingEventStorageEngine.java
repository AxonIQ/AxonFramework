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

import org.axonframework.common.ExceptionUtils;
import org.axonframework.common.configuration.DecoratingComponent;
import org.axonframework.common.configuration.DecoratorDefinition;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.eventsourcing.eventstore.AppendObservation.Outcome;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Decorator for an {@link EventStorageEngine} that reports the terminal outcome of every append to an
 * {@link AppendObserver}.
 * <p>
 * An append is reported at most once, as {@link Outcome.Committed committed} once the
 * {@link AppendTransaction#afterCommit(Object) consistency marker} of the committed events is known,
 * {@link Outcome.Rejected rejected} when an {@link AppendEventsTransactionRejectedException} signals that its
 * {@link AppendCondition} was violated, {@link Outcome.Failed failed} on any other failure, or
 * {@link Outcome.RolledBack rolled back} when the {@link AppendTransaction} is rolled back before any of the other
 * outcomes was reported. Rejections are recognized both when the wrapped engine detects them while starting the
 * append and when it detects them on {@link AppendTransaction#commit() commit}, and also when the rejection is wrapped
 * in another exception, such as a {@link CompletionException}.
 * <p>
 * A rollback that follows a successful {@link AppendTransaction#commit() commit} is not reported, as the events are
 * stored by then. This happens when another commit action of the surrounding {@link ProcessingContext} fails after the
 * append committed: the {@link AppendTransaction#afterCommit(Object) after commit} step then never runs, so no outcome
 * is reported for that append.
 * <p>
 * The decorator only observes. It returns the values of the wrapped engine and relays its failures unchanged, and an
 * exception thrown by the {@link AppendObserver} is logged at {@code WARN} level without affecting the append. An
 * {@link Error} thrown by the observer does not change the result either: it is logged at {@code ERROR} level and, for
 * a rejected or failed append, also added as a {@link Throwable#addSuppressed(Throwable) suppressed} exception to the
 * cause of the relayed failure, with any {@link CompletionException} or {@link ExecutionException} wrapper removed, so
 * it reaches the caller together with that cause. The observer is invoked within the stage the caller depends on, so the outcome is reported before the caller
 * continues. All read and token operations are delegated to the wrapped engine as is.
 * <p>
 * The supported way to install this decorator is through {@link #decoratorDefinition()} or
 * {@link #decoratorDefinition(AppendObserver)}, which decorate every {@link EventStorageEngine} component of the
 * configuration at {@link #DECORATION_ORDER}. The former reports to the {@link AppendObserver} component, if one is
 * registered, and otherwise to a {@link LoggingAppendObserver}:
 * <pre>{@code
 * EventSourcingConfigurer.create()
 *                        .componentRegistry(registry -> registry
 *                                .registerComponent(AppendObserver.class, config -> new MyAppendObserver())
 *                                .registerDecorator(AppendObservingEventStorageEngine.decoratorDefinition()));
 * }</pre>
 * In a Spring Boot application, declare the {@link DecoratorDefinition} returned by {@link #decoratorDefinition()} as
 * a bean, optionally next to an {@link AppendObserver} bean.
 *
 * @author Alexey Afanasyev
 * @see AppendObserver
 * @see AppendObservation
 * @since 5.5.0
 */
public final class AppendObservingEventStorageEngine implements EventStorageEngine, DecoratingComponent {

    /**
     * The order in which the {@link #decoratorDefinition() decorator definitions} of this class decorate an
     * {@link EventStorageEngine}.
     * <p>
     * It is higher than the order of the decorator adding snapshot support, so appends are observed outside of it,
     * and lower than the order of the tracing decorators, so a traced append also covers the time spent in the
     * {@link AppendObserver}.
     */
    public static final int DECORATION_ORDER = 100;

    private static final Logger logger = LoggerFactory.getLogger(AppendObservingEventStorageEngine.class);

    private final EventStorageEngine delegate;
    private final AppendObserver observer;

    /**
     * Constructs an {@code AppendObservingEventStorageEngine} wrapping the given {@code delegate}, reporting append
     * outcomes to the given {@code observer}.
     *
     * @param delegate the {@link EventStorageEngine} to delegate to, cannot be {@code null}
     * @param observer the {@link AppendObserver} to report append outcomes to, cannot be {@code null}
     */
    public AppendObservingEventStorageEngine(EventStorageEngine delegate, AppendObserver observer) {
        this.delegate = Objects.requireNonNull(delegate, "The delegate parameter cannot be null.");
        this.observer = Objects.requireNonNull(observer, "The observer parameter cannot be null.");
    }

    /**
     * Returns a {@link DecoratorDefinition} decorating every {@link EventStorageEngine} component with an
     * {@code AppendObservingEventStorageEngine} at {@link #DECORATION_ORDER}.
     * <p>
     * Append outcomes are reported to the {@link AppendObserver} component of the configuration, if one is
     * registered, and to a {@link LoggingAppendObserver} otherwise.
     *
     * @return a {@link DecoratorDefinition} observing the appends of every {@link EventStorageEngine} component
     */
    public static DecoratorDefinition<EventStorageEngine, AppendObservingEventStorageEngine> decoratorDefinition() {
        return DecoratorDefinition.forType(EventStorageEngine.class)
                                  .with((config, name, delegate) -> new AppendObservingEventStorageEngine(
                                          delegate,
                                          config.getOptionalComponent(AppendObserver.class)
                                                .orElseGet(LoggingAppendObserver::new)
                                  ))
                                  .order(DECORATION_ORDER);
    }

    /**
     * Returns a {@link DecoratorDefinition} decorating every {@link EventStorageEngine} component with an
     * {@code AppendObservingEventStorageEngine} at {@link #DECORATION_ORDER}, reporting append outcomes to the given
     * {@code observer}.
     *
     * @param observer the {@link AppendObserver} to report append outcomes to, cannot be {@code null}
     * @return a {@link DecoratorDefinition} observing the appends of every {@link EventStorageEngine} component
     */
    public static DecoratorDefinition<EventStorageEngine, AppendObservingEventStorageEngine> decoratorDefinition(
            AppendObserver observer
    ) {
        Objects.requireNonNull(observer, "The observer parameter cannot be null.");
        return DecoratorDefinition.forType(EventStorageEngine.class)
                                  .with((config, name, delegate) ->
                                                new AppendObservingEventStorageEngine(delegate, observer))
                                  .order(DECORATION_ORDER);
    }

    @Override
    public CompletableFuture<AppendTransaction<?>> appendEvents(AppendCondition condition,
                                                                 @Nullable ProcessingContext context,
                                                                 List<TaggedEventMessage<?>> events) {
        AppendReporter reporter = new AppendReporter(condition, events, context);
        return relay(() -> delegate.appendEvents(condition, context, events),
                     reporter,
                     transaction -> observing(transaction, reporter));
    }

    private static AppendTransaction<?> observing(AppendTransaction<?> transaction, AppendReporter reporter) {
        return observingTyped(transaction, reporter);
    }

    private static <R> AppendTransaction<R> observingTyped(AppendTransaction<R> transaction,
                                                           AppendReporter reporter) {
        return new ObservingAppendTransaction<>(transaction, reporter, new AtomicBoolean(false));
    }

    /**
     * Invokes the given {@code operation} and returns a future that completes like the future of the operation, but
     * only after its outcome has been handled: a failure is reported to the given {@code reporter}, and a result is
     * handed to {@code onSuccess}, which returns the value to complete with.
     * <p>
     * A failure is relayed as the very instance the operation failed with, and a synchronous exception of the
     * operation is reported and rethrown. The returned future is completed in a {@code finally} block, so it completes
     * even when handling the outcome throws; should {@code onSuccess} fail, the result of the operation is relayed as
     * is.
     */
    private static <T> CompletableFuture<T> relay(Supplier<CompletableFuture<T>> operation,
                                                  AppendReporter reporter,
                                                  UnaryOperator<T> onSuccess) {
        CompletableFuture<T> result;
        try {
            result = Objects.requireNonNull(operation.get(),
                                            "The EventStorageEngine returned a null CompletableFuture.");
        } catch (Throwable failure) {
            reporter.reportFailure(failure);
            throw failure;
        }
        CompletableFuture<T> relayed = new CompletableFuture<>();
        result.whenComplete((value, failure) -> {
            if (failure != null) {
                try {
                    reporter.reportFailure(failure);
                } finally {
                    relayed.completeExceptionally(failure);
                }
            } else {
                T handled = value;
                try {
                    handled = onSuccess.apply(value);
                } finally {
                    relayed.complete(handled);
                }
            }
        });
        return relayed;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public MessageStream<EventMessage> source(SourcingCondition condition, @Nullable ProcessingContext context) {
        return delegate.source(condition, context);
    }

    @Override
    public MessageStream<EventMessage> stream(StreamingCondition condition) {
        return delegate.stream(condition);
    }

    @Override
    public CompletableFuture<TrackingToken> firstToken() {
        return delegate.firstToken();
    }

    @Override
    public CompletableFuture<TrackingToken> latestToken() {
        return delegate.latestToken();
    }

    @Override
    public CompletableFuture<TrackingToken> tokenAt(Instant at) {
        return delegate.tokenAt(at);
    }

    @Override
    public Object decoratedDelegate() {
        return delegate;
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("observer", observer);
    }

    /**
     * Reports the outcome of a single append to the {@link AppendObserver}, making sure only the first reported
     * outcome reaches it.
     * <p>
     * The reporter keeps a copy of the appended events, so the reported events are those offered when the append
     * started.
     */
    private final class AppendReporter {

        private final AppendCondition condition;
        private final List<TaggedEventMessage<?>> events;
        private final @Nullable ProcessingContext context;
        private final AtomicBoolean reported = new AtomicBoolean(false);

        private AppendReporter(AppendCondition condition,
                               List<TaggedEventMessage<?>> events,
                               @Nullable ProcessingContext context) {
            this.condition = condition;
            this.events = List.copyOf(events);
            this.context = context;
        }

        /**
         * Reports the given {@code failure} as a rejection or as a failure. An {@link Error} thrown by the observer is
         * logged and added as a suppressed exception to the cause of the given {@code failure}, with any completion
         * wrapper removed, as callers routinely strip such wrappers and would otherwise lose the error.
         */
        private void reportFailure(Throwable failure) {
            Throwable cause = unwrap(failure);
            Outcome outcome = ExceptionUtils.findException(failure, AppendEventsTransactionRejectedException.class)
                                            .<Outcome>map(Outcome.Rejected::new)
                                            .orElseGet(() -> new Outcome.Failed(cause));
            try {
                report(outcome);
            } catch (Error observerError) {
                logger.error("AppendObserver [{}] threw an error; the append outcome is unaffected",
                             observer, observerError);
                if (observerError != cause) {
                    cause.addSuppressed(observerError);
                }
            }
        }

        /**
         * Reports the given successful {@code outcome}. An {@link Error} thrown by the observer is logged, since the
         * caller receives the successful result of the append rather than a failure it could be attached to.
         */
        private void reportSuccess(Outcome outcome) {
            try {
                report(outcome);
            } catch (Error observerError) {
                logger.error("AppendObserver [{}] threw an error; the append outcome is unaffected",
                             observer, observerError);
            }
        }

        private void report(Outcome outcome) {
            if (!reported.compareAndSet(false, true)) {
                return;
            }
            try {
                observer.observe(new AppendObservation(condition, events, context, outcome));
            } catch (Exception observerFailure) {
                logger.warn("AppendObserver [{}] failed; the append outcome is unaffected",
                            observer, observerFailure);
            }
        }
    }

    /**
     * An {@link AppendTransaction} reporting the outcome of its commit, its
     * {@link AppendTransaction#afterCommit(Object) after commit} step, or its rollback.
     * <p>
     * A rollback following a successful commit is not reported, as the events are stored by then.
     *
     * @param delegate  the {@link AppendTransaction} to delegate to
     * @param reporter  the {@link AppendReporter} to report the outcome of the append to
     * @param committed whether the {@link AppendTransaction#commit() commit} of the delegate succeeded
     * @param <R>       the type of the commit result of the wrapped transaction
     */
    private record ObservingAppendTransaction<R>(AppendTransaction<R> delegate,
                                                 AppendReporter reporter,
                                                 AtomicBoolean committed) implements AppendTransaction<R> {

        @Override
        public CompletableFuture<R> commit() {
            return relay(delegate::commit, reporter, commitResult -> {
                committed.set(true);
                return commitResult;
            });
        }

        @Override
        public CompletableFuture<ConsistencyMarker> afterCommit(R commitResult) {
            return relay(() -> delegate.afterCommit(commitResult), reporter, marker -> {
                reporter.reportSuccess(new Outcome.Committed(marker));
                return marker;
            });
        }

        @Override
        public void rollback() {
            if (committed.get()) {
                delegate.rollback();
                return;
            }
            try {
                delegate.rollback();
            } catch (Throwable failure) {
                reporter.reportFailure(failure);
                throw failure;
            }
            reporter.reportSuccess(new Outcome.RolledBack());
        }
    }
}
