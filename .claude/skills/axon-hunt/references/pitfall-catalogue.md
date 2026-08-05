# The pitfall catalogue -- failure modes to walk against a new subsystem

**This file is self-contained.** It is the hypothesis-generation catalogue that step 3 of
`extending.md` walks: one row at a time, against the subsystem being covered, recording a
verdict for every row **including the rows that do not apply and why**. The walk this suite
already did -- with per-row verdicts and the hypotheses each hit produced -- is tabulated in
`docs/testing-plans/axon-hunt.md` section 4; extend that table rather than re-walking rows it
already answers.

Two parts, and they are used in the same walk:

- **Part 1 -- by claim kind.** Rows organised the way this suite organises claims, because the
  kind picks the checker (`method-essentials.md` section 2). Findings this suite already made
  are cited, so every abstract row has a concrete face.
- **Part 2 -- the recurring sixteen.** Failure families that recur across the published
  analyses of real distributed systems, each with mechanism, where it has been seen, the
  technique that exercises it, and a **hypothesis template** to paste and adapt. These are
  ordered by how often they appear: 1 to 6 are in the majority of analyses, 7 to 14 are
  common, 15 and up are subsystem-specific.

Walk part 1 first -- it is closer to this framework. Then walk part 2, because it is where the
rows nobody here thought of live.

---

# Part 1 -- by claim kind

## Durability and visibility

| Pitfall | The question to ask |
|---|---|
| Acknowledged-but-lost | Is the acknowledgement issued before or after the durability point? On a two-phase store, which phase is the point of no return? (F-17: `commit()` measured racing the database transaction; the interface does not say which commits.) |
| Visible-before-committed | Can a concurrent reader observe an operation mid-flight? Is a multi-item batch made visible atomically? (F-3: a strict prefix of a committing batch is observable.) |
| Ambiguous outcome collapsed | Does the client map a timeout or dropped connection to a definite success or failure? (F-20: every transport failure reported as a consistency rejection, cause discarded.) |
| Retry of an ambiguous write | If the answer was lost but the write landed, does the retry double-apply? What dedup key exists, if any? |
| Skipped-forever under load | Is there any index, sequence or gap machinery where an entry seen "too late" is never revisited? What decides "too late", and is that clock commensurable with what it measures? (F-16: an event skipped for ever because its own timestamp aged past the gap timeout.) |
| Rollback after the point of no return | What does rollback mean once commit succeeded? Does anything call it there anyway? (F-8.) |

## Concurrency control and consistency

| Pitfall | The question to ask |
|---|---|
| Check-then-act races | Is the conflict check and the effect one atomic step, or can another writer land between them? (F-19: appends accepted although their boundary was violated by a concurrent commit.) |
| Sentinel collapse | Do two distinct sentinels (no-condition vs strictest-condition, ORIGIN vs INFINITY) share a code path anywhere? (F-14: both resolve to the same empty map on the aggregate store.) |
| Unimplemented algebra edge | Do combination operators (lowerBound/upperBound, merge) have all their cases implemented, and does ordinary use reach the missing ones? (F-15: `doLowerBound` throws "Not implemented yet", reached by sourcing twice.) |
| Boundary semantics drift | Do the interpreted and the query-building forms of a predicate agree? A store that interprets and a store that builds a query can decide differently from the same object. (Canary C8 established the two forms are independent.) |
| First-writer races on shared init | When N instances initialise one shared row/table/schema at once, does the loser re-read or fail? (F-9: all but one instance fails to start.) |

## Ordering and delivery

| Pitfall | The question to ask |
|---|---|
| Default that silently degrades | Does the documented default match the wired default, and does the wired default behave the same on every store? (F-6: the per-aggregate half of the default policy is inert on DCB stores; everything collapses to one segment.) |
| Empty/unresolvable routing key | What happens when the routing key cannot be resolved -- fallback, skip, or throw-per-message? (F-7: `Optional.get()` throws on every event, read side delivers nothing for ever.) |
| Reserved value in data space | Is any sentinel a value user data can equal? (F-22: `"BROADCAST"` as a plain string; a data value equal to it is delivered once per segment.) |
| Redelivery windows nobody documents | Which operations legitimately redeliver (handover, merge, replay), and does the documentation say so? (F-11: a merge rewinds to the lower token, documented nowhere.) |
| Order across a rebuild | Does any per-key ordering guarantee survive a segment split/merge, a repartition, a reshard? What names the unit of work either side? |

