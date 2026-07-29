# Claims, hypotheses and scenario specifications

The design-time half of the method: how the claim corpus was produced, how a failure-mode
hypothesis becomes a scenario, and the specification template a scenario must satisfy before it
is implementable.

The corpus itself lives in `docs/testing-plans/axon-hunt.md` -- claims C1-C40 in Appendix A.1
with `file:line` sources and verbatim quotes, gaps M1-M18 in Appendix A.3 with the evidence
searched for each. **Read the corpus before adding to it.** This file is the method, not the
content.

---

## 1. Mining claims

A claim is a **guarantee the product makes**, written so that a run could falsify it.

### The record

| Part | Rule |
|---|---|
| **id** | The next free C-number. The list is append-only and versioned; ids are never reused or renumbered. |
| **claim** | One sentence, falsifiable, in the present tense. If you cannot say what observation would refute it, it is not a claim. |
| **kind** | safety / durability / ordering / membership / semantics / liveness / configuration. The kind picks the checker (see `method-essentials.md`). |
| **confidence** | `documented` (stated in Javadoc or the reference guide) or `code-inferred` (only visible in the implementation; the docs are silent). |
| **source** | `file:line` **plus a short verbatim quote.** The quote is what makes the claim auditable by somebody who does not trust you. |
| **falsified by** | What observation would refute it. This clause becomes the checker's assertion, so write it as something a history could show. |

### The rules that made it work

- **A `code-inferred` claim is weaker evidence AND is itself a documentation finding.** Two
  results from one row: the property to test, and the gap to report. Do not silently promote an
  inferred claim to documented.
- **Never invent support.** If the code contradicts an anchored claim, write it up as
  **REFUTED** or **WEAKER THAN STATED**. That is a first-class result, not a failed claim, and
  several of this project's findings are exactly that: a documented default that the wired
  default does not match, a Javadoc naming one policy where the code wires another, a
  configuration path that inverts two documented values.
- **Never pad to a target count.** A claim added to reach a number is a test nobody can
  justify. Some of the plan's own claim rows say "the plan's anchor name does not exist" and
  give the real one, which is the honest form of a claim that did not survive verification.
- **Anchor to the narrowest thing that is true.** "The claim timeout is ten seconds" was wrong
  until it said "on the token store, not on the processor". A claim wrong in its scope produces
  a scenario that manipulates the wrong number.
- **Verify every default you intend to manipulate**, and record the verified value with its
  source. A whole appendix exists for that, because a scenario built on a remembered default
  measures nothing.

### And the gaps -- which is where the value was

A **gap** (an M-number) is something the documentation simply does not say. Record it with the
**evidence you searched**: the identifiers grepped, the Javadoc read, the reference-guide pages
checked, and the result. A gap with no search record is an assertion that you did not find
something.

Gap dispositions used here, all of them honest outcomes:

| Disposition | Meaning |
|---|---|
| CONFIRMED-GAP | Searched, and the documentation is silent. Highest-value target. |
| CONFIRMED DEFECT | The search found a contradiction rather than a silence. Becomes a finding immediately. |
| ACTUALLY SPECIFIED | The documentation does say it, at a location now recorded. **Promote it to a C-number** and retarget the scenario at verifying the stated contract. |
| PARTIALLY SPECIFIED | Specified for one deployment shape and silent for another. The silent half stays a gap. |
| LARGELY DISSOLVED | The premise turned out to be false for a reason discovered during mining. Restate it in the narrower form that survives, or drop it. |

**The gaps are where the bugs were.** Three of this project's high-severity findings came from
M-numbers -- a skip mechanism nobody documented, a startup race nobody documented, a clock-skew
tolerance nobody stated -- and not from documented promises. A guarantee that is written down
has usually been tested by somebody. The sentence nobody wrote is where the defect lives.

### The hard rule

**A test you cannot tie to a claim or gap number does not get written.** If the property is
real and has no number, add the number first. This is not bureaucracy: it is what stops the
suite growing tests that assert the implementation back at itself.

---

## 2. From pitfall catalogue to hypothesis

Hypotheses are not brainstormed. They are produced by walking a catalogue of
distributed-systems failure modes **against this system**, one row at a time, and recording the
verdict for each -- including the rows that do not apply.

The walk in `docs/testing-plans/axon-hunt.md` section 4 has one row per pitfall with three
columns: the pitfall, whether it applies (`y` / `maybe` / `partial` / `n/a` / `deferred`), and
the hypothesis it produces with the claim numbers it targets.

Rules:

- **Record the rows that do not apply, with the reason.** "No authentication layer in scope",
  "the module is absent from this tree", "deferred, and here is why". A catalogue walk that only
  lists hits is indistinguishable from a list somebody thought up.
- **Every hypothesis names its claims.** A hypothesis with no claim number is a hunch.
- **A `maybe` is a real answer** and stays a `maybe` until something settles it.
- **Reserve a name for a pitfall you cannot target yet.** One invariant name is reserved for a
  module that does not exist in this tree, so that when the module lands the row is already
  there rather than being rediscovered.

### One chain that paid off, end to end

This is the shape to copy.

| Stage | Content |
|---|---|
| **Pitfall** | Sequence-number collision and gap handling in a store whose global index comes from a sequence taken before the transaction commits |
| **Applies?** | Yes -- one shipped storage engine works exactly that way |
| **Hypothesis** | A global-sequence gap is dropped after the gap timeout while a long transaction commits later, so the event is never streamed. Targets claims C13 and C14, and gap M2. |
| **Gap evidence** | The documentation states that timed-out gaps are removed for performance and that gaps may never be filled if those events never commit. **Nothing states the outcome for an event that *does* commit after its gap timed out.** That silence is M2. |
| **Scenario** | `no_event_skipped_by_gap_timeout`: one writer holds a transaction open past the gap timeout while another streams past it; the first commits late. Plus a sibling arm on the configuration path that inverts the two gap settings, because a scenario touching gap behaviour must state which path built the engine. |
| **Result** | Finding **F-16**: committed events never delivered, on both configuration paths, decided rather than excused. The arm also produced **F-17** on the way, because building it required measuring where a delay actually sits relative to the commit it was assumed to precede. |

