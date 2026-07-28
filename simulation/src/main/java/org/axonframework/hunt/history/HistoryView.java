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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A recorded history, read back and resolved into operations.
 * <p>
 * This is the only thing checkers consume. It exists so that every checker resolves outcomes the same way and none of
 * them can quietly drop the operations that were still in flight when the run ended, which is the single most common
 * source of findings that are not real.
 * <p>
 * Resolution rules:
 * <ul>
 *     <li>Records are ordered by {@link HistoryRecord#idx()}, not by their position in the file.</li>
 *     <li>Every {@link RecordType#INVOKE} produces exactly one {@link Operation}, in invocation order.</li>
 *     <li>A completion is matched to an invocation by {@link HistoryRecord#id()}.</li>
 *     <li>An invocation with no completion keeps its operation, with outcome {@link Outcome#UNKNOWN}.</li>
 *     <li>An {@link RecordType#INFO} record whose identifier matches no invocation is a standalone note, reported by
 *     {@link #notes()} rather than folded into an operation.</li>
 *     <li>A completion whose identifier matches no invocation is reported by {@link #unpairedCompletions()} rather
 *     than discarded; it means the recorder or the run lost the invocation.</li>
 * </ul>
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class HistoryView {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final HistoryHeader header;
    private final List<HistoryRecord> records;
    private final List<Operation> operations;
    private final List<HistoryRecord> notes;
    private final List<HistoryRecord> unpairedCompletions;

    private HistoryView(HistoryHeader header, List<HistoryRecord> records) {
        this.header = header;
        this.records = List.copyOf(records);

        Map<String, HistoryRecord> completionsById = new HashMap<>();
        List<HistoryRecord> invocations = new ArrayList<>();
        List<HistoryRecord> standaloneNotes = new ArrayList<>();
        for (HistoryRecord record : this.records) {
            if (record.type() == RecordType.INVOKE) {
                invocations.add(record);
            } else {
                completionsById.put(record.id(), record);
            }
        }

        List<Operation> resolved = new ArrayList<>(invocations.size());
        for (HistoryRecord invocation : invocations) {
            resolved.add(new Operation(invocation, completionsById.remove(invocation.id())));
        }
        List<HistoryRecord> orphans = new ArrayList<>();
        for (HistoryRecord leftover : completionsById.values()) {
            if (leftover.type() == RecordType.INFO) {
                standaloneNotes.add(leftover);
            } else {
                orphans.add(leftover);
            }
        }
        standaloneNotes.sort(Comparator.comparingLong(HistoryRecord::idx));
        orphans.sort(Comparator.comparingLong(HistoryRecord::idx));

        this.operations = List.copyOf(resolved);
        this.notes = List.copyOf(standaloneNotes);
        this.unpairedCompletions = List.copyOf(orphans);
    }

    /**
     * Reads a history file written by {@link HistoryRecorder}.
     *
     * @param file the JSON Lines file to read
     * @return a view over the recorded history
     * @throws UncheckedIOException     if the file cannot be read or a line cannot be parsed
     * @throws IllegalArgumentException if the file is empty and therefore carries no header
     */
    public static HistoryView read(Path file) {
        Objects.requireNonNull(file, "The file cannot be null.");
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            return of(lines);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read history file [" + file + "].", e);
        }
    }

    /**
     * Builds a view from already-read JSON Lines.
     * <p>
     * The first line is the header; every following non-blank line is a record.
     *
     * @param lines the history's lines, header first
     * @return a view over the recorded history
     * @throws IllegalArgumentException if there are no lines, and therefore no header
     */
    public static HistoryView of(List<String> lines) {
        Objects.requireNonNull(lines, "The lines cannot be null.");
        List<String> content = lines.stream().filter(line -> !line.isBlank()).toList();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("A history must start with a header line, but it was empty.");
        }
        try {
            HistoryHeader header = MAPPER.readValue(content.getFirst(), HistoryHeader.class);
            List<HistoryRecord> records = new ArrayList<>(content.size() - 1);
            for (String line : content.subList(1, content.size())) {
                records.add(MAPPER.readValue(line, HistoryRecord.class));
            }
            records.sort(Comparator.comparingLong(HistoryRecord::idx));
            return new HistoryView(header, records);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to parse the history.", e);
        }
    }

    /**
     * Returns the run's header.
     *
     * @return the header, carrying the scenario, seed, backend, timescale and workload shape
     */
    public HistoryHeader header() {
        return header;
    }

    /**
     * Returns every record, ordered by {@link HistoryRecord#idx()}.
     *
     * @return all records, nothing filtered and nothing truncated
     */
    public List<HistoryRecord> records() {
        return records;
    }

    /**
     * Returns every operation, in invocation order.
     *
     * @return one operation per invocation, including those that never completed
     */
    public List<Operation> operations() {
        return operations;
    }

    /**
     * Returns the operations with the given name, in invocation order.
     *
     * @param op the operation name to filter on; see {@link HistoryOps}
     * @return the matching operations
     */
    public List<Operation> operations(String op) {
        return operations.stream().filter(operation -> operation.op().equals(op)).toList();
    }

    /**
     * Returns the operations whose effect the history cannot decide.
     *
     * @return the operations with outcome {@link Outcome#UNKNOWN}
     */
    public List<Operation> unknowns() {
        return operations.stream().filter(operation -> operation.outcome() == Outcome.UNKNOWN).toList();
    }

    /**
     * Returns the standalone notes: records that carry evidence rather than an operation's outcome, such as a
     * post-run store scan or proof that a fault landed.
     *
     * @return the standalone {@link RecordType#INFO} records, in recorded order
     */
    public List<HistoryRecord> notes() {
        return notes;
    }

    /**
     * Returns the standalone notes with the given name.
     *
     * @param op the operation name to filter on; see {@link HistoryOps}
     * @return the matching notes, in recorded order
     */
    public List<HistoryRecord> notes(String op) {
        return notes.stream().filter(note -> note.op().equals(op)).toList();
    }

    /**
     * Returns completions whose invocation is missing.
     * <p>
     * A non-empty result means the history is damaged; a checker reading it should report its verdict as
     * inconclusive rather than assert against it.
     *
     * @return the unmatched {@link RecordType#OK} and {@link RecordType#FAIL} records
     */
    public List<HistoryRecord> unpairedCompletions() {
        return unpairedCompletions;
    }

    /**
     * Indicates whether this run rebuilt its segment set, by carrying out a split or a merge.
     * <p>
     * <b>This lives here, once, because five separate oracles need it and a sixth will be written without it.</b> A
     * segment-set rebuild is a property of the run, not of any one invariant, and every signal derived from operation
     * records has to be told about it: a split deletes one token row and creates two, a merge deletes one of a pair and
     * rewrites the other with the lower of their tokens, and the framework blocks local re-claim of a split segment for a
     * hardcoded sixty seconds. So a segment identifier stops naming one unit of work, a stored token goes backwards by
     * design, an ownership interval runs straight through the rebuild, and a read side that has stopped may simply be
     * waiting. Each of those was learned separately and each cost a finding that was not real.
     * <p>
     * Re-deriving it per checker is how the sixth one silently decides something it cannot decide. Ask this instead.
     *
     * @return {@code true} when the framework carried out a split or a merge during the run
     */
    public boolean rebuiltSegments() {
        for (String instruction : List.of(HistoryOps.SPLIT, HistoryOps.MERGE)) {
            for (Operation change : operations(instruction)) {
                HistoryRecord completion = change.completion();
                if (completion != null && "true".equals(completion.stringValue(HistoryOps.CARRIED_OUT))) {
                    return true;
                }
            }
        }
        return false;
    }
}
