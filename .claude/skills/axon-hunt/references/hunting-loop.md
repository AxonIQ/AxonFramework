# The bug-hunting loop, and how to read what it gives you

## The determinism boundary -- read this before pinning anything

Measured, not designed. The measurement is `DeterminismProbe`, which runs the same seed twice
and diffs the two histories; the tables and verbatim output live in `formal/INVARIANTS.md`
section 2 and are the authority. What follows is what a hunter must not get wrong.

| Mode | Reproduces |
|---|---|
| `REAL_THREADS` (the default, and what every shipped scenario runs in) | **Nothing.** Not the record order, not the operation counts, not which appends were accepted. |
| `SINGLE_THREADED` | The **write side** exactly: same append verdicts, same store contents, every time. Nothing else. |
| Four nodes over one store | Nothing, plus a new axis visible directly in the diff: which node wins a segment is not a function of the seed, and neither is how many times a claim is refused. |
| A container-backed store | Never measured, and therefore never claimed. Assume less, not more: the store is reached over a socket and its durable order comes from a sequence taken before the transaction commits. |

A seed fixes which transfers are **attempted**. It fixes nothing about which of them
**commit**, because that is decided by which writer wins a race.

The component that stops `SINGLE_THREADED` being fully deterministic is the streaming
processor: its coordinator and worker executors are injected and the harness supplies
single-threaded ones, which removes concurrency *within* the processor. It cannot remove the
processor's own thread, and a mode that ran the processor on the writer's thread would not be
event processing.

### What that means for pinning

| Asset | Pinned for | What it guarantees |
|---|---|---|
| A **seed** | `SINGLE_THREADED` arms only | The same append verdicts and store contents, every run. The arm asserts its own determinism mode, so the pin cannot be quietly moved onto a contended arm. |
| A **history file** | `REAL_THREADS` runs | The same verdict from the same file, for ever and on any machine, because every checker is a pure function of the history. |

**Never tell anyone that a failing seed reproduces a `REAL_THREADS` failure.** It does not.
Re-running the seed re-runs the same workload shape against a new schedule and may well be
green.

**And state what a pinned history does not do.** It is a fixed record of a run that already
happened, so replaying it can never notice a *new* defect: change the engine however you like
and the file's verdict does not move. A pinned history guards the **oracles** -- it goes red
if a checker is weakened, deleted or unregistered, which is exactly how a suite silently
stops looking. The **engine** is guarded by the live arms and by nothing else.

## The loop

### 1. Hunt, read-only

Cheapest first:

- Run the gate. A red gate on a change is the highest-signal thing there is.
- Point an existing scenario at another backend (`Scenario.onBackend(name)`), which costs a
  declaration and buys a verdict vector.
- Run the seed sweep at a start seed nobody has used.
- Widen a topology or a seed count on an arm whose audit row came back short -- those rows
  are written down in `HUNT-NOTES.md` precisely so somebody can close them.

Do not patch framework code. Do not add a seam to framework code. The only substitution
anywhere in the harness is a wrapper around a real storage engine.

### 2. Triage: is it known, and is it even the engine?

In order, because each step is cheaper than the next:

1. **Is it in `formal/FINDINGS.adoc`?** Several arms are red or inconclusive on purpose, and
   the registry's scenario column says which. Check before writing anything up.
2. **Is it the harness?** Assume so first. On this project the first run of every new arm has
   been mostly harness: one phase's audit caught four harness defects in a single pass, and
   three of the four would have been written up as framework findings. `references/traps.md`
   has them by shape so you can recognise yours.
3. **Is the oracle even in a position to decide?** A note is not a violation. A
   not-applicable statement is not a failure. Read which channel the message came out of.
4. **Did the declared fault land?** A run with no landing evidence has verified nothing, and
   the run reports itself `INCONCLUSIVE` for exactly that reason.

### 3. Reproduce

- **Get a per-backend vector** before arguing about attribution. One command; see
  `running.md`.
- **Attach the history file.** It is the only exact record. The violation message already
  carries the reproduce command and the seed.
- If the arm is `SINGLE_THREADED`, the seed reproduces the write side and you can iterate on
  it directly.
