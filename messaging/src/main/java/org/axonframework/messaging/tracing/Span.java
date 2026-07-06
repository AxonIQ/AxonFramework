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

import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Represents one unit of traced work. One or more spans together form a trace, used to monitor and debug
 * (distributed) applications.
 * <p>
 * A {@code Span} is an abstraction that lets Axon Framework offer tracing capabilities without depending on a
 * specific tracing provider. A span is opened by calling {@link #start()} and ended by closing the returned
 * {@link SpanScope}. Every {@link #start()} must be paired with exactly one {@link SpanScope#close()}.
 * <p>
 * <b>No {@code ThreadLocal}.</b> Parent/child relationships are never derived from a thread-bound "current span".
 * Instead, when a span is created within a {@link org.axonframework.messaging.core.unitofwork.ProcessingContext} (the
 * context is passed to the {@link SpanFactory} factory method), starting it records the span as that context's active
 * span; spans subsequently created from the same {@link SpanFactory} with that context become its children, and the
 * previous active span is restored when this span's {@link SpanScope} is closed. Cross-boundary parenting (across
 * threads or processes) instead rides on message metadata via {@link #propagateContext(Message)} on the dispatch side
 * and {@link SpanFactory#createHandlerSpan(String, Message, org.axonframework.messaging.core.unitofwork.ProcessingContext)}
 * on the handling side.
 * <p>
 * For imperative-style code with no {@link org.axonframework.messaging.core.unitofwork.ProcessingContext} available,
 * the convenience helpers {@link #run(Runnable)}, {@link #runSupplier(Supplier)} and
 * {@link #runSupplierAsync(Supplier)} open and close the scope around the given block; such spans perform no active-span
 * tracking. Framework code that has a {@link org.axonframework.messaging.core.unitofwork.ProcessingContext} should bind
 * the span to its lifecycle through {@link ProcessingContextSpanBinding}.
 *
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @see SpanFactory
 * @since 4.6.0
 */
public interface Span {

    /**
     * Starts this span and returns its {@link SpanScope}. When the span was created with a
     * {@link org.axonframework.messaging.core.unitofwork.ProcessingContext}, starting it records this span as that
     * context's active span (without any {@code ThreadLocal}), so spans created next from the same {@link SpanFactory}
     * with that context nest under it; closing the returned scope restores the previously-active span. The returned
     * scope MUST be closed exactly once; closing it ends the span.
     *
     * @return the {@link SpanScope} governing this span; never {@code null}
     */
    SpanScope start();

    /**
     * Starts this span <em>within</em> the given {@link ProcessingContext}'s lifecycle: starts the span, records its
     * {@link SpanScope} as the context's active scope (under {@link SpanScope#RESOURCE_KEY}, never via a
     * {@code ThreadLocal}), records any processing error on the span, and closes the scope when the context completes
     * (on both the success and error paths). This is the unit-of-work-scoped counterpart to {@link #start()} (which is
     * the imperative edge whose scope the caller closes) and replaces the former {@code ProcessingContextSpanBinding}
     * helper. The scope is stored with {@link ProcessingContext#putResource} so spans created next with the same
     * context instance nest under this one; it is last-writer-wins, matching {@code Message.RESOURCE_KEY}.
     *
     * @param context the processing context whose lifecycle the span is bound to
     * @return the started {@link SpanScope}, also recorded on {@code context} under {@link SpanScope#RESOURCE_KEY}
     */
    default SpanScope start(ProcessingContext context) {
        SpanScope scope = start();
        context.putResource(SpanScope.RESOURCE_KEY, scope);
        context.onError((processingContext, phase, error) -> recordException(error));
        context.doFinally(processingContext -> scope.close());
        return scope;
    }

    /**
     * Adds an attribute to the span, providing extra information to the APM tooling. Implementations return
     * {@code this} for fluent chaining.
     *
     * @param key   the attribute key
     * @param value the attribute value
     * @return this span, for fluent interfacing
     */
    Span addAttribute(String key, String value);

    /**
     * Records the given exception against the span and marks the span as errored. This does NOT end the span; the span
     * is ended when its {@link SpanScope} is closed.
     *
     * @param t the exception to record
     * @return this span, for fluent interfacing
     */
    Span recordException(Throwable t);

    /**
     * Returns a copy of the given {@code message} with this span's tracing context injected into its metadata, so a
     * remote or asynchronous handler can continue the same trace by extracting it (see
     * {@link SpanFactory#createHandlerSpan(String, Message, org.axonframework.messaging.core.unitofwork.ProcessingContext)}).
     * This replaces deriving the context to propagate from a thread-bound "current span": the span propagates
     * <em>itself</em>. Implementations that perform no propagation (no-op, logging) return the input unchanged and
     * never throw.
     *
     * @param message the message to enrich with this span's tracing context
     * @param <M>     the message type
     * @return the message carrying this span's propagated tracing context (possibly the same instance)
     */
    <M extends Message> M propagateContext(M message);

    /**
     * Starts the span, runs the given block inside it, and ends the span afterwards. Exceptions are recorded on the
     * span and rethrown. The {@link Runnable} runs synchronously on the calling thread. This imperative-edge helper
     * performs no active-span tracking; nesting is expressed through a {@code ProcessingContext}, not this helper.
     *
     * @param runnable the block to run
     */
    default void run(Runnable runnable) {
        try (SpanScope ignored = start()) {
            try {
                runnable.run();
            } catch (Throwable t) {
                recordException(t);
                throw t;
            }
        }
    }

    /**
     * Starts the span, runs the given supplier inside it, ends the span afterwards, and returns the supplied value.
     * Exceptions are recorded on the span and rethrown. The {@link Supplier} runs synchronously on the calling thread.
     *
     * @param supplier the value-producing block to run
     * @param <T>      the supplied value type
     * @return the value produced by {@code supplier}
     */
    default <T> T runSupplier(Supplier<T> supplier) {
        try (SpanScope ignored = start()) {
            try {
                return supplier.get();
            } catch (Throwable t) {
                recordException(t);
                throw t;
            }
        }
    }

    /**
     * Starts the span and runs the given asynchronous supplier inside it; the span is ended when the returned
     * {@link CompletableFuture} completes (normally or exceptionally). A failure of the future is recorded on the span.
     * A synchronous failure of the supplier itself is recorded, the span ended, and the throwable rethrown.
     *
     * @param supplier the block producing the {@link CompletableFuture} to trace
     * @param <T>      the future's result type
     * @return a future that completes with the same result/exception as the supplied future
     */
    default <T> CompletableFuture<T> runSupplierAsync(Supplier<CompletableFuture<T>> supplier) {
        SpanScope scope = start();
        CompletableFuture<T> future;
        try {
            future = supplier.get();
        } catch (Throwable t) {
            recordException(t);
            scope.close();
            throw t;
        }
        return future.whenComplete((result, error) -> {
            if (error != null) {
                recordException(error);
            }
            scope.close();
        });
    }
}
