# Axon Hunt -- Working Notes

Append-only. Every phase adds to the bottom of the relevant section; nothing here is deleted or
rewritten, because the value of the file is that a later agent does not rediscover what an earlier
one already paid for.

Write for the next agent, not for yourself. Phase P5 compiles the `axon-hunt` skill out of this
file, so a note is worth writing when it would have saved you an hour, and not otherwise.

Contents:

1. Determinism seams
2. API traps
3. Commands that worked
4. Design decisions and their reasons
5. Deferred to later phases

---

## 1. Determinism seams

### 1.1 `ClockUtils` is JVM-global, and token-claim expiry reads it

`common/src/main/java/org/axonframework/common/ClockUtils.java:49` holds a single static
`AtomicReference<Clock>` for the whole JVM. Token-claim expiry is evaluated against it:
`TokenEntry.expired()` at
`messaging/src/main/java/org/axonframework/messaging/eventhandling/processing/streaming/token/store/jpa/TokenEntry.java:159-160`
calls `ClockUtils.instant()`.

**Consequence: per-node clock skew inside one JVM is impossible through that seam.** Setting a
"node's" clock sets every node's clock. Two nodes in one JVM cannot disagree about whether a claim
has expired.

By contrast `SplitTask` takes an injected `clock` field
(`messaging/src/main/java/org/axonframework/messaging/eventhandling/processing/streaming/pooled/SplitTask.java:113`),
so seams do exist elsewhere in the processor. Do not assume that because one component takes a
`Clock` the claim path does.

Anything needing per-node skew has two options, both of which cost real work:

- a claim-aware fake `TokenStore` implementing the `TokenEntry` claim algebra against an injected
  clock, so each node gets its own; or
- separate JVM processes, which also buys real crash semantics and costs orchestration.

Recorded as finding F-4.

### 1.2 `InMemoryTokenStore` has no ownership concept at all

No owner field, no timestamp, no expiry. `releaseClaim` is a no-op, `fetchToken` never fails on
ownership, `fetchAvailableSegments` equals `fetchSegments`.

**Consequence: any assertion about claim semantics made against it passes vacuously.** That is worse
than having no test, because it reads as coverage. Ownership scenarios need `JdbcTokenStore` over an
in-JVM database, `JpaTokenStore`, or a purpose-built claim-aware fake.

Recorded as finding F-2.

### 1.3 What a seed does and does not fix

Stated in full in `formal/INVARIANTS.md` section 2. The short version: a seed fixes the workload's
*shape* and the reference model's behaviour. It does not fix the thread schedule, the timestamps, or
the order in which lines reach the history file. Only `HistoryRecord.idx` defines a history's order,
and `HistoryView` sorts by it on read. Never assert on file order.

---

## 2. API traps

### 2.1 The name `EventCriteria.none()` does not exist

The no-criteria factory is `EventCriteria.havingAnyTag()`, which returns the `AnyEvent` singleton.
`AppendCondition.none()` does exist and is a different thing: it carries `ConsistencyMarker.INFINITY`
plus `havingAnyTag()`.

### 2.2 `flatten()` and `matches()` can disagree for `AnyEvent` inside an OR

`AnyEvent.matches(...)` returns `true` for everything, but `AnyEvent.flatten()` returns the **empty
set**. The fluent builders collapse the case away (`TagFilteredEventCriteria.or(AnyEvent)` returns
`AnyEvent`), but `EventCriteria.either(Set.of(AnyEvent.INSTANCE, someTagCriteria))` builds an
`OrEventCriteria` that still contains `AnyEvent`: its `matches` is universally true while its
`flatten` yields only the tag criterion.

Why this matters: `InMemoryEventStorageEngine` conflict detection calls `condition.matches(...)`, the
interpreted form, so it is self-consistent. A store that builds a query from `flatten()` instead --
which is what `flatten()` exists for, and what Axon Server and the Postgres DCB engine are expected
to do -- would compute a narrower boundary than the interpreted form. That is a cross-backend
divergence hypothesis worth a scenario when the backend layer lands. Not chased in P0; the generator
in `ModelAndInMemoryEngineAgreeTest` never emits an empty criterion, so it cannot hit it by accident.

### 2.3 Marker positions and their sentinels

`GlobalIndexConsistencyMarker.position(marker)` resolves `ORIGIN` to `-1` and `INFINITY` to
`Long.MAX_VALUE`
(`eventsourcing/src/main/java/org/axonframework/eventsourcing/eventstore/GlobalIndexConsistencyMarker.java:46-55`).
`DcbStoreModel` uses exactly those two values, which is why the model can encode a marker as a plain
`long`.

