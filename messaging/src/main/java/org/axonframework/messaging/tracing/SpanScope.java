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

import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/**
 * The active-span scope returned by {@link Span#start()}. Closing the scope ends the underlying {@link Span}.
 * <p>
 * A {@code SpanScope} is the neutral, provider-agnostic handle to a span that is active <em>on a context branch</em>.
 * It is carried on a {@link ProcessingContext} under {@link #RESOURCE_KEY} and read back with
 * {@link #fromContext(ProcessingContext)} -- mirroring the {@code RESOURCE_KEY} / {@code addToContext} /
 * {@code fromContext} convention of {@link org.axonframework.messaging.core.Message} and {@code TrackingToken}. Two
 * distinct carriers exist:
 * <ul>
 *     <li><b>Lifecycle-covering spans</b> (a batch span, a per-command/query handler span) are recorded on the
 *     <em>root</em> context by {@link Span#coverLifecycle(ProcessingContext)}, last-writer-wins, exactly like
 *     {@code Message.RESOURCE_KEY}'s "current message".</li>
 *     <li><b>Branch-scoped spans</b> (a per-event handler span, a per-invocation method span) are carried on an
 *     immutable <em>branch</em> via {@link #addToContext(ProcessingContext, SpanScope)}: the branch, not the root, is
 *     what flows into the operation's own handling, so its children read back exactly this scope while it is open.
 *     Once it closes, the branch falls back to the parent scope inherited when it was created. The context tree is
 *     therefore the parenting stack, including for a branch retained by deferred lifecycle work.</li>
 * </ul>
 * Both carriers use this same key; a reader never needs to know which flavor produced the scope it reads.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
public interface SpanScope extends AutoCloseable {

    /**
     * Resource key under which the active {@link SpanScope} is carried on a {@link ProcessingContext} -- either on
     * the root context (written by {@link Span#coverLifecycle(ProcessingContext)}) or on an immutable branch (via
     * {@link #addToContext(ProcessingContext, SpanScope)}) -- and read with {@link #fromContext(ProcessingContext)}.
     * <p>
     * <b>Question it answers:</b> <em>"Which scope is the parent for a span created with this exact context instance,
     * and what is the active span for provider-agnostic access (for example {@link Span#addAttribute(String, String)})?"</em>
     * <p>
     * <b>Why one key is enough.</b> Precise parent chaining no longer needs a provider-private stack: a
     * branch-scoped span branches the context it hands to its own operation, so every span created with that
     * branch -- however deeply nested, however many other operations start and finish elsewhere on the root in the
     * meantime -- reads back exactly the branch's scope. A lifecycle-covering span instead writes the root directly,
     * matching {@code Message.RESOURCE_KEY}'s last-writer-wins "current message" semantics, which is correct because
     * such a span <em>is</em> the context's dominant operation for its entire lifetime.
     */
    Context.ResourceKey<SpanScope> RESOURCE_KEY = Context.ResourceKey.withLabel("SpanScope");

    /**
     * Returns a {@link ProcessingContext} carrying the given {@code scope} as the active span scope under
     * {@link #RESOURCE_KEY}. Functional helper for callers composing a context; note it returns a (possibly new)
     * context rather than mutating the given one -- {@link Span#coverLifecycle(ProcessingContext)} instead mutates the
     * live context so spans created next with that same instance can read the active scope.
     * <p>
     * The parent carrier for the closed-scope fallback (see {@link #fromContext(ProcessingContext)}) is captured from
     * {@code context} at this moment. A lifecycle scope that later replaces the root's carrier (last-writer-wins, via
     * {@link Span#coverLifecycle(ProcessingContext)}) does not re-target already-created branches: their fallback
     * chain still ends at the scope inherited here.
     *
     * @param context the processing context to add the scope to
     * @param scope   the active span scope to add
     * @return the processing context carrying {@code scope} under {@link #RESOURCE_KEY}
     */
    static ProcessingContext addToContext(ProcessingContext context, SpanScope scope) {
        SpanScope parent = context.getResource(RESOURCE_KEY);
        return context.withResource(RESOURCE_KEY, new BranchSpanScope(scope, parent));
    }

    /**
     * Retrieves the active {@code SpanScope} from the given {@code context} under {@link #RESOURCE_KEY}.
     *
     * @param context the processing context to read the active span scope from
     * @return the active span scope, or {@code null} when none is present
     */
    static @Nullable SpanScope fromContext(ProcessingContext context) {
        return BranchSpanScope.resolve(context.getResource(RESOURCE_KEY));
    }

    /**
     * Returns the {@link Span} governed by this scope.
     *
     * @return the span this scope governs
     */
    Span span();

    /**
     * Returns whether this scope has closed for context resolution. The result is monotonic and thread-safe: closing
     * must transition it to {@code true} before ending the provider span, and once this method returns {@code true}, it
     * must never return {@code false} again. A scope already observed as closed is skipped during parent resolution;
     * a concurrent resolver that observes it immediately before closure may still select it as a legitimate in-flight
     * parent.
     *
     * @return {@code true} after this scope has closed, otherwise {@code false}
     * @since 5.3.0
     */
    boolean isClosed();

    /**
     * Closes this scope, ending the underlying {@link Span}. Subsequent calls are no-ops -- implementations MUST be
     * idempotent, so a synchronous throw (which closes the scope explicitly) and a later stream-termination callback
     * (which would otherwise close it again) can safely overlap.
     */
    @Override
    void close();

    /**
     * Executes the given {@code operation} within this scope and returns its result. Any scope-bound state an
     * implementation maintains is observable to code running inside the operation and detached again before this
     * method returns. This is the provider extension point the structured {@link Span} operations execute through.
     * Implementations MUST be transparent: return the operation's value, let its exceptions propagate unchanged, and
     * never end the span. An implementation without scope-bound state simply executes the operation unchanged.
     *
     * @param operation the operation to execute within this scope
     * @param <T>       the operation's result type
     * @return the value produced by {@code operation}
     */
    <T> T within(Supplier<T> operation);

    /**
     * Executes the given void {@code operation} within this scope. Convenience overload of {@link #within(Supplier)}
     * for operations without a result; it routes through {@link #within(Supplier)}, so the same transparency rules
     * apply.
     *
     * @param operation the operation to execute within this scope
     */
    default void within(Runnable operation) {
        within(() -> {
            operation.run();
            return null;
        });
    }
}
