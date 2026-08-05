# The plan workflow -- designing a campaign from nothing

**This file is self-contained.** It is the ordered procedure for producing a test plan when
there is no plan yet: a new subsystem, a change under review, or a whole system nobody has
aimed the suite at. `docs/testing-plans/axon-hunt.md` is the output of one run of this
procedure; `assets/plan-template.md` is the shape to fill in.

Use it when the task is **"what should we be testing"**, not "run the suite". For the
hunting loop against the existing corpus, go to `hunting-loop.md`.

**Follow the steps in order.** Later steps consume artefacts the earlier ones produce, and the
usual failure of skipping ahead is a scenario list nobody can justify.

## Two modes -- decide before starting

- **Change-scoped.** The default. A commit, a pull request, a branch diff, a feature. The plan
  covers what *this change* could regress, bounded by its blast radius.
- **Project-wide.** A release-validation plan, a stability plan, "what should we be testing",
  "is our coverage enough". The plan covers what *the system* should be tested for, with an
  explicit inventory of existing tests and a gap analysis driving the new-scenario list.

If the framing is ambiguous, **ask once** before starting. The modes diverge enough that
retrofitting one into the other wastes the work.

---

## 1. Scope the system

Read the entry points: `README`, `CLAUDE.md` or `AGENTS.md`, top-level `docs/`, any existing
test plan or runbook. Note:

- The tenancy and isolation model.
- The persistence model -- what is durable, when, under what contract.
- Replication or consensus, if any -- protocol, quorum, leadership.
- The ordering guarantee exposed to callers.
- The boundaries messages cross.
- The retry and idempotency contract.
- The observability an oracle could consume.

Write it as **one paragraph**. If anything is ambiguous from the repository, **ask -- do not
invent a guarantee.** An invented guarantee produces a scenario that fails for the wrong reason
and a finding nobody can act on.

## 1b. Extract claims and gaps

A plan exists to falsify what the product **claims**. This step is the spine; everything else
hangs off it.

Mine: documentation "guarantees" sections; the reference guide; architecture and design
documents; Javadoc on public API; **error types** (an exception named for a conflict implies a
claim about conflicts); and **existing test names** (a test named for an invariant implies the
invariant is claimed).

Categorise every claim:

| Kind | Example shape |
|---|---|
| safety | "no acknowledged append is ever lost", "linearizable per key" |
| liveness | "every accepted operation eventually commits" |
| durability | "committed events survive a crash" |
| performance | "p99 within X at Y operations per second" |
| operational | "a rolling upgrade is non-disruptive", "configuration changes are atomic" |
| idempotency | "the same key never produces two committed effects" |
| isolation | transactional isolation -- no read sees an uncommitted transaction |
| ordering | "consumers see the order the producer sent", "every reader sees a prefix" |
| membership | "a segment has exactly one owner", "a failed owner is replaced within N" |
| **boundary** | access-boundary semantics: "context A's data is never reachable from context B **on any surface**". Subsumes tenancy, authorisation, namespace and routing -- do not file those separately. **Triggers surface decomposition** -- `boundary-and-isolation.md` |
| **fairness** | per-group performance and noisy-neighbour isolation: "no processing group can starve another". The group may be a tenant, segment, processing group, partition, connection or priority class. **Also triggers surface decomposition** |

Note that `isolation` and `boundary` are two different concepts under two labels on purpose.
Transactional isolation is checked by a consistency checker; a boundary is checked by surface
decomposition.

Each claim carries: **one falsifiable sentence**, a `file:line` anchor with a short verbatim
quote, a kind, and a confidence -- `documented` or `code-inferred`. A `code-inferred` claim is
weaker evidence **and is itself a documentation finding**.

**Missing claims are a first-class output.** Behaviour the implementation relies on that no
claim covers -- a normalisation policy, a specific timeout window, an edge-case error semantic
-- gets its own number in a separate list. In this project those are the M-numbers, and **they
produced the best findings**: F-9, F-10 and F-16 all came from gaps, not from documented
promises. A guarantee that is written down has usually been tested by somebody; the sentence
nobody wrote is where the defect lives.

Rules that make the corpus usable:

- Every claim carries a **falsified by** clause, written as something a history could show.
  That clause becomes the checker's assertion.
- **Never invent support.** An anchored claim the code contradicts is written up as REFUTED or
  WEAKER THAN STATED. That is a first-class result.
- **Never pad to a target count.** A claim added to reach a number is a test nobody can
  justify.
- **A test you cannot tie to a claim or gap number does not get written.** If the property is
  real and has no number, add the number first.

## 2. Scope the change, or the project

**Change-scoped.** List every file touched and every surface affected -- APIs, on-disk formats,
wire messages, SPIs. Write a one-paragraph blast-radius statement.

**Project-wide.** Enumerate the externally observable surfaces -- public APIs, storage formats,
wire protocols, background jobs, operational controls -- and the invariants each must preserve.
Then declare what is **in** scope and what is **explicitly out** of scope. A project-wide plan
that tries to cover everything covers nothing well.

