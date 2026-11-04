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

import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageStreamTestSuite;

import java.util.List;
import java.util.function.Supplier;

/**
 * Runs the {@link MessageStreamTestSuite} against {@link SpanScopedMessageStream}, which lives in this package.
 *
 * @author John Hendrikx
 */
class SpanScopedMessageStreamTest extends MessageStreamTestSuite {

    private static final SpanScope SCOPE = new SpanScope() {
        @Override
        public Span span() {
            throw new UnsupportedOperationException("Not used by SpanScopedMessageStream");
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void close() {
        }

        @Override
        public <T> T within(Supplier<T> operation) {
            return operation.get();
        }
    };

    @Override
    protected List<Case> cases() {
        return List.of(
            new Case(0, new SpanScopedMessageStream<>(MessageStream.empty(), SCOPE)),
            new Case(1, new SpanScopedMessageStream<>(MessageStream.just(msg(0)), SCOPE)),
            new Case(3, new SpanScopedMessageStream<>(MessageStream.fromItems(msg(0), msg(1), msg(2)), SCOPE)),
            new Case("async", 1, new SpanScopedMessageStream<>(MessageStream.fromFuture(delayedEntry(0).thenApply(MessageStream.Entry::message)), SCOPE))
        );
    }

    @Override
    protected List<ErrorCase> errorCases() {
        return List.of(
            new ErrorCase(0, new SpanScopedMessageStream<>(MessageStream.failed(new RuntimeException("oops")), SCOPE)),
            new ErrorCase(2, new SpanScopedMessageStream<>(MessageStream.fromItems(msg(0), msg(1)).concatWith(MessageStream.failed(new RuntimeException("oops"))), SCOPE)),
            new ErrorCase("async", 0, new SpanScopedMessageStream<>(MessageStream.fromFuture(delayedFailedEntry().thenApply(MessageStream.Entry::message)), SCOPE))
        );
    }
}
