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
import org.axonframework.hunt.checker.DeliveryChecker;
import org.axonframework.hunt.checker.FaultLandingChecker;
import org.axonframework.hunt.checker.LivenessChecker;
import org.axonframework.hunt.checker.ModelConformanceChecker;
import org.axonframework.hunt.checker.OrderChecker;
import org.axonframework.hunt.checker.OwnershipChecker;
import org.axonframework.hunt.checker.VisibilityChecker;
import org.axonframework.hunt.fault.AfterCommitFailureFault;
import org.axonframework.hunt.fault.AppendRejectionFault;
import org.axonframework.hunt.fault.DuplicatedAppendFault;
import org.axonframework.hunt.fault.Fault;
import org.axonframework.hunt.fault.FaultSchedule;
import org.axonframework.hunt.fault.FaultWindow;
import org.axonframework.hunt.fault.InjectedLatencyFault;
import org.axonframework.hunt.fault.NodeCrashFault;
import org.axonframework.hunt.fault.PrepareCommitFailureFault;
import org.axonframework.hunt.harness.DeterminismMode;
import org.axonframework.hunt.harness.HsqldbTokenStoreBackend;
import org.axonframework.hunt.harness.HuntTimescale;
import org.axonframework.hunt.workload.BatchWorkload;
import org.axonframework.hunt.workload.LedgerWorkload;
import org.axonframework.hunt.workload.SequencedWorkload;
import org.axonframework.messaging.core.sequencing.HierarchicalSequencingPolicy;
import org.axonframework.messaging.core.sequencing.NoOpSequencingPolicy;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.core.sequencing.SequentialPerAggregatePolicy;
import org.axonframework.messaging.core.sequencing.SequentialPolicy;
import org.axonframework.messaging.eventhandling.EventMessage;

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

    /**
     * The identifier of the single-writer arm of the same hunt, whose write side a seed replays exactly.
     */
    public static final String APPEND_REJECTED_AFTER_MARKER_SINGLE_WRITER =
            "dcb_append_rejected_after_marker_single_writer";

    /**
     * The identifier of the arm failing a transaction in the phase in which events are handed to the store.
     */
    public static final String UNCOMMITTED_NEVER_VISIBLE_PREPARE_COMMIT =
            "uncommitted_never_visible_rolledback_never_delivered_prepare_commit";

    /**
     * The identifier of the arm failing a transaction in the phase in which the store is asked to commit.
     */
    public static final String UNCOMMITTED_NEVER_VISIBLE_COMMIT =
            "uncommitted_never_visible_rolledback_never_delivered_commit";

    /**
     * The identifier of the arm failing a transaction after its commit has already published the batch.
     */
    public static final String UNCOMMITTED_NEVER_VISIBLE_AFTER_COMMIT =
            "uncommitted_never_visible_rolledback_never_delivered_after_commit";

    /**
     * The identifier of the arm running the sequencing policy the framework wires by default.
     */
    public static final String SEQUENCING_POLICY_ORDER_WIRED_DEFAULT =
            "sequencing_policy_order_preserved_wired_default";

    /**
     * The identifier of the arm running an explicit no-op sequencing policy.
     */
    public static final String SEQUENCING_POLICY_ORDER_NO_OP = "sequencing_policy_order_preserved_no_op";

    /**
     * The identifier of the arm running the per-aggregate sequencing policy alone on a store that populates no
     * aggregate identifier.
     */
    public static final String SEQUENCING_POLICY_ORDER_PER_AGGREGATE =
            "sequencing_policy_order_preserved_per_aggregate";

    /**
     * The identifier of the arm reading the store at maximum rate while whole batches are being committed.
     */
    public static final String PARTIAL_BATCH_VISIBILITY = "partial_batch_never_visible_to_concurrent_reader";

    /**
     * The identifier of the arm booting several nodes at once against a token store that holds nothing.
     */
    public static final String CONCURRENT_BOOTSTRAP = "concurrent_bootstrap_initializes_segments_exactly_once";

    /**
     * The identifier of the same bootstrap with a node dropped and brought back while the segments are still being
     * taken.
     */
    public static final String CONCURRENT_BOOTSTRAP_WITH_CHURN =
            "concurrent_bootstrap_initializes_segments_exactly_once_with_node_churn";

    /**
     * How many nodes a bootstrap arm boots at once.
     */
    public static final int BOOTSTRAP_NODES = 4;

    private HuntScenarios() {
        // Utility class.
    }

    /**
     * Returns every scenario this build ships.
     *
     * @return the catalogue, in declaration order
     */
    public static List<Scenario> all() {
        return List.of(appendRejectedAfterMarker(),
                       appendRejectedAfterMarkerUnderFault(),
                       appendRejectedAfterMarkerSingleWriter(),
                       uncommittedNeverVisibleAtPrepareCommit(),
                       uncommittedNeverVisibleAtCommit(),
                       uncommittedNeverVisibleAfterCommit(),
                       sequencingPolicyOrderWiredDefault(),
                       sequencingPolicyOrderNoOp(),
                       sequencingPolicyOrderPerAggregate(),
                       partialBatchVisibility(),
                       concurrentBootstrap(),
                       concurrentBootstrapWithNodeChurn());
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

    /**
     * The same hunt with one writer, which is the only arm whose write side a seed reproduces exactly.
     * <p>
     * Contention still happens, because the arm that claims an account's whole history conflicts with anything that
     * account already holds, and one writer is enough to produce both verdicts. What one writer removes is the race
     * between writers, and with it the only thing that made the append verdicts a property of the thread schedule
     * rather than of the seed. That is what makes this the arm a pinned regression seed is worth pinning against.
     *
     * @return the scenario
     */
    public static Scenario appendRejectedAfterMarkerSingleWriter() {
        return Scenario.builder(APPEND_REJECTED_AFTER_MARKER_SINGLE_WRITER,
                                "One writer conditioning every append on what it read, so the seed replays the write "
                                        + "side exactly")
                       .claims("C1", "C2", "C5", "C6", "C8", "C9")
                       .workload(LedgerWorkload::hotKey)
                       .determinism(DeterminismMode.SINGLE_THREADED)
                       .faults(FaultSchedule.none(Duration.ofSeconds(20)))
                       .oracles(ModelConformanceChecker.APPEND_CONFORMS_TO_DCB_MODEL,
                                AppendOutcomeChecker.UNCONDITIONAL_APPEND_NEVER_REJECTED,
                                AppendOutcomeChecker.REJECTED_APPEND_LEAVES_NO_EVENTS,
                                ConservationChecker.LEDGER_CONSERVES_TOTAL_BALANCE,
                                ConservationChecker.LEDGER_BALANCE_NEVER_NEGATIVE,
                                ConservationChecker.PROJECTION_MATCHES_FOLD_OF_COMMITTED_EVENTS)
                       .seed(1L)
                       .budget(Tier.SMOKE, new TierBudget(400, 3, Duration.ofSeconds(60)))
                       .budget(Tier.RELEASE, new TierBudget(20_000, 200, Duration.ofMinutes(10)))
                       .build();
    }

    /**
     * A transaction killed while its events are being handed to the store.
     * <p>
     * Nothing has been offered to the store yet and the framework has not registered its rollback handler for the
     * transaction, so the batch must be invisible everywhere and absent from the store afterwards, and no rollback is
     * expected to appear at all.
     *
     * @return the scenario
     */
    public static Scenario uncommittedNeverVisibleAtPrepareCommit() {
        return transactionPhaseArm(UNCOMMITTED_NEVER_VISIBLE_PREPARE_COMMIT,
                                   "A transaction failed while its events are handed to the store",
                                   "prepare-commit-failure",
                                   new PrepareCommitFailureFault(3));
    }

    /**
     * A transaction killed at the moment the store is asked to commit.
     * <p>
     * This is the arm that exercises rollback: the framework's error handling discards the transaction, and nothing
     * it offered may reach a consumer or survive in the store.
     *
     * @return the scenario
     */
    public static Scenario uncommittedNeverVisibleAtCommit() {
        return transactionPhaseArm(UNCOMMITTED_NEVER_VISIBLE_COMMIT,
                                   "A transaction refused at the moment the store is asked to commit",
                                   "append-rejection",
                                   new AppendRejectionFault(3));
    }

    /**
     * A transaction killed after its commit has already published the batch.
     * <p>
     * The mirror image of the other two arms, and the one that keeps them honest. Those events were committed, so they
     * are legitimately observable and must stay observable; an oracle reporting them as rolled back would be reading
     * the guarantee far too strongly.
     *
     * @return the scenario
     */
    public static Scenario uncommittedNeverVisibleAfterCommit() {
        return transactionPhaseArm(UNCOMMITTED_NEVER_VISIBLE_AFTER_COMMIT,
                                   "A transaction failed after its commit had already published the batch",
                                   "after-commit-failure",
                                   new AfterCommitFailureFault(3));
    }

    /**
     * Builds one phase arm, with no warmup at all.
     * <p>
     * A warmup is a wall-clock delay before the fault is installed, and this workload issues its whole budget in tens
     * of milliseconds: any warmup at all risks the run finishing before the window opens, which produces a run under
     * a fault that never fired. That is correctly reported as undecided rather than as a pass, but an arm that is
     * undecided by construction verifies nothing. The window is instead opened immediately and kept open for longer
     * than the workload can possibly take.
     */
    private static Scenario transactionPhaseArm(String id, String name, String windowId, Fault fault) {
        FaultSchedule schedule = FaultSchedule.builder()
                                              .warmup(Duration.ZERO)
                                              .window(FaultWindow.immediately(windowId, Duration.ofMillis(800),
                                                                              fault))
                                              .heal(Duration.ofMillis(50))
                                              .settle(Duration.ofSeconds(15))
                                              .build();
        return Scenario.builder(id, name)
                       .claims("C4", "C29")
                       .workload(BatchWorkload::smallBatches)
                       .faults(schedule)
                       .oracles(VisibilityChecker.NO_VISIBILITY_BEFORE_COMMIT,
                                VisibilityChecker.ROLLED_BACK_EVENTS_NEVER_OBSERVABLE,
                                FaultLandingChecker.DECLARED_FAULTS_LAND)
                       .seed(1L)
                       .budget(Tier.SMOKE, new TierBudget(600, 3, Duration.ofSeconds(60)))
                       .budget(Tier.RELEASE, new TierBudget(30_000, 100, Duration.ofMinutes(10)))
                       .build();
    }

    /**
     * The sequencing policy the framework wires when nobody chooses one, on a store that speaks the Dynamic
     * Consistency Boundary protocol natively.
     * <p>
     * The wired default tries a per-aggregate identifier first and falls back to one identifier for everything. A
     * DCB-native store populates no aggregate identifier, so the fallback always wins and the effective behaviour is
     * one sequence identifier for the entire stream. The arm asserts exactly that, and it asserts it with four
     * segments and four workers configured, because the point is that the configured parallelism buys nothing.
     *
     * @return the scenario
     */
    public static Scenario sequencingPolicyOrderWiredDefault() {
        return sequencingArm(SEQUENCING_POLICY_ORDER_WIRED_DEFAULT,
                             "The wired default sequencing policy on a Dynamic Consistency Boundary store",
                             "wired-default",
                             new HierarchicalSequencingPolicy<>(SequentialPerAggregatePolicy.INSTANCE,
                                                                SequentialPolicy.INSTANCE));
    }

    /**
     * An explicitly chosen no-op sequencing policy, which imposes no ordering on anything.
     *
     * @return the scenario
     */
    public static Scenario sequencingPolicyOrderNoOp() {
        return sequencingArm(SEQUENCING_POLICY_ORDER_NO_OP,
                             "An explicit no-op sequencing policy",
                             "no-op",
                             NoOpSequencingPolicy.INSTANCE);
    }

    /**
     * The per-aggregate sequencing policy alone, on a store that populates no aggregate identifier.
     * <p>
     * With no fallback behind it the policy can resolve nothing, which is the situation the arm exists to observe.
     *
     * @return the scenario
     */
    public static Scenario sequencingPolicyOrderPerAggregate() {
        return sequencingArm(SEQUENCING_POLICY_ORDER_PER_AGGREGATE,
                             "The per-aggregate sequencing policy alone on a Dynamic Consistency Boundary store",
                             "per-aggregate",
                             SequentialPerAggregatePolicy.INSTANCE);
    }

    private static Scenario sequencingArm(String id,
                                          String name,
                                          String arm,
                                          SequencingPolicy<? super EventMessage> policy) {
        return Scenario.builder(id, name)
                       .claims("C32", "C33", "C34")
                       .workload(() -> SequencedWorkload.with(arm, policy))
                       .faults(FaultSchedule.none(Duration.ofSeconds(8)))
                       .oracles(OrderChecker.SEQUENCE_KEY_ORDER_PRESERVED)
                       .seed(1L)
                       .budget(Tier.SMOKE, new TierBudget(180, 1, Duration.ofSeconds(45)))
                       .budget(Tier.RELEASE, new TierBudget(5_000, 20, Duration.ofMinutes(5)))
                       .build();
    }

    /**
     * A reader reading the store as fast as it can while hundred-event batches are being committed.
     * <p>
     * The store's documented guarantee is that events become visible after the commit call, which says nothing about
     * whether a batch becomes visible all at once. This arm measures the difference: it counts the reads in which a
     * batch was visible in part and not in whole.
     *
     * @return the scenario
     */
    public static Scenario partialBatchVisibility() {
        return Scenario.builder(PARTIAL_BATCH_VISIBILITY,
                                "One writer committing hundred-event batches while a reader reads at maximum rate")
                       .claims("C4", "C9")
                       .workload(BatchWorkload::wideBatchesUnderAPollingReader)
                       .faults(FaultSchedule.none(Duration.ofSeconds(20)))
                       .oracles(VisibilityChecker.NO_VISIBILITY_BEFORE_COMMIT,
                                AppendOutcomeChecker.REJECTED_APPEND_LEAVES_NO_EVENTS)
                       .seed(1L)
                       // Three seeds rather than one, because the window is narrow and the arm must not depend on
                       // catching it in a single run. A read only sees a batch half-written if it opens its stream
                       // during a commit, which is a small fraction of the run: roughly one read in four hundred was
                       // measured. Three runs put several thousand reads behind the observation instead of one
                       // thousand, which is the difference between an arm that reports the gap and an arm that
                       // sometimes misses it.
                       .budget(Tier.SMOKE, new TierBudget(4_000, 3, Duration.ofSeconds(60)))
                       .budget(Tier.RELEASE, new TierBudget(40_000, 20, Duration.ofMinutes(10)))
                       .build();
    }

    /**
     * Several nodes booting at the same instant against a token store that holds nothing.
     * <p>
     * Genesis is the window the steady-state guarantees say nothing about. Every claim rule in the framework is
     * written for a store that already has rows in it: a claim may be taken when the entry is unowned, owned by the
     * same node, or expired, and all three presuppose an entry. The first deployment has no entries, so several nodes
     * each discover an empty store, each conclude they must create the segments, and race. The contract for that race
     * is that initialising a segment that already exists fails, which makes exactly one of them the winner -- and it
     * is explicit that the outcome is undefined when the rows exist but belong to somebody else.
     * <p>
     * What the arm judges: that the store ends up holding exactly the configured number of segments and no more, that
     * ownership held from the first instant rather than from after things settled, and that nothing was lost or
     * doubled on the way to the projection. Ownership from the first instant is the part that matters. Checking it
     * after the cluster has calmed down would skip the only interesting window.
     * <p>
     * It runs on the claim-capable backend, and it has to: the in-heap token store grants every claim to everybody,
     * so this whole arm would pass against it without testing anything. The workload sequences by account so that
     * events genuinely spread across segments; under the framework's wired default every event on a store speaking
     * the Dynamic Consistency Boundary protocol resolves to one identifier, one identifier hashes to one segment, and
     * a four-segment cluster would quietly be a one-segment cluster.
     * <p>
     * The liveness horizon is two seconds, and the basis is the coordinator's own idle re-poll, which is a hardcoded
     * five hundred milliseconds and does not compress with anything. An event committed just after a coordinator went
     * idle waits for that re-poll before anybody looks, so no honest horizon can be below it; two seconds is four
     * times it, which leaves room for a claim handover on top without leaving so much room that a real stall would
     * slip through. Measured against it, the slowest commit-to-delivery latency this arm produces is a few hundred
     * milliseconds.
     *
     * @return the scenario
     */
    public static Scenario concurrentBootstrap() {
        return bootstrapArm(CONCURRENT_BOOTSTRAP,
                            "Four nodes booting at once against an empty token store",
                            FaultSchedule.none(Duration.ofSeconds(20)));
    }

    /**
     * The same bootstrap with one node dropped and brought back while the segments are still being taken.
     * <p>
     * <b>What this arm can and cannot race, stated rather than implied.</b> Creating the segments is over in
     * milliseconds, and a fault window can only open after the world has been built, so nothing the fault plane does
     * can collide with the initialisation itself. What it does collide with is the stampede that follows: four
     * coordinators taking four segments over several claim intervals, rebalancing as they go. Dropping a node in the
     * middle of that, without letting it release anything, and bringing it back under the same identity is a node
     * leaving and rejoining a cluster that has not finished forming.
     * <p>
     * A repeated delivery is expected here and is permitted, because the crash opens a recovery window and the token
     * store and the projection are not one transactional resource. It is still counted and reported, so a run that
     * doubled a hundred deliveries is not confused with one that doubled two.
     *
     * @return the scenario
     */
    public static Scenario concurrentBootstrapWithNodeChurn() {
        FaultSchedule schedule = FaultSchedule.builder()
                                              .warmup(Duration.ZERO)
                                              .window(FaultWindow.immediately("node-leaves-and-rejoins",
                                                                              Duration.ofMillis(400),
                                                                              new NodeCrashFault(1)))
                                              .heal(Duration.ofMillis(200))
                                              .settle(Duration.ofSeconds(20))
                                              .build();
        return bootstrapArm(CONCURRENT_BOOTSTRAP_WITH_CHURN,
                            "Four nodes booting at once, one of them leaving and rejoining mid-stampede",
                            schedule);
    }

    private static Scenario bootstrapArm(String id, String name, FaultSchedule schedule) {
        return Scenario.builder(id, name)
                       .claims("C18", "C19", "C20", "C36", "M16")
                       .workload(LedgerWorkload::sequencedPerAccount)
                       .backend(HsqldbTokenStoreBackend.NAME)
                       .nodes(BOOTSTRAP_NODES)
                       .deliveryMode(DeliveryMode.AT_LEAST_ONCE_NO_LOSS)
                       .livenessHorizon(Duration.ofSeconds(2))
                       // A claim timeout of a hundred milliseconds is fine against a store that answers in
                       // nanoseconds and hopeless against one reached over JDBC: claims lapse while their owner waits
                       // for the extension it already issued, and the run turns into nodes stealing from each other.
                       // Widened together so the ratio the compression exists to preserve survives.
                       .timescale(HuntTimescale.compressed()
                                               .withClaimTimings(Duration.ofSeconds(2), Duration.ofMillis(400)))
                       .faults(schedule)
                       .oracles(OwnershipChecker.AT_MOST_ONE_SEGMENT_OWNER,
                                DeliveryChecker.NO_COMMITTED_EVENT_GOES_UNDELIVERED,
                                DeliveryChecker.DUPLICATE_DELIVERY_ONLY_INSIDE_RECOVERY_WINDOW,
                                LivenessChecker.COMMITTED_EVENT_DELIVERED_WITHIN_HORIZON,
                                LivenessChecker.ACCEPTED_COMMAND_COMPLETES,
                                ConservationChecker.LEDGER_CONSERVES_TOTAL_BALANCE,
                                ConservationChecker.PROJECTION_MATCHES_FOLD_OF_COMMITTED_EVENTS)
                       .seed(1L)
                       .budget(Tier.SMOKE, new TierBudget(600, 3, Duration.ofSeconds(90)))
                       .budget(Tier.RELEASE, new TierBudget(20_000, 100, Duration.ofMinutes(15)))
                       .build();
    }
}
