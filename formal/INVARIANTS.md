# Axon Hunt -- Invariants

Single source of truth for the properties the hunt suite judges Axon Framework 5 by, for the
history schema every layer records, and for the recipe that adds a new invariant.

Each invariant has a stable **`MachineName`**. That name and its **statement** are reused
character-identical everywhere the invariant appears: this registry, the checker's Javadoc, the
assertion message on the violation, and, when the invariant is also modelled formally, the TLA+
operator name. Drift between those copies is how a suite starts lying about what it verified.

Companion documents:

| Document | Contents |
|---|---|
| `formal/FINDINGS.adoc` | The living findings log: F-numbers, severity, evidence, candidate fix, reproduce command. |
| `formal/HUNT-NOTES.md` | Append-only working notes: determinism seams, API traps, commands that worked. |
| `formal/CANARIES.md` | The mutation campaign: which planted defect each oracle caught, and which escaped. |
| `docs/testing-plans/axon-hunt.md` | The plan: claims C1-C40, missing claims M1-M18, scenarios, coverage matrix. |

---

## 1. Suite constitution

These rules bind every phase. They are not style preferences; each one exists because a
distributed-systems test suite that broke it stopped being able to find bugs.

1. **Never patch the engine.** The suite observes Axon Framework; it does not fix it. A confirmed
   defect becomes a FINDINGS.adoc entry with a candidate fix, plus an expected-gap test that passes
   while the gap exists and flips red when it is closed. A suite that patches what it measures can
   no longer tell you whether the release is broken.
2. **Zero quarantine.** No `@Disabled`, no `@Tag` used to hide a failure, no
   `-Dsurefire.rerunFailingTestsCount`, no silent skip. Every intermittent failure is classified as
   exactly one of: an **engine bug** (reproduces in isolation, becomes a finding), a **harness bug**
   (fixed, and pinned by a regression seed), or a **load artifact** (documented with the evidence
   that it passes in isolation). Quarantine lists are where these suites go to die, and reruns bury
   precisely the flaky-looking real bugs the suite exists to find.
3. **Judge by exit code, not by banner.** `./mvnw -q` suppresses the `BUILD SUCCESS` line, so a
   clean build can print nothing at all. Capture the exit status explicitly and judge on that,
   together with the absence of `<<< ERROR` and `<<< FAILURE`.
4. **A fault without landing evidence makes the run INCONCLUSIVE, never PASS.** Every injected
   fault records proof that it fired: a proxy's API state, a container exit code, a recorded clock
   jump, a fault-injector fire count. A green run under a fault that never landed has verified
   nothing, and reporting it as a pass is worse than reporting nothing.
5. **Unknowns are unknowns.** An operation whose outcome the client could not determine is recorded
   as indeterminate and treated by every checker as may-or-may-not-have-happened. Neither collapsing
   it into failure nor into success is permitted. Trailing operations open at the run boundary are
   never truncated.
6. **Scope determinism claims honestly.** State exactly what a seed fixes and what it does not, on
   both sides of every assertion. See section 2.
7. **Paste real output.** Every claimed result is a result that was run. No verdict is reported from
   a command that was not executed and whose output was not read.

---

## 2. Determinism boundary -- measured, not asserted

What a seed fixes, and what it does not. Overclaiming here is the fastest way to turn a harness bug
into a phantom finding, so nothing in this section is a design intention: it is the output of
`DeterminismProbe`, which runs the same seed twice and diffs the two histories.

Reproduce the measurement:

```bash
./mvnw -q -Phunt -pl simulation -o test -Dtest=DeterminismProbeTest
```

### 2.1 What the probe measured

Scenario: 300 ledger commands over hot accounts, in-memory backend, compressed timescale, seed 77.
The probe compares record sequence (ignoring only `wallTs` and `logicalTs`), operation shape (per
process, per operation, per record type), append verdicts, and the set of events left in the store.

| Property | `REAL_THREADS` | `SINGLE_THREADED` |
|---|---|---|
| Record sequence identical | **no** | **no** |
| Operation shape identical | **no** | **yes** |
| Append verdicts identical (accepted / rejected / unknown counts) | **no** | **yes** |
| Store contents identical | **no** | **yes** |

Verbatim first difference in `SINGLE_THREADED`:

```
record #27 differs: 27|writer-0|commit|INVOKE|null|null
             against 27|projection|deliver|INVOKE|acct-0|null
```

(The index moved from 9 to 27 when the token store began recording its claims: a run now opens with the node's claim
traffic, which pushes the first interleaving between writer and projection further down the file. The property is
unchanged.)

Verbatim differences in `REAL_THREADS` (one run of many; the numbers move every time):

```
record count differs: 2172 against 2134
append verdicts differ: {FAIL=157, OK=139, UNKNOWN=0} against {FAIL=160, OK=136, UNKNOWN=0}
store contents differ: 102 event(s) only in the first run, 96 only in the second
```

### 2.1b What a cluster does to all of that -- measured, on the arm that has one

Scenario: `concurrent_bootstrap_initializes_segments_exactly_once`, four nodes over one in-heap event store and one
in-process HSQLDB token store, six hundred ledger commands, seed 1. Same probe, same diff.

| Property | four nodes, `REAL_THREADS` |
|---|---|
| Record sequence identical | **no** |
| Operation shape identical | **no** |
| Append verdicts identical | **no** |
| Store contents identical | **no** |

Verbatim, from one pair of runs:

```
record count differs: 4247 against 4499
append verdicts differ: {FAIL=335, OK=265, UNKNOWN=0} against {FAIL=299, OK=301, UNKNOWN=0}
store contents differ: 126 event(s) only in the first run, 198 only in the second
```

The operation-shape diff includes the new axis directly: `node-2/claim/FAIL=2 against 1` and
`node-1/claim/INVOKE=2 against 3`. **Which node wins a segment is not a function of the seed, and neither is how many
times a claim is refused.** A cluster adds a second race on top of the one between writers -- the race between
coordinators for the same rows -- and that one decides which node processes which events and therefore how the
projection's work is spread. Nothing about a multi-node run is reproducible from its seed, and the suite claims
nothing about it beyond what this table says.

There is no single-threaded arm for a cluster and there cannot be a useful one. Four coordinators and four workers
against one database is the thing being measured; running it on one thread would measure something else.

The consequence for regression assets is the one already stated in 2.5 and it applies with more force here: a cluster
finding is pinned by its **history file**, never by its seed.

### 2.1c What a real store does to it -- not measured, and not claimed

`DeterminismProbe` has never been run against a container-backed backend, so this document says nothing about whether a
PostgreSQL arm reproduces anything from its seed. Two things make it safe to assume it reproduces less than the in-heap
arms rather than more: the store is reached over a socket, so every latency the schedule depends on is now a network
latency; and the aggregate-based engine's durable order comes from a sequence taken before the transaction commits, so
even the order events end up in is not a function of the order they were offered in.