## Membership, leases and clocks

| Pitfall | The question to ask |
|---|---|
| Clock-skew tolerance unstated | Does any protocol compare a timestamp written by one node against another node's clock? What skew does it tolerate, and where is that stated? (F-10: none stated, none tolerated; overlap bounded by min(skew, one timeout).) |
| Margin mistaken for tolerance | Is an emergent margin (timeout minus refresh rate) being read as a guarantee? It bounds a punctual owner, not the protocol -- nothing makes an owner punctual. (F-10's model check overturned exactly this inference.) |
| Frozen-but-alive | What happens to leases held by a process that is stalled but not dead? Who refreshes them, on which thread? (F-13: the coordinator refreshes on its own thread, so a stalled handler keeps every claim.) |
| Rejoin under old identity | A node that crashes and returns inside the lease timeout: does it resume, or duplicate? A crash window shorter than the timeout is a restart, not a handover. |
| Guard inactive on first use | Monotonicity/anti-rewind guards seeded from a field that is null until first success: is the guard active on the first operation of every cycle? (F-21: the anti-rewind guard is skipped on the first store of every claim.) |

## Configuration

| Pitfall | The question to ask |
|---|---|
| Two paths, two defaults | Does every configuration path (core, Spring Boot, YAML) produce the same effective component? Diff every default pairwise. (F-1: gap settings swapped between paths; F-23: a non-null default makes the "unset" branch unreachable.) |
| Hardcoded durations | Which timing values are literals rather than settings, and does anything derive budgets from them? (F-5: a 60-second post-split block that no timescale compresses.) |
| Documented default is not the wired one | Grep the Javadoc's named default against the constructor's. (F-6.) |
| Compression edge | If timeouts are scaled for testing, which durations do not scale, and what breaks when a timeout is compressed below what the store can answer in? (`traps.md` section 6.) |

## Client-library and version skew

| Pitfall | The question to ask |
|---|---|
| Binary-compatibility break | Has an abstract method been added to a published SPI? `javac` accepts a call against the interface; the JVM refuses at first invocation. Run the compatibility gate before any container run. (F-18.) |
| Error-mapping collapse | Does the client map heterogeneous failures to one exception type? Does it keep the cause? (F-20.) |
| Coordinates and packages moved | The connector lives under `io.axoniq.framework`, not `org.axonframework`; its classes under `io.axoniq.framework.axonserver.connector.*` from 5.1.2. `javap` on an old name reports `class not found`, which reads as a broken artefact and is not one. |

## The read side and projections

| Pitfall | The question to ask |
|---|---|
| At-least-once assumed idempotent | Which handlers double-apply on redelivery, and which redeliveries are licensed? A conservation law over the projection catches double-processing nobody asserted. |
| Progress persisted separately from effects | Are the handler's effects and the progress marker in one transaction? If not, what re-reads the stale marker, and when? (Canary C6: caught only by measuring the rewind at re-claim.) |
| Quiescence undefined | "The read side caught up" -- against what count, whose answer, and can a permanently-lossy store produce the same observation as an interrupted run? (Canary C7 / F-16: loss must be decided on a stopped read side, not only on one that caught up.) |

---

# Part 2 -- the recurring sixteen

For each: decide whether it applies to the subsystem -- `y` / `n` / `maybe`. Every `y` and
most `maybe`s become a hypothesis row that names the claim or gap number it could falsify.
The template is a starting form; **a hypothesis full of placeholders is a hypothesis nobody
will test**, so replace every bracket with a concrete component, count or verb.

---

## 1. Lost updates under concurrent write plus partition

**Symptom.** Two clients update the same key; both writes return success; only one is
preserved. Or: a write returns success and is rolled back when the partition heals.

**Mechanism.** The system accepts writes on both sides of a partition without a quorum, then
has no merge policy when the partition heals -- or uses last-write-wins with skewed clocks.

**Where seen.** MongoDB (2013), Redis (2013), NuoDB (2013), RethinkDB (2016), Aerospike
(2015, 2018).

**Technique.** Consistency checking over a history -- register or counter operations under
partition nemeses. `technique-catalogue.md` section 1.

**Here.** The framework side of this is the append conflict check: two appenders whose
conditions overlap. F-19 is the non-partitioned form. The partitioned form -- a store that
accepts on both sides -- is a property of the store, which is exactly what the per-backend
vector is for.

```
H<n>. Lost update under partition plus recovery -- when a partition splits appenders
across two <store nodes> and both accept appends whose consistency conditions overlap, the
heal-side reconciliation could drop one of them.
Could falsify: C<x> (append durability), C<y> (the conflict check).
Suspected because: <store> claims <consistency level> and the merge policy for concurrent
appends on disjoint partition sides is <documented / inferred / unknown>.
```

---

## 2. Stale reads from followers or secondaries

**Symptom.** A client writes, is acknowledged, then reads from a follower and sees the old
value. Often presented as a performance feature; it violates monotonic-read or
read-your-write.

**Mechanism.** Async replication with unbounded follower lag, and a routing layer that does
not track which replica has seen the write.

**Where seen.** Elasticsearch (2014, 2015), etcd (2014), RabbitMQ (2014), MongoDB (2015),
Cassandra (2013), Kafka (2013).

**Technique.** Consistency checking with a read-write generator and a monotonic-read oracle.
For high throughput, also a metamorphic write-then-immediate-read relation.

**Here.** Sourcing an entity immediately after appending to it is exactly the
read-your-write shape, and it crosses whichever read path the store implements. **The suite
has no monotonic-read or session-consistency checker at all** -- see `oracle-patterns.md`
sections 7 and 8. This is an uncovered row.

```
H<n>. Stale read after append -- an entity sourced immediately after a successful append
returns a state older than that append, because the read is served by <a replica / a cache /
a different connection> that has not applied it.
Could falsify: C<x> (read-your-write on sourcing), C<y> (monotonic reads).
Suspected because: the store's replication is <sync / async> and the read path <does / does
not> carry the consistency marker of the client's last append.
```

---

## 3. Replica divergence after partition or restart

**Symptom.** Replicas converge to *different* states for the same key, with no reconciliation
path. A long-lived inconsistency that outlives the fault that caused it.

**Mechanism.** A partition or crash during the apply phase leaves divergent log tails.
Anti-entropy does not trigger, or has its own bug; there is no canonical winner.

**Where seen.** Aerospike (2015, 2018), Crate (2016), RethinkDB (2016), VoltDB (2016).

**Technique.** Partition plus kill nemeses, then after heal compare **every** replica's state
for **every** key. The oracle is pairwise equality. `oracle-patterns.md` section 13.

**Here.** Two forms. The store's own replicas -- a store property, read off the vector. And
the framework's own: **two token stores, or a token store and a projection, are replicas of
the same progress.** F-21's anti-rewind gap and Canary C6's rewind-at-re-claim are both
divergence between the effect and the marker for it.

```
H<n>. Progress divergence after crash -- after a kill and restart inside the claim
timeout, the stored token and the projection's actual applied set disagree, and nothing
reconciles them.
Could falsify: C<x> (progress durability), C<y> (at-least-once delivery).
Suspected because: the effect and the marker are written <in one transaction / separately>,
and the recovery path <re-reads / trusts> the marker.
```

---

## 4. Linearizability violation under wall-clock skew or timing

**Symptom.** A history of reads and writes that no sequential order explains: operation A
appears after B from one client and before B from another, in a way no linearizable schedule
allows.

**Mechanism.** Wall clock used to order events across nodes without a bounded skew. Or lease
expiry races; or epoch numbers that roll back.

**Where seen.** CockroachDB (2017), MariaDB Galera Cluster (2015, 2026), Percona XtraDB
Cluster (2015), Hazelcast (2017).

**Technique.** A linearizability checker over a register workload.

**Here.** F-10 is the lease-expiry form of this row, and its **model check overturned the
inference drawn from a correct measurement** -- see `traps.md`. The epoch-rollback form is
untested: nothing here checks that a token position never goes backwards across a claim
handover except F-21's guard, which is inactive on the first store of every claim.

```
H<n>. Safety breaks when the two clocks compared belong to different nodes -- <protocol>
compares a timestamp written by node A against node B's clock, with no stated bound, so under
skew of <delta> the decision <names the outcome>.
Could falsify: C<x> (the ordering or exclusion guarantee).
Suspected because: the comparison is <file:line>, the skew bound is <stated where? nowhere?>,
and nothing verifies it at run time.
```

---

## 5. Aborts of committed transactions -- lost acknowledgements

**Symptom.** The client sees success for a write or commit, then learns it did not persist or
was rolled back. Worse: the client sees an error and the write **did** persist.

**Mechanism.** The commit confirmation can be lost between server and client -- partition,
crash. Without a typed *unknown* error, the client has to guess.

**Where seen.** Across most published analyses; particularly bad in MongoDB (2017), RethinkDB
(2016), VoltDB (2016).

**Technique.** Kill and partition nemeses during commit. Track the client-observed verdict
against the server-side authoritative log. `oracle-patterns.md` section 10.

**Here.** F-20 is the framework-side form: **the client collapses every transport failure into
a consistency rejection and discards the cause**, so a caller cannot tell "rejected, do not
retry" from "unknown, retrying may duplicate". That is the exact defect this row predicts, and
it was found by reading rather than by a nemesis.

```
H<n>. Lost acknowledgement indistinguishable from lost append -- when the store commits an
append but the connection drops before the answer arrives, the caller cannot separate
"rejected, safe to retry" from "committed, retrying duplicates", because <the error type>
carries no retryable distinction.
Could falsify: C<x> (idempotency under retry), C<y> (durability of an acknowledged append).
Suspected because: <file:line> maps <n> distinct failure causes onto one exception and
<keeps / discards> the cause.
```

---

## 6. Reconfiguration and membership-change races

**Symptom.** Adding or removing a node during traffic loses committed writes, makes elections
oscillate, or leaves the cluster with no leader.

**Mechanism.** Joint-consensus implementations have edge cases when membership changes overlap
with elections, log catch-up, or other membership changes. A retried change races the
in-flight one and is rejected as "pending", and the original never completes -- so the cluster
wedges.

**Where seen.** RethinkDB (2016), Tendermint (2017), Redis-Raft (2020).

**Technique.** Drive membership changes concurrent with writes plus other nemeses. Plus the
crash-recovery technique for the recovery-state half.

**Here.** The framework's membership is **segment ownership**: a claim, an extension, a
release, a split, a merge. A split issued while a merge is in flight, or a claim retried after
a timeout when the first claim took effect, is precisely this row. F-9 is the first-writer form
at bootstrap. **A split racing a merge is not covered.**

```
H<n>. A segment operation is not idempotent under retry -- when a re-issued
<split / merge / claim> races the in-flight one, the loser is rejected and the original never
completes, leaving <n> segments unowned for ever.
Could falsify: C<x> (segment operations are safe under arbitrary retry), C<y> (liveness of
the read side).
Suspected because: the operation is <documented / inferred> idempotent, and the
retry-after-timeout path assumes the first call did not take effect.
```

---

## 7. Crash-recovery divergence

**Symptom.** A node crashes, restarts, and its post-recovery state differs from the rest.
Often involves partially-fsynced batches or incompletely-applied writes.

**Mechanism.** The fsync contract between application and OS or disk is misunderstood; or
recovery replays from a checkpoint that has its own corruption. A cache-miss path that skips
the persistent store is the common application-level form.

**Where seen.** Across many published analyses; central to the "Torturing Databases" line of
research.

**Technique.** Power-loss simulators, fsync-loss injection, in-process replay-equivalence
checks. `technique-catalogue.md` section 7, `oracle-patterns.md` section 3.

**Here.** We kill and restart. We do **not** enumerate the IO boundaries of an append and
crash at each one, and we have no replay-equivalence oracle. The snapshot path is the
highest-value target: a snapshot is a checkpoint, and a snapshot written from partially-applied
state is this row exactly.

```
H<n>. Recovery skips persisted operations -- a process killed between <persisting the
event> and <updating the in-memory or cached state>, on restart, fails to consult the store
for state the cache did not carry, and treats the operation as not done.
Could falsify: C<x> (durability of acknowledged appends), C<y> (idempotency across restart).
Suspected because: the rehydration path is <full / lazy / on demand> and the
cache-miss-then-store fallback is <consistent across all call sites / per-site discretionary>.
```

---

## 8. Schema migration during traffic

**Symptom.** Adding a column, changing a type, or dropping a constraint while writes are
flowing loses data, corrupts indexes, or breaks live reads.

**Mechanism.** Migrations are not coordinated with the write path, or coordinated only by
best-effort locks that do not extend across nodes.

**Where seen.** Most relational analyses (MySQL 2023, PostgreSQL 2020 and 2025 -- explicitly
verified safe modulo concurrent DDL); long-standing issues in schema-offering NoSQL systems.

**Technique.** Mix writes, reads and DDL concurrently. The oracle: a post-migration scan must
recover every write that returned success.

**Here.** **Wholly uncovered, and it applies.** The framework owns schema: the event table,
the token table, the snapshot table, the dead-letter table -- and it creates them, sometimes
concurrently from several instances (that is F-9). A token-table migration while processors
hold claims, or an event-table migration mid-append, is a realistic operator action nothing
here exercises.

```
H<n>. Online migration loses concurrent operations -- a schema change applied to the
<event / token / snapshot / dead-letter> table while operations are flowing drops or mangles
rows committed between the migration's logical start and end.
Could falsify: C<x> (durability), C<y> (the operational claim that a migration is
non-disruptive).
Suspected because: the migration mechanism <takes a lock / runs per-table / double-writes>,
and the post-migration verification is <byte-for-byte / approximate / absent>.
```

---

## 9. Identifier or sequence-number collision under partition

**Symptom.** Two clients on disjoint partition sides each take the "next" identifier and they
collide. Or, post-recovery, a previously-generated identifier is generated again.

**Mechanism.** The allocator depends on a centralised counter that is not partition-tolerant,
or on a wall clock without a bounded skew. Or the restart path trusts in-memory state instead
of re-reading the maximum persisted value.

**Where seen.** MongoDB (2013), VoltDB (2016).

**Technique.** A uniqueness property for the allocation; consistency checking for the
partition arm.

**Here.** The global sequence of an aggregate-based store is exactly a centralised counter,
and F-3 and F-16 are both about the gap machinery that exists because it is allocated before
commit. **Reuse after restart is untested**: nothing here kills the process between allocating
a sequence and committing it, then checks whether the value is minted twice.

```
H<n>. Sequence value reused after restart -- a restarted <writer> mints a sequence value a
prior incarnation already minted and possibly served, violating the gap-free or
unique-per-stream contract.
Could falsify: C<x> (unique monotone sequence), C<y> (no event is delivered twice under a
distinct position).
Suspected because: the allocator persists <every write / periodically> and the restart path
<re-reads the maximum / assumes in-memory state is canonical>.
```

---

## 10. Watch or change-feed event loss or duplication

**Symptom.** A change-feed consumer misses events that happened, or sees the same event twice
with no idempotency token. Especially bad under leader handoff.

**Mechanism.** Cursor management is per-replica; failover loses or duplicates the cursor
position.

**Where seen.** etcd (2014), Zookeeper (2013), MongoDB change streams, Kafka consumer-group
edge cases.

**Technique.** A watch oracle: after the run, **every committed write appears exactly once in
the consumer's observed sequence**. `oracle-patterns.md` section 9.

**Here.** This is the read side, and it is the suite's densest finding area: F-11 (a merge
rewinds to the lower token, redelivering), F-16 (an event never delivered at all), F-22 (an
event delivered once per segment). The row to still walk is the **resume** form: a stream
reopened after completion, or resumed at a token the store no longer holds.

