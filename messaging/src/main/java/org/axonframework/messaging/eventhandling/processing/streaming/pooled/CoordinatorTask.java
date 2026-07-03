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

import org.axonframework.common.FutureUtils;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.CompletableFuture;

/**
 * A task to be consumed by a {@link Coordinator} during its main coordination task.
 *
 * @author Steven van Beelen
 * @see Coordinator
 * @since 4.5
 */
abstract class CoordinatorTask {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final CompletableFuture<Boolean> result;
    private final String name;

    /**
     * Construct a {@link CoordinatorTask}. The given {@code result} will be completed exceptionally or successfully
     * through the {@link #complete(Boolean, Throwable)} method.
     *
     * @param result the {@link CompletableFuture} to complete exceptionally or successfully
     * @param name   the name of the {@link Coordinator} this instruction will be ran in
     */
    protected CoordinatorTask(CompletableFuture<Boolean> result, String name) {
        this.result = result;
        this.name = name;
    }

    /**
     * Runs the {@link #task()} of this {@link CoordinatorTask}. Ensures the outcome is correctly reflected through
     * invoking the {@link #complete(Boolean, Throwable)}.
     *
     * @return the {@link CompletableFuture} provided in the constructor, to attach tasks to be performed after
     * completion
     */
    CompletableFuture<Boolean> run() {
        try {
            task().whenComplete(this::complete);
        } catch (Exception e) {
            complete(null, e);
        }
        return result;
    }

    /**
     * The task this {@link CoordinatorTask} should perform.
     *
     * @return a {@link CompletableFuture} of {@link Boolean}, marking success or failure of the instruction with {@code
     * true} and {@code false} respectively
     */
    protected abstract CompletableFuture<Boolean> task();

    /**
     * Completes this {@link CoordinatorTask}'s {@link CompletableFuture}. If the given {@code throwable} is not null,
     * {@link CompletableFuture#completeExceptionally(Throwable)} will be invoked on the {@code result}. Otherwise
     * {@link CompletableFuture#complete(Object)} will be invoked with the given {@code outcome}.
     * <p>
     * This method should be invoked as a follow up of a {@link #run()}.
     *
     * @param outcome   a {@link Boolean} defining whether the {@link #run()} ran successfully yes or no
     * @param throwable a {@link Throwable} defining the error scenario the {@link #run()} ran into
     */
    void complete(Boolean outcome, Throwable throwable) {
        if (throwable != null) {
            logger.warn("Processor [{}] failed to run instruction - {}.", name, getDescription(), throwable);
            result.completeExceptionally(throwable);
        } else {
            result.complete(outcome);
        }
    }

    /**
     * Persists the final progress of the given {@code workPackage} within {@code context} before the task reads its
     * {@link org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken}, so the segment(s) the
     * task produces start from the freshly-reconciled token. A {@code null} {@code workPackage} (the segment was claimed
     * from the token store rather than processed locally, so there is no local progress to release) is a no-op.
     *
     * @param workPackage the locally-aborted work package whose final progress to persist, or {@code null} if none
     * @param context     the processing context whose transaction the final store participates in
     * @return a {@link CompletableFuture} completing when the final persistence (if any) has been applied
     */
    protected static CompletableFuture<Void> releaseProgressOf(@Nullable WorkPackage workPackage,
                                                               ProcessingContext context) {
        return workPackage == null ? FutureUtils.emptyCompletedFuture() : workPackage.onSegmentReleased(context);
    }

    /**
     * Shortly describes this {@link CoordinatorTask}.
     *
     * @return a short description of this {@link CoordinatorTask}
     */
    abstract String getDescription();
}