The consequence is the one already stated in 2.5 and it applies here too: a finding on a real store is pinned by its
**history file**, never by its seed.

### 2.1d The determinism boundary now includes what quiescence is measured against, added in P3b

A run's verdict depends on when the drain decides the read side is done, and until P3b that decision was harness
bookkeeping: an append counted as stored when the engine's `commit()` call returned. On a store that commits in two
phases that number runs ahead of anything a reader can see and never comes back down, so **no container-backed arm ever
reached quiescence at any budget**, and every oracle that declines to decide on a run whose read side had not caught up
declined on every one of them.

The boundary is now drawn at the store. Three statements, all measured, all load-bearing:

- **Quiescence is decided against the store's own answer**, through
  `ControllableEventStorageEngine.readableEventIds()` or, where the backend can answer without going through the run's
  own connections, through `HuntBackend.readableEventIds(engine)`. A PostgreSQL arm reaches it; before the change it
  could not.
- **The scan that decides quiescence is the scan the oracles are judged against.** Taking a second, later scan lets a
  batch that landed between the two turn up as a committed event nobody delivered, which is a false loss.
- **Completeness is a set question and progress is a count question, and they are two fields.** A count can reach the
  store's count while half of one batch is undelivered, because a store whose index comes from a sequence taken before a
  commit separates a batch's rows with another writer's -- measured, as a balance mismatch on both PostgreSQL arms with
  nothing lost. And a set cannot answer whether the read side is still moving, because a repeat does not grow it.

The consequence for a verdict is that "the read side had not caught up" is no longer the end of the discussion. A drain
that ends with the delivery count unchanged for longer than the run's stall window reports the read side as **stopped**,
and loss is decided on that exactly as on a run that caught up -- except on a run that rebuilt its segment set, where
the framework's own hardcoded sixty-second post-split re-claim block makes a blocked segment indistinguishable from an
abandoned one, so the signal is ignored and the run stays undecided. That distinction is what separates an interrupted run
from a lossy store; conflating them is what let canary C7 escape, and what hid finding F-16 for a phase.

The stall window is derived as twice the greater of the scenario's liveness horizon and its token-claim timeout, capped at
half the settle budget and floored at two seconds. The cap is not decoration: derived from the horizon alone it came out
exactly equal to the settle budget on the contended-append arm, so it could never elapse and the check could never fire.

### 2.2 What that means, stated plainly

**`REAL_THREADS` -- the default mode, and the one every scenario in the corpus runs in -- is not
reproducible in any sense.** Not the record order, not the operation counts, not even which appends
were accepted. This is not a defect in the harness; it is the point of the mode. Whether writer A or
writer B wins a race for the same consistency boundary decides which of the two appends is rejected,
and that decision is the thread schedule's, not the seed's. A seed fixes *which transfers are
attempted*; it fixes nothing about *which of them commit*.

The practical consequence: **a seed does not reproduce a `REAL_THREADS` failure.** Re-running a
failing seed re-runs the same workload shape against a new schedule and may well be green. That is
why the suite's oracles are history-based rather than expectation-based -- a checker judges the run
that happened, not the run that was expected -- and why a violation carries its history file, which
is the only exact record of the run that failed.

**`SINGLE_THREADED` is reproducible on the write side and only there.** One writer issuing a seeded
sequence against a store nothing else writes to reaches the same verdicts and leaves the same events
behind, every time; that is asserted, not merely observed, by `DeterminismProbeTest`. What is *not*
stable is where the projection's deliveries land relative to the writer's appends, because the
streaming processor's coordinator and workers are separate threads and must be: a mode that ran the
processor on the writer's thread would not be event processing.

The one component that breaks full determinism in `SINGLE_THREADED` mode is therefore
`PooledStreamingEventProcessor`, through its coordinator and worker executors
(`messaging/src/main/java/org/axonframework/messaging/eventhandling/processing/streaming/pooled/PooledStreamingEventProcessorConfiguration.java:340`
and `:352`). Both are injected as `ScheduledExecutorService` instances and the harness supplies
single-threaded ones, which removes concurrency *within* the processor. It cannot remove the
processor's own thread, which is what leaves the interleaving between delivery and append free.

### 2.3 Seams: what exists, and what was deliberately not used

- **Used, because the framework already offers them:** `coordinatorExecutor` and `workerExecutor` on
  `PooledStreamingEventProcessorConfiguration`; `initialSegmentCount`, `tokenClaimInterval`,
  `claimExtensionThreshold` and `batchSize` on the same record; `initialToken` for the replay
  position. No seam was invented where one existed.
- **Not used: the clock.** `ClockUtils` holds a single static `AtomicReference<Clock>`
  (`common/src/main/java/org/axonframework/common/ClockUtils.java:49`) for the whole process, and
  token-claim expiry reads it
  (`messaging/src/main/java/org/axonframework/messaging/eventhandling/processing/streaming/token/store/jpa/TokenEntry.java:159-160`).
  Setting it would leak across every test in the same JVM, and nothing on the L1 path makes a
  decision from it: the in-memory token store has no claim expiry to evaluate (finding F-2), and
  event timestamps are pinned to a constant by the workload rather than read from a clock. Per-node
  clock skew inside one process remains impossible through that seam (finding F-4).
- **Not used: any seam added to framework code.** None was added. The only substitution anywhere is
  `ControllableEventStorageEngine`, which wraps a real storage engine and is the harness's own class.

**Deterministic regardless of mode, because nothing concurrent touches them:**

- The workload shape: writer count, account pool, access distribution, overlap degree, operation mix
  and batch sizes are all pure functions of the seed (`SwarmShape`), and the whole shape is written
  into the history header.
- The reference model. `DcbStoreModel` is pure and depends on the JDK only; the same operation
  sequence always produces the same verdicts and the same store contents.
- Every checker's verdict for a given history file. Checkers are pure functions of the history.

**Never deterministic, in any mode:**

- **Wall-clock timestamps** (`HistoryRecord.wallTs`) and logical timestamps
  (`HistoryRecord.logicalTs`, derived from `System.nanoTime()`). Only the record sequence number
  defines the history's order.
- **The file order of history lines.** Records are serialized outside the recorder's write lock, so
  under contention a line may reach the file out of sequence. `HistoryView` sorts by sequence number
  on read; nothing else may assume file order.

### 2.4 Two consequences for checkers, both learned the hard way

Both of these produced false violations on the first concurrent run and are now part of the
contract:

1. **A concurrent history cannot be replayed in invocation order.** Two writers race and the one
   that asked first is often not the one that landed first. `ModelConformanceChecker` replays in the
   order of the authoritative post-run scan, which is the store's own answer, and falls back to
   completion order only for a history that has no scan in it.
