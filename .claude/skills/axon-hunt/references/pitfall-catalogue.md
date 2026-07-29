# The pitfall catalogue -- failure modes to walk against a new subsystem

**This file is self-contained.** It is the hypothesis-generation catalogue that step 3 of
`extending.md` walks: one row at a time, against the subsystem being covered, recording a
verdict for every row **including the rows that do not apply and why**. The walk this suite
already did -- with per-row verdicts and the hypotheses each hit produced -- is tabulated in
`docs/testing-plans/axon-hunt.md` section 4; extend that table rather than re-walking rows it
already answers.

The catalogue is organised by claim kind, because the kind picks the checker
(`method-essentials.md` section 2). Findings this suite has already made are cited as instances,
so each abstract row has at least one concrete face.

---

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

## How to walk it

One row at a time, three columns: the pitfall, whether it applies (`y` / `maybe` / `partial` /
`n/a` / `deferred`), and the hypothesis it produces with the claim and gap numbers it targets.
Rules, from `claims-and-scenarios.md` section 2: record the rows that do not apply with the
reason; every hypothesis names its claims; a `maybe` is a real answer; reserve a name for a
pitfall you cannot target yet.

The rows above are a floor, not a ceiling. A subsystem introduces failure modes of its own --
the walk ends with "and what does *this* subsystem add that none of these rows covers?"
