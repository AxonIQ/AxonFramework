---
name: axon-hunt
description: Drive and extend the Axon Hunt bug-hunting suite for Axon Framework 5 -- the seeded simulation harness in `simulation/`, its history-based oracles, and the invariant registry, findings log, canary campaigns and TLA+ models in `formal/`. Use when hunting for engine defects, triaging a red or inconclusive hunt run, reproducing a reported customer issue as a scenario, running the smoke / fuzz / chaos / per-backend matrix tiers, replaying a recorded history offline, or reading a per-backend verdict vector. Use equally when EXTENDING the suite: mining claims from Javadoc or code, turning a failure-mode hypothesis into a scenario, writing a scenario specification, adding an invariant, checker, fault, workload, backend, canary or TLA+ model, or covering a subsystem the suite has never touched (query side, dead letter queues, sagas). Also carries the general distributed-systems testing method in full, so use it for: designing a test plan or stability / release-validation campaign for this framework, walking a pitfall catalogue to generate falsification hypotheses, picking a technique (consistency checking, deterministic simulation, chaos, fuzzing, formal methods, property and metamorphic, crash-recovery and upgrade, performance), designing an operation-history schema or a checker, injecting a fault and proving it landed, reducing a reproducer and assigning blame (engine / harness / checker / environment), labelling a verdict beyond PASS-FAIL-INCONCLUSIVE, or testing a boundary claim -- tenant, context, namespace, routing, authorisation -- or a fairness / noisy-neighbour claim across surfaces. Carries the TLA+ working reference too, so use it when writing, checking, reviewing or debugging a TLA+ specification, choosing TLC configuration sections, bounding a state space, reading a counterexample trace, judging whether a green TLC run verified anything, or validating a recorded trace against a specification. Also trigger on MachineName, HistoryView, DcbStoreModel, ScenarioRunner, HuntBackend, canary, landing evidence, "green-but-broken audit", weak oracle, verdict vector, surface decomposition, negative control, TLC, PlusCal, TypeOK, invariant strength, trace validation, ndJsonDeserialize, or "the determinism boundary".
---

# Axon Hunt

This skill is the **method**. The specifics -- which invariants exist, which findings are
open, which canaries were caught, how many tests there are -- live in the documents named
under "Where the truth lives" and are read from there, never from memory, because they move.

Two parts, and they answer different questions:

- **Part 1 -- hunting with the suite.** You want to find, triage, reproduce or pin a defect.
- **Part 2 -- extending the suite.** You want to cover something the corpus does not. The
  scenario list was never meant to be final; part 2 is the discipline that produced it, so a
  new subsystem gets the same rigour instead of a new invented method.

## First command, every session

```bash
git rev-parse --show-toplevel && git branch --show-current
```

Both lines must show the hunt worktree and `feature/dst-testing-suite`. A wrong-checkout
read silently analyses the wrong code, and every conclusion drawn from it is worthless.

The suite is behind a Maven profile; `./mvnw verify` does not build it. Nothing you do here
changes framework code -- see the constitution.

---

# Part 1 -- hunting with the suite

## The mental model

Four stages, each a different kind of artefact:

```
claims          C1-C40 (documented guarantees) + M1-M18 (gaps: things the docs
                do not say) in docs/testing-plans/axon-hunt.md
                a test you cannot tie to a C or M number does not get written
   |
   v
invariants      MachineName + statement, formal/INVARIANTS.md section 3
                one sentence, present tense, worded character-identically in the
                registry, the checker's Javadoc, the violation message and any
                TLA+ operator. Drift between those copies is how a suite starts
                lying about what it verified.
   |
   v
history         one JSON Lines file per run: header + INVOKE/OK/FAIL/INFO records,
                schema in formal/INVARIANTS.md section 4. The history is the
                evidence, the regression asset, and the only exact record of a run.
   |
   v
checkers        pure functions from HistoryView to CheckResult, found by
                ServiceLoader, run against every history whether or not the
                scenario asked for them
```

