package org.axonframework.messaging.eventhandling.processing.streaming.token.store;

import org.axonframework.messaging.eventhandling.processing.streaming.token.GapAwareTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.MergedTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.junit.jupiter.api.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for validating the behavior of {@link ReplayToken} when wrapping complex token types.
 * <p>
 * This test validates replay detection across various nested token scenarios:
 * <ul>
 *   <li>{@link GapAwareTrackingToken} - with gaps, filled gaps, and various positions relative to reset</li>
 *   <li>{@link MergedTrackingToken} - merged segments with gaps in lower/upper segments and nested structures</li>
 * </ul>
 *
 * @author Mateusz Nowak
 * @since 4.12.3
 */
class ReplayTokenWrappingComplexTokenTest {

    @Nested
    class GapAwareTrackingTokenAdvancedTo {

        @Test
        void whenBeforeResetPositionWithoutGapsThenReplay() {
            // given
            TrackingToken tokenAtReset = GapAwareTrackingToken.newInstance(10, emptySet());
            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, null);

            // when
            TrackingToken newToken = GapAwareTrackingToken.newInstance(5, emptySet());
            TrackingToken result = replayToken.advancedTo(newToken);

            // then
            assertInstanceOf(ReplayToken.class, result);
            assertTrue(ReplayToken.isReplay(result), "Event before reset position should be a replay");
        }

        @Test
        void whenAtResetPositionWithoutGapsThenReplay() {
            // given
            TrackingToken tokenAtReset = GapAwareTrackingToken.newInstance(10, emptySet());
            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, null);

            // when
            replayToken = (ReplayToken) replayToken.advancedTo(GapAwareTrackingToken.newInstance(9, emptySet()));
            TrackingToken newToken = GapAwareTrackingToken.newInstance(10, emptySet());
            TrackingToken result = replayToken.advancedTo(newToken);

