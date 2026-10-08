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

package org.axonframework.modelling;

import org.axonframework.common.BuilderUtils;
import org.axonframework.conversion.Converter;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * An {@link EntityIdResolver} implementation that resolves the identifier from a {@link Message}'s
 * {@link org.axonframework.messaging.core.Metadata}.
 * <p>
 * Useful when the payload itself does not carry the identifier of the entity to target. For example, a message
 * translated from another model that only knows the target identifier out-of-band. The identifier is resolved by
 * looking up the given {@code key} in the message's metadata. If the {@code key} is absent, or maps to a blank value, a
 * {@link EntityIdResolutionException} is thrown.
 * <p>
 * Metadata values are always {@link String Strings}. The single-argument constructor enforces this: it assumes
 * {@code ID} to be {@link String} itself, returning the metadata value unconverted. To resolve an identifier of another
 * type, a {@code String}-converted identifier can be converted back to the required type. For that, use the constructor
 * taking a {@link Converter} and the target {@code idType}.
 *
 * @param <ID> the type of the identifier to resolve
 * @author Steven van Beelen
 * @see EntityIdResolver
 * @see FallbackEntityIdResolver
 * @since 5.4.0
 */
public class MetadataEntityIdResolver<ID> implements EntityIdResolver<ID> {

    private final String key;
    @Nullable
    private final Class<ID> idType;
    @Nullable
    private final Converter converter;

    /**
     * Initializes the resolver with the given {@code key}, enforcing {@code ID} to be {@link String}.
     *
     * @param key the name of the metadata entry to resolve the identifier from
     */
    public MetadataEntityIdResolver(String key) {
        BuilderUtils.assertNonEmpty(key, "The metadata key cannot be empty or null");
        this.key = key;
        this.idType = null;
        this.converter = null;
    }

    /**
     * Initializes the resolver with the given {@code key}, converting the resolved metadata value into the given
     * {@code idType} using the given {@code converter}.
     *
     * @param key       the name of the metadata entry to resolve the identifier from
     * @param idType    the type to convert the resolved metadata value into
     * @param converter the {@link Converter} to convert the resolved metadata value with
     */
    public MetadataEntityIdResolver(String key, Class<ID> idType, Converter converter) {
        BuilderUtils.assertNonEmpty(key, "The metadata key cannot be empty or null");
        this.key = key;
        this.idType = Objects.requireNonNull(idType, "The id type may not be null.");
        this.converter = Objects.requireNonNull(converter, "The Converter may not be null.");
    }

    @Override
    @SuppressWarnings("unchecked")
    public ID resolve(
            Message message,
            ProcessingContext context
    ) throws EntityIdResolutionException {
        String value = message.metadata().get(key);
        if (value == null || value.isBlank()) {
            throw new EntityIdResolutionException(message.payloadType(), List.of());
        }

        return converter == null
                ? (ID) value // Cast is still required to comply with API.
                : Objects.requireNonNull(converter.convert(value, idType), "The converted identifier cannot be null.");
    }
}
