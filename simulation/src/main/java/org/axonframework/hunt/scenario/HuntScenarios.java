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
import org.axonframework.hunt.checker.DurabilityChecker;
import org.axonframework.hunt.checker.FaultLandingChecker;
import org.axonframework.hunt.checker.LivenessChecker;
import org.axonframework.hunt.checker.ModelConformanceChecker;
import org.axonframework.hunt.checker.OrderChecker;
import org.axonframework.hunt.checker.OwnershipChecker;
import org.axonframework.hunt.checker.StoredProgressChecker;
import org.axonframework.hunt.checker.VisibilityChecker;
import org.axonframework.hunt.fault.AfterCommitFailureFault;
import org.axonframework.hunt.fault.AppendRejectionFault;
import org.axonframework.hunt.fault.DuplicatedAppendFault;
import org.axonframework.hunt.fault.Fault;
import org.axonframework.hunt.fault.FaultSchedule;
import org.axonframework.hunt.fault.FaultWindow;
import org.axonframework.hunt.fault.InjectedLatencyFault;
import org.axonframework.hunt.fault.LateCommitFault;
import org.axonframework.hunt.fault.NodeCrashFault;
import org.axonframework.hunt.fault.PrepareCommitFailureFault;
import org.axonframework.hunt.fault.ProcessorResetFault;
import org.axonframework.hunt.fault.SegmentSplitMergeFault;
import org.axonframework.hunt.fault.StoreCrashFault;
import org.axonframework.hunt.fault.StorePartitionFault;
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

    /**
     * How long a claim survives without a refresh in every cluster arm.
     * <p>
     * The compressed arm's hundred milliseconds is fine against a store answering in nanoseconds and hopeless against
     * one reached over a JDBC round trip: claims lapse while their owner waits for the extension it already issued, and
     * the run turns into nodes stealing from each other for no reason. Two seconds keeps the ratio to the extension
     * threshold that the compression exists to preserve, and it is the unit the skew arms are expressed in.
     */
    public static final Duration CLUSTER_CLAIM_TIMEOUT = Duration.ofSeconds(2);

    /**
     * The identifier of the arm contending for segments with every node's clock in step.
     */
    public static final String SEGMENT_OWNER_NO_SKEW = "at_most_one_segment_owner_with_skew_none";

    /**
     * The identifier of the arm contending for segments with one node's clock half a claim timeout ahead.
     */
    public static final String SEGMENT_OWNER_HALF_TIMEOUT_SKEW = "at_most_one_segment_owner_with_skew_half_timeout";

    /**
     * The identifier of the arm contending for segments with one node's clock twice a claim timeout ahead, which is
     * expected to break ownership rather than hold it.
     */
    public static final String SEGMENT_OWNER_DOUBLE_TIMEOUT_SKEW = "at_most_one_segment_owner_with_skew_double_timeout";

    /**
     * The identifier of the arm rewinding a processor to the start of the stream while the workload writes.
     */
    public static final String REPLAY_SEES_FULL_PREFIX = "replay_sees_full_prefix_and_flags_redelivery";

    /**
     * The identifier of the same rewind issued on one node while the rest of the cluster keeps processing.
     */
    public static final String REPLAY_SEES_FULL_PREFIX_CROSS_NODE =
            "replay_sees_full_prefix_and_flags_redelivery_cross_node";

    /**
     * The identifier of the arm splitting and merging segments while the workload writes.
     */
    public static final String SPLIT_MERGE_UNDER_LOAD = "split_merge_no_loss_no_dup_under_load";

    /**
     * The identifier of the arm asking a single-segment processor to merge, which the framework must refuse.
     */
    public static final String MERGE_ONLY_SINGLE_SEGMENT = "split_merge_no_loss_no_dup_under_load_single_segment";

    /**
     * The identifier of the arm holding commits open past the store's gap timeout, configured from the framework's own
     * defaults.
     */
    public static final String NO_EVENT_SKIPPED_BY_GAP_TIMEOUT = "no_event_skipped_by_gap_timeout";

    /**
     * The identifier of the same arm against the store configured the way Spring Boot's auto-configuration configures it,
     * whose gap timeout and maximum gap offset are the framework defaults exchanged for each other.
     */
    public static final String NO_EVENT_SKIPPED_BY_GAP_TIMEOUT_SPRING_DEFAULTS =
            "no_event_skipped_by_gap_timeout_spring_defaults";

    /**
     * The gap timeout the arm configured from the framework's own defaults runs at, compressed from the shipped 60000ms.
     */
    public static final int GAP_TIMEOUT_CORE_DEFAULTS_MS = 1_000;

    /**
     * The gap timeout the Spring-Boot-configured arm runs at, compressed from that path's 10000ms default by the same
     * factor.
     */
    public static final int GAP_TIMEOUT_SPRING_DEFAULTS_MS = 167;

    /**
     * The identifier of the arm killing the store outright while a workload appends to it.
     */
    public static final String CRASH_RECOVERY_NO_ACKED_LOSS = "crash_recovery_no_acked_loss_postgres";

    /**
     * The identifier of the arm cutting the network between the application and its store, repeatedly.
     */
    public static final String COMMIT_ACK_MATCHES_DURABILITY_UNDER_PARTITION =
            "commit_ack_matches_durability_under_partition";

    /**
     * The identifier of the arm cutting the network under a streaming read side and asking what the stream resumed with.
     */
    public static final String STREAM_RESUME_NO_LOSS_NO_SILENT_DUP =
            "axonserver_stream_resume_no_loss_no_silent_dup";

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
                       concurrentBootstrapWithNodeChurn(),
                       segmentOwnerWithoutSkew(),
                       segmentOwnerWithHalfTimeoutSkew(),
                       segmentOwnerWithDoubleTimeoutSkew(),
                       replaySeesFullPrefix(),
                       replaySeesFullPrefixAcrossNodes(),
                       splitMergeUnderLoad(),
                       mergeOnlyOnASingleSegment(),
                       noEventSkippedByGapTimeout(),
                       noEventSkippedByGapTimeoutSpringDefaults(),
                       crashRecoveryNoAckedLoss(),
                       commitAckMatchesDurabilityUnderPartition(),
                       streamResumeNoLossNoSilentDuplicate());
    }

    /**
     * Cuts the network under a running streaming read side and asks what the stream resumed with.
     * <p>
     * <b>The question is about the read side, which is what makes it different from the two arms next to it.</b> The
     * partition arm asks whether a commit acknowledgement survives a broken connection; the kill arm asks whether the
     * store kept what it acknowledged. This one asks what happens to a consumer whose event stream is severed
     * mid-flight: when the connection comes back and the stream resumes, no event the store holds may go undelivered, and
     * no event may arrive twice unless the history itself accounts for it -- a position the store handed back that is
     * behind something the segment had already delivered. That is the whole content of the "silent" in the identifier: a
     * duplicate is acceptable if it is explained, and a duplicate nothing explains is a defect the deployment cannot
     * compensate for because nothing told it to.
     * <p>
     * The cut is timed on a wall clock rather than aimed at a commit boundary, which is the opposite choice from the
     * partition arm's and is deliberate: the target here is a long-running stream rather than a millisecond-wide commit
     * window, so a cut placed by elapsed time lands inside one every time. Eight cuts spread over the fault window give
     * the read side several severances and several resumptions rather than one of each.
     * <p>
     * Declared on the breakable Axon Server arm, because it is the only store in this tree whose read side is reached
     * over a network that can be taken away while the store keeps running.
     *
     * @return the stream-resume scenario
     */
    public static Scenario streamResumeNoLossNoSilentDuplicate() {
        FaultSchedule schedule = FaultSchedule.builder()
                                             // Long enough for the read side to have claimed its segments and to be
                                             // streaming, so a cut severs a stream rather than delaying its creation.
                                             .warmup(Duration.ofSeconds(5))
                                             .window(FaultWindow.immediately(
                                                     "stream-cut",
                                                     Duration.ofSeconds(40),
                                                     new StorePartitionFault(Duration.ofSeconds(2), 8,
                                                                             Duration.ofSeconds(2))))
                                             // The connector reconnects on its own beat and the processor re-claims on
                                             // its own; the heal has to outlive both or the drain measures the outage.
                                             .heal(Duration.ofSeconds(15))
                                             .settle(Duration.ofSeconds(90))
                                             .build();
        return Scenario.builder(STREAM_RESUME_NO_LOSS_NO_SILENT_DUP,
                                "A severed event stream resumes without losing an event or repeating an unexplained one")
                       .claims("M9", "C15", "C16", "C17")
                       .backend("axonserver-chaos")
                       // Idempotent, because a severed stream resuming from the last durable token is entitled to repeat
                       // the batch it was in the middle of. A projection that added the amount again would report the
                       // framework's own documented at-least-once behaviour as money appearing out of nowhere.
                       .workload(LedgerWorkload::sequencedPerAccountIdempotent)
                       .timescale(clusterTimescale())
                       .deliveryMode(DeliveryMode.AT_LEAST_ONCE_NO_LOSS)
                       // Eight two-second cuts with two seconds between them, and the read side stops for every one of
                       // them. The horizon covers the whole fault phase, the heal, and a claim timeout.
                       .livenessHorizon(Duration.ofSeconds(60))
                       .faults(schedule)
                       .oracles(DeliveryChecker.NO_COMMITTED_EVENT_GOES_UNDELIVERED,
                                DeliveryChecker.DUPLICATE_DELIVERY_ONLY_INSIDE_RECOVERY_WINDOW,
                                FaultLandingChecker.DECLARED_FAULTS_LAND)
                       .seed(1L)
                       .budget(Tier.SMOKE, new TierBudget(400, 2, Duration.ofMinutes(6)))
                       .budget(Tier.RELEASE, new TierBudget(4_000, 20, Duration.ofMinutes(40)))
                       .build();
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

    /**
     * Nodes contending for the same segments with every clock in step, which is the control arm.
     * <p>
     * With no skew declared, two nodes holding one segment at the same moment for any measurable time is a defect and is
     * asserted as one. What the arm still contains is a mid-batch claim handover: one node is frozen inside its handler
     * for longer than a claim survives, so its claim lapses while it is part-way through a batch and another node takes
     * the segment over. That interleaving is the only one in which the stored token is read back during a run, and it is
     * therefore the only one in which a batch whose effects were committed without its progress becomes visible at all.
     *
     * @return the scenario
     */
    public static Scenario segmentOwnerWithoutSkew() {
        return segmentOwnerArm(SEGMENT_OWNER_NO_SKEW,
                               "Four nodes contending for eight segments with a mid-batch claim handover",
                               Duration.ZERO, Duration.ZERO, 4, 8);
    }

    /**
     * The same contention with one node's clock emulated half a claim timeout ahead.
     * <p>
     * That node considers every other node's claim expired half a timeout early, so it can take a claim that is still
     * legitimately held -- but never more than half a timeout before it would have lapsed anyway. The arm declares that
     * bound as its tolerance and lets the oracle judge it, which is the prediction the emulation makes rather than a
     * fudge factor: a node reading a delta ahead steals at most a delta early, so the overlap can never exceed the delta.
     * <p>
     * Whether it steals anything at all is a matter of how recently the owner last refreshed. An owner refreshes every
     * batch and, when idle, every extension threshold, so a skew smaller than the margin between the claim timeout and
     * that refresh rate often finds nothing to take. The arm reports what it measured rather than assuming either way.
     *
     * @return the scenario
     */
    public static Scenario segmentOwnerWithHalfTimeoutSkew() {
        return segmentOwnerArm(SEGMENT_OWNER_HALF_TIMEOUT_SKEW,
                               "Four nodes contending with one clock half a claim timeout ahead",
                               CLUSTER_CLAIM_TIMEOUT.dividedBy(2), CLUSTER_CLAIM_TIMEOUT.dividedBy(2), 4, 16);
    }

    /**
     * The same contention with one node's clock emulated twice a claim timeout ahead, which is expected to violate.
     * <p>
     * At twice the timeout the skewed node's own view of expiry is already in the past for every row it reads, so it
     * considers every claim in the store stealable at every moment and takes them regardless of who holds them. This arm
     * declares a tolerance of zero, because the framework states none, so ownership cannot hold -- and the arm does not
     * pretend otherwise: its job is to measure how wide the overlap gets, because a scenario that can only pass is
     * measuring nothing. The number it reports is the answer to a question the framework does not document, which is how
     * much clock skew the claim protocol tolerates.
     *
     * @return the scenario
     */
    public static Scenario segmentOwnerWithDoubleTimeoutSkew() {
        return segmentOwnerArm(SEGMENT_OWNER_DOUBLE_TIMEOUT_SKEW,
                               "Four nodes contending with one clock twice a claim timeout ahead",
                               CLUSTER_CLAIM_TIMEOUT.multipliedBy(2), Duration.ZERO, 4, 16);
    }

    private static Scenario segmentOwnerArm(String id,
                                            String name,
                                            Duration skew,
                                            Duration allowance,
                                            int nodes,
                                            int segments) {
        FaultSchedule schedule = FaultSchedule.builder()
                                              .warmup(Duration.ofMillis(250))
                                              // There is deliberately no stall window here, and the reason is a
                                              // measurement. Freezing a node inside its handler does not cost it a
                                              // claim: extending a claim is the coordinator's job and the coordinator
                                              // is a separate thread that keeps running, so the frozen node's rows stay
                                              // fresh throughout. The stall also only lands while the node is still
                                              // handling events, and this workload issues its whole budget in a few
                                              // hundred milliseconds, so on most seeds the checkpoint was never
                                              // reached and the run reported a declared fault with a fire count of
                                              // zero. A fault that provably cannot take a segment away and lands on
                                              // one seed in three is noise, not evidence; the finding it produced is
                                              // written up instead.
                                              // Dropping the node's threads is what really lapses a claim, because
                                              // nothing is left to extend it. The window outlives the claim timeout on
                                              // purpose: a shorter one lets the node come back and re-take its own
                                              // rows before anybody else could, which is a restart rather than a
                                              // handover. Its last batch never stored its progress, so whoever takes
                                              // the segment over resumes from a token that is behind the effects the
                                              // dead node had already applied -- the interleaving the whole arm exists
                                              // for.
                                              .window(FaultWindow.immediately(
                                                      "node-dropped-until-its-claims-lapse",
                                                      CLUSTER_CLAIM_TIMEOUT.plusSeconds(1),
                                                      NodeCrashFault.busiest()))
                                              .heal(Duration.ofMillis(500))
                                              .settle(Duration.ofSeconds(25))
                                              .build();
        return clusterArm(id, name)
                .claims("C15", "C17", "C18", "C19", "C20", "C21", "C22", "C38", "M1", "M3")
                // A claim really changing hands means a redelivery, which the framework says the handler must absorb.
                // A projection that added the amount twice would report the framework's own documented at-least-once
                // behaviour as broken arithmetic.
                .workload(LedgerWorkload::sequencedPerAccountIdempotent)
                .nodes(nodes)
                .segments(segments)
                // Two different shapes, because the two questions need different ones.
                //
                // Without skew: a little more than an even share, so the cluster's capacity exceeds the segment count
                // and a lapsed claim is contested rather than left lying there, while every node still has work. An
                // even share settles and nobody ever wants a segment somebody else holds.
                //
                // With skew: no cap at all, which is the framework's shipped default. Every node then wants every
                // segment, so the skewed node is hungry whatever the boot order gave it and steals deterministically.
                // A capped cluster makes the arm a coin flip -- measured on this harness, the skewed node happened to
                // be one of the nodes that filled its cap and stole nothing at all in one run out of three, which is
                // exactly the kind of arm that reports a clean pass for the wrong reason.
                .segmentsPerNode(skew.isZero() ? Math.max(1, (segments / nodes) + 1) : segments)
                // The emulated skew and the tolerance the oracle applies are two separate numbers. An arm declaring
                // the two equal is testing the prediction the emulation makes -- a node reading delta ahead can take a
                // claim at most delta before it lapses, so the overlap can never exceed delta -- and the oracle judges
                // it. An arm declaring a tolerance of zero is measuring instead: it reports how wide the overlap gets
                // and is expected to break ownership.
                .timescale(clusterTimescale().withEmulatedClockSkew(skew).withSkewAllowance(allowance))
                .faults(schedule)
                .oracles(OwnershipChecker.AT_MOST_ONE_SEGMENT_OWNER,
                         OwnershipChecker.DELIVERY_ATTRIBUTED_TO_SEGMENT_OWNER,
                         StoredProgressChecker.STORED_TOKEN_NEVER_REGRESSES,
                         StoredProgressChecker.STORED_TOKEN_COVERS_DELIVERED_EVENTS,
                         StoredProgressChecker.CLAIM_HANDOVER_REWINDS_AT_MOST_ONE_BATCH,
                         DeliveryChecker.NO_COMMITTED_EVENT_GOES_UNDELIVERED,
                         DeliveryChecker.DUPLICATE_DELIVERY_ONLY_INSIDE_RECOVERY_WINDOW,
                         FaultLandingChecker.DECLARED_FAULTS_LAND,
                         ConservationChecker.LEDGER_CONSERVES_TOTAL_BALANCE)
                .budget(Tier.SMOKE, new TierBudget(2_000, 3, Duration.ofSeconds(150)))
                .budget(Tier.RELEASE, new TierBudget(20_000, 100, Duration.ofMinutes(15)))
                .build();
    }

    /**
     * A processor rewound to the start of the stream while the workload is still writing.
     * <p>
     * Two things are being falsified at once. The framework refuses a reset on a running processor, and the arm asks a
     * running one to reset so the refusal is recorded rather than assumed. Then it shuts every node down, resets, and
     * lets the replay run: after it settles the projection must equal the fold of the whole committed history, and every
     * redelivered event must be one the framework itself flagged as a replay.
     * <p>
     * The projection clears itself on reset, which is what a real projection does and what makes the conservation law a
     * statement about the framework. A projection that kept its balances would add a second copy of every transfer and
     * the arithmetic would report the replay as money appearing out of nowhere.
     *
     * @return the scenario
     */
    public static Scenario replaySeesFullPrefix() {
        return replayArm(REPLAY_SEES_FULL_PREFIX,
                         "A processor rewound to the start of the stream with the cluster stopped first",
                         true);
    }

    /**
     * The same rewind issued on one node while every other node keeps processing.
     * <p>
     * The framework's precondition is that the processor is not running, and it checks that on the local virtual machine
     * only: nothing stops another instance of the same processor from working through the stream while the reset rewrites
     * every token. This arm does exactly that and <b>records what happens without asserting that it is safe</b>, because
     * the framework makes no promise either way. If it corrupts something, that is a finding; if it does not, that is
     * worth knowing too.
     *
     * @return the scenario
     */
    public static Scenario replaySeesFullPrefixAcrossNodes() {
        return replayArm(REPLAY_SEES_FULL_PREFIX_CROSS_NODE,
                         "A processor rewound on one node while the rest of the cluster keeps processing",
                         false);
    }

    private static Scenario replayArm(String id, String name, boolean stopEveryNodeFirst) {
        FaultSchedule schedule = FaultSchedule.builder()
                                              // Long enough that the read side has really caught up with part of the
                                              // stream before the rewind, or the replay has nothing to redeliver and
                                              // the arm proves nothing about redelivery.
                                              .warmup(Duration.ofMillis(1_500))
                                              .window(FaultWindow.immediately(
                                                      "rewind",
                                                      Duration.ofMillis(1_500),
                                                      new ProcessorResetFault(0, stopEveryNodeFirst)))
                                              .heal(Duration.ofMillis(500))
                                              .settle(Duration.ofSeconds(30))
                                              .build();
        return clusterArm(id, name)
                .claims("C26", "C27", "C28", "M15")
                .nodes(2)
                .segments(4)
                // The rewind is a redelivery of the whole stream, and a run that also loses a claim redelivers part of
                // it a third time; a projection that added the amount again on each would report the framework's own
                // documented behaviour as broken arithmetic.
                .workload(LedgerWorkload::sequencedPerAccountIdempotent)
                // The arm deliberately stops the cluster for the length of the rewind, so an event committed just before
                // it waits the whole window plus the coordinator's own idle re-poll before anybody looks at it again. No
                // honest horizon can sit below that sum, and this one is roughly three times it.
                .livenessHorizon(Duration.ofSeconds(15))
                .faults(schedule)
                .oracles(DeliveryChecker.NO_COMMITTED_EVENT_GOES_UNDELIVERED,
                         DeliveryChecker.DUPLICATE_DELIVERY_ONLY_INSIDE_RECOVERY_WINDOW,
                         StoredProgressChecker.STORED_TOKEN_NEVER_REGRESSES,
                         FaultLandingChecker.DECLARED_FAULTS_LAND,
                         ConservationChecker.LEDGER_CONSERVES_TOTAL_BALANCE,
                         ConservationChecker.PROJECTION_MATCHES_FOLD_OF_COMMITTED_EVENTS)
                .budget(Tier.SMOKE, new TierBudget(400, 3, Duration.ofSeconds(120)))
                .budget(Tier.RELEASE, new TierBudget(10_000, 50, Duration.ofMinutes(15)))
                .build();
    }

    /**
     * Segments split and merged over and over while the workload writes and a claim changes hands.
     * <p>
     * A split hands one segment's work to two and a merge hands two segments' work to one, both while events keep
     * arriving. What must survive it is that the union of every segment's deliveries covers every committed event, that
     * nothing is delivered twice outside a recorded handover, and that a key's events stay in order even though the
     * segment carrying them changed underneath.
     * <p>
     * <b>The per-node segment cap has headroom on purpose.</b> A split raises the segment count, and a cluster whose
     * total capacity equals the old count would leave the new segment unclaimable for the rest of the run -- a liveness
     * failure the harness caused. Two nodes may hold three segments each, so the four-segment run can absorb two
     * outstanding splits.
     *
     * @return the scenario
     */
    /**
     * The same cluster asked only to merge, so the framework's refusal on a single-segment processor is observable.
     * <p>
     * A storm that split first would have handed the processor a second segment to merge with before the question was
     * asked, and the refusal would never have been reached.
     *
     * @return the scenario
     */
    public static Scenario mergeOnlyOnASingleSegment() {
        FaultSchedule schedule = FaultSchedule.builder()
                                              .warmup(Duration.ofMillis(400))
                                              .window(FaultWindow.immediately(
                                                      "merge-storm",
                                                      Duration.ofSeconds(3),
                                                      SegmentSplitMergeFault.mergesOnly(0, Duration.ofMillis(500))))
                                              .heal(Duration.ofMillis(500))
                                              .settle(Duration.ofSeconds(20))
                                              .build();
        return splitMergeUnderLoad().withIdentifier(MERGE_ONLY_SINGLE_SEGMENT)
                                    .withNodesAndSegments(1, 1)
                                    .withFaults(schedule);
    }

    public static Scenario splitMergeUnderLoad() {
        FaultSchedule schedule = FaultSchedule.builder()
                                              // Short, because the split has to land while the read side is still
                                              // behind the write side. A segment's children inherit its stored token, so
                                              // a split over a stream the projection has already caught up with creates
                                              // two segments with nothing left to hand them: the instruction succeeds and
                                              // the arm observes nothing. This workload issues its whole budget in a few
                                              // hundred milliseconds, so the window that matters is early and narrow.
                                              .warmup(Duration.ofMillis(150))
                                              .window(FaultWindow.immediately(
                                                      "split-and-merge-storm",
                                                      Duration.ofSeconds(4),
                                                      new SegmentSplitMergeFault(0, Duration.ofMillis(300))))
                                              .heal(Duration.ofSeconds(1))
                                              .settle(Duration.ofSeconds(30))
                                              .build();
        return clusterArm(SPLIT_MERGE_UNDER_LOAD,
                          "Segments split and merged repeatedly while the workload writes")
                .claims("C23", "C24", "C25", "C38", "M10")
                .nodes(2)
                .segments(4)
                .segmentsPerNode(3)
                // A merge hands the merged segment the lower of the two halves' tokens, so events the further-ahead half
                // had already handled arrive again. That is the merge's own design, and a projection that added the
                // amount twice would report it as broken arithmetic.
                .workload(LedgerWorkload::sequencedPerAccountIdempotent)
                // A split blocks re-claim of the segment it is splitting until it completes, under a ceiling of a
                // hardcoded minute that no configuration compresses, and the segment's work stops until a node picks the
                // children up on its own claim beat. An event committed into a segment that is mid-split therefore waits
                // for the instruction plus a claim interval plus the coordinator's idle re-poll before anybody looks at
                // it. Measured on this arm, the slowest such wait is a little over four seconds, so the horizon is set
                // well clear of it rather than just above it.
                .livenessHorizon(Duration.ofSeconds(15))
                .faults(schedule)
                .oracles(DeliveryChecker.NO_COMMITTED_EVENT_GOES_UNDELIVERED,
                         DeliveryChecker.DUPLICATE_DELIVERY_ONLY_INSIDE_RECOVERY_WINDOW,
                         OwnershipChecker.AT_MOST_ONE_SEGMENT_OWNER,
                         StoredProgressChecker.STORED_TOKEN_NEVER_REGRESSES,
                         FaultLandingChecker.DECLARED_FAULTS_LAND,
                         ConservationChecker.LEDGER_CONSERVES_TOTAL_BALANCE)
                .budget(Tier.SMOKE, new TierBudget(4_000, 3, Duration.ofSeconds(150)))
                .budget(Tier.RELEASE, new TierBudget(20_000, 50, Duration.ofMinutes(20)))
                .build();
    }

    /**
     * Concurrent writers over a real store whose commits are held open past its gap timeout.
     * <p>
     * <b>This is the scenario the plan expects most from, and the mechanism is a comparison against the wall clock.</b> The
     * aggregate-based engine's global index comes from a database sequence taken before its transaction commits, so a
     * reader routinely sees index n+1 while index n is a hole that will later fill. Recording that hole as a gap is what
     * makes the reader come back for it, and whether it records one is decided by
     * {@code allowGaps = timestamp.isAfter(now - gapTimeout)}: an event whose own message timestamp has aged past the
     * threshold is read as one whose neighbours are never coming, and the hole below it is never recorded at all. When the
     * held transaction commits, nobody is looking for it.
     * <p>
     * Holding a commit open for longer than the gap timeout drives both halves at once -- the hole outlives the timeout,
     * and the event visible above it has aged past the threshold before it is first read -- so a single delay reaches a
     * skip that would otherwise need a coincidence.
     * <p>
     * <b>The oracle is loss and nothing else.</b> Every event of every acknowledged append must reach the projection.
     * A skipped index is not late: the reader's token has already advanced past it and holds no gap for it, so nothing
     * will ever ask for it again, and the run's drain reports the read side as having stopped rather than as being behind.
     * That distinction is what lets loss be decided here instead of declined.
     * <p>
     * <b>Two configuration arms, because the two configuration paths disagree.</b> The framework's own configuration record
     * and Spring Boot's auto-configuration default the gap timeout and the maximum gap offset to each other's values, so
     * a deployment's gap behaviour depends on how it was assembled. Running one arm and reporting the guarantee would be
     * reporting a result from a configuration half the deployments do not have.
     *
     * @return the scenario, on the arm configured from the framework's own defaults
     */
    public static Scenario noEventSkippedByGapTimeout() {
        return gapTimeoutArm(NO_EVENT_SKIPPED_BY_GAP_TIMEOUT,
                             "postgres-jpa-chaos",
                             GAP_TIMEOUT_CORE_DEFAULTS_MS);
    }

    /**
     * The same hunt against the store configured the way Spring Boot's auto-configuration configures it.
     * <p>
     * Finding F-1 is that the two configuration paths default the gap timeout and the maximum gap offset to each other's
     * values. This arm is the half of the differential that says what that costs.
     *
     * @return the scenario
     */
    public static Scenario noEventSkippedByGapTimeoutSpringDefaults() {
        return gapTimeoutArm(NO_EVENT_SKIPPED_BY_GAP_TIMEOUT_SPRING_DEFAULTS,
                             "postgres-jpa-chaos-spring-defaults",
                             GAP_TIMEOUT_SPRING_DEFAULTS_MS);
    }

    /**
     * The settings both gap arms share, with only the store's gap timeout differing between them.
     * <p>
     * The hold is twice the arm's gap timeout, because a hold shorter than the timeout opens a hole that is correctly
     * filled and proves nothing, and a hold equal to it lands on the comparison's own boundary. Every commit is held
     * rather than a sample of them: the skip needs two temporally adjacent indexes both to have aged, and sampling turns
     * a reliable arm into one that reaches its own mechanism a fraction of the time.
     */
    private static Scenario gapTimeoutArm(String id, String backend, int gapTimeoutMs) {
        Duration hold = Duration.ofMillis(2L * gapTimeoutMs);
        FaultSchedule schedule = FaultSchedule.builder()
                                             // No warmup. The hold has to be in place for the writers' very first
                                             // commits, because a hole opened before it is installed is filled correctly
                                             // and the arm has to wait for the next one.
                                             .window(FaultWindow.immediately("held-commits",
                                                                             Duration.ofSeconds(60),
                                                                             new LateCommitFault(hold, 1.0, 1L)))
                                             .heal(hold)
                                             .settle(Duration.ofSeconds(45))
                                             .build();
        return Scenario.builder(id, "Events committed later than the gap timeout must still all be delivered")
                       .claims("C13", "C14", "M2")
                       .backend(backend)
                       .workload(LedgerWorkload::sequencedPerAccount)
                       .timescale(clusterTimescale())
                       .deliveryMode(DeliveryMode.AT_LEAST_ONCE_NO_LOSS)
                       // Every commit is held for twice the gap timeout, so an event's own journey from append to
                       // readable is at least that long before the read side has looked at anything. The horizon is that
                       // hold plus a claim timeout plus the coordinator's hardcoded idle re-poll, rounded up.
                       .livenessHorizon(hold.plus(CLUSTER_CLAIM_TIMEOUT).plusSeconds(1))
                       .faults(schedule)
                       .oracles(DeliveryChecker.NO_COMMITTED_EVENT_GOES_UNDELIVERED,
                                DurabilityChecker.ACKNOWLEDGED_APPEND_IS_DURABLE,
                                FaultLandingChecker.DECLARED_FAULTS_LAND)
                       .seed(1L)
                       // Small on purpose. Every commit waits twice the gap timeout, so the budget buys wall-clock time
                       // rather than commands, and one skip is as much a finding as a hundred.
                       .budget(Tier.SMOKE, new TierBudget(120, 2, Duration.ofMinutes(5)))
                       .budget(Tier.RELEASE, new TierBudget(600, 20, Duration.ofMinutes(30)))
                       .build();
    }

    /**
     * A store killed outright while a workload is appending to it, and then brought back.
     * <p>
     * <b>The question is not whether the framework handles an error; it is whether the store kept what the framework said
     * it had kept.</b> An append the client saw succeed has been acknowledged all the way up, and the caller has acted on
     * that. If the process is killed a moment later and the row is not there when it comes back, the acknowledgement was
     * a lie, and no amount of correct error handling above it makes any difference.
     * <p>
     * The oracle is therefore put to the store directly, on a connection of its own, in plain SQL: every event of every
     * acknowledged append must be there exactly once. Asking through the run's own entity manager would be asking a pool
     * of connections to a process that was killed, which measures the harness's recovery rather than the store's.
     * <p>
     * <b>Appends that failed are not held to anything, and that is not a loophole.</b> A kill in the middle of a commit
     * leaves the client with an exception that says nothing about whether the transaction was applied -- the request may
     * have landed, the reply may have been lost -- so either answer from the store is correct. What is not correct is an
     * acknowledgement that turns out to be false, and that is what is being looked for. The run publishes how many
     * appends ended ambiguously, and a run with none of them says so and gives up its pass, because a kill that landed
     * outside every commit window tested nothing.
     *
     * @return the scenario
     */
    public static Scenario crashRecoveryNoAckedLoss() {
        FaultSchedule schedule = FaultSchedule.builder()
                                             // Long enough for the writers to be well into their budget, so the kill
                                             // lands in the middle of an append storm rather than before it or after it.
                                             .warmup(Duration.ofSeconds(3))
                                             .window(FaultWindow.immediately(
                                                     "store-killed",
                                                     Duration.ofSeconds(40),
                                                     new StoreCrashFault(Duration.ofSeconds(2), 2,
                                                                         Duration.ofSeconds(4))))
                                             // Long enough for the connection pool to notice, evict and replace the
                                             // handles it holds to the process that was killed: the pool validates every
                                             // second, and the writers have to be able to finish.
                                             .heal(Duration.ofSeconds(10))
                                             .settle(Duration.ofSeconds(60))
                                             .build();
        return Scenario.builder(CRASH_RECOVERY_NO_ACKED_LOSS,
                                "An append the client saw succeed survives the store being killed")
                       .claims("C35", "C4")
                       .backend("postgres-jpa-chaos")
                       .workload(LedgerWorkload::sequencedPerAccountIdempotent)
                       .timescale(clusterTimescale())
                       .deliveryMode(DeliveryMode.AT_LEAST_ONCE_NO_LOSS)
                       // The store is unreachable for two seconds at a time and twice over, and the read side cannot
                       // move while it is. The horizon is both outages, both recoveries and a claim timeout.
                       .livenessHorizon(Duration.ofSeconds(30))
                       .faults(schedule)
                       .oracles(DurabilityChecker.ACKNOWLEDGED_APPEND_IS_DURABLE,
                                FaultLandingChecker.DECLARED_FAULTS_LAND)
                       .seed(1L)
                       .budget(Tier.SMOKE, new TierBudget(400, 2, Duration.ofMinutes(5)))
                       .budget(Tier.RELEASE, new TierBudget(4_000, 10, Duration.ofMinutes(30)))
                       .build();
    }

    /**
     * The network between the application and its store cut and healed repeatedly, with the store left running.
     * <p>
     * <b>This is the arm that produces an acknowledgement nobody can interpret, and no other fault in the suite can.</b>
     * An injected refusal tells the caller clearly that the append did not happen. A torn socket tells it nothing at all:
     * the store is still there, still holding the transaction, and it will decide on its own after the caller has stopped
     * listening. So the client's verdict set has three values rather than two, and the store must agree with the two that
     * are decidable while being free to answer either way on the third.
     * <p>
     * The verdicts are frozen from the history the run recorded as it happened, which is before anything is healed; the
     * store is asked afterwards. An append the client saw succeed must be present exactly once, one the store decided
     * against must be absent, and one whose conversation was lost may be either.
     * <p>
     * <b>A run with no ambiguous append is not a pass.</b> The whole claim is about the window between a commit being sent
     * and its reply arriving, and a partition that missed every one of those windows measured a system nothing happened
     * to. The oracle reports it and the run gives up its verdict, which is the honest answer.
     *
     * @return the scenario
     */
    public static Scenario commitAckMatchesDurabilityUnderPartition() {
        FaultSchedule schedule = FaultSchedule.builder()
                                             .warmup(Duration.ofSeconds(2))
                                             // Aimed at the commit boundary, not at the clock. A commit window is
                                             // milliseconds wide, so a cut placed by elapsed time lands in one only by
                                             // coincidence -- and an arm that asserts the ambiguity such a cut produces
                                             // is flaky by construction, which is worse than one that does not assert
                                             // it. Cutting from inside the store boundary, after the rows are written
                                             // and before the transaction commits, makes every cut land in a commit.
                                             .window(FaultWindow.immediately(
                                                     "network-cut",
                                                     Duration.ofSeconds(40),
                                                     StorePartitionFault.atCommitBoundary(Duration.ofSeconds(2), 8)))
                                             .heal(Duration.ofSeconds(10))
                                             .settle(Duration.ofSeconds(60))
                                             .build();
        return Scenario.builder(COMMIT_ACK_MATCHES_DURABILITY_UNDER_PARTITION,
                                "A commit acknowledgement means the store kept it, even across a broken connection")
                       .claims("C4", "C29", "M5")
                       .backend("postgres-jpa-chaos")
                       .workload(LedgerWorkload::sequencedPerAccountIdempotent)
                       .timescale(clusterTimescale())
                       .deliveryMode(DeliveryMode.AT_LEAST_ONCE_NO_LOSS)
                       // Eight cuts of six hundred milliseconds each with two seconds between them, and the read side
                       // stops for every one of them. The horizon covers the whole fault phase and a claim timeout.
                       .livenessHorizon(Duration.ofSeconds(30))
                       .faults(schedule)
                       .oracles(DurabilityChecker.ACKNOWLEDGED_APPEND_IS_DURABLE,
                                AppendOutcomeChecker.REJECTED_APPEND_LEAVES_NO_EVENTS,
                                FaultLandingChecker.DECLARED_FAULTS_LAND)
                       .seed(1L)
                       .budget(Tier.SMOKE, new TierBudget(400, 2, Duration.ofMinutes(5)))
                       .budget(Tier.RELEASE, new TierBudget(4_000, 20, Duration.ofMinutes(30)))
                       .build();
    }

    /**
     * The settings every cluster arm shares: the claim-capable store, the widened claim timings, and the horizon the
     * coordinator's own idle re-poll sets the floor for.
     */
    private static Scenario.Builder clusterArm(String id, String name) {
        return Scenario.builder(id, name)
                       .workload(LedgerWorkload::sequencedPerAccount)
                       .backend(HsqldbTokenStoreBackend.NAME)
                       .deliveryMode(DeliveryMode.AT_LEAST_ONCE_NO_LOSS)
                       .livenessHorizon(Duration.ofSeconds(4))
                       .timescale(clusterTimescale())
                       .seed(1L);
    }

    private static HuntTimescale clusterTimescale() {
        return HuntTimescale.compressed()
                            .withClaimTimings(CLUSTER_CLAIM_TIMEOUT, Duration.ofMillis(400));
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
                       .timescale(clusterTimescale())
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
