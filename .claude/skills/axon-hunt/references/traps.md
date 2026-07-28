# The traps this project actually hit

Read this before writing up any divergence as a finding. Every item here happened, was
diagnosed, and cost time. They are grouped by what they teach, not by when they happened.

---

## 1. Oracles that pass vacuously

The worst failure mode in this suite is not a red test. It is a green one that never looked.

- **An ownership assertion against a store with no ownership.** The framework's in-heap token
  store has no owner field, no timestamp and no expiry; releasing a claim is a no-op, fetching
  a token never fails on ownership, and the available-segments query equals the all-segments
  query. Any assertion about claim semantics made against it **passes without checking
  anything**, which is worse than having no test because it reads as coverage. The backend
  therefore declares `arbitratesTokenClaims()`, and the ownership invariant reports itself
  **not applicable** rather than passing.
- **One coordinator taking every segment.** Nothing spreads segments across nodes by default:
  the segment cap defaults to a number larger than any real segment count, so the first
  coordinator to reach the store claims everything and the rest of the cluster idles. A
  multi-node scenario that does not cap it is a single-node scenario with extra threads, and no
  ownership question arises in it at all.
- **A capped cluster making a skew arm a coin flip.** Fixing the above by capping introduced
  the opposite problem: with four nodes over sixteen segments at a cap of eight, two nodes take
  everything and two hold nothing, so if the skewed node happens to be one of the takers it is
  at its cap, wants nothing, and steals nothing. Measured: one run in three with no overlap at
  all. The skew arms run **uncapped**, so every node is hungry whatever the boot order gave it.
- **A scan that always fails is indistinguishable from a store holding nothing.** The
  authoritative scan threw, the drain kept its previous answer (the empty list), and
  `containsAll(empty)` declared quiescence -- so a run whose store could not be read at all
  reported itself quiesced with zero readable events and every oracle held vacuously. The bug
  hiding behind it was one wrong column name in one SQL statement, and **nothing in the output
  said "SQL"**. The drain now tracks whether it ever got an answer and carries the refusal's
  message into a note.
- **A generator that stopped producing the interesting case.** The reference-model differential
  asserts that at least one append was accepted and at least one was rejected, per seed.
  Without that, generator drift that stopped producing conflicts would leave the test green and
  vacuous.
- **A fault window over an idle system.** A fault window opens after a wall-clock warmup. If
  the workload finishes issuing its commands inside the warmup, the window opens over an idle
  system and the fault fires zero times. The run is correctly reported inconclusive and it
  looks like a broken fault; it is a badly sized scenario. One workload issues its whole budget
  in tens of milliseconds, and with a twenty-millisecond warmup the arms were bimodal --
  sometimes sixty-five fires, sometimes zero, depending on how warm the virtual machine was.

**The general rule.** For every oracle you rely on, ask: on this run, did it decide anything?
Not "did it pass" -- did it *decide*. The landing-evidence rule and the weak-oracle list in
`method-essentials.md` are the mechanical version of that question.

---

## 2. Harness bugs that produced convincing FALSE findings

Four of these were caught in a single audit pass, and **three of the four would have been
written up as framework findings.** Each one arrived with a plausible violation message and a
number attached.

| What was reported | What it really was |
|---|---|
| 89 acknowledged appends apparently lost | The authoritative scan used a column name the table does not have. The scan threw, the drain kept the empty list, quiescence was trivially satisfied, and every oracle held against a store believed to hold nothing. |
| A balance mismatch on two arms with nothing lost | Quiescence was decided by comparing **counts**. A store whose index comes from a sequence taken before a commit separates one batch's rows with another writer's, so the count can be reached while half a transfer is undelivered -- and the projection was folded mid-transfer. Quiescence now compares **sets**. |
| 154 visibility violations on a gap arm, and no skip at all | The fault delayed the append transaction's `commit()`, which on a transaction-managed store does no work and **races** the database transaction rather than preceding it. The harness's own delay was being measured. |
| 9 token regressions, one of 426 positions | A token write is recorded as successful when its **call** returns, and on a shared-resource arm that write joins the transaction that commits the batch. A write whose transaction then died with the connection never landed, so the next claim read back the older token. |

Four more from earlier, same shape:

| What was reported | What it really was |
|---|---|
| 628 of 1000 commands failing on a new store | The recorder called a marker accessor the store's marker type does not support, so **the recorder threw and took the command with it**. Anything in the recording path must degrade rather than throw. |
| Four rollback violations on a crash arm | A crashed node's executors were shut down with the default rejection policy, so a rejection landed **inside an unrelated writer's commit** and rolled that writer's transaction back. A real crashed process is simply not there to be notified; the crash now installs a discard policy first. |
| Model-conformance violations on the first concurrent run | The history was replayed in **invocation** order. Two writers race and the one that asked first is often not the one that landed first, so the model was fed a store state the store never had. It now replays in the order of the authoritative post-run scan, which is the store's own answer. |
| Deliveries recorded before the commit that produced them | The store publishes inside `commit()` and the harness writes its record after that call returns, so a fast consumer legitimately lands in between. The wrapper now records the commit's **invocation**, and the visibility oracle compares against that -- and against the **earliest** such record, because a store that duplicates an append commits the same event twice. |

