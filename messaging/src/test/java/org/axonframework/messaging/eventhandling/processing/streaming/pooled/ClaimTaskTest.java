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

package org.axonframework.messaging.eventhandling.processing.streaming.pooled;

import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.UnableToClaimTokenException;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Test class validating the {@link ClaimTask}.
 *
 * @author Laura Devriendt
 */
class ClaimTaskTest {

    private static final String PROCESSOR_NAME = "test";
    private static final int SEGMENT_ID = 0;
    private static final Segment SEGMENT = Segment.splitBalanced(Segment.ROOT_SEGMENT, 1).getFirst();
    private static final Segment OTHER_SEGMENT = Segment.splitBalanced(Segment.ROOT_SEGMENT, 1).getLast();

    private final TokenStore tokenStore = mock(TokenStore.class);
    private final Map<Integer, WorkPackage> workPackages = new HashMap<>();
    private final Map<Integer, Instant> releasesDeadlines = new HashMap<>();

    private CompletableFuture<Boolean> result;
    private ClaimTask testSubject;

    @BeforeEach
    void setUp() {
        result = new CompletableFuture<>();
        testSubject = new ClaimTask(
                result,
                PROCESSOR_NAME,
                SEGMENT_ID,
                workPackages,
                releasesDeadlines,
                tokenStore,
                new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE)
        );
    }

    @Nested
    class WhenSegmentAlreadyActivelyProcessed {

        @Test
        void runReturnsTrueImmediatelyWithoutQueryingTokenStore() throws ExecutionException, InterruptedException {
            // given - segment is already being processed by this coordinator
            workPackages.put(SEGMENT_ID, mock(WorkPackage.class));

            // when
            testSubject.run();

            // then
            assertThat(result).isDone();
            assertThat(result.get()).isTrue();
            verifyNoInteractions(tokenStore);
        }
    }

    @Nested
    class WhenSegmentNotAvailableForClaiming {

        @Test
        void runReturnsFalseWhenNoSegmentsAreAvailable() throws ExecutionException, InterruptedException {
            // given - token store has no available segments
            when(tokenStore.fetchAvailableSegments(eq(PROCESSOR_NAME), any()))
                    .thenReturn(completedFuture(List.of()));

            // when
            testSubject.run();

            // then
            assertThat(result).isDone();
            assertThat(result.get()).isFalse();
        }

        @Test
        void runReturnsFalseWhenTargetSegmentIsNotAmongAvailableSegments() throws ExecutionException, InterruptedException {
            // given - token store has available segments, but not the one we want to claim
            when(tokenStore.fetchAvailableSegments(eq(PROCESSOR_NAME), any()))
                    .thenReturn(completedFuture(List.of(OTHER_SEGMENT)));

            // when
            testSubject.run();

            // then
            assertThat(result).isDone();
            assertThat(result.get()).isFalse();
        }
    }

    @Nested
    class WhenSegmentAvailableForClaiming {

        @BeforeEach
        void stubAvailableSegments() {
            when(tokenStore.fetchAvailableSegments(eq(PROCESSOR_NAME), any()))
                    .thenReturn(completedFuture(List.of(SEGMENT)));
        }

        @Test
        void runReturnsTrueWhenTokenIsSuccessfullyClaimed() throws ExecutionException, InterruptedException {
            // given - token store allows fetching the token (i.e., claiming the segment)
            when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_ID), any()))
                    .thenReturn(completedFuture(new GlobalSequenceTrackingToken(0)));

            // when
            testSubject.run();

            // then
            verify(tokenStore).fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_ID), any());
            assertThat(result).isDone();
            assertThat(result.get()).isTrue();
        }

        @Test
        void runReturnsFalseWithoutExceptionalCompletionWhenFetchTokenThrows()
                throws ExecutionException, InterruptedException {
            // the given-token is already claimed by another processor
            when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_ID), any()))
                    .thenReturn(CompletableFuture.failedFuture(new UnableToClaimTokenException("already claimed by another processor")));

            // when
            testSubject.run();

            // then - claim failure is a normal false result, not an exceptional completion
            assertThat(result).isDone();
            assertThat(result.isCompletedExceptionally()).isFalse();
            assertThat(result.get()).isFalse();
        }
    }
}
