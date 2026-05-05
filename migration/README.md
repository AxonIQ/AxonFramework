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
