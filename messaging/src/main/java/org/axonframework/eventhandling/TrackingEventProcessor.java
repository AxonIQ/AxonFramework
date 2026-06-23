/*
 * Copyright (c) 2010-2023. Axon Framework
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

package org.axonframework.eventhandling;

import org.axonframework.common.Assert;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.AxonNonTransientException;
import org.axonframework.common.ExceptionUtils;
import org.axonframework.common.ProcessUtils;
import org.axonframework.common.stream.BlockingStream;
import org.axonframework.common.transaction.NoTransactionManager;
import org.axonframework.common.transaction.TransactionManager;
import org.axonframework.eventhandling.tokenstore.TokenStore;
import org.axonframework.eventhandling.tokenstore.UnableToClaimTokenException;
import org.axonframework.lifecycle.Lifecycle;
import org.axonframework.lifecycle.Phase;
import org.axonframework.messaging.StreamableMessageSource;
import org.axonframework.messaging.unitofwork.BatchingUnitOfWork;
import org.axonframework.messaging.unitofwork.RollbackConfiguration;
import org.axonframework.messaging.unitofwork.RollbackConfigurationType;
import org.axonframework.messaging.unitofwork.UnitOfWork;
import org.axonframework.monitoring.MessageMonitor;
import org.axonframework.monitoring.NoOpMessageMonitor;
import org.axonframework.tracing.SpanFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.annotation.Nonnull;

import static java.util.Collections.singleton;
import static java.util.Collections.singletonMap;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.axonframework.common.BuilderUtils.assertNonNull;
import static org.axonframework.common.ProcessUtils.executeWithRetry;
import static org.axonframework.common.io.IOUtils.closeQuietly;

/**
 * EventProcessor implementation that tracks events from a {@link StreamableMessageSource}.
 * <p>
 * A supplied {@link TokenStore} allows the EventProcessor to keep track of its position in the event log. After
 * processing an event batch the EventProcessor updates its tracking token in the TokenStore.
 * <p>
 * A TrackingEventProcessor is able to continue processing from the last stored token when it is restarted. It is also
 * capable of replaying events from any starting token. To replay the entire event log, simply invoke {@link
 * #resetTokens()} on this processor to adjust the positions of the {@link TrackingToken}(s) within the {@link
 * TokenStore}. To replay from a specific point, {@link #resetTokens(Function)} can be utilized to define the new point
 * to start at.
 * <p>
 * Note, the {@link #getName()} of this {@link StreamingEventProcessor} is used to obtain the tracking token from the
 * {@code TokenStore}, so take care when renaming a {@link TrackingEventProcessor}.
 *
 * @author Rene de Waele
 * @author Christophe Bouhier
 * @since 3.0
 */
public class TrackingEventProcessor extends AbstractEventProcessor implements StreamingEventProcessor, Lifecycle {

    private static final Logger logger = LoggerFactory.getLogger(TrackingEventProcessor.class);

    private final StreamableMessageSource<TrackedEventMessage<?>> messageSource;
    private final TokenStore tokenStore;
    private final Function<StreamableMessageSource<TrackedEventMessage<?>>, TrackingToken> initialTrackingTokenBuilder;
    private final TransactionManager transactionManager;
    private final int batchSize;
    private final int segmentsSize;
    private final boolean autoStart;

    private final ThreadFactory threadFactory;
    private final Map<String, Thread> workerThreads = new ConcurrentSkipListMap<>();
    private final long workerTerminationTimeout;
    private final AtomicReference<State> state = new AtomicReference<>(State.NOT_STARTED);
    private final AtomicBoolean workLauncherRunning = new AtomicBoolean(false);
    private final ConcurrentMap<Integer, TrackerStatus> activeSegments = new ConcurrentSkipListMap<>();
    private final ConcurrentMap<Integer, Long> segmentReleaseDeadlines = new ConcurrentSkipListMap<>();
    private final String segmentIdResourceKey;
    private final String lastTokenResourceKey;
    private final AtomicInteger availableThreads;
    private final long tokenClaimInterval;
    private final AtomicReference<String> tokenStoreIdentifier = new AtomicReference<>();
    private final int maxThreadCount;

    private final ConcurrentMap<Integer, List<Instruction>> instructions = new ConcurrentHashMap<>();
    private final List<Instruction> launcherInstructions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean shouldRunLauncherImmediately = new AtomicBoolean(false);
    private final boolean storeTokenBeforeProcessing;
    private final int eventAvailabilityTimeout;
    private final EventTrackerStatusChangeListener trackerStatusChangeListener;

    /**
     * Instantiate a {@link TrackingEventProcessor} based on the fields contained in the {@link Builder}.
     * <p>
     * Will assert that the Event Processor {@code name}, {@link EventHandlerInvoker}, {@link StreamableMessageSource},
     * {@link TokenStore} and {@link TransactionManager} are not {@code null}, and will throw an {@link
     * AxonConfigurationException} if any of them is {@code null}.
     *
     * @param builder the {@link Builder} used to instantiate a {@link TrackingEventProcessor} instance
     */
    protected TrackingEventProcessor(Builder builder) {
        super(builder);
        TrackingEventProcessorConfiguration config = builder.trackingEventProcessorConfiguration;
        this.tokenClaimInterval = config.getTokenClaimInterval();
        this.eventAvailabilityTimeout = config.getEventAvailabilityTimeout();
        this.storeTokenBeforeProcessing = builder.storeTokenBeforeProcessing;
        this.batchSize = config.getBatchSize();
        this.autoStart = config.isAutoStart();

        this.messageSource = builder.messageSource;
        this.tokenStore = builder.tokenStore;

        this.segmentsSize = config.getInitialSegmentsCount();
        this.transactionManager = builder.transactionManager;

        this.availableThreads = new AtomicInteger(config.getMaxThreadCount());
        this.maxThreadCount = config.getMaxThreadCount();
        this.threadFactory = config.getThreadFactory(builder.name);
        this.workerTerminationTimeout = config.getWorkerTerminationTimeout();
        this.segmentIdResourceKey = "Processor[" + builder.name + "]/SegmentId";
        this.lastTokenResourceKey = "Processor[" + builder.name + "]/Token";
        this.initialTrackingTokenBuilder = config.getInitialTrackingToken();
        this.trackerStatusChangeListener = config.getEventTrackerStatusChangeListener();

        registerHandlerInterceptor((unitOfWork, interceptorChain) -> {
            if (!(unitOfWork instanceof BatchingUnitOfWork) || ((BatchingUnitOfWork<?>) unitOfWork).isFirstMessage()) {
                Instant startTime = now();
                TrackingToken lastToken = unitOfWork.getResource(lastTokenResourceKey);
                if (storeTokenBeforeProcessing) {
                    tokenStore.storeToken(lastToken,
                                          builder.name,
                                          unitOfWork.getResource(segmentIdResourceKey));
                } else {
                    tokenStore.extendClaim(getName(), unitOfWork.getResource(segmentIdResourceKey));
                }
                unitOfWork.onPrepareCommit(uow -> {
                    if (!storeTokenBeforeProcessing) {
                        tokenStore.storeToken(lastToken,
                                              builder.name,
                                              unitOfWork.getResource(segmentIdResourceKey));
                    } else if (now().isAfter(startTime.plusMillis(eventAvailabilityTimeout))) {
                        tokenStore.extendClaim(getName(), unitOfWork.getResource(segmentIdResourceKey));
                    }
                });
            }
            return interceptorChain.proceed();
        });
    }

