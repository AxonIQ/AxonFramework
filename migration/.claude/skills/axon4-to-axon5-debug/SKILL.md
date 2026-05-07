---
name: axon4-to-axon5-debug
description: >-
  Debug and fix Axon Framework 4 to Axon Framework 5 compilation failures
  after OpenRewrite has already run. Runs the current Maven or Gradle build,
  clusters compiler diagnostics by likely root cause, delegates one
  highest-leverage cluster at a time to the matching atomic
  axon4-to-axon5-* migration skill, reruns compilation, and repeats from
  fresh evidence. Use for post-OpenRewrite build failures, AF4-to-AF5 compile
  triage, or requests to route migration errors to dedicated skills. Do not
  use for the full phased migration flow; use axon4-to-axon5-migration for
  that.
---

# AF4 -> AF5: compile-debug orchestrator

This skill turns compiler output into an evidence-driven fix loop. It is
different from `axon4-to-axon5-migration`: it does not care about migration
phases, phase checkpoints, or phase progress. The only source of truth is the
current build output after OpenRewrite and any previous manual or delegated
fixes.

## Goal

Reach a compiling project by repeatedly:

1. running main + test compilation,
2. clustering diagnostics into likely root causes,
3. delegating one high-leverage cluster to the matching sibling migration
   skill,
4. rerunning compilation, and
5. rebuilding the queue from fresh evidence.

Do not fix diagnostics line-by-line. One missing dependency, stale package, or
unmigrated construct can create dozens of cascade errors. Find the root cause
group first.

## Preconditions

- OpenRewrite has already run for the target project.
- The target project is the project being migrated, not the Axon Framework 5
  repository that stores this skill.
- If the target has `.axon4-to-axon5-migration/learnings.md`, read it before
  routing. Pull in only entries that explain the errors in front of you.
- Do not create a separate debug progress directory.
- Do not update `.axon4-to-axon5-migration/progress.md`; this skill is not the
  phased migration orchestrator.

## Debug loop

### 1. Determine the target and build tool

If the user did not name a target path, use the current working directory when
it contains `pom.xml`, `mvnw`, `build.gradle`, `build.gradle.kts`, or `gradlew`.
Otherwise ask for the target path.

Prefer wrapper scripts when present.

### 2. Run compilation as the source of truth

Use main + test compilation by default so fixtures, migrated tests, and test
support code are included without running the test suite.

Maven:

```bash
./mvnw test-compile -DskipTests -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

Gradle:

```bash
./gradlew testClasses -x test
```

If the project has an existing migration profile and the full compile is
blocked by intentionally unmigrated code, retry with the profile only when the
error evidence says the build cannot reach the current migrated scope:

```bash
./mvnw -Pmigration test-compile -DskipTests -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

Do not treat a stale migration profile as truth. If the profile excludes files
needed by the current root-cause group, route to
`axon4-to-axon5-maven-migration-profile`.

### 3. Normalize diagnostics

For each compiler diagnostic, capture:

- file path and line number,
- module/source set if visible,
- error kind (`cannot find symbol`, incompatible types, missing package,
  method signature mismatch, annotation attribute missing, dependency/build
  failure),
- symbol/type/method/package named in the message,
- nearby source context from the file, and
- whether the error appears primary or cascading.

When Maven or Gradle fails before Java compilation, classify that separately as
build-tool, JDK, dependency, or profile scope. Do not route non-Java build
failures to construct migration skills.

### 4. Cluster by root cause

Group errors by the smallest migration action that is likely to remove the
largest cascade. Good clusters usually share one of:

- same source file or construct,
- same missing AF4 package/type,
- same stale gateway or handler API,
- same test fixture API,
- same configuration API,
- same event-store/storage-engine API,
- same dependency/profile/JDK problem.

Prefer one root-cause group over many superficial message groups. For example,
`AggregateLifecycle.apply`, `AggregateTestFixture`, missing `@Command`, and
missing `@EventTag` around the same aggregate should be one aggregate cluster,
not four unrelated clusters.

### 5. Pick one cluster and delegate

Pick the highest-leverage cluster using this priority:

1. build/JDK/dependency/profile issues that prevent meaningful Java
   diagnostics,
2. dependency or migration-profile gaps that hide the intended target scope,
3. construct roots that create many cascades,
4. production sources before test sources when both fail independently,
5. test fixtures tied to an already-migrated construct,
6. isolated stale imports or obvious leftovers.

Delegate exactly one cluster at a time. Give the sibling skill concrete
targets: file paths, class names, key diagnostics, and the verification command
to rerun afterwards.

Do not send code-mutating sibling-skill work to a subagent when the sibling
skill may need to ask the human a question. The question must reach the main
conversation.

### 6. Rerun and repeat

After the delegated skill finishes, rerun the same compile command (or the
adjusted command if the build/profile changed). Rebuild the diagnostic queue
from scratch. Do not keep following an old queue after code changes.

Continue until:

- compilation is green,
- the next cluster is blocked on missing product intent or unsupported AF5
  feature,
- only manual/out-of-scope errors remain, or
- the user asks to stop.

## Routing matrix

Use the first matching route that explains the root cause. Read the sibling
skill's `SKILL.md` before delegating.

