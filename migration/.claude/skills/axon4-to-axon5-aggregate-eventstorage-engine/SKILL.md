---
name: axon4-to-axon5-aggregate-eventstorage-engine
description: >-
  Configure the Axon Framework 5 aggregate-centric `EventStorageEngine` for a
  project migrating from Axon Framework 4. Picks the right AF5 implementation
  based on what AF4 used — `AggregateBasedJpaEventStorageEngine` (which also
  needs a SQL table migration: `domain_event_entry` →
  `aggregate_event_entry`, plus column renames) for JPA projects, or
  `AggregateBasedAxonServerEventStorageEngine` (auto-registered by the
  Axoniq Spring Boot starter — no code change needed when Spring is present)
  for Axon Server projects. Without Spring Boot, wires the engine via
  `EventSourcingConfigurer.create().componentRegistry(...)`. Atomic — one
  storage-engine wiring per run, with the matching schema migration (or an
  explicit "schema migration deferred to ops" hand-off) emitted alongside.
---

# AF4 → AF5: aggregate-centric `EventStorageEngine` configuration

Atomic migration of the **event store backend** for one project: pick the
right AF5 `EventStorageEngine` implementation, wire it up, and (for JPA)
emit the schema migration. AF4 used `EmbeddedEventStore` +
`JpaEventStorageEngine` / a `JdbcEventStorageEngine` / an Axon Server
backed engine; AF5 collapses that to a single bean of type
`org.axonframework.eventsourcing.eventstore.EventStorageEngine`.

> **Keep this skill generic.** It runs across many target projects.
> Project-specific quirks (custom converters, custom persistence
> exception resolvers, multi-datasource setups) live in the target
> project's `.axon4-to-axon5-migration/learnings.md`, never inline here.

## Where this fits in the migration

This skill is **phase 9** in the orchestrator (`axon4-to-axon5-migration`),
sitting between `axon4-to-axon5-writeconfiguration` (phase 8) and the
stabilization sweep (phase 10). It comes after configuration writers
because the storage-engine bean is itself a configuration bean — most
projects declare it in the same Spring `@Configuration` class that the
writeconfiguration skill has just rewritten. Running this phase last
keeps the configuration class stable while you swap the engine.

## Decision tree — which AF5 engine?

Inspect the AF4 wiring before changing anything. The mapping is:

| AF4 wiring observed | AF5 target | Notes |
|---|---|---|
| `JpaEventStorageEngine.builder()…build()` (often inside `EmbeddedEventStore`) | `AggregateBasedJpaEventStorageEngine` | **Schema migration required** — see Path A. |
| `AxonServerEventStore` / `AxonServer*EventStore` autoconfigured by `axon-spring-boot-starter` | `AggregateBasedAxonServerEventStorageEngine` | Auto-registered by `axoniq-spring-boot-starter`'s `AxonServerAutoConfiguration`. **Just remove the AF4 bean** — see Path B. |
| `JdbcEventStorageEngine.builder()…build()` | *No drop-in equivalent in AF5 yet* | Stop, surface to the user. Options: (1) move to Axon Server (Path B), (2) move to JPA + Hibernate over the same DB (Path A), (3) defer until AF5 ships a JDBC equivalent. |
| Custom `EventStorageEngine` subclass | Reimplement on top of `AggregateBasedJpaEventStorageEngine` or `AggregateBasedAxonServerEventStorageEngine` | Out of scope for one atomic invocation — open a follow-up issue. |

Detection in the target project (run from target root):

```bash
grep -RnE 'JpaEventStorageEngine|JdbcEventStorageEngine|EmbeddedEventStore|AxonServerEventStore' \
     --include='*.java' --include='*.kt' src 2>/dev/null

# Spring Boot starter signals:
grep -RnE 'axon-server-connector|axoniq-spring-boot-starter|axon-spring-boot-starter' pom.xml */pom.xml 2>/dev/null
```

The dependency check matters: if the project depends on
`axoniq-spring-boot-starter` (or pre-migration `axon-spring-boot-starter`
plus `axon-server-connector`), Path B is almost always the right answer
even if a `JpaEventStorageEngine` bean is also declared — AF4 lets the
two co-exist; AF5 expects exactly one `EventStorageEngine`.

After this inspection, ask the user with `AskUserQuestion` to confirm
the chosen path, surfacing the evidence (dependencies + existing
storage-engine beans). Default to the path the inspection points at;
mark it `(Recommended)`.

## FQN cheat sheet

Use this table — never guess imports.

