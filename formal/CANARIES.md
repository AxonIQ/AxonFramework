# Axon Hunt -- Canaries

An oracle that has never caught a planted bug is decoration. This file is the measurement: deliberate defects were
applied to **framework** code one at a time, the suite was run against each, and what it caught was recorded.

Two rules govern the campaign and neither is negotiable.

1. **A canary diff is never committed.** Every mutation is applied, measured, and reverted. The gate at the end of
   the phase is `git diff --stat main -- messaging eventsourcing modelling common conversion extensions test
   integrationtests`, which must print nothing.
2. **A mutation that escapes is a real gap in the suite**, not a curiosity. It is written up here plainly, and either
   closed in the same phase or filed as follow-up work with the oracle it needs.

---

## How a canary is run

```bash
# 1. Apply exactly one mutation to framework code.
# 2. Rebuild the mutated module into the local repository, so the hunt module resolves it.
./mvnw -q -o -pl eventsourcing -am install -DskipTests

# 3. Run the whole suite. Not a subset: the point is to learn which arms catch it, including the ones nobody
#    expected to.
./mvnw -q -Phunt -pl simulation -o test

# 4. Record the verdict, then revert and reinstall the clean module.
git checkout -- eventsourcing
./mvnw -q -o -pl eventsourcing -am install -DskipTests
```

`-o` keeps the loop offline and fast; `-am install -DskipTests` is what puts the mutated engine where the hunt
module will find it, because the hunt module resolves `axon-eventsourcing` from the repository rather than from the
reactor when it is built alone.

**Judge by the exit code.** Under `-q` a clean build prints nothing at all.

---

## Campaign of 2026-07-27 (L1, in-memory backend)

Every mutation below was applied to
`eventsourcing/src/main/java/org/axonframework/eventsourcing/eventstore/inmemory/InMemoryEventStorageEngine.java`,
measured at the smoke tier with the fixed seed set, and reverted.

NOTE ON READING THE COUNTS: for `AppendConformsToDcbModel` the number is NOT a measure of how
strongly a mutation was detected. The model replay diverges from the store after its first
mismatch, so every later comparison in the same run is downstream of that divergence; only the
FIRST violation of that invariant is evidence. Read the counts below as "caught / not caught",
never as sensitivity. The invariants that fold state rather than replay it
(`LedgerConservesTotalBalance`, `ProjectionMatchesFoldOfCommittedEvents`,
`AtMostOneSegmentOwner`, `RolledBackEventsNeverObservable`) do not have this limitation. See the
divergence entry in `FINDINGS.adoc` and the note in `HUNT-NOTES.md`.

| # | Mutation | Should be caught by | Caught? | Tier / seeds | Violations raised |
|---|---|---|---|---|---|
| C1 | The conflict check never finds a conflict | `AppendConformsToDcbModel` | **yes** | SMOKE, fixed set | 3567 `AppendConformsToDcbModel`, 5 `LedgerBalanceNeverNegative` |
| C2 | The conflict scan skips the event sitting exactly at the marker | `AppendConformsToDcbModel` | **yes** | SMOKE, fixed set | 52 `AppendConformsToDcbModel` |
| C3 | A committed batch of more than one event silently loses its first | `LedgerConservesTotalBalance`, `ProjectionMatchesFoldOfCommittedEvents` | **yes** | SMOKE, fixed set | 3028 `AppendConformsToDcbModel`, 57 `LedgerConservesTotalBalance`, 55 `ProjectionMatchesFoldOfCommittedEvents`, 8 `LedgerBalanceNeverNegative` |
| C4 | A rolled-back transaction publishes its batch anyway | `RolledBackEventsNeverObservable` | **yes** | SMOKE, fixed set | 3606 `RolledBackEventsNeverObservable`, 2695 `NoVisibilityBeforeCommit`, 954 `RejectedAppendLeavesNoEvents`, 46 `AppendConformsToDcbModel`, 4 `ProjectionMatchesFoldOfCommittedEvents` |

No mutation escaped the suite. What follows is the detail, including which arms stayed green and why that matters.

---

### C1 -- the conflict check never finds a conflict

```diff
     private boolean containsConflicts(AppendCondition condition) {
-        if (Objects.equals(condition.consistencyMarker(), ConsistencyMarker.INFINITY)) {
+        if (true) {
             return WITHOUT_MARKER;
         }
```

**Caught.** Test classes that went red:

```
org.axonframework.hunt.fault.FaultsLandTest
org.axonframework.hunt.model.ModelAndInMemoryEngineAgreeTest$Differential
org.axonframework.hunt.model.ModelAndInMemoryEngineAgreeTest$KnownEdges
org.axonframework.hunt.scenario.HuntReproduceTest
org.axonframework.hunt.scenario.RegressionSeedsTest$PinnedSeedsOfTheArmThatReplaysExactly
org.axonframework.hunt.scenario.ScenarioRunnerTest$ANewScenario
org.axonframework.hunt.scenario.ScenarioRunnerTest$ContendedAppendsHoldTheProtocol
```

The bluntest mutation, and the one every layer sees: the sequential differential, the pinned single-writer seeds and
the contended smoke arms all catch it independently.

### C2 -- the conflict scan skips the event sitting exactly at the marker

```diff
-        return this.eventStorage.tailMap(GlobalIndexConsistencyMarker.position(condition.consistencyMarker()))
+        return this.eventStorage.tailMap(GlobalIndexConsistencyMarker.position(condition.consistencyMarker()) + 1)
```

**Caught**, at a fiftieth of C1's volume, which is the point: this is the subtle version of the same defect, and the
one a hand-written test is least likely to have. Test classes that went red:

