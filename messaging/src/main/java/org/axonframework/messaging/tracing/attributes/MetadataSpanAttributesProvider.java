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

package org.axonframework.messaging.tracing.attributes;

import org.axonframework.messaging.tracing.SpanAttributesProvider;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.interception.CorrelationDataInterceptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Adds message metadata entries to the span, each under the key {@code <prefix><metadataKey>}.
 * <p>
 * By default the prefix is {@link #METADATA_PREFIX} ({@code axoniq.metadata.}) and every metadata entry is added. The
 * prefix can be overridden through the constructor -- for example to keep the Axon Framework 4 prefix
 * {@code axon_metadata_}. An optional allowlist restricts which metadata keys are added; an empty allowlist means all
 * keys. Entries with a {@code null} value are skipped.
 * <p>
 * Besides the metadata already attached to the message, this provider also includes correlation data staged on the
 * {@link ProcessingContext} under {@link CorrelationDataInterceptor#CORRELATION_DATA}. Dispatch-side spans are created
 * by the outermost tracing decorators <em>before</em> the {@link CorrelationDataInterceptor} dispatch interceptor
 * merges that staged data onto the outgoing message, so reading the staged resource lets the span report the metadata
 * the message will effectively carry. Staged entries take precedence over message entries with the same key, mirroring
 * the interceptor's merge. The allowlist applies to staged entries as well, so disabling or restricting this provider
 * governs all metadata attributes in one place.
 *
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @since 4.6.0
 */
public final class MetadataSpanAttributesProvider implements SpanAttributesProvider {

    /**
     * Default prefix prepended to each metadata key to form the span attribute key.
     */
    public static final String METADATA_PREFIX = "axoniq.metadata.";

    private final String prefix;
    private final Set<String> allowedKeys;

    /**
     * Creates a provider that adds all metadata entries under the default {@link #METADATA_PREFIX} prefix.
     */
    public MetadataSpanAttributesProvider() {
        this(METADATA_PREFIX, Set.of());
    }

    /**
     * Creates a provider that adds the given metadata keys (when present) under the default {@link #METADATA_PREFIX}
     * prefix. An empty set means all keys.
     *
     * @param allowedKeys the metadata keys to add, or an empty set for all keys
     */
    public MetadataSpanAttributesProvider(Set<String> allowedKeys) {
        this(METADATA_PREFIX, allowedKeys);
    }

    /**
     * Creates a provider that adds the given metadata keys (when present) under the given {@code prefix}. An empty
     * allowlist means all keys.
     *
     * @param prefix      the prefix prepended to each metadata key to form the span attribute key
     * @param allowedKeys the metadata keys to add, or an empty set for all keys
     */
    public MetadataSpanAttributesProvider(String prefix, Set<String> allowedKeys) {
        this.prefix = Objects.requireNonNull(prefix, "prefix may not be null");
        this.allowedKeys = Set.copyOf(Objects.requireNonNull(allowedKeys, "allowedKeys may not be null"));
    }

    @Override
    public Map<String, String> provideForMessage(Message message, @Nullable ProcessingContext context) {
        Map<String, String> attributes = new HashMap<>();
        message.metadata().forEach((key, value) -> addAttribute(attributes, key, value));
        stagedCorrelationData(context).forEach((key, value) -> addAttribute(attributes, key, value));
        return attributes;
    }

    private void addAttribute(Map<String, String> attributes, String key, @Nullable String value) {
        if (value != null && (allowedKeys.isEmpty() || allowedKeys.contains(key))) {
            attributes.put(prefix + key, value);
        }
    }

    private static Map<String, String> stagedCorrelationData(@Nullable ProcessingContext context) {
        if (context == null || !context.containsResource(CorrelationDataInterceptor.CORRELATION_DATA)) {
            return Map.of();
        }
        return context.getResource(CorrelationDataInterceptor.CORRELATION_DATA);
    }
}
