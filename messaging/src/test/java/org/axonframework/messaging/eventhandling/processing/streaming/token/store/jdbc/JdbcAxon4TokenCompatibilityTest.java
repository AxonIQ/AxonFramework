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

package org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc;

import org.axonframework.conversion.ConversionException;
import org.axonframework.conversion.Converter;
import org.axonframework.conversion.TestConverter;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GapAwareTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.MergedTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.ConfigToken;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates that a token store populated by Axon Framework 4 can be read by Axon Framework 5, which no longer has the
 * Axon Framework 4 token classes.
 * <p>
 * The serialized forms below are the exact bytes produced by Axon Framework 4's Jackson serializer (captured by running
 * it against Axon Framework 4.13), so these cases reflect what a migrated store actually contains rather than a
 * reconstruction. Each case exercises the production read path through {@link JdbcTokenEntry}, on both the Jackson 3 and
 * the Jackson 2 converter.
 */
class JdbcAxon4TokenCompatibilityTest {

    private static final String AXON_4_GLOBAL = "org.axonframework.eventhandling.GlobalSequenceTrackingToken";
    private static final String AXON_4_GAP_AWARE = "org.axonframework.eventhandling.GapAwareTrackingToken";
    private static final String AXON_4_MERGED = "org.axonframework.eventhandling.MergedTrackingToken";
    private static final String AXON_4_REPLAY = "org.axonframework.eventhandling.ReplayToken";
    private static final String AXON_4_CONFIG = "org.axonframework.eventhandling.tokenstore.ConfigToken";

    // These are the exact bytes Axon 4 writes with Jackson. See the class javadoc.
    private static final String GLOBAL_JSON = "{\"globalIndex\":5}";
    private static final String GAP_JSON = "{\"index\":10,\"gaps\":[3,7]}";
    private static final String MERGED_JSON =
            "{\"lowerSegmentToken\":{\"@c\":\".GlobalSequenceTrackingToken\",\"globalIndex\":3},"
                    + "\"upperSegmentToken\":{\"@c\":\".GlobalSequenceTrackingToken\",\"globalIndex\":4}}";
    private static final String REPLAY_JSON =
            "{\"tokenAtReset\":{\"@class\":\"" + AXON_4_GLOBAL + "\",\"globalIndex\":10},"
                    + "\"currentToken\":{\"@class\":\"" + AXON_4_GLOBAL + "\",\"globalIndex\":5},"
                    + "\"context\":null}";
    private static final String CONFIG_JSON = "{\"config\":{\"id\":\"abc\"}}";

    private static JdbcTokenEntry axon4Row(String tokenJson, String axon4TokenType) {
        return new JdbcTokenEntry(tokenJson.getBytes(StandardCharsets.UTF_8), axon4TokenType,
                                  "2020-01-01T00:00:00.000Z", "owner", "my-processor", new Segment(0, 0));
    }

    @Nested
    class SimpleTokens {

        @ParameterizedTest
        @EnumSource(value = TestConverter.class, names = {"JACKSON", "JACKSON2"})
        void configTokenReadAtStartup(TestConverter testConverter) {
            // Every Axon 4 store has a config token row. Axon 5 reads it while starting up.
            Converter converter = testConverter.getConverter();
            JdbcTokenEntry entry = axon4Row(CONFIG_JSON, AXON_4_CONFIG);

            assertThat(entry.getToken(converter)).isEqualTo(new ConfigToken(Map.of("id", "abc")));
        }

        @ParameterizedTest
        @EnumSource(value = TestConverter.class, names = {"JACKSON", "JACKSON2"})
        void globalSequenceTokenRead(TestConverter testConverter) {
            Converter converter = testConverter.getConverter();
            JdbcTokenEntry entry = axon4Row(GLOBAL_JSON, AXON_4_GLOBAL);

            assertThat(entry.getToken(converter)).isEqualTo(new GlobalSequenceTrackingToken(5L));
        }

