---
name: axon4-to-axon5-migration
description: >-
  Orchestrate an end-to-end Axon Framework 4 → 5 migration of a target project
  by sequencing the atomic per-construct migration skills (OpenRewrite, event
  sourced aggregates, event processors, command gateway, query gateway, query
  handlers, read/write configuration) in the correct order, with human
  confirmation checkpoints between phases and resumable progress tracking
  written to `.axon4-to-axon5-migration/progress.md` inside the target project.
  Use whenever the user asks to "migrate a project to Axon Framework 5",
  "run the AF4→AF5 migration end to end", "drive the full migration",
  "resume the migration", or names a target repo to upgrade from Axon 4 to
  Axon 5 as a whole. Not for one-off, single-class rewrites — those go to the
  per-construct skills directly.
---

# AF4 → AF5: full-project migration orchestrator

Coordinates the AF4 → AF5 migration of a single target project by driving the
atomic per-construct skills in the correct phase order, pausing for human
confirmation between phases, and persisting progress so the migration can be
interrupted and resumed without losing context.

> **This skill does not itself rewrite code.** Every concrete code change is
> delegated to a sibling skill (`axon4-to-axon5-openrewrite`,
> `axon4-to-axon5-eventsourcing-aggregate`,
> `axon4-to-axon5-eventprocessor`,
> `axon4-to-axon5-commandgateway`,
> `axon4-to-axon5-querygateway`,
> `axon4-to-axon5-queryhandler`,
> `axon4-to-axon5-readconfiguration`,
> `axon4-to-axon5-writeconfiguration`,
> `axon4-to-axon5-maven-migration-profile`). The orchestrator's job is sequencing,
> scope discovery, verification, progress tracking, and human checkpoints.

> **Keep this skill generic.** It runs across any consumer project. All
> project-specific knowledge — surprises, recipe choices, manual fixes —
> lives inside the *target* project's `.axon4-to-axon5-migration/` directory
> (per-project working notes), never in this skill.

## Phase map

| # | Phase | Driver skill | What it covers |
|---|---|---|---|
| 1 | OpenRewrite bulk rewrite | `axon4-to-axon5-openrewrite` | Mechanical package/coordinate/BOM rewrites. Project is **expected to be non-compiling** afterwards. |
| 2 | Event-sourced aggregates | `axon4-to-axon5-eventsourcing-aggregate` | One aggregate per invocation — repeat until none remain. |
| 3 | Event-handling components | `axon4-to-axon5-eventprocessor` | One event handler / projector / saga-like reactor per invocation. |
| 4 | Command dispatch (top-of-chain) | `axon4-to-axon5-commandgateway` | Controllers, schedulers, CLI runners — one class per invocation. |
| 5 | Query dispatch (top-of-chain) | `axon4-to-axon5-querygateway` | Same shape as phase 4, query side. |
| 6 | Query handlers | `axon4-to-axon5-queryhandler` | One `@QueryHandler` class per invocation (mostly import-only). |
| 7 | Configuration readers | `axon4-to-axon5-readconfiguration` | Classes that *inject* `Configuration` to look up runtime components. |
| 8 | Configuration writers | `axon4-to-axon5-writeconfiguration` | `@Configuration` / `Configurer` / `ConfigurerModule` classes. |
| 9 | Stabilization | (manual + framework reference) | Whatever still doesn't compile or test green — fix using migration docs, AF5 examples, framework sources. |

The order is **not arbitrary**: aggregates and handlers come first because their
shapes reach into many other classes; configuration is last because it ties the
already-migrated components together.

## Working files in the target project

Every artifact this orchestrator persists lives **inside the target project**, in
a top-level directory called `.axon4-to-axon5-migration/`. This makes it
trivial for the user to inspect, commit (or `.gitignore`), and for a future run
of the orchestrator to pick up where it left off.

```
<target>/.axon4-to-axon5-migration/
├── progress.md       # source of truth — current phase, completed phases, in-flight items
├── learnings.md      # surprises, manual fixes, project-specific patterns observed during the run
└── plan.md           # optional — initial scope inventory (aggregates, handlers, configs found)
```

