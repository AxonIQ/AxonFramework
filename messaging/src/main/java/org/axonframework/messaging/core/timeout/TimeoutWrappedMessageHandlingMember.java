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

import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.WrappedMessageHandlingMember;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.TimeoutException;

/**
 * Represents a {@link MessageHandlingMember} that wraps another {@link MessageHandlingMember} and enforces a timeout on
 * the invocation of the wrapped member. It does this by starting a {@link AxonTimeLimitedTask} and completes this upon
 * completion. When the execution takes too long, it will throw a {@link TimeoutException}.
 * <p>
 * If the {@code warningThreshold} is lower than the {@code timeout}, warnings will be logged at the configured
 * {@code warningInterval} before the timeout is reached.
 *
 * @param <T> The type of the target object
 * @author Mitchell Herrijgers
 * @since 4.11.0
 */
class TimeoutWrappedMessageHandlingMember<T> extends WrappedMessageHandlingMember<T> {

    private final int timeout;
    private final int warningThreshold;
    private final int warningInterval;

    /**
     * Creates a new {@link TimeoutWrappedMessageHandlingMember} for the given {@code original} handler with the given
     * {@code timeout}, {@code warningThreshold} and {@code warningInterval}.
     *
     * @param original         The original handler to wrap
     * @param timeout          The timeout in milliseconds
     * @param warningThreshold The threshold in milliseconds after which a warning is logged. Setting this to a value
     *                         higher than or equal to {@code timeout} will disable warnings.
     * @param warningInterval  The interval in milliseconds between warnings.
     */
    TimeoutWrappedMessageHandlingMember(MessageHandlingMember<T> original,
                                        int timeout,
                                        int warningThreshold,
                                        int warningInterval) {
        super(original);
        this.timeout = timeout;
        this.warningThreshold = warningThreshold;
        this.warningInterval = warningInterval;
    }

    @Override
    public MessageStream<?> handle(Message message, ProcessingContext context, @Nullable T target) {
        String taskName = String.format("Message [%s] for handler [%s]",
                                        message.type().name(),
                                        target != null ? target.getClass().getName() : null);
        AxonTimeLimitedTask task = new AxonTimeLimitedTask(
                taskName,
                timeout,
                warningThreshold,
                warningInterval,
                getClass()
        );
        task.start();
        try {
            MessageStream<?> result = super.handle(message, context, target);
            task.ensureNoInterruptionWasSwallowed();
            return result;
        } catch (Exception e) {
            return MessageStream.failed(task.detectInterruptionInsteadOfException(e));
        } finally {
            task.complete();
        }
    }

    /**
     * Returns the timeout of the message handler in milliseconds.
     *
     * @return the timeout of the message handler in milliseconds
     */
    public int getTimeout() {
        return timeout;
    }

    /**
     * Returns the threshold in milliseconds after which a warning is logged. Setting this to a value higher than
     * {@link #getTimeout()} will disable warnings.
     *
     * @return the threshold in milliseconds after which a warning is logged
     */
    public int getWarningThreshold() {
        return warningThreshold;
    }

    /**
     * Returns the interval in milliseconds between warnings.
     *
     * @return the interval in milliseconds between warnings
     */
    public int getWarningInterval() {
        return warningInterval;
    }
}
