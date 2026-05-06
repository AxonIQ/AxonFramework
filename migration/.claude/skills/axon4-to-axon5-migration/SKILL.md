---
name: axon4-to-axon5-migration
description: >-
  Orchestrate an end-to-end Axon Framework 4 → 5 migration of a target project
  by sequencing the atomic per-construct migration skills (OpenRewrite, event
  sourced aggregates, event processors, command gateway, query gateway, query
  handlers, read/write configuration, aggregate event-storage engine) in the
  correct order, with human confirmation checkpoints between phases and
  resumable progress tracking written to `.axon4-to-axon5-migration/progress.md`
  inside the target project.
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
> `axon4-to-axon5-aggregate-eventstorage-engine`,
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
| 9 | Aggregate `EventStorageEngine` wiring | `axon4-to-axon5-aggregate-eventstorage-engine` | One-shot bean swap (`JpaEventStorageEngine` → `AggregateBasedJpaEventStorageEngine`, or rely on autoconfig for `AggregateBasedAxonServerEventStorageEngine`); JPA path also emits a SQL migration script. |
| 10 | Stabilization | (manual + framework reference) | Whatever still doesn't compile or test green — fix using migration docs, AF5 examples, framework sources. |

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
├── plan.md           # optional — initial scope inventory (aggregates, handlers, configs found)
└── sql/              # phase 9 (JPA path) — emitted DDL/DML scripts the user runs against the database
```

- `progress.md` is **mandatory** — read it on every invocation, write it after every phase transition or in-phase iteration.
- `learnings.md` is **append-only**. When a sub-skill or a manual fix surfaces something non-obvious (a recipe choice, a custom converter, a Spring profile quirk), append a dated entry. This is the project's migration journal.
- `plan.md` is optional but recommended — generated once at the start of phase 2 from a scope sweep, then updated as items are checked off.
- `sql/` is created on demand by phase 9 when the project takes the JPA path. It holds the `domain_event_entry` → `aggregate_event_entry` rename script (or a Flyway/Liquibase changeset, depending on what the project uses). The orchestrator never runs the script itself — the user does, on a controlled environment.

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

Then read `progress.md` if it exists. **The mode of the run is decided by
this file:**

```bash
test -f <target>/.axon4-to-axon5-migration/progress.md
```

#### Existing progress → resume mode

If `progress.md` exists, you are in **resume mode**. This is the common
case — the user `/clear`-ed between phases (or even between items),
and your job is to honor the persisted state without re-deciding it.

Follow the [Resume protocol](#resume-protocol--what-a-fresh-session-does)
in the "Context-resumable design" section: read `progress.md`,
sanity-check the working tree, read `learnings.md` selectively, confirm
with the user, then jump straight to the action named in the
**▶︎ RESUME HERE** block.

Do NOT re-run step 0a (out-of-scope sweep) or step 0b (commit cadence)
on resume — both were answered on the first run and pinned in the
"Pinned user decisions" block of `progress.md`. If the pinned decisions
are missing (older `progress.md`), ask the user once and update the
pinned section in the same commit as your first action.

#### No progress file → fresh-start mode

If `progress.md` does not exist, this is a fresh run:

```bash
mkdir -p <target>/.axon4-to-axon5-migration
```

Initialize `progress.md` from `references/progress-template.md` —
substitute the target path, today's date, and the active branch.
Leave the `▶︎ RESUME HERE` block pointing at "Step 0a — out-of-scope
sweep" so a `/clear` immediately after init still resumes correctly.

Then run step 0a, step 0b, and proceed to phase 1 as usual.

#### The persistence invariant

Whatever mode you're in: every state change you make ends with
**`progress.md` rewritten + a commit that includes it**. Never mutate
the working tree without a corresponding `progress.md` update in the
same commit. The "Recent activity log" section is where the persistent
trail lives — append to it, don't replace.

#### 0a. Out-of-scope sweep — warn the user before any code changes

Before launching phase 1, scan the target project for AF4 features the
orchestrator **does not migrate**. The full list and detection commands
live in [`references/out-of-scope.md`](out-of-scope.md). The current set is:

- **Sagas** (`@Saga`, `@SagaEventHandler`, `SagaConfigurer`, …)
- **Aggregate snapshotting** (`Snapshotter`, `SnapshotTriggerDefinition`, …)
- **MongoDB extension** (`org.axonframework.extensions.mongo.*`, `axon-mongo` deps)
- **Kafka extension** (`org.axonframework.extensions.kafka.*`, `axon-kafka` deps)

Run each detection grep from `references/out-of-scope.md` against the
target. For every feature with one or more hits:

1. Print a clear warning naming the feature and the matched files /
   dependencies. Don't bury this in a paragraph — make it visible.
2. Tell the user there is **no clear migration path in Axon Framework 5
   yet** for these constructs, that the orchestrator will leave them
   untouched, and that compile / runtime failures from those files in
   phase 10 are expected.
3. Ask via `AskUserQuestion`:
   - `Continue — accept that <feature(s)> will not be migrated` *(Recommended when the user understands the trade-off)*
   - `Pause — let me remove or replace these first, then re-run the orchestrator`
4. Record the decision (and the matched files) in `learnings.md` under
   an `## Out-of-scope features detected` heading so future sessions
   inherit the context without re-prompting.