A scenario does not compute a verdict. It declares a workload, a fault schedule, a backend,
a timescale, a determinism mode and a seed; the runner drives it, records a history, and
folds the whole registered checker set over that history. Verdicts are therefore
reproducible from a file even where runs are not.

### The two spine commitments

**The reference model is the primary oracle.** `DcbStoreModel` is a pure, JDK-only
executable model of the DCB append protocol. Every history is replayed against it, and a
divergence is attributed to one *named rule* rather than to "the store". Property checkers
are the secondary net: they only catch what somebody thought of, and a reference model
catches the semantic drift nobody enumerated. Corollary: **do not add concurrency to the
model.** It is sequential on purpose, so it can be compared against a TLA+ specification of
the same protocol; properties outside its reach are checked against the real engine.

**The backend differential is the attribution strategy.** The thing under test is not the
framework, it is the framework crossed with a store protocol. The same scenario object, the
same workload supplier, the same checker set and the same timings run once per registered
backend, and every result carries a **verdict vector**:

```
VECTOR <scenario> in-memory:PASS hsqldb-tokens:PASS postgres-jpa:FAIL(1 n/a) postgres-jpa-split-tokens:FAIL(1 n/a)
```

| Shape | Attribution |
|---|---|
| broken on every backend | core framework logic |
| broken on one backend | that adapter, or that store's own semantics |
| `n/a` on one backend | the invariant is inexpressible there; the vector claims no coverage |

**A finding without a vector is a finding nobody can argue about.** It is one store's
observation and the write-up must say so. Producing the vector costs one command
(`references/running.md`), and it is what turns "the framework loses events" into "the
aggregate-based JPA adapter loses events and the boundary-native store does not".

## Where the truth lives

Read these. Never copy them into a report, and never quote from this skill a number one of
them owns.

| Document | What it owns | Open it when |
|---|---|---|
| `formal/INVARIANTS.md` | The suite constitution; the measured determinism boundary; the MachineName registry with statements, claims, checkers, scenarios and TLA+ operators; the four checker channels; the history schema; the add-an-invariant and add-a-scenario/fault/workload/backend recipes | Before writing any assertion or checker. Before quoting any invariant wording. |
| `formal/FINDINGS.adoc` | F-1..F-n: what is wrong, severity, evidence, per-backend vector, candidate fix, reproduce command | Before reporting anything as new. A red arm is usually a known finding. |
| `formal/CANARIES.md` | The mutation campaigns: which planted defect each oracle caught, which escaped, and the honest "not canaried" tables | Before trusting a green run. Before claiming an oracle works. |
| `formal/HUNT-NOTES.md` | Append-only notes: determinism seams, API traps, commands that worked, design decisions and their reasons | When something does not behave as you expect. Nearly every trap in this repo is already in there. |
| `formal/tla/README.md` | The models, their bounds, what each configuration produced, the MachineName bridge, and which registry invariants have no model and why | Before adding or reading a TLA+ model. |
| `docs/testing-plans/axon-hunt.md` | Claims C1-C40 with `file:line` sources, gaps M1-M18 with the evidence searched, the failure-mode hypotheses, the scenario corpus, the design commitments, the extensibility charter, the knowledge-usage playbook | Before writing a scenario. To find the C or M number that justifies it. |

Append to `HUNT-NOTES.md` whatever cost you an hour. That file exists so the next agent does
not pay again.

## Knowledge: everything load-bearing is in this skill or in this repo

This skill depends on **no external skill and no private repository**. Every reference under
`references/` is self-sufficient for the work it describes, and the in-repo documents under
"Where the truth lives" are the authority on specifics:

