# TLA+ models -- DCB append and token claim

Two small design models of Axon Framework protocols, checked with TLC, bridged to
the running suite by MachineName.

The bridge is the point. An invariant is real only when the same statement exists
in three places, worded identically: in [`../INVARIANTS.md`](../INVARIANTS.md), in
the Java assertion, and in the TLA+ operator. Where a model checks something the
suite does not, or the suite checks something the model words differently, this
file says so rather than implying a bridge that is not there.

These are **design** models, not transliterations of Java. They are deliberately
tiny so TLC finishes in about a second per run, and every result below is a
statement about the bounds in the `.cfg`, not about the general case.

## Files

| File | Purpose |
|---|---|
| `Sanity.tla` / `Sanity.cfg` | A counter with a trivial invariant. Proves the TLC and `.cfg` wiring before either real model, so a failure in a real run is about the model rather than the tool chain. |
| `DcbRules.tla` | The append protocol's decision rules as pure operators over an explicit store, plus the finite event/boundary/batch pools. No variables, so both modules below bind to exactly these operators and exactly these pools. |
| `DcbAppend.tla` | The append state machine: writers source against a head, then offer a batch under the condition they hold. Invariants live here. |
| `DcbCrossCheck.tla` | Emits the reference model's decision for every case in a finite domain, for replay through the Java model. |
| `TokenClaim.tla` | The token-claim protocol for one segment: claims, steals, a clock-skew bound, crash, and a liveness property. |
| `crosscheck/CrossCheck.java` | Replays the emitted cases through `DcbStoreModel` and reports agreement. Not in the Maven reactor, not a test. |
| `MCAppend_*.cfg`, `MCClaim_*.cfg` | One configuration per property, in violated/fixed pairs where a real finding exists. |

`formal/` is not a Maven module. Nothing here affects `./mvnw verify`.

## Get the checker

`tools/tla2tools.jar` is git-ignored. Fetch it once:

```sh
mkdir -p formal/tla/tools
curl -fsSL -o formal/tla/tools/tla2tools.jar \
  https://github.com/tlaplus/tlaplus/releases/download/v1.7.4/tla2tools.jar
```

Verified with TLC2 Version 2.19 of 08 August 2024 on OpenJDK 23. Any JDK 11 or
later works.

## Run every configuration

All commands run **from the worktree root**, with no `cd`: TLC resolves
`EXTENDS`ed modules from the spec file's own directory.

```sh
J="java -XX:+UseParallelGC -cp formal/tla/tools/tla2tools.jar tlc2.TLC -workers auto -metadir formal/tla/states"

# Wiring smoke test
$J -config formal/tla/Sanity.cfg formal/tla/Sanity.tla

# --- DCB append -----------------------------------------------------------
$J -config formal/tla/MCAppend_safe.cfg                 formal/tla/DcbAppend.tla
$J -config formal/tla/MCAppend_unconditional.cfg        formal/tla/DcbAppend.tla
$J -config formal/tla/MCAppend_unconditional_fixed.cfg  formal/tla/DcbAppend.tla
$J -config formal/tla/MCAppend_conformance.cfg          formal/tla/DcbAppend.tla
$J -config formal/tla/MCAppend_conformance_fixed.cfg    formal/tla/DcbAppend.tla
$J -config formal/tla/MCAppend_illegalcommit.cfg        formal/tla/DcbAppend.tla

# --- Token claim ----------------------------------------------------------
$J -config formal/tla/MCClaim_noskew.cfg                 formal/tla/TokenClaim.tla
$J -config formal/tla/MCClaim_skew_below_margin.cfg      formal/tla/TokenClaim.tla
$J -config formal/tla/MCClaim_skew_below_margin_fixed.cfg formal/tla/TokenClaim.tla
$J -config formal/tla/MCClaim_skew_bounded_by_skew.cfg   formal/tla/TokenClaim.tla
$J -config formal/tla/MCClaim_skew_double.cfg            formal/tla/TokenClaim.tla
$J -config formal/tla/MCClaim_skew_double_tight.cfg      formal/tla/TokenClaim.tla
$J -config formal/tla/MCClaim_live.cfg                   formal/tla/TokenClaim.tla
```

## What each run produced

Measured on the sweep of 2026-07-28, OpenJDK 23 on macOS, 8 workers. `states`
is distinct states; `depth` is the depth of the complete state graph search.

