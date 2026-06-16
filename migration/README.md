# Migration

Module containing [OpenRewrite](https://docs.openrewrite.org/) recipes for migrating Axon Framework applications from
4.x to 5.x.

## What it does

Running a top-level recipe is the first automated step toward AF5 — it rewrites the parts of your AF4 codebase and build
files that can be transformed mechanically. It does **not** guarantee a compiling project at the end: every codebase is
different, and the changes that can't be safely automated are left for you (or an AI-assisted follow-up) to finish by
hand. Concretely, the recipes do:

Where a recipe made a change that **the compiler will not flag** but that still needs your attention
(for example: an annotation attribute that has no AF5 equivalent, a fallback value the recipe could
not infer, or an interceptor body that compiles but still references AF4 APIs), it leaves a marker
comment so the location is easy to find:

- Java / Kotlin line comment: `// TODO(axon4to5): <action>`
- Java / Kotlin inline (inside expressions): `/* TODO(axon4to5): <action> */`
- Properties / YAML: `# TODO(axon4to5): <action>`

After the migration run, search for every such location with:

    grep -r "TODO(axon4to5)" src/

- **Build files**: swaps the BOM (`axon-bom` → `axon-framework-bom` or `axoniq-framework-bom`), bumps the Java
  source/target to the configured LTS (minimum Java 21), and removes dependencies with no AF5 port (e.g.
  `axon-spring-aot`).
- **Package and class renames**: moves handler annotations into `.annotation.*` subpackages, renames `EventBus` →
  `EventSink`, `ConfigurerModule` → `ConfigurationEnhancer`, and shifts extension packages
  (e.g. `org.axonframework.micrometer` → `org.axonframework.extension.metrics.micrometer`).
- **Annotation rewrites**: `@Aggregate` → `@EventSourced` (with `tagKey` and `idType`), `@AggregateRoot` →
  `@EventSourcedEntity`, `@AggregateIdentifier` removed (identity now flows through `tagKey` + `@EventTag`),
  `@TargetAggregateIdentifier` → `@TargetEntityId`, `@Revision` → `@Event(version = …)`, `@ProcessingGroup` →
  `@Namespace`. **Removes `@CreationPolicy` and `AggregateCreationPolicy`** unconditionally and **adds `@EntityCreator`
  to no-arg constructors** (creating one if missing). Together this preserves `CREATE_IF_MISSING` semantics for
  instance command handlers without further work — AF5 materializes an empty entity via the no-arg `@EntityCreator`
  and runs the handler. `ALWAYS` → `static` is the only `@CreationPolicy` value the recipe does not translate
  automatically; reshape the handler manually after the run. Also lifts `@RoutingKey` from record/data-class
  parameters onto class-level `@Command`, and adds `@EventTag` to record components matching entity id fields.
- **Message API**: drops generic type arguments on message types, renames accessors (`getPayload()` → `payload()`,
  `getMetaData()` → `metadata()`, `getIdentifier()` → `identifier()`, …), rewrites two-arg `SequencingPolicy`
  lambdas to return `Optional`, and replaces static `AggregateLifecycle.apply(...)` with an injected
  `EventAppender#append(...)`.
- **Query API**: unwraps `ResponseTypes.instanceOf(X.class)` to a direct class argument and renames `query()` →
  `queryMany()` when paired with `multipleInstancesOf`.
- **Command handling**: converts `@CommandHandler` constructors to `public static void handle(...)` methods, and swaps
  in-handler `CommandGateway` fields for injected `CommandDispatcher` parameters.
- **Spring config**: renames `axon.serializer.*` properties to `axon.converter.*` in `application.properties`/YAML, and
  adds advisory TODOs above obsolete `sequencing-policy` settings. Also **migrates Spring Boot snapshotting
  configuration** from the AF4 two-step pattern (`@Bean SnapshotTriggerDefinition` + `@Aggregate(snapshotTriggerDefinition
  = "…")`) to the AF5 `@Snapshotting` annotation on the entity class, removing the bean definition:
  `EventCountSnapshotTriggerDefinition(snapshotter, N)` → `@Snapshotting(afterEvents = N)`;
  `AggregateLoadTimeSnapshotTriggerDefinition(snapshotter, millis)` → `@Snapshotting(afterSourcingTime = "PTxS")`.
  Custom implementations that cannot be inferred automatically receive a `// TODO(axon4to5):` comment instead.
- **Test fixtures**: replaces `AggregateTestFixture`/`SagaTestFixture` with `AxonTestFixture` and rewrites the fluent
  Given-When-Then chain to the new phase-aware API.
- **Commercial path** (`UpgradeAxon4ToAxoniq5` only): additionally re-namespaces Axon Server connector, DLQ,
  distributed-messaging, and Testcontainer classes from `org.axonframework.*` to `io.axoniq.framework.*` and swaps the
  Spring Boot starter to `axoniq-spring-boot-starter`.

## Usage

### Maven

Run a top-level recipe against a target project with the Maven plugin:

```bash
mvn -U org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.recipeArtifactCoordinates=org.axonframework:axon-migration:5.2.0-SNAPSHOT \
  -Drewrite.activeRecipes=io.axoniq.framework.migration.UpgradeAxon4ToAxoniq5
```

Replace the recipe name with `org.axonframework.migration.UpgradeAxon4ToAxoniq5` for the non-commercial path.

### Gradle

Gradle projects don't carry the OpenRewrite plugin by default, so the
recipes are applied via the [init script](init.gradle) shipped in this
module. Copy `migration/init.gradle` into your target project (or
reference it by absolute path) and run:

```bash
gradle rewriteRun --init-script init.gradle \
  -Drewrite.activeRecipe=io.axoniq.framework.migration.UpgradeAxon4ToAxoniq5
```

Replace the recipe name with `org.axonframework.migration.UpgradeAxon4ToAxon5`
for the non-commercial path. Override the recipe artifact version (default
`5.2.0-SNAPSHOT`) with `-Drewrite.axonMigrationVersion=<version>` if you
need a different release. The script also wires Sonatype Snapshots and
Maven Local so `-SNAPSHOT` coordinates resolve without extra
configuration.

> Do **not** combine the init script with a `rewrite { }` block in
> `build.gradle(.kts)` -- pick one or the other (an OpenRewrite
> constraint).

> Run Gradle on **JDK 21**. Gradle 8.x ships Groovy 3, which fails to
> parse the init script on JDK 25 with `Unsupported class file major
> version 69`. If `./gradlew --version` reports a Launcher JVM newer
> than 21, prefix the command with
> `JAVA_HOME=/path/to/jdk-21` (or set `org.gradle.java.home` in
> `gradle.properties`).

For background, see the upstream guide on
[running rewrite on a Gradle project without modifying the build](https://docs.openrewrite.org/running-recipes/running-rewrite-on-a-gradle-project-without-modifying-the-build).

### Two top-level recipes

| Recipe                                                | What it does                                                                                                                                                                                                                                                                            | When to use                                                                                                                                                                                                                                            |
|-------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `org.axonframework.migration.UpgradeAxon4ToAxon5`     | Migrates AF4 → non-commercial AF5. Renames packages/classes and updates Maven coordinates within the `org.axonframework.*` namespace.                                                                                                                                                   | You want to land on non-commercial AF5. **Warning**: if your AF4 app uses Axon Server / distributed command bus / DLQ, this recipe alone leaves your codebase non-compiling — those features moved to commercial. Use `UpgradeAxon4ToAxoniq5` instead. |
| `io.axoniq.framework.migration.UpgradeAxon4ToAxoniq5` | Composes `UpgradeAxon4ToAxon5` then layers commercial-only migrations on top: source rewrites for Axon Server / DLQ / distributed-messaging, BOM swap to `axoniq-framework-bom`, Spring Boot starter swap to `axoniq-spring-boot-starter`, conditional `AddDependency` for Axoniq jars. | Recommended default for AF4 applications using Axon Server / DLQ / distributed messaging.                                                                                                                                                              |

### Per-module recipes

Each top-level recipe is a composition of per-module recipes that you can
also run independently. Module names map 1:1 to published Maven modules.

#### Group A — `axon4-to-axon5-*` (non-commercial AF5)

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

#### Group B — `axon4-to-axoniq5-*` (commercial-only additions, namespace `io.axoniq.framework.migration`)

| Module                          | Recipe                               |
|---------------------------------|--------------------------------------|
| Axoniq BOM swap                 | `Axon4ToAxoniq5Bom`                  |
| Axoniq Spring Boot starter swap | `Axon4ToAxoniq5SpringBoot`           |
| Axon Server connector           | `Axon4ToAxoniq5AxonServerConnector`  |
| Sequenced Dead-Letter Queue     | `Axon4ToAxoniq5DeadLetter`           |
| Distributed messaging           | `Axon4ToAxoniq5DistributedMessaging` |
| Testcontainer (Axon Server)     | `Axon4ToAxoniq5Testcontainer`        |

A placeholder for the Axoniq-only event-streaming module without finalized
class-level mappings (`Axon4ToAxoniq5EventStreaming`) exists as an
`axon4-to-axoniq5-*.yml` file; it is a no-op today.