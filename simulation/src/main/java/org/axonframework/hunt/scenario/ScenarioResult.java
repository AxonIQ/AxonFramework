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

import org.axonframework.hunt.checker.CheckResult;
import org.axonframework.hunt.checker.Violation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What one seed of one scenario concluded, with everything needed to act on it.
 * <p>
 * A result that says only "failed" costs its reader an hour. This one carries the seed, the fault trace, the history
 * file and a command that replays the run, so that acting on it starts with running one line.
 *
 * @param scenarioId       the scenario that produced this result
 * @param seed             the seed it ran
 * @param tier             the tier it ran at
 * @param verdict          what it concluded
 * @param violations       every invariant found broken, across every checker
 * @param notes            everything that stopped the run being decisive
 * @param measurements     facts the run produced that the history accounts for; they do not move the verdict
 * @param notApplicable    the invariants this run cannot express, named so that a gap is never read as a pass
 * @param faultFires       how often each declared fault actually fired
 * @param results          the per-checker verdicts, for a full report
 * @param history          the history file the run wrote
 * @param wallTime         how long the run took
 * @param reproduceCommand the command that replays it
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public record ScenarioResult(String scenarioId,
                             long seed,
                             Tier tier,
                             Verdict verdict,
                             List<Violation> violations,
                             List<String> notes,
                             List<String> measurements,
                             List<String> notApplicable,
                             Map<String, Long> faultFires,
                             List<CheckResult> results,
                             Path history,
                             Duration wallTime,
                             String reproduceCommand) {

    private static final int RENDER_LIMIT = 5;

    /**
     * Compact constructor rejecting missing parts and defensively copying every collection.
     */
    public ScenarioResult {
        Objects.requireNonNull(scenarioId, "The scenarioId cannot be null.");
        Objects.requireNonNull(tier, "The tier cannot be null.");
        Objects.requireNonNull(verdict, "The verdict cannot be null.");
        Objects.requireNonNull(history, "The history cannot be null.");
        Objects.requireNonNull(wallTime, "The wallTime cannot be null.");
        Objects.requireNonNull(reproduceCommand, "The reproduceCommand cannot be null.");
        violations = List.copyOf(Objects.requireNonNull(violations, "The violations cannot be null."));
        notes = List.copyOf(Objects.requireNonNull(notes, "The notes cannot be null."));
        measurements = List.copyOf(Objects.requireNonNull(measurements, "The measurements cannot be null."));
        notApplicable = List.copyOf(Objects.requireNonNull(notApplicable, "The notApplicable cannot be null."));
        faultFires = Map.copyOf(Objects.requireNonNull(faultFires, "The faultFires cannot be null."));
        results = List.copyOf(Objects.requireNonNull(results, "The results cannot be null."));
    }

    /**
     * Indicates whether the run may be reported as a pass.
     *
     * @return {@code true} only for {@link Verdict#PASS}
     */
    public boolean passed() {
        return verdict == Verdict.PASS;
    }

    /**
     * Renders the result as a report.
     *
     * @return a multi-line rendering naming the verdict, every violation, every note, the fault trace, and the
     *     command that replays the run
     */
    @Override
    public String toString() {
        String newLine = System.lineSeparator();
        StringBuilder rendering = new StringBuilder()
                .append(verdict).append(' ').append(scenarioId)
                .append(" [tier=").append(tier)
                .append(", seed=").append(seed)
                .append(", wall=").append(wallTime.toMillis()).append("ms]");
        render(rendering, newLine, "violation", violations.stream().map(Object::toString).toList());
        render(rendering, newLine, "note", notes);
        render(rendering, newLine, "measured", measurements);
        render(rendering, newLine, "n/a", notApplicable);
        rendering.append(newLine).append("  faults: ").append(faultFires.isEmpty() ? "none declared" : faultFires);
        rendering.append(newLine).append("  history: ").append(history);
        rendering.append(newLine).append("  reproduce: ").append(reproduceCommand);
        return rendering.toString();
    }

    private static void render(StringBuilder rendering, String newLine, String label, List<String> lines) {
        // A report nobody reads is a report nobody acts on, so the rendering is capped. Nothing is dropped: the
        // lists themselves are complete, and a reader wanting all of them has them on the record.
        int shown = Math.min(lines.size(), RENDER_LIMIT);
        for (int index = 0; index < shown; index++) {
            rendering.append(newLine).append("  ").append(label).append(": ").append(lines.get(index));
        }
        if (lines.size() > shown) {
            rendering.append(newLine).append("  ").append(label).append(": ... and ")
                     .append(lines.size() - shown).append(" more");
        }
    }
}
