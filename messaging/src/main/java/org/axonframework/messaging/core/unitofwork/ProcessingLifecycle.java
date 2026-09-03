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

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Interface dedicate to managing the lifecycle of any process.
 * <p/>
 * Throughout the lifecycle of any process, actions need to be taken in different phases. Numerous
 * {@link DefaultPhases default phases} are defined that are used throughout the framework for these actions. The
 * {@link ProcessingLifecycle} has shorthand operations corresponding to these {@code DefaultPhases}. For example, the
 * {@link #onPreInvocation(Function)} uses the {@link DefaultPhases#PRE_INVOCATION} phase.
 * <p/>
 * When the {@link ProcessingLifecycle#isStarted() ProcessingLifecycle is started}, the phases are invoked beginning
 * with the lowest {@link Phase#order()}, moving up until all phase actions are executed. Note that actions registered
 * in a {@code Phase} with the same order may be executed in parallel.
 * <p/>
 * If necessary, a custom {@link Phase} can be defined that can be invoked before <b>or</b> after any other
 * {@code Phase}. To ensure it is invoked before/after one of the {@code DefaultPhases}, set the {@link Phase#order()}
 * to a value higher/lower than the {@code DefaultPhase} you want the step to occur before or after.
 *
 * @author Allard Buijze
 * @author Gerard Klijs
 * @author Milan Savić
 * @author Mitchell Herrijgers
 * @author Sara Pellegrini
 * @author Steven van Beelen
 * @since 5.0.0
 */
public interface ProcessingLifecycle {

    /**
     * Returns {@code true} when this {@link ProcessingLifecycle} is started, {@code false} otherwise.
     *
     * @return {@code true} when this {@link ProcessingLifecycle} is started, {@code false} otherwise.
     */
    boolean isStarted();

    /**
     * Returns {@code true} when this {@link ProcessingLifecycle} is in error, {@code false} otherwise.
     * <p>
     * When {@code true}, the {@link #onError(ErrorHandler) registered ErrorHandlers} will be invoked.
     *
     * @return {@code true} when this {@link ProcessingLifecycle} is in error, {@code false} otherwise.
     */
    boolean isError();

    /**
     * Returns {@code true} when this {@link ProcessingLifecycle} is committed, {@code false} otherwise.
     *
     * @return {@code true} when this {@link ProcessingLifecycle} is committed, {@code false} otherwise.
     */
    boolean isCommitted();

    /**
     * Returns {@code true} when this {@link ProcessingLifecycle} is completed, {@code false} otherwise.
     * <p>
     * Note that this {@code ProcessingLifecycle} is marked as completed for a successful and failed completion.
     *
     * @return {@code true} when this {@link ProcessingLifecycle} is completed, {@code false} otherwise.
     */
    boolean isCompleted();

    /**
     * Registers the provided {@code action} to be executed in the given {@code phase}. Uses the
     * {@link CompletableFuture} returned by the {@code action} to compose actions and to carry the action's result.
     * <p>
     * Use this operation when the return value of the {@code action} is important.
     * <p>
     * An action may register further actions while it runs, but only for a {@code Phase} with a higher
     * {@link Phase#order() order} than the one it is running in. Registering for the {@code Phase} currently running,
     * or for one already passed, is rejected.
     * <p>
     * A component that is itself invoked from within a late {@code Phase} therefore cannot register for that same
     * {@code Phase}. It can register for a custom {@code Phase} ordered in the gap above it: {@code Phase} is an
     * interface over an arbitrary order and the {@link DefaultPhases} leave {@code 10000} between them, so such an
     * action still runs once the current {@code Phase} completed and before the next default one begins.
     *
     * @param phase  The {@link Phase} to execute the given {@code action} in.
     * @param action The {@link Function} that's given the active {@link ProcessingContext} and returns a
     *               {@link CompletableFuture} for chaining purposes and to carry the action's result.
     * @return This {@link ProcessingLifecycle} instance for fluent interfacing.
     * @throws IllegalStateException when the given {@code phase} has an {@link Phase#order() order} at or below that
     *                               of the {@code Phase} this {@link ProcessingLifecycle} is currently in
     */
    ProcessingLifecycle on(Phase phase, Function<ProcessingContext, CompletableFuture<?>> action);

    /**
     * Registers the provided {@code action} to be executed in the given {@code phase}.
     * <p>
     * Use this operation when there is no need for a return value of the registered {@code action}.
     *
     * @param phase  The {@link Phase} to execute the given {@code action} in.
     * @param action A {@link Consumer} that's given the active {@link ProcessingContext} to perform its action.
     * @return This {@link ProcessingLifecycle} instance for fluent interfacing.
     */
    default ProcessingLifecycle runOn(Phase phase, Consumer<ProcessingContext> action) {
        return on(phase, c -> {
            action.accept(c);
            return FutureUtils.emptyCompletedFuture();
        });
    }

    /**
     * Registers the provided {@code action} to be executed in the
     * {@link DefaultPhases#PRE_INVOCATION pre-invocation phase}.
     * <p>
     * Use this operation when the return value of the {@code action} is important.
     *
     * @param action The {@link Function} that's given the active {@link ProcessingContext} and returns a
     *               {@link CompletableFuture} for chaining purposes and to carry the action's result.
     * @return This {@link ProcessingLifecycle} instance for fluent interfacing.
     */
    default ProcessingLifecycle onPreInvocation(Function<ProcessingContext, CompletableFuture<?>> action) {
        return on(DefaultPhases.PRE_INVOCATION, action);
    }

    /**
     * Registers the provided {@code action} to be executed in the
     * {@link DefaultPhases#PRE_INVOCATION pre-invocation phase}.
     * <p>
     * Use this operation when there is no need for a return value of the registered {@code action}.
     *
     * @param action A {@link Consumer} that's given the active {@link ProcessingContext} to perform its action.
     * @return This {@link ProcessingLifecycle} instance for fluent interfacing.
     */
    default ProcessingLifecycle runOnPreInvocation(Consumer<ProcessingContext> action) {
        return runOn(DefaultPhases.PRE_INVOCATION, action);
    }

    /**
     * Registers the provided {@code action} to be executed in the {@link DefaultPhases#INVOCATION invocation phase}.
     * <p>
     * Use this operation when the return value of the {@code action} is important.
     *
     * @param action The {@link Function} that's given the active {@link ProcessingContext} and returns a
     *               {@link CompletableFuture} for chaining purposes and to carry the action's result.
     * @return This {@link ProcessingLifecycle} instance for fluent interfacing.
     */
    default ProcessingLifecycle onInvocation(Function<ProcessingContext, CompletableFuture<?>> action) {
        return on(DefaultPhases.INVOCATION, action);
    }

    /**
     * Registers the provided {@code action} to be executed in the {@link DefaultPhases#INVOCATION invocation phase}.
     * <p>
     * Use this operation when there is no need for a return value of the registered {@code action}.
     *
     * @param action A {@link Consumer} that's given the active {@link ProcessingContext} to perform its action.
     * @return This {@link ProcessingLifecycle} instance for fluent interfacing.
     */
    default ProcessingLifecycle runOnInvocation(Consumer<ProcessingContext> action) {
        return runOn(DefaultPhases.INVOCATION, action);
    }

    /**
     * Registers the provided {@code action} to be executed in the
     * {@link DefaultPhases#POST_INVOCATION post invocation phase}.
     * <p>
     * Use this operation when the return value of the {@code action} is important.
     *
     * @param action The {@link Function} that's given the active {@link ProcessingContext} and returns a
     *               {@link CompletableFuture} for chaining purposes and to carry the action's result.
     * @return This {@link ProcessingLifecycle} instance for fluent interfacing.
     */
    default ProcessingLifecycle onPostInvocation(Function<ProcessingContext, CompletableFuture<?>> action) {
        return on(DefaultPhases.POST_INVOCATION, action);
    }

    /**
     * Registers the provided {@code action} to be executed in the
     * {@link DefaultPhases#POST_INVOCATION post invocation phase}.
     * <p>
     * Use this operation when there is no need for a return value of the registered {@code action}.
     *
     * @param action A {@link Consumer} that's given the active {@link ProcessingContext} to perform its action.
     * @return This {@link ProcessingLifecycle} instance for fluent interfacing.
     */
    default ProcessingLifecycle runOnPostInvocation(Consumer<ProcessingContext> action) {
        return runOn(DefaultPhases.POST_INVOCATION, action);
    }

    /**
     * Registers the provided {@code action} to be executed in the
     * {@link DefaultPhases#PREPARE_COMMIT prepare commit phase}.
     * <p>
     * Use this operation when the return value of the {@code action} is important.
     *
     * @param action The {@link Function} that's given the active {@link ProcessingContext} and returns a
     *               {@link CompletableFuture} for chaining purposes and to carry the action's result.
     * @return This {@link ProcessingLifecycle} instance for fluent interfacing.
     */
    default ProcessingLifecycle onPrepareCommit(Function<ProcessingContext, CompletableFuture<?>> action) {
        return on(DefaultPhases.PREPARE_COMMIT, action);
    }

    /**
     * Registers the provided {@code action} to be executed in the
     * {@link DefaultPhases#PREPARE_COMMIT prepare commit phase}.
     * <p>
     * Use this operation when there is no need for a return value of the registered {@code action}.
     *
     * @param action A {@link Consumer} that's given the active {@link ProcessingContext} to perform its action.
     * @return This {@link ProcessingLifecycle} instance for fluent interfacing.
     */
    default ProcessingLifecycle runOnPrepareCommit(Consumer<ProcessingContext> action) {
        return runOn(DefaultPhases.PREPARE_COMMIT, action);
    }

    /**
     * Registers the provided {@code action} to be executed in the {@link DefaultPhases#COMMIT commit phase}.
     * <p>
     * Use this operation when the return value of the {@code action} is important.
     *
     * @param action The {@link Function} that's given the active {@link ProcessingContext} and returns a
     *               {@link CompletableFuture} for chaining purposes and to carry the action's result.
     * @return This {@link ProcessingLifecycle} instance for fluent interfacing.
     */
    default ProcessingLifecycle onCommit(Function<ProcessingContext, CompletableFuture<?>> action) {
        return on(DefaultPhases.COMMIT, action);
    }

    /**
     * Registers the provided {@code action} to be executed in the {@link DefaultPhases#COMMIT commit phase}.
     * <p>
     * Use this operation when there is no need for a return value of the registered {@code action}.
     *
     * @param action A {@link Consumer} that's given the active {@link ProcessingContext} to perform its action.
     * @return This {@link ProcessingLifecycle} instance for fluent interfacing.
     */
    default ProcessingLifecycle runOnCommit(Consumer<ProcessingContext> action) {
        return runOn(DefaultPhases.COMMIT, action);
    }

    /**
     * Registers the provided {@code action} to be executed in the
     * {@link DefaultPhases#AFTER_COMMIT after commit phase}.
     * <p>
     * Use this operation when the return value of the {@code action} is important.
     *
     * @param action The {@link Function} that's given the active {@link ProcessingContext} and returns a
     *               {@link CompletableFuture} for chaining purposes and to carry the action's result.
     * @return This {@link ProcessingLifecycle} instance for fluent interfacing.
     */
    default ProcessingLifecycle onAfterCommit(Function<ProcessingContext, CompletableFuture<?>> action) {
        return on(DefaultPhases.AFTER_COMMIT, action);
    }

    /**
     * Registers the provided {@code action} to be executed in the
     * {@link DefaultPhases#AFTER_COMMIT after commit phase}.
     * <p>
     * Use this operation when there is no need for a return value of the registered {@code action}.
     *
     * @param action A {@link Consumer} that's given the active {@link ProcessingContext} to perform its action.
     * @return This {@link ProcessingLifecycle} instance for fluent interfacing.
     */
    default ProcessingLifecycle runOnAfterCommit(Consumer<ProcessingContext> action) {
        return runOn(DefaultPhases.AFTER_COMMIT, action);
    }

    /**
     * Registers the provided {@code action} to be executed when this {@link ProcessingLifecycle} encounters an error
     * during the action of any {@link Phase}. This includes failures from actions registered through
     * {@link #whenComplete(Consumer)}.
     * <p>
     * When the given {@link ErrorHandler ErrorHandlers} are invoked {@link #isError()} and {@link #isCompleted()} will
     * return {@code true}.
     *
     * @param action The error handler to execute when this {@link ProcessingLifecycle} encounters an error during phase
     *               execution.
     * @return This {@link ProcessingLifecycle} instance for fluent interfacing.
     */
    ProcessingLifecycle onError(ErrorHandler action);

    /**
     * Registers the provided {@code action} to be executed when this {@link ProcessingLifecycle} completes <b>all</b>
     * registered actions.
     *
     * @param action A {@link Consumer} that's given the active {@link ProcessingContext} to perform its action.
     * @return This {@link ProcessingLifecycle} instance for fluent interfacing.
     */
    ProcessingLifecycle whenComplete(Consumer<ProcessingContext> action);

    /**
     * Registers the provided {@code action} to be executed {@link #onError(ErrorHandler) on error} of <b>and</b>
     * {@link #whenComplete(Consumer) when completing} this {@link ProcessingLifecycle}.
     * <p>
     * Note that if the given {@code action} throws an exception when completing this {@code ProcessingLifecycle} it
     * will be invoked <em>again</em> as an on {@link ErrorHandler}.
     *
     * @param action A {@link Consumer} that's given the active {@link ProcessingContext} to perform its action.
     * @return This {@link ProcessingLifecycle} instance for fluent interfacing.
     */
    default ProcessingLifecycle doFinally(Consumer<ProcessingContext> action) {
        onError((c, p, e) -> action.accept(c));
        whenComplete(action);
        return this;
    }

    /**
     * Functional interface describing an operation that's invoked when an error is detected within the
     * {@link ProcessingLifecycle}.
     *
     * @author Allard Buijze
     * @author Gerard Klijs
     * @author Milan Savić
     * @author Mitchell Herrijgers
     * @author Sara Pellegrini
     * @author Steven van Beelen
     * @since 5.0.0
     */
    @FunctionalInterface
    interface ErrorHandler {

        /**
         * Invoked when an error is detected in a {@link ProcessingLifecycle} and it has been aborted.
         * <p>
         * In this state, the lifecycle will <b>always</b> return {@code true} for {@link ProcessingContext#isError()}
         * and {@link ProcessingContext#isCompleted()}.
         *
         * @param processingContext The context in which the error occurred.
         * @param phase             The phase used to register the handler which caused the {@link ProcessingLifecycle}
         *                          to fail.
         * @param error             The exception or error describing the cause.
         */
        void handle(ProcessingContext processingContext, Phase phase, Throwable error);
    }

    /**
     * Interface describing a possible phase for the {@link ProcessingLifecycle} to perform steps in.
     * <p>
     * Lifecycle actions are invoked in the {@link #order()} of their respective phase, where action in phases with the
     * same order may be invoked in parallel.
     *
     * @author Allard Buijze
     * @author Gerard Klijs
     * @author Milan Savić
     * @author Mitchell Herrijgers
     * @author Sara Pellegrini
     * @author Steven van Beelen
     * @since 5.0.0
     */
    interface Phase {

        /**
         * The order of this phase compared to other phases. Phases with the same order are considered "simultaneous"
         * and may have their handlers invoked in parallel.
         *
         * @return The {@code int} describing the relative order of this phase.
         */
        int order();

        /**
         * Checks if the {@link Phase#order()} of {@code this Phase} is <b>smaller</b> than the order of the
         * {@code other}. Returns {@code true} if this is the case and {@code false} otherwise.
         *
         * @param other The {@link Phase} to validate if its {@link Phase#order() order} is larger than the order of
         *              {@code this Phase}.
         * @return {@code true} if the {@link Phase#order()} of {@code this Phase} is <b>smaller</b> than the order of
         * the {@code other Phase}.
         */
        default boolean isBefore(Phase other) {
            return this.order() < other.order();
        }

        /**
         * Checks if the {@link Phase#order()} of {@code this Phase} is <b>larger</b> than the order of the
         * {@code other}. Returns {@code true} if this is the case and {@code false} otherwise.
         *
         * @param other The {@link Phase} to validate if its {@link Phase#order() order} is smaller than the order of
         *              {@code this Phase}.
         * @return {@code true} if the {@link Phase#order()} of {@code this Phase} is <b>larger</b> than the order of
         * the {@code other Phase}.
         */
        default boolean isAfter(Phase other) {
            return this.order() > other.order();
        }
    }

    /**
     * Default phases used for the shorthand methods in the {@link ProcessingLifecycle}.
     *
     * @author Allard Buijze
     * @author Gerard Klijs
     * @author Milan Savić
     * @author Mitchell Herrijgers
     * @author Sara Pellegrini
     * @author Steven van Beelen
     * @since 5.0.0
     */
    enum DefaultPhases implements Phase {

        /**
         * Phase used to contain actions that occur <b>before</b> the main invocation. Has an order of {@code -10000}.
         */
        PRE_INVOCATION(-10000),
        /**
         * Phase used to contain actions that occur <b>during</b> the main invocation. Has an order of {@code 0}.
         */
        INVOCATION(0),
        /**
         * Phase used to contain actions that occur <b>after</b> the main invocation. Has an order of {@code 10000}.
         */
        POST_INVOCATION(10000),
        /**
         * Phase used to contain actions to prepare the commit of the {@link ProcessingLifecycle}. Has an order of
         * {@code 20000}.
         */
        PREPARE_COMMIT(20000),
        /**
         * Phase used to contain actions to commit of the {@link ProcessingLifecycle}. Has an order of {@code 30000}.
         */
        COMMIT(30000),
        /**
         * Phase used to contain actions to execute after the commit of the {@link ProcessingLifecycle}. Has an order of
         * {@code 40000}.
         */
        AFTER_COMMIT(40000);

        private final int order;

        DefaultPhases(int order) {
            this.order = order;
        }

        @Override
        public int order() {
            return order;
        }
    }
}