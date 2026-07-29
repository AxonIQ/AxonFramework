# Method essentials -- the portable distillation

**This file is self-sufficient.** It carries the load-bearing rules of the distributed-systems
testing method this suite was built with, so that adding a checker, a fault or a new arm needs
nothing outside this repository. For hypothesis generation against a new subsystem, the
companion is `pitfall-catalogue.md`.

Where this suite's own implementation already encodes a rule, the rule is stated with the
suite's vocabulary, because that is the form you will actually need.

---

## 1. Operation-history discipline

**A checker cannot consume what was not recorded.** If a scenario claims to test safety,
durability, idempotency, isolation, ordering or membership, an operation history with a known
schema is the prerequisite. Without one, the strongest verdict a run can produce is "no obvious
regression" -- never "the claim survived the fault". For pure performance, liveness and
operational scenarios the discipline is optional.

### The fields, and what each one is for

This suite's schema is in `formal/INVARIANTS.md` section 4 and is the authority. The mapping
onto the general discipline, with the reason each field exists:

| Discipline field | This suite | Why it is load-bearing |
|---|---|---|
| unique operation id | `id` | Correlates an invocation with its completion. |
| process / session | `process` | Which client, thread or session issued it. |
| invoke timestamp | `logicalTs` on the `INVOKE` record | Monotonic source only. Never wall clock. |
| complete timestamp | `logicalTs` on the `OK`/`FAIL`/`INFO` record | Absent means the operation never completed, which is expressible only because invocation and completion are **separate records**. |
| operation type | `op` (open string set) | Recording a new operation kind must not require editing any existing class. |
| key | `key` | The object addressed: a tag, a segment, an account. |
| input | `value` on the invocation | Arguments. |
| output | `value` on the completion | Results. |
| error | `error` | The error reported. |
| unknown marker | `type = INFO` with `error` set | Timeout, dropped connection, ambiguous commit. |
| node | `node` | Required for any replication, leadership or membership claim. |
| fault window | `faultEpoch` | **The most-skipped and most load-bearing field.** Without it a checker cannot tell whether an anomaly happened under fault, so it cannot attribute one. |

Plus one field the general schema does not have and this one needs: `idx`, a strictly
increasing sequence number that **defines the history's order**. File order does not, because
records are serialized outside the recorder's write lock; the reader sorts by `idx`.

### The rules that make a history checkable

- **Invocation and completion are separate records.** A single record carrying both timestamps
  cannot represent an operation that never completed, which is the one case the whole
  discipline exists for.
- **Three outcomes, not two.** Success, failure, and *unknown*. A timeout or an ambiguous
  commit is `UNKNOWN` and every checker treats it as may-or-may-not-have-happened. Collapsing
  it into failure is a common bug that hides real anomalies; collapsing it into success invents
  them.
- **Never truncate trailing operations.** An operation still in flight when the run ends is
  recorded with its invocation only and resolves to `UNKNOWN`. Run-boundary truncation is the
  single largest source of fake findings in history-checked systems.
- **Never collapse a retry.** Each attempt is its own operation with its own correlation id.
  Detecting the duplicate is the checker's job, not the recorder's. A recorder that emits one
  entry covering a whole retry chain makes exactly-once indistinguishable from at-least-once.
- **Never record an output for an operation whose outcome is unknown.** The checker reads it as
  "we know what happened".
- **Never use wall-clock time for ordering or timing.** Two skewed sources make a correct
  system look inconsistent. Wall clock is for correlating with external evidence only.
- **Record only successes and you have a partial history that yields a meaningless pass.**
- **Anything in the recording path must degrade rather than throw.** A recorder that throws
  takes the operation with it, and the history then describes a system that was never
  exercised.

### Weak history

A history is **weak** if it is missing a completion timestamp for a non-timeout operation, an
unknown marker for an operation that did not return, the fault window when a fault overlapped
the workload, or the node for a replication or membership claim. A weak history produces an
inconclusive verdict on the grounds that the oracle is too weak, not a pass. Fix the recorder
before re-running.

---

## 2. The checker picker

Pick the checker from the property, not from taste. Map your claim onto a model, then take the
row.

