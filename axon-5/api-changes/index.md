# Axon Framework 5 — API Changes

As is to be expected of a new major release, a lot of things have changed compared to the previous major release.
This guide collects all changes that may prove breaking to users when migrating from Axon Framework 4 to 5.
Some changes have a low chance of directly impacting users (like the [Message Stream](03-messages-and-stream.md#message-stream)),
while others certainly impact all users (like the [Test Fixture](07-entities-and-test-fixtures.md#test-fixtures) adjustment).

## Table of Contents

### Overview

| # | File | Topic |
|---|------|-------|
| 01 | [Overview and Dependencies](01-overview-and-dependencies.md) | Document structure, version/dependency requirements (JDK 21, Spring Boot 3+, Jakarta), and high-level summary of all breaking change areas |

### Major API Changes

| # | File | Topic |
|---|------|-------|
| 02 | [ProcessingContext and Unit of Work](02-processing-context.md) | `UnitOfWork` → `ProcessingContext` / `ProcessingLifecycle` rewrite; legacy components |
| 03 | [Messages and MessageStream](03-messages-and-stream.md) | `MessageType`, `QualifiedName`, `Metadata` string values, getter renames, `Message` conversion, `MessageStream`, async-native overview |
| 04 | [Commands](04-commands.md) | `CommandBus`, `CommandGateway`, `CommandDispatcher`; removed `CommandCallback`, `DisruptorCommandBus` |
| 05 | [Event Store and Event Processors](05-event-store-and-processors.md) | DCB / `EventCriteria`, `EventStoreTransaction`, appending/sourcing/streaming, JPA and Axon Server storage engines; `PooledStreamingEventProcessor`, sequencing policies |
| 06 | [ApplicationConfigurer and Configuration](06-configuration.md) | New layered `ApplicationConfigurer` API: `ComponentBuilder`, `ComponentDecorator`, `ConfigurationEnhancer`, `ModuleBuilder`, `ComponentFactory`, lifecycle management |
| 07 | [Entities (Aggregates) and Test Fixtures](07-entities-and-test-fixtures.md) | Aggregates → Entities, `EntityMetamodel`, immutable entities, `@EntityCreator`, creational command handlers, Spring `@EventSourced`; `AxonTestFixture` |
| 08 | [Serialization, Conversion, and Interceptors](08-serialization-and-interceptors.md) | `Serializer` → `Converter`, `JacksonConverter` default, XStream removal, `MessageTypeResolver`; interceptor interface and registration changes |

### Minor API Changes

| # | File | Topic |
|---|------|-------|
| 09 | [Queries, Event Handling, and Minor Changes](09-queries-and-minor-changes.md) | `QueryBus` / `QueryGateway` async-native, `ResponseType` removal, subscription queries, `QueryUpdateEmitter`; event handling multi-handler behavior; all minor API changes |

### Stored Format Changes

| # | File | Topic |
|---|------|-------|
| 10 | [Stored Format Changes](10-stored-format-changes.md) | Database schema changes: JPA event entry, dead letters, deadlines, token store |

### Class and Method Reference

| # | File | Topic |
|---|------|-------|
| 11 | [Class Reference](11-class-reference.md) | Reference tables: package renames, moved/renamed classes, removed classes, marked-for-removal, `implements`/`extends` changes, constants |
| 12 | [Method Reference](12-method-reference.md) | Reference tables: constructor parameter changes, moved/renamed methods, removed methods, changed return types |