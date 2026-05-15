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

package org.axonframework.messaging.core;

import org.jspecify.annotations.Nullable;


import java.util.Map;

/**
 * Abstract base class for {@link Message Messages}.
 *
 * @author Rene de Waele
 * @author Steven van Beelen
 * @since 3.0.0
 */
public abstract class AbstractMessage implements Message {

    private final String identifier;
    private final MessageType type;

    /**
     * Initializes a new {@link Message} with given {@code identifier} and {@code type}.
     *
     * @param identifier The identifier of this {@link Message}.
     * @param type       The {@link MessageType type} for this {@link Message}.
     */
    public AbstractMessage(String identifier,
                           MessageType type) {
        this.identifier = identifier;
        this.type = type;
    }

    @Override
    public String identifier() {
        return this.identifier;
    }

    @Override
    public MessageType type() {
        return this.type;
    }

    @Override
    public Message withMetadata(Map<String, String> metadata) {
        if (metadata().equals(metadata)) {
            return this;
        }
        return withMetadata(Metadata.from(metadata));
    }

    @Override
    public Message andMetadata(Map<String, @Nullable String> metadata) {
        if (metadata.isEmpty()) {
            return this;
        }
        return withMetadata(metadata().mergedWith(metadata));
    }

    /**
     * Returns a new message instance with the same payload and properties as this message but given {@code metadata}.
     *
     * @param metadata The metadata in the new message
     * @return a copy of this instance with given metadata
     */
    protected abstract Message withMetadata(Metadata metadata);
}
