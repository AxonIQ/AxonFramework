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
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.TrackerStatus;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.UnableToClaimTokenException;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils;
import org.junit.jupiter.api.*;
import org.mockito.InOrder;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.common.FutureUtils.emptyCompletedFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link SplitTask}.
 *
 * @author Steven van Beelen
 */
class SplitTaskTest {

    private static final String PROCESSOR_NAME = "test";
    private static final int SEGMENT_ID = Segment.ROOT_SEGMENT.getSegmentId();
    private final Map<Integer, WorkPackage> workPackages = new HashMap<>();
    private final TokenStore tokenStore = mock(TokenStore.class);
    private final WorkPackage workPackage = mock(WorkPackage.class);
    private final Map<Integer, java.time.Instant> releasesDeadlines = new HashMap<>();
    private CompletableFuture<Boolean> result;
    private SplitTask testSubject;

    @BeforeEach
    void setUp() {
        result = new CompletableFuture<>();

        testSubject = new SplitTask(
                result,
                PROCESSOR_NAME,
                SEGMENT_ID,
                workPackages,
                releasesDeadlines,
                tokenStore,
                UnitOfWorkTestUtils.SIMPLE_FACTORY,
                ClockUtils.get()
        );
    }

    @Test
    void runSplitsSegmentFromWorkPackage() throws ExecutionException, InterruptedException {
        Segment testSegmentToSplit = Segment.ROOT_SEGMENT;
        TrackingToken testTokenToSplit = new GlobalSequenceTrackingToken(0);

        TrackerStatus[] expectedTokens = TrackerStatus.split(testSegmentToSplit, testTokenToSplit);
        TrackerStatus expectedOriginal = expectedTokens[0];
        TrackerStatus expectedSplit = expectedTokens[1];

        when(workPackage.segment()).thenReturn(testSegmentToSplit);
        when(workPackage.abort(null)).thenReturn(emptyCompletedFuture());
        when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_ID), any()))
                .thenReturn(completedFuture(testTokenToSplit));
        when(tokenStore.initializeSegment(any(), eq(PROCESSOR_NAME), eq(new Segment(0, 1)), any()))
                .thenReturn(emptyCompletedFuture());
        when(tokenStore.initializeSegment(any(), eq(PROCESSOR_NAME), eq(new Segment(1, 1)), any()))
                .thenReturn(emptyCompletedFuture());
        when(tokenStore.deleteToken(eq(PROCESSOR_NAME), eq(Segment.ROOT_SEGMENT.getSegmentId()), any()))
                .thenReturn(emptyCompletedFuture());

        workPackages.put(SEGMENT_ID, workPackage);

        testSubject.run();

        // original token must be reinitialized (due to mask change):
        verify(tokenStore).initializeSegment(eq(expectedOriginal.getTrackingToken()),
                                             eq(PROCESSOR_NAME),
                                             eq(expectedOriginal.getSegment()),
                                             any());
        verify(tokenStore).initializeSegment(eq(expectedSplit.getTrackingToken()),
                                             eq(PROCESSOR_NAME),
                                             eq(expectedSplit.getSegment()),
                                             any());
        verify(tokenStore).deleteToken(eq(PROCESSOR_NAME),
                                       eq(expectedOriginal.getSegment().getSegmentId()),
                                       any());
        verify(tokenStore, never()).releaseClaim(any(), anyInt(), any());
        assertTrue(result.isDone());
        assertTrue(result.get());
    }

    @Test
    void runSplitsSegmentAfterClaiming() throws ExecutionException, InterruptedException {
        Segment testSegmentToSplit = Segment.ROOT_SEGMENT;
        TrackingToken testTokenToSplit = new GlobalSequenceTrackingToken(0);

        TrackerStatus[] expectedTokens = TrackerStatus.split(testSegmentToSplit, testTokenToSplit);
        TrackerStatus expectedOriginal = expectedTokens[0];
        TrackerStatus expectedSplit = expectedTokens[1];

        when(tokenStore.fetchSegment(eq(PROCESSOR_NAME), eq(Segment.ROOT_SEGMENT.getSegmentId()), any()))
                .thenReturn(completedFuture(Segment.ROOT_SEGMENT));
        when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_ID), any()))
                .thenReturn(completedFuture(testTokenToSplit));
        when(tokenStore.initializeSegment(any(), eq(PROCESSOR_NAME), eq(new Segment(0, 1)), any()))
                .thenReturn(emptyCompletedFuture());
        when(tokenStore.initializeSegment(any(), eq(PROCESSOR_NAME), eq(new Segment(1, 1)), any()))
                .thenReturn(emptyCompletedFuture());
        when(tokenStore.deleteToken(eq(PROCESSOR_NAME), eq(Segment.ROOT_SEGMENT.getSegmentId()), any()))
                .thenReturn(emptyCompletedFuture());

        testSubject.run();

        // original token must be reinitialized (due to mask change):
        verify(tokenStore).initializeSegment(eq(expectedOriginal.getTrackingToken()),
                                             eq(PROCESSOR_NAME),
                                             eq(expectedOriginal.getSegment()),
                                             any());
        verify(tokenStore).initializeSegment(eq(expectedSplit.getTrackingToken()),
                                             eq(PROCESSOR_NAME),
                                             eq(expectedSplit.getSegment()),
                                             any());
        verify(tokenStore).deleteToken(eq(PROCESSOR_NAME),
                                       eq(expectedOriginal.getSegment().getSegmentId()),
                                       any());
        verify(tokenStore, never()).releaseClaim(any(), anyInt(), any());
        assertTrue(result.isDone());
        assertTrue(result.get());
    }

    @Test
    void runCompletesExceptionallyThroughUnableToClaimTokenException() {
        when(tokenStore.fetchSegment(eq(PROCESSOR_NAME), eq(Segment.ROOT_SEGMENT.getSegmentId()), any()))
                .thenReturn(completedFuture(Segment.ROOT_SEGMENT));
        when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_ID), any()))
                .thenThrow(new UnableToClaimTokenException("some exception"));

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
        when(tokenStore.fetchSegment(eq(PROCESSOR_NAME), eq(Segment.ROOT_SEGMENT.getSegmentId()), any()))
                .thenThrow(new IllegalStateException("some exception"));

        testSubject.run();

        assertTrue(result.isDone());
        assertTrue(result.isCompletedExceptionally());
        assertThatThrownBy(() -> result.get())
            .isInstanceOf(ExecutionException.class)
            .cause()
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void description() {
        String result = testSubject.getDescription();
        assertNotNull(result);
        assertTrue(result.contains("Split"));
    }

    @Test
    void runSplitsSegment_deleteTokenCalledBeforeReInitialization_andNoSeparateReleaseClaim()
            throws ExecutionException, InterruptedException {
        Segment testSegmentToSplit = Segment.ROOT_SEGMENT;
        TrackingToken testTokenToSplit = new GlobalSequenceTrackingToken(0);

        TrackerStatus[] expectedTokens = TrackerStatus.split(testSegmentToSplit, testTokenToSplit);
        TrackerStatus expectedOriginal = expectedTokens[0]; // Segment(0, mask=1)
        TrackerStatus expectedSplit   = expectedTokens[1]; // Segment(1, mask=1)

        when(workPackage.segment()).thenReturn(testSegmentToSplit);
        when(workPackage.abort(null)).thenReturn(emptyCompletedFuture());
        when(tokenStore.fetchToken(eq(PROCESSOR_NAME), eq(SEGMENT_ID), any()))
                .thenReturn(completedFuture(testTokenToSplit));
        when(tokenStore.initializeSegment(any(), eq(PROCESSOR_NAME), eq(new Segment(0, 1)), any()))
                .thenReturn(emptyCompletedFuture());
        when(tokenStore.initializeSegment(any(), eq(PROCESSOR_NAME), eq(new Segment(1, 1)), any()))
                .thenReturn(emptyCompletedFuture());
        when(tokenStore.deleteToken(eq(PROCESSOR_NAME), eq(Segment.ROOT_SEGMENT.getSegmentId()), any()))
                .thenReturn(emptyCompletedFuture());

        workPackages.put(SEGMENT_ID, workPackage);

        testSubject.run();

        verify(tokenStore, never()).releaseClaim(any(), anyInt(), any());
        verify(tokenStore).deleteToken(eq(PROCESSOR_NAME),
                                       eq(Segment.ROOT_SEGMENT.getSegmentId()),
                                       any());

        InOrder inOrder = inOrder(tokenStore);
        inOrder.verify(tokenStore).initializeSegment(
                eq(expectedSplit.getTrackingToken()), eq(PROCESSOR_NAME), eq(expectedSplit.getSegment()), any());
        inOrder.verify(tokenStore).deleteToken(
                eq(PROCESSOR_NAME), eq(expectedOriginal.getSegment().getSegmentId()), any());
        inOrder.verify(tokenStore).initializeSegment(
                eq(expectedOriginal.getTrackingToken()), eq(PROCESSOR_NAME), eq(expectedOriginal.getSegment()), any());

        assertTrue(result.isDone());
        assertTrue(result.get());
    }
}