# Knowledge routing -- load skill X when Y, take Z from it

**This file is dependent by construction:** it is the map to knowledge that lives outside this
repository. Every row therefore ends with **if it is absent**, because those skills are
harness-managed and may not exist where you are running, and a skill that dead-ends on a missing
plugin is worthless.

Check what is installed before planning around it:

```bash
ls ~/.claude-work/skills/ ~/.claude/skills/ 2>/dev/null
```

The distilled essentials of everything load-bearing are in this skill's own references. The rule
of thumb: **`method-essentials.md` and `dcb-semantics.md` are enough to do the work; the external
skills are for depth on a class of thing this suite has not built.**

---

## `axon-flow-tla-dst`

The setup this harness was modelled on: a formal-plus-simulation pairing over a different
event-sourced engine, packaged as a method skill.

- **When to load it.** Before designing a *new harness class* -- a seam, a wrapper, a fuzz or
  reproduce entry point, a regression-pinning mechanism. And when the expansion recipes here feel
  under-specified: its `references/expanding.md` has the step-by-step forms this repo's
  `recipes.md` is the adaptation of.
- **What to take.** The invariant-registry shape; the findings-document format; the
  fault-injectable store wrapper pattern; the anti-hang design (wall-clock deadline primary, step
  cap secondary, violation objects carrying the seed, the fault trace and a reproduce command);
  the surefire wiring for the sweep, the reproduce path and the tag-based exclusion; and the hard
  rules -- never patch the engine, never mask flakiness with reruns, judge by exit code, scope
  determinism honestly on both sides of an assertion, keep invariant wording identical across the
  registry, the specification and the assertion.
- **What NOT to expect.** It is a different engine with a different protocol. Its invariants are
  workflow-scoped and none of them transfers. It is also single-node and in-memory only, which is
  precisely what this suite's multi-node layer, container tier and backend differential exist to
  fix -- so do not copy its *coverage* shape, only its method.
- **If it is absent.** `design-commitments.md` section 4 lists what was taken and how it was
  adapted, and `recipes.md` is the adapted form. The reference repository itself is readable at
  `/Users/stefandragisic/Projects/axon-flow-spec` on branch `poc/tla_dst` -- read it with
  `git show poc/tla_dst:<path>` or a temporary worktree, never by checking the branch out over
  somebody's working tree.

---

## `designing-distributed-system-tests`

The plan was produced with it. Reopen it when **extending** the plan, not to re-read it.

Its `references/` directory, and which file for which question:

| File | Load it when | Take |
|---|---|---|
| `history-discipline.md` | Before touching the recorder or adding a record type | The field discipline and the ambiguous-outcome rules. **Distilled in `method-essentials.md` section 1**, including the correction this project had to make to it. |
| `common-distributed-systems-pitfalls.md` | Generating hypotheses for a subsystem with no coverage | The catalogue to walk. It is the largest of the references and the one with no substitute here. |
| `deterministic-simulation.md` | Designing a seeded arm or a new seam | What DST detects and misses, and the plumbing precondition. **Distilled in `method-essentials.md` section 5.** |
| `chaos-and-fault-injection.md` | Adding a fault class the suite has not built | Nemesis shapes and the evidence each produces. |
| `crash-recovery-and-upgrade.md` | A crash, restart or version-upgrade arm | The recovery-verification shape. Nothing here covers upgrade at all. |
| `formal-methods-tla.md` | Adding a model | How a plan uses a model, and how much to model. |
| `fuzzing.md`, `property-and-metamorphic.md`, `jepsen-and-elle.md`, `performance-and-benchmarking.md`, `boundary-and-isolation-testing.md` | Only if the question is genuinely one of those | Nothing here needs them today: no isolation-anomaly question has arisen, and the framework has no tenant boundary. |

- **What NOT to expect.** It is generic. It knows nothing about this framework's protocol, its
  seams, or which of its own advice this project already had to correct -- and it did have to be
  corrected in one place (see `design-commitments.md` section 2). It also produces *plans*, not
  scenarios: the five-field specification template in `claims-and-scenarios.md` is what turns its
  output into something implementable.
- **If it is absent.** `method-essentials.md` sections 1 and 5 carry the load-bearing parts. For
  the pitfall walk, the existing hypotheses are tabulated in
  `docs/testing-plans/axon-hunt.md` section 4 -- walk those and add what the new subsystem
  introduces.

---

## `executing-distributed-system-tests`

Load it when **running** scenarios and judging results.

- **What to take.** Three things, all non-negotiable and all already binding here: **landing
  evidence** (a fault with no proof it fired makes the run inconclusive, never a pass); the
  **green-but-broken audit** (run before declaring any pass); and its `oracle-patterns.md`
  **checker-picker table**, which is where this suite's checker designs came from -- consult it
  before writing a new checker rather than picking a shape by taste. Its
  `finding-classification.md` and `verdict-taxonomy.md` are the source of the three-valued verdict
  and the flake-classification discipline.
- **What NOT to expect.** Its verdict taxonomy is richer than this suite's three values, and it
  includes surface and fairness machinery for boundary claims that do not apply to a
  single-tenant framework library -- the plan says so explicitly rather than inventing them.
- **If it is absent.** `method-essentials.md` sections 2, 3 and 4 carry the checker picker, the
  landing-evidence rules and the audit checklist including the weak-oracle list.
  `hunting-loop.md` carries the verdict and flake classification in this suite's own vocabulary.

---

## `dcb-axoniq` and `dynamic-consistency-boundaries`

