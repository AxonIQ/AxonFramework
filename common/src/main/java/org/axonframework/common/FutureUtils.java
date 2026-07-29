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

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Utility class containing reusable functionality for interacting with the {@link CompletableFuture}.
 *
 * @author Allard Buijze
 * @since 5.0.0
 */
public final class FutureUtils {

    /**
     * Default safety-net timeout for {@link #joinAndUnwrap(CompletableFuture)}. Thirty seconds is long enough that no
     * legitimate synchronous operation (in-memory, local DB, or UoW execution) should ever hit it, yet short enough to
     * surface truly hung threads (connection pool exhaustion, deadlocks, network partitions) before they cascade into
     * larger outages. Callers that expect longer waits should use {@link #joinAndUnwrap(CompletableFuture, Duration)}
     * with an explicit timeout.
     */
    private static final Duration DEFAULT_JOIN_TIMEOUT = Duration.ofSeconds(30);

    private FutureUtils() {
        // Utility class
    }

    /**
     * Utility method that doesn't do anything. Its purpose is to simplify conversion of a {@link CompletableFuture}
     * with any generic type to a {@code CompletableFuture<Void>}.
     * <p>
     * Example: {@code completableFuture.thenApply(FutureUtils::ignoreResult}
     *
     * @param toIgnore The actual result of the {@link CompletableFuture}.
     * @param <T>      The declared result of the {@link CompletableFuture} to ignore the result of.
     * @return {@code null}, as that's the only valid value for {@link Void}.
     */
    @SuppressWarnings("UnusedReturnValue")
    public static <T> Void ignoreResult(@SuppressWarnings("unused") T toIgnore) {
        return null;
    }

    /**
     * Creates a completed {@link CompletableFuture} with {@code null} as result.
     *
     * @param <T> The declared type to return in the {@link CompletableFuture}.
     * @return A {@link CompletableFuture} that is completed with {@code null}.
     */
    public static <T> CompletableFuture<T> emptyCompletedFuture() {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Combines the given {@code futures} into a single {@link CompletableFuture} that completes once all of them
     * complete, returning an already completed future when {@code futures} is empty.
     * <p>
     * This is a drop-in alternative to {@link CompletableFuture#allOf(CompletableFuture[])} for code paths that
     * routinely deal with empty collections, such as fan-out over a dynamic set of handlers, subscribers, or segments.
     * It avoids allocating an array and a dependent future for a combination that has nothing to wait for.
     * <p>
     * The returned future completes exceptionally with a {@link CompletionException} as soon as any of the given
     * {@code futures} completes exceptionally, matching the behavior of
     * {@link CompletableFuture#allOf(CompletableFuture[])}.
     *
     * @param futures the futures to combine
     * @return a future that completes once every future in {@code futures} completes, or a completed future if
     * {@code futures} is empty
     * @since 5.3.0
     */
    public static CompletableFuture<Void> allOrEmpty(List<CompletableFuture<Void>> futures) {
        return futures.isEmpty()
                ? emptyCompletedFuture()
                : CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    /**
     * Creates a function that can be passed to a {@link CompletableFuture#whenComplete(BiConsumer)} or
     * {@link CompletableFuture#whenCompleteAsync(BiConsumer)} invocation, to complete the given {@code future} with the
     * same result as the {@link CompletableFuture} that this function is passed to.
     *
     * @param future The {@link CompletableFuture} to also complete.
     * @param <T>    The declared type of result from the {@link CompletableFuture}.
     * @return A function that completes another {@code future} with the same results.
     */
    public static <T> BiConsumer<T, Throwable> alsoComplete(CompletableFuture<T> future) {
        return (r, e) -> {
            if (e == null) {
                future.complete(r);
            } else {
                future.completeExceptionally(e);
            }
        };
    }

    /**
     * Unwrap given {@code exception} from the exception-wrappers added by {@link CompletableFuture}.
     * <p>
     * More specifically, if the given {@code exception} is a {@link CompletionException} or {@link ExecutionException},
     * it returns the cause. Otherwise, it will return the exception as-is.
     *
     * @param exception The exception to unwrap.
     * @return The unwrapped exception if the given {@code exception} is of type {@link CompletionException} or
     * {@link ExecutionException}. Otherwise, it is returned as is.
     */
    public static Throwable unwrap(Throwable exception) {
        return exception instanceof CompletionException || exception instanceof ExecutionException
                ? exception.getCause()
                : exception;
    }

    /**
     * Safely catches exceptions thrown by the given {@code fn} and returns a {@link CompletableFuture} that completes.
     *
     * @param fn  A lambda returning a {@link CompletableFuture}.
     * @param <T> Type of the completable future.
     * @return A completable future that completes exceptionally if the given lambda throws an exception.
     */
        public static <T> CompletableFuture<T> runFailing(final Supplier<CompletableFuture<T>> fn) {
        try {
            return fn.get();
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Joins a {@link CompletableFuture} and unwraps any {@link CompletionException} to throw the actual cause,
     * preserving the exact exception type without wrapping checked exceptions.
     * <p>
     * Applies a default safety-net timeout of 30 seconds. If the future does not complete within that window, a
     * {@link TimeoutException} is thrown. Callers that expect longer completion times should use
     * {@link #joinAndUnwrap(CompletableFuture, Duration)} with an explicit timeout.
     *
     * @param future The {@link CompletableFuture} to join.
     * @param <T>    The type of the future's result.
     * @return The result of the future.
     * @throws TimeoutException if the future does not complete within the default timeout.
     * @throws Throwable        the unwrapped cause if the future completed exceptionally (exact type preserved).
     */
    @SuppressWarnings("JavadocDeclaration")
    @Nullable
    public static <T> T joinAndUnwrap(CompletableFuture<T> future) {
        return joinAndUnwrap(future, DEFAULT_JOIN_TIMEOUT);
    }

    /**
     * Joins a {@link CompletableFuture} and unwraps any {@link CompletionException} to throw the actual cause,
     * preserving the exact exception type without wrapping checked exceptions. If the future does not complete within
     * the given {@code timeout}, a {@link TimeoutException} is thrown.
     * <p>
     * This method uses {@link CompletableFuture#orTimeout(long, TimeUnit)} to enforce the deadline. On futures that are
     * already complete (the common case), the timeout is a no-op.
     *
     * @param future  The {@link CompletableFuture} to join.
     * @param timeout The maximum time to wait for the future to complete.
     * @param <T>     The type of the future's result.
     * @return The result of the future, or {@code null} if the future completed with a {@code null} value.
     * @throws TimeoutException if the future does not complete within the given {@code timeout}.
     * @throws Throwable        the unwrapped cause if the future completed exceptionally (exact type preserved).
     */
    @SuppressWarnings("JavadocDeclaration")
    public static <T> T joinAndUnwrap(CompletableFuture<T> future, Duration timeout) {
        try {
            return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                         .join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                sneakyThrow(new TimeoutException(
                        "Future did not complete within " + timeout
                ));
            }
            sneakyThrow(e.getCause());
            return null; // unreachable, but needed for compilation
        }
    }

    /**
     * Utility method to throw checked exceptions without declaring them in the method signature. This uses type erasure
     * to bypass Java's checked exception mechanism, allowing the preservation of the exact exception type when
     * rethrowing.
     * <p>
     * This method should be used carefully and only when you're certain about the exception handling contract of your
     * calling code.
     *
     * @param exception The exception to throw.
     * @param <E>       The type of exception (inferred).
     * @throws E The exception with its original type.
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable exception) throws E {
        throw (E) exception;
    }
}
