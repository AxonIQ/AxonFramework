# Boundary and isolation -- claims that hold across every surface or not at all

**This file is self-contained.** It is the discipline for a claim about a **boundary** -- one
thing is separated from another -- or about **fairness** -- no group starves another. Those
claims are not properties of one code path. They hold across every surface where the boundary
could leak, and a test against one surface proves that surface.

Use it when a scenario's claim is of kind `boundary` or `fairness`. Do **not** use it for the
`isolation` kind in the claim corpus, which means transactional isolation and belongs to the
conflict-semantics checkers -- see `dcb-semantics.md`. Two different concepts, two labels, on
purpose.

**Where this bites in this framework.** Multi-tenancy is the obvious one: an Axon Server
context is a boundary, and every surface that reaches the store crosses it. But so is a
segment range, a processing group name, a tag, and a DCB consistency boundary itself. Each is
a key that something is supposed to stay inside, enforced by code that mostly nobody tested
from the outside.

---

## 1. Enumerate the surfaces before writing the oracle

A surface is any path by which an operation reaches the state the boundary protects. The
starting catalogue for this framework, by subsystem. **It is a floor. Add what your subsystem
has that this misses.**

### Command and event handling

- Command dispatch (local bus, distributed bus, connector)
- Event append through the event store
- Event append through an event appender injected into a command handler
- Snapshot write and snapshot read
- Entity sourcing (the `SourcingCondition` path, which is a *different* read path from
  streaming)

### The read side

- Streaming event source: open, resume, close
- Token store: fetch, store, claim, release, extend, delete
- Segment split and merge
- Reset and replay, including replay-context handling
- Dead letter queue: enqueue, evict, process, purge
- Sequencing-policy resolution (the routing key path)

### Queries

- Direct query
- Scatter-gather query
- Subscription query: initial result, update emission, cancellation
- Query update emitter reached from an event handler

### Administration and out-of-band paths

- Configuration, per path: core, Spring Boot, YAML, environment
- Processor start, stop, shutdown
- Metrics and tracing exporters
- Connector-level context or tenant selection
- Anything the harness reaches by going around the framework -- a raw JDBC connection, a
  direct table read. **These are surfaces too, and they are where a leak is easiest to prove
  and easiest to fake. See the anti-pattern at the end.**

### Background paths -- the ones that carry no request context

Enumerate these separately, because they are where boundary claims usually break: a path that
runs without whatever carried the boundary key on the request.

- The coordinator thread and its claim refresh
- Gap-detection and gap-timeout machinery
- Snapshot triggers
- Retry and redelivery loops
- Deadline and scheduled-message dispatch
- Any framework-owned executor

### The generic catalogues, by kind of system

The framework crosses several of these, and a store or a deployment under test is usually one of
them. Use the matching catalogue as a second pass over section 1, because a surface that belongs
to the *store* still leaks the *framework's* boundary.

**Database.** SQL or query API; backup and restore; a change-data-capture stream; the admin API
(schema changes, role management, configuration); driver libraries, per language, each with its
own connection state; bulk import and export tools.

**Queue or message broker.** Produce; consume, per consumer group; offset management; consumer
group rebalancing; the dead-letter queue; replay and time-travel reads.

**Object store.** Put, get, list, delete; multipart upload; lifecycle -- expiration, transitions
between storage classes; cross-region replication; bucket-level administration.

**Control plane.** The REST or RPC API; the CLI; the scheduler; the reconciler or controller
loop; the metadata store.

**Multi-tenant service.** The public API; per-language SDKs; background jobs -- cron and queued
workers; exports and reporting; billing and metering; the metrics and observability stack; audit
logs.

**Multi-region service.** Per-region read and write; replication lag; region failover; the data
residency boundary.

The catalogue is not exhaustive. Add the surfaces the system under test has that none of these
rows names -- and note that the last two rows of the multi-tenant list, **observability and audit
logs, are the ones most often left out of a decomposition** and among the easiest to leak
through.

---

## 2. The boundary claim matrix

Fill every field before implementing. A blank field is a surface nobody checked.

```
Boundary claim:         one falsifiable sentence
Boundary keys:          what scopes it -- context, tenant, tag, segment range,
                        processing group, entity id
Surfaces:               from section 1, plus subsystem-specific
Operations:             per surface, which operations the arm issues
Positive controls:      what legitimate access must still succeed
Negative controls:      what illegitimate access must be denied AND must not be
                        observable in metrics, logs, spans or timing
Confusable inputs:      crafted keys designed to provoke a leak -- section 3
Async paths:            background jobs, retries, gap machinery, DLQ, snapshots
Observability paths:    metrics, spans and logs that could themselves leak
Oracle:                 the property per arm, covering both controls
Smoke budget:           configuration, duration, faults, seeds for PASS-smoke
Hardening budget:       strictly stronger on every dimension
```

The last two rows are why this matters: without them, a boundary claim gets quoted off a
smoke run. See `verdicts-and-classification.md` section 1.