## 2b. Inventory existing tests -- project-wide only

Walk the whole test surface: unit tests, integration tests, existing fault-injection harnesses,
smoke scripts, CI workflows, test-plan documents. For each notable one, capture: which
subsystem, which invariant it pins, and which failure modes it would catch.

This becomes the left column of the coverage matrix. **Do not re-test what is already covered
well** -- the point of the gap analysis is what is not.

## 3. Generate hypotheses

For each claim: under what conditions could the system fail to honour it? Every hypothesis is
tied to claims by number.

**Walk the pitfall catalogue before generating from intuition.** `pitfall-catalogue.md` --
both parts. For every row, decide `y` / `n` / `maybe`, and record the reason for every `n`.
Every `y` and most `maybe`s become a hypothesis row. **This is the step that prevents a plan
covering only what the author already thought of**, and it is the highest-leverage step in the
whole procedure.

Cover these categories, or say explicitly why one is not applicable: correctness, durability,
liveness, partial failure, idempotency and replay, upgrade and rollback, configuration,
performance and fairness. **Writing "not applicable because ..." surfaces a wrong assumption
more often than it sounds like it would.**

Tag any claim of kind `boundary` or `fairness` now, because those trigger surface decomposition
in step 5.

## 4. Select techniques

Open `technique-catalogue.md`. For each technique picked, write down: which hypotheses it
addresses, what it would catch that the others would miss, and its cost.

**Two to four techniques is the normal range.** One is suspicious -- re-check whether several
distinct hypotheses were collapsed into one. A project-wide plan usually reaches five to seven.

For any scenario that will be *serious* -- any claim of kind safety, durability, idempotency,
isolation, ordering or membership -- pick the checker now from `oracle-patterns.md`. **The
checker choice is part of the plan, not a run-time decision.**

## 4b. Map coverage and name the gaps -- project-wide only

A table indexed by **claim**, not by hypothesis. Each row: the claim, the hypothesis that would
falsify it, the existing tests that exercise it, a verdict (covered / partial / not covered),
and the **gap kind** -- no test, shallow test, oracle too weak, no fault-injection variant, no
scale variant. Sort by claim severity times gap, worst at the top.

This table is the heart of a project-wide plan: it says where the product's claims are
unverified. Without it the plan is a wishlist.

For a large system -- fifty or more claims -- **split it**: a per-claim summary with a rolled-up
verdict, then the per-hypothesis detail. A single matrix where a load-bearing claim appears in
many rows is unreadable.

## 4c. Declare environment requirements

Per technique picked, list what the run will need: a container runtime, toolchains at version
floors, backing services, fault-injection facilities, kernel features, observability tooling,
project-specific binaries, licences.

Name **which scenarios depend on each requirement**, so a missing one produces a scoped
inconclusive rather than a dead session. Mark the ones the project brings up itself.

## 5. Design scenarios

One per hypothesis, at least. **Name each scenario after the claim it falsifies, never after its
setup.** `no_event_skipped_by_gap_timeout` beats "three nodes with chaos": a test named for its
claim is harder to weaken, and a test named for its setup tells a reader nothing about what it
verifies.

Every scenario declares five things. **A scenario missing any of the five is not
implementable** -- write the five before writing the record.

| Field | What it must say | The failure it prevents |
|---|---|---|
| ORACLE | Exactly what is compared to what, at exactly what moment. "The projection converges" is not an oracle; "the projection's balances sum to the opening total after quiescence" is | An arm that runs and decides nothing |
| WORKLOAD | Operation mix, key distribution, concurrency, batch sizes, command count per tier | A load generator mistaken for a test |
| EVIDENCE | What proves each declared fault fired, taken from the thing perturbed | A green run under a fault that never landed |
| AMBIGUITY | How a timeout, a dropped connection or a mid-commit failure is classified | Unknowns collapsed into pass or fail |
| BUDGET | Commands, seeds and wall clock per tier, and what counts as a pass at each | A hardening verdict quoted off a smoke run |

**Resist "the logs look fine" as an oracle.** It must be a machine-checkable property, a model
comparison, or a threshold defined before the run.

### The model, history and checker block -- for serious scenarios

Mandatory when any claim the scenario falsifies is of kind safety, durability, idempotency,
isolation, ordering or membership. Also mandatory **per arm** for any decomposed boundary or
fairness scenario -- every arm is serious.

- **Model under test.** From the picker in `history-discipline.md` section 5.
- **Operation history.** Which fields the recorder captures, any extension, and the vantage
  point.
- **Checker.** By name, from `oracle-patterns.md`. If there is none, write the justification for
  why the alternative oracle is strong enough alone.
- **Fault plus landing evidence.** The fault, from `fault-catalogue.md`, **plus the observable
  signal that proves it landed**. "The injector reported success" is not landing evidence.
- **Ambiguous outcomes.** How the recorder treats timeouts, unknown commits, retries and
  duplicates. State only the deviations from the default.
