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

package org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.Persistence;
import org.axonframework.conversion.ConversionException;
import org.axonframework.conversion.Converter;
import org.axonframework.conversion.TestConverter;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GapAwareTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.MergedTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.ConfigToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Validates that a JPA token store populated by Axon Framework 4 can be read by Axon Framework 5, which no longer has
 * the Axon Framework 4 token classes. Mirrors the JDBC
 * {@link org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.JdbcTokenEntry} coverage for
 * the JPA {@link TokenEntry}.
 * <p>
 * The serialized forms below are the exact bytes produced by Axon Framework 4's Jackson serializer (captured by running
 * it against Axon Framework 4.13), so these cases reflect what a migrated store actually contains rather than a
 * reconstruction. Each case exercises the production read path through {@link TokenEntry} on a row hydrated from the
 * database by the JPA provider, on both the Jackson 3 and the Jackson 2 converter.
 */
class JpaAxon4TokenCompatibilityTest {

    private static final String PROCESSOR_NAME = "my-processor";

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

    private final EntityManagerFactory entityManagerFactory = Persistence.createEntityManagerFactory("tokenstore");
    private final EntityManager entityManager = entityManagerFactory.createEntityManager();

    private EntityTransaction transaction;

    @BeforeEach
    void beginTransaction() {
        transaction = entityManager.getTransaction();
        transaction.begin();
    }

    @AfterEach
    void rollbackTransaction() {
        transaction.rollback();
        entityManager.close();
        entityManagerFactory.close();
    }

    /**
     * Persists a valid row, rewrites the token and type columns into the exact shape Axon 4 wrote, then reloads it. The
     * reload forces the JPA provider to hydrate the entry from the database, reproducing what an Axon 5 application sees
     * when it starts against an Axon 4 store. There is no constructor that sets an arbitrary type name, because a live
     * token always resolves its own class.
     */
    private TokenEntry axon4Row(String tokenJson, String axon4TokenType, Converter converter) {
        TokenEntry seed = new TokenEntry(PROCESSOR_NAME, Segment.ROOT_SEGMENT,
                                         new GlobalSequenceTrackingToken(0L), converter);
        entityManager.persist(seed);
        entityManager.flush();
        entityManager.createQuery("UPDATE TokenEntry t SET t.tokenType = :type, t.token = :bytes "
                                          + "WHERE t.processorName = :name AND t.segment = :segment")
                     .setParameter("type", axon4TokenType)
                     .setParameter("bytes", tokenJson.getBytes(StandardCharsets.UTF_8))
                     .setParameter("name", PROCESSOR_NAME)
                     .setParameter("segment", Segment.ROOT_SEGMENT.getSegmentId())
                     .executeUpdate();
        entityManager.clear();
        return entityManager.find(TokenEntry.class,
                                  new TokenEntry.PK(PROCESSOR_NAME, Segment.ROOT_SEGMENT.getSegmentId()));
    }

    @Nested
    class SimpleTokens {

        @ParameterizedTest
        @EnumSource(value = TestConverter.class, names = {"JACKSON", "JACKSON2"})
        void configTokenReadAtStartup(TestConverter testConverter) {
            // Every Axon 4 store has a config token row. Axon 5 reads it while starting up.
            Converter converter = testConverter.getConverter();
            TokenEntry entry = axon4Row(CONFIG_JSON, AXON_4_CONFIG, converter);

            assertThat(entry.getToken(converter)).isEqualTo(new ConfigToken(Map.of("id", "abc")));
        }

        @ParameterizedTest
        @EnumSource(value = TestConverter.class, names = {"JACKSON", "JACKSON2"})
        void globalSequenceTokenRead(TestConverter testConverter) {
            Converter converter = testConverter.getConverter();
            TokenEntry entry = axon4Row(GLOBAL_JSON, AXON_4_GLOBAL, converter);

            assertThat(entry.getToken(converter)).isEqualTo(new GlobalSequenceTrackingToken(5L));
        }

        @ParameterizedTest
        @EnumSource(value = TestConverter.class, names = {"JACKSON", "JACKSON2"})
        void gapAwareTokenRead(TestConverter testConverter) {
            Converter converter = testConverter.getConverter();
            TokenEntry entry = axon4Row(GAP_JSON, AXON_4_GAP_AWARE, converter);

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
            TokenEntry entry = axon4Row(MERGED_JSON, AXON_4_MERGED, converter);

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
            TokenEntry entry = axon4Row(GLOBAL_JSON, AXON_4_GLOBAL, converter);

            // Storing progress again writes the Axon 5 name, so later reads no longer need the mapping.
            TrackingToken loaded = entry.getToken(converter);
            entry.updateToken(loaded, converter);
            entityManager.flush();

            assertThat(storedTokenType()).isEqualTo(GlobalSequenceTrackingToken.class.getName());
            assertThat(entry.getToken(converter)).isEqualTo(new GlobalSequenceTrackingToken(5L));
        }

        private String storedTokenType() {
            return entityManager.createQuery("SELECT t.tokenType FROM TokenEntry t "
                                                     + "WHERE t.processorName = :name AND t.segment = :segment",
                                             String.class)
                                .setParameter("name", PROCESSOR_NAME)
                                .setParameter("segment", Segment.ROOT_SEGMENT.getSegmentId())
                                .getSingleResult();
        }
    }

    @Nested
    class Axon5TokensUnaffected {

        @ParameterizedTest
        @EnumSource(value = TestConverter.class, names = {"JACKSON", "JACKSON2"})
        void tokenStoredUnderAxon5NameStillRead(TestConverter testConverter) {
            Converter converter = testConverter.getConverter();
            GlobalSequenceTrackingToken original = new GlobalSequenceTrackingToken(5L);
            entityManager.persist(new TokenEntry(PROCESSOR_NAME, Segment.ROOT_SEGMENT, original, converter));
            entityManager.flush();
            entityManager.clear();

            TokenEntry entry = entityManager.find(TokenEntry.class,
                                                  new TokenEntry.PK(PROCESSOR_NAME,
                                                                    Segment.ROOT_SEGMENT.getSegmentId()));
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
            TokenEntry entry = axon4Row(xStreamXml, AXON_4_GLOBAL, converter);

            // The class name resolves here, so the failure comes from the unreadable content and not a missing class.
            assertThatThrownBy(() -> entry.getToken(converter))
                    .isInstanceOf(ConversionException.class);
        }

        @Test
        void replayTokenIsNotAutoMigrated() {
            // A ReplayToken changed shape between versions. Axon 4 named the field context, and Axon 5 renamed it to
            // resetContext and changed its type. So it is not mapped, and a replay must be completed or reset first.
            Converter converter = TestConverter.JACKSON.getConverter();
            TokenEntry entry = axon4Row(REPLAY_JSON, AXON_4_REPLAY, converter);

            assertThatThrownBy(() -> entry.getToken(converter))
                    .hasMessageContaining(AXON_4_REPLAY);
        }
    }
}