**The rule that comes out of all of them.** *Do not write up a divergence from a first run.*
Fix the harness until the divergence stops shrinking, and only then attribute it. On the first
real-store run, 227 + 1417 + 131 + 2 reported violations reduced to 231 violations of one
invariant -- which was the finding -- after exactly two harness corrections.

**And the diagnostic that found every one of them:** one `python3` pass over the history
(`running.md` has it). None was diagnosed by reading the assertion message.

---

## 3. When a scenario perturbs something new, every derived signal has to be told

This has now happened **five separate times**, and the answer has been the same every time.

| What the scenario perturbed | What broke, before it was told |
|---|---|
| Segment membership (a split or merge) | Four oracles reported findings that were not real: an interval derived from claim traffic ran straight through the rebuild so the next claimer looked like a second simultaneous owner; a segment identifier does not name the same unit of work either side of a rebuild; a merged segment inherits the lower token so its stored position goes backwards by design; and every event the further-ahead half had handled arrives again. |
| A store connection being broken | A token write's recorded outcome is the outcome of the **call**, not of the transaction, so all three durable-progress invariants had to become not applicable on such a run. |
| A read side that stopped rather than lagged | A new stall signal turned a membership arm intermittent, because the framework blocks re-claim of a splitting segment for a hardcoded minute that no timescale compresses -- so a blocked segment looks exactly like an abandoned one. |
| An injected store failure | It carries no protocol verdict. Recording every failure the same way turns every faulted run into a protocol violation. |
| A fault that rewrites the store | The model oracle and the conservation oracle become undecidable. Reporting the money the harness destroyed as a framework defect is a false finding with a convincing message. |

**The honest default is to stop deciding, not to widen a tolerance.** A checker that
occasionally misses a violation costs a run; one that invents a violation costs a week of
somebody's time chasing a defect that is not there.

The same asymmetry drives how intervals are derived: an ownership interval starts when the
store **answered** and expires from when the node **asked**, so the narrow reading can only
ever under-report an overlap.

---

## 4. Refusing to ship a red test, even when it would pin a real finding

Twice, an assertion was written that turned a planted defect red on the arm it was designed
for -- and was then removed, because it is red on a clean engine too.

The first time: an assertion that each arm's read side caught up. It turned the mutation red on
the store it was aimed at and left the other arms green, which is exactly the attribution a
backend canary is for. But the arms did not reach quiescence on a clean engine at any budget
tried, because the harness counted an append as stored when the engine's commit call returned,
and on that engine the call returns while the database transaction is still open.

The second time, after the accounting was fixed: the same assertion was green on one arm's two
seeds of a clean engine, and **red on a sibling arm's two seeds of the same clean engine**,
because that arm loses events without any fault at all.

**Green-on-clean has to mean always green on clean, not usually.** Shipping either assertion
would have shipped a permanently red test, which is the same inertness as an always-green one
in the opposite direction. Both times the behaviour was written up as an intermittent finding
instead, where an intermittent result can be reported as intermittent, and the numbers were
recorded in `CANARIES.md` so nobody has to re-measure to see why the assertion is not there.

The related trap, in the other direction: **never assert that the suite finds nothing else.**
`allSatisfy(violation -> machineName == theKnownOne)` asserted that one finding was the *only*
thing wrong on a store. It stopped being true the moment a second thing became decidable
there -- and the second thing was a real finding, not a broken expectation. Pin the finding you
mean, not the shape of the whole result.

---

## 5. The inference trap: a measurement plus an inference is not a result

The clearest single lesson in the project.

Three arms measured how far two nodes can hold one segment at once under emulated clock skew.
Two of the three measured statements were straightforward. The third was an **inference** drawn
from the numbers rather than a measurement: that a skew below the margin between the claim
timeout and the owner's refresh rate is *invisible*, because an owner refreshes its row often
enough that no stealable row is ever found.

The evidence was there all along and was rounded off. At a skew of 1000 ms inside a 1600 ms
margin, the arms measured overlaps of **964 to 992 ms** -- not zero. That was written off as
the arm occasionally getting lucky, because there was nothing to compare it against.

The model contradicted the inference, not the measurement. TLC found a violation at a skew of
two ticks against a margin of four, in twelve steps, and the sibling configuration that
*forces* the owner to refresh on schedule holds. So the margin bounds a **punctual owner**, not
the protocol -- and nothing makes an owner punctual, because refreshing is work a scheduled
thread does when it is scheduled, and one missed window is enough.

