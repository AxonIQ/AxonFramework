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

import org.axonframework.messaging.core.Context.ResourceKey;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.Tag;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Functional interface towards resolving a {@link Set} of {@link Tag Tags} for a given {@link EventMessage}.
 *
 * @author Steven van Beelen
 * @since 5.0.0
 */
@FunctionalInterface
public interface TagResolver {

    /**
     * {@link ResourceKey} under which {@link #resolve(EventMessage, ProcessingContext)} caches resolved tags, keyed by
     * {@link EventMessage#identifier() event identifier}, for the lifetime of a {@link ProcessingContext}.
     */
    ResourceKey<Map<String, Set<Tag>>> RESOLVED_TAGS_CACHE_KEY = ResourceKey.withLabel("resolvedEventTags");

    /**
     * Resolves a {@link Set} of {@link Tag Tags} for the given {@code event}.
     *
     * @param event The event to resolve a {@link Set} of {@link Tag Tags} for.
     * @return A {@link Set} of {@link Tag Tags} for the given {@code event}.
     */
    Set<Tag> resolve(EventMessage event);

    /**
     * Resolves a {@link Set} of {@link Tag Tags} for the given {@code event}, caching the result in the given
     * {@code context} so repeated resolutions of the same event within one unit of work are computed only once.
     * <p>
     * The same event is frequently tagged more than once within a single {@link ProcessingContext} — for example while
     * appending an event and while filtering it against the {@link org.axonframework.messaging.eventstreaming.EventCriteria}
     * of each entity loaded in that unit of work. This default implementation caches per
     * {@link EventMessage#identifier() event identifier} to avoid re-resolving; implementations that are genuinely
     * context-aware may override it.
     *
     * @param event   The event to resolve a {@link Set} of {@link Tag Tags} for.
     * @param context The {@link ProcessingContext} used to cache resolved tags.
     * @return A {@link Set} of {@link Tag Tags} for the given {@code event}.
     */
    default Set<Tag> resolve(EventMessage event, ProcessingContext context) {
        return context
                .computeResourceIfAbsent(RESOLVED_TAGS_CACHE_KEY, ConcurrentHashMap::new)
                .computeIfAbsent(event.identifier(), id -> resolve(event));
    }
}