2. **An event becomes visible before the record saying it was committed is written.** The store
   publishes inside `commit()`, and the harness writes its record after that call returns, so a fast
   consumer can legitimately be recorded ahead of it. `VisibilityChecker` therefore compares a
   delivery against the *invocation* of the commit, and against the earliest such record when an
   event was committed more than once.

**Deliberately out of the model's reach:** `DcbStoreModel` is sequential. It defines what the store
contains once an operation has taken effect, and says nothing about what a concurrent reader may
observe while a batch is being committed. That is a separate property, checked against the real
engine and tracked as finding F-3, and it is now reproduced by
`partial_batch_never_visible_to_concurrent_reader` rather than resting on reading alone.

### 2.5 What gets pinned, given all of the above

The measurement in 2.1 invalidates the obvious regression strategy. Pinning the seed of a failing
run only works where the seed decides the run, and under real threads it does not, so the suite pins
two different things and says which is which.

| Asset | Pinned for | Where | What it guarantees |
|---|---|---|---|
| A **seed** | `SINGLE_THREADED` arms only | `RegressionSeedsTest`, against `dcb_append_rejected_after_marker_single_writer` | The same append verdicts and the same store contents, every run. The arm asserts its own determinism mode, so the pin cannot be quietly moved onto a contended arm. |
| A **history file** | `REAL_THREADS` runs | `simulation/src/test/resources/hunt-histories/`, replayed by `RegressionSeedsTest` and `HistoryReplayTest` | The same verdict from the same file, for ever and on any machine, because every checker is a pure function of the history. |

Offline replay is what makes the second row possible. `ScenarioRunner.replay(Path)` runs the whole
registered checker set over an existing history with no simulation at all, and folds the same
three-valued verdict a live run folds:

```bash
./mvnw -Phunt -pl simulation -am test -Dtest=HistoryReplayTest \
    -Dhunt.history=simulation/target/hunt-histories/<dir>/<scenario>-<seed>.jsonl \
    -Dsurefire.failIfNoSpecifiedTests=false
```

The wording follows the same rule. `HistoryHeader.reproduceCommand()` annotates the command it
renders for a `REAL_THREADS` run as a re-sample rather than a replay, and points at the history file
instead. Two histories ship with the suite as permanent proof that the mechanism works in both
directions: one contended run in which nothing was found broken, and the same scenario at the same
seed recorded while the store's conflict check was deliberately bypassed, which replays to a `FAIL`
on `AppendConformsToDcbModel`.

**What a pinned history does not do, stated before anyone assumes otherwise.** A history is a fixed
record of a run that already happened, so replaying it can never notice a *new* defect in the
framework: change the engine however you like and the file's verdict does not move. A pinned history
guards the **oracles** -- it goes red if a checker is weakened, deleted or unregistered, which is
exactly how a suite silently stops looking. The **engine** is guarded by the live arms that run on
every change, and by nothing else. The mutation campaign in `formal/CANARIES.md` is the measurement
of which live arms catch what.

---

## 3. MachineName registry

Every invariant the suite enforces. The statement column is normative: it is the exact wording that
must appear in the checker's Javadoc, in the violation message, and in any TLA+ operator comment
modelling the same property.

