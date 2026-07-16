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

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;

import java.util.Map;

/**
 * Internal hook for Axon Framework modules that ship their own {@link TrackingToken}, so they can register that token's
 * Axon Framework 4 to Axon Framework 5 class name mapping with {@link LegacyTokenTypes}. This keeps a token store
 * written by Axon Framework 4 readable after upgrading.
 * <p>
 * This is not an application extension point. It exists only so framework modules can add their own tokens to the
 * migration mapping. The token stores discover the implementations with {@link java.util.ServiceLoader}, registered in a
 * {@code META-INF/services/org.axonframework.messaging.eventhandling.processing.streaming.token.store.LegacyTokenTypeMapper}
 * file.
 * <p>
 * Marked {@link Internal} for the same reason as {@link LegacyTokenTypes}. It is a migration bridge, and the set of
 * mapped names may change between releases.
 *
 * @author Laura Devriendt
 * @since 5.2.0
 * @deprecated Temporary bridge for reading Axon Framework 4 token stores after upgrading. Scheduled for removal in
 * 5.5.0, once existing stores have migrated to the Axon Framework 5 token class names.
 */
@Internal
@Deprecated(since = "5.2.0", forRemoval = true)
public interface LegacyTokenTypeMapper {

    /**
     * Returns Axon Framework 4 token class names mapped to their current Axon Framework 5 classes, keyed by the fully
     * qualified class name as stored in the token type column. Must not return {@code null}. If a key is already mapped
     * by Axon Framework, the built-in mapping is kept and the contributed one is ignored.
     *
     * @return the Axon Framework 4 token class names mapped to their current Axon Framework 5 classes, never
     * {@code null}
     */
    Map<String, Class<? extends TrackingToken>> mappings();
}