Read off the two lessons. The hypothesis came from a **silence**, not from a promise. And the
scenario produced a second finding as a by-product of being built honestly -- because the first
version of the arm proved nothing and finding out why was itself a result.

---

## 3. The scenario specification template

Scenarios were first written as one-liners, and **every one of them had to be sharpened
afterwards.** The five things a one-liner leaves implicit are the template.

Fill this in **before** writing the `Scenario` record. A scenario missing any of the five is
not implementable.

```
SCENARIO <id>            a stable snake_case identifier; it goes in the history header
FALSIFIES                the C-numbers and M-numbers this arm tries to break

ORACLE       exactly what is compared to what, at exactly what moment, and which
             registry MachineNames decide it. Name the not-applicable cases too.
WORKLOAD     op mix, key distribution and cardinality, concurrency, batch sizes,
             and the command count per tier
EVIDENCE     what proves each declared fault fired, taken from the thing perturbed
AMBIGUITY    how a timeout, a dropped connection or a mid-commit failure is
             classified, and what resolves it
BUDGET       commands, seeds and wall-clock per tier, and what counts as a pass
```

### What each field must not be

| Field | Not good enough | Good enough |
|---|---|---|
| ORACLE | "the projection converges" | "the balances the projection reports sum to the ledger's opening total after quiescence, decided by `LedgerConservesTotalBalance`; not applicable on a run in which a fault rewrote the store" |
| ORACLE | "OwnershipChecker" | "for every segment, the intervals during which distinct nodes hold its claim overlap by no more than the run's declared skew allowance, derived conservatively: an interval starts when the store answered and expires from when the node asked" |
| WORKLOAD | "an append storm" | "K writers over T tags with skewed tag selection and a declared overlap degree; 70% source-then-append, 20% ORIGIN-anchored append with criteria, 10% unconditional append as a control; batches of 1 to 5 events, seeded" |
| EVIDENCE | "smoke has no faults, so none" -- **this one is fine**, and saying it is the point | "the proxy's own reported state either side of each cut" for a faulted arm |
| AMBIGUITY | silence | "an append future completing with anything other than the store's own rejection exception, or not completing before the phase deadline, is UNKNOWN; resolved only by the post-heal authoritative scan" |
| BUDGET | "a few seeds" | "smoke 300 commands x 3 seeds, 0 violations, wall under 4 minutes; hardening 100k x 100 seeds; no tolerance" |

### Three things the template makes you decide early, which is the point

- **A control arm.** If the claim is conditional ("exactly-once *iff* the resources are
  shared"), it is falsifiable only by running **both** arms. One arm of a conditional claim is
  not coverage of it.
- **Which configuration path built the component.** Where two configuration paths disagree
  about a default, an arm that does not say which one it used has measured an unknown.
- **Whether the arm can reach a pass at all.** If your ORACLE field describes something that
  can never be satisfied on this arm -- an attribution oracle on a run that rebuilds its
  segments, a duplicate oracle on an arm that redelivers by definition -- then the honest form is
  a **measurement** or a **not-applicable** statement, not a note. An arm that can never reach a
  pass can never signal a regression either.

### Budgets and tiers

Three tiers, and the fault-composition limit is the reason they exist: smoke runs at most one
fault at a time, hardening allows pairs, release has no limit. Starting with compound faults
destroys attribution -- something broke and there are four candidate causes with no way to
separate them.

- **A budget is a property of the arm, not of the claim.** The same scenario gets different
  budgets and timings on different stores, and a differential that gives every arm the same
  ones is still comparing the same experiment.
- **Never quote a heap-sized budget at a container** and then report the shortfall as a
  finding. That was tried twice before it was believed.
- **Size the command count against the warmup, not the other way round.** A workload that
  finishes inside the fault warmup opens its window over an idle system.
- **A verdict may not be quoted above the tier that was actually run.** One seed is one
  interleaving; a hardening claim needs at least three seeds agreeing and more than one
  topology.

---

## 4. Coverage bookkeeping, and the entry that matters most

When the scenario lands, three things get updated:

1. **The claim-to-scenario matrix**, so a claim's coverage is a fact rather than an intention.
2. **The invariant registry's scenario column**, including the not-applicable cases, so nobody
   reads a row as coverage it does not have.
3. **The residual list**: what is still uncovered, why, and who owns it.

The third is the one that decays if nobody defends it. Two honest dispositions are in use:

- **accepted residual** -- no module exists in this tree to target, or the cost is not justified
  before a cheaper arm has produced findings. Both forms name the condition under which the
  residual should be revisited.
- **blocked, with evidence** -- an arm that cannot run is recorded with the error that blocks
  it, as a finding of its own, never skipped silently. One arm here spent a phase in that state
  before a harness shim unblocked it.

And keep a **redundancy check**. Some double coverage is deliberate (every safety cluster wants
at least two independent falsification paths); some is accidental and expensive. Both should be
labelled, so a release-tier budget is not spent twice on the same claim.

Finally: **record what shipped, separately from what was planned.** The plan's matrix is
intent. A matrix that records intent and is read as coverage is the same mistake as a green test
that never ran.