    /**
     * Instantiate a Builder to be able to create a {@link TrackingEventProcessor}.
     * <p>
     * The {@link RollbackConfigurationType} defaults to a {@link RollbackConfigurationType#ANY_THROWABLE}, the
     * {@link ErrorHandler} is defaulted to a {@link PropagatingErrorHandler}, the {@link MessageMonitor} defaults to a
     * {@link NoOpMessageMonitor}, the {@link TrackingEventProcessorConfiguration} to a
     * {@link TrackingEventProcessorConfiguration#forSingleThreadedProcessing()} call, and the
     * {@link EventProcessorSpanFactory} to a {@link DefaultEventProcessorSpanFactory} backed by a
     * {@link org.axonframework.tracing.NoOpSpanFactory}. The Event Processor {@code name}, {@link EventHandlerInvoker},
     * {@link StreamableMessageSource}, {@link TokenStore} and {@link TransactionManager} are
     * <b>hard requirements</b> and as such should be provided.
     *
     * @return a Builder to be able to create a {@link TrackingEventProcessor}
     */
    public static Builder builder() {
        return new Builder();
    }

    private Instant now() {
        return GenericEventMessage.clock.instant();
    }

    @Override
    public void registerLifecycleHandlers(@Nonnull LifecycleRegistry handle) {
        if (autoStart) {
            handle.onStart(Phase.INBOUND_EVENT_CONNECTORS, this::start);
        }
        handle.onShutdown(Phase.INBOUND_EVENT_CONNECTORS, this::shutdownAsync);
    }

    /**
     * Start this processor. The processor will open an event stream on its message source in a new thread using {@link
     * StreamableMessageSource#openStream(TrackingToken)}. The {@link TrackingToken} used to open the stream will be
     * fetched from the {@link TokenStore}.
     * <p>
     * Upon start up of an application, this method will be invoked in the {@link Phase#INBOUND_EVENT_CONNECTORS}
     * phase.
     */
    @Override
    public void start() {
        if (activeProcessorThreads() > 0 || workLauncherRunning.get()) {
            if (state.get().isRunning()) {
                // then it's ok. It's already running
                return;
            } else {
                // this is problematic. There are still active threads pending a shutdown.
                throw new IllegalStateException("Cannot start this processor. It is pending shutdown...");
            }
        }
        State previousState = state.getAndSet(State.STARTED);
        if (!previousState.isRunning()) {
            workLauncherRunning.set(true);
            startSegmentWorkers();
        }
    }

    @Override
    public CompletableFuture<Boolean> splitSegment(int segmentId) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (!tokenStore.requiresExplicitSegmentInitialization()) {
            result.completeExceptionally(new UnsupportedOperationException(
                    "TokenStore must require explicit initialization to safely split tokens"
            ));
            return result;
        }

        if (!this.activeSegments.containsKey(segmentId)) {
            return CompletableFuture.completedFuture(false);
        }

