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

package org.axonframework.messaging.unitofwork;

import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle;
import org.axonframework.messaging.core.unitofwork.transaction.Transaction;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.ResultMessage;
import org.axonframework.messaging.core.correlation.CorrelationDataProvider;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * This class represents a Unit of Work that monitors the processing of a {@link Message}.
 * <p/>
 * Before processing begins a Unit of Work is bound to the active thread by registering it with the {@link
 * CurrentUnitOfWork}. After processing, the Unit of Work is deregistered from the {@link CurrentUnitOfWork}.
 * <p/>
 * Handlers can be notified about the state of the processing of the Message by registering with this Unit of Work.
 *
 * @author Allard Buijze
 * @since 0.6
 * @deprecated In favor of the {@link ProcessingLifecycle}.
 */
@Deprecated(since = "5.0.0", forRemoval = true)
public interface LegacyUnitOfWork<T extends Message> {

    /**
     * Starts the current unit of work. The UnitOfWork instance is registered with the CurrentUnitOfWork.
     */
    void start();

    /**
     * Commits the Unit of Work. This should be invoked after the Unit of Work Message has been processed. Handlers
     * registered to the Unit of Work will be notified.
     * <p/>
     * After the commit (successful or not), any registered clean-up handlers ({@link #onCleanup(Consumer)}}) will be
     * invoked and the Unit of Work is deregistered from the {@link CurrentUnitOfWork}.
     * <p/>
     * If the Unit of Work fails to commit, e.g. because an exception is raised by one of its handlers, the Unit of Work
     * is rolled back.
     *
     * @throws IllegalStateException if the UnitOfWork wasn't started or if the Unit of Work is not the 'current' Unit
     *                               of Work returned by {@link CurrentUnitOfWork#get()}.
     */
    void commit();

    /**
     * Initiates the rollback of this Unit of Work, invoking all registered rollback ({@link #onRollback(Consumer) and
     * clean-up handlers {@link #onCleanup(Consumer)}} respectively. Finally, the Unit of Work is deregistered from the
     * {@link CurrentUnitOfWork}.
     * <p/>
     * If the rollback is a result of an exception, consider using {@link #rollback(Throwable)} instead.
     *
     * @throws IllegalStateException if the Unit of Work is not in a compatible phase.
     */
    default void rollback() {
        rollback(null);
    }

    /**
     * Initiates the rollback of this Unit of Work, invoking all registered rollback ({@link #onRollback(Consumer) and
     * clean-up handlers {@link #onCleanup(Consumer)}} respectively. Finally, the Unit of Work is deregistered from the
     * {@link CurrentUnitOfWork}.
     *
     * @param cause The cause of the rollback. May be {@code null}.
     * @throws IllegalStateException if the Unit of Work is not in a compatible phase.
     */
    void rollback(Throwable cause);

    /**
     * Indicates whether this UnitOfWork is started. It is started when the {@link #start()} method has been called, and
     * if the UnitOfWork has not been committed or rolled back.
     *
     * @return {@code true} if this UnitOfWork is started, {@code false} otherwise.
     */
    default boolean isActive() {
        return phase().isStarted();
    }

    /**
     * Returns the current phase of the Unit of Work.
     *
     * @return the Unit of Work phase
     */
    Phase phase();

    /**
     * Register given {@code handler} with the Unit of Work. The handler will be notified when the phase of the
     * Unit of Work changes to {@link Phase#PREPARE_COMMIT}.
     *
     * @param handler the handler to register with the Unit of Work
     */
    void onPrepareCommit(Consumer<LegacyUnitOfWork<T>> handler);

    /**
     * Register given {@code handler} with the Unit of Work. The handler will be notified when the phase of the
     * Unit of Work changes to {@link Phase#COMMIT}.
     *
     * @param handler the handler to register with the Unit of Work
     */
    void onCommit(Consumer<LegacyUnitOfWork<T>> handler);

    /**
     * Register given {@code handler} with the Unit of Work. The handler will be notified when the phase of the
     * Unit of Work changes to {@link Phase#AFTER_COMMIT}.
     *
     * @param handler the handler to register with the Unit of Work
     */
    void afterCommit(Consumer<LegacyUnitOfWork<T>> handler);

    /**
     * Register given {@code handler} with the Unit of Work. The handler will be notified when the phase of the
     * Unit of Work changes to {@link Phase#ROLLBACK}. On rollback, the cause for the rollback can obtained from the
     * supplied
     *
     * @param handler the handler to register with the Unit of Work
     */
    void onRollback(Consumer<LegacyUnitOfWork<T>> handler);

