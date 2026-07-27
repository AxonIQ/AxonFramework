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

import org.axonframework.hunt.history.HistoryView;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Finds every registered {@link Checker} and runs them all.
 * <p>
 * Discovery goes through the {@link ServiceLoader}, so a new invariant reaches every scenario by adding one class and
 * one line to {@code META-INF/services/org.axonframework.hunt.checker.Checker}. Nothing that already exists is
 * edited, and no scenario opts a checker in: the whole set runs against every history, because an invariant that only
 * runs where somebody remembered it is an invariant that will be forgotten.
 * <p>
 * Example usage:
 * <pre>{@code
 * List<CheckResult> results = CheckerRegistry.runAll(HistoryView.read(historyFile));
 * List<Violation> violations = results.stream().flatMap(result -> result.violations().stream()).toList();
 * }</pre>
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class CheckerRegistry {

    private CheckerRegistry() {
        // Utility class.
    }

    /**
     * Returns every registered checker, ordered by name so that a report reads the same way twice.
     *
     * @return the registered checkers
     */
    public static List<Checker> discover() {
        return ServiceLoader.load(Checker.class, CheckerRegistry.class.getClassLoader())
                            .stream()
                            .map(ServiceLoader.Provider::get)
                            .sorted(Comparator.comparing(Checker::name))
                            .toList();
    }

    /**
     * Runs every registered checker against the given history.
     *
     * @param history the recorded run to judge
     * @return one result per checker, in checker-name order
     */
    public static List<CheckResult> runAll(HistoryView history) {
        Objects.requireNonNull(history, "The history cannot be null.");
        return discover().stream().map(checker -> checker.check(history)).toList();
    }

    /**
     * Runs every registered checker and collects everything they found broken.
     *
     * @param history the recorded run to judge
     * @return every violation, across every checker
     */
    public static List<Violation> violations(HistoryView history) {
        return runAll(history).stream().flatMap(result -> result.violations().stream()).toList();
    }
}
