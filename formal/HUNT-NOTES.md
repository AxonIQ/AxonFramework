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

---

# Phase P1a -- the L1 harness

## 1. Determinism seams (continued)

### 1.4 What the probe actually measured

Full table and verbatim output in `formal/INVARIANTS.md` section 2. The short version, because it is
the thing a later agent will otherwise assume wrongly:

- **`REAL_THREADS` reproduces nothing.** Not the record order, not the operation counts, not which
  appends were accepted. Two runs of seed 77 differed by 38 records, by 3 in the accepted-append
  count, and by ~100 events in the store. A seed fixes which transfers are *attempted* and nothing
  about which of them *commit*, because that is decided by which writer wins a race.
  **Never tell anyone a failing seed reproduces a `REAL_THREADS` failure.** It does not. The history
  file is the only exact record of a run.
- **`SINGLE_THREADED` reproduces the write side exactly** -- same append verdicts, same store
  contents, every time -- and nothing else. The record sequence still differs, from record #9
  onwards, because the projection's deliveries interleave with the writer's appends and the
  streaming processor's threads are not the writer's thread.
- The component that stops `SINGLE_THREADED` being fully deterministic is
  `PooledStreamingEventProcessor`: its coordinator and worker executors are injected (used), but it
  still has its own threads (unavoidable without stopping it being event processing).

### 1.5 Injection points that exist, and one that was deliberately not used

Verified against 5.3.0-SNAPSHOT:

| Seam | Exists? | Used? |
|---|---|---|
| `PooledStreamingEventProcessorConfiguration.coordinatorExecutor(ScheduledExecutorService)` | yes | yes |
| `PooledStreamingEventProcessorConfiguration.workerExecutor(ScheduledExecutorService)` | yes | yes |
| `.initialSegmentCount`, `.tokenClaimInterval(long ms)`, `.claimExtensionThreshold(long ms)`, `.batchSize` | yes | yes |
| `.initialToken(Function<TrackingTokenSource, CompletableFuture<TrackingToken>>)` | yes | yes -- `source -> source.firstToken(null)`, to get a plain token rather than the default replay-wrapped one |
| `.clock(Clock)` on the processor | yes, but `@Deprecated(forRemoval)` | no |
| A per-run clock on the token-claim path | **no** -- `ClockUtils` is one static `AtomicReference<Clock>` for the process (F-4) | no; setting it would leak across every test in the JVM, and nothing on the L1 path decides anything from it |
| `EventStoreTransaction.overrideAppendCondition(UnaryOperator<AppendCondition>)` | yes | yes -- it is how a workload produces an ORIGIN-anchored append |

No seam was added to framework code. The only substitution is `ControllableEventStorageEngine`,
which wraps a real engine.

## 2. API traps (continued)

### 2.9 Plain-Java wiring that compiles, verbatim

```java
EventStore eventStore = new StorageEngineBackedEventStore(storageEngine, new SimpleEventBus(), tagResolver);
SimpleCommandBus commandBus = new SimpleCommandBus(new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE));
PooledStreamingEventProcessorConfiguration configuration =
        new PooledStreamingEventProcessorConfiguration(new EventProcessorConfiguration("name", null))
                .eventSource(eventStore)              // StorageEngineBackedEventStore is a StreamableEventSource
                .tokenStore(new InMemoryTokenStore())
                .coordinatorExecutor(scheduled).workerExecutor(scheduled)
                .initialToken(source -> source.firstToken(null));
PooledStreamingEventProcessor processor =
        new PooledStreamingEventProcessor("name", List.of(component), configuration);
processor.start().orTimeout(30, TimeUnit.SECONDS).join();
```

`EventProcessorConfiguration`'s second argument is a nullable `Configuration`; `null` is fine
outside the configuration module. No `Converter` is needed anywhere on this path: the in-memory
engine stores the `EventMessage` itself and the handler reads `event.payload()` back as the original
object.

### 2.10 A command handler's return type

`CommandHandler.handle(CommandMessage, ProcessingContext)` returns
`MessageStream.Single<CommandResultMessage>`. Two ways to produce one:

- `MessageStream.just(new GenericCommandResultMessage(new MessageType("x"), payload))` for a
  synchronous result;
- `MessageStream.fromFuture(future)` when the handler had to source first. **Not**
  `MessageStream.fromItems(...)`, which is not `Single`.

