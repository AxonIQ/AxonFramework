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
 * @param checkerName the checker that produced this result
 * @param violations  the invariants it found broken; empty when they all held
 * @param notes       what stopped it from deciding, if anything
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public record CheckResult(String checkerName, List<Violation> violations, List<String> notes) {

    /**
     * Compact constructor rejecting a missing checker name and defensively copying both lists.
     */
    public CheckResult {
        Objects.requireNonNull(checkerName, "The checkerName cannot be null.");
        violations = List.copyOf(Objects.requireNonNull(violations, "The violations cannot be null."));
        notes = List.copyOf(Objects.requireNonNull(notes, "The notes cannot be null."));
    }

    /**
     * Creates a result reporting that every invariant the checker enforces held, with nothing left undecided.
     *
     * @param checkerName the checker that produced the result
     * @return a clean result
     */
    public static CheckResult holding(String checkerName) {
        return new CheckResult(checkerName, List.of(), List.of());
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
     * @return a multi-line rendering listing every violation and every note
     */
    @Override
    public String toString() {
        StringBuilder rendering = new StringBuilder(checkerName)
                .append(holds() ? (inconclusive() ? ": INCONCLUSIVE" : ": PASS") : ": FAIL");
        violations.forEach(violation -> rendering.append(System.lineSeparator()).append("  ").append(violation));
        notes.forEach(note -> rendering.append(System.lineSeparator()).append("  note: ").append(note));
        return rendering.toString();
    }
}
