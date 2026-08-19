---
name: dynamic-consistency-boundaries
description: >
  Explain and reason about Dynamic Consistency Boundaries (DCB) and how Axon Framework 5 and
  Axon Server implement them. Use when the user asks about "DCB", "dynamic consistency boundary",
  "consistency boundary", "killing the aggregate", modeling consistency across multiple entities,
  "EventCriteria", "@EventCriteriaBuilder", "@EventTag", "@EventSourcedEntity", "Tag", tags vs
  streams, "AppendCondition", "SourcingCondition", "ConsistencyMarker", optimistic concurrency in
  the event store, how to choose a consistency boundary, why an aggregate is too big or too small,
  or how Axon Server's DCB event store (dcb.proto) sources and appends events with criteria.
---

# Dynamic Consistency Boundaries (DCB)

A reference skill for explaining DCB and mapping the concept onto Axon Framework 5 and the
Axon Server DCB event store. Use it to answer "what / why / how" questions and to ground
explanations in the real framework types and the public Axon Server gRPC API.

## What DCB is, in one paragraph

Classic event sourcing fixes the consistency boundary to the aggregate: one aggregate maps to
one event stream, and that stream is the unit of optimistic locking. DCB (introduced by Sara
Pellegrini, "Killing the Aggregate") moves the boundary from the *stream* to a *set of events
selected by a query*. Instead of "load aggregate X, append to stream X", you "source exactly the
events that matter for this decision (selected by tags and event types), decide, then append on
the condition that no new matching event appeared since you read." The boundary is defined per
decision, dynamically, rather than baked into the storage layout.

## When to use this skill

- The user asks what DCB is, why it exists, or how it differs from aggregates/streams/sagas.
- The user is modeling an invariant that spans more than one entity (e.g. "a course holds at most
  N students" AND "a student joins at most M courses") and is unsure where the boundary goes.
- The user asks how a specific Axon Framework 5 type or annotation relates to DCB.
- The user asks what the Axon Server DCB event store can and cannot do.

This skill is for **explaining and designing**. It is not a code generator; when a question is
about concepts, prefer a clear explanation grounded in the references below over writing files.

## The mental model: source -> decide -> append

DCB is optimistic concurrency control with a query-shaped lock:

1. **Source** the events matching a `criteria` (tags + event types). The store also returns a
   **consistency marker** -- the position of the head at read time.
2. **Decide**: fold those events into a decision model (current state) and run the business rule
   to produce new events.
3. **Append** the new events together with the *same criteria* and the marker. The store accepts
   the append only if no event matching the criteria was stored after the marker; otherwise it
   rejects and the command is retried.

The criteria is both the *read filter* and the *write conflict definition*. Keep it as narrow as
the invariant allows: too broad means false conflicts and contention; too narrow means a missed
invariant.

## Terminology map: DCB <-> Axon Framework 5 <-> Axon Server

| DCB concept | Axon Framework 5 | Axon Server (dcb.proto) |
|---|---|---|
| Tag (key/value classifier on an event) | `Tag.of(key, value)` (`org.axonframework.messaging.eventstreaming.Tag`); declaratively via `@EventTag` on event fields | `Tag { bytes key; bytes value; }` |
| Criterion (AND of tags, restricted to event types) | `EventCriteria.havingTags(...).andBeingOneOfTypes(...)` | `TagsAndNamesCriterion { repeated string name; repeated Tag tag; }` |
| Criteria (OR of criterions) | `EventCriteria.either(...)` / `.or(...)` | `repeated Criterion` |
| The DCB query for a decision model | `@EventCriteriaBuilder` method on an `@EventSourcedEntity` | `criterion` list in `SourceEventsRequest` |
| Decision model / current state | `@EventSourcedEntity` + `@EventSourcingHandler` evolve methods | (client-side fold; not a server concept) |
| Read phase | `SourcingCondition` -> `EventStoreTransaction.source(...)` | `Source(SourceEventsRequest)` |
| Consistency marker / head | `ConsistencyMarker` (e.g. `GlobalIndexConsistencyMarker`) | `consistency_marker` / `GetHead` |
| Append phase with conflict check | `AppendCondition` (marker + criteria) -> `EventAppender.append(...)` | `Append(stream AppendEventsRequest)` with `ConsistencyCondition` |
| Conflict rejected | append transaction rejected -> command retried | append fails the consistency condition |

## How to answer a DCB question

1. **Classify** the question: theory (why/what), Axon Framework usage (which type/annotation),
   Axon Server capability (what the API allows), or modeling ("where does the boundary go?").
2. **Load the matching reference** (see below) and ground the answer in real types and method
   names -- do not invent API. When citing framework behavior, point at the real example files in
   `examples/university-java/`.
3. **For modeling questions**, work through the source -> decide -> append loop explicitly:
   what events does the rule depend on, what tags select them, what is the narrowest criteria,
   and what is the resulting conflict surface.
4. **Verify before asserting**: package names and signatures in the references reflect AF5 at
   write time; if a question hinges on an exact signature, open the file and confirm it still
   matches before stating it as fact.

## References

Load the one that fits the question; do not read all of them up front.

- `references/concepts.md` -- DCB theory: the problem with aggregate=stream, the three-phase
  optimistic-locking model, the full glossary (global sequence, head, tag, criterion, criteria,
  consistency marker), and the benefits/caveats. Sources: dcb.events and the "Dynamic Consistency
  Boundaries" article.
- `references/axon-framework-api.md` -- the AF5 type map with fully-qualified names, the
  annotation-based flow (`@EventSourcedEntity`, `@EventCriteriaBuilder`, `@EventTag`,
  `@InjectEntity`, `EventAppender`), the lower-level contracts (`EventCriteria`, `Tag`,
  `SourcingCondition`, `AppendCondition`, `ConsistencyMarker`, `EventStoreTransaction`,
  `EventStorageEngine`), and how they compose.
- `references/axon-server-api.md` -- the verbatim Axon Server DCB gRPC surface (services, RPCs,
  messages) and an explicit "what is possible / what is not" derived from it.
- `references/examples.md` -- worked patterns: the course-subscription invariant (full code from
  the university example), plus canonical DCB.events patterns (unique username, sequential
  invoice number) modeled with Axon Framework criteria.

## Common misconceptions to correct

- **"DCB removes consistency / is eventual consistency."** No. DCB gives *strong* consistency for
  the boundary you select per decision; it just stops forcing that boundary to equal an aggregate
  stream.
- **"A tag is a stream."** No. Events are stored in one global log; tags are classifiers used to
  *select* events at query time. One event can carry several tags and thus participate in several
  boundaries.
- **"The criteria filters on payload/metadata content."** No. Axon Server matches only on event
  **name** (type) and **tags**. Anything finer (counting, ranges, content predicates) is done by
  folding the sourced events client-side in the decision model.
- **"Criteria semantics are all AND."** Within one criterion, tags are ANDed and types are an
  OR-set; across criterions the criteria is ORed. See the API references for the exact rule.