```
H<n>. Stream resume drops or duplicates across handover -- when a segment changes owner,
the resume token lands <behind> (redelivery) or <ahead> (skip) of the position the previous
owner actually applied.
Could falsify: C<x> (at-least-once delivery), C<y> (progress durability across handover).
Suspected because: the token's scope is <per owner / global> and the handover <translates /
does not translate> it.
```

---

## 11. Cross-shard or cross-partition transaction non-atomicity

**Symptom.** A multi-key transaction commits some keys and not others; a read sees a partial
transaction.

**Mechanism.** The distributed commit protocol has edge cases under coordinator failure --
prepare-then-die.

**Where seen.** TiDB (2019), CockroachDB (2017), FaunaDB (2019), YugaByte DB (2019), Dgraph
(2018, 2020).

**Technique.** Anomaly detection over multi-key transactions; the oracle is the isolation
level claimed.

**Here.** A batch append is a multi-key transaction, and its keys are tags. **F-3 is the
partial-visibility half of this row already confirmed**: a strict prefix of a committing batch
is observable. The untested half is the coordinator-failure form: a process killed
mid-multi-tag append, and whether a subsequent sourcing sees part of it.

```
H<n>. Multi-tag append partially visible under a kill -- a process killed between
<the first and the last> write of one batch leaves a strict prefix durable and visible to a
subsequent sourcing.
Could falsify: C<x> (atomic batch visibility), C<y> (the consistency boundary).
Suspected because: the batch is committed <in one store transaction / per event>, and the
recovery path <rolls back / leaves> a partial batch.
```

