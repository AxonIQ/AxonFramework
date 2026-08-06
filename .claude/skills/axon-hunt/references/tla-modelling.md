# TLA+ modelling and checking -- writing a model, and not being fooled by a green one

**This file is self-contained.** It is the working reference for writing and checking a TLA+
model: the syntax, the module and configuration structure, the review checklist, the common
bugs, how to read a counterexample, and -- the part that matters most here -- **how to tell a
model that verified something from one that verified nothing.**

It does **not** own this repository's models. `formal/tla/README.md` owns the model files, their
bounds, the exact TLC commands, what each configuration produced, and which registry invariants
have no model and why. Read that for specifics; read this for method.

To replay a **recorded run** through a model rather than exploring a state space, see
`tla-trace-validation.md`.

---

## 1. What a model buys, and what it cannot

**Detects well.** Protocol-level defects: a safety invariant violated, an ordering violation, a
livelock, a reconfiguration edge case. Implicit assumptions made explicit and then refuted by a
counterexample. Fairness and liveness at design time, before any code exists.

**Misses.** Implementation bugs -- the model is not the code. Performance and wall clock.
Anything outside the modelled state space: an unmodelled message type, a new fault class.

**The pairing that works.** A model proves the design; tests catch the implementation's drift
from it. Neither replaces the other. In this suite the model has already earned its keep once by
**overturning an inference drawn from a correct measurement** -- see `traps.md`.

**The bridge is the point.** An invariant is real only when the same statement exists in three
places, worded identically: the registry row, the Java assertion, and the TLA+ operator. A model
whose operator is worded differently from the registry has not bridged anything, and
`formal/tla/README.md` says so per invariant rather than implying a bridge that is not there.

---

## 2. Module structure

```tla
---- MODULE ModuleName ----
EXTENDS Naturals, Sequences     \* imports
CONSTANTS N, Nodes              \* parameters
ASSUME NAssumption == N \in Nat \ {0}
VARIABLES x, y                  \* state
vars == <<x, y>>

TypeOK == x \in Nat /\ y \in BOOLEAN

Init == x = 0 /\ y = FALSE

A == x < N /\ x' = x + 1 /\ UNCHANGED y
B == y' = ~y /\ UNCHANGED x
Next == A \/ B

Spec == Init /\ [][Next]_vars
====
```

Rules that come out of practice rather than the grammar:

- **`Init` uses `=`, never `:=`.** Actions use `x'` for the next state.
- **Every variable is accounted for in every action**, by an assignment or by `UNCHANGED`.
- **Invariants never mention primed variables.** An invariant is about the current state.
- **`TypeOK` lives next to the variable declarations**, so a reader learns the types before the
  actions. Write it as `f \in [S -> T]` rather than `\A s \in S : f[s] \in T`.
- **`ASSUME` every constant.** `ASSUME N \in Nat \ {0}` rather than leaving zero reachable. TLC
  refuses to check a model whose assumptions are violated, which is the point. Name them --
  `ASSUME NAssumption == ...` -- so a proof or a review can refer to them. A conjoined `ASSUME`
  works but TLC will not say which conjunct failed.
- **Keep the spec free of model-checking artefacts.** Put model values in a separate module that
  extends the spec, and substitute in the configuration. A spec that depends on TLC features
  cannot be checked by anything else.

## 3. Syntax reference

### Logic

`~P` / `\neg P` not. `P /\ Q` and. `P \/ Q` or. `P => Q` implies. `P <=> Q` / `P \equiv Q`
equivalence.

### Comparison and arithmetic

`=` `#` (also `/=`) `<` `>` `<=` (`\leq`) `>=` (`\geq`).
`+` `-` `*` `\div` (integer division) `%` `^`.

### Sets

`e \in S`, `e \notin S`, `S \union T` (also `\cup`), `S \intersect T` (also `\cap`),
`S \ T` (also `\setminus`), `S \subseteq T`, `SUBSET S` (the power set), `UNION S` (flatten),
`{e : x \in S}` (map), `{x \in S : P}` (filter).

