---
name: axon4-to-axon5-openrewrite
description: >-
  Run the Axon Framework 4 → 5 OpenRewrite recipes (`org.axonframework:axon-migration`)
  against a target project. Interactively asks the user (a) which target
  repository to apply the recipes against, (b) which licensed flavour to
  land on — **free** (Apache 2.0, `org.axonframework.*`) via
  `UpgradeAxon4ToAxon5` or **Axoniq commercial** (`io.axoniq.framework.*`)
  via `UpgradeAxon4ToAxoniq5`, and (c) whether to run the top-level recipe
  or a curated subset of per-module recipes. Inspects the target project
  first (build tool, AF4 dependencies, use of Axon Server / DLQ /
  DistributedCommandBus) so the choice is informed, not blind. Then runs
  the OpenRewrite Maven plugin against the target project and reports
  what changed. Atomic — one OpenRewrite invocation per run.
---

# AF4 → AF5: run OpenRewrite migration recipes

Drives the published `org.axonframework:axon-migration` OpenRewrite
recipes against a user-named target project. This is the **bulk
mechanical** leg of the AF4 → AF5 migration (package renames, FQN moves,
Maven coordinate swaps, BOM swaps, dependency version bumps, Java
compiler-target bump). Anything the recipes *can't* mechanically rewrite
is left to the per-construct migration skills (`axon4-to-axon5-eventprocessor`,
`axon4-to-axon5-eventsourcing-aggregate`, `axon4-to-axon5-commandgateway`,
`axon4-to-axon5-querygateway`, `axon4-to-axon5-queryhandler`,
`axon4-to-axon5-readconfiguration`, `axon4-to-axon5-writeconfiguration`).

> **Keep this skill generic.** It is invoked across many target projects.
> All project-specific knowledge — recipe choices that worked, surprises
> in a given codebase — lives in `references/examples/` only.

## What this runs

Two top-level recipes are available, plus a roster of per-module recipes
that compose into them:

| Recipe | Scope | When to pick |
|---|---|---|
| `org.axonframework.migration.UpgradeAxon4ToAxon5` | Free AF5 (Apache 2.0). Bumps Java compiler target, upgrades Spring Boot to 3.5.x, renames packages/classes inside `org.axonframework.*`, bumps Maven coordinates, swaps the BOM. | Target app does **not** use Axon Server, the sequenced DLQ, or `DistributedCommandBus`. Those features were dropped from free AF5. |
| `org.axonframework.migration.UpgradeAxon4ToAxoniq5` | Commercial Axoniq AF5 (`io.axoniq.framework.*`). Composes the free leg first (including Spring Boot upgrade), then layers commercial-only rewrites: Axon Server connector, DLQ, distributed messaging, BOM swap to `axoniq-framework-bom`, Spring Boot starter swap to `axoniq-spring-boot-starter`. | Target app uses **any** of: Axon Server connector, sequenced DLQ, `DistributedCommandBus`. Recommended default if unsure — the free recipe alone leaves those projects non-compiling. |

Both top-level recipes include `org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_5`,
which bumps `spring-boot-starter-parent` (or `spring-boot-dependencies` BOM) to the
latest 3.5.x patch and applies all Spring Boot 3.x source changes (most notably
`javax` → `jakarta`). **Projects already at Spring Boot 3.5.x or later (including
Spring Boot 4.x) are unaffected** — OpenRewrite never downgrades version numbers, and
the source-level sub-recipes are idempotent against already-migrated code.

Per-module recipes (runnable independently when the user wants only one
slice — e.g. just messaging, or just the Spring Boot starter) are listed
in `migration/README.md`. Use them when a full top-level run is overkill
(e.g. a small isolated module).

## Selection rule

The skill processes **one** target repository per run. If the user named
a path, use it. Otherwise, ask. Never run the recipes against the Axon
Framework repository itself.

## Procedure

### 1. Determine the target repository

The recipes must run **inside the target project's working tree**, not
in the AxonFramework repo. If the user did not name a path:

- Use `AskUserQuestion` to ask for the absolute path of the target
  project. Offer the most recently-mentioned candidates as options when
  available; always include "Other" so the user can paste a path.
- Validate that the path exists, contains a `pom.xml` (or
  `build.gradle` / `build.gradle.kts`), and is a git repository
  (`git -C <path> rev-parse --show-toplevel` succeeds).
- **Refuse** if the path resolves inside the AxonFramework repo
  (`git -C <path> remote get-url origin` matches AxonFramework). The
  recipes are designed for **consumer** projects, not the framework
  itself.

