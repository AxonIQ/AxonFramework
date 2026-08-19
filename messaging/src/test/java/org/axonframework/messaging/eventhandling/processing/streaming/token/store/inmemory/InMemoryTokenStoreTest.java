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

package org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory;

import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.UnableToClaimTokenException;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.UnableToInitializeTokenException;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.common.FutureUtils.joinAndUnwrap;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link InMemoryTokenStore}.
 *
 * @author Allard Buijze
 */
class InMemoryTokenStoreTest {

    private InMemoryTokenStore testSubject;

    @BeforeEach
    void setUp() {
        testSubject = new InMemoryTokenStore();
    }

    @Test
    void initializeTokens() {
        List<Segment> createdSegments = joinAndUnwrap(
            testSubject.initializeTokenSegments("test1", 7, null, createProcessingContext())
        );

        List<Segment> actual = joinAndUnwrap(testSubject.fetchSegments("test1", null));

        assertThat(actual).containsExactlyInAnyOrderElementsOf(createdSegments);
    }

    @Test
    void initializeTokenSegmentsFailsWhenSegmentsAlreadyPresent() {
        joinAndUnwrap(testSubject.initializeTokenSegments(
                "test1",
                7,
                null,
                createProcessingContext()));

        assertThrows(UnableToClaimTokenException.class, () -> joinAndUnwrap(testSubject.initializeTokenSegments(
                "test1",
                1,
                null,
                createProcessingContext())));
    }

    @Test
    void identifierIsPresent() {
        assertNotNull(joinAndUnwrap(testSubject.retrieveStorageIdentifier(mock())));
    }

    @Test
    void initializeTokensAtGivenPosition() {
        List<Segment> createdSegments = joinAndUnwrap(testSubject.initializeTokenSegments(
                "test1",
                7,
                new GlobalSequenceTrackingToken(10),
                createProcessingContext()
        ));

        List<Segment> actual = joinAndUnwrap(testSubject.fetchSegments("test1", null));

        assertThat(actual).containsExactlyInAnyOrderElementsOf(createdSegments);

        for (Segment segment : actual) {
            assertEquals(new GlobalSequenceTrackingToken(10),
                         joinAndUnwrap(testSubject.fetchToken("test1", segment, null)));
        }
    }

    @Test
    void updateToken() {
        var ctx = createProcessingContext();
        joinAndUnwrap(testSubject.initializeTokenSegments("test1", 1, null, ctx));
        joinAndUnwrap(
                testSubject.storeToken(new GlobalSequenceTrackingToken(1), "test1", 0, ctx)
        );

        assertEquals(new GlobalSequenceTrackingToken(1),
                     joinAndUnwrap(testSubject.fetchToken("test1", 0, null)));
    }

    @Test
    void initializeAtGivenToken() {
        joinAndUnwrap(testSubject.initializeTokenSegments(
                "test1",
                2,
                new GlobalSequenceTrackingToken(1),
                createProcessingContext()
        ));

        assertEquals(new GlobalSequenceTrackingToken(1),
                     joinAndUnwrap(testSubject.fetchToken("test1", 0, null)));
        assertEquals(new GlobalSequenceTrackingToken(1),
                     joinAndUnwrap(testSubject.fetchToken("test1", 1, null)));
    }

    @Test
    void initializeTokensWhileAlreadyPresent() {
        assertThrows(UnableToClaimTokenException.class,
                     () -> joinAndUnwrap(testSubject.fetchToken("test1", 1, null)));
    }

    @Test
    void querySegments() {
        prepareTokenStore(createProcessingContext());

        {
            List<Segment> segments = joinAndUnwrap(testSubject.fetchSegments("proc1", null));
            assertThat(segments.size(), is(2));
        }
        {
            List<Segment> segments = joinAndUnwrap(testSubject.fetchSegments("proc2", null));
            assertThat(segments.size(), is(1));
        }
        {
            List<Segment> segments = joinAndUnwrap(testSubject.fetchSegments("proc3", null));
            assertThat(segments.size(), is(0));
        }
    }

    @Test
    void queryAvailableSegments() {
        prepareTokenStore(createProcessingContext());

        {
            final List<Segment> segments =
                    joinAndUnwrap(testSubject.fetchAvailableSegments("proc1", null));
            assertThat(segments.size(), is(2));
            assertThat(segments.get(0).getSegmentId(), is(0));
            assertThat(segments.get(1).getSegmentId(), is(1));
        }
        {
            final List<Segment> segments =
                    joinAndUnwrap(testSubject.fetchAvailableSegments("proc2", null));
            assertThat(segments.size(), is(1));
            assertThat(segments.getFirst().getSegmentId(), is(0));
        }
        {
            final List<Segment> segments =
                    joinAndUnwrap(testSubject.fetchAvailableSegments("proc3", null));
            assertThat(segments.size(), is(0));
        }
    }

    @Nested
    class CompletableFutureFailures {

        @Test
        void fetchTokenForMissingSegmentReturnsFailedFuture() {
            // when
            CompletableFuture<TrackingToken> result = assertDoesNotThrow(
                    () -> testSubject.fetchToken("test1", 1, null)
            );

            // then
            assertThat(result).isCompletedExceptionally();
            assertThat(result.exceptionNow())
                    .isInstanceOf(UnableToClaimTokenException.class)
                    .hasMessageContaining("No token was initialized for segment 1 for processor test1");
        }

        @Test
        void storeTokenForMissingSegmentReturnsFailedFuture() {
            // when
            CompletableFuture<Void> result = assertDoesNotThrow(
                    () -> testSubject.storeToken(new GlobalSequenceTrackingToken(1),
                                                 "test1",
                                                 1,
                                                 createProcessingContext())
            );

            // then
            assertThat(result).isCompletedExceptionally();
            assertThat(result.exceptionNow())
                    .isInstanceOf(UnableToClaimTokenException.class)
                    .hasMessageContaining("No such token for processor 'test1' and segment 1");
        }

        @Test
        void initializeSegmentForExistingSegmentReturnsFailedFuture() {
            // given
            joinAndUnwrap(testSubject.initializeSegment(null,
                                                        "test1",
                                                        Segment.ROOT_SEGMENT,
                                                        createProcessingContext()));

            // when
            CompletableFuture<Void> result = assertDoesNotThrow(
                    () -> testSubject.initializeSegment(null,
                                                        "test1",
                                                        Segment.ROOT_SEGMENT,
                                                        createProcessingContext())
            );

            // then
            assertThat(result).isCompletedExceptionally();
            assertThat(result.exceptionNow())
                    .isInstanceOf(UnableToInitializeTokenException.class)
                    .hasMessageContaining("Token was already present");
        }
    }

    private ProcessingContext createProcessingContext() {
        return new StubProcessingContext();
    }

    private void prepareTokenStore(ProcessingContext ctx) {
        joinAndUnwrap(testSubject.initializeTokenSegments("test", 1, null, ctx));
        joinAndUnwrap(testSubject.initializeTokenSegments("proc1", 2, null, ctx));
        joinAndUnwrap(testSubject.initializeTokenSegments("proc2", 1, null, ctx));

        assertNull(joinAndUnwrap(testSubject.fetchToken("test", 0, null)));
        joinAndUnwrap(testSubject.storeToken(new GlobalSequenceTrackingToken(1L), "proc1", 0, ctx));
        joinAndUnwrap(testSubject.storeToken(new GlobalSequenceTrackingToken(2L), "proc1", 1, ctx));
        joinAndUnwrap(testSubject.storeToken(new GlobalSequenceTrackingToken(2L), "proc2", 0, ctx));
    }
}