------------------------------ MODULE DcbAppend ------------------------------
(***************************************************************************)
(* The append-protocol state machine: writers source against a store head,   *)
(* then offer a batch under the condition they are holding, and the conflict *)
(* check happens at the second moment rather than the first.                 *)
(*                                                                          *)
(* The decision rules, the marker sentinels and the finite pools all live in  *)
(* DcbRules, so that the cross-check module can evaluate the same operators   *)
(* over the same pools without inheriting this module's state.               *)
(***************************************************************************)
EXTENDS DcbRules

----------------------------------------------------------------------------
VARIABLES
    store,          \* Seq of EventIdx: the durable store; position of store[i] is i-1
    pc,             \* [Writers -> {"idle", "sourced"}]
    marker,         \* [Writers -> ORIGIN..INFINITY] the marker the writer is anchored at
    boundary,       \* [Writers -> BoundaryIdx] the boundary the writer sourced under
    \* Watched latches.  Each is set once and never cleared, so the invariant
    \* that reads it is "this never happened" and TLC's counterexample is the
    \* step that made it happen.
    conforms,       \* BOOLEAN: every recorded verdict equalled the model's verdict
    uncondRejected, \* BOOLEAN: some append anchored at INFINITY was rejected
    illegalCommit,  \* BOOLEAN: some accepted append had a match in its scan range
    commitRulesOk,  \* BOOLEAN: every accepted batch took consecutive positions from the
                    \*   head in offer order and reported last position plus one
    markerRegressed,\* BOOLEAN: some accepted append reported a marker at or below the
                    \*   marker an earlier accepted append reported
    lastMarker      \* ORIGIN..INFINITY: the marker the last accepted append reported

vars == <<store, pc, marker, boundary,
          conforms, uncondRejected, illegalCommit, commitRulesOk, markerRegressed, lastMarker>>

TypeOK ==
    /\ store \in Seq(EventIdx)
    /\ Len(store) <= MaxLen
    /\ pc \in [Writers -> {"idle", "sourced"}]
    /\ marker \in [Writers -> (ORIGIN..INFINITY)]
    /\ boundary \in [Writers -> BoundaryIdx]
    /\ conforms \in BOOLEAN
    /\ uncondRejected \in BOOLEAN
    /\ illegalCommit \in BOOLEAN
    /\ commitRulesOk \in BOOLEAN
    /\ markerRegressed \in BOOLEAN
    /\ lastMarker \in (ORIGIN..INFINITY)

Init ==
    /\ store = << >>
    /\ pc = [w \in Writers |-> "idle"]
    /\ marker = [w \in Writers |-> ORIGIN]
    /\ boundary = [w \in Writers |-> NoBoundary]
    /\ conforms = TRUE
    /\ uncondRejected = FALSE
    /\ illegalCommit = FALSE
    /\ commitRulesOk = TRUE
    /\ markerRegressed = FALSE
    /\ lastMarker = ORIGIN

----------------------------------------------------------------------------
(* ACTIONS *)

\* Source(w, b): writer w reads the boundary b and anchors at the store head.
\* SourceMarkerIsStoreHeadAtSourceTime: "The marker a sourcing reports is the
\* store head at the moment it read, independent of the boundary and of what
\* matched."  That rule is this assignment; it is not separately checkable here.
Source(w, b) ==
    /\ pc[w] = "idle"
    /\ pc' = [pc EXCEPT ![w] = "sourced"]
    /\ marker' = [marker EXCEPT ![w] = Len(store)]
    /\ boundary' = [boundary EXCEPT ![w] = b]
    /\ UNCHANGED <<store, conforms, uncondRejected, illegalCommit,
                   commitRulesOk, markerRegressed, lastMarker>>

\* SourceNothing(w): writer w intends an append that takes part in nobody's
\* consistency boundary.  This is AppendCondition.none(): marker INFINITY, no
\* criteria (NoAppendCondition.java:42-44).
SourceNothing(w) ==
    /\ pc[w] = "idle"
    /\ pc' = [pc EXCEPT ![w] = "sourced"]
    /\ marker' = [marker EXCEPT ![w] = INFINITY]
    /\ boundary' = [boundary EXCEPT ![w] = NoBoundary]
    /\ UNCHANGED <<store, conforms, uncondRejected, illegalCommit,
                   commitRulesOk, markerRegressed, lastMarker>>

\* TryAppend(w, k): writer w offers batch k under the condition it is holding.
\* The engine decides; the store follows the engine's decision, and the model's
\* decision is recorded alongside it.  That is exactly what the primary oracle
\* does with a recorded history: it replays the recorded outcomes and asks the
\* reference model what it would have decided at each point.
TryAppend(w, k) ==
    LET batch   == Batches[k]
        m       == marker[w]
        b       == boundary[w]
        engOk   == EngineAccepts(store, m, b)
        modOk   == ModelAccepts(store, m, b)
        \* AcceptedBatchTakesConsecutivePositionsInOfferOrder: "An accepted batch
        \* occupies consecutive positions starting at the store head, assigned in
        \* offer order."
        positions == [ i \in 1..Len(batch) |-> Len(store) + i - 1 ]
        \* CommitMarkerIsLastPositionPlusOne: "The marker an accepted append
        \* reports is one past its last position; an empty batch reports ORIGIN."
        reported  == positions[Len(batch)] + 1
    IN  /\ pc[w] = "sourced"
        /\ Len(store) + Len(batch) <= MaxLen
        /\ pc' = [pc EXCEPT ![w] = "idle"]
        \* Reset the condition to its canonical idle value so that two writers
        \* holding no condition are one state rather than many.
        /\ marker' = [marker EXCEPT ![w] = ORIGIN]
        /\ boundary' = [boundary EXCEPT ![w] = NoBoundary]
        \* RejectedAppendLeavesStoreUnchanged: "A rejected append stores none of
        \* its batch."  The store changes on the accepting branch only.
        /\ store' = IF engOk THEN store \o batch ELSE store
        \* The right-hand side of every latch is fully parenthesised on purpose.
        \* "/\" and "\/" bind LOOSER than "=" in TLA+, so x' = a /\ b parses as
        \* (x' = a) /\ b -- an assignment plus a guard, which silently forbids the
        \* very transition the latch exists to record.
        /\ conforms' = (conforms /\ (engOk = modOk))
        /\ uncondRejected' = (uncondRejected \/ (m = INFINITY /\ ~engOk))
        /\ illegalCommit' = (illegalCommit
                             \/ (engOk /\ m # INFINITY /\ MatchInScanRange(store, m, b)))
        /\ commitRulesOk' = (commitRulesOk
                             /\ (~engOk \/ (positions[1] = Len(store)
                                            /\ reported = Len(store) + Len(batch))))
        /\ markerRegressed' = (markerRegressed \/ (engOk /\ reported <= lastMarker))
        /\ lastMarker' = IF engOk THEN reported ELSE lastMarker

Next ==
    \/ \E w \in Writers, b \in BoundaryIdx : Source(w, b)
    \/ \E w \in Writers : SourceNothing(w)
    \/ \E w \in Writers, k \in BatchIdx : TryAppend(w, k)
    \* Terminal idle: the store is full, so no batch can ever be accepted again
    \* and the run is over.  Stuttering here keeps behaviours infinite and stops
    \* TLC reporting the legitimate terminal state as a deadlock.  It leaves every
    \* variable unchanged, so it cannot affect any invariant.
    \/ /\ Len(store) = MaxLen
       /\ UNCHANGED vars

Spec == Init /\ [][Next]_vars

----------------------------------------------------------------------------
(* INVARIANTS.                                                              *)
(*                                                                          *)
(* Two names below are MachineNames from the registry in ../INVARIANTS.md    *)
(* and carry that registry's statement verbatim.  The rest are rule names    *)
(* from the same file's reference-model rule table and carry those           *)
(* statements verbatim.  Nothing here is worded differently from the Java    *)
(* it is bridged to.                                                        *)

\* AppendConformsToDcbModel (registry MachineName, statement verbatim):
\* "Every append recorded as successful is accepted by the DCB reference model
\* at its point in the history, and every append recorded as rejected is
\* rejected by it."
AppendConformsToDcbModel == conforms

\* UnconditionalAppendNeverRejected (registry MachineName, statement verbatim):
\* "An append made without a consistency condition is never rejected as
\* conflicting."
UnconditionalAppendNeverRejected == ~uncondRejected

\* AppendIsLegalIffNoMatchInScanRange (reference-model rule, statement
\* verbatim): "The append is accepted exactly when the scan finds no match."
\* Checked in the direction that matters for a store's callers: no append the
\* store accepted had a match in the range its own marker named.  The other
\* direction is over-rejection, which AppendConformsToDcbModel catches.
AppendIsLegalIffNoMatchInScanRange == ~illegalCommit

\* AcceptedBatchTakesConsecutivePositionsInOfferOrder and
\* CommitMarkerIsLastPositionPlusOne (reference-model rules, statements
\* verbatim): "An accepted batch occupies consecutive positions starting at the
\* store head, assigned in offer order." / "The marker an accepted append
\* reports is one past its last position; an empty batch reports ORIGIN."
\* These hold by the way the Append action is written, so this is an encoding
\* check: it catches an editing mistake in the model, not a design hole.
CommitPositionsAndMarkerFollowTheRules == commitRulesOk

\* The monotonicity the commit-marker rule implies: consecutive positions from a
\* head that only grows means every accepted append reports a strictly higher
\* marker than the one before.  A store whose marker went backwards would let a
\* writer anchor behind events it had already been told about.
CommitMarkerNeverRegresses == ~markerRegressed

===============================================================================
