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

import org.axonframework.common.Assert;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.sequencing.HierarchicalSequencingPolicy;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.core.sequencing.SequentialPerAggregatePolicy;
import org.axonframework.messaging.core.sequencing.SequentialPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.SequenceOverridingEventHandlingComponent;
import org.axonframework.messaging.eventhandling.replay.ReplayStatusChanged;
import org.axonframework.messaging.eventhandling.replay.ReplayStatusChangedHandler;
import org.axonframework.messaging.eventhandling.replay.ReplayStatusChangedHandlerRegistry;
import org.axonframework.messaging.eventhandling.replay.ResetContext;
import org.axonframework.messaging.eventhandling.replay.ResetHandler;
import org.axonframework.messaging.eventhandling.replay.ResetHandlerRegistry;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple implementation of the {@link EventHandlingComponent}, {@link EventHandlerRegistry}, and
 * {@link ResetHandlerRegistry}.
 * <p>
 * As such, it contains a collection of {@link EventHandler EventHandlers} to invoke on
 * {@link #handle(EventMessage, ProcessingContext) handle}, and a set of {@link ResetHandler ResetHandlers} to invoke on
 * {@link #handle(ResetContext, ProcessingContext) reset}.
 *
 * @author Steven van Beelen
 * @since 5.0.0
 */
public class SimpleEventHandlingComponent implements
        EventHandlingComponent,
        EventHandlerRegistry<SimpleEventHandlingComponent>,
        ResetHandlerRegistry<SimpleEventHandlingComponent>,
        ReplayStatusChangedHandlerRegistry<SimpleEventHandlingComponent> {

    private static final SequencingPolicy<? super EventMessage> DEFAULT_SEQUENCING_POLICY = new HierarchicalSequencingPolicy<>(
            SequentialPerAggregatePolicy.INSTANCE,
            SequentialPolicy.INSTANCE
    );

    private final String name;
    private final ConcurrentHashMap<QualifiedName, List<EventHandler>> eventHandlers = new ConcurrentHashMap<>();
    private final SequencingPolicy<? super EventMessage> sequencingPolicy;
    private final Set<ResetHandler> resetHandlers = ConcurrentHashMap.newKeySet();
    private final Set<ReplayStatusChangedHandler> replayStatusChangedHandlers = ConcurrentHashMap.newKeySet();

    /**
     * Instantiates a simple {@link EventHandlingComponent} that is able to handle events and delegate them to
     * subcomponents.
     * <p>
     * Uses a default sequencing policy that will first try for the {@link SequentialPerAggregatePolicy}, falling back
     * to the {@link SequentialPolicy} when the former returns no sequence value.
     * <p>
     * The {@code SequentialPerAggregatePolicy} half resolves a sequence identifier only on event stores that publish an
     * aggregate identifier alongside the event. On an event store speaking the Dynamic Consistency Boundary protocol it
     * resolves nothing, so the {@code SequentialPolicy} fallback answers for every event with a single constant
     * identifier: events are handled strictly one at a time, and all of them are routed to the one
     * {@link org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment} that identifier hashes
     * into. Use {@link #create(String, SequencingPolicy)} with a policy matching your application's ordering
     * requirement to process events concurrently on such a store.
     *
     * @param name The name of the component, used for {@link DescribableComponent describing} the component.
     * @return A simple {@link EventHandlingComponent} instance with the given {@code name}.
     */
    public static SimpleEventHandlingComponent create(String name) {
        return create(name, DEFAULT_SEQUENCING_POLICY);
    }

    /**
     * Instantiates a simple {@link EventHandlingComponent} that is able to handle events and delegate them to
     * subcomponents, using the given {@code sequencingPolicy} to decide how to sequence incoming events.
     *
     * @param name             The name of the component, used for {@link DescribableComponent describing} the
     *                         component.
     * @param sequencingPolicy The {@link SequencingPolicy} to use for sequencing events through, for example,
     *                         {@link #sequenceIdentifierFor(EventMessage, ProcessingContext)}.
     * @return A simple {@link EventHandlingComponent} instance with the given {@code name}.
     */
    public static SimpleEventHandlingComponent create(String name,
                                                      SequencingPolicy<? super EventMessage> sequencingPolicy) {
        return new SimpleEventHandlingComponent(name, sequencingPolicy);
    }

    protected SimpleEventHandlingComponent(String name,
                                           SequencingPolicy<? super EventMessage> sequencingPolicy) {
        this.name = Assert.nonEmpty(name, "The name may not be null or empty.");
        this.sequencingPolicy = Objects.requireNonNull(sequencingPolicy, "Sequencing Policy may not be null.");
    }

    @Override
    public MessageStream.Empty<Message> handle(EventMessage event,
                                               ProcessingContext context) {
        QualifiedName name = event.type().qualifiedName();
        List<EventHandler> handlers = eventHandlers.get(name);
        if (handlers == null || handlers.isEmpty()) {
            return MessageStream.failed(
                    new NoHandlerForEventException(name, SimpleEventHandlingComponent.class.getName())
            );
        }
        MessageStream<Message> result = MessageStream.empty();

        for (var handler : handlers) {
            var handlerResult = handler.handle(event, context);
            result = result.concatWith(handlerResult);
        }
        return result.ignoreEntries().cast();
    }

    @Override
    public SimpleEventHandlingComponent subscribe(Set<QualifiedName> names,
                                                  EventHandler handler) {
        Objects.requireNonNull(handler, "The given handler cannot be null.");
        names.forEach(name -> eventHandlers.compute(name, (q, handlers) -> {
            if (handlers == null) {
                handlers = new CopyOnWriteArrayList<>();
            }
            handlers.add(handler);
            return handlers;
        }));
        return this;
    }

    @Override
    public SimpleEventHandlingComponent subscribe(QualifiedName name,
                                                  EventHandler handler) {
        return subscribe(Set.of(name), handler);
    }

    @Override
    public Set<QualifiedName> supportedEvents() {
        return Set.copyOf(eventHandlers.keySet());
    }

    /**
     * {@inheritDoc}
     * <p>
     * The implementation automatically chooses the appropriate sequencing policy based on the event context:
     * <ul>
     *     <li>If the aggregate identifier is present in the {@link ProcessingContext} (i.e., the event is an aggregate
     *         event), it uses {@link SequentialPerAggregatePolicy} to ensure events for the same aggregate are
     *         processed sequentially while allowing concurrent processing of events from different aggregates.</li>
     *     <li>If no aggregate identifier is present (i.e., the event is not an aggregate event), it uses
     *         {@link SequentialPolicy} which ensures all events are processed sequentially (no concurrency) as the
     *         safest default option.</li>
     * </ul>
     * <p>
     * Override this method to provide custom sequencing behavior. Or use a
     * {@link SequenceOverridingEventHandlingComponent} if you cannot inherit from a certain
     * {@code EventHandlingComponent} implementation.
     * <p>
     */
    @SuppressWarnings("OptionalGetWithoutIsPresent")
    @Override
    public Object sequenceIdentifierFor(EventMessage event, ProcessingContext context) {
        var qualifiedName = event.type().qualifiedName();
        List<EventHandler> handlers = eventHandlers.get(qualifiedName);

        if (handlers == null || handlers.isEmpty()) {
            return sequencingPolicy.sequenceIdentifierFor(event, context).get();
        }

        return handlers.stream()
                       .filter(EventHandlingComponent.class::isInstance)
                       .map(EventHandlingComponent.class::cast)
                       .findFirst()
                       .map(component -> component.sequenceIdentifierFor(event, context))
                       .orElse(sequencingPolicy.sequenceIdentifierFor(event, context).get());
    }

    @Override
    public MessageStream.Empty<Message> handle(ResetContext resetContext, ProcessingContext context) {
        MessageStream<Message> result = MessageStream.empty();

        for (ResetHandler handler : resetHandlers) {
            MessageStream<Message> handlerResult = handler.handle(resetContext, context);
            result = result.concatWith(handlerResult);
        }

        return result.ignoreEntries().cast();
    }

    @Override
    public SimpleEventHandlingComponent subscribe(ResetHandler resetHandler) {
        resetHandlers.add(resetHandler);
        return this;
    }

    @Override
    public MessageStream.Empty<Message> handle(ReplayStatusChanged statusChange,
                                               ProcessingContext context) {
        MessageStream<Message> result = MessageStream.empty();

        for (ReplayStatusChangedHandler handler : replayStatusChangedHandlers) {
            MessageStream<Message> handlerResult = handler.handle(statusChange, context);
            result = result.concatWith(handlerResult);
        }

        return result.ignoreEntries().cast();
    }

    @Override
    public SimpleEventHandlingComponent subscribe(ReplayStatusChangedHandler replayStatusChangedHandler) {
        replayStatusChangedHandlers.add(replayStatusChangedHandler);
        return this;
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("name", name);
        descriptor.describeProperty("eventHandlers", eventHandlers);
        descriptor.describeProperty("resetHandlers", resetHandlers);
        descriptor.describeProperty("replayStatusChangedHandlers", replayStatusChangedHandlers);
        descriptor.describeProperty("sequencingPolicy", sequencingPolicy);
    }
}