`ORIGIN.lowerBound(x)` is always `ORIGIN` and `INFINITY.upperBound(x)` is always `INFINITY`; the
sentinels absorb rather than compare
(`eventsourcing/src/main/java/org/axonframework/eventsourcing/eventstore/ConsistencyMarkers.java`).
`INFINITY.position()` throws `UnsupportedOperationException`, so never call it.

### 2.4 Positions start at 0, and the head of an empty store is 0

`InMemoryEventStorageEngine.nextIndex()` returns 0 for an empty store, then `lastKey() + 1`. A commit
reports `lastAssignedPosition + 1`. A sourcing reports `lastKey + 1`, or 0 when empty. So the
"head" is the store's size, and the marker an empty store reports is 0, **not** ORIGIN. An empty
batch, however, reports `ConsistencyMarker.ORIGIN`
(`InMemoryEventStorageEngine.java:142-143`, the `.orElse(ORIGIN)` on an empty reduce).

### 2.5 A conflicting append can fail at either of two points

`InMemoryEventStorageEngine.appendEvents(...)` returns an already-failed future when it detects the
conflict early (`:107-110`), and `commit()` returns a failed future when it detects one under the
lock (`:122-126`). Both are the same rejection. Any code driving the engine must treat a failure
from *either* call as a rejection, or it will report the early-detection case as a crash.

### 2.6 `MessageStream` is not `AutoCloseable`

It has a `close()` method but does not implement `AutoCloseable`, so try-with-resources does not
compile. Use `try { ... } finally { stream.close(); }`.

### 2.7 Draining a sourcing stream

`stream.next()` returns `Optional<Entry<EventMessage>>`, and `Entry` extends `Context`, so the
consistency marker comes off the entry directly:
`entry.getResource(ConsistencyMarker.RESOURCE_KEY)`. The terminal entry carries the marker and a
`TerminalEventMessage` rather than a real event, so distinguish them by whether the marker resource
is present, not by the message type.

### 2.8 `Position.START` resolves to `Long.MIN_VALUE`

`GlobalIndexPosition.toIndex(Position.START)` is `Long.MIN_VALUE`, which the engine clamps with
`Math.max(0, ...)`. Do not use the raw value for arithmetic.

---

## 3. Commands that worked

Run from the repository root of the `feature/dst-testing-suite` worktree.

```bash
# Confirm the worktree before anything else. Both lines must match.
git rev-parse --show-toplevel && git branch --show-current

# The hunt module only exists under the profile.
./mvnw -q -Phunt -pl simulation -am test

# Iterating on the module alone, once its dependencies are installed. Much faster.
./mvnw -q -Phunt -pl simulation -o test

# One test class.
./mvnw -q -Phunt -pl simulation -o test -Dtest=ModelAndInMemoryEngineAgreeTest

# Prove the default reactor does not build the module.
./mvnw -q help:evaluate -Dexpression=project.modules -DforceStdout
```

**Judge by the exit code.** `-q` suppresses the `BUILD SUCCESS` banner, so a clean build prints
nothing. Also beware the pipe: `mvn ... | tail` reports `tail`'s exit status, not Maven's. Either
redirect to a file and check `$?`, or use `set -o pipefail`.

```bash
# Right:
./mvnw -q -Phunt -pl simulation -o test > /tmp/test.log 2>&1; echo "EXIT=$?"

# Wrong: EXIT is tail's, and is 0 even when the build failed.
./mvnw -q -Phunt -pl simulation -o test 2>&1 | tail -20; echo "EXIT=$?"
```

**Surefire's per-class `.txt` summary reports `Tests run: 0` for a class whose tests all live in
`@Nested` classes.** The XML report has the real count. Do not read the `.txt` and conclude nothing
ran:

```bash
grep -o 'tests="[0-9]*"' simulation/target/surefire-reports/*.xml
```

**ASCII check** across everything the suite adds:

```bash
LC_ALL=C grep -rn '[^ -~\t]' simulation formal
```

Must print nothing.

---

## 4. Design decisions and their reasons

### 4.1 The reference model is sequential, on purpose

`DcbStoreModel` applies one operation at a time. That is what makes it comparable against a TLA+
specification of the same protocol, and it is a real boundary: the model cannot express what a
concurrent reader observes mid-commit. Finding F-3 lives exactly in that blind spot, which is why it
is documented as read-confirmed rather than test-confirmed. Do not "fix" the model by adding
concurrency to it; check that property against the real engine instead.

### 4.2 The model depends on the JDK only

No Axon types in `org.axonframework.hunt.model`. A criterion is `Set<String> types` plus
`Set<ModelTag> tags`; a marker is a `long`. The point is that the model can be compared against any
storage engine, and against a formal specification, without either dragging the other's dependencies
in. `DcbHistoryCodec` renders the model's vocabulary into the plain maps a history record carries,
so both the recorder and the checker use one encoder.

