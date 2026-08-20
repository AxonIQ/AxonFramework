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
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Message handler interceptor that sets a timeout on the processing of the current {@link ProcessingContext}. If the
 * timeout is reached, the thread is interrupted and the transaction will be rolled back automatically.
 * <p>
 * The timeout measuring starts from the moment this interceptor is invoked, and ends measuring when the commit of the
 * {@link ProcessingContext} is completed. This interceptor's position in the chain is determined by the order in which
 * it (or the {@link org.axonframework.messaging.core.interception.HandlerInterceptorRegistry} decorator registering it)
 * is registered: the first-registered interceptor becomes the outermost one, and thus the one measuring the full
 * duration of the chain. To register this interceptor so it measures the complete processing duration, register it
 * first, e.g. through {@code MessagingConfigurer.registerCommandHandlerInterceptor(...)}/
 * {@code registerEventHandlerInterceptor(...)}/{@code registerQueryHandlerInterceptor(...)}, or through a
 * {@code ConfigurationEnhancer} decorating the
 * {@link org.axonframework.messaging.core.interception.HandlerInterceptorRegistry} at a low order.
 *
 * @author Mitchell Herrijgers
 * @since 4.11.0
 */
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
     * @param componentName    the name of the component to be included in the logging
     * @param timeout          the timeout in milliseconds
     * @param warningThreshold the threshold in milliseconds after which a warning is logged. Setting this to a value
     *                         higher than {@code timeout} will disable warnings
     * @param warningInterval  the interval in milliseconds between warnings
     */
    public UnitOfWorkTimeoutInterceptorBuilder(String componentName,
                                               int timeout,
                                               int warningThreshold,
                                               int warningInterval) {
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
     * @param componentName    the name of the component to be included in the logging
     * @param timeout          the timeout in milliseconds
     * @param warningThreshold the threshold in milliseconds after which a warning is logged. Setting this to a value
     *                         higher than {@code timeout} will disable warnings
     * @param warningInterval  the interval in milliseconds between warnings
     * @param executorService  the executor service to schedule the timeout and warnings
     * @param logger           the logger to log warnings and errors
     */
    public UnitOfWorkTimeoutInterceptorBuilder(String componentName,
                                               int timeout,
                                               int warningThreshold,
                                               int warningInterval,
                                               ScheduledExecutorService executorService,
                                               Logger logger) {
        BuilderUtils.assertNonEmpty(componentName, "The component name may not be empty or null.");
        this.componentName = componentName;
        this.timeout = timeout;
        this.warningThreshold = warningThreshold;
        this.warningInterval = warningInterval;
        this.executorService = Objects.requireNonNull(executorService, "The executor service may not be null.");
        this.logger = Objects.requireNonNull(logger, "The logger may not be null.");
    }

    /**
     * Constructs a {@link CommandMessage} handler interceptor, to be registered on (e.g.) the {@link CommandBus}.
     *
     * @return a {@link CommandMessage} handler interceptor, to be registered on (e.g.) the {@link CommandBus}
     */
    public MessageHandlerInterceptor<CommandMessage> buildCommandInterceptor() {
        return build();
    }

    /**
     * Constructs a {@link EventMessage} handler interceptor, to be registered on (e.g.) the
     * {@link EventProcessorConfiguration}.
     *
     * @return a {@link EventMessage} handler interceptor, to be registered on (e.g.) the
     * {@link EventProcessorConfiguration}
     */
    public MessageHandlerInterceptor<EventMessage> buildEventInterceptor() {
        return build();
    }

    /**
     * Constructs a {@link QueryMessage} handler interceptor, to be registered on (e.g.) the {@link QueryBus}.
     *
     * @return a {@link QueryMessage} handler interceptor, to be registered on (e.g.) the {@link QueryBus}
     */
    public MessageHandlerInterceptor<QueryMessage> buildQueryInterceptor() {
        return build();
    }

    <T extends Message> MessageHandlerInterceptor<T> build() {
        return (message, context, interceptorChain) -> {
            AxonTimeLimitedTask task = resolveOrInitTaskFor(context);
            try {
                MessageStream<?> proceed = interceptorChain.proceed(message, context);
                task.ensureNoInterruptionWasSwallowed();
                return proceed;
            } catch (Exception e) {
                return MessageStream.failed(task.detectInterruptionInsteadOfException(e));
            }
        };
    }

    AxonTimeLimitedTask resolveOrInitTaskFor(ProcessingContext context) {
        String taskName = "UnitOfWork of " + componentName;
        AxonTimeLimitedTask result = context.getResource(TRANSACTION_TIME_LIMIT_CONTEXT_RESOURCE_KEY);
        if (result == null) {
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
            result = taskTimeout;
        }
        return result;
    }
}
