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

package org.axonframework.messaging.core.unitofwork;

import org.axonframework.common.FutureUtils;
import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.ApplicationContext;
import org.axonframework.messaging.core.Context;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * This class represents a Unit of Work that monitors the processing of a task.
 * <p/>
 * As an implementation of the {@link ProcessingLifecycle}, steps can be attached in several
 * {@link ProcessingContext.DefaultPhases phases} of the Unit of Work to ensure the task-to-process is taken care off
 * correctly. Furthermore, the Unit of Work implements resource management through the {@link ProcessingContext},
 * providing the possibility to carry along resources throughout the phases.
 * <p/>
 * It is strongly recommended to interface with the {@code ProcessingLifecycle} and/or {@code ProcessingContext} instead
 * of with the {@code UnitOfWork} directly.
 *
 * @author Allard Buijze
 * @author Gerard Klijs
 * @author Milan Savić
 * @author Mitchell Herrijgers
 * @author Sara Pellegrini
 * @author Steven van Beelen
 * @since 0.6
 */
public class UnitOfWork implements ProcessingLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(UnitOfWork.class);

    private final String identifier;
    private final UnitOfWorkProcessingContext context;

    /**
     * Constructs a {@code UnitOfWork} with the given parameters.
     *
     * @param identifier           The identifier of this Unit of Work.
     * @param workScheduler        The {@link Executor} for processing unit of work actions.
     * @param applicationContext   The {@link ApplicationContext} for component resolution.
     * @param lifecycleInterceptor The {@link ProcessingLifecycleInterceptor} invoked immediately around every action
     *                             and handler dispatch, or {@code null} for the direct, zero-overhead path.
     */
    @Internal
    UnitOfWork(
            String identifier,
            Executor workScheduler,
            boolean forceSyncProcessing,
            ApplicationContext applicationContext,
            @Nullable ProcessingLifecycleInterceptor lifecycleInterceptor
    ) {
        Objects.requireNonNull(identifier, "identifier may not be null.");
        Objects.requireNonNull(workScheduler, "workScheduler may not be null.");
        Objects.requireNonNull(applicationContext, "applicationContext may not be null.");
        this.identifier = identifier;
        this.context = new UnitOfWorkProcessingContext(
                identifier,
                workScheduler,
                forceSyncProcessing,
                applicationContext,
                lifecycleInterceptor
        );
    }

    @Override
    public boolean isStarted() {
        return context.isStarted();
    }

    @Override
    public boolean isError() {
        return context.isError();
    }

    @Override
    public boolean isCommitted() {
        return context.isCommitted();
    }

    @Override
    public boolean isCompleted() {
        return context.isCompleted();
    }

    @Override
    public UnitOfWork on(Phase phase, Function<ProcessingContext, CompletableFuture<?>> action) {
        context.on(phase, action);
        return this;
    }

    @Override
    public ProcessingLifecycle onError(ErrorHandler action) {
        return context.onError(action);
    }

    @Override
    public ProcessingLifecycle whenComplete(Consumer<ProcessingContext> action) {
        return context.whenComplete(action);
    }

    /**
     * Executes all the registered action in their respective
     * {@link ProcessingLifecycle.Phase phases}.
     *
     * @return A {@link CompletableFuture} that returns normally when this Unit Of Work has been committed or
     * exceptionally with the exception that caused the Unit of Work to fail.
     */
    public CompletableFuture<Void> execute() {
        return context.commit();
    }

    /**
     * Registers the given {@code action} for the {@link DefaultPhases#INVOCATION invocation Phase} and executes this
     * Unit of Work right away.
     * <p>
     * The return value of the given {@code action} is returned when this Unit of Work is committed, disregarding
     * intermittent results of actions registered in other
     * {@link ProcessingLifecycle.Phase phases}.
     *
     * @param action The {@link Function} that's given the active {@link ProcessingContext} and returns a
     *               {@link CompletableFuture} for chaining purposes and to carry the action's result.
     * @param <R>    The type of return value returned by the {@code action}.
     * @return A {@link CompletableFuture} that returns normally with the return value of the given {@code action} when
     * the Unit Of Work has been committed. Or, an exceptionally completed future with the exception that caused this
     * Unit of Work to fail.
     */
    public <R> CompletableFuture<R> executeWithResult(Function<ProcessingContext, CompletableFuture<R>> action) {
        CompletableFuture<R> result = new CompletableFuture<>();
        context.onInvocationWithResult(processingContext -> safe(() -> action.apply(processingContext)), result);
        return execute().thenCompose(ignored -> result);
    }

    /**
     * Wraps a given {@code action} so that any {@link Throwable} it throws is returned as a failed
     * {@link CompletableFuture} instead of propagating.
     * <p>
     * {@link Throwable} is caught (not only {@link Exception}) so that an {@link Error} also fails the future rather
     * than escaping and leaving the Unit of Work hung; such {@code Error}s are logged as they signal severe,
     * easily-missed problems.
     *
     * @param action A {@link Callable} to execute within the try-catch block.
     * @return A {@link CompletableFuture} wrapping both the successful and exceptional result of the given
     * {@code action}.
     */
    private <R> CompletableFuture<R> safe(Callable<CompletableFuture<R>> action) {
        try {
            CompletableFuture<R> result = action.call();
            if (result == null) {
                return CompletableFuture.failedFuture(new NullPointerException(
                        "The action returned a null CompletableFuture."));
            }
            return result;
        } catch (Throwable t) {
            if (t instanceof Error) {
                logger.error("An Error escaped a Unit of Work action and was captured as a failed result. "
                                     + "This typically indicates a severe problem such as a classpath or "
                                     + "dependency mismatch.", t);
            }
            return CompletableFuture.failedFuture(t);
        }
    }

    @Override
    public String toString() {
        return "UnitOfWork{" + "identifier='" + identifier + '\'' + "phase='" + context.currentPhase.get() + '\'' + '}';
    }

    private static class UnitOfWorkProcessingContext implements ProcessingContext {

        private final AtomicReference<Status> status = new AtomicReference<>(Status.NOT_STARTED);
        private final AtomicReference<Phase> currentPhase = new AtomicReference<>(null);

        private final ConcurrentNavigableMap<Phase, Queue<PhaseAction>> phaseActions =
                new ConcurrentSkipListMap<>(Comparator.comparingInt(Phase::order));
        private final Queue<Consumer<ProcessingContext>> completeHandlers = new ConcurrentLinkedQueue<>();
        private final Queue<ErrorHandler> errorHandlers = new ConcurrentLinkedQueue<>();
        private final AtomicReference<@Nullable CauseAndPhase> errorCause = new AtomicReference<>();

        private final String identifier;
        private final Executor workScheduler;
        private final ApplicationContext applicationContext;
        private final ConcurrentMap<ResourceKey<?>, Object> resources;
        private final boolean forceSyncProcessing;
        private final @Nullable ProcessingLifecycleInterceptor lifecycleInterceptor;

        private UnitOfWorkProcessingContext(
                String identifier,
                Executor workScheduler,
                boolean forceSyncProcessing,
                ApplicationContext applicationContext,
                @Nullable ProcessingLifecycleInterceptor lifecycleInterceptor
        ) {
            this.identifier = identifier;
            this.workScheduler = workScheduler;
            this.forceSyncProcessing = forceSyncProcessing;
            this.resources = new ConcurrentHashMap<>();
            this.applicationContext = applicationContext;
            this.lifecycleInterceptor = lifecycleInterceptor;
        }

        @Override
        public boolean isStarted() {
            return status.get() != Status.NOT_STARTED;
        }

        @Override
        public boolean isError() {
            return status.get() == Status.COMPLETED_ERROR;
        }

        @Override
        public boolean isCommitted() {
            return status.get() == Status.COMPLETED;
        }

        @Override
        public boolean isCompleted() {
            Status currentStatus = status.get();
            return currentStatus == Status.COMPLETED || currentStatus == Status.COMPLETED_ERROR;
        }

        @Override
        public ProcessingLifecycle on(Phase phase, Function<ProcessingContext, CompletableFuture<?>> action) {
            return on(phase, action, null);
        }

        /**
         * Registers the given {@code action} for the {@link DefaultPhases#INVOCATION invocation phase}, completing
         * {@code result} with its own post-{@link ProcessingLifecycleInterceptor interceptor} outcome once that
         * phase runs.
         *
         * @param action the action to register, given the active {@link ProcessingContext}
         * @param result the {@link CompletableFuture} to complete with the given {@code action}'s own
         *               post-interceptor outcome
         * @param <R>    the type of value returned by the given {@code action}
         */
        private <R> void onInvocationWithResult(Function<ProcessingContext, CompletableFuture<R>> action,
                                                CompletableFuture<R> result) {
            on(DefaultPhases.INVOCATION, action::apply, (value, error) -> {
                if (error == null) {
                    @SuppressWarnings("unchecked")
                    R typedValue = (R) value;
                    result.complete(typedValue);
                } else {
                    result.completeExceptionally(error);
                }
            });
        }

        /**
         * Registers the given {@code action} for the given {@code phase}, optionally tapping its own
         * post-{@link ProcessingLifecycleInterceptor interceptor} outcome through {@code resultSink}.
         * <p>
         * The aggregate pass/fail signal {@link #runNextPhase()} computes for a phase (via
         * {@link CompletableFuture#allOf(CompletableFuture[])} across every handler registered in that phase)
         * discards individual per-handler values. {@code resultSink}, when non-null, is invoked with this
         * specific handler's own (possibly interceptor-transformed) value or exception, independent of that
         * aggregate signal. Used exclusively by {@link UnitOfWork#executeWithResult(Function)}; every other
         * caller goes through the public {@link #on(Phase, Function)}, which always passes {@code null}.
         *
         * @param phase      the {@link Phase} to register the given {@code action} in
         * @param action     the action to register, given the active {@link ProcessingContext}
         * @param resultSink the sink to notify with this handler's own post-interceptor (value, error) pair, or
         *                   {@code null} when the aggregate phase signal is the only outcome of interest
         * @return this {@link ProcessingLifecycle} for a fluent API
         */
        private ProcessingLifecycle on(Phase phase,
                                       Function<ProcessingContext, CompletableFuture<?>> action,
                                       @Nullable BiConsumer<Object, Throwable> resultSink) {
            var current = currentPhase.get();
            if (current != null && phase.order() <= current.order()) {
                throw new IllegalStateException(
                        "Failed to register handler in phase " + phase + " (" + phase.order() + "). "
                                + "ProcessingContext is already in phase " + current + " (" + current.order() + ")."
                );
            }
            phaseActions.computeIfAbsent(phase, p -> new ConcurrentLinkedQueue<>())
                        .add(new PhaseAction(safe(phase, action), resultSink));
            return this;
        }

        /**
         * Wraps a given {@code action}, that is to be executed in the given {@code phase}, in a try-catch block to
         * ensure exceptions are exclusively returned as a failed {@link CompletableFuture}.
         *
         * @param phase  The original phase instance the handler is registered under
         * @param action The {@link Function} to perform safely. It's given the active {@link ProcessingContext} and
         *               returns a {@link CompletableFuture} for chaining purposes and to carry the action's result.
         * @return A {@link CompletableFuture} wrapping both the successful and exceptional result of the given
         * {@code action}.
         */
        private Function<ProcessingContext, CompletableFuture<?>> safe(
                Phase phase, Function<ProcessingContext, CompletableFuture<?>> action
        ) {
            return processingContext -> {
                CompletableFuture<?> result;
                try {
                    result = action.apply(processingContext);
                } catch (Exception e) {
                    result = CompletableFuture.failedFuture(e);
                }

                return result.exceptionallyCompose(e -> {
                    if (!errorCause.compareAndSet(null, new CauseAndPhase(phase, e))) {
                        errorCause.get().cause().addSuppressed(e);
                    }
                    return CompletableFuture.failedFuture(e);
                });
            };
        }

        @Override
        public ProcessingLifecycle onError(ErrorHandler action) {
            ErrorHandler silentAction = failSilently(action);
            this.errorHandlers.add(silentAction);
            var currentStatus = status.get();

            if (currentStatus == Status.COMPLETED_ERROR && errorHandlers.remove(silentAction)) {
                // When in the COMPLETED_ERROR status, execute immediately.
                // The removal attempt is to make sure that we aren't concurrently executing from the registering thread
                // as well as the thread that completed the processing context.
                CauseAndPhase causeAndPhase = errorCause.get();
                silentAction.handle(this, causeAndPhase.phase(), causeAndPhase.cause());
            }
            return this;
        }

        private ErrorHandler failSilently(ErrorHandler action) {
            return (context, phase, exception) -> {
                try {
                    action.handle(context, phase, exception);
                } catch (Exception e) {
                    logger.warn("An onError handler threw an exception.", e);
                }
            };
        }

        @Override
        public ProcessingLifecycle whenComplete(Consumer<ProcessingContext> action) {
            Consumer<ProcessingContext> silentAction = completeSilently(action);
            this.completeHandlers.add(silentAction);
            var currentStatus = status.get();

            if (currentStatus == Status.COMPLETED && completeHandlers.remove(silentAction)) {
                // When in the COMPLETED status, execute immediately.
                // The removal attempt is to make sure that we aren't concurrently executing from the registering thread
                // as well as the thread that completed the processing context.
                silentAction.accept(this);
            }
            return this;
        }

        private Consumer<ProcessingContext> completeSilently(Consumer<ProcessingContext> action) {
            return processingContext -> {
                try {
                    action.accept(processingContext);
                } catch (Exception e) {
                    logger.warn("A Completion handler threw an exception.", e);
                }
            };
        }

        private CompletableFuture<Void> commit() {
            if (!status.compareAndSet(Status.NOT_STARTED, Status.STARTED)) {
                throw new IllegalStateException(
                        "Cannot switch [" + status.get() + "] to STARTED. "
                                + "This ProcessingContext cannot be committed (again)."
                );
            }

            if (forceSyncProcessing) {
                try {
                    executeAllPhaseHandlers().join();
                    runCompletionHandlers();
                    return FutureUtils.emptyCompletedFuture();
                } catch (CompletionException e) {
                    return runErrorHandlers(e.getCause());
                } catch (Exception e) {
                    return runErrorHandlers(e);
                }
            }

            return executeAllPhaseHandlers()
                    .thenRunAsync(this::runCompletionHandlers, workScheduler)
                    .exceptionallyComposeAsync(this::runErrorHandlers, workScheduler);
        }

        private CompletableFuture<Void> executeAllPhaseHandlers() {
            if (phaseActions.isEmpty()) {
                // We're done.
                return FutureUtils.emptyCompletedFuture();
            }

            CompletableFuture<Void> nextPhaseResult = runNextPhase();
            // Avoid stack overflow due to recursion when executed in single thread.
            while (!phaseActions.isEmpty() && nextPhaseResult.isDone()) {
                if (nextPhaseResult.isCompletedExceptionally()) {
                    return nextPhaseResult;
                } else {
                    nextPhaseResult = runNextPhase();
                }
            }
            return nextPhaseResult.thenCompose(result -> executeAllPhaseHandlers());
        }

        private void runCompletionHandlers() {
            status.set(Status.COMPLETED);

            while (!completeHandlers.isEmpty()) {
                Consumer<ProcessingContext> nextCompletionHandler = completeHandlers.poll();
                if (nextCompletionHandler != null) {
                    workScheduler.execute(() -> interceptCompletion(
                            () -> nextCompletionHandler.accept(this)
                    ));
                }
            }
        }

        private CompletableFuture<Void> runErrorHandlers(Throwable e) {
            status.set(Status.COMPLETED_ERROR);
            errorCause.compareAndSet(null, new CauseAndPhase(currentPhase.get(), e)); // fallback in case the error did not come from a phase handler
            CauseAndPhase recordedCause = errorCause.get();

            while (!errorHandlers.isEmpty()) {
                ErrorHandler nextErrorHandler = errorHandlers.poll();
                if (nextErrorHandler != null) {
                    workScheduler.execute(
                            () -> interceptError(
                                    recordedCause.phase(),
                                    recordedCause.cause(),
                                    () -> nextErrorHandler.handle(this, recordedCause.phase(), recordedCause.cause())
                            )
                    );
                }
            }
            return CompletableFuture.failedFuture(e);
        }

        private CompletableFuture<Void> runNextPhase() {
            if (phaseActions.isEmpty()) {
                return FutureUtils.emptyCompletedFuture();
            }
            Phase current = phaseActions.firstKey();
            currentPhase.set(current);

            Queue<PhaseAction> actionQueue = phaseActions.remove(current);
            if (actionQueue == null || actionQueue.isEmpty()) {
                logger.debug("Skipping phase {} (with order [{}]), since no actions are registered.",
                             current, current.order());
                return FutureUtils.emptyCompletedFuture();
            }
            logger.debug("Calling {}# actions in phase {} (with order {}).",
                         actionQueue.size(), current, current.order());

            CompletableFuture<Void> phaseResult =
                    actionQueue.stream()
                               .map(phaseAction -> FutureUtils.emptyCompletedFuture()
                                                              .thenComposeAsync(
                                                                      ignored -> intercept(
                                                                              current, phaseAction.handler()
                                                                      ),
                                                                      workScheduler
                                                              )
                                                              .whenComplete((value, error) -> {
                                                                  if (phaseAction.resultSink() != null) {
                                                                      phaseAction.resultSink()
                                                                                 .accept(value, error);
                                                                  }
                                                              })
                                                              .thenAccept(FutureUtils::ignoreResult))
                               .reduce(CompletableFuture::allOf)
                               .orElseGet(FutureUtils::emptyCompletedFuture);
            if (forceSyncProcessing) {
                try {
                    phaseResult.join();
                    return FutureUtils.emptyCompletedFuture();
                } catch (CompletionException e) {
                    return CompletableFuture.failedFuture(e.getCause());
                }
            }
            return phaseResult;
        }

        /**
         * Runs the given phase {@code handler} through the {@link ProcessingLifecycleInterceptor}, or directly
         * when no interceptor is installed, keeping the original zero-overhead execution path.
         */
        private CompletableFuture<?> intercept(Phase phase, Function<ProcessingContext, CompletableFuture<?>> handler) {
            return lifecycleInterceptor != null
                    ? lifecycleInterceptor.interceptPhase(this, phase, () -> handler.apply(this))
                    : handler.apply(this);
        }

        /**
         * Runs the given completion-handler {@code action} through the {@link ProcessingLifecycleInterceptor}, or
         * directly when no interceptor is installed.
         * <p>
         * A misbehaving interceptor that throws is logged and swallowed here: this dispatch runs on a bare
         * {@code workScheduler.execute(...)} worker thread, so an escaping exception would otherwise vanish silently
         * and block every completion handler still queued behind it. A failing interceptor must not turn an otherwise
         * successful completion into a failure.
         */
        private void interceptCompletion(Runnable action) {
            try {
                if (lifecycleInterceptor != null) {
                    lifecycleInterceptor.interceptCompletion(this, action);
                } else {
                    action.run();
                }
            } catch (Exception e) {
                logger.warn("A ProcessingLifecycleInterceptor threw an exception while intercepting a completion "
                                    + "handler dispatch. The exception is ignored so remaining handlers keep running.",
                            e);
            }
        }

        /**
         * Runs the given error-handler {@code action} through the {@link ProcessingLifecycleInterceptor}, or
         * directly when no interceptor is installed.
         * <p>
         * A misbehaving interceptor that throws is logged and swallowed here, for the same reason as in
         * {@link #interceptCompletion(Runnable)}: this dispatch runs on a bare {@code workScheduler.execute(...)}
         * worker thread, so an escaping exception would otherwise vanish silently and block every error handler still
         * queued behind it.
         */
        private void interceptError(@Nullable Phase failedPhase, Throwable cause, Runnable action) {
            try {
                if (lifecycleInterceptor != null) {
                    lifecycleInterceptor.interceptError(this, failedPhase, cause, action);
                } else {
                    action.run();
                }
            } catch (Exception e) {
                logger.warn("A ProcessingLifecycleInterceptor threw an exception while intercepting an error "
                                    + "handler dispatch. The exception is ignored so remaining handlers keep running.",
                            e);
            }
        }

        @Override
        public boolean containsResource(Context.ResourceKey<?> key) {
            return resources.containsKey(key);
        }

        @Override
        public <T> T getResource(ResourceKey<T> key) {
            //noinspection unchecked
            return (T) resources.get(key);
        }

        @Override
        public Map<ResourceKey<?>, Object> resources() {
            return Map.copyOf(resources);
        }

        @Override
        public <T> T putResource(ResourceKey<T> key,
                                 T resource) {
            //noinspection unchecked
            return (T) resources.put(key, resource);
        }

        @Override
        public <T> T updateResource(ResourceKey<T> key,
                                    UnaryOperator<T> resourceUpdater) {
            //noinspection unchecked
            return (T) resources.compute(key, (k, v) -> resourceUpdater.apply((T) v));
        }

        @Override
        public <T> T putResourceIfAbsent(ResourceKey<T> key,
                                         T resource) {
            //noinspection unchecked
            return (T) resources.putIfAbsent(key, resource);
        }

        @Override
        public <T> T computeResourceIfAbsent(ResourceKey<T> key,
                                             Supplier<T> resourceSupplier) {
            //noinspection unchecked
            return (T) resources.computeIfAbsent(key, t -> resourceSupplier.get());
        }

        @Override
        public <T> T removeResource(ResourceKey<T> key) {
            //noinspection unchecked
            return (T) resources.remove(key);
        }

        @Override
        public <T> boolean removeResource(ResourceKey<T> key,
                                          T expectedResource) {
            return resources.remove(key, expectedResource);
        }

        @Override
        public <C> C component(Class<C> type, @Nullable String name) {
            return applicationContext.component(type, name);
        }

        @Override
        public <C> C component(Class<C> type) {
            return applicationContext.component(type);
        }

        @Override
        public String toString() {
            return "UnitOfWorkProcessingContext{"
                    + "identifier='" + identifier + '\'' + ", currentPhase=" + currentPhase.get()
                    + '}';
        }

        private enum Status {
            NOT_STARTED, STARTED, COMPLETED_ERROR, COMPLETED
        }

        /**
         * Tuple combining the given {@code phase} and {@code cause} to be used during the invocation of registered
         * {@link ProcessingLifecycle.ErrorHandler ErrorHandlers}.
         *
         * @param phase The {@link ProcessingLifecycle.Phase} in which the given
         *              {@code cause} was thrown.
         * @param cause The {@link Throwable} thrown in an action executed in the given {@code phase}.
         */
        private record CauseAndPhase(Phase phase, Throwable cause) {

        }

        /**
         * Pairing of a phase {@code handler} with an optional {@code resultSink} that receives that specific
         * handler's own post-{@link ProcessingLifecycleInterceptor interceptor} outcome (value or exception),
         * independent of the aggregate pass/fail signal {@link #runNextPhase()} computes across every handler
         * registered in the same phase via {@link CompletableFuture#allOf(CompletableFuture[])}.
         * <p>
         * {@code resultSink} is {@code null} for every handler registered through the public
         * {@link #on(Phase, Function)} contract. Only {@link #onInvocationWithResult(Function, CompletableFuture)},
         * used exclusively by {@link UnitOfWork#executeWithResult(Function)}, installs one.
         *
         * @param handler    the action registered for a given {@link Phase}
         * @param resultSink the sink to notify with this handler's own post-interceptor (value, error) pair, or
         *                   {@code null} when the aggregate phase signal is the only outcome of interest
         */
        private record PhaseAction(Function<ProcessingContext, CompletableFuture<?>> handler,
                                   @Nullable BiConsumer<Object, Throwable> resultSink) {

        }
    }
}
