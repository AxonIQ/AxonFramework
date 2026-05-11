---
name: axon4-explore
description: >
  Inventory the Axon Framework 4 surface area of a project before migrating to AF5. Use when
  the user says "explore AF4 project", "inventory aggregates", "scan AF4 aggregates", "find
  all aggregates", "what aggregates exist in this project", "before migration", "AF4 audit",
  "list aggregates and their commands/events", or any variation of taking a structured
  snapshot of the AF4 components in a Java or Kotlin codebase. Driven by the `axon-migration`
  OpenRewrite recipe `org.axonframework.migration.find.ExploreAxon4Aggregates`. Read-only —
  does not modify any source files.
---

# axon4-explore

Runs the OpenRewrite recipe `ExploreAxon4Aggregates` against an AF4 project and writes one
YAML document per aggregate to `<project>/.axon4-explore/components/aggregate@<fqn>.yaml`.
The LLM then reads those YAML files to understand the project's aggregate surface area.

## When to use

- The user wants a structured snapshot of every aggregate in their AF4 project before
  migration begins.
- The user wants to see, per aggregate: persistence style, config style, polymorphism role,
  feature flags (snapshots, cache, multi-entity, etc.), and the **command + event vocabulary**
  the aggregate handles.
- The user is about to run AF4→AF5 migration recipes and wants pre-flight context.

## When NOT to use

- The project is already on AF5. This skill is AF4-specific.
- The user wants to *modify* source code. This skill only reads and emits YAML.
- The user wants to inventory non-aggregate components (sagas, projections, command
  gateways, REST controllers). Future component types will live under the same
  `components/<type>@<fqn>.yaml` scheme but are out of scope today.

## What you get

Each aggregate detected produces a YAML record covering: identity (FQN, package, language,
file path), config style (`spring` vs `non-spring`), persistence (`event-sourcing` vs
`state-stored`), feature flags (snapshot trigger, cache, multi-entity, deadlines, JPA,
revision, polymorphism role), and the **command + event vocabulary** the aggregate handles
(FQN of the first parameter of every `@CommandHandler`, `@EventHandler`, and
`@EventSourcingHandler` method). See `references/output-format.md` for the YAML schema.

For implementation details (which AST patterns map to which fields, polymorphism
detection algorithm, edge cases), see `migration/docs/explore-axon4-aggregates/` in the
AxonFramework5 repo. You don't need that to use the skill.

## Invocation

The user invokes this skill with the absolute path to the AF4 project root as `$ARGUMENTS`
(default: the current working directory if not specified).

Run the wrapper script — it Maven-invokes the recipe and prints the output directory:

```bash
bash "${CLAUDE_PLUGIN_ROOT:-/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5}/.claude/skills/axon4-explore/scripts/run.sh" "$ARGUMENTS"
```

Then read the generated YAML files:

```bash
ls "$ARGUMENTS/.axon4-explore/components/"
```

Each file is named `aggregate@<fully.qualified.ClassName>.yaml`. Read on demand — the LLM
typically only needs to load the YAMLs relevant to the user's current question.

## Output contract

For every aggregate detected, the skill produces:

- `<project>/.axon4-explore/components/aggregate@<fqn>.yaml` — full per-aggregate record
  including command/event vocabulary.
- `<project>/target/rewrite/datatables/<timestamp>/org.axonframework.migration.find.AggregateFeaturesTable.csv` —
  flat one-row-per-aggregate CSV with all the same fields. Useful for grep / scripting.

See `examples/bikerental-bike.yaml` for a real example.

## Failure modes

- **No aggregates detected**: the project has no `@Aggregate`-annotated classes and no
  `configureAggregate(...)` calls. The recipe completes successfully and emits nothing.
- **Maven build fails**: the project doesn't compile under the active JDK. The recipe needs
  the project to compile to parse its sources. Fix the project's compilation first.
- **Migration JAR not installed**: the user hasn't published `org.axonframework:axon-migration:5.1.1-SNAPSHOT`
  to their local `~/.m2`. From the AxonFramework5 repo: `./mvnw -pl migration install -DskipTests`.

## Caveats

- Re-running the skill **overwrites** existing YAML files in `.axon4-explore/`. Tell the
  user to add `.axon4-explore/` to their `.gitignore` if they don't want it tracked.
- The recipe relies on type resolution — if Axon 4 isn't on the project's compile classpath,
  some annotation matches fall back to simple-name matching, which can produce false
  positives for projects that define their own `Aggregate` / `EventHandler` annotations.
- Polymorphism Signal 2 (`withSubtypes`) requires the call to be chained directly off
  `configureAggregate(...)`. Indirect / variable-bound chains are flagged in `notes` rather
  than resolved automatically.
