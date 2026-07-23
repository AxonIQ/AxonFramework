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
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle.Phase;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Seam that is invoked <em>immediately around</em> every action executed by a {@link UnitOfWork}, on the very thread
 * that runs the action.
 * <p>
 * Every registered phase action (INVOCATION, PREPARE_COMMIT, COMMIT, AFTER_COMMIT, ...), as well as the completion- and
 * error-handler dispatch sites, passes through this interceptor. This makes it the single choke point for bridging
 * thread-bound state, such as distributed tracing context, MDC, or security context, into the segments that the
 * framework executes on its own {@link UnitOfWorkConfiguration#workScheduler() work scheduler} threads.
 * <p>
 * The three dispatch kinds are exposed as three separate, all-{@code abstract} methods,
 * {@link #interceptPhase(ProcessingContext, Phase, Supplier)},
 * {@link #interceptCompletion(ProcessingContext, Runnable)}, and
 * {@link #interceptError(ProcessingContext, Phase, Throwable, Runnable)}, rather than a single method discriminated by
 * a nullable flag. The compiler therefore forces every implementation to consider all three sites, removing the risk
 * of an implementation that silently covers phase actions while missing the completion- or error-handler dispatch.
 * Implementations that want to apply the same behavior uniformly to all three kinds (the common case for state-bridging
 * infrastructure such as a tracing binding) should use {@link #intercept(UniformInterceptor)} instead of implementing
 * this interface directly.
 * <p>
 * No interceptor is installed by default: {@link UnitOfWorkConfiguration#defaultValues()} leaves the interceptor
 * {@code null}, and the {@link UnitOfWork} then runs actions directly, adding no behavior and no overhead. An
 * interceptor is meant to be installed by infrastructure (for example a tracing binding) through
 * {@link UnitOfWorkConfiguration#addLifecycleInterceptor(ProcessingLifecycleInterceptor)}, which composes contributors
 * via {@link #andThen(ProcessingLifecycleInterceptor)} so multiple installers never clobber one another.
 * <p>
 * Implementations run on the action's thread and MUST restore any thread-bound state they mutate before returning,
 * regardless of the action's outcome (typically via try-with-resources).
 * <p>
 * <b>Evolution policy:</b> should a future minor release introduce another dispatch-kind method, its default
 * implementation MUST delegate to an existing method of this interface (safe-by-default), never pass through the action
 * unintercepted (skip-by-default). This preserves the guarantee that a wrap-everything implementor (any implementation
 * obtained through {@link #intercept(UniformInterceptor)}) keeps covering every dispatch site across releases.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
public interface ProcessingLifecycleInterceptor {

    /**
     * Invoked on the thread that executes the {@code action}, immediately around it. This is the seam for bridging
     * thread-bound state (tracing context, MDC, security) into phase actions registered in a lifecycle phase.
     *
     * @param context the {@link ProcessingContext} of the {@link UnitOfWork} executing the action
     * @param phase   the {@link ProcessingLifecycle.Phase} the action executes in
     * @param action  the action to execute, returning a {@link CompletableFuture} that completes when the action is done
     * @return the result of executing the {@code action}
     */
    CompletableFuture<?> interceptPhase(ProcessingContext context, Phase phase,
                                        Supplier<CompletableFuture<?>> action);

    /**
     * Invoked on the thread that executes the {@code action}, immediately around it. This is the seam for bridging
     * thread-bound state into a {@code whenComplete}-handler dispatch, running after the lifecycle completed
     * successfully.
     *
     * @param context the {@link ProcessingContext} of the {@link UnitOfWork} whose completion handler is dispatched
     * @param action  the completion-handler dispatch to execute
     */
    void interceptCompletion(ProcessingContext context, Runnable action);

    /**
     * Invoked on the thread that executes the {@code action}, immediately around it. This is the seam for bridging
     * thread-bound state into an {@code onError}-handler dispatch, running after the lifecycle failed.
     *
     * @param context     the {@link ProcessingContext} of the {@link UnitOfWork} whose error handler is dispatched
     * @param failedPhase the {@link ProcessingLifecycle.Phase} whose action failed, or {@code null} when the failure
     *                    preceded the first phase
     * @param cause       the failure that moved the lifecycle into its error state
     * @param action      the error-handler dispatch to execute
     */
    void interceptError(ProcessingContext context, @Nullable Phase failedPhase, Throwable cause,
                        Runnable action);

    /**
     * Creates a {@link ProcessingLifecycleInterceptor} that applies the given {@code interceptor} uniformly to all
     * three dispatch kinds: phase actions, completion-handler dispatch, and error-handler dispatch.
     * <p>
     * This is the shape most infrastructure contributors need: state-bridging behavior (restoring thread-locals,
     * activating a live span, ...) that does not depend on which kind of dispatch is being intercepted, expressed as a
     * single lambda that can never under-cover a dispatch site.
     *
     * @param interceptor the kind-agnostic interceptor applied to every dispatch site
     * @return a {@link ProcessingLifecycleInterceptor} delegating every dispatch kind to {@code interceptor}
     */
    static ProcessingLifecycleInterceptor intercept(UniformInterceptor interceptor) {
        Objects.requireNonNull(interceptor, "interceptor may not be null.");
        return new ProcessingLifecycleInterceptor() {

            @Override
            public CompletableFuture<?> interceptPhase(ProcessingContext context, Phase phase,
                                                       Supplier<CompletableFuture<?>> action) {
                return interceptor.intercept(context, action);
            }

            @Override
            public void interceptCompletion(ProcessingContext context, Runnable action) {
                interceptor.intercept(context, () -> {
                    action.run();
                    return FutureUtils.emptyCompletedFuture();
                });
            }

            @Override
            public void interceptError(ProcessingContext context, @Nullable Phase failedPhase, Throwable cause,
                                       Runnable action) {
                interceptor.intercept(context, () -> {
                    action.run();
                    return FutureUtils.emptyCompletedFuture();
                });
            }
        };
    }

    /**
     * Composes this interceptor with the {@code other} one, invoking {@code this} on the outside and {@code other} on
     * the inside (closest to the action), per dispatch kind. Composition ensures multiple contributors never clobber
     * each other.
     *
     * @param other the interceptor to invoke inside this one
     * @return a composed {@link ProcessingLifecycleInterceptor}
     */
    default ProcessingLifecycleInterceptor andThen(ProcessingLifecycleInterceptor other) {
        Objects.requireNonNull(other, "other may not be null.");
        ProcessingLifecycleInterceptor self = this;
        return new ProcessingLifecycleInterceptor() {

            @Override
            public CompletableFuture<?> interceptPhase(ProcessingContext context, Phase phase,
                                                       Supplier<CompletableFuture<?>> action) {
                return self.interceptPhase(context, phase, () -> other.interceptPhase(context, phase, action));
            }

            @Override
            public void interceptCompletion(ProcessingContext context, Runnable action) {
                self.interceptCompletion(context, () -> other.interceptCompletion(context, action));
            }

            @Override
            public void interceptError(ProcessingContext context, @Nullable Phase failedPhase, Throwable cause,
                                       Runnable action) {
                self.interceptError(context, failedPhase, cause,
                                    () -> other.interceptError(context, failedPhase, cause, action));
            }
        };
    }

    /**
     * Kind-agnostic interceptor applied uniformly to all dispatch sites by {@link #intercept(UniformInterceptor)}.
     */
    @FunctionalInterface
    interface UniformInterceptor {

        /**
         * Invoked on the thread that executes the {@code action}, immediately around it, regardless of which
         * dispatch kind (phase action, completion handler, or error handler) is being intercepted.
         *
         * @param context the {@link ProcessingContext} of the {@link UnitOfWork} executing the action
         * @param action  the action to execute, returning a {@link CompletableFuture} that completes when the action is done
         * @return the result of executing the {@code action}
         */
        CompletableFuture<?> intercept(ProcessingContext context, Supplier<CompletableFuture<?>> action);
    }
}