- `progress.md` is **mandatory** — read it on every invocation, write it after every phase transition or in-phase iteration.
- `learnings.md` is **append-only**. When a sub-skill or a manual fix surfaces something non-obvious (a recipe choice, a custom converter, a Spring profile quirk), append a dated entry. This is the project's migration journal.
- `plan.md` is optional but recommended — generated once at the start of phase 2 from a scope sweep, then updated as items are checked off.

Never write progress to the Axon Framework 5 repository or to this skill's
directory. Project-specific output goes into the target project's working tree
only.

## Procedure

### Step 0. Determine the target project and load progress

If the user didn't name a target path, ask via `AskUserQuestion` (offer the
most-recently-mentioned candidates plus "Other" so they can paste a path).
Validate the path exists, contains `pom.xml` / `build.gradle*`, and is a git
repository. Refuse if it points at the AxonFramework repo itself — same rule as
`axon4-to-axon5-openrewrite` step 1.

Then, *before doing anything else*:

```bash
test -f <target>/.axon4-to-axon5-migration/progress.md && cat <target>/.axon4-to-axon5-migration/progress.md
```

- If `progress.md` exists, **read it fully** and tell the user where the
  previous run left off ("Last completed phase: 2. In-flight: aggregates 3 of 7
  migrated."). Confirm whether to resume from that point or restart a phase.
- If it does not exist, this is a fresh run. Create the directory:
  ```bash
  mkdir -p <target>/.axon4-to-axon5-migration
  ```
  and initialize `progress.md` from the template in
  `references/progress-template.md` (substitute the target path, today's date,
  and mark phase 1 as `in-progress`).

Always update `progress.md` whenever the state changes — completing a phase,
finishing one item inside a phase, recording a blocker. Treat it as the single
source of truth a future session will rely on.

### Step 1. Phase 1 — OpenRewrite

Invoke the `axon4-to-axon5-openrewrite` skill. That skill itself asks the user
for license target (free vs Axoniq) and recipe scope, runs the recipe, and
reports the diff. Don't second-guess its prompts.

After it returns:
- Update `progress.md`: mark phase 1 complete, capture the recipe(s) that ran
  and the resolved artifact version (the openrewrite skill reports both).
- Append to `learnings.md`: any surprising hot-spot modules from the diff stat,
  or behavior-change warnings the openrewrite skill flagged.
- **Do not run `mvn compile` or the test suite** — the project is expected to
  be non-compiling at this point. That's by design and step 2 onwards is what
  fixes it.

Then **stop and ask the user** via `AskUserQuestion`:

- `Continue to phase 2 (aggregates)` *(Recommended)*
- `Re-run phase 1 with different recipe choices` — re-invoke the openrewrite skill
- `Pause migration here` — note "paused after phase 1" in progress.md and exit

Never proceed to phase 2 without an explicit user yes.

### Step 2. Phase 2 — Event-sourced aggregates

Phase 2–8 all share the same shape: discover items, set up scoped verification,
migrate one item at a time, verify, log, repeat. The pattern is described once
here and referenced by phases 3–8.

#### 2a. Discover the scope

Find every AF4 aggregate still in the project. Run inside the target:

```bash
grep -RlE '@Aggregate\b|org\.axonframework\.spring\.stereotype\.Aggregate' \
     --include='*.java' --include='*.kt' <target>/src 2>/dev/null
grep -RlE 'org\.axonframework\.eventsourcing\.EventSourcingHandler' \
     --include='*.java' --include='*.kt' <target>/src 2>/dev/null
```

Combine and deduplicate. Each unique class is one invocation of the
sub-skill. Record the list in `plan.md` under a `## Phase 2 — Aggregates`
heading with an unchecked checkbox per class.

If the list is empty, mark phase 2 complete in `progress.md` and skip to step 3.

#### 2b. Set up scoped verification

Before migrating, invoke `axon4-to-axon5-maven-migration-profile`. That skill
seeds (or extends) a `migration` Maven profile so per-test verification works
while the rest of the codebase is still mid-migration.

#### 2c. Migrate one aggregate, verify, repeat

For each unmigrated aggregate in `plan.md`:

1. Invoke `axon4-to-axon5-eventsourcing-aggregate` for that one class.
2. After it returns, re-invoke `axon4-to-axon5-maven-migration-profile` if new
   files were created or moved into different packages — the include list may
   need to grow.
3. Verify just that aggregate's tests:
   ```bash
   ./mvnw test -Pmigration -Dtest='<FQTestClass>' -DfailIfNoTests=false
   ```
4. If green: tick the checkbox in `plan.md`, append a one-liner to
   `progress.md` ("Phase 2: migrated `<FQClass>` ✓"), and continue to the next
   aggregate.
5. If red: stop the loop, record the failure context in `learnings.md`
   (failing test name, root cause, fix applied), and ask the user how to
   proceed:
   - `Fix manually now` — pause for the user to edit, then re-run verification.
   - `Skip this aggregate for now` — mark it `[blocked]` in plan.md and move
     on; revisit in phase 9.
   - `Stop the migration here` — exit cleanly; the next session resumes from
     the marked spot.

When every aggregate is checked off (or explicitly blocked-and-deferred), mark
phase 2 complete in `progress.md` and stop for the human checkpoint:

```
Phase 2 complete. <N> aggregates migrated, <M> deferred.

Continue to phase 3 (event processors)? [yes / iterate phase 2 / pause]
```

Use `AskUserQuestion`. Don't auto-advance.

### Steps 3–8. Phases 3–8 — same shape, different sub-skill

For each subsequent phase, repeat the discover → scope → migrate-one → verify
→ checkpoint pattern from step 2, swapping in the right detection grep and the
right driver skill:

| Phase | Driver skill | Detection grep |
|---|---|---|
| 3 | `axon4-to-axon5-eventprocessor` | `@ProcessingGroup` from `org.axonframework.config.ProcessingGroup`, or `@EventHandler` from `org.axonframework.eventhandling.EventHandler` |
| 4 | `axon4-to-axon5-commandgateway` | imports of `org.axonframework.commandhandling.gateway.CommandGateway` **outside** classes that already have `@EventHandler`/`@CommandHandler`/`@QueryHandler`/`@MessageHandlerInterceptor` (those are handler-resident dispatchers — out of scope for the gateway skill) |
| 5 | `axon4-to-axon5-querygateway` | imports of `org.axonframework.queryhandling.QueryGateway` outside message handlers |
| 6 | `axon4-to-axon5-queryhandler` | `@QueryHandler` from `org.axonframework.queryhandling.QueryHandler` |
| 7 | `axon4-to-axon5-readconfiguration` | injections of `org.axonframework.config.Configuration` or `org.axonframework.config.EventProcessingConfiguration` |
| 8 | `axon4-to-axon5-writeconfiguration` | `@Bean` methods returning `Configurer` / `ConfigurerModule` / `EventProcessingConfigurer`-lambdas, or direct use of `DefaultConfigurer.defaultConfiguration()` |

Detection greps are starting points — let the sub-skill make the final
in/out-of-scope call. Each sub-skill has its own scope rules in its SKILL.md.

After each phase, the same human checkpoint:

```
Phase <N> complete. <summary>.

Continue to phase <N+1>? [yes / iterate phase <N> / pause]
```

### Step 9. Phase 9 — stabilization

By phase 9, the per-construct skills have done all the structured work. What's
left is the long-tail: things the recipes didn't reach, edge cases that didn't
fit any single skill, behavior changes that need code reasoning rather than
mechanical rewriting.

Procedure:

1. Drop the `migration` Maven profile scope — verify against the **full**
   build now:
   ```bash
   ./mvnw clean verify
   ```
2. Triage failures into three buckets:
   - **Compile errors** — usually a missed import or an API rename the recipes
     didn't catch. Fix using:
     - the migration docs at `docs/reference-guide/modules/migration/` in the
       AxonFramework repo,
     - working examples under `examples/` (especially
       `university-java-springboot4` for Spring-on-AF5 patterns),
     - the framework sources directly when an API changed shape.
   - **Test failures with clear root cause** — fix in place; append the
     diagnosis to `learnings.md` so future migrations of similar projects
     benefit.
   - **Behavior changes** — anything the openrewrite skill flagged in step 1
     that the user has not yet addressed. Surface these explicitly; don't
     silently "fix" semantic differences.
3. Loop on `./mvnw clean verify` until green (or until the user accepts a
   known-deferred subset).
4. Mark phase 9 complete in `progress.md` with a final summary: total time,
   per-phase item counts, deferred items, behavior changes confirmed.

### Step 10. Wrap up

When all phases are complete:

- Show `progress.md` and `learnings.md` to the user as a final summary.
- Suggest (do not run) `git status` so the user can stage/commit on their own
  cadence.
- Remind the user that `.axon4-to-axon5-migration/` is theirs to keep
  (commit it for institutional memory) or delete (if they prefer a clean
  history). Don't delete it for them.

## Resuming an interrupted run

A new conversation that invokes this skill against a target with an existing
`.axon4-to-axon5-migration/progress.md` is the common case, not the exception.
The resume protocol:

1. Read `progress.md` end-to-end — every phase entry, every per-item line.
2. Read `learnings.md` if it exists — past surprises shape current decisions.
3. Tell the user, in two or three sentences: "You're at phase X. Last item
   completed: Y. <N> items remain in this phase. Resume from there?" Use
   `AskUserQuestion`.
4. Trust `progress.md`. If the user says it's wrong, ask them what to update —
   don't rewrite it from scratch.

## Subagents

If the harness supports subagents (`Agent` tool with `subagent_type`), use them
selectively for **discovery** sweeps that don't change files — e.g. the
phase-2-style `grep` to inventory aggregates can run in a subagent, returning
the list without polluting the orchestrator's context.

Do **not** spawn a subagent to *invoke a sub-skill that mutates code*. The
sub-skills themselves prompt the user, run builds, and need to remain in the
main conversation so the human-in-the-loop checkpoints still work. A subagent
running `axon4-to-axon5-eventsourcing-aggregate` would surface its
`AskUserQuestion` calls as opaque "agent reports", losing the interactivity
that makes the skills usable.

Rule of thumb: subagents for **read-only discovery** (scope sweeps, framework
documentation lookups). Main conversation for **everything that writes files
or asks the user a question.**

## Common pitfalls

- **Skipping the human checkpoint between phases.** Each phase produces a
  diff the user wants to review. Auto-advancing strips that opportunity.
- **Running `mvn verify` after phase 1.** It will fail — that's the design.
  Wait for phase 9.
- **Letting `progress.md` drift.** If you finish migrating an aggregate and
  forget to tick it off, the next session will redo it. Update after every
  item, not at the end of the phase.
- **Re-running OpenRewrite to "fix" something a per-construct skill couldn't
  handle.** OpenRewrite is one-shot mechanical. Stabilization (phase 9) is
  where judgment-driven leftovers get fixed.
- **Treating a Gradle target like a Maven target.** Phase 1's openrewrite
  skill bails on Gradle today and the verification approach in phases 2–8
  assumes Maven (`./mvnw test -Pmigration ...`). For Gradle projects, ask the
  user upfront whether they're OK with a fully-manual verify loop, or stop.

## Reference docs

- `references/progress-template.md` — the initial shape of `progress.md`.
- `references/phase-checklist.md` — quick checklist per phase (helpful when
  jumping in mid-migration).
- AxonFramework migration paths: `docs/reference-guide/modules/migration/pages/paths/`
  in the AxonFramework5 repo.
- AF5 examples: `examples/` in the AxonFramework5 repo.
