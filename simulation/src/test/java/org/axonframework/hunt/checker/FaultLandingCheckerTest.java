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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that a fault which never fired stops the run being reported as a pass.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class FaultLandingCheckerTest {

    @TempDir
    Path directory;

    private final FaultLandingChecker checker = new FaultLandingChecker();

    @Test
    void aFaultThatFiredLeavesTheVerdictAlone() {
        // given
        SyntheticHistory history = new SyntheticHistory(directory, "landed");
        history.faultEvidence("injected-latency", 42L);

        // when
        CheckResult result = checker.check(history.view());

        // then
        assertThat(result.holds()).isTrue();
        assertThat(result.inconclusive()).isFalse();
    }

    @Test
    void aFaultThatNeverFiredMakesTheRunUndecidedWithoutCallingItBroken() {
        // given
        SyntheticHistory history = new SyntheticHistory(directory, "unlanded");
        history.faultEvidence("write-then-vanish", 0L);

        // when
        CheckResult result = checker.check(history.view());

        // then missing evidence is not a broken property, and must not be reported as one
        assertThat(result.violations()).isEmpty();
        assertThat(result.inconclusive()).isTrue();
        assertThat(result.notes().getFirst()).contains("write-then-vanish").contains("never fired");
    }
}
