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
import org.axonframework.hunt.model.DcbHistoryCodec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks that a sequencing policy delivers each of its keys in the order that key's events were appended.
 * <p>
 * The append order is the store's own, taken from the authoritative scan the run records once it has quiesced, rather
 * than from the order in which appends were issued. Under concurrency the writer that asked first is often not the
 * writer that landed first, so judging delivery against issue order would report a correct read side as broken.
 * <p>
 * Only deliveries carrying a sequence identifier are judged. A workload that does not track sequencing records none,
 * and this checker then holds vacuously rather than guessing an identifier the framework never resolved: a guessed key
 * would make the verdict a property of the checker instead of a property of the run.
 * <p>
 * Two situations produce a note rather than a violation. A delivered event that is absent from the scan cannot be
 * placed in the append order at all, and a second delivery of an event already delivered is a duplication rather than
 * a reordering; both are surfaced, and neither is decided here.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public class OrderChecker implements Checker {

    /**
     * The stable name of the invariant requiring per-key delivery order to match append order.
     */
    public static final String SEQUENCE_KEY_ORDER_PRESERVED = "SequenceKeyOrderPreserved";

    /**
     * The statement of {@value #SEQUENCE_KEY_ORDER_PRESERVED}, character-identical to the invariant registry.
     */
    public static final String SEQUENCE_KEY_ORDER_PRESERVED_STATEMENT =
            "For every sequence identifier, the order in which its events are delivered to a consumer equals the "
                    + "order in which those events were appended.";

    @Override
    public String name() {
        return "OrderChecker";
    }

    @Override
    public Set<String> machineNames() {
        return Set.of(SEQUENCE_KEY_ORDER_PRESERVED);
    }

    @Override
    public CheckResult check(HistoryView history) {
        List<Operation> deliveries = history.operations(HistoryOps.DELIVER).stream()
                                            .filter(delivery -> delivery.invocation()
                                                                        .stringValue(HistoryOps.SEQUENCE_KEY) != null)
                                            .toList();
        if (deliveries.isEmpty()) {
            return CheckResult.holding(name());
        }

        List<HistoryRecord> scans = history.notes(HistoryOps.SCAN);
        if (scans.isEmpty()) {
            return new CheckResult(name(), List.of(),
                                   List.of("The run recorded no authoritative scan, so the append order the "
                                                   + "deliveries should follow is unknown."));
        }
        Map<String, Integer> appendOrder = new HashMap<>();
        List<String> stored = scans.getLast().stringListValue(DcbHistoryCodec.EVENT_IDS);
        for (int position = 0; position < stored.size(); position++) {
            appendOrder.putIfAbsent(stored.get(position), position);
        }

        List<Violation> violations = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, Integer> unplaceable = new LinkedHashMap<>();
        Map<String, Delivered> lastPerKey = new HashMap<>();
        int duplicates = 0;

        for (Operation delivery : deliveries) {
            HistoryRecord record = delivery.invocation();
            String eventId = record.stringValue(DcbHistoryCodec.EVENT_ID);
            String key = record.stringValue(HistoryOps.SEQUENCE_KEY);
            if (eventId == null || key == null) {
                continue;
            }
            if (!seen.add(eventId)) {
                duplicates++;
                continue;
            }
            Integer position = appendOrder.get(eventId);
            if (position == null) {
                unplaceable.merge(key, 1, Integer::sum);
                continue;
            }
            Delivered previous = lastPerKey.put(key, new Delivered(eventId, position, record));
            if (previous != null && previous.position() > position) {
                violations.add(Violation.of(SEQUENCE_KEY_ORDER_PRESERVED,
                                            SEQUENCE_KEY_ORDER_PRESERVED_STATEMENT,
                                            "under sequence identifier [" + key + "] event " + eventId
                                                    + " (store position " + position + ") was delivered after "
                                                    + previous.eventId() + " (store position " + previous.position()
                                                    + ")",
                                            List.of(previous.record(), record),
                                            history.header()));
                // Keep the later of the two as the reference point, so one inversion is one violation rather than a
                // cascade of them against a position the read side has already passed.
                lastPerKey.put(key, new Delivered(previous.eventId(), previous.position(), previous.record()));
            }
        }

        if (duplicates > 0) {
            notes.add(duplicates + " delivery/deliveries repeated an event already delivered; a repeat is a "
                              + "duplication rather than a reordering and is not judged here.");
        }
        if (!unplaceable.isEmpty()) {
            notes.add("Deliveries of events absent from the authoritative scan, per sequence identifier: "
                              + unplaceable + "; their place in the append order is unknown.");
        }
        return new CheckResult(name(), List.copyOf(violations), List.copyOf(notes));
    }

    private record Delivered(String eventId, int position, HistoryRecord record) {

    }
}
