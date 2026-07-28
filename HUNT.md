# Axon Hunt -- start here

A bug-hunting test suite for Axon Framework 5. Read less, understand more: this page is the
map, not the territory.

## 1. What this is

The existing tests check that features work. This suite tries to **break documented
guarantees** -- and, more productively, to find out what happens where the documentation says
nothing. It drives real framework components with seeded, concurrent, deliberately faulted
workloads; records every operation to a JSON Lines history; and judges each run by replaying
that history against a reference model and a set of invariant checkers. It runs multi-node
clusters, real databases in containers, network partitions and process kills, and it
model-checks two protocols with TLA+.

It has found **19 defects and gaps** (`formal/FINDINGS.adoc`): 7 high severity, 7 medium, 4
low, plus one measured non-defect. Thirteen were reproduced by a failing test, four confirmed by
reading, two measured; two were also model-checked. Nothing in the framework was patched to get
them -- that is a rule, see section 6.

If you work on the event store, event processors, tokens or segments, this concerns you: the
findings are in your code. If you are adding a feature elsewhere, section 9 tells you how to
get it covered.

## 2. Reading ladder

### Rung 1 -- five minutes, to orient

Read section 3 (terminology) and section 4 (structure map) of this page. Then skim the "At a
glance" table at the top of `formal/FINDINGS.adoc`.

**Do not read yet:** the plan (`docs/testing-plans/axon-hunt.md`, 1286 lines), the invariant
registry, the working notes. They are reference material and they will not make sense before
you have seen a run.

### Rung 2 -- half an hour, to run something and read a result

```bash
./mvnw -q -Phunt -pl simulation -am test > /tmp/hunt.log 2>&1; echo "EXIT=$?"
```

Green prints nothing; judge by the exit code. Then look at what a run actually produced:

```bash
ls simulation/target/hunt-histories/*/
head -1 simulation/target/hunt-histories/*/*.jsonl | head -3
```

