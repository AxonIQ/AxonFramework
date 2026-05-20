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

import org.axonframework.common.ClockUtils;
import org.axonframework.common.FutureUtils;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.MergedTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.UnableToClaimTokenException;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test class validating the {@link MergeTask}.
 *
 * @author Steven van Beelen
 */
class MergeTaskTest {

    private static final String PROCESSOR_NAME = "test";
    private static final int SEGMENT_TO_MERGE = 0;
    private static final int SEGMENT_TO_BE_MERGED = 1;
    private static final Segment SEGMENT_ZERO = Segment.splitBalanced(Segment.ROOT_SEGMENT, 1).get(SEGMENT_TO_MERGE);
    private static final Segment SEGMENT_ONE = Segment.splitBalanced(Segment.ROOT_SEGMENT, 1).get(SEGMENT_TO_BE_MERGED);
    private final Map<Integer, WorkPackage> workPackages = new HashMap<>();
    private final TokenStore tokenStore = mock(TokenStore.class);
    private final WorkPackage workPackageOne = mock(WorkPackage.class);
    private final WorkPackage workPackageTwo = mock(WorkPackage.class);
    private CompletableFuture<Boolean> result;
    private MergeTask testSubject;

    private final Map<Integer, java.time.Instant> releasesDeadlines = new HashMap<>();

