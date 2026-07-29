# The binding design commitments, the charter, and the lineage

The harness was constrained up front by a numbered set of commitments in
`docs/testing-plans/axon-hunt.md` section 3b. That section is the authority on what each one
says. This file carries the thing the list cannot: **which of them proved load-bearing in
practice, and which had to be corrected.** That judgement is worth more than the list, because
a commitment that survived contact is a rule to keep and one that did not is a trap to avoid
re-setting.

---

## 1. Load-bearing, confirmed by measurement

### The executable reference model as the primary oracle

A pure, dependency-free model of the append protocol, replayed against every history, with each
decision attributed to a **named rule**. Property checkers only catch what somebody thought of;
the model catches drift nobody enumerated, and a divergence points at one rule rather than at
"the store".

*Evidence it was worth it:* the bluntest planted defect turned three independent assets red --
the sequential differential, the pinned single-writer seeds and the contended smoke arms -- and
the subtle version of the same defect (an off-by-one in the scan range) was caught at a fiftieth
of the volume, which is the version a hand-written test is least likely to have.

*The constraint it imposes, and keep it:* the model is **sequential**, so it can be compared
against a formal specification of the same protocol. Do not add concurrency to it. Properties
outside its reach are checked against the real engine.

### The backend differential as the attribution strategy

The system under test is the library crossed with a store protocol, so the same scenario object,
workload supplier, checker set and timings run once per store, and every result carries a
verdict vector.

*Evidence:* it ended every whose-defect argument, and it produced the one column that decides
attribution -- the store that arbitrates claims **and** speaks the boundary protocol is the only
clean column on one arm, which is what makes the other columns' `n/a` entries readable rather
than mysterious.

*The corollary that had to be enforced:* a differential must give every arm the same timings and
the same budget, or the divergence stops being attributable to the store. Setters exist on the
scenario record for exactly that.

### A conservation-law workload as the cheap global oracle

One default workload -- accounts, conditioned transfers, a balance projection -- and one
inequality: the balances sum to the opening total.

*Evidence, twice, in two different mutation campaigns:* a store that silently dropped the first
event of a multi-event batch was caught **by the arithmetic**, with nobody having written an
assertion about batch completeness; and a claim-algebra defect that let four nodes process the
same segment was caught independently by the same sum, with nobody having written an assertion
about double processing.

*What it costs:* the conservation shape has to match the arm. A workload whose commands can fail
while their events are durably stored must not be held to it. An arm that deliberately hands a
claim over needs an idempotent projection, which is a **weaker** oracle -- and the registry says
so rather than implying a sharpness it does not have.

### Planted-bug validation

Deliberate framework mutations, applied one at a time, run against the whole suite, recorded,
and reverted. An oracle that never caught a planted bug is decoration.

*Evidence:* two mutations escaped. Both were written up as gaps rather than quietly dropped,
both named exactly what would catch them, and both were later caught after those things were
built -- and one of them, on re-run, revealed that the mechanism everybody expected to catch it
could not, while a completely different quantity could. That is a result no amount of reasoning
would have produced.

### Zero quarantine

No disabling, no tag used to hide a failure, no rerun count, no silent skip. Every intermittent
failure classified as engine bug, harness bug or load artifact.

*Evidence:* the surrounding project's own continuous-integration configuration reruns failing
tests five times, which is documented in the plan as a risk rather than copied as a pattern. The
hunt module pins the count to zero in its own build file, and every hunt job **asserts** that
the pin is still there -- because a pin nobody checks is a pin that silently disappears.

---

## 2. Needed correcting in practice

### The operation-history commitment contradicted itself

As originally written, a history record carried an invocation timestamp and a completion
timestamp in **one** record. That cannot represent an operation that never completed -- which is
the single case the whole discipline exists for. The workable form, and the one the suite uses,
is **separate invocation and completion records correlated by identifier**, with a third record
type for an outcome that is genuinely unknown. Everything downstream depends on it: unknowns,
open trailing operations, un-collapsed retries, and the visibility oracle's ability to compare a
delivery against a commit's *invocation* rather than its completion.

If you are copying the commitment, copy the corrected form.

### Time compression is harness-wide, except where it is not

The commitment says every timeout-dependent scenario runs with framework timeouts compressed.
Three edges:

- **Some durations do not compress at all.** A coordinator's idle re-poll and a post-split
  re-claim block are hardcoded, and both are long enough to dominate an arm's budget. Any
  horizon or stall window has to be derived above them, and the derivation must be written down
  with its basis.
- **A timeout compressed below what the store can answer in produces a failure that does not
  mention timeouts.** Measured on a single-node run against a real store: dozens of claims and
  releases, over a thousand repeated deliveries, a hundred-plus token regressions and a
  conservation violation. Widening the claim timings removed every one of them and left the one
  real finding.
- **The compressed ratio matters more than the absolute values.** The arms preserve a fixed
  ratio between the claim timeout and the extension threshold, because the protocol's behaviour
  turns on the ratio, and the formal model uses the same ratio in ticks for the same reason.

### The pause nemesis exists and no scenario declares one

The commitment argues -- correctly -- that a process freeze is a distinct failure class that a
kill can never produce: it holds every lock and every open transaction while every deadline in
the system expires, and then continues as if nothing happened. The primitive is built and
verified by hand against a container.

**No scenario declares it.** So the whole class of failure the commitment was written for is
unexercised, and that is recorded as a gap in `CANARIES.md` and in `HUNT-NOTES.md` rather than
counted as coverage. A related measurement explains why the in-process version is not a
substitute: a node frozen inside its handler **keeps its claims**, because extending a claim is
the coordinator's work on a separate thread that keeps running.

### Two commitments that were deliberately not honoured, with the evidence

