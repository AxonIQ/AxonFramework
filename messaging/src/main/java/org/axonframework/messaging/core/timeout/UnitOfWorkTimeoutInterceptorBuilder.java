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

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.slf4j.Logger;

import java.util.concurrent.ScheduledExecutorService;

/**
 * Message handler interceptor that sets a timeout on the processing of the current {@link ProcessingContext}. If the
 * timeout is reached, the thread is interrupted and the transaction will be rolled back automatically.
 * <p>
 * Note: Due to interceptor ordering, this interceptor may not be the first in the chain. We are unable to work around
 * this, and as such the timeout measuring starts from the moment this interceptor is invoked, and ends measuring when
 * the commit of the {@link ProcessingContext} is completed.
 *
 * @author Mitchell Herrijgers
 * @since 4.11.0
 */
// TODO revisit as part of #3559
public class UnitOfWorkTimeoutInterceptorBuilder {

    private static final String TRANSACTION_TIME_LIMIT_RESOURCE_KEY = "_transactionTimeLimit";
    private static final Context.ResourceKey<AxonTimeLimitedTask> TRANSACTION_TIME_LIMIT_CONTEXT_RESOURCE_KEY =
            Context.ResourceKey.withLabel(TRANSACTION_TIME_LIMIT_RESOURCE_KEY);

    private final String componentName;
    private final int timeout;
    private final int warningThreshold;
    private final int warningInterval;
    private final ScheduledExecutorService executorService;
    private final Logger logger;

    /**
     * Creates a new {@code UnitOfWorkTimeoutInterceptor} for the given {@code componentName} with the given
     * {@code timeout}, {@code warningThreshold} and {@code warningInterval}. The warnings and timeout will be scheduled
     * on the {@link AxonTaskJanitor#INSTANCE}. If you want to use a different {@link ScheduledExecutorService} or
     * {@link Logger} to log on, use the other
     * {@link #UnitOfWorkTimeoutInterceptorBuilder(String, int, int, int, ScheduledExecutorService, Logger)}.
     *
     * @param componentName    The name of the component to be included in the logging
     * @param timeout          The timeout in milliseconds
     * @param warningThreshold The threshold in milliseconds after which a warning is logged. Setting this to a value
     *                         higher than {@code timeout} will disable warnings.
     * @param warningInterval  The interval in milliseconds between warnings.
     */
    public UnitOfWorkTimeoutInterceptorBuilder(String componentName,
                                               int timeout,
                                               int warningThreshold,
                                               int warningInterval
    ) {
        this(componentName,
             timeout,
             warningThreshold,
             warningInterval,
             AxonTaskJanitor.INSTANCE,
             AxonTaskJanitor.LOGGER);
    }

    /**
     * Creates a new {@code UnitOfWorkTimeoutInterceptor} for the given {@code componentName} with the given
     * {@code timeout}, {@code warningThreshold} and {@code warningInterval}. The warnings and timeout will be scheduled
     * on the provided {@code executorService}.
     *
     * @param componentName    The name of the component to be included in the logging
     * @param timeout          The timeout in milliseconds
     * @param warningThreshold The threshold in milliseconds after which a warning is logged. Setting this to a value
     *                         higher than {@code timeout} will disable warnings.
     * @param warningInterval  The interval in milliseconds between warnings.
     * @param executorService  The executor service to schedule the timeout and warnings
     * @param logger           The logger to log warnings and errors
     */
    public UnitOfWorkTimeoutInterceptorBuilder(String componentName,
                                               int timeout,
                                               int warningThreshold,
                                               int warningInterval,
                                               ScheduledExecutorService executorService,
                                               Logger logger
    ) {
        this.componentName = componentName;
        this.timeout = timeout;
        this.warningThreshold = warningThreshold;
        this.warningInterval = warningInterval;
        this.executorService = executorService;
        this.logger = logger;
    }

    /**
     * Constructs a {@link CommandMessage} handler interceptor, to be registered on (e.g.) the
     * {@link CommandBus}.
     *
     * @return A {@link CommandMessage} handler interceptor, to be registered on (e.g.) the
     * {@link CommandBus}.
     */
    public MessageHandlerInterceptor<CommandMessage> buildCommandInterceptor() {
        return build();
    }

    /**
     * Constructs a {@link EventMessage} handler interceptor, to be registered on (e.g.) the
     * {@link EventProcessorConfiguration}.
     *
     * @return A {@link EventMessage} handler interceptor, to be registered on (e.g.) the
     * {@link EventProcessorConfiguration}.
     */
    public MessageHandlerInterceptor<EventMessage> buildEventInterceptor() {
        return build();
    }

    /**
     * Constructs a {@link QueryMessage} handler interceptor, to be registered on (e.g.) the
     * {@link QueryBus}.
     *
     * @return A {@link QueryMessage} handler interceptor, to be registered on (e.g.) the
     * {@link QueryBus}.
     */
    public MessageHandlerInterceptor<QueryMessage> buildQueryInterceptor() {
        return build();
    }

    <T extends Message> MessageHandlerInterceptor<T> build() {
        return (message, context, interceptorChain) -> {
            initializeTimeoutIfNotInitialized(context);
            AxonTimeLimitedTask task = context.getResource(TRANSACTION_TIME_LIMIT_CONTEXT_RESOURCE_KEY);
            try {
                MessageStream<?> proceed = interceptorChain.proceed(message, context);
                task.ensureNoInterruptionWasSwallowed();
                return proceed;
            } catch (Exception e) {
                return MessageStream.failed(task.detectInterruptionInsteadOfException(e));
            }
        };
    }

    void initializeTimeoutIfNotInitialized(ProcessingContext context) {
        String taskName = "UnitOfWork of " + componentName;
        if (!context.containsResource(TRANSACTION_TIME_LIMIT_CONTEXT_RESOURCE_KEY)) {
            AxonTimeLimitedTask taskTimeout = new AxonTimeLimitedTask(
                    taskName,
                    timeout,
                    warningThreshold,
                    warningInterval,
                    executorService,
                    logger,
                    UnitOfWorkTimeoutInterceptorBuilder.class
            );
            context.putResource(TRANSACTION_TIME_LIMIT_CONTEXT_RESOURCE_KEY, taskTimeout);
            taskTimeout.start();
            context.runOnAfterCommit(u -> taskTimeout.complete());
            context.onError((ctx, phase, error) -> taskTimeout.complete());
        }
    }
}