Consequences, both of which matter more than the finding:

- The candidate fix the finding was about to recommend -- document the margin as the tolerated
  skew -- would have **published a tolerance the framework does not enforce.**
- **A model earns its place where a measurement produced a number somebody rounded off.** Two
  confirmations were worth little; the arms had already measured them. One contradiction paid
  for the whole layer.

And the mirror-image trap: the tolerance and the perturbation must be **two numbers**. They
were one for an afternoon, and the arm could not fail -- the tolerance grew with the
perturbation while the overlap saturated at one claim timeout.

---

## 6. Compressed time, and the durations that do not compress

Time compression is a harness-wide dimension and it has hard edges. Each of these was measured:

- **A hundred-millisecond claim timeout does not survive an in-process database**, and against
  a container it is far worse and wears a completely different face: 43 claims and 43 releases
  on **one node**, 1417 repeated deliveries, 131 token regressions and a conservation
  violation. None of that mentions a timeout. Widening the claim timings to what the cluster
  arms use removed every one of those violations and left exactly one behind, which was the
  real finding. **A run showing mass redelivery on one node should have its claim timeout
  checked before anything else.**
- **The coordinator's idle re-poll is hardcoded and does not compress.** No honest liveness
  horizon can sit below it, which is why the cluster arms declare four times it rather than a
  multiple of the slowest latency the arm happened to produce -- that would be circular and
  would drift upward every time somebody's laptop was busy.
- **A split blocks local re-claim for a hardcoded minute**, which no timescale compresses and
  which is longer than any stall window a thirty-second settle budget can derive.
- **A crash window shorter than the claim timeout is a restart, not a handover.** A node
  brought back inside the claim timeout re-takes its own rows immediately, because the claim
  algebra permits the same owner whether or not the claim expired. Nothing changes hands and no
  stored token is read by anybody else.
- **A skew smaller than the owner's refresh margin is nearly invisible** (see trap 5), so an
  arm built at that scale measures almost nothing.
- **A budget sized for the heap makes a container-backed arm permanently undecided.** Measured
  twice before it was believed. Budgets and timings are properties of the **arm**, and a
  differential that gives every arm the same ones is still comparing the same experiment.

---

## 7. Small things that cost hours

- **`timeout` does not exist on macOS.** Exit 127, no output; in a loop it reads as every
  configuration silently doing nothing.
- **Stale surefire reports inflate a test count and look current.** Remove the whole `target`
  directory, not just `surefire-reports`, and count `<testcase` elements.
- **`-Dtest=A+B` runs nothing and reports success in three seconds** when nested selectors are
  involved. Comma, never plus. Check the `Tests run:` line.
- **`-DskipTests` skips failsafe too**, so an integration-test run needs surefire silenced some
  other way.
- **A build that stops with no output against a container is a lock, not a hang.** Surefire
  buffers a test's output until the method ends. Ask the database what it is waiting on.
- **A killed container run costs the next one about two minutes** while the reaper works, and
  the build looks wedged.
- **Starting nodes in a loop is not concurrent.** A sequential stream lets the first node's
  coordinator create and claim everything before the second `start()` is called: measured, one
  of four nodes ever attempted the initialisation. Release them from a barrier.
- **A configuration record that reads like a builder is not one.** Its setters mutate and
  return `this`, so a cluster sharing a template silently gives every node the last node's
  executors.
- **An in-memory database catalogue outlives its last connection**, so a suite creating one per
  run leaks for the length of the build unless it is explicitly shut down.
- **A polling coordinator inside a storage engine outlives the run unless the engine is
  closed.** Measured: four megabytes of stack traces in one build, and a run that took minutes
  instead of seconds because it was writing them.
- **A connection pool that refuses rather than waits.** A run has a writer thread per
  participant, a coordinator, several workers and one connection per out-of-transaction read;
  the default pool size is far too small and the failure is thrown from inside the coordinator,
  where it reads as a read side that never caught up.
- **In TLA+, `x' = a /\ b` is an assignment plus a guard**, and it surfaces as a deadlock in a
  state that looks perfectly able to move on. Parenthesise every primed assignment whose
  right-hand side is a conjunction or a disjunction. It cost an hour, and the two things that
  would have found it in a minute are identical state counts with the flag on and off, and
  asserting that the action is enabled.
- **A dependency that resolves is not a dependency that runs.** One tool was attempted across
  two artefact coordinates and three JDKs; it compiled and failed at run time every time
  because its agent could not retransform classes. It was removed rather than left behind a
  version guard, because a test that only runs on a platform the author could not try is a
  quarantine with extra steps.
