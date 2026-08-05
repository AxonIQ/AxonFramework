# Findings: <slug> @ <UTC timestamp>

**Plan:** <path>
**Framework version under test:** <version, from the history header>
**Connector version, if any:** <version, from the history header>
**Session directory:** <absolute path>

Procedure that produces this document: `references/execution-workflow.md`.

## Summary

One paragraph. **The headline result first** -- "one invariant violation found, reproducible on
the PostgreSQL arm", or "all six scenarios passed at the smoke tier with stated coverage", or
"two scenarios could not run: no container daemon".

## Scenario results

One row per arm. A scenario with several drivers or several backends gets a row each, so verdicts
and evidence do not get conflated. For a decomposed scenario, each arm uses an `S<n>/arm`
identifier and the parent identifier carries the aggregate under the downgrade rule.

| ID | Scenario | Verdict | Backend | Oracle | Oracle execution evidence | Landing evidence | Artefact |
|---|---|---|---|---|---|---|---|
| S1 | <name> | PASS-hardening | in-memory | <property> | "property fired 12,401 times, 0 failures" | not applicable -- no fault | `artifacts/<history>.jsonl` |
| S1 | <name> | FAIL-reproducible | postgres-jpa | <property> | "model replayed 4,217 operations, 1 divergence at rule R3" | proxy API disabled and enabled either side of the cut | `artifacts/<history>.jsonl` |

Verdicts are the labels in `references/verdicts-and-classification.md` section 1: `PASS-smoke`,
`PASS-hardening`, `FAIL-reproducible`, `FAIL-nondeterministic`, `INCONCLUSIVE-env`,
`INCONCLUSIVE-fault-not-proven`, `INCONCLUSIVE-oracle-too-weak`, `PARTIAL-surface`,
`PARTIAL-model`, `NOT-RUN`.

### Verdict vector, per scenario

```
VECTOR <scenario> in-memory:PASS hsqldb-tokens:PASS postgres-jpa:FAIL(1) axonserver:n/a
```

**A finding without a vector is a finding nobody can argue about.** Broken on every backend means
core logic; broken on one means that adapter or that store's semantics; `n/a` on one means the
invariant is inexpressible there and the vector claims no coverage.

## Findings

### F<n>: <short title>

- **Scenario and arm:** S<n>, <backend>
- **Hypothesis addressed:** H<n>
- **Verdict:** <label> -- and the budget tier actually met
- **Blame:** engine | harness | checker | environment -- exactly one. "Unknown, pending a re-run
  on <what you will change>" is an acceptable interim value; a guess that turns out wrong is not
- **Kind:** timing | ordering | partition | crash-recovery | upgrade | config | fault-handling |
  performance. Secondary tags: replication, leadership, idempotency, durability, liveness, safety
- **Severity:** as `formal/FINDINGS.adoc` defines it -- that document owns the scale
- **What happened:** one paragraph, factual, with the checker's own violation message quoted
- **Reproducer:** the pinned **history file** for a contended run, or the seed for a
  single-threaded one, plus the exact command. **Reduced from N faults to k over M re-runs** -- or
  a statement that it was not reduced, and why
- **Evidence:** log excerpts, metric snapshots, the landing evidence, the paths under
  `artifacts/`
- **Hypothesised cause and owner:** one paragraph, naming the subsystem
- **Reporting decision:** does it meet the bar -- a test that fails on unfixed code and passes on
  fixed code? If not, say so plainly. **A finding recorded honestly and not filed is a good
  outcome**
- **Next action:** one sentence

Repeat per finding.

## Coverage summary

Per hypothesis in the plan: which scenarios exercised it and the result. **A hypothesis not
exercised is listed with its reason** -- out of scope, blocked, deferred. Those are the gaps worth
naming.

## Surface coverage -- boundary and fairness scenarios

