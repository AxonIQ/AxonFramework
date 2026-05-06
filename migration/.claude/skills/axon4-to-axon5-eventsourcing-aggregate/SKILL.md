---
name: axon4-to-axon5-eventsourcing-aggregate
description: Migrates classes annotated with @Aggregate and with @EventSourcingHandler inside to the @EventSourcedEntity
---

Execute following migration instruction based on the migration paths from:
- [aggregates](../../../../docs/reference-guide/modules/migration/pages/paths/aggregates)
- [test-fixtures.adoc](../../../../docs/reference-guide/modules/migration/pages/paths/test-fixtures.adoc)

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
| `AggregateTestFixture` | `org.axonframework.test.aggregate.AggregateTestFixture` |
| `FixtureConfiguration` | `org.axonframework.test.aggregate.FixtureConfiguration` |
| `AggregateNotFoundException` | `org.axonframework.modelling.command.AggregateNotFoundException` |

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
| `AxonTestFixture` | `org.axonframework.test.fixture.AxonTestFixture` |
| `EventSourcingConfigurer` | `org.axonframework.eventsourcing.configuration.EventSourcingConfigurer` |
| `EventSourcedEntityModule` | `org.axonframework.eventsourcing.configuration.EventSourcedEntityModule` |

> ⚠️ The reference docs sometimes show `@InjectState` — that name does not exist in the codebase. Always use `@InjectEntity`.

## Instructions

### Step 0: Pre-flight fast-path (ALWAYS run first)

Before doing any transformation work, check whether the aggregate is already migrated:

0.1. **Check for compilation problems in the aggregate file** (and its associated test fixture file). Use `mcp__ide__getDiagnostics` / `mcp__jetbrains__get_file_problems` if available, otherwise run a scoped `./mvnw compile` (with `-Pmigration` if a migration profile is configured).

0.2. **If there are zero compilation problems**, run the tests for the corresponding test fixture (the `*Test` class for this aggregate). Use the same command shape as step T.5 below:

```bash
./mvnw test -Pmigration -Dtest='<FQTestClass>' -DfailIfNoTests=false
```

(Drop `-Pmigration` if the surrounding code already compiles cleanly.)

0.3. **If the tests are green**, the aggregate is most likely already migrated. STOP and ask the user via `AskUserQuestion`:

> "The aggregate `<Name>` compiles cleanly and its tests pass. How should I proceed?
> - **Skip** — treat as already migrated and move on.
> - **Deep verify** — diff the current AF5 source against the AF4 baseline (`git log` / `git show` on the pre-migration revision) and walk steps 1–14 + T.1–T.5 below to confirm nothing was silently lost (e.g. dropped `snapshotTriggerDefinition`, missing `@EventTag`, lost `@CreationPolicy` semantics)."

Only proceed to step 1 if the user picks **Deep verify**, or if step 0.1/0.2 reported failures.

> ⚠️ Do not skip step 0. A "skip" answer here is the most efficient outcome when the aggregate was already migrated by an earlier pass (OpenRewrite, a prior session, etc.) — running steps 1–14 against an already-migrated class wastes context and risks double-edits.

### Step 1+: Full migration

1. Work on the class given while invoking this skill or find the first class that is annotated with `@Aggregate` and has methods annotated with `@EventSourcingHandler`. This is the Aggregate that we will be working on. It's possible that some work is already done on this class — like changed annotations. Then continue work and make sure that it compiles and the corresponding tests are green.
2. Identify all classes for commands handled by this Aggregate — first parameter of the methods annotated with `@CommandHandler`.
3. Identify all classes for events handled by this Aggregate — first parameter of the methods annotated with `@EventSourcingHandler`.
4. In each command class:
    - remove import: `org.axonframework.modelling.command.TargetAggregateIdentifier`
    - add import: `org.axonframework.modelling.annotation.TargetEntityId`
    - replace `@TargetAggregateIdentifier` with `@TargetEntityId`
