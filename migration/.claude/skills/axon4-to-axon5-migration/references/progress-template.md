# Axon Framework 4 → 5 Migration — Progress

> Source of truth for this project's migration. The orchestrator skill
> (`axon4-to-axon5-migration`) reads this file at the start of every run to
> figure out where to resume. Update it after every phase transition and
> after every individual item migrated inside a phase.

- **Target project:** `<absolute-path-to-target-project>`
- **Started:** `<YYYY-MM-DD>`
- **Last updated:** `<YYYY-MM-DD>`
- **Active branch:** `<branch-name-at-start>`
- **Out-of-scope features detected:** _none / list (see `learnings.md` for matched files)_
- **User decision on out-of-scope features:** _continue / paused / n/a_
- **Commit cadence:** _per-item (default) / per-phase (squashed) / no automatic commits_

## Phase status

Legend: `pending` · `in-progress` · `complete` · `paused` · `skipped`

| # | Phase | Status | Notes |
|---|---|---|---|
| 1 | OpenRewrite (`axon4-to-axon5-openrewrite`) | pending | |
| 2 | Event-sourced aggregates (`axon4-to-axon5-eventsourcing-aggregate`) | pending | |
| 3 | Event-handling components (`axon4-to-axon5-eventprocessor`) | pending | |
| 4 | Command dispatchers (`axon4-to-axon5-commandgateway`) | pending | |
| 5 | Query dispatchers (`axon4-to-axon5-querygateway`) | pending | |
| 6 | Query handlers (`axon4-to-axon5-queryhandler`) | pending | |
| 7 | Configuration readers (`axon4-to-axon5-readconfiguration`) | pending | |
| 8 | Configuration writers (`axon4-to-axon5-writeconfiguration`) | pending | |
| 9 | Aggregate `EventStorageEngine` wiring (`axon4-to-axon5-aggregate-eventstorage-engine`) | pending | |
| 10 | Stabilization (full `./mvnw clean verify`) | pending | |

## Phase 1 — OpenRewrite

- **Recipe(s) run:** _to fill in once executed_
- **Resolved artifact version:** _e.g. `5.1.0` from the Maven log_
- **License target chosen:** _free / Axoniq commercial_
- **Diff stat summary:** _N files changed, top 3 hot-spot modules_
- **Behavior changes flagged by the openrewrite skill:** _verbatim_

## Phase 2 — Event-sourced aggregates

Items discovered by the orchestrator's scope sweep at the start of phase 2.
Tick each item as the per-class skill finishes and verification passes.

- [ ] `<FQ.AggregateClass>` — `<status / blockers>`

## Phase 3 — Event-handling components

- [ ] `<FQ.EventHandlerClass>`

## Phase 4 — Command dispatchers (top-of-chain only)

- [ ] `<FQ.DispatchingClass>`

## Phase 5 — Query dispatchers (top-of-chain only)

- [ ] `<FQ.DispatchingClass>`

## Phase 6 — Query handlers

- [ ] `<FQ.QueryHandlerClass>`

## Phase 7 — Configuration readers

- [ ] `<FQ.ConfigReadingClass>`

## Phase 8 — Configuration writers

- [ ] `<FQ.ConfigClass>`

## Phase 9 — Aggregate `EventStorageEngine` wiring

- **Path chosen:** _A (JPA) / B (Axon Server) / C (non-Spring)_
- **Evidence:** _AF4 beans observed + dependencies (axon-spring-boot-starter / axon-server-connector / axoniq-spring-boot-starter)_
- **Configuration class touched:** _FQ class — bean(s) deleted/replaced_
- **SQL migration script:** _path under `.axon4-to-axon5-migration/sql/` or under the project's Flyway/Liquibase dir; "n/a" for paths B and C_
- **SQL run against production / staging?** _no — pending user_
- **Custom `Serializer` → `Converter` ports flagged:** _list_

## Phase 10 — Stabilization

- **`./mvnw clean verify` first run:** _PASS / FAIL — module(s) failing_
- **Outstanding compile errors:** _list with FQ class + one-line cause_
- **Outstanding test failures:** _list with test name + one-line cause_
- **Deferred items from earlier phases:** _list with rationale_

## Changelog

A reverse-chronological log of significant transitions. Append a one-liner
every time a phase changes status or an item is checked off.

- `<YYYY-MM-DD>` — initialized progress.md (orchestrator first run).
