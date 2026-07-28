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

import java.util.List;
import java.util.Objects;

/**
 * What one checker concluded about one history.
 * <p>
 * A checker reports three things, not two. It can hold, it can be broken, and it can be unable to decide, because the
 * history contained operations whose outcome is unknown. Folding the third into either of the other two is how a
 * suite produces confident answers it has not earned: a run that could not decide is inconclusive, never a pass.
 *
 * A checker also has two things to say that are <em>not</em> verdicts, and folding either of them into a note is how
 * an arm becomes permanently undecided and stops being able to signal anything at all.
 * <ul>
 *     <li>A <b>measurement</b> is a fact the run produced which the history fully accounts for. The framework's
 *     behaviour explains it, the checker checked it, and nothing is unknown -- so it is printed and the verdict
 *     stands. A repeated delivery that a recorded rewind licenses, bounded by the position that rewind went back to,
 *     is the standing example.</li>
 *     <li>A <b>not-applicable</b> statement names an invariant this run cannot express at all: a claim assertion
 *     against a store with no notion of an owner, or an attribution assertion across a segment-set rebuild, where a
 *     segment identifier stops naming one unit of work. Reporting it as undecidedness says the run tried and failed;
 *     reporting nothing says it passed. Neither is true, so it is named.</li>
 * </ul>
 * Only {@code violations} and {@code notes} move a verdict. That is the whole distinction: a note means the verdict
 * is weaker than it looks, and the other two mean it is exactly what it says while something else is worth reading.
 *
 * @param checkerName   the checker that produced this result
 * @param violations    the invariants it found broken; empty when they all held
 * @param notes         what stopped it from deciding, if anything
 * @param measurements  facts the run produced that the history accounts for, which do not weaken the verdict
 * @param notApplicable the invariants this run cannot express, named so that a gap is never read as a pass
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public record CheckResult(String checkerName,
                          List<Violation> violations,
                          List<String> notes,
                          List<String> measurements,
                          List<String> notApplicable) {

    /**
     * Compact constructor rejecting a missing checker name and defensively copying every list.
     */
    public CheckResult {
        Objects.requireNonNull(checkerName, "The checkerName cannot be null.");
        violations = List.copyOf(Objects.requireNonNull(violations, "The violations cannot be null."));
        notes = List.copyOf(Objects.requireNonNull(notes, "The notes cannot be null."));
        measurements = List.copyOf(Objects.requireNonNull(measurements, "The measurements cannot be null."));
        notApplicable = List.copyOf(Objects.requireNonNull(notApplicable, "The notApplicable cannot be null."));
    }

    /**
     * Creates a result carrying violations and notes only, which is what most checkers have to report.
     *
     * @param checkerName the checker that produced this result
     * @param violations  the invariants it found broken
     * @param notes       what stopped it from deciding
     */
    public CheckResult(String checkerName, List<Violation> violations, List<String> notes) {
        this(checkerName, violations, notes, List.of(), List.of());
    }

    /**
     * Creates a result reporting that every invariant the checker enforces held, with nothing left undecided.
     *
     * @param checkerName the checker that produced the result
     * @return a clean result
     */
    public static CheckResult holding(String checkerName) {
        return new CheckResult(checkerName, List.of(), List.of(), List.of(), List.of());
    }

    /**
     * Creates a result reporting that the invariants named cannot be expressed by this run.
     *
     * @param checkerName the checker that produced the result
     * @param statements  one statement per invariant the run cannot express, saying why
     * @return a result that is neither a pass nor undecided, and says which
     */
    public static CheckResult notApplicable(String checkerName, List<String> statements) {
        return new CheckResult(checkerName, List.of(), List.of(), List.of(), statements);
    }

    /**
     * Indicates whether every invariant the checker enforces held.
     *
     * @return {@code true} when no violation was found
     */
    public boolean holds() {
        return violations.isEmpty();
    }

    /**
     * Indicates whether the history left the checker unable to decide part of its verdict.
     *
     * @return {@code true} when the checker recorded at least one note
     */
    public boolean inconclusive() {
        return !notes.isEmpty();
    }

    /**
     * Renders the result as a report.
     *
     * @return a multi-line rendering listing every violation, note, measurement and not-applicable statement
     */
    @Override
    public String toString() {
        StringBuilder rendering = new StringBuilder(checkerName)
                .append(holds() ? (inconclusive() ? ": INCONCLUSIVE" : ": PASS") : ": FAIL");
        violations.forEach(violation -> rendering.append(System.lineSeparator()).append("  ").append(violation));
        notes.forEach(note -> rendering.append(System.lineSeparator()).append("  note: ").append(note));
        measurements.forEach(m -> rendering.append(System.lineSeparator()).append("  measured: ").append(m));
        notApplicable.forEach(n -> rendering.append(System.lineSeparator()).append("  n/a: ").append(n));
        return rendering.toString();
    }
}
