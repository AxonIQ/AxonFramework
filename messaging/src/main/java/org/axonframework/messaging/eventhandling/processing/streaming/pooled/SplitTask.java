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
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.TrackerStatus;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.axonframework.common.FutureUtils.joinAndUnwrap;

/**
 * A {@link CoordinatorTask} implementation dedicated to splitting a {@link Segment}.
 * <p>
 * If the {@link Coordinator} owning this instruction is currently in charge of the specified segment, the
 * {@link WorkPackage} will be aborted and subsequently its segment split. When the {@code Coordinator} is not in charge
 * of the specified {@code segmentId}, it will try to claim the segment's {@link TrackingToken} and then split it.
 * <p>
 * In either way, the segment's claim (if present) will be released, so that another thread can proceed with processing
 * it.
 *
 * @author Steven van Beelen
 * @see Coordinator
 * @since 4.5
 */
class SplitTask extends CoordinatorTask {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final String name;
    private final int segmentId;
    private final Map<Integer, WorkPackage> workPackages;
    private final Map<Integer, java.time.Instant> releasesDeadlines;
    private final TokenStore tokenStore;
    private final UnitOfWorkFactory unitOfWorkFactory;
    private final java.time.Clock clock;

    /**
     * Constructs a {@code SplitTask}.
     *
     * @param result            The {@link CompletableFuture} to {@link #complete(Boolean, Throwable)} once
     *                          {@link #run()} has finalized.
     * @param name              The name of the {@link Coordinator} this instruction will be run in. Used to correctly
     *                          deal with the {@code tokenStore}.
     * @param segmentId         The identifier of the {@link Segment} this instruction should split
     * @param workPackages      The collection of {@link WorkPackage}s controlled by the {@link Coordinator}. Will be
     *                          queried for the presence of the given {@code segmentId}.
     * @param releasesDeadlines the map of segments that are blocked from claiming until a specified time
     * @param tokenStore        The storage solution for {@link TrackingToken}s. Used to claim the {@code segmentId} if
     *                          it is not present in the {@code workPackages} and to store the split segment.
     * @param unitOfWorkFactory The {@link UnitOfWorkFactory} that spawns {@link UnitOfWork UnitOfWorks} used to invoke
     *                          all {@link TokenStore} operations inside a unit of work.
     * @param clock             the clock used for time-based operations
     */
    SplitTask(CompletableFuture<Boolean> result,
              String name,
              int segmentId,
              Map<Integer, WorkPackage> workPackages,
              Map<Integer, java.time.Instant> releasesDeadlines,
              TokenStore tokenStore,
              UnitOfWorkFactory unitOfWorkFactory,
              java.time.Clock clock) {

        super(result, name);
        this.name = name;
        this.segmentId = segmentId;
        this.workPackages = workPackages;
        this.releasesDeadlines = releasesDeadlines;
        this.tokenStore = tokenStore;
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Performs a {@link Segment} split. Will succeed if either the given {@code workPackages} contain a
     * {@link WorkPackage} corresponding to the given {@code segmentId} or if the {@link TrackingToken} for the
     * {@code segmentId} can be claimed.
     */
    @Override
    protected CompletableFuture<Boolean> task() {
        // Block the segment from being claimed by the Coordinator while we perform the split.
        // This prevents a race condition where the Coordinator might claim a segment that's being split.
        releasesDeadlines.put(segmentId,
                              clock.instant().plusSeconds(60)); // Block for 1 minute (will be cleared after split)

        logger.debug("Processor [{}] will perform split instruction for segment {}.", name, segmentId);
        // Remove WorkPackage so that the CoordinatorTask cannot find it to release its claim upon impending abortion.
        WorkPackage workPackage = workPackages.remove(segmentId);
        return workPackage != null ? abortAndSplit(workPackage) : fetchSegmentAndSplit(segmentId);
    }

    private CompletableFuture<Boolean> abortAndSplit(WorkPackage workPackage) {
        return workPackage.abort(null)
                          .thenApply(e -> splitAndRelease(workPackage.segment()));
    }

    private CompletableFuture<Boolean> fetchSegmentAndSplit(int segmentId) {
        return unitOfWorkFactory.create().executeWithResult(
                context -> tokenStore.fetchSegment(name, segmentId, context)
                                     .thenApply(this::splitAndRelease)
        );
    }

    private boolean splitAndRelease(Segment segmentToSplit) {
        try {
            joinAndUnwrap(unitOfWorkFactory.create().executeWithResult(
                    context -> tokenStore.fetchToken(name, segmentToSplit.getSegmentId(), context)
                                         .thenApply(tokenToSplit -> TrackerStatus.split(segmentToSplit, tokenToSplit))
                                         .thenCompose(splitStatuses -> splitAndRelease(
                                                 splitStatuses, segmentToSplit, context
                                         ))
            ));
        } finally {
            // Remove the segment from the releases deadlines to allow the Coordinator to claim the split segments
            releasesDeadlines.remove(segmentToSplit.getSegmentId());
        }
        return true;
    }

    private CompletableFuture<Void> splitAndRelease(TrackerStatus[] splitStatuses,
                                                    Segment segmentToSplit,
                                                    ProcessingContext context) {
        return tokenStore.initializeSegment(
                                 splitStatuses[1].getTrackingToken(),
                                 name,
                                 splitStatuses[1].getSegment(),
                                 context
                         )
                         .thenCompose(result -> tokenStore.deleteToken(
                                 name,
                                 splitStatuses[0].getSegment().getSegmentId(),
                                 context
                         ))
                         .thenCompose(result -> tokenStore.initializeSegment(
                                 splitStatuses[0].getTrackingToken(),
                                 name,
                                 splitStatuses[0].getSegment(),
                                 context
                         ))
                         .thenRun(() -> logger.info(
                                 "Processor [{}] successfully split {} into {} and {}.",
                                 name, segmentToSplit, splitStatuses[0].getSegment(), splitStatuses[1].getSegment()
                         ));
    }

    @Override
    String getDescription() {
        return "Split Task for segment " + segmentId;
    }
}