| Need | In this skill / this repo |
|---|---|
| The load-bearing rules in short form -- history discipline, checker picker, landing evidence, the audit, weak oracles, DST seams, anti-hang design | `references/method-essentials.md` |
| The same rules in full -- every field, every pattern, every mechanism | `references/history-discipline.md`, `references/oracle-patterns.md`, `references/fault-catalogue.md`, `references/green-but-broken-audit.md` |
| Hypothesis generation for a subsystem with no coverage | `references/pitfall-catalogue.md` -- part 1 by claim kind, part 2 the recurring sixteen with hypothesis templates -- plus the walked table in `docs/testing-plans/axon-hunt.md` section 4 |
| Which technique to reach for, what it misses, its tools and papers | `references/technique-catalogue.md` |
| A claim about tenancy, context, routing or fairness | `references/boundary-and-isolation.md` |
| Labelling a verdict, reducing a reproducer, assigning blame and kind | `references/verdicts-and-classification.md` |
| Designing a campaign from nothing, or running one as a session | `references/plan-workflow.md`, `references/execution-workflow.md`, and the three files under `assets/` |
| Writing, checking or debugging a TLA+ model -- and telling a green model from a vacuous one | `references/tla-modelling.md`; `formal/tla/README.md` owns the models themselves |
| Checking a recorded run against a model rather than exploring a state space | `references/tla-trace-validation.md` |
| The DCB conflict semantics a checker, model rule or TLA+ operator must encode | `references/dcb-semantics.md`, and the rule table with engine `file:line` evidence in `formal/INVARIANTS.md` section 3.1 |
| AF5 wiring for a workload, backend or probe -- verbatim, compiling, with the plain-Java traps | `formal/HUNT-NOTES.md` section 2; the published Javadoc is the fallback |
| Contribution conventions for code in this repo | the repository's own `CLAUDE.md` and `.claude/rules/`; `build/checkstyle.xml` is the enforcement |
| Third-party specifics (Testcontainers, Toxiproxy, TLC, JDBC drivers) | verified, pasted commands in `references/running.md` and `formal/HUNT-NOTES.md` -- they were run against the versions this suite uses, which beats documentation |

Two **optional** tools help when present and cost nothing when absent:

- **`graphify`** -- a prebuilt knowledge graph of the framework, if `graphify-out/` exists.
  `query`/`path`/`explain` to orient before grepping; the graph orients, the source decides;
  never quote it as evidence. Absent: grep, starting from the checker classes in the invariant
  registry and the `file:line` anchors in the claim corpus.
- **`ctx7`** -- third-party library documentation. Its quota ran out partway through this
  project, so nothing here may depend on it; the verified commands in `running.md` and
  `HUNT-NOTES.md` exist precisely so it is never needed.

**What this skill deliberately does not contain:** the design rationale of the DCB boundary
concept itself (why an aggregate is the wrong unit, how to choose a boundary -- a modelling
question; `dcb-semantics.md` is sufficient for *checking* work); AF5 application-building
guidance beyond what the harness wires; and any copy of the numbers the in-repo documents own --
findings, invariants and canary results are read from `formal/`, never from here, because they
move.

The general distributed-systems method **is** carried here in full, not distilled away: the
technique catalogue with its tools and papers, the recurring pitfall families with hypothesis
templates, every oracle pattern, every fault mechanism, the whole audit, the boundary discipline
and the verdict labels. Each of those files says where this suite already covers the ground and,
explicitly, where it does not -- the uncovered rows are the point.

## The bug-hunting loop

Detail, with the determinism boundary spelled out: **`references/hunting-loop.md`**.

1. **Hunt read-only.** Run a tier, or point an existing scenario at another backend. Never
   patch framework code. Never add a seam to framework code.
2. **Triage.** Is it in `FINDINGS.adoc` already? Is it the harness rather than the engine?
   Assume the harness first: in this project's history the first run of a new arm has been
   mostly harness every single time.
3. **Reproduce.** Get a per-backend vector. **`REAL_THREADS` reproduces nothing** -- not the
   record order, not the operation counts, not which appends were accepted. A finding is
   pinned by its **history file**, never by its seed. `SINGLE_THREADED` reproduces the write
   side exactly and nothing else.
4. **Reduce, then assign blame.** Bisect the fault schedule down to the smallest sequence that
   still reproduces, then classify the defect as **engine, harness, checker or environment** --
   `references/verdicts-and-classification.md` sections 2 and 3. Three of those four are our
   work, not the framework's, and the tells that separate them are tabulated there.