        @ParameterizedTest
        @EnumSource(value = TestConverter.class, names = {"JACKSON", "JACKSON2"})
        void gapAwareTokenRead(TestConverter testConverter) {
            Converter converter = testConverter.getConverter();
            JdbcTokenEntry entry = axon4Row(GAP_JSON, AXON_4_GAP_AWARE);

            assertThat(entry.getToken(converter)).isEqualTo(GapAwareTrackingToken.newInstance(10L, List.of(3L, 7L)));
        }
    }

    @Nested
    class WrappingTokens {

        @ParameterizedTest
        @EnumSource(value = TestConverter.class, names = {"JACKSON", "JACKSON2"})
        void mergedTokenRead(TestConverter testConverter) {
            // A MergedTrackingToken stores its wrapped tokens with a package relative type name.
            // Remapping the outer type is enough for the wrapped tokens to resolve.
            Converter converter = testConverter.getConverter();
            JdbcTokenEntry entry = axon4Row(MERGED_JSON, AXON_4_MERGED);

            assertThat(entry.getToken(converter)).isEqualTo(new MergedTrackingToken(new GlobalSequenceTrackingToken(3L),
                                                                                    new GlobalSequenceTrackingToken(4L)));
        }
    }

    @Nested
    class SelfHealing {

        @ParameterizedTest
        @EnumSource(value = TestConverter.class, names = {"JACKSON", "JACKSON2"})
        void tokenReadFromAxon4RowIsResavedUnderAxon5Name(TestConverter testConverter) {
            Converter converter = testConverter.getConverter();
            JdbcTokenEntry axon4Entry = axon4Row(GLOBAL_JSON, AXON_4_GLOBAL);

            TrackingToken loaded = axon4Entry.getToken(converter);
            JdbcTokenEntry resaved = new JdbcTokenEntry(loaded, converter);

            // Saving the token again writes the Axon 5 name, so later reads no longer need the mapping.
            assertThat(resaved.getTokenType()).isEqualTo(GlobalSequenceTrackingToken.class.getName());
            assertThat(resaved.getToken(converter)).isEqualTo(new GlobalSequenceTrackingToken(5L));
        }
    }

    @Nested
    class Axon5TokensUnaffected {

        @ParameterizedTest
        @EnumSource(value = TestConverter.class, names = {"JACKSON", "JACKSON2"})
        void tokenStoredUnderAxon5NameStillRead(TestConverter testConverter) {
            Converter converter = testConverter.getConverter();
            GlobalSequenceTrackingToken original = new GlobalSequenceTrackingToken(5L);
            JdbcTokenEntry entry = new JdbcTokenEntry(converter.convert(original, byte[].class),
                                                      GlobalSequenceTrackingToken.class.getName(),
                                                      "2020-01-01T00:00:00.000Z", "owner", "my-processor",
                                                      new Segment(0, 0));

            assertThat(entry.getToken(converter)).isEqualTo(original);
        }
    }

    @Nested
    class DocumentedLimitations {

        @Test
        void xStreamSerializedTokenCannotBeRead() {
            // Axon 4 used XStream by default, which wrote XML. Axon 5 has no XStream converter, so it cannot
            // read that content even when the class name maps.
            Converter converter = TestConverter.JACKSON.getConverter();
            String xStreamXml = "<org.axonframework.eventhandling.GlobalSequenceTrackingToken>"
                    + "<globalIndex>5</globalIndex>"
                    + "</org.axonframework.eventhandling.GlobalSequenceTrackingToken>";
            JdbcTokenEntry entry = axon4Row(xStreamXml, AXON_4_GLOBAL);

            // The class name resolves here, so the failure comes from the unreadable content and not a missing class.
            assertThatThrownBy(() -> entry.getToken(converter))
                    .isInstanceOf(ConversionException.class);
        }

        @Test
        void replayTokenIsNotAutoMigrated() {
            // A ReplayToken changed shape between versions. Axon 4 named the field context, and Axon 5 renamed it to
            // resetContext and changed its type. So it is not mapped, and a replay must be completed or reset first.
            Converter converter = TestConverter.JACKSON.getConverter();
            JdbcTokenEntry entry = axon4Row(REPLAY_JSON, AXON_4_REPLAY);

            assertThatThrownBy(() -> entry.getToken(converter))
                    .hasMessageContaining(AXON_4_REPLAY);
        }
    }
}