5. Annotate each command class with `@Command` (FQN above). If the AF4 class had `@RoutingKey` on a property, set `@Command(routingKey = "<propertyName>")` and remove the `@RoutingKey` annotation + import.
6. In the aggregate class, identify the `@AggregateIdentifier`-annotated property and which `@EventSourcingHandler` sets it from an event property. That event property is the one to annotate with `@EventTag`.
7. Annotate the aggregate-id field in **every** event with `@EventTag(key = "<EntityName>")`. Use the entity's simple class name (e.g. `"Army"`) so it matches the entity's `tagKey` (see Path A.2). Without DCB, exactly one `@EventTag` per event.
8. Annotate each event class with `@Event` (FQN above). If the AF4 event had `@Revision("x")`, replace it with `@Event(version = "x")` and remove the `@Revision` annotation + import. Otherwise add bare `@Event` (default name = simple class name, default version = `0.0.1`).
9. Remove the `@AggregateIdentifier` annotation (and import) from the aggregate class. The id field stays as a regular field.
10. Replace import `org.axonframework.eventsourcing.EventSourcingHandler` → `org.axonframework.eventsourcing.annotation.EventSourcingHandler`.
11. Replace import `org.axonframework.commandhandling.CommandHandler` → `org.axonframework.messaging.commandhandling.annotation.CommandHandler`.
12. Annotate the aggregate's no-arg constructor with `@EntityCreator` (mandatory in AF5). If no no-arg ctor exists, add one. The framework instantiates the entity via this ctor before applying events.
13. Replace `AggregateLifecycle.apply(event)` calls with `eventAppender.append(event)`. Add `EventAppender eventAppender` as a method parameter to every `@CommandHandler`. Remove the static import of `AggregateLifecycle.apply`.
14. Migrate `@CreationPolicy` / `AggregateCreationPolicy` (remove imports + annotation) by reshaping the command handler. **Verify your choice against the test suite — the framework distinguishes creational vs instance handlers strictly:**
    - **`CreationPolicy.ALWAYS`** (creation-only command) → make the `@CommandHandler` a **`static`** method. No entity injection needed; the framework calls it only when no entity exists yet, and throws `EntityAlreadyExistsForCreationalCommandHandlerException` if the entity already exists.
    - **`CreationPolicy.CREATE_IF_MISSING`** → **prefer keeping the handler instance-level (NOT static)** combined with a no-arg `@EntityCreator`. The framework materializes an empty entity on first invocation, so the same instance handler runs whether the entity is new or pre-existing — exactly matching AF4's create-or-update semantics. Do **not** use `static` + `@InjectEntity` here: a static handler is treated as creational-only and will throw on existing entities.
    - **`CreationPolicy.NEVER`** (update command) → instance-level `@CommandHandler` (no `static`). Default behavior; nothing else to do.
15. Move to Path A or Path B based on the framework used in this project.

### Path A: Spring Boot Configuration

If Spring Boot is used (e.g. project depends on `axoniq-spring-boot-starter`), follow this path.

A.1. Replace `@Aggregate` (`org.axonframework.spring.stereotype.Aggregate`) with `@EventSourced` (`org.axonframework.extension.spring.stereotype.EventSourced`).

A.2. Configure the `@EventSourced` annotation:
- **`tagKey`**: set to the same value used in events' `@EventTag(key = ...)`. Default is the entity's simple class name; if you use that as the tag key, you can omit `tagKey`. Recommended: be explicit (`@EventSourced(tagKey = "Army")`).
- **`idType`**: set when the AF4 `@AggregateIdentifier` field is **not** `String`. The default is `String.class`; mismatched types cause silent identifier-resolution failures. Example: `@EventSourced(tagKey = "Army", idType = ArmyId.class)` when the id field is `ArmyId`.

> ⚠️ **`snapshotTriggerDefinition` is not portable.** If the AF4 `@Aggregate` had `snapshotTriggerDefinition = "..."`, that attribute does not exist on `@EventSourced` — it is silently dropped during migration. Warn the user that snapshot configuration must be re-wired separately (e.g. via a Spring `@Bean` or configurer API) if snapshotting was required.

### Path B: Axon Framework Native Configuration

Not supported right now. Keep it for later.

## Test fixture migration

After migrating the entity, migrate the tests. AF4's `AggregateTestFixture` is replaced by AF5's `AxonTestFixture`, which is built from an `ApplicationConfigurer` so test and production share the same configuration. See [`test-fixtures.adoc`](../../../../docs/reference-guide/modules/migration/pages/paths/test-fixtures.adoc) for full coverage.

T.1. **Locate the test classes.** Find the test class for the migrated aggregate (typically `<Aggregate>Test`) and any subclasses (`grep -rln "extends <Aggregate>Test"`). Migrate the base class first.

T.2. **Replace `AggregateTestFixture` with `AxonTestFixture`** in the base test class:
- Replace the import `org.axonframework.test.aggregate.AggregateTestFixture` with `org.axonframework.test.fixture.AxonTestFixture`.
- Replace the field type `AggregateTestFixture<?>` with `AxonTestFixture`.
- In `@BeforeEach`, replace `new AggregateTestFixture<>(<Aggregate>.class)` with `AxonTestFixture.with(<configurer>)`.
- The minimal first-step configurer for a single entity is:
  ```java
  EventSourcingConfigurer.create()
                         .registerEntity(EventSourcedEntityModule.autodetected(<IdType>.class, <Aggregate>.class))
  ```
  By default, the fixture's `Customization(integrationEnabled=false)` already disables Axon Server / Postgres enhancers, so a plain `AxonTestFixture.with(configurer)` is enough for unit tests.
- Add an `@AfterEach tearDown() { fixture.stop(); }` to release the configuration.