    /**
     * Register given {@code handler} with the Unit of Work. The handler will be notified when the phase of the
     * Unit of Work changes to {@link Phase#CLEANUP}.
     *
     * @param handler the handler to register with the Unit of Work
     */
    void onCleanup(Consumer<LegacyUnitOfWork<T>> handler);

    /**
     * Returns an optional for the parent of this Unit of Work. The optional holds the Unit of Work that was active when
     * this Unit of Work was started. In case no other Unit of Work was active when this Unit of Work was started the
     * optional is empty, indicating that this is the Unit of Work root.
     *
     * @return an optional parent Unit of Work
     */
    Optional<LegacyUnitOfWork<?>> parent();

    /**
     * Check that returns {@code true} if this Unit of Work has not got a parent.
     *
     * @return {@code true} if this Unit of Work has no parent
     */
    default boolean isRoot() {
        return !parent().isPresent();
    }

    /**
     * Returns the root of this Unit of Work. If this Unit of Work has no parent (see {@link #parent()}) it returns
     * itself, otherwise it returns the root of its parent.
     *
     * @return the root of this Unit of Work
     */
    default LegacyUnitOfWork<?> root() {
        //noinspection unchecked // cast is used to remove inspection error in IDE
        return parent().map(LegacyUnitOfWork::root).orElse((LegacyUnitOfWork) this);
    }

    /**
     * Get the message that is being processed by the Unit of Work. A Unit of Work processes a single Message over its
     * life cycle.
     *
     * @return the Message being processed by this Unit of Work
     */
    T getMessage();

    /**
     * Transform the Message being processed using the given operator and stores the result.
     * <p>
     * Implementations should take caution not to change the message type to a type incompatible with the current Unit
     * of Work. For example, do not return a CommandMessage when transforming an EventMessage.
     *
     * @param transformOperator The transform operator to apply to the stored message
     * @return this Unit of Work
     */
    LegacyUnitOfWork<T> transformMessage(Function<T, ? extends Message> transformOperator);

    /**
     * Get the correlation data contained in the {@link #getMessage() message} being processed by the Unit of Work.
     * <p/>
     * By default this correlation data will be copied to other {@link Message messages} created in the context of this
     * Unit of Work, so long as these messages extend from {@link GenericMessage}.
     *
     * @return The correlation data contained in the message processed by this Unit of Work
     */
    Metadata getCorrelationData();

    /**
     * Register given {@code correlationDataProvider} with this Unit of Work. Correlation data providers are used
     * to provide meta data based on this Unit of Work's {@link #getMessage() Message} when {@link
     * #getCorrelationData()} is invoked.
     *
     * @param correlationDataProvider the Correlation Data Provider to register
     */
    void registerCorrelationDataProvider(CorrelationDataProvider correlationDataProvider);

    /**
     * Returns a mutable map of resources registered with the Unit of Work.
     *
     * @return mapping of resources registered with this Unit of Work
     */
    Map<String, Object> resources();

    /**
     * Returns the resource attached under given {@code name}, or {@code null} if no such resource is
     * available.
     *
     * @param name The name under which the resource was attached
     * @param <R>  The type of resource
     * @return The resource mapped to the given {@code name}, or {@code null} if no resource was found.
     */
    @SuppressWarnings("unchecked")
    default <R> R getResource(String name) {
        return (R) resources().get(name);
    }

    /**
     * Returns the resource attached under given {@code name}. If there is no resource mapped to the given key yet
     * the {@code mappingFunction} is invoked to provide the mapping.
     *
     * @param key             The name under which the resource was attached
     * @param mappingFunction The function that provides the mapping if there is no mapped resource yet
     * @param <R>             The type of resource
     * @return The resource mapped to the given {@code key}, or the resource returned by the
     * {@code mappingFunction} if no resource was found.
     */
    @SuppressWarnings("unchecked")
    default <R> R getOrComputeResource(String key, Function<? super String, R> mappingFunction) {
        return (R) resources().computeIfAbsent(key, mappingFunction);
    }

    /**
     * Returns the resource attached under given {@code name}. If there is no resource mapped to the given key,
     * the {@code defaultValue} is returned.
     *
     * @param key          The name under which the resource was attached
     * @param defaultValue The value to return if no mapping is available
     * @param <R>          The type of resource
     * @return The resource mapped to the given {@code key}, or the resource returned by the
     * {@code mappingFunction} if no resource was found.
     */
    @SuppressWarnings("unchecked")
    default <R> R getOrDefaultResource(String key, R defaultValue) {
        return (R) resources().getOrDefault(key, defaultValue);
    }

