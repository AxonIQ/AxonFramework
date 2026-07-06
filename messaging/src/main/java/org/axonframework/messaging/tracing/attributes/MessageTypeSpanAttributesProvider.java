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
 * Adds the message's {@link org.axonframework.messaging.core.MessageType type} (its qualified name plus version) to the
 * span. By default the attribute key is {@link #DEFAULT_ATTRIBUTE_KEY} ({@code axoniq.message.type}); a different key can be
 * supplied through the constructor -- for example to keep the Axon Framework 4 key {@code axon_message_type}.
 *
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @since 4.6.0
 */
public final class MessageTypeSpanAttributesProvider implements SpanAttributesProvider {

    /**
     * Default attribute key under which the message type is recorded.
     */
    public static final String DEFAULT_ATTRIBUTE_KEY = "axoniq.message.type";

    private final String attributeKey;

    /**
     * Creates a provider recording the message type under the default {@link #DEFAULT_ATTRIBUTE_KEY} key.
     */
    public MessageTypeSpanAttributesProvider() {
        this(DEFAULT_ATTRIBUTE_KEY);
    }

    /**
     * Creates a provider recording the message type under the given {@code attributeKey}.
     *
     * @param attributeKey the span attribute key to record the message type under
     */
    public MessageTypeSpanAttributesProvider(String attributeKey) {
        this.attributeKey = Objects.requireNonNull(attributeKey, "attributeKey may not be null");
    }

    @Override
    public Map<String, String> provideForMessage(Message message, @Nullable ProcessingContext context) {
        return Map.of(attributeKey, message.type().toString());
    }
}
