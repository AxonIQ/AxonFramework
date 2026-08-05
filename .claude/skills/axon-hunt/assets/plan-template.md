# Test plan: <slug>

**Date:** <YYYY-MM-DD>
**Plan mode:** change-scoped | project-wide
**System under test:** <name and version>
**Change under test:** <commit or pull request, or "not applicable -- project-wide">
**Author:** <who>
**Status:** draft | reviewed | executed

Procedure that produces this document: `references/plan-workflow.md`.

## 0. Architectural summary

A self-contained one-pager, thirty lines at most, describing the system as it **actually
exists**. A reviewer who has never seen the code should be able to follow the rest of the plan
from this section alone. Cover:

- The major components, one line each, and what each owns.
- The data flow for the canonical request.
- Where state is durable, and which backend holds which kind of state.
- Where any consensus or coordination runs, and by what protocol.
- The trust and tenancy boundaries.
- A picture. Text-art is fine.

This is not the technique catalogue -- that is reference material. This is the deployed system,
written so a reviewer can spot a missing test that a flat scenario list would hide.

## 1. Scope

**Change-scoped.** One paragraph: what the change adds, modifies or removes, and which
subsystems it touches.

Files touched:

- `path` -- what changed
- `path` -- what changed

**Project-wide.** Which slice is in scope -- which modules, surfaces, stores -- and what is
**explicitly out** of scope, with a one-line reason each. A plan that tries to cover everything
covers nothing well.

## 1b. Claims under test

The spine. Every guarantee the system promises, extracted from documentation, reference guide,
Javadoc, error types and existing test names.

Each claim is **one falsifiable sentence**, anchored to `file:line` with a short verbatim quote,
with a kind and a confidence. Kinds: safety, liveness, durability, performance, operational,
idempotency, isolation, ordering, membership, **boundary**, **fairness**.

`isolation` means transactional isolation. **`boundary`** means access-boundary semantics --
tenancy, authorisation, namespace, routing, cross-surface reachability -- and subsumes them, so
they do not appear as separate kinds. **`fairness`** means per-group performance and
noisy-neighbour isolation. Both `boundary` and `fairness` trigger the surface-decomposition block
in section 7.

| ID | Claim | Kind | Source (`file:line`) | Confidence | Falsified by |
|---|---|---|---|---|---|
| C1 | "every committed event is eventually delivered to every subscribed processor" | durability | `path:120` | documented | a history where a committed append never appears in any delivery record |
| C2 | ... | ... | ... | documented / code-inferred | ... |

Every hypothesis in section 4 and every scenario in section 7 references at least one claim by
identifier. An untethered hypothesis produces a ceremonial scenario -- drop it, or add the
missing claim to section 1c.

## 1c. Gaps -- behaviour no claim covers

Behaviour the implementation relies on for which no documented or inferred claim exists. Each row
is a place where documentation and code have drifted, and **historically these produce the best
findings**.

For each: what was searched, and what was **not** found. "Nothing states the outcome for X" is
only credible with the search behind it.

| ID | Behaviour relied on | Evidence searched | Suggested action |
|---|---|---|---|
| M1 | the outcome for an entry that arrives after its own timeout | reference guide, Javadoc of both components, the configuration reference | document the outcome, and add a scenario |
| M2 | ... | ... | ... |

If this section is empty, **say so explicitly**: "documentation and code appear aligned on every
behaviour the plan exercised." Do not omit the section -- explicit emptiness is the signal.

## 2. The system model

One paragraph each:

- **Tenancy and isolation.** How is a boundary enforced, and by which key?
- **Persistence.** What is durable, when, under what contract?
- **Replication or coordination.** Which protocol, which quorum, which ownership?
- **Ordering.** What ordering is exposed to callers, and per what unit?
- **Boundaries.** Which calls cross a process, a network or a store?
- **Retry and idempotency.** What does the caller retry, and what deduplicates?
- **Observability.** What logs, metrics and traces exist that an oracle could consume?

## 3. Existing test inventory -- project-wide only

| Test or harness | Subsystem | Invariant pinned | Failure modes it catches |
|---|---|---|---|
| `Class#method` | append | no duplicate position under retry | retry storm, restart-then-retry |
| `<arm>` | read side | progress monotonicity | crash and re-claim |