    /**
     * Attach a transaction to this Unit of Work, using the given {@code transactionManager}. The transaction will be
     * managed in the lifecycle of this Unit of Work. Failure to start a transaction will cause this Unit of Work
     * to be rolled back.
     *
     * @param transactionManager The Transaction Manager to create, commit and/or rollback the transaction
     */
    default void attachTransaction(TransactionManager transactionManager) {
        try {
            Transaction transaction = transactionManager.startTransaction();
            onCommit(u -> transaction.commit());
            onRollback(u -> transaction.rollback());
        } catch (Throwable t) {
            rollback(t);
            throw t;
        }
    }

    /**
     * Execute the given {@code task} in the context of this Unit of Work. If the Unit of Work is not started yet
     * it will be started.
     * <p/>
     * If the task executes successfully the Unit of Work is committed. If any exception is raised while executing the
     * task, the Unit of Work is rolled back and the exception is thrown.
     *
     * @param task the task to execute
     */
    default void execute(Consumer<ProcessingContext> task) {
        execute(task, RollbackConfigurationType.ANY_THROWABLE);
    }

    /**
     * Execute the given {@code task} in the context of this Unit of Work. If the Unit of Work is not started yet
     * it will be started.
     * <p/>
     * If the task executes successfully the Unit of Work is committed. If an exception is raised while executing the
     * task, the {@code rollbackConfiguration} determines if the Unit of Work should be rolled back or committed,
     * and the exception is thrown.
     *
     * @param task                  the task to execute
     * @param rollbackConfiguration configuration that determines whether or not to rollback the unit of work when task
     *                              execution fails
     */
    default void execute(Consumer<ProcessingContext> task, RollbackConfiguration rollbackConfiguration) {
        ResultMessage resultMessage = executeWithResult((ctx) -> {
            task.accept(ctx);
            return null;
        }, rollbackConfiguration);
        if (resultMessage.payload() instanceof Throwable e) {
            if (e instanceof RuntimeException exception) {
                throw exception;
            }
            throw new RuntimeException(e);
        }
    }

    /**
     * Execute the given {@code task} in the context of this Unit of Work. If the Unit of Work is not started yet
     * it will be started.
     * <p/>
     * If the task executes successfully the Unit of Work is committed and the result of the task is returned. If any
     * exception is raised while executing the task, the Unit of Work is rolled back and the exception is thrown.
     *
     * @param <R>  the type of result that is returned after successful execution
     * @param task the task to execute
     * @return The result of the task wrapped in Result Message
     */
    default <R> ResultMessage executeWithResult(ProcessingContextCallable<R> task) {
        return executeWithResult(task, RollbackConfigurationType.ANY_THROWABLE);
    }

    /**
     * Execute the given {@code task} in the context of this Unit of Work. If the Unit of Work is not started yet
     * it will be started.
     * <p/>
     * If the task executes successfully the Unit of Work is committed and the result of the task is returned. If
     * execution fails, the {@code rollbackConfiguration} determines if the Unit of Work should be rolled back or
     * committed.
     *
     * @param <R>                   the type of result that is returned after successful execution
     * @param task                  the task to execute
     * @param rollbackConfiguration configuration that determines whether or not to rollback the unit of work when task
     *                              execution fails
     * @return The result of the task wrapped in Result Message
     */
    <R> ResultMessage executeWithResult(ProcessingContextCallable<R> task,
                                           RollbackConfiguration rollbackConfiguration);

    /**
     * Get the result of the task that was executed by this Unit of Work. If the Unit of Work has not been given a task
     * to execute this method returns {@code null}.
     * <p>
     * Note that the value of the returned ExecutionResult's {@link ExecutionResult#isExceptionResult()} does not
     * determine whether or not the UnitOfWork has been rolled back. To check whether or not the UnitOfWork was rolled
     * back check {@link #isRolledBack}.
     *
     * @return The result of the task executed by this Unit of Work, or {@code null} if the Unit of Work has not
     * been given a task to execute.
     */
    ExecutionResult getExecutionResult();

    /**
     * Check if the Unit of Work has been rolled back.
     *
     * @return {@code true} if the unit of work was rolled back, {@code false} otherwise.
     */
    boolean isRolledBack();

    /**
     * Check if the Unit of Work is the 'currently' active Unit of Work returned by {@link CurrentUnitOfWork#get()}.
     *
     * @return {@code true} if the Unit of Work is the currently active Unit of Work
     */
    default boolean isCurrent() {
        return CurrentUnitOfWork.isStarted() && CurrentUnitOfWork.get() == this;
    }

