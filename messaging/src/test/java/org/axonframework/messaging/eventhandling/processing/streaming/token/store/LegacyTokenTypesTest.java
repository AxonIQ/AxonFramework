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

import org.axonframework.messaging.eventhandling.processing.streaming.token.GapAwareTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the Axon Framework 4 to Axon Framework 5 token class name mapping used when reading a legacy token store.
 */
@SuppressWarnings("removal") // exercises the deprecated migration bridge on purpose
class LegacyTokenTypesTest {

    @Test
    void returnsAxon5ClassForKnownAxon4Name() {
        assertThat(LegacyTokenTypes.currentTypeFor("org.axonframework.eventhandling.GlobalSequenceTrackingToken"))
                .isEqualTo(GlobalSequenceTrackingToken.class);
    }

    @Test
    void returnsNullForAxon5Name() {
        assertThat(LegacyTokenTypes.currentTypeFor(GlobalSequenceTrackingToken.class.getName())).isNull();
    }

    @Test
    void returnsNullForUnknownName() {
        assertThat(LegacyTokenTypes.currentTypeFor("com.example.UnknownTrackingToken")).isNull();
    }

    @Test
    void doesNotMapReplayToken() {
        // A ReplayToken changed shape between versions, so it is not mapped. Such a token must be reset instead.
        assertThat(LegacyTokenTypes.currentTypeFor("org.axonframework.eventhandling.ReplayToken")).isNull();
    }

    @Test
    void resolvesMappingContributedThroughServiceLoader() {
        // StubLegacyTokenTypeMapper is registered through META-INF/services and contributes this mapping.
        assertThat(LegacyTokenTypes.currentTypeFor("com.example.LegacyCustomToken"))
                .isEqualTo(GlobalSequenceTrackingToken.class);
    }

    @Test
    void builtInMappingsTakePrecedenceOverServiceLoaderContributions() {
        // StubLegacyTokenTypeMapper also tries to remap a built-in name; the built-in mapping must win.
        assertThat(LegacyTokenTypes.currentTypeFor("org.axonframework.eventhandling.GapAwareTrackingToken"))
                .isEqualTo(GapAwareTrackingToken.class);
    }
}