| MachineName | Statement | Claims | Checker class | Scenarios | TLA+ operator |
|---|---|---|---|---|---|
| `AppendConformsToDcbModel` | Every append recorded as successful is accepted by the DCB reference model at its point in the history, and every append recorded as rejected is rejected by it. | C1, C2, C3, C5, C6, C7, C8, C10 | `ModelConformanceChecker` | `dcb_append_rejected_after_marker_under_contention`, `dcb_append_rejected_after_marker_single_writer`. Reports itself **not applicable** on any backend that does not implement the protocol the model describes, which is every aggregate-based store: its condition is a map from aggregate identifier to sequence number rather than a boundary over tags and a marker, so replaying such a history against the model would report the difference in protocol as a defect on every append. | `DcbAppend.tla` `AppendConformsToDcbModel`, statement verbatim. Checked by `MCAppend_conformance.cfg` (violated on the modelled aggregate-store engine, finding F-14) and `MCAppend_conformance_fixed.cfg` (holds). |
| `NoVisibilityBeforeCommit` | No event is delivered to a consumer before the commit of the transaction that appended it. | C4, C29 | `VisibilityChecker` | `uncommitted_never_visible_rolledback_never_delivered_prepare_commit`, `..._commit`, `..._after_commit`, `partial_batch_never_visible_to_concurrent_reader` | -- |
| `RolledBackEventsNeverObservable` | No event of a rolled-back transaction is ever delivered to a consumer or present in a post-run scan of the store. | C29 | `VisibilityChecker` | `uncommitted_never_visible_rolledback_never_delivered_prepare_commit`, `..._commit`, `..._after_commit` | -- |
| `UnconditionalAppendNeverRejected` | An append made without a consistency condition is never rejected as conflicting. | C2 | `AppendOutcomeChecker` | `dcb_append_rejected_after_marker_under_contention`, `dcb_append_rejected_after_marker_single_writer` | `DcbAppend.tla` `UnconditionalAppendNeverRejected`, statement verbatim. Checked by `MCAppend_unconditional.cfg` (violated, reproducing finding F-14 at the design level) and `MCAppend_unconditional_fixed.cfg` (holds). |
| `RejectedAppendLeavesNoEvents` | No event offered by an append recorded as rejected is present in the authoritative scan taken after the run has quiesced. | C9, C10 | `AppendOutcomeChecker` | `dcb_append_rejected_after_marker_under_contention`, `dcb_append_rejected_after_marker_single_writer`, `partial_batch_never_visible_to_concurrent_reader` | -- |
| `SequenceKeyOrderPreserved` | For every sequence identifier, the order in which its events are delivered to a consumer equals the order in which those events were appended. | C32, C33, C34 | `OrderChecker` | `sequencing_policy_order_preserved_wired_default` only. The other two arms of that scenario deliver nothing at all (finding F-7), so they contribute no ordering evidence, and no other workload records a sequence identifier. | -- |
| `LedgerConservesTotalBalance` | The balances the projection reports sum to the ledger's opening total. | C4, C15, C16 | `ConservationChecker` | every scenario driving the ledger | -- |
| `LedgerBalanceNeverNegative` | No account balance is negative at any point in the sequence of committed transfers. | C1, C5, C8 | `ConservationChecker` | every scenario driving the ledger | -- |
| `ProjectionMatchesFoldOfCommittedEvents` | The balance projection at the end of the run equals the fold of the transfers the run committed. | C4, C15, C16 | `ConservationChecker` | every scenario driving the ledger | -- |
| `DeclaredFaultsLand` | Every fault a run declares fires at least once, and the run records how often and against what. | -- (suite constitution, rule 4) | `FaultLandingChecker` | every scenario declaring a fault | -- |
| `AtMostOneSegmentOwner` | For every segment, the intervals during which distinct nodes hold its token claim never overlap by more than the run's declared clock-skew allowance. | C18, C19, C20, C22, M3 | `OwnershipChecker` | `concurrent_bootstrap_initializes_segments_exactly_once`, `..._with_node_churn`, `at_most_one_segment_owner_with_skew_none`, `..._half_timeout`, `..._double_timeout` (expected to break, and measured), `split_merge_no_loss_no_dup_under_load`. Reports itself **not applicable** on any backend whose token store implements no ownership -- the store has no owner to arbitrate, so there is nothing here for the invariant to be true or false about -- and is silent for a single-node run. | `TokenClaim.tla` `AtMostOneSegmentOwner`, statement verbatim, parameterised by `SKEW_ALLOWANCE`. Six configurations; `MCClaim_skew_below_margin.cfg` is violated and its `_fixed` sibling holds, which is what refined finding F-10. |
| `DeliveryAttributedToSegmentOwner` | Every event a node delivers from a segment is delivered while that node holds the segment's claim, or within one claim timeout of losing it. | C18, C19, C21, M1 | `OwnershipChecker` | `at_most_one_segment_owner_with_skew_none`, `..._half_timeout`, `..._double_timeout`. Reports itself **not applicable** on a run that split or merged a segment: a segment identifier does not name the same unit of work either side of a rebuild, so no interval derived from claim traffic can follow it across one. It is a scoping statement rather than undecidedness, which is what lets a membership scenario reach a verdict at all. | -- |
| `NoCommittedEventGoesUndelivered` | Every event a committed append made visible is delivered to a consumer at least once. | C15, C16, C17, M4 | `DeliveryChecker` | every scenario whose run recorded that its read side either caught up **or stopped moving**. The second half is not a widening for convenience: a store that skips an index for ever leaves the read side permanently behind, which is the same observation an interrupted run produces, so declining on "did not catch up" alone declined on the very defect the invariant exists to find -- measured, as the escape of canary C7 and then as finding F-16. A drain that ends with the delivery count unchanged for longer than the run's stall window has nothing in flight, and loss is decided on it exactly as on a run that caught up. | -- |
| `DuplicateDeliveryOnlyInsideRecoveryWindow` | An event is delivered more than once only while a recorded claim transition, segment-count change or node recovery window is open, or as part of a replay the delivery itself reports, and never at all when the run declares exactly-once delivery. | C16, C17, C27 | `DeliveryChecker` | every scenario whose run recorded that its read side caught up. A repeat is licensed by a **rewind the history recorded** -- the position the store told a node to resume from, when that position is behind an event the segment had already delivered -- and is bounded by that position as well as by one claim timeout; a replay is bounded by the position the reset rewound to instead of by time. A repeat the history accounts for is a measurement and does not move the verdict; one inside a window that no rewind explains is a note and does; one with neither is a violation. No shipped scenario declares exactly-once, because no shipped deployment has a transactional read model; that half of the invariant is exercised by its canaries only, and the registry says so rather than implying coverage it does not have. | -- |
| `CommittedEventDeliveredWithinHorizon` | Every committed event that reaches a consumer reaches it within the run's declared liveness horizon. | C13, C14, C15 | `LivenessChecker` | every scenario whose run recorded that its read side caught up | -- |
| `AcceptedCommandCompletes` | Every command the run dispatched reaches a recorded outcome. | C4, C29 | `LivenessChecker` | every scenario | -- |
| `StoredTokenNeverRegresses` | For every segment, each token stored for it reports a position at or beyond the position of the token stored for it before, unless the framework itself flagged that store as part of a replay or the segment had been merged since. | C38 | `StoredProgressChecker`  Reports itself **not applicable**, together with the other two invariants of this checker, on a run whose declared faults include one that breaks the store's connection: a token write's recorded outcome is the outcome of the call and not of the transaction that would have committed it, so a write that returned moments before the connection died may never have landed, and the next claim reading back the older token looks exactly like a regression without being one.  Reports itself **not applicable**, together with the other two invariants of this checker, on a run whose token store grants every claim: several nodes each believe they own every segment and each writes its own position over the others', so the stored token walks backwards by construction -- measured, as 24 regressions on a four-node run over the framework's in-heap token store. | every scenario whose token store recorded a write, which is every cluster arm; silent on the in-heap token store, whose writes carry no claim decision and are not recorded. |
| `StoredTokenCoversDeliveredEvents` | For every segment, the last token stored for it reports a position at or beyond the position of every event that segment delivered. | C15, C16 | `StoredProgressChecker` | every cluster arm. Only the lagging direction is checked; see the note below on why the over-claiming direction is not checkable per segment.  Reports itself **not applicable**, together with the other two invariants of this checker, on a run whose declared faults include one that breaks the store's connection: a token write's recorded outcome is the outcome of the call and not of the transaction that would have committed it, so a write that returned moments before the connection died may never have landed, and the next claim reading back the older token looks exactly like a regression without being one.  Reports itself **not applicable**, together with the other two invariants of this checker, on a run whose token store grants every claim: several nodes each believe they own every segment and each writes its own position over the others', so the stored token walks backwards by construction -- measured, as 24 regressions on a four-node run over the framework's in-heap token store. | -- |
| `ClaimHandoverRewindsAtMostOneBatch` | When a segment's token is claimed again, the events already delivered from that segment that the stored token does not cover are the events of at most one batch. | C15, C16, C17, M1, M4 | `StoredProgressChecker` | `at_most_one_segment_owner_with_skew_none`, `..._half_timeout`, `..._double_timeout`, `replay_sees_full_prefix_and_flags_redelivery`, `split_merge_no_loss_no_dup_under_load`  Reports itself **not applicable**, together with the other two invariants of this checker, on a run whose declared faults include one that breaks the store's connection: a token write's recorded outcome is the outcome of the call and not of the transaction that would have committed it, so a write that returned moments before the connection died may never have landed, and the next claim reading back the older token looks exactly like a regression without being one.  Reports itself **not applicable**, together with the other two invariants of this checker, on a run whose token store grants every claim: several nodes each believe they own every segment and each writes its own position over the others', so the stored token walks backwards by construction -- measured, as 24 regressions on a four-node run over the framework's in-heap token store. | -- |
| `AcknowledgedAppendIsDurable` | Every event of an append the client saw succeed is present exactly once in the authoritative scan of the store. | C4, C29, C35, M5 | `DurabilityChecker` | `crash_recovery_no_acked_loss_postgres`, `commit_ack_matches_durability_under_partition`, `no_event_skipped_by_gap_timeout`, `..._spring_defaults`. The only invariant in the suite that is about the store keeping what it said it kept rather than about the framework's decisions, and therefore the only one whose answer can differ between a map in the heap and a database that was killed mid-write. Three client verdicts, two of them decidable: an append that succeeded binds the store, an append the store decided against is covered by `RejectedAppendLeavesNoEvents`, and an append whose failure was not a decision -- a dropped connection, a store that stopped answering -- binds nothing, because the request may have landed and the reply may have been lost. Reports itself **not applicable** on a run in which a fault made the store hold something other than what was offered: the harness falsified the acknowledgement, so the store is not answerable for it. The ambiguous count is always published, and a run that declared an ambiguity-making fault and produced none of it gives up its verdict.  Reports itself **not applicable** on a store that commits outside the append transaction when the run also breaks the store's connection: an append is recorded as acknowledged when the engine's commit call returns, and on such a store that call does no work and races the database transaction, so the acknowledgement is the harness's and not the client's. Decided on a store whose commit call is the commit; measured, with the client's verdict set published, on one whose is not. | -- |