| Configuration | Property | Result | States | Depth |
|---|---|---|---|---|
| `Sanity.cfg` | `NeverExceedsLimit` | No error | 4 | 4 |
| `MCAppend_safe.cfg` | all five, plus `TypeOK` | **No error** | 2784 | 9 |
| `MCAppend_unconditional.cfg` | `UnconditionalAppendNeverRejected` | **VIOLATED** | 314 | 6 |
| `MCAppend_unconditional_fixed.cfg` | `UnconditionalAppendNeverRejected` | No error | 2784 | 9 |
| `MCAppend_conformance.cfg` | `AppendConformsToDcbModel` | **VIOLATED** | 295 | 6 |
| `MCAppend_conformance_fixed.cfg` | `AppendConformsToDcbModel` | No error | 2784 | 9 |
| `MCAppend_illegalcommit.cfg` | `AppendIsLegalIffNoMatchInScanRange`, `CommitMarkerNeverRegresses` | No error | 5532 | 11 |
| `MCClaim_noskew.cfg` | `AtMostOneSegmentOwner` | No error | 1030 | 15 |
| `MCClaim_skew_below_margin.cfg` | `AtMostOneSegmentOwner` | **VIOLATED** | 243 | 12 |
| `MCClaim_skew_below_margin_fixed.cfg` | `AtMostOneSegmentOwner` | No error | 308 | 22 |
| `MCClaim_skew_bounded_by_skew.cfg` | `AtMostOneSegmentOwner` | No error | 1506 | 16 |
| `MCClaim_skew_double.cfg` | `AtMostOneSegmentOwner` | No error | 4838 | 16 |
| `MCClaim_skew_double_tight.cfg` | `AtMostOneSegmentOwner` | **VIOLATED** | 69 | 8 |
| `MCClaim_live.cfg` | `ClaimEventuallyAvailable` (temporal) | No error | 7658 | 20 |
| `MCAppend_crosscheck.cfg` | `ProbeTypeOK`; emits 960 cases | No error | 40 | 4 |

Every run finished in under two seconds.

**How to read the state counts.** A "No error" run explored its whole reachable
state space, so its count is exact and reproducible. A "VIOLATED" run stopped at
the first counterexample, and with several workers the number of states explored
before that varies between invocations. The counterexample itself does not.

## Model 1: DCB append

### Bounds TLC checked

Two writers. Three distinct events over two tags and two types. Four boundaries,
one of them a two-criterion disjunction. Three batches, one of two events. At
most three stored events. That is what was checked; nothing here is a statement
about an unbounded store or an unbounded number of writers.

### What it abstracts away

- **Concurrency inside one append.** The conflict check and the commit are one
  atomic step. That is not a convenience: it is exactly what the executable
  reference model does, and that model's own Javadoc says so -- "deliberately
  sequential: operations take effect one at a time ... The model says nothing
  about what a concurrent reader may observe while a batch is being committed;
  that is a separate property, checked against the real engine rather than
  against this model." What is **not** abstracted away is the deferral between
  sourcing and appending: two writers may source against the same head and
  append in either order, which is the race the protocol exists to arbitrate.
- **Time, transactions, batching across transactions, the read side, delivery,
  tokens.** Nothing here has a clock or a consumer.
- **Event identity.** Events come from a fixed pool; two appends of the same
  pool entry are indistinguishable. The protocol's decisions depend on an
  event's tags and type and on nothing else.
- **The sourcing side.** Only appends are modelled. Of the reference model's
  eleven rules, `SourceReturnsMatchingEventsFromStartAscending` is out of scope,
  and `SourceMarkerIsStoreHeadAtSourceTime` is present as the assignment the
  `Source` action makes rather than as a checkable invariant.
- **Four matching rules are definitions, not invariants.**
  `CriterionTagsMatchByContainsAll`,
  `CriterionTypesMatchByMembershipOrAnyWhenEmpty`,
  `CriteriaMatchIsDisjunctionOverCriteria` and
  `ConflictScanCoversPositionsAtOrAfterMarker` are the transition relation
  itself, so there is nothing for an invariant to add. They are what the
  cross-check tests, over a larger domain than the reachable state space.

### The flag, and the violated/fixed pair

`INFINITY_IS_ORIGIN` is `FALSE` for an engine that speaks the boundary protocol
and `TRUE` for the aggregate-based engine, which resolves ORIGIN and INFINITY to
the same empty set of aggregate positions
(`AggregateBasedConsistencyMarker.java:72-74`) and therefore cannot tell "append
with no condition" from "append only if this boundary is empty".

With the flag on, TLC produces this counterexample in five states:

