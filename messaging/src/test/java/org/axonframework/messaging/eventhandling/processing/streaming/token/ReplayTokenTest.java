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

package org.axonframework.messaging.eventhandling.processing.streaming.token;

import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Collections;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.axonframework.conversion.PassThroughConverter;
import org.junit.jupiter.api.*;

/**
 * Test class validating the {@link ReplayToken}.
 *
 * @author Allard Buijze
 */
class ReplayTokenTest {

    private TrackingToken innerToken;

    @BeforeEach
    void setUp() {
        innerToken = GapAwareTrackingToken.newInstance(10, Collections.singleton(9L));
    }

    @Test
    void advanceReplayTokenWithinReplaySegment() {
        ReplayToken testSubject = (ReplayToken) ReplayToken.createReplayToken(innerToken);
        TrackingToken actual = testSubject.advancedTo(GapAwareTrackingToken.newInstance(8, emptySet()));
        assertInstanceOf(ReplayToken.class, actual);
        assertTrue(ReplayToken.isReplay(actual));
    }

    @Test
    void regularTokenIsProvidedWhenResetBeyondCurrentPosition() {
        TrackingToken token1 = new GlobalSequenceTrackingToken(1);
        TrackingToken token2 = new GlobalSequenceTrackingToken(2);

        TrackingToken actual = ReplayToken.createReplayToken(token1, token2);
        assertSame(token2, actual);
    }

    @Test
    void replayTokenIsProvidedWhenResetAtCurrentPosition() {
        TrackingToken token1 = new GlobalSequenceTrackingToken(2);
        TrackingToken token2 = new GlobalSequenceTrackingToken(2);

        TrackingToken actual = ReplayToken.createReplayToken(token1, token2);
        assertInstanceOf(ReplayToken.class, actual);
        assertTrue(ReplayToken.isReplay(actual));
    }