5. **Verify yourself.** Mutate the thing you think is the cause and check the verdict moves.
   A differential that has never been shown to fail is indistinguishable from one that
   compares nothing.
6. **Pin or reject.** Pin: a `FINDINGS.adoc` entry with a candidate fix, plus an
   expected-gap test that passes while the gap exists and flips red when it is closed, plus a
   pinned history for a contended run or a pinned seed for a single-threaded one. Reject: say
   what it actually was, and if it was the harness, fix the harness and pin that.

## What may be reported upstream

Recording a finding and reporting it are different acts with different bars. `FINDINGS.adoc` takes
everything the suite learned. An issue or a pull request on `AxonIQ/AxonFramework` or
`AxonIQ/axoniq-framework` takes far less.

**The bar for reporting: a test that fails on unfixed code and passes on fixed code.** Nothing else
gets filed. Not a grep, not a measurement, not an argument from reading the source.

| Kind of finding | Record in `FINDINGS.adoc` | File upstream |
|---|---|---|
| Behaviour is wrong, and a test proves it | yes | **yes** |
| Behaviour is wrong, but nothing can be made to fail | yes | **no** |
| Behaviour is correct, only the documentation is silent or wrong | yes | **no** |
| A design or API you would prefer differently | yes | **no** |
| Harness defect | yes, plus fix the harness | **no** |

Three shapes that look like evidence and are not:

- **A characterisation test** that passes before and after. It pins today's behaviour so a future
  change is noticed. It demonstrates no defect.
- **A mutation-validated test**, red only when you deliberately break production code. That proves
  the test has teeth, not that a bug exists.
- **An inspection** -- a `grep`, a `sed`, a `javap` showing what the source says. Enough to record a
  finding. Never enough to ask somebody to change their code.

If the fix would be documentation, the answer is no. A reviewer cannot act on it the way they act on
a failing test, and it costs them the same triage. Keep it in `FINDINGS.adoc`, where the next person
hunting this subsystem will find it.

**This is not an invitation to force a test.** If the only way to make something fail is a fake that
asserts against itself, or an assertion on a private field nothing reads, then the finding does not
meet the bar. Say so and move on. A finding recorded honestly and not filed is a good outcome; a
filed issue whose test proves nothing is worse than silence, because it spends somebody else's
attention and teaches them to discount the next one.

## Running it

Every command, verified, with the traps: **`references/running.md`**.

```bash
# The gate. The whole module, which is what CI runs on a change.
./mvnw -q -Phunt -pl simulation -am test > /tmp/hunt.log 2>&1; echo "EXIT=$?"

# Replay one recorded history offline. No simulation; same verdict for ever.
./mvnw -Phunt -pl simulation -am test -Dtest=HistoryReplayTest \
    -Dhunt.history=<path to .jsonl> -Dsurefire.failIfNoSpecifiedTests=false

# Re-sample one scenario at one seed on one backend. Under REAL_THREADS this is a
# re-sample, not a replay.
./mvnw -Phunt -pl simulation -am test -Dtest=HuntReproduceTest \
    -Dhunt.scenario=<id> -Dhunt.seed=<n> -Dhunt.backend=<name> -Dhunt.timescale=compressed \
    -Dsurefire.failIfNoSpecifiedTests=false
```

Three traps that each cost an earlier phase real time:

- **Judge by the exit code.** Under `-q` a clean build prints nothing, and a pipe reports the
  *last* command's status, so `mvn ... | tail` exits 0 even when the build failed.
- **Counting tests: `rm -rf simulation/target`, the whole directory.** The per-class `.txt`
  report says `Tests run: 0` for `@Nested` classes; the XML `tests=` attribute is also wrong;
  and deleting only `surefire-reports` leaves the previous container run's reports to be
  counted with freshly touched timestamps. Count `<testcase` elements after removing `target`.