---

## 12. Clock-skew-dependent safety violations

**Symptom.** Operations that should be ordered by real-time precedence are not, when one
node's clock jumps forward or back.

**Mechanism.** "Skew is bounded by X" built into safety, with no enforcement of the bound at
run time.

**Where seen.** CockroachDB (2017) is the famous case; a foundational concern generally
(Spanner avoids it by exposing the uncertainty interval instead).

**Technique.** A clock-adjustment nemesis, then a safety check that should survive a bounded
skew.

**Here.** F-10, measured and then model-checked. The trap worth repeating: **a process-global
clock seam is not a per-node seam**. Claim expiry here reads a single static clock reference
for the whole process, so setting "a node's" clock sets every node's -- which is why F-10 was
reproduced by shortening one node's view of the timeout instead, an algebraically equivalent
knob. See `method-essentials.md` section 5.

```
H<n>. Safety breaks when skew exceeds the assumed bound -- under an injected skew of more
than <delta>, the observed history violates <the ordering or exclusion guarantee> although no
message was lost.
Could falsify: C<x> (the safety bound on skew).
Suspected because: <component> assumes skew <= <delta> via <NTP / nothing>, and the run time
<does / does not> verify it before relying on it.
```

---

## 13. Authorisation, quota or metadata state divergence under partition