```
org.axonframework.hunt.fault.FaultsLandTest
org.axonframework.hunt.model.ModelAndInMemoryEngineAgreeTest$Differential
org.axonframework.hunt.model.ModelAndInMemoryEngineAgreeTest$KnownEdges
org.axonframework.hunt.scenario.HuntReproduceTest
org.axonframework.hunt.scenario.ScenarioRunnerTest$ContendedAppendsHoldTheProtocol
```

**The one asset that did not catch it: the pinned single-writer seeds.** That is not a defect in the pin, it is what
the pin is for. An event can only sit exactly at another append's marker if a second writer landed it there between
the first writer's read and its append, and a single-writer arm has no second writer. The consequence is worth
stating plainly, because the pinning policy makes single-writer seeds the only pinned seeds:

> **Pinned seeds cannot catch a contention-only defect.** The contended smoke arms and the sequential differential
> are what catch those, and they run on every change for exactly that reason.

The same reasoning, from the other direction, is why the campaign is run against the whole suite rather than against
the arm each mutation was designed for.

### C3 -- a committed batch loses its first event

```diff
                     ConsistencyMarker newLatest =
-                            events.stream()
+                            (events.size() > 1 ? events.subList(1, events.size()) : events).stream()
```

**Caught**, by the conservation law first and foremost, which is the whole argument for having one: nobody wrote an
assertion about batch completeness, and the arithmetic caught it anyway. Test classes that went red:

```
org.axonframework.hunt.fault.FaultsLandTest
org.axonframework.hunt.model.ModelAndInMemoryEngineAgreeTest$Differential
org.axonframework.hunt.scenario.HuntReproduceTest
org.axonframework.hunt.scenario.PartialBatchVisibilityTest$TheStoreOnceTheRunHasQuiesced
org.axonframework.hunt.scenario.RegressionSeedsTest$PinnedSeedsOfTheArmThatReplaysExactly
org.axonframework.hunt.scenario.ScenarioRunnerTest$AFaultThatNeverFires
org.axonframework.hunt.scenario.ScenarioRunnerTest$ANewScenario
org.axonframework.hunt.scenario.ScenarioRunnerTest$ContendedAppendsHoldTheProtocol
org.axonframework.hunt.scenario.TransactionPhaseFailureTest$AFailureAfterTheCommitHasPublishedTheBatch
org.axonframework.hunt.scenario.TransactionPhaseFailureTest$AFailureAtTheMomentOfCommit
org.axonframework.hunt.scenario.TransactionPhaseFailureTest$AFailureWhileEventsAreHandedToTheStore
```

**Cost note for whoever runs the campaign next.** This mutation makes the suite take roughly eleven minutes instead
of one. Nothing is wedged: the harness counts what it offered the store and waits for the read side to catch up with
it, and under a store that quietly keeps less than it was offered the read side never can, so every scenario spends
its full settle budget before reporting itself undecided. Budget for it rather than treating the slowdown as a hang.

### C4 -- a rolled-back transaction publishes its batch anyway

```diff
             @Override
             public void rollback() {
-                finished.set(true);
+                if (finished.getAndSet(true)) {
+                    return;
+                }
+                appendLock.lock();
+                try {
+                    for (TaggedEventMessage<?> event : events) {
+                        eventStorage.put(nextIndex(), event);
+                    }
+                    openStreams.forEach(m -> m.callback().run());
+                } finally {
+                    appendLock.unlock();
+                }
             }
```

**Caught**, by the arm built for it. Test classes that went red:

```
org.axonframework.hunt.fault.FaultsLandTest
org.axonframework.hunt.scenario.ScenarioRunnerTest$AStoreThatLosesHalfABatch
org.axonframework.hunt.scenario.TransactionPhaseFailureTest$AFailureAtTheMomentOfCommit
```

Three things are worth reading off that list. The commit-phase arm caught it, which is the arm whose entire purpose
is to make a rollback happen. The prepare-commit arm did **not**, correctly: an append that never returned a
transaction has no rollback to corrupt. The after-commit arm did **not** either, also correctly: the rollback it
provokes happens on a batch that was already published, and the harness records that rollback as having discarded
nothing.

That last point is the one to be careful about. Without it, C4 would look caught by the after-commit arm too, for
entirely the wrong reason, and the suite would report committed events as observable-after-rollback in every clean
run of that arm.

---

## Campaign of 2026-07-28 (L2, claim-capable backend)

Two mutations, both in `messaging` rather than in the storage engine, aimed at the two oracles the multi-node layer
added. The recipe is the same as above with `messaging` in place of `eventsourcing`:

```bash
./mvnw -q -o -pl messaging -am install -DskipTests   # after mutating
./mvnw -q -Phunt -pl simulation -o test               # measure
git checkout -- messaging                             # revert, always
./mvnw -q -o -pl messaging -am install -DskipTests    # restore
```

| # | Mutation | Should be caught by | Caught? | Tier / seeds | Violations raised |
|---|---|---|---|---|---|
| C5 | Any node may claim a segment another node already holds | `AtMostOneSegmentOwner` | **yes** | SMOKE, fixed set | 72 `AtMostOneSegmentOwner`, 24 `LedgerConservesTotalBalance`, 24 `ProjectionMatchesFoldOfCommittedEvents`, 15 `AppendConformsToDcbModel`, 5 `LedgerBalanceNeverNegative` |
| C6 | A batch's handler effects commit but this cycle's token progress does not | `DuplicateDeliveryOnlyInsideRecoveryWindow` | **no -- escaped** (see the 2026-07-28 re-run below, which catches it) | SMOKE, fixed set | none; the suite went green |

### C5 -- any node may claim a segment another node already holds

