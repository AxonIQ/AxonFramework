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

import org.axonframework.hunt.checker.AppendOutcomeChecker;
import org.axonframework.hunt.checker.ConservationChecker;
import org.axonframework.hunt.checker.FaultLandingChecker;
import org.axonframework.hunt.checker.ModelConformanceChecker;
import org.axonframework.hunt.fault.DuplicatedAppendFault;
import org.axonframework.hunt.fault.FaultSchedule;
import org.axonframework.hunt.fault.FaultWindow;
import org.axonframework.hunt.fault.InjectedLatencyFault;
import org.axonframework.hunt.workload.LedgerWorkload;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The scenarios this build ships, by identifier.
 * <p>
 * The list exists so that a run can be reproduced from its own history: the command a violation prints names a
 * scenario, and this is where that name is resolved. It is a catalogue and nothing more. Running a scenario does not
 * require it to be here, which is the point of scenarios being data.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class HuntScenarios {

    /**
     * The identifier of the scenario that hunts for an append accepted after its consistency marker went stale.
     */
    public static final String APPEND_REJECTED_AFTER_MARKER = "dcb_append_rejected_after_marker_under_contention";

    /**
     * The identifier of the same hunt, run with a pair of faults installed.
     */
    public static final String APPEND_REJECTED_AFTER_MARKER_UNDER_FAULT =
            "dcb_append_rejected_after_marker_under_contention_with_faults";

    private HuntScenarios() {
        // Utility class.
    }

    /**
     * Returns every scenario this build ships.
     *
     * @return the catalogue, in declaration order
     */
    public static List<Scenario> all() {
        return List.of(appendRejectedAfterMarker(), appendRejectedAfterMarkerUnderFault());
    }

    /**
     * Returns the scenario with the given identifier.
     *
     * @param id the scenario's identifier
     * @return the scenario, or empty when the identifier names nothing this build ships
     */
    public static Optional<Scenario> byId(String id) {
        return all().stream().filter(scenario -> scenario.id().equals(id)).findFirst();
    }

    /**
     * Concurrent writers over a small pool of hot accounts, every append conditioned on what its writer read.
     * <p>
     * The hunt is for an append that the store accepted although an event matching its boundary had landed after the
     * marker it was anchored at. Every append the run makes is replayed against the reference model at its point in
     * the history, so an acceptance the protocol does not permit is caught wherever it happens rather than only where
     * somebody thought to look.
     * <p>
     * Two arms run alongside it, and both are part of the same record rather than separate tests. Appends made
     * without sourcing anything carry no condition at all and must never be rejected; if one is, the conflict check
     * is firing where it cannot legitimately fire and every rejection count in the run is suspect. And every append
     * the store rejected must have left nothing behind, checked against an authoritative scan taken after the run has
     * gone quiet.
     * <p>
     * Contention is the whole point, so the account access distribution is pinned to the hot-key shape rather than
     * left to the seed: a uniform spread over the same accounts would spend the entire budget never producing a
     * conflict, and a run with no conflicts in it proves nothing about the conflict path.
     *
     * @return the scenario
     */
    public static Scenario appendRejectedAfterMarker() {
        return Scenario.builder(APPEND_REJECTED_AFTER_MARKER,
                                "Concurrent conditioned appends over hot accounts must never both commit")
                       .claims("C1", "C2", "C5", "C6", "C8", "C9")
                       .workload(LedgerWorkload::hotKey)
                       .faults(FaultSchedule.none(Duration.ofSeconds(20)))
                       .oracles(ModelConformanceChecker.APPEND_CONFORMS_TO_DCB_MODEL,
                                AppendOutcomeChecker.UNCONDITIONAL_APPEND_NEVER_REJECTED,
                                AppendOutcomeChecker.REJECTED_APPEND_LEAVES_NO_EVENTS,
                                ConservationChecker.LEDGER_CONSERVES_TOTAL_BALANCE,
                                ConservationChecker.LEDGER_BALANCE_NEVER_NEGATIVE,
                                ConservationChecker.PROJECTION_MATCHES_FOLD_OF_COMMITTED_EVENTS)
                       .seed(1L)
                       .budget(Tier.SMOKE, new TierBudget(1_000, 3, Duration.ofSeconds(60)))
                       .budget(Tier.RELEASE, new TierBudget(100_000, 1_000, Duration.ofMinutes(10)))
                       .build();
    }

    /**
     * The same hunt with the store made slow and occasionally doubling a commit.
     * <p>
     * Latency widens the window between reading a marker and appending against it, which is where a conflict-check
     * defect would live; the doubled commit is there to prove the read side's arithmetic notices. It declares no
     * smoke budget on purpose, because two simultaneous faults is more than the smoke tier permits: a failure under
     * compound faults cannot be attributed, and attribution is what makes a finding a finding.
     *
     * @return the scenario
     */
    public static Scenario appendRejectedAfterMarkerUnderFault() {
        FaultSchedule schedule = FaultSchedule.builder()
                                              .warmup(Duration.ofMillis(250))
                                              .window(FaultWindow.immediately(
                                                      "slow-and-doubling",
                                                      Duration.ofSeconds(2),
                                                      new InjectedLatencyFault(Duration.ofMillis(2)),
                                                      new DuplicatedAppendFault(97)))
                                              .heal(Duration.ofMillis(250))
                                              .settle(Duration.ofSeconds(20))
                                              .build();
        return Scenario.builder(APPEND_REJECTED_AFTER_MARKER_UNDER_FAULT,
                                "The same hunt with a slow store that occasionally doubles a commit")
                       .claims("C1", "C5", "C6", "C8")
                       .workload(LedgerWorkload::hotKey)
                       .faults(schedule)
                       .oracles(ModelConformanceChecker.APPEND_CONFORMS_TO_DCB_MODEL,
                                AppendOutcomeChecker.UNCONDITIONAL_APPEND_NEVER_REJECTED,
                                FaultLandingChecker.DECLARED_FAULTS_LAND)
                       .seed(1L)
                       .budget(Tier.HARDENING, new TierBudget(2_000, 3, Duration.ofSeconds(90)))
                       .budget(Tier.RELEASE, new TierBudget(100_000, 100, Duration.ofMinutes(10)))
                       .build();
    }
}
