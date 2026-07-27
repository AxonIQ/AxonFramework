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

/**
 * Converts a {@link HistoryView} into the EDN history format that Elle consumes.
 * <p>
 * The history schema was chosen so that this conversion is mechanical: an Elle operation is
 * {@code {:index :process :type :f :value}} and every one of those has a direct counterpart in a
 * {@link HistoryRecord} ({@link HistoryRecord#idx()}, {@link HistoryRecord#process()}, {@link HistoryRecord#type()},
 * {@link HistoryRecord#op()}, {@link HistoryRecord#value()}), with {@link RecordType#INFO} carrying Elle's
 * indeterminate outcome. The field mapping is written down in {@code formal/INVARIANTS.md}.
 * <p>
 * The conversion itself is not implemented. Elle answers questions about transactional isolation anomalies, and this
 * suite's questions are so far about DCB conflict semantics, visibility, and ownership, which the checkers over
 * {@link HistoryView} answer directly. Keeping the schema convertible costs nothing; adding the dependency before
 * there is a question for it would.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class HistoryToElle {

    private HistoryToElle() {
        // Utility class.
    }

    /**
     * Renders the given history as an Elle EDN history.
     *
     * @param history the history to convert
     * @return the EDN rendering of the history
     * @throws UnsupportedOperationException always
     */
    public static String toEdn(HistoryView history) {
        // TODO Implement when a transactional-isolation question actually needs Elle. The field mapping is fixed and
        //  documented in formal/INVARIANTS.md; only the EDN rendering is missing.
        throw new UnsupportedOperationException(
                "Elle conversion is not implemented; the history schema is convertible but no checker needs it yet."
        );
    }
}