- **`timeout` does not exist on macOS.** `timeout 600 java ...` exits 127 with no output,
  which inside a loop reads as every configuration silently doing nothing.

## Reading a failure

Detail, including flake classification: **`references/hunting-loop.md`**. The labels, the
decision tree, the reduction recipe and the blame tells:
**`references/verdicts-and-classification.md`**.

The verdict is three-valued and the third value is load-bearing. `PASS`: every required
oracle ran, every declared fault landed, the read side settled, nothing broke. `FAIL`: an
invariant was found broken. `INCONCLUSIVE`: the run could not tell -- a fault that never
fired, an unknown outcome, a read side that never caught up, a required oracle not
registered, a budget overrun.

Three values are what a checker channel can decide; they are not enough to write up. **A pass is
capped by the budget tier the run actually met** -- smoke or hardening, never whichever sounds
better. **A failure splits on whether a reproducer exists.** And **`INCONCLUSIVE` splits four
ways, each with a different repair**: a missing capability, a fault that was not proven to land,
an oracle too weak for what was recorded, or an arm nobody attempted. Three of those four are
harness work. Reaching for the label first tells you what to fix.

A multi-arm scenario reports **a verdict per arm plus the capped aggregate**, never the aggregate
alone: any unrun or partial arm caps the whole scenario. That is the same rule the `n/a` column of
a per-backend vector already encodes.

A checker has four things to say and only two move a verdict: a **violation** (`FAIL`), a
**note** (`INCONCLUSIVE`), a **measurement** (a fact the history fully accounts for; the
verdict stands) and a **not-applicable** statement (an invariant this run cannot express, by
name; the verdict stands). The last two exist because arms were measured reporting
`INCONCLUSIVE` on every seed of every run, and **an arm that can never reach a pass can never
signal a regression either.** A permanently inconclusive or permanently red scenario is
exactly as worthless as one that always passes. Treat either as a bug in the arm.

Under zero quarantine, every intermittent failure is classified as exactly one of: an
**engine bug** (reproduces, becomes a finding), a **harness bug** (fixed, and pinned), or a
**load artifact** (documented with the evidence that it passes in isolation). No fourth
option, and no retry.

---

# Part 2 -- extending the suite

The corpus is instances; the harness is the product. Any future property, fault, workload,
backend or scenario class must be addable without touching an existing one. Everything below
is the discipline that produced the existing corpus, so that a subsystem nobody has covered
yet gets the same rigour rather than a fresh invented method.

## The design pipeline

```
mine claims from Javadoc and code       ->  C-numbers (documented) and M-numbers (gaps)
walk a pitfall catalogue against them   ->  falsification hypotheses
write each hypothesis to the template   ->  a scenario record (data, not code)
decide which invariants judge it        ->  existing MachineNames, or a new registry row
plant a bug the new oracle should catch ->  a canary, measured and reverted
record what is still uncovered          ->  an accepted residual with an owner
```

Full method: **`references/claims-and-scenarios.md`** (claims mining, confidence,
refutation, the pitfall walk, the five-field scenario template). When the task is a whole
campaign rather than one scenario -- a new subsystem, a change under review, "what should we be
testing" -- the ordered procedure is **`references/plan-workflow.md`** and the shape to fill is
**`assets/plan-template.md`**. To run one as a session that somebody else will read:
**`references/execution-workflow.md`**, with `assets/session-log-template.md` and
`assets/findings-report-template.md`.

**One chain that paid off end to end**, so the shape is concrete rather than abstract:

| Stage | Content |
|---|---|
| Pitfall | Sequence-number collision / gap handling in a store with a pre-commit sequence |
| Hypothesis | A JPA global-sequence gap is dropped after the gap timeout while a long transaction commits later, so the event is never streamed (claims C13, C14, gap M2) |
| Gap evidence | The docs state that timed-out gaps are removed for performance and that gaps may never be filled if the events never commit. Nothing states the outcome for an event that *does* commit after its gap timed out. |
| Scenario | `no_event_skipped_by_gap_timeout`, plus a sibling arm on the inverted Spring Boot configuration |
| Result | Finding F-16: committed events never delivered, on both configuration paths, decided rather than excused. The same arm produced F-17 on the way. |

