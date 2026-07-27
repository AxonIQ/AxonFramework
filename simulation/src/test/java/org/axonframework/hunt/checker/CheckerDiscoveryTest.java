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

import org.axonframework.hunt.model.ModelAppendCondition;
import org.axonframework.hunt.model.ModelCriterion;
import org.axonframework.hunt.model.ModelEvent;
import org.axonframework.hunt.model.ModelTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that checkers reach a run without any scenario opting them in, and that every invariant they claim is
 * reported under a distinct name.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class CheckerDiscoveryTest {

    private static final ModelTag STUDENT = ModelTag.of("student", "s-1");

    @TempDir
    Path directory;

    @Test
    void everyRegisteredCheckerIsFoundWithoutBeingNamedByTheCaller() {
        // given / when
        var discovered = CheckerRegistry.discover();

        // then
        assertThat(discovered).extracting(Checker::name)
                              .contains("ModelConformanceChecker", "VisibilityChecker");
    }

    @Test
    void everyInvariantNameIsClaimedByExactlyOneChecker() {
        // given
        var discovered = CheckerRegistry.discover();

        // when
        var claimed = discovered.stream().flatMap(checker -> checker.machineNames().stream()).toList();

        // then
        assertThat(claimed).doesNotHaveDuplicates()
                           .contains(ModelConformanceChecker.APPEND_CONFORMS_TO_DCB_MODEL,
                                     VisibilityChecker.NO_VISIBILITY_BEFORE_COMMIT,
                                     VisibilityChecker.ROLLED_BACK_EVENTS_NEVER_OBSERVABLE);
    }

    @Test
    void runningEveryCheckerOverOneHistoryCollectsViolationsFromAllOfThem() {
        // given a history that breaks both a model-conformance rule and a visibility rule
        SyntheticHistory history = new SyntheticHistory(directory, "breaks-both");
        Set<ModelCriterion> boundary = Set.of(ModelCriterion.havingTags(STUDENT));
        ModelEvent first = new ModelEvent("e-0", "StudentEnrolled", Set.of(STUDENT));
        ModelEvent second = new ModelEvent("e-1", "StudentEnrolled", Set.of(STUDENT));
        history.appendOk(ModelAppendCondition.withCriteria(boundary), first);
        history.appendOk(ModelAppendCondition.withCriteria(boundary), second);
        history.deliver("e-ghost");

        // when
        var violations = CheckerRegistry.violations(history.view());

        // then
        assertThat(violations).extracting(Violation::machineName)
                              .containsExactlyInAnyOrder(ModelConformanceChecker.APPEND_CONFORMS_TO_DCB_MODEL,
                                                         VisibilityChecker.NO_VISIBILITY_BEFORE_COMMIT);
    }
}