**Symptom.** Authorisation or quota state becomes inconsistent across nodes after a partition.
A principal is admitted on one node and denied on another. A quota is exceeded and nothing
notices.

**Mechanism.** That state is replicated by a different mechanism from the data state, with
weaker consistency.

**Where seen.** Less common in the narrow consistency-analysis corpus; central to many real
production incidents.

**Technique.** Assert convergence of the state post-partition; treat the quota counter as a
counter workload. And **the boundary discipline** -- see `boundary-and-isolation.md`, because
this row is a boundary claim and needs surface decomposition, not a single oracle.

**Here.** The framework's form is the **context or tenant**, and the metadata that scopes it:
which context a connection is bound to, which processing group owns which segment, which
configuration a node believes. A node holding a stale view of any of those is this row.
**Wholly uncovered.**

```
H<n>. Boundary metadata diverges under partition -- when a partition isolates the
<coordinator / control plane / configuration source>, each side's subsequent
<routing / admission / ownership> decisions diverge, and post-heal reconciliation keeps the
divergence rather than resolving it.
Could falsify: C<x> (context or tenant isolation), C<y> (single ownership per segment).
Suspected because: that state lives in <where> and its replication is
<sync / async / eventual>.
```

---

## 14. Lease expiry under contention

**Symptom.** A lease holder's renewal is slow -- because of unrelated contention -- so the
lease expires before the renewal returns. Ownership is forfeited although the holder is alive.

