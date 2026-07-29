# DCB semantics a checker or a model must encode

**This file is self-sufficient** for writing or reviewing anything that decides whether an
append conflicts: a checker, a reference-model rule, or a TLA+ operator. It deliberately does
not carry the design rationale of the boundary concept itself (why an aggregate is the wrong
unit, how to choose a boundary) -- that is a modelling question rather than a checking one, and
nothing in this suite needs it.

The normative in-repo statement of these rules is the reference-model rule table in
`formal/INVARIANTS.md` section 3.1, with the engine `file:line` evidence for each. Read that
before quoting a rule; this file explains the semantics so you can tell whether your encoding
is right.

## The protocol in one paragraph

A command handler **sources** events under a `SourcingCondition` carrying an `EventCriteria`,
decides, and **appends** new events under an `AppendCondition` carrying an `EventCriteria` and
a `ConsistencyMarker`. The marker is a position in the store's global order. The append is
accepted exactly when no event **at or after** the marker matches the criteria. That is the
whole of the concurrency control: a boundary over tags plus a position, evaluated at append
time. There is no aggregate and no per-aggregate sequence number.

## The rules, and the mistakes each one prevents

### Matching

1. **A criterion's tags match by `containsAll`.** An event matches a criterion only when it
   carries **every** tag the criterion names. It may carry more.
   *Mistake it prevents:* treating tag matching as intersection. A criterion naming two tags
   does not match an event carrying one of them.

2. **A criterion naming types matches only those types; a criterion naming none matches any
   type.** The type set is an additional filter on top of the tags, and an empty type set is
   "any", not "none".

3. **A boundary matches when any of its criteria match, and an empty boundary matches
   everything.** Disjunction across criteria, conjunction within one.
   *Mistake it prevents:* encoding an empty criteria set as "matches nothing". A criteria with
   no tags and no types flattens to no criteria and matches every event, which is why this
   suite's reference model encodes it as an empty set and keeps the OR rule uniform.

### The scan range

4. **The conflict scan covers stored events at positions greater than or equal to the marker.**
   At or after, not after.
   *Mistake it prevents:* an off-by-one that stops the event sitting exactly at the marker from
   counting as a conflict. That is a real defect shape -- it was planted as a canary and caught,
   at a fiftieth of the volume of the blunt version, which is exactly why the subtle one is
   worth planting.

5. **ORIGIN resolves to position -1**, so an ORIGIN-anchored append conflicts with **anything**
   already stored that matches the criteria: the scan covers the whole store.

6. **INFINITY bypasses the scan entirely.** An append anchored at INFINITY is accepted without
   scanning. That is what "append with no consistency condition" means, and the invariant
   `UnconditionalAppendNeverRejected` is exactly this rule stated as a guarantee.

### The decision and its effects

7. **The append is accepted exactly when the scan finds no match.** Iff, in both directions: an
   accepted append must have had no match, and a rejected one must have had one.

8. **An accepted batch occupies consecutive positions starting at the store head, assigned in
   offer order.**

9. **The marker an accepted append reports is one past its last position; an empty batch
   reports ORIGIN.**

10. **A rejected append stores none of its batch.** No partial batch. This is a separate
    invariant (`RejectedAppendLeavesNoEvents`) because it is decided against an authoritative
    scan of the store rather than against the model.

### Sourcing

11. **Sourcing returns every matching event at or after the start position, in ascending
    position order.**

12. **The marker a sourcing reports is the store head at the moment it read** -- independent of
    the boundary and of what matched. This is the one that surprises people: the marker is not
    "the position of the last matching event", it is the head.

## Positions, sentinels and the arithmetic

Get these wrong and a checker computes a boundary the engine never used.

