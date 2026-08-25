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

import org.axonframework.common.Assert;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.correlation.CorrelationDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Abstract implementation of the Unit of Work. It provides default implementations of all methods related to the
 * processing of a Message.
 *
 * @author Allard Buijze
 * @since 3.0
 * @deprecated This class will be removed.
 */
@Deprecated(since = "5.0.0", forRemoval = true)
public abstract class AbstractLegacyUnitOfWork<T extends Message> implements LegacyUnitOfWork<T> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractLegacyUnitOfWork.class);
    private final Map<String, Object> resources = new HashMap<>();
    private final Collection<CorrelationDataProvider> correlationDataProviders = new LinkedHashSet<>();
    private LegacyUnitOfWork<?> parentUnitOfWork;
    private Phase phase = Phase.NOT_STARTED;
    private boolean rolledBack;

    @Override
    public void start() {
        if (logger.isDebugEnabled()) {
            logger.debug("Starting Unit Of Work");
        }
        Assert.state(Phase.NOT_STARTED.equals(phase()), () -> "UnitOfWork is already started");
        rolledBack = false;
        onRollback(u -> rolledBack = true);
        CurrentUnitOfWork.ifStarted(parent -> {
            // we're nesting.
            this.parentUnitOfWork = parent;
            root().onCleanup(r -> changePhase(Phase.CLEANUP, Phase.CLOSED));
        });
        changePhase(Phase.STARTED);
        CurrentUnitOfWork.set(this);
    }

    @Override
    public void commit() {
        if (logger.isDebugEnabled()) {
            logger.debug("Committing Unit Of Work");
        }
        Assert.state(phase() == Phase.STARTED, () -> String.format("The UnitOfWork is in an incompatible phase: %s", phase()));
        Assert.state(isCurrent(), () -> "The UnitOfWork is not the current Unit of Work");
        try {
            if (isRoot()) {
                commitAsRoot();
            } else {
                commitAsNested();
            }
        } finally {
            CurrentUnitOfWork.clear(this);
        }
    }

    private void commitAsRoot() {
        try {
            try {
                changePhase(Phase.PREPARE_COMMIT, Phase.COMMIT);
            } catch (Exception e) {
                setRollbackCause(e);
                changePhase(Phase.ROLLBACK);
                throw e;
            }
            if (phase() == Phase.COMMIT) {
                changePhase(Phase.AFTER_COMMIT);
            }
        } finally {
            changePhase(Phase.CLEANUP, Phase.CLOSED);
        }
    }

    private void commitAsNested() {
        try {
            changePhase(Phase.PREPARE_COMMIT, Phase.COMMIT);
            delegateAfterCommitToParent(this);
            parentUnitOfWork.onRollback(u -> changePhase(Phase.ROLLBACK));
        } catch (Exception e) {
            setRollbackCause(e);
            changePhase(Phase.ROLLBACK);
            throw e;
        }
    }

    private void delegateAfterCommitToParent(LegacyUnitOfWork<?> uow) {
        Optional<LegacyUnitOfWork<?>> parent = uow.parent();
        if (parent.isPresent()) {
            parent.get().afterCommit(this::delegateAfterCommitToParent);
        } else {
            changePhase(Phase.AFTER_COMMIT);
        }
    }

    @Override
    public void rollback(Throwable cause) {
        if (logger.isDebugEnabled()) {
            logger.debug("Rolling back Unit Of Work.", cause);
        }
        Assert.state(isActive() && phase().isBefore(Phase.ROLLBACK),
                     () -> String.format("The UnitOfWork is in an incompatible phase: %s", phase()));
        Assert.state(isCurrent(), () -> "The UnitOfWork is not the current Unit of Work");
        try {
            setRollbackCause(cause);
            changePhase(Phase.ROLLBACK);
            if (isRoot()) {
                changePhase(Phase.CLEANUP, Phase.CLOSED);
            }
        } finally {
            CurrentUnitOfWork.clear(this);
        }
    }

    @Override
    public Optional<LegacyUnitOfWork<?>> parent() {
        return Optional.ofNullable(parentUnitOfWork);
    }

    @Override
    public Map<String, Object> resources() {
        return resources;
    }

    @Override
    public boolean isRolledBack() {
        return rolledBack;
    }

    @Override
    public void registerCorrelationDataProvider(CorrelationDataProvider correlationDataProvider) {
        correlationDataProviders.add(correlationDataProvider);
    }

    @Override
    public Metadata getCorrelationData() {
        if (correlationDataProviders.isEmpty()) {
            return Metadata.emptyInstance();
        }
        Map<String, String> result = new HashMap<>();
        for (CorrelationDataProvider correlationDataProvider : correlationDataProviders) {
            try {
                result.putAll(correlationDataProvider.correlationDataFor(getMessage()));
            } catch (Exception e) {
                logger.warn(
                        "Encountered exception creating correlation data for message with id: '{}' "
                                + "using correlation provider with class: '{}'"
                                + "will continue without as this might otherwise prevent a rollback.",
                        getMessage().identifier(),
                        correlationDataProvider.getClass(),
                        e);
            }
        }
        return Metadata.from(result);
    }

    @Override
    public void onPrepareCommit(Consumer<LegacyUnitOfWork<T>> handler) {
        addHandler(Phase.PREPARE_COMMIT, handler);
    }

    @Override
    public void onCommit(Consumer<LegacyUnitOfWork<T>> handler) {
        addHandler(Phase.COMMIT, handler);
    }

    @Override
    public void afterCommit(Consumer<LegacyUnitOfWork<T>> handler) {
        addHandler(Phase.AFTER_COMMIT, handler);
    }

    @Override
    public void onRollback(Consumer<LegacyUnitOfWork<T>> handler) {
        addHandler(Phase.ROLLBACK, handler);
    }

    @Override
    public void onCleanup(Consumer<LegacyUnitOfWork<T>> handler) {
        addHandler(Phase.CLEANUP, handler);
    }

    @Override
    public Phase phase() {
        return phase;
    }

    /**
     * Overwrite the current phase with the given {@code phase}.
     *
     * @param phase the new phase of the Unit of Work
     */
    protected void setPhase(Phase phase) {
        this.phase = phase;
    }

    /**
     * Ask the unit of work to transition to the given {@code phases} sequentially. In each of the phases the
     * unit of work is responsible for invoking the handlers attached to each phase.
     * <p/>
     * By default this sets the Phase and invokes the handlers attached to the phase.
     *
     * @param phases The phases to transition to in sequential order
     */
    protected void changePhase(Phase... phases) {
        for (Phase phase : phases) {
            setPhase(phase);
            notifyHandlers(phase);
        }
    }

    /**
     * Provides the collection of registered Correlation Data Providers of this Unit of Work. The returned collection is a live view of the providers
     * registered. Any changes in the registration are reflected in the returned collection.
     *
     * @return The Correlation Data Providers registered with this Unit of Work.
     */
    protected Collection<CorrelationDataProvider> correlationDataProviders() {
        return correlationDataProviders;
    }

    /**
     * Notify the handlers attached to the given {@code phase}.
     *
     * @param phase The phase for which to invoke registered handlers.
     */
    protected abstract void notifyHandlers(Phase phase);

    /**
     * Register the given {@code handler} with the Unit of Work. The handler will be invoked when the
     * Unit of Work changes its phase to the given {@code phase}.
     *
     * @param phase   the Phase of the Unit of Work at which to invoke the handler
     * @param handler the handler to add
     */
    protected abstract void addHandler(Phase phase, Consumer<LegacyUnitOfWork<T>> handler);

    /**
     * Set the execution result of processing the current {@link #getMessage() Message}.
     *
     * @param executionResult the ExecutionResult of the currently handled Message
     */
    protected abstract void setExecutionResult(ExecutionResult executionResult);

    /**
     * Sets the cause for rolling back this Unit of Work.
     *
     * @param cause The cause for rolling back this Unit of Work
     */
    protected abstract void setRollbackCause(Throwable cause);
}