T.3. **Convert each test method to the fluent given/when/then API.** Mapping (see [test-fixtures.adoc](../../../../docs/reference-guide/modules/migration/pages/paths/test-fixtures.adoc) for the full list):

| AF4 | AF5 |
|---|---|
| `fixture.given(events…)` | `fixture.given().events(events…)` (or `.event(e)` for single) |
| `fixture.given(List<?> events)` | `fixture.given().events(list)` — but prefer `.given().noPriorActivity()` when the list is empty |
| `fixture.givenCommands(c…)` | `fixture.given().command(c)` |
| `fixture.givenNoPriorActivity()` | `fixture.given().noPriorActivity()` |
| `.when(cmd)` | `.when().command(cmd)` |
| `.when(cmd, metadata)` | `.when().command(cmd, metadata)` |
| `.expectEvents(events…)` | `.then().events(events…)` |
| `.expectNoEvents()` | `.then().noEvents()` |
| `.expectException(Cls.class)` | `.then().exception(Cls.class)` |
| `.expectException(Cls.class).expectExceptionMessage(msg)` | `.then().exception(Cls.class, msg)` |
| `.expectSuccessfulHandlerExecution()` | `.then().success()` |
| `.expectResultMessagePayload(p)` | `.then().resultMessagePayload(p)` |
| `.expectEventsMatching(matcher)` | `.then().eventsSatisfy(consumer)` or `.eventsMatch(predicate)` |

> ⚠️ **`EventMessage` accessors are record-style in AF5.** Inside `eventsSatisfy(events -> { ... })` lambdas (or any other place that handles a raw `EventMessage`), use `events.get(0).payload()` and `events.get(0).metaData()` — **NOT** AF4's JavaBean-style `getPayload()` / `getMetaData()`. The AF4 names do not exist on the AF5 `EventMessage` interface and produce `cannot find symbol: method getPayload()` compile errors.

T.4. **Adjust behavioral assertions for AF5 semantics.** Several AF4 fixture errors no longer exist in AF5:
- `AggregateNotFoundException` is **not** thrown for instance handlers in AF5. With a no-arg `@EntityCreator`, the framework always materializes an empty entity, so the handler runs and any domain rule against empty state surfaces instead. Update such tests to expect the actual domain exception (e.g. a domain-rule violation message) and add a comment noting the semantic shift.
  - **Watch out for NPE as the "actual exception"**: if the `@CommandHandler` body calls a method on a null entity-state field (e.g. `null.equals(...)` when a field that is only set by an `@EventSourcingHandler` was never initialised), it throws `NullPointerException` rather than a meaningful domain exception. In that case, use `Exception.class` as the expected type and add a `// TODO` comment that the domain model should add an explicit "entity not yet initialised" guard before operating on those fields.
- Static (creational) handlers throw `EntityAlreadyExistsForCreationalCommandHandlerException` (`org.axonframework.modelling.entity`) when the entity already exists. If you see this in a test that should succeed on existing entities, the handler shouldn't be `static` — re-check step 14.

T.5. **Run just the migrated tests.** Confirm all tests pass before moving on. If tests fail, do not declare success — re-check steps 14 and T.4 for handler shape and exception expectations. The test run is also the smoke test for step 14: a wrong static-vs-instance `CreationPolicy` choice has no compile-time signal — only the test run surfaces `EntityAlreadyExistsForCreationalCommandHandlerException`.

If the project's other modules / sub-packages still use AF4 APIs and the surrounding `mvn compile` is broken, run the `axon4-to-axon5-maven-migration-profile` skill to add (or extend) a `migration` Maven profile that scopes compilation and tests to the files currently being migrated. Re-run that skill any time the working diff grows beyond the existing include list.

> ⚠️ **Prefer per-file includes over package wildcards.** A glob like `com/example/write/**/*.java` will pull in every file in that package — including `*Mcp.java`, `*RestApi.java`, and other non-migration files that may still use AF4 APIs or Java preview features. If those files fail compilation, switch from the wildcard to an explicit per-file `<include>` list. The `axon4-to-axon5-maven-migration-profile` skill supports both strategies.

Then verify with:

```bash
./mvnw test -Pmigration -Dtest='<FQTestClass1>,<FQTestClass2>' -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

> ⚠️ **Multi-module reactor (`-pl <a>,<b>`) needs the surefire flag too.** When `-pl` includes modules that don't contain a class matching the `-Dtest=…` pattern (e.g. you scope to `core-api,rental` but the test only lives in `rental`), surefire fails the build with `No tests matching pattern "…" were executed!` for the empty modules. Always pass **both** `-DfailIfNoTests=false` (for plain `surefire:test`) **and** `-Dsurefire.failIfNoSpecifiedTests=false` (for the explicit `-Dtest=…` filter). The two flags suppress different surefire failure paths.

For a clean project where the surrounding code compiles, drop `-Pmigration` — plain `./mvnw test -Dtest=…` is enough (still keep both flags when running across multiple modules).