Conceptual depth on the consistency-boundary protocol.

- **When to load.** Before writing anything that decides whether an append conflicts, if you are
  not already fluent -- a checker, a reference-model rule, a formal operator. And when a
  *modelling* question arises: how to choose a boundary, why an aggregate is the wrong unit, what
  the criteria are meant to express.
- **What to take.** The real semantics rather than a folk version: conjunction of tags within a
  criterion, disjunction across criteria, marker lower and upper bounds, and how the server's own
  store sources and appends under criteria.
- **What NOT to expect.** They are about designing with the boundary, not about the engine's
  arithmetic. The positions, sentinels, off-by-ones and the two points at which a conflicting
  append can fail are not in them.
- **If it is absent.** `dcb-semantics.md` is self-sufficient for checking work, and the
  reference-model rule table in `formal/INVARIANTS.md` section 3.1 carries the engine
  `file:line` evidence for every rule.

---

## `axoniq-app-dev`

The API reference for everything the harness wires up. It has its own internal routing; use it
rather than reading the whole skill.

| Need | Its file |
|---|---|
| Building a workload: command handlers, decision models over a boundary | `commands/decision-models-dcb.md` |
| Exact append-condition, sourcing-condition and marker APIs | `event-store/primitives.md` |
| How the event store layers over a storage engine | `event-store/internals.md` |
| The streaming processor: tokens, segments, split and merge, replay, multi-node | `events/processors.md` |
| Plain-Java wiring for a harness, including a PostgreSQL event store | `configuration/plain-java.md` |
| Maven coordinates for the commercial boundary-native PostgreSQL engine | `getting-started/dependencies.md` |
| The test fixture | `testing/*.md` -- useful for a workload sanity check, **not** for the suite's oracles: a recording bus is not history discipline |

- **What NOT to expect.** It is about building applications. It will not tell you which seams
  exist for a *test* harness, and it will not warn you about the traps that only appear when you
  drive the framework from plain Java with no Spring -- a transactional executor provider that
  throws without a transaction manager attached to the context, a mutable configuration record
  whose setters return `this`, an ambiguous overload for a bare lambda. Those are in
  `formal/HUNT-NOTES.md` section 2, which is the place to look first.
- **If it is absent.** `formal/HUNT-NOTES.md` section 2 has verbatim compiling wiring for the
  harness paths, and the published Javadoc is the fallback for anything else.

---

## `axoniq-framework-5-expert`

- **When to load.** Once per session if you are writing code into this repository: it carries the
  contribution conventions -- module layout, Javadoc style, nullability annotations, test rules --
  and all suite code must pass this repo's checkstyle. Also when you need to understand a
  framework internal deeply enough to say whether a behaviour is a defect.
- **What to take.** The conventions, and the framework's own design principles, which are what
  make a candidate fix in a finding plausible rather than naive.
- **What NOT to expect.** It will not adjudicate whether something is a defect. That is what the
  claim corpus is for: a behaviour is a defect relative to a documented guarantee or a stated
  contract, and the claim record is the thing that says which.
- **If it is absent.** The repository's own `CLAUDE.md` and `.claude/rules/` carry the
  conventions, and `build/checkstyle.xml` is the enforcement.

---

## `graphify`

- **When to load.** Before grepping, whenever you are orienting in an unfamiliar framework
  subsystem. A prebuilt graph of the messaging, modelling and event-sourcing modules plus the
  design documents exists at `graphify-out/`.
- **What to take.** `query "<question>"` for breadth-first context that cites `file:line`;
  `path "<A>" "<B>"` for the wiring between two components; `explain "<Class>"` for one class's
  role. Then open the cited line: **the graph orients, the source decides.**
- **What NOT to expect.** It is not authoritative on behaviour and it can be stale. Never quote it
  as evidence in a finding; quote the `file:line` it pointed you at. Keep `graphify-out/` out of
  version control.
- **If it is absent.** Grep, and budget the time. Start from the class names in the invariant
  registry's checker column and from the `file:line` anchors in the claim corpus -- between them
  they name most of the framework surface the suite touches.

---

## `ctx7` (Context7 documentation CLI)

- **When to load.** Third-party library or tool documentation: Testcontainers, Toxiproxy, the
  TLA+ tool chain, a JDBC driver, Hibernate.
- **How.** `npx ctx7@latest library "<name>" "<question>"` to resolve an id, then
  `npx ctx7@latest docs <id> "<question>"`.
- **What NOT to expect, and this is a measured warning.** **Its quota was exhausted partway
  through this project's container work.** Plan for it being unavailable: it is a convenience, and
  every third-party interaction the suite depends on is already recorded as verified commands
  rather than as documentation references.
- **If it is absent.** The container primitives, the proxy's API calls and the entity-manager
  configuration that needs no XML file are all written out verbatim, with their verified output,
  in `formal/HUNT-NOTES.md` section 2 and in `running.md`. Prefer those: they were run against
  the actual versions this suite uses.

---

## Order of operations, when several apply

1. **Worktree check.** Always first.
2. **`formal/` and the plan.** In-repo truth beats any external skill, and most questions end
   here.
3. **This skill's references.** They are the distillation, and they are about *this* suite.
4. **The external skill for depth**, on a class of thing the suite has not built.
5. **`ctx7`** for a third-party detail, last, and expect it to be unavailable.

And the rule that overrides all of it: **do not load a skill you do not have a question for.**
Every one of them is long, and reading one speculatively is the most expensive way to avoid
reading `formal/HUNT-NOTES.md`, which probably already has the answer.
