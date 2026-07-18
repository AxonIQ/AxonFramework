# DCB in Axon Framework 5

How the framework implements the source -> decide -> append loop. Two layers: the high-level
annotation-based modeling API (what most application code uses) and the lower-level contracts it
is built on.

Package note: the criteria/tag primitives live in `messaging`
(`org.axonframework.messaging.eventstreaming`), while the event-store conditions and markers live
in `eventsourcing` (`org.axonframework.eventsourcing.eventstore`). The annotations live in
`org.axonframework.eventsourcing.annotation`.

## Layer 1: annotation-based modeling (application code)

This is the DCB loop expressed declaratively. See `references/examples.md` for the full
university-example code; the pieces:

### Tag events with `@EventTag`

Put tags on the event payload so the store can classify the event when it is appended. The tag
key defaults to the field/accessor name; set `key` to control it.

```java
public record StudentSubscribedToCourse(
        @EventTag(key = FacultyTags.STUDENT_ID) String studentId,
        @EventTag(key = FacultyTags.COURSE_ID)  CourseId courseId
) {}
```

`@EventTag` is repeatable (container `@EventTags`) and works on fields and methods. An
`AnnotationBasedTagResolver` reads these at append time. Iterable fields yield one tag per
element; Map fields map keys to tag keys (or use the provided `key` for every value).

### Define the decision model with `@EventSourcedEntity`

A plain class whose `@EventSourcingHandler` methods fold sourced events into the state the rule
needs. It is the "decision model": built per command, not a persisted aggregate.

```java
@EventSourcedEntity
static class State {
    // fields the rule inspects ...
    @EntityCreator public State() {}

    @EventSourcingHandler void evolve(CourseCreated e) { /* ... */ }
    @EventSourcingHandler void evolve(StudentSubscribedToCourse e) { /* ... */ }
    // ... one evolve per event the criteria sources
}
```

### Declare the DCB query with `@EventCriteriaBuilder`

A static method on the entity that turns the entity's id into the `EventCriteria` used to source
it. This IS the consistency boundary for decisions on this model.

```java
@EventCriteriaBuilder
private static EventCriteria resolveCriteria(SubscriptionId id) {
    return EventCriteria.either(
        EventCriteria.havingTags(Tag.of(FacultyTags.COURSE_ID, id.courseId().toString()))
                     .andBeingOneOfTypes(CourseCreated.class.getName(),
                                         CourseCapacityChanged.class.getName(),
                                         StudentSubscribedToCourse.class.getName(),
                                         StudentUnsubscribedFromCourse.class.getName()),
        EventCriteria.havingTags(Tag.of(FacultyTags.STUDENT_ID, id.studentId()))
                     .andBeingOneOfTypes(StudentEnrolledInFaculty.class.getName(),
                                         StudentSubscribedToCourse.class.getName(),
                                         StudentUnsubscribedFromCourse.class.getName())
    );
}
```

