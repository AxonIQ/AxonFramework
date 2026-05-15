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
import org.axonframework.common.FutureUtils;
import org.axonframework.common.Registration;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.lifecycle.Phase;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.SimpleEntry;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.SubscribableEventSource;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.EventProcessingException;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.eventhandling.processing.ProcessorEventHandlingComponents;
import org.axonframework.messaging.eventhandling.processing.errorhandling.ErrorContext;
import org.axonframework.messaging.eventhandling.processing.errorhandling.ErrorHandler;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static org.axonframework.common.BuilderUtils.assertThat;

/**
 * Event processor implementation that {@link EventBus#subscribe(BiFunction) subscribes} to the {@link EventBus} for
 * events. Events published on the event bus are supplied to this processor in the publishing thread.
 * <p>
 *
 * @author Rene de Waele
 * @since 3.0.0
 */
public class SubscribingEventProcessor implements EventProcessor {

    private final String name;
    private final SubscribingEventProcessorConfiguration configuration;
    private final SubscribableEventSource eventSource;
    private final ProcessorEventHandlingComponents eventHandlingComponents;
    private final ErrorHandler errorHandler;
    private final Consumer<? super EventMessage> ignoredMessageHandler;

    private volatile Registration eventBusRegistration;

    /**
     * Instantiate a {@code SubscribingEventProcessor} with given {@code name}, {@code eventHandlingComponents} and
     * based on the fields contained in the {@link SubscribingEventProcessorConfiguration}.
     * <p>
     * Will assert the following for their presence in the configuration, prior to constructing this processor:
     * <ul>
     *     <li>A {@link SubscribableEventSource}.</li>
     * </ul>
     * If any of these is not present or does not comply to the requirements an {@link AxonConfigurationException} is thrown.
     *
     * @param name                    A {@link String} defining this {@link EventProcessor} instance.
     * @param eventHandlingComponents The {@link EventHandlingComponent}s which will handle all the individual
     *                                {@link EventMessage}s.
     * @param configuration           The {@link SubscribingEventProcessorConfiguration} used to configure a
     *                                {@code SubscribingEventProcessor} instance.
     */
    public SubscribingEventProcessor(
            String name,
            List<EventHandlingComponent> eventHandlingComponents,
            SubscribingEventProcessorConfiguration configuration
    ) {
        this.name = Objects.requireNonNull(name, "Name may not be null");
        assertThat(name, n -> Objects.nonNull(n) && !n.isEmpty(), "Event Processor name may not be null or empty");
        Objects.requireNonNull(configuration, "SubscribingEventProcessorConfiguration may not be null");
        configuration.validate();
        this.configuration = configuration;
        this.eventSource = this.configuration.eventSource();
        this.eventHandlingComponents = new ProcessorEventHandlingComponents(eventHandlingComponents);
        this.errorHandler = this.configuration.errorHandler();
        this.ignoredMessageHandler = this.configuration.ignoredMessageHandler();
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * Start this processor. This will register the processor with the {@link EventBus}.
     * <p>
     * Upon start up of an application, this method will be invoked in the
     * {@link Phase#LOCAL_MESSAGE_HANDLER_REGISTRATIONS} phase.
     */
    @Override
    public CompletableFuture<Void> start() {
        if (eventBusRegistration != null) {
            // This event processor has already been started
            return FutureUtils.emptyCompletedFuture();
        }
        eventBusRegistration = eventSource.subscribe((events, context) -> {
            this.process(events.stream().map(it -> (EventMessage) it).toList(), context);
            return CompletableFuture.completedFuture(null);
        });
        return FutureUtils.emptyCompletedFuture();
    }

    @Override
    public boolean isRunning() {
        return eventBusRegistration != null;
    }

    @Override
    public boolean isError() {
        // this implementation will never stop because of an error
        return false;
    }

    /**
     * Process the given messages. A Unit of Work must be created for this processing.
     * <p>
     * This implementation creates a Batching unit of work for the given batch of {@code eventMessages}.
     *
     * @param eventMessages the messages to process
     * @param context the {@link ProcessingContext} to use
     */
    protected void process(List<EventMessage> eventMessages, @Nullable ProcessingContext context) {
        try {
            if (context != null) { // if ProcessingContext is provided from the outside, the events will be processed in that context
                FutureUtils.joinAndUnwrap(processWithErrorHandling(eventMessages, context).asCompletableFuture());
            } else { // otherwise new UnitOfWork is created
                UnitOfWork unitOfWork = this.configuration.unitOfWorkFactory().create();
                unitOfWork.onInvocation(processingContext -> processWithErrorHandling(eventMessages,
                                                                                      processingContext).asCompletableFuture());
                FutureUtils.joinAndUnwrap(unitOfWork.execute());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new EventProcessingException("Exception occurred while processing events", e);
        }
    }

    private MessageStream.Empty<Message> processWithErrorHandling(List<EventMessage> events,
                                                                  ProcessingContext context) {
        List<MessageStream.Entry<? extends EventMessage>> supportedEntries =
                events.stream()
                      .filter(event -> {
                          boolean supported = eventHandlingComponents.supports(event.type().qualifiedName());
                          if (!supported) {
                              ignoredMessageHandler.accept(event);
                          }
                          return supported;
                      })
                      .<MessageStream.Entry<? extends EventMessage>>map(event -> new SimpleEntry<>(event, Context.empty())).toList();
        return eventHandlingComponents.handle(supportedEntries, context)
                                      .onErrorContinue(ex -> {
                                          try {
                                              errorHandler.handleError(new ErrorContext(name, ex, events, context));
                                          } catch (RuntimeException re) {
                                              return MessageStream.failed(re);
                                          } catch (Exception e) {
                                              return MessageStream.failed(new EventProcessingException(
                                                      "Exception occurred while processing events", e
                                              ));
                                          }
                                          return MessageStream.empty().cast();
                                      })
                                      .ignoreEntries()
                                      .cast();
    }

    /**
     * Shut down this processor. This will deregister the processor with the {@link EventBus}.
     * <p>
     * Upon shutdown of an application, this method will be invoked in the
     * {@link Phase#LOCAL_MESSAGE_HANDLER_REGISTRATIONS} phase.
     */
    @Override
    public CompletableFuture<Void> shutdown() {
        if (eventBusRegistration != null) {
            eventBusRegistration.cancel();
        }
        eventBusRegistration = null;
        return FutureUtils.emptyCompletedFuture();
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("name", name);
        descriptor.describeProperty("mode", "subscribing");
        descriptor.describeProperty("running", isRunning());
        descriptor.describeProperty("eventHandlingComponents", eventHandlingComponents);
        descriptor.describeProperty("configuration", configuration);
    }
}
