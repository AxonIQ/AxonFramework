# Operation-history discipline -- the full schema, the recorders and the anti-patterns

**This file is self-contained.** A checker cannot consume what was not recorded. If a scenario
claims to test safety, durability, idempotency, isolation, ordering or membership, an operation
history with a known schema is the **prerequisite**. Without one, the strongest verdict a run
can produce is "no obvious regression" -- never "the claim survived the fault".

`method-essentials.md` section 1 carries the short form. This is the long form: the general
schema and its mapping onto ours, where to record from, what disqualifies a history, how to
model ambiguity, and which fields each model demands.

**When it is mandatory.** Any claim of kind safety, durability, idempotency, isolation, ordering
or membership. **Optional** for pure performance, liveness and operational scenarios, where a
workload plus a threshold oracle is sufficient.

---

## 1. The general schema, and this suite's

Twelve fields, of which invoke and complete are one conceptual slot -- an operation's lifetime.
**Fields an operation type does not use are recorded as null, not omitted**, so a reader can
tell "absent" from "not applicable".

| General field | Meaning | This suite |
|---|---|---|
| `op_id` | Globally unique per run. A UUID, or a monotonic counter | `id` |
| `process_id` | The client, session or connection that issued it | `process` |
| `invoke_ts` | When it was sent. A monotonic source, preferred | `logicalTs` on the `INVOKE` record |
| `complete_ts` | When the answer arrived. Null for a timeout | `logicalTs` on the `OK` / `FAIL` / `INFO` record |
| `op_type` | `read`, `write`, `cas`, `append`, `lock`, `lease`, `join`, `leave`, ... -- model-dependent | `op`, an **open** string set: recording a new operation kind must not require editing any existing class |
| `key` | The object, session or shard addressed | `key` -- a tag, a segment, an account |
| `input` | The arguments sent | `value` on the invocation |
| `output` | The answer received. Null if the outcome is unknown | `value` on the completion |
| `error` | The error, if the answer was one. Mutually exclusive with `output` for operations that return a value | `error` |
| `timeout_marker` | True if the outcome is unknown | `type = INFO` with `error` set |
| `node_seen` | Which node, leader or route the client talked to. **Required** for any replication, leadership or membership claim | `node` |
| `fault_epoch` | Which fault window was active during the operation; null if none | `faultEpoch` |

**`fault_epoch` is the most-skipped field and the most load-bearing.** Without it a checker
cannot tell whether an anomaly happened under fault, so it cannot attribute one.

Plus one field the general schema does not have and this one needs:

- **`idx`** -- a strictly increasing sequence number that **defines the history's order**. File
  order does not, because records are serialized outside the recorder's write lock. The reader
  sorts by `idx`.

`formal/INVARIANTS.md` section 4 is the authority on our schema. This table is the mapping, not
a second definition.

---

## 2. Where to record from

Three vantage points, with different blind spots.

- **In-process recorder.** The client emits one record per invocation and one per completion.
  Closest to the truth, cheapest, and **blind to anything the client library swallowed** -- a
  retry the library hid never appears.
- **External probe or tap.** A network-layer recorder intercepts client-to-server traffic.
  Catches the library-swallowed retries; **blind to anything that does not cross the network** --
  in-process caches, batched writes, local buses.
- **Server-side audit.** The server records every operation it processed. Most accurate for what
  the server saw; **blind to operations the server never received** -- client crashes, dropped
  packets.

For most consistency claims the recommended pair is **in-process plus server-side**: an anomaly
surfaces as a mismatch between the two.

**In this suite.** In-process only, and that is worth stating as a scope limit rather than
leaving implied. The consequence: **a retry the connector performs internally is invisible to
our history**, so an at-least-once behaviour originating inside the client library would be
recorded as exactly-once. The store's own audit is the missing second vantage point, and the
verdict vector partially compensates -- a behaviour visible on one store and not another is
narrowed even without a tap.

---

## 3. Complete, and weak

A history is **complete** for a scenario when it carries every field the chosen checker
requires. It is **weak** when any of these is true:

- A completion timestamp is missing for an operation that was not a timeout.
- The unknown marker is missing on an operation that did not return.
- The fault window is missing when a fault overlapped the workload.
- The node is missing for a replication, leadership or membership claim.
- Ordering or timing was recorded against wall clock where the checker compares timestamps
  across processes.

**A weak history produces an inconclusive verdict on the grounds that the oracle is too weak --
never a pass.** Fix the recorder, then re-run. See `verdicts-and-classification.md` section 1
for the label and its repair.

