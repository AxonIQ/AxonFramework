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
import org.jspecify.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes a hunt run's operation history as JSON Lines.
 * <p>
 * The recorder is thread-safe and append-only. Its contract is the part of the suite everything else leans on:
 * <ul>
 *     <li>the header is written first, so a history is reproducible from itself;</li>
 *     <li>an operation's invocation and its completion are separate records, joined by a correlation identifier,
 *     so an operation that never completes still leaves a trace;</li>
 *     <li>records are numbered from a single counter, so the history has one unambiguous order;</li>
 *     <li>nothing is buffered past {@link #close()}.</li>
 * </ul>
 * Records are serialized outside the write lock and written inside it; the lock is held only for the duration of one
 * {@code write} call, so recording does not measurably serialize the workload it observes. File order may therefore
 * differ from {@link HistoryRecord#idx()} order under contention, which is why {@link HistoryView} sorts on read.
 * <p>
 * Example usage:
 * <pre>{@code
 * HistoryHeader header = HistoryHeader.of("s1-dcb-append", 42L, "in-memory", "compressed", Map.of("writers", "4"));
 * try (HistoryRecorder recorder = HistoryRecorder.writingTo(path, header)) {
 *     HistoryRecorder.ProcessRecorder writer = recorder.forProcess("writer-0", "node-a");
 *     HistoryRecorder.Invocation append = writer.invoke(HistoryOps.APPEND, "acct:a1", args);
 *     append.ok(Map.of("marker", 7L));
 * }
 * }</pre>
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class HistoryRecorder implements AutoCloseable {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final Writer out;
    private final Object writeLock = new Object();
    private final AtomicLong nextIdx = new AtomicLong();
    private final AtomicLong nextCorrelation = new AtomicLong();
    private final long originNanos = System.nanoTime();

    private volatile @Nullable String faultEpoch;

    private HistoryRecorder(Writer out, HistoryHeader header) {
        this.out = out;
        writeLine(header);
    }

    /**
     * Creates a recorder writing to the given {@code file}, replacing any file already there.
     *
     * @param file   the file to write the history to; parent directories are created when missing
     * @param header the header describing the run, written as the first line
     * @return a recorder ready to accept operations
     * @throws UncheckedIOException if the file cannot be opened
     */
    public static HistoryRecorder writingTo(Path file, HistoryHeader header) {
        Objects.requireNonNull(file, "The file cannot be null.");
        Objects.requireNonNull(header, "The header cannot be null.");
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            return new HistoryRecorder(writer, header);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to open history file [" + file + "].", e);
        }
    }

    /**
     * Declares the fault window every subsequent record is written under.
     * <p>
     * Without this, a checker cannot tell whether an anomaly happened under fault or in the clear, which is the
     * difference between a finding and a footnote.
     *
     * @param epoch the identifier of the active fault window, or {@code null} once it has healed
     */
    public void faultEpoch(@Nullable String epoch) {
        this.faultEpoch = epoch;
    }

    /**
     * Binds a process identity to this recorder.
     *
     * @param process the client, thread or session issuing the operations
     * @param node    the node those operations are routed to, or {@code null} when the run is single-node
     * @return a recorder stamping every record with the given identity
     */
    public ProcessRecorder forProcess(String process, @Nullable String node) {
        return new ProcessRecorder(Objects.requireNonNull(process, "The process cannot be null."), node);
    }

    /**
     * Flushes and closes the underlying file. Safe to call once; subsequent writes fail.
     */
    @Override
    public void close() {
        synchronized (writeLock) {
            try {
                out.flush();
                out.close();
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to close the history file.", e);
            }
        }
    }

    private void writeLine(Object value) {
        String line;
        try {
            line = MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to serialize a history line.", e);
        }
        synchronized (writeLock) {
            try {
                out.write(line);
                out.write('\n');
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to write a history line.", e);
            }
        }
    }

    private void emit(String process,
                      @Nullable String node,
                      String op,
                      RecordType type,
                      String correlationId,
                      @Nullable String key,
                      Map<String, Object> value,
                      @Nullable String error) {
        writeLine(new HistoryRecord(nextIdx.getAndIncrement(),
                                    System.nanoTime() - originNanos,
                                    Instant.now().toEpochMilli(),
                                    process,
                                    node,
                                    op,
                                    type,
                                    correlationId,
                                    key,
                                    value,
                                    error,
                                    faultEpoch));
    }

    /**
     * Records operations on behalf of one process.
     * <p>
     * Obtained from {@link HistoryRecorder#forProcess(String, String)}. Instances are thread-safe, but a writer per
     * workload thread keeps {@link HistoryRecord#process()} meaningful.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public final class ProcessRecorder {

        private final String process;
        private final @Nullable String node;

        private ProcessRecorder(String process, @Nullable String node) {
            this.process = process;
            this.node = node;
        }

        /**
         * Records that an operation was issued.
         *
         * @param op    the operation name; see {@link HistoryOps}
         * @param key   the object the operation addresses, or {@code null}
         * @param value the operation's arguments
         * @return a handle used to record the operation's outcome
         */
        public Invocation invoke(String op, @Nullable String key, Map<String, Object> value) {
            Objects.requireNonNull(op, "The op cannot be null.");
            String correlationId = "op-" + nextCorrelation.getAndIncrement();
            emit(process, node, op, RecordType.INVOKE, correlationId, key, value, null);
            return new Invocation(process, node, op, correlationId, key);
        }

        /**
         * Records a standalone note that is not the outcome of an invocation, such as a post-run store scan or
         * evidence that a fault landed.
         *
         * @param op    the operation name; see {@link HistoryOps}
         * @param key   the object the note concerns, or {@code null}
         * @param value the note's payload
         */
        public void info(String op, @Nullable String key, Map<String, Object> value) {
            Objects.requireNonNull(op, "The op cannot be null.");
            emit(process, node, op, RecordType.INFO, "note-" + nextCorrelation.getAndIncrement(), key, value, null);
        }
    }

    /**
     * A handle to an operation whose outcome is not yet recorded.
     * <p>
     * Exactly one of {@link #ok(Map)}, {@link #fail(String, Map)} or {@link #indeterminate(String, Map)} should be
     * called. Calling none is legitimate and is how a run leaves an operation open at its boundary; the operation
     * then surfaces as an unknown rather than disappearing.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public final class Invocation {

        private final String process;
        private final @Nullable String node;
        private final String op;
        private final String correlationId;
        private final @Nullable String key;

        private Invocation(String process,
                           @Nullable String node,
                           String op,
                           String correlationId,
                           @Nullable String key) {
            this.process = process;
            this.node = node;
            this.op = op;
            this.correlationId = correlationId;
            this.key = key;
        }

        /**
         * Returns the identifier joining this invocation to its completion.
         *
         * @return the correlation identifier
         */
        public String correlationId() {
            return correlationId;
        }

        /**
         * Records that the operation succeeded.
         *
         * @param value the operation's result
         */
        public void ok(Map<String, Object> value) {
            emit(process, node, op, RecordType.OK, correlationId, key, value, null);
        }

        /**
         * Records that the operation definitely failed and did not take effect.
         *
         * @param error the error the operation reported
         * @param value any diagnostic detail
         */
        public void fail(String error, Map<String, Object> value) {
            emit(process, node, op, RecordType.FAIL, correlationId, key, value, error);
        }

        /**
         * Records that the operation's outcome is unknown: it may or may not have taken effect.
         *
         * @param error the error or timeout that left the outcome unknown
         * @param value any diagnostic detail
         */
        public void indeterminate(String error, Map<String, Object> value) {
            emit(process, node, op, RecordType.INFO, correlationId, key, value, error);
        }
    }
}