Line 1 of a history is the header (scenario, seed, backend, timescale, workload shape, and the
version combination the run's meaning depends on); every following line is one operation. Then replay a recorded run and watch an oracle fail on
purpose:

```bash
./mvnw -Phunt -pl simulation -o test -Dtest=HistoryReplayTest \
    -Dhunt.history=simulation/src/test/resources/hunt-histories/pinned-conflict-check-bypass.jsonl \
    -Dsurefire.failIfNoSpecifiedTests=false
```

That file is a recording of a run made while the store's conflict check was deliberately
bypassed. The build passes, and it prints `FAIL pinned_conflict_check_bypass` with the
violations. That is the whole idea of the suite in one command: **the verdict is a function of
the history, so it is the same on every machine, for ever.**

**Do not read yet:** the checker implementations, the TLA+ models.

### Rung 3 -- deeper, before you implement anything

Read, in this order:

1. `formal/INVARIANTS.md` sections 1 and 2 -- the constitution and the measured determinism
   boundary. Section 2 is the one people skip and then get wrong.
2. `formal/INVARIANTS.md` section 3 -- the invariant registry. Twenty invariants; find the ones
   your change touches.
3. `formal/CANARIES.md` -- which oracles have been proven able to fail, and which have not.
4. `formal/HUNT-NOTES.md` -- the trap list. Nearly everything that will surprise you is already
   in there.
5. The agent skill at `.claude/skills/axon-hunt/` -- SKILL.md plus eleven references. It is
   written for AI agents, and it is also the densest description of the method for a human. Read
   `references/traps.md` even if you read nothing else.

**Still do not read** the plan end to end. Use it as a lookup: Appendix A for a claim,
section 4 for the failure-mode hypotheses, section 8 for the extensibility rules.

## 3. Terminology

The vocabulary is precise and unfamiliar, and this is the biggest barrier to reading anything
else here.

| Term | One line |
|---|---|
| **claim** | A guarantee the framework makes, written as one falsifiable sentence anchored to `file:line` with a verbatim quote. Numbered C1-C40. |
| **gap** | Something the documentation does not say, recorded with the evidence that was searched for it. Numbered M1-M18. **Most findings came from gaps, not from claims.** |
| **invariant** | A property the suite judges runs by. Has a stable name and an exact statement. |
| **MachineName** | That stable name. The same statement appears character-identically in the registry, the checker's Javadoc, the violation message and any TLA+ operator. Drift between those copies is how a suite starts lying about what it verified. |
| **checker** | A Java class that is a pure function from a history to a result. Found by service loading, so it runs against every history whether or not a scenario asked for it. |
| **oracle** | The thing that decides pass or fail. A checker is one implementation of an oracle; so is the reference model, and so is a conservation law. Every checker is an oracle; not every oracle is a checker. |
| **reference model** | A pure, dependency-free executable model of the append protocol. The **primary** oracle: property checkers only catch what somebody thought of, a model catches drift nobody enumerated. |
| **operation history** | One JSON Lines file per run. Separate records for an operation's invocation and its completion, so an operation that never completed is expressible. The evidence, the regression asset, and the only exact record of a run. |
| **verdict** | Three values: `PASS`, `FAIL`, `INCONCLUSIVE`. The third exists because a distributed system returns three outcomes and so does a test of one -- collapsing it reports confidence nobody earned. |
| **canary** | A deliberate defect planted in framework code to check that an oracle notices. Applied, measured, **reverted**; only the write-up persists. An oracle that never caught a planted bug is decoration. |
| **landing evidence** | Proof that an injected fault actually fired, taken from the thing perturbed (a container's exit code, a proxy's own reported state) and not from the harness's intention. Without it the run is `INCONCLUSIVE`, never `PASS`. |
| **fault / nemesis** | The same thing. "Fault" is this suite's word for the injector class; "nemesis" is the industry word for the agent that perturbs the system. This repo says fault. |
| **backend verdict vector** | What each store concluded, as `store:VERDICT` pairs. Broken everywhere means core framework logic; broken on one store means that adapter. This is how a finding stops being a whose-defect argument. |
| **tier** | How hard a run pushes and how many faults may overlap: `SMOKE` (one fault, per change), `HARDENING` (pairs, scheduled), `RELEASE` (storms). Compound faults first would destroy attribution. |
| **scenario record** | A scenario is **data**, not code: a workload shape, a fault schedule, a backend selector, a timescale, an oracle set, a determinism mode, a seed and a per-tier budget. New scenarios change no harness code. |
| **quiescence** | The read side has caught up with what the **store** says it holds -- decided by comparing sets of event identifiers, not counts. A run that never quiesced has not lost anything; a run whose read side **stopped** has. |
| **licensed redelivery window** | Delivering an event twice is permitted when the history records a rewind that explains it: a claim handed back a position behind something already delivered. Licensed repeats are reported and do not fail the run; unexplained ones do. |
| **expected-gap test** | A test that passes **while** a defect exists and flips red when it is fixed. How a finding is pinned without patching the engine. |

Three pairs people conflate: **claim vs invariant** -- a claim is what the product promises, an
invariant is what the suite checks (one invariant can serve several claims, and some invariants
are properties of the workload rather than of the framework). **Checker vs oracle** -- see above.
**Fault vs nemesis** -- same thing, different vocabulary.

## 4. Structure map

The "when" column is the useful one.

| Path | What is in it | Open it when |
|---|---|---|
| `simulation/` | The harness: workloads, faults, history recorder, checkers, scenarios, backends. A Maven module behind `-Phunt`, so `./mvnw verify` does not build it. | You are running or extending the suite. |
| `formal/INVARIANTS.md` | The constitution, the measured determinism boundary, the invariant registry, the history schema, and the recipes for adding an invariant, scenario, fault, workload or backend. | Before writing any assertion. Before quoting any invariant wording. |
| `formal/FINDINGS.adoc` | F-1..F-18: what is wrong, severity, evidence, per-backend vector, candidate fix, reproduce command, and how it was found. | Before reporting anything as new. When a suite arm goes red. |
| `formal/CANARIES.md` | Seven planted defects, what each one caught, the two that escaped, and honest tables of what has never been canaried. | Before trusting a green run. |
| `formal/HUNT-NOTES.md` | Append-only working notes: determinism seams, API traps, commands that worked, design decisions and their reasons. | When something does not behave as you expect. Append whatever costs you an hour. |
| `formal/CONNECTOR-COMPATIBILITY.md` | Which released Axon Server connector versions load against this reactor, which methods the harness shims and what each one models, and what to do about a combination that fails the gate. Derived from a runnable check, not from reading. | Before changing a connector or framework version. When a run fails with `AbstractMethodError` or a missing method. |
| `formal/tla/` | Two TLA+ models (append protocol, token claim) with 15 configurations in violated/fixed pairs, plus a model-to-model cross-check. `README.md` has the bounds and every result. | Before touching a model, or when you want a property proven at small bounds rather than sampled. |
| `integrationtests/` | The pre-existing integration suites, now runnable against a real store with `-Dhunt.backend=<name>` and no new test classes. | Multiplying existing coverage across stores. |
| `docs/testing-plans/axon-hunt.md` | The plan: claims C1-C40 with sources, gaps M1-M18 with search evidence, failure-mode hypotheses, the scenario corpus, the design commitments, the extensibility charter. | As a lookup, not a read-through. |
| `.github/workflows/hunt.yml` | Smoke on every pull request across two JDKs; the seed sweep nightly; the container tiers weekly. Asserts that test reruns stay disabled. | Changing what CI runs. |
| `.claude/skills/axon-hunt/` | The agent skill: how to hunt with the suite, and the method that built it. | You are an agent, or you want the method densely. |

## 5. I want to ...

| Goal | Do this |
|---|---|
| Run the fast suite | `./mvnw -q -Phunt -pl simulation -am test > /tmp/hunt.log 2>&1; echo "EXIT=$?"` |
| Count what ran | `rm -rf simulation/target` first, then `grep -ho '<testcase ' simulation/target/surefire-reports/*.xml \| wc -l` |
| Run one test class, fast | `./mvnw -q -Phunt -pl simulation -o test -Dtest=DeterminismProbeTest` |
| Run one scenario at one seed on one store | `./mvnw -Phunt -pl simulation -o test -Dtest=HuntReproduceTest -Dhunt.scenario=<id> -Dhunt.seed=<n> -Dhunt.backend=<name> -Dhunt.timescale=compressed -Dsurefire.failIfNoSpecifiedTests=false` |
| Reproduce a failure from a history file | `./mvnw -Phunt -pl simulation -o test -Dtest=HistoryReplayTest -Dhunt.history=<path.jsonl> -Dsurefire.failIfNoSpecifiedTests=false` |
| Sweep many seeds | `./mvnw -Phunt -pl simulation -o test -Dhunt.excludedGroups= -Dtest=HuntFuzzTest -Dsurefire.failIfNoSpecifiedTests=false -Dhunt.seeds=3 -Dhunt.startSeed=90000` (raise the seed count for a real sweep) |
| Run the container tier -- store comparison | `./mvnw -Phunt -pl simulation -o test -Dhunt.excludedGroups=fuzz -Dtest=BackendDifferentialTest -Dsurefire.failIfNoSpecifiedTests=false` (needs Docker; tens of minutes) |
| Run the container tier -- one chaos arm | `./mvnw -Phunt -pl simulation -o test -Dhunt.excludedGroups=fuzz -Dtest='StoreInfrastructureFailureTest$HoldingCommitsOpenPastTheStoresGapTimeout' -Dsurefire.failIfNoSpecifiedTests=false` (needs Docker; about five minutes) |
| Run the existing integration tests against PostgreSQL | `./mvnw -Pintegration-test -pl integrationtests verify -Djacoco.skip=true -Dtest=NoSuchUnitTest -Dsurefire.failIfNoSpecifiedTests=false -Dhunt.backend=postgres-jpa` |
| Model-check a protocol | `formal/tla/README.md` has every command and every result; the tool jar is one `curl` away and each run takes about a second |
| Check what is already known about an area | `formal/FINDINGS.adoc` at-a-glance table, then the invariant registry's scenario column in `formal/INVARIANTS.md` section 3 |
| Triage a red build | `.claude/skills/axon-hunt/references/hunting-loop.md` -- and assume the harness before the engine |
| Add an invariant | `formal/INVARIANTS.md` section 5, then `.claude/skills/axon-hunt/references/recipes.md` recipe 1. Check the registry first: the property you want may exist |
| Add a scenario | `formal/INVARIANTS.md` section 6.1, plus the five-field template in `.claude/skills/axon-hunt/references/claims-and-scenarios.md` |
| Add a backend | `formal/INVARIANTS.md` section 6.4. One class, one registration line, and every existing scenario runs against it. For a backend reached through a released client library, run the compatibility gate first -- see section 5b |
| Run the Axon Server arm | `./mvnw -Phunt -pl simulation -o test -Dhunt.excludedGroups=fuzz -Dtest=AxonServerBackendTest -Dsurefire.failIfNoSpecifiedTests=false` (needs Docker; about five minutes) |
| Run the Axon Server chaos arms | `./mvnw -Phunt -pl simulation -o test -Dhunt.excludedGroups=fuzz -Dtest=AxonServerInfrastructureFailureTest -Dsurefire.failIfNoSpecifiedTests=false` (needs Docker; tens of minutes) |
| Run the existing integration tests against Axon Server | `./mvnw -Pintegration-test -pl integrationtests verify -Djacoco.skip=true -Dtest=NoSuchUnitTest -Dsurefire.failIfNoSpecifiedTests=false -Dhunt.backend=axonserver` |
| Check a version combination | section 5b, or straight to `./mvnw -q -Phunt -pl simulation -o test -Dtest=ConnectorCompatibilityTest -Dsurefire.failIfNoSpecifiedTests=false` |
| Cover a subsystem with no coverage | `.claude/skills/axon-hunt/references/extending.md` |

Two traps worth knowing before your first run: **`-Dtest=A+B` runs nothing** and reports success
in three seconds (use a comma), and **deleting only `surefire-reports` inflates a test count**
with the previous container run's reports (delete the whole `target`).

## 5b. I want to change a version

A backend is not one thing. A store reached over a wire is this reactor crossed with a client
library crossed with a server version, and any of the three moving changes what a run means. The
Axon Server arm exists because that combination was mishandled once: an abstract method was added to
a storage-engine interface, the released connector had not implemented it, `javac` accepted every
call and the JVM refused the first one -- and the arm was written off as blocked for a whole phase.
So changing a version is a first-class operation here, and these are the knobs.

| Goal | Do this |
|---|---|
| Change the **Axon Server image** | `AxonServerHuntBackend.IMAGE` in `simulation/src/test/java/org/axonframework/hunt/harness/AxonServerHuntBackend.java`, and `AxonServerTestInfrastructure.IMAGE` in `integrationtests/src/test/java/org/axonframework/integrationtests/testsuite/infrastructure/AxonServerTestInfrastructure.java`. Both are `public static final String` and both are pinned on purpose. |
| Know which **image tags work** | `docker.axoniq.io/axoniq/axonserver:2026.0.0` is what both arms run and what every number in `formal/FINDINGS.adoc` was measured on; `:latest` is the same image id locally. The arm needs a **Dynamic Consistency Boundary context**, which the image creates from `AXONIQ_AXONSERVER_STANDALONE_DCB=true`. Both arms wait for the server's own `Creating DCB context: default` log line rather than for the health endpoint -- the endpoint reports its sub-components up about twenty seconds before the context has a leader. A tag that does not write that line does not have the feature and the container start will time out saying so. |
| Change the **connector version** | `simulation/pom.xml` and `integrationtests/pom.xml`, both `io.axoniq.framework:axon-server-connector`, both with an explicit `<version>` and an exclusion on `org.axonframework:*` so this reactor's own artefacts win. Then run the compatibility gate below. Which versions are `supported` / `shimmed` / `incompatible`, and which methods each shim covers, is `formal/CONNECTOR-COMPATIBILITY.md` -- read it there, never from a copy. |
| Change the **framework version** | The reactor's `${revision}` in the root `pom.xml`. This is a repo-wide change, not a test knob: it moves every module. **The thing to know is that moving it can silently break a released connector**, by making a method abstract that the connector does not implement -- which compiles and then fails at the first call. That is what the compatibility gate is for, and it is why it runs in the default hunt build rather than only in the container tier. |
| Change the **PostgreSQL image** | `PostgresJpaHuntBackend.IMAGE` in `simulation/src/test/java/org/axonframework/hunt/harness/PostgresJpaHuntBackend.java` and `PostgresTestInfrastructure.IMAGE` in `integrationtests/.../infrastructure/PostgresTestInfrastructure.java`. Both are `postgres:16-alpine` today. |
| See **which versions a past run used** | Every history header carries them. `head -1 <history>.jsonl \| python3 -c "import json,sys; print(json.load(sys.stdin)['versions'])"`. On the Axon Server arm that prints the framework version, the connector coordinates and version, the image tag, and the method the harness shimmed. A verdict quoted without them is not quotable. |
| Run the **compatibility gate** | `./mvnw -q -Phunt -pl simulation -o test -Dtest=ConnectorCompatibilityTest -Dsurefire.failIfNoSpecifiedTests=false`. About a second, no container. Add `-Dhunt.connectorJar=<path to a jar>` to ask about another artefact. |
| Handle a combination the gate **rejects** | In this order, and the first that applies is right: **pick a version the table records as usable** (cheapest, and it keeps the arm testing this reactor); **extend the shim** in `ContextCarryingAxonServerEngine` plus its row in the table, but only for a method a scenario actually drives; **record the combination as incompatible** with the real error and say what coverage is lost -- which is the only answer for a class that cannot be loaded at all, because no shim fixes a missing type. Never relax the gate to make it pass: a gate that has stopped comparing is how the arm got blocked in the first place. |

### The whole workflow in one example

Ask what connector 5.1.2 would need against this reactor:

```bash
./mvnw -q -Phunt -pl simulation -o test -Dtest=ConnectorCompatibilityTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dhunt.connectorJar=$HOME/.m2/repository/io/axoniq/framework/axon-server-connector/5.1.2/axon-server-connector-5.1.2.jar
```

Real output:

```
CONNECTOR COMPATIBILITY axon-server-connector-5.1.2.jar against framework 5.3.0-SNAPSHOT
  classes=79 unresolvable=0 unimplemented=4
  UNIMPLEMENTED AggregateBasedAxonServerEventStorageEngine -> EventStorageEngine.source(SourcingCondition, ProcessingContext)
  UNIMPLEMENTED AxonServerEventStorageEngine -> EventStorageEngine.source(SourcingCondition, ProcessingContext)
  UNIMPLEMENTED AxonServerSnapshotStore -> SnapshotStore.load(QualifiedName, Object, ProcessingContext)
  UNIMPLEMENTED AxonServerSnapshotStore -> SnapshotStore.store(QualifiedName, Object, Snapshot, ProcessingContext)
  verdict=shimmable
```

Read it as: every class loads, so nothing is structurally incompatible; four methods are missing, so
this is a `shimmed` combination, not a `supported` one; one of the four is the engine the arm drives
and the harness supplies it; the other three are recorded as undriven. `5.2.1` and `5.2.2` print the
same four against 90 classes. That single command is the whole workflow -- it names the methods, and
`formal/CONNECTOR-COMPATIBILITY.md` says what to do with each.

## 6. The constitution, and why

Four rules bind everything here. Each exists because a suite that broke it stopped being able
to find bugs.

- **The engine is never patched.** A confirmed defect gets a findings entry, a candidate fix and
  an expected-gap test -- not a fix. *Why:* a suite that patches what it measures can no longer
  tell you whether the release is broken.
- **Zero quarantine, no retries.** No disabling, no tag used to hide a failure, no rerun count.
  Every intermittent failure is classified as an engine bug, a harness bug, or a load artifact
  with evidence. *Why:* reruns bury exactly the flaky-looking real bugs the suite exists to find.
- **A fault with no landing evidence makes the run inconclusive, never a pass.** *Why:* a green
  run under a fault that never fired has verified nothing, and calling it a pass is worse than
  reporting nothing.
- **Determinism is claimed only where it was measured.** The measurement is in
  `formal/INVARIANTS.md` section 2. The headline: under real threads **a seed reproduces
  nothing** -- not the operation counts, not even which appends were accepted. Findings are
  therefore pinned by their **history file**, not by their seed. *Why:* overclaiming here turns a
  harness bug into a phantom finding faster than anything else.

Plus two the project learned the hard way: **never ship a test that is red on a clean engine**,
even when it would pin a real finding; and **never assert that the suite finds nothing else** --
that is an assertion that the suite must stop working.

## 7. Current state, honestly

**Covered.** The append protocol under contention, on five store configurations, with a
per-store verdict vector. Commit visibility and rollback across three transaction phases.
Multi-node segment ownership, claim handover under emulated clock skew, split and merge under
load, replay after reset, concurrent bootstrap. Durable token progress. Real PostgreSQL with
process kills, network partitions and commits held open past the store's gap timeout. **Real Axon
Server** -- the only persistent store here that speaks the boundary protocol, so the reference model
now judges the append protocol somewhere the events outlive the process -- with process kills,
network partitions, and a read side whose event stream is severed while it is streaming. Two
protocols model-checked exhaustively at small bounds, with the executable model cross-checked
against the specification over a whole finite domain.

**Covered with a stated adaptation.** The Axon Server arm links a **released** connector against this
reactor, and no released connector implements
`EventStorageEngine.source(SourcingCondition, ProcessingContext)` -- an abstract method added to an SPI,
which `javac` accepts and the JVM refuses. The harness supplies that one method and nothing else; three
further methods are unimplemented and deliberately unshimmed. Every history the arm writes carries the
framework version, the connector version, the image tag and the shimmed method, and
`formal/CONNECTOR-COMPATIBILITY.md` says which combinations load and what the shim does not model. This
arm was recorded as blocked for a whole phase; F-18 now says what was actually true.

**Blocked, with evidence.** The commercial boundary-native PostgreSQL engine is not reachable without
credentials; the hook for it exists.

**Came back short in the audits** -- these cap what may honestly be claimed, and they are all
written down per arm in `formal/HUNT-NOTES.md`:

- Almost everything has run at the **smoke tier with a fixed seed set**. Several arms run **one
  seed**, which is one interleaving. Nothing anywhere says how many seeds a subtler defect needs.
- **One topology per arm.** A defect that needs five nodes will not surface at four.
- Infrastructure faults have only ever hit a **single node**, never a store shared by a cluster.
- The **process-freeze** primitive is built and verified by hand, and no scenario declares one --
  so the class of failure a kill cannot produce is unexercised.
- No **transactional read model** exists in this tree, so no arm declares exactly-once delivery
  and half of one invariant has only ever run against synthetic histories.
- Two canaries **escaped** before later being caught, and both write-ups are still there next to
  their re-runs. Several things have never had a defect planted at them at all: the split and
  merge algebra, the sequencing-policy path, and any tier above smoke.
- The Axon Server arm's **token store is the framework's in-heap one**, because the connector carries
  none. Its event store is real and its claim side is not, so every ownership invariant reports itself
  inexpressible there. A verdict from that column is a verdict about the event store.
- **One Axon Server**, never a cluster of them -- which is the failure that store exists to survive.
- The Axon Server **differential column covers three of the five matrix scenarios**, at two seeds each. The run wedged
  after an `OutOfMemoryError` partway through the fourth and was killed; one of the three is inconclusive on this store
  because its hundred-event batches time out over gRPC, which is a budget shortfall and not a divergence.
- The Axon Server arm's **canary escaped**, by pushing the arm into undecidedness rather than failure. Its oracles have
  been shown to go red on two *real* defects there, which is worth more, but that is not the same as a planted one.
  `formal/CANARIES.md` has the escape and the few lines that would close it.

**Uncovered surfaces.** Queries and subscription queries (workload-level only), dead letter
queues (module absent), sagas and process managers (absent), message transformation and schema
evolution, version upgrade and rollback, XA and two-datasource deployments. On Axon Server
specifically: distributed command and query handling through it, server-side persistent streams, and
snapshots -- the arm substitutes the event store and nothing else, on purpose, so that a divergence is
attributable to the event store.

**Two open mechanisms.** F-16's mechanism on a clean engine is not fully explained; settling it
needs the reader's token instrumented rather than inferred. F-19's is bounded and not explained: Axon
Server accepts an append its consistency boundary forbids, under concurrency, and nine separate
experiments narrowed it to "inside the append operation, one or two batches wide" without naming it --
settling that one needs the server's own instrumentation rather than a client-side experiment. They are
the two most valuable open questions in the set.

## 8. Where the truth lives

These are live and they move. **This page must never duplicate them** -- a stale copy is worse
than a pointer, and if you find yourself wanting to paste a number here, paste the path instead.

| Document | Owns |
|---|---|
| `formal/INVARIANTS.md` | The constitution, the determinism boundary, the invariant registry, the history schema, the add-a-thing recipes |
| `formal/FINDINGS.adoc` | Every finding: evidence, severity, per-backend vector, candidate fix, reproduce command |
| `formal/CANARIES.md` | Which oracles have been proven able to fail, which escaped, and what has never been canaried |
| `formal/tla/README.md` | The models, their bounds, every configuration's result, and the name bridge to the Java |
| `formal/HUNT-NOTES.md` | Seams, traps, working commands, and the reason behind every design decision |
| `formal/CONNECTOR-COMPATIBILITY.md` | The framework-to-connector version table, the shimmed method set, and the escalation order for a rejected combination |
| `docs/testing-plans/axon-hunt.md` | Claims, gaps, hypotheses, the scenario corpus, the design commitments, the extensibility charter |

## 9. Your first week

**Day one.** Read rung 1 and rung 2 of section 2, and run both commands there. You now know what
a history is and why a verdict is reproducible from one.

**Day two.** Read rung 3. Pick one finding from `formal/FINDINGS.adoc` and run its reproduce
command -- every entry has one. Read the history it produces with the script in
`.claude/skills/axon-hunt/references/running.md`.

**Then pick one of these.** All three exist today, all three are real, and none needs permission:

1. **Reproduce and sharpen an open finding.** F-16 is the highest-value one: a committed event
   skipped for ever on a real store, on both configuration paths, whose mechanism on a clean
   engine is still open. Settling it means instrumenting the reader's token instead of inferring
   it. F-9 (concurrent startup losing all but one instance) and F-7 (an unresolvable sequence
   identifier stopping the read side dead) are both high severity, both reproduced, and both
   have candidate fixes nobody has evaluated.
2. **Close a short audit row.** The cheapest is a second topology on an arm that has one, or a
   third seed on an arm that runs one. It is a declaration change, it needs no new code, and it
   raises what the arm's verdict may honestly claim. `formal/HUNT-NOTES.md` names them per arm.
3. **Plant a canary at something that has never had one.** The split and merge algebra is the
   obvious candidate: the scenarios exist and nothing has ever been planted against them, so
   nobody knows whether those oracles can fail. The recipe is
   `.claude/skills/axon-hunt/references/recipes.md` recipe 6, the loop is four commands, and the
   result is a row in `formal/CANARIES.md` either way.

Whichever you pick: append what surprised you to `formal/HUNT-NOTES.md`. That file is the reason
the next person spends an hour where you spent a day.