---

## 4. Ambiguous outcomes

Real distributed systems return **three** things, not two: success, failure, and *unknown*. The
history must model unknowns first-class.

- **Timeout.** The invocation is recorded; the completion carries the unknown marker and no
  output. Every checker treats it as *may-or-may-not-have-happened*. **Treating a timeout as
  failure hides real anomalies; treating it as success invents them.**
- **Unknown commit.** An error that does not distinguish "did not commit" from "committed, the
  answer was lost" gets the same treatment as a timeout. The error string is retained for
  diagnosis only, and **must not** be read as an outcome. F-20 is exactly this: the framework
  collapses every transport failure into a consistency rejection and discards the cause, so the
  *system* has the same defect the recorder is forbidden to have.
- **A retry that succeeds after a failure.** Two separate operations, same input, different
  identifiers. **Never merged.** If the system is exactly-once, detecting the duplicate is the
  checker's job, not the recorder's. A recorder that emits one entry covering a whole retry
  chain makes exactly-once indistinguishable from at-least-once.
- **A duplicate response.** The same operation identifier appearing twice with different outputs
  is a **bug in the recorder**, not a finding. Different identifiers with the same input and
  overlapping invocation windows are legitimate retries.
- **An operation still in flight when the run ends.** Recorded with its invocation only, and it
  resolves to unknown. **Never truncate trailing operations** -- run-boundary truncation is the
  single largest source of fake findings in history-checked systems.

---

## 5. Which fields each model demands

Record every field for every serious scenario. The rows below name the ones that, if dropped,
make the checker **unsound** for that model.

| Model | Required beyond the core minimum |
|---|---|
| register -- one key | the node, for per-replica linearizability |
| map -- multi-key | the key, and the node |
| queue | the operation type restricted to enqueue and dequeue; a strict invocation ordering |
| log | the operation type restricted to append and read; the read's output includes its position |
| lock | acquire and release; the process; the fault window |
| lease | acquire, renew and release; an invocation-timestamp precision finer than the lease duration |
| session | the process as the session identifier; the write's output returns a version |
| membership table | join, leave and view; the node per view |
| counter | increment and read; the input is the delta, the output the post-value |
| ledger | credit, debit and balance; the key is the account; the business idempotency key is in the input |

The model decides which checker is applicable at all; this table tells the recorder which fields
that checker will demand. Pick the model **before** writing the recorder, or the recorder will be
missing a field the checker needs and the arm will be inconclusive on every run.

### The framework's objects, and their lease-precision trap

The token store is the `lease` row, and its requirement is the one most easily broken here: the
invocation-timestamp precision must be finer than the lease duration. Under **timescale
compression** the claim timeout shrinks while the recorder's tick does not, so a compression
factor large enough makes two distinct claim events share a timestamp -- and an exclusion
checker cannot then order them. Compress the timeout and the recording granularity together, or
state the floor below which the arm is inconclusive by construction.

---

## 6. Anti-patterns

- **Recording only successes.** A partial history yields a meaningless pass. Timeouts and
  failures disappear, and the checker gives a verdict on a fiction.
- **Dropping the unknown marker.** Same effect: unknowns collapse into the success-or-failure
  binary and the checker can no longer reason about could-have-succeeded operations.
- **Per-client wall clock.** Two clients with skewed clocks produce a history the checker reads
  as inconsistent even when the system is correct. Use a monotonic source per recorder, or the
  server's receive timestamp as the canonical order. **Wall clock is for correlating with
  external evidence only.**
- **Collapsing retries.** Each attempt is its own operation with its own identifier.
- **Recording an output for an operation whose outcome is unknown.** The checker reads it as "we
  know what happened".
- **Truncating trailing operations.** See section 4.
- **A recorder that throws.** Anything in the recording path must **degrade rather than throw**.
  A recorder that throws takes the operation with it, and the history then describes a system
  that was never exercised.
- **A single record carrying both timestamps.** It cannot represent an operation that never
  completed, which is the one case the whole discipline exists for. This was a real correction in
  this project: the original commitment specified one record and contradicted itself.

---

## 7. What a scenario must declare

Every serious scenario states, in its record: which fields the recorder captures, any
scenario-specific extension, and the recording vantage point. If it uses the default schema
unmodified, "default schema, in-process" is a complete answer. Anything else is written down --
because a checker that needs a field nobody declared is an arm that will be inconclusive on
every seed, and nobody will know why.