- If it is contended, iterate on the **history**: `HistoryReplayTest` re-judges the same file
  offline, in milliseconds, with the whole checker set.

### 4. Verify yourself

Nothing here is trusted because it looked right the first time.

- **Make the thing you believe is the cause, and check the verdict moves.** A differential
  that has never been shown to fail is indistinguishable from one that compares nothing. The
  reference-model differential was mutation-checked this way (perturbing the conflict scan by
  one position turned 5 of 12 seeds red), and so was the model-to-model cross-check (swapping
  two boundaries in one pool turned 86 of 960 cases into disagreements).
- **Check the arm is not vacuous.** Did both verdicts occur? Did the fault fire? Did the read
  side settle? Did the oracle you are relying on report anything at all, on any run?
- **Run the green-but-broken audit** before claiming a pass. The checklist is in
  `method-essentials.md`. On this project it found an overclaim in every single phase.

### 5. Pin or reject

**Pin a real finding** with all four of:

1. A `FINDINGS.adoc` entry: what is wrong, severity, evidence, per-backend vector, candidate
   fix, reproduce command, and how it was found. A finding discovered *by reading* is
   legitimate and is labelled as such, because that is weaker evidence than one a failing test
   produced and saying so is the point of the label.
2. An **expected-gap test**: it passes while the gap exists and flips red when the gap is
   closed. Assert the neighbouring guarantee alongside it, so the finding cannot be misread as
   something worse than it is.
3. A regression asset: a pinned seed for a single-threaded arm, a pinned history for a
   contended one.
4. If the registry row's statement no longer holds as written, an honest "holds?" note on the
   row. Do not silently reword the invariant to match the engine.

**Reject it plainly.** Say what it actually was. If it was the harness, fix the harness, pin
the fix, and add the shape to `HUNT-NOTES.md` -- the next agent will hit the same class of
thing.

## Reading a result

### The three-valued verdict

| Verdict | Means |
|---|---|
| `PASS` | Every required oracle ran, every declared fault landed, the read side settled, and nothing was found broken. |
| `FAIL` | At least one invariant was found broken. |
| `INCONCLUSIVE` | Nothing was found broken and the run cannot be called a pass: a declared fault never fired, an operation's outcome is unknown, the read side never caught up, a required oracle is not registered, or the run outlived its budget. |

The third value exists because a distributed system returns three outcomes and so does a test
of one. Collapsing it into either of the others reports confidence nobody earned.

### The four channels a checker speaks through

| Channel | Moves the verdict? | Means |
|---|---|---|
| violation | yes, to `FAIL` | An invariant was found broken. |
| note | yes, to `INCONCLUSIVE` | Something stopped the checker deciding. |
| measurement | no | A fact the run produced which the history fully accounts for. The framework's behaviour explains it and the checker checked it, so the verdict stands and the number is printed. |
| not applicable | no | An invariant this run cannot express at all, named. Reporting it as a note says the run tried and failed; reporting nothing says it passed. Neither is true. |

The two verdict-neutral channels were added because of a measurement, not a preference: two
arms reported `INCONCLUSIVE` on every seed of every run, one because a reset redelivers by
definition and one because an attribution oracle cannot be judged across a segment-set
rebuild. **An arm that can never reach a pass can never signal a regression either**, which
makes it exactly as inert as one that always passes.

**So: a permanently inconclusive scenario and a permanently red scenario are both bugs in the
arm.** Fix the arm's decidability, or move the claim to a measurement, or delete the arm.
Never leave it as decoration.

The situations in which a checker must decline rather than decide are enumerated in
`formal/INVARIANTS.md` section 3 ("When a checker must not decide"). Read that table before
widening any tolerance. **The honest default when a scenario perturbs something new is to stop
deciding, not to widen a tolerance** -- that has come up five separate times on this project,
and each time the widening would have hidden something.

### Attribution, via the vector

```
VECTOR <scenario> in-memory:PASS hsqldb-tokens:PASS postgres-jpa:FAIL(1 n/a) postgres-jpa-split-tokens:FAIL(1 n/a)
```

