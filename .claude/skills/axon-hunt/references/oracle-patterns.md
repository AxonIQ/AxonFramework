# Oracle patterns -- the checker picker and the fourteen shapes

**This file is self-contained.** An oracle is the thing that says a run passed or failed.
Without a real oracle you have a load generator and nothing more, and **most green-but-broken
results come from an oracle that did not actually run**.

`method-essentials.md` section 2 carries the short form of the picker. This file is the long
form: every pattern, what it needs as input, what it outputs, and the way it fails silently.

Two rules bind every pattern in this file, without exception:

- **Cite the fact that it ran, not just its verdict.** "The property fired 12,401 times, 0
  failures." "The model replayed 4,217 operations, 0 divergences." A verdict with no execution
  evidence is indistinguishable from an oracle that never ran.
- **Declare its scope.** Say what it cannot detect. This suite does that in the registry's
  scenario column and, per run, through the not-applicable channel.

And one more, local to this suite: **prove the checker can fail.** Every checker here has
synthetic histories with its rule planted broken, built through the synthetic-history helper
rather than by hand-fabricating records. A checker with no demonstrated failure mode is
decoration.

---

## The checker picker

Map the claim onto a model, then take the row. The model is what the operations look like, not
what the class is called.

| Model | Claim kind | Checker |
|---|---|---|
| register -- one key | safety | linearizability against a sequential specification |
| log | durability | no-lost-ack, plus replay equivalence |
| map -- multi-key | isolation | serializability per key and across keys |
| session | safety | session consistency, or monotonic read |
| ledger | idempotency | dedup on a business idempotency key |
| membership table | membership | reconciliation across replicas at quiescence |
| queue | ordering | prefix or order checker |
| counter | safety | linearizability, or an invariant over final state per key |
| lock or lease | safety | an exclusion property, plus an invariant over final state |
| any group -- tenant, segment, processing group, connection | fairness | a formula-based per-group checker, section 14 |

If the model is not in the table, take the closest row and **write down why the checker still
applies** -- or write down that there is no checker and why. The second is a legitimate answer
and a better one than a checker that cannot decide.

### How this framework's objects map onto the models

| Framework object | Model | Notes |
|---|---|---|
| The event store, appending under conditions | log, plus map over tags | The conflict check makes it multi-key: a condition names tags |
| An event-sourced entity | register | Sourced state is one value per identity |
| A streaming event source per segment | queue | Consumers see a prefix, with licensed exceptions |
| A token store row | lease | Owner, timestamp, expiry -- a lease with a position attached |
| A projection under a conservation law | counter, or ledger | Whichever the invariant is arithmetic over |
| Segment ownership across nodes | membership table | Join and leave are claim and release |
| A subscription query | session | Initial result plus updates is a session guarantee |
| The dead-letter queue | queue | And a head-of-line-block property, section 15 of the pitfall catalogue |

---

## 1. Linearizability or serializability over an operation history

- **When.** Anything claimed linearizable or serializable.
- **How.** Feed the complete history to a checker that searches for a sequential order
  consistent with real-time precedence. Tools: `Elle` (general, transactional anomalies),
  `Porcupine` (Go), `Knossos` (Clojure).
- **Inputs.** A complete history with invoke and complete timestamps plus the value returned.
- **Output.** An anomaly classification -- G0, G1a, G1b, G1c, G-single, G2-item, G2 -- or a
  counter-example interleaving. **A classification beats a boolean**: it names which anomaly,
  which tells the owner what to fix.
- **Fails silently when.** The history is truncated. The checker cannot see what it does not
  have. **Always cross-check operation counts before trusting a pass.**

**Here.** Not used directly -- the reference model plays this role for the append protocol,
because the property is DCB-specific and no general checker encodes it. What is worth copying
is the classification discipline: our model attributes a divergence to **one named rule**
rather than to "the store".

---

## 2. Property assertion

- **When.** An invariant holds at every step, or at quiescence.
- **How.** Assert in code during the run, or offline against recorded state.
- **Fails silently when.** The assertion never ran, because the path was not taken. **Cite how
  many times it fired.**

**Here.** The default shape for anything the model does not cover. The failure mode is real and
has been measured: an arm can report `INCONCLUSIVE` on every seed of every run, which means the
assertion has never once been reached -- and an arm that can never pass can never signal a
regression either.

---

## 3. Replay equivalence

- **When.** Crash recovery, idempotent retries, fork operations.
- **How.** Snapshot state A, perform the operation, snapshot B. Replay from A. Compare to B.
  They must match, allowing for documented non-determinism.
- **Fails silently when.** The comparison is too lenient -- it ignores a field it should not.
  A comparison that ignores timestamps is usually right; one that ignores a version or a
  position is usually the bug.

**Here.** **Absent, and it is the most valuable missing oracle for the snapshot and recovery
paths.** A snapshot is state A; the events after it replay to state B; sourcing the entity
from scratch must produce the same state. Nothing checks that today.

