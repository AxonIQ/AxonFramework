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

package org.axonframework.messaging.eventhandling.processing.streaming.token.store;

import org.axonframework.common.ClassUtils;
import org.axonframework.common.annotation.Internal;
import org.axonframework.conversion.Converter;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GapAwareTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.MergedTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Maps Axon Framework 4 {@link TrackingToken} class names to their Axon Framework 5 counterparts, so a token store
 * written by Axon Framework 4 can be read after upgrading. Reading yields an Axon Framework 5 token, so the next save
 * stores the new name and the store migrates itself.
 * <p>
 * Modules that ship their own tracking token add its mapping through {@link LegacyTokenTypeMapper}, which is discovered
 * with {@link java.util.ServiceLoader}.
 * <p>
 * Marked {@link Internal} because it exists purely as a migration bridge for upgrading applications: it is invoked by
 * the token stores while reading, never by application code, and its mapping table is the only thing keeping an Axon
 * Framework 4 store readable. The set of mapped names may change between releases as tokens are added or as the
 * migration window for older stores closes, so callers must not depend on it.
 *
 * @author Laura Devriendt
 * @since 5.2.0
 * @deprecated Temporary bridge for reading Axon Framework 4 token stores after upgrading. Scheduled for removal in
 * 5.5.0, once existing stores have migrated to the Axon Framework 5 token class names.
 */
@Internal
@Deprecated(since = "5.2.0", forRemoval = true)
public final class LegacyTokenTypes {

    private static final Logger logger = LoggerFactory.getLogger(LegacyTokenTypes.class);

    private static final Map<String, Class<? extends TrackingToken>> AXON_4_TO_AXON_5 = loadMappings();

    private LegacyTokenTypes() {
    }

    private static Map<String, Class<? extends TrackingToken>> loadMappings() {
        Map<String, Class<? extends TrackingToken>> mappings = new HashMap<>();
        mappings.put("org.axonframework.eventhandling.GlobalSequenceTrackingToken", GlobalSequenceTrackingToken.class);
        mappings.put("org.axonframework.eventhandling.GapAwareTrackingToken", GapAwareTrackingToken.class);
        mappings.put("org.axonframework.eventhandling.MergedTrackingToken", MergedTrackingToken.class);
        mappings.put("org.axonframework.eventhandling.tokenstore.ConfigToken", ConfigToken.class);
        // Contributed mappings are added on top, but cannot override the built-in mappings above.
        ServiceLoader.load(LegacyTokenTypeMapper.class, LegacyTokenTypes.class.getClassLoader())
                     .forEach(mapper -> addContributedMappings(mappings, mapper));
        return Map.copyOf(mappings);
    }

    private static void addContributedMappings(Map<String, Class<? extends TrackingToken>> mappings,
                                               LegacyTokenTypeMapper mapper) {
        Objects.requireNonNull(mapper.mappings(), "A LegacyTokenTypeMapper must not return null mappings.")
               .forEach((tokenType, tokenClass) -> {
                   Class<? extends TrackingToken> builtIn = mappings.putIfAbsent(tokenType, tokenClass);
                   if (builtIn != null && !builtIn.equals(tokenClass)) {
                       logger.debug("Ignoring contributed token mapping for [{}]; the built-in mapping to [{}] "
                                            + "takes precedence.", tokenType, builtIn.getName());
                   }
               });
    }

    /**
     * Returns the Axon Framework 5 token class for the given Axon Framework 4 {@code tokenType}, or {@code null} if it
     * is not a known Axon Framework 4 token name.
     *
     * @param tokenType the token class name read from the store
     * @return the matching Axon Framework 5 token class, or {@code null} if unknown
     */
    @Nullable
    public static Class<? extends TrackingToken> currentTypeFor(String tokenType) {
        return AXON_4_TO_AXON_5.get(tokenType);
    }

    /**
     * Deserializes the given {@code token} bytes into a {@link TrackingToken}, resolving the target class from the
     * given {@code tokenType}. A known Axon Framework 4 token name is remapped to its Axon Framework 5 counterpart
     * before deserializing; any other name is loaded as-is.
     *
     * @param converter the converter to deserialize the token with
     * @param token     the serialized token bytes
     * @param tokenType the token class name read from the store
     * @return the deserialized tracking token
     * @since 5.2.0
     */
    public static TrackingToken deserialize(Converter converter, byte[] token, String tokenType) {
        Class<? extends TrackingToken> axon5Type = currentTypeFor(tokenType);
        if (axon5Type != null) {
            return converter.convert(token, axon5Type);
        }
        return converter.convert(token, ClassUtils.loadClass(tokenType));
    }
}
