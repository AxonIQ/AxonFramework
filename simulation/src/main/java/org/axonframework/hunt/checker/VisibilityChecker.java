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

package org.axonframework.hunt.checker;

import org.axonframework.hunt.history.HistoryOps;
import org.axonframework.hunt.history.HistoryRecord;
import org.axonframework.hunt.history.HistoryView;
import org.axonframework.hunt.history.Operation;
import org.axonframework.hunt.history.Outcome;
import org.axonframework.hunt.model.DcbHistoryCodec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks that nothing becomes observable before it is committed, and that nothing rolled back ever becomes observable
 * at all.
 * <p>
 * Two invariants, one pass over the history:
 * <ul>
 *     <li>{@value #NO_VISIBILITY_BEFORE_COMMIT} - a delivery must be preceded by the commit of the transaction that
 *     appended the delivered event.</li>
 *     <li>{@value #ROLLED_BACK_EVENTS_NEVER_OBSERVABLE} - an event a transaction rolled back must appear in no
 *     delivery and in no post-run scan of the store.</li>
 * </ul>
 * <p>
 * A commit whose outcome is unknown makes its events could-have-been-committed, so a delivery of one of them is not
 * treated as a violation; the ambiguity is reported instead. A rollback whose outcome is unknown is treated the same
 * way, since it may not have discarded anything.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public class VisibilityChecker implements Checker {

    /**
     * The stable name of the invariant forbidding pre-commit visibility.
     */
    public static final String NO_VISIBILITY_BEFORE_COMMIT = "NoVisibilityBeforeCommit";

    /**
     * The statement of {@value #NO_VISIBILITY_BEFORE_COMMIT}, character-identical to the invariant registry.
     */
    public static final String NO_VISIBILITY_BEFORE_COMMIT_STATEMENT =
            "No event is delivered to a consumer before the commit of the transaction that appended it.";

    /**
     * The stable name of the invariant forbidding rolled-back events from ever being observed.
     */
    public static final String ROLLED_BACK_EVENTS_NEVER_OBSERVABLE = "RolledBackEventsNeverObservable";

    /**
     * The statement of {@value #ROLLED_BACK_EVENTS_NEVER_OBSERVABLE}, character-identical to the invariant registry.
     */
    public static final String ROLLED_BACK_EVENTS_NEVER_OBSERVABLE_STATEMENT =
            "No event of a rolled-back transaction is ever delivered to a consumer or present in a post-run scan of "
                    + "the store.";

    @Override
    public String name() {
        return "VisibilityChecker";
    }

    @Override
    public Set<String> machineNames() {
        return Set.of(NO_VISIBILITY_BEFORE_COMMIT, ROLLED_BACK_EVENTS_NEVER_OBSERVABLE);
    }

    @Override
    public CheckResult check(HistoryView history) {
        List<Violation> violations = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        Map<String, HistoryRecord> committedAt = new HashMap<>();
        Set<String> ambiguouslyCommitted = new HashSet<>();
        collect(history, HistoryOps.COMMIT, committedAt, ambiguouslyCommitted, notes, "commit");

        Map<String, HistoryRecord> rolledBackAt = new HashMap<>();
        Set<String> ambiguouslyRolledBack = new HashSet<>();
        collect(history, HistoryOps.ROLLBACK, rolledBackAt, ambiguouslyRolledBack, notes, "rollback");

        for (Operation delivery : history.operations(HistoryOps.DELIVER)) {
            String eventId = delivery.invocation().stringValue(DcbHistoryCodec.EVENT_ID);
            if (eventId == null) {
                continue;
            }
            HistoryRecord commit = committedAt.get(eventId);
            if (commit == null) {
                if (ambiguouslyCommitted.contains(eventId)) {
                    notes.add("Event " + eventId + " was delivered and its commit outcome is unknown.");
                } else {
                    violations.add(Violation.of(NO_VISIBILITY_BEFORE_COMMIT,
                                                NO_VISIBILITY_BEFORE_COMMIT_STATEMENT,
                                                "event " + eventId + " was delivered but never committed",
                                                delivery.records(),
                                                history.header()));
                }
            } else if (delivery.invocation().idx() < commit.idx()) {
                violations.add(Violation.of(NO_VISIBILITY_BEFORE_COMMIT,
                                            NO_VISIBILITY_BEFORE_COMMIT_STATEMENT,
                                            "event " + eventId + " was delivered at record #"
                                                    + delivery.invocation().idx() + ", before its commit at record #"
                                                    + commit.idx(),
                                            List.of(delivery.invocation(), commit),
                                            history.header()));
            }
            if (rolledBackAt.containsKey(eventId)) {
                violations.add(Violation.of(ROLLED_BACK_EVENTS_NEVER_OBSERVABLE,
                                            ROLLED_BACK_EVENTS_NEVER_OBSERVABLE_STATEMENT,
                                            "event " + eventId + " was delivered although its transaction rolled back",
                                            List.of(delivery.invocation(), rolledBackAt.get(eventId)),
                                            history.header()));
            } else if (ambiguouslyRolledBack.contains(eventId)) {
                notes.add("Event " + eventId + " was delivered and its rollback outcome is unknown.");
            }
        }

        for (HistoryRecord scan : history.notes(HistoryOps.SCAN)) {
            for (String eventId : scan.stringListValue(DcbHistoryCodec.EVENT_IDS)) {
                if (rolledBackAt.containsKey(eventId)) {
                    violations.add(Violation.of(ROLLED_BACK_EVENTS_NEVER_OBSERVABLE,
                                                ROLLED_BACK_EVENTS_NEVER_OBSERVABLE_STATEMENT,
                                                "event " + eventId
                                                        + " is present in a post-run scan although its transaction "
                                                        + "rolled back",
                                                List.of(scan, rolledBackAt.get(eventId)),
                                                history.header()));
                } else if (ambiguouslyRolledBack.contains(eventId)) {
                    notes.add("Event " + eventId + " is present in a post-run scan and its rollback outcome is "
                                      + "unknown.");
                }
            }
        }
        return new CheckResult(name(), List.copyOf(violations), List.copyOf(notes));
    }

    private static void collect(HistoryView history,
                                String op,
                                Map<String, HistoryRecord> resolved,
                                Set<String> ambiguous,
                                List<String> notes,
                                String label) {
        for (Operation operation : history.operations(op)) {
            List<String> eventIds = operation.invocation().stringListValue(DcbHistoryCodec.EVENT_IDS);
            if (eventIds.isEmpty()) {
                eventIds = completionEventIds(operation);
            }
            if (operation.outcome() == Outcome.OK) {
                // The invocation, not the completion: an event becomes visible somewhere inside the commit, and the
                // record saying the commit returned is written after the store has already published it. Comparing a
                // delivery against the completion would report every fast consumer as a visibility violation.
                HistoryRecord at = operation.invocation();
                // The earliest such record wins. An event can be committed more than once -- a store that duplicates
                // an append writes it twice -- and it became visible at the first of them, not the last.
                eventIds.forEach(eventId -> resolved.merge(eventId, at,
                                                           (existing, candidate) ->
                                                                   existing.idx() <= candidate.idx()
                                                                           ? existing : candidate));
            } else if (operation.outcome() == Outcome.UNKNOWN) {
                ambiguous.addAll(eventIds);
                notes.add("The " + label + " of " + eventIds + " (record #" + operation.invocation().idx()
                                  + ") has an unknown outcome.");
            }
        }
    }

    private static List<String> completionEventIds(Operation operation) {
        HistoryRecord completion = operation.completion();
        return completion == null ? List.of() : completion.stringListValue(DcbHistoryCodec.EVENT_IDS);
    }
}
