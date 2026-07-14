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

package org.axonframework.eventsourcing.eventstore.tracing;

import org.axonframework.messaging.tracing.SpanAttributesProvider;
import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.Tag;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Adds an {@link EventMessage}'s {@link Tag Tags} to the span, each under the key {@code <prefix><tagKey>}. Tags are
 * resolved through a {@link TagResolver}.
 * <p>
 * <b>Best-effort.</b> Tags are resolved from the <em>current</em> payload-class declaration, not from what was
 * physically stored alongside the event. A resolved value can therefore be absent or differ from the stored tag - for
 * example after the payload class evolved or tags reassigned.
 * <p>
 * By default the prefix is {@link #EVENT_TAG_PREFIX} ({@code axoniq.event_tag.}) and every resolved tag is added. The
 * prefix can be overridden through the constructor. An optional allowlist restricts which tag keys are added; an empty
 * allowlist means all keys. Messages that are not {@link EventMessage EventMessages} contribute nothing.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
public final class EventTagsSpanAttributesProvider implements SpanAttributesProvider {

    /**
     * Default prefix prepended to each tag key to form the span attribute key.
     */
    public static final String EVENT_TAG_PREFIX = "axoniq.event_tag.";

    private final TagResolver tagResolver;
    private final String prefix;
    private final Set<String> allowedKeys;

    /**
     * Creates a provider that adds all resolved tags under the default {@link #EVENT_TAG_PREFIX} prefix.
     *
     * @param tagResolver the {@link TagResolver} used to resolve an event's tags
     */
    public EventTagsSpanAttributesProvider(TagResolver tagResolver) {
        this(tagResolver, EVENT_TAG_PREFIX, Set.of());
    }

    /**
     * Creates a provider that adds the given tag keys (when resolved) under the default {@link #EVENT_TAG_PREFIX}
     * prefix. An empty set means all keys.
     *
     * @param tagResolver the {@link TagResolver} used to resolve an event's tags
     * @param allowedKeys the tag keys to add, or an empty set for all keys
     */
    public EventTagsSpanAttributesProvider(TagResolver tagResolver, Set<String> allowedKeys) {
        this(tagResolver, EVENT_TAG_PREFIX, allowedKeys);
    }

    /**
     * Creates a provider that adds the given tag keys (when resolved) under the given {@code prefix}. An empty
     * allowlist means all keys.
     *
     * @param tagResolver the {@link TagResolver} used to resolve an event's tags
     * @param prefix      the prefix prepended to each tag key to form the span attribute key
     * @param allowedKeys the tag keys to add, or an empty set for all keys
     */
    public EventTagsSpanAttributesProvider(TagResolver tagResolver, String prefix, Set<String> allowedKeys) {
        this.tagResolver = Objects.requireNonNull(tagResolver, "tagResolver may not be null");
        this.prefix = Objects.requireNonNull(prefix, "prefix may not be null");
        this.allowedKeys = Set.copyOf(Objects.requireNonNull(allowedKeys, "allowedKeys may not be null"));
    }

    @Override
    public Map<String, String> provideForMessage(Message message, @Nullable ProcessingContext context) {
        if (!(message instanceof EventMessage event)) {
            return Map.of();
        }
        Map<String, String> attributes = null;
        for (var tag : tagResolver.resolve(event)) {
            if (allowedKeys.isEmpty() || allowedKeys.contains(tag.key())) {
                if (attributes == null) {
                    attributes = new HashMap<>();
                }
                attributes.put(prefix + tag.key(), tag.value());
            }
        }
        return attributes == null ? Map.of() : attributes;
    }
}
