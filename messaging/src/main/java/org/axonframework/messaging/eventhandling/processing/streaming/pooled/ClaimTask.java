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
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;


/**
 * A {@link CoordinatorTask} implementation dedicated to claiming a token so that the {@link Coordinator} can start a
 * new {@link WorkPackage} in its next cycle.
 * <p>
 * It achieves this by removing the segment from the {@code releasesDeadlines} map which prevent the segment from being
 * claimed if a previous release operation was done, and claiming the token in the store. Upon the next iteration of the
 * {@link Coordinator}, it will see the segment is claimed and will start a new {@link WorkPackage} for it.
 *
 * @author Mitchell Herrijgers
 * @see Coordinator
 * @since 4.9.0
 */
class ClaimTask extends CoordinatorTask {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final String name;
    private final int segmentId;
    private final Map<Integer, WorkPackage> workPackages;
    private final Map<Integer, Instant> releasesDeadlines;
    private final TokenStore tokenStore;
    private final UnitOfWorkFactory unitOfWorkFactory;

    /**
     * Constructs a {@code ClaimTask}.
     *
     * @param result            The {@link CompletableFuture} to {@link #complete(Boolean, Throwable)} once
     *                          {@link #run()} has finalized.
     * @param name              The name of the {@link Coordinator} this instruction will be run in. Used to correctly
     *                          deal with the {@code tokenStore}.
     * @param segmentId         The identifier of the {@link Segment} this instruction should claim.
     * @param workPackages      The collection of {@link WorkPackage}s controlled by the {@link Coordinator}. Will be
     *                          queried for the presence of the given {@code segmentId} and the segment to merge it
     *                          with.
     * @param releasesDeadlines The map of release deadlines for each segment.
     * @param tokenStore        The storage solution for {@link TrackingToken}s. Used to claim the {@code segmentId} if
     *                          it is not present in the {@code workPackages}, to remove one of the segments and merge
     *                          the merged token.
     * @param unitOfWorkFactory The {@link UnitOfWorkFactory} that spawns {@link UnitOfWork UnitOfWorks} used to invoke
     *                          all {@link TokenStore} operations inside a unit of work.
     */
    ClaimTask(CompletableFuture<Boolean> result,
              String name,
              int segmentId,
              Map<Integer, WorkPackage> workPackages,
              Map<Integer, Instant> releasesDeadlines,
              TokenStore tokenStore,
              UnitOfWorkFactory unitOfWorkFactory) {
        super(result, name);
        this.name = name;
        this.segmentId = segmentId;
        this.workPackages = workPackages;
        this.releasesDeadlines = releasesDeadlines;
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.tokenStore = tokenStore;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Performs a {@link Segment} claim. Will succeed if the {@link TrackingToken} respective for that segment is
     * claimable and has been successfully claimed.
     */
    @Override
    protected CompletableFuture<Boolean> task() {
        logger.debug("Processor [{}] will perform claim instruction for segment {}.", name, segmentId);

        if (workPackages.containsKey(segmentId)) {
            return CompletableFuture.completedFuture(true);
        }
        releasesDeadlines.remove(segmentId);
        return unitOfWorkFactory.create().executeWithResult(
                context -> tokenStore.fetchAvailableSegments(name, context)
        ).thenCompose(segments -> {
            Optional<Segment> segmentToClaim = segments.stream()
                                                       .filter(segment -> segment.getSegmentId() == segmentId)
                                                       .findFirst();
            if (segmentToClaim.isEmpty()) {
                logger.info("Processor [{}] cannot claim segment {}. It is not available.", name, segmentId);
                return CompletableFuture.completedFuture(false);
            }

            return unitOfWorkFactory.create().executeWithResult(
                    context -> tokenStore.fetchToken(name, segmentId, context)
            ).thenApply(token -> true)
             .exceptionally(e -> {
                 logger.warn("Processor [{}] cannot claim segment {} due to an error.", name, segmentId, e);
                 return false;
             });
        });
    }

    @Override
    String getDescription() {
        return "Claim Task for segment " + segmentId;
    }
}