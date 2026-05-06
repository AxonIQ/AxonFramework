# Phase checklist — quick reference

A condensed cheat sheet for jumping into any phase. Use this when resuming a
mid-migration and you want a one-page reminder of the loop shape.

## Universal per-phase loop (phases 2–8)

```
discover scope ─▶ scope verification (migration profile) ─▶
  ┌─────────────────────────────────────────────────┐
  │ pick next unchecked item from plan.md           │
  │ invoke per-construct skill on that one item     │
  │ run scoped tests: mvn test -Pmigration -Dtest=… │
  │ green? tick it. red? log to learnings.md, ask   │
  │ user (fix / skip / stop)                        │
  └─────────────────────────────────────────────────┘
                         loop
no items left ─▶ update progress.md ─▶ AskUserQuestion checkpoint
```

## Per-phase quick refs

### Phase 1 — OpenRewrite

- Driver: `axon4-to-axon5-openrewrite`
- Verification: **none** — project is expected to be non-compiling.
- Checkpoint: `[continue / iterate / pause]`

### Phase 2 — Aggregates

- Driver: `axon4-to-axon5-eventsourcing-aggregate`
- Discover: `grep -RlE '@Aggregate\b' --include='*.java' <target>/src`
- Verify: `./mvnw test -Pmigration -Dtest='<FQ>Test' -DfailIfNoTests=false`
- Per-item gotcha: aggregates have a paired test; verify both compile/test.

### Phase 3 — Event processors

- Driver: `axon4-to-axon5-eventprocessor`
- Discover: `grep -RlE '@ProcessingGroup|@EventHandler' --include='*.java' <target>/src`
- Verify: same scoped command, target the projector's test class.
- Per-item gotcha: class-level `CommandGateway` field becomes a method-param `CommandDispatcher`; constructor changes propagate to test setup.

### Phase 4 — Command gateway dispatchers

- Driver: `axon4-to-axon5-commandgateway`
- Discover: `grep -RlE 'org\.axonframework\.commandhandling\.gateway\.CommandGateway' --include='*.java' <target>/src`
- Filter out: classes with `@EventHandler` / `@CommandHandler` / `@QueryHandler` / `@MessageHandlerInterceptor` (handler-resident dispatch — out of scope, lives in phase 3).
- Verify: target the controller/scheduler's test (often integration test).

### Phase 5 — Query gateway dispatchers

- Driver: `axon4-to-axon5-querygateway`
- Discover: `grep -RlE 'org\.axonframework\.queryhandling\.QueryGateway' --include='*.java' <target>/src`
- Filter out: same handler-resident exclusion as phase 4.
- Per-item gotcha: `ResponseType` wrapper is gone; multi-result queries → `queryMany(...)`.

### Phase 6 — Query handlers

- Driver: `axon4-to-axon5-queryhandler`
- Discover: `grep -RlE 'org\.axonframework\.queryhandling\.QueryHandler' --include='*.java' <target>/src`
- Per-item gotcha: usually import-only, but verify because subscription-query types may have moved too.

### Phase 7 — Configuration readers

- Driver: `axon4-to-axon5-readconfiguration`
- Discover: `grep -RlE 'org\.axonframework\.config\.(Configuration|EventProcessingConfiguration)' --include='*.java' <target>/src`
- Per-item gotcha: lookup methods become async (`CompletableFuture<Void>` for `start`/`shutdown`/`resetTokens`).

### Phase 8 — Configuration writers

- Driver: `axon4-to-axon5-writeconfiguration`
- Discover: classes with `@Bean` returning `Configurer` / `ConfigurerModule`, or direct `DefaultConfigurer.defaultConfiguration()`.
- Per-item gotcha: `ConfigurerModule` becomes `ConfigurationEnhancer`; lifecycle hooks move from `Configurer.onStart/onShutdown` to `lifecycleRegistry`.

### Phase 9 — Aggregate `EventStorageEngine` wiring

- Driver: `axon4-to-axon5-aggregate-eventstorage-engine`
- One-shot phase (not a per-class loop). At most one storage engine per
  project.
- Inspection: `grep -RnE 'JpaEventStorageEngine|JdbcEventStorageEngine|EmbeddedEventStore|AxonServerEventStore' --include='*.java' src` plus `grep -nE 'axon-server-connector|axoniq-spring-boot-starter|axon-spring-boot-starter' pom.xml */pom.xml`.
- Three paths the sub-skill picks between: A (JPA — code change + SQL),
  B (Axon Server — delete AF4 bean, autoconfig wins, no SQL), C (non-Spring
  — register on `EventSourcingConfigurer.componentRegistry`).
- **Per-item gotcha — JPA path only:** The sub-skill emits a SQL script
  (`domain_event_entry` → `aggregate_event_entry` rename plus column
  renames + sequence + nullability). The orchestrator does not run it —
  the user does, on a controlled environment. Do not advance to phase 10
  until they confirm the SQL has run against the build's database.
- Verify: `./mvnw -Pmigration test-compile -DfailIfNoTests=false` —
  runtime verification of the storage engine belongs to phase 10.

### Phase 10 — Stabilization

- No driver skill — judgment-driven manual fixes.
- **Pre-flight:** confirm phase-9 SQL has been applied to the database
  the build will hit. Otherwise integration tests against the event
  store fail in misleading ways.
- Drop the `migration` profile scope; run `./mvnw clean verify`.
- Triage: compile errors, test failures, behavior changes (recipe-flagged).
- Reference material:
  - `docs/reference-guide/modules/migration/pages/paths/` (in AxonFramework5 repo)
  - `examples/university-java-springboot-4/` (Spring-on-AF5 reference)
  - `axon-5/api-changes/10-stored-format-changes.md` (schema column reference)
  - framework sources for changed APIs

## Out-of-scope features

Before phase 1 — and on every resume — sweep the target for AF4
features the orchestrator does NOT migrate. Full list and detection
commands: [`out-of-scope.md`](out-of-scope.md). Current set:
**Sagas**, **aggregate snapshotting**, **MongoDB extension**, **Kafka
extension**. If any are detected, warn the user and ask whether to
continue or pause. Record the decision in `learnings.md`.

## Commit cadence (default)

- Phase 1: one commit for the OpenRewrite diff.
- Phases 2–8: one commit per migrated item (one aggregate, one handler,
  one configuration class, etc.).
- Phase 9: one commit for the storage-engine wiring (include the SQL
  script in the same commit on Path A).
- Phase 10: one commit per logical fix (don't bundle compile fixes,
  test fixes, and behavior-change fixes together).
- Always include the matching `progress.md` / `learnings.md` /
  `plan.md` / `sql/*` updates in the same commit. Stage explicit paths,
  never `git add -A`. Never push, never amend, never `--no-verify`.

User can opt out at step 0b — record the choice in
`progress.md` and `learnings.md`.

## Anti-patterns (don't do these)

- Auto-advancing past a phase checkpoint without an explicit user yes.
- Running `mvn compile` / `mvn verify` between phases 1–8 against the full
  codebase. Use the `migration` profile to scope.
- Re-invoking OpenRewrite to "fix" something a per-construct skill couldn't
  handle — OpenRewrite is one-shot mechanical.
- Spawning a subagent to *invoke a code-mutating per-construct skill*. The
  user's `AskUserQuestion` prompts inside those skills must reach the main
  conversation.
- Letting `progress.md` drift behind reality. Update on every transition.
