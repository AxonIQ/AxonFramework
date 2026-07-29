# Extending the suite to a subsystem with no coverage

The corpus covers the event store, the append protocol, the streaming processor's tokens,
segments and delivery, and two real stores. Several surfaces of the framework have **no coverage
at all**. This is the end-to-end recipe for adding one, and the list of what is currently
uncovered.

The recipe is not new method: it is the design pipeline from `claims-and-scenarios.md` plus the
mechanical recipes from `recipes.md`, in order, with the bookkeeping that stops the result being
read as more than it is.

---

## The recipe

### Step 0 -- orient before reading code

If a prebuilt knowledge graph is available (the optional `graphify` tool, with its output at
`graphify-out/`), use it before grepping: `graphify query "<question>"` to orient in the
subsystem, `graphify path "<A>" "<B>"` to find the wiring between two components, and
`graphify explain "<Class>"` for a single class's role. Then open the cited `file:line`, because
the graph orients and the source decides. Without it, grep -- starting from the checker classes
named in the invariant registry and the `file:line` anchors in the claim corpus, which between
them name most of the framework surface the suite touches.

Then read the subsystem's own tests. This repository's existing suites are shared abstract
classes that new backends extend, and their existing concurrent-append races are usable as
workload seeds. Reuse before you build: the point of extending the suite is coverage the existing
tests do not have, and you cannot tell what that is until you know what they do.

### Step 1 -- run the claims pass over the subsystem

Method and record format: `claims-and-scenarios.md` section 1.

- Mine the Javadoc and the reference guide for **documented** guarantees.
- Mine the implementation for **code-inferred** ones, remembering that each is also a
  documentation finding.
- Verify every default you intend to manipulate, and record it with its source.
- Append new claim numbers to `docs/testing-plans/axon-hunt.md` Appendix A.1. The list is
  append-only; ids are never reused.
- Anything anchored that the code contradicts is written up as REFUTED or WEAKER THAN STATED --
  a result, not a failure.

### Step 2 -- record the gaps, with the evidence you searched

Method: `claims-and-scenarios.md` section 1, "And the gaps". This is where the yield is. For each
question the documentation does not answer, record the identifiers you grepped, the Javadoc you
read, and the disposition (confirmed gap / confirmed defect / actually specified / partially
specified / dissolved). Append M-numbers.

### Step 3 -- walk the pitfall catalogue against the subsystem

Method: `claims-and-scenarios.md` section 2. One row per failure mode, **including the rows that
do not apply and why.** Each hypothesis names the claim and gap numbers it targets.

The catalogue to walk is `pitfall-catalogue.md`, organised by claim kind with this suite's own
findings as concrete instances of each row. The walk this suite already did -- per-row verdicts
and the hypotheses each hit produced -- is tabulated in `docs/testing-plans/axon-hunt.md`
section 4; extend that table, and end with what the new subsystem introduces that no row covers.

### Step 4 -- write scenario specifications, not scenarios

Fill in the five-field template (ORACLE, WORKLOAD, EVIDENCE, AMBIGUITY, BUDGET) for each
hypothesis worth an arm. `claims-and-scenarios.md` section 3. **A scenario missing any of the
five is not implementable**, and writing the five is what surfaces the control arm, the
configuration path and the decidability question early rather than after the arm is built.

### Step 5 -- decide which existing invariants already judge it

Read `formal/INVARIANTS.md` section 3 and ask, per row: does this apply to the new subsystem as
written?

- **It applies unchanged.** Nothing to do. The checker already runs against every history, so
  the moment your workload emits the operations it reads, you have coverage.
- **It applies but the new arm cannot express it.** Add the not-applicable case to the row, with
  its mechanism, and make the checker report it. Reporting it as a note says the run tried and
  failed; reporting nothing says it passed. Neither is true.
- **The property is new.** Add a registry row and a checker (`recipes.md` recipe 1). One checker
  may enforce several invariants.

Most of what a new subsystem needs is **recording**, not checking. If the invariant needs an
operation the recorder does not emit, add a constant to the operations list and record it --
that changes no existing code path, because the operation name is an open string set.

### Step 6 -- emit the history

This is the whole integration cost. Anything that can emit the history schema gets the full
oracle set for free: the checkers, the three-valued verdict, the offline replay, the reproduce
command, the per-backend vector.

The emitting side is `HistoryRecorder`, the reading side is `HistoryView`, and the operation names
in use are constants on `HistoryOps`, which is explicitly non-exhaustive. The schema is
`formal/INVARIANTS.md` section 4 and it is the contract: fields are added, never repurposed.

Read `method-essentials.md` section 1 before writing a single record, and get these right or the
history is weak and the strongest available verdict is "no obvious regression":

- separate invocation and completion records, correlated by identifier;
- a third record type for an outcome that is genuinely unknown, never collapsed;
- the fault window on every record;
- the node on every record, if any claim is about replication or membership;
- one monotonic time source, and wall clock only for external correlation;
- retries never collapsed;
- and **degrade rather than throw** anywhere in the recording path.

### Step 7 -- plant a bug the new oracle should catch