### 2.11 How to get each shape of append condition out of the framework

The condition the storage engine receives is derived, never the one the caller built. To exercise
each shape from a workload:

| Wanted condition | How |
|---|---|
| `AppendCondition.none()`, marker INFINITY | append **without sourcing anything** in that processing context |
| Marker at the sourced position, criteria OR-ed from the sourcings | source, then append -- the normal path |
| ORIGIN-anchored with criteria | `transaction.overrideAppendCondition(ignored -> AppendCondition.withCriteria(criteria))` |

### 2.12 `DefaultEventStoreTransaction` filters the terminal entry for you

`transaction.source(condition)` already filters out the entry carrying the `ConsistencyMarker` and
the `TerminalEventMessage` (`DefaultEventStoreTransaction.java:123`). A `reduce` over that stream
sees real events only. The engine's own `source(...)` does not filter, which is why the post-run scan
in `ScenarioRunner` still has to skip the marker entry itself.

### 2.13 The append transaction is generic, and the wrapper has to erase it

`EventStorageEngine.appendEvents(...)` returns `CompletableFuture<AppendTransaction<?>>`, and
`afterCommit(R)` takes whatever `commit()` returned. A wrapper cannot preserve that type parameter
usefully; cast once to `AppendTransaction<Object>` in the wrapper's constructor and be done. A
wrapper that reports a commit it did not make must return `null` from `commit()` and answer
`ConsistencyMarker.ORIGIN` from `afterCommit(null)`.

## 3. Commands that worked (continued)

```bash
# The whole module, which is the gate.
./mvnw -q -Phunt -pl simulation -am test > /tmp/hunt.log 2>&1; echo "EXIT=$?"

# Iterating on one test class, offline, much faster.
./mvnw -q -Phunt -pl simulation -o test -Dtest=ScenarioRunnerTest

# Replay one run. This is the command every violation prints, and it works.
./mvnw -Phunt -pl simulation -am test -Dtest=HuntReproduceTest \
    -Dhunt.scenario=dcb_append_rejected_after_marker_under_contention \
    -Dhunt.seed=2 -Dhunt.backend=in-memory -Dhunt.timescale=compressed \
    -Dsurefire.failIfNoSpecifiedTests=false

# The seed sweep, which is tagged out of every normal build.
./mvnw -Phunt -pl simulation -am test -Dhunt.excludedGroups= -Dtest=HuntFuzzTest \
    -Dsurefire.failIfNoSpecifiedTests=false -Dhunt.seeds=500 -Dhunt.startSeed=10000

# The determinism measurement.
./mvnw -q -Phunt -pl simulation -o test -Dtest=DeterminismProbeTest
```

**Counting tests with `@Nested` classes: the surefire XML `tests=` attribute lies too.** P0 recorded
that the per-class `.txt` reports `Tests run: 0`; the XML's `tests=` attribute is also wrong (it
reported `1` for a class with two nested cases). Count the `<testcase>` elements:

```bash
grep -ho '<testcase ' simulation/target/surefire-reports/*.xml | wc -l
```

Histories are written under `simulation/target/hunt-histories/<name>/`, not to a temporary
directory, because the reproduce command a failure prints is only useful next to the file it came
from.

**Checking that no invariant statement has drifted.** The registry and the checker constants must be
character-identical, and the only way that stays true is to check it mechanically. This script reads
every `*_STATEMENT` constant in the checker package, finds its MachineName's row in
`formal/INVARIANTS.md`, and reports any difference:

```bash
python3 - <<'"'"'EOF'"'"'
import re, glob
stmts = {}
for f in glob.glob('"'"'simulation/src/main/java/org/axonframework/hunt/checker/*.java'"'"'):
    src = open(f).read()
    for m in re.finditer(r'"'"'public static final String (\w+_STATEMENT)\s*=\s*(.*?);'"'"', src, re.S):
        stmts[m.group(1)] = '"'"''"'"'.join(re.findall(r'"'"'"((?:[^"\\]|\\.)*)"'"'"', m.group(2)))
names = {}
for f in glob.glob('"'"'simulation/src/main/java/org/axonframework/hunt/checker/*.java'"'"'):
    for m in re.finditer(r'"'"'public static final String (\w+)\s*=\s*"([^"]+)";'"'"', open(f).read()):
        if not m.group(1).endswith('"'"'_STATEMENT'"'"'):
            names[m.group(1)] = m.group(2)
inv = open('"'"'formal/INVARIANTS.md'"'"').read()
for key, stmt in sorted(stmts.items()):
    mn = names.get(key[:-10])
    row = re.search(r'"'"'\|\s*`'"'"' + re.escape(mn or '"'"'?'"'"') + r'"'"'`\s*\|\s*(.*?)\s*\|'"'"', inv) if mn else None
    if mn and (not row or row.group(1) != stmt):
        print('"'"'DRIFT'"'"', mn)
EOF
```

