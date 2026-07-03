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

import org.axonframework.common.util.DelegateScheduledExecutorService;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.SimpleEntry;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.progress.SegmentProgressStrategy;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.progress.SegmentProgressStrategyFactory;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import static org.mockito.Mockito.spy;

/**
 * Published, {@link WorkPackage}-aware support base for testing {@link SegmentProgressStrategy} implementations against a
 * real work package. Lives in the {@code pooled} package (so it has access to the package-private {@code WorkPackage})
 * and is published in the messaging test-jar, allowing out-of-module tests (such as an extension's checkpointing
 * strategy) to drive a genuine work package through the public {@link WorkPackageHarness} bridge without needing access
 * to the package-private type itself.
 * <p>
 * A subclass obtains a {@link WorkPackageHarness} via {@link #harness(SegmentProgressStrategyFactory)}, supplying the
 * {@link SegmentProgressStrategyFactory} under test. The harness wires that factory into a work package backed by this
 * support's (spied) {@link #tokenStore()} and a {@link RecordingBatchProcessor}, and exposes the work package's
 * lifecycle operations.
 * <p>
 * This base is deliberately checkpoint-agnostic: it is typed only on the progress-persistence seam, so it serves the
 * default {@link org.axonframework.messaging.eventhandling.processing.streaming.pooled.progress.TokenStoringProgressStrategy}
 * as well as any advanced strategy.
 *
 * @author Allard Buijze
 * @since 5.2.0
 */
public abstract class SegmentProgressStrategyTestSupport {

    /**
     * The processor name used for the work packages and token store entries created by this support.
     */
    protected static final String PROCESSOR_NAME = "test";

    private TokenStore tokenStore;
    private ScheduledExecutorService executorService;
    private Segment segment;
    private TrackingToken initialToken;

    @BeforeEach
    void initProgressSupport() {
        tokenStore = spy(new InMemoryTokenStore());
        executorService = new DelegateScheduledExecutorService(Executors.newScheduledThreadPool(1));
        segment = Segment.ROOT_SEGMENT;
        initialToken = new GlobalSequenceTrackingToken(0L);
        tokenStore.initializeSegment(initialToken, PROCESSOR_NAME, segment, null);
    }

    @AfterEach
    void tearDownProgressSupport() {
        executorService.shutdown();
    }

    /**
     * Returns the spied {@link InMemoryTokenStore} backing the work packages created by this support, for verifying
     * {@link TokenStore#storeToken(TrackingToken, String, int, ProcessingContext) store} interactions.
     *
     * @return the spied token store
     */
    protected TokenStore tokenStore() {
        return tokenStore;
    }

    /**
     * Returns the default (root) {@link Segment} used by {@link #harness(SegmentProgressStrategyFactory)}.
     *
     * @return the default segment
     */
    protected Segment segment() {
        return segment;
    }

    /**
     * Returns the initial {@link TrackingToken} the work packages and token store entries start from.
     *
     * @return the initial token
     */
    protected TrackingToken initialToken() {
        return initialToken;
    }

    /**
     * Initializes the given {@code segment} in the token store at the {@link #initialToken()}, for tests that drive more
     * than one segment.
     *
     * @param segment the segment to initialize
     */
    protected void initializeSegment(Segment segment) {
        tokenStore.initializeSegment(initialToken, PROCESSOR_NAME, segment, null);
    }

    /**
     * Builds a {@link WorkPackageHarness} for the {@link #segment() default segment}, wiring the given
     * {@code progressStrategyFactory} into the work package under test.
     *
     * @param progressStrategyFactory the strategy factory under test
     * @return a harness around a fresh work package for the default segment
     */
    protected WorkPackageHarness harness(SegmentProgressStrategyFactory progressStrategyFactory) {
        return harness(segment, progressStrategyFactory);
    }

    /**
     * Builds a {@link WorkPackageHarness} for the given {@code segment}, wiring the given {@code progressStrategyFactory}
     * into the work package under test.
     *
     * @param segment                 the segment the work package is responsible for
     * @param progressStrategyFactory the strategy factory under test
     * @return a harness around a fresh work package for {@code segment}
     */
    protected WorkPackageHarness harness(Segment segment, SegmentProgressStrategyFactory progressStrategyFactory) {
        return new WorkPackageHarness(segment, progressStrategyFactory);
    }

    /**
     * Creates an event {@link SimpleEntry} carrying a {@link GlobalSequenceTrackingToken} at the given {@code position}.
     *
     * @param position the stream position of the event
     * @return an event entry at {@code position}
     */
    protected static SimpleEntry<EventMessage> eventAt(long position) {
        return new SimpleEntry<>(
                EventTestUtils.asEventMessage("event-" + position),
                TrackingToken.addToContext(Context.empty(), new GlobalSequenceTrackingToken(position))
        );
    }

