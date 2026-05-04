# Axon Framework 5 — API Changes (Split Reference)

This directory contains the Axon Framework 4→5 breaking change guide split into focused files,
optimized as inputs for LLM-assisted migration skills.

## Files

| # | File | Topic | Lines (original) |
|---|------|-------|-----------------|
| 01 | [01-overview-and-dependencies.md](01-overview-and-dependencies.md) | Document structure, version/dependency requirements, and high-level summary of all breaking change areas | 1–84 |
| 02 | [02-processing-context.md](02-processing-context.md) | `UnitOfWork` → `ProcessingContext` / `ProcessingLifecycle` rewrite; legacy components | 85–168 |
| 03 | [03-messages-and-stream.md](03-messages-and-stream.md) | `MessageType`, `QualifiedName`, `Metadata` string values, getter renames, `Message` conversion, `MessageStream`, async-native overview | 169–322 |
| 04 | [04-commands.md](04-commands.md) | `CommandBus`, `CommandGateway`, `CommandDispatcher`; removed `CommandCallback`, `DisruptorCommandBus` | 323–433 |
| 05 | [05-event-store-and-processors.md](05-event-store-and-processors.md) | DCB / `EventCriteria`, `EventStoreTransaction`, appending/sourcing/streaming, JPA and Axon Server storage engines; `PooledStreamingEventProcessor`, sequencing policies | 434–699 |
| 06 | [06-configuration.md](06-configuration.md) | New layered `ApplicationConfigurer` API: `ComponentBuilder`, `ComponentDecorator`, `ConfigurationEnhancer`, `ModuleBuilder`, `ComponentFactory`, lifecycle management | 700–1038 |
| 07 | [07-entities-and-test-fixtures.md](07-entities-and-test-fixtures.md) | Aggregates → Entities, `EntityMetamodel`, immutable entities, `@EntityCreator`, creational command handlers, Spring `@EventSourced`; `AxonTestFixture` | 1039–1438 |
| 08 | [08-serialization-and-interceptors.md](08-serialization-and-interceptors.md) | `Serializer` → `Converter`, `JacksonConverter` default, XStream removal, `MessageTypeResolver`; interceptor interface and registration changes | 1439–1585 |
| 09 | [09-queries-and-minor-changes.md](09-queries-and-minor-changes.md) | `QueryBus` / `QueryGateway` async-native, `ResponseType` removal, subscription queries, `QueryUpdateEmitter`; event handling multi-handler behavior; all minor API changes | 1586–1843 |
| 10 | [10-stored-format-changes.md](10-stored-format-changes.md) | Database schema changes: JPA event entry, dead letters, deadlines, token store | 1844–1919 |
| 11 | [11-class-reference.md](11-class-reference.md) | Reference tables: package renames, moved/renamed classes, removed classes, marked-for-removal, `implements`/`extends` changes, constants | 1920–2224 |
| 12 | [12-method-reference.md](12-method-reference.md) | Reference tables: constructor parameter changes, moved/renamed methods, removed methods, changed return types | 2225–2436 |

## Usage as Migration Skill Inputs

- **Files 01–09**: Conceptual migration — load the relevant file(s) for the area you are migrating.
- **Files 10**: Schema migration — load when writing Flyway/Liquibase scripts.
- **Files 11–12**: Quick lookup tables — load alongside a conceptual file when you need exact class/method renames.

The original (unsplit) file is at `../api-changes.md`.
