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

    /**
     * The evidence one injected fault leaves behind: what was declared, how often it fired, and against what.
     * Recorded as a standalone {@link RecordType#INFO} record once the fault has been removed. A declared fault whose
     * fire count is zero is what makes a run inconclusive rather than a pass.
     */
    public static final String FAULT = "fault";

    /**
     * The boundary between two phases of a run: warmup, a fault window, heal, settle, verdict. Recorded as a
     * standalone {@link RecordType#INFO} record so that a reader can place every other record in a phase.
     */
    public static final String PHASE = "phase";

    /**
     * A commit that stored something other than what was offered, because a fault interfered. Recorded as a
     * standalone {@link RecordType#INFO} record naming what was offered and what was actually stored. A checker that
     * replays a history against the reference model cannot decide a run containing one of these.
     */
    public static final String STORE_PERTURBED = "store-perturbed";

    /**
     * A workload's read model, as it stood once the run had quiesced. Recorded as a standalone
     * {@link RecordType#INFO} record so that a checker can compare it against its own fold of the run's committed
     * effects.
     */
    public static final String PROJECTION = "projection";

    /**
     * A ledger transfer: the workload-level operation that moves an amount from one account to another. Its value
     * carries the accounts, the amount and the identifiers of the two events it appends.
     */
    public static final String TRANSFER = "transfer";

    /**
     * One complete read of the store by a concurrent reader, recorded as a standalone {@link RecordType#INFO} record.
     * Its value carries, per batch the reader saw anything of, how many of that batch's events were observable in
     * that single read and how many the batch holds. It is what makes a partially-visible batch a fact rather than a
     * suspicion.
     */
    public static final String POLL = "poll";

    /**
     * The sequence identifier the framework resolved for one delivered event, recorded on the
     * {@link #DELIVER deliver} record under {@link #SEQUENCE_KEY} and, when the resolution failed, as a standalone
     * {@link RecordType#INFO} record under this name carrying the error.
     */
    public static final String SEQUENCE = "sequence";

    /**
     * The value key a delivery's sequence identifier is recorded under. A delivery without it is one whose workload
     * does not track sequencing, and every ordering oracle ignores it rather than guessing.
     */
    public static final String SEQUENCE_KEY = "sequenceKey";

    private HistoryOps() {
        // Utility class.
    }
}