    @BeforeEach
    void setUp() {
        result = new CompletableFuture<>();

        when(tokenStore.fetchSegment(eq(PROCESSOR_NAME), eq(SEGMENT_ZERO.getSegmentId()), any()))
            .thenReturn(completedFuture(SEGMENT_ZERO));
        when(tokenStore.fetchSegment(eq(PROCESSOR_NAME), eq(SEGMENT_ONE.getSegmentId()), any()))
            .thenReturn(completedFuture(SEGMENT_ONE));

        releasesDeadlines.clear();

        testSubject = new MergeTask(
                result, PROCESSOR_NAME, SEGMENT_TO_MERGE, workPackages, releasesDeadlines, tokenStore,
                new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE), ClockUtils.get()
        );
    }

    @Test
    void runReturnsFalseThroughSegmentIdsWhichCannotMerge() throws ExecutionException, InterruptedException {
        when(tokenStore.fetchSegment(eq(PROCESSOR_NAME), eq(Segment.ROOT_SEGMENT.getSegmentId()), any()))
            .thenReturn(completedFuture(Segment.ROOT_SEGMENT));

        testSubject.run();

        verify(tokenStore).fetchSegment(eq(PROCESSOR_NAME), eq(Segment.ROOT_SEGMENT.getSegmentId()), any());
        assertTrue(result.isDone());
        assertFalse(result.get());
    }

    @Test
    void runMergeSegmentsFromWorkPackages() throws ExecutionException, InterruptedException {
        TrackingToken testTokenToMerge = new GlobalSequenceTrackingToken(0);
        TrackingToken testTokenToBeMerged = new GlobalSequenceTrackingToken(1);

        when(workPackageOne.segment()).thenReturn(SEGMENT_ZERO);
        when(workPackageOne.abort(null)).thenReturn(FutureUtils.emptyCompletedFuture());
        when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_MERGE), any()))
                .thenReturn(completedFuture(testTokenToMerge));
        workPackages.put(SEGMENT_TO_MERGE, workPackageOne);
        when(workPackageTwo.segment()).thenReturn(SEGMENT_ONE);
        when(workPackageTwo.abort(null)).thenReturn(FutureUtils.emptyCompletedFuture());
        when(tokenStore.deleteToken(anyString(), anyInt(), any())).thenReturn(FutureUtils.emptyCompletedFuture());
        when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_BE_MERGED), any()))
                .thenReturn(completedFuture(testTokenToBeMerged));
        when(tokenStore.initializeSegment(any(), eq(PROCESSOR_NAME), eq(new Segment(0, 0)), any()))
                .thenReturn(FutureUtils.emptyCompletedFuture());

        workPackages.put(SEGMENT_TO_BE_MERGED, workPackageTwo);

        ArgumentCaptor<TrackingToken> mergedTokenCaptor = ArgumentCaptor.forClass(TrackingToken.class);

        testSubject.run();

        verify(tokenStore).fetchSegment(eq(PROCESSOR_NAME), eq(SEGMENT_ZERO.getSegmentId()), any());
        verify(tokenStore).fetchSegment(eq(PROCESSOR_NAME), eq(SEGMENT_ONE.getSegmentId()), any());
        verify(tokenStore).deleteToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_BE_MERGED), any());
        verify(tokenStore).deleteToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_MERGE), any());
        verify(tokenStore).initializeSegment(mergedTokenCaptor.capture(),
                                             eq(PROCESSOR_NAME),
                                             eq(new Segment(0, 0)),
                                             any());
        TrackingToken resultToken = mergedTokenCaptor.getValue();
        assertTrue(resultToken.getClass().isAssignableFrom(MergedTrackingToken.class));
        assertEquals(testTokenToMerge, ((MergedTrackingToken) resultToken).lowerSegmentToken());
        assertEquals(testTokenToBeMerged, ((MergedTrackingToken) resultToken).upperSegmentToken());
        verify(tokenStore, never()).releaseClaim(any(), anyInt(), any());

        assertTrue(result.isDone());
        assertTrue(result.get());
    }

    @Test
    void runMergeSegmentsAfterClaimingBoth() throws ExecutionException, InterruptedException {
        TrackingToken testTokenToMerge = new GlobalSequenceTrackingToken(0);
        TrackingToken testTokenToBeMerged = new GlobalSequenceTrackingToken(1);
        when(tokenStore.deleteToken(anyString(), anyInt(), any())).thenReturn(FutureUtils.emptyCompletedFuture());
        when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_MERGE), any()))
                .thenReturn(completedFuture(testTokenToMerge));
        when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_BE_MERGED), any()))
                .thenReturn(completedFuture(testTokenToBeMerged));
        when(tokenStore.initializeSegment(any(), eq(PROCESSOR_NAME), eq(new Segment(0, 0)), any()))
                .thenReturn(FutureUtils.emptyCompletedFuture());

        ArgumentCaptor<TrackingToken> mergedTokenCaptor = ArgumentCaptor.forClass(TrackingToken.class);

        testSubject.run();

        verify(tokenStore).fetchSegment(eq(PROCESSOR_NAME), eq(SEGMENT_ZERO.getSegmentId()), any());
        verify(tokenStore).fetchSegment(eq(PROCESSOR_NAME), eq(SEGMENT_ONE.getSegmentId()), any());
        verify(tokenStore).deleteToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_BE_MERGED), any());
        verify(tokenStore).deleteToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_MERGE), any());
        verify(tokenStore).initializeSegment(mergedTokenCaptor.capture(),
                                             eq(PROCESSOR_NAME),
                                             eq(new Segment(0, 0)),
                                             any());
        TrackingToken resultToken = mergedTokenCaptor.getValue();
        assertTrue(resultToken.getClass().isAssignableFrom(MergedTrackingToken.class));
        assertEquals(testTokenToMerge, ((MergedTrackingToken) resultToken).lowerSegmentToken());
        assertEquals(testTokenToBeMerged, ((MergedTrackingToken) resultToken).upperSegmentToken());
        verify(tokenStore, never()).releaseClaim(any(), anyInt(), any());

        assertTrue(result.isDone());
        assertTrue(result.get());
    }

    @Test
    void runMergeSegmentsFromWorkPackageAndClaimedSegment() throws ExecutionException, InterruptedException {
        TrackingToken testTokenToMerge = new GlobalSequenceTrackingToken(0);
        TrackingToken testTokenToBeMerged = new GlobalSequenceTrackingToken(1);

        when(workPackageOne.segment()).thenReturn(SEGMENT_ZERO);
        when(workPackageOne.abort(null)).thenReturn(FutureUtils.emptyCompletedFuture());
        when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_MERGE), any()))
                .thenReturn(completedFuture(testTokenToMerge));
        workPackages.put(SEGMENT_TO_MERGE, workPackageOne);
        when(tokenStore.deleteToken(anyString(), anyInt(), any())).thenReturn(FutureUtils.emptyCompletedFuture());
        when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_BE_MERGED), any()))
                .thenReturn(completedFuture(testTokenToBeMerged));
        when(tokenStore.initializeSegment(any(), eq(PROCESSOR_NAME), eq(new Segment(0, 0)), any()))
                .thenReturn(FutureUtils.emptyCompletedFuture());

        ArgumentCaptor<TrackingToken> mergedTokenCaptor = ArgumentCaptor.forClass(TrackingToken.class);

        testSubject.run();

        verify(tokenStore).fetchSegment(eq(PROCESSOR_NAME), eq(SEGMENT_ZERO.getSegmentId()), any());
        verify(tokenStore).fetchSegment(eq(PROCESSOR_NAME), eq(SEGMENT_ONE.getSegmentId()), any());
        verify(tokenStore).deleteToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_BE_MERGED), any());
        verify(tokenStore).deleteToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_MERGE), any());
        verify(tokenStore).initializeSegment(mergedTokenCaptor.capture(),
                                             eq(PROCESSOR_NAME),
                                             eq(new Segment(0, 0)),
                                             any());
        TrackingToken resultToken = mergedTokenCaptor.getValue();
        assertTrue(resultToken.getClass().isAssignableFrom(MergedTrackingToken.class));
        assertEquals(testTokenToMerge, ((MergedTrackingToken) resultToken).lowerSegmentToken());
        assertEquals(testTokenToBeMerged, ((MergedTrackingToken) resultToken).upperSegmentToken());
        verify(tokenStore, never()).releaseClaim(any(), anyInt(), any());

        assertTrue(result.isDone());
        assertTrue(result.get());
    }

    @Test
    void runCompletesExceptionallyThroughUnableToClaimTokenExceptionOnFetch() {
        when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_MERGE), any()))
                .thenReturn(CompletableFuture.failedFuture(new UnableToClaimTokenException("some exception")));

        testSubject.run();

        assertTrue(result.isDone());
        assertTrue(result.isCompletedExceptionally());
        assertThatThrownBy(() -> result.get())
            .isInstanceOf(ExecutionException.class)
            .cause()
            .isInstanceOf(UnableToClaimTokenException.class);
    }

    @Test
    void runCompletesExceptionallyThroughUnableToClaimTokenExceptionOnDelete() {
        when(workPackageOne.segment()).thenReturn(SEGMENT_ZERO);
        when(workPackageOne.abort(null)).thenReturn(FutureUtils.emptyCompletedFuture());
        when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_MERGE), any()))
                .thenReturn(completedFuture(new GlobalSequenceTrackingToken(0)));
        workPackages.put(SEGMENT_TO_MERGE, workPackageOne);
        when(workPackageTwo.segment()).thenReturn(SEGMENT_ONE);
        when(workPackageTwo.abort(null)).thenReturn(FutureUtils.emptyCompletedFuture());
        when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_BE_MERGED), any()))
                .thenReturn(completedFuture(new GlobalSequenceTrackingToken(1)));
        when(tokenStore.deleteToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_MERGE), any()))
                .thenReturn(FutureUtils.emptyCompletedFuture());

        workPackages.put(SEGMENT_TO_BE_MERGED, workPackageTwo);

        when(tokenStore.deleteToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_BE_MERGED), any()))
                .thenReturn(CompletableFuture.failedFuture(new UnableToClaimTokenException("some exception")));

        testSubject.run();

        assertTrue(result.isDone());
        assertTrue(result.isCompletedExceptionally());
        assertThatThrownBy(() -> result.get())
            .isInstanceOf(ExecutionException.class)
            .cause()
            .isInstanceOf(UnableToClaimTokenException.class);
    }

    @Test
    void runCompletesExceptionallyThroughOtherException() {
        when(workPackageOne.segment()).thenReturn(SEGMENT_ZERO);
        when(workPackageOne.abort(null)).thenReturn(FutureUtils.emptyCompletedFuture());
        when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_MERGE), any()))
                .thenReturn(completedFuture(new GlobalSequenceTrackingToken(0)));
        workPackages.put(SEGMENT_TO_MERGE, workPackageOne);
        when(workPackageTwo.segment()).thenReturn(SEGMENT_ONE);
        when(workPackageTwo.abort(null)).thenReturn(FutureUtils.emptyCompletedFuture());
        when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_BE_MERGED), any()))
                .thenReturn(completedFuture(new GlobalSequenceTrackingToken(1)));
        when(tokenStore.deleteToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_MERGE), any()))
                .thenReturn(FutureUtils.emptyCompletedFuture());
        workPackages.put(SEGMENT_TO_BE_MERGED, workPackageTwo);

        when(tokenStore.deleteToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_BE_MERGED), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("some exception")));

        testSubject.run();

        assertTrue(result.isDone());
        assertTrue(result.isCompletedExceptionally());
        assertThatThrownBy(() -> result.get())
            .isInstanceOf(ExecutionException.class)
            .cause()
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runMergeSegmentsPlacesLowerSegmentTokenFirstRegardlessOfInitiatingSegment() {
        // given - merge is initiated from segment 1 (higher ID), so thatSegment (0) < thisSegment (1) is true
        // this exercises the swapped branch: MergedTrackingToken.merged(thatToken, thisToken)
        TrackingToken tokenForSegment0 = new GlobalSequenceTrackingToken(0);
        TrackingToken tokenForSegment1 = new GlobalSequenceTrackingToken(1);

        MergeTask mergeFromSegmentOne = new MergeTask(
                new CompletableFuture<>(), PROCESSOR_NAME, SEGMENT_TO_BE_MERGED, workPackages,
                releasesDeadlines, tokenStore, new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE),
                ClockUtils.get()
        );
        when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_MERGE), any()))
                .thenReturn(completedFuture(tokenForSegment0));
        when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_BE_MERGED), any()))
                .thenReturn(completedFuture(tokenForSegment1));
        when(tokenStore.deleteToken(anyString(), anyInt(), any())).thenReturn(FutureUtils.emptyCompletedFuture());
        when(tokenStore.releaseClaim(anyString(), anyInt(), any())).thenReturn(FutureUtils.emptyCompletedFuture());
        when(tokenStore.initializeSegment(any(), eq(PROCESSOR_NAME), eq(new Segment(0, 0)), any()))
                .thenReturn(FutureUtils.emptyCompletedFuture());

        ArgumentCaptor<TrackingToken> mergedTokenCaptor = ArgumentCaptor.forClass(TrackingToken.class);

        // when
        mergeFromSegmentOne.run();

        // then - lower-ID segment token is always placed first, regardless of which segment initiated the merge
        verify(tokenStore).initializeSegment(mergedTokenCaptor.capture(), eq(PROCESSOR_NAME), eq(new Segment(0, 0)), any());
        TrackingToken resultToken = mergedTokenCaptor.getValue();
        assertThat(resultToken).isInstanceOf(MergedTrackingToken.class);
        assertThat(((MergedTrackingToken) resultToken).lowerSegmentToken()).isEqualTo(tokenForSegment0);
        assertThat(((MergedTrackingToken) resultToken).upperSegmentToken()).isEqualTo(tokenForSegment1);
    }

    @Nested
    class WhenMergeSegmentsFails {

        @Test
        void runClearsReleasesDeadlinesEvenWhenMergedWithThrows() {
            // given - fetchSegment returns an incompatible thatSegment (different mask), causing mergedWith to throw
            Segment incompatibleSegment = new Segment(1, 3);
            when(tokenStore.fetchSegment(eq(PROCESSOR_NAME), eq(SEGMENT_ONE.getSegmentId()), any()))
                    .thenReturn(completedFuture(incompatibleSegment));
            when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_MERGE), any()))
                    .thenReturn(completedFuture(new GlobalSequenceTrackingToken(0)));
            when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_TO_BE_MERGED), any()))
                    .thenReturn(completedFuture(new GlobalSequenceTrackingToken(1)));

            // when
            testSubject.run();

            // then - the result is exceptionally completed, and both release deadlines are removed
            assertThat(result).isDone();
            assertThat(result.isCompletedExceptionally()).isTrue();
            assertThat(releasesDeadlines).isEmpty();
        }
    }

    @Test
    void description() {
        String result = testSubject.getDescription();
        assertNotNull(result);
        assertTrue(result.contains("Merge"));
    }
}