It printed nothing at the end of P1a, across nine statements.

## 4. Design decisions and their reasons (continued)

### 4.12 A concurrent history cannot be replayed in invocation order

The first concurrent run produced model-conformance violations that were not real. Two writers race;
the one that asked first is often not the one that landed first, and replaying in invocation order
therefore feeds the model a store state the store never had. `ModelConformanceChecker` now replays in
the order of the **authoritative post-run scan**, which is the store's own answer to that question,
and falls back to completion order only for a history with no scan (a hand-written one, or a run that
ended early). Rejections are checked against the **final** store state, which is sound because a
conflict is monotone in a growing store: an append that still has no conflict at the end never had
one earlier.

### 4.13 An event is visible before the record saying it was committed

Same first run, same kind of false violation. The store publishes inside `commit()`; the harness
writes its record after that call returns; a fast consumer legitimately lands in between. Two
changes: the wrapper records the commit's **invocation** before calling the delegate, and
`VisibilityChecker` compares a delivery against that invocation rather than the completion -- and
against the **earliest** one, because a store that duplicates an append commits the same event twice
and it became visible at the first.

### 4.14 An injected failure is not a protocol rejection

If the harness makes a commit fail, the reference model knows nothing about it and would say the
append should have succeeded. Recording every failure the same way would turn every faulted run into
a protocol violation. The wrapper therefore records an injected refusal under
`InjectedStoreFailureException`, and every checker treats only
`AppendEventsTransactionRejectedException` as the protocol saying no.

### 4.15 A fault that rewrites the store makes the model oracle undecidable, on purpose

`Fault.perturbsStoreContents()` declares it. Vanish, duplicate and partial-batch all set it, and both
`ModelConformanceChecker` and `ConservationChecker` downgrade to notes when one of them fired. The
alternative -- reporting the money the harness itself destroyed as a framework defect -- is a false
finding with a very convincing-looking violation message attached.

`ConflictCheckBypassFault` deliberately does **not** set it: the batch stored is exactly the batch
offered, and what changed is whether the append should have been allowed. That is what makes it the
canary. A run under it goes red, which is the only evidence that the oracle is an oracle.

### 4.16 Warmup is wall-clock, and that is a trap worth knowing about

A fault window opens `warmup` after the run starts. If the workload finishes issuing its commands
inside the warmup, the window opens over an idle system and the fault fires zero times -- the run is
correctly reported inconclusive, and it looks like a broken fault. It is a badly sized scenario.
1000 ledger commands take about 250 ms, so a 100 ms warmup already overlaps most of the run. Size the
command count against the warmup, not the other way round; the landing-evidence rule will catch it
either way, which is exactly what it is for.

### 4.17 Conservation is checked against the projection, not against the events

The ledger's transfers each append a withdrawal *and* a deposit in one batch, so a torn batch
destroys money. The oracle compares the sum of the projection's balances against the opening total,
and the projection is built by a real streaming processor over a real event store. That makes the
check genuinely end to end: it fails if the write side lost a conflict check, if the store tore a
batch, or if the read side lost or doubled a delivery -- without the suite having to guess which.

### 4.18 The scenario catalogue is a convenience, not the registry

`ScenarioRunner` never looks a scenario up. `HuntScenarios` exists only so that
`-Dhunt.scenario=<id>` can resolve a name, which is what the reproduce command needs. A scenario that
is never reproduced by name does not have to be there at all, and
`ScenarioRunnerTest.ANewScenario` proves it by declaring one at the call site.

### 4.19 What a scenario's `oracles` set does, and does not, do

It does not select which checkers run. Every registered checker runs against every history, because
an invariant that only runs where somebody remembered it will be forgotten. The set is a guard: the
runner notes, and the run is downgraded, if a MachineName the scenario requires is not enforced by
any registered checker. That catches an oracle deleted or unregistered by accident, which otherwise
turns into a suite that passes because it stopped looking.

