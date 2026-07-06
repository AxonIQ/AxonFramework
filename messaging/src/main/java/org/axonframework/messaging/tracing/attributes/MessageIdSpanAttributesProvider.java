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
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * Adds the {@link Message#identifier() message identifier} to the span. By default the attribute key is
 * {@link #DEFAULT_ATTRIBUTE_KEY} ({@code axoniq.message.id}); a different key can be supplied through the constructor -- for
 * example to keep the Axon Framework 4 key {@code axon_message_id}.
 *
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @since 4.6.0
 */
public final class MessageIdSpanAttributesProvider implements SpanAttributesProvider {

    /**
     * Default attribute key under which the message identifier is recorded.
     */
    public static final String DEFAULT_ATTRIBUTE_KEY = "axoniq.message.id";

    private final String attributeKey;

    /**
     * Creates a provider recording the message identifier under the default {@link #DEFAULT_ATTRIBUTE_KEY} key.
     */
    public MessageIdSpanAttributesProvider() {
        this(DEFAULT_ATTRIBUTE_KEY);
    }

    /**
     * Creates a provider recording the message identifier under the given {@code attributeKey}.
     *
     * @param attributeKey the span attribute key to record the message identifier under
     */
    public MessageIdSpanAttributesProvider(String attributeKey) {
        this.attributeKey = Objects.requireNonNull(attributeKey, "attributeKey may not be null");
    }

    @Override
    public Map<String, String> provideForMessage(Message message, @Nullable ProcessingContext context) {
        return Map.of(attributeKey, message.identifier());
    }
}