```diff
     public boolean mayClaim(String owner, TemporalAmount claimTimeout) {
-        return this.owner == null || owner.equals(this.owner) || expired(claimTimeout);
+        return true;
     }
```

in
`messaging/src/main/java/org/axonframework/messaging/eventhandling/processing/streaming/token/store/jdbc/JdbcTokenEntry.java`.
One line, and it removes the entire claim algebra: an entry owned by a live node now looks claimable to every other
node, and `fetchAvailableSegments` -- which filters on the same method -- offers every segment to everybody.

**Caught.** Test classes that went red:

```
org.axonframework.hunt.scenario.ConcurrentBootstrapTest$ANodeLeavingAndRejoiningMidStampede
org.axonframework.hunt.scenario.ConcurrentBootstrapTest$FourNodesBootingIntoAnEmptyTokenStore
```

A sample violation, verbatim:

```
[AtMostOneSegmentOwner] For every segment, the intervals during which distinct nodes hold its token claim never
overlap by more than the run's declared clock-skew allowance. | broken by: segment [hunt-projection/0] was held by
node-1 and node-3 at the same time for 20576ms, which is more than the declared clock-skew allowance of 0ms
```

Three things are worth reading off that result.

**The ownership oracle caught it directly, and the conservation law caught it independently.** Twenty-four
`LedgerConservesTotalBalance` violations came from the same runs, because four nodes processing the same segment apply
the same transfers several times and the projection's arithmetic stops adding up. That is the argument for a
conservation law restated in a new place: nobody wrote an assertion about double processing, and the sum of the
balances noticed anyway.

**Every arm that stayed green stayed green correctly.** The whole single-node corpus was untouched, which is what a
mutation in the claim algebra should do to a suite where only two scenarios have more than one node. It is also the
measurement of how thin the multi-node coverage still is: two scenario identifiers, both bootstrap arms, are the
entire blast radius of this mutation.

**The overlap it reports is enormous** -- twenty seconds against a two-second claim timeout -- because with the
algebra gone nobody ever loses a claim, so every node's interval runs to the end of the run. A subtler mutation would
produce a subtler overlap, and whether the oracle catches one of those has not been measured here.

### C6 -- a batch's effects commit without its progress

```diff
-        unitOfWork.onPrepareCommit(progressStrategy::onBatchCommit);
+        // canary: the batch's handler effects commit, this cycle's progress does not
```

in `messaging/src/main/java/org/axonframework/messaging/eventhandling/processing/streaming/pooled/WorkPackage.java`,
removing the line whose own comment reads "One transaction handles the batch AND persists this cycle's progress".
This is the classic at-least-once defect: the handler's effects land, the token does not advance, and the batch is
handed to the handler again.

**ESCAPED.** The suite went green: exit code 0, no test class red, and the only violations in the whole run were the
eighteen the suite raises on purpose through its own conflict-check-bypass fault. A mutation that breaks the
one-transaction guarantee between a batch and its progress cost the suite nothing, and that is a gap rather than a
curiosity.

Two compounding reasons, both established from the run rather than guessed.

**The stale token had no observable effect, because no segment changed hands mid-batch.** A work package keeps its
position in memory and advances through it whether or not the store was told; the stored token only matters when
somebody re-reads it, which happens when a claim is handed over or a node comes back. In the bootstrap arms nothing
handed a claim over mid-batch. The churn arm did crash and restart a node, and its history still shows five hundred
and forty deliveries with **zero** repeats: the node came back and resumed past the point the crash interrupted,
without re-reading far enough back to double anything.

**Where the mutation did double deliveries, the oracle correctly refused to judge them.** Exactly one run in the
suite saw them, and it is the arm that installs a store-duplicating fault:

```
INCONCLUSIVE fault_duplicate
  note: Repeated deliveries, by how many times an event arrived: {2=308}; 0 repeat(s) inside a recovery window and
  308 outside one, across 0 recorded window(s). Not judged because a fault made the store hold something other than
  what was offered.
```

The oracle saw all three hundred and eight, counted them, and declined to blame the framework for them -- which is
the right call for a run in which the harness itself is doubling appends, and the wrong outcome here, because these
particular repeats were the mutation's and not the fault's. The downgrade is per run and total: one store-perturbing
fault suppresses duplicate judgement for every repeat in that run, including the ones the fault could not have
caused.

### What would have caught it, and what that costs

| Missing oracle | Why it would have caught C6 | Status |
|---|---|---|
| A stored-token monotonicity and coverage check: the token written for a segment must cover every event already delivered from it | The mutation stops writing the token entirely, so the stored token falls arbitrarily far behind the delivered prefix from the very first batch, with no handover needed | Not built. It is scenario S17 in the plan, and it needs the token store's writes recorded, which this phase's recording decorator deliberately does not do because storing a token carries no claim decision. |
| A claim handover forced to land **mid-batch** | That is the only situation in which a stale stored token is read back during a run, and it is what turns C6 from invisible into a doubled effect | Not built. It is scenario S4, which needs a stall aimed at a work package rather than at a node, and is the next phase's. |
| Attributing a store perturbation to the fault that caused it, so that a vanish or a torn batch does not suppress duplicate judgement the way a duplicated append legitimately does | Two of the three store-perturbing faults cannot cause a repeated delivery at all, so their runs should still judge duplicates | Not built. The `store-perturbed` note already carries which interference fired, so the refinement is small; it was not done here because it only matters once an arm produces duplicates for a reason other than the fault. |

The honest summary is that the duplicate oracle works -- it counted every repeat -- and that nothing in the current
scenario corpus puts it in a position to judge one. Two of the three gaps above are the next phase's scenarios, which
is where this belongs; the third is a checker refinement worth doing when the first arm needs it.