## 5. Deferred to later phases (continued)

| Deferred | Why | Owner |
|---|---|---|
| Scenarios S3, S10, S16 | The harness they need is done; they are declarations plus their oracles. S16 needs the reader-during-commit probe, which is a workload rather than a scenario knob. | P1b |
| Lincheck (D9) | Orthogonal to the scenario harness and lands with its own dependency. | P1b |
| The canary run and `CANARIES.md` (D8) | `ConflictCheckBypassFault` is the first canary and is already pinned by two tests; the rest of the mutation set and the document are a phase of their own. | P1b |
| The CI workflow | The tags and properties it needs are in place: `-Dhunt.excludedGroups=`, `-Dhunt.seeds`, `-Dhunt.startSeed`, `-Dhunt.scenario`, `-Dhunt.seed`, `-Dhunt.tier`, and `surefire.rerunFailingTestsCount` is pinned to 0 in the module's POM. | P1b |
| A realistic-timescale arm that is actually run | `HuntTimescale.realistic()` exists and is selectable; no scenario declares it, because at L1 nothing depends on a real timeout. It becomes meaningful when a claim-capable token store lands. | P2 |
| Per-tier fault schedules | A scenario carries one schedule. The faulted arm of a scenario is a second record with its own identifier, which also keeps a reproduce command unambiguous. Revisit only if the duplication becomes real. | when it hurts |

---

# Phase P1b -- completing L1

## 1. Determinism seams (continued)

### 1.6 The standing policy change: pin histories, not seeds

P1a measured that `REAL_THREADS` reproduces nothing. That invalidates "pin the failing seed" as a
regression strategy, so from P1b the suite pins two different things and never confuses them. The
full statement is `formal/INVARIANTS.md` section 2.5. The parts a future agent will otherwise get
wrong:

- `RegressionSeedsTest` pins seeds **only** for `SINGLE_THREADED` arms, and one of its cases asserts
  that the arm it pins really is single-threaded. Adding a pinned seed for a contended arm claims a
  reproducibility that has been measured not to exist.
- The contended arms' regression asset is a **history file** under
  `simulation/src/test/resources/hunt-histories/`, replayed by `ScenarioRunner.replay(Path)`.
- **A pinned history guards the oracles, not the engine.** Its verdict does not move when the engine
  changes, because it is a record of a run that already happened. The engine is guarded only by the
  live arms. Say this out loud whenever the pinning strategy is described, or it reads as far
  stronger than it is.
- `HistoryHeader.reproduceCommand()` now annotates the command it renders for a `REAL_THREADS` run
  as a re-sample rather than a replay. Do not remove the annotation to tidy the output.

### 1.7 A single writer still produces both append verdicts

`dcb_append_rejected_after_marker_single_writer` exists because a pinned seed needs a deterministic
arm. It was not obvious in advance that one writer would still exercise the rejection path, and it
does: the ledger's `seize` command anchors at ORIGIN, so it conflicts with anything already stored
under that account whether or not anybody else is writing.

## 2. API traps (continued)

### 2.14 `SimpleEventHandlingComponent` throws on a policy that resolves nothing

`SimpleEventHandlingComponent.sequenceIdentifierFor` calls `.get()` on the policy's `Optional`
(`:176` and `:184`). `NoOpSequencingPolicy` and a bare `SequentialPerAggregatePolicy` on a DCB store
both return empty, so every event throws `NoSuchElementException` and the processor delivers
nothing. Recorded as finding F-7. Two consequences for the harness:

- Any scenario choosing one of those policies is `INCONCLUSIVE` by construction, because the read
  side never catches up. Give it a short settle budget; the coordinator retries in a tight loop
  (2204 attempts in eight seconds was measured) and every second of settle is wasted.
- If you need to observe what identifier the framework resolved, wrap the component in a
  `DelegatingEventHandlingComponent` and override `sequenceIdentifierFor`. The processor asks the
  **outermost** component, so a wrapper sees the real call. Recomputing the policy from the workload
  answers a different question.

### 2.15 One sequence identifier means one segment, not just one thread

Segment assignment hashes the sequence identifier
(`SegmentMatcher.java:65`, reached from `ProcessorEventHandlingComponents.java:193-198`). Under the
wired default on a DCB store every event resolves to the same identifier, so every event lands in
the same segment and the other segments get nothing. Configuring more segments changes nothing at
all. This sharpens F-6 and is only visible by measuring, not by reading the policy.

