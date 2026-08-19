# DCB Concepts

Theory behind Dynamic Consistency Boundaries, independent of any framework. Sources:
https://dcb.events/ and the article "Dynamic Consistency Boundaries"
(https://milan.event-thinking.io/2025/05/dynamic-consistency-boundaries.html), which builds on
Sara Pellegrini's "Killing the Aggregate".

## The problem: aggregate == stream is rigid

Traditional event sourcing maps one aggregate to one event stream, and uses that stream as the
consistency boundary (the unit of optimistic locking). This has three structural problems:

1. **Inflexibility.** Once a boundary is in production it is hard to change. Splitting a stream
   preserves order, but merging streams requires manual migration and business coordination.
2. **Invariants that do not fit one stream.** Some rules span entities (e.g. "a course holds at
   most N students" and "a student joins at most M courses" must both hold for one subscribe
   action). With aggregate=stream you cannot enforce both transactionally; you fall back to a
   process manager / saga and eventual consistency, publishing compensating events and tolerating
   temporary inconsistency.
3. **One event, one stream.** A single fact that is relevant to several decisions has to be
   copied across streams, because an event belongs to exactly one stream.

The root cause is that the consistency boundary is chosen *once*, up front, at storage-layout
time -- and the granularity is almost never right for every decision.

## The idea: move the boundary from the stream to the query

DCB redefines the granularity of consistency from "event stream" to "the set of events selected
by a query." Events live in a single global, ordered log. A *decision* declares which events it
depends on by a query over event types and tags. That query *is* the consistency boundary, chosen
per decision, dynamically.

## The protocol: optimistic locking with a query-shaped lock

Three phases:

1. **Read.** The client reads only the events matching its criteria, and receives a
   **consistency marker** -- the store's head position at read time (the position the next
   appended event will take).
2. **Decide.** The client folds the read events into whatever in-memory state the rule needs, then
   runs the rule and produces zero or more new events.
3. **Append.** The client sends the new events together with (a) the *same criteria* used to read
   and (b) the consistency marker. The store appends only if **no event matching the criteria was
   stored after the marker**. If a conflicting event slipped in between read and append, the
   append is rejected and the command is retried (re-read, re-decide, re-append).

The criteria does double duty: it is the read filter and it defines what counts as a conflict.

## Glossary

- **Global sequence.** The unique, monotonic position of an event in the single global log. The
  first event is at position 0.
- **Head.** The global sequence the *next* appended event will occupy. Captured at read time, it
  becomes the consistency marker.
- **Tail.** The position of the first (oldest) stored event still retained.
- **Tag.** A key/value pair classifying an event, e.g. `{key: "courseId", value: "c1"}` or
  `{key: "studentId", value: "s1"}`. An event may carry several tags. Tags are how the query
  selects events; they are NOT separate streams.
- **Criterion.** One building block of a query: a set of tags (all of which must be present --
  AND) optionally restricted to a set of event types (the event's type must be one of them --
  OR among the types). An event matches a criterion when it has all the criterion's tags and its
  type is in the criterion's type set.
- **Criteria.** A collection of criterions combined with OR -- an event matches the criteria if it
  matches at least one criterion.
- **Consistency marker.** The head value captured during read, replayed at append time so the
  store can detect concurrent modifications within the criteria.
- **Decision model.** The transient state a command handler folds from the sourced events in order
  to evaluate the rule. It exists only for the duration of the decision; it is not persisted as a
  first-class entity.

## A worked criteria

For "subscribe student `xyz` to course `abc`", the decision depends on: the course's capacity
changes, who is already subscribed to course `abc`, and which courses student `xyz` is already in.
That is three criterions ORed together:

```
anyOf(
  allOf(type("CourseCapacityChanged"),     tag("courseId", "abc")),
  allOf(type("StudentSubscribedToCourse"),  tag("courseId", "abc")),
  allOf(type("StudentSubscribedToCourse"),  tag("studentId", "xyz"))
)
```

This sources from the global log exactly: capacity changes for course `abc`, subscriptions to
course `abc` (to count occupancy), and subscriptions by student `xyz` (to count their course
load). Nothing else. The conflict surface is exactly those same events.

## Benefits

- **Smaller reads.** Source only the events a decision needs, not a whole aggregate's history.
- **Flexible refactoring.** The global log is untouched; you change a boundary by changing a
  query, not by restructuring streams.
- **Cross-entity invariants without sagas.** Rules spanning entities can be enforced in one
  transactional decision instead of via eventually-consistent process managers.
- **Lower contention when scoped well.** Narrow criteria mean fewer transactions conflict.

## Caveats

- A criteria that is too broad behaves like an over-large aggregate: frequent false conflicts and
  contention. (The difference from aggregates is that you can adjust the criteria without
  restructuring storage.)
- A criteria that is too narrow silently drops an invariant -- the rule reads from an incomplete
  picture. Modeling the criteria correctly is the core design task.
- The store only filters on type and tags. Counting, thresholds, and content predicates are the
  decision model's job, computed by folding the sourced events client-side.
