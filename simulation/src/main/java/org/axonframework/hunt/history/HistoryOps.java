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
 * The operation names a hunt run records, as plain strings.
 * <p>
 * {@link HistoryRecord#op()} is deliberately a {@code String} rather than an enum: the set of operations is open.
 * Recording a new kind of operation means passing a new name, never editing this class or any other. The constants
 * below are the names in use, so that recorders and checkers agree on spelling; a checker that does not recognise a
 * name ignores the record.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class HistoryOps {

    /**
     * An append of one or more events under an {@code AppendCondition}.
     */
    public static final String APPEND = "append";

    /**
     * A sourcing read under a {@code SourcingCondition}.
     */
    public static final String SOURCE = "source";

    /**
     * The commit of an append transaction. Its value carries the identifiers of the events it made visible.
     */
    public static final String COMMIT = "commit";

    /**
     * The rollback of an append transaction. Its value carries the identifiers of the events it discarded.
     */
    public static final String ROLLBACK = "rollback";

    /**
     * A token claim taken by a node for a segment.
     */
    public static final String CLAIM = "claim";

    /**
     * An extension of a token claim already held.
     */
    public static final String EXTEND = "extend";

    /**
     * The voluntary release of a token claim.
     */
    public static final String RELEASE = "release";

    /**
     * A token claim taken from another node whose claim had expired.
     */
    public static final String STEAL = "steal";

    /**
     * The delivery of an event to a handler.
     */
    public static final String DELIVER = "deliver";

    /**
     * A processor token reset.
     */
    public static final String RESET = "reset";

    /**
     * A segment split.
     */
    public static final String SPLIT = "split";

    /**
     * A segment merge.
     */
    public static final String MERGE = "merge";

    /**
     * The initialisation of a processor's token segments.
     */
    public static final String INIT_SEGMENTS = "init-segments";

    /**
     * An authoritative scan of the store after the run has quiesced. Recorded as a standalone
     * {@link RecordType#INFO} record whose value carries the identifiers of every event present.
     */
    public static final String SCAN = "scan";

    private HistoryOps() {
        // Utility class.
    }
}
