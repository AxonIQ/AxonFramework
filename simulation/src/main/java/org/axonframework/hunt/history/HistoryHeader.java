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

import java.util.Map;
import java.util.Objects;

/**
 * The first line of every history file: everything needed to reproduce the run that wrote it.
 * <p>
 * A history is self-describing on purpose. Reading the header tells you which scenario ran, against which backend, at
 * which timescale, with which seed and which workload shape, so a violation found weeks later can be replayed without
 * consulting any other artefact.
 *
 * @param schemaVersion the history schema version this file was written against; see {@link #CURRENT_SCHEMA_VERSION}
 * @param scenarioId    the identifier of the scenario that produced the run
 * @param seed          the seed that fixes the workload shape and the fault schedule
 * @param backend       the store the run was driven against, for example {@code in-memory} or {@code postgres-jpa}
 * @param timescale     the timescale arm, for example {@code compressed} or {@code realistic}
 * @param workloadShape the workload's shape knobs, as a flat map of name to rendered value
 * @author Stefan Dragisic
 * @since 5.3.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HistoryHeader(
        int schemaVersion,
        String scenarioId,
        long seed,
        String backend,
        String timescale,
        Map<String, String> workloadShape
) {

    /**
     * The schema version this build writes. Bumped only when a field changes meaning; adding a field does not bump it.
     */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * The workload-shape key recording how much of a run's scheduling was pinned down.
     */
    private static final String DETERMINISM = "determinism";

    /**
     * The determinism mode under which a seed fixes the workload's shape and nothing about the thread schedule.
     */
    private static final String RE_SAMPLING_DETERMINISM = "REAL_THREADS";

    /**
     * Compact constructor defaulting the workload shape and rejecting missing identity fields.
     */
    public HistoryHeader {
        Objects.requireNonNull(scenarioId, "The scenarioId cannot be null.");
        Objects.requireNonNull(backend, "The backend cannot be null.");
        Objects.requireNonNull(timescale, "The timescale cannot be null.");
        workloadShape = workloadShape == null ? Map.of() : Map.copyOf(workloadShape);
    }

    /**
     * Creates a header at the current schema version.
     *
     * @param scenarioId    the identifier of the scenario producing the run
     * @param seed          the seed fixing workload shape and fault schedule
     * @param backend       the store the run is driven against
     * @param timescale     the timescale arm
     * @param workloadShape the workload's shape knobs
     * @return a header stamped with {@link #CURRENT_SCHEMA_VERSION}
     */
    public static HistoryHeader of(String scenarioId,
                                   long seed,
                                   String backend,
                                   String timescale,
                                   Map<String, String> workloadShape) {
        return new HistoryHeader(CURRENT_SCHEMA_VERSION, scenarioId, seed, backend, timescale, workloadShape);
    }

    /**
     * Returns the command that runs this scenario at this seed again, and says plainly what that buys.
     * <p>
     * Every violation carries this string so that a report is actionable without the reader knowing the harness. What
     * the string must not do is overclaim. A run whose scheduling was left to real threads is not reproduced by its
     * seed: the seed fixes which operations are attempted and nothing about which of them win their races, so
     * re-running it draws a fresh sample of the same shape and may well come back clean. For those runs the command
     * is annotated as a re-sample, and the reader is pointed at the recorded history, which is the only exact record
     * of the run that broke and can be re-judged with {@code -Dhunt.history=<the file>}.
     *
     * @return a copy-pasteable Maven command, annotated when re-running it re-samples rather than replays
     */
    @JsonIgnore
    public String reproduceCommand() {
        String command = "./mvnw -Phunt -pl simulation -am test -Dtest=HuntReproduceTest"
                + " -Dhunt.scenario=" + scenarioId
                + " -Dhunt.seed=" + seed
                + " -Dhunt.backend=" + backend
                + " -Dhunt.timescale=" + timescale;
        if (RE_SAMPLING_DETERMINISM.equals(workloadShape.get(DETERMINISM))) {
            return command + " (re-samples the schedule; it does NOT replay this run -- to re-judge this exact run "
                    + "offline, run -Dtest=HistoryReplayTest -Dhunt.history=<the history file this violation names>)";
        }
        return command;
    }
}
