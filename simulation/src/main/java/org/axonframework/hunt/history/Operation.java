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

package org.axonframework.hunt.history;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * An invocation paired with its completion, as resolved by {@link HistoryView}.
 * <p>
 * The completion is {@code null} when the operation was still in flight at the end of the run. That is not an error
 * and the operation is not dropped: it is reported with outcome {@link Outcome#UNKNOWN}.
 *
 * @param invocation the {@link RecordType#INVOKE} record that started the operation
 * @param completion the record that ended it, or {@code null} when the operation never completed
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public record Operation(HistoryRecord invocation, @Nullable HistoryRecord completion) {

    /**
     * Compact constructor rejecting a missing invocation.
     */
    public Operation {
        Objects.requireNonNull(invocation, "The invocation cannot be null.");
    }

    /**
     * Returns what the history says about this operation's effect.
     *
     * @return {@link Outcome#OK}, {@link Outcome#FAIL}, or {@link Outcome#UNKNOWN} for an indeterminate or missing
     *     completion
     */
    public Outcome outcome() {
        if (completion == null) {
            return Outcome.UNKNOWN;
        }
        return switch (completion.type()) {
            case OK -> Outcome.OK;
            case FAIL -> Outcome.FAIL;
            case INVOKE, INFO -> Outcome.UNKNOWN;
        };
    }

    /**
     * Returns the operation's name.
     *
     * @return the operation name; see {@link HistoryOps}
     */
    public String op() {
        return invocation.op();
    }

    /**
     * Returns the identifier joining the invocation to its completion.
     *
     * @return the correlation identifier
     */
    public String id() {
        return invocation.id();
    }

    /**
     * Returns the records that make up this operation, for inclusion in a violation report.
     *
     * @return the invocation, followed by the completion when there is one
     */
    public List<HistoryRecord> records() {
        return completion == null ? List.of(invocation) : List.of(invocation, completion);
    }
}