Honest gaps, so that nobody reads the table above as covering more than it does.

| Not canaried | Why | Owner |
|---|---|---|
| ~~The read side: a processor that skips, duplicates or reorders a delivery~~ | Attempted as C6, escaped, re-run after the ownership and durable-progress oracles landed, and **caught**. See the re-run above. | closed |
| Attributing a store perturbation to the fault that caused it | Named in C6's original diagnosis and still unbuilt. The re-run shows it was not what blocked C6: the arms that catch the mutation install no store-perturbing fault, so nothing suppresses their judgement. Worth doing when an arm produces duplicates for a reason other than the fault it declared. | follow-up |
| A mutation of the split or merge algebra | The membership scenarios ship, and no mutation of `SplitTask`, `MergeTask` or `Segment` has been run against them. The oracles they would exercise -- coverage across segment epochs, per-key order across a rebuild -- have never been shown to fail. | follow-up |
| The sequencing policy path | `SequenceKeyOrderPreserved` is exercised only by the arms in `SequencingPolicyOrderTest`, and a mutation of `SequencingEventHandlingComponent`'s chaining has not been run against it | follow-up |
| Anything backend-specific | One backend ships. A per-backend verdict vector needs at least two | the phase that adds a backend |
| Every mutation at a tier above smoke | The campaign was run at the smoke tier with the fixed seed set. Nothing here says how many seeds a subtler mutation would need | the phase that runs the fuzz tier |

---

## Re-run of C6 after the L2 layer was completed (2026-07-28)

C6 escaped once. The three things its own write-up said would catch it were a stored-token coverage oracle, a claim
handover that lands mid-batch, and per-interference attribution of the store-perturbation downgrade. Two of those were
built; the third turned out not to be needed. The mutation was applied again, unchanged, and measured against the same
recipe.

```diff
-        unitOfWork.onPrepareCommit(progressStrategy::onBatchCommit);
+        // canary: the batch's handler effects commit, this cycle's progress does not
```

in `messaging/src/main/java/org/axonframework/messaging/eventhandling/processing/streaming/pooled/WorkPackage.java:387`.

| # | Mutation | Should be caught by | Caught? | Tier / seeds | Violations raised |
|---|---|---|---|---|---|
| C6 (re-run) | A batch's handler effects commit but this cycle's token progress does not | `ClaimHandoverRewindsAtMostOneBatch`, `StoredTokenCoversDeliveredEvents` | **yes** | SMOKE, fixed set | 84 `ClaimHandoverRewindsAtMostOneBatch`, 94 `StoredTokenCoversDeliveredEvents` across the run |

**Caught.** Test classes that went red:

```
org.axonframework.hunt.harness.ClaimCapableBackendTest$AScenarioWrittenBeforeItExisted
org.axonframework.hunt.scenario.ConcurrentBootstrapTest$ANodeLeavingAndRejoiningMidStampede
org.axonframework.hunt.scenario.ReplayAfterResetTest$RewindingAStoppedProcessor
org.axonframework.hunt.scenario.ScenarioRunnerTest$ContendedAppendsHoldTheProtocol
org.axonframework.hunt.scenario.SplitAndMergeUnderLoadTest$ASplitAndMergeStormWhileTheWorkloadWrites
```

A sample violation, verbatim:

```
[ClaimHandoverRewindsAtMostOneBatch] When a segment's token is claimed again, the events already delivered from that
segment that the stored token does not cover are the events of at most one batch. | broken by: segment [0] was claimed
by node-0 while the stored token reported position -1, leaving 403 event(s) already delivered from it uncovered, which
is more than the run's batch of 64
```

and the distribution the same run reported, which is the shape of the defect in one line:

```
Claim handovers, by how many already-delivered events the stored token left uncovered: {0=35, 345=2, 400=2}
```

### What actually catches it, and what turned out not to matter

**The rewind at a re-claim is the detector, and the quantity is not the redeliveries.** The original diagnosis expected
the duplicate oracle to catch the doubled effects. It cannot, on its own: a duplicate inside a recovery window is licensed
by the framework's own contract, so a mutation that produces licensed duplicates is reported and not violated. What is not
licensed is *how far the stored token had fallen behind the effects already applied* when the token was read back. Under
the guarantee that is one batch at most, because every batch that finished stored its progress as part of finishing.
Under the mutation the stored token never moved at all -- `position -1` in the violation above -- so the rewind is
everything the segment had ever done. Measured: up to 774 events against a batch of 64.

**A stored-token position of -1 under sustained load was the surprise.** The mutation does not stop the token being
stored altogether: `WorkPackage.upkeepIfThresholdIsMet` opens its own transaction on the claim-extension beat and lets the
progress strategy catch up, so an *idle* segment still stores its position. That path is only reached when the work
package finds nothing to handle, so under load it is never reached, and the stored token stays at its initial value for
the whole busy period. That is why a coverage oracle measured only after quiescence would still have missed this: by then
the segments have gone idle and caught up. Measuring at the instant a claim is granted is what makes it visible.

**A mid-batch handover was necessary, and a crash is what produces one.** The stall seam cannot: a node frozen inside its
handler keeps every claim it holds, because extending a claim is the coordinator's work on a separate thread that keeps
running (finding F-13). Dropping the node's threads for longer than a claim survives is what leaves a segment owned by a
process that has stopped refreshing it, and it is what the ownership scenario does.