If no out-of-scope features are detected, log "Out-of-scope sweep clean"
to `learnings.md` and move on.

When iterating later phases, any detection grep hit that resolves to a
file matched in this sweep is **skipped** — the orchestrator does not
hand off-out-of-scope files to per-construct skills, even if their
detection greps match.

#### 0b. Commit cadence — set the user's expectation

This orchestrator commits frequently and intentionally — every
significant unit of progress is a separate commit. The cadence is
documented in the [Commit cadence](#commit-cadence) section below; tell
the user the rough shape now so they're not surprised when commits
appear:

- One commit per phase that runs to completion (phases 1, 9, 10).
- One commit per migrated item inside iterative phases (phases 2–8) —
  one aggregate, one event handler, one configuration class, etc.
- Always on the user's current branch, never on `main`. Never pushed.

If the user prefers a different cadence (e.g. one squashed commit per
phase, or no commits at all so they can stage manually), let them
override now via `AskUserQuestion` and record the choice in
`learnings.md`. Default is the per-item cadence above.

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
- **Commit the OpenRewrite diff.** This is a large, mechanical change set —
  isolating it in its own commit makes the rest of the migration history
  legible. See [Commit cadence](#commit-cadence) for the exact command and
  message shape. Skip if the user opted out of automatic commits in step 0b.

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
   `progress.md` ("Phase 2: migrated `<FQClass>` ✓"), **commit the
   per-item diff** (see [Commit cadence](#commit-cadence) — one commit
   per migrated item, on the current branch, no push), and continue to
   the next aggregate.
5. If red: stop the loop, record the failure context in `learnings.md`
   (failing test name, root cause, fix applied), and ask the user how to
   proceed:
   - `Fix manually now` — pause for the user to edit, then re-run verification.
   - `Skip this aggregate for now` — mark it `[blocked]` in plan.md and move
     on; revisit in phase 10 (stabilization).
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

### Step 9. Phase 9 — Aggregate `EventStorageEngine` wiring

Once the configuration writers are migrated (phase 8), there is exactly one
piece of framework-level configuration left to switch: the
`EventStorageEngine` bean. AF4 wired it explicitly (often inside
`EmbeddedEventStore` with a `JpaEventStorageEngine` /
`JdbcEventStorageEngine`); AF5 collapses that to a single
`EventStorageEngine` bean, picked from
`AggregateBasedJpaEventStorageEngine` (JPA path) or
`AggregateBasedAxonServerEventStorageEngine` (Axon Server path —
auto-registered by the Axoniq Spring Boot starter).

This phase is **one-shot**, not iterative. There's at most one storage
engine to wire per project, so there's no `plan.md` checklist for it.

Procedure:

1. Invoke `axon4-to-axon5-aggregate-eventstorage-engine`. The skill
   itself inspects the AF4 wiring (storage-engine beans + dependency
   manifest), recommends a path (A: JPA, B: Axon Server, C: non-Spring
   Configuration API), and asks the user to confirm via
   `AskUserQuestion`.
2. After it returns:
   - On the **JPA path**, a SQL migration script (or Flyway/Liquibase
     changeset) lands under `<target>/.axon4-to-axon5-migration/sql/`
     (or under the project's existing migration directory). The
     orchestrator does **not** run it — the user runs it on a backup or
     staging database first.
   - On the **Axon Server path**, the skill mostly *removes* AF4 beans
     so the autoconfig can win. No SQL.
   - On the **non-Spring path**, the skill emits a
     `componentRegistry(...)` registration on the existing
     `EventSourcingConfigurer`.
3. Verify the configuration class still compiles, scoped to the
   `migration` profile:
   ```bash
   ./mvnw -Pmigration test-compile -DfailIfNoTests=false
   ```
   This phase usually adds zero new tests — verification of the
   storage-engine wiring at runtime belongs to phase 10 (stabilization),
   after the SQL has been applied.
4. Append a learnings entry to
   `<target>/.axon4-to-axon5-migration/learnings.md`: which path was
   chosen, evidence behind the choice (dependencies + bean inventory),
   the SQL script location (Path A only), and any custom
   `Serializer`-to-`Converter` ports surfaced.
5. **Commit the storage-engine wiring change.** Include the SQL script
   in the same commit (Path A) — the script is part of the migration
   artifact and belongs with the bean swap in version control even
   though it must be applied to the database separately. See
   [Commit cadence](#commit-cadence).

Then the same human checkpoint:

```
Phase 9 complete. <path chosen>. <SQL script: <path> | none>.

Continue to phase 10 (stabilization)? [yes / iterate phase 9 / pause]
```

> **Don't tie the SQL run to the bean swap.** If the user is migrating a
> production system, recommend they ship the SQL on a quiet window,
> verify the renamed table is healthy under the existing AF4 deploy
> (which can still read it because column renames are backwards-readable
> with the right driver settings — but verify per-vendor), and only then
> ship the AF5 bean change. A single deploy that flips both is much
> harder to roll back.

### Step 10. Phase 10 — stabilization

By phase 10, the per-construct skills have done all the structured work
and the storage engine is wired. What's left is the long-tail: things
the recipes didn't reach, edge cases that didn't fit any single skill,
behavior changes that need code reasoning rather than mechanical
rewriting.

Procedure:

1. Confirm with the user that the SQL migration from phase 9 (if any)
   has been run against the database the build will hit. Without that,
   integration tests touching the event store will fail in confusing
   ways. If the user hasn't run it yet, offer to defer phase 10 until
   they have.
2. Drop the `migration` Maven profile scope — verify against the **full**
   build now:
   ```bash
   ./mvnw clean verify
   ```
3. Triage failures into three buckets:
   - **Compile errors** — usually a missed import or an API rename the recipes
     didn't catch. Fix using:
     - the migration docs at `docs/reference-guide/modules/migration/` in the
       AxonFramework repo,
     - working examples under `examples/` (especially
       `university-java-springboot-4` for Spring-on-AF5 patterns),
     - the framework sources directly when an API changed shape.
   - **Test failures with clear root cause** — fix in place; append the
     diagnosis to `learnings.md` so future migrations of similar projects
     benefit.
   - **Behavior changes** — anything the openrewrite skill flagged in step 1
     that the user has not yet addressed. Surface these explicitly; don't
     silently "fix" semantic differences.
4. Loop on `./mvnw clean verify` until green (or until the user accepts a
   known-deferred subset).
5. Mark phase 10 complete in `progress.md` with a final summary: total time,
   per-phase item counts, deferred items, behavior changes confirmed.
6. **Commit the stabilization fixes.** During the triage loop above,
   commit per logical fix as you go (e.g. one commit per failing test
   resolved, one per compile error class). Don't bundle disparate
   fixes — small commits keep the history bisectable if a regression
   surfaces post-migration. See [Commit cadence](#commit-cadence).

### Step 11. Wrap up

When all phases are complete:

- Show `progress.md` and `learnings.md` to the user as a final summary.
- Suggest (do not run) `git status` so the user can stage/commit on their own
  cadence.
- Remind the user that `.axon4-to-axon5-migration/` is theirs to keep
  (commit it for institutional memory) or delete (if they prefer a clean
  history). Don't delete it for them.

## Commit cadence

Migrations are easier to review, bisect, and roll back when each
significant unit of progress is its own commit. The orchestrator commits
**on the user's current branch** (never on `main`, never with `--force`)
and **never pushes**. The user controls the remote.

### When to commit

| Trigger | Commit message shape |
|---|---|
| Phase 1 finished (OpenRewrite) | `chore(af5-migration): apply OpenRewrite recipe <recipe-name>@<version>` |
| One aggregate migrated (phase 2) | `refactor(af5-migration): migrate aggregate <SimpleClassName> to AF5` |
| One event-handling component migrated (phase 3) | `refactor(af5-migration): migrate event handler <SimpleClassName> to AF5` |
| One command-gateway dispatcher migrated (phase 4) | `refactor(af5-migration): migrate command dispatch in <SimpleClassName> to AF5` |
| One query-gateway dispatcher migrated (phase 5) | `refactor(af5-migration): migrate query dispatch in <SimpleClassName> to AF5` |
| One query handler migrated (phase 6) | `refactor(af5-migration): migrate query handler <SimpleClassName> to AF5` |
| One configuration reader migrated (phase 7) | `refactor(af5-migration): migrate configuration reader <SimpleClassName> to AF5` |
| One configuration writer migrated (phase 8) | `refactor(af5-migration): migrate configuration <SimpleClassName> to AF5` |
| Storage-engine wired (phase 9) | `feat(af5-migration): wire AggregateBased{Jpa,AxonServer}EventStorageEngine` (include the SQL script in the same commit on Path A) |
| Stabilization fix (phase 10) | `fix(af5-migration): <one-line description of the fix>` — one commit per logical fix, not one big bundle |

Always include the matching `progress.md` / `learnings.md` / `plan.md`
update in the same commit as the code change it documents — the journal
and the work belong together.

### Always include the migration journal

`<target>/.axon4-to-axon5-migration/progress.md` (and `learnings.md`,
`plan.md`, `sql/*` when present) is part of the migration artifact.
Stage it alongside the code change in every commit so the history
self-documents.

### Commit command shape

Use a heredoc to keep the commit message clean and respect the user's
git identity:

```bash
git -C <target> add <changed-files> .axon4-to-axon5-migration/
git -C <target> commit -m "$(cat <<'EOF'
refactor(af5-migration): migrate aggregate Faculty to AF5

Phase 2 / 7 aggregates. Verified via:
  ./mvnw test -Pmigration -Dtest='org.example.FacultyTest' -DfailIfNoTests=false
EOF
)"
```

Never:
- `git add -A` / `git add .` — risk of staging unrelated WIP or secrets.
- `git commit --amend` — each migration step is its own historical
  record. If a step needs a follow-up fix, commit the fix as a new
  commit on top.
- `git push` — the user pushes when they're ready. The orchestrator
  does not.
- `--no-verify` — if a pre-commit hook fails, surface it to the user
  and let them decide. Skipping hooks silently masks problems the user
  installed them to catch.

### Opt-out and overrides

The user can disable automatic commits in step 0b. If they do:
- Stop running `git commit` from any phase.
- Still update `progress.md` / `learnings.md` after each unit of
  progress (the journal is independent of git).
- At each human checkpoint, suggest the user commit manually before
  advancing — but don't block on it.

### What if the working tree is dirty mid-phase?

If `git -C <target> status --porcelain` shows files that the
orchestrator did not touch (e.g. user-side WIP that crept in), pause
and ask via `AskUserQuestion`:

- `Stage and commit only the migration files I touched` *(Recommended)* — use `git add` with explicit paths.
- `Let me handle the working tree first` — pause, let the user clean
  up, then resume.
- `Skip this commit` — record the skip in `progress.md` and continue
  without committing.

Don't silently sweep the user's WIP into a migration commit.

## Context-resumable design — the core contract

A full AF4→AF5 migration of a real project will not fit in a single
context window. The orchestrator is **explicitly designed** so the user
can run `/clear` (or start a fresh session entirely) between phases —
or even between individual items inside a phase — and the next session
picks up exactly where the previous one stopped.

This works because of two invariants the orchestrator enforces on
every step:

1. **Every unit of progress ends with a commit that includes
   `progress.md`.** A unit of progress is: a per-item migration in
   phases 2–8, or the whole of phases 1, 9, and 10. After the commit,
   the working tree is clean and `progress.md` reflects the new state.
2. **`progress.md` is self-contained.** The "▶︎ RESUME HERE" block at
   the top of the file names the next phase, the next item, the next
   sub-skill to invoke, and the exact verification command. No prior
   conversation is required to know what comes next.

Because of (1) and (2), at any commit boundary the user may safely
`/clear` and start a fresh session. The new session reads
`progress.md`, executes one more unit of progress, commits, and the
cycle continues.

### Encourage `/clear` between units

After every commit on a non-trivial unit (the whole of phase 1, or
every 2–3 migrated aggregates / handlers, or anything that touched
many files), proactively suggest:

> "I've committed `<short-sha>`. Working tree is clean and
> `progress.md` is updated. This is a safe point to `/clear` the
> context if you'd like — when you come back, just invoke this skill
> again and I'll resume from `progress.md`."

Don't insist; the user decides. But surface the option, especially as
the conversation grows. Long-running migrations that never `/clear`
will eventually lose the focus and accuracy that short, targeted
sessions deliver.

### What the orchestrator writes to `progress.md`

After each unit of progress, **before committing**:

1. Update the **▶︎ RESUME HERE** block to point at the next unit:
   - Set `Current phase` and `Phase status`.
   - Set `Next action` to a one-sentence imperative.
   - Set `Exact sub-skill to invoke` to the FQ skill name plus the
     specific target (FQ class name for iterative phases).
   - Set `Exact verification command` to the copy/paste-ready command
     a fresh session will run after the next sub-skill returns.
   - Set `Last commit recorded by orchestrator` to the SHA you are
     about to write — get the SHA after the commit and amend the
     resume block in a follow-up touch-up commit only if needed
     (usually you already know the previous commit's SHA at the time
     you're writing the new resume block).
2. Update the matching row in the **Phase status** table
   (`Items done / total`, `Last commit`).
3. Update the matching item row in the per-phase plan table
   (status `done` + commit SHA).
4. Append a one-liner to **Recent activity log**.
5. If a non-obvious lesson surfaced, append a dated entry to
   `learnings.md` and link it from the per-phase section.

Then commit (per the [Commit cadence](#commit-cadence) section). The
working tree is now clean and the next session can resume.

### Resume protocol — what a fresh session does

The resume sequence runs **before** any other work, on every
invocation of this skill against an existing target:

1. **Read `progress.md` top-to-bottom.** The resume block tells you
   exactly what to do next; the per-phase tables tell you what's done.
2. **Sanity-check the working tree** against the recorded last commit:
   ```bash
   git -C <target> rev-parse --short HEAD
   git -C <target> status --porcelain
   ```
   If `HEAD` matches the recorded last commit and the tree is clean,
   the previous session left a tidy checkpoint — you may proceed.
   If `HEAD` is ahead (the user committed manually), surface it but
   trust their state.
   If the tree is **dirty**, the previous session crashed mid-step.
   Ask the user via `AskUserQuestion` whether to:
   - Inspect the dirty diff together and decide,
   - Reset to the recorded commit (only with explicit user OK — this
     is destructive),
   - Continue from the dirty state (treat the in-progress edits as
     yours to finish).
3. **Read `learnings.md` only if needed.** Skim the index; pull in
   only the entries relevant to the next action. The full file may
   not fit in context for a long-running migration — and it doesn't
   need to. Read on demand.
4. **Confirm with the user** in one or two sentences: "Resuming at
   phase X. Next item: Y. Continuing?" — using `AskUserQuestion` so
   the user has a chance to redirect.
5. **Trust `progress.md`.** If the user says it's wrong, fix it
   together first, commit the fix, and only then continue. Don't
   rewrite progress from scratch — it's the historical record of a
   multi-session effort.

### What does NOT need to survive `/clear`

These are deliberately *not* persisted to `progress.md`, because they
either don't matter at resume time or are derivable on demand:

- The full transcript of any previous sub-skill invocation.
- Verbose tool outputs (Maven logs, OpenRewrite recipe stdout).
- Reasoning chains the orchestrator went through to pick the next
  item — the picked item is enough.
- Every grep result. Discovery is re-runnable; the *enumerated plan*
  in the per-phase table is what matters.

If something here turns out to matter at resume time (e.g. a behavior
change the openrewrite skill flagged), promote it to `learnings.md`
or to the relevant phase section in `progress.md` — don't rely on
ambient context.

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
- `references/out-of-scope.md` — AF4 features the orchestrator does **not**
  migrate (sagas, snapshotting, Mongo extension, Kafka extension), with
  detection commands. Read at the start of every run, before phase 1.
- AxonFramework migration paths: `docs/reference-guide/modules/migration/pages/paths/`
  in the AxonFramework5 repo.
- AF5 examples: `examples/` in the AxonFramework5 repo.