---

## 4. Metric SLO threshold

- **When.** Performance, fairness, availability.
- **How.** Define the p99 latency, error rate or throughput floor **before** the run. Pass
  means within threshold across the measurement window.
- **Fails silently when.** The window is too short, or the threshold was set from the same run
  that measured the baseline.

**Here.** Not used, and honestly so: timescale compression makes absolute latency meaningless.
The exception is section 14 -- a *relative* per-group formula survives compression, because
both sides scale together.

---

## 5. Statistical comparison against a baseline

- **When.** "Did this regress?" rather than "is this correct?"
- **How.** Capture a baseline run and a candidate run, then apply a test that controls the
  false-positive rate -- a bootstrap confidence interval on the percentile of interest.
- **Fails silently when.** One run is compared against one run. **Always replicate.**

**Here.** The related rule that has bitten this project: **re-baseline whenever the harness
changes, and state both counts.** A comparison against a baseline taken before a harness change
is a comparison of two different experiments.

---

## 6. Cross-implementation differential

- **When.** There is a reference implementation, or a model, to compare against.
- **How.** Send the same operations to both and diff the outputs.
- **Fails silently when.** **The reference has the same bug.**

**Here.** This is the suite's primary oracle, and the failure mode above is exactly why the
reference model is itself cross-checked against an independent formal specification over a
whole finite domain. The second form of the same pattern is the **per-backend vector**: the
same scenario against every registered store, so a failure names the store instead of starting
an argument.

---

## 7. Session-consistency checker

- **When.** The claim is "reads honour the writes my session has issued so far" --
  read-your-writes, read-after-write within a session.
- **How.** Group operations by session. For each session, verify its reads see the union of
  that session's prior committed writes, plus anything the global order may have inserted
  between them. **No cross-session guarantee is required** -- asserting one turns this into a
  linearizability check and produces false failures.
- **Inputs.** A history with a session identifier and a complete per-session invoke ordering.
- **Fails silently when.** Session boundaries are not marked. A client that reconnects starts a
  new session; a checker that treats the new connection as the same session produces a false
  **pass**.

**Here.** Absent. The natural target is **append-then-source**: an entity sourced by the same
caller immediately after that caller's successful append must reflect it. The session
identifier is the connection or the processing context; the reconnect trap is real, because a
connector reconnect is invisible to the caller.

---

## 8. Monotonic-read checker

- **When.** The claim is "once a session has read value V for a key, it never sees an older
  value for that key".
- **How.** For each session and key, record the sequence of read outputs and assert it is
  non-decreasing under the system's own value ordering -- a version, a sequence, a consistency
  marker.
- **Inputs.** A history with a session identifier, a key, and ordered reads.
- **Fails silently when.** The value ordering is inferred wrongly -- most often by comparing
  wall-clock timestamps in a skewed cluster. **Use the system's own version primitive, never
  the recorder's clock.**

**Here.** Absent, and it has an obvious target: a **token position must never go backwards**
except through a documented rewind. F-11 (a merge rewinds to the lower token) and F-21 (the
anti-rewind guard is inactive on the first store of a claim) are both monotonic-read failures
in the progress domain, and both were found by reading rather than by a checker. The version
primitive is the token's own position -- not a timestamp.

---

## 9. Prefix or order checker

- **When.** The claim is about queue or log order -- "every consumer sees a prefix of the
  global order".
- **How.** The producer side records the canonical order; each consumer records what it
  observed; assert each observation is a prefix of the canonical order -- no reordering, no
  skips, no inserts.
- **Inputs.** A producer history plus a per-consumer history, cross-referenced by operation
  identifier.
- **Fails silently when.** **The legitimate reordering window is not encoded.** A system may
  reorder within an in-flight window; encode the window and assert prefix-of-order only outside
  it. Without that, in-flight reordering reads as a violation.

**Here.** The order checker over sequence keys. The window that must be encoded is the set of
**licensed redeliveries**: handover, merge, replay. F-11's merge rewind is inside the window
and therefore not a violation of *this* checker -- it is a documentation finding. Encoding the
window and encoding it too generously are both failure modes; the registry's statement is what
pins which.

---

## 10. No-lost-ack checker

- **When.** The claim is "every acknowledged write appears in the final state".
- **How.** Scan the history for operations that succeeded, collect their inputs, then read the
  final state at quiescence and assert every collected value is present.
- **Inputs.** A history with the completion outcome and the unknown marker; plus a final-state
  dump.
- **Fails silently when.** **Timeouts are treated as acknowledged.** They are not -- they are
  unknown. The checker must **ignore** them, not decide them in either direction. Deciding them
  as acknowledged invents findings; deciding them as failed hides them.

**Here.** The durability channel. F-16 is what it caught: committed events never delivered. The
refinement this project needed: **loss must be decided on a stopped read side, not only on one
that caught up** -- because a permanently-lossy store and an interrupted run produce the same
observation on a read side that is still moving.

---