**Per-interference attribution of the store-perturbation downgrade was not needed.** The third item in the original
diagnosis was a refinement to stop a vanish or a torn batch suppressing duplicate judgement. It remains unbuilt, and the
re-run shows why it was not the blocker: the arms that catch C6 do not install a store-perturbing fault at all, so
nothing suppresses their judgement. The refinement is still worth having and is still listed as not canaried.

### What the campaign learned about its own oracles, from the same run

Three of this phase's own oracles reported findings that were not real before they were corrected, and each correction is
part of what makes the C6 result trustworthy rather than lucky.

| Oracle | What it reported | Why it was wrong | What it does now |
|---|---|---|---|
| `StoredTokenNeverRegresses` | A 426-position regression on segment 4 | The write was one a node made after losing its claim, and the store had refused it on the owner clause of its own update statement | Judges the outcome, not the attempt |
| `StoredTokenNeverRegresses` | A regression across a merge | A merge gives the merged segment the lower of two tokens, so the surviving identifier goes backwards by design | Licenses a rewind across a recorded split or merge, over the instruction's whole span rather than its issue instant |
| `AtMostOneSegmentOwner`, `DeliveryAttributedToSegmentOwner` | Two nodes holding one segment for 1812ms, and deliveries from nodes holding no claim | A split deletes a token row and creates two; no interval derived from claim traffic can follow a segment across that | Ends every interval at a segment-set rebuild, and refuses to judge attribution at all on a run that rebuilt its segments |

## Campaign of 2026-07-28 (P6, the Axon Server backend)

```bash
# The mutation is in messaging, so that module and its dependents are what get reinstalled.
./mvnw -q -o -pl messaging -am install -DskipTests
./mvnw -Phunt -pl simulation -o test -Dhunt.excludedGroups=fuzz -Dtest=HuntReproduceTest \
    -Dhunt.scenario=dcb_append_rejected_after_marker_single_writer -Dhunt.seed=1 \
    -Dhunt.backend=<in-memory | axonserver> -Dsurefire.failIfNoSpecifiedTests=false
git checkout -- messaging && ./mvnw -q -o -pl messaging -am install -DskipTests
```

### What this campaign had to establish, and the constraint it ran into first

A canary on a new backend is only worth planting if its detection **depends on that backend's semantics**. A mutation
every arm catches says nothing about the new one.

The obvious target was recorded five phases ago as an untested hypothesis (`HUNT-NOTES` note 2.2): the in-heap engine
decides a boundary through `EventCriteria.matches()`, the interpreted form, while a store that builds a query does it
from `EventCriteria.flatten()`. Poison `flatten()` and every in-heap arm is untouched while a wire-query store sees the
wrong thing. Exactly the shape wanted.

**It cannot be pointed at the model oracle, and the reason is structural rather than fixable here.** `AxonModelCodec`
records an append's condition by reading `condition.criteria().flatten()` -- the same accessor the connector builds its
wire condition from. So a mutation of `flatten()` moves the reference model and the store **together**, they agree, and
the comparison sees nothing. Generalised, and worth carrying: *the model-conformance oracle judges the condition the
engine was handed, so no framework mutation of how that condition is derived can be caught by it.* Canarying that oracle
on this arm would need a mutation of the **store's decision**, and the store is a container rather than framework code.

So the canary targets the one `flatten()` path the recorder does not share: `EventCriteria.havingAnyTag()`, which the
whole-store scan uses and which the recorder never reads.

| Canary | Mutation | Expected to be caught by | Caught? | Tier | Numbers |
|---|---|---|---|---|---|
| C8 | The match-everything criteria flattens to a criterion nothing matches | `AcknowledgedAppendIsDurable` on the Axon Server arm; nothing on any in-heap arm | **no -- escaped**, and the escape is informative | SMOKE, seed 1, `in-memory` and `axonserver` | in-heap arm clean, as predicted. Axon Server arm reported **one note and nothing else**: "The run outlived its wall-clock budget". `INCONCLUSIVE`, not `FAIL`. |

### C8 -- the match-everything criteria flattens to a criterion nothing matches

`messaging/src/main/java/org/axonframework/messaging/eventstreaming/AnyEvent.java`:

```java
 public Set<EventCriterion> flatten() {
-    // AnyEvent does not have a criterion, as it matches all.
-    return Collections.emptySet();
+    // CANARY C8: a criterion no event can match, so a store that builds its query from flatten() sees nothing.
+    return EventCriteria.havingTags(Tag.of("hunt-canary", "no-such-tag")).flatten();
 }
```

`AnyEvent.matches(...)` is left returning `true`, which is the whole point: the interpreted form still says "everything
matches" and the flattened form now says "nothing does". A store that interprets is unaffected; a store that queries is
blinded.

**Half the prediction held.** The in-heap arm was untouched -- clean run, 268 of 400 appends acknowledged, no violation --
which confirms note 2.2's five-phase-old hypothesis directly: the two forms really are independent, and a defect in one is
invisible to a store that uses the other. That is worth having on its own.

**The other half did not, and the reason is the interesting part.** The prediction was
`AcknowledgedAppendIsDurable`: the authoritative scan asks the store through `havingAnyTag()`, so under the mutation it
returns nothing, and every acknowledged append's events are then absent from it. What actually happened is that the arm
**never got as far as being judged**. Its only note was that the run outlived its wall-clock budget, so the verdict was
`INCONCLUSIVE` and no oracle decided anything.

### Why it escaped, and what would close it

Two distinct holes, and both are about the arm rather than about the oracle.

1. **An undecided arm cannot signal.** This is the suite's own recorded lesson -- "an arm that can never reach a pass can
   never signal a regression either" -- arriving from the other direction: a mutation that pushes an arm into
   *undecidedness* is as invisible as one that leaves it green. The three-valued verdict protects against false findings
   and it does not protect against this.