- **Reduction plan.** If it fails, the minimisation recipe, then the blame classification --
  `verdicts-and-classification.md` sections 2 and 3.

For a non-serious scenario, write "not applicable -- no gated claim kind falsified" and move on.
**Do not invent a model to fill the field.**

### The surface-decomposition block -- for boundary and fairness scenarios

Mandatory when any claim the scenario falsifies is of kind `boundary` or `fairness`. Fill the
matrix in `boundary-and-isolation.md` section 2, then lift its rows here: surfaces (at least
three per boundary claim, or a written justification for fewer), operations per surface,
adversarial inputs from the confusable catalogue, positive controls, negative controls, delayed
and asynchronous paths, observability paths, and the **arms** with their own identifiers.

This block is a **sibling** to the model block, not a replacement. A scenario falsifying both a
consistency claim and a boundary claim fills both.

For a non-boundary scenario, write "not applicable" and skip it. **Do not invent surfaces to
fill the field.**

### Three budget tiers, per scenario

- **Smoke.** The minimum configuration, duration, fault count and seed count for a smoke pass.
- **Hardening.** Strictly stronger on **every** dimension. Required for a hardening pass.
- **Release.** A long, repeated or statistical gate -- **or** an explicit
  `not provided -- <reason>. Revisit when: <condition>.` Empty, "TBD" and "see the environment
  section" are disallowed. An absent release budget must be a disclosure, never a silent gap.

## 5b. Argue that the coverage is enough

A plan that lists scenarios without arguing they are **enough** is a wishlist. Three parts:

1. **An architectural summary**, thirty lines at most: the major components, the data flow for
   the canonical request, where state is durable, where consensus runs, where the trust
   boundaries are. Not the technique catalogue -- the system as it actually exists, written so a
   reviewer who has never seen the code can follow the plan and spot a missing test.
2. **The adequacy argument, per claim.** The form: "claim C could be violated under threats T1,
   T2, T3; scenarios Sa, Sb, Sc exercise those threats under conditions X, Y, Z; therefore if C
   is wrong at least one of them catches it." A reviewer must be able to accept the argument or
   point at a specific gap -- "Sa does not actually inject T2".
3. **Residual uncertainty.** What the plan does **not** falsify, and why that is acceptable
   today, and when to revisit. This is what turns a plan from "tests" into an argument for
   shipping.

For any boundary or fairness claim, add a **per-aspect confidence table** -- one row per arm or
group, rated high / moderate / low with a one-line reason. A single paragraph saying "confidence
is moderate" without naming which aspects are moderate is the specific failure the table
prevents.

## 6. Write the plan file

Fill `assets/plan-template.md`. Default destination `docs/testing-plans/<slug>.md`. Create the
directory if it does not exist. **Pick a descriptive slug** -- the slug is the handoff.

## 7. Self-check

Read the plan back. Every hypothesis has at least one scenario. Every scenario has an oracle
that is not "the logs look fine". Every technique cites its reference. Fix what fails; do not
move on with a known gap.

**The adequacy test.** Imagine a reviewer who has never seen the code reading it cover to cover,
then being asked: "if all of these pass, are you comfortable shipping?" If the plan does not
contain enough for them to answer with confidence -- the architectural summary, the per-claim
adequacy argument, the residual list -- it is not done. **A list of scenarios is not a confidence
argument.**

### Anti-pattern checks, before declaring it done

1. **A name promising decomposition the plan does not deliver.** If a scenario name contains
   "across all surfaces", or names a boundary keyword -- routing, tenant, context, isolation,
   blast radius, region, shard, namespace, failure domain -- **and** its surface block is empty
   or names one surface: either fill the decomposition or rename the scenario. The framing worth
   internalising: tests tend to validate that a boundary mechanism **exists**, not that it
   **actually contains failure**. This check forces the author to say which one theirs does.
2. **A boundary claim with no negative controls.** Half the boundary asserted.
3. **A fairness claim with no per-group formula.** An aggregate threshold is not a fairness
   oracle.
4. **A vague oracle.** "No leaks", "no unauthorised access", "it converges" -- sharpen to a
   model comparison or a formula.
5. **A confidence statement that does not name the untested surfaces.** Any boundary claim with
   an arm expected to be unrun must have that arm named in the statement.
6. **A release budget that is neither a budget nor a disclosure.**
7. **A scenario with no claim or gap number.** Drop it, or add the number first.

## Early exit

If the change genuinely does not warrant a plan -- a documentation-only change, a typo, a
refactor with no behaviour change already covered -- **say so explicitly** and recommend the
lighter testing. Do not produce a ceremonial plan.

## What this procedure does not do

- It does not run anything. That is `execution-workflow.md`.
- It does not write the checkers, the models or the harness. It says which to reach for.
- It does not replace the existing corpus. It extends it, under the same discipline -- see
  `extending.md` for the mechanical recipe once the plan exists.