Scenario columns are filled in as the scenarios land; an invariant with no scenario is asserted by
its unit-level canaries only, and the registry says so rather than implying coverage it does not
have.

Two rows are not properties of Axon Framework and are marked as such. `DeclaredFaultsLand` is an
evidence rule: it never reports a violation, only a note, because a fault that did not fire is
missing evidence rather than a broken guarantee, and the two must not be reported the same way. The
three ledger rows are properties of the ledger workload, which is the vehicle: they hold if and only
if the framework did not lose, double or tear anything, which is exactly why a conservation law is
worth more than the assertions somebody would otherwise have thought to write.

**When a checker must not decide.** Three situations downgrade a verdict to a note rather than
producing a violation, and every checker that can meet them handles them explicitly:

| Situation | Why deciding would be wrong |
|---|---|
| An operation's outcome is unknown | The replayed state is no longer known to be the store's. |
| A fault made the store hold something other than what was offered | The missing or doubled data is the harness's doing; blaming the framework for it is a false finding. `Fault.perturbsStoreContents()` declares which faults can do this. |
| An append failed for a reason other than the store's own consistency check | An injected infrastructure failure carries no protocol verdict for the model to be held to. |
| A delivery carries no sequence identifier | The identifier is the framework's, and a checker that guesses one makes the verdict a property of the checker. `OrderChecker` judges only deliveries whose identifier the run recorded. |
| A delivered event is absent from the authoritative scan | Its place in the append order is unknown, so an ordering oracle has nothing to compare against. |
| The run's token store implements no ownership | Every claim is granted to everybody, so an ownership assertion made against it is true without checking anything. `OwnershipChecker` reports that rather than passing, whenever more than one node claimed against such a store. |
| The read side had not caught up when the run ended | Nothing was lost; the run was interrupted. `DeliveryChecker` and `LivenessChecker` both refuse to decide, and the runner records the fact under `quiesced` on the settle phase record so that a checker can tell an interrupted run from a complete one. |
| A repeated delivery the run's declared mode permits | The framework says a stolen claim may cause an event to be handled twice, so it is not a violation. It is still reported, with a distribution, and the report downgrades the run to undecided: a projection that applied a transfer twice is a fact somebody should look at even when the deployment permits it. |
| A node that never came up | The framework promises nothing about how many instances survive a start. `LivenessChecker` reports it, so a run that exercised a smaller cluster than it declared cannot be a clean pass. |

**One thing a rollback record deliberately does not say.** The framework registers one error handler per append transaction and calls `AppendTransaction.rollback()` from it whatever phase the error arrived in, so an error strictly after a successful commit produces a rollback of a batch the store has already published. `ControllableEventStorageEngine` records that rollback as having discarded nothing, keeping the offered identifiers under `offeredEventIds` and flagging the situation with `afterCommit`. Recording it any other way would make every such run report committed, legitimately visible events as observable-after-rollback, which is a false finding. What the framework's contract does not say about a rollback after a commit is a real gap, and it is recorded as finding F-8 rather than as a violation.

**Which invariants have a formal model, and which do not.** Three of the twenty rows above are checked as TLA+
operators as well as by a checker: `AppendConformsToDcbModel` and `UnconditionalAppendNeverRejected` in
`tla/DcbAppend.tla`, and `AtMostOneSegmentOwner` in `tla/TokenClaim.tla`. Each carries the statement in its
row **verbatim**, because an invariant that is worded one way in Java and another way in a specification is two
invariants that happen to share a name. The other seventeen have no model, and `tla/README.md` names each one
and the reason: seven are about a consumer and neither model has one, three are about a durable token position
and neither model has one, five are properties of the workload or of the harness rather than of a protocol, and
two are decided against an authoritative scan of a real store, which no model has either. A row whose TLA+
column reads `--` is a row with no model, not a row with a model nobody wrote down.

### 3.2 What a checker reports, and which of it moves a verdict

A checker has four things to say, and only two of them are verdicts. Collapsing the other two into undecidedness is how
an arm becomes permanently inconclusive and stops being able to signal anything; dropping them is how a gap starts
reading as coverage.

| Channel | Moves the verdict? | What it means |
|---|---|---|
| **violation** | yes, to `FAIL` | An invariant was found broken. |
| **note** | yes, to `INCONCLUSIVE` | Something stopped the checker deciding: an unknown outcome, a read side that had not caught up, a fault that rewrote the store, a fact the history does not account for. |
| **measurement** | no | A fact the run produced which the history fully accounts for. The framework's behaviour explains it and the checker checked it, so the verdict stands and the number is printed. A redelivery a recorded rewind licenses, bounded by the position that rewind went back to, is the standing example. |
| **not applicable** | no | An invariant this run cannot express at all, named. A claim assertion against a store with no owner; an attribution assertion across a segment-set rebuild; the reference model against a store implementing a different protocol. Reporting it as a note says the run tried and failed; reporting nothing says it passed. Neither is true. |

The two verdict-neutral channels were added in P3a, and the reason is a measurement rather than a preference: before
them, the replay arm and the split-and-merge arm reported `INCONCLUSIVE` on every seed of every run -- the replay arm
because every one of its 314-364 licensed redeliveries produced a note, the membership arm because the attribution
oracle can never be judged on a run that rebuilds its segments. **An arm that can never reach a pass can never signal a
regression either**, which makes it as inert as one that always passes.

### 3.3 The backend verdict vector

Every result carries what each store concluded, because a framework is a library and the thing under test is really the
library crossed with a store protocol. The vector is written as `backend:VERDICT` pairs, with the count of invariants
that store could not express in brackets:

```
VECTOR dcb_append_rejected_after_marker_under_contention in-memory:PASS hsqldb-tokens:PASS postgres-jpa:FAIL(1 n/a) postgres-jpa-split-tokens:FAIL(1 n/a)
```

How to read it, which is the whole point of recording it:

| Shape | Attribution |
|---|---|
| broken on every backend | core framework logic |
| broken on one backend | that adapter, or that store's own semantics |
| `n/a` on one backend | the invariant is inexpressible there; the vector claims no coverage for it |

Every finding in `formal/FINDINGS.adoc` that a backend differential produced carries its vector. A finding with no
vector was found on one store and says so.

### 3.1 Reference-model rules

`DcbStoreModel` attributes every decision to a named rule so that a divergence points at one rule
rather than at the model as a whole. These names are the bridge to the TLA+ specification of the
same protocol.

| Rule | Statement | Engine evidence |
|---|---|---|
| `MarkerInfinityBypassesConflictCheck` | An append anchored at INFINITY is accepted without scanning. | `InMemoryEventStorageEngine.java:169-171` |
| `ConflictScanCoversPositionsAtOrAfterMarker` | The conflict scan covers stored events at positions greater than or equal to the marker; ORIGIN resolves to -1 and therefore covers the whole store. | `InMemoryEventStorageEngine.java:173`, `GlobalIndexConsistencyMarker.java:46-55` |
| `CriterionTagsMatchByContainsAll` | An event matches a criterion only when it carries every tag the criterion names. | `TagFilteredEventCriteria.java` (`tags.containsAll`) |
| `CriterionTypesMatchByMembershipOrAnyWhenEmpty` | A criterion naming types matches only those types; a criterion naming none matches any type. | `TagAndTypeFilteredEventCriteria.java` (`types.isEmpty() \|\| types.contains`) |
| `CriteriaMatchIsDisjunctionOverCriteria` | A boundary matches when any of its criteria match; an empty boundary matches everything. | `OrEventCriteria.java` (`anyMatch`), `AnyEvent.java` (`return true`) |
| `AppendIsLegalIffNoMatchInScanRange` | The append is accepted exactly when the scan finds no match. | `InMemoryEventStorageEngine.java:107-110`, `:124-126` |
| `AcceptedBatchTakesConsecutivePositionsInOfferOrder` | An accepted batch occupies consecutive positions starting at the store head, assigned in offer order. | `InMemoryEventStorageEngine.java:127-141` |
| `CommitMarkerIsLastPositionPlusOne` | The marker an accepted append reports is one past its last position; an empty batch reports ORIGIN. | `InMemoryEventStorageEngine.java:131`, `:142-143` |
| `RejectedAppendLeavesStoreUnchanged` | A rejected append stores none of its batch. | `InMemoryEventStorageEngine.java:107-110`, `:124-126` |
| `SourceReturnsMatchingEventsFromStartAscending` | Sourcing returns every matching event at or after the start position, in ascending position order. | `InMemoryEventStorageEngine.java:289-306` |
| `SourceMarkerIsStoreHeadAtSourceTime` | The marker a sourcing reports is the store head at the moment it read, independent of the boundary and of what matched. | `InMemoryEventStorageEngine.java:195-197`, `:371-378` |

Each rule is pinned by a case in `DcbStoreModelTest` and cross-checked against the real engine by
`ModelAndInMemoryEngineAgreeTest`.

These rules are also the operators of `tla/DcbRules.tla`, quoted from this table word for word. Seven of the
eleven appear there: four as definitions, because the matching and scan-range rules **are** the transition
relation and there is nothing for an invariant to add, and three as checkable invariants over committed appends.
The two sourcing rules are out of the append model's scope. That the two models really do decide the same way is
not asserted: `tla/DcbCrossCheck.tla` emits the specification's decision for every case in a finite domain and
`tla/crosscheck/CrossCheck.java` replays each one through `DcbStoreModel`. Last run: 960 cases, 960 agreed, 0
disagreed, over stores of up to three events drawn from a three-event pool, every marker from ORIGIN to the
maximum head plus INFINITY, and four boundaries. `tla/README.md` has the command and the mutation that proves
the comparison can fail.

---

## 4. History schema, version 1

A history is a JSON Lines file. Line 1 is the header; every following line is a record. It is
written by `HistoryRecorder` and read by `HistoryView`; nothing else parses it.

Fields are **added, never repurposed**. Adding a field does not bump the schema version; changing
what a field means does. Unknown fields are ignored on read, so a history written by an older run
stays readable.

### 4.1 Header (line 1)

| Field | Type | Meaning |
|---|---|---|
| `schemaVersion` | int | The schema this file was written against. Currently `1`. |
| `scenarioId` | string | The scenario that produced the run. |
| `seed` | long | The seed fixing the workload shape and the fault schedule. |
| `backend` | string | The store the run was driven against, for example `in-memory`, `postgres-jpa`, `axonserver`. |
| `timescale` | string | The timescale arm, for example `compressed` or `realistic`. |
| `workloadShape` | map<string,string> | The workload's shape knobs, rendered flat. |
| `versions` | map<string,string> | The version combination the run's meaning depends on. Added in P6; absent from any history written before it, and defaulted to empty on read. |

**Why the versions belong in the header.** A backend is not one thing: a store reached over a wire is
this reactor crossed with a client library crossed with a store version, and any of the three moving
changes what a run means. That is not theoretical here -- the Axon Server arm was recorded as blocked
for a whole phase by an abstract method added to a storage-engine interface that the released connector
had not implemented, which `javac` accepts and the JVM refuses. Recording the combination as data makes
"is this divergence the framework's or the skew's" a lookup instead of an argument, and it means a
verdict vector and a finding both carry it without anybody having to remember to write it down.

The keys are open, like `op`. In use today:

| Key | Meaning |
|---|---|
| `framework` | This reactor's version, from `hunt.frameworkVersion` (which the module's surefire configuration fills from `${project.version}`) or from the storage-engine jar's manifest. |
| `connector` | The client library reaching the store, as `group:artifact:version`, read from the artefact's own manifest rather than from a constant, so a dependency bump nobody updated the constant for is visible. |
| `image` | The store's container image tag. |
| `<component>.shimmed` | A method the harness supplies because the client library does not, so that no verdict from the arm can be quoted without the adaptation. |

A backend contributes its own keys through `HuntBackend.versions()`; the runner adds `framework`.
`formal/CONNECTOR-COMPATIBILITY.md` records which combinations are usable and what each shim models.

The header exists so that a history is reproducible from itself.
`HistoryHeader.reproduceCommand()` renders the command that replays the run, and every violation
carries it.

### 4.2 Record (lines 2..n)

