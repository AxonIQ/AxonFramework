# Migration

Module containing [OpenRewrite](https://docs.openrewrite.org/) recipes for migrating Axon Framework applications from
4.x to 5.x.

## What it does

Running a top-level recipe is the first automated step toward AF5 — it rewrites the parts of your AF4 codebase and build
files that can be transformed mechanically. It does **not** guarantee a compiling project at the end: every codebase is
different, and the changes that can't be safely automated are left for you (or an AI-assisted follow-up) to finish by
hand. Concretely, the recipes do:

- **Build files**: swaps the BOM (`axon-bom` → `axon-framework-bom` or `axoniq-framework-bom`), bumps the Java
  source/target to the configured LTS (minimum Java 21), and removes dependencies with no AF5 port (e.g. `axon-spring-aot`).
- **Package and class renames**: moves handler annotations into `.annotation.*` subpackages, renames `EventBus` →
  `EventSink`, `ConfigurerModule` → `ConfigurationEnhancer`, and shifts extension packages
  (e.g. `org.axonframework.micrometer` → `org.axonframework.extension.metrics.micrometer`).
- **Annotation rewrites**: `@Aggregate` → `@EventSourced` (with `tagKey` and `idType`), `@TargetAggregateIdentifier` →
  `@TargetEntityId`, `@Revision` → `@Event(version = …)`, `@ProcessingGroup` → `@Namespace`. Adds `@EntityCreator` to
  no-arg constructors, lifts `@RoutingKey` from record/data-class parameters onto class-level `@Command`, and adds
  `@EventTag` to record components matching entity id fields.
- **Message API**: drops generic type arguments on message types, renames accessors (`getPayload()` → `payload()`,
  `getMetaData()` → `metadata()`, `getIdentifier()` → `identifier()`, …), rewrites two-arg `SequencingPolicy`
  lambdas to return `Optional`, and replaces static `AggregateLifecycle.apply(...)` with an injected
  `EventAppender#append(...)`.
- **Query API**: unwraps `ResponseTypes.instanceOf(X.class)` to a direct class argument and renames `query()` →
  `queryMany()` when paired with `multipleInstancesOf`.
- **Command handling**: converts `@CommandHandler` constructors to `public static void handle(...)` methods, and swaps
  in-handler `CommandGateway` fields for injected `CommandDispatcher` parameters.
- **Spring config**: renames `axon.serializer.*` properties to `axon.converter.*` in `application.properties`/YAML, and
  adds advisory TODOs above obsolete `sequencing-policy` settings and lost aggregate config (e.g.
  `snapshotTriggerDefinition`).
- **Test fixtures**: replaces `AggregateTestFixture`/`SagaTestFixture` with `AxonTestFixture` and rewrites the fluent
  Given-When-Then chain to the new phase-aware API.
- **Commercial path** (`UpgradeAxon4ToAxoniq5` only): additionally re-namespaces Axon Server connector, DLQ,
  distributed-messaging, and Testcontainer classes from `org.axonframework.*` to `io.axoniq.framework.*` and swaps the
  Spring Boot starter to `axoniq-spring-boot-starter`.

## Usage

Run a top-level recipe against a target project with the Maven plugin:

```bash
mvn -U org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.recipeArtifactCoordinates=org.axonframework:axon-migration:5.2.0-SNAPSHOT \
  -Drewrite.activeRecipes=org.axonframework.migration.UpgradeAxon4ToAxoniq5
```

Replace the recipe name with `UpgradeAxon4ToAxon5` for the free path.

For Gradle it requires a
custom [Gradle init script](https://docs.openrewrite.org/running-recipes/running-rewrite-on-a-gradle-project-without-modifying-the-build).

### Two top-level recipes

| Recipe                                              | What it does                                                                                                                                                                                                                                                                            | When to use                                                                                                                                                                                                                                  |
|-----------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `org.axonframework.migration.UpgradeAxon4ToAxon5`   | Migrates AF4 → free AF5. Renames packages/classes and updates Maven coordinates within the `org.axonframework.*` namespace.                                                                                                                                                             | You want to land on free AF5. **Warning**: if your AF4 app uses Axon Server / distributed command bus / DLQ, this recipe alone leaves your codebase non-compiling — those features moved to commercial. Use `UpgradeAxon4ToAxoniq5` instead. |
| `org.axonframework.migration.UpgradeAxon4ToAxoniq5` | Composes `UpgradeAxon4ToAxon5` then layers commercial-only migrations on top: source rewrites for Axon Server / DLQ / distributed-messaging, BOM swap to `axoniq-framework-bom`, Spring Boot starter swap to `axoniq-spring-boot-starter`, conditional `AddDependency` for Axoniq jars. | Recommended default for AF4 applications using Axon Server / DLQ / distributed messaging.                                                                                                                                                    |

### Per-module recipes

Each top-level recipe is a composition of per-module recipes that you can
also run independently. Module names map 1:1 to published Maven modules.

#### Group A — `axon4-to-axon5-*` (free AF5)

| Module                                                     | Recipe                                      |
|------------------------------------------------------------|---------------------------------------------|
| BOM                                                        | `Axon4ToAxon5Bom`                           |
| Messaging                                                  | `Axon4ToAxon5Messaging`                     |
| Modelling                                                  | `Axon4ToAxon5Modelling`                     |
| Event sourcing                                             | `Axon4ToAxon5EventSourcing`                 |
| Common (config + module API; was AF4 `axon-configuration`) | `Axon4ToAxon5Common`                        |
| Conversion (was Serialization)                             | `Axon4ToAxon5Conversion`                    |
| Test (axon-test)                                           | `Axon4ToAxon5Test`                          |
| Spring extension                                           | `Axon4ToAxon5SpringExtension`               |
| Spring Boot extension                                      | `Axon4ToAxon5SpringBootExtension`           |
| Spring Boot Actuator extension                             | `Axon4ToAxon5SpringBootActuatorExtension`   |
| Dropwizard Metrics extension                               | `Axon4ToAxon5MetricsDropwizardExtension`    |
| Micrometer Metrics extension                               | `Axon4ToAxon5MetricsMicrometerExtension`    |
| OpenTelemetry tracing extension                            | `Axon4ToAxon5TracingOpenTelemetryExtension` |
| Reactor extension                                          | `Axon4ToAxon5ReactorExtension`              |

Placeholders for extensions without finalized AF5 mappings (Kafka, AMQP, Mongo,
JGroups, Spring Cloud, Multitenancy, CDI, OpenTracing) exist as `axon4-to-axon5-extension-*.yml`
files; they're no-ops today.

#### Group B — `axon4-to-axoniq5-*` (commercial-only additions)

| Module                          | Recipe                               |
|---------------------------------|--------------------------------------|
| Axoniq BOM swap                 | `Axon4ToAxoniq5Bom`                  |
| Axoniq Spring Boot starter swap | `Axon4ToAxoniq5SpringBoot`           |
| Axon Server connector           | `Axon4ToAxoniq5AxonServerConnector`  |
| Sequenced Dead-Letter Queue     | `Axon4ToAxoniq5DeadLetter`           |
| Distributed messaging           | `Axon4ToAxoniq5DistributedMessaging` |
| Testcontainer (Axon Server)     | `Axon4ToAxoniq5Testcontainer`        |

Placeholders for Axoniq-only Maven modules without finalized class-level mappings
(`Axon4ToAxoniq5EventStreaming`, `Axon4ToAxoniq5Postgresql`) exist as
`axon4-to-axoniq5-*.yml` files; they're no-ops today.