| Model | Claim category | Checker shape |
|---|---|---|
| register (single key) | safety | linearizability against a sequential specification |
| log | durability | no-lost-ack, plus replay equivalence |
| map (multi-key) | isolation | serializability per key and across keys |
| session | safety | session consistency / monotonic read |
| ledger | idempotency | dedup on a business idempotency key |
| membership table | membership | reconciliation across replicas at quiescence |
| queue | ordering | prefix / order checker |
| counter | safety | linearizability, or an invariant over final state per key |
| lock / lease | safety | exclusion property, plus an invariant over final state |

If your model is not in the table, take the closest row and write down why the checker still
applies -- or write down that there is no checker and why, which is a legitimate answer and a
better one than a checker that cannot decide.

### The shapes this suite uses, and their failure modes

| Shape | Used for | Fails silently when |
|---|---|---|
| **Cross-implementation differential** against a reference model | the primary oracle for the append protocol | the reference has the same bug -- which is why the model is cross-checked against an independent formal specification over a whole finite domain |
| **Invariant over final state** (a conservation law) | the cheap global oracle | "quiescence" is undefined. Define it as no in-flight operations, no pending background work, and the read side converged, and **verify each separately** before applying the invariant |
| **No-lost-ack** | durability | timeouts are treated as acknowledged. They are not: they are unknown. The checker must ignore them, not decide them either way |
| **Prefix / order** | ordering per sequence key | the legitimate reordering window is not encoded, so in-flight reordering reads as a violation |
| **Exactly-once / dedup** | idempotency | the idempotency key is the operation id, which makes the checker trivially true |
| **Property assertion** | everything else | the assertion never ran because the path was not taken. Cite how many times it fired |
| **Replay equivalence** | crash recovery, idempotent retries | the comparison is too lenient -- it ignores a field it should not |

**Required of every oracle, without exception:** cite the fact that it ran, not just its
verdict ("the property fired 12,401 times, 0 failures"), and **declare its scope** -- say what
it cannot detect. This suite's registry does that in the scenario column, and its
not-applicable channel does it per run.

**And: prove the checker can fail.** Every checker here has synthetic histories with its rule
planted broken. A checker with no demonstrated failure mode is decoration.

---

## 3. Landing evidence

**A fault without landing evidence makes the run inconclusive, never a pass.** A green run
under a fault that never fired has verified nothing, and reporting it as a pass is worse than
reporting nothing.

Evidence must come from **the thing that was perturbed**, not from the harness's intention to
perturb it:

| Fault | Not evidence | Evidence |
|---|---|---|
| network cut | "the proxy was disabled" | the proxy's own API reporting `{"enabled":false}` and `{"enabled":true}` either side of the cut |
| process kill | "kill was called" | the container reporting `exited` with the signalled exit code, plus the store's own recovery line with its own timestamp |
| process freeze | "pause was called" | the container reporting itself paused |
| in-process fault | "the fault was installed" | a fire count taken where the fault actually perturbed something |
| clock change | "the clock was moved" | the recorded jump |

Two refinements this project needed:

- **A primitive that cannot confirm its own effect must report a miss**, and the run is
  inconclusive. Each primitive here verifies its effect from the infrastructure before counting
  a fire.
- **A fault lands when the instruction reaches the system, not when the system agrees.** A
  refused instruction is evidence, and an arm built around a refusal is not a fault that never
  fired.

Also required: **healed and settled before the verdict.** The schedule is warmup, fault
window(s), heal, settle, then judge. A verdict taken during a fault manufactures false
positives at run boundaries. And faults compose in stages -- single faults first, pairs once the
single-fault behaviour is understood, storms only where nobody is reading the result by hand --
because compound-first destroys attribution: something broke and there are four candidate
causes with no way to separate them.

---

## 4. The green-but-broken audit

Run this before declaring any scenario a pass. On this project it found at least one overclaim
in **every** phase, and in one phase four rows came back wrong and would have been written up
as framework findings.