**Split into arms.** If the decomposition spans more than three surfaces, more than three
claim kinds, or needs more than one independent oracle, split it: `S-n/append`, `S-n/stream`,
`S-n/admin`. Each arm gets its own oracle and **its own verdict**, and the downgrade rule
applies -- any `NOT-RUN` or `PARTIAL-*` arm caps the scenario. A single aggregate verdict
over a decomposed boundary claim folds the untested surface into a pass, which is the exact
failure this whole file exists to prevent.

---

## 3. Confusable inputs -- cover at least one from every row

A boundary is enforced by comparing keys. Every row below is a pair that a comparison can get
wrong, and this framework has already been bitten by two of them.

| Class | Pair to try | Seen here |
|---|---|---|
| Same name, different scope | entity `acme` in context A vs context B; the same processing group name in two applications | |
| Prefix collision | `orders` vs `orders-archive`; tag key `id` vs `identifier` | |
| Case folding | `Acme` / `acme` / `ACME` -- and whether the store's collation folds where the framework's `equals` does not | |
| Embedded separator | a tag value containing the character the storage layer uses to join key and value | |
| Reserved value in the data space | a payload or key equal to a framework sentinel | **F-22: `"BROADCAST"` is a plain string; user data equal to it is delivered once per segment** |
| Sentinel collapse | no-condition vs strictest-condition; `ORIGIN` vs `INFINITY` reaching one code path | **F-14: both resolve to the same empty map on the aggregate-based store** |
| Range off-by-one | segment masks whose ranges abut; the key exactly on the boundary; `lowerBound` equal to `upperBound` | **F-15: the combination operator is unimplemented on the edge ordinary use reaches** |
| Recycled identifier | a processing group deleted and recreated; a segment id reused after a merge; a token row deleted and re-inserted | |
| Integer boundary | a segment id or sequence at `2^31 - 1`, `2^31`, `-1`, `0` | |
| Unicode normalisation | a composed vs decomposed form of the same tag value | |
| Empty and absent | empty string vs null vs absent key -- three states the framework may collapse to two | **F-7: an unresolvable routing key throws per message** |

Two rows already produced findings without anybody running a boundary campaign. That is the
argument for running one.

---

## 4. Negative-control anti-patterns

Every one of these has the same shape: the arm asserts half the boundary and reports a pass.

- **Positive control only.** "The legitimate reader saw its own events" is not "the other
  context's reader saw nothing". Assert both, in the same arm.
- **Denial without an observability check.** The cross-boundary read was rejected -- and the
  metric counter still moved, the span still carried the identifier, the exception message
  still named the resource. The denial leaked what it denied.
- **No-leak conflated with no-error.** A clean exception log is not an absence of
  cross-boundary data. Check the data.
- **Async path skipped.** The synchronous path denies it and the background path -- the
  retry, the DLQ processor, the snapshot trigger, the gap scan -- runs without the context
  that carried the key. Cover the async paths in the same arm, not in a follow-up nobody
  writes.
- **Timing side channel unmeasured.** A denied operation that takes measurably longer when
  the target exists discloses existence. If the claim is about isolation rather than access
  control, say explicitly that timing is out of scope; do not leave it implied.

---

## 5. Fairness -- per-group, never aggregate

A fairness claim needs a **formula over per-group metrics**, declared before the run. An
aggregate percentile meeting a threshold proves nothing about fairness: one starved group
disappears into the aggregate.

Declare all four, per group dimension:

```
worst_group_p99 / aggregate_p99  <= threshold   no group has materially worse tail latency
min_group_throughput            >= threshold   no group is starved
error_rate_by_group             == 0           no group sees elevated errors
repeat_spread                   <= threshold   run-to-run variance per group is bounded
```

The group dimension must be the boundary the claim is about. In this framework the candidates
are: segment, processing group, context, tag, sequencing key, and connection. **Picking a
dimension coarser than the claim is the standard way a fairness arm passes vacuously** --
aggregating across segments hides a starved segment, which is exactly what F-6 turned out to
be about.

The most productive fairness shape here is not throughput at all: it is **a shared resource
on a critical path**. A renewal, a claim extension or a heartbeat that shares a thread pool
or a connection pool with the workload will starve under load, and the protocol will look
like it failed when the holder was alive the whole time. See the lease rows in
`pitfall-catalogue.md`.

---

## 6. When this is overkill

A claim about one key with no boundary semantics does not need the matrix. Forcing surface
decomposition on a genuinely single-surface claim produces ceremony that decides nothing.
Use this file when the claim names a thing that is supposed to stay inside something.

---

## The anti-pattern that fakes every boundary result

**Never inject at a layer the framework does not read through.** Deleting a row, corrupting a
value or planting a key directly in the store, when the framework writes through a prefix, a
context, a schema, a converter or a tag-joining scheme, lands the fault on bytes the framework
never reads. The arm then reports that the boundary held.

Mirror the framework's own write path when injecting at the storage layer, and **verify by
reading the value back through the same path** before counting the fault as landed. This is
the storage-layer form of the landing-evidence rule in `method-essentials.md` section 3, and
it is the single easiest way to produce a convincing false pass in a boundary campaign.