**Prefer `\union` and `\intersect` over `\cup` and `\cap`** -- they read the way people say them,
and a model is read by people more often than by TLC.

### Quantifiers and choice

`\A x \in S : P` for all. `\E x \in S : P` exists. `CHOOSE x \in S : P` -- see section 6, this
one has a trap.

### Temporal

`[]P` always. `<>P` eventually. `[A]_v` -- A, or v unchanged. `<<A>>_v` -- A, and v changed.
`WF_v(A)` weak fairness. `SF_v(A)` strong fairness. `P ~> Q` -- P leads to Q.

### State relations

`x'` the next-state value. `UNCHANGED <<x, y>>` -- shorthand for `<<x,y>>' = <<x,y>>`.

### Conditionals and bindings

```tla
IF P THEN e1 ELSE e2
CASE p1 -> e1 [] p2 -> e2 [] OTHER -> e3
LET x == e1
    y == e2
IN  body
```

### Data types

| Kind | Forms |
|---|---|
| Boolean | `TRUE`, `FALSE`, `BOOLEAN` |
| Numbers | `Nat`, `Int`, `Real`, `a..b` |
| Strings | `"text"`, `STRING` |
| Sequences | `<<a, b, c>>`, `Len`, `seq[i]` (1-based), `Append`, `Head`, `Tail`, `SubSeq(s,m,n)`, `s \o t` |
| Records | `[f1 \|-> v1, f2 \|-> v2]`, `r.f`, `[r EXCEPT !.f = v]`, `[f1: S1, f2: S2]` (the set of records) |
| Functions | `[x \in S \|-> e]`, `f[x]`, `DOMAIN f`, `[f EXCEPT ![x] = e]`, `[S -> T]` (the set of functions) |

**`EXCEPT` nests, and `@` is the old value.** Write `[f EXCEPT ![p].count = @ + 1]`, never a full
record literal restating the fields that do not change -- restating them is how a field silently
stops being updated when the record grows a new one.

### Operators are not functions

An **operator** is a macro: `Double(x) == 2 * x`, applied as `Double(3)`. A **function** is a
value: `f == [x \in Nat |-> 2 * x]`, applied as `f[3]`. A function can be stored in a variable,
quantified over and compared; an operator cannot. Most modelling of per-node state is a
**function** keyed by node, because it has to live in a variable.

### Standard modules

- `Naturals`, `Integers` -- arithmetic and ranges.
- `Sequences` -- `Seq(S)`, `Len`, `Head`, `Tail`, `Append`, `\o`, `SubSeq`.
- `FiniteSets` -- `Cardinality(S)`, `IsFiniteSet(S)`.
- `Json` -- `ndJsonDeserialize`, for trace validation. See the companion file.
- `TLC` -- `Print`, `Assert`, `ToString`, `TLCGet`, `:>` and `@@` for building functions.
  **`EXTENDS TLC` is rarely wanted in a plain spec.** Its operators are for special cases; the
  notable exception is `@@` for merging two functions. Prefer an invariant over `TLC!Assert`,
  because a violated invariant gives you a counterexample and an assertion gives you a message.

The **CommunityModules** collection carries well-tested operators, several with efficient TLC
overrides: https://github.com/tlaplus/CommunityModules

### `EXTENDS` against `INSTANCE`

`EXTENDS M` inlines M's definitions -- convenient, and it clashes if both modules define the same
name. `I == INSTANCE M` namespaces them behind `I!Op`. `INSTANCE M WITH c <- v, var <- expr`
additionally **substitutes** M's constants and variables, which is what a refinement mapping is.
A module with `CONSTANTS` or `VARIABLES` you need to bind can only be brought in by `INSTANCE`.

### Indentation is semantic in one place

Everywhere else it is ignored. In a **junctor list** -- a multi-line `/\` or `\/` chain -- the
operators' indentation determines the grouping:

```tla
/\ TRUE
  \/ TRUE          \* this disjunction binds tighter: (TRUE \/ TRUE) /\ FALSE