| Thing | Value |
|---|---|
| First position in a store | 0 |
| Head of an empty store | 0, **not** ORIGIN |
| Marker an accepted append reports | last assigned position + 1 |
| Marker a sourcing reports | last key + 1, or 0 when the store is empty |
| Marker an **empty** batch reports | ORIGIN |
| ORIGIN as a position | -1 |
| INFINITY as a position | the maximum long |
| `INFINITY.position()` | **throws** -- never call it |
| `ORIGIN.lowerBound(x)` | always ORIGIN; the sentinels absorb rather than compare |
| `INFINITY.upperBound(x)` | always INFINITY |
| A stream start of "the beginning" | resolves to the minimum long, which the engine clamps at 0. Never use the raw value in arithmetic. |

So the "head" is the store's size, and an empty store reports 0 while an empty batch reports
ORIGIN. Those are two different things and both appear in histories.

## Traps that have actually bitten a checker here

- **The no-criteria factory is not called `none()`.** `AppendCondition.none()` exists and is a
  different thing: it carries INFINITY plus the match-everything criteria. The criteria-level
  match-everything factory is a separate call, and a checker that assumes the wrong one encodes
  the wrong boundary.

- **The interpreted form and the flattened form can disagree.** The match-everything singleton
  answers `true` to every match and flattens to the **empty set**. The fluent builders collapse
  that case away, but constructing a disjunction explicitly can produce a boundary whose
  interpreted match is universally true while its flattened form yields only the other
  criterion. An engine that builds a query from the flattened form -- which is what flattening
  exists for -- computes a **narrower** boundary than the interpreted form. That is a live
  cross-backend divergence hypothesis and it is not yet chased; the generator in the
  differential never emits an empty criterion, so it cannot hit it by accident.

- **A conflicting append can fail at either of two points.** The engine detects the conflict
  early, when the events are offered, and again under its lock at commit. Both are the same
  rejection. Code driving the engine must treat a failure from *either* call as a rejection, or
  it reports the early-detection case as a crash.

- **The condition the storage engine receives is derived, never the one the caller built.** To
  exercise each shape from a workload: append **without sourcing** for an INFINITY-anchored
  unconditional append; source then append for a marker at the sourced position with criteria
  OR-ed from the sourcings; and override the append condition explicitly for an ORIGIN-anchored
  append with criteria.

- **An injected failure is not a protocol rejection.** Only the store's own rejection exception
  means the protocol said no. Recording every failure the same way turns every faulted run into
  a protocol violation.

- **The framework filters the terminal entry on the transaction's own source call, but not on
  the engine's.** A sourcing stream's terminal entry carries the consistency marker and a
  placeholder message rather than a real event. Distinguish it by whether the marker resource is
  present on the entry, not by the message type.

## Where an aggregate-based store diverges, and why it matters to a checker

The aggregate-based engine does not implement this protocol. Its append condition is a map from
aggregate identifier to sequence number, not a boundary over tags plus a position, and its
marker resolves ORIGIN and INFINITY to the **same** empty map -- so it cannot tell "append with
no condition" from "append only if this boundary is empty". Two consequences a checker must
encode:

1. **The reference model must report itself not applicable on such a store.** Replaying its
   history against a model of a different protocol reports the difference in protocol as a
   defect on **every append**. The backend declares which protocol it speaks, and the checker
   reads that.
2. **The divergence is a real finding, and it is over-rejection only.** An unconditional append
   succeeds exactly once per aggregate and is refused for ever after. Exhaustive model checking
   at small bounds establishes that the collapsed marker never *accepts* an append the protocol
   forbids and never lets a reported marker go backwards -- which a suite run cannot establish,
   because it can only report the divergences it happened to produce.

Also worth knowing before writing a workload for such a store: sourcing twice in one processing
context throws, because the framework combines the two markers with an operation the
aggregate-based marker does not implement. Ordinary event-sourced command handling reaches it.

## What the reference model deliberately does not say

`DcbStoreModel` is **sequential**. It defines what the store contains once an operation has
taken effect, and says nothing about what a concurrent reader may observe while a batch is
being committed. That blind spot is real and is where a partial-batch-visibility finding lives.

**Do not fix the model by adding concurrency to it.** Its sequentiality is what makes it
comparable against a formal specification of the same protocol. Check concurrent-observation
properties against the real engine, with a separate invariant.