Group by subsystem. This is the left column of the coverage matrix.

## 4. Failure-mode hypotheses

Numbered, so scenarios link back. Produced by walking `references/pitfall-catalogue.md` -- both
parts -- **before** generating from intuition.

H1. **<title>** -- <one sentence on what could go wrong>
   - Could falsify: C1, C3, M2
   - Suspected because: <the code path, the prior finding, the pitfall row>
   - Subsystem: <name>
   - Pitfall row: <part and number, or "not in the catalogue -- new">

Cover, at minimum: correctness, durability, liveness, partial failure, idempotency and replay,
upgrade and rollback, configuration, performance and fairness. **If a category is genuinely not
applicable, say so and why** -- writing that sentence surfaces a wrong assumption more often than
it sounds like it would.

### The pitfall walk

One row per catalogue row, including the rows that do not apply.

| Pitfall row | Applies | Hypothesis it produced | Claims and gaps targeted |
|---|---|---|---|
| Part 1, acknowledged-but-lost | y | H1 | C1, M2 |
| Part 2, 8 -- schema migration under traffic | maybe | H7 | C9 |
| Part 2, 11 -- cross-shard atomicity | n/a | -- | the store is single-node in every arm |

## 5. Coverage matrix -- project-wide only

One row per claim and hypothesis pair. Sorted by claim severity times gap, worst first.

| Claim | Hypothesis | Existing tests | Verdict | Gap kind | Scenario |
|---|---|---|---|---|---|
| C1 | H1 | none | not covered | no test | S1 |
| C2 | H2 | `Class#method` | partial | oracle too weak | S2 |
| C3 | H3 | `Class#a`, `Class#b` | covered | -- | -- |

Verdicts: covered / partial / not covered. Gap kinds: no test / shallow test / oracle too weak /
no fault-injection variant / no scale variant.

For fifty or more claims, split into a per-claim summary followed by this per-hypothesis detail.

## 6. Technique selection

Per technique, from `references/technique-catalogue.md`:

- **Hypotheses it addresses:** H1, H3
- **What it catches that the others miss:**
- **Reference:** section <n> of the technique catalogue
- **Cost:**

Two to four techniques is the normal range. One is suspicious.

## 6b. Environment requirements

| Requirement | Version floor | Scenarios that need it | How to get it |
|---|---|---|---|
| container runtime and daemon | any | S4, S6 | brought up by the harness; the daemon must be running |
| the images the container tier pulls | pinned in the workflow | S4, S6 | pull up front, so a pull failure reads as a pull failure |
| JDK | 21 | all | -- |
| a licence for the licensed arm | -- | S8 | out of reach without one; S8 is inconclusive without it |

Name the scenarios per requirement, so a missing one produces a scoped inconclusive rather than a
dead session. Mark what the harness brings up itself.

## 7. Scenarios

Each closes one or more rows above. **Named after the claim it falsifies, never after its
setup.**

### Scenario S1: <claim-shaped name>

- **Falsifies if it fails:** C1, C3 (identifiers from section 1b)
- **Closes:** H1, H2
- **Technique:** <from section 6>
- **ORACLE:** exactly what is compared to what, at exactly what moment
- **WORKLOAD:** operation mix, key distribution, concurrency, batch sizes, command count per tier
- **EVIDENCE:** what proves each declared fault fired, taken from the thing perturbed
- **AMBIGUITY:** how a timeout, a dropped connection or a mid-commit failure is classified
- **Backends:** which registered backends this arm runs against, and which invariants are
  inexpressible on which -- so the vector's `n/a` columns are declared, not discovered
- **Observability required:** what the run must record for the oracle to decide
- **Smoke budget:** the minimum configuration, duration, fault count and seed count for a smoke
  pass
- **Hardening budget:** strictly stronger on every dimension
- **Release budget:** a long, repeated or statistical gate -- **or**
  `not provided -- <reason>. Revisit when: <condition>.` Empty and "TBD" are disallowed
- **Target arm:** the class this becomes, under `simulation/src/test/java/.../hunt/scenario/`
- **Invariants that judge it:** the MachineNames, existing or new. A new one needs a registry row
  first

