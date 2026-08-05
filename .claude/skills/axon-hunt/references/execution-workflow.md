# The execution workflow -- running a plan and reporting it

**This file is self-contained.** It is the ordered procedure for executing a plan: a campaign
from `plan-workflow.md`, or a tier of the existing corpus run as a deliberate session rather
than as a build. The output is a **session directory** and a **findings report**, and a reviewer
should be able to read those two artefacts and decide something without re-running anything.

The failure this procedure exists to avoid: **a green result that nobody checked the workload,
the fault and the oracle each did their job for.** The audit at step 6 is not optional.

For the tight triage loop against a single red arm, `hunting-loop.md` is shorter and is the right
file. Use this one when the session has more than one scenario, or when somebody else will read
the result.

---

## 1. Load the plan

Read it. If a plan was described in conversation rather than written, extract the scenario list
and **write it down** before running anything.

**If the plan is missing oracles or budget tiers, halt.** Hand back to the design procedure
rather than improvise. Improvising an oracle in the moment is how a green-but-broken result gets
produced -- the oracle ends up shaped to whatever the run happened to do.

## 2. Discover the toolbox

**Search before writing any new code.** In this repository:

- `simulation/src/main/java/.../hunt/` -- the harness: recorder, model, checkers, faults,
  workloads, backends.
- `simulation/src/test/java/.../hunt/scenario/` -- the existing scenario arms. One of them is
  usually within one field of what you need.
- `formal/` -- the registry, the findings, the canaries, the notes, the models.
- `references/running.md` -- every command, verified, with its traps.
- `.github/workflows/hunt.yml` -- what CI already runs and at which tier.

Record what you found. **Do this before any scenario runs**; it is what stops a session
re-inventing a checker that already exists three files over.

## 3. Probe the environment, and ask first

**Ask before probing.** List the capabilities the plan needs and ask what is available and what
to skip. Most operators already know whether they have a container runtime, a licence, a working
image cache. Asking up front saves a round, surfaces substitutions they know about, and respects
their authority over their own machine. **Then** verify with quick probes and reconcile any gap
between what was said and what is present.

What to probe here: a container runtime and its daemon; the images the container tier needs, so a
pull failure is reported as a pull failure and not as a fault that mysteriously never landed; the
JDK at the floor the module requires; any licence a licensed arm needs; and `timeout`, which does
not exist on macOS and whose absence reads as every configuration silently doing nothing.

Produce a row per requirement: requirement, present, version, source.

**For a missing capability, do not silently mark it inconclusive.** Two cases:

1. **Trivially installable.** Surface the command with a one-line explanation of what it enables,
   and offer to proceed once it is installed. Do not run privileged installs without explicit
   approval.
2. **Non-trivial** -- needs service setup, a licence, administrative access. Explain what is
   missing, which scenarios depend on it, and what would be gained. Then ask whether to wait,
   proceed and mark those scenarios inconclusive, or substitute a degraded approximation --
   **documented honestly as a substitution**.

Inconclusive is only the right verdict once the operator has been told and has either declined or
the capability is genuinely out of reach. "Tried and silently no-opped" stays forbidden.

## 4. Establish a session directory

```
<session root>/<slug>/<UTC timestamp>/
  session-log.md      the timeline, the toolbox, the capability matrix
  logs/               per-scenario output
  metrics/            snapshots
  artifacts/          recorded histories, dumps, captures
  findings/           per-scenario verdict files, then the report
```

Fill the header of `assets/session-log-template.md` into `session-log.md` now, not at the end.

**Recorded histories are the session's most valuable artefact.** Under real threads a seed does
not reproduce the run that broke, so the history is the only exact record of it. Copy them into
`artifacts/` -- `simulation/target/` is deleted by the next build, and a failure whose history is
gone is a failure nobody can act on.

## 5. Run scenarios in plan order

### Checkpoint discipline for anything long

Container bring-up, a cold build, a multi-node arm and a seed sweep all stay silent for minutes.
Watchdogs kill a task after five to ten minutes of silence.

- Before any command expected to exceed about three minutes, append a one-line "starting" entry
  to the session log: scenario, command, expected duration. Append the result with elapsed time.
- For anything past five minutes, run it in the background and pull status every sixty to a
  hundred and twenty seconds -- tail the output, check the container state -- rather than blocking
  on one foreground call.
- **Never let a single foreground command exceed the watchdog budget.** If a build genuinely needs
  that long, split it: warm the cache first, then time the scenario.
- **Write per-scenario findings as the session goes**, not only at the end. Partial findings beat
  a missing report when a scenario times out.

### Per scenario

1. **Preconditions.** Everything up cleanly, the fault plane responsive, a baseline captured.
2. **Start the workload** using the discovered driver.
3. **Inject the faults** per the schedule.
4. **Capture the landing evidence the plan declared** -- that signal, not a generic one. If it is
   absent or ambiguous the verdict is inconclusive-fault-not-proven, **never a pass**, and the
   oracle's result is irrelevant either way. For a scenario with no declared signal, take the
   best-effort one from the matching row of `fault-catalogue.md`.