Recorded here because "we chose not to" and "we forgot" must be distinguishable.

- **A linearizability tool for the concurrent primitives.** Attempted across two artefact
  coordinates and three virtual-machine versions. It compiled and failed at run time every time,
  because its agent could not retransform classes. The dependency and the probe were **removed**
  rather than left behind a version guard, because a test that only runs on a platform the
  author could not try is a quarantine with extra steps. The target it was aimed at is still
  worth having and is written down.
- **A transactional-anomaly analyser.** The history schema was deliberately designed so that a
  converter to its format is mechanical, and the field mapping is written down -- but no
  dependency is taken, because no transactional-isolation question has yet needed one. Taking a
  dependency before there is a question for it is cost without benefit.

---

## 3. The extensibility charter, as an executable acceptance test

> **Add a new invariant, a new fault and a new backend without editing any existing scenario.**

That must be a documented, mechanical recipe, and it is checked at every phase boundary. If a
change requires surgery on existing classes, the design failed the charter and gets reworked
before the work closes.

What the charter buys, concretely:

- **A scenario is data, not code.** A scenario record is a workload shape, a fault schedule, a
  backend selector, a timescale arm, an oracle set, a determinism mode and a seed. New
  scenarios -- including ones reproducing a customer issue or targeting a change's blast radius
  -- are new records. The corpus is the initial instance set, not the product.
- **Five open registries, each with a recipe** (all in `recipes.md`): invariants and checkers,
  faults, workloads, backends, formal models.
- **The history schema is the stable contract.** Fields are added, never repurposed; changing
  what a field means bumps the version, adding one does not; unknown fields are ignored on read.
  Anything that can emit this history -- a new module, an example application, a client
  reproduction rig -- gets the full oracle set for free.
- **The claims list is append-only and versioned.** A new framework feature enters by adding
  claim numbers, hypotheses and scenario records, and the design method re-runs incrementally.
- **No scenario-count assumptions anywhere.** Caps, matrices, corpus sizes and fuzz assertions
  derive from the registries, not from hardcoded lists.

### What violating it looks like

Worth knowing by shape, because it is easy to do by accident and it looks productive:

The first attempt at running the existing integration-test suites across stores wrote **one leaf
test class per abstract suite per store** -- nineteen files for the first store, and nineteen
more for every store added after. It was deleted. The abstract suite's infrastructure accessor
stopped being abstract and now resolves the store from a system property with an in-memory
default; the nineteen leaves became backend-neutral runners holding nothing but a name; and a
per-backend verdict comes from running the same classes once per store.

Two properties that recovered and the other shape did not have: **adding a store costs one file
in total**, and the default build starts no container because the default property value says
so -- which is stronger than a tag, because there is nothing to forget to tag.

The same failure shape in other registries: a checker that a scenario has to opt into (an
invariant that only runs where somebody remembered it will be forgotten); a fault the schedule
has to know the type of; a per-tier duplicate of a scenario record. All three were avoided, and
the reasons are in `HUNT-NOTES.md`.

### Reserved integration points

Named in the charter so that a future agent finds a socket rather than inventing one: fuzz
biasing towards a package (for an agent hunting a specific change), scenario-record import from
an external report (for a reproduce-a-customer-issue flow), and history export for external
analysers.

---

## 4. Lineage: what was copied, and what had to be adapted

This harness deliberately copies an existing formal-plus-simulation setup rather than inventing
one: a prior private proof-of-concept pairing a TLA+ model with a deterministic simulation
harness over a different event-sourced engine. That repository is not reachable from here and
nothing below depends on it -- the table is the substance, and `recipes.md` plus
`method-essentials.md` are the adapted, self-contained form of its method. The lineage is
recorded as provenance and as a checklist of what a setup like this needs.

What was taken:

| Taken | Adapted how |
|---|---|
| The invariant registry structure, with a cross-reference contract table | Same structure; MachineNames map to claim and gap numbers instead of workflow invariants |
| The findings-document format: numbered findings, severity, evidence, candidate fix, reproduce command | Same, plus a per-backend verdict vector per finding, which the reference had no need for |
| The fault-injectable store wrapper | Same pattern over a real storage engine, extended with token-store fault hooks and an infrastructure seam on the backend |
| The determinism seams | **Prefer the framework's existing injection points.** This framework already injects executors, initial tokens and the timing knobs, so no seam was invented. Where a seam does not exist, find an algebraically equivalent knob rather than patching (see `method-essentials.md`) |
| Probabilistic fault-injection points | Same class shape, and the fire points live in **harness wrappers, never in framework code** |
| The fuzz, reproduce and regression-seed test wiring, and the tag-based exclusion of the sweep | Copied including the property names, so the invocation is recognisable to anybody who knows the reference |
| The anti-hang design: wall-clock deadline primary, step cap secondary, violation objects carrying the seed, the fault trace and a reproduce command | Copied verbatim |
| The formal layer's layout: pure-operator module, state machine, per-property configurations, violated/fixed pairs, git-ignored tool fetch | Same; two models here instead of one |

**And what the reference's own weaknesses dictated about this design**, which is the more useful
half of the lineage. The reference was single-node, in-memory only, prone to per-invariant
tolerance creep, scoped its invariants to one workflow type, and had weak liveness. Each of
those is why something here exists: the multi-node layer, the container tier and the backend
differential, the four-channel checker result (which replaces tolerance creep with an explicit
not-applicable statement), the registry keyed by MachineName rather than by feature, and a
declared liveness horizon whose basis is written down.

Two hard rules inherited verbatim, because violating either is how a suite starts lying: **never
patch the engine** -- a finding gets an expected-gap test that flips when it is fixed -- and
**never mask flakiness with reruns.**