```
State 1: store = <<>>,  both writers idle
State 2: Source(w1, boundary 1)          w1 anchors at head 0
State 3: SourceNothing(w2)               w2 takes marker INFINITY (= 4 here)
State 4: TryAppend(w1, <<1>>)            accepted; store = <<1>>
State 5: TryAppend(w2, <<1>>)            REJECTED
         uncondRejected = TRUE, conforms = FALSE
```

State 5 is the finding: `w2` asked for no consistency at all and the engine
rejected it as conflicting, because the collapsed marker made it scan a store
that has an event in it. `MCAppend_unconditional_fixed.cfg` is the same run with
the flag off and reports no error, which is what makes the pair a demonstration
rather than an assertion.

`MCAppend_conformance.cfg` is the same divergence seen by the primary oracle
instead: the engine's verdict and the reference model's verdict disagree, which
is precisely what a replayed history reports.

### One result the model settles that the suite could not

`MCAppend_illegalcommit.cfg` runs the divergent engine and checks
`AppendIsLegalIffNoMatchInScanRange` and `CommitMarkerNeverRegresses`. Both hold
across all 5532 states. **The marker collapse is over-rejection only**: it never
accepts an append the protocol forbids, and it never lets a reported marker go
backwards. A suite run cannot establish that -- it can only report the
divergences it happened to produce.

## Model 2: token claim

### Bounds TLC checked

Two nodes, one of them with a clock that reads `SKEW` ticks ahead. One segment.
A claim timeout of 5 ticks and a refresh interval of 1 tick, which is the same
5:1 ratio as the simulated arms' 2000 ms claim timeout and 400 ms extension
threshold. A clock bounded at 10 ticks for the safety runs and 12 for the
liveness run. At most one crash, and only on the liveness run.

### What it abstracts away

- **Segments.** One. The invariant is stated per segment, so one segment is
  enough to break it and more would only multiply states.
- **Everything being processed.** No events, batches, or token positions. A
  refresh stands for both an explicit claim extension and the token write that
  refreshes the row as a side effect, because the row cannot tell them apart
  either.
- **A skewed node's writes.** `SKEW` shifts the comparison a node performs, not
  the timestamp it writes. That is the same approximation the simulated arms
  make and for the same reason: `stamp + timeout < now + delta` is the same
  inequality as `stamp + (timeout - delta) < now`, so skewing the comparison
  reproduces a skewed node's decisions exactly.
- **Wall-clock time.** Ticks. The ratio between the claim timeout and the
  refresh interval is what the ticks preserve, because that ratio is what the
  protocol's behaviour turns on.
- **Crashes on the safety runs.** `MaxCrashes` is 0 there, so the skew results
  isolate the skew mechanism. A crashed node's derived interval stays open until
  expiry, because neither the losing node nor the oracle is told about a crash --
  correct for the oracle, but it would mix a harmless overlap (with a node that
  cannot process anything) into a measurement about a harmful one.

### Where the model agrees with the measurement, and where it does not

The simulated arms measured three things about clock skew. The model confirms
two and contradicts the third.

**Confirmed: the overlap is bounded by the skew.** With the forgiven overlap set
equal to the skew, `MCClaim_skew_bounded_by_skew.cfg` reports no error across
1506 states. A node reading `delta` ahead can take a claim at most `delta`
before it would have lapsed anyway, so the overlap can never exceed `delta`.

**Confirmed: the overlap also saturates at one claim timeout.** At a skew of
twice the claim timeout, `MCClaim_skew_double.cfg` holds with the forgiven
overlap set to one claim timeout (4838 states), and
`MCClaim_skew_double_tight.cfg` violates with it set one tick lower. So the
overlap reaches exactly one claim timeout and never exceeds it, however far
ahead the thief's clock reads. Its counterexample is three states long: both
nodes claim at tick 0, and the second node's clock is far enough ahead that the
first node's fresh row already looks expired.

**Contradicted: a skew below the margin between the claim timeout and the
refresh interval is not invisible.** The measurement's own numbers already
hinted at this -- a skew of 1000 ms against a margin of 1600 ms produced
overlaps of 964 to 992 ms rather than none -- and the model settles why.
`MCClaim_skew_below_margin.cfg` runs a skew of 2 ticks against a margin of 4 and
TLC finds a violation in twelve steps:

```
State 2: Claim(n1)      n1 takes the row at tick 0; rowStamp = 0
States 3-6: Tick        the clock reaches 4; n1 has NOT refreshed
State 7: Claim(n2)      n2 reads tick 6, sees 0 + 5 < 6, and steals
                        n1's interval [0, 5], n2's interval [4, 9], overlap 1
```

