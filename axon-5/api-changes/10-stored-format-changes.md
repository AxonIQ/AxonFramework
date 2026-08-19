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

