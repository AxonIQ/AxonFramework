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
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Represents one unit of traced work. One or more spans together form a trace, used to monitor and debug
 * (distributed) applications.
 * <p>
 * A {@code Span} is an abstraction that lets Axon Framework offer tracing capabilities without depending on a
 * specific tracing provider. A span is opened by calling {@link #start()} and ended by closing the returned
 * {@link SpanScope}; closing is idempotent (see {@link SpanScope#close()}).
 * <p>
 * Parent/child relationships are explicit. Every span is created with a
 * {@link org.axonframework.messaging.core.unitofwork.ProcessingContext} passed to the {@link SpanFactory} factory
 * method, and its parent is resolved <em>at creation time</em> from that context's active {@link SpanScope} (see
 * {@link SpanScope#RESOURCE_KEY}). Parenting across asynchronous or process boundaries instead rides on message
 * metadata via {@link #propagateContext(Message)} on the dispatch side and
 * {@link SpanFactory#createHandlerSpan(String, Message, org.axonframework.messaging.core.unitofwork.ProcessingContext)}
 * on the handling side.
 * <p>
 * Two flavors of span cover every use in the framework, distinguished by how they interact with a context's active
 * scope:
 * <ul>
 *     <li><b>Branch-scoped</b> -- the span covers one sub-operation of a context (a per-event handler span, dispatch,
 *     or repository operation). Use {@link #branch(ProcessingContext, Function)},
 *     {@link #branchAsync(ProcessingContext, Function)} or {@link #branchStream(ProcessingContext, Function)}. Each
 *     starts the span, hands the operation a context <em>branch</em> carrying the scope (via
 *     {@link SpanScope#addToContext(ProcessingContext, SpanScope)}) so the operation's own children parent under it,
 *     executes the operation within the scope (via {@link SpanScope#within(Supplier)}), and closes the scope when the
 *     operation's own result terminates -- never when the enclosing context completes.</li>
 *     <li><b>Lifecycle-covering</b> -- the span <em>is</em> the context's dominant operation for its entire lifetime (a
 *     streaming-processor batch span or a per-command/query handler span). Use
 *     {@link #coverLifecycle(ProcessingContext)}: it records this span's scope on the context's root
 *     (last-writer-wins) and closes it when the context completes.</li>
 * </ul>
 *
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @see SpanFactory
 * @since 4.6.0
 */
public interface Span {

    /**
     * Starts this span and returns its {@link SpanScope}, without writing anything to a {@link ProcessingContext}.
     * This is the imperative edge underlying the <b>branch-scoped</b> flavor: the caller is responsible for making the
     * returned scope reachable to the sub-operation it covers -- typically via
     * {@link SpanScope#addToContext(ProcessingContext, SpanScope)} -- and for closing it explicitly when that
     * sub-operation's own result terminates. Closing is idempotent; see {@link SpanScope#close()}.
     *
     * @return the {@link SpanScope} governing this span; never {@code null}
     */
    SpanScope start();

    /**
     * Starts this span to cover the given {@link ProcessingContext}'s lifecycle: starts the span, records its
     * {@link SpanScope} on the context's root under {@link SpanScope#RESOURCE_KEY} (last-writer-wins -- matching
     * {@code Message.RESOURCE_KEY}), records any processing error on the span, and closes
     * the scope when the context completes (on both the success and error paths). This is the
     * <b>context-lifetime</b> counterpart to {@link #start()}: use it only when this span <em>is</em> the context's
     * dominant operation for its entire lifetime (a batch span; a per-command/query handler span) -- never for a span
     * that covers just one sub-operation of a longer-lived context, which would silently steal every other
     * sub-operation's parent for the rest of the context's lifetime.
     *
     * @implSpec This method is a fixed composition of {@link #start()} and {@link #recordException(Throwable)}: the
     * framework relies on the scope being recorded on the context's root and closed exactly once when the context
     * completes, on both the success and error paths. Redefining it changes those guarantees for every framework
     * call site at once; provider-specific scope execution belongs in {@link SpanScope#within(Supplier)} instead.
     *
     * @param context the processing context whose lifecycle the span is bound to
     * @return the started {@link SpanScope}, also recorded on {@code context} under {@link SpanScope#RESOURCE_KEY}
     * @since 5.3.0
     */
    default SpanScope coverLifecycle(ProcessingContext context) {
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
     * The span propagates <em>itself</em>, without consulting ambient state. Implementations that perform no
     * propagation (no-op, logging) return the input unchanged and never throw.
     *
     * @param message the message to enrich with this span's tracing context
     * @param <M>     the message type
     * @return the message carrying this span's propagated tracing context (possibly the same instance)
     */
    <M extends Message> M propagateContext(M message);

    /**
     * Runs the given value-producing operation as a <b>branch-scoped</b> span: starts this span,
     * hands the operation a context branch carrying this span's scope (via
     * {@link SpanScope#addToContext(ProcessingContext, SpanScope)}) so the operation's own children parent under this
     * span, executes the operation within the scope (via {@link SpanScope#within(Supplier)}), and ends the span when
     * the operation returns -- deterministically, on both the value and the throw path. Exceptions are recorded on
     * the span and rethrown. When {@code context} is
     * {@code null}, the operation receives {@code null} and no branch is created.
     *
     * @implSpec This method is a fixed composition of {@link #start()}, {@link #recordException(Throwable)}, and
     * {@link SpanScope#within(Supplier)}: the framework relies on the span ending exactly once, on both the value and
     * the throw path, with failures recorded before the scope closes. Redefining it changes those guarantees for
     * every framework call site at once; provider-specific behavior belongs in {@link SpanScope#within(Supplier)},
     * the extension point this composition already calls.
     *
     * @param context   the processing context to branch for the operation, or {@code null} when none is available
     * @param operation the value-producing block to run, receiving the branched context (or {@code null})
     * @param <T>       the produced value type
     * @return the value produced by {@code operation}
     * @since 5.3.0
     */
    default <T> T branch(@Nullable ProcessingContext context,
                         Function<@Nullable ProcessingContext, T> operation) {
        SpanScope scope = start();
        ProcessingContext scoped = context == null ? null : SpanScope.addToContext(context, scope);
        try {
            return scope.within(() -> operation.apply(scoped));
        } catch (Throwable t) {
            recordException(t);
            throw t;
        } finally {
            scope.close();
        }
    }

    /**
     * Runs the given asynchronous operation as a <b>branch-scoped</b> span: starts this span, hands the operation a
     * context branch carrying this span's scope (via
     * {@link SpanScope#addToContext(ProcessingContext, SpanScope)}) so the operation's own children -- including those
     * created in asynchronous continuations -- parent under this span, executes the operation's synchronous frame
     * within the scope (via {@link SpanScope#within(Supplier)}), and ends the span when the returned
     * {@link CompletableFuture} completes (normally or exceptionally). A failure of the future is recorded on the
     * span; a synchronous failure of the operation itself is recorded, the span ended, and the throwable rethrown.
     * <p>
     * When {@code context} is non-{@code null}, a close-only leak backstop is also registered via
     * {@link ProcessingContext#doFinally}: should the framework abandon the returned future's completion path, the
     * span still ends when the context does. Closing is idempotent, so the backstop and the primary close may overlap
     * safely.
     *
     * @implSpec This method is a fixed composition of {@link #start()}, {@link #recordException(Throwable)}, and
     * {@link SpanScope#within(Supplier)}: the framework relies on the span ending exactly once when the returned
     * future completes -- with the context-completion backstop as the only other closer -- and on failures being
     * recorded before the scope closes. Redefining it changes those guarantees for every framework call site at once;
     * provider-specific behavior belongs in {@link SpanScope#within(Supplier)}, the extension point this composition
     * already calls.
     *
     * @param context   the processing context to branch for the operation, or {@code null} when none is available
     * @param operation the block producing the {@link CompletableFuture} to trace, receiving the branched context (or
     *                  {@code null})
     * @param <T>       the future's result type
     * @return a future that completes with the same result/exception as the operation's future
     * @since 5.3.0
     */
    default <T> CompletableFuture<T> branchAsync(
            @Nullable ProcessingContext context,
            Function<@Nullable ProcessingContext, CompletableFuture<T>> operation
    ) {
        SpanScope scope = start();
        ProcessingContext branched = null;
        if (context != null) {
            // Leak backstop only (idempotent close, no recordException): the primary close is the future's
            // completion below; a context-level error must not stamp this operation's span.
            context.doFinally(processingContext -> scope.close());
            branched = SpanScope.addToContext(context, scope);
        }
        ProcessingContext scoped = branched;
        CompletableFuture<T> future;
        try {
            future = Objects.requireNonNull(scope.within(() -> operation.apply(scoped)),
                                            "The operation returned a null CompletableFuture.");
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

    /**
     * Runs the given {@link MessageStream}-producing operation as a <b>branch-scoped</b> span: starts this span, hands
     * the operation a context branch carrying this span's scope (via
     * {@link SpanScope#addToContext(ProcessingContext, SpanScope)}) so the operation's own children -- including those
     * created in asynchronous continuations -- parent under this span, executes the operation's synchronous frame
     * within the scope (via {@link SpanScope#within(Supplier)}), and ends the span when the returned stream terminates:
     * on normal completion, or on an error (recorded on the span first). A synchronous failure of the operation itself
     * is recorded, the span ended, and the throwable rethrown.
     * <p>
     * When {@code context} is non-{@code null}, a close-only leak backstop is also registered via
     * {@link ProcessingContext#doFinally}: should the framework abandon the returned stream before it terminates, the
     * span still ends when the context does. Closing is idempotent, so the backstop and the primary close may overlap
     * safely.
     *
     * @implSpec This method is a fixed composition of {@link #start()}, {@link #recordException(Throwable)}, and
     * {@link SpanScope#within(Supplier)}: the framework relies on the span ending exactly once when the returned
     * stream terminates -- with the context-completion backstop as the only other closer -- and on failures being
     * recorded before the scope closes. Redefining it changes those guarantees for every framework call site at once;
     * provider-specific behavior belongs in {@link SpanScope#within(Supplier)}, the extension point this composition
     * already calls.
     *
     * @param context   the processing context to branch for the operation, or {@code null} when none is available
     * @param operation the block producing the {@link MessageStream} to trace, receiving the branched context (or
     *                  {@code null})
     * @param <M>       the type of {@link Message} carried by the stream
     * @return a stream completing with the same entries/error as the operation's stream, ending this span on
     * termination
     * @since 5.3.0
     */
    default <M extends Message> MessageStream<M> branchStream(
            @Nullable ProcessingContext context,
            Function<@Nullable ProcessingContext, MessageStream<M>> operation
    ) {
        SpanScope scope = start();
        ProcessingContext branched = null;
        if (context != null) {
            // Leak backstop only (idempotent close, no recordException): the primary close is stream-driven below;
            // a context-level error must not stamp this operation's span.
            context.doFinally(processingContext -> scope.close());
            branched = SpanScope.addToContext(context, scope);
        }
        ProcessingContext scoped = branched;
        // The whole composition runs inside the scope: composing the wrappers may already pull the operation's stream
        // once (stream-construction probes), which is what subscribes a reactive handler's Flux. That subscription
        // must observe this span's scope, not an enclosing one. The outermost SpanScopedMessageStream then re-enters
        // the scope around every later pull, so lazily executing parts of the operation run within the same scope.
        try {
            return scope.within(() -> {
                MessageStream<M> result = operation.apply(scoped);
                return new SpanScopedMessageStream<>(
                        result.onErrorContinue(error -> {
                                  recordException(error);
                                  scope.close();
                                  return MessageStream.failed(error);
                              })
                              .onComplete(scope::close),
                        scope);
            });
        } catch (Throwable t) {
            recordException(t);
            scope.close();
            throw t;
        }
    }
}
