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

import java.util.Set;

/**
 * An oracle that judges a recorded run.
 * <p>
 * A checker reads a history and reports whether the invariants it enforces held. It never reads a file, never talks
 * to the system under test, and never depends on how the run was driven, so the same checker judges a single-process
 * simulation and a multi-node run against a real store.
 * <p>
 * Implementations are found through the {@link java.util.ServiceLoader}. Adding a checker means writing the class and
 * naming it in {@code META-INF/services/org.axonframework.hunt.checker.Checker}; no existing class changes.
 * <p>
 * Two obligations come with implementing this interface. Every name in {@link #machineNames()} must appear in the
 * invariant registry with the same wording it is asserted under, and the checker must be shown to fail on a history
 * that plants the violation it claims to catch. A checker with no demonstrated failure mode is decoration.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public interface Checker {

    /**
     * Returns the checker's own name, used to attribute its verdict.
     *
     * @return the checker's name, conventionally its simple class name
     */
    String name();

    /**
     * Returns the stable names of the invariants this checker enforces.
     * <p>
     * A checker may enforce more than one; each violation names the specific invariant it broke.
     *
     * @return the invariant names, identical to their names in the invariant registry
     */
    Set<String> machineNames();

    /**
     * Judges the given history.
     *
     * @param history the recorded run, with invocations already paired to completions
     * @return the verdict, listing any violations and anything the history left undecided
     */
    CheckResult check(HistoryView history);
}