### 4.3 An empty criteria set means match-everything

This mirrors the framework faithfully: a criteria with no tags and no types flattens to no criteria
and matches every event (`AnyEvent`). Encoding it as an empty `Set<ModelCriterion>` costs nothing and
keeps the model's OR rule uniform.

### 4.4 `op` is a string, not an enum

The plan requires that recording a new operation kind touch no existing code. A `String` field
guarantees that; `HistoryOps` holds the names in use and is explicitly non-exhaustive. A checker that
does not recognise an operation ignores it.

### 4.5 Checkers are found by `ServiceLoader`

Adding an invariant means a new class plus one line in
`simulation/src/main/resources/META-INF/services/org.axonframework.hunt.checker.Checker`. No existing
class changes, and no scenario opts a checker in: the whole set runs against every history, because
an invariant that only runs where somebody remembered it will be forgotten.

### 4.6 One checker may enforce several invariants

`Checker.machineNames()` returns a set, and each `Violation` names the specific invariant it broke.
`VisibilityChecker` enforces two. The registry in `INVARIANTS.md` is keyed by MachineName, so several
rows can name the same checker class.

### 4.7 An unknown outcome downgrades the verdict rather than deciding it

`ModelConformanceChecker` replays a history against the model. On an append whose outcome is unknown
it applies the append if the model would accept it, so the replay can continue, and marks the replay
ambiguous. From that point a mismatch becomes a note instead of a violation, because the replayed
state is no longer known to match the store's. `CheckResult.inconclusive()` reports it. Asserting
against a state that might not be the store's is how a history-checked suite invents findings.

### 4.8 The recorder serializes outside its write lock

The lock is held only for the `write` call, so recording does not measurably serialize the workload
it observes. The cost is that file order may differ from `idx` order under contention, which is why
`HistoryView` sorts on read.

### 4.9 The reproduce command names a test class that does not exist yet

`HistoryHeader.reproduceCommand()` renders
`./mvnw -Phunt -pl simulation -am test -Dtest=HuntReproduceTest -Dhunt.scenario=... -Dhunt.seed=...`.
`HuntReproduceTest` is P1's to write, and must read `hunt.scenario`, `hunt.seed`, `hunt.backend` and
`hunt.timescale` from system properties. Until it lands the string is a promise, not a command.

### 4.10 The differential test was mutation-checked

Perturbing the model's conflict scan by one position (`scanFrom + 1`, so the event exactly at the
marker stops counting as a conflict) makes 5 of the 12 seeds and both `KnownEdges` cases fail. The
perturbation was reverted. Do this whenever a differential test passes on the first run: a
differential that has never been shown to fail is indistinguishable from one that compares nothing.
The same reasoning is why every checker has planted-bad canaries.

### 4.11 The differential asserts it exercised both verdicts

Each seed asserts that at least one append was accepted and at least one was rejected. Without that,
a generator drift that stopped producing conflicts would leave the test green and vacuous.

---

## 5. Deferred to later phases

Recorded so that nobody re-derives the decision.

| Deferred | Why | Owner |
|---|---|---|
| The `Scenario` record | The plan gives it three real scenarios to be shaped by; designing it against zero use cases would produce the wrong record. The four types P1 builds on -- `HistoryRecord`, `HistoryView`, `Checker`, `DcbStoreModel` -- are designed so it needs none of them changed. | P1 |
| Fault injectors, `FaultSchedule`, timescale config, workloads | Same reason: they are the substance of P1 and P2, and the recorder already carries the `faultEpoch` field they need. | P1, P2 |
| `HuntReproduceTest` | The reproduce command the header renders points at it. | P1 |
| Concurrency probe for F-3 | Structurally out of the sequential differential's reach. Needs a reader polling against a committing writer, which is scenario S16. | P1 |
| Elle conversion | The schema is convertible and the field mapping is written down in `INVARIANTS.md`. No transactional-isolation question needs it yet, and taking the dependency before there is a question for it is cost without benefit. | when a question needs it |
| Ownership and claim scenarios | Unbuildable on `InMemoryTokenStore` (F-2), and per-node skew is unreachable through `ClockUtils` (F-4). Needs a claim-capable store, and a claim-aware fake or separate processes for skew. | P2 |
| Backend differential matrix | Needs the `TestInfrastructure` implementations. The `backend` field is already in the history header so that per-backend verdict vectors can be attributed once they exist. | P3 |
| TLA+ models | The reference-model rules are named and tabulated in `INVARIANTS.md` section 3.1 precisely so that the operators can be written against them. | P4 |