**Mechanism.** The renewal critical path shares a resource -- a connection pool, a lock, a
single thread -- with other work. A spike in the other work starves the renewal.

**Where seen.** Common in consensus implementations built on shared infrastructure.

**Technique.** Load the contended resource, then measure the renewal path's latency against
the lease window. This is the case where **a performance measurement is a safety oracle**.

**Here.** Directly applicable and **only half-covered**. F-13 is the *inverse* -- the
coordinator refreshes on its own thread, so a stalled handler keeps every claim. This row is
the other direction: the coordinator's own thread or the token store's connection pool
starved by the workload, so a live owner loses claims it holds. Nothing measures the renewal
path's latency against the claim timeout.

```
H<n>. Claim expires under unrelated contention -- when <the shared pool / the coordinator
thread> is saturated by <n> concurrent <operations>, claim extension on the shared path stalls
longer than the claim timeout, and the expiry check releases a live owner's segments.
Could falsify: C<x> (ownership stability under load), C<y> (the claim budget is sufficient
against worst-case store latency).
Suspected because: the extension path uses <a shared pool / a dedicated connection>; the
claim timeout is <t> against a worst-observed store latency of <m>.
```

---

## 15. Idempotency replay bypassed by a cold cache

**Symptom.** A retry with the same idempotency key, after the in-memory dedup state is cold --
post-restart, post-handover, post-eviction -- commits a **second** effect instead of replaying
the first.