    /**
     * Enum indicating possible phases of the Unit of Work.
     */
    enum Phase {

        /**
         * Indicates that the unit of work has been created but has not been registered with the {@link
         * CurrentUnitOfWork} yet.
         */
        NOT_STARTED(false, false, false),

        /**
         * Indicates that the Unit of Work has been registered with the {@link CurrentUnitOfWork} but has not been
         * committed, because its Message has not been processed yet.
         */
        STARTED(true, false, false),

        /**
         * Indicates that the Unit of Work is preparing its commit. This means that {@link #commit()} has been invoked
         * on the Unit of Work, indicating that the Message {@link #getMessage()} of the Unit of Work has been
         * processed.
         * <p/>
         * All handlers registered to be notified before commit {@link #onPrepareCommit} will be invoked. If no
         * exception is raised by any of the handlers the Unit of Work will go into the {@link #COMMIT} phase, otherwise
         * it will be rolled back.
         */
        PREPARE_COMMIT(true, false, false),

        /**
         * Indicates that the Unit of Work has been committed and is passed the {@link #PREPARE_COMMIT} phase.
         */
        COMMIT(true, true, false),

        /**
         * Indicates that the Unit of Work is being rolled back. Generally this is because an exception was raised while
         * processing the {@link #getMessage() message} or while the Unit of Work was being committed.
         */
        ROLLBACK(true, true, true),

        /**
         * Indicates that the Unit of Work is after a successful commit. In this phase the Unit of Work cannot be rolled
         * back anymore.
         */
        AFTER_COMMIT(true, true, true),

        /**
         * Indicates that the Unit of Work is after a successful commit or after a rollback. Any resources tied to this
         * Unit of Work should be released.
         */
        CLEANUP(false, true, true),

        /**
         * Indicates that the Unit of Work is at the end of its life cycle. This phase is final.
         */
        CLOSED(false, true, true);

        private final boolean started;
        private final boolean reverseCallbackOrder;
        private final boolean suppressHandlerErrors;

        Phase(boolean started, boolean reverseCallbackOrder, boolean suppressHandlerErrors) {
            this.started = started;
            this.reverseCallbackOrder = reverseCallbackOrder;
            this.suppressHandlerErrors = suppressHandlerErrors;
        }

        /**
         * Check if a Unit of Work in this phase has been started, i.e. is registered with the {@link
         * CurrentUnitOfWork}.
         *
         * @return {@code true} if the Unit of Work is started when in this phase, {@code false} otherwise
         */
        public boolean isStarted() {
            return started;
        }

        /**
         * Check whether registered handlers for this phase should be invoked in the order of registration (first
         * registered handler is invoked first) or in the reverse order of registration (last registered handler is
         * invoked first).
         *
         * @return {@code true} if the order of invoking handlers in this phase should be in the reverse order of
         * registration, {@code false} otherwise.
         */
        public boolean isReverseCallbackOrder() {
            return reverseCallbackOrder;
        }

        /**
         * Indicates whether the handlers triggered for this phase should have their exceptions suppressed. This is the
         * case for phases where cleanup or post-error processing is done. Exceptions in those phases should not trigger
         * exceptions, but to a best-effort attempt to clean up.
         *
         * @return {@code true} when errors should be suppressed, otherwise {@code false}
         */
        public boolean isSuppressHandlerErrors() {
            return suppressHandlerErrors;
        }

        /**
         * Check if this Phase comes before given other {@code phase}.
         *
         * @param phase The other Phase
         *
         * @return {@code true} if this comes before the given {@code phase}, {@code false} otherwise.
         */
        public boolean isBefore(Phase phase) {
            return ordinal() < phase.ordinal();
        }

        /**
         * Check if this Phase comes after given other {@code phase}.
         *
         * @param phase The other Phase
         * @return {@code true} if this comes after the given {@code phase}, {@code false} otherwise.
         */
        public boolean isAfter(Phase phase) {
            return ordinal() > phase.ordinal();
        }
    }

    /**
     * Functional interface for a callable that can be executed in the context of a {@link ProcessingContext}.
     *
     * @param <R> The type of result that is returned after successful execution.
     */
    @FunctionalInterface
    interface ProcessingContextCallable<R> {

        /**
         * Execute the callable in the context of the given {@code context}.
         *
         * @param context The context in which to execute the callable.
         * @return The result of the callable.
         * @throws Exception when there was a problem that prevented invocation of the method or if an exception was
         *                   thrown from the invoked method.
         */
        R call(ProcessingContext context) throws Exception;
    }
}