#### S1 -- model, history and checker

Mandatory if any claim above is of kind safety, durability, idempotency, isolation, ordering or
membership; and **per arm** for any decomposed scenario. Otherwise write "not applicable -- no
gated claim kind falsified" and skip.

- **Model under test:** register | map | queue | log | lock | lease | session | membership table |
  counter | ledger | other(<name>) -- see `references/history-discipline.md` section 5
- **Operation history:** which fields the recorder captures, any extension, and the vantage
  point. "Default schema, in-process" is a complete answer if true
- **Checker:** by name from `references/oracle-patterns.md`. If none, the justification for why
  the alternative oracle is strong enough alone
- **Fault plus landing evidence:** the fault from `references/fault-catalogue.md`, plus the
  observable signal proving it landed. "The injector reported success" is not landing evidence
- **Ambiguous outcomes:** only the deviations from the default
- **Reduction plan:** the minimisation recipe, then the blame classification --
  `references/verdicts-and-classification.md` sections 2 and 3

#### S1 -- surface decomposition

Mandatory if any claim above is of kind `boundary` or `fairness`. Otherwise write "not
applicable" and skip. Fill the matrix in `references/boundary-and-isolation.md` section 2, then
lift it here.

- **Boundary claim:** one sentence
- **Boundary keys:** what scopes it
- **Surfaces:** at least three per boundary claim, or a written justification for fewer
- **Operations:** per surface
- **Adversarial inputs:** from the confusable catalogue -- at least one per class
- **Positive controls:** what legitimate access must still succeed
- **Negative controls:** what illegitimate access must be denied **and** not observable in
  metrics, logs, spans or timing
- **Delayed and asynchronous paths:** background work that runs without the request's context
- **Observability paths:** what could itself leak
- **Arms:** `S1/append`, `S1/stream`, `S1/admin` -- each with its own model block, its own oracle
  and **its own verdict**. Split is mandatory past three surfaces, three claim kinds, or one
  independent oracle
- **Fairness formula, if applicable:** all four thresholds, and the group dimension

### Scenario S2: ...

## 7b. Coverage adequacy argument

| Claim | Threats -- how it could fail | Scenarios exercising them | Why that is sufficient |
|---|---|---|---|
| C1 | (a) a race between two writers, (b) a crash mid-commit, (c) a skipped entry under load | S1 (a), S4 (b), S6 (c) | all three threat dimensions are exercised under the worst-case fault for each; no threat is left without a scenario |

A reviewer must be able to accept each row or point at a specific gap. If a row is hard to fill,
that claim's scenarios are inadequate -- add scenarios, or move the limit to section 7c.

## 7c. Residual uncertainty

| Uncertainty | Why uncovered | Why acceptable today | When to revisit |
|---|---|---|---|
| C12 not exercised under a mixed-version cluster | no upgrade harness exists | every supported deployment upgrades all nodes together | when a rolling upgrade is supported |

If empty, state that explicitly. Do not hide an empty section -- explicit emptiness is the
confidence-builder.

## 7d. Confidence statement

One paragraph, four to eight sentences, in plain language: what a reviewer should believe if
every scenario passes.

**Use conservative phrasing.** "We have not observed X under the tested threats", never "X
cannot happen". No finite suite supports an absolute negative.

**Name the untested surfaces.** If any boundary or fairness claim has an arm expected to be unrun
or partial, the statement must say which. A reader must never come away believing a boundary claim
was fully exercised when one surface carried it.

**Per-aspect confidence** -- required for any boundary or fairness claim, optional otherwise:

| Claim | Aspect | Confidence | Reason |
|---|---|---|---|
| C20 | the append surface | high | S1/append passed at the hardening tier |
| C20 | the administration surface | low | no arm ran |

Levels: high / moderate / low, one line of reason each, citing the scenario or the gap. If every
aspect is high, render the table as one line saying so.

## 8. What this plan does not cover

Whole subsystems or modes declared out of scope on purpose. Distinct from section 7c, which is
about claims the scenarios do not fully exercise.

## 9. Open questions and follow-ups

Long-tail work that should not block the actionable scenarios above.

- <question> -- owner, by when
