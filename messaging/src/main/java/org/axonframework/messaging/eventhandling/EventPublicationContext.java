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

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Identifies events delivered to subscribers on a branch of the {@link ProcessingContext} in which they were
 * published.
 * <p>
 * Processor type cannot provide this information: a subscribing processor may receive events in the publisher's
 * context from an event bus, or in a context owned by another source such as a persistent stream. Event buses that
 * reuse the publisher's context mark the delivery branch through {@link #forPublishedEvents(ProcessingContext, List)}.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
public final class EventPublicationContext {

    private static final Context.ResourceKey<Set<String>> PUBLISHED_EVENT_IDENTIFIERS =
            Context.ResourceKey.withLabel("org.axonframework.messaging.eventhandling.publishedEventIdentifiers");

    private EventPublicationContext() {
    }

    /**
     * Returns a branch of the given {@code context} identifying the events delivered in their publication context.
     * Event sources that created a separate processing context must not call this method.
     *
     * @param context the publication context, or {@code null} when publication has no context
     * @param events  the events delivered to subscribers
     * @return the marked context branch, or {@code null} when {@code context} is {@code null}
     */
    public static @Nullable ProcessingContext forPublishedEvents(
            @Nullable ProcessingContext context,
            List<? extends EventMessage> events
    ) {
        Objects.requireNonNull(events, "events may not be null");
        if (context == null) {
            return null;
        }
        Set<String> identifiers = events.stream()
                                        .map(EventMessage::identifier)
                                        .collect(Collectors.toUnmodifiableSet());
        return context.withResource(PUBLISHED_EVENT_IDENTIFIERS, identifiers);
    }

    /**
     * Returns whether the given {@code event} is being handled in the context in which it was published.
     *
     * @param event   the event being handled
     * @param context the event-handling context
     * @return {@code true} when this context branch represents delivery from the event's publisher
     */
    public static boolean isPublicationContextFor(EventMessage event, ProcessingContext context) {
        Set<String> identifiers = context.getResource(PUBLISHED_EVENT_IDENTIFIERS);
        return identifiers != null && identifiers.contains(event.identifier());
    }
}
