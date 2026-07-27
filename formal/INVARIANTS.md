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

## 2. Determinism boundary

What a seed fixes, and what it does not. Overclaiming here is the fastest way to turn a harness bug
into a phantom finding.

**Deterministic given a seed:**

- The workload shape: the number of writers, the tag pool, the tag-selection distribution, the
  operation mix, the batch sizes, and the order in which one process issues its operations.
- The reference model. `DcbStoreModel` is pure and depends on the JDK only; the same operation
  sequence always produces the same verdicts and the same store contents.
- Every checker's verdict for a given history file. Checkers are pure functions of the history.
- The history schema and the assignment of record sequence numbers within a run.

**Not deterministic, seed or no seed:**

- **Interleaving across threads and nodes.** Real threads are used deliberately, because the bugs
  being hunted live in interleavings. A seed fixes the shape of the load, never the schedule.
- **Wall-clock timestamps** (`HistoryRecord.wallTs`) and logical timestamps
  (`HistoryRecord.logicalTs`, derived from `System.nanoTime()`). Only the record sequence number
  defines the history's order.
- **The file order of history lines.** Records are serialized outside the recorder's write lock, so
  under contention a line may reach the file out of sequence. `HistoryView` sorts by sequence number
  on read; nothing else may assume file order.
- **Anything reading the JVM-global clock.** `ClockUtils` holds a single static
  `AtomicReference<Clock>` (`common/src/main/java/org/axonframework/common/ClockUtils.java:49`), and
  token-claim expiry reads it
  (`messaging/src/main/java/org/axonframework/messaging/eventhandling/processing/streaming/token/store/jpa/TokenEntry.java:159-160`).
  Per-node clock skew inside one JVM is therefore impossible through that seam. See
  `formal/HUNT-NOTES.md`.

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
| `RolledBackEventsNeverObservable` | No event of a rolled-back transaction is ever delivered to a consumer or present in a post-run scan of the store. | C29 | `VisibilityChecker` | (P1: S3) | -- |

Scenario columns are filled in as the scenarios land; an invariant with no scenario is asserted by
its unit-level canaries only, and the registry says so rather than implying coverage it does not
have.

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

## 6. Adding a fault, a workload, or a backend

The same shape applies; the recipes land with the phases that build those registries. What is fixed
already is the contract they all meet: whatever drives the system records the history described in
section 4, and gets the whole checker set for free.
