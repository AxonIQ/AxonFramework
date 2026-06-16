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
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Contains operations that are used to interact with {@link GapAwareTrackingToken} used in Aggregate based JPA event
 * store implementation.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
record GapAwareTrackingTokenOperations(
        int gapTimeout,
        Logger logger
) {

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

    Instant gapTimeoutThreshold() {
        return ClockUtils.instant().minus(gapTimeout, ChronoUnit.MILLIS);
    }
}