/\ FALSE

\/ TRUE
\/ TRUE
  /\ FALSE         \* TRUE \/ (TRUE /\ FALSE)
```

**Align every junctor in a list at the same column.** Spaces, never tabs. A misaligned junctor
is either a syntax error or, worse, a different formula that still checks.

## 4. The configuration file

A `.cfg` names formulas; it should not contain TLA+ expressions. Definitions live in the `.tla`.

```
SPECIFICATION Spec
CONSTANTS
    N     <- MC_N
    Nodes <- MC_Nodes
INVARIANTS
    TypeOK
    Safety
PROPERTIES
    Liveness
CONSTRAINTS
    StateLimit
CHECK_DEADLOCK FALSE
```

| Section | Purpose | Level |
|---|---|---|
| `SPECIFICATION` | The temporal formula to check -- `Init /\ [][Next]_vars /\ Fairness` | 3 |
| `INIT` + `NEXT` | The alternative. **Safety only** -- no fairness, so no liveness. Cannot be combined with `SPECIFICATION` | 1, 2 |
| `CONSTANTS` | Bind or replace declared constants. `<-` replaces a constant *or an operator* with another definition | 0 |
| `INVARIANTS` | State predicates true in every reachable state | 1 |
| `PROPERTIES` | Temporal formulas. Liveness, action properties, refinement | 2 or 3 |
| `CONSTRAINTS` | A state failing this is not explored further, and is not an error. Bounds the search | 1 |
| `ACTION_CONSTRAINT` | The same, on transitions rather than states | 2 |
| `SYMMETRY` | A permutation set; symmetric states are treated as one. **Wrong symmetry gives wrong results** | 1 |
| `VIEW` | A state abstraction; two states with the same view are one. **Too coarse and TLC misses errors** | 1 |
| `ALIAS` | A record-valued formula that replaces the raw state in a printed trace. Display only | 2 |
| `POSTCONDITION` | Checked once, after checking finishes. One only | 0 |
| `CHECK_DEADLOCK` | `TRUE` by default. Set `FALSE` when the model legitimately terminates | -- |

**Formula levels**, because a section rejects a formula above its level: 0 constants only; 1
unprimed variables (a state); 2 primed and unprimed (a transition); 3 temporal operators. Each
level is a degenerate case of the next.

**A state predicate belongs in `INVARIANTS`, not in `PROPERTIES` as `[]P`.** Same meaning, much
cheaper.

**Never conjoin invariants into one.** TLC reports only that the composite failed, not which part:

```tla
Inv == TypeOK /\ Safety /\ Bounded      \* do not do this
```

List them separately in the configuration and TLC names the one that broke. This is the single
cheapest configuration change that makes a red run readable.

**Bound the model with `CONSTRAINT`, not with an extra enablement condition.** Adding
`count <= Max` as a guard changes the specification; adding it as a constraint changes only what
TLC explores. Keep the model general and let the configuration bound it.

**Model values beat integer ranges** for a set whose order is irrelevant: `Nodes = {n1, n2}`
rather than `Nodes == 1..2`. It reads better in a counterexample and it is what `SYMMETRY`
needs. And **document a magic number** in the configuration -- why 3 and not 4 is exactly the
question a reader will have.

## 5. Checking it -- the order that finds bugs fastest

1. **Parse.** Syntax and level checking, before anything else. A level error is a real defect,
   not a formality.
2. **Simulate.** TLC's simulation mode walks long random traces instead of exploring
   breadth-first, so it finds shallow bugs in seconds that exhaustive checking would reach only
   after minutes. **Any warning is investigated, not ignored.**
3. **Explore.** Generate random behaviours and **read them.** Does each trace look like something
   the real system could do? Simulation and checking verify that the properties hold; only a
   human confirms the model describes the intended system. Look for impossible combinations and
   transitions that should not exist.
4. **Check exhaustively, smallest configuration first.** Rely on the small-scope hypothesis: most
   defects show up in tiny configurations. Two writers, two nodes, a batch of two. Then grow --
   and remember that a linear growth in a constant is an exponential growth in states.
5. **Check more than one configuration.** A single constant assignment does not expose corner
   cases. Vary them, and start from the smallest.

In this repository, step 1 has a dedicated wiring smoke test -- `Sanity.tla` with a trivial
invariant -- so that a failure in a real run is about the model rather than the tool chain. Keep
that habit when adding a model.

## 6. The traps that make a model say nothing

### `CHOOSE` is not non-determinism

`CHOOSE x \in S : P` picks **one specific** element, and always the same one. It is Hilbert's
epsilon, not an existential.

Use it to **name a value**: `Max(S) == CHOOSE s \in S : \A t \in S : s >= t`, or a null:
`NULL == CHOOSE x : x \notin D`.

**Never use it to reduce the state space.** This looks like an optimisation and is a bug:

```tla
SendMsg(m) == \E r \in Nodes : inbox' = [inbox EXCEPT ![r] = @ \union {m}]   \* every receiver
WrongSend(m) == LET r == CHOOSE n \in Nodes : TRUE                            \* always the same one
                IN inbox' = [inbox EXCEPT ![r] = @ \union {m}]
