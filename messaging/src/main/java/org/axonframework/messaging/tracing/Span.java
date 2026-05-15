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

package org.axonframework.messaging.tracing;

import org.axonframework.common.FutureUtils;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Represents a part of the application logic that will be traced. One or multiple spans together form a trace and are
 * often used to debug and monitor (distributed) applications.
 * <p>
 * The {@link Span} is an abstraction for Axon Framework to have tracing capabilities without knowing the specific
 * tracing provider. Calling {@link #start()} will start the {@code span} and make it active to the current thread. For
 * every start invocation, a respective {@link #end()} should be called as well to prevent scope leaks.
 * <p>
 * Creating {@link Span spans} is the responsibility of the {@link SpanFactory} which should be implemented by the
 * tracing provider of choice.
 * <p>
 * Important! In order to make this span the parent for any new span created during its execution,
 * {@link #makeCurrent()} should be called. This method will return a {@link SpanScope}, on which
 * {@link SpanScope#close()} should be invoked during the same code execution on the same thread. If not, this span will
 * become the unwanted parent of any children. You can make the same span the current for multiple threads at any point
 * in time, as long as you close them before calling {@link Span#end()}
 * <p>
 * Each {@link #start()} should eventually result in an {@link #end()} being called, but this does not have to be done
 * on the same thread.
 *
 * @author Mitchell Herrijgers
 * @see SpanFactory For more information about creating different kinds of traces.
 * @since 4.6.0
 */
public interface Span {

    /**
     * Starts the Span. However, does not set this span as the span of the current thread. See {@link #makeCurrent()} in
     * order to do so.
     *
     * @return The span for fluent interfacing.
     */
    Span start();

    /**
     * Sets the Span as the current for the current thread. The returned {@link SpanScope} must be closed before ending
     * the Span, on the same thread, or through a try-with-resources statement in the same thread as this method was
     * called.
     * <p>
     * You can make a span current on as many threads as you like, but you have to close every {@link SpanScope}, or
     * context will leak into the current thread. Note that if this is neglected, the {@link #end()} method should warn
     * the user in order to report this back to the framework.
     *
     * @return The scope of the span that must be closed be
     */
    default SpanScope makeCurrent() {
        return () -> {
        };
    }

    /**
     * Ends the span. All scopes should have been closed at this point. In addition, a span can only be ended once.
     * <p>
     * If scopes are still open when this method is called, either an exception should be thrown or an error log should
     * be produced to warn the user of the leak. This information can then be reported back to the developers of the
     * framework for a fix.
     */
    void end();

    /**
     * Records an exception to the span. This will be reported to the APM tooling, which can show more information about
     * the error in the trace. This method does not end the span.
     *
     * @param t The exception to record
     * @return The span for fluent interfacing.
     */
    Span recordException(Throwable t);

    /**
     * Runs a piece of code which will be traced. Exceptions will be caught automatically and added to the span, then
     * rethrown. The span will be started before the execution, and ended after execution. Note that the
     * {@link Runnable} will be invoked instantly and synchronously.
     *
     * @param runnable The {@link Runnable} to execute.
     */
    default void run(Runnable runnable) {
        this.start();
        try (SpanScope unused = this.makeCurrent()) {
            runnable.run();
        } catch (Exception e) {
            this.recordException(e);
            throw e;
        } finally {
            this.end();
        }
    }

    /**
     * Wraps a {@link Runnable}, propagating the current span context to the actual thread that runs the
     * {@link Runnable}. If you don't wrap a runnable before passing it to an {@link java.util.concurrent.Executor} the
     * context will be lost and a new trace will be started.
     *
     * @param runnable The {@link Runnable} to wrap
     * @return A wrapped runnable which propagates the span's context across threads.
     */
    default Runnable wrapRunnable(Runnable runnable) {
        return () -> run(runnable);
    }

    /**
     * Runs a piece of code which will be traced. Exceptions will be caught automatically and added to the span, then
     * rethrown. The span will be started before the execution, and ended after execution. Note that the
     * {@link Callable} will be invoked instantly and synchronously.
     *
     * @param <T> the callable result type
     * @param callable The {@link Callable} to execute.
     * @return the result returned by the {@link Callable}
     * @throws Exception when the {@link Callable} throws an exception
     */
    default <T> T runCallable(Callable<T> callable) throws Exception {
        this.start();
        try (SpanScope unused = this.makeCurrent()) {
            return callable.call();
        } catch (Exception e) {
            this.recordException(e);
            throw e;
        } finally {
            this.end();
        }
    }

    /**
     * Wraps a {@link Callable}, propagating the current span context to the actual thread that runs the
     * {@link Callable}. If you don't wrap a callable before passing it to an {@link java.util.concurrent.Executor} the
     * context will be lost and a new trace will be started.
     *
     * @param callable The {@link Callable} to wrap
     * @return A wrapped callable which propagates the span's context across threads.
     */
    default <T> Callable<T> wrapCallable(Callable<T> callable) {
        return () -> runCallable(callable);
    }

    /**
     * Runs a piece of code that returns a value and which will be traced. Exceptions will be caught automatically and
     * added to the span, then rethrown. The span will be started before the execution, and ended after execution. Note
     * that the {@link Supplier} will be invoked instantly and synchronously.
     *
     * @param <T> the supplier result type
     * @param supplier The {@link Supplier} to execute.
     * @return the result returned by the supplier
     */
    default <T> T runSupplier(Supplier<T> supplier) {
        this.start();
        try (SpanScope unused = this.makeCurrent()) {
            return supplier.get();
        } catch (Exception e) {
            this.recordException(e);
            throw e;
        } finally {
            this.end();
        }
    }

    default <T> CompletableFuture<T> runSupplierAsync(Supplier<CompletableFuture<T>> supplier) {
        this.start();
        CompletableFuture<T> future = new CompletableFuture<>();
        try (SpanScope ignored = this.makeCurrent()) {
            supplier.get().whenComplete(FutureUtils.alsoComplete(future));
        } catch (Exception e) {
            future.completeExceptionally(e);
        } finally {
            future = future.whenComplete((r, e) -> {
                if (e != null) {
                    this.recordException(e);
                }
                this.end();
            });
        }
        return future;
    }

    /**
     * Wraps a {@link Supplier}, tracing the invocation. Exceptions will be caught automatically and added to the span,
     * then rethrown. The span will be started before the execution, and ended after execution.
     *
     * @param supplier The {@link Supplier} to wrap
     * @return A wrapped Supplier
     */
    default <T> Supplier<T> wrapSupplier(Supplier<T> supplier) {
        return () -> runSupplier(supplier);
    }

    /**
     * Runs a piece of code that returns a value and which will be traced. Exceptions will be caught automatically and
     * added to the span, then rethrown. The span will be started before the execution, and ended after execution. Note
     * that the {@link Consumer} will be invoked instantly and synchronously.
     *
     * @param <T> the type consumed by the consumer
     * @param supplier The {@link Consumer} to execute.
     * @param consumedObject The object to consume.
     */
    default <T> void runConsumer(Consumer<T> supplier, T consumedObject) {
        this.start();
        try (SpanScope unused = this.makeCurrent()) {
            supplier.accept(consumedObject);
        } catch (Exception e) {
            this.recordException(e);
            throw e;
        } finally {
            this.end();
        }
    }

    /**
     * Wraps a {@link Consumer}, tracing the invocation. Exceptions will be caught automatically and added to the span,
     * then rethrown. The span will be started before the execution, and ended after execution.
     *
     * @param supplier The {@link Consumer} to wrap
     * @return A wrapped Consumer
     */
    default <T> Consumer<T> wrapConsumer(Consumer<T> supplier) {
        return (consumedObject) -> runConsumer(supplier, consumedObject);
    }

    /**
     * Adds an attribute to the span. This can be used to add extra information to the span, which can be used by the
     * APM tooling to provide more information about the span.
     *
     * @param key   The key of the attribute.
     * @param value The value of the attribute.
     * @return The span for fluent interfacing.
     */
    default Span addAttribute(String key, @Nullable String value) {
        return this;
    }
}
