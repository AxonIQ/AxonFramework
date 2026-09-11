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
package org.axonframework.messaging.core.timeout;

import org.axonframework.common.BuilderUtils;
import org.axonframework.common.FutureUtils;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle.DefaultPhases;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle.Phase;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycleInterceptor;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkConfiguration;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A {@link UnitOfWorkFactory} that decorates another {@code factory}, setting a timeout on every {@link UnitOfWork} it
 * creates.
 * <p>
 * If the timeout is reached, every thread currently processing the {@code UnitOfWork} is interrupted. A
 * {@code UnitOfWork} may run more than one phase action for the same phase concurrently, each on a different thread
 * (for example, dispatched through a configured {@code Executor}). The interceptor installed by this factory registers
 * and unregisters each such thread with the {@link AxonTimeLimitedTask} as its phase actions start and complete, so a
 * fired timeout reaches every thread genuinely still executing guarded work, not only the most recently started one.
 * <p>
 * The timeout starts counting from the moment the first phase action of the created {@code UnitOfWork} begins
 * executing, not from the moment {@link #create(String, Function)} itself is called, so time spent between creation and
 * the actual start of processing is not counted against it. Since every {@code UnitOfWork} is created exactly once, a
 * single {@link AxonTimeLimitedTask} is used per {@code UnitOfWork}, started lazily on whichever phase action runs
 * first for that instance, and completed in a dedicated phase ordered after every other phase, so it keeps guarding the
 * {@code UnitOfWork} for the full duration of its {@code AFTER_COMMIT} phase actions.
 * <p>
 * Detecting a fired timeout whose interruption was swallowed by a phase action (for example, an event handler using the
 * default {@code LoggingErrorHandler}) is handled automatically: this factory installs a
 * {@link ProcessingLifecycleInterceptor} on every {@code UnitOfWork} it creates, via
 * {@link UnitOfWorkConfiguration#addLifecycleInterceptor(ProcessingLifecycleInterceptor)}, wrapping every phase action
 * so a swallowed interruption is converted into an {@link AxonTimeoutException}. Since a failed phase halts all later
 * phases, this uniform wrapping cannot doubly convert the same fired timeout, and it covers every phase (not only the
 * invocation), including {@code PREPARE_COMMIT}, {@code COMMIT}, and {@code AFTER_COMMIT}. Callers no longer need to
 * check for a swallowed interruption themselves.
 *
 * @author Mitchell Herrijgers
 * @author Steven van Beelen
 * @see AxonTimeLimitedTask
 * @since 5.4.0
 */
public class TimeoutUnitOfWorkFactory implements UnitOfWorkFactory {

    /**
     * The phase in which the {@link AxonTimeLimitedTask} guarding a created {@code UnitOfWork} is completed, ordered
     * after {@link DefaultPhases#AFTER_COMMIT} to ensure it includes any after commit hooks added.
     */
    private static final Phase TIMEOUT_CLEANUP_PHASE = () -> DefaultPhases.AFTER_COMMIT.order() + 1;

    private final UnitOfWorkFactory delegate;
    private final String componentName;
    private final int timeout;
    private final int warningThreshold;
    private final int warningInterval;
    private final ScheduledExecutorService executorService;
    private final Logger logger;

    /**
     * Creates a new {@code TimeoutUnitOfWorkFactory} decorating the given {@code delegate}, for the given
     * {@code componentName} with the given {@code timeout}, {@code warningThreshold} and {@code warningInterval}. The
     * warnings and timeout will be scheduled on the {@link AxonTaskJanitor#INSTANCE}. If you want to use a different
     * {@link ScheduledExecutorService} or {@link Logger} to log on, use the other
     * {@link #TimeoutUnitOfWorkFactory(UnitOfWorkFactory, String, int, int, int, ScheduledExecutorService, Logger)}.
     *
     * @param delegate         the delegate {@link UnitOfWorkFactory} used to create the actual {@link UnitOfWork}
     * @param componentName    the name of the component to be included in the logging
     * @param timeout          the timeout in milliseconds
     * @param warningThreshold the threshold in milliseconds after which a warning is logged. Setting this to a value
     *                         higher than {@code timeout} will disable warnings
     * @param warningInterval  the interval in milliseconds between warnings
     */
    public TimeoutUnitOfWorkFactory(UnitOfWorkFactory delegate,
                                    String componentName,
                                    int timeout,
                                    int warningThreshold,
                                    int warningInterval) {
        this(delegate,
             componentName,
             timeout,
             warningThreshold,
             warningInterval,
             AxonTaskJanitor.INSTANCE,
             AxonTaskJanitor.LOGGER);
    }

    /**
     * Creates a new {@code TimeoutUnitOfWorkFactory} decorating the given {@code delegate}, for the given
     * {@code componentName} with the given {@code timeout}, {@code warningThreshold} and {@code warningInterval}. The
     * warnings and timeout will be scheduled on the provided {@code executorService}.
     *
     * @param delegate         the delegate {@link UnitOfWorkFactory} used to create the actual {@link UnitOfWork}
     * @param componentName    the name of the component to be included in the logging
     * @param timeout          the timeout in milliseconds
     * @param warningThreshold the threshold in milliseconds after which a warning is logged. Setting this to a value
     *                         higher than {@code timeout} will disable warnings
     * @param warningInterval  the interval in milliseconds between warnings
     * @param executorService  the executor service to schedule the timeout and warnings
     * @param logger           the logger to log warnings and errors
     */
    public TimeoutUnitOfWorkFactory(UnitOfWorkFactory delegate,
                                    String componentName,
                                    int timeout,
                                    int warningThreshold,
                                    int warningInterval,
                                    ScheduledExecutorService executorService,
                                    Logger logger) {
        BuilderUtils.assertNonEmpty(componentName, "The component name may not be empty or null.");
        this.delegate = Objects.requireNonNull(delegate, "The delegate UnitOfWorkFactory may not be null.");
        this.componentName = componentName;
        this.timeout = timeout;
        this.warningThreshold = warningThreshold;
        this.warningInterval = warningInterval;
        this.executorService = Objects.requireNonNull(executorService, "The executor service may not be null.");
        this.logger = Objects.requireNonNull(logger, "The logger may not be null.");
    }

    @Override
    public UnitOfWork create(String identifier,
                             Function<UnitOfWorkConfiguration, UnitOfWorkConfiguration> customization) {
        AxonTimeLimitedTask task = new AxonTimeLimitedTask(
                "UnitOfWork of " + componentName,
                timeout,
                warningThreshold,
                warningInterval,
                executorService,
                logger,
                TimeoutUnitOfWorkFactory.class
        );

        UnitOfWork unitOfWork = delegate.create(
                identifier,
                customization.andThen(config -> config.addLifecycleInterceptor(new TimeoutInterceptor(task)))
        );

        unitOfWork.on(TIMEOUT_CLEANUP_PHASE, context -> {
            task.complete();
            return FutureUtils.emptyCompletedFuture();
        });
        unitOfWork.onError((ctx, phase, error) -> task.complete());

        return unitOfWork;
    }

    /**
     * A {@link ProcessingLifecycleInterceptor} that wraps every phase action to be able to spot swallowed interruptions
     * to convert them into {@link AxonTimeoutException AxonTimeoutExceptions}.
     * <p>
     * {@link #interceptCompletion Completion-} and {@link #interceptError error-handler} are invoked as is as those
     * aren't spots where a caller's own business logic could swallow an interruption.
     */
    private static final class TimeoutInterceptor implements ProcessingLifecycleInterceptor {

        private final AxonTimeLimitedTask task;

        private TimeoutInterceptor(AxonTimeLimitedTask task) {
            this.task = task;
        }

        @Override
        public CompletableFuture<?> interceptPhase(ProcessingContext context,
                                                   Phase phase,
                                                   Supplier<CompletableFuture<?>> action) {
            Thread activeThread = Thread.currentThread();
            task.bindToCurrentThread();
            task.startIfNotStarted();
            return detectSwallowedInterruption(action.get(), activeThread);
        }

        @Override
        public void interceptCompletion(ProcessingContext context, Runnable action) {
            action.run();
        }

        @Override
        public void interceptError(ProcessingContext context,
                                   @Nullable Phase failedPhase,
                                   Throwable cause,
                                   Runnable action) {
            action.run();
        }

        /**
         * Returns a {@link CompletableFuture} that fails with an {@link AxonTimeoutException} when the given
         * {@code task} was {@link AxonTimeLimitedTask#isInterrupted() interrupted}, regardless of how the given
         * {@code result} itself completed.
         * <p>
         * This is used to detect a fired timeout whose interruption was swallowed by a phase action (for example, an
         * event handler using the default {@code LoggingErrorHandler}) and reported success despite the given
         * {@code task} having timed out.
         *
         * @param result       the {@link CompletableFuture} to check
         * @param activeThread the thread that was {@link AxonTimeLimitedTask#bindToCurrentThread() bound} to run the
         *                     phase action producing the given {@code result}, unregistered once it completes
         * @param <R>          the type of the result
         * @return a {@link CompletableFuture} that fails with an {@link AxonTimeoutException} when the given
         * {@code task} was interrupted, or otherwise completes exactly as the given {@code result} did
         */
        private <R> CompletableFuture<R> detectSwallowedInterruption(CompletableFuture<R> result, Thread activeThread) {
            CompletableFuture<R> converted = new CompletableFuture<>();
            result.whenComplete((value, error) -> {
                task.unbind(activeThread);
                if (task.isInterrupted()) {
                    // The interrupt already served its purpose of unblocking the thread; clear its transient status so
                    // it doesn't cause a spurious InterruptedException in unrelated code running on this same thread
                    // afterward (for example, this thread being returned to a pool and reused for other work).
                    //noinspection ResultOfMethodCallIgnored
                    Thread.interrupted();
                    converted.completeExceptionally(new AxonTimeoutException(task.getTaskName() + " has timed out."));
                } else if (error != null) {
                    converted.completeExceptionally(error);
                } else {
                    converted.complete(value);
                }
            });
            return converted;
        }
    }
}
