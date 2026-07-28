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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plants each way durable progress can be wrong and checks that the oracle finds it.
 * <p>
 * A checker that has never been shown to fail is decoration, and this one guards the guarantee the mutation campaign
 * once broke without the suite noticing: a batch's handler effects and its token progress are persisted together. So each
 * case here builds the smallest history in which one of the three properties is false, and one sound history alongside
 * it, because a checker that reports everything broken is no more useful than one that reports nothing.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class StoredProgressCheckerTest {

    private static final Map<String, String> SHAPE = Map.of(OwnershipChecker.CLAIM_TIMEOUT_MS, "2000",
                                                            OwnershipChecker.SKEW_ALLOWANCE_MS, "0",
                                                            StoredProgressChecker.BATCH_SIZE, "2");

    private final StoredProgressChecker checker = new StoredProgressChecker();

    @Nested
    class AStoredTokenGoingBackwards {

        @Test
        void isReportedAsARegression(@TempDir Path directory) {
            // given a segment whose durable progress goes backwards
            SyntheticHistory history = new SyntheticHistory(directory, "regressing-token", SHAPE);
            history.tokenStored("node-a", 0, 40L);
            history.tokenStored("node-a", 0, 12L);
            history.settled(true);

            // when the oracle judges it
            CheckResult result = checker.check(history.view());

            // then the regression is reported, and reported under the invariant it breaks
            assertThat(result.violations()).singleElement()
                                          .satisfies(violation -> assertThat(violation.machineName())
                                                  .isEqualTo(StoredProgressChecker.STORED_TOKEN_NEVER_REGRESSES));
        }

        @Test
        void isNotReportedWhenTheStoreRefusedTheWrite(@TempDir Path directory) {
            // given a node that lost its claim and offered a stale position, which the store refused
            SyntheticHistory history = new SyntheticHistory(directory, "refused-stale-token", SHAPE);
            history.tokenStored("node-a", 0, 40L);
            history.tokenStoreRefused("node-b", 0, 12L);
            history.settled(true);

            // when the oracle judges it
            CheckResult result = checker.check(history.view());

            // then nothing is reported: the claim protocol turned the stale write away, which is it working rather than
            // progress going backwards
            assertThat(result.violations()).isEmpty();
        }
    }

    @Nested
    class DurableProgressLaggingTheEffectsAlreadyApplied {

        @Test
        void isReportedWhenTheLastStoredTokenDoesNotCoverThem(@TempDir Path directory) {
            // given a segment that delivered an event at position 30 and never stored a token reaching it
            SyntheticHistory history = new SyntheticHistory(directory, "uncovered-delivery", SHAPE);
            history.tokenStored("node-a", 0, 10L);
            history.deliverFromSegment("node-a", 0, "e-30", 30L);
            history.settled(true);

            // when the oracle judges it
            CheckResult result = checker.check(history.view());

            // then the shortfall is reported
            assertThat(result.violations()).singleElement()
                                          .satisfies(violation -> assertThat(violation.machineName())
                                                  .isEqualTo(StoredProgressChecker.STORED_TOKEN_COVERS_DELIVERED_EVENTS));
        }

        @Test
        void isNotReportedWhenTheReadSideNeverCaughtUp(@TempDir Path directory) {
            // given the same shortfall on a run that was still catching up when it ended
            SyntheticHistory history = new SyntheticHistory(directory, "uncovered-but-interrupted", SHAPE);
            history.tokenStored("node-a", 0, 10L);
            history.deliverFromSegment("node-a", 0, "e-30", 30L);
            history.settled(false);

            // when the oracle judges it
            CheckResult result = checker.check(history.view());

            // then it is reported rather than decided: the run was interrupted, not incomplete
            assertThat(result.violations()).isEmpty();
            assertThat(result.notes()).isNotEmpty();
        }
    }

    @Nested
    class AHandoverRewindingPastOneBatch {

        @Test
        void isReportedWhenTheStoredTokenHadFallenFurtherBehindThanABatch(@TempDir Path directory) {
            // given a segment that delivered three events, stored nothing past the first, and then changed hands, with a
            // batch of two
            SyntheticHistory history = new SyntheticHistory(directory, "wide-rewind", SHAPE);
            history.claimGranted("node-a", 0);
            history.tokenStored("node-a", 0, 10L);
            history.deliverFromSegment("node-a", 0, "e-20", 20L);
            history.deliverFromSegment("node-a", 0, "e-30", 30L);
            history.deliverFromSegment("node-a", 0, "e-40", 40L);
            history.pause(2L);
            history.claimGranted("node-b", 0);
            history.tokenStored("node-b", 0, 40L);
            history.settled(true);

            // when the oracle judges it
            CheckResult result = checker.check(history.view());

            // then the rewind is reported: three events already applied sat past the stored token, and one batch holds
            // two
            assertThat(result.violations()).singleElement()
                                          .satisfies(violation -> assertThat(violation.machineName())
                                                  .isEqualTo(StoredProgressChecker.CLAIM_HANDOVER_REWINDS_AT_MOST_ONE_BATCH));
        }

        @Test
        void isNotReportedWhenTheHandoverInheritedEveryFinishedBatch(@TempDir Path directory) {
            // given the same handover with the progress of every delivered event durably stored first
            SyntheticHistory history = new SyntheticHistory(directory, "narrow-rewind", SHAPE);
            history.claimGranted("node-a", 0);
            history.deliverFromSegment("node-a", 0, "e-20", 20L);
            history.deliverFromSegment("node-a", 0, "e-30", 30L);
            history.tokenStored("node-a", 0, 40L);
            history.pause(2L);
            history.claimGranted("node-b", 0);
            history.settled(true);

            // when the oracle judges it
            HistoryView view = history.view();
            CheckResult result = checker.check(view);

            // then nothing is reported, and the rewind the run really cost is measured as zero
            assertThat(result.violations()).isEmpty();
            assertThat(StoredProgressChecker.widestHandoverRewind(view)).isZero();
        }
    }

    @Nested
    class AHistoryWithNoTokenWriteInIt {

        @Test
        void isLeftAlone(@TempDir Path directory) {
            // given a history from a run whose token store recorded nothing
            SyntheticHistory history = new SyntheticHistory(directory, "no-token-writes", SHAPE);
            history.deliver("e-1");
            history.settled(true);

            // when the oracle judges it
            HistoryView view = history.view();

            // then it holds without deciding anything, rather than reporting a segment that never existed
            assertThat(checker.check(view).violations()).isEmpty();
            assertThat(checker.check(view).notes()).isEmpty();
        }
    }
}