If no `@EventCriteriaBuilder` is present, the default resolver uses the `tagKey()` from
`@EventSourcedEntity` (or the entity's simple name) with `id.toString()` as the tag value.

### Decide in the command handler with `@InjectEntity` + `EventAppender`

The framework sources the entity (read phase), injects it, the handler decides, and
`EventAppender.append(...)` performs the append phase under the entity's criteria + marker.

```java
@CommandHandler
void handle(SubscribeStudentToCourse cmd, @InjectEntity State state, EventAppender appender) {
    List<StudentSubscribedToCourse> events = decide(cmd, state); // throws on rule violation
    appender.append(events);
}
```

### Wire it up

```java
var entity = EventSourcedEntityModule.autodetected(SubscriptionId.class, State.class);
var commands = CommandHandlingModule.named("SubscribeStudent")
        .commandHandlers()
        .autodetectedCommandHandlingComponent(c -> new SubscribeStudentToCourseCommandHandler());
configurer.registerEntity(entity).registerCommandHandlingModule(commands);
```

## Layer 2: the underlying contracts

These are what the annotation layer builds on. Reach for them when explaining mechanics or when
writing programmatic (non-annotation) code.

### Criteria and tags (`org.axonframework.messaging.eventstreaming`)

- `Tag` -- record; `Tag.of(String key, String value)`.
- `EventCriteria` -- sealed interface, the framework's criteria.
  - `EventCriteria.havingTags(Tag... | String...)` -> a tag-only criterion (returns an
    `EventTypeRestrictableEventCriteria`).
  - `.andBeingOneOfTypes(String... typeNames)` -> restrict that criterion to event types.
  - `EventCriteria.havingAnyTag()` -> match regardless of tags (e.g. for a global processor).
  - `EventCriteria.either(EventCriteria...)` and `criteria.or(other)` -> OR composition.
  - `Set<EventCriterion> flatten()` and `boolean matches(QualifiedName type, Set<Tag> tags)`.

### Conditions and markers (`org.axonframework.eventsourcing.eventstore`)

- `SourcingCondition` -- the read request: wraps an `EventCriteria` (and a sourcing strategy /
  start position). `SourcingCondition.conditionFor(criteria)`.
- `AppendCondition` -- the write request: a `ConsistencyMarker` plus the `EventCriteria` that
  defines conflicts. `AppendCondition.none()` (for events outside any boundary),
  `AppendCondition.withCriteria(criteria)`, `.withMarker(marker)`, `.orCriteria(criteria)`.
- `ConsistencyMarker` -- the marker/head abstraction. Constants `ORIGIN` (any matching event is a
  conflict) and `INFINITY`. `GlobalIndexConsistencyMarker` carries a single global position;
  `AggregateBasedConsistencyMarker` tracks per-aggregate sequence numbers (compatibility path).
- `TagResolver` -- `Set<Tag> resolve(EventMessage event)`; `AnnotationBasedTagResolver` reads
  `@EventTag`/`@EventTags`; `MultiTagResolver`, `PayloadBasedTagResolver`,
  `MetadataBasedTagResolver` are alternatives.
- `TaggedEventMessage` -- an `EventMessage` paired with its `Set<Tag>`.

### The event store and transaction

- `EventStore` -- `EventStoreTransaction transaction(ProcessingContext context)`.
- `EventStoreTransaction` -- `source(SourcingCondition)` returns a `MessageStream` of events whose
  terminal element carries the `ConsistencyMarker`; `appendEvent(EventMessage)` queues an event;
  `setConditionOverride(...)` adjusts the append condition.
- `EventStorageEngine` (`@Internal` SPI) -- `appendEvents(AppendCondition, context, List<TaggedEventMessage>)`,
  `source(SourcingCondition)`, `stream(StreamingCondition)`. The `AppendTransaction` it returns has
  `commit()` / `rollback()` / `afterCommit(...)`; the consistency check happens at prepare-commit
  and rejects with an append-rejected exception on conflict.
- `EventSourcingRepository<ID, E>` + `CriteriaResolver<I>` (`EventCriteria resolve(I id, context)`)
  -- the programmatic equivalent of `@EventSourcedEntity` + `@EventCriteriaBuilder`.

## End-to-end flow (what the framework does per command)

1. Resolve the entity id from the command; `CriteriaResolver` / `@EventCriteriaBuilder` turns it
   into an `EventCriteria`.
2. Build a `SourcingCondition` and `source(...)` it on the `EventStoreTransaction`. Fold the
   stream through `@EventSourcingHandler` evolve methods into the decision model; capture the
   `ConsistencyMarker` from the stream's terminal element.
3. Invoke the `@CommandHandler` with the injected state; it decides and calls
   `EventAppender.append(...)`.
4. The framework tags each new event (`TagResolver`), builds an `AppendCondition` from the marker
   + criteria, and `appendEvents(...)`. On prepare-commit the engine verifies no matching event
   appeared after the marker; conflict -> reject -> retry.

## Testing

Use `AxonTestFixture` (test module) with `given().event(...).when().command(...).then().success().events(...)`.
The `given` events are stored with their tags, so the criteria-based sourcing in the handler is
exercised exactly as in production. See `examples/university-java/.../CourseCreationTest.java`.
