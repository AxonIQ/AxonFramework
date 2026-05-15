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

package org.axonframework.messaging.eventhandling.processing.subscribing;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ConfigurationExtension;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.SubscribableEventSource;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.eventhandling.processing.errorhandling.ErrorHandler;
import org.axonframework.messaging.eventhandling.processing.errorhandling.PropagatingErrorHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.axonframework.common.BuilderUtils.assertNonNull;

/**
 * Configuration class for a {@link SubscribingEventProcessor}.
 * <p>
 * {@link ErrorHandler} is defaulted to a {@link PropagatingErrorHandler} and the {@link UnitOfWorkFactory} defaults to
 * the {@link SimpleUnitOfWorkFactory}. The Event Processor {@link SubscribableEventSource} is <b>hard requirements</b>
 * and as such should be provided.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
public class SubscribingEventProcessorConfiguration extends EventProcessorConfiguration {

    private SubscribableEventSource eventSource;
    private Consumer<? super EventMessage> ignoredMessageHandler;
    private List<MessageHandlerInterceptor<? super EventMessage>> sepInterceptors;

    /**
     * Constructs a new {@code SubscribingEventProcessorConfiguration} copying properties from the given configuration.
     *
     * @param base The {@link EventProcessorConfiguration} to copy properties from.
     */
    @Internal
    public SubscribingEventProcessorConfiguration(EventProcessorConfiguration base) {
        super(base);
    }

    /**
     * Constructs a new {@code SubscribingEventProcessorConfiguration} with default values and retrieve global default
     * values.
     *
     * @param base          The {@link EventProcessorConfiguration} to copy properties from.
     * @param configuration The configuration, used to retrieve global default values, like
     *                      {@link MessageHandlerInterceptor MessageHandlerInterceptors}, from.
     */
    @Internal
    public SubscribingEventProcessorConfiguration(EventProcessorConfiguration base,
                                                  Configuration configuration) {
        super(base);
    }

    @Override
    public SubscribingEventProcessorConfiguration errorHandler(ErrorHandler errorHandler) {
        super.errorHandler(errorHandler);
        return this;
    }

    /**
     * Sets the {@link SubscribableEventSource} (e.g. the {@link EventBus}) to which this {@link EventProcessor}
     * implementation will subscribe itself to receive {@link EventMessage}s.
     *
     * @param eventSource The {@link SubscribableEventSource} (e.g. the {@link EventBus}) to which this
     *                    {@link EventProcessor} implementation will subscribe itself to receive {@link EventMessage}s.
     * @return The current instance, for fluent interfacing.
     */
    public SubscribingEventProcessorConfiguration eventSource(SubscribableEventSource eventSource) {
        assertNonNull(eventSource, "SubscribableEventSource may not be null");
        this.eventSource = eventSource;
        return this;
    }

    /**
     * Sets the handler, that is invoked when the event is ignored by all
     * {@link org.axonframework.messaging.eventhandling.EventHandlingComponent EventHandlingComponents} this
     * {@link SubscribingEventProcessor} controls. Defaults to a no-op.
     *
     * @param ignoredMessageHandler The handler, that is invoked when the event is ignored by all
     *                              {@link org.axonframework.messaging.eventhandling.EventHandlingComponent
     *                              EventHandlingComponents} this {@link SubscribingEventProcessor} controls.
     * @return The current Builder instance, for fluent interfacing.
     */
    public SubscribingEventProcessorConfiguration ignoredMessageHandler(
            Consumer<? super EventMessage> ignoredMessageHandler
    ) {
        this.ignoredMessageHandler = ignoredMessageHandler;
        return this;
    }

    @Override
    public SubscribingEventProcessorConfiguration unitOfWorkFactory(UnitOfWorkFactory unitOfWorkFactory) {
        super.unitOfWorkFactory(unitOfWorkFactory);
        return this;
    }

    /**
     * Registers the given {@link EventMessage}-specific {@link MessageHandlerInterceptor} for the
     * {@link SubscribingEventProcessor} under construction.
     *
     * @param interceptor The {@link EventMessage}-specific {@link MessageHandlerInterceptor} to register for the
     *                    {@link SubscribingEventProcessor} under construction.
     * @return This {@code SubscribingEventProcessorConfiguration}, for fluent interfacing.
     */
        public SubscribingEventProcessorConfiguration withInterceptor(
            MessageHandlerInterceptor<? super EventMessage> interceptor
    ) {
        //noinspection unchecked | Casting to EventMessage is safe.
            this.interceptors.add(interceptor);
        return this;
    }

    /**
     * Validates whether the fields contained in this Builder are set accordingly.
     *
     * @throws AxonConfigurationException if one field is asserted to be incorrect according to the Builder's
     *                                    specifications.
     */
    @Override
    protected void validate() throws AxonConfigurationException {
        super.validate();
        assertNonNull(eventSource, "The SubscribableMessageSource is a hard requirement and should be provided");
    }

    /**
     * Returns the {@link SubscribableEventSource} to which this processor subscribes.
     *
     * @return The {@link SubscribableEventSource} for receiving events.
     */
    public SubscribableEventSource eventSource() {
        return eventSource;
    }

    /**
     * Returns the list of {@link EventMessage}-specific {@link MessageHandlerInterceptor MessageHandlerInterceptors} to
     * add to the {@link SubscribingEventProcessor} under construction with this configuration implementation.
     *
     * @return The list of {@link EventMessage}-specific {@link MessageHandlerInterceptor MessageHandlerInterceptors} to
     * add to the {@link SubscribingEventProcessor} under construction with this configuration implementation.
     */
    public List<MessageHandlerInterceptor<? super EventMessage>> interceptors() {
        if (sepInterceptors == null) {
            sepInterceptors = new ArrayList<>();
            sepInterceptors.addAll(super.interceptorBuilder.apply(SubscribingEventProcessor.class, processorName));
            sepInterceptors.addAll(super.interceptors);
        }
        return new ArrayList<>(sepInterceptors);
    }

    /**
     * Returns the handler for ignored messages.
     *
     * @return The ignored message handler.
     */
    public Consumer<? super EventMessage> ignoredMessageHandler() {
        if (ignoredMessageHandler == null) {
            ignoredMessageHandler =
                    eventMessage -> monitorBuilder.apply(SubscribingEventProcessor.class, processorName)
                                                  .onMessageIngested(eventMessage)
                                                  .reportIgnored();
        }
        return ignoredMessageHandler;
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        super.describeTo(descriptor);
        descriptor.describeProperty("eventSource", eventSource);
    }

    @Override
    public <T extends ConfigurationExtension<?>> SubscribingEventProcessorConfiguration extend(
            Class<T> extensionType,
            Supplier<T> factory
    ) {
        super.extend(extensionType, factory);
        return this;
    }
}