Note where the value came from: **the gaps, not the documented promises.** F-9, F-10 and
F-16 all came from M-numbers. A guarantee that is written down has usually been tested by
somebody; the sentence nobody wrote is where the defect lives.

## The claims rule

- Every claim is one **falsifiable** sentence, anchored to `file:line` with a short verbatim
  quote, carrying a kind (safety / durability / ordering / membership / semantics / ...) and
  a confidence: `documented` (stated in Javadoc or reference docs) or `code-inferred` (only
  visible in the implementation). **A `code-inferred` claim is weaker evidence AND is itself
  a documentation finding.**
- Every claim carries a **falsified by** clause. That clause is the checker's assertion, so
  write it as something a history could show.
- **Never invent support.** An anchored claim the code contradicts is written up as REFUTED
  or WEAKER THAN STATED, and that is a first-class result, not a failed claim.
- **Never pad to a target count.** The corpus is append-only and versioned; a claim added to
  reach a number is a test nobody can justify.
- **A test you cannot tie to a claim or gap number does not get written.** If the property is
  real and has no number, add the number to the plan first.

## The scenario template

Scenarios were first written as one-liners, and every one of them had to be sharpened later.
The five things a one-liner leaves implicit are the template. Fill-in form and worked
examples: **`references/claims-and-scenarios.md`**.

| Field | What it must say | The failure it prevents |
|---|---|---|
| ORACLE | Exactly what is compared to what, at exactly what moment. "The projection converges" is not an oracle; "the projection's balances sum to the opening total after quiescence" is. | An arm that runs and decides nothing |
| WORKLOAD | Op mix, key distribution, concurrency, batch sizes, command count per tier | A load generator mistaken for a test |
| EVIDENCE | What proves each declared fault fired, taken from the thing perturbed and not from the harness | A green run under a fault that never landed |
| AMBIGUITY | How a timeout, a dropped connection or a mid-commit failure is classified | Unknowns collapsed into pass or fail |
| BUDGET | Commands, seeds and wall-clock per tier, and what counts as pass at each | A hardening verdict quoted off a smoke run |

**A scenario missing any of the five is not implementable.** Write the five before writing
the record.

## The design commitments, and which of them held

The harness was constrained up front by numbered commitments in the plan. The list matters
less than this judgement, which is what practice produced:

**Load-bearing, confirmed by measurement.**

- The executable reference model as primary oracle. It catches semantic drift nobody
  enumerated, and it attributes a divergence to a named rule.
- The backend differential as attribution. Every argument about whose defect it is ends at
  the vector.
- A conservation-law workload as the cheap global oracle. It caught a double-processing
  mutation for which nobody had written an assertion, by arithmetic alone -- twice, in two
  different mutation campaigns.
- Planted-bug validation. Every oracle worth trusting has been shown to go red on a defect,
  and the two that escaped are written up as gaps rather than quietly dropped.
- Zero quarantine. Reruns would have buried the intermittent findings that turned out to be
  real.

**Needed correcting in practice.**

- The operation-history commitment contradicted itself: one record carrying an invocation
  timestamp and a completion timestamp cannot represent an operation that never completed.
  Separate invocation and completion records, correlated by identifier, is the only workable
  form -- and it is what makes an unknown outcome expressible at all.
- Time compression is a harness-wide dimension, except where it is not: several framework
  durations do not compress (a hardcoded coordinator re-poll, a hardcoded post-split
  re-claim block), and a timeout compressed below what a real store can answer in produces
  mass redelivery that looks like a framework defect. See `references/traps.md`.
- The pause primitive was built, verified by hand, and **no scenario declares one**, so the
  whole class of failure a kill cannot produce is still unexercised. That is recorded as a
  gap, not as coverage.

Detail and the full list: **`references/design-commitments.md`**, which also carries the
extensibility charter as an acceptance test and the lineage of what was copied from the
reference implementation.

