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

import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.jspecify.annotations.NullMarked;

import java.util.Map;

/**
 * Test {@link LegacyTokenTypeMapper} registered through {@code META-INF/services} to verify that additional mappings
 * are picked up by {@link LegacyTokenTypes}.
 */
@NullMarked
@SuppressWarnings("removal") // implements the deprecated migration SPI for test purposes
public class StubLegacyTokenTypeMapper implements LegacyTokenTypeMapper {

    @Override
    public Map<String, Class<? extends TrackingToken>> mappings() {
        return Map.of(
                "com.example.LegacyCustomToken", GlobalSequenceTrackingToken.class,
                // Deliberately attempts to override a built-in mapping; LegacyTokenTypes must ignore this.
                "org.axonframework.eventhandling.GapAwareTrackingToken", GlobalSequenceTrackingToken.class
        );
    }
}
