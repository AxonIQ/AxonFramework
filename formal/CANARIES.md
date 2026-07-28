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

## What is not yet canaried

Honest gaps, so that nobody reads the table above as covering more than it does.

| Not canaried | Why | Owner |
|---|---|---|
| The read side: a processor that skips, duplicates or reorders a delivery | Needs a mutation in `messaging`, and the arms that would catch it (claim handover, split and merge, replay) need a claim-capable token store that does not exist yet | the phase that adds one |
| The sequencing policy path | `SequenceKeyOrderPreserved` is exercised only by the arms in `SequencingPolicyOrderTest`, and a mutation of `SequencingEventHandlingComponent`'s chaining has not been run against it | follow-up |
| Anything backend-specific | One backend ships. A per-backend verdict vector needs at least two | the phase that adds a backend |
| Every mutation at a tier above smoke | The campaign was run at the smoke tier with the fixed seed set. Nothing here says how many seeds a subtler mutation would need | the phase that runs the fuzz tier |
