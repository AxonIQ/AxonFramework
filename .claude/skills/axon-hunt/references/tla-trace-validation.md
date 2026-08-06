# TLA+ trace validation -- replay a recorded run through the model

**This file is self-contained.** It describes how to check a **real recorded history** against a
TLA+ specification, instead of exploring a state space. The specification stops being a design
document and becomes an oracle over actual runs.

**Why this matters here specifically.** The suite records every run as **JSON Lines** -- one
object per line -- which is exactly the format TLA+'s `Json` module reads. Today the bridge
between the models and the engine is two things: identical wording via MachineName, and
`crosscheck/CrossCheck.java`, which replays a **finite synthetic domain** through the Java
reference model. Neither one ever shows the specification a real contended run. Trace validation
does, and it costs one new module plus one configuration per model.

`formal/INVARIANTS.md` section 4 owns the history schema. `formal/tla/README.md` owns the models.
This file owns the technique.

---

## 1. The idea

```
a hunt run
    |  records
    v
history.jsonl  (one JSON object per line)  ------+
                                                  |  read by ndJsonDeserialize
the existing model, e.g. DcbAppend.tla  ----+     |
                                             |    v
                                        DcbAppendTrace.tla    the validation module:
                                             |                maps each record to an action
                                             v                and pins the state either side
                                        TLC + POSTCONDITION
                                             |
                                    trace accepted, or rejected at line N
```

The validation module **constrains the specification to the behaviour the trace describes** and
asks whether such a behaviour exists. If it does not, either the engine did something the design
forbids, or the model does not describe the engine. **Both answers are findings**, and they land in
different places -- the second is a model defect, which under the blame taxonomy is a checker bug,
not an engine bug. See `verdicts-and-classification.md` section 3.

## 2. When it applies, and when it does not

**Applies** to a totally ordered trace observed from one point: a sequential event log, a state
machine, or a concurrent system whose records are taken at consistent points -- inside a critical
section, or under one lock, or serialised through one recorder.

**Does not apply**, without more machinery, to a genuinely concurrent trace with arbitrary
interleaving observed from several points. That needs vector clocks or an explicit happens-before
relation.

**Where that leaves this suite.** The recorder assigns a **strictly increasing `idx` under its own
lock, and `idx` defines the history's order** -- so a recorded history *is* a totally ordered trace
from one observation point, which is precisely the applicable case. Two honest limits:

- **`SINGLE_THREADED` histories are the natural first target.** The order is the real order and
  there is no interleaving to abstract.
- **A `REAL_THREADS` history is still a total order of records**, but a rejected trace then has a
  third possible cause: the model has no concurrent action for something the engine legitimately
  did concurrently. Start single-threaded, get an accepted trace, and only then aim it at a
  contended one -- otherwise the first rejection is uninterpretable.

**The constants must match the run.** A trace was produced with concrete parameters; the model has
to be configured with those same values or the validation fails for a reason that has nothing to do
with the engine. Take them from the history header, which records the version combination, the
backend, the timescale and the workload shape for exactly this kind of reason.

## 3. The four components

| Component | Here |
|---|---|
| The trace | `simulation/target/hunt-histories/<...>.jsonl`, or a pinned one under `simulation/src/test/resources/hunt-histories/` |
| The specification | The existing model -- `DcbAppend.tla`, `TokenClaim.tla`. **Unchanged** |
| The validation module | New: `DcbAppendTrace.tla`. Reads the trace, maps records to actions |
| The configuration | New: `DcbAppendTrace.cfg`. Constants matching the run, plus `POSTCONDITION` and `VIEW` |

The specification is not modified. That is the whole point -- a validation module that had to
change the model would be validating against something other than the design.

## 4. Reading the trace

```tla
EXTENDS Json

TraceLog == ndJsonDeserialize("history.jsonl")
```

That yields a **sequence of records**:

```tla
<< [idx |-> 1, type |-> "INVOKE", op |-> "append", key |-> "a", ...],
   [idx |-> 2, type |-> "OK",     op |-> "append", key |-> "a", ...],
   ... >>
```

JSON maps onto TLA+ directly, which is why no conversion layer is needed:

| JSON | TLA+ |
|---|---|
| `42` | an integer |
| `"append"` | a string |
| `true` / `false` | `TRUE` / `FALSE` |
| `[1, 2, 3]` | a sequence |
| `{"x": 1}` | a record |
| `null` | absence -- handle it explicitly, see section 8 |

Take the path from the environment so one module serves every history:

```tla
TraceLog ==
    ndJsonDeserialize(
        IF "HUNT_HISTORY" \in DOMAIN IOEnv
        THEN IOEnv.HUNT_HISTORY
        ELSE "history.jsonl")
```

