# Axon Framework 5 — API Changes: Event Store and Event Processors

> Part of the Axon Framework 4→5 migration guide.
> Covers: Dynamic Consistency Boundary (DCB), `EventCriteria` / `Tag` model, `EventStoreTransaction`,
> appending and sourcing events, `StreamableEventSource`, `EventStorageEngine` async-native changes,
> JPA (`AggregateBasedJpaEventStorageEngine`) and Axon Server storage engine migration;
> removal of `TrackingEventProcessor` in favor of `PooledStreamingEventProcessor`,
> Processing Group layer removal, and `SequencingPolicy` configuration with `@SequencingPolicy`.

## Event Store

The `EventStore` has seen a rigorous change in Axon Framework 5 to accompany the Dynamic Consistency Boundary.

The Dynamic Consistency Boundary, or DCB for short, allows for a flexible boundary to what should be appended
consistently with other existing event streams in the event store. In doing so, it eliminates the focus on the
aggregate identifier, replacing it for user defined "tags." Note that tags are plural. As such, an event is no longer
either attached to zero or one aggregate/entity, but potentially several.

This shift will provide greater flexibility in deriving models, as there is no longer a hard boundary around the
aggregate stream. It allows users to depend on N-"aggregate" streams in one sourcing operation, allowing commands to
span a more complete view.

To not overencumber the sourcing operation, not only tags, but also (event) "types" are used during event store
operation. The types act as a filter on the entity streams that matching the tags. The tags and the types combined from
the `EventCriteria`. It is this `EventCriteria` that Axon Framework uses
for appending events, sourcing events, and streaming events.