**Mechanism.** The idempotency check is "look in the cache; on a miss, proceed", with no
fallback to the persistent record.

**Where seen.** A recurring application-level shape rather than a store-level one.

**Technique.** Restart between the original and the retry; the oracle is a conflict error or a
replay of the original result.

**Here.** The framework's dedup surfaces are the **consistency marker**, the **snapshot**, and
whatever a handler keeps in memory. F-21's guard, seeded from a field that is null until the
first success, is precisely a cold-state bypass: the guard is skipped on the first store of
every claim. The generalisation to walk: **every guard seeded from mutable state has a cold
window, and the cold window recurs at every handover, not only at startup.**

```
H<n>. A guard is inactive on the first operation of every cycle -- after
<restart / handover / eviction>, the <monotonicity / dedup / anti-rewind> check has no prior
value to compare against and admits an operation it would otherwise reject.
Could falsify: C<x> (the monotonicity or exactly-once claim).
Suspected because: the guard reads <field>, which is <null / absent> until the first success,
and the cycle restarts on every <claim / handover / reconnect>.
```

---

## 16. Asynchronous queue head-of-line block on a missing referent

**Symptom.** A background queue -- an outbox, an index-apply loop, a replication log, a
dead-letter queue -- stalls on the first entry it cannot process, blocking every later entry
behind it.

**Mechanism.** A defensive early return on an unrepairable entry, instead of skip-advance-and-
continue. Common when a cleanup process removes data out from under an in-flight queue.

**Where seen.** A recurring application-level shape.

**Technique.** A no-head-of-line-block property; plus the crash-recovery technique for the
purged-out-from-under variant.

**Here.** Two live surfaces. The **dead-letter queue**, which is a queue whose entries can
reference state a later operation removed -- and which the suite has never touched. And the
**event processor** itself: F-7 is this row already confirmed in its harshest form -- an
unresolvable routing key throws per message, so the read side delivers nothing for ever, and
nothing counts the stall. Note the observability half of the row: **a stall with no counter
reads as a quiet system.**

```
H<n>. The queue stalls on one unprocessable entry -- when entry N references state that
<a purge / a snapshot / a merge> has removed, the loop returns without advancing past N,
blocking N+1 onward indefinitely, with no counter an operator could see.
Could falsify: C<x> (eventual processing of every enqueued item), C<y> (observability of a
stall).
Suspected because: the loop has an early-return path for a missing referent at <file:line>
that neither advances the cursor nor increments a counter.
```

---

## How to walk it

One row at a time, three columns: the pitfall, whether it applies (`y` / `maybe` / `partial` /
`n/a` / `deferred`), and the hypothesis it produces with the claim and gap numbers it targets.
Rules, from `claims-and-scenarios.md` section 2: record the rows that do not apply with the
reason; every hypothesis names its claims; a `maybe` is a real answer; reserve a name for a
pitfall you cannot target yet.

The rows above are a floor, not a ceiling. A subsystem introduces failure modes of its own --
the walk ends with "and what does *this* subsystem add that none of these rows covers?"

## How this catalogue grows

Add a row to part 2 when a failure family appears across three or more independent analyses or
gets a public post-mortem. Add a row to part 1 when a finding here does not fit an existing
row -- and cite the finding, so the abstract row keeps a concrete face. Drop a "where seen"
list that grows past five entries.