| # | Check | What counts as evidence |
|---|---|---|
| 1 | The workload really ran | Commands issued and committed, per seed |
| 2 | **The oracle really ran** | A planted defect that turned this arm red. That is the only proof of an oracle worth having. |
| 3 | Faults really landed | Per fault: a fire count and the perturbed thing's own report |
| 4 | Faults did not no-op | The fault could not have fired against something idle or irrelevant |
| 5 | No clock-skew masking | Every interval and latency is a difference of two monotonic timestamps |
| 6 | Run duration met the tier claimed | A hardening verdict needs a hardening budget |
| 7 | No silent error suppression | The exceptions the run logged, and that each reached an oracle as a failed operation |
| 8 | Recovery completed | Every crash restarted, every cut healed, every split merged back, quiescence reached |
| 9 | Baseline comparison is fair | Re-baseline whenever the harness changes, and state both counts |
| 10 | One pass is not a pass | At least three seeds with the same verdict, and more than one topology |

**Write down which rows came back short.** A short row caps what may honestly be claimed; it
is usually the difference between a smoke budget and a hardening one, not a defect in the arm.
Quoting a hardening verdict off a run whose audit came back short is the exact overclaim the
audit exists to prevent.

### Weak oracles: signals too weak to prove anything alone

Each of these, **in isolation**, cannot tell a pass from a fail. Pair every one with a real
checker.

- **Final state only** -- misses every transient anomaly the system recovered from.
- **Logs only** -- absence of errors is not presence of correctness.
- **Health checks only** -- liveness, not correctness.
- **A single successful failover** -- a smoke test, not a hardening test.
- **No-error metrics** -- except the system swallowed the errors.
- **Short runs** -- miss what only appears under sustained pressure.
- **Symmetric partitions only** -- real partitions are often one-way.
- **Client libraries that hide retries** -- the in-process history undercounts what the system
  saw.
- **Wall-clock timestamps** -- two skewed sources make a correct system look wrong.
- **One topology** -- a defect that needs five nodes will not surface at three.
- **One seed** -- one seed is one interleaving. Treat any hardening claim built on a single
  seed as partial until at least three seeds agree.

The last two are the rows that have come back short most often here, and they are written into
`HUNT-NOTES.md` per arm rather than left implied.

---

## 5. Deterministic simulation: what it buys and where the seams go

**What it detects well:** race conditions and ordering bugs, retry storms, deadlocks and
livelocks, and anything that only surfaces under a specific interleaving.

**What it misses:** everything outside the simulated boundary -- kernel, driver and disk
behaviour, real latency distributions, real scheduling and resource limits. That is why this
suite has a container tier as well, and why a fault that works in the heap is not assumed to
work across a database round trip.

### Seam rules

- **Prefer an injection point the framework already offers.** Executors, initial tokens, batch
  sizes, claim intervals and extension thresholds are all already injectable here, and none of
  them was invented.
- **Never add a seam to framework code.** The only substitution anywhere in this harness is a
  wrapper around a real storage engine. A suite that modifies what it measures cannot tell you
  whether the release is broken.
- **A process-global seam is not a per-node seam.** Claim expiry here reads a single static
  clock reference for the whole process, so setting "a node's" clock sets every node's. Do not
  assume that because one component takes a clock, the path you care about does.
- **When a seam is unreachable, look for an algebraically equivalent knob.** Expiry is
  `stamp + timeout < now`. A node whose clock reads `delta` ahead evaluates
  `stamp + timeout < now + delta`, which is the same inequality as
  `stamp + (timeout - delta) < now` -- so shortening one node's view of the timeout reproduces
  that node's decisions **exactly**, with no clock substitution at all. State what such an
  emulation does *not* model (here: the timestamps that node writes) everywhere it appears.
- **Probabilistic fault-injection points go in harness wrappers, never in framework code.**
- **Assertions and oracles live inside the simulation as well as outside it.** A property
  asserted only at the end misses everything the system recovered from -- and, measured here,
  a coverage oracle applied only after quiescence would have missed a defect entirely, because
  by then the segments had gone idle and caught up. Measuring at the instant a claim is granted
  is what made it visible.
- **Say what a seed fixes, on both sides of every assertion.** See `hunting-loop.md`; the
  honest scope here is that only the write side of a single-threaded arm reproduces.

### Anti-hang design

Copy it verbatim; it is cheap and it is what stops a wedged run from looking like a slow one:

- a **wall-clock deadline** as the primary bound,
- a **step or command cap** as the secondary bound,
- and a violation object carrying the seed, the fault trace and a **reproduce command**, so a
  report that says a run broke also says how to see it break again.