2. **`HuntReproduceTest` asserts no verdict.** It is a re-sampler for a human to read, so even a `FAIL` there would not
   have failed a build. The assertion belongs on the arm's own test class, which does assert, and the canary was run
   through the reproducer because it is the only entry point that takes `-Dhunt.backend` for an arbitrary scenario.

What would close it, in order of value:

- **A scan that answers empty is not a store that holds nothing.** The drain already distinguishes "the store gave no
  answer" from "the store answered" -- P3b added that after a wrong column name hid 89 apparently-lost appends -- but an
  answer of *zero* on a run that recorded acknowledged appends is the same class of lie and is currently accepted. A guard
  comparing the scan against the count of acknowledged appends would have turned this mutation red immediately, and it is
  a few lines in the drain. **This is the highest-value follow-up from the whole campaign.**
- A backend-parameterised arm in `AxonServerBackendTest` that asserts a verdict, so a canary run through it fails a build.

### What this campaign did not do

- It did not canary the reference-model oracle on this arm, and that is structural: see the constraint above. The arm's
  audit carries it as a short row rather than as an omission.
- It planted one mutation, not a set. Every other campaign in this document planted at least two.

## What is not yet canaried

Honest gaps, so that nobody reads the tables above as covering more than they do.

| Not canaried | Why | Owner |
|---|---|---|
| ~~The read side: a processor that skips, duplicates or reorders a delivery~~ | Attempted as C6, escaped, re-run after the ownership and durable-progress oracles landed, and **caught**. See the re-run above. | closed |
| Attributing a store perturbation to the fault that caused it | Named in C6's original diagnosis and still unbuilt. The re-run shows it was not what blocked C6: the arms that catch the mutation install no store-perturbing fault, so nothing suppresses their judgement. Worth doing when an arm produces duplicates for a reason other than the fault it declared. | follow-up |
| A mutation of the split or merge algebra | The membership scenarios ship, and no mutation of `SplitTask`, `MergeTask` or `Segment` has been run against them. The oracles they would exercise -- coverage across segment epochs, per-key order across a rebuild -- have never been shown to fail. | follow-up |
| The sequencing policy path | `SequenceKeyOrderPreserved` is exercised only by the arms in `SequencingPolicyOrderTest`, and a mutation of `SequencingEventHandlingComponent`'s chaining has not been run against it | follow-up |
| Anything backend-specific | One event-store backend ships, and the second backend differs only in its token store. A per-backend verdict vector needs two stores that speak the same protocol | the phase that adds a backend |
| Every mutation at a tier above smoke | Both campaigns were run at the smoke tier with the fixed seed set. Nothing here says how many seeds a subtler mutation would need | the phase that runs the fuzz tier |

---

## Campaign of 2026-07-28 (P3a, the real backend)

One mutation, in the aggregate-based JPA engine's gap handling. It was chosen because **the in-heap store cannot express
it at all**: an in-heap `TreeMap` has no durable index taken from a sequence before a transaction commits, so it has no
gaps, so there is nothing to mutate. The mutation is only reachable, and only detectable, on a real store -- which is what
a backend canary has to be if it is measuring anything the phase added.

```bash
# after mutating
./mvnw -q -o -pl eventsourcing -am install -DskipTests
./mvnw -Phunt -pl simulation -o test -Dhunt.excludedGroups=fuzz -Dtest=BackendDifferentialTest \
    -Dsurefire.failIfNoSpecifiedTests=false
git checkout -- eventsourcing                            # revert, always
./mvnw -q -o -pl eventsourcing -am install -DskipTests   # restore
```

| # | Mutation | Should be caught by | Caught? | Tier / seeds | What the run showed |
|---|---|---|---|---|---|
| C7 | The gap-aware token records no gap, so an index skipped at read time is never re-read | `NoCommittedEventGoesUndelivered` | **no -- escaped** | SMOKE, fixed seed, `postgres-jpa` and `postgres-jpa-split-tokens` | The run took seven times as long and the read side never caught up; no loss violation was raised, and no test went red because of the mutation |

### C7 -- the gap-aware token records no gap

```diff
     private GapAwareTrackingToken calculateToken(...) {
-        boolean allowGaps = timestamp.isAfter(gapTimeoutThreshold);
+        boolean allowGaps = false;
```

in `eventsourcing/src/main/java/org/axonframework/eventsourcing/eventstore/jpa/AggregateBasedJpaEventStorageEngine.java`.
This engine's `globalIndex` comes from a database sequence taken before the transaction commits, so a reader routinely
sees index `n+1` while `n` is still uncommitted. Recording `n` as a gap is what makes the reader come back for it. Remove
that and every event whose neighbour committed first is skipped for ever.

**It escaped, and the reason matters more than the mutation.** The arm's verdict changed -- from a clean
seven-second run to one that exhausted its settle budget and overran its wall budget -- and the checker that exists to
catch loss said nothing at all:

```
postgres-jpa note: The read side had not caught up with the store within the settle budget.
postgres-jpa note: The run outlived its wall-clock budget of scenario dcb_append_rejected_after_marker_under_contention seed 1.
postgres-jpa note: 0 NoCommittedEventGoesUndelivered violations
```

`DeliveryChecker` refuses to call an event lost on a run whose read side had not caught up, and it is right to: an
interrupted run has not lost anything. **But a store that loses an event permanently produces exactly the same
observation** -- the read side can never catch up, because what it is waiting for will never arrive -- so the guard that
stops false findings also swallows the real one. This is the same shape as the C6 escape, in a new place: a downgrade
meant to protect against a harness artefact suppressed the oracle that would have caught a framework defect.

