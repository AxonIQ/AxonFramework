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

import org.axonframework.hunt.history.HistoryOps;
import org.axonframework.hunt.history.HistoryRecord;
import org.axonframework.hunt.history.HistoryView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Checks that the faults a run declared actually fired.
 * <p>
 * A green run under a fault that never landed has verified nothing at all, and reporting it as a pass is worse than
 * reporting nothing, because it puts a tick next to a property the run never tested. This checker is what makes that
 * impossible: a declared fault with no fires produces a note, and a note makes the run inconclusive.
 * <p>
 * It never reports a violation. A fault that did not fire is missing evidence, not a broken property, and the two
 * must not be reported the same way.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public class FaultLandingChecker implements Checker {

    /**
     * The stable name of the evidence rule this checker enforces.
     */
    public static final String DECLARED_FAULTS_LAND = "DeclaredFaultsLand";

    /**
     * The statement of {@link #DECLARED_FAULTS_LAND}, character-identical to the invariant registry.
     */
    public static final String DECLARED_FAULTS_LAND_STATEMENT =
            "Every fault a run declares fires at least once, and the run records how often and against what.";

    @Override
    public String name() {
        return "FaultLandingChecker";
    }

    @Override
    public Set<String> machineNames() {
        return Set.of(DECLARED_FAULTS_LAND);
    }

    @Override
    public CheckResult check(HistoryView history) {
        List<String> notes = new ArrayList<>();
        for (HistoryRecord fault : history.notes(HistoryOps.FAULT)) {
            long fires = fault.longValue("fires", 0L);
            if (fires == 0) {
                notes.add("The declared fault [" + fault.stringValue("kind")
                                  + "] never fired, so the run verified nothing under it.");
            }
        }
        return new CheckResult(name(), List.of(), List.copyOf(notes));
    }
}
