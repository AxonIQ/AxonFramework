# Migration

OpenRewrite recipes for migrating Axon Framework applications from 4.x to 5.x.

## Pick your destination

Axon Framework 5 ships in two licensed flavours. Both may be source-available;
the difference is the license, not openness.

- **Free Axon Framework 5** (Apache 2.0) — `org.axonframework.*` artifacts.
  Use if your AF4 application doesn't depend on Axon Server, distributed
  command bus, or the sequenced dead-letter queue. Those features were
  **dropped from free AF5** and now live only in Axoniq Framework 5
  commercial. Snapshotting is partially redesigned in free AF5
  (`Snapshotter` interface relocated to `org.axonframework.eventsourcing.snapshot.api`;
  `EventCountSnapshotTriggerDefinition` / `SpringAggregateSnapshotterFactoryBean` /
  `AggregateSnapshotter` are removed without direct rename targets — those
  uses need manual migration).
- **Axoniq Framework 5** (commercial) — `io.axoniq.framework.*` artifacts.
  Required if you used any of the dropped features in AF4. Composes the
  free migration first and then layers the commercial-only changes on top.

> Note: free AF5 and Axoniq AF5 are sibling distributions of the same release
> line, not different versions. Picking Axoniq is a license choice, not a
> version bump.

### Two top-level recipes

| Recipe | What it does | When to use |
|---|---|---|
| `org.axonframework.migration.UpgradeAxon4ToAxon5` | Migrates AF4 → free AF5. Renames packages/classes and updates Maven coordinates within the `org.axonframework.*` namespace. | You want to land on free AF5. **Warning**: if your AF4 app uses Axon Server / distributed command bus / DLQ, this recipe alone leaves your codebase non-compiling — those features moved to commercial. Use `UpgradeAxon4ToAxoniq5` instead. |
| `org.axonframework.migration.UpgradeAxon4ToAxoniq5` | Composes `UpgradeAxon4ToAxon5` then layers commercial-only migrations on top: source rewrites for Axon Server / DLQ / distributed-messaging, BOM swap to `axoniq-framework-bom`, Spring Boot starter swap to `axoniq-spring-boot-starter`, conditional `AddDependency` for Axoniq jars. | Recommended default for AF4 applications using Axon Server / DLQ / distributed messaging. |

### Per-module recipes

Each top-level recipe is a composition of per-module recipes that you can
also run independently. Module names map 1:1 to published Maven modules.

#### Group A — `axon4-to-axon5-*` (free AF5)

| Module | Recipe |
|---|---|
| BOM | `Axon4ToAxon5Bom` |
| Messaging | `Axon4ToAxon5Messaging` |
| Modelling | `Axon4ToAxon5Modelling` |
| Event sourcing | `Axon4ToAxon5EventSourcing` |
| Configuration | `Axon4ToAxon5Configuration` |
| Conversion (was Serialization) | `Axon4ToAxon5Conversion` |
| Test fixture | `Axon4ToAxon5TestFixture` |
| Spring extension | `Axon4ToAxon5SpringExtension` |
| Spring Boot extension | `Axon4ToAxon5SpringBootExtension` |
| Spring Boot Actuator extension | `Axon4ToAxon5SpringBootActuatorExtension` |
| Dropwizard Metrics extension | `Axon4ToAxon5MetricsExtension` |
| Micrometer extension | `Axon4ToAxon5MicrometerExtension` |
| OpenTelemetry tracing extension | `Axon4ToAxon5TracingOpenTelemetryExtension` |
| Reactor extension | `Axon4ToAxon5ReactorExtension` |

Placeholders for extensions without finalized AF5 mappings (Kafka, AMQP, Mongo,
JGroups, Spring Cloud, Multitenancy, CDI, OpenTracing) exist as `axon4-to-axon5-extension-*.yml`
files; they're no-ops today.

#### Group B — `axon4-to-axoniq5-*` (commercial-only additions)

| Module | Recipe |
|---|---|
| Axoniq BOM swap | `Axon4ToAxoniq5Bom` |
| Axoniq Spring Boot starter swap | `Axon4ToAxoniq5SpringBoot` |
| Axon Server connector | `Axon4ToAxoniq5AxonServerConnector` (also covers `AxonServerSnapshotStore` since it lives in this Maven module) |
| Sequenced Dead-Letter Queue | `Axon4ToAxoniq5DeadLetter` |
| Distributed messaging | `Axon4ToAxoniq5DistributedMessaging` |

