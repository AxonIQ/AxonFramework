# Migration

OpenRewrite recipes for migrating Axon Framework applications from 4.x to 5.x.

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

Run the upgrade recipe against a target project with the Maven plugin:

```bash
mvn -U org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.recipeArtifactCoordinates=org.axonframework:axon-migration:LATEST \
  -DactiveRecipes=org.axonframework.migration.UpgradeAxonFramework4to5
```

Available recipes are declared under `src/main/resources/META-INF/rewrite/`.

## Java version upgrade

The top-level `UpgradeAxonFramework4to5` recipe upgrades the Java compiler target as its first step.
Two recipes work together:

1. **`org.openrewrite.java.migrate.UpgradeToJava21`** (upstream, from `rewrite-migrate-java`)
   chains source-level modernizations from Java 8 up through 21 — text blocks, pattern matching,
   sequenced collections, `javax`→`jakarta` rewrites, removed/deprecated API replacements. It is
   self-gating: modules already on Java 21+ are left untouched.

2. **`org.axonframework.migration.UpgradeJava`** (this module) bumps the compiler target in
   `pom.xml`/`build.gradle`. It is a thin, validated wrapper around `UpgradeJavaVersion` that:
   - defaults to **Java 25** (the latest LTS at the time of writing);
   - rejects targets below 21 with a clear error, since Axon Framework 5 requires Java 21+;
   - is a no-op for modules already at or above the requested target.

### Choosing the target version

Default behavior — running `UpgradeAxonFramework4to5` upgrades the build to **Java 25**.

If you want to stay on Java 21 (or pin to any other LTS ≥ 21), wrap the upgrade in your own
`rewrite.yml` placed at the root of the project being migrated:

```yaml
# rewrite.yml in the project you are migrating
---
type: specs.openrewrite.org/v1beta/recipe
name: com.acme.UpgradeAxonForUs
displayName: Upgrade to Axon 5, keep Java 21
recipeList:
  - org.openrewrite.java.migrate.UpgradeToJava21
  - org.axonframework.migration.UpgradeJava:
      targetVersion: 21
  - org.openrewrite.maven.UpgradeDependencyVersion:
      groupId: org.axonframework
      artifactId: "*"
      newVersion: 5.x
  # Compose the rest of the AF4→AF5 migration:
  - org.axonframework.migration.AxonFramework4to5SpringExtension
  - org.axonframework.migration.AxonFramework4to5SpringBootExtension
  - org.axonframework.migration.AxonFramework4to5MessagingRenames
  - org.axonframework.migration.AxonFramework4to5ModellingRenames
  - org.axonframework.migration.AxonFramework4to5EventSourcingRenames
  - org.axonframework.migration.AxonFramework4to5ConfigurationRenames
  - org.axonframework.migration.AxonFramework4to5SerializationToConversion
  - org.axonframework.migration.AxonFramework4to5TestFixtureRenames
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
  -DactiveRecipes=org.axonframework.migration.AxonFramework4to5MessagingRenames,org.axonframework.migration.AxonFramework4to5ModellingRenames
```

Each per-module recipe (Messaging, Modelling, EventSourcing, Configuration, TestFixture, Spring,
SpringBoot, Metrics, Micrometer, Tracing-OpenTelemetry, …) is independently runnable.
