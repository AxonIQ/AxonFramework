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

package org.axonframework.hunt.scenario;

import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GapAwareTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.WrappedToken;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Probes the replay gate of {@link ReplayToken}: {@link ReplayToken#replayContext} and
 * {@link ReplayToken#getTokenAtReset} apply while an event is a redelivery, and must not apply to
 * the first fresh event either side of the replay boundary - including a fresh event delivered
 * while the token is still ReplayToken-wrapped (a gap position the reset token had never seen).
 */
class ReplayContextBoundaryGateTest {

    private static final JacksonConverter CONVERTER = new JacksonConverter();
    private static final byte[] RESET_CONTEXT = "ctx".getBytes();

    private static Optional<String> contextOf(TrackingToken token) {
        return ReplayToken.replayContext(token, String.class, CONVERTER);
    }

    @Nested
    class GlobalSequenceBoundary {

        @Test
        void replayContextAppliesUpToTheResetPositionAndTheTokenUnwrapsBeyondIt() {
            TrackingToken atReset = new GlobalSequenceTrackingToken(3);
            TrackingToken token = ReplayToken.createReplayToken(atReset, null, RESET_CONTEXT);

            for (long position = 0; position <= 3; position++) {
                token = WrappedToken.advance(token, new GlobalSequenceTrackingToken(position));
                assertThat(ReplayToken.isReplay(token))
                        .as("position %d is a redelivery", position).isTrue();
                assertThat(contextOf(token)).as("context at position %d", position).contains("ctx");
                assertThat(ReplayToken.getTokenAtReset(token)).isPresent();
            }

            // the first live event: strictly after the reset position, token unwraps entirely
            token = WrappedToken.advance(token, new GlobalSequenceTrackingToken(4));
            assertThat(token).isNotInstanceOf(ReplayToken.class);
            assertThat(ReplayToken.isReplay(token)).isFalse();
            assertThat(contextOf(token)).isEmpty();
            assertThat(ReplayToken.getTokenAtReset(token)).isEmpty();
        }
    }

    @Nested
    class GapPositionInsideTheBoundary {

        @Test
        void freshGapEventInsideTheReplayWindowDoesNotReceiveTheReplayContext() {
            // reset happened at index 10 with position 5 never seen (a gap): replaying from the
            // tail redelivers 0..4 and 6..10, while 5 arrives for the very first time
            TrackingToken atReset = GapAwareTrackingToken.newInstance(10, List.of(5L));
            TrackingToken token = ReplayToken.createReplayToken(atReset, null, RESET_CONTEXT);

            for (long position = 0; position <= 4; position++) {
                token = WrappedToken.advance(token, GapAwareTrackingToken.newInstance(position, List.of()));
                assertThat(ReplayToken.isReplay(token))
                        .as("position %d was processed before the reset", position).isTrue();
                assertThat(contextOf(token)).as("context at position %d", position).contains("ctx");
            }

            // position 5: never processed before the reset - a fresh event, delivered while the
            // token is still ReplayToken-wrapped
            token = WrappedToken.advance(token, GapAwareTrackingToken.newInstance(5, List.of()));
            System.out.println("[probe] gap event token=" + token
                                       + " isReplay=" + ReplayToken.isReplay(token)
                                       + " context=" + contextOf(token)
                                       + " tokenAtReset=" + ReplayToken.getTokenAtReset(token));
            assertThat(token).isInstanceOf(ReplayToken.class);
            assertThat(ReplayToken.isReplay(token)).as("a fresh event is not a replay").isFalse();
            assertThat(contextOf(token)).as("replay context must not apply to a fresh event").isEmpty();
            assertThat(ReplayToken.getTokenAtReset(token)).isEmpty();

            // position 6: processed before the reset - a redelivery again, gate reopens
            token = WrappedToken.advance(token, GapAwareTrackingToken.newInstance(6, List.of()));
            assertThat(ReplayToken.isReplay(token)).as("position 6 is a redelivery").isTrue();
            assertThat(contextOf(token)).contains("ctx");
            assertThat(ReplayToken.getTokenAtReset(token)).isPresent();

            // beyond the reset position the token unwraps
            for (long position = 7; position <= 10; position++) {
                token = WrappedToken.advance(token, GapAwareTrackingToken.newInstance(position, List.of()));
            }
            token = WrappedToken.advance(token, GapAwareTrackingToken.newInstance(11, List.of()));
            assertThat(token).isNotInstanceOf(ReplayToken.class);
            assertThat(contextOf(token)).isEmpty();
        }
    }
}
