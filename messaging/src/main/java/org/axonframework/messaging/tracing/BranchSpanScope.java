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

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Context carrier for a branch-scoped span and the parent scope inherited by that branch. While the delegate is open
 * it is the branch's active scope; after it closes, resolution continues with the parent carrier.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
final class BranchSpanScope implements SpanScope {

    private final SpanScope delegate;
    private final @Nullable SpanScope parent;

    BranchSpanScope(SpanScope delegate, @Nullable SpanScope parent) {
        this.delegate = Objects.requireNonNull(delegate, "delegate may not be null");
        this.parent = parent;
    }

    static @Nullable SpanScope resolve(@Nullable SpanScope candidate) {
        while (candidate instanceof BranchSpanScope branch) {
            if (!branch.delegate.isClosed()) {
                return branch.delegate;
            }
            candidate = branch.parent;
        }
        return candidate == null || candidate.isClosed() ? null : candidate;
    }

    @Override
    public Span span() {
        return delegate.span();
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public <T> T within(Supplier<T> operation) {
        return delegate.within(operation);
    }
}