### AF4 (remove these)

| Element | FQN |
|---|---|
| `EmbeddedEventStore` | `org.axonframework.eventsourcing.eventstore.EmbeddedEventStore` |
| `JpaEventStorageEngine` | `org.axonframework.eventsourcing.eventstore.jpa.JpaEventStorageEngine` |
| `JdbcEventStorageEngine` | `org.axonframework.eventsourcing.eventstore.jdbc.JdbcEventStorageEngine` |
| `EventStore` (interface) | `org.axonframework.eventsourcing.eventstore.EventStore` |
| `DomainEventEntry` (JPA entity) | `org.axonframework.eventsourcing.eventstore.jpa.DomainEventEntry` |
| `EntityManagerProvider` | `org.axonframework.common.jpa.EntityManagerProvider` |
| `TransactionManager` | `org.axonframework.common.transaction.TransactionManager` |
| `XStreamSerializer` / `JacksonSerializer` | `org.axonframework.serialization.*` |

### AF5 (add these)

| Element | FQN |
|---|---|
| `EventStorageEngine` | `org.axonframework.eventsourcing.eventstore.EventStorageEngine` |
| `AggregateBasedJpaEventStorageEngine` | `org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngine` |
| `AggregateBasedJpaEventStorageEngineConfiguration` | `org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngineConfiguration` |
| `AggregateBasedAxonServerEventStorageEngine` | `io.axoniq.framework.axonserver.connector.event.AggregateBasedAxonServerEventStorageEngine` |
| `AggregateEventEntry` (JPA entity) | `org.axonframework.eventsourcing.eventstore.jpa.AggregateEventEntry` |
| `JpaTransactionalExecutorProvider` | `org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider` |
| `EventConverter` | `org.axonframework.messaging.eventhandling.conversion.EventConverter` |
| `Converter` | `org.axonframework.conversion.Converter` |
| `EventSourcingConfigurer` | `org.axonframework.eventsourcing.configuration.EventSourcingConfigurer` |
| `ComponentDefinition` | `org.axonframework.common.configuration.ComponentDefinition` |
| `ConfigurationEnhancer` | `org.axonframework.common.configuration.ConfigurationEnhancer` |
| `Phase` (lifecycle) | `org.axonframework.common.lifecycle.Phase` |

> AF5 replaced `Serializer` with `Converter` / `EventConverter`. The new
> storage engines take an `EventConverter`, not a `Serializer`. If the
> AF4 code wired a `Serializer` bean as a constructor argument to the
> storage engine, that wiring is gone — `EventConverter` is now resolved
> from the configuration registry.

## Path A — Spring Boot + JPA

Use when the AF4 project depended on `axon-spring-boot-starter` **without**
the Axon Server connector and configured `JpaEventStorageEngine` (often
implicitly via the starter, sometimes explicitly via a `@Bean`).

### A.1. Remove the AF4 storage-engine wiring

If the project declared its own `EventStore` / `EventStorageEngine`
`@Bean`, **delete those bean methods**. The AF5 Spring Boot autoconfig
(`JpaEventStoreAutoConfiguration`) registers an
`AggregateBasedJpaEventStorageEngine` automatically when an
`EntityManagerFactory` and a `PlatformTransactionManager` are present
and no other `EventStorageEngine` / `EventStore` bean exists.

If the AF4 wiring *only* provided a `Serializer` to the storage engine,
that bean can still stay (now resolved as a `Converter`) — but it's
usually cleaner to drop the explicit `EventStorageEngine` `@Bean`
entirely and let autoconfig win.

### A.2. If you need to override defaults — register a `ConfigurationEnhancer`

When the AF4 wiring tuned `batchSize`, `gapTimeout`, `lowestGlobalSequence`,
`maxGapOffset`, `gapCleaningThreshold`, or the
`PersistenceExceptionResolver`, port the customization to a
`ConfigurationEnhancer` bean. The shape mirrors the framework's
`AggregateBasedJpaEventStorageEngineConfigurationEnhancer` (see
`extensions/spring/spring-boot-autoconfigure/src/main/java/org/axonframework/extension/springboot/autoconfig/JpaEventStoreAutoConfiguration.java`
in the AxonFramework5 repo for the reference shape):

