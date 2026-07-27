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

package org.axonframework.messaging.eventhandling;

import org.axonframework.common.FutureUtils;
import org.axonframework.common.Registration;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * Simple implementation of the {@link EventBus} that provides synchronous event publication with optional
 * {@link ProcessingContext} integration for transactional event handling.
 * <p>
 * This event bus supports two publication modes depending on whether a {@link ProcessingContext} is provided:
 * <ul>
 *     <li><b>Immediate publication (context is {@code null}):</b> Events are published directly to all subscribers
 *     without any queueing or lifecycle management, outside any transactional boundary. The returned
 *     {@link CompletableFuture} completes once all subscribers have finished handling the events, and completes
 *     exceptionally when a subscriber fails.</li>
 *     <li><b>Deferred publication (context is provided):</b> Events are queued and published during the
 *     {@link ProcessingLifecycle.DefaultPhases#PREPARE_COMMIT PREPARE_COMMIT}
 *     phase of the processing lifecycle. This ensures that events are only published if the processing context
 *     successfully reaches the commit phase, providing transactional consistency.</li>
 * </ul>
 * <p>
 * <b>ProcessingContext Integration:</b>
 * <p>
 * When events are published with a {@link ProcessingContext}, this event bus registers lifecycle hooks on the first
 * publication within that context:
 * <ul>
 *     <li>A {@code onPrepareCommit} handler that publishes all queued events to subscribers</li>
 *     <li>A {@code doFinally} handler that cleans up the event queue to free memory</li>
 * </ul>
 * All events published within the same {@link ProcessingContext} are queued together and published as a single batch
 * during the PREPARE_COMMIT phase. If additional events are published by event handlers during this phase, they are
 * also processed in the same phase to ensure complete event propagation.
 * <p>
 * <b>Publication Restrictions:</b>
 * <p>
 * Event publication is <b>forbidden</b> once the {@link ProcessingContext} has been committed. Attempting to publish
 * events during or after the
 * {@link ProcessingLifecycle.DefaultPhases#COMMIT COMMIT} phase will result
 * in an {@link IllegalStateException}. This restriction ensures that events cannot be published after the
 * transactional boundary has been crossed, preventing inconsistent state.
 * <p>
 * Example usage:
 * <pre>{@code
 * // Immediate publication (no transactional context)
 * EventBus eventBus = new SimpleEventBus();
 * eventBus.publish(null, List.of(new GenericEventMessage<>(new OrderPlacedEvent())));
 *
 * // Deferred publication within a UnitOfWork
 * UnitOfWork uow = unitOfWorkFactory.create();
 * uow.runOnInvocation(ctx -> {
 *     eventBus.publish(ctx, List.of(new GenericEventMessage<>(new OrderPlacedEvent())));
 *     // Events are not yet published - they're queued
 * });
 * uow.execute(); // Events are published during PREPARE_COMMIT phase
 * }</pre>
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @author René de Waele
 * @since 0.5
 */
public class SimpleEventBus implements EventBus {

    private final Context.ResourceKey<List<EventMessage>> eventsKey = Context.ResourceKey.withLabel("EventBus_Events");
    private final EventSubscribers eventSubscribers = new EventSubscribers();

    /**
     * Instantiate an {@code SimpleEventBus}.
     **/
    public SimpleEventBus() {
        super();
    }

    @Override
    public Registration subscribe(
            BiFunction<List<? extends EventMessage>, ProcessingContext, CompletableFuture<?>> eventsBatchConsumer
    ) {
        return eventSubscribers.subscribe(eventsBatchConsumer);
    }

    @Override
    public CompletableFuture<Void> publish(@Nullable ProcessingContext context, List<? extends EventMessage> events) {
        if (context == null) {
            // No processing context, publish immediately and propagate subscriber completion to the publisher.
            return notifySubscribers(events, context);
        }

        registerEventPublishingHooks(context, events);
        return FutureUtils.emptyCompletedFuture();
    }

    private void registerEventPublishingHooks(ProcessingContext context, List<? extends EventMessage> events) {
        // Check if we're already in or past the commit phase - publishing is forbidden at this point
        if (context.isCommitted()) {
            throw new IllegalStateException(
                    "It is not allowed to publish events when the ProcessingContext has already been committed. "
                            + "Please start a new ProcessingContext before publishing events."
            );
        }

        // Register lifecycle handlers and create event queue on first publish in this context
        List<EventMessage> eventQueue = context.computeResourceIfAbsent(eventsKey, () -> {
            ArrayList<EventMessage> queue = new ArrayList<>();

            context.onPrepareCommit(ctx -> {
                List<EventMessage> queuedEvents = ctx.getResource(eventsKey);
                return processEventsInPhase(queuedEvents, ctx, this::notifySubscribers);
            });

            // Clean up events resource on completion or error to free memory
            context.doFinally(ctx -> ctx.removeResource(eventsKey));

            return queue;
        });

        eventQueue.addAll(events);
    }

    private CompletableFuture<Void> notifySubscribers(
            List<? extends EventMessage> events,
            @Nullable ProcessingContext publicationContext
    ) {
        return eventSubscribers.notifySubscribers(
                events,
                EventPublicationContext.forPublishedEvents(publicationContext, events)
        );
    }

    /**
     * Process events during a specific phase, handling events published during the phase.
     *
     * @param queuedEvents The events queued for processing
     * @param context      The processing context
     * @param processor    The processor to invoke for the events
     * @return A {@link CompletableFuture} that completes when all event processing is done
     */
    private CompletableFuture<Void> processEventsInPhase(
            List<EventMessage> queuedEvents,
            ProcessingContext context,
            BiFunction<List<? extends EventMessage>, ProcessingContext, CompletableFuture<Void>> processor
    ) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        int processedItems = queuedEvents.size();

        // Create a copy to avoid concurrent modification during event publication
        futures.add(processor.apply(new ArrayList<>(queuedEvents), context));

        // Make sure events published during this phase are also processed
        while (processedItems < queuedEvents.size()) {
            List<EventMessage> newMessages = new ArrayList<>(
                    queuedEvents.subList(processedItems, queuedEvents.size())
            );
            processedItems = queuedEvents.size();
            futures.add(processor.apply(newMessages, context));
        }

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("eventsKey", eventsKey);
        descriptor.describeProperty("eventSubscribers", eventSubscribers);
    }

}