`recipes.md` recipe 6. Apply one mutation to the framework code the new arm exercises, run the
**whole** suite, record which arms went red and which stayed green and why, then revert. Verify
the revert gate prints nothing.

**A new oracle that has not been shown to go red is decoration.** If the mutation escapes, that
is a real gap: write it up next to what would catch it, and either close it or file it with the
oracle it needs.

### Step 8 -- record the coverage, and the residual, honestly

Three updates, and the third is the one that decays if nobody defends it:

1. The claim-to-scenario matrix, so coverage is a fact rather than an intention.
2. The invariant registry's scenario column, including the not-applicable cases.
3. **The residual list**: what is still uncovered, why, and who owns it -- with the condition
   under which it should be revisited.

Then run the green-but-broken audit (`method-essentials.md` section 4) and **write down which
rows came back short.** A short row caps what may honestly be claimed and is usually the
difference between a smoke budget and a hardening one. And append to `HUNT-NOTES.md` whatever
cost you an hour.

---

## What is currently uncovered

Named so that the next agent knows where to start, rather than rediscovering the map. Each row's
authority is the document named; check it before starting, because these move.

### Surfaces of the framework with no coverage at all

| Surface | Why there is none, and what it would take |
|---|---|
| **The query side**: queries, subscription queries | Declared out of scope for this iteration and exercised only as a workload. It is a message type with its own bus, registry and handler contract, so it needs its own claims pass -- the existing invariants are all about appends, tokens and deliveries and none of them applies unchanged. |
| **Dead letter queues** | The module is absent from this tree. One invariant name is reserved so the row exists when the module lands; nothing else. |
| **Process managers / sagas** | Absent. They are the pattern most likely to produce interesting membership and idempotency claims when they arrive. |
| **Message transformation / schema evolution** | Named as an open follow-up. It is the classic upgrade-shaped failure and there is no arm for it. |
| **Upgrade and rollback across framework versions** | Needs two framework versions in one harness; out of scope for this iteration and named as such. |
| **Distributed messaging over the server connector** | The *event store* half runs: the Axon Server arm links a released connector against this reactor with one method shimmed by the harness, recorded in `formal/CONNECTOR-COMPATIBILITY.md` (the linkage break itself is a finding). Distributed command/query routing over the connector has no coverage at all. |
| **Two-phase / XA resource managers** | An accepted residual: it needs an XA transaction manager and two resource managers in the harness, and the cost is not justified before the single-resource and split-resource arms have produced findings. |

### Things the suite has built but never exercised in a scenario

These are cheaper than a new subsystem and are the highest-yield starting points.

| Gap | State |
|---|---|
| **The process-freeze primitive** | Built and verified by hand against a container; **no scenario declares one**. The whole class of failure a kill cannot produce is unexercised. It belongs with a cluster rather than with a single node. |
| **A transactional read model, and with it a real exactly-once arm** | No shipped deployment shares a transactional resource between the token store and a read model, so no arm declares exactly-once and half of one invariant has only ever run against synthetic histories. The right oracle is an applied-count per event identifier written in the same transaction. |
| **Faults against a real store shared by a cluster** | Every infrastructure fault has only ever run against a single node. A kill, a cut or a freeze against a store shared by a cluster is a different failure: the one where nodes disagree about whether the store is there. |
| **A realistic-timescale arm that is actually run** | The timescale exists and is selectable; nothing declares it. |
| **Gap cleaning as a mechanism distinct from gap suppression** | Only half the gap machinery is driven. |
| **Any tier above smoke** | Every mutation campaign and almost every arm has run at the smoke tier with a fixed seed set. Nothing anywhere says how many seeds a subtler defect would need. |

### Oracles never shown able to fail

From `formal/CANARIES.md`, which is the authority and has more:

- A mutation of the split or merge algebra. The membership scenarios exist; nothing has been
  planted against them.
- The sequencing-policy path.
- Attributing a store perturbation to the fault that caused it -- one store-perturbing fault
  currently suppresses duplicate judgement for every repeat in that run, including ones it could
  not have caused.
- Anything that would catch a fault firing against the **wrong thing**. A fault that stopped
  firing is caught by the landing-evidence rule; one that fired against the wrong target is not.

### Open mechanisms

One finding's mechanism on a clean engine is still open, and settling it needs the reader's token
instrumented rather than inferred. `formal/FINDINGS.adoc` names it and says exactly what is
established and what is not. That is the single most valuable open question in the set, because
it is a real skip on a real store whose cause is not yet fully explained.

---

## What "done" looks like for an extension

All six, or the extension is not finished:

1. New claim and gap numbers in the plan, with sources and search evidence.
2. Scenario specifications with all five fields filled in.
3. Registry rows for any new invariant, worded identically in the registry, the checker and the
   violation.
4. A canary that shows the new oracle can go red, applied and reverted, recorded in
   `CANARIES.md`.
5. A green-but-broken audit with its short rows written down.
6. The residual list updated, with owners, and `HUNT-NOTES.md` appended.

And the gates: `git status --porcelain -- messaging eventsourcing modelling common conversion
extensions test integrationtests` prints nothing, the module is green with its test count taken
after removing the whole `target` directory, and the ASCII check prints nothing.