```

The second explores one routing and reports that everything is fine. The same trap applies to
`Init`: `memory \in [T -> V]` explores every initial mapping;
`memory = [t \in T |-> CHOOSE v \in V : TRUE]` explores exactly one.

`RandomElement` from the `TLC` module has the same shape and is almost always wrong for the same
reason. Use `x' \in S`.

### Stuttering is already there

`[Next]_vars` means `Next \/ vars' = vars`. **Never add a `Skip == UNCHANGED vars` disjunct** --
it is already included, and an explicit one is noise. Worse, `UNCHANGED <<>>` is `TRUE`: a
disjunct of `UNCHANGED <<>>` makes `Next` allow **any** state, which passes every check for the
wrong reason. It usually arrives by deleting variables from an `UNCHANGED` and not deleting the
`UNCHANGED`.

### Fairness only where it is justified

Without fairness, no liveness property holds -- and **if a liveness property holds without
fairness, either the property or the fairness constraint is wrong.**

`WF_v(A)`: if A stays enabled, it eventually happens. `SF_v(A)`: if A is enabled infinitely
often, it eventually happens. **Prefer weak, and check whether it suffices before reaching for
strong.** Not every action deserves fairness: a failure action, or a writer that is not obliged
to keep writing, should not be fair.

**A liveness property should assert repeated progress, not one occurrence.** `<>(consumed > 0)`
is satisfied by a system that makes one step and then wedges. `[]<>(buffer = <<>>)` -- the buffer
is empty infinitely often -- is the property you meant.

### If-then-else changes what `ENABLED` means

`IF guard THEN ... ELSE UNCHANGED vars` makes the action **always enabled**; a guard as a
conjunct makes it enabled only when the guard holds. Under fairness those are different
specifications. Pick deliberately.

### Locality -- nothing stops an action touching two nodes

In TLA+ **every variable is global.** Per-node state is modelled as a function keyed by node, and
nothing prevents an action from updating two nodes at once -- which is not a thing a distributed
protocol can do. Some multi-component updates are legitimate: a message transfer moves an item
from one node's outbox to another's inbox, because those are channels, not local state.

For state that is genuinely node-local, assert it:

```tla
NoConcurrentStateChanges ==
    [][\A p, q \in Nodes :
         /\ p # q
         /\ state[p] # state'[p]
         => UNCHANGED state[q]
      ]_vars
```

It is a temporal property, so it goes under `PROPERTIES`, not `INVARIANTS`. Group a node's
variables when it has several:

```tla
NodeLocal(n) == <<state[n], buffer[n], timer[n]>>
```

It constrains *writing* another node's state, not *reading* it -- reading is a separate modelling
question.