### What was tried to close it, why it turned the mutation red, and why it was still removed

An assertion was added that each arm's read side **caught up** and that the run did not overrun its budget. It did turn the
mutation red on `postgres-jpa` and left the in-heap and HSQLDB arms green, which is exactly the attribution a backend
canary is for. It was then **removed**, because it is red on a clean engine too.

Measured, on an unmutated engine: the PostgreSQL arms do not reach quiescence with this workload at any budget tried --
a thousand commands with a thirty-second settle and three hundred with a sixty-second settle behave identically. The
reason is in the harness, not in the mutation: `ControllableEventStorageEngine` counts an append as stored when the
engine's `commit()` call returns, and on this engine that call returns while the database transaction is still open, so
the number the read side is chased against is not a number of readable events. Shipping the assertion anyway would have
shipped a permanently red test -- the same inertness, in the opposite direction, that this phase removed from the replay
and membership arms.

So the mutation escapes, and it escapes for a reason worth writing down in full.

| What would catch it | Why | Status |
|---|---|---|
| A store-count the read side can actually reach: count an append as readable when its **transaction** commits, not when the engine's commit call returns | Quiescence then means what it says on a two-phase store, and "the read side caught up" becomes an assertion a clean run satisfies and a lossy one does not | Not built. It is a change to `ControllableEventStorageEngine`'s accounting and needs an after-commit hook the wrapper already receives. |
| A loss oracle that distinguishes permanent loss from lateness | `DeliveryChecker` declines to call an event lost on a run whose read side had not caught up, and a store that loses an event permanently produces exactly that observation. Comparing the delivered set's growth across the settle phase would decide it: a set that stopped growing while events are still missing has lost them. | Not built. Same shape as the gap C6 left, in a new place. |

Neither is exotic and both are small. What they are not is free, and claiming the canary was caught on the strength of an
assertion that is red either way would have been the overclaim this file exists to prevent.

### What this campaign did not do

| Not canaried | Why | Owner |
|---|---|---|
| A mutation of the aggregate sequencer or the marker algebra | Two real findings already live there (F-14 and F-15), so the arms are red on those stores for reasons that are not planted, and a mutation's effect could not be told apart from them until they are fixed | after F-14 and F-15 |
| Anything on Axon Server | P3b's, and the connector is absent from this tree | P3b |
| The gap mutation at a tier above smoke | One seed of one scenario on two stores. Nothing here says how many seeds a subtler gap defect would need | the phase that runs the fuzz tier |
| A loss oracle that can tell permanent loss from lateness | The gap above is closed by asserting on the run rather than on the invariant. A checker that compared the delivered set's growth across the settle phase could decide it properly, and would close the C6-shaped hole for good | follow-up |

---

## Campaign of 2026-07-28 (P3b, the C7 re-run)

C7 escaped in P3a, and its write-up named exactly two things that would close it: a store-count the read side can actually
reach, and a loss oracle that can tell permanent loss from lateness. Both were built in this phase, and the mutation was
applied again, unchanged:

```diff
     private GapAwareTrackingToken calculateToken(...) {
-        boolean allowGaps = timestamp.isAfter(gapTimeoutThreshold);
+        boolean allowGaps = false;
```

in `eventsourcing/src/main/java/org/axonframework/eventsourcing/eventstore/jpa/AggregateBasedJpaEventStorageEngine.java:427`.

The recipe is P3a's, with the arms this phase added:

```bash
./mvnw -q -o -pl eventsourcing -am install -DskipTests            # after mutating
./mvnw -Phunt -pl simulation -o test -Dhunt.excludedGroups=fuzz \
    -Dtest='StoreInfrastructureFailureTest$HoldingCommitsOpenPastTheStoresGapTimeout,BackendDifferentialTest$WhatEachStoreDeliveredOfWhatItCommitted' \
    -Dsurefire.failIfNoSpecifiedTests=false
git checkout -- eventsourcing                                     # revert, always
./mvnw -q -o -pl eventsourcing -am install -DskipTests            # restore
```

### The two changes that closed the hole, and the measurement that proves each one

**The store is now asked what it holds.** `ControllableEventStorageEngine.readableEventIds()` scans the store, the drain
decides quiescence by asking whether every readable identifier was delivered, and the scan that decides quiescence is the
one the oracles are judged against. Measured, on a clean engine, before and after:

```
before: postgres-jpa note: The read side had not caught up with the store within the settle budget.   (every run, every budget)
after:  MEASURED postgres-jpa read side caught up: true
```

That is the change P3a said was needed and could not make, and it is what turns "the read side caught up" from a wish into
an observation.

**Loss is decided on a read side that has stopped, not only on one that caught up.** `DeliveryChecker` declined to call an
event lost on a run whose read side had not caught up -- correctly, because an interrupted run has lost nothing -- and a
store that skips an index for ever produces exactly that observation. The drain now reports whether the delivery count
stopped moving for longer than the run's stall window, and loss is decided on a stalled read side exactly as on a quiesced
one. `DeliveryCheckerTest.callsItLossWhenTheReadSideHadStoppedMovingAltogether` pins it.

### What the re-run showed, and the honest reading of it

| # | Mutation | Should be caught by | Caught? | Tier / seeds | What the run showed |
|---|---|---|---|---|---|
| C7 (re-run) | The gap-aware token records no gap, so an index skipped at read time is never re-read | `NoCommittedEventGoesUndelivered` | **yes** | SMOKE, seeds 1 and 2, `postgres-jpa`, `postgres-jpa-split-tokens`, `postgres-jpa-chaos`, `postgres-jpa-chaos-spring-defaults` | On the shared-resource PostgreSQL arm the clean engine delivers everything it commits and the mutated engine loses events, with the loss **decided** rather than excused. Both halves are new: the loss is what the mutation causes, and the decision is what P3a could not make. |

