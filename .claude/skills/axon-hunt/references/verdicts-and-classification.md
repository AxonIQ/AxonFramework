# Verdicts and classification -- three orthogonal axes

**This file is self-contained.** It sharpens two things the three-valued verdict leaves
implicit: *why* a run was inconclusive, and *what* a finding is once it exists.

The suite's runtime verdict stays three-valued -- `PASS`, `FAIL`, `INCONCLUSIVE` -- because
that is what a checker channel can decide (`INVARIANTS.md` section 2). Everything below is a
**label on top of that verdict**, written into the write-up and into `FINDINGS.adoc`. None of
it changes harness code.

Three axes, and a finding needs all three. They are independent, and conflating any two is
how a report sends a defect to the wrong queue:

| Axis | Question | Values |
|---|---|---|
| **Verdict** | What did the run decide, and at what budget? | below, section 1 |
| **Blame** | Which component holds the defect? | below, section 3 |
| **Kind** | What class of bug is it? | below, section 4 |

---

## 1. Verdict, with its reason

`PASS` and `FAIL` are not one value each, and `INCONCLUSIVE` is not one state.

### PASS is gated by the budget, not by the oracle

An arm that ran the smoke budget produces at most **`PASS-smoke`** whatever the oracles say.
**`PASS-hardening`** additionally requires the tier's declared configuration, duration, seed
count and fault count -- all of them, from the scenario's BUDGET field.

`PASS-smoke` is evidence that the harness works. It is not evidence that the framework
honours the claim under fault. Quoting a hardening conclusion off a smoke run is the exact
overclaim the green-but-broken audit exists to catch (`method-essentials.md` section 4).

### FAIL splits on whether there is a reproducer

| Label | Meaning | What to do |
|---|---|---|
| **`FAIL-reproducible`** | Re-runs on the same history, or on the same seed under `SINGLE_THREADED` | Reduce it (section 2), classify blame, then decide against the reporting bar in `SKILL.md` |
| **`FAIL-nondeterministic`** | Observed once; a re-run with identical inputs did not show it | Pin the **history file**, never the seed. Write it up as intermittent. Never file it as though a reproducer exists |

Under `REAL_THREADS` almost every first observation is `FAIL-nondeterministic` until a history
is pinned. The history is what converts it -- see `hunting-loop.md`.

### INCONCLUSIVE splits four ways, and the split is the fix list

`INCONCLUSIVE` is the most under-read verdict in the suite: three of these four are **harness
work**, not framework findings, and each has a different repair.

| Label | The run could not decide because | Fix |
|---|---|---|
| **`INCONCLUSIVE-env`** | A required capability was absent: no Docker daemon, no image, no driver, `timeout` missing on macOS, no license for a licensed arm | Install or substitute. The gap is between the arm and the machine, not the framework |
| **`INCONCLUSIVE-fault-not-proven`** | A fault was scheduled and the injector reported success, but the perturbed thing's own evidence is absent or ambiguous | The arm did not test what it claimed. The oracle result is irrelevant either way. See `method-essentials.md` section 3 |
| **`INCONCLUSIVE-oracle-too-weak`** | The workload ran and the faults landed, but the checker cannot separate pass from fail on what was recorded | Usually a **weak history** -- a missing `faultEpoch`, a missing unknown marker, a missing `node`. Fix the recorder, then re-run. Second cause: a checker mismatched to the model; re-pick from the checker table |
| **`NOT-RUN`** | The arm is declared and was never attempted -- out of session time, gated behind a container tier, harness not built yet | Nothing is wrong. Name the arm in the write-up so it does not fold silently into the aggregate |

`NOT-RUN` and `INCONCLUSIVE-env` are not the same claim. `NOT-RUN` is "we did not try";
`INCONCLUSIVE-env` is "we tried and the machine could not".

### Partial coverage caps the aggregate

Two labels for an arm that ran and decided less than it looks like it decided:

- **`PARTIAL-surface`** -- the surface checks were clean (no exceptions, recovery completed,
  final state looks right) but the arm's declared model-level checker never ran. Quotable as
  "no obvious regression". Never quotable as evidence about the claim.
- **`PARTIAL-model`** -- the checker ran, on a subset of what the workload produced. Say
  which subset, so a reader can judge whether the unchecked part matters. Stronger than
  `PARTIAL-surface`; still not hardening.

`PARTIAL-model` is what our not-applicable channel produces when it fires: an invariant the
run could not express, named. That is honest, and it is also a cap.

**The downgrade rule.** Any `NOT-RUN` or `PARTIAL-*` arm caps the whole scenario at
`PARTIAL-surface`, regardless of how many sibling arms passed. A multi-arm scenario reports
a verdict per arm and the capped aggregate, never the aggregate alone -- otherwise an
untested surface folds into a pass. This is what makes the per-backend vector readable:
`n/a` on one backend is a cap on that column, not a pass.

### Assigning it, at run end

