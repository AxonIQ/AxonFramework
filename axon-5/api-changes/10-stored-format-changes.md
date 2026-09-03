# Axon Framework 5 — API Changes: Stored Format Changes

> Part of the Axon Framework 4→5 migration guide.
> Covers: database schema changes that require migration scripts.
> Sections: JPA event entry rename (`domain_event_entry` → `aggregate_event_entry`) and column renames,
> Dead Letter table column renames (JPA and JDBC), Deadline scheduler format changes
> (JobRunr, Quartz, dbscheduler), and TokenStore new `mask` column.

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

1. The JobRunr `org.axonframework.deadline.jobrunr.DeadlineDetails` expects the `QualifiedName` to be present under the
   field `type`.
2. The Quartz `org.axonframework.deadline.quartz.DeadlineJob` expects the QualifiedName to be present in the
   `JobDataMap` under the key `qualifiedType`.
3. The dbscheduler `org.axonframework.deadline.dbscheduler.DbSchedulerBinaryDeadlineDetails` expects the `QualifiedName`
   to be present under the field `t`.
4. The dbscheduler `org.axonframework.deadline.dbscheduler.DbSchedulerHumanReadableDeadlineDetails` expects the
   `QualifiedName` to be present under the field `type`.

## TokenStore

1. A `mask` column containing the mask associated with each segment was added to avoid
   having to query all segments in order to calculate it.

## Sagas

The saga tables are unchanged, so an Axon Framework 4 saga table can be read and written by `axon-legacy` without
migration in the default configuration. Two columns need a closer look: `sagaType`, which is a condition on that
statement, and `revision`, whose contents changed.

### The `sagaType` column

Axon Framework 4 derived this column, and the value it matched against when finding a saga, through the `Serializer`:
`serializer.serialize(saga).getType().getName()` on write and `serializer.typeForClass(sagaType).getName()` on read.
`axon-legacy` uses the class name directly on both sides.

For the default configuration those are the same string, so nothing changes: the Jackson serializer returned the class
name, and so did XStream for a class without an alias. An application that mapped its saga classes to some other type
name, an XStream alias being the usual way to get one, has rows whose `sagaType` column holds that alias. Those rows are
not reachable through `axon-legacy`, because `findSagas`, and the association queries behind loading and deleting, match
the column literally against the class name. The saga row itself still loads by identifier, but without its
associations, so it can never be routed an event.

Such a table needs its `sagaType` columns rewritten to the class name before use, in both the saga entry and the
association value entry tables:

```sql
UPDATE SagaEntry            SET sagaType = 'com.example.OrderSaga' WHERE sagaType = 'order-saga';
UPDATE AssociationValueEntry SET sagaType = 'com.example.OrderSaga' WHERE sagaType = 'order-saga';
```

Reading a saga back changed with it. Axon Framework 4 resolved the class from the stored `sagaType`, so
`serializer.deserialize` returned whatever the row said. `axon-legacy` converts into the class the caller asked for and
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