**Directly relevant here.** The token-claim model is a multi-node protocol over per-segment state.
An action that quietly updates two owners at once would make an exclusion invariant hold for a
reason the real protocol cannot rely on.

### History variables cost state

A variable that exists only so an invariant can be written -- and that appears in no enablement
condition -- is a history variable. Legitimate, and each one multiplies the state space. Name it
for its purpose, and add it only when the property cannot be written without it.

## 7. Always be suspicious of success

**A model that does nothing satisfies every property.** "No error found" is only meaningful when
two things hold: the model faithfully describes the intended system, and the invariants are strong
enough to detect a real defect. Neither is checked by TLC.

Four ways to find out, in increasing cost:

### Read the state count

If the distinct-state count is implausibly low, the model is almost certainly wrong -- an
enablement condition nothing satisfies, a constraint that cuts off the interesting part, an `Init`
that is unsatisfiable in all but one way. `formal/tla/README.md` records states and depth per
configuration for exactly this reason: a number that moves unexpectedly after a change is a
signal.

### Read the coverage

Run with coverage enabled. It is line coverage for a specification, and it shows dead formulas:

- **Per-action state coverage.** How many states each action was enabled in, and how many
  transitions it generated. **An action with zero transitions never fired** -- its guard is too
  restrictive, or it conflicts with something else. That is the same defect as a Java assertion
  that never ran.
- **Per-variable value coverage.** Which distinct values each variable took. A counter that only
  ever reached 0 and 1 when it could go higher means the constants are too small or a behaviour is
  unreachable. Suspiciously high coverage is the other failure -- state-space explosion.
- **Dead branches.** An `IF` arm never taken, a disjunct never satisfied, an implication whose
  antecedent is never true.

Any unexercised formula is worth investigating. It is a model bug, an over-constrained
configuration, or genuinely dead text that should be deleted.

### Inject a bug and check that something goes red

The TLA+ form of a canary campaign, and the direct answer to "are these invariants strong
enough". **Mutate the state machine, leave the invariants and properties untouched**, and check
that at least one of them breaks:

- Remove an enablement condition -- allow an action where it should be impossible.
- Flip an arithmetic or comparison operator: `+` for `-`, `<` for `<=`.
- Weaken a condition: `x > 0` becomes `x >= 0`.
- Remove a disjunct from `Next`.
- Remove a fairness constraint.

**A mutation nothing catches means the correctness conditions are incomplete.** Strengthen them or
add one. This is the same rule the suite applies to its Java checkers -- every one has to be shown
to go red on a planted defect -- and it is why this repository keeps violated-and-fixed
configuration pairs where a real finding exists: the pair *is* the evidence that the property has
teeth.

### Check the model against something independent

The strongest form, and what this suite does: the reference model is compared against the
specification over a whole finite domain, so a shared bug has to be present in two independently
written artefacts to survive. See `formal/tla/README.md` for the cross-check.

## 8. Reading a counterexample

TLC prints the trace that reached the bad state. Work it in this order:

1. **Minimise first.** Shrink constants and constraints to the smallest configuration that still
   violates. Everything after this is easier on a short trace.
2. **Find the violating state.** Which state fails the invariant.
3. **Find the transition into it.** Which action produced that state, from which predecessor.
4. **Diff the two states.** What changed, and what changed that you did not expect. An unexpected
   change is the defect more often than the expected one being wrong.
5. **Form one hypothesis.** Is the guard too weak? Is `Init` wrong? Is the invariant too strong --
   which is a real possibility and a real finding, because it means the property was mis-stated?
6. **Test it.** Add a narrower intermediate invariant, or a `Print`, or comment out one disjunct
   to see whether the violation disappears.
7. **Fix minimally, re-check everything**, then re-check at a larger configuration.

**For a property violation, check the invariants first.** Remove the `PROPERTIES` entries, rerun,
and confirm every invariant holds. A liveness failure on top of a broken invariant is a symptom,
not the defect.

**Make the trace readable with `ALIAS`.** A record-valued formula that replaces the raw state:
name the fields, hide the noise, compute the derived value you actually want to see, and include
`ENABLED` of the action you suspect:

```tla
Alias == [ pos |-> position, owner |-> owner,
           expired |-> stamp + timeout < now,
           canSteal |-> ENABLED Steal ]
```

It changes nothing about the checking and a great deal about the ten minutes after a red run.

### The bugs that account for most red runs

| Bug | Wrong | Right |
|---|---|---|
| Missing guard | `Remove(i) == items' = items \ {i}` | `Remove(i) == i \in items /\ items' = items \ {i}` |
| Off by one | `count < Max /\ count' = count + 1` when `count` must stay below `Max` | `count + 1 < Max /\ count' = count + 1` |
| Missing `UNCHANGED` | `A == x' = x + 1` | `A == x' = x + 1 /\ UNCHANGED y` |
| `EXCEPT` without `!` | `[f EXCEPT [k] = v]` | `[f EXCEPT ![k] = v]` |
| Variable never initialised | `Init == x = 0` with `y` declared | `Init == x = 0 /\ y = 0` |
| Primed variable in an invariant | `Inv == x' > 0` | `Inv == x > 0` |
| `=` instead of `'` in an action | `A == x = x + 1` | `A == x' = x + 1` |

## 9. Refinement -- relating a detailed model to an abstract one

A concrete specification **refines** an abstract one when every behaviour it allows is allowed by
the abstract one. It is semantic: the two need not share variables or modules, and it is
**stuttering insensitive**, so extra concrete steps that do not change the abstract state are
fine.

To check it: instantiate the abstract module inside the concrete one, map the variables, and make
the abstract specification a property of the concrete one.

```tla
High == INSTANCE HighLevel WITH counter <- x       \* or WITH counter <- x + y
Refinement == High!Spec
```

```
SPECIFICATION Spec
PROPERTY Refinement
```

If the concrete module already defines a symbol of the same name -- possibly as a state function
rather than a variable -- the mapping is implicit and `WITH` is unnecessary.

Where this would be useful here: an abstract append protocol refined by a concrete
per-store model, so that a store adapter's own semantics become a refinement question rather than
an argument.

## 10. Proofs -- what to know and what to skip

TLC checks a model at bounded scope. TLAPS **proves** a theorem at unbounded scope, and the usual
first target is type correctness:

```tla
LEMMA TypeCorrect == Spec => []TypeOK
<1>1. Init => TypeOK                 BY Assumption DEF Init, TypeOK
<1>2. TypeOK /\ [Next]_vars => TypeOK' BY Assumption DEF TypeOK, Next, vars, A, B
<1>. QED                             BY <1>1, <1>2, PTL DEF Spec
```

Proofs live in a separate `_proof.tla` module that extends the spec, need `EXTENDS TLAPS` for the
pragmas, and must **name and cite every `ASSUME`** in a `BY` clause -- an uncited assumption is a
step that silently will not discharge.

**Declare an important property as a `THEOREM`** -- but only one that is actually proven or at
least checked, because an unproven theorem in a specification reads as a guarantee and is not one.

Nothing in this repository is proven with TLAPS today, and that is a deliberate scope limit rather
than an oversight: the models are design models bounded by their configurations, and every result
they carry is a statement about those bounds.

## 11. PlusCal

An imperative language that compiles to TLA+. Less expressive -- it can express a subset of what
TLA+ can. **Do not write it unless somebody explicitly asks for it.** Nothing here uses it.

## 12. Further reading

- TLA+ home, the language and TLC: https://lamport.azurewebsites.net/tla/tla.html
- CommunityModules: https://github.com/tlaplus/CommunityModules
- Coverage statistics, in detail: https://explain.tlapl.us/module-coverage-statistics.md
- "How Amazon Web Services Uses Formal Methods", Newcombe et al., CACM 2015.
- "Using Lightweight Formal Methods to Validate a Key-Value Storage Node in Amazon S3",
  Bornholt et al., SOSP 2021 -- property testing combined with model checking, which is the shape
  this suite uses.