## 11. Exactly-once or idempotency checker

- **When.** The claim is "the same idempotency key never produces two committed effects".
- **How.** Group operations by the idempotency key carried in the input -- which is usually
  **not** the operation identifier -- and assert at most one committed effect per group, in the
  final state or in any consumer's view.
- **Inputs.** A history with the idempotency key annotated on every operation, plus the final
  state or an audit trail.
- **Fails silently when.** **The idempotency key is the operation identifier.** Then the
  checker is trivially true and tells you nothing. The key must be a *business* key the client
  controls and reuses across retries.

**Here.** The dedup channel, and the trap above is worth restating because our recorder assigns
an `id` per operation on purpose -- a retry is a **new** operation with a **new** id, and the
business key lives in the value. A checker keyed on `id` would pass on every history ever
recorded.

---

## 12. Invariant over final state

- **When.** The claim is "after quiescence the system satisfies invariant I" -- a ledger sums
  to the opening total, every joined member appears once, no segment is unowned.
- **How.** Drive the workload, drive the faults, reach quiescence, dump the final state,
  evaluate the predicate.
- **Inputs.** A final-state dump and the invariant as a predicate.
- **Fails silently when.** **Quiescence is undefined.** Define it as: no operations in flight,
  **and** no pending background work, **and** the read side converged -- and **verify each
  separately** before applying the invariant.

**Here.** The conservation law, and the cheapest oracle in the suite by a wide margin. It
caught a double-processing mutation for which nobody had written an assertion, by arithmetic
alone, in two separate mutation campaigns. Its weakness is the one above: it sees only the end
state, so it misses every transient anomaly the system recovered from -- which is why it is
paired with in-run assertions, never used alone.

---

## 13. Reconciliation across replicas

- **When.** The claim is "at quiescence every replica converges to the same state".
- **How.** After workload and faults complete, force quiescence -- drain in flight, stop new
  operations, wait the system's own convergence window. Dump each replica's full state. Diff
  pairwise.
- **Inputs.** Per-replica final-state dumps, with the replica identity recorded per operation.
- **Fails silently when.** Replicas are compared **before** convergence has completed.
  Convergence is a property of the system, not of the test; some systems have eventual windows
  measured in minutes.

**Here.** Two applications. Across store nodes -- read off the per-backend vector. And within
the framework: **the stored token against the projection's actually-applied set** are two
replicas of the same progress, and a divergence between them is F-21's and Canary C6's shape.
The framework form is the one with no checker today.

---

## 14. Fairness checker -- formula-based, per group

- **When.** The claim is "no group is unfairly starved, throttled or prioritised". The group
  can be a tenant, a segment, a processing group, a partition, a connection, a priority class,
  a sequencing key.
- **How.** Record per-group metrics -- latency percentiles, throughput, error rate -- over a
  measurement window, then evaluate a formula. Declare all four before the run:

```
worst_group_p99 / aggregate_p99  <= threshold   no group has materially worse tail latency
min_group_throughput            >= threshold   no group is starved
error_rate_by_group             == 0           no group sees elevated errors
repeat_spread                   <= threshold   run-to-run variance per group is bounded
```

- **Inputs.** Per-group metric series, the thresholds declared in the scenario, and the group
  dimension.
- **Fails silently when.** **The group is too coarse to surface the unfairness.** Aggregating
  across segments hides a starved segment; aggregating across contexts hides a starved context.
  The group dimension must be the boundary the claim is about.

**Here.** Absent, and the failure mode above is not hypothetical: **F-6 is a starvation finding
in disguise** -- the default policy collapses to one segment, so every other segment is starved
of work, and an aggregate throughput number would look fine. The most productive shape in this
framework is not throughput at all but **a shared resource on a critical path**: a claim
extension or a coordinator poll starved by workload on the same pool. See
`boundary-and-isolation.md` section 5.

---

## Choosing between them, when more than one fits

Pick the **weakest oracle that can still fail on the defect you are hunting**, then add a
second one that fails differently. Two oracles that fail for the same reason are one oracle.
The suite's own pairing is the pattern: a reference-model differential (section 6) as the
primary net, a conservation law (section 12) as the cheap global one, and a property assertion
(section 2) per named invariant. Three different failure modes, so a defect that slips one is
caught by another.

## The shapes this suite uses, and where each fails silently

| Shape | Used for | Fails silently when |
|---|---|---|
| Cross-implementation differential against a reference model | the primary oracle for the append protocol | the reference shares the bug -- hence the formal cross-check |
| Invariant over final state, as a conservation law | the cheap global oracle | quiescence is undefined |
| No-lost-ack | durability | timeouts are treated as acknowledged |
| Prefix or order | ordering per sequence key | the legitimate reordering window is not encoded |
| Exactly-once dedup | idempotency | the key is the operation id |
| Property assertion | everything else | the path was never taken; cite the fire count |
| Replay equivalence | crash recovery, idempotent retries | the comparison ignores a field it should not |
