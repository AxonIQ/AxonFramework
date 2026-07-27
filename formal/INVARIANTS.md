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
record #9 differs: 9|writer-0|append|INVOKE|null|null
             against 9|projection|deliver|INVOKE|acct-0|null
```

Verbatim differences in `REAL_THREADS` (one run of many; the numbers move every time):

```
record count differs: 2172 against 2134
append verdicts differ: {FAIL=157, OK=139, UNKNOWN=0} against {FAIL=160, OK=136, UNKNOWN=0}
store contents differ: 102 event(s) only in the first run, 96 only in the second
```

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
engine and tracked as finding F-3.

---

## 3. MachineName registry

Every invariant the suite enforces. The statement column is normative: it is the exact wording that
must appear in the checker's Javadoc, in the violation message, and in any TLA+ operator comment
modelling the same property.

| MachineName | Statement | Claims | Checker class | Scenarios | TLA+ operator |
|---|---|---|---|---|---|
| `AppendConformsToDcbModel` | Every append recorded as successful is accepted by the DCB reference model at its point in the history, and every append recorded as rejected is rejected by it. | C1, C2, C3, C5, C6, C7, C8, C10 | `ModelConformanceChecker` | (P1: S1) | (P4: `DcbAppend.tla`) |
| `NoVisibilityBeforeCommit` | No event is delivered to a consumer before the commit of the transaction that appended it. | C4, C29 | `VisibilityChecker` | (P1: S3) | -- |
| `RolledBackEventsNeverObservable` | No event of a rolled-back transaction is ever delivered to a consumer or present in a post-run scan of the store. | C29 | `VisibilityChecker` | (P1b: S3) | -- |
| `UnconditionalAppendNeverRejected` | An append made without a consistency condition is never rejected as conflicting. | C2 | `AppendOutcomeChecker` | `dcb_append_rejected_after_marker_under_contention` | (P4: `DcbAppend.tla`) |
| `RejectedAppendLeavesNoEvents` | No event offered by an append recorded as rejected is present in the authoritative scan taken after the run has quiesced. | C9, C10 | `AppendOutcomeChecker` | `dcb_append_rejected_after_marker_under_contention` | -- |
| `LedgerConservesTotalBalance` | The balances the projection reports sum to the ledger's opening total. | C4, C15, C16 | `ConservationChecker` | every scenario driving the ledger | -- |
| `LedgerBalanceNeverNegative` | No account balance is negative at any point in the sequence of committed transfers. | C1, C5, C8 | `ConservationChecker` | every scenario driving the ledger | -- |
| `ProjectionMatchesFoldOfCommittedEvents` | The balance projection at the end of the run equals the fold of the transfers the run committed. | C4, C15, C16 | `ConservationChecker` | every scenario driving the ledger | -- |
| `DeclaredFaultsLand` | Every fault a run declares fires at least once, and the run records how often and against what. | -- (suite constitution, rule 4) | `FaultLandingChecker` | every scenario declaring a fault | -- |

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
