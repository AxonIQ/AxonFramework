# Recipes

Every recipe below edits **zero existing classes**. That is the extensibility charter, and it
is checked at every phase boundary by a test that declares a scenario at the call site and
runs it through the same runner as the shipped ones.

**The golden rule, in all of them.** A property is done only when it exists in three places,
worded identically:

1. a row in `formal/INVARIANTS.md`,
2. an assertion in code (a checker's statement constant and its violation message), and
3. a scenario or a pinned seed that exercises it.

Two of the three is a property nobody checks, or a check nobody can find. The registry is the
authority on the wording; the code is diffed against it mechanically (script at the end of
this file).

---

## 1. Add an invariant and its checker

The normative version is `formal/INVARIANTS.md` section 5. Read it; this is the operational
summary.

0. **First: does it already exist?** Read the registry in `formal/INVARIANTS.md` section 3 before
   writing anything. Twenty-odd MachineNames ship, several checkers enforce more than one, and the
   properties people most often propose adding -- a token never going backwards, no committed event
   going undelivered, a duplicate only inside a recovery window -- are already there. If a row
   exists, the useful work is different work: read what the row's **not-applicable** cases are, what
   direction it does **not** check, and which scenarios exercise it. Widening an existing invariant's
   coverage (a new arm, another backend, a topology, more seeds) is usually worth more than a new
   row, and it does not risk two invariants that happen to share a meaning.
1. **Find the claim.** A test with no C-number or M-number from
   `docs/testing-plans/axon-hunt.md` does not get written. If the property is real and has no
   number, add the number to the plan first -- the claims list is append-only.
2. **Write the statement.** One sentence, present tense, saying what must always be true.
   This exact string goes in the registry, the Javadoc and the violation.
3. **Add the registry row**: MachineName, statement, claims, checker class, scenarios, TLA+
   operator (`--` when it is not modelled, which is normal and is not an omission).
4. **Write the checker** in `org.axonframework.hunt.checker`, implementing `Checker`:
   - `name()` returns the class's simple name;
   - `machineNames()` returns the invariant names it enforces -- one checker may enforce
     several, and each `Violation` names the specific one it broke;
   - `check(HistoryView)` returns a `CheckResult`, building each violation with
     `Violation.of(machineName, statement, detail, records, history.header())` so the seed and
     the reproduce command come along automatically.
   Declare the statement as a `public static final String ..._STATEMENT` on the checker so the
   registry and the code can be diffed.
5. **Record what the checker needs.** If the invariant needs an operation the recorder does
   not emit, add a constant to `HistoryOps` and record it. `op` is a plain string precisely so
   that recording a new operation kind changes no existing code path, and a checker that does
   not recognise an operation ignores it.
6. **Register it**: append the fully-qualified class name to
   `simulation/src/main/resources/META-INF/services/org.axonframework.hunt.checker.Checker`.
   It now runs against every history in every scenario, without any scenario opting it in,
   because an invariant that only runs where somebody remembered it will be forgotten.
7. **Prove it can fail.** One synthetic history per rule the checker enforces, with that rule
   planted broken, plus at least one sound history it passes. Build them through
   `SyntheticHistory`, never by fabricating records. **A checker with no demonstrated failure
   mode is decoration**; this step is what makes it an oracle.
8. **Handle unknowns explicitly**, and write the decision into the Javadoc. The default is a
   note, not a violation.

Pick the checker shape from the property, not from taste: the checker-picker table is in
`method-essentials.md`.

**If the invariant turns out not to hold:** do not fix the engine. Record the finding in
`formal/FINDINGS.adoc` with its evidence and a candidate fix, keep the registry row with an
honest "holds?" note, and pin the observed behaviour with an expected-gap test that flips red
when the gap closes.

**Anything in the recording path must degrade rather than throw.** A recorder that throws
takes the operation with it, and the resulting history describes a system that was never
exercised -- measured once as 628 of 1000 commands failing because the recorder called an
accessor the store's marker type does not support.

---

## 2. Add a fault

One class implementing `Fault`. It declares its `kind()`, its `parameters()` and whether it
`perturbsStoreContents()`; it reaches the system only through the `FaultSite` it is handed,
and it increments the `FaultEvidence` it is given every time it actually perturbs something.
Nothing else changes: the schedule takes whatever it is given, and the runner writes the
evidence into the history without knowing what the fault was.

Prove it lands by adding a case to `FaultsLandTest`, which drives a short run with the one
fault installed and asserts the fire count is positive. **The evidence is not optional**: a
declared fault whose fire count is zero makes the run inconclusive.

Five things this project learned the hard way about faults:

- **`perturbsStoreContents()` is a load-bearing declaration.** A fault that makes the store
  hold something other than what was offered makes the model oracle and the conservation
  oracle undecidable, and both downgrade to notes when one fired. The alternative -- reporting
  the money the harness itself destroyed as a framework defect -- is a false finding with a
  very convincing message attached. The conflict-check-bypass fault deliberately does **not**
  set it, and that is what makes it the canary: the batch stored is exactly the batch offered,
  and what changed is whether the append should have been allowed.
- **A fault lands when the instruction reaches the framework, not when the framework agrees.**
  A refused merge is evidence. A fault that counted only acceptances made the one arm built
  around a refusal permanently inconclusive.
- **A fault that undoes itself belongs in the fault, not in the scenario's budget.** The heal
  phase exists for this: a split storm merges back whatever it left split, or the segment
  nobody is allowed to claim never catches up and the arm reports the fault's own damage.
- **Aim node-level faults at the busiest node.** Aimed by index, they land on a node holding
  nothing and record themselves as having fired.
- **Measure where your delay actually sits** before building an arm around it. On a
  transaction-managed store the append transaction's `commit()` and the database transaction's
  commit are registered in the same lifecycle phase with no ordering between them, so a delay
  installed at `commit()` may delay nothing that matters. One pass over a history comparing a
  delivery's timestamp against its commit record answers it in a minute.

A fault that reaches outside the virtual machine goes through the **backend's**
`StoreInfrastructure`, which has three primitives -- cut the network, kill the process, freeze
the process. They are not three settings of one: a kill takes the state; a cut takes the
network and leaves the state, its clock and its open transactions, which is the only way an
acknowledgement becomes genuinely ambiguous; a freeze takes neither and stops the clock from
the process's point of view, holding every lock while every deadline in the system expires. An
in-heap store returns `StoreInfrastructure.none()`, so the same scenario run there reports a
fault that never fired rather than a pass.

---

## 3. Add a workload shape

One class implementing `Workload`: the command handlers it registers, the projection it
returns, the tags its events carry, the shape it derives from the seed, and the final
read-model state it records so a checker can compare against it. Conservation hooks are
optional -- a workload that records nothing a checker recognises simply gets no verdict from
that checker rather than noise.

- **Derive the shape from the seed, and log it into the history header.** Writer count, key
  pool, access distribution, overlap degree, operation mix and batch sizes are pure functions
  of the seed. State-space coverage comes from distribution shape, not from operation volume;
  hot-key skew is where conflict-path defects live.
- **A conservation law is the cheapest strong oracle there is.** The ledger's transfers append
  a withdrawal and a deposit in one batch, and the sum of the projection's balances is compared
  against the opening total. It fails if the write side lost a conflict check, if the store
  tore a batch, or if the read side lost or doubled a delivery, without the suite having to
  guess which -- and it has twice caught a mutation for which nobody had written an assertion.
- **Choose the conservation shape against the arm.** A workload whose commands can report
  failure while their events are durably stored must not be held to a conservation law, or
  every run of that arm reports lost money. An arm that deliberately hands a claim over needs
  an *idempotent* projection, because the framework's guarantee in a split-resource deployment
  is at-least-once and its own documentation says handlers must be idempotent. An idempotent
  projection is a **weaker** oracle -- the sum stops noticing a repeated delivery -- and the
  registry says so.
- **Resolve payloads by type, never by `instanceof` on the raw payload.** An in-heap engine
  stores the message itself; a converting store hands back a byte array with a converter
  attached, and only the annotated handler path converts for you. Ask for the class you want.
  The place that is easy to miss is the **sequencing policy**, which runs on the read side: a
  policy that pattern-matches on the raw payload silently stops keying by anything meaningful
  against a converting store.
- **A cluster workload must sequence by a real key.** Segment assignment hashes the sequence
  identifier, and the framework's wired default resolves to one identifier for everything on a
  boundary-protocol store, so every event lands in one segment however many are configured. A
  policy that ever answers nothing throws once per event and delivers nothing at all.

---

## 4. Add a backend

One class implementing `HuntBackend`, plus one line in
`simulation/src/main/resources/META-INF/services/org.axonframework.hunt.harness.HuntBackend`
(or the test-scope copy, if it needs containers or drivers the default build must not compile
against -- the service loader merges the files rather than shadowing them). Every existing
scenario then runs against it by name, and the per-backend verdict vector becomes available
for free.

**A backend is a run-time selection, never a class per backend.** The first attempt wrote one
leaf test class per abstract suite per store -- nineteen files for the first store and nineteen
more for every store after -- and it was deleted. The leaves became backend-neutral runners
holding nothing but a name, and a per-backend verdict comes from running the same classes once
per backend with `-Dhunt.backend=<name>`. Two properties that preserves and the other shape
did not: adding a store costs one file in total, and the default build starts no container
because the default property value says so, which is stronger than a tag because there is
nothing to forget to tag.

Two methods are mandatory (`name()`, `createEngine()`); the rest have defaults that are right
for an in-heap store. The three that decide whether your oracles mean anything:

| Method | Getting it wrong |
|---|---|
| `arbitratesTokenClaims()` | Defaults to `false`, which makes the ownership invariant report itself unverifiable rather than passing vacuously. Answering `true` for a store with no owner is how a suite reports coverage it does not have. |
| `speaksDynamicConsistencyBoundaries()` | Defaults to `true`. Answer `false` for an aggregate-based store, or the reference model replays its history against a model of a protocol it does not implement and reports the difference as a defect on every append. |
| `commitsOutsideAppendTransaction()` | Says whether the append transaction's `commit()` returning is the moment of durability. On a transaction-managed store it is not, and the durability invariant is published as a measurement rather than decided. |

Also worth knowing before wiring one:

- `createTokenStores(runId, claimTimeout)` takes the claim timeout as a **parameter** because
  it is a store setting and does not travel through the processor configuration with the run's
  other compressed timings. The ownership oracle derives a claim's expiry from exactly this
  number, so a store configured with the shipped default while the header says a compressed
  one reports every legitimate handover as an overlap.
- `transactionManager(engine)` cannot be `null` for a persistent store. Its engine asks the
  processing context for the executor to append through, and having the run's transaction there
  is what makes an append become durable in the framework's commit phase rather than the moment
  the engine is handed the events. Without it, every visibility oracle reports the harness's
  wiring as a framework defect.
- `readableEventIds(engine)` exists so a durability question is not routed through the run's
  own connection pool. After the store has been killed, the run's connections point at a
  process that no longer exists, and the one question that must not go through the harness's
  plumbing is whether the store kept what it said it kept. Answering it with a fresh connection
  also **changed the answer**: the engine's own source reports fewer events than the table
  holds.
- The constructor must not start anything. Every build instantiates every registered provider.

**Proving a new backend inherits the corpus costs one test:** take a shipped scenario, call
`Scenario.onBackend(name)`, run it, assert the verdict. That is what turns the charter from a
claim into a property.

**Budget and timings are properties of the arm, not of the claim.** `Scenario.withBudget`,
`withTimescale` and `onBackend` exist so a differential can give every arm timings and budgets
a real store can meet without editing the experiment. What it must not do is quote a
heap-sized budget at a container and then report the shortfall as a finding.

---

## 5. Add a scenario

A `Scenario` is a **data record**, not a class. Build one with `Scenario.builder(id, name)`
and declare: `claims`, `workload` (a `Supplier`, so every run gets its own instance),
`faults` (a `FaultSchedule`: warmup, windows, heal, settle -- or `FaultSchedule.none(settle)`),
`backend`, `timescale`, `determinism`, `oracles`, `seed`, `budget` per tier, and for a cluster
`nodes`, `segments` and `segmentsPerNode`. Run it with `ScenarioRunner.run(scenario, tier,
seed, directory)` or `runTier(...)`.

Write the five-field specification before writing the record -- ORACLE, WORKLOAD, EVIDENCE,
AMBIGUITY, BUDGET. The template and worked examples are in `claims-and-scenarios.md`. A
scenario missing any of the five is not implementable.

Two things about the declaration that are easy to misread:

- **The catalogue is a convenience, not the registry.** The runner never looks a scenario up.
  Add it to `HuntScenarios.all()` only so that `-Dhunt.scenario=<id>` can resolve the name,
  which is what the reproduce command needs.
- **The `oracles` set does not select which checkers run.** Every registered checker runs
  against every history. The set is a guard: the run is downgraded if a MachineName the
  scenario requires is not enforced by any registered checker, which catches an oracle deleted
  or unregistered by accident -- otherwise a suite passes because it stopped looking.

For a cluster, three more things: nodes are released from a **barrier**, not started in a loop;
segments must be capped per node or the first coordinator takes everything; and the
configuration record is mutable with setters that return `this`, so build **one configuration
per node** or every node silently gets the last node's executors.

---

## 6. Add a canary

The mutation campaign is what makes the oracles trustworthy rather than decorative. Two rules,
neither negotiable:

1. **A canary diff is never committed.** Applied, measured, reverted. The gate is
   `git diff --stat main -- messaging eventsourcing modelling common conversion extensions test integrationtests`,
   which must print nothing.
2. **A mutation that escapes is a real gap in the suite**, not a curiosity. It is written up
   plainly and either closed in the same piece of work or filed as follow-up with the oracle it
   needs.

The loop is in `running.md`. Three things about designing one:

- **Run the whole suite, not the arm the mutation was designed for.** The point is to learn
  which arms catch it, including the ones nobody expected -- and which stayed green, and why
  that is correct.
- **Aim at a mechanism the store can actually express.** The most informative canary in the
  campaign was one an in-heap store cannot express at all: no durable index taken from a
  sequence before a transaction commits means no gaps, so there is nothing to mutate. A
  backend canary has to be reachable and detectable only on a real store, or it is measuring
  the previous layer.
- **Record what stayed green and why.** A pinned single-writer seed cannot catch a
  contention-only defect: an event can only sit exactly at another append's marker if a second
  writer landed it there. That is not a defect in the pin, it is what the pin is for, and
  writing it down is what stops somebody "fixing" the pin.

Write the result into `formal/CANARIES.md`: the diff snippet, the oracle that should have
caught it, whether it did, the tier and seeds, the violations raised, and the list of test
classes that went red. **An escaped canary stays in the record next to its re-run** -- never
overwritten -- because the record of the hole is the evidence the hole existed.

---

## 7. Add a TLA+ model

Read `formal/tla/README.md` first; it carries the bounds, the results and the bridge table.

1. **Check the registry before modelling anything.** Where a plan promises an invariant name,
   the registry is the authority: of five names one plan section promised, one was a registry
   MachineName, one named two scenarios rather than an invariant, one named a checker that had
   been replaced, and one had no counterpart anywhere. Inventing registry entries to match a
   plan produces TLA+ operators with no Java twin in a registry column implying a bridge that
   is not there.
2. **Quote the statement verbatim.** An invariant worded one way in Java and another way in a
   specification is two invariants that happen to share a name. Where the operator checks a
   reference-model rule rather than a registry invariant, quote the rule's statement instead
   and say so in the bridge table.
3. **Put pure operators in a module with no VARIABLES.** A module that `EXTENDS` a module with
   variables inherits them and must initialise them; splitting the decision rules into their
   own variable-free module is what lets two downstream modules bind to the same operators over
   the same pools. If a cross-check restated a rule, it would be comparing a third model
   against the other two.
4. **One BOOLEAN flag per finding, and the flag switches a mechanism, not an outcome.** A flag
   that switched the outcome directly ("allow the invariant to break") proves nothing; a flag
   that switches the mechanism means the violated/fixed pair demonstrates that *this*
   mechanism is what breaks the invariant.
5. **Parenthesise the whole right-hand side of every primed assignment whose value is a
   conjunction or a disjunction.** `/\` and `\/` bind looser than `=`, so
   `latch' = latch /\ cond` parses as an assignment **plus a guard** and silently disables the
   transition the latch exists to record. It surfaces as `Error: Deadlock reached` in a state
   that looks perfectly able to move on. Two things find it in a minute: identical
   reachable-state counts with the flag on and off (a flag that changes nothing about which
   states exist is not landing), and `INVARIANT ENABLED(SomeAction)` on a guarded state
   predicate.
6. **Add the row to `formal/tla/README.md`**, including what the model abstracts away and what
   the bounds were. An absent bridge row is not an oversight to be left blank; the README names
   every registry invariant with no model and the reason.

Two things worth aiming a model at, because they are what only a model can do:

- **The dangerous direction of a known finding.** One configuration runs the divergent engine
  and asks the opposite question from the finding -- does the collapsed marker ever *accept* an
  append the protocol forbids? Across all its states it does not. A suite run cannot establish
  that; it can only report the divergences it happened to produce.
- **A number somebody rounded off.** A model earns its place by contradicting a measurement,
  not by agreeing with one. Two of three measured statements were confirmed, which was worth
  little because the arms had already measured them; the third was overturned, and it changed
  what a finding's candidate fix should say. The measurement had the evidence all along and it
  had been written off as the arm getting lucky.

**And make the cross-check fail before believing it.** 960 of 960 agreeing on the first try is
either a correct pair of models or a broken comparison, and the two look identical from the
outside. Swapping two boundaries in the Java pool produced 86 disagreements; then it was
reverted and the clean result reconfirmed. Two minutes, and it is the difference between a
cross-check and a claim about one.

---

## The drift check

The registry and the checker constants must be character-identical, and the only way that
stays true is to check it mechanically. Run this before committing any new or edited statement;
it prints nothing when nothing has drifted.

```bash
python3 - <<'EOF'
import re, glob
stmts = {}
for f in glob.glob('simulation/src/main/java/org/axonframework/hunt/checker/*.java'):
    src = open(f).read()
    for m in re.finditer(r'public static final String (\w+_STATEMENT)\s*=\s*(.*?);', src, re.S):
        stmts[m.group(1)] = ''.join(re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(2)))
names = {}
for f in glob.glob('simulation/src/main/java/org/axonframework/hunt/checker/*.java'):
    for m in re.finditer(r'public static final String (\w+)\s*=\s*"([^"]+)";', open(f).read()):
        if not m.group(1).endswith('_STATEMENT'):
            names[m.group(1)] = m.group(2)
inv = open('formal/INVARIANTS.md').read()
for key, stmt in sorted(stmts.items()):
    mn = names.get(key[:-10])
    row = re.search(r'\|\s*`' + re.escape(mn or '?') + r'`\s*\|\s*(.*?)\s*\|', inv) if mn else None
    if mn and (not row or row.group(1) != stmt):
        print('DRIFT', mn)
EOF
```
