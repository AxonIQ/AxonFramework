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
import org.axonframework.messaging.unitofwork.LegacyUnitOfWork.Phase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Maintains the context around the processing of a single Message. This class notifies handlers when the Unit of Work
 * processing the Message transitions to a new {@link Phase}.
 *
 * @author Rene de Waele
 * @since 3.0
 * @deprecated This instance will be removed.
 */
@Deprecated(since ="5.0.0")
public class MessageProcessingContext<T extends Message> {

    private static final Logger LOGGER = LoggerFactory.getLogger(MessageProcessingContext.class);
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    private static final Deque EMPTY = new LinkedList<>();

    private final EnumMap<Phase, Deque<Consumer<LegacyUnitOfWork<T>>>> handlers = new EnumMap<>(Phase.class);
    private T message;
    private ExecutionResult executionResult;

    /**
     * Creates a new processing context for the given {@code message}.
     *
     * @param message The Message that is to be processed.
     */
    public MessageProcessingContext(T message) {
        this.message = message;
    }

    /**
     * Invoke the handlers in this collection attached to the given {@code phase}.
     *
     * @param unitOfWork    The Unit of Work that is changing its phase
     * @param phase         The phase for which attached handlers should be invoked
     */
    @SuppressWarnings("unchecked")
    public void notifyHandlers(LegacyUnitOfWork<T> unitOfWork, Phase phase) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Notifying handlers for phase {}", phase.toString());
        }
        Deque<Consumer<LegacyUnitOfWork<T>>> l = handlers.getOrDefault(phase, EMPTY);
        while (!l.isEmpty()) {
            try {
                l.remove().accept(unitOfWork);
            } catch (Exception e) {
                if (phase.isSuppressHandlerErrors()) {
                    LOGGER.info("An error occurred while executing a lifecycle phase handler for phase {}", phase, e);
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * Adds a handler to the collection. Note that the order in which you register the handlers determines the order
     * in which they will be handled during the various stages of a unit of work.
     *
     * @param phase   The phase of the unit of work to attach the handler to
     * @param handler The handler to invoke in the given phase
     */
    public void addHandler(Phase phase, Consumer<LegacyUnitOfWork<T>> handler) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Adding handler {} for phase {}", handler.getClass().getName(), phase.toString());
        }
        final Deque<Consumer<LegacyUnitOfWork<T>>> consumers = handlers.computeIfAbsent(phase, p -> new ArrayDeque<>());
        if (phase.isReverseCallbackOrder()) {
            consumers.addFirst(handler);
        } else {
            consumers.add(handler);
        }
    }

    /**
     * Set the execution result of processing the current {@link #getMessage() Message}. In case this context has a
     * previously set ExecutionResult, setting a new result is only allowed if the new result is an exception result.
     * <p/>
     * In case the previously set result is also an exception result, the exception in the new execution result is
     * added to the original exception as a suppressed exception.
     *
     * @param executionResult the ExecutionResult of the currently handled Message
     */
    @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
    public void setExecutionResult(ExecutionResult executionResult) {
        Assert.state(this.executionResult == null || executionResult.isExceptionResult(),
                     () -> String.format("Cannot change execution result [%s] to [%s] for message [%s].",
                        this.executionResult, executionResult, message));
        if (this.executionResult != null && this.executionResult.isExceptionResult()) {
            this.executionResult.getExceptionResult().addSuppressed(executionResult.getExceptionResult());
        } else {
            this.executionResult = executionResult;
        }
    }

    /**
     * Get the Message that is being processed in this context.
     *
     * @return the Message that is being processed
     */
    public T getMessage() {
        return message;
    }

    /**
     * Get the result of processing the {@link #getMessage() Message}. If the Message has not been processed yet this
     * method returns {@code null}.
     *
     * @return The result of processing the Message, or {@code null} if the Message hasn't been processed
     */
    public ExecutionResult getExecutionResult() {
        return executionResult;
    }

    /**
     * Transform the Message being processed using the given operator.
     *
     * @param transformOperator The transform operator to apply to the stored message
     */
    public void transformMessage(Function<T, ? extends Message> transformOperator) {
        message = (T) transformOperator.apply(message);
    }

    /**
     * Reset the processing context. This clears the execution result and map with registered handlers, and replaces
     * the current Message with the given {@code message}.
     *
     * @param message The new message that is being processed
     */
    public void reset(T message) {
        this.message = message;
        handlers.clear();
        executionResult = null;
    }
}