        this.instructions.computeIfAbsent(segmentId, i -> new CopyOnWriteArrayList<>())
                         .add(new SplitSegmentInstruction(result, segmentId));
        return result;
    }

    @Override
    public String getTokenStoreIdentifier() {
        return tokenStoreIdentifier.updateAndGet(i -> i != null ? i : calculateIdentifier());
    }

    private String calculateIdentifier() {
        return transactionManager.fetchInTransaction(
                () -> tokenStore.retrieveStorageIdentifier().orElse("--unknown--")
        );
    }

    @Override
    public CompletableFuture<Boolean> mergeSegment(int segmentId) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (!tokenStore.requiresExplicitSegmentInitialization()) {
            result.completeExceptionally(new UnsupportedOperationException(
                    "TokenStore must require explicit initialization to safely merge tokens"
            ));
            return result;
        }

        TrackerStatus segmentStatus = this.activeSegments.get(segmentId);
        if (segmentStatus == null) {
            return CompletableFuture.completedFuture(false);
        }

        if (segmentId == segmentStatus.getSegment().mergeableSegmentId()) {
            logger.info("A merge request can only be fulfilled if there is more than one segment");
            return CompletableFuture.completedFuture(false);
        }

        int segmentToMerge = segmentStatus.getSegment().mergeableSegmentId();
        this.instructions.computeIfAbsent(segmentId, i -> new CopyOnWriteArrayList<>())
                         .add(new MergeSegmentInstruction(result, segmentId, segmentToMerge));
        return result;
    }

    /**
     * Fetch and process event batches continuously for as long as the processor is not shutting down. The processor
     * will process events in batches. The maximum size of size of each event batch is configurable.
     * <p>
     * Events with the same tracking token (which is possible as result of upcasting) should always be processed in the
     * same batch. In those cases the batch size may be larger than the one configured.
     *
     * @param segment The {@link Segment} of the Stream that should be processed.
     */
    protected void processingLoop(Segment segment) {
        BlockingStream<TrackedEventMessage<?>> eventStream = null;
        long errorWaitTime = 1;
        try {
            // only execute the loop when in running state, no processing instructions have been executed, and the
            // segment is not ignored for release
            while (state.get().isRunning() && !processInstructions(segment.getSegmentId())
                    && canClaimSegment(segment.getSegmentId())) {
                try {
                    eventStream = ensureEventStreamOpened(eventStream, segment);
                    processBatch(segment, eventStream);
                    errorWaitTime = 1;
                    TrackerStatus trackerStatus = activeSegments.get(segment.getSegmentId());
                    if (trackerStatus.isErrorState()) {
                        TrackerStatus validStatus =
                                activeSegments.computeIfPresent(segment.getSegmentId(), (k, v) -> v.unmarkError());
                        trackerStatusChangeListener.onEventTrackerStatusChange(
                                singletonMap(segment.getSegmentId(), validStatus)
                        );
                    }
                } catch (UnableToClaimTokenException e) {
                    logger.info("Segment is owned by another node. Releasing thread to process another segment...");
                    releaseSegment(segment.getSegmentId());
                } catch (Exception e) {
                    // Make sure to start with a clean event stream. The exception may have caused an illegal state
                    if (errorWaitTime == 1) {
                        logger.warn("Error occurred. Starting retry mode.", e);
                    }
                    logger.warn("Releasing claim on token and preparing for retry in {}s, error message: {}", errorWaitTime, e.getMessage());
                    TrackerStatus trackerStatus = activeSegments.get(segment.getSegmentId());
                    if (!trackerStatus.isErrorState()) {
                        TrackerStatus errorStatus =
                                activeSegments.computeIfPresent(segment.getSegmentId(), (k, v) -> v.markError(e));
                        trackerStatusChangeListener.onEventTrackerStatusChange(
                                singletonMap(segment.getSegmentId(), errorStatus)
                        );
                    }
                    releaseToken(segment);
                    closeQuietly(eventStream);
                    eventStream = null;
                    doSleepFor(SECONDS.toMillis(errorWaitTime));
                    errorWaitTime = Math.min(errorWaitTime * 2, 60);
                }
            }
        } finally {
            closeQuietly(eventStream);
            releaseToken(segment);
        }
    }

    private boolean processInstructions(int segmentId) {
        List<Instruction> toExecute = instructions.getOrDefault(segmentId, Collections.emptyList());
        boolean instructionsPresent = !toExecute.isEmpty();
        for (Instruction instruction : toExecute) {
            toExecute.remove(instruction);

            instruction.run();
        }

        return instructionsPresent;
    }

    private void releaseToken(Segment segment) {
        try {
            transactionManager.executeInTransaction(() -> {
                tokenStore.releaseClaim(getName(), segment.getSegmentId());
                eventHandlerInvoker().segmentReleased(segment);
            });
            logger.info("Released claim");
        } catch (Exception e) {
            // Ignore exception
            if (logger.isDebugEnabled()) {
                logger.debug("Release claim failed", e);
            } else if (logger.isInfoEnabled()) {
                logger.info("Release claim failed");
            }
        }
    }

    /**
     * Indicates whether the {@code eventMessage} identified with given {@code token} should be processed as part of the
     * given {@code segment}. This implementation is away of merge tokens and will recursively detect the (sub)segment
     * in which an event should be handled.
     *
     * @param token   The token to check segment validity for
     * @param segment The segment to process the event in
     * @return {@code true} if this event should be handled, otherwise {@code false}
     */
    protected Set<Segment> processingSegments(TrackingToken token, Segment segment) {
        Optional<MergedTrackingToken> mergedToken = WrappedToken.unwrap(token, MergedTrackingToken.class);
        if (mergedToken.isPresent()) {
            Segment[] splitSegments = segment.split();
            Set<Segment> segments = new TreeSet<>();
            if (mergedToken.get().isLowerSegmentAdvanced()) {
                segments.addAll(processingSegments(mergedToken.get().lowerSegmentToken(), splitSegments[0]));
            }

            if (mergedToken.get().isUpperSegmentAdvanced()) {
                segments.addAll(processingSegments(mergedToken.get().upperSegmentToken(), splitSegments[1]));
            }
            return segments;
        }
        return singleton(segment);
    }

    private void processBatch(Segment segment, BlockingStream<TrackedEventMessage<?>> eventStream) throws Exception {
        List<TrackedEventMessage<?>> batch = new ArrayList<>();
        try {
            TrackingToken lastToken = null;
            Collection<Segment> processingSegments = Collections.emptySet();

            long processingDeadline = now().toEpochMilli() + eventAvailabilityTimeout;
            long processingTime = eventAvailabilityTimeout;
            while (batch.isEmpty() && processingTime > 0 && eventStream.hasNextAvailable((int) processingTime, MILLISECONDS)) {
                processingTime = processingDeadline - now().toEpochMilli();

                final TrackedEventMessage<?> firstMessage = eventStream.nextAvailable();
                lastToken = firstMessage.trackingToken();
                processingSegments = processingSegments(lastToken, segment);
                if (canHandle(firstMessage, processingSegments)) {
                    batch.add(firstMessage);
                } else {
                    ignoreEvent(eventStream, firstMessage);
                }
                // Next to checking batch sizes, we must also ensure that both the current message in the batch
                // and the next (if present) allow for processing with a batch.
                for (int i = 0; isRegularProcessing(segment, processingSegments)
                        && i < batchSize * 10 && batch.size() < batchSize
                        && eventStream.peek().map(m -> isRegularProcessing(segment, m)).orElse(false); i++) {
                    final TrackedEventMessage<?> trackedEventMessage = eventStream.nextAvailable();
                    lastToken = trackedEventMessage.trackingToken();
                    if (canHandle(trackedEventMessage, processingSegments)) {
                        batch.add(trackedEventMessage);
                    } else {
                        ignoreEvent(eventStream, trackedEventMessage);
                    }
                }

                if (batch.isEmpty()) {
                    // The batch is empty because none of the events can be handled by this segment. Update the status.
                    TrackingToken finalLastToken = lastToken;
                    TrackerStatus previousStatus = activeSegments.get(segment.getSegmentId());
                    TrackerStatus updatedStatus = activeSegments.computeIfPresent(
                            segment.getSegmentId(), (k, v) -> v.advancedTo(finalLastToken)
                    );
                    if (previousStatus.isDifferent(updatedStatus, trackerStatusChangeListener.validatePositions())) {
                        trackerStatusChangeListener.onEventTrackerStatusChange(
                                singletonMap(segment.getSegmentId(), updatedStatus)
                        );
                    }
                }
            }

            if (lastToken == null) {
                // The token is never updated, so we extend the token claim.
                checkSegmentCaughtUp(segment, eventStream);
                transactionManager.executeInTransaction(
                        () -> tokenStore.extendClaim(getName(), segment.getSegmentId())
                );
                return;
            } else if (batch.isEmpty()) {
                // The token is updated but didn't contain events for this segment. So, we update the token position.
                TrackingToken finalLastToken = lastToken;
                transactionManager.executeInTransaction(
                        () -> tokenStore.storeToken(finalLastToken, getName(), segment.getSegmentId())
                );
                return;
            }

            TrackingToken finalLastToken = lastToken;
            // Make sure all subsequent events with the same token (if non-null) as the last are added as well.
            // These are the result of upcasting and should always be processed in the same batch.
            while (eventStream.peek().filter(event -> finalLastToken.equals(event.trackingToken())).isPresent()) {
                final TrackedEventMessage<?> trackedEventMessage = eventStream.nextAvailable();
                if (canHandle(trackedEventMessage, processingSegments)) {
                    batch.add(trackedEventMessage);
                } else {
                    ignoreEvent(eventStream, trackedEventMessage);
                }
            }

            UnitOfWork<? extends EventMessage<?>> unitOfWork = new BatchingUnitOfWork<>(batch);
            unitOfWork.attachTransaction(transactionManager);
            unitOfWork.resources().put(segmentIdResourceKey, segment.getSegmentId());
            unitOfWork.resources().put(lastTokenResourceKey, finalLastToken);
            processInUnitOfWork(batch, unitOfWork, processingSegments);

            TrackerStatus previousStatus = activeSegments.get(segment.getSegmentId());
            TrackerStatus updatedStatus =
                    activeSegments.computeIfPresent(segment.getSegmentId(), (k, v) -> v.advancedTo(finalLastToken));
            if (previousStatus.isDifferent(updatedStatus, trackerStatusChangeListener.validatePositions())) {
                trackerStatusChangeListener.onEventTrackerStatusChange(
                        singletonMap(segment.getSegmentId(), updatedStatus)
                );
            }
            checkSegmentCaughtUp(segment, eventStream);
        } catch (InterruptedException e) {
            if (isRunning()) {
                logger.error("Event processor [{}] was interrupted. Shutting down.", getName(), e);
                setShutdownState();
            }
            Thread.currentThread().interrupt();
        }
    }

    private void ignoreEvent(BlockingStream<TrackedEventMessage<?>> eventStream,
                             TrackedEventMessage<?> trackedEventMessage) {
        if (!canHandleType(trackedEventMessage.getPayloadType())) {
            eventStream.skipMessagesWithPayloadTypeOf(trackedEventMessage);
        }
        reportIgnored(trackedEventMessage);
    }

    /**
     * Will remove a thread and log at warn in case the named thread wasn't actually removed.
     *
     * @param name the expected name of the thread
     */
    private void removeThread(String name) {
        Thread removed = workerThreads.remove(name);
        if (isNull(removed)) {
            logger.warn(
                    "Expected to remove thread with name: '{}' from workerThreads, but it was not part of the map.",
                    name
            );
        }
    }

    /**
     * Indicates whether any of the components handling events for this Processor are able to handle the given {@code
     * eventMessage} for any of the given {@code segments}.
     *
     * @param eventMessage The message to handle
     * @param segments     The segments to handle the message in
     * @return whether the given message should be handled as part of anyof the give segments
     * @throws Exception when an exception occurs evaluating the message
     */
    protected boolean canHandle(EventMessage<?> eventMessage, Collection<Segment> segments) throws Exception {
        for (Segment segment : segments) {
            if (canHandle(eventMessage, segment)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRegularProcessing(Segment segment, TrackedEventMessage<?> nextMessage) {
        return nextMessage != null
                && isRegularProcessing(segment, processingSegments(nextMessage.trackingToken(), segment));
    }

    /**
     * Indicates if the given {@code processingSegments} should be considered normal processing for the given {@code
     * segment}. This is the case if only given {@code segment} is included in the {@code processingSegments}.
     *
     * @param segment            The segment assigned to this thread for processing
     * @param processingSegments The segments for which a received event should be processed
     * @return {@code true} if this is considered regular processor, {@code false} if this event should be treated
     * specially (in its own batch)
     */
    private boolean isRegularProcessing(Segment segment, Collection<Segment> processingSegments) {
        return processingSegments.size() == 1 && Objects.equals(processingSegments.iterator().next(), segment);
    }

    private void checkSegmentCaughtUp(Segment segment, BlockingStream<TrackedEventMessage<?>> eventStream) {
        if (!eventStream.hasNextAvailable()) {
            TrackerStatus previousStatus = activeSegments.get(segment.getSegmentId());
            if (!previousStatus.isCaughtUp()) {
                TrackerStatus updatedStates =
                        activeSegments.computeIfPresent(segment.getSegmentId(), (k, v) -> v.caughtUp());
                trackerStatusChangeListener.onEventTrackerStatusChange(singletonMap(segment.getSegmentId(), updatedStates));
            }
        }
    }

    private BlockingStream<TrackedEventMessage<?>> ensureEventStreamOpened(
            BlockingStream<TrackedEventMessage<?>> eventStreamIn, Segment segment) {
        BlockingStream<TrackedEventMessage<?>> eventStream = eventStreamIn;
        if (eventStream == null && state.get().isRunning()) {
            final TrackingToken trackingToken = transactionManager.fetchInTransaction(
                    () -> tokenStore.fetchToken(getName(), segment.getSegmentId())
            );
            logger.info("Fetched token: {} for segment: {}", trackingToken, segment);
            eventStream = transactionManager.fetchInTransaction(
                    () -> doOpenStream(trackingToken));
        }
        return eventStream;
    }

    private BlockingStream<TrackedEventMessage<?>> doOpenStream(TrackingToken trackingToken) {
        if (trackingToken instanceof WrappedToken) {
            return new WrappedMessageStream(
                    (WrappedToken) trackingToken,
                    messageSource.openStream(WrappedToken.unwrapLowerBound(trackingToken))
            );
        }
        return messageSource.openStream(WrappedToken.unwrapLowerBound(trackingToken));
    }

    /**
     * {@inheritDoc}
     * <p>
     * This will also ignore the specified this segment for "re-claiming" for twice the {@link
     * TrackingEventProcessorConfiguration#getTokenClaimInterval()} token claim interval.
     */
    @Override
    public void releaseSegment(int segmentId) {
        releaseSegment(segmentId, tokenClaimInterval * 2, MILLISECONDS);
    }

    /**
     * {@inheritDoc}
     * <p>
     * This method will put the segment on a non-claim map. During the next iteration of the {@link #processingLoop(Segment)}
     * the segments will be unclaimed and the worker stopped if it is found to be in this map. This means it can take
     * up to the batch processing time, or up to the {@link #eventAvailabilityTimeout} if there are no events in the stream
     * for the segment to be unclaimed.
     */
    @Override
    public void releaseSegment(int segmentId, long releaseDuration, TimeUnit unit) {
        segmentReleaseDeadlines.put(segmentId, System.currentTimeMillis() + unit.toMillis(releaseDuration));
    }

    /**
     * {@inheritDoc}
     * <p>
     * This method will add an instruction for the {@link WorkerLauncher} and set a flag that interrupts its sleep
     * to reduce the time it takes for the processor to claim the segment. Note that a thread has to be available for
     * a segment to start processing. The result will be {@code false} if there is none available.
     */
    @Override
    public CompletableFuture<Boolean> claimSegment(int segmentId) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (this.activeSegments.containsKey(segmentId)) {
            return CompletableFuture.completedFuture(true);
        }

        this.launcherInstructions.add(new ClaimSegmentInstruction(result, segmentId));
        shouldRunLauncherImmediately.set(true);
        return result;
    }

    private boolean canClaimSegment(int segmentId) {
        return segmentReleaseDeadlines.getOrDefault(segmentId, Long.MIN_VALUE) < System.currentTimeMillis();
    }

    @Override
    public void resetTokens() {
        resetTokens(initialTrackingTokenBuilder);
    }

    @Override
    public <R> void resetTokens(R resetContext) {
        resetTokens(initialTrackingTokenBuilder, resetContext);
    }

    @Override
    public void resetTokens(
            @Nonnull Function<StreamableMessageSource<TrackedEventMessage<?>>, TrackingToken> initialTrackingTokenSupplier) {
        resetTokens(initialTrackingTokenSupplier.apply(messageSource));
    }

    @Override
    public <R> void resetTokens(
            @Nonnull Function<StreamableMessageSource<TrackedEventMessage<?>>, TrackingToken> initialTrackingTokenSupplier,
            R resetContext
    ) {
        resetTokens(initialTrackingTokenSupplier.apply(messageSource), resetContext);
    }

    @Override
    public void resetTokens(@Nonnull TrackingToken startPosition) {
        resetTokens(startPosition, null);
    }

    @Override
    public <R> void resetTokens(@Nonnull TrackingToken startPosition, R resetContext) {
        Assert.state(supportsReset(), () -> "The handlers assigned to this Processor do not support a reset");
        Assert.state(!isRunning() && activeProcessorThreads() == 0 && !workLauncherRunning.get(),
                     () -> "TrackingProcessor must be shut down before triggering a reset");
        transactionManager.executeInTransaction(() -> {
            int[] segments = tokenStore.fetchSegments(getName());
            TrackingToken[] tokens = new TrackingToken[segments.length];
            for (int i = 0; i < segments.length; i++) {
                tokens[i] = tokenStore.fetchToken(getName(), segments[i]);
            }
            // we now have all tokens, hurray
            eventHandlerInvoker().performReset(resetContext);

            for (int i = 0; i < tokens.length; i++) {
                tokenStore.storeToken(ReplayToken.createReplayToken(tokens[i], startPosition, resetContext),
                                      getName(),
                                      segments[i]);
            }
        });
    }

    @Override
    public boolean supportsReset() {
        return eventHandlerInvoker().supportsReset();
    }

    @Override
    public boolean isRunning() {
        return state.get().isRunning();
    }

    @Override
    public boolean isError() {
        return state.get() == State.PAUSED_ERROR;
    }

    /**
     * Returns the {@link StreamableMessageSource} this processor is using
     *
     * @return {@link StreamableMessageSource}
     */
    public StreamableMessageSource<? extends TrackedEventMessage<?>> getMessageSource() {
        return messageSource;
    }

    /**
     * Shuts down the processor. Blocks until shutdown is complete.
     */
    @Override
    public void shutDown() {
        setShutdownState();
        awaitTermination().join();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Will be shutdown on the {@link Phase#INBOUND_EVENT_CONNECTORS} phase.
     */
    @Override
    public CompletableFuture<Void> shutdownAsync() {
        setShutdownState();
        return awaitTermination();
    }

    private void setShutdownState() {
        if (state.getAndSet(State.SHUT_DOWN).isRunning()) {
            logger.info("Shutdown state set for Processor '{}'.", getName());
        }
    }

    private CompletableFuture<Void> awaitTermination() {
        if (activeProcessorThreads() <= 0 && !workLauncherRunning.get()) {
            return CompletableFuture.completedFuture(null);
        }

        logger.info("Processor '{}' awaiting termination...", getName());
        ProcessUtils.executeUntilTrue(() -> !workLauncherRunning.get(), 10L, 100L);
        return workerThreads.entrySet()
                            .stream()
                            .map(worker -> CompletableFuture.runAsync(() -> {
                                try {
                                    Thread workerThread = worker.getValue();
                                    workerThread.join(workerTerminationTimeout);
                                    if (workerThread.isAlive()) {
                                        workerThread.interrupt();
                                        workerThread.join(workerTerminationTimeout);
                                        if (workerThread.isAlive()) {
                                            logger.warn("Forced shutdown of Tracking Processor Worker '{}' was unsuccessful. Consider increasing workerTerminationTimeout.", worker.getKey()
                                            );
                                        }
                                    }
                                } catch (InterruptedException e) {
                                    logger.info(
                                            "Thread was interrupted waiting for TrackingProcessor Worker '{}' shutdown.",
                                            worker.getKey()
                                    );
                                    Thread.currentThread().interrupt();
                                }
                            }))
                            .reduce(CompletableFuture::allOf)
                            .orElse(CompletableFuture.completedFuture(null));
    }

    /**
     * Returns the number of threads this processor has available to assign segments. These threads may or may not
     * already be active.
     *
     * @return the number of threads this processor has available to assign segments.
     */
    public int availableProcessorThreads() {
        return availableThreads.get();
    }

    @Override
    public int maxCapacity() {
        return maxThreadCount;
    }

    /**
     * Returns an approximation of the number of threads currently processing events.
     *
     * @return an approximation of the number of threads currently processing events
     */
    public int activeProcessorThreads() {
        return this.activeSegments.size();
    }

    @Override
    public Map<Integer, EventTrackerStatus> processingStatus() {
        return Collections.unmodifiableMap(activeSegments);
    }

    /**
     * Get the state of the event processor. This will indicate whether or not the processor has started or is shutting
     * down.
     *
     * @return the processor state
     */
    protected State getState() {
        return state.get();
    }

    /**
     * Starts workers for a number of segments. When only the {@link Segment#ROOT_SEGMENT root } segment {@link
     * TokenStore#fetchSegments(String) exists} in the  TokenStore, it will be split in multiple segments as configured
     * by the {@link TrackingEventProcessorConfiguration#andInitialSegmentsCount(int)}, otherwise the existing segments
     * in the TokenStore will be used.
     * <p/>
     * An attempt will be made to instantiate a worker for each segment. This will succeed when the number of threads
     * matches the requested segments. The number of active threads can be configured with {@link
     * TrackingEventProcessorConfiguration#forParallelProcessing(int)}. When insufficient threads are available to serve
     * the number of segments, it will result in some segments not being processed.
     */
    protected void startSegmentWorkers() {
        spawnWorkerThread(new WorkerLauncher()).start();
    }

    private Thread spawnWorkerThread(Worker worker) {
        Thread workerThread = threadFactory.newThread(worker);
        workerThreads.put(worker.name(), workerThread);
        return workerThread;
    }

    /**
     * Instructs the current Thread to sleep until the given deadline. This method may be overridden to check for flags
     * that have been set to return earlier than the given deadline.
     * <p>
     * The default implementation will sleep in blocks of 100ms, intermittently checking for the processor's state. Once
     * the processor stops running, this method will return immediately (after detecting the state change).
     *
     * @param millisToSleep The number of milliseconds to sleep
     */
    protected void doSleepFor(long millisToSleep) {
        doSleepFor(millisToSleep, new AtomicBoolean(false));
    }

    /**
     * Instructs the current Thread to sleep until the given deadline. This method may be overridden to check for flags
     * that have been set to return earlier than the given deadline.
     * <p>
     * The default implementation will sleep in blocks of 100ms, intermittently checking for the processor's state. Once
     * the processor stops running, this method will return immediately (after detecting the state change).
     * <p>
     * This method will also return when the given interruptFlag is set to {@code true}. This can facilitate immediately
     * needed actions for the sleeping thread.
     *
     * @param millisToSleep The number of milliseconds to sleep
     * @param interruptFlag A flag that is set when the thread should stop sleeping
     */
    protected void doSleepFor(long millisToSleep, AtomicBoolean interruptFlag) {
        long deadline = System.currentTimeMillis() + millisToSleep;
        try {
            long timeLeft;
            while (!interruptFlag.get()
                    && getState().isRunning()
                    && (timeLeft = deadline - System.currentTimeMillis()) > 0) {
                Thread.sleep(Math.min(timeLeft, 100));
            }
        } catch (InterruptedException e) {
            if (isRunning()) {
                logger.warn("Thread interrupted. Preparing to shut down event processor");
                setShutdownState();
            }
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Enum representing the possible states of the Processor
     */
    protected enum State {

        /**
         * Indicates the processor has not been started yet.
         */
        NOT_STARTED(false),
        /**
         * Indicates that the processor has started and is (getting ready to) processing events
         */
        STARTED(true),
        /**
         * Indicates that the processor has been paused. It can be restarted to continue processing.
         */
        PAUSED(false),
        /**
         * Indicates that the processor has been shut down. It can be restarted to continue processing.
         */
        SHUT_DOWN(false),
        /**
         * Indicates that the processor has been paused due to an error that it was unable to recover from. Restarting
         * is possible once the error has been resolved.
         */
        PAUSED_ERROR(false);

        private final boolean allowProcessing;

        State(boolean allowProcessing) {
            this.allowProcessing = allowProcessing;
        }

        boolean isRunning() {
            return allowProcessing;
        }
    }

    /**
     * Builder class to instantiate a {@link TrackingEventProcessor}.
     * <p>
     * The {@link RollbackConfigurationType} defaults to a {@link RollbackConfigurationType#ANY_THROWABLE}, the
     * {@link ErrorHandler} is defaulted to a {@link PropagatingErrorHandler}, the {@link MessageMonitor} defaults to a
     * {@link NoOpMessageMonitor}, the {@link EventProcessorSpanFactory} defaults to a
     * {@link DefaultEventProcessorSpanFactory} backed by a {@link org.axonframework.tracing.NoOpSpanFactory} and the
     * {@link TrackingEventProcessorConfiguration} to a
     * {@link TrackingEventProcessorConfiguration#forSingleThreadedProcessing()} call. The Event Processor {@code name},
     * {@link EventHandlerInvoker}, {@link StreamableMessageSource}, {@link TokenStore} and {@link TransactionManager}
     * are
     * <b>hard requirements</b> and as such should be provided.
     */
    public static class Builder extends AbstractEventProcessor.Builder {

        private StreamableMessageSource<TrackedEventMessage<?>> messageSource;
        private TokenStore tokenStore;
        private TransactionManager transactionManager;
        private TrackingEventProcessorConfiguration trackingEventProcessorConfiguration =
                TrackingEventProcessorConfiguration.forSingleThreadedProcessing();
        private Boolean storeTokenBeforeProcessing;

        public Builder() {
            super.rollbackConfiguration(RollbackConfigurationType.ANY_THROWABLE);
        }

        @Override
        public Builder name(@Nonnull String name) {
            super.name(name);
            return this;
        }

        @Override
        public Builder eventHandlerInvoker(@Nonnull EventHandlerInvoker eventHandlerInvoker) {
            super.eventHandlerInvoker(eventHandlerInvoker);
            return this;
        }

        /**
         * {@inheritDoc}. Defaults to a {@link RollbackConfigurationType#ANY_THROWABLE})
         */
        @Override
        public Builder rollbackConfiguration(@Nonnull RollbackConfiguration rollbackConfiguration) {
            super.rollbackConfiguration(rollbackConfiguration);
            return this;
        }

        @Override
        public Builder errorHandler(@Nonnull ErrorHandler errorHandler) {
            super.errorHandler(errorHandler);
            return this;
        }

        @Override
        public Builder messageMonitor(@Nonnull MessageMonitor<? super EventMessage<?>> messageMonitor) {
            super.messageMonitor(messageMonitor);
            return this;
        }

        @Override
        public Builder spanFactory(@Nonnull EventProcessorSpanFactory spanFactory) {
            super.spanFactory(spanFactory);
            return this;
        }

        @Override
        @Deprecated
        public Builder spanFactory(@Nonnull SpanFactory spanFactory) {
            super.spanFactory(spanFactory);
            return this;
        }

        /**
         * Sets the {@link StreamableMessageSource} (e.g. the {@link EventBus}) which this {@link EventProcessor} will
         * track.
         *
         * @param messageSource the {@link StreamableMessageSource} (e.g. the {@link EventBus}) which this
         *                      {@link EventProcessor} will track
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder messageSource(StreamableMessageSource<TrackedEventMessage<?>> messageSource) {
            assertNonNull(messageSource, "StreamableMessageSource may not be null");
            this.messageSource = messageSource;
            return this;
        }

        /**
         * Sets the {@link TokenStore} used to store and fetch event tokens that enable this {@link EventProcessor} to
         * track its progress.
         *
         * @param tokenStore the {@link TokenStore} used to store and fetch event tokens that enable this {@link
         *                   EventProcessor} to track its progress
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder tokenStore(TokenStore tokenStore) {
            assertNonNull(tokenStore, "TokenStore may not be null");
            this.tokenStore = tokenStore;
            return this;
        }

        /**
         * Sets the {@link TransactionManager} used when processing {@link EventMessage}s.
         * <p/>
         * Note that setting this value influences the behavior for storing tokens either at the start or at the end of
         * a batch. If a TransactionManager other than a {@link NoTransactionManager} is configured, the default
         * behavior is to store the last token of the Batch to the Token Store before processing of events begins. If
         * the {@link NoTransactionManager} is provided, the default is to extend the claim at the start of the unit of
         * work, and update the token after processing Events. When tokens are stored at the start of a batch, a claim
         * extension will be sent at the end of the batch if processing that batch took longer than the {@link
         * TrackingEventProcessorConfiguration#andEventAvailabilityTimeout(long, TimeUnit) tokenClaimUpdateInterval}.
         * <p>
         * Use {@link #storingTokensAfterProcessing()} to force storage of tokens at the end of a batch.
         *
         * @param transactionManager the {@link TransactionManager} used when processing {@link EventMessage}s
         * @return the current Builder instance, for fluent interfacing
         * @see #storingTokensAfterProcessing()
         */
        public Builder transactionManager(TransactionManager transactionManager) {
            assertNonNull(transactionManager, "TransactionManager may not be null");
            this.transactionManager = transactionManager;
            if (storeTokenBeforeProcessing == null) {
                storeTokenBeforeProcessing = transactionManager != NoTransactionManager.instance();
            }
            return this;
        }

        /**
         * Sets the {@link TrackingEventProcessorConfiguration} containing the fine grained configuration options for a
         * {@link TrackingEventProcessor}. Defaults to a {@link TrackingEventProcessorConfiguration#forSingleThreadedProcessing()}
         * call.
         *
         * @param trackingEventProcessorConfiguration the {@link TrackingEventProcessorConfiguration} containing the
         *                                            fine grained configuration options for a {@link
         *                                            TrackingEventProcessor}
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder trackingEventProcessorConfiguration(
                TrackingEventProcessorConfiguration trackingEventProcessorConfiguration) {
            assertNonNull(trackingEventProcessorConfiguration, "TrackingEventProcessorConfiguration may not be null");
            this.trackingEventProcessorConfiguration = trackingEventProcessorConfiguration;
            return this;
        }

        /**
         * Set this processor to store Tracking Tokens only at the end of processing. This has an impact on performance,
         * as the processor will need to extend the claim at the start of the process, and then update the token at the
         * end. This causes 2 round-trips to the Token Store per batch of events.
         * <p>
         * Enable this when a Token Store cannot participate in a transaction, or when at-most-once-delivery semantics
         * are desired.
         * <p>
         * The default behavior is to store the last token of the Batch to the Token Store before processing of events
         * begins, if a TransactionManager is configured. If the {@link NoTransactionManager} is provided, the default
         * is to extend the claim at the start of the unit of work, and update the token after processing Events. When
         * tokens are stored at the start of a batch, a claim extension will be sent at the end of the batch if
         * processing that batch took longer than the {@link TrackingEventProcessorConfiguration#andEventAvailabilityTimeout(long,
         * TimeUnit) tokenClaimUpdateInterval}.
         *
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder storingTokensAfterProcessing() {
            this.storeTokenBeforeProcessing = false;
            return this;
        }

        /**
         * Initializes a {@link TrackingEventProcessor} as specified through this Builder.
         *
         * @return a {@link TrackingEventProcessor} as specified through this Builder
         */
        public TrackingEventProcessor build() {
            return new TrackingEventProcessor(this);
        }

        /**
         * Validates whether the fields contained in this Builder are set accordingly.
         *
         * @throws AxonConfigurationException if one field is asserted to be incorrect according to the Builder's
         *                                    specifications
         */
        @Override
        protected void validate() throws AxonConfigurationException {
            super.validate();
            if (storeTokenBeforeProcessing == null) {
                storeTokenBeforeProcessing = false;
            }
            assertNonNull(messageSource, "The StreamableMessageSource is a hard requirement and should be provided");
            assertNonNull(tokenStore, "The TokenStore is a hard requirement and should be provided");
            assertNonNull(transactionManager, "The TransactionManager is a hard requirement and should be provided");
        }
    }

    private static class WrappedMessageStream implements BlockingStream<TrackedEventMessage<?>> {

        private final BlockingStream<TrackedEventMessage<?>> delegate;
        private WrappedToken lastToken;

        public WrappedMessageStream(WrappedToken token, BlockingStream<TrackedEventMessage<?>> delegate) {
            this.delegate = delegate;
            this.lastToken = token;
        }

        @Override
        public Optional<TrackedEventMessage<?>> peek() {
            return delegate.peek();
        }

        @Override
        public boolean hasNextAvailable(int timeout, TimeUnit unit) throws InterruptedException {
            return delegate.hasNextAvailable(timeout, unit);
        }

        @Override
        public TrackedEventMessage<?> nextAvailable() throws InterruptedException {
            TrackedEventMessage<?> trackedEventMessage = alterToken(delegate.nextAvailable());
            this.lastToken = trackedEventMessage.trackingToken() instanceof WrappedToken
                             ? (WrappedToken) trackedEventMessage.trackingToken()
                             : null;
            return trackedEventMessage;
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public boolean hasNextAvailable() {
            return delegate.hasNextAvailable();
        }

        public <T> TrackedEventMessage<T> alterToken(TrackedEventMessage<T> message) {
            if (lastToken == null) {
                return message;
            }
            return message.withTrackingToken(lastToken.advancedTo(message.trackingToken()));
        }
    }

    private abstract class Instruction implements Runnable {

        private final CompletableFuture<Boolean> result;

        public Instruction(CompletableFuture<Boolean> result) {
            this.result = result;
        }

        public void run() {
            try {
                executeWithRetry(() -> transactionManager.executeInTransaction(() -> result.complete(runSafe())),
                                 re -> ExceptionUtils.findException(re, UnableToClaimTokenException.class).isPresent(),
                                 tokenClaimInterval, MILLISECONDS, 10);
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        }

        protected abstract boolean runSafe();
    }

    private class TrackingSegmentWorker implements Worker {

        private final Segment segment;

        public TrackingSegmentWorker(Segment segment) {
            this.segment = segment;
        }

        @Override
        public void run() {
            try {
                processingLoop(segment);
            } catch (Throwable e) {
                logger.error("Processing loop ended due to uncaught exception. Pausing processor in Error State.", e);
                state.set(State.PAUSED_ERROR);
                throw e;
            } finally {
                cleanUp();
            }
        }

        @Override
        public String toString() {
            return "TrackingSegmentWorker{" +
                    "processor=" + getName() +
                    ", segment=" + segment +
                    '}';
        }

        @Override
        public String name() {
            return TrackingSegmentWorker.class.getSimpleName() + segment.getSegmentId();
        }

        private void freeSegment() {
            TrackerStatus removedStatus = activeSegments.remove(segment.getSegmentId());
            if (removedStatus != null) {
                trackerStatusChangeListener.onEventTrackerStatusChange(
                        singletonMap(segment.getSegmentId(), new RemovedTrackerStatus(removedStatus))
                );
            }
        }

        @Override
        public void cleanUp() {
            freeSegment();
            removeThread(name());
            logger.info("Worker for segment {} stopped.", segment);

            final int currentAvailableThreads = availableThreads.getAndIncrement();

            if (!workLauncherRunning.get() && currentAvailableThreads == 0 && getState().isRunning()) {
                boolean launchedSinceGetCalled = workLauncherRunning.getAndSet(true);
                if (!launchedSinceGetCalled) {
                    logger.info("No Worker Launcher active. Using current thread to assign segments.");
                    Worker workerLauncher = new WorkerLauncher();
                    workerThreads.put(workerLauncher.name(), Thread.currentThread());
                    workerLauncher.run();
                }
            }
        }
    }

    private class WorkerLauncher implements Worker {

        private TrackingSegmentWorker workingInCurrentThread = null;

        @Override
        public void run() {
            try {
                int waitTime = 1;
                String processorName = TrackingEventProcessor.this.getName();
                while (getState().isRunning()) {
                    List<Segment> segmentsToClaim;
                    shouldRunLauncherImmediately.set(false);

                    while (!launcherInstructions.isEmpty()) {
                        Instruction instruction = launcherInstructions.get(0);
                        launcherInstructions.remove(instruction);
                        instruction.run();
                    }

                    try {
                        int[] tokenStoreCurrentSegments = transactionManager.fetchInTransaction(
                                () -> tokenStore.fetchSegments(processorName)
                        );

                        // When in an initial stage, split segments to the requested number.
                        if (tokenStoreCurrentSegments.length == 0 && segmentsSize > 0) {
                            transactionManager.executeInTransaction(
                                    () -> {
                                        TrackingToken initialToken = initialTrackingTokenBuilder.apply(messageSource);
                                        tokenStore.initializeTokenSegments(processorName, segmentsSize, initialToken);
                                    }
                            );
                        }
                        segmentsToClaim = transactionManager.fetchInTransaction(
                                () -> tokenStore.fetchAvailableSegments(processorName)
                        );
                        waitTime = 1;
                    } catch (Exception e) {
                        if (waitTime == 1) {
                            logger.warn("Fetch Segments for Processor '{}' failed: {}. Preparing for retry in {}s",
                                        processorName, e.getMessage(), waitTime, e);
                        } else {
                            logger.info(
                                    "Fetching Segments for Processor '{}' still failing: {}. Preparing for retry in {}s",
                                    processorName, e.getMessage(), waitTime
                            );
                        }
                        doSleepFor(SECONDS.toMillis(waitTime), shouldRunLauncherImmediately);
                        waitTime = Math.min(waitTime * 2, 60);

                        continue;
                    }

                    // Submit segmentation workers matching the size of our thread pool (-1 for the current dispatcher).
                    // Keep track of the last processed segments...
                    for (int i = 0; i < segmentsToClaim.size() && availableThreads.get() > 0; i++) {
                        Segment segment = segmentsToClaim.get(i);
                        int segmentId = segment.getSegmentId();

                        if (!activeSegments.containsKey(segmentId) && canClaimSegment(segmentId)) {
                            try {
                                transactionManager.executeInTransaction(() -> {
                                    TrackingToken token = tokenStore.fetchToken(processorName, segment);
                                    logger.info("Worker assigned to segment {} for processing", segment);
                                    TrackerStatus newStatus = new TrackerStatus(segment, token);
                                    TrackerStatus previousStatus = activeSegments.putIfAbsent(segmentId, newStatus);

                                    if (previousStatus == null) {
                                        trackerStatusChangeListener.onEventTrackerStatusChange(
                                                singletonMap(segmentId, new AddedTrackerStatus(newStatus))
                                        );
                                    }
                                });
                            } catch (UnableToClaimTokenException ucte) {
                                // When not able to claim a token for a given segment, we skip the
                                logger.debug("Unable to claim the token for segment: {}. It is owned by another process or has been split/merged concurrently", segmentId
                                );

                                TrackerStatus removedStatus = activeSegments.remove(segmentId);
                                if (removedStatus != null) {
                                    trackerStatusChangeListener.onEventTrackerStatusChange(
                                            singletonMap(segmentId, new RemovedTrackerStatus(removedStatus))
                                    );
                                }

                                continue;
                            } catch (Exception e) {
                                TrackerStatus removedStatus = activeSegments.remove(segmentId);
                                if (removedStatus != null) {
                                    trackerStatusChangeListener.onEventTrackerStatusChange(
                                            singletonMap(segmentId, new RemovedTrackerStatus(removedStatus))
                                    );
                                }
                                if (AxonNonTransientException.isCauseOf(e)) {
                                    logger.error(
                                            "An unrecoverable error has occurred wile attempting to claim a token "
                                                    + "for segment: {}. Shutting down processor [{}].",
                                            segmentId, getName(), e
                                    );
                                    state.set(State.PAUSED_ERROR);
                                    break;
                                }
                                logger.info(
                                        "An error occurred while attempting to claim a token for segment: {}. "
                                                + "Will retry later...",
                                        segmentId, e
                                );
                                break;
                            }

                            TrackingSegmentWorker trackingSegmentWorker =
                                    new TrackingSegmentWorker(activeSegments.get(segmentId).getSegment());
                            if (availableThreads.decrementAndGet() > 0) {
                                logger.info("Dispatching new tracking segment worker: {}", trackingSegmentWorker);
                                spawnWorkerThread(trackingSegmentWorker).start();
                            } else {
                                workingInCurrentThread = trackingSegmentWorker;
                                return;
                            }
                        }
                    }
                    doSleepFor(tokenClaimInterval, shouldRunLauncherImmediately);
                }
            } finally {
                cleanUp();
            }
        }

        @Override
        public String name() {
            return WorkerLauncher.class.getSimpleName();
        }

        /**
         * Cleans up once the worker is done. To make sure a shutdown run at almost the same time the cleanup is
         * triggered this has some complexity. We need to make sure when it's still running, and becoming a worker, the
         * thread is added before switching the {@code workLauncherRunning} as a shutdown might be called in between,
         * and might leave the thread running.
         */
        @Override
        public void cleanUp() {
            removeThread(name());
            if (nonNull(workingInCurrentThread)) {
                if (getState().isRunning()) {
                    logger.info("Using current Thread for last segment worker: {}", workingInCurrentThread);
                    workerThreads.put(workingInCurrentThread.name(), Thread.currentThread());
                    workLauncherRunning.set(false);
                    workingInCurrentThread.run();
                } else {
                    logger.info("freeing segment since segment worker will not be started for worker: {}",
                                workingInCurrentThread);
                    workingInCurrentThread.freeSegment();
                    workLauncherRunning.set(false);
                }
            } else {
                workLauncherRunning.set(false);
            }
        }

    }


    private class ClaimSegmentInstruction extends Instruction {

        private final int segmentId;

        public ClaimSegmentInstruction(CompletableFuture<Boolean> result, int segmentId) {
            super(result);
            this.segmentId = segmentId;
        }

        @Override
        protected boolean runSafe() {
            logger.info("Processing claim instruction for segment [{}] in processor [{}]", segmentId, getName());
            if (availableThreads.get() == 0) {
                logger.info("Cannot claim segment [{}] in processor [{}] due to not enough threads being available", segmentId, getName());
                return false;
            }
            segmentReleaseDeadlines.remove(segmentId);
            tokenStore.fetchToken(getName(), segmentId);
            return true;
        }
    }

    private class SplitSegmentInstruction extends Instruction {

        private final int segmentId;

        public SplitSegmentInstruction(CompletableFuture<Boolean> result, int segmentId) {
            super(result);
            this.segmentId = segmentId;
        }

        @Override
        protected boolean runSafe() {
            logger.info("Processing split instruction for segment [{}] in processor [{}]", segmentId, getName());
            TrackerStatus status = activeSegments.get(segmentId);
            TrackerStatus[] newStatus = status.split();
            int newSegmentId = newStatus[1].getSegment().getSegmentId();
            tokenStore.initializeSegment(newStatus[1].getTrackingToken(), getName(), newSegmentId);
            activeSegments.put(segmentId, newStatus[0]);
            // We don't invoke the trackerStatusChangeListener because the segment's size changes
            //  are not taken into account, which is the sole thing changing when doing a split.
            shouldRunLauncherImmediately.set(true);
            return true;
        }
    }

    private class MergeSegmentInstruction extends Instruction {

        private final int segmentId;
        private final int otherSegment;

        public MergeSegmentInstruction(CompletableFuture<Boolean> result, int ownedSegment, int otherSegment) {
            super(result);
            this.segmentId = ownedSegment;
            this.otherSegment = otherSegment;
        }

        @Override
        protected boolean runSafe() {
            logger.info("Processing merge instruction for segments [{}] and [{}] in processor [{}]",
                        segmentId, otherSegment, getName());
            releaseSegment(otherSegment);
            while (activeSegments.containsKey(otherSegment)) {
                Thread.yield();
            }
            TrackerStatus status = activeSegments.get(segmentId);
            TrackingToken otherToken = tokenStore.fetchToken(getName(), otherSegment);
            Segment segmentToMergeWith = Segment.computeSegment(otherSegment, tokenStore.fetchSegments(getName()));
            if (!status.getSegment().isMergeableWith(segmentToMergeWith)) {
                tokenStore.releaseClaim(getName(), otherSegment);
                return false;
            }
            Segment newSegment = status.getSegment().mergedWith(segmentToMergeWith);
            //we want to keep the token with the segmentId obtained by the merge operation, and to delete the other
            int tokenToDelete = (newSegment.getSegmentId() == segmentId) ? otherSegment : segmentId;
            tokenStore.deleteToken(getName(), tokenToDelete);

            TrackingToken mergedToken = otherSegment < segmentId
                                        ? new MergedTrackingToken(otherToken, status.getInternalTrackingToken())
                                        : new MergedTrackingToken(status.getInternalTrackingToken(), otherToken);

            tokenStore.storeToken(mergedToken, getName(), newSegment.getSegmentId());
            shouldRunLauncherImmediately.set(true);
            return true;
        }
    }

    /**
     * Wrapper around {@link Runnable} to introduce Tracking Processor specific management methods.
     */
    private interface Worker extends Runnable {

        /**
         * The name of this worker instance.
         *
         * @return name of this worker instance
         */
        String name();

        /**
         * Clean-up this worker instance.
         */
        void cleanUp();
    }
}