```
Was the arm attempted at all?
  no  -> NOT-RUN
  yes -> was a required capability missing before the workload started?
           yes -> INCONCLUSIVE-env
           no  -> did any checker report a violation?
                    yes -> re-runs on the same history or seed?
                             yes -> FAIL-reproducible
                             no  -> FAIL-nondeterministic
                    no  -> was every declared fault proven landed?
                             no, and faults were declared -> INCONCLUSIVE-fault-not-proven
                             no faults declared (smoke)   -> continue
                             yes                          -> continue
                                 did the declared checker run?
                                   no  -> PARTIAL-surface
                                   yes -> did the history carry the fields it needs?
                                            no  -> INCONCLUSIVE-oracle-too-weak
                                            yes -> did it cover every op the workload made?
                                                     no  -> PARTIAL-model
                                                     yes -> fault active?
                                                              no  -> PASS-smoke
                                                              yes -> PASS-hardening
```

**A permanently `INCONCLUSIVE` arm is as worthless as one that always passes** -- it can
never signal a regression. Treat it as a bug in the arm and give it one of the four labels
above, which names the repair.

---

## 2. Reduce before filing

A finding that needs a full contended run with four faults is hard for the owner to act on.
Reduce it to the smallest fault sequence that still reproduces.

**When:** always, for a `FAIL-reproducible` you intend to file. **Never** for an
`INCONCLUSIVE` -- fix the inconclusiveness first, or you are reducing noise.

1. Start from the recorded history: every fault, every operation, in `idx` order.
2. **Bisect by halves.** Drop the first half of the fault schedule. Still reproduces? Recurse
   on the second half. Does not reproduce? Keep the first half and recurse there.
3. **Then drop one at a time** from what survives. Leave out anything the reproducer does not
   need.
4. **Stop** when removing any single remaining element makes it stop reproducing.
5. **Record the budget** in the finding: "reduced from N faults to k over M re-runs".

Two ways to get this wrong:

- **Over-reducing.** Reduction can drop something causally required that happens not to
  change the outcome on this interleaving. Keep at least one element of each fault category
  the defect depends on.
- **Reducing the unreducible.** If the defect needs an interleaving that does not appear on
  every run, stop. Record what you tried, label it `FAIL-nondeterministic`, and either pin the
  history as-is or move the arm to `SINGLE_THREADED` where the write side does reproduce.

---

## 3. Blame -- which component holds the defect

A reduced reproducer is not yet a bug report. The same reproducer means four different things
depending on what is actually broken, and three of the four are our work, not the framework's.

**In this project's history the first run of a new arm has been the harness nearly every
time.** Assume harness until the tells say otherwise.

| Blame | Tells |
|---|---|
| **Engine** | Survives swapping the workload for a different one over the same API. Reproduces on more than one backend, or the vector attributes it to one adapter for a reason you can name. Matches a pitfall row |
| **Harness** | Disappears when the workload is replaced with another over the same API. The recorded operations are ones the framework API does not actually accept, or are issued in an order no real client produces. A fault fired against something idle |
| **Checker** | The "violation" makes sense only if you deny a relaxation the framework documents -- a licensed redelivery window, a merge rewind, an at-least-once guarantee read as exactly-once. Re-running the checker over a known-good pinned history also flags it |
| **Environment** | Disappears on another machine, JDK, container runtime or filesystem. Timing lines up with a host event -- a Docker pause, an NTP step, a CPU throttle |

Write it as `Blame: engine | harness | checker | environment`. A blame you are not sure of is
`Blame: unknown -- pending re-run on <the thing you will change>`. That is honest; a guess
that turns out wrong costs the owner a triage cycle and teaches them to discount the next one.

**A harness or checker defect is fixed and pinned, not filed.** That is the zero-quarantine
rule doing its job: the fix carries a case that would have caught it.

---

## 4. Kind -- what class of bug it is

One primary category, after the TaxDC taxonomy (Leesatapornwongsa et al., ASPLOS'16). It is
what makes findings searchable across runs and comparable with published corpora.

| Kind | In this framework, typically |
|---|---|
| **Timing** | Anything depending on relative timing: a gap timeout, a claim expiry, a coordinator re-poll |
| **Ordering** | Event order, sequencing policy, per-key delivery order across a split or merge |
| **Partition** | Full, partial or one-way loss of connectivity to a store or a peer |
| **Crash-recovery** | State after a process death and restart: tokens, snapshots, in-flight appends |
| **Upgrade** | Mixed-version cluster, a schema migration, a connector against a different framework version |
| **Config** | A default that differs between configuration paths, or a hardcoded duration |
| **Fault-handling** | A defect **in** the error path itself. Empirically the largest category (Yuan et al., OSDI'14) and the one happy-path tests never reach |
| **Performance** | Correct but slow: tail latency, head-of-line blocking, throughput collapse |

Secondary tags, any that apply: replication, leadership, idempotency, durability, liveness,
safety.

Severity stays as `FINDINGS.adoc` defines it -- critical / high / medium / low -- and that
document owns the scale. Kind is orthogonal to severity: a `Config` bug can be critical and a
`Safety` tag is critical by default.

---

## What a finding's classification block looks like

```
Verdict:  FAIL-reproducible (postgres-jpa), PASS-smoke (in-memory)
Blame:    engine
Kind:     Timing; tags: durability, liveness
Reduced:  from 4 faults to 1 over 6 re-runs
```

Four lines, and they answer: what the run decided and at what budget, whose queue it belongs
in, what it is comparable to, and how small the reproducer got. A finding missing any of them
is one somebody else has to re-derive.