### 2.16 `Optional.orElse` is eager, and the framework relies on it not being

`SimpleEventHandlingComponent.java:184` reads
`...findFirst().map(component -> component.sequenceIdentifierFor(...)).orElse(policy...get())`. The
`orElse` argument is evaluated first, so the fallback `get()` runs even when the sub-component would
have answered. Part of F-7.

### 2.17 A rollback can arrive after a successful commit

`DefaultEventStoreTransaction` registers one `onError` handler per transaction and calls
`rollback()` from it whatever phase failed, so a failure in `AFTER_COMMIT` rolls back an already
published batch. `ControllableEventStorageEngine` records such a rollback as having discarded
nothing (`eventIds` empty, `offeredEventIds` full, `afterCommit=true`). Do not "fix" that recording:
without it, `RolledBackEventsNeverObservable` reports every committed event of the transaction as
observable-after-rollback, which is a false finding. Recorded as F-8.

### 2.18 `InMemoryEventStorageEngine.source(...)` snapshots its end position

`source(...)` sets the stream's end to `eventStorage.lastKey()` at the moment it is opened, and
`next()` reads the map with no lock. A stream opened while a batch is being committed therefore ends
mid-batch, which is the mechanism behind F-3 and what makes it observable at all.

### 2.19 `ConflictCheckBypassFault` cannot land with a single writer

The in-memory engine detects a conflict twice: early, in `appendEvents`, and again under the lock in
`commit()`. The bypass fault acts at commit time, so it can only bypass a conflict that appeared
*between* the two checks -- which needs a second writer. Under `SINGLE_THREADED` the fault fires
(the hook is consulted) but nothing conflicting ever reaches the commit check, and the run is clean.
Costs an hour if you try to build a small deterministic canary history with it.

## 3. Commands that worked (continued)

```bash
# Re-judge a recorded history offline. No simulation; same verdict every time.
./mvnw -Phunt -pl simulation -am test -Dtest=HistoryReplayTest \
    -Dhunt.history=simulation/target/hunt-histories/<dir>/<scenario>-<seed>.jsonl \
    -Dsurefire.failIfNoSpecifiedTests=false

# Run the canary campaign. The hunt module resolves axon-eventsourcing from the local repository
# when built alone, so a framework mutation has to be installed before it is visible.
./mvnw -q -o -pl eventsourcing -am install -DskipTests   # after mutating
./mvnw -q -Phunt -pl simulation -o test                   # measure
git checkout -- eventsourcing                             # revert, always
./mvnw -q -o -pl eventsourcing -am install -DskipTests   # restore

# The revert gate. Must print nothing.
git diff --stat main -- messaging eventsourcing modelling common conversion extensions test integrationtests
```

`-Dtest=A+B` is not a thing; surefire wants `-Dtest=A,B`.

## 4. Design decisions and their reasons (continued)

### 4.20 S3 does not drive the ledger

The three transaction-phase arms use `BatchWorkload`, not `LedgerWorkload`, and the reason is not
convenience. A failure injected after the commit leaves events durably stored while the command that
produced them reports failure; `ConservationChecker` folds only successful commands, so it would
report the difference as lost money and produce a false violation on every run of that arm. A
workload with no conservation law simply gets no verdict from that checker.

### 4.21 Zero warmup, because this workload is too fast for one

`BatchWorkload` issues its whole budget in tens of milliseconds. With a twenty-millisecond warmup the
arms were bimodal: sometimes 65 fires, sometimes zero, depending on how warm the JVM was. Zero fires
is correctly reported as undecided, but an arm that is undecided half the time verifies nothing half
the time. The arms now open their window immediately and hold it open far longer than the workload
can take. This is the P0 warmup trap (note 4.16) meeting a workload two orders of magnitude faster
than the ledger.

### 4.22 `OrderChecker` judges only deliveries that carry an identifier

The sequence identifier is the framework's, so the checker uses the one the run recorded and ignores
deliveries that carry none. That makes it silent for every workload that does not track sequencing,
which is all of them except `SequencedWorkload`. The alternative -- guessing an identifier -- would
make the verdict a property of the checker rather than of the run. The cost is honest and is written
into the registry, and it is thinner than it first looks: of the three sequencing arms only the
wired-default one delivers anything at all, so exactly one run in the whole suite produces keyed
deliveries for this oracle to judge. Widening it means either a workload that records a sequence
identifier alongside the ledger, or an arm on a policy that resolves per key -- both worth doing,
neither done here.

