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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One line of a hunt run's operation history.
 * <p>
 * Every operation produces at least two records: one {@link RecordType#INVOKE} when it is issued and one completion
 * ({@link RecordType#OK}, {@link RecordType#FAIL} or {@link RecordType#INFO}) when its outcome is known, joined by
 * {@link #id()}. An operation whose completion never arrives keeps its invocation record and is surfaced by
 * {@link HistoryView} as an unknown; it is never dropped.
 * <p>
 * Fields are added over time, never repurposed. Unknown properties are ignored on read, so a history written by an
 * older run stays readable.
 *
 * @param idx        strictly increasing sequence number assigned by the recorder; defines the history's order
 * @param logicalTs  the harness's logical clock reading, in nanoseconds since the recorder was created
 * @param wallTs     wall-clock reading, in milliseconds since the epoch, for correlating with external evidence
 * @param process    the client, thread or session that issued the operation
 * @param node       the node the operation was routed to, or {@code null} when the run is single-node
 * @param op         the operation name; see {@link HistoryOps} for the names in use
 * @param type       whether this record is the invocation or a completion, and which completion
 * @param id         the correlation identifier joining an invocation to its completion
 * @param key        the object the operation addressed (a tag, a segment, an account), or {@code null}
 * @param value      operation-specific payload: arguments on the invocation, results on the completion
 * @param error      the error the operation reported, or {@code null}
 * @param faultEpoch the identifier of the fault window active when the record was written, or {@code null}
 * @author Stefan Dragisic
 * @since 5.3.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HistoryRecord(
        long idx,
        long logicalTs,
        long wallTs,
        String process,
        @Nullable String node,
        String op,
        RecordType type,
        String id,
        @Nullable String key,
        Map<String, Object> value,
        @Nullable String error,
        @Nullable String faultEpoch
) {

    /**
     * Compact constructor rejecting the fields a checker cannot work without and defaulting the value map.
     */
    public HistoryRecord {
        Objects.requireNonNull(process, "The process cannot be null.");
        Objects.requireNonNull(op, "The op cannot be null.");
        Objects.requireNonNull(type, "The type cannot be null.");
        Objects.requireNonNull(id, "The id cannot be null.");
        value = value == null ? Map.of() : Map.copyOf(value);
    }

    /**
     * Returns a copy of this record with the given {@code idx}.
     * <p>
     * Used by the recorder, which assigns sequence numbers centrally.
     *
     * @param newIdx the sequence number to stamp on the copy
     * @return a copy of this record carrying the given sequence number
     */
    @JsonIgnore
    public HistoryRecord withIdx(long newIdx) {
        return new HistoryRecord(newIdx, logicalTs, wallTs, process, node, op, type, id, key, value, error,
                                 faultEpoch);
    }

    /**
     * Returns the {@link #value()} entry under {@code name} as a string.
     *
     * @param name the value key to read
     * @return the entry as a string, or {@code null} when absent
     */
    @JsonIgnore
    public @Nullable String stringValue(String name) {
        Object raw = value.get(name);
        return raw == null ? null : String.valueOf(raw);
    }

    /**
     * Returns the {@link #value()} entry under {@code name} as a long.
     * <p>
     * JSON does not distinguish integral widths, so any {@link Number} is accepted.
     *
     * @param name         the value key to read
     * @param defaultValue the value to return when the entry is absent or not numeric
     * @return the entry as a long, or {@code defaultValue}
     */
    @JsonIgnore
    public long longValue(String name, long defaultValue) {
        Object raw = value.get(name);
        return raw instanceof Number number ? number.longValue() : defaultValue;
    }

    /**
     * Returns the {@link #value()} entry under {@code name} as a list of strings.
     *
     * @param name the value key to read
     * @return the entry as a list of strings, empty when absent or not a list
     */
    @JsonIgnore
    public List<String> stringListValue(String name) {
        Object raw = value.get(name);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    /**
     * Indicates whether this record completes an operation rather than starting one.
     *
     * @return {@code true} when this record is not an {@link RecordType#INVOKE}
     */
    @JsonIgnore
    public boolean isCompletion() {
        return type != RecordType.INVOKE;
    }
}