| Field | Type | Meaning |
|---|---|---|
| `idx` | long | Strictly increasing sequence number. Defines the history's order. File order does not. |
| `logicalTs` | long | The harness's logical clock, in nanoseconds since the recorder was created. Monotonic within a run. |
| `wallTs` | long | Wall clock, epoch milliseconds. For correlating with external evidence only; never for ordering. |
| `process` | string | The client, thread or session that issued the operation. |
| `node` | string, nullable | The node the operation was routed to. Required for any replication, leadership or membership claim. |
| `op` | string | The operation name. Open set; see `HistoryOps`. |
| `type` | enum | `INVOKE`, `OK`, `FAIL`, or `INFO`. |
| `id` | string | Correlation identifier joining an invocation to its completion. |
| `key` | string, nullable | The object the operation addressed: a tag, a segment, an account. |
| `value` | object | Operation-specific payload. Arguments on the invocation, results on the completion. |
| `error` | string, nullable | The error the operation reported. |
| `faultEpoch` | string, nullable | The fault window active when the record was written; `null` outside any fault. |

`op` is a plain string rather than an enum on purpose: recording a new kind of operation must not
require editing any existing class. The names in use are constants on `HistoryOps`, which is
explicitly non-exhaustive.

### 4.3 Outcome resolution

| Situation | Recorded as | Resolved outcome |
|---|---|---|
| Operation succeeded | `INVOKE` then `OK` | `OK` |
| Operation definitely failed | `INVOKE` then `FAIL` | `FAIL` |
| Timeout, dropped connection, ambiguous commit | `INVOKE` then `INFO` with `error` set | `UNKNOWN` |
| Run ended with the operation in flight | `INVOKE` only | `UNKNOWN` |
| Evidence with no operation: post-run scan, fault landing | `INFO` with no matching `INVOKE` | reported as a note, not an operation |
| Completion whose invocation is missing | `OK`/`FAIL` with no matching `INVOKE` | reported by `unpairedCompletions()`; the history is damaged |

An internally retried call is **not** collapsed into one entry. Each attempt is its own operation
with its own correlation identifier; detecting the duplicate is the checker's job, not the
recorder's.

### 4.4 Elle field mapping

The schema was chosen so that a converter to Elle's EDN history format is mechanical. No Elle
dependency is taken until a transactional-isolation question actually needs one; `HistoryToElle`
holds the place.

