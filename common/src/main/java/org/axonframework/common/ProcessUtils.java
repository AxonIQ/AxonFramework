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

package org.axonframework.common;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Processing utilities.
 *
 * @author Marc Gathier
 * @since 4.2
 */
public final class ProcessUtils {

    private ProcessUtils() {
    }

    /**
     * Executes an action, with potential retry in case of an exception.
     *
     * @param runnable       action to execute
     * @param retryPredicate predicate to determine if the action should be retried based on the exception
     * @param timeout        timeout for the retries
     * @param timeUnit       unit for the timeout
     * @param retryInterval  time to wait between retries of the action
     */
    public static void executeWithRetry(Runnable runnable, Predicate<RuntimeException> retryPredicate,
                                        long timeout, TimeUnit timeUnit,
                                        long retryInterval) {
        long completeBefore = System.currentTimeMillis() + timeUnit.toMillis(timeout);
        RuntimeException lastException = new RuntimeException();
        while (completeBefore > System.currentTimeMillis()) {
            try {
                runnable.run();
                return;
            } catch (RuntimeException re) {
                if (!retryPredicate.test(re)) {
                    throw re;
                }

                try {
                    Thread.sleep(retryInterval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw re;
                }
                lastException = re;
            }
        }

        throw lastException;
    }

    /**
     * Executes an action asynchronously, with potential retry in case the result is false. Exception handling should
     * be taken care of within the action if needed.
     *
     * @param action        action to execute, will be executed until the result is {@code true} or max tries are
     *                      reached
     * @param retryInterval time in milliseconds to wait between retries of the action
     * @param maxTries      maximum number of times the action is invoked
     * @param executor      executor used to schedule the delay between retries
     * @return a {@link CompletableFuture} that completes when the action returns {@code true}, or exceptionally with a
     *         {@link ProcessRetriesExhaustedException} if max tries is reached
     */
    public static CompletableFuture<Void> executeUntilTrue(Supplier<CompletableFuture<Boolean>> action,
                                                                 long retryInterval, long maxTries, Executor executor) {
        return executeUntilTrue(action, retryInterval, maxTries, maxTries, executor);
    }

    private static CompletableFuture<Void> executeUntilTrue(Supplier<CompletableFuture<Boolean>> action,
                                                                  long retryInterval, long originalMaxTries,
                                                                  long attemptsLeft, Executor executor) {
        if (attemptsLeft <= 0) {
            return CompletableFuture.failedFuture(new ProcessRetriesExhaustedException(String.format(
                    "Tried invoking the action for %d times, without the result being true", originalMaxTries
            )));
        }
        return action.get().thenCompose(result -> {
            if (Boolean.TRUE.equals(result)) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture
                    .runAsync(() -> {}, CompletableFuture.delayedExecutor(retryInterval, TimeUnit.MILLISECONDS,
                                                                          executor))
                    .thenCompose(ignored -> executeUntilTrue(action, retryInterval, originalMaxTries,
                                                                  attemptsLeft - 1, executor));
        });
    }

    /**
     * Executes an action, with potential retry in case the result is false. Exception handling should be taken care of
     * within the action if needed.
     *
     * @param runnable      action to execute, will be executed till the result is true, or max tries are reached
     * @param retryInterval time to wait between retries of the action
     * @param maxTries      maximum number of times the action is invoked
     */
    public static void executeUntilTrue(BooleanSupplier runnable, long retryInterval, long maxTries) {
        AtomicLong totalTriesCounter = new AtomicLong();
        boolean result = runnable.getAsBoolean();
        while (!result) {
            if (totalTriesCounter.incrementAndGet() >= maxTries){
                throw new ProcessRetriesExhaustedException(String.format(
                        "Tried invoking the action for %d times, without the result being true",
                        maxTries));
            }
            try {
                Thread.sleep(retryInterval);
                result = runnable.getAsBoolean();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
