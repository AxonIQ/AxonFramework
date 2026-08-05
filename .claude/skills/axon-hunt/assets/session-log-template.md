# Session log: <slug> @ <UTC timestamp>

**System under test:** <name>
**Plan:** <path>
**Framework version:** <version>
**Branch and commit:** <branch> @ <sha>
**Session directory:** <absolute path>
**Operator:** <who>

## Toolbox discovered

What harness, checkers, faults, workloads, backends, scenarios, commands and documents are
already present. **Fill this before any scenario runs** -- it is what stops a session
re-inventing something that exists three files over.

- `<path>` -- <role>

## Environment capability matrix

One row per requirement the plan declared, or that the chosen techniques imply. **Ask what is
available before probing**, then reconcile with quick probes. Fill before any scenario runs.

| Requirement | Present | Version | Source or note |
|---|---|---|---|
| container runtime daemon | yes / no | <version> | <how detected> |
| the images the container tier needs | yes / no | pinned | pulled up front, so a pull failure reads as a pull failure |
| JDK | yes | <version> | -- |
| licence for the licensed arm | yes / no | -- | without it, S<n> is inconclusive-env |
| `timeout` | yes / no | -- | absent on macOS; its absence reads as every configuration doing nothing |

## Preconditions

- [ ] The build the suite drives is installed
- [ ] `simulation/target` removed, if any test count will be taken
- [ ] Containers up and healthy, for a container tier
- [ ] The fault plane responsive -- the proxy answering its own API
- [ ] A baseline captured, if anything will be compared

## Scenario timeline

The raw record. Append as the session runs; the findings report cites entries here. **Log a
starting line before any command expected to exceed about three minutes, and the result line with
elapsed time afterwards** -- that is what keeps a long run distinguishable from a wedged one.

| Time (UTC) | Scenario / arm | Event | Notes |
|---|---|---|---|
| | S1 | start | command, expected duration |
| | S1 | fault injected: <type> | the declared landing signal, captured |
| | S1 | healed, settling | |
| | S1 | oracle: <result> | fire count or operations consumed |
| | S1 | end | elapsed |

## Histories captured

**Copy every recorded history out of the build directory.** Under real threads a seed does not
reproduce the run that broke, so the history is the only exact record of it, and the next build
deletes `simulation/target`.

| Scenario / arm | Backend | Seed | History file under `artifacts/` | Kept because |
|---|---|---|---|---|
| S1 | postgres-jpa | 10412 | `S1-postgres-10412.jsonl` | the violation |
| S1 | in-memory | 10412 | `S1-inmemory-10412.jsonl` | the clean comparison |

## Scenario verdicts

Record **the budget tier actually met** next to each verdict, so the report can show the verdict
is defensible. A pass at the hardening tier requires the hardening budget to have been met, not
merely a clean oracle.

| Scenario / arm | Backend | Tier met | Verdict | Notes |
|---|---|---|---|---|
| S1 | in-memory | hardening | PASS-hardening | |
| S1 | postgres-jpa | hardening | FAIL-reproducible | blame pending -- re-run on the other adapter |
| S5/admin | -- | -- | NOT-RUN | no harness for that surface |

## Artefacts

- `logs/` -- per-scenario output
- `metrics/` -- snapshots
- `artifacts/` -- recorded histories, dumps, captures
- `findings/` -- per-scenario verdict files, written as the session goes, then the report

## Notes worth keeping

Anything that cost an hour. Append it here during the session, then move it to
`formal/HUNT-NOTES.md` at the end -- that file exists so the next agent does not pay again.
