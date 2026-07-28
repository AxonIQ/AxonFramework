------------------------------- MODULE DcbRules -------------------------------
(***************************************************************************)
(* The decision rules of the Dynamic Consistency Boundary append protocol,   *)
(* as pure operators over an explicit store, plus the finite pools TLC is    *)
(* run over.  This module declares no variables, so both the protocol state  *)
(* machine (DcbAppend) and the cross-check generator (DcbCrossCheck) bind to *)
(* exactly these operators and exactly these pools.                         *)
(*                                                                          *)
(* The rules this model encodes are the rules of the executable reference    *)
(* model in the simulation module, DcbStoreModel.  Their statements are      *)
(* quoted verbatim from the reference-model rule table in                    *)
(* ../INVARIANTS.md, which is the same wording that appears in that class's  *)
(* Javadoc.  An invariant is only real when the statement is identical in    *)
(* all three places; where it is not, this file says so rather than          *)
(* paraphrasing.                                                            *)
(*                                                                          *)
(* WHAT THIS MODEL DELIBERATELY ABSTRACTS AWAY                              *)
(*                                                                          *)
(*   Concurrency inside one append.  The check and the commit are a single   *)
(*   atomic step, exactly as in DcbStoreModel, whose own Javadoc says it is  *)
(*   "deliberately sequential: operations take effect one at a time, in the  *)
(*   order they are applied ... The model says nothing about what a          *)
(*   concurrent reader may observe while a batch is being committed; that is *)
(*   a separate property, checked against the real engine rather than        *)
(*   against this model."  What is NOT abstracted away is the deferral       *)
(*   between sourcing and appending: two writers may source against the same *)
(*   head and append in either order, which is the race the protocol exists  *)
(*   to arbitrate.                                                          *)
(*                                                                          *)
(*   Time, batching across transactions, the read side, delivery, tokens.    *)
(*   Nothing here has a clock or a consumer.                                *)
(*                                                                          *)
(*   Event identity.  Events are drawn from a fixed finite pool; two         *)
(*   appends of the same pool entry are indistinguishable.  The protocol's   *)
(*   decisions depend on an event's tags and type and on nothing else.       *)
(*                                                                          *)
(*   Sourcing results.  Only the append side is modelled.  The two sourcing  *)
(*   rules of the reference model are not invariants here; the marker a      *)
(*   Source action takes is the store head at that instant, which is that    *)
(*   half of the protocol reduced to an assignment.                          *)
(*                                                                          *)
(* FLAG                                                                     *)
(*                                                                          *)
(*   INFINITY_IS_ORIGIN - FALSE models an engine that speaks the boundary    *)
(*   protocol: a marker of INFINITY claims no boundary and nothing can       *)
(*   conflict with it.  TRUE models the aggregate-based engine, which        *)
(*   resolves ORIGIN and INFINITY to the same empty set of aggregate         *)
(*   positions and therefore treats "append with no condition" and "append   *)
(*   only if this boundary is empty" as the same request                     *)
(*   (AggregateBasedConsistencyMarker.java:72-74).                           *)
(***************************************************************************)
EXTENDS Integers, Sequences, FiniteSets, TLC

CONSTANTS
    Writers,            \* set of writers, e.g. {w1, w2}
    Events,             \* tuple of events; an event is [tags |-> SUBSET STRING,
                        \*   type |-> STRING].  Defined below.
    Boundaries,         \* tuple of boundaries; a boundary is a set of criteria and a
                        \*   criterion is [tags |-> SUBSET STRING, types |-> SUBSET STRING].
                        \*   Boundaries[1] must be the empty boundary, which is both
                        \*   AnyEvent and the boundary AppendCondition.none() carries.
                        \*   Defined below.
    Batches,            \* tuple of batches; a batch is a non-empty sequence of indices
                        \*   into Events, in offer order.  Defined below.
    MaxLen,             \* bound on the number of stored events
    INFINITY_IS_ORIGIN  \* BOOLEAN, see above

\* The two marker sentinels.  ORIGIN is position -1, exactly as the framework's
\* global-index marker resolves it, so a scan from ORIGIN covers the whole store.
\* INFINITY sits above every reachable head, so no real marker can equal it.
ORIGIN   == -1
INFINITY == MaxLen + 1

EventIdx    == 1..Len(Events)
BoundaryIdx == 1..Len(Boundaries)
BatchIdx    == 1..Len(Batches)

\* The empty boundary, which every unconditional append carries.
NoBoundary == 1

----------------------------------------------------------------------------
(* THE RULES, as pure operators over an explicit store.                     *)
(*                                                                          *)
(* Written as functions of their arguments rather than of the state so that  *)
(* the cross-check module can evaluate exactly these operators over cases    *)
(* the reachable state space does not contain.                              *)

\* CriterionTagsMatchByContainsAll: "An event matches a criterion only when it
\* carries every tag the criterion names."
CriterionTagsMatch(crit, ev) == crit.tags \subseteq ev.tags

\* CriterionTypesMatchByMembershipOrAnyWhenEmpty: "A criterion naming types
\* matches only those types; a criterion naming none matches any type."
CriterionTypesMatch(crit, ev) == crit.types = {} \/ ev.type \in crit.types

CriterionMatches(crit, ev) == CriterionTagsMatch(crit, ev) /\ CriterionTypesMatch(crit, ev)

\* CriteriaMatchIsDisjunctionOverCriteria: "A boundary matches when any of its
\* criteria match; an empty boundary matches everything."
BoundaryMatches(crits, ev) == crits = {} \/ \E c \in crits : CriterionMatches(c, ev)

\* Position of the i-th stored entry is i-1, so that an empty store has head 0
\* and the first stored event sits at position 0, as in the reference model.
EventAt(s, pos) == Events[s[pos + 1]]

\* ConflictScanCoversPositionsAtOrAfterMarker: "The conflict scan covers stored
\* events at positions greater than or equal to the marker; ORIGIN resolves to -1
\* and therefore covers the whole store."
ScanRange(s, m) == { pos \in 0..(Len(s) - 1) : pos >= m }

MatchInScanRange(s, m, bIdx) ==
    \E pos \in ScanRange(s, m) : BoundaryMatches(Boundaries[bIdx], EventAt(s, pos))

\* MarkerInfinityBypassesConflictCheck: "An append anchored at INFINITY is
\* accepted without scanning."
\* AppendIsLegalIffNoMatchInScanRange: "The append is accepted exactly when the
\* scan finds no match."
\* Together these are the reference model's decision, which is the decision the
\* primary oracle holds a recorded history to.
ModelAccepts(s, m, bIdx) == m = INFINITY \/ ~MatchInScanRange(s, m, bIdx)

\* The marker the engine under test actually scans from.  On an engine that
\* speaks the boundary protocol this is the marker the writer asked for; on the
\* aggregate-based engine an INFINITY marker collapses onto ORIGIN.
EngineMarkerOf(m) == IF INFINITY_IS_ORIGIN /\ m = INFINITY THEN ORIGIN ELSE m

EngineAccepts(s, m, bIdx) ==
    LET em == EngineMarkerOf(m)
    IN  em = INFINITY \/ ~MatchInScanRange(s, em, bIdx)


----------------------------------------------------------------------------
(* THE FINITE POOLS TLC IS RUN OVER.                                        *)
(*                                                                          *)
(* A .cfg file cannot express a record or a set of records, so the pools live *)
(* here and each .cfg substitutes them with "Events <- MCEvents".  They are   *)
(* harness data rather than protocol, and they are in this module rather than *)
(* in a harness of their own so that the protocol model and the cross-check   *)
(* provably bind to the SAME pool.                                          *)
(*                                                                          *)
(* THE BOUNDS TLC ACTUALLY CHECKS: three distinct events over two tags and    *)
(* two types, four boundaries, three batches.  Every result is a statement    *)
(* about these bounds and the MaxLen in the .cfg, not about the general case.  *)

\* Three events over two tags and two types.  Enough for a criterion to match on
\* one tag, to require both, and to discriminate on type.
MCEvents ==
    << [tags |-> {"a"},      type |-> "e1"],
       [tags |-> {"a", "b"}, type |-> "e2"],
       [tags |-> {"b"},      type |-> "e1"] >>

\* Four boundaries.  The first MUST be the empty boundary: it is both AnyEvent
\* and the boundary an unconditional append carries.  The last is a two-criterion
\* disjunction, so the disjunction rule is exercised rather than assumed.
MCBoundaries ==
    << {},
       { [tags |-> {"a"},      types |-> {}] },
       { [tags |-> {"a", "b"}, types |-> {}] },
       { [tags |-> {"a"}, types |-> {"e1"}], [tags |-> {"b"}, types |-> {}] } >>

\* Three batches in offer order.  The third has two events, so the consecutive
\* position rule has something to be true of.
MCBatches == << <<1>>, <<2>>, <<1, 3>> >>

===============================================================================
