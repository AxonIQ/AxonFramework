# Axon Framework 4 → 5 Migration — Progress

> **Single source of truth for this project's migration.** Designed so a
> session with **zero prior context** can read this file alone and
> resume exactly where the previous session stopped. Every phase
> transition, every per-item migration, and every user decision lands
> here before the orchestrator yields control. After a checkpoint
> commit, the next session may safely `/clear` — the resume protocol
> picks up from this file.
>
> **Update protocol:** rewrite the relevant section, then commit.
> Never split "did the work" and "wrote progress.md" across commits.

---

## ▶︎ RESUME HERE — read this first

The single block a fresh session needs to make the next move. Keep it
**current** and **concrete** (FQ class names, exact commands — not
paraphrases). When you finish a unit of work, rewrite this block to
point at the next unit *before* committing.

- **Current phase:** _e.g. `Phase 2 — Event-sourced aggregates` / `between phases — awaiting user checkpoint`_
- **Phase status:** _pending / in-progress / awaiting-human-checkpoint / paused / complete_
- **Next action (one sentence):** _e.g. "Invoke `axon4-to-axon5-eventsourcing-aggregate` against `org.example.faculty.Faculty`, then verify the next unmigrated item in the Phase 2 plan."_
- **Exact sub-skill to invoke:** _e.g. `axon4-to-axon5-eventsourcing-aggregate` with target class `org.example.faculty.Faculty`_
- **Exact verification command:**
  ```bash
  ./mvnw -f <target>/pom.xml test -Pmigration -Dtest='org.example.faculty.FacultyTest' -DfailIfNoTests=false
  ```
- **Awaiting user input?** _yes (and the question) / no_
- **Working-tree expectation at resume time:** _e.g. "clean — last commit `abc1234` is the previous item." If dirty, the previous session crashed mid-step; investigate before continuing._
- **Last commit recorded by orchestrator:** `<short-sha>` — `<commit subject>`

---

## Project metadata

- **Target project:** `<absolute-path-to-target-project>`
- **Started:** `<YYYY-MM-DD>`
- **Last updated:** `<YYYY-MM-DD HH:MM>`
- **Active branch:** `<branch-name-at-start>`

---

## Pinned user decisions

These are **frozen** for the run. A fresh session must respect them
without re-asking the user.