## The charter, as an executable acceptance test

> Add a new invariant, a new fault and a new backend without editing any existing scenario.

That must be mechanical, and it is checked at every phase boundary. **What violating it looks
like:** the first attempt at multiplying the integration tests across stores wrote one leaf
class per abstract suite per store -- nineteen files for the first store, and nineteen more
for every store after. It was deleted and replaced by a run-time backend selection, which
made adding a store cost one file in total. If a change requires surgery on existing
classes, the design failed the charter and gets reworked before the work closes.

Two rules that are easy to violate by accident: **a scenario is a data record**, so new
scenarios change no harness code; and **a backend is a run-time selection**, never a class
per backend.

## Oracle and checker design

- Pick the checker from the property, not from the code you feel like writing. The
  checker-picker table is in **`references/method-essentials.md`**.
- The reference model is the primary net; property checkers are the secondary one. A new
  property usually means a new checker, not a change to the model.
- **Every checker must be proven able to fail on a planted-bad history before it is
  trusted.** Build those histories through the synthetic-history helper, never by fabricating
  records by hand. A checker with no demonstrated failure mode is decoration.
- Decide explicitly, in the checker's Javadoc, what it does with an unknown outcome. The
  default is a note, not a violation.

## Extending to a subsystem with no coverage

End-to-end recipe, plus the currently uncovered surfaces and who owns each:
**`references/extending.md`**.

## Recipes

Each is mechanical and edits **zero existing classes**. Full steps: **`references/recipes.md`**.

| Add | Costs |
|---|---|
| An invariant + checker | A registry row, a `Checker` class, one line in the `META-INF/services` file, canaries that plant the rule broken |
| A fault | One `Fault` class, plus a case proving it fires |
| A workload shape | One `Workload` class; conservation hooks optional |
| A backend | One `HuntBackend` class plus one service-registration line |
| A canary | A mutation applied to framework code, measured, and **reverted**; only the `CANARIES.md` row persists |
| A TLA+ model | A `.tla` plus a violated/fixed `.cfg` pair, bridged by MachineName, statement quoted verbatim |

**The golden rule, in every recipe.** A property is done only when it exists in three places,
worded identically: a row in `INVARIANTS.md`, an assertion in code, and a scenario or a
pinned seed that exercises it. Two of the three is a property nobody checks, or a check
nobody can find.

---

# Shared: the rules that bind both parts

## The constitution

Verbatim in `formal/INVARIANTS.md` section 1. Each rule exists because a suite that broke it
stopped being able to find bugs.

1. **Never patch the engine.** A confirmed defect becomes a finding plus an expected-gap
   test, not a fix. A suite that patches what it measures can no longer tell you whether the
   release is broken.
2. **Zero quarantine.** No `@Disabled`, no tag used to hide a failure, no
   `rerunFailingTestsCount`, no silent skip.
3. **Judge by exit code, not by banner.**
4. **A fault without landing evidence makes the run `INCONCLUSIVE`, never `PASS`.**
5. **Unknowns are unknowns.** Never collapsed into success or failure; trailing in-flight
   operations are never truncated.
6. **Scope determinism claims honestly**, on both sides of every assertion.
7. **Paste real output.** No verdict from a command that was not run.

Two more, learned here rather than inherited:

- **Refuse to ship a test that is red on a clean engine**, even when it would pin a real
  finding. Green-on-clean has to mean always, not usually. Write the finding up as
  intermittent instead.
- **Never assert that the suite finds nothing else.** `allSatisfy(v -> v == theKnownOne)` is
  an assertion that the suite must stop working. Pin the finding you mean, not the shape of
  the whole result.

## Honest reporting

Not a footnote: **`references/honest-reporting.md`**. The short form:

- Paste real output. Never claim an unrun result. If a command was too slow to run in full,
  run the shortest form that proves the invocation and say which you did.
- Run the green-but-broken audit before declaring any pass, and **say which rows came back
  short.** In this project's history, every phase's audit found at least one overclaim.
