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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plants overlapping and non-overlapping claims and checks which ones the ownership oracle catches.
 * <p>
 * An oracle nobody has watched fail is decoration, so each case here breaks exactly one thing. The cases that must
 * <em>not</em> fire matter as much as the ones that must: a handover after a claim lapsed, and a node re-taking its
 * own lapsed claim, are both ordinary and both would look like an overlap to a checker that counted carelessly.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class OwnershipCheckerTest {

    private static final Map<String, String> ARBITRATING =
            Map.of(OwnershipChecker.ARBITRATES_CLAIMS, "true",
                   OwnershipChecker.CLAIM_TIMEOUT_MS, "200",
                   OwnershipChecker.SKEW_ALLOWANCE_MS, "0");

    @Nested
    class ATwoNodeHistoryOnAStoreThatArbitrates {

        @Test
        void reportsTwoNodesHoldingOneSegmentAtOnce(@TempDir Path directory) {
            // given one node granted a claim and a second granted the same claim well inside the timeout
            SyntheticHistory history = new SyntheticHistory(directory, "overlapping_claims", ARBITRATING);
            history.claimGranted("node-a", 0);
            history.pause(20);
            history.claimGranted("node-b", 0);

            // when the ownership oracle judges it
            CheckResult result = new OwnershipChecker().check(history.view());

            // then the overlap must be reported
            assertThat(result.violations()).hasSize(1);
            assertThat(result.violations().getFirst().machineName())
                    .isEqualTo(OwnershipChecker.AT_MOST_ONE_SEGMENT_OWNER);
            assertThat(result.violations().getFirst().detail()).contains("node-a", "node-b", "segment [p/0]");
        }

        @Test
        void acceptsAHandoverAfterTheFirstClaimHasLapsed(@TempDir Path directory) {
            // given a claim that was never extended and a second claim taken after the timeout had passed
            SyntheticHistory history = new SyntheticHistory(directory, "handover_after_expiry", ARBITRATING);
            history.claimGranted("node-a", 0);
            history.pause(260);
            history.claimGranted("node-b", 0);

            // when the ownership oracle judges it
            CheckResult result = new OwnershipChecker().check(history.view());

            // then nothing is broken: the store's own rule says that claim had expired
            assertThat(result.holds()).isTrue();
        }

        @Test
        void acceptsANodeRetakingItsOwnLapsedClaim(@TempDir Path directory) {
            // given one node letting its claim lapse and taking it again, with another node holding a different
            // segment throughout so the history has two nodes in it
            SyntheticHistory history = new SyntheticHistory(directory, "self_reclaim", ARBITRATING);
            history.claimGranted("node-a", 0);
            history.claimGranted("node-b", 1);
            history.pause(260);
            history.claimGranted("node-a", 0);

            // when the ownership oracle judges it
            CheckResult result = new OwnershipChecker().check(history.view());

            // then one node holding one segment twice is not two owners
            assertThat(result.holds()).isTrue();
        }

        @Test
        void acceptsAHandoverAfterAnExplicitRelease(@TempDir Path directory) {
            // given a claim given back before another node took it
            SyntheticHistory history = new SyntheticHistory(directory, "handover_after_release", ARBITRATING);
            history.claimGranted("node-a", 0);
            history.claimReleased("node-a", 0);
            history.claimGranted("node-b", 0);

            // when the ownership oracle judges it
            CheckResult result = new OwnershipChecker().check(history.view());

            // then an orderly handover is not an overlap
            assertThat(result.holds()).isTrue();
        }

        @Test
        void reportsAnOverlapThatAnExtensionKeptAlive(@TempDir Path directory) {
            // given a node that kept extending its claim, and a second node granted the same segment meanwhile
            SyntheticHistory history = new SyntheticHistory(directory, "overlap_under_extension", ARBITRATING);
            history.claimGranted("node-a", 0);
            history.pause(150);
            history.claimExtended("node-a", 0);
            history.pause(100);
            history.claimGranted("node-b", 0);

            // when the ownership oracle judges it
            CheckResult result = new OwnershipChecker().check(history.view());

            // then the extension kept the first claim alive, so the second one overlaps it
            assertThat(result.violations()).hasSize(1);
        }

        @Test
        void acceptsAnOverlapInsideAnExplicitlyDeclaredSkewAllowance(@TempDir Path directory) {
            // given the same overlap, and a run that declared a clock-skew allowance wide enough to cover it
            Map<String, String> tolerant = Map.of(OwnershipChecker.ARBITRATES_CLAIMS, "true",
                                                  OwnershipChecker.CLAIM_TIMEOUT_MS, "200",
                                                  OwnershipChecker.SKEW_ALLOWANCE_MS, "500");
            SyntheticHistory history = new SyntheticHistory(directory, "overlap_within_skew", tolerant);
            history.claimGranted("node-a", 0);
            history.pause(20);
            history.claimGranted("node-b", 0);

            // when the ownership oracle judges it
            CheckResult result = new OwnershipChecker().check(history.view());

            // then the allowance absorbs it, and it absorbed it because the run said so rather than because the
            // checker decided to be lenient
            assertThat(result.holds()).isTrue();
        }
    }

    @Nested
    class AHistoryTheOracleMustNotDecide {

        @Test
        void saysSoWhenTheStoreArbitratesNothing(@TempDir Path directory) {
            // given the same overlap against a store the run declared as having no ownership at all
            Map<String, String> vacuous = Map.of(OwnershipChecker.ARBITRATES_CLAIMS, "false",
                                                 OwnershipChecker.CLAIM_TIMEOUT_MS, "200",
                                                 OwnershipChecker.SKEW_ALLOWANCE_MS, "0");
            SyntheticHistory history = new SyntheticHistory(directory, "no_arbitration", vacuous);
            history.claimGranted("node-a", 0);
            history.claimGranted("node-b", 0);

            // when the ownership oracle judges it
            CheckResult result = new OwnershipChecker().check(history.view());

            // then it names the invariant as one this run cannot express, rather than passing, failing, or reporting
            // undecidedness: the store has no owner, so there is nothing here for the invariant to be true or false
            // about, and a run against it must still be able to reach a verdict on everything else
            assertThat(result.violations()).isEmpty();
            assertThat(result.notes()).isEmpty();
            assertThat(result.notApplicable()).hasSize(1);
            assertThat(result.notApplicable().getFirst()).contains("implements no ownership");
        }

        @Test
        void holdsSilentlyForASingleNode(@TempDir Path directory) {
            // given one node claiming the same segment repeatedly
            SyntheticHistory history = new SyntheticHistory(directory, "single_node", ARBITRATING);
            history.claimGranted("node-a", 0);
            history.claimGranted("node-a", 0);

            // when the ownership oracle judges it
            CheckResult result = new OwnershipChecker().check(history.view());

            // then there is nothing to arbitrate and nothing to report
            assertThat(result.holds()).isTrue();
            assertThat(result.notes()).isEmpty();
        }

        @Test
        void holdsSilentlyWhenTheRunNeverSaidWhatItsStoreDoes(@TempDir Path directory) {
            // given an overlap in a history written before the run recorded that field
            SyntheticHistory history = new SyntheticHistory(directory, "no_header_field");
            history.claimGranted("node-a", 0);
            history.claimGranted("node-b", 0);

            // when the ownership oracle judges it
            CheckResult result = new OwnershipChecker().check(history.view());

            // then it leaves the history alone rather than guessing what store produced it
            assertThat(result.holds()).isTrue();
            assertThat(result.notes()).isEmpty();
        }
    }
}
