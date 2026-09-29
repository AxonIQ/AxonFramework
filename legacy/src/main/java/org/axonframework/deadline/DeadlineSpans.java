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

package org.axonframework.deadline;

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.tracing.Span;
import org.axonframework.messaging.tracing.SpanScope;

/**
 * Span helpers shared by the {@link DeadlineManager} implementations in this module.
 * <p>
 * The deadline managers schedule and cancel work that is only run later, once the surrounding unit of work reaches its
 * commit phase. They therefore need a {@link Runnable} that carries its span with it, rather than a span opened around
 * the call that created it.
 *
 * @author Axon Framework
 * @since 5.4.0
 */
@Internal
public final class DeadlineSpans {

    private DeadlineSpans() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Wraps the given {@code task} so that, whenever it is eventually run, it runs as a branch-scoped operation of the
     * given {@code span}: the span is started, the task runs within its {@link SpanScope}, any failure is recorded on
     * the span, and the scope is closed again.
     *
     * @param span the span to run the task within
     * @param task the task to run
     * @return a {@link Runnable} running {@code task} within {@code span}
     */
    public static Runnable spanned(Span span, Runnable task) {
        return () -> span.branch(null, context -> {
            task.run();
            return null;
        });
    }
}
