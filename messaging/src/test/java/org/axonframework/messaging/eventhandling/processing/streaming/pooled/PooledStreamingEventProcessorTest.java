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

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.FutureUtils;
import org.axonframework.common.util.DelegateScheduledExecutorService;
import org.axonframework.common.util.MockException;
import org.axonframework.conversion.DelegatingGeneralConverter;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.conversion.TestConverter;
import org.axonframework.messaging.core.ApplicationContext;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.AsyncInMemoryStreamableEventSource;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.RecordingEventHandlingComponent;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.errorhandling.ErrorContext;
import org.axonframework.messaging.eventhandling.processing.errorhandling.ErrorHandler;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.SegmentChangeListener;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.axonframework.messaging.eventhandling.replay.ReplayBlockingEventHandlingComponent;
import org.axonframework.messaging.eventhandling.replay.ReplayStatus;
import org.axonframework.messaging.eventhandling.replay.ReplayStatusChangedHandler;
import org.axonframework.messaging.eventhandling.replay.ResetHandler;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.axonframework.common.FutureUtils.joinAndUnwrap;
import static org.axonframework.common.util.AssertUtils.assertWithin;
import static org.axonframework.messaging.eventhandling.EventTestUtils.createEvents;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link PooledStreamingEventProcessor}.
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @author Steven van Beelen
 */
@Tags({
        @Tag("flaky"),
})
class PooledStreamingEventProcessorTest {

    private static final Logger logger = LoggerFactory.getLogger(
            PooledStreamingEventProcessorTest.class);

    private static final String PROCESSOR_NAME = "test";

    private PooledStreamingEventProcessor testSubject;
    private ProcessingContext processingContext;
    private AsyncInMemoryStreamableEventSource stubMessageSource;
    private InMemoryTokenStore tokenStore;
    private ScheduledExecutorService coordinatorExecutor;
    private ScheduledExecutorService workerExecutor;
    private SimpleEventHandlingComponent simpleEhc;
    private RecordingEventHandlingComponent defaultEventHandlingComponent;
    private GeneralConverter converter;

