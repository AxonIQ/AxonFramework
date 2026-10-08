# Axon Framework 5 — API Changes: Stored Format Changes

> Part of the Axon Framework 4→5 migration guide.
> Covers: database schema changes that require migration scripts.
> Sections: JPA event entry rename (`domain_event_entry` → `aggregate_event_entry`) and column renames,
> Dead Letter table column renames (JPA and JDBC), the unchanged deadline job layout
> (JobRunr, Quartz, db-scheduler), and TokenStore new `mask` column.

Stored Format Changes
=====================

## Events

The JPA `org.axonframework.eventsourcing.eventstore.jpa.DomainEventEntry` is replaced entirely for the
`org.axonframework.eventsourcing.eventstore.jpa.AggregateEventEntry`.
This thus changes the default table name from `domain_event_entry` to `aggregate_event_entry`.

Besides the entry and table rename, several columns have been renamed compared to the `DomainEventEntry`, being:

1. `DomainEventEntry#eventIdentifier` (inherited from `AbstractEventEntry`) is now called
   `AggregateEventEntry#identifier`.
2. `DomainEventEntry#payloadType` (inherited from `AbstractEventEntry`) is now called `AggregateEventEntry#type`.
3. `DomainEventEntry#payloadRevision` (inherited from `AbstractEventEntry`) is now called `AggregateEventEntry#version`.
4. `DomainEventEntry#timeStamp` (inherited from `AbstractEventEntry`) is now called `AggregateEventEntry#timestamp`.
5. `DomainEventEntry#type` (inherited from `AbstractDomainEventEntry`) is now called
   `AggregateEventEntry#aggregateType`.
6. `DomainEventEntry#sequenceNumber` (inherited from `AbstractDomainEventEntry`) is now called
   `AggregateEventEntry#aggregateSequenceNumber`.
7. `DomainEventEntry#metaData` (inherited from `AbstractEventEntry`) is now called `AggregateEventEntry#metadata`.

Furthermore, some of the expectations placed on the fields have adjusted, being:

1. The `payloadRevision`, renamed to `version`, is **not** optional anymore.
2. The `payload` field no longer has a max column length of 10_000.
3. The `metadata` field no longer has a max column length of 10_000.
4. The `aggregateIdentifier` **is** optional right now.
5. The `sequenceNumber`, renamed to `aggregateSequenceNumber`, is **not** optional anymore.