```sh
HUNT_HISTORY=simulation/src/test/resources/hunt-histories/pinned-conflict-check-bypass.jsonl \
  java -cp formal/tla/tools/tla2tools.jar tlc2.TLC \
    -config formal/tla/DcbAppendTrace.cfg formal/tla/DcbAppendTrace.tla
```

## 5. The validation module

```tla
---- MODULE DcbAppendTrace ----
EXTENDS DcbAppend, Json, TLC, Naturals, Sequences

TraceLog ==
    ndJsonDeserialize(
        IF "HUNT_HISTORY" \in DOMAIN IOEnv THEN IOEnv.HUNT_HISTORY
                                           ELSE "history.jsonl")

\* Drop the header line: the first record describes the run, it is not an operation.
Events == SubSeq(TraceLog, 2, Len(TraceLog))

\* The event under consideration at this step.
event == Events[TLCGet("level")]

\* Tighter than the model's own Init: the actual starting state of this run.
Init == store = <<>> /\ ...

\* One predicate per record kind. Guard on the record first, then the model's
\* own action, then pin the resulting state.
IsAppendOk ==
    /\ event.type = "OK"
    /\ event.op   = "append"
    /\ Append(event.key, event.value)          \* the model's action, unchanged
    /\ store' = event.value.storeAfter          \* pin the next state, if recorded

IsAppendRejected ==
    /\ event.type = "FAIL"
    /\ event.op   = "append"
    /\ RejectAppend(event.key, event.value)
    /\ UNCHANGED store

\* An unknown outcome constrains nothing but must not silently match an action.
IsUnknown ==
    /\ event.type = "INFO"
    /\ \/ Append(event.key, event.value)
       \/ UNCHANGED store

\* Records the model does not describe pass through without a transition.
IsIgnored ==
    /\ event.op \notin {"append"}
    /\ UNCHANGED vars

Next ==
    /\ TLCGet("level") <= Len(Events)
    /\ \/ IsAppendOk
       \/ IsAppendRejected
       \/ IsUnknown
       \/ IsIgnored

Spec == Init /\ [][Next]_vars

TraceAccepted ==
    LET d == TLCGet("stats").diameter IN
    IF d - 1 = Len(Events)
    THEN TRUE
    ELSE Print(<<"Rejected at event", d, Events[d]>>, FALSE)

\* MANDATORY when using TLCGet("level").
TraceView == <<vars, TLCGet("level")>>
====
```

```
CONSTANTS
    Events    <- MC_Events
    Boundaries <- MC_Boundaries
SPECIFICATION Spec
POSTCONDITION TraceAccepted
VIEW          TraceView
```

### The five pieces, and why each is there

**`TLCGet("level")`** is the current step number, starting at 1. It replaces an explicit index
variable, which keeps the state space from carrying one more dimension.

**`VIEW` is mandatory with `TLCGet("level")`.** Consecutive events often leave the model variables
unchanged -- two rejected appends in a row change nothing. Without the view, TLC sees the same
state twice, concludes it has found a cycle, and **stops early while reporting success**. Putting
the level in the view makes step 10 and step 11 distinct states. This is the single most common way
a trace-validation setup silently validates the first three lines of a ten-thousand-line history.

**`POSTCONDITION`** is what turns "no invariant was violated" into "the whole trace matched".
`diameter - 1` against `Len` compares states to events: the diameter counts the initial state, the
trace does not. Without it, a run that matched two events and then found nothing enabled reports no
error.

**`Init` is tighter than the model's `Init`.** The model admits every legal starting state; the
trace started from one. If the header or the first record carries it, take it from there:
`Init == position = Events[1].value.position`.

**The order of conjuncts is a performance decision.** Cheap record guards first -- `event.type`,
then `event.op` -- and the model's action last. It is evaluated for every candidate transition.

## 6. Mapping records to actions -- the part that carries the judgement

**One predicate per record kind.** The failure mode to avoid is a single predicate that admits
the whole next-state relation:

```tla
IsEvent == Next        \* validates nothing: any event matches any action
```

That accepts every trace and reports success. It is the trace-validation form of a vacuous oracle,
and it looks exactly like a working setup.

Each predicate does four things, in this order:

```tla
IsClaim ==
    /\ event.op = "claim"          \* 1. which record kind
    /\ event.value.owner = owner   \* 2. the state before, as recorded
    /\ Claim(event.node)           \* 3. the model's action
    /\ owner' = event.value.owner  \* 4. the state after, as recorded
```

Steps 2 and 4 are what make it an oracle. **A predicate that names the action but pins no state
only checks that the action was possible, not that the engine computed the right result.** That is
still worth something -- it catches an operation the design forbids -- and it is much weaker.
Say which of the two a given arm does.

### Three record kinds this schema forces you to handle

**An unknown outcome.** `type = INFO` with an error set means the operation may or may not have
taken effect. The model must allow **both**, as `IsUnknown` above does. Forcing it either way is
the same defect the history discipline forbids in a checker: deciding an unknown as success invents
divergences, deciding it as failure hides them. See `history-discipline.md` section 4.