The steal needs the row to be older than `claimTimeout - skew`, which is 3
ticks. The refresh interval is 1 tick, so a *punctual* owner never lets that
happen -- and `MCClaim_skew_below_margin_fixed.cfg`, which forbids the clock to
advance past the point at which a running owner owes a refresh, reports no error
across 308 states. **The margin is a bound on a punctual owner, not on the
protocol.** Nothing makes an owner punctual: refreshing is work a scheduled
thread does when it is scheduled, and one missed window is enough.

That result is recorded against finding F-10 in
[`../FINDINGS.adoc`](../FINDINGS.adoc), because it changes what that finding's
candidate fix should say. Documenting `claimTimeout - refreshInterval` as the
tolerated skew would document a bound the framework does not enforce.

### Liveness

`ClaimEventuallyAvailable` -- a segment whose owner is not running is eventually
claimed by a node that is -- holds under fairness on the clock and on claiming,
with one crash, across 7658 states. Clocks are in step for that run: the
question is whether a lost process wedges a segment, not whether skew widens an
overlap.

Because the clock is bounded, what holds is "within the model's horizon". That
is the same shape of statement the suite's own declared liveness horizon makes,
and the `Crash` action is guarded so that a crash cannot happen with less than a
claim timeout left -- otherwise the model's horizon would be reported as a
liveness defect.

## Cross-check: the two models of the append protocol against each other

`DcbStoreModel` (Java) and `DcbRules.tla` are supposed to encode the same rules.
Checking that by eye is not checking it.

**Direction: TLC to Java.** `DcbCrossCheck.tla` walks every store of at most
`MaxLen` events drawn from the event pool, and for each one prints the reference
model's decision for every marker from ORIGIN to the maximum head plus INFINITY,
crossed with every boundary. It `EXTENDS DcbRules`, so the operators it evaluates
are literally the operators `DcbAppend` checks, over literally the same pool.
The domain is deliberately larger than the append protocol's reachable state
space: a store the protocol would never produce is still a store the Java model
must decide the same way.

```sh
java -cp formal/tla/tools/tla2tools.jar tlc2.TLC -workers 1 -metadir formal/tla/states \
    -config formal/tla/MCAppend_crosscheck.cfg formal/tla/DcbCrossCheck.tla 2>/dev/null \
  | java -cp simulation/target/classes formal/tla/crosscheck/CrossCheck.java
```

`simulation/target/classes` has to exist; build it with
`./mvnw -q -Phunt -pl simulation -am test-compile` (or any earlier build).

**Result:**

```
cases=960 agreed=960 disagreed=0
```

960 is 40 stores times 6 markers times 4 boundaries, which is the whole domain,
not a sample.

**It can fail.** A cross-check that cannot fail proves nothing, so it was
verified against a deliberate mutation: swapping two boundaries in the Java pool
only produces `cases=960 agreed=874 disagreed=86`, with the disagreeing cases
printed. The mutation was reverted and the clean result reconfirmed.

**What the cross-check does not cover.** Only the accept-or-reject decision. The
position assignment and the reported marker are checked inside the TLA+ model
(`CommitPositionsAndMarkerFollowTheRules`) and inside `DcbStoreModelTest`
separately; they are not compared across the two. Neither is the sourcing side.

## The MachineName bridge, stated exactly

Two of the operators below carry a registry MachineName and its statement
verbatim. The rest carry a reference-model rule name and its statement verbatim.
Nothing is worded differently from the Java it is bridged to.