| Scenario / arm | Surface | Planned | Executed | Verdict | Downgrade reason |
|---|---|---|---|---|---|
| S5/append | the append path | yes | yes | PASS-hardening | -- |
| S5/stream | the streaming path | yes | yes | PASS-smoke | -- |
| S5/admin | the administration path | yes | no | NOT-RUN | no harness for that surface yet |
| S5 | (aggregate) | | | PARTIAL-surface | one unrun arm |

**Surfaces this run did not cover:** name each, with the reason. If every planned surface ran,
render this as one line saying so.

If the plan had no decomposed scenarios, render this whole section as one line: "no boundary or
fairness scenarios in this plan." **Explicit emptiness is the signal.**

## Release-budget disclosures

Every scenario whose release budget was `not provided`, lifted verbatim with its revisit
condition.

| Scenario | Why no release budget | Revisit when |
|---|---|---|
| S5 | no statistical-gate harness exists | the fuzz tier supports a multi-day sweep |

If every scenario declared a concrete release budget, render this as one line saying so.

## Adequacy against the plan

| Claim | What the plan argued | What actually ran | Adequacy after this run |
|---|---|---|---|
| C1 | S1 + S4 + S6 cover threats (a), (b), (c) | S1 pass, S4 pass, S6 inconclusive -- no container daemon | two of three threat dimensions falsifiable; (c) not exercised |

**Any row where what ran differs from what the plan argued means the residual uncertainty is
larger than the plan declared.** Surface those rows, so a reviewer can accept the gap or ask for a
re-run.

## Confidence delta

Against the plan's confidence statement:

- **More:** which claims are better validated than before.
- **Less:** which are less validated than the plan hoped -- what went inconclusive, what new
  uncertainty surfaced.
- **Unchanged:** which this run did not move at all.

This is what a stakeholder reads. Everything above is the evidence trail.

## Green-but-broken audit

Per scenario, all twenty-four rows from `references/green-but-broken-audit.md`, each with its
evidence. **Name the short rows** -- a short row caps what may honestly be claimed.

Red flags:

- [ ] 1. The workload produced the expected commands throughout -- cite the count per seed
- [ ] 2. The oracle really ran -- cite a planted defect that turned this arm red, or the fire
  count
- [ ] 3. Faults verifiably landed -- cite the declared signal, from the perturbed side
- [ ] 4. No fault silently no-opped -- rule out an idle target and an injection at the wrong layer
- [ ] 5. No clock-skew masking -- every interval from one monotonic source
- [ ] 6. The run met the tier claimed -- name the tier
- [ ] 7. No silent error suppression -- the exceptions logged, and that each reached an oracle
- [ ] 8. Recovery completed -- restarted, healed, merged back, quiescence reached
- [ ] 9. The baseline comparison is fair -- re-baselined if the harness changed; both counts stated
- [ ] 10. Statistical claims replicated -- at least three seeds agreeing

Weak-oracle audit -- **any unchecked row makes the arm ineligible for a hardening pass**:

- [ ] Not final state only
- [ ] Not logs only
- [ ] Not health checks only
- [ ] More than one failover exercised, if failover-based
- [ ] Not no-error metrics alone
- [ ] Not short runs alone -- the duration is justified by the tier
- [ ] At least one asymmetric partition variant, if applicable
- [ ] Client-library-hidden retries accounted for, or the scope limit stated
- [ ] Timestamps monotonic, not wall clock
- [ ] More than one topology, if the claim is size-independent
- [ ] At least three seeds for any statistical claim
- [ ] Not a single surface, for a boundary claim
- [ ] Negative controls present, not positive-control only
- [ ] A per-group breakdown, for a fairness claim -- not an aggregate

## Session verdict

**FAIL | DONE | DONE WITH CONCERNS | INCONCLUSIVE | BLOCKED**

Set by the strongest evidence found, not by counting. Partial and unrun arms are concerns, never
clean passes. Rules in `references/execution-workflow.md` section 8.