Lastly, the sequence generator for the global index (resulting in the event's position in the event store) has been
specified in more detail for the `AggregateEventEntry`. The `DomainEventEntry` had a simple `@GeneratedValue`. With
the upgrade from Hibernate 5 to Hibernate 6, this caused issues, as the default sequence generator configuration
changed. Notable changes were switching to an automated generator type, using a unique sequence generator per table and
a default allocation size of 50.

The automated generator type selection is not ideal for Axon Framework. Hence, this is fixed to a sequence-based
generator.
The 'generator-per-table' is desired and as such specified for the `AggregateEventEntry` under the sequence name
`aggregate-event-global-index-sequence`. The default allocation size of 50 is far from desired, however. This
introduces large amounts of gaps, which will slow down event streaming to event processors. Hence, the allocation size
is fixed to 1 to minimize the amount of gaps. Although this enforces a round trip to the database to retrieve the
`AggregateEventEntry#globalIndex` for **every** event that is being appended, this outweighs the concerns on
consuming events through the `EventStorageEngine#stream(StreamingCondition)` method tremendously.

## Dead Letters

1. The JPA `org.axonframework.messaging.jpa.deadletter.eventhandling.DeadLetterEventEntry` has renamed the `messageType`
   column to `eventType`.
2. The JPA `org.axonframework.messaging.jpa.deadletter.eventhandling.DeadLetterEventEntry` has renamed the `type` column
   to `aggregateType`.
3. The JPA `org.axonframework.messaging.jpa.deadletter.eventhandling.DeadLetterEventEntry` expects the `QualifiedName`
   to be present under the `type` column, non-nullable.
4. The JDBC `org.axonframework.messaging.jdbc.deadletter.eventhandling.DeadLetterSchema` has renamed the `messageType`
   column to `eventType`.
5. The JDBC `org.axonframework.messaging.jdbc.deadletter.eventhandling.DeadLetterSchema` has renamed the `type` column
   to `aggregateType`.
6. The JDBC `org.axonframework.messaging.jdbc.deadletter.eventhandling.DeadLetterSchema` expects the `QualifiedName` to
   be present under the `type` column, non-nullable.

## Deadlines

The Quartz, JobRunr and db-scheduler deadline managers in `axoniq-legacy` keep the Axon Framework 4.13 job layout:
every `JobDataMap` key, details field, job signature and task name is unchanged, and no `QualifiedName` is stored.
Deadlines that Axon Framework 4 scheduled therefore fire without migration, and during a rolling upgrade Axon Framework 4
and Axon Framework 5 nodes fire and cancel each other's jobs. The `MessageType` of a fired deadline is derived from its
payload class.

The payload, metadata and scope descriptor are read and written with the `Converter` given to the manager's builder.
It has to match the `Serializer` the Axon Framework 4 deadline manager used, including its configuration:

- **Jackson:** a `JacksonConverter` configured like the Axon Framework 4 `ObjectMapper`. The Spring Boot
  auto-configuration of the JobRunr and db-scheduler deadline managers uses the `EventConverter`, as Axon Framework 4
  used the event serializer.
- **XStream:** the Quartz deadline manager defaulted to an `XStreamSerializer`, and so did Spring Boot applications
  without `axon.serializer.*` properties. Their jobs are XStream XML, which only an
  `org.axonframework.conversion.xstream.XStreamConverter`, configured with the application's own `XStream` instance,
  reads; see the reference guide's Conversion page, XStreamConverter section. In Spring Boot, this means defining the
  `DeadlineManager` bean.
- **Java serialization:** jobs written by Axon Framework 4's `JavaSerializer` are not readable.

Further differences when reading a job:

- A payload type name that does not resolve to a class, such as an XStream alias or a class that was renamed or
  removed, fires the deadline with an `org.axonframework.deadline.UnknownDeadlinePayload`. It holds the stored type
  name, revision and data.
- Metadata values become strings: numbers and booleans as `String.valueOf(...)`, nested maps and lists as JSON. An Axon
  Framework 4 node reading a job written by Axon Framework 5 sees every metadata value as a string.
- The stored revision is read but not used. A deadline payload is not upcast.
- Quartz jobs in the Axon Framework 3.3 layout, with the `serializedDeadlineMessage` key, are not readable and fail with
  a `DeadlineException`.

`axoniq-legacy` manages its own versions of Quartz, JobRunr and db-scheduler. Both Axon Framework versions share the
scheduler library's own store during a rolling upgrade, so run the same library version on all nodes.

## TokenStore

1. A `mask` column containing the mask associated with each segment was added to avoid
   having to query all segments in order to calculate it.

## Sagas

The saga tables are unchanged, so an Axon Framework 4 saga table can be read and written by `axoniq-legacy` without
migration, provided the `serializedSaga` column holds JSON. That requires the Axon Framework 4 node's `Serializer` to
have been Jackson, whether configured explicitly or defaulted by Spring Boot auto-configuration. Two further columns
need a closer look: `sagaType`, which is a condition on that statement, and `revision`, whose contents changed.

### The `serializedSaga` column (XStream)

Many applications never configured a `Serializer` for the legacy saga stores built without Spring Boot
auto-configuration, which then defaulted to XStream, so in that configuration the `serializedSaga` column is XStream
XML rather than JSON. No `axoniq-legacy` `Converter` reads XStream XML unless it is an
`org.axonframework.conversion.xstream.XStreamConverter`, configured with the application's own `XStream` instance; see
the reference guide's Conversion page, XStreamConverter section, for how to configure one. Without it, loading such a
saga fails with a `ConversionException` naming the saga type, rather than returning a saga with default field values.

### The `sagaType` column

Axon Framework 4 derived this column, and the value it matched against when finding a saga, through the `Serializer`:
`serializer.serialize(saga).getType().getName()` on write and `serializer.typeForClass(sagaType).getName()` on read.
`axoniq-legacy` uses the class name directly on both sides.

For the default configuration those are the same string, so nothing changes: the Jackson serializer returned the class
name, and so did XStream for a class without an alias. An application that mapped its saga classes to some other type
name, an XStream alias being the usual way to get one, has rows whose `sagaType` column holds that alias. Those rows are
not reachable through `axoniq-legacy`, because `findSagas`, and the association queries behind loading and deleting, match
the column literally against the class name. The saga row itself still loads by identifier, but without its
associations, so it can never be routed an event.

Such a table needs its `sagaType` columns rewritten to the class name before use, in both the saga entry and the
association value entry tables:

```sql
UPDATE SagaEntry            SET sagaType = 'com.example.OrderSaga' WHERE sagaType = 'order-saga';
UPDATE AssociationValueEntry SET sagaType = 'com.example.OrderSaga' WHERE sagaType = 'order-saga';
```

Reading a saga back changed with it. Axon Framework 4 resolved the class from the stored `sagaType`, so
`serializer.deserialize` returned whatever the row said. `axoniq-legacy` converts into the class the caller asked for and
ignores the stored name. In the saga flow those are the same class, since a saga is found by an association query that
already filtered on it, so this is not separately observable; it only means the stored name is no longer what selects
the type.

### The `revision` column

Axon Framework 4 filled it from the saga class's `@Revision` value, which the `Serializer` resolved, and rewrote it on
every save. The `Converter` has no revision concept, and nothing has ever read the value back: the revision was half of
a `SerializedType`, which is what an upcaster chain matches on, and saga stores never ran an upcaster chain.

1. An `INSERT` writes the constant `SagaEntry.LEGACY_REVISION`, currently `"axon-legacy"`, marking the row as one this
   module created.
2. An `UPDATE` leaves the column alone, so a revision written by Axon Framework 4 survives rather than being replaced.
3. No query reads the column.
4. Schema creation retains the column, so a table created here is one an Axon Framework 4 application can still read.

Two consequences are visible only from outside the store. A row created here has no `@Revision`-derived value where
Axon Framework 4 would have recorded one, and a row Axon Framework 4 created keeps the revision from its last Axon
Framework 4 write rather than tracking the current class. A migration script or support query reading the column
directly will see that. A `revision` column narrowed to `NOT NULL`, which the Axon Framework 4 schema does not do, still
accepts these inserts.