            // then
            assertInstanceOf(ReplayToken.class, result);
            assertTrue(ReplayToken.isReplay(result), "Event at exact reset position should be a replay");
        }

        @Test
        void whenAfterResetPositionWithoutGapsThenNotReplay() {
            // given
            TrackingToken tokenAtReset = GapAwareTrackingToken.newInstance(10, emptySet());
            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, null);

            // when
            TrackingToken newToken = GapAwareTrackingToken.newInstance(11, emptySet());
            TrackingToken result = replayToken.advancedTo(newToken);

            // then
            assertFalse(ReplayToken.isReplay(result), "Event after reset position should not be a replay");
        }

        @Test
        void whenAtGapThenNotReplay() {
            // given
            TrackingToken tokenAtReset = GapAwareTrackingToken.newInstance(10, setOf(7L, 8L));
            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, null);

            // when
            TrackingToken newToken = GapAwareTrackingToken.newInstance(7, emptySet());
            TrackingToken result = replayToken.advancedTo(newToken);

            // then
            assertInstanceOf(ReplayToken.class, result, "Should still be in replay mode");
            assertFalse(ReplayToken.isReplay(result), "Event at gap position should NOT be replay");
        }

        @Test
        void whenAtGapAndWithAnotherGapThenNotReplay() {
            // given
            TrackingToken tokenAtReset = GapAwareTrackingToken.newInstance(10, setOf(7L, 8L));
            TrackingToken currentToken = ReplayToken.createReplayToken(tokenAtReset, null);

            // when
            TrackingToken newToken = GapAwareTrackingToken.newInstance(8, setOf(7L));
            TrackingToken result = ((ReplayToken) currentToken).advancedTo(newToken);

            // then
            assertFalse(ReplayToken.isReplay(result));
        }

        @Test
        void whenAfterGapBeforeResetAndNotFilledGapsThenReplay() {
            // given
            TrackingToken tokenAtReset = GapAwareTrackingToken.newInstance(10, setOf(7L, 8L));
            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, null);

            // when
            TrackingToken newToken = GapAwareTrackingToken.newInstance(9, setOf(7L, 8L));
            TrackingToken result = replayToken.advancedTo(newToken);

            // then
            assertInstanceOf(ReplayToken.class, result, "Should still be in replay mode");
            assertTrue(ReplayToken.isReplay(result), "Event after gap but before reset should be replay");
        }

        @Test
        void whenAfterGapBeforeResetAndFilledGapsThenReplay() {
            // given
            // tokenAtReset at index 10 with gaps at 7,8
            // Before reset, processor saw: 0,1,2,3,4,5,6,9,10 (NOT 7,8)
            TrackingToken tokenAtReset = GapAwareTrackingToken.newInstance(10, setOf(7L, 8L));
            TrackingToken currentToken = ReplayToken.createReplayToken(tokenAtReset, null);

            // when
            // Now event 9 arrives - currentToken has NO gaps (they were filled)
            TrackingToken newToken = GapAwareTrackingToken.newInstance(9, emptySet());
            TrackingToken result = ((ReplayToken) currentToken).advancedTo(newToken);

            // then
            assertInstanceOf(ReplayToken.class, result, "Should still be in replay mode");
            assertTrue(ReplayToken.isReplay(result),
                       "Event 9 was processed before reset, should be marked as replay even though gaps were filled");
        }

        @Test
        void givenStartPositionWhenAfterGapBeforeResetAndFilledGapsThenReplay() {
            // given
            TrackingToken tokenAtReset = GapAwareTrackingToken.newInstance(10, setOf(7L, 8L));
            TrackingToken currentToken = ReplayToken.createReplayToken(tokenAtReset,
                                                                       new GapAwareTrackingToken(0L, emptyList()));

            // when
            TrackingToken newToken = GapAwareTrackingToken.newInstance(9, emptySet());
            TrackingToken result = ((ReplayToken) currentToken).advancedTo(newToken);

            // then
            assertInstanceOf(ReplayToken.class, result, "Should still be in replay mode");
            assertTrue(ReplayToken.isReplay(result),
                       "Event 9 was processed before reset, should be marked as replay even though gaps were filled");
        }

        @Test
        void whenAtResetPositionAndFilledGapsThenReplay() {
            // given
            TrackingToken tokenAtReset = GapAwareTrackingToken.newInstance(10, setOf(7L, 8L));

            // when
            TrackingToken currentToken = ReplayToken.createReplayToken(tokenAtReset, null);
            for (int i = 0; i <= 9; i++) {
                currentToken = ((ReplayToken) currentToken).advancedTo(
                        GapAwareTrackingToken.newInstance(i, emptySet())
                );
            }
            TrackingToken newToken = GapAwareTrackingToken.newInstance(10, emptySet());
            TrackingToken result = ((ReplayToken) currentToken).advancedTo(newToken);

            // then
            assertTrue(ReplayToken.isReplay(result));
            assertInstanceOf(ReplayToken.class, result,
                             "Should still be in replay mode at reset index - " +
                                     "event 10 was processed before reset");
        }

        @Test
        void whenAfterResetPositionAndFilledGapsThenNotReplay() {
            // given
            TrackingToken tokenAtReset = GapAwareTrackingToken.newInstance(10, setOf(7L, 8L));

            // when
            TrackingToken currentToken = ReplayToken.createReplayToken(tokenAtReset, null);
            for (int i = 0; i <= 10; i++) {
                currentToken = ((ReplayToken) currentToken).advancedTo(
                        GapAwareTrackingToken.newInstance(i, emptySet())
                );
            }
            TrackingToken newToken = GapAwareTrackingToken.newInstance(11, emptySet());
            TrackingToken result = ((ReplayToken) currentToken).advancedTo(newToken);

            // then
            assertFalse(ReplayToken.isReplay(result),
                        "Event 11 was never processed before reset, should not be a replay");
        }

        @Test
        void whenAfterResetPositionAndNotFilledGapsThenNotReplay() {
            // given
            TrackingToken tokenAtReset = GapAwareTrackingToken.newInstance(10, setOf(7L, 8L));
            TrackingToken currentToken = ReplayToken.createReplayToken(tokenAtReset, null);

            // when
            TrackingToken newToken = GapAwareTrackingToken.newInstance(11, setOf(7L, 8L));
            TrackingToken result = ((ReplayToken) currentToken).advancedTo(newToken);

            // then
            assertFalse(ReplayToken.isReplay(result),
                        "Event 11 was never processed before reset, should not be a replay");
        }

        @Test
        void whenBeforeResetPositionAndNotFilledGapsThenReplay() {
            // given
            TrackingToken tokenAtReset = GapAwareTrackingToken.newInstance(10, setOf(7L, 8L));

            // when
            TrackingToken currentToken = ReplayToken.createReplayToken(tokenAtReset, null);
            for (int i = 0; i <= 7; i++) {
                currentToken = ((ReplayToken) currentToken).advancedTo(
                        GapAwareTrackingToken.newInstance(i, emptySet())
                );
            }
            TrackingToken newToken = GapAwareTrackingToken.newInstance(9, setOf(8L));
            TrackingToken result = ((ReplayToken) currentToken).advancedTo(newToken);

            // then
            assertInstanceOf(ReplayToken.class, result);
            assertTrue(ReplayToken.isReplay(result),
                       "Event 9 was processed before reset, should be replay even with partial gap fill");
        }

        @Test
        void whenAtGapJustBeforeResetPositionThenNotReplay() {
            // given
            TrackingToken tokenAtReset = GapAwareTrackingToken.newInstance(10, setOf(9L));
            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, null);

            // when - gap 9 gets filled during replay
            TrackingToken token9 = GapAwareTrackingToken.newInstance(9, emptySet());
            TrackingToken resultAt9 = replayToken.advancedTo(token9);

            // then
            assertInstanceOf(ReplayToken.class, resultAt9);
            assertFalse(ReplayToken.isReplay(resultAt9),
                        "Event 9 was a gap before reset, should NOT be marked as replay");

            // when
            TrackingToken token10 = GapAwareTrackingToken.newInstance(10, emptySet());
            TrackingToken resultAt10 = ((ReplayToken) resultAt9).advancedTo(token10);

            // then
            assertInstanceOf(ReplayToken.class, resultAt10);
            assertTrue(ReplayToken.isReplay(resultAt10),
                       "Event 10 was processed before reset, should be marked as replay");
        }

        @Test
        void multipleDisjointGapsInTokenAtReset() {
            // given
            TrackingToken tokenAtReset = GapAwareTrackingToken.newInstance(15, setOf(3L, 4L, 9L, 10L));
            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, null);

            // when-then
            TrackingToken result2 = replayToken.advancedTo(GapAwareTrackingToken.newInstance(2, emptySet()));
            assertTrue(ReplayToken.isReplay(result2), "Event 2 was processed, should be replay");

            // when-then
            TrackingToken result3 = ((ReplayToken) result2).advancedTo(GapAwareTrackingToken.newInstance(3,
                                                                                                         emptySet()));
            assertFalse(ReplayToken.isReplay(result3), "Event 3 was a gap, should NOT be replay");

            // when-then
            TrackingToken result5 = ((ReplayToken) result3).advancedTo(
                    GapAwareTrackingToken.newInstance(5, setOf(4L)));
            assertTrue(ReplayToken.isReplay(result5), "Event 5 was processed, should be replay");
        }

        @Test
        void whenAtResetAndGapsNotFilledDuringReplayThenReplay() {
            // given
            GapAwareTrackingToken tokenAtReset = GapAwareTrackingToken.newInstance(5, setOf(3L));
            GapAwareTrackingToken currentTokenWithGap = GapAwareTrackingToken.newInstance(4, setOf(3L));
            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset,
                                                                                  currentTokenWithGap,
                                                                                  null);

            // when
            GapAwareTrackingToken newToken = GapAwareTrackingToken.newInstance(5, setOf(3L));
            TrackingToken result = replayToken.advancedTo(newToken);

            // then
            assertInstanceOf(ReplayToken.class, result, "Should still be in replay mode");
            assertTrue(ReplayToken.isReplay(result), "Event 5 was processed before reset");
        }

        @Test
        void whenGapAtStartPositionThenNotReplay() {
            // given
            GapAwareTrackingToken tokenAtReset = GapAwareTrackingToken.newInstance(5, setOf(0L));
            GapAwareTrackingToken currentToken = GapAwareTrackingToken.newInstance(0, emptySet());

            // when
            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, currentToken);

            // then
            assertInstanceOf(ReplayToken.class, replayToken);
            assertFalse(ReplayToken.isReplay(replayToken), "Event 0 was a gap, should NOT be marked as replay");
        }

        @Test
        void whenBetweenGapsThenReplay() {
            // given
            GapAwareTrackingToken tokenAtReset = GapAwareTrackingToken.newInstance(10, setOf(3L, 7L));
            GapAwareTrackingToken currentToken = GapAwareTrackingToken.newInstance(4, emptySet());
            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, currentToken, null);

            // when
            GapAwareTrackingToken newToken = GapAwareTrackingToken.newInstance(5, emptySet());
            TrackingToken result = replayToken.advancedTo(newToken);

            // then
            assertInstanceOf(ReplayToken.class, result, "Should still be in replay mode");
            assertTrue(ReplayToken.isReplay(result),
                       "Event 5 was processed before reset, should be marked as replay");
        }
    }

    @Nested
    class MergedTrackingTokenAdvancedToGapAwareTrackingToken {

        @Test
        void isReplayOnMergedTokenWithoutGaps() {
            MergedTrackingToken tokenAtReset = new MergedTrackingToken(
                    GapAwareTrackingToken.newInstance(5, emptySet()),
                    GapAwareTrackingToken.newInstance(10, emptySet())
            );
            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, null);

            // Position 3: before lower segment
            TrackingToken beforeLowerSegment = replayToken.advancedTo(GapAwareTrackingToken.newInstance(3, emptySet()));
            assertInstanceOf(ReplayToken.class, beforeLowerSegment);
            assertTrue(ReplayToken.isReplay(beforeLowerSegment), "Before lower segment should be replay");

            // Position 5: at lower segment
            TrackingToken atLowerSegment = replayToken.advancedTo(GapAwareTrackingToken.newInstance(5, emptySet()));
            assertInstanceOf(ReplayToken.class, atLowerSegment);
            assertTrue(ReplayToken.isReplay(atLowerSegment), "At lower segment should be replay");

            // Position 7: between segments
            TrackingToken betweenSegments = replayToken.advancedTo(GapAwareTrackingToken.newInstance(7, emptySet()));
            assertInstanceOf(ReplayToken.class, betweenSegments);
            assertFalse(ReplayToken.isReplay(betweenSegments), "Between segments should NOT be replay");

            // Position 10: at upper segment
            TrackingToken atUpperSegment = replayToken.advancedTo(GapAwareTrackingToken.newInstance(10, emptySet()));
            assertInstanceOf(ReplayToken.class, atUpperSegment);
            assertFalse(ReplayToken.isReplay(atUpperSegment), "At upper segment should NOT be replay");

            // Position 11: past upper segment - exits replay
            TrackingToken pastUpperSegment = replayToken.advancedTo(GapAwareTrackingToken.newInstance(11, emptySet()));
            assertInstanceOf(GapAwareTrackingToken.class, pastUpperSegment);
            assertFalse(ReplayToken.isReplay(pastUpperSegment), "Past upper segment should exit replay");
        }

        @Test
        void isReplayOnMergedTokenWithGapInLowerSegment() {
            MergedTrackingToken tokenAtReset = new MergedTrackingToken(
                    new GapAwareTrackingToken(3, setOf(2L)),
                    new GapAwareTrackingToken(9, emptySet())
            );
            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, null);

            // Position 1: before gap at 2, was processed
            TrackingToken beforeGapWasProcessed = replayToken.advancedTo(new GapAwareTrackingToken(1, emptySet()));
            assertInstanceOf(ReplayToken.class, beforeGapWasProcessed);
            assertTrue(ReplayToken.isReplay(beforeGapWasProcessed), "Before gap should be replay");

            // Position 2: at gap position in lower segment
            TrackingToken atGapPosition = replayToken.advancedTo(new GapAwareTrackingToken(2, emptySet()));
            assertInstanceOf(ReplayToken.class, atGapPosition);
            assertFalse(ReplayToken.isReplay(atGapPosition), "At gap position should NOT be replay");

            // Position 3: at lower index, was processed
            TrackingToken atLowerIndex = replayToken.advancedTo(new GapAwareTrackingToken(3, emptySet()));
            assertInstanceOf(ReplayToken.class, atLowerIndex);
            assertTrue(ReplayToken.isReplay(atLowerIndex), "At lower index should be replay");

            // Position 4: past lower segment
            TrackingToken pastLowerSegment = replayToken.advancedTo(new GapAwareTrackingToken(4, emptySet()));
            assertInstanceOf(ReplayToken.class, pastLowerSegment);
            assertFalse(ReplayToken.isReplay(pastLowerSegment), "Past lower segment should NOT be replay");

            // Position 9: at upper index
            TrackingToken atUpperIndex = replayToken.advancedTo(new GapAwareTrackingToken(9, emptySet()));
            assertInstanceOf(ReplayToken.class, atUpperIndex);
            assertFalse(ReplayToken.isReplay(atUpperIndex), "At upper index should NOT be replay");

            // Position 10: past upper, exits replay
            TrackingToken pastUpperExitsReplay = replayToken.advancedTo(new GapAwareTrackingToken(10, emptySet()));
            assertInstanceOf(GapAwareTrackingToken.class, pastUpperExitsReplay);
            assertFalse(ReplayToken.isReplay(pastUpperExitsReplay), "Past upper should exit replay");
        }

        @Test
        void isReplayOnMergedTokenWithGapInUpperSegment() {
            MergedTrackingToken tokenAtReset = new MergedTrackingToken(
                    GapAwareTrackingToken.newInstance(5, emptySet()),
                    GapAwareTrackingToken.newInstance(10, setOf(8L))  // gap at 8
            );
            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, null);

            // Event at gap position 8 - NOT replay
            TrackingToken resultAt8 = replayToken.advancedTo(GapAwareTrackingToken.newInstance(8, emptySet()));
            assertInstanceOf(ReplayToken.class, resultAt8);
            assertFalse(ReplayToken.isReplay(resultAt8), "Event at gap position should NOT be replay");

            // Event at position 9 (past lower segment) - NOT replay
            TrackingToken resultAt9 = ((ReplayToken) resultAt8).advancedTo(GapAwareTrackingToken.newInstance(9,
                                                                                                             setOf(8L)));
            assertInstanceOf(ReplayToken.class, resultAt9);
            assertFalse(ReplayToken.isReplay(resultAt9), "Event past lower segment should NOT be replay");
        }

        @Test
        void isReplayOnMergedTokenWithGapInBothSegments() {
            MergedTrackingToken tokenAtReset = new MergedTrackingToken(
                    GapAwareTrackingToken.newInstance(5, setOf(2L)),
                    GapAwareTrackingToken.newInstance(10, setOf(8L))
            );
            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, null);

            // Event at lower gap (position 2)
            TrackingToken eventAtLowerGap = replayToken.advancedTo(GapAwareTrackingToken.newInstance(2, emptySet()));
            assertInstanceOf(ReplayToken.class, eventAtLowerGap);
            assertFalse(ReplayToken.isReplay(eventAtLowerGap), "Event at lower gap should NOT be replay");

            // Event at upper gap (position 8)
            TrackingToken eventAtUpperGap = replayToken.advancedTo(GapAwareTrackingToken.newInstance(8, emptySet()));
            assertInstanceOf(ReplayToken.class, eventAtUpperGap);
            assertFalse(ReplayToken.isReplay(eventAtUpperGap), "Event at upper gap should NOT be replay");

            // Event past lower segment (position 7)
            TrackingToken eventPastLowerSegment = replayToken.advancedTo(GapAwareTrackingToken.newInstance(7,
                                                                                                           emptySet()));
            assertInstanceOf(ReplayToken.class, eventPastLowerSegment);
            assertFalse(ReplayToken.isReplay(eventPastLowerSegment), "Event past lower segment should NOT be replay");
        }

        // ==================== Progressive Replay Through MergedTrackingToken ====================

        /**
         * Tests progressive replay through a MergedTrackingToken, verifying replay status at each step. Key insight:
         * Only events ≤ lower segment index are replays. Events past lower but before upper are NOT replays (lower
         * segment hasn't seen them) but stay in ReplayToken wrapper.
         */
        @Test
        void progressiveReplayThroughMergedToken() {
            MergedTrackingToken tokenAtReset = new MergedTrackingToken(
                    GapAwareTrackingToken.newInstance(3, setOf(2L)),  // gap at 2, lower at 3
                    GapAwareTrackingToken.newInstance(9, emptySet())  // upper at 9
            );
            TrackingToken currentToken = ReplayToken.createReplayToken(tokenAtReset, null);

            // Position 0: before lower segment, was processed → replay
            currentToken = ((ReplayToken) currentToken).advancedTo(GapAwareTrackingToken.newInstance(0, emptySet()));
            assertTrue(ReplayToken.isReplay(currentToken), "Position 0 should be replay");

            // Position 1: before gap, was processed → replay
            currentToken = ((ReplayToken) currentToken).advancedTo(GapAwareTrackingToken.newInstance(1, emptySet()));
            assertTrue(ReplayToken.isReplay(currentToken), "Position 1 should be replay");

            // Position 2: gap in lower segment → NOT replay
            currentToken = ((ReplayToken) currentToken).advancedTo(GapAwareTrackingToken.newInstance(2, emptySet()));
            assertFalse(ReplayToken.isReplay(currentToken), "Position 2 (gap) should NOT be replay");

            // Position 3: at lower segment index, was processed → replay
            currentToken = ((ReplayToken) currentToken).advancedTo(GapAwareTrackingToken.newInstance(3, emptySet()));
            assertTrue(ReplayToken.isReplay(currentToken), "Position 3 should be replay");

            // Position 5: between segments → NOT replay (lower segment hasn't seen it)
            currentToken = ((ReplayToken) currentToken).advancedTo(GapAwareTrackingToken.newInstance(5, emptySet()));
            assertInstanceOf(ReplayToken.class, currentToken, "Should still be in ReplayToken");
            assertFalse(ReplayToken.isReplay(currentToken), "Position 5 should NOT be replay");

            // Position 9: at upper segment → NOT replay (lower segment hasn't seen it)
            currentToken = ((ReplayToken) currentToken).advancedTo(GapAwareTrackingToken.newInstance(9, emptySet()));
            assertInstanceOf(ReplayToken.class, currentToken, "Should still be in ReplayToken");
            assertFalse(ReplayToken.isReplay(currentToken), "Position 9 should NOT be replay");

            // Position 10: past upper segment → exits replay
            currentToken = ((ReplayToken) currentToken).advancedTo(GapAwareTrackingToken.newInstance(10, emptySet()));
            assertFalse(ReplayToken.isReplay(currentToken), "Position 10 should exit replay");
        }

        @Test
        void givenSegmentsAtSamePositionWhenAtThatPositionThenReplay() {
            // given
            MergedTrackingToken tokenAtReset = new MergedTrackingToken(
                    GapAwareTrackingToken.newInstance(10, emptySet()),
                    GapAwareTrackingToken.newInstance(10, emptySet())
            );
            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, null);

            // when
            TrackingToken result = replayToken.advancedTo(GapAwareTrackingToken.newInstance(10, emptySet()));

            // then
            assertInstanceOf(ReplayToken.class, result);
            assertTrue(ReplayToken.isReplay(result),
                       "Event at shared position should be replay");
        }

        @Test
        void givenSegmentsAtSamePositionWhenAfterThatPositionThenNotReplay() {
            // given
            MergedTrackingToken tokenAtReset = new MergedTrackingToken(
                    GapAwareTrackingToken.newInstance(10, emptySet()),
                    GapAwareTrackingToken.newInstance(10, emptySet())
            );
            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, null);

            // when
            TrackingToken result = replayToken.advancedTo(GapAwareTrackingToken.newInstance(11, emptySet()));

            // then
            assertFalse(ReplayToken.isReplay(result),
                        "Event past shared position should exit replay");
        }

        @Test
        void givenSegmentsWithSameGapWhenAtThatGapThenNotReplay() {
            // given
            MergedTrackingToken tokenAtReset = new MergedTrackingToken(
                    GapAwareTrackingToken.newInstance(7, setOf(5L)),
                    GapAwareTrackingToken.newInstance(10, setOf(5L))
            );
            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, null);

            // when
            TrackingToken result = replayToken.advancedTo(GapAwareTrackingToken.newInstance(5, emptySet()));

            // then
            assertInstanceOf(ReplayToken.class, result);
            assertFalse(ReplayToken.isReplay(result),
                        "Event at shared gap position should NOT be replay");
        }

        /**
         * MergedTrackingToken containing another MergedTrackingToken. This can happen with multiple merge operations.
         * The lowerBound of nested MergedToken is still the innermost lowerSegment (3).
         */
        @Test
        void givenNestedMergedTokenWhenAtInnermostLowerSegmentThenReplay() {
            // given
            MergedTrackingToken innerMerged = new MergedTrackingToken(
                    GapAwareTrackingToken.newInstance(3, emptySet()),
                    GapAwareTrackingToken.newInstance(5, emptySet())
            );

            MergedTrackingToken tokenAtReset = new MergedTrackingToken(
                    innerMerged,
                    GapAwareTrackingToken.newInstance(10, emptySet())
            );

            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, null);

            // when
            TrackingToken result = replayToken.advancedTo(GapAwareTrackingToken.newInstance(3, emptySet()));

            // then
            assertInstanceOf(ReplayToken.class, result, "Should still be in ReplayToken wrapper");
            assertTrue(ReplayToken.isReplay(result),
                       "Event at position 3 IS replay - innermost lower segment has seen it");
        }

        @Test
        void givenNestedMergedTokenWhenPastInnermostLowerSegmentThenNotReplay() {
            // given
            MergedTrackingToken innerMerged = new MergedTrackingToken(
                    GapAwareTrackingToken.newInstance(3, emptySet()),
                    GapAwareTrackingToken.newInstance(5, emptySet())
            );

            // Outer merged token with inner as lower segment
            MergedTrackingToken tokenAtReset = new MergedTrackingToken(
                    innerMerged,
                    GapAwareTrackingToken.newInstance(10, emptySet())
            );

            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, null);

            // when
            TrackingToken result = replayToken.advancedTo(GapAwareTrackingToken.newInstance(7, emptySet()));

            // then
            assertInstanceOf(ReplayToken.class, result, "Should still be in ReplayToken wrapper");
            assertFalse(ReplayToken.isReplay(result),
                        "Event at position 7 is NOT replay - innermost lower segment (3) hasn't seen it");
        }

        @Test
        void givenNestedMergedTokenWhenPastOuterUpperBoundThenNotReplay() {
            // given
            MergedTrackingToken innerMerged = new MergedTrackingToken(
                    GapAwareTrackingToken.newInstance(3, emptySet()),
                    GapAwareTrackingToken.newInstance(5, emptySet())
            );

            MergedTrackingToken tokenAtReset = new MergedTrackingToken(
                    innerMerged,
                    GapAwareTrackingToken.newInstance(10, emptySet())
            );

            ReplayToken replayToken = (ReplayToken) ReplayToken.createReplayToken(tokenAtReset, null);

            // when
            TrackingToken result = replayToken.advancedTo(GapAwareTrackingToken.newInstance(11, emptySet()));

            // then
            assertFalse(ReplayToken.isReplay(result),
                        "Event past outer upper bound should exit replay");
        }
    }

    private static Set<Long> setOf(Long... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