    @Test
    void serializationDeserialization() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        TrackingToken replayToken = ReplayToken.createReplayToken(innerToken);
        String serializedReplayToken = objectMapper.writer().writeValueAsString(replayToken);
        ReplayToken deserializedReplayToken = objectMapper.readerFor(ReplayToken.class)
                                                          .readValue(serializedReplayToken);
        assertEquals(replayToken, deserializedReplayToken);
    }

    @Test
    void position() {
        GapAwareTrackingToken startPosition = GapAwareTrackingToken.newInstance(11L, Collections.singleton(9L));

        TrackingToken replayToken = ReplayToken.createReplayToken(innerToken, startPosition);

        assertTrue(replayToken.position().isPresent());
        assertEquals(11L, replayToken.position().getAsLong());
    }

    @Test
    void positionIsZeroAtReset() {
        TrackingToken replayToken = ReplayToken.createReplayToken(innerToken);
        assertEquals(0, replayToken.position().orElse(-1));
    }

    @Test
    void getTokenAtReset() {
        ReplayToken testSubject = (ReplayToken) ReplayToken.createReplayToken(innerToken);
        TrackingToken actual = testSubject.advancedTo(GapAwareTrackingToken.newInstance(6, emptySet()));
        assertInstanceOf(ReplayToken.class, actual);
        assertEquals(testSubject.getTokenAtReset(), innerToken);
    }

    @Test
    void replayContextIsResolvedWhileReplaying() {
        byte[] context = "context".getBytes();
        TrackingToken tokenAtReset = new GlobalSequenceTrackingToken(10);
        TrackingToken startPosition = new GlobalSequenceTrackingToken(2);

        TrackingToken replayToken = ReplayToken.createReplayToken(tokenAtReset, startPosition, context);

        assertTrue(ReplayToken.isReplay(replayToken), "Precondition: token should still be replaying");
        assertThat(ReplayToken.replayContext(replayToken, byte[].class, PassThroughConverter.INSTANCE))
                .containsSame(context);
    }

    @Test
    void replayContextIsNotResolvedForNonReplayEventWhileTokenIsStillWrapped() {
        // A gap in the current token that the reset token doesn't share makes the freshly delivered event a "new"
        // event that does not strictly pass the reset point. The ReplayToken therefore keeps its wrapper (and context)
        // while reporting lastMessageWasReplay=false. Such live events must not resolve the reset context.
        byte[] context = "context".getBytes();
        TrackingToken tokenAtReset = GapAwareTrackingToken.newInstance(10, Collections.singleton(8L));
        TrackingToken startPosition = GapAwareTrackingToken.newInstance(8, emptySet());

        TrackingToken replayToken = ReplayToken.createReplayToken(tokenAtReset, startPosition, context);

        assertInstanceOf(ReplayToken.class, replayToken, "Precondition: token should still be wrapped");
        assertFalse(ReplayToken.isReplay(replayToken), "Precondition: token should not be replaying");
        assertThat(ReplayToken.replayContext(replayToken, byte[].class, PassThroughConverter.INSTANCE))
                .as("Context must not be resolved once the token is no longer replaying")
                .isEmpty();
    }

    @Test
    void getTokenAtResetIsEmptyForNonReplayEventWhileTokenIsStillWrapped() {
        // Same still-wrapped-but-not-replaying scenario: getResetPosition must report empty, matching its documented
        // contract ("In case a replay finished or no replay is active, an OptionalLong.empty()").
        byte[] context = "context".getBytes();
        TrackingToken tokenAtReset = GapAwareTrackingToken.newInstance(10, Collections.singleton(8L));
        TrackingToken startPosition = GapAwareTrackingToken.newInstance(8, emptySet());

        TrackingToken replayToken = ReplayToken.createReplayToken(tokenAtReset, startPosition, context);

        assertInstanceOf(ReplayToken.class, replayToken, "Precondition: token should still be wrapped");
        assertFalse(ReplayToken.isReplay(replayToken), "Precondition: token should not be replaying");
        assertThat(ReplayToken.getTokenAtReset(replayToken))
                .as("Reset position must not be reported once the token is no longer replaying")
                .isEmpty();
    }

    @Test
    void createReplayTokenReturnsStartPositionIfTokenAtResetIsNull() {
        TrackingToken tokenAtReset = null;
        TrackingToken startPosition = new GlobalSequenceTrackingToken(1);

        //noinspection ConstantConditions
        TrackingToken result = ReplayToken.createReplayToken(tokenAtReset, startPosition);

        assertEquals(startPosition, result);
    }

    @Test
    void createReplayTokenReturnsStartPositionIfStartPositionCoversTokenAtReset() {
        TrackingToken tokenAtReset = new GlobalSequenceTrackingToken(1);
        TrackingToken startPosition = new GlobalSequenceTrackingToken(2);

        TrackingToken result = ReplayToken.createReplayToken(tokenAtReset, startPosition);

        assertEquals(startPosition, result);
    }

    @Test
    void createReplayTokenReturnsWrappedReplayTokenIfTokenAtResetIsReplayToken() {
        TrackingToken tokenAtReset = ReplayToken.createReplayToken(new GlobalSequenceTrackingToken(1));
        TrackingToken startPosition = new GlobalSequenceTrackingToken(2);

        TrackingToken result = ReplayToken.createReplayToken(tokenAtReset, startPosition);

        assertEquals(startPosition, result);
    }

    @Test
    void createReplayTokenReturnsReplayToken() {
        TrackingToken tokenAtReset = new GlobalSequenceTrackingToken(2);
        TrackingToken startPosition = new GlobalSequenceTrackingToken(1);

        TrackingToken result = ReplayToken.createReplayToken(tokenAtReset, startPosition);

        assertInstanceOf(ReplayToken.class, result);
        assertEquals(tokenAtReset, ((ReplayToken) result).getTokenAtReset());
        assertEquals(startPosition, ((ReplayToken) result).getCurrentToken());
    }

    /**
     * Tests for {@link ReplayToken#advancedTo(TrackingToken)} with {@link GlobalSequenceTrackingToken}. These tests
     * verify replay detection behavior with simple sequential tokens.
     */
    @Nested
    class AdvancedToWithGlobalSequenceTrackingToken {

        @Test
        void eventBeforeResetPositionIsReplay() {
            // tokenAtReset at index 7 - processor saw events 0-7 before reset
            TrackingToken tokenAtReset = new GlobalSequenceTrackingToken(7);
            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, null);

            // Event at index 5 - clearly before reset position
            TrackingToken newToken = new GlobalSequenceTrackingToken(5);
            TrackingToken result = replayToken.advancedTo(newToken);

            assertInstanceOf(ReplayToken.class, result);
            assertTrue(ReplayToken.isReplay(result), "Event before reset position should be a replay");
        }

        @Test
        void eventAtExactResetPositionIsReplay() {
            // tokenAtReset at index 7
            TrackingToken tokenAtReset = new GlobalSequenceTrackingToken(7);
            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, null);

            // Advance to position 6 first
            replayToken = (ReplayToken) replayToken.advancedTo(new GlobalSequenceTrackingToken(6));

            // Event at index 7 - exactly at reset position (was already processed before reset)
            TrackingToken newToken = new GlobalSequenceTrackingToken(7);
            TrackingToken result = replayToken.advancedTo(newToken);

            assertInstanceOf(ReplayToken.class, result);
            assertTrue(ReplayToken.isReplay(result), "Event at exact reset position should be a replay");
        }

        @Test
        void eventAfterResetPositionEndsReplay() {
            // tokenAtReset at index 7
            TrackingToken tokenAtReset = new GlobalSequenceTrackingToken(7);
            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, null);

            // Event at index 8 - after reset position (new event, never seen before)
            TrackingToken newToken = new GlobalSequenceTrackingToken(8);
            TrackingToken result = replayToken.advancedTo(newToken);

            // Should exit replay mode entirely
            assertInstanceOf(GlobalSequenceTrackingToken.class, result);
            assertFalse(ReplayToken.isReplay(result), "Event after reset position should not be a replay");
        }

        @Test
        void replayProgressesThroughMultipleEvents() {
            TrackingToken tokenAtReset = new GlobalSequenceTrackingToken(5);
            TrackingToken currentToken = ReplayToken.createReplayToken(tokenAtReset, null);

            // Process events 0 through 5 - all should be replays
            for (int i = 0; i <= 5; i++) {
                currentToken = ((ReplayToken) currentToken).advancedTo(new GlobalSequenceTrackingToken(i));
                assertInstanceOf(ReplayToken.class, currentToken);
                assertTrue(ReplayToken.isReplay(currentToken), "Event " + i + " should be a replay");
            }

            // Event 6 should end the replay
            currentToken = ((ReplayToken) currentToken).advancedTo(new GlobalSequenceTrackingToken(6));
            assertFalse(ReplayToken.isReplay(currentToken), "Event 6 should not be a replay");
        }

        @Test
        void advancedToShouldKeepReplayWhenTokenReachesSameIndex() {
            GlobalSequenceTrackingToken tokenAtReset = new GlobalSequenceTrackingToken(6);

            TrackingToken replayToken = ReplayToken.createReplayToken(tokenAtReset, null);

            // Advance to the same index as tokenAtReset
            GlobalSequenceTrackingToken newToken = new GlobalSequenceTrackingToken(6);
            TrackingToken advancedToken = ((ReplayToken) replayToken).advancedTo(newToken);

            assertTrue(ReplayToken.isReplay(advancedToken),
                       "Should have remained in replay mode since newToken (index 6) does not have tokenAtReset beyond its position");
        }
    }

    @Nested
    class SamePositionAs {

        @Test
        void samePositionAsWithReplayTokenComparesCurrentTokens() {
            TrackingToken tokenAtReset = new GlobalSequenceTrackingToken(10);
            TrackingToken currentToken1 = new GlobalSequenceTrackingToken(5);
            TrackingToken currentToken2 = new GlobalSequenceTrackingToken(5);

            ReplayToken replayToken1 = new ReplayToken(tokenAtReset, currentToken1, new byte[0]);
            ReplayToken replayToken2 = new ReplayToken(tokenAtReset, currentToken2, new byte[0]);

            assertTrue(replayToken1.samePositionAs(replayToken2));
            assertTrue(replayToken2.samePositionAs(replayToken1));
        }

        @Test
        void samePositionAsWithReplayTokenReturnsFalseWhenCurrentTokensDiffer() {
            TrackingToken tokenAtReset = new GlobalSequenceTrackingToken(10);
            TrackingToken currentToken1 = new GlobalSequenceTrackingToken(5);
            TrackingToken currentToken2 = new GlobalSequenceTrackingToken(6);

            ReplayToken replayToken1 = new ReplayToken(tokenAtReset, currentToken1, new byte[0]);
            ReplayToken replayToken2 = new ReplayToken(tokenAtReset, currentToken2, new byte[0]);

            assertFalse(replayToken1.samePositionAs(replayToken2));
            assertFalse(replayToken2.samePositionAs(replayToken1));
        }

        @Test
        void samePositionAsWithNonReplayTokenDelegatesToCurrentToken() {
            TrackingToken tokenAtReset = new GlobalSequenceTrackingToken(10);
            TrackingToken currentToken = new GlobalSequenceTrackingToken(5);
            TrackingToken otherToken = new GlobalSequenceTrackingToken(5);

            ReplayToken replayToken = new ReplayToken(tokenAtReset, currentToken, new byte[0]);

            assertTrue(replayToken.samePositionAs(otherToken));
        }

        @Test
        void samePositionAsWithNonReplayTokenReturnsFalseWhenDifferent() {
            TrackingToken tokenAtReset = new GlobalSequenceTrackingToken(10);
            TrackingToken currentToken = new GlobalSequenceTrackingToken(5);
            TrackingToken otherToken = new GlobalSequenceTrackingToken(6);

            ReplayToken replayToken = new ReplayToken(tokenAtReset, currentToken, new byte[0]);

            assertFalse(replayToken.samePositionAs(otherToken));
        }

        @Test
        void samePositionAsWithNullCurrentTokenReturnsFalse() {
            TrackingToken tokenAtReset = new GlobalSequenceTrackingToken(10);
            ReplayToken replayToken = new ReplayToken(tokenAtReset, null, new byte[0]);

            TrackingToken otherToken = new GlobalSequenceTrackingToken(5);

            assertFalse(replayToken.samePositionAs(otherToken));
        }

        @Test
        void samePositionAsWithBothNullCurrentTokens() {
            TrackingToken tokenAtReset = new GlobalSequenceTrackingToken(10);
            ReplayToken replayToken1 = new ReplayToken(tokenAtReset, null, new byte[0]);
            ReplayToken replayToken2 = new ReplayToken(tokenAtReset, null, new byte[0]);

            // When both have null currentToken, samePositionAs() returns false
            // because currentToken != null check fails
            assertFalse(replayToken1.samePositionAs(replayToken2));
        }

        @Test
        void samePositionAsWithGapAwareTrackingToken() {
            TrackingToken tokenAtReset = GapAwareTrackingToken.newInstance(10, emptySet());
            TrackingToken currentToken = GapAwareTrackingToken.newInstance(5, emptySet());
            TrackingToken sameCurrentToken = GapAwareTrackingToken.newInstance(5, emptySet());

            ReplayToken replayToken = new ReplayToken(tokenAtReset, currentToken, new byte[0]);

            assertTrue(replayToken.samePositionAs(sameCurrentToken));
        }
    }

    @Nested
    class ConcludesReplay {

        @Test
        void returnsFalseWhenCurrentTokenIsAtBeforeTokenAtReset() {
            TrackingToken tokenAtReset = new GlobalSequenceTrackingToken(5);
            TrackingToken currentToken = new GlobalSequenceTrackingToken(2);
            TrackingToken replayToken = new ReplayToken(tokenAtReset, currentToken, new byte[0]);

            assertThat(ReplayToken.concludesReplay(replayToken)).isFalse();
        }

        @Test
        void returnsTrueWhenCurrentTokenIsAtTheSamePositionTokenAtReset() {
            TrackingToken tokenAtReset = new GlobalSequenceTrackingToken(5);
            TrackingToken currentToken = new GlobalSequenceTrackingToken(5);
            TrackingToken replayToken = new ReplayToken(tokenAtReset, currentToken, new byte[0]);

            assertThat(ReplayToken.concludesReplay(replayToken)).isTrue();
        }

        @Test
        void returnsTrueWhenCurrentTokenFarSurpassedTheTokenAtReset() {
            TrackingToken tokenAtReset = new GlobalSequenceTrackingToken(5);
            TrackingToken currentToken = new GlobalSequenceTrackingToken(10);
            TrackingToken replayToken = new ReplayToken(tokenAtReset, currentToken, new byte[0]);

            assertThat(ReplayToken.concludesReplay(replayToken)).isTrue();
        }

        @Test
        void returnsFalseForNonReplayToken() {
            TrackingToken token = new GlobalSequenceTrackingToken(5);

            assertThat(ReplayToken.concludesReplay(token)).isFalse();
        }

        @Test
        void returnsTrueWithGapAwareTokensAtSamePosition() {
            TrackingToken tokenAtReset = GapAwareTrackingToken.newInstance(10, emptySet());
            TrackingToken currentToken = GapAwareTrackingToken.newInstance(10, emptySet());
            TrackingToken replayToken = new ReplayToken(tokenAtReset, currentToken, new byte[0]);

            assertThat(ReplayToken.concludesReplay(replayToken)).isTrue();
        }
    }
}