**The measurement, side by side.** Same arm, same seeds, same budget, one line of the engine apart:

```
clean    MEASURED postgres-jpa seed 1 undelivered=0 decided=false caughtUp=true
clean    MEASURED postgres-jpa seed 2 undelivered=0 decided=false caughtUp=true
mutated  MEASURED postgres-jpa seed 1 undelivered=3 decided=true  caughtUp=false
mutated  MEASURED postgres-jpa seed 2 undelivered=6 decided=true  caughtUp=false
```

`decided=true` is the whole of what changed in the suite. In P3a the mutated run produced the identical
`caughtUp=false` observation and the oracle reported `0 NoCommittedEventGoesUndelivered violations`, because a read side
that has not caught up was excused. It now reports the loss and names the events.

### What was shipped, and what was deliberately not

The assertion that ships is the **decidability** property:

> where the read side stopped moving with events still in the store, loss is decided and not excused.

Measured green on a clean engine and green under the mutation -- it asserts that a skip is called a skip, not that no skip
happened -- and it goes red the moment the guard regresses.
`DeliveryCheckerTest.callsItLossWhenTheReadSideHadStoppedMovingAltogether` is the unit-level red-on-mutation proof: it
plants an undelivered committed event with `stalled=true` and fails if the checker reports a note instead of a violation.

**What was not shipped is `assertThat(caughtUp).isTrue()` on the PostgreSQL arms, and the reason is measured rather than
cautious.** It is the assertion that would turn C7 red, and it is green on the shared-resource arm's two seeds above --
but the split-resource arm loses events on a *clean* engine on both its seeds:

```
clean    MEASURED postgres-jpa-split-tokens seed 1 undelivered=2 decided=true caughtUp=false
clean    MEASURED postgres-jpa-split-tokens seed 2 undelivered=4 decided=true caughtUp=false
```

and the shared-resource arm did so too on an earlier clean run of the same arm at a different stall window. So the
assertion is red on a clean engine some of the time, which is a flaky test rather than an oracle -- the exact thing P3a
refused to ship and for the same reason. The loss itself is written up as finding F-16 instead, where an intermittent
result can be reported as intermittent.

The consequence for the campaign, stated plainly: **C7 is caught and discriminating as a measurement, and is not pinned by
an assertion.** Re-running it is two commands and the numbers above are what to compare against.

### Two harness defects this campaign found before the mutation was applied at all

Recorded because both were silent, both looked like framework findings, and both would have been written up as such.

| What was reported | What it really was |
|---|---|
| 89 `AcknowledgedAppendIsDurable` violations on the kill arm -- every acknowledged append apparently lost | The authoritative scan used the column name `eventidentifier`; the column is `identifier`. The scan threw, the drain kept its previous answer (the empty list), `containsAll(empty)` declared quiescence, and every oracle held vacuously against a store believed to hold nothing. |
| A balance mismatch on both PostgreSQL arms with nothing lost | Quiescence was decided by comparing counts. A store whose index comes from a sequence taken before a commit separates one batch's rows with another writer's, so the count can be reached while half a transfer is undelivered, and the projection was folded mid-transfer. Quiescence now compares sets. |
| 154 `NoVisibilityBeforeCommit` violations on the gap arm | The fault delayed `AppendTransaction.commit()`, which on a transaction-managed store does no work and races the database transaction rather than preceding it (finding F-17). The harness's own delay was being measured. |
| 9 `StoredTokenNeverRegresses` violations on the partition arm, one a 426-position regression | A token write is recorded as successful when its call returns, and on a shared-resource arm that write joins the transaction that commits the batch. A write whose transaction then died with the connection never landed, so the next claim reads back the older token. All three of that checker's invariants are now not applicable on a run declaring a connection-breaking fault. |

## What is not yet canaried

| Not canaried | Why | Owner |
|---|---|---|
| A mutation of the gap machinery that the framework does not already exhibit | C7 is subsumed by finding F-16. A discriminating gap canary now needs a mutation whose effect F-16 does not already produce -- of `withGapsCleaned`, or of `GapAwareTrackingToken.advanceTo`'s offset arithmetic. | after F-16 is fixed |
| Anything against the infrastructure faults themselves | The three primitives are verified against the container by hand and every arm asserts its own landing evidence, but no mutation has been planted that a landing-evidence check would catch. A fault that silently stopped firing would be caught by `DeclaredFaultsLand`; one that fired against the wrong thing would not. | follow-up |
| The `store-freeze` primitive | Built and verified against the container, and no scenario declares it. Nothing here says what the suite would do with a store that stops answering and then continues. | the phase that pauses a cluster |
| A mutation of the split or merge algebra | Unchanged from P2b. | follow-up |
| Anything at a tier above smoke | Unchanged. Every campaign so far is smoke with a fixed seed set. | the phase that runs the fuzz tier |
| The reference-model oracle on any store the suite does not implement in Java | Structural, established by C8: the oracle records an append's condition through the same accessor the store's client builds its wire condition from, so no framework mutation of the condition's derivation can be caught by it. Catching one would need a mutation of the store's own decision, which for Axon Server is a container. | needs a mutable store, or an injected wire-level fault |
| Anything on the Axon Server arm that a *decided* verdict would catch | C8 pushed the arm into undecidedness instead of failure, so no oracle on it has yet been shown to go red on a planted defect. The arm's oracles have been shown to go red on a **real** defect (F-19, F-20), which is worth more, but it is not the same thing as a planted one. | the phase that adds the empty-scan guard |
