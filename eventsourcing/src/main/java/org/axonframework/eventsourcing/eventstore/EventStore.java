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

package org.axonframework.eventsourcing.eventstore;

import org.axonframework.messaging.core.SubscribableEventSource;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;
import org.axonframework.messaging.eventstreaming.StreamableEventSource;

import java.util.List;

/**
 * Infrastructure component providing the means to start an {@link EventStoreTransaction} to
 * {@link EventStoreTransaction#appendEvent(EventMessage) append events} and
 * {@link EventStoreTransaction#source(SourcingCondition) event source} models from the underlying storage solution.
 * <p>
 * As an extension of the {@link EventBus}, this {@code EventStore} serves as both the event storage mechanism and
 * the event distribution mechanism. This dual role allows the EventStore to persist events durably while simultaneously
 * distributing them to subscribed event handlers, eliminating the need for a separate {@link EventBus} component in
 * event sourcing scenarios. Through the {@link SubscribableEventSource} capability inherited
 * from EventBus, components can {@link SubscribableEventSource#subscribe(java.util.function.BiFunction) subscribe}
 * to receive events as they are stored. The exact timing of when events are published to subscribers is
 * implementation-dependent and may occur within the same transaction if the {@link ProcessingContext} is shared between
 * the storage and distribution operations.
 * <p>
 * As an implementation of the {@link EventSink}, this {@code EventStore} will initiate a
 * {@link #transaction(ProcessingContext)} when {@link #publish(ProcessingContext, List)} is triggered to append events.
 * When a {@code null ProcessingContext} is given on {@link #publish(ProcessingContext, List)}, the implementation
 * should decide to construct a context itself or fail outright.
 * <p>
 * As an implementation of the {@link StreamableEventSource}, this {@code EventStore} will allow for {@link #open} a
 * stream of events and use it as a source for
 * {@link StreamingEventProcessor}.
 *
 * @author Allard Buijze
 * @author Rene de Waele
 * @author Steven van Beelen
 * @since 0.1.0
 */
public interface EventStore extends StreamableEventSource, EventBus {

    /**
     * Retrieves the {@link EventStoreTransaction transaction for appending events} for the given
     * {@code processingContext}. If no transaction is available, a new, empty transaction is created.
     *
     * @param processingContext The context for which to retrieve the {@link EventStoreTransaction}.
     * @return The {@link EventStoreTransaction}, existing or newly created, for the given {@code processingContext}.
     */
    EventStoreTransaction transaction(ProcessingContext processingContext);
}