```java
@Bean
public ConfigurationEnhancer aggregateBasedJpaEventStorageEngineCustomization(
        EntityManagerFactory entityManagerFactory,
        PersistenceExceptionResolver persistenceExceptionResolver) {

    return registry -> {
        UnaryOperator<AggregateBasedJpaEventStorageEngineConfiguration> configurer = config ->
                config.batchSize(200)
                      .gapTimeout(60_000)
                      .persistenceExceptionResolver(persistenceExceptionResolver);

        ComponentDefinition<EventStorageEngine> definition =
                ComponentDefinition.ofType(EventStorageEngine.class)
                                   .withBuilder(configuration ->
                                           new AggregateBasedJpaEventStorageEngine(
                                                   new JpaTransactionalExecutorProvider(entityManagerFactory),
                                                   configuration.getComponent(EventConverter.class),
                                                   configurer))
                                   .onShutdown(Phase.INBOUND_EVENT_CONNECTORS, ese -> {
                                       if (ese instanceof AggregateBasedJpaEventStorageEngine engine) {
                                           engine.close();
                                       }
                                   });

        registry.registerIfNotPresent(definition, SearchScope.ALL);
    };
}
```

Default knobs (no enhancer) are usually fine for migrations — only
introduce the enhancer if the AF4 code was explicitly tuning them. If
the only customization was `Serializer` choice, drop it: `EventConverter`
is auto-resolved.

### A.3. Database schema migration

AF5 uses a different table — `aggregate_event_entry` — with renamed
columns and a stricter sequence generator. **You must migrate the data,
not just the schema.** The framework cannot read AF4 events out of the
old table.

The full diff (per
`axon-5/api-changes/10-stored-format-changes.md` in the
AxonFramework5 repo):

| AF4 column (`domain_event_entry`) | AF5 column (`aggregate_event_entry`) | Constraint change |
|---|---|---|
| `event_identifier` | `identifier` | now `NOT NULL` |
| `payload_type` | `type` | — |
| `payload_revision` | `version` | now **NOT NULL** (was optional) |
| `time_stamp` | `timestamp` | — |
| `type` | `aggregate_type` | — |
| `sequence_number` | `aggregate_sequence_number` | now **NOT NULL** |
| `meta_data` | `metadata` | — |
| `payload` | `payload` | max length cap of 10_000 **removed** |
| `aggregate_identifier` | `aggregate_identifier` | now **optional** (DCB-friendly) |
| (table-name index) | unique index on `(aggregate_identifier, aggregate_sequence_number)` | — |
| `@GeneratedValue` (default) | sequence generator `aggregate-event-global-index-sequence`, allocation size **1** | — |

Emit a migration SQL script alongside the bean change. The shape:

```sql
-- AF4 → AF5: rename domain_event_entry → aggregate_event_entry
-- Run inside a transaction. Verify counts before and after.

ALTER TABLE domain_event_entry RENAME TO aggregate_event_entry;

ALTER TABLE aggregate_event_entry RENAME COLUMN event_identifier     TO identifier;
ALTER TABLE aggregate_event_entry RENAME COLUMN payload_type         TO type;
ALTER TABLE aggregate_event_entry RENAME COLUMN payload_revision     TO version;
ALTER TABLE aggregate_event_entry RENAME COLUMN time_stamp           TO timestamp;
ALTER TABLE aggregate_event_entry RENAME COLUMN type                 TO aggregate_type;
ALTER TABLE aggregate_event_entry RENAME COLUMN sequence_number      TO aggregate_sequence_number;
ALTER TABLE aggregate_event_entry RENAME COLUMN meta_data            TO metadata;

-- AF4 allowed null payload_revision; AF5 requires it.
UPDATE aggregate_event_entry SET version = '0' WHERE version IS NULL;
ALTER TABLE aggregate_event_entry ALTER COLUMN version SET NOT NULL;

-- aggregate_sequence_number now NOT NULL (in AF4 it could be null for non-domain events).
ALTER TABLE aggregate_event_entry ALTER COLUMN aggregate_sequence_number SET NOT NULL;

-- Drop the AF4 length cap on payload / metadata (vendor-specific syntax — adjust).
-- e.g. PostgreSQL: ALTER TABLE … ALTER COLUMN payload  TYPE bytea;
--                  ALTER TABLE … ALTER COLUMN metadata TYPE bytea;

-- Sequence generator with allocation size 1.
CREATE SEQUENCE IF NOT EXISTS "aggregate-event-global-index-sequence" INCREMENT BY 1 MINVALUE 1;
-- Seed the sequence past the highest existing global_index, vendor-specifically.

-- Unique index on (aggregate_identifier, aggregate_sequence_number).
CREATE UNIQUE INDEX IF NOT EXISTS aggregate_event_entry_aggidx
    ON aggregate_event_entry (aggregate_identifier, aggregate_sequence_number);
```

