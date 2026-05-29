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
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.MergedTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;


/**
 * A {@link CoordinatorTask} implementation dedicated to merging two {@link Segment}s.
 * <p>
 * If the {@link Coordinator} owning this instruction is currently in charge of the {@code segmentId} and the segment to
 * merge it with, both {@link WorkPackage}s will be aborted, after which the merge will start. When this coordinator is
 * not in charge of one of the two segments, it will try to claim either segment's {@link TrackingToken} and perform the
 * merge then.
 * <p>
 * In either approach, this operation will delete one of the segments and release the claim on the other so that another
 * thread can proceed with processing it.
 *
 * @author Steven van Beelen
 * @see Coordinator
 * @since 4.5
 */
class MergeTask extends CoordinatorTask {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final String name;
    private final int segmentId;
    private final Map<Integer, WorkPackage> workPackages;
    private final TokenStore tokenStore;
    private final UnitOfWorkFactory unitOfWorkFactory;
    private final Map<Integer, Instant> releasesDeadlines;
    @Deprecated(forRemoval = true, since = "5.2.0")
    private final Clock clock;

    /**
     * Constructs a {@code MergeTask}.
     *
     * @param result            The {@link CompletableFuture} to {@link #complete(Boolean, Throwable)} once
     *                          {@link #run()} has finalized.
     * @param name              The name of the {@link Coordinator} this instruction will run in. Used to correctly deal
     *                          with the {@code tokenStore}.
     * @param segmentId         The identifier of the {@link Segment} this instruction should merge.
     * @param workPackages      The collection of {@link WorkPackage}s controlled by the {@link Coordinator}. Will be
     *                          queried for the presence of the given {@code segmentId} and the segment to merge it
     *                          with.
     * @param releasesDeadlines  the map of segments that are blocked from claiming until a specified time
     * @param tokenStore        The storage solution for {@link TrackingToken}s. Used to claim the {@code segmentId} if
     *                          it is not present in the {@code workPackages}, to remove one of the segments and merge
     *                          the merged token.
     * @param unitOfWorkFactory The {@link UnitOfWorkFactory} that spawns {@link UnitOfWork UnitOfWorks} used to invoke
     *                          all {@link TokenStore} operations inside a unit of work.
     * @param clock             the clock used for time-based operations, deprecated in favor of {@link ClockUtils#get()}
     */
    MergeTask(CompletableFuture<Boolean> result,
              String name,
              int segmentId,
              Map<Integer, WorkPackage> workPackages,
              Map<Integer, Instant> releasesDeadlines,
              TokenStore tokenStore,
              UnitOfWorkFactory unitOfWorkFactory,
              @Deprecated(forRemoval = true, since = "5.2.0")
              Clock clock) {
        super(result, name);
        this.name = name;
        this.segmentId = segmentId;
        this.workPackages = workPackages;
        this.releasesDeadlines = releasesDeadlines;
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.tokenStore = tokenStore;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Performs a {@link Segment} merge. Will succeed if either the given {@code workPackages} contain the
     * {@link WorkPackage}s corresponding to the given {@code segmentId} and the identifier to merge with. Or, if the
     * {@link TrackingToken}(s) for the segments can be claimed.
     */
    @Override
    protected CompletableFuture<Boolean> task() {
        logger.debug("Processor [{}] will perform merge instruction for segment {}.", name, segmentId);

        return unitOfWorkFactory.create().executeWithResult(
                context -> tokenStore.fetchSegment(name, segmentId, context)
        ).thenCompose(thisSegment -> {
            if (segmentId == thisSegment.mergeableSegmentId()) {
                logger.debug("Processor [{}] cannot merge segment {}. "
                                     + "A merge request can only be fulfilled if there is more than one segment.",
                             name, segmentId);
                return CompletableFuture.completedFuture(false);
            }

            return unitOfWorkFactory.create().executeWithResult(
                    context -> tokenStore.fetchSegment(name, thisSegment.mergeableSegmentId(), context)
            ).thenCompose(thatSegment -> {
                CompletableFuture<TrackingToken> thisTokenFuture = tokenFor(thisSegment.getSegmentId());
                CompletableFuture<TrackingToken> thatTokenFuture = tokenFor(thatSegment.getSegmentId());
                return thisTokenFuture
                        .thenCombine(thatTokenFuture,
                                     (thisToken, thatToken) -> mergeSegments(thisSegment, thisToken, thatSegment, thatToken))
                        .thenCompose(Function.identity());
            });
        });
    }

    private CompletableFuture<TrackingToken> tokenFor(int segmentId) {
        // Block the segment from being claimed by the Coordinator while we perform the merge.
        // This prevents a race condition where the Coordinator might claim a segment that's being merged.
        releasesDeadlines.put(segmentId,
                              clock.instant().plusSeconds(60)); // Block for 1 minute (will be cleared after merge)

        // Remove WorkPackage so that the CoordinatorTask cannot find it to release its claim upon impending abortion.
        return workPackages.containsKey(segmentId)
                ? workPackages.remove(segmentId)
                              .abort(null)
                              .thenCompose(e -> fetchTokenInUnitOfWork(segmentId))
                : fetchTokenInUnitOfWork(segmentId);
    }

    private CompletableFuture<TrackingToken> fetchTokenInUnitOfWork(int segmentId) {
        return unitOfWorkFactory.create().executeWithResult(
                context -> tokenStore.fetchToken(name, segmentId, context)
        );
    }

    private CompletableFuture<Boolean> mergeSegments(Segment thisSegment, TrackingToken thisToken,
                                                     Segment thatSegment, TrackingToken thatToken) {
        return unitOfWorkFactory.create().executeWithResult(context -> {
            Segment mergedSegment = thisSegment.mergedWith(thatSegment);
            TrackingToken mergedToken = thatSegment.getSegmentId() < thisSegment.getSegmentId()
                    ? MergedTrackingToken.merged(thatToken, thisToken)
                    : MergedTrackingToken.merged(thisToken, thatToken);
            return tokenStore.deleteToken(name, thisSegment.getSegmentId(), context)
                             .thenCompose(result -> tokenStore.deleteToken(name, thatSegment.getSegmentId(), context))
                             .thenCompose(result -> tokenStore.initializeSegment(mergedToken, name, mergedSegment, context))
                             .thenApply(unused -> {
                                 logger.info("Processor [{}] successfully merged {} with {} into {}.",
                                             name, thisSegment, thatSegment, mergedSegment);
                                 return true;
                             });
        }).whenComplete((result, throwable) -> {
            releasesDeadlines.remove(thisSegment.getSegmentId());
            releasesDeadlines.remove(thatSegment.getSegmentId());
        });
    }

    @Override
    String getDescription() {
        return "Merge Task for segment " + segmentId;
    }
}