    @BeforeEach
    void setUp() {
        processingContext = mock(ProcessingContext.class);
        stubMessageSource = spy(new AsyncInMemoryStreamableEventSource());
        when(stubMessageSource.firstToken(null))
                .thenReturn(CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(-1)));
        tokenStore = spy(new InMemoryTokenStore());
        coordinatorExecutor = spy(new DelegateScheduledExecutorService(Executors.newScheduledThreadPool(2)));
        workerExecutor = new DelegateScheduledExecutorService(Executors.newScheduledThreadPool(8));
        simpleEhc = SimpleEventHandlingComponent.create("test");
        simpleEhc.subscribe(new QualifiedName(Integer.class), (event, ctx) -> MessageStream.empty());
        defaultEventHandlingComponent = spy(new RecordingEventHandlingComponent(simpleEhc));
        converter = new DelegatingGeneralConverter(TestConverter.JACKSON.getConverter());
        withTestSubject(List.of()); // default always applied
    }

    @AfterEach
    void tearDown() {
        FutureUtils.joinAndUnwrap(testSubject.shutdown());
        coordinatorExecutor.shutdown();
        workerExecutor.shutdown();
    }

    private PooledStreamingEventProcessor withTestSubject(List<EventHandlingComponent> eventHandlingComponents) {
        return withTestSubject(eventHandlingComponents, UnaryOperator.identity());
    }

    private PooledStreamingEventProcessor withTestSubject(
            List<EventHandlingComponent> eventHandlingComponents,
            UnaryOperator<PooledStreamingEventProcessorConfiguration> configOverride
    ) {
        var componentsWithDefault = new ArrayList<>(eventHandlingComponents);
        componentsWithDefault.add(defaultEventHandlingComponent);

        TestApplicationContext testApplicationContext = new TestApplicationContext();
        testApplicationContext.addComponent(GeneralConverter.class, null, converter);
        EventProcessorConfiguration baseConfig = new EventProcessorConfiguration(PROCESSOR_NAME, null);
        var testDefaultConfiguration = new PooledStreamingEventProcessorConfiguration(baseConfig)
                .eventSource(stubMessageSource)
                .unitOfWorkFactory(new SimpleUnitOfWorkFactory(testApplicationContext))
                .tokenStore(tokenStore)
                .coordinatorExecutor(coordinatorExecutor)
                .workerExecutor(workerExecutor)
                .initialSegmentCount(8)
                .claimExtensionThreshold(500);
        var customizedConfiguration = configOverride.apply(testDefaultConfiguration);

        var processor = new PooledStreamingEventProcessor(
                PROCESSOR_NAME,
                componentsWithDefault,
                customizedConfiguration
        );
        this.testSubject = processor;
        return processor;
    }

    @Test
    void processorOnlyTriesToClaimAvailableSegments() {
        var ctx = createProcessingContext();

        List<Segment> createdSegments = joinAndUnwrap(tokenStore.initializeTokenSegments(
                "test",
                4,
                new GlobalSequenceTrackingToken(1),
                createProcessingContext()
        ));
        assertThat(createdSegments).isNotNull();

        joinAndUnwrap(
                tokenStore.storeToken(new GlobalSequenceTrackingToken(2L), "test", 1, ctx)
        );

        when(tokenStore.fetchAvailableSegments(eq(testSubject.name()), any()))
                .thenReturn(completedFuture(Collections.singletonList(createdSegments.get(2))));

        startEventProcessor();

        assertWithin(1, TimeUnit.SECONDS, () -> assertEquals(1, testSubject.processingStatus().size()));
        assertWithin(1, TimeUnit.SECONDS, () -> assertTrue(testSubject.processingStatus().containsKey(2)));
        verify(tokenStore, never())
                .fetchToken(eq(testSubject.name()), intThat(i -> Arrays.asList(0, 1, 3).contains(i)), any());
    }

    private void startEventProcessor() {
        testSubject.start().join();
    }

    @Test
    void handlingEventsByMultipleEventHandlingComponents() {
        // given
        SimpleEventHandlingComponent ehc1 = SimpleEventHandlingComponent.create("test");
        ehc1.subscribe(new QualifiedName(String.class), (event, ctx) -> MessageStream.empty());
        var eventHandlingComponent1 = new RecordingEventHandlingComponent(ehc1);
        SimpleEventHandlingComponent ehc2 = SimpleEventHandlingComponent.create("test");
        ehc2.subscribe(new QualifiedName(String.class), (event, ctx) -> MessageStream.empty());
        var eventHandlingComponent2 = new RecordingEventHandlingComponent(ehc2);

        List<EventHandlingComponent> components = List.of(eventHandlingComponent1, eventHandlingComponent2);
        withTestSubject(components, customization -> customization.initialSegmentCount(1));

        // when
        EventMessage supportedEvent1 = EventTestUtils.asEventMessage("Payload");
        EventMessage supportedEvent2 = EventTestUtils.asEventMessage("Payload");
        stubMessageSource.publishMessage(supportedEvent1);
        stubMessageSource.publishMessage(supportedEvent2);
        startEventProcessor();

        // then
        await().atMost(200, TimeUnit.MILLISECONDS)
               .untilAsserted(() -> assertThat(testSubject.processingStatus()).hasSizeGreaterThan(0));

        // then
        assertThat(eventHandlingComponent1.recorded()).containsExactly(supportedEvent1, supportedEvent2);
        assertThat(eventHandlingComponent2.recorded()).containsExactly(supportedEvent1, supportedEvent2);

        // then
        await().atMost(200, TimeUnit.MILLISECONDS)
               .untilAsserted(() -> {
                   long currentPosition = testSubject.processingStatus().get(0).getCurrentPosition().orElse(0);
                   assertThat(currentPosition).isEqualTo(2);
               });
    }

    private ProcessingContext createProcessingContext() {
        return new StubProcessingContext();
    }

    @Nested
    class LifecycleTest {

        @Test
        void startShutsDownImmediatelyIfCoordinatorExecutorThrowsAnException() {
            // given
            doThrow(new IllegalArgumentException("Some exception")).when(coordinatorExecutor)
                                                                   .submit(any(Runnable.class));

            // when
            assertThrows(IllegalArgumentException.class, () -> FutureUtils.joinAndUnwrap(testSubject.start()));

            // then
            assertFalse(testSubject.isRunning());
        }

        @Test
        void secondStartInvocationIsIgnored() {
            // given
            startEventProcessor();

            // when - The second invocation does not cause the Coordinator to schedule another CoordinationTask.
            startEventProcessor();

            // then
            verify(coordinatorExecutor, times(1)).submit(any(Runnable.class));
        }

        @Test
        void startingProcessorClaimsAllAvailableTokens() {
            // given
            List<EventMessage> events =
                    createEvents(100);
            events.forEach(stubMessageSource::publishMessage);

            // when
            startEventProcessor();

            // then
            assertWithin(1, TimeUnit.SECONDS, () -> assertEquals(8, testSubject.processingStatus().size()));
            assertWithin(2, TimeUnit.SECONDS, () -> {
                long nonNullTokens = IntStream.range(0, 8)
                                              .mapToObj(i -> tokenStore.fetchToken(PROCESSOR_NAME, i, null))
                                              .filter(Objects::nonNull)
                                              .count();
                assertEquals(8, nonNullTokens);
            });
            assertEquals(8, testSubject.processingStatus().size());
        }

        @Test
        void shutdownProcessorWhichHasNotStartedYetReturnsCompletedFuture() {
            assertTrue(testSubject.shutdown().isDone());
        }

        @Test
        void shutdownProcessorAsyncTwiceReturnsSameFuture() {
            startEventProcessor();

            CompletableFuture<Void> resultOne = testSubject.shutdown();
            CompletableFuture<Void> resultTwo = testSubject.shutdown();

            assertSame(resultOne, resultTwo);
        }

        @Test
        void startFailsWhenShutdownIsInProgress() throws Exception {
            // Use CountDownLatch to block worker threads from actually doing work, and thus shutting down successfully.
            CountDownLatch latch = new CountDownLatch(1);
            doAnswer(i -> latch.await(10, TimeUnit.MILLISECONDS))
                    .when(defaultEventHandlingComponent)
                    .handle(any(EventMessage.class), any(ProcessingContext.class));

            startEventProcessor();

            List<EventMessage> events = createEvents(5);
            events.forEach(stubMessageSource::publishMessage);

            assertWithin(1, TimeUnit.SECONDS, () -> assertFalse(testSubject.processingStatus().isEmpty()));

            CompletableFuture<Void> shutdownComplete = testSubject.shutdown();
            assertThrows(IllegalStateException.class, () -> FutureUtils.joinAndUnwrap(testSubject.start()));
            // Unblock the Worker threads
            latch.countDown();
            shutdownComplete.get(1, TimeUnit.SECONDS);

            // This is allowed
            assertDoesNotThrow(() -> FutureUtils.joinAndUnwrap(testSubject.start()));
        }

        @Test
        void isRunningOnlyReturnsTrueForStartedProcessor() {
            assertFalse(testSubject.isRunning());

            startEventProcessor();

            assertTrue(testSubject.isRunning());
        }

        @Test
        void isErrorForFailingMessageSourceOperation() {
            assertFalse(testSubject.isError());

            startEventProcessor();

            assertFalse(testSubject.isError());

            stubMessageSource.publishMessage(AsyncInMemoryStreamableEventSource.FAIL_EVENT);

            assertWithin(500, TimeUnit.MILLISECONDS, () -> assertTrue(testSubject.isError()));

            // After one exception the Coordinator#errorWaitBackOff is 1 second. After this, the Coordinator should proceed.
            List<EventMessage> events = createEvents(5);
            events.forEach(stubMessageSource::publishMessage);
            assertWithin(1500, TimeUnit.MILLISECONDS, () -> assertFalse(testSubject.isError()));
        }

        @Test
        void isErrorWhenOpeningTheStreamFails() {
            when(stubMessageSource.open(any(), any())).thenThrow(new IllegalStateException("Failed to open the stream"))
                                                      .thenCallRealMethod();
            withTestSubject(List.of());

            assertFalse(testSubject.isError());

            startEventProcessor();

            assertWithin(500, TimeUnit.MILLISECONDS, () -> assertTrue(testSubject.isError()));

            // After one exception the Coordinator#errorWaitBackOff is 1 second. After this, the Coordinator should proceed.
            List<EventMessage> events = createEvents(5);
            events.forEach(stubMessageSource::publishMessage);
            assertWithin(1500, TimeUnit.MILLISECONDS, () -> assertFalse(testSubject.isError()));
        }

        @Test
        void isCaughtUpWhenDoneProcessing() {
            mockSlowEventHandler();
            withTestSubject(List.of(), (c -> c.initialSegmentCount(1)));
            List<EventMessage> events = createEvents(3);
            events.forEach(stubMessageSource::publishMessage);

            startEventProcessor();

            AtomicReference<Instant> startedProcessing = new AtomicReference<>(null);
            assertWithin(
                    5, TimeUnit.SECONDS,
                    () -> {
                        assertEquals(1, testSubject.processingStatus().size());
                        startedProcessing.compareAndSet(null, Instant.now());
                    }
            );
            assertWithin(
                    5, TimeUnit.SECONDS,
                    () -> assertTrue(testSubject.processingStatus().get(0).isCaughtUp())
            );
            Instant now = Instant.now();
            //It should have taken 2 seconds (rounded down) or more this will fail, want changed to normal mock, then it goes faster
            assertTrue(Duration.between(startedProcessing.get(), now).getSeconds() >= 2);
        }

        private void mockSlowEventHandler() {
            doAnswer(invocation -> {
                Thread.sleep(1000);
                return MessageStream.empty();
            }).when(defaultEventHandlingComponent).handle(any(EventMessage.class), any(ProcessingContext.class));
        }
    }

    @Disabled("Tracing another task!")
    @Nested
    class TracingTest {

//        @Test
//        void handlingEventsAreCorrectlyTraced() throws Exception {
//            CountDownLatch countDownLatch = new CountDownLatch(8);
//            List<Message<?>> invokedMessages = new CopyOnWriteArrayList<>();
//            doAnswer(
//                    answer -> {
//                        EventMessage<?> message = answer.getArgument(0, EventMessage.class);
//                        invokedMessages.add(message);
//                        spanFactory.verifySpanActive("StreamingEventProcessor.batch");
//                        spanFactory.verifySpanActive("StreamingEventProcessor.process", message);
//                        countDownLatch.countDown();
//                        return MessageStream.empty();
//                    }
//            ).when(stubEventHandlingComponent).handle(any(), any());
//
//            List<EventMessage<Integer>> events = createEvents(8);
//            events.forEach(stubMessageSource::publishMessage);
//            testSubject.start();
//            assertTrue(countDownLatch.await(5, TimeUnit.SECONDS));
//            invokedMessages.forEach(
//                    e -> assertWithin(
//                            1, TimeUnit.SECONDS,
//                            () -> spanFactory.verifySpanCompleted("StreamingEventProcessor.process", e)
//                    )
//            );
//            spanFactory.verifySpanCompleted("StreamingEventProcessor.batch");
//        }
    }

    @Nested
    class ProcessingContextResourcesTest {

        @Test
        void handlingEventsHaveSegmentAndTokenInProcessingContext() throws Exception {
            // given
            CountDownLatch countDownLatch = new CountDownLatch(8);
            var eventHandlingComponent = SimpleEventHandlingComponent.create("test");
            eventHandlingComponent.subscribe(new QualifiedName(Integer.class), (event, context) -> {
                boolean containsSegment = Segment.fromContext(context).isPresent();
                boolean containsToken = TrackingToken.fromContext(context).isPresent();
                if (!containsSegment) {
                    logger.error("UoW didn't contain the segment!");
                    return MessageStream.empty();
                }
                if (!containsToken) {
                    logger.error("UoW didn't contain the token!");
                    return MessageStream.empty();
                }
                countDownLatch.countDown();
                return MessageStream.empty();
            });
            withTestSubject(List.of(eventHandlingComponent));

            // when
            List<EventMessage> events = createEvents(8);
            events.forEach(stubMessageSource::publishMessage);
            startEventProcessor();

            // then
            assertTrue(countDownLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @Nested
    class TokenManagementTest {

        @Test
        void retriesWhenTokenInitializationInitiallyFails() {
            // given
            doThrow(new RuntimeException("Simulated failure")).doCallRealMethod()
                                                              .when(tokenStore)
                                                              .initializeTokenSegments(any(), anyInt(), any(), any());

            // when
            List<EventMessage> events =
                    createEvents(100);
            events.forEach(stubMessageSource::publishMessage);
            startEventProcessor();

            // then
            assertTrue(testSubject.isRunning());

            assertWithin(1, TimeUnit.SECONDS, () -> assertEquals(8, testSubject.processingStatus().size()));
            assertWithin(2, TimeUnit.SECONDS, () -> {
                long nonNullTokens = IntStream.range(0, 8)
                                              .mapToObj(i -> joinAndUnwrap(tokenStore.fetchToken(PROCESSOR_NAME,
                                                                                                 i,
                                                                                                 null)))
                                              .filter(Objects::nonNull)
                                              .count();
                assertEquals(8, nonNullTokens);
            });
            assertEquals(8, testSubject.processingStatus().size());
        }

        @Test
        void processingStatusIsUpdatedWithTrackingToken() {
            startEventProcessor();

            List<EventMessage> events =
                    createEvents(5);
            events.forEach(stubMessageSource::publishMessage);

            assertWithin(
                    1, TimeUnit.SECONDS,
                    () -> testSubject.processingStatus().values().forEach(
                            status -> assertEquals(5, status.getCurrentPosition().orElse(0))
                    )
            );
        }

        @Test
        void allTokensUpdatedToLatestValue() {
            List<EventMessage> events = createEvents(100);
            events.forEach(stubMessageSource::publishMessage);

            startEventProcessor();

            assertWithin(1, TimeUnit.SECONDS, () -> assertEquals(8, testSubject.processingStatus().size()));
            assertWithin(6, TimeUnit.SECONDS, () -> {
                long lowestToken = IntStream.range(0, 8)
                                            .mapToObj(i -> joinAndUnwrap(tokenStore.fetchToken(testSubject.name(),
                                                                                               i,
                                                                                               null)))
                                            .mapToLong(token -> token == null ? 0 : token.position().orElse(0))
                                            .min()
                                            .orElse(-1);
                assertEquals(100, lowestToken);
            });
        }

        @Test
        void tokenStoreReturningSingleNullToken() {
            var ctx = createProcessingContext();
            tokenStore.initializeTokenSegments(testSubject.name(), 2, null, ctx);
            joinAndUnwrap(
                    tokenStore.storeToken(new GlobalSequenceTrackingToken(0), testSubject.name(), 1, ctx)
            );

            startEventProcessor();

            assertWithin(1, TimeUnit.SECONDS, () -> assertEquals(2, testSubject.processingStatus().size()));
        }

        @Test
        void getTokenStoreIdentifier() {
            String expectedIdentifier = "some-identifier";

            when(tokenStore.retrieveStorageIdentifier(any()))
                    .thenReturn(completedFuture(expectedIdentifier));

            assertEquals(expectedIdentifier, testSubject.getTokenStoreIdentifier());
        }

        @Test
        void releaseSegmentMakesTheTokenUnclaimedForTwiceTheTokenClaimInterval() {
            // Given...
            int testSegmentId = 0;
            int testTokenClaimInterval = 500;

            withTestSubject(List.of(), c -> c.initialSegmentCount(1).tokenClaimInterval(testTokenClaimInterval));

            startEventProcessor();
            // Assert the single WorkPackage is in progress prior to invoking the release.
            assertWithin(
                    testTokenClaimInterval, TimeUnit.MILLISECONDS,
                    () -> assertNotNull(testSubject.processingStatus().get(testSegmentId))
            );

            // When...
            FutureUtils.joinAndUnwrap(testSubject.releaseSegment(testSegmentId));

            await().atMost(testTokenClaimInterval + 200, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertNull(testSubject.processingStatus().get(testSegmentId)));

            // Assert that within twice the tokenClaimInterval, the WorkPackage is in progress again.
            await().atMost((testTokenClaimInterval * 2) + 200, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertNotNull(testSubject.processingStatus().get(testSegmentId)));
        }
    }

    @Nested
    class Coordinating {

        @Test
        void coordinationIsTriggeredThroughEventAvailabilityCallback() {
            boolean streamCallbackSupported = true;
            AsyncInMemoryStreamableEventSource testMessageSource = new AsyncInMemoryStreamableEventSource(
                    streamCallbackSupported, true);
            stubMessageSource = testMessageSource;
            withTestSubject(List.of());

            List<EventMessage> events1 = createEvents(4);
            events1.forEach(testMessageSource::publishMessage);

            startEventProcessor();

            assertWithin(1, TimeUnit.SECONDS, () -> assertEquals(8, testSubject.processingStatus().size()));
            assertWithin(1, TimeUnit.SECONDS, () -> {
                long lowestToken = testSubject.processingStatus().values().stream()
                                              .map(status -> status.getCurrentPosition().orElse(-1))
                                              .min(Long::compareTo)
                                              .orElse(-1L);

                assertEquals(4, lowestToken);
            });

            List<EventMessage> events2 = createEvents(4);
            events2.forEach(testMessageSource::publishMessage);
            testMessageSource.runOnAvailableCallback();

            assertWithin(1, TimeUnit.SECONDS, () -> {
                long lowestToken = testSubject.processingStatus().values().stream()
                                              .map(status -> status.getCurrentPosition().orElse(-1))
                                              .min(Long::compareTo)
                                              .orElse(-1L);

                assertEquals(8, lowestToken);
            });
        }

        @Test
        void coordinatorExtendsClaimsEarlierForBusyWorkPackages() {
            withTestSubject(
                    List.of(),
                    c -> c.initialSegmentCount(1).enableCoordinatorClaimExtension()
            );

            AtomicBoolean isWaiting = new AtomicBoolean(false);
            CountDownLatch handleLatch = new CountDownLatch(1);
            doAnswer(invocation -> {
                // Waiting for the latch to simulate a slow/busy WorkPackage.
                isWaiting.set(true);
                handleLatch.await(5, TimeUnit.SECONDS);
                return MessageStream.empty();
            }).when(defaultEventHandlingComponent)
              .handle(any(EventMessage.class), any(ProcessingContext.class));

            List<EventMessage> events = createEvents(42);
            events.forEach(stubMessageSource::publishMessage);

            startEventProcessor();

            // Wait until we've reached the blocking WorkPackage before validating if the token is extended.
            // Otherwise, the WorkPackage may extend the token itself.
            await().pollDelay(Duration.ofMillis(50))
                   .atMost(Duration.ofSeconds(5))
                   .until(isWaiting::get);

            // As the WorkPackage is blocked, we can verify if the claim is extended but not stored.
            verify(tokenStore, timeout(5000)).extendClaim(eq(PROCESSOR_NAME), eq(0), any());
            verify(tokenStore, never()).storeToken(any(), eq(PROCESSOR_NAME), eq(0), any());

            // Unblock the WorkPackage after successful validation
            handleLatch.countDown();

            // Processing finished...
            await().pollDelay(Duration.ofMillis(50))
                   .atMost(Duration.ofSeconds(5))
                   .until(() -> testSubject.processingStatus().get(0).isCaughtUp());
            // Validate the token is stored
            verify(tokenStore, timeout(5000).atLeastOnce()).storeToken(any(),
                                                                       eq(PROCESSOR_NAME),
                                                                       eq(0),
                                                                       any(ProcessingContext.class));
        }

        @Test
        void coordinatorExtendingClaimFailsAndAbortsWorkPackage() {
            withTestSubject(
                    List.of(),
                    c -> c.initialSegmentCount(1).enableCoordinatorClaimExtension()
            );

            String expectedExceptionMessage = "bummer";
            doThrow(new RuntimeException(expectedExceptionMessage))
                    .when(tokenStore)
                    .extendClaim(eq(PROCESSOR_NAME), eq(0), any());

            AtomicBoolean isWaiting = new AtomicBoolean(false);
            CountDownLatch handleLatch = new CountDownLatch(1);
            doAnswer(invocation -> {
                // Waiting for the latch to simulate a slow/busy WorkPackage.
                isWaiting.set(true);
                handleLatch.await(5, TimeUnit.SECONDS);
                return MessageStream.empty();
            }).when(defaultEventHandlingComponent)
              .handle(any(EventMessage.class), any(ProcessingContext.class));

            List<EventMessage> events = createEvents(42);
            events.forEach(stubMessageSource::publishMessage);

            startEventProcessor();

            // Wait until we've reached the blocking WorkPackage before validating if the token is extended.
            // Otherwise, the WorkPackage may extend the token itself.
            await().pollDelay(Duration.ofMillis(50))
                   .atMost(Duration.ofSeconds(5))
                   .until(isWaiting::get);

            // As the WorkPackage is blocked, we can verify if the claim is extended, but not stored.
            verify(tokenStore, timeout(5000)).extendClaim(eq(PROCESSOR_NAME), eq(0), any());
            verify(tokenStore, never()).storeToken(any(), eq(PROCESSOR_NAME), eq(0), any());

            // Although the WorkPackage is waiting, the Coordinator should in the meantime fail with extending the claim.
            // This update the processing status of the WorkPackage.
            await().pollDelay(Duration.ofMillis(50))
                   .atMost(Duration.ofSeconds(5))
                   .until(() -> testSubject.processingStatus().get(0)
                                           .getError()
                                           .getMessage().equals(expectedExceptionMessage));

            // Unblock the WorkPackage after successful validation
            handleLatch.countDown();
        }
    }

    @Nested
    class WorkPackageAbortingTest {

        @Test
        void exceptionWhileHandlingEventAbortsWorker() {
            List<EventMessage> events = createEvents(5);
            doReturn(MessageStream.failed(new RuntimeException("Simulating worker failure")))
                    .doReturn(MessageStream.empty())
                    .when(defaultEventHandlingComponent)
                    .handle(ArgumentMatchers.<EventMessage>argThat(em -> em.identifier()
                                                                           .equals(events.get(2).identifier())),
                            any(ProcessingContext.class));

            startEventProcessor();

            await().pollDelay(Duration.ofMillis(50))
                   .atMost(Duration.ofSeconds(1))
                   .untilAsserted(() -> assertThat(testSubject.processingStatus()).hasSize(8));
            await().pollDelay(Duration.ofMillis(50))
                   .atMost(Duration.ofSeconds(1))
                   .untilAsserted(() -> {
                       List<Segment> segments = joinAndUnwrap(tokenStore.fetchSegments(PROCESSOR_NAME, null));
                       assertThat(segments).isNotNull();
                       int segmentCount = segments.size();
                       assertThat(segmentCount).isEqualTo(8);
                   });

            events.forEach(e -> stubMessageSource.publishMessage(e));

            assertWithin(1, TimeUnit.SECONDS, () -> {
                try {
                    verify(defaultEventHandlingComponent).handle(
                            ArgumentMatchers.<EventMessage>argThat(
                                    em -> em.identifier().equals(events.get(2).identifier())
                            ),
                            any()
                    );
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertWithin(1, TimeUnit.SECONDS, () -> {
                assertThat(testSubject.processingStatus()).hasSize(7);
                // JohnH: the key of the processes that was removed is pretty much random, asserting that processor
                // 2 must always exists seems incorrect (but you have an 87.5% chance...)
                //assertThat(testSubject.processingStatus()).containsKey(2);
            });
        }

        @Test
        void workPackageIsAbortedWhenExtendingClaimFails() {
            withTestSubject(List.of(), c -> c.claimExtensionThreshold(10));

            doThrow(new MockException("Simulated failure")).when(tokenStore)
                                                           .extendClaim(any(), anyInt(), any());
            //  from legacy? .eventSource(new AsyncInMemoryStreamableEventSource(true))
            startEventProcessor();
            assertWithin(
                    250, TimeUnit.MILLISECONDS,
                    () -> verify(tokenStore, atLeastOnce()).extendClaim(eq(testSubject.name()), eq(0), any())
            );
            assertWithin(100, TimeUnit.MILLISECONDS, () -> assertTrue(testSubject.processingStatus().isEmpty()));
        }

        @Test
        void shutdownCompletesAfterAbortingWorkPackages()
                throws InterruptedException, ExecutionException, TimeoutException {
            startEventProcessor();
            Stream.of(1, 2, 2, 4, 5)
                  .map(i -> new GenericEventMessage(new MessageType("event"), i))
                  .forEach(stubMessageSource::publishMessage);

            assertWithin(1, TimeUnit.SECONDS, () -> assertFalse(testSubject.processingStatus().isEmpty()));

            testSubject.shutdown().get(1, TimeUnit.SECONDS);
            assertWithin(1, TimeUnit.SECONDS, () -> assertEquals(0, testSubject.processingStatus().size()));

            assertFalse(coordinatorExecutor.isShutdown());
            assertFalse(workerExecutor.isShutdown());
        }
    }

    @Nested
    class EventFilteringTest {

        @Test
        void handlingMessageTypeNotSupportedByEventHandlingComponentWillAdvanceToken() {
            // given - Let all events through EventCriteria but configure an EventHandlingComponent to not support Integer events
            withTestSubject(List.of(), c -> c.initialSegmentCount(1));
            QualifiedName integerTypeName = new QualifiedName(Integer.class.getName());
            when(defaultEventHandlingComponent.supports(integerTypeName)).thenReturn(false);

            // when - Publish an Integer event that will reach the processor but won't be handled
            EventMessage eventToIgnore = EventTestUtils.asEventMessage(1337);
            stubMessageSource.publishMessage(eventToIgnore);
            startEventProcessor();

            // then - Verify processor status and token advancement
            await().atMost(1, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(testSubject.processingStatus()).hasSize(1));
            await().atMost(200, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> {
                       long currentPosition = testSubject.processingStatus().get(0).getCurrentPosition().orElse(0);
                       assertThat(currentPosition).isEqualTo(1);
                   });

            // then - Verify no events were handled
            verify(defaultEventHandlingComponent, never())
                    .handle(any(EventMessage.class), any(ProcessingContext.class));
        }

        @Test
        void handlingMessageTypeSupportedByEventHandlingComponentWillAdvanceToken() {
            // given
            withTestSubject(
                    List.of(),
                    c -> c.initialSegmentCount(1)
            );

            // when
            EventMessage supportedEvent = EventTestUtils.asEventMessage(123);
            stubMessageSource.publishMessage(supportedEvent);
            startEventProcessor();

            // then
            await().atMost(1, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(testSubject.processingStatus()).hasSize(1));
            await().atMost(200, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> {
                       long currentPosition = testSubject.processingStatus().get(0).getCurrentPosition().orElse(0);
                       assertThat(currentPosition).isEqualTo(1);
                   });

            // then
            verify(defaultEventHandlingComponent, times(1))
                    .handle(any(EventMessage.class), any(ProcessingContext.class));
        }

        @Test
        void eventCriteriaFiltersEventsOnSourceLevelSoEventIsNotHandledAndTokenNotAdvanced() {
            // given - Configure EventCriteria to filter out Integer events at stream level
            EventCriteria stringOnlyCriteria = EventCriteria.havingAnyTag()
                                                            .andBeingOneOfTypes(new QualifiedName(String.class.getName()));
            withTestSubject(
                    List.of(),
                    c -> c.initialSegmentCount(1)
                          .eventCriteria(ignored -> stringOnlyCriteria)
            );

            // when - Publish an Integer event that will be filtered out by EventCriteria before reaching processor
            EventMessage eventToFilter = EventTestUtils.asEventMessage(1337);
            stubMessageSource.publishMessage(eventToFilter);
            startEventProcessor();

            // then - Verify processor status, but token should NOT advance (stays at 0)
            await().atMost(1, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(testSubject.processingStatus()).hasSize(1));
            await().atMost(200, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> {
                       long currentPosition = testSubject.processingStatus().get(0).getCurrentPosition().orElse(0);
                       assertThat(currentPosition).isEqualTo(0); // Token should not advance - event was filtered at stream level
                   });

            // then - Verify no events were handled (filtered out by EventCriteria)
            verify(defaultEventHandlingComponent, never())
                    .handle(any(EventMessage.class), any(ProcessingContext.class));

            // then - Verify the event was tracked as ignored (even though filtered at stream level)
            assertThat(stubMessageSource.getIgnoredEvents()).hasSize(1);
            assertThat(stubMessageSource.getIgnoredEvents().getFirst().payload()).isEqualTo(1337);
        }

        @Test
        void eventsWhichMustBeIgnoredAreNotHandled() {
            // given
            EventCriteria stringOnlyCriteria = EventCriteria.havingAnyTag()
                                                            .andBeingOneOfTypes(new QualifiedName(String.class.getName()));

            SimpleEventHandlingComponent ehc = SimpleEventHandlingComponent.create("test");
            ehc.subscribe(new QualifiedName(String.class), (event, ctx) -> MessageStream.empty());
            var stringEventHandlingComponent = new RecordingEventHandlingComponent(ehc);
            withTestSubject(
                    List.of(stringEventHandlingComponent),
                    c -> c.initialSegmentCount(1)
                          .eventCriteria(ignored -> stringOnlyCriteria)
            );

            EventMessage eventToIgnoreOne = EventTestUtils.asEventMessage(1337);
            EventMessage eventToIgnoreTwo = EventTestUtils.asEventMessage(42);
            EventMessage eventToIgnoreThree = EventTestUtils.asEventMessage(9001);
            List<Integer> eventsToIgnore = new ArrayList<>();
            eventsToIgnore.add(eventToIgnoreOne.payloadAs(Integer.class));
            eventsToIgnore.add(eventToIgnoreTwo.payloadAs(Integer.class));
            eventsToIgnore.add(eventToIgnoreThree.payloadAs(Integer.class));

            EventMessage eventToHandleOne = EventTestUtils.asEventMessage("some-text");
            EventMessage eventToHandleTwo = EventTestUtils.asEventMessage("some-other-text");
            List<String> eventsToHandle = new ArrayList<>();
            eventsToHandle.add(eventToHandleOne.payloadAs(String.class));
            eventsToHandle.add(eventToHandleTwo.payloadAs(String.class));

            List<Object> eventsToValidate = new ArrayList<>();
            eventsToValidate.add(eventToHandleOne.payload());
            eventsToValidate.add(eventToHandleTwo.payload());

            // when
            stubMessageSource.publishMessage(eventToIgnoreOne);
            stubMessageSource.publishMessage(eventToIgnoreTwo);
            stubMessageSource.publishMessage(eventToIgnoreThree);
            stubMessageSource.publishMessage(eventToHandleOne);
            stubMessageSource.publishMessage(eventToHandleTwo);

            startEventProcessor();

            await().atMost(1, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(testSubject.processingStatus()).hasSize(1));

            // then - Verify that only String events are handled (Integer events are filtered out by EventCriteria).
            await().atMost(1, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(stringEventHandlingComponent.recorded()).hasSameSizeAs(
                           eventsToValidate));

            // then - Validate that the correct String events were handled.
            List<EventMessage> handledEvents = stringEventHandlingComponent.recorded();
            assertThat(handledEvents).hasSize(2);

            List<Object> handledPayloads = handledEvents.stream()
                                                        .map(EventMessage::payload)
                                                        .collect(Collectors.toList());
            assertThat(handledPayloads).containsExactlyInAnyOrderElementsOf(eventsToHandle);

            // then - Verify that ignored events are tracked correctly
            List<EventMessage> ignoredEvents = stubMessageSource.getIgnoredEvents();
            assertThat(ignoredEvents).hasSize(3);

            List<Object> ignoredPayloads = ignoredEvents.stream()
                                                        .map(EventMessage::payload)
                                                        .collect(Collectors.toList());
            assertThat(ignoredPayloads).containsExactlyInAnyOrderElementsOf(eventsToIgnore);
        }

        @Test
        void eventHandlingComponentReprocessEventsDuringReplay() {
            // given
            List<EventMessage> recordedEvents = new CopyOnWriteArrayList<>();

            var eventHandlingComponent = SimpleEventHandlingComponent.create("test");
            eventHandlingComponent.subscribe(new QualifiedName(String.class), (event, ctx) -> {
                recordedEvents.add(event);
                return MessageStream.empty();
            });

            // do not clear event source after close
            stubMessageSource = new AsyncInMemoryStreamableEventSource(false, false);
            withTestSubject(
                    List.of(eventHandlingComponent),
                    c -> c.initialSegmentCount(1)
            );

            // Publish events
            EventMessage event1 = EventTestUtils.asEventMessage("event-1");
            EventMessage event2 = EventTestUtils.asEventMessage("event-2");
            EventMessage event3 = EventTestUtils.asEventMessage("event-3");
            stubMessageSource.publishMessage(event1);
            stubMessageSource.publishMessage(event2);
            stubMessageSource.publishMessage(event3);

            // when - Start and process events normally (not during replay)
            startEventProcessor();

            // Wait for initial processing to complete (events processed normally, not during replay)
            await().atMost(2, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(recordedEvents).containsOnly(event1, event2, event3));

            joinAndUnwrap(testSubject.shutdown());

            // Clear recorded events to track only replay events
            recordedEvents.clear();

            // Reset tokens to trigger replay (reset to position before any events)
            joinAndUnwrap(testSubject.resetTokens(source -> source.firstToken(null)));

            // Restart to process events during replay
            startEventProcessor();

            // then - wait for catchup
            await().atMost(2, TimeUnit.SECONDS)
                   .untilAsserted(() -> {
                       long currentPosition = testSubject.processingStatus().get(0).getCurrentPosition().orElse(0);
                       assertThat(currentPosition).isEqualTo(3);
                   });

            // then - verify events reprocessed during replay
            assertThat(recordedEvents).containsOnly(event1, event2, event3);
        }

        @Test
        void replayBlockingEventHandlingComponentBlocksEventsDuringReplay() {
            // given
            List<EventMessage> recordedEvents = new CopyOnWriteArrayList<>();

            // Create a component that wraps event handling with replay blocking
            var innerComponent = SimpleEventHandlingComponent.create("test");
            innerComponent.subscribe(new QualifiedName(String.class), (event, ctx) -> {
                recordedEvents.add(event);
                return MessageStream.empty();
            });

            var replayBlockingComponent = new ReplayBlockingEventHandlingComponent<>(innerComponent);

            // do not clear event source after close
            stubMessageSource = new AsyncInMemoryStreamableEventSource(false, false);
            withTestSubject(
                    List.of(replayBlockingComponent),
                    c -> c.initialSegmentCount(1)
            );

            // Publish events
            EventMessage event1 = EventTestUtils.asEventMessage("event-1");
            EventMessage event2 = EventTestUtils.asEventMessage("event-2");
            EventMessage event3 = EventTestUtils.asEventMessage("event-3");
            stubMessageSource.publishMessage(event1);
            stubMessageSource.publishMessage(event2);
            stubMessageSource.publishMessage(event3);

            // when - Start and process events normally (not during replay)
            startEventProcessor();

            // Wait for initial processing to complete (events processed normally, not during replay)
            await().atMost(2, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(recordedEvents).containsOnly(event1, event2, event3));

            joinAndUnwrap(testSubject.shutdown());

            // Clear recorded events to track only replay events
            recordedEvents.clear();

            // Reset tokens to trigger replay (reset to position before any events)
            joinAndUnwrap(testSubject.resetTokens(source -> source.firstToken(null)));

            // Restart to process events during replay
            startEventProcessor();

            // then - wait for catchup
            await().atMost(2, TimeUnit.SECONDS)
                   .untilAsserted(() -> {
                       long currentPosition = testSubject.processingStatus().get(0).getCurrentPosition().orElse(0);
                       assertThat(currentPosition).isEqualTo(3);
                   });

            // then - verify no events processed during replay
            assertThat(recordedEvents).isEmpty();
        }

        @Test
        void replayBlockingEventHandlingComponentCorrectlySkipsEventsWithBatchSizeGreaterThanOne() {
            // given
            List<EventMessage> recordedEvents = new CopyOnWriteArrayList<>();

            var innerComponent = SimpleEventHandlingComponent.create("test");
            innerComponent.subscribe(new QualifiedName(String.class), (event, ctx) -> {
                recordedEvents.add(event);
                return MessageStream.empty();
            });
            var replayBlockingComponent = new ReplayBlockingEventHandlingComponent<>(innerComponent);

            // do not clear event source after close
            stubMessageSource = new AsyncInMemoryStreamableEventSource(false, false);
            withTestSubject(
                    List.of(replayBlockingComponent),
                    c -> c.initialSegmentCount(1).batchSize(5)
            );

            // when - publish 5 events and process normally (not during replay)
            EventMessage event1 = EventTestUtils.asEventMessage("event-1");
            EventMessage event2 = EventTestUtils.asEventMessage("event-2");
            EventMessage event3 = EventTestUtils.asEventMessage("event-3");
            EventMessage event4 = EventTestUtils.asEventMessage("event-4");
            EventMessage event5 = EventTestUtils.asEventMessage("event-5");
            stubMessageSource.publishMessage(event1);
            stubMessageSource.publishMessage(event2);
            stubMessageSource.publishMessage(event3);
            stubMessageSource.publishMessage(event4);
            stubMessageSource.publishMessage(event5);

            startEventProcessor();
            await().atMost(2, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(recordedEvents).containsOnly(event1, event2, event3, event4, event5));

            joinAndUnwrap(testSubject.shutdown());
            recordedEvents.clear();

            // Reset tokens to trigger a replay from the beginning
            joinAndUnwrap(testSubject.resetTokens(source -> source.firstToken(null)));

            // when - restart; with batchSize=5 all 5 replay events land in a single batch
            startEventProcessor();

            // then - wait for the processor to catch up to position 5
            await().atMost(2, TimeUnit.SECONDS)
                   .untilAsserted(() -> {
                       long currentPosition = testSubject.processingStatus().get(0).getCurrentPosition().orElse(0);
                       assertThat(currentPosition).isEqualTo(5);
                   });

            // then - replay-blocking component must have skipped all events: each event in the batch must have
            // had isReplaying=true (i.e. its per-event ReplayToken was correctly injected, not the plain
            // batch-end GlobalSequenceTrackingToken)
            assertThat(recordedEvents).isEmpty();
        }

        @Test
        void perEventTokenIsCorrectWhenBatchCrossesReplayBoundaryInMiddle() {
            // given
            // batchSize=5, 3 events processed before reset (tokenAtReset=3), then 5 more events added.
            // Batch 1 (full, size 5): tokens 1-3 are replay, tokens 4-5 are post-replay — boundary in middle.
            // Batch 2 (partial, size 3): tokens 6-8 are post-replay.
            // The batch-end of batch 1 is the plain GST(5).
            Map<EventMessage, TrackingToken> recordedTokens = Collections.synchronizedMap(new HashMap<>());

            var innerComponent = SimpleEventHandlingComponent.create("test");
            innerComponent.subscribe(new QualifiedName(String.class), (event, ctx) -> {
                TrackingToken.fromContext(ctx).ifPresent(t -> recordedTokens.put(event, t));
                return MessageStream.empty();
            });
            innerComponent.subscribe((ResetHandler) (resetContext, ctx) -> MessageStream.empty());

            stubMessageSource = new AsyncInMemoryStreamableEventSource(false, false);
            withTestSubject(
                    List.of(innerComponent),
                    c -> c.initialSegmentCount(1).batchSize(5)
            );

            // publish 3 events (tokens 1-3) — these will become replay events
            EventMessage event1 = EventTestUtils.asEventMessage("event-1");
            EventMessage event2 = EventTestUtils.asEventMessage("event-2");
            EventMessage event3 = EventTestUtils.asEventMessage("event-3");
            stubMessageSource.publishMessage(event1);
            stubMessageSource.publishMessage(event2);
            stubMessageSource.publishMessage(event3);

            startEventProcessor();
            await().atMost(2, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(recordedTokens).containsKeys(event1, event2, event3));
            joinAndUnwrap(testSubject.shutdown());
            recordedTokens.clear();

            // reset to replay from the start; tokenAtReset = GST(3)
            joinAndUnwrap(testSubject.resetTokens(source -> source.firstToken(null)));

            // publish 5 more events (tokens 4-8) — tokens 4-5 land in batch 1 (post-replay), tokens 6-8 in batch 2
            EventMessage event4 = EventTestUtils.asEventMessage("event-4");
            EventMessage event5 = EventTestUtils.asEventMessage("event-5");
            EventMessage event6 = EventTestUtils.asEventMessage("event-6");
            EventMessage event7 = EventTestUtils.asEventMessage("event-7");
            EventMessage event8 = EventTestUtils.asEventMessage("event-8");
            stubMessageSource.publishMessage(event4);
            stubMessageSource.publishMessage(event5);
            stubMessageSource.publishMessage(event6);
            stubMessageSource.publishMessage(event7);
            stubMessageSource.publishMessage(event8);

            // when - restart
            startEventProcessor();
            await().atMost(2, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(recordedTokens)
                           .containsKeys(event1, event2, event3, event4, event5, event6, event7, event8));

            // then — each event must carry its own per-event token
            TrackingToken tokenAtReset = new GlobalSequenceTrackingToken(3);

            // events 1-2: replay, not concluding
            assertPerEventReplayToken(recordedTokens.get(event1), tokenAtReset, new GlobalSequenceTrackingToken(1), false);
            assertPerEventReplayToken(recordedTokens.get(event2), tokenAtReset, new GlobalSequenceTrackingToken(2), false);
            // event3: replay boundary — still a ReplayToken, concludesReplay = true
            assertPerEventReplayToken(recordedTokens.get(event3), tokenAtReset, new GlobalSequenceTrackingToken(3), true);

            // events 4-8: post-replay — plain GlobalSequenceTrackingToken
            assertEquals(new GlobalSequenceTrackingToken(4), recordedTokens.get(event4));
            assertEquals(new GlobalSequenceTrackingToken(5), recordedTokens.get(event5));
            assertEquals(new GlobalSequenceTrackingToken(6), recordedTokens.get(event6));
            assertEquals(new GlobalSequenceTrackingToken(7), recordedTokens.get(event7));
            assertEquals(new GlobalSequenceTrackingToken(8), recordedTokens.get(event8));
        }

        @Test
        void perEventTokenIsCorrectWhenReplayEndsAtBatchBoundary() {
            // given
            // batchSize=5, 5 events processed before reset (tokenAtReset=5), then 3 more events added.
            // Batch 1 (full, size 5): tokens 1-5 are all replay — boundary falls at the very end of the batch.
            // Batch 2 (partial, size 3): tokens 6-8 are post-replay.
            // The batch-end of batch 1 is ReplayToken(current=5, atReset=5) — still a ReplayToken.
            Map<EventMessage, TrackingToken> recordedTokens = Collections.synchronizedMap(new HashMap<>());

            var innerComponent = SimpleEventHandlingComponent.create("test");
            innerComponent.subscribe(new QualifiedName(String.class), (event, ctx) -> {
                TrackingToken.fromContext(ctx).ifPresent(t -> recordedTokens.put(event, t));
                return MessageStream.empty();
            });
            innerComponent.subscribe((ResetHandler) (resetContext, ctx) -> MessageStream.empty());

            stubMessageSource = new AsyncInMemoryStreamableEventSource(false, false);
            withTestSubject(
                    List.of(innerComponent),
                    c -> c.initialSegmentCount(1).batchSize(5)
            );

            // publish 5 events (tokens 1-5) — these will become replay events
            EventMessage event1 = EventTestUtils.asEventMessage("event-1");
            EventMessage event2 = EventTestUtils.asEventMessage("event-2");
            EventMessage event3 = EventTestUtils.asEventMessage("event-3");
            EventMessage event4 = EventTestUtils.asEventMessage("event-4");
            EventMessage event5 = EventTestUtils.asEventMessage("event-5");
            stubMessageSource.publishMessage(event1);
            stubMessageSource.publishMessage(event2);
            stubMessageSource.publishMessage(event3);
            stubMessageSource.publishMessage(event4);
            stubMessageSource.publishMessage(event5);

            startEventProcessor();
            await().atMost(2, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(recordedTokens)
                           .containsKeys(event1, event2, event3, event4, event5));
            joinAndUnwrap(testSubject.shutdown());
            recordedTokens.clear();

            // reset to replay from the start; tokenAtReset = GST(5)
            joinAndUnwrap(testSubject.resetTokens(source -> source.firstToken(null)));

            // publish 3 more events (tokens 6-8) — these land in batch 2 (post-replay)
            EventMessage event6 = EventTestUtils.asEventMessage("event-6");
            EventMessage event7 = EventTestUtils.asEventMessage("event-7");
            EventMessage event8 = EventTestUtils.asEventMessage("event-8");
            stubMessageSource.publishMessage(event6);
            stubMessageSource.publishMessage(event7);
            stubMessageSource.publishMessage(event8);

            // when - restart
            startEventProcessor();
            await().atMost(2, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(recordedTokens)
                           .containsKeys(event1, event2, event3, event4, event5, event6, event7, event8));

            // then — each event must carry its own per-event token
            TrackingToken tokenAtReset = new GlobalSequenceTrackingToken(5);

            // events 1-4: replay, not concluding — before the fix all saw ReplayToken(current=5, atReset=5)
            assertPerEventReplayToken(recordedTokens.get(event1), tokenAtReset, new GlobalSequenceTrackingToken(1), false);
            assertPerEventReplayToken(recordedTokens.get(event2), tokenAtReset, new GlobalSequenceTrackingToken(2), false);
            assertPerEventReplayToken(recordedTokens.get(event3), tokenAtReset, new GlobalSequenceTrackingToken(3), false);
            assertPerEventReplayToken(recordedTokens.get(event4), tokenAtReset, new GlobalSequenceTrackingToken(4), false);
            // event5: replay boundary — still a ReplayToken, concludesReplay = true
            assertPerEventReplayToken(recordedTokens.get(event5), tokenAtReset, new GlobalSequenceTrackingToken(5), true);

            // events 6-8: post-replay — plain GlobalSequenceTrackingToken
            assertEquals(new GlobalSequenceTrackingToken(6), recordedTokens.get(event6));
            assertEquals(new GlobalSequenceTrackingToken(7), recordedTokens.get(event7));
            assertEquals(new GlobalSequenceTrackingToken(8), recordedTokens.get(event8));
        }

        private void assertPerEventReplayToken(TrackingToken token,
                                               TrackingToken expectedAtReset,
                                               TrackingToken expectedCurrent,
                                               boolean expectedConcludesReplay) {
            assertInstanceOf(ReplayToken.class, token);
            ReplayToken replayToken = (ReplayToken) token;
            assertEquals(expectedCurrent, replayToken.getCurrentToken());
            assertEquals(expectedAtReset, replayToken.getTokenAtReset());
            assertTrue(ReplayToken.isReplay(token));
            assertEquals(expectedConcludesReplay, ReplayToken.concludesReplay(token));
        }
    }

    @Nested
    class SegmentClaimingAndReleasingTest {

        @Test
        void releaseAndClaimSegment() {
            // given
            int testSegmentId = 0;
            int testTokenClaimInterval = 5000;

            withTestSubject(
                    List.of(),
                    c -> c.initialSegmentCount(2).tokenClaimInterval(testTokenClaimInterval)
            );

            // when
            startEventProcessor();

            // then - Assert the single WorkPackage is in progress prior to invoking the merge.
            assertWithin(
                    testTokenClaimInterval, TimeUnit.MILLISECONDS,
                    () -> assertNotNull(testSubject.processingStatus().get(testSegmentId))
            );

            // when
            FutureUtils.joinAndUnwrap(testSubject.releaseSegment(testSegmentId, 180, TimeUnit.SECONDS));

            // then - Assert the MergeTask is done and completed successfully.
            assertWithin(testTokenClaimInterval,
                         TimeUnit.MILLISECONDS,
                         () -> assertEquals(1, testSubject.processingStatus().size()));

            testSubject.claimSegment(testSegmentId);

            // then - Assert the Coordinator has only one WorkPackage at work now.
            assertWithin(testTokenClaimInterval,
                         TimeUnit.MILLISECONDS,
                         () -> assertEquals(2, testSubject.processingStatus().size()));
        }
    }

    @Nested
    class SegmentChangeTest {

        @Test
        void releaseSegmentMakesTheTokenUnclaimedForTwiceTheTokenClaimInterval() {
            // given
            int testSegmentId = 0;
            int testTokenClaimInterval = 500;

            withTestSubject(
                    List.of(),
                    c -> c.initialSegmentCount(1).tokenClaimInterval(testTokenClaimInterval)
            );

            // when
            startEventProcessor();

            // then - Assert the single WorkPackage is in progress prior to invoking the release.
            assertWithin(
                    testTokenClaimInterval, TimeUnit.MILLISECONDS,
                    () -> assertNotNull(testSubject.processingStatus().get(testSegmentId))
            );

            // when
            FutureUtils.joinAndUnwrap(testSubject.releaseSegment(testSegmentId));

            await().atMost(testTokenClaimInterval + 200, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertNull(testSubject.processingStatus().get(testSegmentId)));

            // then - Assert that within twice the tokenClaimInterval, the WorkPackage is in progress again.
            await().atMost((testTokenClaimInterval * 2) + 200, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertNotNull(testSubject.processingStatus().get(testSegmentId)));
        }

        @Test
        void segmentChangeListenerIsInvokedOnClaimAndRelease() {
            // given
            int testSegmentId = 0;
            int testTokenClaimInterval = 100;
            List<Integer> claimedSegments = new CopyOnWriteArrayList<>();
            List<Integer> releasedSegments = new CopyOnWriteArrayList<>();

            SegmentChangeListener listener = SegmentChangeListener
                    .runOnClaim(segment -> claimedSegments.add(segment.getSegmentId()))
                    .andThen(SegmentChangeListener.runOnRelease(segment -> releasedSegments.add(segment.getSegmentId())));

            withTestSubject(
                    List.of(),
                    c -> c.initialSegmentCount(1)
                          .tokenClaimInterval(testTokenClaimInterval)
                          .addSegmentChangeListener(listener)
            );

            // when
            startEventProcessor();

            // then
            await().atMost(2, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(claimedSegments).contains(testSegmentId));

            // when
            FutureUtils.joinAndUnwrap(testSubject.releaseSegment(testSegmentId, 200, TimeUnit.MILLISECONDS));

            // then
            await().atMost(2, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(releasedSegments).contains(testSegmentId));

            // We assert the same segment id was observed at least twice:
            // first claim at startup, then a re-claim after the release duration elapsed.
            await().atMost(2, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(claimedSegments.stream()
                                                                  .filter(id -> id == testSegmentId)
                                                                  .count()).isGreaterThanOrEqualTo(2));
        }

        @Test
        void splitSegment() {
            // given
            int testSegmentId = 0;
            int testTokenClaimInterval = 500;

            withTestSubject(
                    List.of(),
                    c -> c.initialSegmentCount(1).tokenClaimInterval(testTokenClaimInterval)
            );

            // when
            startEventProcessor();

            // then - Assert the single WorkPackage is in progress prior to invoking the split.
            assertWithin(
                    500, TimeUnit.MILLISECONDS,
                    () -> assertNotNull(testSubject.processingStatus().get(testSegmentId))
            );

            // when
            CompletableFuture<Boolean> result = testSubject.splitSegment(testSegmentId);

            // then - Assert the SplitTask is done and completed successfully.
            assertWithin(testTokenClaimInterval * 2, TimeUnit.MILLISECONDS, () -> assertTrue(result.isDone()));
            assertFalse(result.isCompletedExceptionally());
            // then - Assert the Coordinator has set two WorkPackages on the segments.
            assertWithin(
                    testTokenClaimInterval, TimeUnit.MILLISECONDS,
                    () -> assertNotNull(testSubject.processingStatus().get(testSegmentId))
            );
            assertWithin(
                    testTokenClaimInterval, TimeUnit.MILLISECONDS,
                    () -> assertNotNull(testSubject.processingStatus().get(1))
            );
        }

        @Test
        void splitAndMergeSegmentOfGroupOf4() {
            // given
            int testSegmentId = 2;
            int splitSegmentId = 6;  // splitting segment 2 when there are 4 segments results in a new segment 6/7
            int testTokenClaimInterval = 500;

            withTestSubject(
                    List.of(),
                    c -> c.initialSegmentCount(4).tokenClaimInterval(testTokenClaimInterval)
            );

            // when
            startEventProcessor();

            // wait until the segment we want to split is in use, and verify all segments are correct in the token store:
            await().untilAsserted(() -> {
                assertNotNull(testSubject.processingStatus().get(testSegmentId));
                assertThat(tokenStore.fetchSegments(PROCESSOR_NAME, null).join())
                        .containsExactlyInAnyOrder(
                                new Segment(0, 3),
                                new Segment(1, 3),
                                new Segment(2, 3),
                                new Segment(3, 3)
                        );
            });

            // split segment:
            boolean success = testSubject.splitSegment(testSegmentId).join();

            assertThat(success).isTrue();

            // wait until the two split segments are in use, and verify all segments are correct in the token store:
            await().untilAsserted(() -> {
                assertNotNull(testSubject.processingStatus().get(testSegmentId));
                assertNotNull(testSubject.processingStatus().get(splitSegmentId));
                assertThat(tokenStore.fetchSegments(PROCESSOR_NAME, null).join())
                        .containsExactlyInAnyOrder(
                                new Segment(0, 3),
                                new Segment(1, 3),
                                new Segment(3, 3),
                                new Segment(2, 7),
                                new Segment(6, 7)
                        );
            });

            // merge segment:
            success = testSubject.mergeSegment(1).join();

            assertThat(success).isTrue();

            // wait until the merged segments is in use, and verify all segments are correct in the token store:
            await().untilAsserted(() -> {
                assertNotNull(testSubject.processingStatus().get(1));
                assertThat(tokenStore.fetchSegments(PROCESSOR_NAME, null).join())
                        .containsExactlyInAnyOrder(
                                new Segment(0, 3),
                                new Segment(1, 1),
                                new Segment(2, 7),
                                new Segment(6, 7)
                        );
            });
        }
    }

    @Nested
    class ErrorHandlerTest {

        @Test
        void errorHandlerIsInvokedWhenEventHandlingComponentHandleFails() {
            // given
            var mockErrorHandler = mock(ErrorHandler.class);
            var expectedError = new RuntimeException("Simulated handling error");
            var failingEventHandlingComponent = SimpleEventHandlingComponent.create("test");
            failingEventHandlingComponent.subscribe(new QualifiedName(String.class),
                                                    (event, context) -> MessageStream.failed(expectedError));
            withTestSubject(List.of(failingEventHandlingComponent), c -> c.errorHandler(mockErrorHandler));

            // when
            EventMessage testEvent = EventTestUtils.asEventMessage("Payload");
            stubMessageSource.publishMessage(testEvent);
            startEventProcessor();

            // then
            await().atMost(1, TimeUnit.SECONDS)
                   .untilAsserted(() -> {
                       var errorContextCaptor = ArgumentCaptor.forClass(ErrorContext.class);
                       verify(mockErrorHandler).handleError(errorContextCaptor.capture());

                       var capturedContext = errorContextCaptor.getValue();
                       assertThat(capturedContext.error()).isEqualTo(expectedError);
                       assertThat(capturedContext.eventProcessor()).isEqualTo(PROCESSOR_NAME);

                       var eventMessages = capturedContext.failedEvents();
                       assertThat(eventMessages).hasSize(1);
                       assertThat(eventMessages.getFirst()).isEqualTo(testEvent);
                   });
        }

        @Test
        void errorHandlerIsInvokedWhenEventHandlingComponentSupportsFails() {
            // given
            var mockErrorHandler = mock(ErrorHandler.class);
            var expectedError = new RuntimeException("Simulated handling error");
            when(defaultEventHandlingComponent.supports(new QualifiedName(Integer.class))).thenThrow(expectedError);
            withTestSubject(List.of(), c -> c.errorHandler(mockErrorHandler)
                                             .initialSegmentCount(1)
                                             .eventCriteria(ignored -> EventCriteria.havingAnyTag())
            );

            // when
            EventMessage testEvent = EventTestUtils.asEventMessage(42);
            stubMessageSource.publishMessage(testEvent);
            startEventProcessor();

            // then
            await().atMost(1, TimeUnit.SECONDS)
                   .untilAsserted(() -> {
                       var errorContextCaptor = ArgumentCaptor.forClass(ErrorContext.class);
                       verify(mockErrorHandler).handleError(errorContextCaptor.capture());

                       var capturedContext = errorContextCaptor.getValue();
                       assertThat(capturedContext.error()).isEqualTo(expectedError);
                       assertThat(capturedContext.eventProcessor()).isEqualTo(PROCESSOR_NAME);

                       var eventMessages = capturedContext.failedEvents();
                       assertThat(eventMessages).hasSize(1);
                       assertThat(eventMessages.getFirst()).isEqualTo(testEvent);
                   });
        }
    }

    @Nested
    class ResetSupportTest {

        @Test
        void startingAfterShutdownLetsProcessorProceed() {
            startEventProcessor();
            FutureUtils.joinAndUnwrap(testSubject.shutdown());

            List<EventMessage> events = createEvents(100);
            events.forEach(stubMessageSource::publishMessage);

            startEventProcessor();

            assertWithin(
                    1, TimeUnit.SECONDS,
                    () -> assertEquals(8, testSubject.processingStatus().size())
            );
            assertWithin(2, TimeUnit.SECONDS, () -> {
                long nonNullTokens = IntStream.range(0, 8)
                                              .mapToObj(i -> tokenStore.fetchToken(PROCESSOR_NAME,
                                                                                   i,
                                                                                   null))
                                              .filter(Objects::nonNull)
                                              .count();
                assertEquals(8, nonNullTokens);
            });
            assertEquals(8, testSubject.processingStatus().size());
        }

        @Test
        void supportsResetReturnsTrueWhenComponentSupportsReset() {
            assertTrue(testSubject.supportsReset());
        }

        @Test
        void resetTokensFailsIfTheProcessorIsStillRunning() {
            startEventProcessor();

            var thrown = assertThrows(IllegalStateException.class, () -> joinAndUnwrap(testSubject.resetTokens()));
            assertEquals("The Processor must be shut down before triggering a reset.", thrown.getMessage());
        }

        @Test
        void resetTokensWithDefaultFirstTokenAsStart() {
            // given
            TrackingToken initialToken = new GlobalSequenceTrackingToken(42);
            int expectedSegmentCount = 2;
            TrackingToken expectedToken = ReplayToken.createReplayToken(initialToken, initialToken);

            AtomicBoolean resetHandlerInvoked = new AtomicBoolean(false);
            simpleEhc.subscribe((ResetHandler) (resetContext, ctx) -> {
                resetHandlerInvoked.set(true);
                return MessageStream.empty();
            });
            withTestSubject(
                    List.of(),
                    c -> c.initialSegmentCount(expectedSegmentCount)
                          .initialToken(source -> CompletableFuture.completedFuture(initialToken))
            );

            // when - Start and stop the processor to initialize the tracking tokens
            startEventProcessor();
            assertWithin(2, TimeUnit.SECONDS, () -> {
                List<Segment> segments = joinAndUnwrap(tokenStore.fetchSegments(PROCESSOR_NAME, null));
                assertThat(segments).isNotNull();
                assertEquals(expectedSegmentCount, segments.size());
            });
            joinAndUnwrap(testSubject.shutdown());

            // when - Reset tokens
            joinAndUnwrap(testSubject.resetTokens());

            // then - Verify reset handler was invoked
            assertTrue(resetHandlerInvoked.get());

            // then - The token stays the same, as the original and token after reset are identical.
            List<Segment> segments = joinAndUnwrap(tokenStore.fetchSegments(PROCESSOR_NAME, null));
            assertThat(segments).isNotNull();
            TrackingToken token0 = joinAndUnwrap(
                    tokenStore.fetchToken(PROCESSOR_NAME, segments.get(0).getSegmentId(), null)
            );
            TrackingToken token1 = joinAndUnwrap(
                    tokenStore.fetchToken(PROCESSOR_NAME, segments.get(1).getSegmentId(), null)
            );
            assertEquals(expectedToken, token0);
            assertEquals(expectedToken, token1);
            // isReplay == true since ReplayToken#createReplayToken result in a ReplayToken for same position
            assertTrue(ReplayToken.isReplay(token0));
            assertTrue(ReplayToken.isReplay(token1));
        }

        @Test
        void resetTokensFromDefaultFirstTokenWithResetContext() {
            // given
            TrackingToken initialToken = new GlobalSequenceTrackingToken(42);
            int expectedSegmentCount = 2;
            String expectedContext = "my-context";
            byte[] convertedContext = converter.convert(expectedContext, byte[].class);
            TrackingToken expectedToken = ReplayToken.createReplayToken(initialToken, initialToken, convertedContext);

            AtomicBoolean resetHandlerInvoked = new AtomicBoolean(false);
            simpleEhc.subscribe((ResetHandler) (resetContext, ctx) -> {
                resetHandlerInvoked.set(true);
                return MessageStream.empty();
            });

            withTestSubject(
                    List.of(),
                    c -> c.initialSegmentCount(expectedSegmentCount)
                          .initialToken(source -> CompletableFuture.completedFuture(initialToken))
            );

            // when - Start and stop the processor to initialize the tracking tokens
            joinAndUnwrap(testSubject.start());
            await().atMost(Duration.ofSeconds(2L)).untilAsserted(
                    () -> {
                        List<Segment> segments = joinAndUnwrap(tokenStore.fetchSegments(PROCESSOR_NAME, null));
                        assertThat(segments).isNotNull();
                        assertEquals(expectedSegmentCount, segments.size());
                    }
            );
            joinAndUnwrap(testSubject.shutdown());

            // when - Reset tokens with context
            joinAndUnwrap(testSubject.resetTokens(expectedContext));

            // then - Verify reset handler was invoked
            assertTrue(resetHandlerInvoked.get());

            // then - The token stays the same, as the original and token after reset are identical.
            List<Segment> segments = joinAndUnwrap(tokenStore.fetchSegments(PROCESSOR_NAME, null));
            assertThat(segments).isNotNull();
            TrackingToken token0 = joinAndUnwrap(
                    tokenStore.fetchToken(PROCESSOR_NAME, segments.get(0).getSegmentId(), null)
            );
            TrackingToken token1 = joinAndUnwrap(
                    tokenStore.fetchToken(PROCESSOR_NAME, segments.get(1).getSegmentId(), null)
            );
            assertEquals(expectedToken, token0);
            assertEquals(expectedToken, token1);
            // isReplay == true since ReplayToken#createReplayToken result in a ReplayToken for same position
            assertTrue(ReplayToken.isReplay(token0));
            assertTrue(ReplayToken.isReplay(token1));
        }

        @Test
        void isReplaying() {
            withTestSubject(List.of(), c -> c.initialSegmentCount(1));

            List<EventMessage> events = createEvents(100);
            startEventProcessor();

            events.forEach(stubMessageSource::publishMessage);

            assertWithin(
                    1, TimeUnit.SECONDS,
                    () -> {
                        assertEquals(1, testSubject.processingStatus().size());
                        assertTrue(testSubject.processingStatus().get(0).isCaughtUp());
                        assertFalse(testSubject.processingStatus().get(0).isReplaying());
                        assertFalse(testSubject.isReplaying());
                    }
            );

            FutureUtils.joinAndUnwrap(testSubject.shutdown());
            FutureUtils.joinAndUnwrap(testSubject.resetTokens(source -> source.latestToken(processingContext)));
            startEventProcessor();

            assertWithin(
                    5, TimeUnit.SECONDS, () -> {
                        assertEquals(1, testSubject.processingStatus().size());
                        assertTrue(testSubject.processingStatus().get(0).isCaughtUp());
                        assertTrue(testSubject.processingStatus().get(0).isReplaying());
                        assertFalse(testSubject.isReplaying());
                    }
            );
        }

        @Test
        void resetTokensWithLatestTokenAsStart() {
            // given
            int expectedSegmentCount = 2;
            TrackingToken expectedToken = new GlobalSequenceTrackingToken(42);

            AtomicBoolean resetHandlerInvoked = new AtomicBoolean(false);
            simpleEhc.subscribe((ResetHandler) (resetContext, ctx) -> {
                resetHandlerInvoked.set(true);
                return MessageStream.empty();
            });

            withTestSubject(
                    List.of(),
                    c -> c.initialSegmentCount(expectedSegmentCount)
                          .initialToken(source -> CompletableFuture.completedFuture(expectedToken))
            );

            // when - Start and stop the processor to initialize the tracking tokens
            startEventProcessor();
            assertWithin(2, TimeUnit.SECONDS, () -> {
                List<Segment> segments = joinAndUnwrap(tokenStore.fetchSegments(PROCESSOR_NAME, null));
                assertThat(segments).isNotNull();
                assertEquals(expectedSegmentCount, segments.size());
            });
            joinAndUnwrap(testSubject.shutdown());

            // when - Reset tokens
            joinAndUnwrap(testSubject.resetTokens(source -> source.latestToken(null)));

            // then - Verify reset handler was invoked
            assertTrue(resetHandlerInvoked.get());

            // then - Verify tokens are wrapped in ReplayToken
            List<Segment> segments = joinAndUnwrap(tokenStore.fetchSegments(PROCESSOR_NAME, null));
            assertThat(segments).isNotNull();
            TrackingToken token0 = joinAndUnwrap(
                    tokenStore.fetchToken(PROCESSOR_NAME, segments.get(0).getSegmentId(), null)
            );
            TrackingToken token1 = joinAndUnwrap(
                    tokenStore.fetchToken(PROCESSOR_NAME, segments.get(1).getSegmentId(), null)
            );
            assertTrue(ReplayToken.isReplay(token0));
            assertTrue(ReplayToken.isReplay(token1));
        }

        @Test
        void resetTokensFromLatestTokenAndWithResetContext() {
            // given
            TrackingToken testToken = new GlobalSequenceTrackingToken(42);
            int expectedSegmentCount = 2;
            String expectedContext = "my-context";
            byte[] convertedContext = converter.convert(expectedContext, byte[].class);

            AtomicReference<Object> capturedResetPayload = new AtomicReference<>();
            simpleEhc.subscribe((ResetHandler) (resetContext, ctx) -> {
                capturedResetPayload.set(resetContext.payload());
                return MessageStream.empty();
            });

            withTestSubject(
                    List.of(),
                    c -> c.initialSegmentCount(expectedSegmentCount)
                          .initialToken(source -> CompletableFuture.completedFuture(testToken))
            );

            // when - Start and stop the processor to initialize the tracking tokens
            startEventProcessor();
            assertWithin(2, TimeUnit.SECONDS, () -> {
                List<Segment> segments = joinAndUnwrap(tokenStore.fetchSegments(PROCESSOR_NAME, null));
                assertThat(segments).isNotNull();
                assertEquals(expectedSegmentCount, segments.size());
            });
            joinAndUnwrap(testSubject.shutdown());

            // when - Reset tokens with context
            joinAndUnwrap(testSubject.resetTokens(source -> source.latestToken(null), expectedContext));

            // then - Verify reset handler received the context
            assertEquals(expectedContext, capturedResetPayload.get());

            // then - Verify tokens are wrapped in ReplayToken with context
            List<Segment> segments = joinAndUnwrap(tokenStore.fetchSegments(PROCESSOR_NAME, null));
            assertThat(segments).isNotNull();
            TrackingToken token0 = joinAndUnwrap(
                    tokenStore.fetchToken(PROCESSOR_NAME, segments.get(0).getSegmentId(), null)
            );
            TrackingToken token1 = joinAndUnwrap(
                    tokenStore.fetchToken(PROCESSOR_NAME, segments.get(1).getSegmentId(), null)
            );
            assertThat(token0).isNotNull();
            assertTrue(ReplayToken.isReplay(token0));
            assertThat(token1).isNotNull();
            assertTrue(ReplayToken.isReplay(token1));
            // Verify the reset context is stored in the ReplayToken
            assertThat(convertedContext).containsSequence(((ReplayToken) token0).resetContext());
            assertThat(convertedContext).containsSequence(((ReplayToken) token1).resetContext());
        }

        @Test
        void replayStatusChangedHandlerIsInvokedWhenResetTokensResultsInReplayToken() throws InterruptedException {
            // given
            CountDownLatch statusHandlerInvoked = new CountDownLatch(1);
            CompletableFuture<Message> replayStatusFuture = new CompletableFuture<>();
            AtomicReference<ReplayStatus> capturedStatus = new AtomicReference<>();
            simpleEhc.subscribe((ReplayStatusChangedHandler) (statusChange, ctx) -> {
                statusHandlerInvoked.countDown();
                return MessageStream.fromFuture(
                        replayStatusFuture.thenApply(ignored -> {
                            capturedStatus.set(statusChange.status());
                            return ignored;
                        })
                ).ignoreEntries();
            });

            TrackingToken initialToken = new GlobalSequenceTrackingToken(42);
            withTestSubject(
                    List.of(),
                    c -> c.initialSegmentCount(2)
                          .initialToken(source -> CompletableFuture.completedFuture(initialToken))
            );

            // when - Start and stop the processor to initialize tracking tokens with a non-null value
            startEventProcessor();
            await().atMost(Duration.ofSeconds(1))
                   .pollDelay(Duration.ofMillis(250))
                   .untilAsserted(() -> {
                       List<Segment> segments = joinAndUnwrap(tokenStore.fetchSegments(PROCESSOR_NAME, null));
                       assertThat(segments).isNotNull();
                       assertThat(segments).hasSize(2);
                   });
            joinAndUnwrap(testSubject.shutdown());

            // when - Reset tokens. Since tokens are non-null, ReplayToken.createReplayToken creates a ReplayToken.
            CompletableFuture<Void> resetTokensFuture = testSubject.resetTokens();
            if (statusHandlerInvoked.await(500, TimeUnit.SECONDS)) {
                replayStatusFuture.complete(null);
            } else {
                fail("Replay Status Changed Handler has not been invoked while this was expected.");
            }
            joinAndUnwrap(resetTokensFuture);

            // then
            assertThat(capturedStatus.get()).isEqualTo(ReplayStatus.REPLAY);
        }

        @Test
        void replayStatusChangedHandlerIsNotInvokedWhenResetTokensDoesNotResultInReplayToken() {
            // given
            AtomicBoolean replayStatusHandlerInvoked = new AtomicBoolean(false);
            simpleEhc.subscribe((ReplayStatusChangedHandler) (statusChange, ctx) -> {
                replayStatusHandlerInvoked.set(true);
                return MessageStream.empty();
            });
            withTestSubject(List.of(), c -> c.initialSegmentCount(1));

            // Initialize segments with null tokens
            joinAndUnwrap(tokenStore.initializeTokenSegments(PROCESSOR_NAME, 1, null, createProcessingContext()));

            // when - Reset tokens to null position. Since current tokens are null,
            // ReplayToken.createReplayToken(null, startPosition) returns startPosition directly (not a ReplayToken).
            joinAndUnwrap(testSubject.resetTokens(source -> source.firstToken(null)));

            // then
            assertThat(replayStatusHandlerInvoked.get()).isFalse();
        }
    }

    @Nested
    class ConfigurationTest {

        @Test
        void maxCapacityDefaultsToShortMax() {
            assertEquals(Short.MAX_VALUE, testSubject.maxCapacity());
        }

        @Test
        void maxCapacityReturnsConfiguredCapacity() {
            int expectedMaxCapacity = 500;
            withTestSubject(List.of(), (c -> c.maxClaimedSegments(expectedMaxCapacity)));

            assertEquals(expectedMaxCapacity, testSubject.maxCapacity());
        }

        @Test
        void zeroOrNegativeInitialSegmentCountThrowsAxonConfigurationException() {
            assertThrows(AxonConfigurationException.class,
                         () -> withTestSubject(List.of(), c -> c.initialSegmentCount(0)));
            assertThrows(AxonConfigurationException.class,
                         () -> withTestSubject(List.of(), c -> c.initialSegmentCount(-1)));
        }
    }

    private class TestApplicationContext implements ApplicationContext {

        private final Map<Key<?>, Object> components = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <C> C component(@NonNull Class<C> type, @Nullable String name) {
            return (C) components.get(new Key<>(type, name));
        }

        <C> void addComponent(@NonNull Class<C> type, @Nullable String name, @NonNull C component) {
            components.put(new Key<>(type, name), component);
        }

        private record Key<C>(Class<C> type, @Nullable String name) {

        }
    }
}
