# Honest reporting

A bug-hunting suite's output is a claim about a piece of software that other people will act on.
This is not a style section: every rule here exists because breaking it once would have sent
somebody after a defect that was not there, or left a hole reading as coverage.

---

## 1. Paste real output

- **Never claim an unrun result.** No verdict from a command that was not executed and whose
  output was not read.
- **Judge by the exit code**, not by a banner. Under `-q` a clean build prints nothing, and a
  pipe reports the last command's status rather than the build's.
- If a command is too slow to run in full -- a nightly sweep, a full container matrix -- **run the
  shortest form that proves the invocation is correct and say which you did.** "Verified at 3
  seeds; the scheduled job runs the same command with 250" is honest. "Verified" is not.
- Quote the numbers you actually saw, not the numbers you expected. The measured overlaps that
  eventually overturned an inference were in the record all along, at 964 to 992 ms against a
  bound of 1000 ms; they had been described as "just under its bound", which was true and hid the
  point.

## 2. Say which audit rows came back short

Run the green-but-broken audit before declaring any pass (checklist in
`method-essentials.md` section 4), and publish it as a table with a verdict per row: `ok`,
`ok, after N corrections`, or **short**.

On this project the audit found at least one overclaim in **every** phase. In one pass four rows
came back wrong and three of those four would have been written up as framework findings. In
another, four rows came back short and they are exactly what caps what may be claimed: one
topology, one seed per arm, no fault on a real store, no transactional read model.

**A short row is not a defect in the arm.** It is usually the difference between a smoke budget
and a hardening one. Recording it costs a line; not recording it means somebody later quotes a
hardening verdict off a smoke run, which is the overclaim the audit exists to prevent.

Two rows come back short most often, and both are worth checking before anybody asks: **one
topology** (a defect needing five nodes will not surface at three) and **one seed** (one seed is
one interleaving; a hardening claim needs at least three agreeing).

## 3. Record what was not tested, with an owner

Two established patterns, both worth copying exactly:

- **A "not canaried" table** in `formal/CANARIES.md`: what has never had a planted defect aimed
  at it, why, and who owns closing it. Rows are struck through when closed and the reason is left
  visible, rather than deleted.
- **Accepted residuals** in the plan's coverage appendix: a claim with no coverage, the reason,
  and **the condition under which it should be revisited**. "No module exists in this tree to
  target" and "the cost is not justified before the cheaper arm has produced findings" are both
  complete answers; "not done" is not.

A third, for anything blocked: **blocked, with evidence.** One arm here is not skipped silently
but recorded as a finding carrying the linkage error that prevents it. A silent skip is
indistinguishable from an oversight; a recorded block is a fact somebody can act on.

## 4. An escaped canary stays in the record

Two mutations escaped the suite. Both write-ups are still there, in full, next to their later
re-runs which caught them.

**Do not overwrite an escape with its re-run.** The record of the hole is the evidence that the
hole existed, and it is the more interesting half: one escape's original diagnosis named three
things that would catch it, two were built, and the re-run showed **the third was never the
blocker** -- while also showing that the oracle everybody expected to catch it structurally
cannot, because the behaviour it produces is licensed by the framework's own contract. None of
that is recoverable from a document that says "caught".

The same rule for an assertion that was written, measured, and removed. Both times an assertion
was removed for being red on a clean engine, the four numbers that justify its absence were
written into `CANARIES.md`, so the next agent does not have to re-measure to find out why it is
not there.

## 5. Label how strong the evidence is

Every finding says **how it was found**, and the labels are not interchangeable:

| Label | Means |
|---|---|
| Confirmed by reading | Legitimate, and weaker than a test. Saying so is the point of the label. |
| Reproduced by test | A failing run produced it, and the history is attached. |
| Measured | A number was produced; no invariant was broken. Common for a behaviour that is not a defect but that somebody must know about. |
| Model-checked | Exhaustively at stated bounds. The bounds are part of the claim. |

And per finding: **a per-backend verdict vector, or an explicit statement that it was found on
one store.** A finding with no vector is one store's observation. Producing the vector costs one
command.

## 6. Never assert an absence of findings

Two assertions had to be weakened, both for the same reason: they asserted that nothing **else**
was wrong.

- "Every violation on this store is the one we know about" stopped being true the moment a second
  thing became decidable there -- and the second thing was a real finding, not a broken
  expectation. Pin the finding you mean, not the shape of the whole result: assert the known
  divergence is **present**, not that it is **alone**.
- "This store found every invariant expressible" stopped being true when three invariants learned
  to say they cannot be judged against a store with no owner. It now asserts only what it meant.

**An assertion that the suite finds nothing else is an assertion that the suite must stop
working.**

## 7. Distinguish a measurement from an inference

The single clearest lesson in the project, and the one most likely to be repeated.

A measurement is what a run produced. An inference is what you concluded from it. **A measurement
plus an inference is not a result**, and the write-up must separate them, because the inference is
the part that turns out to be wrong.

One arm measured a bound correctly and inferred a tolerance from it. The inference was published
as a candidate fix. A formal model later contradicted **the inference, not the measurement**: the
bound holds only for a component behaving punctually, and nothing makes it punctual. Publishing
the fix as written would have documented a tolerance the framework does not enforce.

So: state the measurement, state the inference, and say what would settle it. "Measured X;
inferred Y; not established, because Z" is a complete and useful report. "Y" alone is a claim
somebody will build on.

## 8. Reporting a run, concretely

Whatever the audience, a run report answers these:

1. **What was run**, as the exact command, and at what tier and how many seeds.
2. **What the verdict was**, per arm and per backend, with the vector.
3. **What the oracles decided** -- and for the ones that declined, why, and out of which channel.
4. **What evidence each declared fault produced**, taken from the thing perturbed.
5. **Which audit rows came back short**, and therefore what may not be claimed.
6. **What is new** versus what is an existing finding, checked against `formal/FINDINGS.adoc`
   rather than against memory.
7. **The history file**, because under real threads it is the only exact record of the run.

And the thing not to do: do not write up a divergence from a first run. Fix the harness until the
divergence stops shrinking, and only then attribute it. On the first real-store run here, roughly
1800 reported violations reduced to 231 violations of one invariant -- which was the finding --
after exactly two harness corrections.