### 4.23 S16 asserts the gap, and asserts the neighbouring guarantee too

`PartialBatchVisibilityTest` asserts that a torn observation **is** found, because the suite records
current behaviour and never patches the engine. Alongside it, the arm asserts that nothing was
delivered before its commit and that the authoritative scan holds every batch whole, so the finding
cannot be read as "the store loses part of a batch". It will flip red when batch visibility becomes
atomic; that flip is the signal to re-evaluate F-3, not a test to repair.

### 4.24 Lincheck did not land, and here is exactly why

Attempted, and abandoned deliberately rather than fought. What was tried and what happened:

| Attempt | Result |
|---|---|
| `org.jetbrains.lincheck:lincheck:3.1` | Does not resolve: its transitive `org.jetbrains.kotlin:kotlin-stdlib-common:2.1.21` has no jar on Maven Central. Fixable by excluding `kotlin-stdlib-common`. |
| `org.jetbrains.kotlinx:lincheck:2.39` | Resolves, but the artifact is a Kotlin multiplatform **metadata** jar containing three files and no classes. The JVM artifact is `lincheck-jvm`. |
| `org.jetbrains.kotlinx:lincheck-jvm:2.39`, JDK 23 | Compiles. Fails at run time. |
| `org.jetbrains.lincheck:lincheck:3.1` with the exclusion, JDK 23 | Compiles. Fails at run time, identically. |
| The same, JDK 22 | Fails identically. |

The run-time failure, verbatim:

```
java.lang.InternalError: class redefinition failed: invalid class
	at java.instrument/sun.instrument.InstrumentationImpl.retransformClasses0(Native Method)
	at java.instrument/sun.instrument.InstrumentationImpl.retransformClasses(InstrumentationImpl.java:225)
	at org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.install(LincheckJavaAgent.kt:157)
	at org.jetbrains.kotlinx.lincheck.LinChecker.checkImpl$lincheck(LinChecker.kt:384)
```

Lincheck's agent could not retransform classes on either JDK 22 or JDK 23. No JDK 21 was available
on the machine, so whether Lincheck works there was **not** established -- do not record it as
"works on 21", because nobody ran it there. The dependency and the probe test were removed rather
than left in a state that cannot be exercised: a test that only runs on a JDK the author could not
try is a quarantine with extra steps.

If a later phase wants Lincheck, the two things to get right first are the `lincheck-jvm` artifact
identifier (or the `kotlin-stdlib-common` exclusion on 3.x) and a JDK the agent supports. The target
that was being aimed at is worth keeping: `InMemoryTokenStore.initializeTokenSegments` is a
non-atomic `fetchSegments`-then-`put`, which is a real linearizability question and is exactly what
scenario S15 is about.

### 4.25 The canary campaign is the exit gate, and it is cheap to re-run

`formal/CANARIES.md` holds the recipe, the four mutations, and what each one caught. Two things to
carry forward: a mutation that makes the store keep less than it was offered stretches the suite from
one minute to eleven (the read side can never catch up, so every scenario burns its settle budget),
and the pinned single-writer seeds cannot catch a contention-only mutation -- the contended arms and
the differential are what catch those.

## 5. Deferred to later phases (continued)

| Deferred | Why | Owner |
|---|---|---|
| S10 arm (b): the wired default on the aggregate-based JPA backend | This is the other half of the differential that exposes F-6: the same configuration behaves differently where `AGGREGATE_IDENTIFIER_KEY` *is* populated. It needs an aggregate-based JPA `HuntBackend`, which does not exist. Arms (a), (c) and (d) all ship. | P3 |
| Lincheck | See 4.24. Needs a JDK its agent supports. | when a JDK is available |
| A canary against the read side | Every mutation so far is in the storage engine. Mutating `messaging` needs the arms that would catch it -- claim handover, split, merge, replay -- and those need a claim-capable token store. | the phase that adds one |
| `hunt-fuzz` and `hunt-chaos` CI jobs | Stubbed as comments in `.github/workflows/hunt.yml` rather than as disabled jobs, because a scheduled job that exists and does nothing reads as coverage. | P3 |
| A per-backend verdict vector | One backend ships; a vector needs two. | P3 |