Place the script under `<target>/.axon4-to-axon5-migration/sql/01-rename-domain-to-aggregate-event-entry.sql`
so it lives next to the rest of the migration paperwork.

If the project already uses Flyway or Liquibase, **don't** run raw SQL —
emit a Flyway migration (`V<NN>__af5_aggregate_event_entry.sql`) or a
Liquibase changeset under the project's existing migration directory
instead, and tell the user where it landed.

The `payload` and `metadata` `byte[]` content does not need to be
rewritten — they remain converter-encoded blobs and AF5's `EventConverter`
reads the same bytes the AF4 `Serializer` wrote, provided the same
converter/serializer is configured. Surface this explicitly to the
user so they don't double-migrate.

### A.4. Schema validation entity

AF5 ships `AggregateEventEntry` as a `@Entity`-annotated class
(`org.axonframework.eventsourcing.eventstore.jpa.AggregateEventEntry`).
The Spring Boot autoconfig registers it via `@RegisterDefaultEntities`,
so no `persistence.xml` change is required — but if the project uses an
explicit `LocalContainerEntityManagerFactoryBean` with a fixed
`packagesToScan`, **add `org.axonframework.eventsourcing.eventstore.jpa`**
to that list.

## Path B — Spring Boot + Axon Server

Use when the AF4 project depended on `axon-server-connector` (free) or
`axoniq-spring-boot-starter` (commercial AF5).

### B.1. Remove the AF4 storage-engine wiring

If the project declared its own `EventStore` / `EventStorageEngine`
`@Bean`, **delete those bean methods.** The Axoniq Spring Boot starter's
`AxonServerAutoConfiguration` registers
`AggregateBasedAxonServerEventStorageEngine` automatically. Adding your
own bean prevents autoconfig from winning.

### B.2. Confirm the connector dependency is in place

```xml
<dependency>
    <groupId>io.axoniq.framework</groupId>
    <artifactId>axoniq-spring-boot-starter</artifactId>
</dependency>
```

(The OpenRewrite phase 1 should already have done this swap. Verify.)

### B.3. No schema migration

Axon Server stores its own events. You're done — no SQL, no entity
package changes, no enhancer.

### B.4. Caveat — converter wiring

If the AF4 project explicitly wired a `Serializer` bean (e.g. Jackson)
to the storage engine, port that to a `Converter` / `EventConverter`
bean. Without one, AF5 falls back to the default converter, which may
not round-trip events serialized by a customized AF4 Jackson serializer.
Surface this to the user.

## Path C — No Spring (programmatic Configuration API)

Use when the AF4 project bootstrapped via `DefaultConfigurer.defaultConfiguration()`
directly (no Spring Boot autoconfig). The writeconfiguration skill
(phase 8) should have already converted that bootstrap to
`EventSourcingConfigurer.create()`.

### C.1. Register the engine on the configurer

```java
EventSourcingConfigurer configurer = EventSourcingConfigurer.create();

configurer.componentRegistry(registry ->
    registry.registerComponent(
        EventStorageEngine.class,
        configuration -> new AggregateBasedJpaEventStorageEngine(
            new JpaTransactionalExecutorProvider(entityManagerFactory),
            configuration.getComponent(EventConverter.class),
            cfg -> cfg.batchSize(100)
        )
    )
);
```

For the Axon Server variant:

```java
configurer.componentRegistry(registry ->
    registry.registerComponent(
        EventStorageEngine.class,
        configuration -> new AggregateBasedAxonServerEventStorageEngine(
            configuration.getComponent(AxonServerConnection.class),
            configuration.getComponent(EventConverter.class)
        )
    )
);
```

(For the AxonServer variant in non-Spring setups, the AxonServer
connector still provides an enhancer that registers it for you when
on the classpath. Inspect `AxonServerConfigurationEnhancer` in the
connector module to see whether you can `componentRegistry(r -> ...)`
to enable rather than registering manually.)

### C.2. JPA path — same SQL migration as A.3

The schema migration is identical regardless of Spring; run it against
the same database the engine points at.

## Procedure

1. **Determine the target project and locate the storage-engine wiring.**
   Use the detection greps above. If the orchestrator
   (`axon4-to-axon5-migration`) drove this skill, the target is already
   established; otherwise ask via `AskUserQuestion`.

2. **Ask the user to confirm the path** with `AskUserQuestion`. Offer
   Path A / Path B / Path C as options, label the matching one
   `(Recommended)` based on the inspection in step 1, and surface the
   evidence in the question text.