It is the `EventCriteria` that thus allows you to define "slices" of an otherwise potentially large aggregate model.
Events that (although part of the entity's stream) don't influence the decision-making process, can be omitted when
sourcing an entity.

As becomes apparent, this is a rather massive changes for those interacting directly with the `EventStore` API from Axon
Framework. Luckily, most users will not interact with this infrastructure component directly. Although this shift
removes the aggregate focus entirely, it does not remove the option to use aggregates. It is purely the internals of
appending, sourcing, and streaming that shift from a 0-or-1 event stream focus to a 0-N event stream solution.

### Appending Events

In the past, you would use the `EventStore#publish` operation to publish events. To ensure the event would be part of an
aggregate stream, users would deal with the `AggregateLifecycle#apply` operation. This used, internally, a `ThreadLocal`
to find the "active" aggregate model, providing the `apply` operation knowledge about the aggregate identifier and
sequence number.

To append events in Axon Framework 5, users first need to start an `EventStoreTransaction` with an active
`ProcessingContext` (see [Unit of Work](02-processing-context.md#unit-of-work) for more on the `ProcessingContext`).
From there, to append, you would use the `EventStoreTransaction#appendEvent(EventMessage)` operation. To make it so that
appending events are part of an aggregate / consistency boundary that's active, users would first invoke
`EventStoreTransaction#source(SourcingCondition)` (as further explained [here](#sourcing-events)). It is the act of
sourcing that instructs Axon Framework to make a matching `AppendCondition` to use during appending events.

In code, this would like so:

```java
public void appendEvents(EventStore eventStore,
                         ProcessingContext context,
                         EventMessage<?> event) {
    EventStoreTransaction transaction = eventStore.transaction(context);
    transaction.appendEvent(event);
}
```

As stated in [Unit of Work](02-processing-context.md#unit-of-work), the `ProcessingContext` is propagated throughout Axon Framework. As such, it
is **always** available in message handling functions.

Note that above is the technical solution, applicable only to those interacting with the `EventStore` directly. To
publish events as part of an entity, an `EventAppender` can be injected in command handling methods. On an
`@CommandHandler` annotated method, this would look as follows:

```java

@CommandHandler
public void handle(SubscribeStudentCommand command,
                   EventAppender appender) {
    StudentSubscribedEvent event = this.decide(command);
    appender.append(event);
}
```

### Sourcing Events

In the past, to source an aggregate, the `EventStore#readEvents(String aggregateIdentifier)` method or
`EventStore#readEvents(String aggregateIdentifier, Long firstSequenceNumber)` method was used. Since events are no
longer attached to a single aggregate, neither exist as is anymore.

Instead, the `EventStoreTransaction`, that is also used for [appending events](#appending-events), should be used to
source an entity. More specifically, the `EventStoreTransaction#source(SourcingCondition)` method should be invoked. The
`SourcingCondition` in turn contains the `EventCriteria` to source for, as well as that it is able to define a start and
end position.

If you want to source an (old-fashioned) aggregate, the `EventCriteria` contains a single `Tag` of key `aggregateId` and
a value matching the aggregate to source. In code, this would look as follows:

```java
public void sourcingEvents(EventStore eventStore,
                           ProcessingContext context) {
    Tag aggregateIdTag = new Tag("aggregateId", UUID.randomUUID().toString());
    EventCriteria criteria = EventCriteria.havingTags(aggregateIdTag);
    SourcingCondition sourcingCondition = SourcingCondition.conditionFor(criteria);

    EventStoreTransaction transaction = eventStore.transaction(context);
    MessageStream<? extends EventMessage<?>> sourcedEvents = transaction.source(sourcingCondition);
    // Process the sourced events as desired...
}
```

Note that we do not expect users to source an aggregate / entity manually like this. Axon Framework has extensive
support to define both state-based and event-sourced entities, ensuring all components are in place such that you
*never* have to create any form of condition.

### Streaming Events

In the past, to stream events, the `StreamableMessageSource#openStream(TrackingToken)` method (which the `EventStore`
implements) would be used. This behavior shifted to align with a DCB-based event store. This means we now expect a
condition with an `EventCriteria`, referring to several tags and types. For streaming events, the most feasible filter
are the types, as event streaming is intended to create query models.

To stream events, Axon Framework 5 has replaced the `StreamableMessageSource` for a `StreamableEventSource`.
Furthermore, the `open` operation no longer expects a `TrackingToken`, but a `StreamingCondition` instead. When invoked
manually (thus without the use of Event Processors), this would look as such:

```java
public void streamingEvents(
        StreamableEventSource<EventMessage<?>> streamableEventSource
) throws ExecutionException, InterruptedException, TimeoutException {
    CompletableFuture<TrackingToken> asyncToken = streamableEventSource.headToken();
    TrackingToken trackingToken = asyncToken.get(500, TimeUnit.MILLISECONDS);
    StreamingCondition streamingCondition = StreamingCondition.startingFrom(trackingToken);

    MessageStream<EventMessage<?>> eventStream = streamableEventSource.open(streamingCondition);
    // Process the event stream as desired...
}
```

### Event Storage

#### Generic `EventStorageEngine` changes

The `EventStorageEngine` is now specific to the `StorageEngineBackedEventStore` and
aligns with the changes made to this store and `StreamableMessageSource` to service both
interfaces correctly.

As such, it's API now uses asynchronous operations throughout - all methods now return
`CompletableFuture` objects instead of blocking calls. Furthermore, event appending requires an `AppendCondition` and
uses
`TaggedEventMessage` objects, with operations wrapped in an `AppendTransaction` that must be explicitly committed or
rolled back for better transactional control. The `TaggedEventMessage` wraps an `EventMessage` and a set of `Tags` that
are important labels of the `EventMessage`.

Event retrieval is consolidated into two primary methods: `source()` for finite streams and `stream()` for infinite
streams. Both use condition objects (the aforementioned `SourcingCondition` in [appending events](#appending-events) and
`StreamingCondition` in [streaming events](#streaming-events)) to specify filtering and positioning
logic, replacing direct parameter-based methods. Token creation is streamlined with `firstToken()`, `latestToken()`, and
`tokenAt()` methods that all return futures.

Although the chance is slim users are hit with the API change on the `EventStorageEngine` itself, as we recommend event
reading and writing through higher level APIs, if you are hit, we recommend a manual transition. Both when directly
using the  `EventStorageEngine` or with a manual implementation of the old `EventStorageEngine`.
During such a migration, you'll need to handle async operations with `CompletableFutures`, replace direct append calls
with transactional ones using conditions, and convert aggregate-specific reads to condition-based sourcing calls. The
API removes aggregate-specific methods and snapshot functionality in favor of the more generic condition-based approach.

#### JPA based Event Storage

If you've chosen a JPA-based event storage solution in pre-Axon-Framework-5, that means you need to switch from the
`JpaEventStorageEngine` to the `AggregateBasedJpaEventStorageEngine`. Any changes to the `EventStorageEngine` API are
described shortly [here](#generic-eventstorageengine-changes).

We have introduced an entirely new JPA entry for the `AggregateBasedJpaEventStorageEngine`, called the
`AggregateBasedJpaEntry`. This entry has numerous difference compared to the `DomainEventEntry` used by the
`JpaEventStorageEngine`. For one, the layering of the `DomainEventEntry`, which had four abstract classes and two
interfaces (marked for removal [here](11-class-reference.md#removed-classes)) and will not return for the `AggregateBasedJpaEntry`.
Furthermore, next to the class name, resolution in a table rename, several columns have been renamed. Please see
the [Stored Format Changes](10-stored-format-changes.md#stored-format-changes) section for more details on the actual changes.

Besides the entry, the construction of the storage engine changed slightly as well.
The previously used builder-pattern now only remains for the customizable fields, whereas the necessary fields are
simple required parameters of the constructor of the `AggregateBasedJpaEventStorageEngine`. The customizable fields (
like gap timeouts and batch size) can be found in the `AggregateBasedJpaEventStorageEngineConfiguration`.

#### Axon Server based Event Storage

The `AxonServerEventStore` has been removed entirely, in favor of two new `EventStorageEngine` implementations dedicated
to Axon Server.
These are:

1. The `AggregateBasedAxonServerEventStorageEngine` - mandatory for aggregate-based event store formats.
2. The `AxonServerEventStorageEngine` - mandatory for DCB-based event store formats.

As was the case for Axon Framework 4, whenever the `axon-server-connector` is on the classpath, Axon Framework will
default to Axon Server for commands, events, and queries. To disable this default, the `axon-server-connector` can once
more be excluded, or it can be disabled in the `AxonServerConfiguration`. Whenever Axon Server is present, Axon
Framework will assume you want a DCB-based event store. As such, it will construct an `AxonServerEventStorageEngine` by
default.

For green field projects this suffices. For those migrating, be mindful that the stored format of Axon Server needs to
align with DCB for it to work with the `AxonServerEventStorageEngine`!
If the stored format still relies on the aggregate-based format, be sure to configure the
`AggregateBasedAxonServerEventStorageEngine` instead.

## Event Processors

The `EventProcessingModule` (along with the `EventProcessingConfigurer` and `EventProcessingConfiguration` interfaces
that were implemented by this class) has been removed from the framework. To configure default settings for Event
Processors and register instances, use the `MessagingConfigurer#eventProcessing` method.

### Processing Group layer removal

The `ProcessingGroup` layer has been removed from the framework. This layer was used to group Event Handlers to be
assigned to a single Event Processor.
The new configuration API just allows you to register Event Handlers directly to an Event Processor with the following
syntax:

```java
public void configurePSEP() {
    EventProcessorModule.pooledStreaming("when-student-enrolled-to-max-courses-then-send-notification")
                        .eventHandlingComponents(components -> components.declarative(eventHandler1)
                                                                         .autodetected(eventHandler2))
                        .notCustomized();
}
```

With this usage the `eventHandler1` and `eventHandler2` will be assigned to the same Event Processor with the name
`when-student-enrolled-to-max-courses-then-send-notification`.
It's an equivalent of the `@ProcessingGroup("when-student-enrolled-to-max-courses-then-send-notification")` annotation
before.

### TrackingEventProcessor Removal

The `TrackingEventProcessor` has been removed from the framework, with `PooledStreamingEventProcessor` taking over as
the default streaming event processor. The main difference between these processors lies in their threading model, but
the benefits of the PooledStreaming event processor far outweighed the Tracking one.

In the `PooledStreamingEventProcessor` there is a much lower IO overhead, and more segments can be processed in parallel
with the same resources. The processor uses one thread pool to read the event stream and another thread pool to process
the events, so it reads the stream only once regardless of segment count. For example, when processing 8 segments on a
single instance, instead of reading the event stream 8 times, it now reads it once. In the contrary, the
`TrackingEventProcessor` opens a separate event stream per segment it claims.

The pooled streaming processor has one limitation: segments process as fast as the slowest segment. However, this minor
disadvantage is outweighed by the `PooledStreamingEventProcessor` advantages and does not warrant maintaining the
`TrackingEventProcessor`. Users who previously configured `TrackingEventProcessor` instances or used `tracking` mode in
Spring Boot configuration should migrate to `PooledStreamingEventProcessor`.

### SequencingPolicy Configuration

While using Dynamic Consistency Boundary (DCB) instead of the Aggregate approach, the framework cannot
ensure proper event ordering by default. To be on the safe side and avoid any potential out-of-order processing
issues, we set `SequentialPolicy` by default (for events published by Aggregates it's still
`SequentialPerAggregatePolicy`), which means that events are processed in the order they were published.
This is not efficient because you cannot distribute the processing of events across different `EventProcessor` segments.
But it's straightforward to tune the behavior. The new `@SequencingPolicy` annotation allows declaring sequencing
policies on event handler methods or classes. Alternatively, you can use the declarative approach with the builder
pattern. The most useful approach with DCB might be the `PropertySequencingPolicy`, which allows you to process events
in order when they have the same value for a certain property. For example, you can process `StudentEnrolledEvent`s in
order when they have the same `courseId` property, because they are related to the same course, but allow parallel
processing of events that are related to different courses.

```java
// Annotation approach
@EventHandler
@SequencingPolicy(type = PropertySequencingPolicy.class, properties = {"courseId"})
public void handle(StudentEnrolledEvent event) {
    // Handler logic
}

// Declarative approach
EventHandlingComponent component = new DefaultEventHandlingComponentBuilder(baseComponent)
        .sequencingPolicy(new PropertySequencingPolicy(StudentEnrolledEvent.class, "courseId"))
        .handles(new QualifiedName(StudentEnrolledEvent.class), /* event handling method*/)
        .build();
```

The annotation can be defined on the class or method level.
For comprehensive usage examples and configuration options, see the `@SequencingPolicy` JavaDoc documentation.

