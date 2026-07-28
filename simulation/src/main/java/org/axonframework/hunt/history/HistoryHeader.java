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
 * <b>A run's meaning depends on a version combination, so the combination is recorded as data.</b> A backend is not one
 * thing: it is this reactor crossed with whatever client library reaches the store and whatever version of the store
 * answers. The block that stopped this suite covering Axon Server for a whole phase was not a test problem at all -- it
 * was an abstract method added to a storage-engine interface that the released connector had not implemented yet, which
 * {@code javac} accepts and the JVM refuses. A verdict from such an arm is unreadable without knowing which combination
 * produced it, and an argument about whether a divergence is the framework's or the skew's is unwinnable without it. The
 * {@code versions} map therefore travels with every history, so attribution is mechanical rather than a discussion.
 *
 * @param schemaVersion the history schema version this file was written against; see {@link #CURRENT_SCHEMA_VERSION}
 * @param scenarioId    the identifier of the scenario that produced the run
 * @param seed          the seed that fixes the workload shape and the fault schedule
 * @param backend       the store the run was driven against, for example {@code in-memory} or {@code postgres-jpa}
 * @param timescale     the timescale arm, for example {@code compressed} or {@code realistic}
 * @param workloadShape the workload's shape knobs, as a flat map of name to rendered value
 * @param versions      the version combination the run's meaning depends on: the framework, the client library reaching
 *                      the store when there is one, the store's own image tag when there is one, and the harness methods
 *                      shimmed to make the combination link at all
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
        Map<String, String> workloadShape,
        Map<String, String> versions
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
     * Compact constructor defaulting the maps and rejecting missing identity fields.
     * <p>
     * {@code versions} defaults to empty rather than being rejected, because a history written before the field existed
     * has none and must stay readable: the schema's rule is that fields are added and never repurposed.
     */
    public HistoryHeader {
        Objects.requireNonNull(scenarioId, "The scenarioId cannot be null.");
        Objects.requireNonNull(backend, "The backend cannot be null.");
        Objects.requireNonNull(timescale, "The timescale cannot be null.");
        workloadShape = workloadShape == null ? Map.of() : Map.copyOf(workloadShape);
        versions = versions == null ? Map.of() : Map.copyOf(versions);
    }

    /**
     * Creates a header at the current schema version, recording no version combination.
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
        return of(scenarioId, seed, backend, timescale, workloadShape, Map.of());
    }

    /**
     * Creates a header at the current schema version, recording the version combination the run's meaning depends on.
     *
     * @param scenarioId    the identifier of the scenario producing the run
     * @param seed          the seed fixing workload shape and fault schedule
     * @param backend       the store the run is driven against
     * @param timescale     the timescale arm
     * @param workloadShape the workload's shape knobs
     * @param versions      the framework, client-library, store-image and shimmed-method facts of the combination
     * @return a header stamped with {@link #CURRENT_SCHEMA_VERSION}
     */
    public static HistoryHeader of(String scenarioId,
                                   long seed,
                                   String backend,
                                   String timescale,
                                   Map<String, String> workloadShape,
                                   Map<String, String> versions) {
        return new HistoryHeader(CURRENT_SCHEMA_VERSION, scenarioId, seed, backend, timescale, workloadShape,
                                 versions);
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