**An invocation with no completion.** An operation still in flight when the run ended has an
`INVOKE` and nothing else. It resolves to unknown, and the trace must not be truncated to make it
go away -- run-boundary truncation is the single largest source of fake findings in history-checked
systems.

**A record the model does not describe.** A history carries every operation; a model covers one
protocol. Everything else needs an explicit pass-through with `UNCHANGED vars`, and the set of
ignored operations should be **named as a list rather than defaulted**, so that a new operation
kind appearing in histories does not get silently ignored by a model that should have rejected it.

### Weaker patterns, and what each costs

```tla
\* Multiple kinds, dispatched. OTHER -> FALSE rejects an unknown kind, which is
\* usually what you want: an unrecognised record should not pass unnoticed.
Next ==
    /\ TLCGet("level") <= Len(Events)
    /\ CASE event.op = "append" -> IsAppend
         [] event.op = "claim"  -> IsClaim
         [] OTHER               -> FALSE

\* Partial information: the action is checked, the result is not, because the
\* history does not record it. Weaker -- say so in the write-up.
IsAppendLoose ==
    /\ event.op = "append"
    /\ Append(event.key, event.value)

\* Non-determinism the trace does not resolve: allow the set of outcomes the
\* record is consistent with, and no more.
IsAdvance ==
    /\ event.op = "advance"
    /\ position' \in {event.value.position, event.value.position + 1}
```

## 7. Reading a rejection

A rejected trace prints the event index and the record. Then, in order:

1. **Is it the last event?** Then it is probably not a rejection at all -- check the
   `POSTCONDITION` arithmetic and whether the header line was dropped.
2. **Do the constants match the run?** Take them from the history header. A batch size or a
   boundary pool smaller than the run used rejects a legal operation. This is the most common
   cause by a wide margin.
3. **Is the record kind mapped at all?** An unmapped kind with no pass-through rejects.
4. **Does the model have an action for what happened?** If the engine did something concurrent that
   the model has no concurrent action for, the model is the gap. Under a `REAL_THREADS` history this
   is likely, which is why the single-threaded arm comes first.
5. **Only then: the engine violated the design.** That is the finding, and it is a strong one --
   the design says this cannot happen and a recorded run did it.

Give the counterexample readable state with `ALIAS` -- see `tla-modelling.md` section 8. A raw
dump of a store variable at event 4,000 is unreadable; a record naming the head, the boundary and
whether the condition held is not.

**Keep the constants small for everything the trace does not exercise.** A data or payload domain
the model treats as opaque should be the smallest set that lets validation run -- two model values,
not a range. The trace pins the values that matter; the rest only inflate the state space.

## 8. Making a history easier to validate

Two changes to what gets recorded, both of which pay off for the Java checkers as well:

- **Record the state either side, not just the operation.** A record carrying only "an append
  happened" supports step 3 of the mapping and neither step 2 nor step 4. A record carrying the
  head before and the head after makes the validation an oracle over the engine's computation
  rather than over its choice of operation.
- **Record at a stable point.** After the state update, not before, and not between two updates
  that are supposed to be one transition. A record taken mid-transition describes a state the model
  correctly says is impossible, and the rejection is the recorder's fault.

Note what this does **not** ask for: no change to framework code, and no new seam. The recorder is
harness code, and widening what it records is harness work.

## 9. What to do with an accepted trace

An accepted trace is a real result and it is worth stating precisely, because it is easy to
overclaim:

> The specification admits the behaviour this run produced, at the constants the run used, for the
> operations the mapping covers.

It is **not** "the engine conforms to the design". It says nothing about operations the mapping
passes through, nothing about state the records do not carry, and nothing about any other run.
Name those three limits when reporting it -- the scope-declaration rule from
`oracle-patterns.md` applies here in full.

The regression asset is the pair: **the pinned history plus the validation module**. Like every
oracle in this suite, it has to be shown able to fail -- validate a history recorded while a rule
was deliberately bypassed, and check that it is rejected at the event where the bypass happened.
This repository already keeps such a history for exactly this purpose:
`pinned-conflict-check-bypass.jsonl`, recorded with the store's conflict check bypassed. **If a
validation module accepts that trace, the module is broken**, and that is the canary for this
technique.

## 10. Further reading

- The `Json` module: https://github.com/tlaplus/CommunityModules/blob/master/modules/Json.tla
- "Validating Traces of Distributed Programs Against TLA+ Specifications":
  https://arxiv.org/abs/2404.16075
- "Smart Casual Verification of the Confidential Consortium Framework":
  https://arxiv.org/abs/2406.17455
- A worked example, end to end:
  https://github.com/tlaplus/Examples/blob/master/specifications/ewd998/EWD998ChanTrace.tla
- NDJSON: http://ndjson.org/