Placeholders for Axoniq-only Maven modules without finalized class-level mappings
(`Axon4ToAxoniq5EventStreaming`, `Axon4ToAxoniq5Postgresql`) exist as
`axon4-to-axoniq5-*.yml` files; they're no-ops today.

## Layout

```
migration/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/org/axonframework/migration/        # Imperative Java recipes
    │   └── resources/META-INF/rewrite/              # Declarative YAML recipes
    └── test/
        └── java/org/axonframework/migration/        # Recipe tests
```

## Build

```bash
./mvnw -pl migration -am clean install
```

## Usage

Run a top-level recipe against a target project with the Maven plugin:

```bash
mvn -U org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.recipeArtifactCoordinates=org.axonframework:axon-migration:LATEST \
  -DactiveRecipes=org.axonframework.migration.UpgradeAxon4ToAxon5
```

Replace the recipe name with `UpgradeAxon4ToAxoniq5` for the commercial path.

## Java version upgrade

Both top-level recipes bump the Java compiler target as their first step, via
**`org.axonframework.migration.UpgradeJava`** — a thin, validated wrapper around
the upstream `UpgradeJavaVersion` recipe that:

- defaults to **Java 25** (the latest LTS at the time of writing);
- rejects targets below 21 with a clear error, since Axon Framework 5 requires Java 21+;
- is a no-op for modules already at or above the requested target.

This recipe only updates build files (`pom.xml` / `build.gradle`). **It does not apply source-level
Java modernizations** (text blocks, pattern matching, sequenced collections, `javax`→`jakarta`,
removed/deprecated API rewrites). If you want those as well, run the upstream
`org.openrewrite.java.migrate.UpgradeToJava21` recipe separately, or compose it into your own
wrapping recipe (see below).

### Choosing the target version

Default behavior — running either top-level recipe upgrades the build to **Java 25**.

If you want to stay on Java 21 (or pin to any other LTS ≥ 21), wrap the upgrade in your own
`rewrite.yml` placed at the root of the project being migrated:

```yaml
# rewrite.yml in the project you are migrating
---
type: specs.openrewrite.org/v1beta/recipe
name: com.acme.UpgradeAxonForUs
displayName: Upgrade to Axon 5, keep Java 21
recipeList:
  - org.axonframework.migration.UpgradeJava:
      targetVersion: 21
  # Optional: also apply source-level Java modernizations.
  # - org.openrewrite.java.migrate.UpgradeToJava21
  - org.openrewrite.maven.UpgradeDependencyVersion:
      groupId: org.axonframework
      artifactId: "*"
      newVersion: 5.x
  # Compose the rest of the AF4 → free-AF5 migration:
  - org.axonframework.migration.Axon4ToAxon5Bom
  - org.axonframework.migration.Axon4ToAxon5SpringExtension
  - org.axonframework.migration.Axon4ToAxon5SpringBootExtension
  - org.axonframework.migration.Axon4ToAxon5Messaging
  - org.axonframework.migration.Axon4ToAxon5Modelling
  - org.axonframework.migration.Axon4ToAxon5EventSourcing
  - org.axonframework.migration.Axon4ToAxon5Configuration
  - org.axonframework.migration.Axon4ToAxon5Conversion
  - org.axonframework.migration.Axon4ToAxon5TestFixture
```

Then invoke your wrapper recipe:

```bash
mvn -U org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.recipeArtifactCoordinates=org.axonframework:axon-migration:LATEST \
  -DactiveRecipes=com.acme.UpgradeAxonForUs
```

### Why a wrapper recipe and not `-D`

The OpenRewrite Maven plugin does not expose recipe-level `@Option` values as `-D` properties.
Customization happens by composing recipes — define a thin top-level recipe that references
`org.axonframework.migration.UpgradeJava` with the `targetVersion` you want, and invoke that
recipe instead. This is the same pattern OpenRewrite themselves use for Spring and JUnit
upgrade variants.

### Skipping the Java upgrade entirely

If the project being migrated already manages its Java version through other means and you only
want the Axon-specific renames, run the per-module recipes directly:

```bash
mvn -U org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.recipeArtifactCoordinates=org.axonframework:axon-migration:LATEST \
  -DactiveRecipes=org.axonframework.migration.Axon4ToAxon5Messaging,org.axonframework.migration.Axon4ToAxon5Modelling
```

Each per-module recipe is independently runnable.