| TLA+ operator | Source of the wording | Java it is bridged to | Configuration |
|---|---|---|---|
| `AppendConformsToDcbModel` | registry MachineName | `ModelConformanceChecker.APPEND_CONFORMS_TO_DCB_MODEL_STATEMENT` | `MCAppend_conformance.cfg` / `_fixed` |
| `UnconditionalAppendNeverRejected` | registry MachineName | `AppendOutcomeChecker.UNCONDITIONAL_APPEND_NEVER_REJECTED_STATEMENT` | `MCAppend_unconditional.cfg` / `_fixed` |
| `AppendIsLegalIffNoMatchInScanRange` | reference-model rule | `DcbStoreModel.Rule.APPEND_IS_LEGAL_IFF_NO_MATCH_IN_SCAN_RANGE` | `MCAppend_illegalcommit.cfg`, `MCAppend_safe.cfg` |
| `CommitPositionsAndMarkerFollowTheRules` | two reference-model rules, quoted in the operator's comment | `DcbStoreModel` commit path, pinned by `DcbStoreModelTest` | `MCAppend_safe.cfg` |
| `CommitMarkerNeverRegresses` | the monotonicity the commit-marker rule implies | `DcbStoreModel.head()` growing only | `MCAppend_safe.cfg`, `MCAppend_illegalcommit.cfg` |
| `AtMostOneSegmentOwner` | registry MachineName, parameterised by `SKEW_ALLOWANCE` | `OwnershipChecker.AT_MOST_ONE_SEGMENT_OWNER_STATEMENT` | all six `MCClaim` safety configurations |
| `ClaimEventuallyAvailable` | **model only** -- no registry MachineName, no checker | none. What a run observes is the consequence, `NoCommittedEventGoesUndelivered` | `MCClaim_live.cfg` |

### Three names the plan promised that do not exist

The plan's architecture section named five invariants for these two models.
`AtMostOneSegmentOwner` is a registry MachineName and is modelled as one. The
other four are not registry MachineNames, and rather than inventing registry
entries for them, here is what each turned out to mean:

| Name in the plan | What exists instead |
|---|---|
| `AppendRejectedAfterMarker` | Not an invariant. It is the name of two **scenarios**, `dcb_append_rejected_after_marker_under_contention` and `..._single_writer`. The rule those scenarios exercise is `AppendIsLegalIffNoMatchInScanRange`, which is modelled. |
| `NoConflictingCommits` | The plan's own `DcbConflictChecker`, which was never built: design commitment D1 replaced it with the reference-model oracle. Modelled as `AppendIsLegalIffNoMatchInScanRange` checked over committed appends, which is the same statement. |
| `MarkerMonotonic` | Modelled as `CommitMarkerNeverRegresses`, and its rule half as `CommitPositionsAndMarkerFollowTheRules`. |
| `ClaimEventuallyAvailable` | Nothing. Modelled here and labelled model-only in the table above. |

### Registry invariants with no model, and why

Of the twenty MachineNames in the registry, three are modelled --
`AppendConformsToDcbModel` and `UnconditionalAppendNeverRejected` in the append
model, `AtMostOneSegmentOwner` in the claim model. The other seventeen are not,
and the reasons fall into four groups.

- **They are about a consumer, and neither model has one.**
  `NoVisibilityBeforeCommit`, `RolledBackEventsNeverObservable`,
  `SequenceKeyOrderPreserved`, `NoCommittedEventGoesUndelivered`,
  `DuplicateDeliveryOnlyInsideRecoveryWindow`,
  `CommittedEventDeliveredWithinHorizon`,
  `DeliveryAttributedToSegmentOwner`. Adding delivery to either model is a third
  model, not an extension of these two.
- **They are about durable progress, and neither model has a token position.**
  `StoredTokenNeverRegresses`, `StoredTokenCoversDeliveredEvents`,
  `ClaimHandoverRewindsAtMostOneBatch`. The claim model has claims but no
  positions; positions would double its state and answer a different question.
- **They are properties of the workload or the harness rather than of a
  protocol.** `LedgerConservesTotalBalance`, `LedgerBalanceNeverNegative`,
  `ProjectionMatchesFoldOfCommittedEvents`, `AcceptedCommandCompletes`,
  `DeclaredFaultsLand`.
- **Two are about a store keeping what it said it kept.**
  `AcknowledgedAppendIsDurable` and `RejectedAppendLeavesNoEvents` are decided
  against an authoritative scan of a real store after a run has quiesced. The
  append model has no crash and no scan; `RejectedAppendLeavesStoreUnchanged`,
  the reference-model rule underneath the second one, is encoded as the shape of
  the `TryAppend` action.

## A trap worth knowing before editing either model

In TLA+, `/\` and `\/` bind **looser** than `=`. So

```
x' = someLatch \/ someCondition
```

parses as `(x' = someLatch) \/ someCondition`, and

```
x' = someLatch /\ someCondition
```

parses as `(x' = someLatch) /\ someCondition` -- an assignment plus a **guard**.
The second form silently disables the very transition the latch exists to
record, and it surfaces as `Error: Deadlock reached` in a state that looks
perfectly able to move on, not as a parse error and not as a failed invariant.
It cost an hour here. Every latch assignment in `DcbAppend.tla` therefore has a
fully parenthesised right-hand side, and there is a comment saying why.