| Shape | Attribution |
|---|---|
| broken on every backend | core framework logic |
| broken on one backend | that adapter, or that store's own semantics |
| `n/a` on one backend | the invariant is inexpressible there; the vector claims no coverage for it |

Reading the example: the `n/a` on each PostgreSQL arm is the reference model against a store
that does not implement the boundary protocol. A column that is `n/a` is not a column that
passed.

The vector is also how a fault gets attributed. A fault that works in the heap and silently
does nothing across a database round trip would turn one column into a fault-free control
that reads as coverage, which is why the matrix asserts that a run whose declared fault never
fired may not pass, and prints the fire counts either way.

### Flake classification, under zero quarantine

Every intermittent failure is exactly one of three things. There is no fourth option, no
`@Disabled`, no tag used to hide it, and no rerun count.

| Class | How to establish it | What it costs you |
|---|---|---|
| **Engine bug** | It reproduces in isolation, or its history replays to the same verdict | A `FINDINGS.adoc` entry and an expected-gap test |
| **Harness bug** | Reading the history explains the violation as something the harness did | A fix, plus a regression pin so it cannot come back |
| **Load artifact** | It passes in isolation, and you have the evidence that it does | A written record with that evidence attached |

Quarantine lists are where these suites go to die, and reruns bury precisely the
flaky-looking real bugs the suite exists to find.

One more rule that came out of practice: **refuse to ship a test that is red on a clean
engine**, even when it would pin a real finding. Green-on-clean has to mean *always* green on
clean, not usually. An assertion measured green on one arm's two seeds and red on a sibling
arm's two seeds of the same clean engine is a flaky test, not an oracle. Write the behaviour
up as an intermittent finding instead, where an intermittent result can be reported as
intermittent.

## Symptoms and their usual causes

Every row here was diagnosed at least once on this project. Check the row before opening an
investigation.

| Symptom | Look here first |
|---|---|
| Mass redelivery, token regressions and a conservation violation on a **single-node** run against a real store | The claim timeout. Compressed timings do not survive a network round trip; the owner's extension is still in flight when its own claim lapses. |
| Every oracle declines; the run says the read side never caught up | How quiescence is measured. It must be decided against the **store's own answer**, and the scan that decides quiescence must be the scan the oracles are judged against. |
| A run reports itself quiesced with zero readable events and every oracle holds | The authoritative scan is failing and its refusal is being swallowed. `containsAll(empty)` is true. |
| Committed events occasionally never delivered, on a real store, with no fault | A known finding before it is anything else: an event whose transaction commits later than the gap timeout after its own message timestamp can be skipped for ever. Check `FINDINGS.adoc` for it, and check **which configuration path built the engine** -- two paths disagree about the two gap settings, and that is a second finding. Reproduce with the gap arm and its Spring-defaults sibling; get the vector before attributing anything. |
| A committed event never delivered, on a run that split or merged a segment | The framework blocks re-claim of a splitting segment for a hardcoded minute, which no timescale compresses. A blocked segment looks exactly like an abandoned one. |
| A fault reports itself as never fired | It may be a badly sized scenario rather than a broken fault: if the workload finishes inside the warmup, the window opens over an idle system. Size the command count against the warmup. |
| A node-level fault fires and nothing changes hands | It was aimed by index and landed on a node holding no segments. Aim at the busiest node. |
| Four nodes start and only one attempts the segment initialisation | They were started in a loop. A sequential stream is not concurrent; release them from a barrier. |
| A cluster's segments all live on one node | Nothing spreads segments by default. Uncapped, the first coordinator to reach the store claims everything and the rest idle. |
| A checker reports a regression of hundreds of positions | An outcome read from the wrong thing: the outcome of a *call* is not the outcome of the *transaction* that would have committed it. |
| A delay installed "before the commit" changes nothing | Measure where the delay actually sits. On a transaction-managed store the append transaction's commit and the database transaction's commit are registered in the same lifecycle phase with no ordering between them. |
| A build stops with no output at all against a container | A relation lock, not a hang. Surefire buffers output until the method ends. Ask the database what it is waiting on. |
