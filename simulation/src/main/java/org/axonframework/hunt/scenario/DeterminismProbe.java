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

package org.axonframework.hunt.scenario;

import org.axonframework.hunt.history.HistoryOps;
import org.axonframework.hunt.history.HistoryRecord;
import org.axonframework.hunt.history.HistoryView;
import org.axonframework.hunt.history.Outcome;
import org.axonframework.hunt.model.DcbHistoryCodec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Measures what a seed actually fixes, by running it twice and diffing the two histories.
 * <p>
 * Every simulation harness claims reproducibility and most of them are wrong about how much. The only way to know is
 * to run the same seed twice and compare, which is what this does. It reports four separate answers rather than one
 * boolean, because the useful question is not whether a run is deterministic but which of its properties are:
 * <ul>
 *     <li><b>record sequence</b> -- whether the two histories are the same records in the same order, ignoring only
 *     the fields the determinism boundary already declares free (the two timestamps);</li>
 *     <li><b>operation shape</b> -- whether each process issued the same operations, the same number of times, with
 *     the same outcomes, regardless of how they interleaved;</li>
 *     <li><b>append verdicts</b> -- whether the same number of appends were accepted and rejected;</li>
 *     <li><b>store contents</b> -- whether the two runs left the same set of events behind.</li>
 * </ul>
 * A reading is a measurement, not an assertion. What the harness may claim is exactly what the probe shows and
 * nothing more; an honest "shape-stable only" is a better outcome than an overclaim, because a reproducibility claim
 * the harness cannot honour turns every real finding into an argument about the harness.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class DeterminismProbe {

    private DeterminismProbe() {
        // Utility class.
    }

    /**
     * Runs the given seed twice and compares the two histories.
     *
     * @param scenario         the scenario to run
     * @param tier             the tier whose budget applies
     * @param seed             the seed to run twice
     * @param historyDirectory the directory the two runs' histories are written to
     * @return what the two runs agreed on, and where they differed
     */
    public static Reading probe(Scenario scenario, Tier tier, long seed, Path historyDirectory) {
        Objects.requireNonNull(scenario, "The scenario cannot be null.");
        Path first = historyDirectory.resolve("run-a");
        Path second = historyDirectory.resolve("run-b");
        ScenarioResult firstRun = ScenarioRunner.run(scenario, tier, seed, ScenarioRunner.historyDirectory(first));
        ScenarioResult secondRun = ScenarioRunner.run(scenario, tier, seed, ScenarioRunner.historyDirectory(second));
        return compare(scenario, seed, HistoryView.read(firstRun.history()), HistoryView.read(secondRun.history()));
    }

    /**
     * Compares two histories of the same seed.
     *
     * @param scenario the scenario both runs used
     * @param seed     the seed both runs used
     * @param first    the first run's history
     * @param second   the second run's history
     * @return what the two runs agreed on, and where they differed
     */
    public static Reading compare(Scenario scenario, long seed, HistoryView first, HistoryView second) {
        List<String> differences = new ArrayList<>();

        boolean sequenceIdentical = first.records().size() == second.records().size();
        if (!sequenceIdentical) {
            differences.add("record count differs: " + first.records().size() + " against "
                                    + second.records().size());
        } else {
            for (int index = 0; index < first.records().size(); index++) {
                String left = signature(first.records().get(index));
                String right = signature(second.records().get(index));
                if (!left.equals(right)) {
                    sequenceIdentical = false;
                    differences.add("record #" + index + " differs: " + left + " against " + right);
                    break;
                }
            }
        }

        Map<String, Integer> firstShape = shape(first);
        Map<String, Integer> secondShape = shape(second);
        boolean shapeIdentical = firstShape.equals(secondShape);
        if (!shapeIdentical) {
            differences.add("operation shape differs: " + difference(firstShape, secondShape));
        }

        Map<String, Integer> firstVerdicts = appendVerdicts(first);
        Map<String, Integer> secondVerdicts = appendVerdicts(second);
        boolean verdictsIdentical = firstVerdicts.equals(secondVerdicts);
        if (!verdictsIdentical) {
            differences.add("append verdicts differ: " + firstVerdicts + " against " + secondVerdicts);
        }

        Set<String> firstStore = storedEvents(first);
        Set<String> secondStore = storedEvents(second);
        boolean storeIdentical = firstStore.equals(secondStore);
        if (!storeIdentical) {
            Set<String> onlyInFirst = new TreeSet<>(firstStore);
            onlyInFirst.removeAll(secondStore);
            Set<String> onlyInSecond = new TreeSet<>(secondStore);
            onlyInSecond.removeAll(firstStore);
            differences.add("store contents differ: " + onlyInFirst.size() + " event(s) only in the first run, "
                                    + onlyInSecond.size() + " only in the second");
        }

        return new Reading(scenario.id(), seed, scenario.determinism().name(), sequenceIdentical, shapeIdentical,
                           verdictsIdentical, storeIdentical, List.copyOf(differences));
    }

    private static String signature(HistoryRecord record) {
        return record.idx() + "|" + record.process() + "|" + record.op() + "|" + record.type() + "|"
                + record.key() + "|" + record.error();
    }

    private static Map<String, Integer> shape(HistoryView history) {
        Map<String, Integer> counts = new TreeMap<>();
        history.records().forEach(record ->
                counts.merge(record.process() + "/" + record.op() + "/" + record.type(), 1, Integer::sum));
        return counts;
    }

    private static Map<String, Integer> appendVerdicts(HistoryView history) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Outcome outcome : Outcome.values()) {
            counts.put(outcome.name(), 0);
        }
        history.operations(HistoryOps.APPEND)
               .forEach(append -> counts.merge(append.outcome().name(), 1, Integer::sum));
        return counts;
    }

    private static Set<String> storedEvents(HistoryView history) {
        List<HistoryRecord> scans = history.notes(HistoryOps.SCAN);
        return scans.isEmpty() ? Set.of() : Set.copyOf(scans.getLast().stringListValue(DcbHistoryCodec.EVENT_IDS));
    }

    private static Map<String, String> difference(Map<String, Integer> first, Map<String, Integer> second) {
        Map<String, String> differences = new LinkedHashMap<>();
        Set<String> keys = new TreeSet<>(first.keySet());
        keys.addAll(second.keySet());
        keys.forEach(key -> {
            Integer left = first.get(key);
            Integer right = second.get(key);
            if (!Objects.equals(left, right)) {
                differences.put(key, left + " against " + right);
            }
        });
        return Map.copyOf(differences);
    }

    /**
     * What two runs of one seed agreed on.
     *
     * @param scenarioId              the scenario both runs used
     * @param seed                    the seed both runs used
     * @param determinism             the determinism mode both runs used
     * @param recordSequenceIdentical whether the two histories are the same records in the same order
     * @param shapeIdentical          whether each process issued the same operations the same number of times
     * @param appendVerdictsIdentical whether the same number of appends were accepted, rejected and left unknown
     * @param storeContentsIdentical  whether the two runs left the same set of events in the store
     * @param differences             where the two runs differed, in the order the probe found them
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public record Reading(String scenarioId,
                          long seed,
                          String determinism,
                          boolean recordSequenceIdentical,
                          boolean shapeIdentical,
                          boolean appendVerdictsIdentical,
                          boolean storeContentsIdentical,
                          List<String> differences) {

        /**
         * Compact constructor defensively copying the differences.
         */
        public Reading {
            differences = List.copyOf(Objects.requireNonNull(differences, "The differences cannot be null."));
        }

        /**
         * Renders the reading as a report.
         *
         * @return a multi-line rendering of what held and what did not
         */
        @Override
        public String toString() {
            String newLine = System.lineSeparator();
            StringBuilder rendering = new StringBuilder("Determinism probe of ").append(scenarioId)
                                                                                .append(" seed ").append(seed)
                                                                                .append(" in ").append(determinism)
                                                                                .append(" mode:")
                    .append(newLine).append("  record sequence identical: ").append(recordSequenceIdentical)
                    .append(newLine).append("  operation shape identical: ").append(shapeIdentical)
                    .append(newLine).append("  append verdicts identical: ").append(appendVerdictsIdentical)
                    .append(newLine).append("  store contents identical:  ").append(storeContentsIdentical);
            differences.forEach(difference -> rendering.append(newLine).append("  difference: ").append(difference));
            return rendering.toString();
        }
    }
}