| Elle field | Hunt field |
|---|---|
| `:index` | `idx` |
| `:process` | `process` |
| `:type` (`:invoke` / `:ok` / `:fail` / `:info`) | `type` (`INVOKE` / `OK` / `FAIL` / `INFO`) |
| `:f` (the operation's function) | `op` |
| `:value` | `value` |
| `:error` | `error` |

Elle's `:info` and this schema's `INFO` mean the same thing: the operation may or may not have taken
effect.

Fields Elle has no slot for (`node`, `key`, `faultEpoch`, `logicalTs`, `wallTs`, `id`) travel in the
value map or are dropped, depending on what the analysis needs. They are recorded regardless,
because a checker that cannot tell whether an anomaly happened under fault cannot attribute it.

---

## 5. Adding an invariant and its checker

The whole point of the harness is that this costs no surgery. Following this recipe requires editing
**zero existing classes**.

1. **Find the claim.** A test with no C-number or M-number from `docs/testing-plans/axon-hunt.md`
   does not get written. If the property is real but has no claim, add the claim to the plan first;
   the claims list is append-only.
2. **Write the statement.** One sentence, in the present tense, saying what must always be true. This
   exact string is what goes in the registry, the Javadoc, and the violation.
3. **Add the registry row** in section 3 above: MachineName, statement, claims, checker class,
   scenarios, TLA+ operator (`--` when the property is not modelled formally, which is normal).
4. **Write the checker.** A new class in `org.axonframework.hunt.checker` implementing `Checker`:
   - `name()` returns the class's simple name;
   - `machineNames()` returns the invariant names it enforces, one checker may enforce several;
   - `check(HistoryView)` returns a `CheckResult`, building each `Violation` with
     `Violation.of(machineName, statement, detail, records, history.header())` so that the seed and
     the reproduce command come along automatically.
   Declare the statement as a `public static final String` on the checker so that the registry and
   the code can be diffed mechanically.
5. **Record what the checker needs.** If the invariant needs an operation the recorder does not yet
   emit, add a constant to `HistoryOps` and record it. Adding an operation name changes no existing
   code path.
6. **Register it.** Append the fully-qualified class name to
   `simulation/src/main/resources/META-INF/services/org.axonframework.hunt.checker.Checker`. It now
   runs against every history, in every scenario, without any scenario opting it in.
7. **Prove it can fail.** Write the canaries: one synthetic history per rule the checker enforces
   where that rule is planted broken, plus at least one sound history it passes. A checker with no
   demonstrated failure mode is decoration, and this step is what makes it an oracle instead. Build
   the histories through `SyntheticHistory`, never by fabricating records.
8. **Handle unknowns explicitly.** Decide, and write down in the checker's Javadoc, what the checker
   does with an operation whose outcome is unknown. The default is a note, not a violation.

**Handling an invariant that turns out not to hold.** Do not fix the engine. Record the finding in
`formal/FINDINGS.adoc` with its evidence and a candidate fix, keep the registry row with an honest
"holds?" note, and pin the observed behaviour with an expected-gap test that flips red when the gap
is closed.

## 6. Adding a scenario, a fault, a workload, or a backend

Every one of these costs a declaration and no surgery. That is the charter, and it is checked by
`ScenarioRunnerTest`, which declares a scenario at the call site and runs it through the same runner
as the shipped ones.

### 6.1 A scenario

A scenario is a `Scenario` record. Build one with `Scenario.builder(id, name)` and declare:

| Field | What it says |
|---|---|
| `claims` | The C-numbers and M-numbers from the plan this scenario tries to falsify. A scenario with no claim does not get written. |
| `workload` | A `Supplier<Workload>`, because every run gets its own instance and its own state. |
| `faults` | A `FaultSchedule`: warmup, windows, heal, settle. `FaultSchedule.none(settle)` for a scenario whose claim needs no fault. |
| `backend` | The name of a registered `HuntBackend`. |
| `timescale` | `HuntTimescale.compressed()` or `.realistic()`. |
| `determinism` | `REAL_THREADS` or `SINGLE_THREADED`; see section 2 for what each buys. |
| `oracles` | The MachineNames that must be registered and must hold. The whole registered checker set runs regardless: this is a guard against an oracle silently disappearing, not a filter. |
| `seed` | The base seed a tier's seeds count up from. |
| `budget` | Per tier: how many commands, how many seeds, how long a seed may take. |

Run it with `ScenarioRunner.run(scenario, tier, seed, directory)` or
`ScenarioRunner.runTier(scenario, tier, directory)`. Add it to `HuntScenarios.all()` only if it
should be reachable by name from `-Dhunt.scenario`, which is what the reproduce command needs.

The verdict is three-valued and the runner will not report a pass unless every required oracle is
registered, every declared fault fired, the read side caught up, and nothing was found broken.

### 6.2 A fault

One class implementing `Fault`. It declares its `kind()`, its `parameters()`, and whether it
`perturbsStoreContents()`; it reaches the system only through the `FaultSite` it is handed, and it
increments the `FaultEvidence` it is given every time it actually perturbs something. Nothing else
changes: `FaultSchedule` takes whatever it is given, and the runner writes the evidence into the
history without knowing what the fault was.

The evidence is not optional. A declared fault whose fire count is zero makes the run inconclusive,
which is `FaultLandingChecker`'s only job, and it is what stops a green run under a fault that never
landed from being reported as a pass.

Prove a new fault lands by adding a case to `FaultsLandTest`, which drives a short run with one
fault installed and asserts the fire count is positive.

### 6.3 A workload

One class implementing `Workload`: the command handlers it registers, the projection it returns, the
tags its events carry, the shape it derives from the seed, and the final read-model state it records
so a checker can compare against it. Conservation hooks are optional; a workload that records
nothing a checker recognises simply gets no verdict from that checker rather than noise.

### 6.4 A backend

One class implementing `HuntBackend`, plus one line in
`simulation/src/main/resources/META-INF/services/org.axonframework.hunt.harness.HuntBackend`. Every
existing scenario then runs against it by name, and the per-backend verdict vector that attributes a
finding to the framework or to one adapter becomes available for free.

Four methods, of which two have defaults that are right for an in-heap store:

| Method | What it must say |
|---|---|
| `name()` | The name a scenario selects it by, and the name in the history header. |
| `createEngine()` | A fresh, empty event storage engine per run. |
| `createTokenStores(runId, claimTimeout)` | One shared token store per run, handed out one view per node so each node claims under its own identity. The default gives every node the framework's in-heap store, which has no owner at all. |
| `arbitratesTokenClaims()` | Whether that store decides who owns a segment. Defaults to `false`, which makes `AtMostOneSegmentOwner` report itself unverifiable rather than passing vacuously. Getting this wrong in the optimistic direction is how a suite reports coverage it does not have. |
| `versions()` | The version facts the store's meaning depends on: the client library reaching it, the store's own version, and any method the harness had to supply for the combination to link at all. Defaults to empty, which is right for a store that is this reactor and nothing else. They go into the history header, so a finding carries the combination it was observed on. A key ending in `.shimmed` is the one that stops a verdict being quoted without its adaptation. |
| `readableEventIds(engine)` | Every readable identifier the store holds, in store order, or `null` to let the run ask the engine. **A store reached asynchronously must override it.** The generic scan drains a sourcing stream with a `next()` loop that stops at the first empty answer, which is right for a store that materialises its answer in the heap and reports **zero** for a gRPC stream, because such a stream is empty until its first message arrives. Measured on the Axon Server arm: a store holding four events answered `4` through `MessageStream.reduce` and `0` through the loop. A scan that always answers nothing makes quiescence trivially true and every delivery oracle hold vacuously. |
| `speaksDynamicConsistencyBoundaries()` | Whether an append condition on this store is a boundary over tags and a marker. Defaults to `true`. Answer `false` for an aggregate-based store, or the reference model will replay its history against a model of a protocol it does not implement and report the difference as a defect on every append. |
| `transactionManager(engine)` | The transaction manager every unit of work of the run is wrapped in, or `null` for an in-heap store. A persistent store cannot opt out: its engine asks the processing context for the executor to append through, and having the run's transaction there is also what makes an append become durable in the framework's commit phase rather than the moment the engine is handed the events. Without it every visibility oracle reports the harness's wiring as a framework defect. |

The claim timeout is passed in rather than read from a configuration, because it is a **store** setting: it does not
travel through the processor configuration the way the run's other compressed timings do, and the ownership oracle
derives a claim's expiry from exactly this number. A store configured with the shipped ten seconds while the checker
assumes a compressed hundred milliseconds reports every legitimate handover as an overlap.

Proving a new backend inherits the corpus costs one test: take a shipped scenario, call `Scenario.onBackend(name)`,
run it, and assert the verdict. `ClaimCapableBackendTest` does exactly that, and it is what turns the extensibility
charter from a claim into a property.

**Before any of that, if the store is reached through a released client library rather than through this reactor's own
code: check the combination links.** Adding an abstract method to an SPI is a binary-compatibility break that `javac`
does not see, because a call resolves against the interface; the JVM refuses it at the first invocation with
`AbstractMethodError`. `ConnectorCompatibilityTest` answers that in about a second, before any container starts, and
fails the build when a method is neither shimmed nor recorded as undriven:

```bash
./mvnw -q -Phunt -pl simulation -o test -Dtest=ConnectorCompatibilityTest \
    -Dsurefire.failIfNoSpecifiedTests=false          # add -Dhunt.connectorJar=<path> for another artefact
```

Whatever it reports goes in `formal/CONNECTOR-COMPATIBILITY.md`, and any method the harness supplies goes in the
backend's `versions()` so it reaches every history the arm writes. Skipping this step buys a ten-minute container run
that ends in a stack trace naming a method rather than a version, which is how one arm of this suite was written off as
blocked for a whole phase.

### 6.5 A cluster

A scenario declares `nodes(n)` and gets `n` framework instances sharing one event store, one token store and one read
model. Three things about that are worth knowing before writing one.

- **The nodes are released from a barrier, not started in a loop.** Calling `start()` on each in turn looks concurrent
  and is not: the first node's coordinator has created and claimed every segment before the loop reaches the second.
  Measured on this harness -- with a sequential loop, exactly one of four nodes ever attempted to create the segments.
- **Segments are shared out evenly.** The shipped `maxSegmentProvider` is `Short.MAX_VALUE`, so without a cap the
  first node to reach the store takes every segment and the rest of the cluster idles. `HuntWorld` caps a multi-node
  run at `segments / nodes` each. A cluster whose segments all live on one node is a single-node run with extra
  threads.
- **The workload must sequence by a real key.** Segment assignment hashes the sequence identifier, and the framework's
  wired default resolves to one identifier for everything on a store speaking the Dynamic Consistency Boundary
  protocol (finding F-6), so every event lands in one segment however many are configured.
  `LedgerWorkload.sequencedPerAccount()` exists for this. A policy that ever answers nothing throws once per event and
  delivers nothing at all (finding F-7), so a key must always be resolvable.

Node operations available to a fault: crash (drop the node without releasing its claims), restart (bring it back under
the same identity), and pause (hold its handling thread past the claim timeout). Crashing deliberately does **not**
shut the processor down, because an orderly shutdown gives the claims back and the state worth testing is the one
where it does not.
