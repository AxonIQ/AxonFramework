# The green-but-broken audit -- the non-optional pre-pass checklist

**This file is self-contained.** A scenario that "passed" without these checks completing did
not pass. Run both lists before declaring any scenario a pass, and **write down which rows came
back short**.

On this project the audit found at least one overclaim in **every** phase. In one phase four
rows came back wrong and would have been written up as framework findings.

Two lists, guarding two different failures:

- **Part 1 -- red flags.** Guards against *the test never ran*.
- **Part 2 -- weak oracles.** Guards against *the test ran but the oracle could not tell a pass
  from a fail*.

A short row **caps what may honestly be claimed**. It is usually the difference between a smoke
budget and a hardening one, not a defect in the arm. Quoting a hardening verdict off a run whose
audit came back short is the exact overclaim this audit exists to prevent -- see
`verdicts-and-classification.md` section 1.

---

# Part 1 -- the ten red flags

| # | Check | What counts as evidence |
|---|---|---|
| 1 | **The workload really ran** | Commands issued and committed, per seed, for the expected duration. A generator that silently stopped or rate-collapsed makes the run tell you nothing |
| 2 | **The oracle really ran** | A planted defect that turned **this arm** red. That is the only proof of an oracle worth having. Failing that: the number of times the property fired, or the number of operations the checker consumed |
| 3 | **Faults really landed** | Per fault: a fire count **and** the perturbed thing's own report. "The proxy was disabled" is not evidence; the proxy's own API reporting disabled and then enabled is |
| 4 | **Faults did not no-op** | The fault could not have fired against something idle or irrelevant. Common causes: a rule in the wrong chain, a qdisc on the wrong interface, a restart the orchestrator immediately reversed, an injection at a layer the framework does not read through |
| 5 | **No clock-skew masking** | Every interval and latency is a difference of two **monotonic** timestamps from the same source |
| 6 | **The run met the tier claimed** | The budget tier actually met, named. A hardening verdict needs a hardening budget |
| 7 | **No silent error suppression** | The exceptions the run logged, and evidence that each reached an oracle as a failed operation. Grep the log for what a higher layer swallowed |
| 8 | **Recovery completed** | Every crash restarted, every cut healed, every split merged back, quiescence reached. Not "stayed up in a degraded mode the oracle did not recognise as degraded" |
| 9 | **The baseline comparison is fair** | Re-baseline whenever the harness changes, and state both counts |
| 10 | **One pass is not a pass** | At least three seeds with the same verdict, and more than one topology, for any statistical claim |

---

# Part 2 -- weak oracles

Each of these, **in isolation**, cannot tell a pass from a fail. Pair every one with a real
checker from `oracle-patterns.md`. Any unchecked row means the arm is not eligible for a
hardening verdict.

| Weak signal | Why it is weak | Pair it with |
|---|---|---|
| **Final state only** | Misses every transient anomaly the system recovered from | no-lost-ack (pattern 10), plus in-run assertions |
| **Logs only** | The absence of errors is not the presence of correctness | any real property checker |
| **Health checks only** | Liveness, not correctness | any consistency checker |
| **A single successful failover** | A smoke test, not a hardening test | repeated failover, plus reconciliation across replicas (pattern 13) |
| **No-error metrics** | Except the system swallowed the errors | a client-side history with the unknown marker set, then no-lost-ack |
| **Short runs** | Miss what only appears under sustained pressure -- compaction races, slow leaks, queue overflow | a duration justified by the tier claimed |
| **Symmetric partitions only** | Real partitions are frequently one-way | at least one asymmetric variant -- `fault-catalogue.md`, network table |
| **Client libraries that hide retries** | The in-process history undercounts what the system saw | a second vantage point, or an explicit scope statement -- `history-discipline.md` section 2 |
| **Wall-clock timestamps** | Two skewed sources make a correct system look wrong | a monotonic source per recorder |
| **One topology** | A defect that needs five nodes will not surface at three | at least two topologies, if the claim is size-independent |
| **One seed** | One seed is one interleaving | at least three seeds agreeing, before any hardening claim |
| **A single surface** | A boundary claim tested on one path proves that path | surface decomposition -- `boundary-and-isolation.md` |
| **Positive control only** | "The legitimate reader saw its own data" is not "the other side saw nothing". Asserts half the boundary | explicit negative controls, including the observability paths |
| **An aggregate percentile with no per-group breakdown** | An aggregate meeting a threshold does not prove fairness; one starved group vanishes into it | the per-group formula -- `oracle-patterns.md` section 14 |

**The two rows that have come back short most often here** are *one topology* and *one seed*.
They are written into `HUNT-NOTES.md` per arm rather than left implied.

---

## Recording the audit

Per scenario, record all twenty-four rows with their evidence. The shape:

```
Scenario: <id>            Budget tier met: smoke | hardening | release

Red flags
 1 workload ran            OK    <n> commands committed across <k> seeds
 2 oracle ran              OK    canary C<n> turned this arm red; property fired <n> times
 3 faults landed           OK    proxy API disabled/enabled either side; <n> fires
 4 faults did not no-op    SHORT the store was idle for the first <t> of the window
 5 no clock-skew masking   OK    every interval from one monotonic source
 6 tier met                OK    hardening: <config> x <duration> x <faults> x <seeds>
 7 no error suppression    OK    <n> exceptions logged, all <n> reached an oracle
 8 recovery completed      OK    container restarted, proxy healed, quiescence at <t>
 9 baseline fair           n/a   no baseline comparison in this arm
10 replicated             SHORT one topology only

Weak oracles
 ... one line per row, OK / SHORT / n/a, with the reason
```

`SHORT` rows are the output that matters. Two short rows above cap this arm at a smoke verdict
however clean the oracles were, and **saying so is the point**. An audit with no short rows,
run on a first attempt, is itself a red flag: in this project's history it has meant the audit
was not actually performed.
