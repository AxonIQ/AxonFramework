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

/**
 * The active-span scope returned by {@link Span#start()}. Closing the scope ends the underlying {@link Span}.
 * <p>
 * A {@code SpanScope} is the neutral, provider-agnostic handle to the span that is active on a
 * {@link ProcessingContext}. It is stored on the context under {@link #RESOURCE_KEY} (never via a {@code ThreadLocal})
 * by {@link Span#start(ProcessingContext)} and can be read back with {@link #fromContext(ProcessingContext)} -- mirroring
 * the {@code RESOURCE_KEY} / {@code addToContext} / {@code fromContext} convention of
 * {@link org.axonframework.messaging.core.Message} and {@code TrackingToken}. Under nested spans this is
 * last-writer-wins (the most recently started span on the context), exactly like {@code Message.RESOURCE_KEY}'s
 * "current message"; precise parent chaining is the tracing provider's concern, kept on its own private resource so
 * composed factories (see {@link MultiSpanFactory}) do not collide.
 *
 * @author AxonIQ
 * @since 5.2.0
 */
public interface SpanScope extends AutoCloseable {

    /**
     * Resource key under which the active {@code SpanScope} is stored on a {@link ProcessingContext}.
     */
    Context.ResourceKey<SpanScope> RESOURCE_KEY = Context.ResourceKey.withLabel("SpanScope");

    /**
     * Returns a {@link ProcessingContext} carrying the given {@code scope} as the active span scope under
     * {@link #RESOURCE_KEY}. Functional helper for callers composing a context; note it returns a (possibly new)
     * context rather than mutating the given one -- {@link Span#start(ProcessingContext)} instead mutates the live
     * context so spans created next with that same instance can read the active scope.
     *
     * @param context the processing context to add the scope to
     * @param scope   the active span scope to add
     * @return the processing context carrying {@code scope} under {@link #RESOURCE_KEY}
     */
    static ProcessingContext addToContext(ProcessingContext context, SpanScope scope) {
        return context.withResource(RESOURCE_KEY, scope);
    }

    /**
     * Retrieves the active {@code SpanScope} from the given {@code context} under {@link #RESOURCE_KEY}.
     *
     * @param context the processing context to read the active span scope from
     * @return the active span scope, or {@code null} when none is present
     */
    static @Nullable SpanScope fromContext(ProcessingContext context) {
        return context.getResource(RESOURCE_KEY);
    }

    /**
     * Returns the {@link Span} governed by this scope.
     *
     * @return the span this scope governs
     */
    Span span();

    /**
     * Closes this scope, ending the underlying {@link Span}. Must be invoked exactly once.
     */
    @Override
    void close();
}
