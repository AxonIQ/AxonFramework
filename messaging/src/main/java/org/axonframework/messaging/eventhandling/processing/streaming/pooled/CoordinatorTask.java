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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * A task to be consumed by a {@link Coordinator} during its main coordination task.
 *
 * @author Steven van Beelen
 * @see Coordinator
 * @since 4.5
 */
abstract class CoordinatorTask {

    /**
     * Ceiling on the re-claim block a {@link SplitTask} or {@link MergeTask} installs for the segment(s) whose
     * {@link org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken} it is about to
     * rewrite.
     * <p>
     * The block stops the {@link Coordinator} running the task from claiming a segment while that segment's token is
     * being deleted and re-initialized. Every task clears its own block once its {@link #task()} completes, whether it
     * succeeded, failed, or threw before its completion handler was attached, so this ceiling is only reached by a task
     * whose future never completes at all. Even then it is not permanent: the {@code Coordinator} drops the entry the
     * first time it evaluates a segment whose deadline has passed, so the block expires without anyone clearing it.
     * <p>
     * The block is scoped to a single {@code Coordinator}: it stops <em>this</em> processor instance from re-claiming
     * the segment, and does not stop another instance from claiming it mid-split or mid-merge.
     */
    static final Duration RECLAIM_BLOCK_TIMEOUT = Duration.ofSeconds(60);

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final CompletableFuture<Boolean> result;
    private final String name;
    private final AtomicReference<@Nullable CompletableFuture<Void>> awaitedRelease = new AtomicReference<>();

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
     * invoking the {@link #complete(Boolean, Throwable)}, unless the task has to
     * {@link #awaitRelease(CompletableFuture) await a release} first. Such a run ends right away, without completing the
     * result, so the {@link Coordinator} can keep coordinating segments while the release runs. The task is to be run
     * again once that release has ended.
     * <p>
     * The returned {@link CompletableFuture} completes once this run has ended: with the release to await before
     * running the task again, or with {@code null} once the task is done, exceptionally when it failed.
     *
     * @return a {@link CompletableFuture} completing once this run of the task has ended, with the release to await
     * before running it again, if any
     */
    CompletableFuture<@Nullable CompletableFuture<Void>> run() {
        return FutureUtils.runFailing(this::task)
                          .handle((outcome, throwable) -> {
                              CompletableFuture<Void> release = awaitedRelease.getAndSet(null);
                              if (release != null && throwable == null) {
                                  logger.debug("Processor [{}] awaits a release before instruction - {}.",
                                               name, getDescription());
                                  return CompletableFuture.completedFuture(release);
                              }
                              complete(outcome, throwable);
                              return throwable == null
                                      ? CompletableFuture.<@Nullable CompletableFuture<Void>>completedFuture(null)
                                      : CompletableFuture.<@Nullable CompletableFuture<Void>>failedFuture(throwable);
                          })
                          .thenCompose(Function.identity());
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
     * Has this task await the given {@code release} before it runs again. A task touching the claim or token of a
     * segment the {@link Coordinator} is still releasing awaits that release, so the remainder of the release does not
     * act on the segment while the task does. The {@code Coordinator} does not wait for the release while running the
     * task, which would keep it from coordinating any other segment for as long as the release takes. Instead, it runs
     * the task again once the release has ended, whether it succeeded or not, and performs no other task until then.
     * <p>
     * Invoke this from {@link #task()} before it changes anything, and return its result from {@link #task()}.
     *
     * @param release the release of a segment this task touches
     * @return the {@link CompletableFuture} for {@link #task()} to return
     */
    protected CompletableFuture<Boolean> awaitRelease(CompletableFuture<Void> release) {
        awaitedRelease.set(release);
        return CompletableFuture.completedFuture(false);
    }

    /**
     * Shortly describes this {@link CoordinatorTask}.
     *
     * @return a short description of this {@link CoordinatorTask}
     */
    abstract String getDescription();
}