| Evidence in cluster | Route |
|---|---|
| `@Aggregate`, `@AggregateIdentifier`, `@TargetAggregateIdentifier`, `@CreationPolicy`, `AggregateLifecycle.apply`, `@EventSourcingHandler` in an aggregate, command/event payload annotations, `AggregateTestFixture`, `FixtureConfiguration`, aggregate fixture fluent API | `axon4-to-axon5-eventsourcing-aggregate` |
| `@EventHandler`, `@ProcessingGroup`, projector/event processor classes, event-handler-resident `CommandGateway`, sequencing policy migration, DLQ-adjacent event-processing configuration in handler context | `axon4-to-axon5-eventprocessor` |
| Top-of-chain `org.axonframework.commandhandling.gateway.CommandGateway` in REST controllers, schedulers, runners, CLI/service entry points with no active message handler | `axon4-to-axon5-commandgateway` |
| Top-of-chain `org.axonframework.queryhandling.QueryGateway`, `ResponseTypes`, query dispatch callers, subscription/query result shape at an input adapter | `axon4-to-axon5-querygateway` |
| `org.axonframework.queryhandling.QueryHandler`, query-handler annotation package move, query handler class that is not otherwise an event processor | `axon4-to-axon5-queryhandler` |
| Injected/read-only `org.axonframework.config.Configuration`, `EventProcessingConfiguration`, runtime lookups like event processors, command bus, query bus, event store, or lifecycle operations | `axon4-to-axon5-readconfiguration` |
| `Configurer`, `ConfigurerModule`, `DefaultConfigurer`, `EventProcessingConfigurer`, `onStart`, `onShutdown`, component registration, Spring `@Bean` configuration writers | `axon4-to-axon5-writeconfiguration` |
| `JpaEventStorageEngine`, `JdbcEventStorageEngine`, `EmbeddedEventStore`, `AxonServerEventStore`, `EventStorageEngine` bean wiring, aggregate-centric legacy event-store configuration | `axon4-to-axon5-aggregate-eventstorage-engine` |
| Maven migration profile includes/excludes, `-Pmigration` cannot reach the right files, javac compiles transitive files unexpectedly, build scope prevents current migrated code from compiling | `axon4-to-axon5-maven-migration-profile` |

## Limited direct fixes

Directly edit only when no sibling skill owns the construct and the fix is
small, mechanical, and verified by compilation. Allowed examples:

- stale imports left behind after a sibling migration,
- obvious package/class rename leftovers not covered by any sibling skill,
- small dependency, plugin, profile, or wrapper-command fixes,
- deleting an unused import after a delegated fix.

Never directly rewrite a construct that has a dedicated sibling skill. If the
cluster is an aggregate, gateway, handler, configuration reader/writer, event
processor, storage engine, or migration profile issue, delegate.

For manual or unsupported work, stop with a concrete diagnosis instead of
guessing. Name the missing migration path, unsupported feature, or product
decision needed.

## Learning behavior

Before routing:

1. Read `<target>/.axon4-to-axon5-migration/learnings.md` if it exists.
2. Search sibling `references/examples/` only for the route under
   consideration. Do not load all examples up front.

After a non-obvious delegated or direct fix, append a concise dated entry to
`<target>/.axon4-to-axon5-migration/learnings.md` if that file already exists,
or create it only when the target project is already using the
`.axon4-to-axon5-migration/` directory. Record:

- root-cause cluster,
- delegated skill or direct-fix reason,
- files/classes affected,
- verification command and result,
- any generic lesson that should influence later clusters.

Do not record routine import cleanup unless it prevented a misleading cascade.

## Report format

When presenting a triage or handoff, use this shape:

```markdown
## Compile Command
`<command>` -> `<green|failed|blocked>`

## Queue
1. `<group id>` -> `<route>`
   Root cause: `<short hypothesis>`
   Evidence: `<files/classes + representative diagnostics>`
   Action: `<delegate to skill X on class/file Y>` or `<small direct fix>`
   Verify: `<command>`

## Current Action
Delegating `<group id>` to `<skill>` because `<reason>`.

## Blocked / Manual
- `<cluster>`: `<why no sibling skill/direct fix applies>`

## Learnings Consulted
- `<target learnings entries or sibling examples used>`
```

Keep the queue complete enough for the human to understand the plan, but fix
only the current highest-leverage group before rerunning compilation.

## Dry-check examples

- `cannot find symbol: variable AggregateLifecycle` or
  `AggregateLifecycle.apply(...)` in an aggregate: route to
  `axon4-to-axon5-eventsourcing-aggregate`.
- `package org.axonframework.test.aggregate does not exist` or
  `AggregateTestFixture`: route to `axon4-to-axon5-eventsourcing-aggregate`.
- `package org.axonframework.commandhandling.gateway does not exist` in a REST
  controller: route to `axon4-to-axon5-commandgateway`.
- `ResponseTypes` or AF4 `QueryGateway` dispatch in an input adapter: route to
  `axon4-to-axon5-querygateway`.
- `package org.axonframework.queryhandling does not exist` for
  `@QueryHandler`: route to `axon4-to-axon5-queryhandler`.
- `ConfigurerModule` or `EventProcessingConfigurer` no longer resolves in a
  configuration class: route to `axon4-to-axon5-writeconfiguration`.
- `JpaEventStorageEngine` or `EmbeddedEventStore` wiring fails: route to
  `axon4-to-axon5-aggregate-eventstorage-engine`.
- Maven compiles unrelated broken files under `-Pmigration`: route to
  `axon4-to-axon5-maven-migration-profile`.