- **License target (phase 1):** _free Axon Framework 5 / Axoniq commercial — set after phase 1 user prompt_
- **Recipe scope (phase 1):** _top-level / per-module subset (list)_
- **Out-of-scope features detected (step 0a):** _none / list (full match list in `learnings.md`)_
- **User decision on out-of-scope:** _continue (accept they won't be migrated) / paused — n/a if none detected_
- **Commit cadence (step 0b):** _per-item (default) / per-phase squashed / no automatic commits_
- **Storage-engine path (phase 9):** _A (JPA) / B (Axon Server autoconfig) / C (non-Spring) — set after phase 9 user prompt_
- **Build tool:** _Maven / Gradle (Maven only is fully automated; Gradle is manual)_

---

## Phase status

Legend: `pending` · `in-progress` · `awaiting-checkpoint` · `complete` · `paused` · `skipped`

| # | Phase | Status | Items done / total | Last commit |
|---|---|---|---|---|
| 1 | OpenRewrite (`axon4-to-axon5-openrewrite`) | pending | — | — |
| 2 | Event-sourced aggregates (`axon4-to-axon5-eventsourcing-aggregate`) | pending | 0 / ? | — |
| 3 | Event-handling components (`axon4-to-axon5-eventprocessor`) | pending | 0 / ? | — |
| 4 | Command dispatchers (`axon4-to-axon5-commandgateway`) | pending | 0 / ? | — |
| 5 | Query dispatchers (`axon4-to-axon5-querygateway`) | pending | 0 / ? | — |
| 6 | Query handlers (`axon4-to-axon5-queryhandler`) | pending | 0 / ? | — |
| 7 | Configuration readers (`axon4-to-axon5-readconfiguration`) | pending | 0 / ? | — |
| 8 | Configuration writers (`axon4-to-axon5-writeconfiguration`) | pending | 0 / ? | — |
| 9 | Aggregate `EventStorageEngine` wiring (`axon4-to-axon5-aggregate-eventstorage-engine`) | pending | — | — |
| 10 | Stabilization (full `./mvnw clean verify`) | pending | — | — |

> When a phase enters `in-progress`, fill out its detailed section
> below with the **complete enumerated plan** (every FQ class name).
> Don't rely on a fresh session re-running the discovery grep — the
> grep result might shift mid-migration as files move and the next
> session would re-discover already-migrated items.

---

## Phase 1 — OpenRewrite

- **Recipe(s) run:** _e.g. `org.axonframework.migration.UpgradeAxon4ToAxoniq5`_
- **Resolved artifact version:** _e.g. `5.1.0` (from the Maven log)_
- **Diff stat summary:** _N files changed, top 3 hot-spot modules_
- **Behavior changes flagged by the openrewrite skill:** _verbatim, so the next session sees them without re-reading the openrewrite output_
- **Commit:** `<short-sha>` — `chore(af5-migration): apply OpenRewrite recipe …`

---

## Phase 2 — Event-sourced aggregates

> Enumerate every aggregate at the start of the phase. Don't add new
> items mid-phase — if the discovery grep would return more entries on
> a re-run, that's a signal an earlier phase moved code; surface it as
> a blocker.

| # | FQ aggregate class | FQ test class | Status | Commit |
|---|---|---|---|---|
| 1 | `org.example.faculty.Faculty` | `org.example.faculty.FacultyTest` | pending | — |
| 2 | … | … | pending | — |

**Status legend per item:** `pending` · `in-progress` · `done` · `blocked: <reason>` · `deferred-to-phase-10`

**Verification command template** (copy/paste-able):
```bash
./mvnw -f <target>/pom.xml test -Pmigration -Dtest='<FQTestClass>' -DfailIfNoTests=false
```

**Phase-2-specific learnings:** _link to anchor in `learnings.md` if non-trivial_

---

## Phase 3 — Event-handling components

| # | FQ class | FQ test class | Status | Commit |
|---|---|---|---|---|
| 1 | … | … | pending | — |

---

## Phase 4 — Command dispatchers (top-of-chain only)

> Filter: classes that import `org.axonframework.commandhandling.gateway.CommandGateway`
> **and** are NOT message handlers (`@EventHandler` / `@CommandHandler` /
> `@QueryHandler` / `@MessageHandlerInterceptor`). The filtered-out
> classes belong to phase 3.

| # | FQ class | FQ test class | Status | Commit |
|---|---|---|---|---|
| 1 | … | … | pending | — |

---

## Phase 5 — Query dispatchers (top-of-chain only)

| # | FQ class | FQ test class | Status | Commit |
|---|---|---|---|---|
| 1 | … | … | pending | — |

---

## Phase 6 — Query handlers

| # | FQ class | FQ test class | Status | Commit |
|---|---|---|---|---|
| 1 | … | … | pending | — |

---

## Phase 7 — Configuration readers

| # | FQ class | FQ test class | Status | Commit |
|---|---|---|---|---|
| 1 | … | … | pending | — |

---

## Phase 8 — Configuration writers

| # | FQ class | FQ test class | Status | Commit |
|---|---|---|---|---|
| 1 | … | … | pending | — |

---

## Phase 9 — Aggregate `EventStorageEngine` wiring

- **Path chosen:** _A (JPA) / B (Axon Server) / C (non-Spring)_
- **Evidence:** _AF4 beans observed + dependencies_
- **Configuration class touched:** _FQ class — bean(s) deleted/replaced_
- **SQL migration script:** _path under `.axon4-to-axon5-migration/sql/` or under Flyway/Liquibase dir; "n/a" for paths B and C_
- **SQL run against the build's database?** _no (pending user) / yes — date_
- **Custom `Serializer` → `Converter` ports flagged:** _list_
- **Commit:** `<short-sha>` — `feat(af5-migration): wire AggregateBased…EventStorageEngine`

---

## Phase 10 — Stabilization

- **Pre-flight (phase-9 SQL applied if applicable):** _yes / no — n/a_
- **`./mvnw clean verify` first run:** _PASS / FAIL — module(s) failing_
- **Outstanding compile errors:**
  - `<FQ class>` — `<one-line cause>` — status: `pending` / `fix in commit <sha>`
- **Outstanding test failures:**
  - `<FQ test method>` — `<one-line cause>` — status: `pending` / `fix in commit <sha>`
- **Deferred items from earlier phases (revisit here):** _list with rationale, copy from per-phase tables_
- **Behavior changes confirmed by user:** _list_

---

## Recent activity log

A reverse-chronological log of significant transitions. The orchestrator
appends one entry every time it commits or yields to a human checkpoint.
Keep entries terse — this section is for "did this happen?" lookups, not
for prose. The detailed per-phase tables above are the working state.

- `<YYYY-MM-DD HH:MM>` — `<short-sha>` — `<one-line summary>`
- `<YYYY-MM-DD HH:MM>` — initialized progress.md (orchestrator first run).
