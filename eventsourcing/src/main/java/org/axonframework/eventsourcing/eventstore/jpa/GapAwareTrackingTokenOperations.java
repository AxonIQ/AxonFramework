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

package org.axonframework.eventsourcing.eventstore.jpa;

import org.axonframework.common.ClockUtils;
import org.axonframework.common.DateTimeUtils;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GapAwareTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/**
 * Contains operations that are used to interact with {@link GapAwareTrackingToken} used in Aggregate based JPA event
 * store implementation.
 *
 * @param gapTimeout           the amount of time in milliseconds until a gap may be considered timed out and thus ready
 *                             for removal by {@link #withGapsCleaned(GapAwareTrackingToken, List)}
 * @param maxGapOffset         the maximum distance in global indices between a gap and the highest index a token has
 *                             seen; gaps further behind than this are dropped from the token
 * @param lowestGlobalSequence the first global index the backing table is expected to contain
 * @param logger               the logger to report an unparsable stored timestamp on
 * @author Mateusz Nowak
 * @since 5.0.0
 */
record GapAwareTrackingTokenOperations(
        int gapTimeout,
        int maxGapOffset,
        long lowestGlobalSequence,
        Logger logger
) {

    /**
     * Advances the given {@code token} to the given {@code globalIndex}, recording every index in between as a gap.
     * <p>
     * Every hole below {@code globalIndex} is recorded, without exception. A hole exists precisely because that index
     * was taken from the database sequence by a transaction that has not committed yet, and nothing about the rows a
     * reader can see tells it whether that transaction is still going to commit. Choosing not to record the gap
     * therefore drops the event for good once the transaction does commit: the token has moved past the index and holds
     * nothing that would ever bring the reader back to it.
     * <p>
     * The cost of recording is bounded by the {@code maxGapOffset}, which drops gaps that have fallen further than that
     * many indices behind the token, and by {@link #withGapsCleaned(GapAwareTrackingToken, List)}.
     *
     * @param token       the token to advance, or {@code null} when the reader has not established one yet
     * @param globalIndex the global index of the event that was just read
     * @return the given {@code token} advanced to {@code globalIndex}
     */
    GapAwareTrackingToken advance(@Nullable GapAwareTrackingToken token, long globalIndex) {
        return token == null
                ? GapAwareTrackingToken.newInstance(globalIndex, initialGaps(globalIndex))
                : token.advanceTo(globalIndex, maxGapOffset);
    }

    private Collection<Long> initialGaps(long globalIndex) {
        long lowestGap = Math.max(Math.min(lowestGlobalSequence, globalIndex), globalIndex - maxGapOffset);
        return LongStream.range(lowestGap, globalIndex)
                         .boxed()
                         .collect(Collectors.toCollection(TreeSet::new));
    }

    GapAwareTrackingToken withGapsCleaned(GapAwareTrackingToken token, List<Object[]> indexAndTimestampBetweenGaps) {
        Instant gapTimeoutThreshold = gapTimeoutThreshold();
        GapAwareTrackingToken cleanedToken = token;
        for (Object[] existingEvent : indexAndTimestampBetweenGaps) {
            try {
                Instant timestamp = DateTimeUtils.parseInstant(existingEvent[1].toString());
                long sequenceNumber = (long) existingEvent[0];
                boolean gapFilled = cleanedToken.getGaps().contains(sequenceNumber);
                if (gapFilled || timestamp.isAfter(gapTimeoutThreshold)) {
                    // filled a gap or found an entry that is too recent. Should not continue cleaning up
                    return cleanedToken;
                }
                boolean gapRightBeforeTheEvent = cleanedToken.getGaps().contains(sequenceNumber - 1);
                if (gapRightBeforeTheEvent) {
                    cleanedToken = cleanedToken.withGapsTruncatedAt(sequenceNumber);
                }
            } catch (DateTimeParseException e) {
                if (logger.isDebugEnabled()) {
                    logger.info("Unable to parse timestamp ('{}') to clean old gaps. Trying to proceed. ",
                                e.getParsedString(), e);
                } else {
                    logger.info("Unable to parse timestamp ('{}') to clean old gaps. Trying to proceed. " +
                                        "Exception message: {}. (enable debug logging for full stack trace)",
                                e.getParsedString(), e.getMessage());
                }
            }
        }
        return cleanedToken;
    }

    GapAwareTrackingToken assertGapAwareTrackingToken(TrackingToken trackingToken) {
        if (trackingToken instanceof GapAwareTrackingToken gapAwareTrackingToken) {
            return gapAwareTrackingToken;
        } else {
            throw new IllegalArgumentException(
                    "Tracking Token is not of expected type. Must be GapAwareTrackingToken. Is: "
                            + trackingToken.getClass().getName());
        }
    }

    private Instant gapTimeoutThreshold() {
        return ClockUtils.instant().minus(gapTimeout, ChronoUnit.MILLIS);
    }
}
