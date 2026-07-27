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

package org.axonframework.hunt.fault;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The proof that one fault actually fired, and what it fired against.
 * <p>
 * A fault that was declared but never perturbed anything has verified nothing, so this counter is not an optional
 * diagnostic: the runner reads it to decide whether the run may be reported as a pass at all. Every fault increments
 * it at the moment it takes effect, naming the thing it took effect on, so that a reader can tell an append rejection
 * aimed at one writer from one that hit every writer.
 * <p>
 * Instances are thread-safe: faults fire from whichever workload thread reached the fault point.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class FaultEvidence {

    private static final int TARGET_SAMPLE_LIMIT = 8;

    private final String kind;
    private final Map<String, String> parameters;
    private final AtomicLong fires = new AtomicLong();
    private final AtomicReference<List<String>> targets = new AtomicReference<>(List.of());

    /**
     * Creates evidence for a fault of the given kind.
     *
     * @param kind       the fault's kind, as reported by {@link Fault#kind()}
     * @param parameters the fault's parameters, recorded so a reader knows what was declared
     */
    public FaultEvidence(String kind, Map<String, String> parameters) {
        this.kind = Objects.requireNonNull(kind, "The kind cannot be null.");
        this.parameters = Map.copyOf(Objects.requireNonNull(parameters, "The parameters cannot be null."));
    }

    /**
     * Records that the fault took effect once, against the named target.
     *
     * @param target what the fault perturbed: a writer, an event identifier, a segment
     */
    public void fired(String target) {
        Objects.requireNonNull(target, "The target cannot be null.");
        fires.incrementAndGet();
        targets.updateAndGet(current -> {
            if (current.size() >= TARGET_SAMPLE_LIMIT || current.contains(target)) {
                return current;
            }
            List<String> extended = new java.util.ArrayList<>(current);
            extended.add(target);
            return List.copyOf(extended);
        });
    }

    /**
     * Returns the fault's kind.
     *
     * @return the kind, as reported by {@link Fault#kind()}
     */
    public String kind() {
        return kind;
    }

    /**
     * Returns how often the fault took effect.
     *
     * @return the fire count; zero means the fault never landed
     */
    public long fires() {
        return fires.get();
    }

    /**
     * Renders the evidence as a history record's value.
     *
     * @return the kind, the parameters, the fire count and a sample of the targets
     */
    public Map<String, Object> asRecordValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", kind);
        value.put("fires", fires.get());
        value.put("targets", targets.get());
        parameters.forEach((name, setting) -> value.put("param." + name, setting));
        return Map.copyOf(value);
    }

    @Override
    public String toString() {
        return kind + " fired " + fires.get() + " time(s) against " + targets.get();
    }
}