### 2. Pre-flight: confirm the working tree is clean

OpenRewrite rewrites files in place. Mid-run failures or a wrong recipe
choice are easiest to recover from with a clean baseline.

```bash
git -C <target> status --porcelain
```

If the output is non-empty, surface it to the user via
`AskUserQuestion` with three options:

1. **Stash and continue** — run `git -C <target> stash push -u -m "pre-openrewrite"` and proceed.
2. **Commit first, then continue** — pause and let the user commit; re-run the skill afterwards.
3. **Continue anyway** — proceed without stashing (only sensible when the dirty files are intentional unrelated WIP).

Default recommendation: **commit first**. Make that the first option and
mark it `(Recommended)`.

### 3. Detect the build tool

```bash
ls <target>/pom.xml <target>/build.gradle <target>/build.gradle.kts 2>/dev/null
```

- `pom.xml` present → Maven. Use the `rewrite-maven-plugin` invocation
  shown below.
- `build.gradle*` only → Gradle. The Axon migration JAR is published to
  Maven Central; OpenRewrite has a Gradle plugin equivalent
  (`org.openrewrite.rewrite`). **This skill currently scaffolds only
  the Maven invocation.** For Gradle, fall back to instructing the user
  manually and stop — do not silently emit a half-baked Gradle config.
- Both present → ask the user which to drive (Maven is preferred when
  available; the recipes were primarily exercised through it).

### 4. Inspect the target for free-vs-commercial signals

Before asking the user which top-level recipe to run, look at the
project so the question is informed. Run, in the target root:

```bash
# AF4 dependency footprint — narrow the search to dependency declarations.
grep -RE 'org\.axonframework' --include='pom.xml' --include='build.gradle*' <target>

# Source-level signals that the project uses commercial-only features.
grep -RE 'AxonServer|DistributedCommandBus|SequencedDeadLetterQueue|DeadLetter' \
     --include='*.java' --include='*.kt' <target>/src 2>/dev/null
```

Classify the signals into three buckets:

- **Strong commercial signal** — any of: a dependency on
  `axon-server-connector`, `axon-distributed-commandbus-*`, references
  to `AxonServerConfiguration`, `DistributedCommandBus`,
  `SequencedDeadLetterQueue` from `org.axonframework.eventhandling.deadletter.*`,
  or `DeadLetter` types being explicitly handled.
- **Weak commercial signal** — Spring Boot autoconfig wiring an Axon
  Server profile but no source references; commented-out DLQ snippets;
  test-only references.
- **No commercial signal** — only core `axon-messaging` /
  `axon-modelling` / `axon-eventsourcing` / Spring Boot starter
  dependencies and no source references to the dropped features.