- Record what was **not** tested, with an owner. The canary document's "not canaried" table
  and the coverage matrix's accepted residuals are the pattern to copy.
- An escaped canary stays in the record next to its re-run. Overwriting it erases the
  evidence that the hole existed, and the hole is the interesting part.

## The traps this project actually hit

**`references/traps.md`** -- read it before writing up any divergence. It carries the vacuous
oracles, the four harness bugs that produced convincing false findings in a single phase, and
the inference trap: **a measurement plus an inference is not a result.** One phase measured a
skew bound correctly and drew the wrong conclusion from it; the model that followed
contradicted the inference, not the measurement.

## Reference index

**This repository's own machinery.**

| File | Read it when |
|---|---|
| `references/running.md` | You need a command. All of them, verified, with their traps. |
| `references/hunting-loop.md` | Triaging, reproducing or interpreting a result. Determinism boundary and flake classification. |
| `references/recipes.md` | Adding an invariant, fault, workload, backend, canary or model. |
| `references/traps.md` | Before writing up any divergence as a finding. |
| `references/dcb-semantics.md` | Writing or reviewing anything that decides whether an append conflicts. |
| `references/claims-and-scenarios.md` | Mining claims, or designing a scenario from a hypothesis. |
| `references/design-commitments.md` | Making a design decision about the harness itself. |
| `references/extending.md` | Covering a subsystem that has no coverage today. |
| `references/honest-reporting.md` | Writing up anything anyone else will act on. |

**The method, in short form and in full.** Each of the full files marks where this suite already
covers the ground and where it does not.

| File | Read it when |
|---|---|
| `references/method-essentials.md` | The short form of all of it. Writing a checker, a fault or a new arm. |
| `references/technique-catalogue.md` | Choosing how to attack a hypothesis. Symptom-to-technique selector, then eight techniques with what each misses, its tools and its papers. |
| `references/pitfall-catalogue.md` | Generating hypotheses. Part 1 by claim kind with this suite's findings as instances; part 2 the sixteen recurring families with hypothesis templates. |
| `references/history-discipline.md` | Designing or fixing a recorder. Every field, the vantage points, weak history, ambiguous outcomes, the model-to-fields picker. |
| `references/oracle-patterns.md` | Picking or writing a checker. The picker, then fourteen patterns with how each fails silently. |
| `references/fault-catalogue.md` | Injecting anything. Mechanism, landing evidence and cleanup per fault, across process, network, storage, time, cluster and framework. |
| `references/green-but-broken-audit.md` | Before declaring any pass. Ten red flags, fourteen weak oracles, and how to record which rows came back short. |
| `references/boundary-and-isolation.md` | A claim about tenancy, context, routing, or fairness. Surfaces, the claim matrix, confusable inputs, negative controls, the per-group formula. |
| `references/verdicts-and-classification.md` | Labelling a verdict, reducing a reproducer, assigning blame and kind. |
| `references/tla-modelling.md` | Writing or checking a TLA+ model. Syntax, configuration sections, the review checklist, the traps that make a model verify nothing, coverage, and reading a counterexample. |
| `references/tla-trace-validation.md` | Replaying a **recorded history** through a model instead of exploring a state space. The histories are already JSON Lines, which is what the checker reads. |
| `references/plan-workflow.md` | Designing a campaign from nothing. The ordered procedure and its anti-pattern checks. |
| `references/execution-workflow.md` | Running a campaign as a session somebody else will read. |

**Assets.** Shapes to fill, not documents to read: `assets/plan-template.md`,
`assets/session-log-template.md`, `assets/findings-report-template.md`.

## Installing this skill elsewhere

The skill ships as the `axon-hunt` plugin in the `axoniq-internal` marketplace:

```
/plugin marketplace add AxonIQ/internal-agent-skills
/plugin install axon-hunt@axoniq-internal
```

Nothing in the skill is generated, so the install is the whole setup. The skill points at
`formal/` and `simulation/` by relative path, so it is only fully useful next to the
worktree it describes.
