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

import java.util.function.Consumer;
import java.util.function.Function;


import org.axonframework.common.Assert;
import org.axonframework.common.ObjectUtils;
import org.axonframework.messaging.core.GenericResultMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.ResultMessage;
import org.axonframework.messaging.core.unitofwork.LegacyMessageSupportingContext;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;

/**
 * Implementation of the UnitOfWork that processes a single message.
 *
 * @author Allard Buijze
 * @since 0.6
 * @deprecated In favor of the {@link UnitOfWork}.
 */
@Deprecated(since = "5.0.0", forRemoval = true)
public class LegacyDefaultUnitOfWork<T extends Message> extends AbstractLegacyUnitOfWork<T> {

    private final MessageProcessingContext<T> processingContext;

    /**
     * Starts a new DefaultUnitOfWork instance, registering it a CurrentUnitOfWork. This methods returns the started
     * UnitOfWork instance.
     * <p>
     * Note that this Unit Of Work type is not meant to be shared among different Threads. A single DefaultUnitOfWork
     * instance should be used exclusively by the Thread that created it.
     *
     * @param message the message that will be processed in the context of the unit of work
     * @return the started UnitOfWork instance
     */
    public static <T extends Message> LegacyDefaultUnitOfWork<T> startAndGet(T message) {
        LegacyDefaultUnitOfWork<T> uow = new LegacyDefaultUnitOfWork<>(message);
        uow.start();
        return uow;
    }

    /**
     * Initializes a Unit of Work (without starting it).
     *
     * @param message the message that will be processed in the context of the unit of work
     */
    public LegacyDefaultUnitOfWork(T message) {
        processingContext = new MessageProcessingContext<>(message);
    }

    @Override
    public <R> ResultMessage executeWithResult(ProcessingContextCallable<R> task,
                                                  RollbackConfiguration rollbackConfiguration) {
        if (phase() == Phase.NOT_STARTED) {
            start();
        }
        Assert.state(phase() == Phase.STARTED,
                     () -> String.format("The UnitOfWork has an incompatible phase: %s", phase()));
        R result;
        ResultMessage resultMessage;
        try {
            ProcessingContext context = new LegacyMessageSupportingContext(getMessage());
            //noinspection DuplicatedCode
            result = task.call(context);
            if (result instanceof ResultMessage) {
                //noinspection unchecked
                resultMessage = (ResultMessage) result;
            } else if (result instanceof Message) {
                resultMessage = new GenericResultMessage(((Message) result).type(),
                                                           result,
                                                           ((Message) result).metadata());
            } else {
                resultMessage = new GenericResultMessage(new MessageType(ObjectUtils.nullSafeTypeOf(result)), result);
            }
        } catch (Error | Exception e) {
            resultMessage = GenericResultMessage.asResultMessage(e);
            if (rollbackConfiguration.rollBackOn(e)) {
                rollback(e);
                return resultMessage;
            }
        }
        setExecutionResult(new ExecutionResult(resultMessage));
        try {
            commit();
        } catch (Exception e) {
            resultMessage = GenericResultMessage.asResultMessage(e);
        }
        return resultMessage;
    }

    @Override
    protected void setRollbackCause(Throwable cause) {
        MessageType type = new MessageType(ObjectUtils.nullSafeTypeOf(cause));
        setExecutionResult(new ExecutionResult(new GenericResultMessage(type, cause)));
    }

    @Override
    protected void notifyHandlers(Phase phase) {
        processingContext.notifyHandlers(this, phase);
    }

    @Override
    protected void addHandler(Phase phase, Consumer<LegacyUnitOfWork<T>> handler) {
        Assert.state(!phase.isBefore(phase()), () -> "Cannot register a listener for phase: " + phase
                + " because the Unit of Work is already in a later phase: " + phase());
        processingContext.addHandler(phase, handler);
    }

    @Override
    public T getMessage() {
        return processingContext.getMessage();
    }

    @Override
    public LegacyUnitOfWork<T> transformMessage(Function<T, ? extends Message> transformOperator) {
        processingContext.transformMessage(transformOperator);
        return this;
    }

    @Override
    public ExecutionResult getExecutionResult() {
        return processingContext.getExecutionResult();
    }

    @Override
    protected void setExecutionResult(ExecutionResult executionResult) {
        processingContext.setExecutionResult(executionResult);
    }
}