Record the bucket (you'll use it to recommend the right recipe in step 5).

### 5. Ask which recipe(s) to run — interactively

Issue a single `AskUserQuestion` call with **two questions**:

**Question 1 — license target.** Header: `License`.
- `Free Axon Framework 5 (Apache 2.0)` — runs `UpgradeAxon4ToAxon5`.
  Description should note: requires the project to *not* use Axon Server,
  DLQ, or `DistributedCommandBus`.
- `Axoniq Framework 5 (commercial)` — runs `UpgradeAxon4ToAxoniq5`.
  Description should note: required when the project uses Axon Server,
  DLQ, or `DistributedCommandBus`; recommended default otherwise too.

If step 4 found a **strong commercial signal**, make the Axoniq option
first and label it `(Recommended)`. If **no commercial signal**, make
the free option first and label it `(Recommended)`. Mention the
specific artifacts/classes you found in the question text so the user
sees the evidence behind the recommendation.

**Question 2 — recipe scope.** Header: `Scope`.
- `Top-level recipe` — runs the full composition (recommended default).
- `Per-module subset` — user picks a subset; ask a follow-up
  `AskUserQuestion` (multiSelect) listing the per-module recipe names
  from `migration/README.md` filtered to the chosen license group
  (Group A for free, Group A + Group B for commercial).

Make `Top-level recipe` the first option and mark it `(Recommended)`
unless the inspection in step 4 turned up only one module's worth of
AF4 surface area.

### 6. Construct the OpenRewrite invocation

Maven invocation template:

```bash
mvn -U -f <target>/pom.xml \
    org.openrewrite.maven:rewrite-maven-plugin:run \
    -Drewrite.recipeArtifactCoordinates=org.axonframework:axon-migration:LATEST \
    -DactiveRecipes=<recipe1>,<recipe2>,...
```

- `-U` forces a fresh resolution of the recipe artifact (the migration
  JAR ships frequent fixes). Keep it.
- `-DactiveRecipes` is comma-separated; for a top-level run there's
  exactly one entry. For a per-module subset there are several.
- Use `LATEST` for `recipeArtifactCoordinates` unless the user pinned a
  version — the recipes are reproducible and bug fixes land continually.
  Mention the resolved version in your post-run summary so the user
  can pin it later if they want a stable run.
- For **Gradle** targets you stopped at step 3. Don't emit a Maven
  command for them.

### 7. Show the user the exact command, then run it

Print the command verbatim before executing — this is a destructive
operation against their working tree, and they should see what's about
to happen even though step 2 confirmed a clean baseline. Run it from
the target directory (or with `-f <target>/pom.xml`); never `cd` into
the target if the user is working from a different directory.

```bash
mvn -U -f <target>/pom.xml \
    org.openrewrite.maven:rewrite-maven-plugin:run \
    -Drewrite.recipeArtifactCoordinates=org.axonframework:axon-migration:LATEST \
    -DactiveRecipes=org.axonframework.migration.UpgradeAxon4ToAxoniq5
```

If the run fails to resolve the recipe artifact, fall back to the
explicit current release (`5.1.0` at the time of writing — confirm
against `migration/pom.xml`'s `${revision}` if in doubt) instead of
`LATEST`.

### 8. Summarize what changed

After the recipe run completes:

```bash
git -C <target> status --short
git -C <target> diff --stat
```

Report to the user:
- Which recipe(s) ran and the resolved artifact version.
- File-count summary (added / modified) from `git diff --stat`.
- Hot spots: the modules with the most changes (inspect the diff stats
  and surface the top 3 paths).
- A reminder that mechanical rewrites only cover what the recipes
  encode — the per-construct migration skills (listed at the top of
  this file) handle the human-judgment-required leftovers
  (`CommandGateway` calls inside handlers, `@EventSourcingHandler`
  reorganization, configuration enhancers, etc.).

### 9. Stop and let the user verify

Do **not** run the target's test suite. Do **not** stage or commit the
changes. The user decides whether the diff is acceptable, fixes the
non-mechanical leftovers using the per-construct skills, and commits
when ready. If the user stashed in step 2, remind them about
`git stash pop`.

## Caveats

- **Don't run the recipe inside the AxonFramework repo.** The recipes
  are for consumer projects — running them against the framework itself
  rewrites framework source. Step 1 enforces this.
- **`LATEST` is reproducible per run, not across days.** If the user
  wants a deterministic re-run later, capture the resolved version from
  the Maven log and pin it.
- **Free vs commercial is a license choice, not a version bump.** The
  two top-level recipes target the same release line; picking one
  doesn't lock the user into a different upgrade cadence later.
- **Spring Boot upgrade is safe on already-modern projects.** The
  `UpgradeSpringBoot_3_5` step is a no-op when the project is already
  on Spring Boot 3.5.x or newer (including Spring Boot 4.x). OpenRewrite
  will not downgrade the version.
- **Compile failures after the recipe are normal.** The skill name is
  intentionally about *running OpenRewrite*, not about leaving a
  green build behind. The per-construct migration skills (and possibly
  `axon4-to-axon5-maven-migration-profile` for scoped iterative
  verification) take it from here.
- **Gradle targets are out of scope today.** Step 3 hands off to the
  user with instructions rather than emitting a partial Gradle config.

## Reference docs

- `migration/README.md` — full per-module recipe inventory (Group A free,
  Group B commercial), Maven invocation patterns, wrapper-recipe pattern
  for customizing `targetVersion` on the Java compiler bump.
- `docs/reference-guide/modules/migration/pages/paths/index.adoc` —
  human-language migration paths the recipes implement (import & package
  changes table).

## Examples

`references/examples/` — one file per real run, preserved verbatim. New
runs are added, not merged: if a project surfaces a new recipe-choice
pattern (e.g. needed a per-module subset because the project mixes AF4
and partially-migrated modules), drop in
`references/examples/<NN>-<project>-<short-desc>.md`.

(empty on creation — populate from real invocations as they happen.)

## Notes for the human

- This skill is iteratively improved via the `reflect` skill. After a
  run, if anything surprised you (recipe choice, target detection,
  command failure), reflect so the lesson lands here.
- This skill **only runs the recipes**. It does not handle the
  per-construct, judgment-call rewrites — those are sibling skills.