5. **Heal, then settle, then stop.** A verdict taken during a fault manufactures false positives.
6. **Apply the oracles.** Every registered checker folds over the history whether or not the
   scenario asked for it, which is the point -- a checker nobody declared has caught findings
   here.
7. **Record the verdict with its execution evidence.** Use the labels and the decision tree in
   `verdicts-and-classification.md` section 1. Do not free-form a verdict. A pass needs both the
   oracle's execution evidence and the fault's landing evidence.

### Per arm, for a decomposed scenario

1. Run each arm as its own scenario, in plan order. Log entries carry the **arm** identifier.
2. Score each arm independently. An arm the session never reached is `NOT-RUN`.
3. Compute the aggregate by the **downgrade rule**: any `NOT-RUN` or partial arm caps the
   scenario at `PARTIAL-surface`, regardless of which arms passed.
4. Record **both** the per-arm verdicts and the aggregate. An aggregate alone folds an untested
   surface into a pass.

### The budget gate

A pass verdict requires the run to have **met the tier**, not merely produced a clean oracle. Met
only the smoke budget means at best a smoke pass. Record the tier actually met next to the
verdict, so the report can show the verdict is defensible.

## 6. Run the audit before declaring any pass

Both lists in `green-but-broken-audit.md`: the ten red flags, then the fourteen weak-oracle rows.

Record every row with its evidence, and **name the short ones**. A serious scenario with any
unchecked weak-oracle row is not eligible for a hardening pass -- downgrade it.

On this project the audit has found at least one overclaim in every phase. An audit with no short
rows on a first attempt has, historically here, meant the audit was not really performed.

## 7. On a failure, capture before moving on

Do not start the next scenario before recording:

- The **history file**, copied out of the build directory.
- The **reduced reproducer** -- `verdicts-and-classification.md` section 2.
- The **blame** -- engine, harness, checker or environment. Section 3. Required before filing
  anything. "Unknown, pending a re-run on X" is an acceptable interim value; a wrong guess is not.
- The **kind** -- section 4. Orthogonal to blame.
- The evidence: log excerpts, metric snapshots, the checker's own violation message.
- The hypothesised cause and the owning subsystem.
- The next action, in one sentence.

**Triage against the existing findings first.** A red arm is usually a known finding -- and
usually, on the first run of a new arm, the harness. Assume the harness until the tells say
otherwise.

## 8. Write the report

Fill `assets/findings-report-template.md`. Lead with the headline result. Cover **every**
hypothesis in the plan, including the ones not exercised -- those are gaps worth naming.

**Inconclusive is a first-class verdict, not a soft failure.** A scenario blocked by a missing
capability, a missing prerequisite or an environment limit gets the label with a one-line reason.
Never a silent pass; never a session-wide blocked unless literally nothing ran.

Four sections close the loop the plan opened, and without them the report says what passed but
not whether to ship:

- **Surface coverage.** Per arm: planned, executed, verdict, downgrade reason. Then the aggregate
  by the downgrade rule. Name every surface this run did not cover, with the reason. If the plan
  had no decomposed scenarios, render one line saying so -- **explicit emptiness is the signal**.
- **Release-budget disclosures.** Lift every "not provided" declaration verbatim, with its
  revisit condition. One line if there were none.
- **Adequacy against the plan.** A row per claim: what the plan argued, what actually ran, and the
  adequacy after this run. Any row where those differ means the residual uncertainty is larger
  than the plan declared -- **surface it** so a reviewer can accept the gap or ask for a re-run.
- **Confidence delta.** What should a reviewer believe **more**, **less**, or **unchanged** than
  the plan claimed. This is the part a stakeholder reads; the rest is the evidence trail.

### The session verdict

Set it by the **strongest evidence found**, not by counting.

| Session | When |
|---|---|
| **FAIL** | Any failure with a reproducer. Lead with the finding, even if most scenarios were inconclusive |
| **DONE** | No failure, at least one pass with cited evidence, and every other scenario also a clean pass |
| **DONE WITH CONCERNS** | As above, but any scenario was inconclusive, partial or unrun. **Treat partial and unrun as concerns, never as clean passes** |
| **INCONCLUSIVE** | No pass and no failure, but at least one scenario produced signal |
| **BLOCKED** | Nothing ran at all -- every scenario unrun, or the plan could not be loaded |

A high inconclusive fraction is not itself a problem if each one has an honest one-line reason.
It means the plan needs an environment the operator did not have.

**If a write is refused**, return the report as text and say so explicitly. Do not skip producing
the artefact, and do not report it as written.

## Autonomy -- what a session may and may not touch

- **Never patch the engine.** The whole reason a verdict from this suite means anything.
- **Never add a seam to framework code.** The only substitution is a wrapper around a real
  component.
- A canary is the one deliberate exception, and it is **measured and reverted** -- only the
  canary record persists.
- Scenario code, checkers, faults and workloads go under `simulation/`. Findings, registry rows,
  canary records and notes go under `formal/`. Nothing else moves.
- **Never commit on the operator's behalf.** Surface the diff.
