---
name: axon4-to-axon5-eventsourcing-aggregate
description: Migrates  classes annotated with @Aggregate and with @EventSourcingHandler insider to the @EventSourcedEntity
---

Execute following migration instruction based on the migration paths from:
[aggregates](../../../../docs/reference-guide/modules/migration/pages/paths/aggregates)

## Annotation / class FQN cheat sheet

Use this table for every step below — never guess imports.

### AF4 (remove these)

| Element | FQN |
|---|---|
| `@TargetAggregateIdentifier` | `org.axonframework.modelling.command.TargetAggregateIdentifier` |
| `@AggregateIdentifier` | `org.axonframework.modelling.command.AggregateIdentifier` |
| `@CreationPolicy` | `org.axonframework.modelling.command.CreationPolicy` |
| `AggregateCreationPolicy` (enum) | `org.axonframework.modelling.command.AggregateCreationPolicy` |
| `@CommandHandler` | `org.axonframework.commandhandling.CommandHandler` |
| `@EventSourcingHandler` | `org.axonframework.eventsourcing.EventSourcingHandler` |
| `AggregateLifecycle.apply(...)` | `org.axonframework.modelling.command.AggregateLifecycle` |
| `@Aggregate` (Spring) | `org.axonframework.spring.stereotype.Aggregate` |
| `@Revision` | `org.axonframework.serialization.Revision` |
| `@RoutingKey` | `org.axonframework.commandhandling.RoutingKey` |

### AF5 (add these if needed)

| Element | FQN |
|---|---|
| `@TargetEntityId` | `org.axonframework.modelling.annotation.TargetEntityId` |
| `@EventTag` | `org.axonframework.eventsourcing.annotation.EventTag` |
| `@CommandHandler` | `org.axonframework.messaging.commandhandling.annotation.CommandHandler` |
| `@Command` | `org.axonframework.messaging.commandhandling.annotation.Command` |
| `@EventSourcingHandler` | `org.axonframework.eventsourcing.annotation.EventSourcingHandler` |
| `@Event` | `org.axonframework.messaging.eventhandling.annotation.Event` |
| `EventAppender` | `org.axonframework.messaging.eventhandling.gateway.EventAppender` |
| `@EntityCreator` | `org.axonframework.eventsourcing.annotation.reflection.EntityCreator` |
| `@InjectEntity` (NOT `@InjectState`) | `org.axonframework.modelling.annotation.InjectEntity` |
| `@EventSourced` (Spring) | `org.axonframework.extension.spring.stereotype.EventSourced` |
| `@EventSourcedEntity` (core) | `org.axonframework.eventsourcing.annotation.EventSourcedEntity` |

> ⚠️ The reference docs sometimes show `@InjectState` — that name does not exist in the codebase. Always use `@InjectEntity`.

## Instructions

1. Work on the class given while invoking this skill or find the first class that is annotated with `@Aggregate` and has methods annotated with `@EventSourcingHandler`. This is the Aggregate that we will be working on.
2. Identify all classes for commands handled by this Aggregate — first parameter of the methods annotated with `@CommandHandler`.
3. Identify all classes for events handled by this Aggregate — first parameter of the methods annotated with `@EventSourcingHandler`.
4. In each command class:
    - remove import: `org.axonframework.modelling.command.TargetAggregateIdentifier`
    - add import: `org.axonframework.modelling.annotation.TargetEntityId`
    - replace `@TargetAggregateIdentifier` with `@TargetEntityId`
5. Annotate each command class with `@Command` (FQN above). If the AF4 class had `@RoutingKey` on a property, set `@Command(routingKey = "<propertyName>")` and remove the `@RoutingKey` annotation + import.
6. In the aggregate class, identify the `@AggregateIdentifier`-annotated property and which `@EventSourcingHandler` sets it from an event property. That event property is the one to annotate with `@EventTag`.
7. Annotate the aggregate-id field in **every** event with `@EventTag(key = "<EntityName>")`. Use the entity's simple class name (e.g. `"Army"`) so it matches the entity's `tagKey` (see step 14). Without DCB, exactly one `@EventTag` per event.
8. Annotate each event class with `@Event` (FQN above). If the AF4 event had `@Revision("x")`, replace it with `@Event(version = "x")` and remove the `@Revision` annotation + import. Otherwise add bare `@Event` (default name = simple class name, default version = `0.0.1`).
9. Remove the `@AggregateIdentifier` annotation (and import) from the aggregate class. The id field stays as a regular field.
10. Replace import `org.axonframework.eventsourcing.EventSourcingHandler` → `org.axonframework.eventsourcing.annotation.EventSourcingHandler`.
11. Replace import `org.axonframework.commandhandling.CommandHandler` → `org.axonframework.messaging.commandhandling.annotation.CommandHandler`.
12. Annotate the aggregate's no-arg constructor with `@EntityCreator` (mandatory in AF5). If no no-arg ctor exists, add one. The framework instantiates the entity via this ctor before applying events.
13. Replace `AggregateLifecycle.apply(event)` calls with `eventAppender.append(event)`. Add `EventAppender eventAppender` as a method parameter to every `@CommandHandler`. Remove the static import of `AggregateLifecycle.apply`.
14. Migrate `@CreationPolicy` / `AggregateCreationPolicy` (remove imports + annotation) by reshaping the command handler:
    - **`CreationPolicy.ALWAYS`** (creation command) → make the `@CommandHandler` a **`static`** method. No entity injection needed; static = "no instance exists yet".
    - **`CreationPolicy.CREATE_IF_MISSING`** → **`static`** `@CommandHandler` with an `@InjectEntity @Nullable <Entity> entity` parameter (use `org.jspecify.annotations.Nullable`). Branch on `entity == null` for the create path vs the update path. ⚠️ Semantic note: AF4's CREATE_IF_MISSING was "create-or-update". The doc example throws on existing — only use that if the AF4 code had a similar guard. For "create-or-update", read state from `entity` when non-null and from defaults (e.g. empty collections) when null.
    - **`CreationPolicy.NEVER`** (update command) → instance-level `@CommandHandler` (no `static`). Default behavior; nothing else to do.
15. Move to Path A or Path B based on the framework used in this project.

### Path A: Spring Boot Configuration

If Spring Boot is used (e.g. project depends on `axoniq-spring-boot-starter`), follow this path.

A.1. Replace `@Aggregate` (`org.axonframework.spring.stereotype.Aggregate`) with `@EventSourced` (`org.axonframework.extension.spring.stereotype.EventSourced`).

A.2. Configure the `@EventSourced` annotation:
- **`tagKey`**: set to the same value used in events' `@EventTag(key = ...)`. Default is the entity's simple class name; if you use that as the tag key, you can omit `tagKey`. Recommended: be explicit (`@EventSourced(tagKey = "Army")`).
- **`idType`**: set when the AF4 `@AggregateIdentifier` field is **not** `String`. The default is `String.class`; mismatched types cause silent identifier-resolution failures. Example: `@EventSourced(tagKey = "Army", idType = ArmyId.class)` when the id field is `ArmyId`.

### Path B: Axon Framework Native Configuration

Not supported right now. Keep it for later.