    /**
     * Public bridge over a package-private {@link WorkPackage}, exposing exactly the lifecycle operations a strategy
     * test needs. Lets out-of-module subclasses drive a real work package without referencing the package-private type.
     */
    public final class WorkPackageHarness {

        private final WorkPackage workPackage;
        private final RecordingBatchProcessor batchProcessor = new RecordingBatchProcessor();

        private WorkPackageHarness(Segment segment, SegmentProgressStrategyFactory progressStrategyFactory) {
            this.workPackage = WorkPackage.builder()
                                          .name(PROCESSOR_NAME)
                                          .tokenStore(tokenStore)
                                          .unitOfWorkFactory(UnitOfWorkTestUtils.SIMPLE_FACTORY)
                                          .executorService(executorService)
                                          .eventFilter((event, context, seg) -> true)
                                          .batchProcessor(batchProcessor)
                                          .segment(segment)
                                          .initialToken(initialToken)
                                          .batchSize(1)
                                          .claimExtensionThreshold(5000)
                                          .progressStrategyFactory(progressStrategyFactory)
                                          .segmentStatusUpdater(op -> {
                                          })
                                          .build();
        }

        /**
         * Returns the {@link Segment} the underlying work package is responsible for.
         *
         * @return the work package's segment
         */
        public Segment segment() {
            return workPackage.segment();
        }

        /**
         * Notifies the work package that its segment was claimed (driving {@link SegmentProgressStrategy#onSegmentClaimed()}).
         */
        public void onSegmentClaimed() {
            workPackage.onSegmentClaimed();
        }

        /**
         * Schedules an event for processing by the work package.
         *
         * @param entry the event entry to schedule
         * @return {@code true} if the event was scheduled, {@code false} if it was ignored
         */
        public boolean scheduleEvent(MessageStream.Entry<? extends EventMessage> entry) {
            return workPackage.scheduleEvent(entry);
        }

        /**
         * Aborts the work package (releasing its segment), driving {@link SegmentProgressStrategy#onAbort()}.
         *
         * @param reason the abort reason, may be {@code null}
         * @return a future completing with the first abort reason once processing has stopped
         */
        public CompletableFuture<Throwable> abort(@Nullable Throwable reason) {
            return workPackage.abort(reason);
        }

        /**
         * Runs the final release persistence ({@link SegmentProgressStrategy#onSegmentReleased(ProcessingContext)})
         * within a unit of work, exactly as the coordinator does while the claim is still held.
         *
         * @return a future completing when the release persistence (if any) has been applied
         */
        public CompletableFuture<Void> release() {
            return UnitOfWorkTestUtils.SIMPLE_FACTORY.create().executeWithResult(workPackage::onSegmentReleased);
        }

        /**
         * Returns the {@link RecordingBatchProcessor} capturing what this work package handled.
         *
         * @return the recording batch processor
         */
        public RecordingBatchProcessor batchProcessor() {
            return batchProcessor;
        }
    }

    /**
     * Recording {@link WorkPackage.BatchProcessor}: captures the handled messages and the {@link Segment} present in the
     * handling context, and lets a test observe each batch's {@link ProcessingContext} (for example, to resolve a
     * resource a strategy contributed).
     */
    public static final class RecordingBatchProcessor implements WorkPackage.BatchProcessor {

        private final List<EventMessage> processed = new CopyOnWriteArrayList<>();
        private final List<Consumer<ProcessingContext>> contextObservers = new CopyOnWriteArrayList<>();
        private volatile @Nullable Segment segmentInContext;

        /**
         * Registers an observer invoked, on the handling thread, with each batch's {@link ProcessingContext}. Extract
         * what is needed synchronously, as the context is only valid for the duration of the callback.
         *
         * @param observer the context observer to register
         */
        public void observeContext(Consumer<ProcessingContext> observer) {
            contextObservers.add(observer);
        }

        /**
         * Returns the messages handled so far.
         *
         * @return the handled messages
         */
        public List<EventMessage> processed() {
            return processed;
        }

        /**
         * Returns the {@link Segment} resolved from the most recent handling context, or {@code null} if none was
         * present.
         *
         * @return the segment seen in the handling context, or {@code null}
         */
        public @Nullable Segment segmentInContext() {
            return segmentInContext;
        }

        @Override
        public MessageStream.Empty<Message> process(List<MessageStream.Entry<? extends EventMessage>> entries,
                                                    ProcessingContext context) {
            Segment.fromContext(context).ifPresent(resolved -> segmentInContext = resolved);
            contextObservers.forEach(observer -> observer.accept(context));
            entries.forEach(entry -> processed.add(entry.message()));
            return MessageStream.empty();
        }
    }
}