3. **Apply the wiring change** following the chosen path. Make the
   smallest possible change — typically deleting an AF4 `@Bean` method
   or replacing one constructor call. Touch only the configuration
   class, not the storage-engine sources themselves.

4. **For Path A: emit the SQL migration.** Place it under
   `<target>/.axon4-to-axon5-migration/sql/` if the project has no
   migration tool, or under the project's Flyway/Liquibase directory
   with the right filename pattern. **Do not execute it.** The user
   runs it against their database when they're ready.

5. **Verify the bean wiring compiles.** Use the migration profile from
   `axon4-to-axon5-maven-migration-profile`:
   ```bash
   ./mvnw -Pmigration test-compile -DfailIfNoTests=false
   ```
   The configuration class is usually small enough to add to the
   profile's includes without ripple.

6. **Append a learnings entry.** In
   `<target>/.axon4-to-axon5-migration/learnings.md`, record:
   - the path chosen (A/B/C) and why,
   - the resolved source of `EventConverter` (autoconfig vs explicit
     `Converter` bean),
   - any custom serializer that needed porting,
   - the SQL script's path (Path A only) and whether it was emitted as
     raw SQL or as a Flyway/Liquibase changeset.

7. **Stop. Don't run the SQL, don't run the full test suite.** The
   user runs the SQL on a backup/staging environment first. The
   stabilization phase (phase 10 in the orchestrator) handles
   end-to-end verification once the data has been migrated.

## Caveats

- **Don't migrate data and code in the same commit.** The SQL renames
  the source-of-truth event log; the code expects the new shape. If
  the script and the code change are tied to the same deploy, a
  partial deploy leaves the system unable to read its own events.
  Recommend the user split: ship the SQL on a quiet window, verify
  the renamed table is healthy, then ship the bean change.
- **Two `EventStorageEngine` beans = startup failure.** Spring Boot
  autoconfig backs off only when no other bean is present. Leftover
  AF4 `@Bean EventStore` / `@Bean EventStorageEngine` declarations
  must be removed.
- **`payload`/`metadata` length cap removal is database-vendor-specific.**
  Postgres uses `bytea` (no cap to drop); MySQL and Oracle have explicit
  column-type changes. Don't emit a one-size-fits-all `ALTER COLUMN`.
- **JDBC has no AF5 drop-in yet.** If the project's AF4 wiring used
  `JdbcEventStorageEngine`, this skill stops and asks the user to pick
  a different target (JPA or Axon Server). Don't try to write a custom
  AF5 JDBC engine inside a migration run.
- **Custom `Serializer` ≠ `Converter`.** Most AF4 Jackson/XStream
  configurations port automatically because AF5 ships matching default
  converters. If the AF4 project subclassed a serializer or used custom
  `RevisionResolver` / `ContentTypeConverter`, the port is *not*
  one-line — surface to stabilization.
- **`AggregateEventEntry` table comes from the framework JAR.** Don't
  copy it into the project. If a project shipped a custom
  `DomainEventEntry` subclass to add columns, treat that as a custom
  storage-engine subclass — out of scope for this atomic skill.
- **The OpenRewrite phase 1 does NOT handle the schema migration.** The
  recipes rewrite Java code; they have no SQL knowledge. Even if every
  Java file looks AF5-clean post-recipe, the database is still on the
  AF4 schema until you run the script from this skill.

## Reference docs

- `axon-5/api-changes/10-stored-format-changes.md` — authoritative list of
  column renames and constraint changes.
- `eventsourcing/src/main/java/org/axonframework/eventsourcing/eventstore/jpa/AggregateBasedJpaEventStorageEngine.java`
  — the JPA engine constructor and configuration knobs.
- `eventsourcing/src/main/java/org/axonframework/eventsourcing/eventstore/jpa/AggregateEventEntry.java`
  — the JPA entity that the schema must match.
- `extensions/spring/spring-boot-autoconfigure/src/main/java/org/axonframework/extension/springboot/autoconfig/JpaEventStoreAutoConfiguration.java`
  — reference shape for the `ConfigurationEnhancer` + `ComponentDefinition`
  pattern.
- `connector/axon-server-connector/src/main/java/io/axoniq/framework/axonserver/connector/event/AggregateBasedAxonServerEventStorageEngine.java`
  (in the AxoniqFramework repo) — Axon Server engine source.

## Examples

`references/examples/` — one file per real run, preserved verbatim.
Empty on creation; populate from real invocations.
