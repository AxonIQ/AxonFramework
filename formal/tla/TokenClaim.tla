------------------------------ MODULE TokenClaim ------------------------------
(***************************************************************************)
(* A design model of the token-claim protocol for one segment: claims,       *)
(* steals, a clock-skew bound, and crash.                                   *)
(*                                                                          *)
(* The arbitration being modelled is a single inequality.  A node may take a *)
(* claim when the row's timestamp plus the claim timeout is behind that       *)
(* node's own reading of the clock:                                         *)
(*                                                                          *)
(*   JdbcTokenEntry.java:202-207   mayClaim / expired                       *)
(*   TokenEntry.java:159-161       the same comparison on the JPA store      *)
(*                                                                          *)
(* The timestamp is written by whichever node last touched the row and the   *)
(* clock is read by whichever node is asking, so the two come from different *)
(* machines in every deployment the token store exists for.  SKEW is how far *)
(* ahead one node's reading runs.                                           *)
(*                                                                          *)
(* HOW INTERVALS ARE DERIVED, and why it matters that it is done this way.   *)
(*                                                                          *)
(*   Ownership is not directly observable, so the ownership oracle in the    *)
(*   simulation module derives intervals from recorded claim traffic, and     *)
(*   this model derives them the same way, from OwnershipChecker's own        *)
(*   Javadoc: "A granted claim opens an interval; a granted extension or      *)
(*   token write refreshes it ...; a release closes it; and, failing all      *)
(*   four, it closes when the store's own rule says the claim expired, one    *)
(*   claim timeout after the last time its owner refreshed it."              *)
(*                                                                          *)
(*   The consequence worth being explicit about: a node that loses its row    *)
(*   to a thief keeps an open interval until its own claim would have lapsed. *)
(*   That is not an artefact.  Neither the oracle nor the losing node knows   *)
(*   the row changed hands, and the framework's own answer to a stolen claim  *)
(*   is that the loser discovers it when its next token write fails.          *)
(*                                                                          *)
(* WHAT THIS MODEL DELIBERATELY ABSTRACTS AWAY                              *)
(*                                                                          *)
(*   Segments.  One segment.  The invariant is stated per segment, so one     *)
(*   segment is enough to break it and more would only multiply states.       *)
(*                                                                          *)
(*   Events, batches, delivery, tokens as positions.  Nothing is processed    *)
(*   here.  A refresh stands for both an explicit claim extension and the     *)
(*   token write that refreshes the row as a side effect; the model does not  *)
(*   distinguish them because the row cannot either.                          *)
(*                                                                          *)
(*   A skewed node's writes.  SKEW shifts the comparison a node performs and  *)
(*   not the timestamp it writes.  That is the same approximation the         *)
(*   simulated arms make, and for the same reason: shortening one node's      *)
(*   claim timeout by delta reproduces exactly the decisions of a node whose  *)
(*   clock reads delta ahead, because the inequality                          *)
(*   stamp + timeout < now + delta is the inequality                          *)
(*   stamp + (timeout - delta) < now.                                        *)
(*                                                                          *)
(*   Wall-clock time.  Time is an integer tick count bounded by MaxTime.      *)
(*   Every duration in a .cfg is in ticks, and the ratio between the claim    *)
(*   timeout and the refresh interval is what the ticks are chosen to         *)
(*   preserve, because that ratio is what the protocol's behaviour turns on.  *)
(*                                                                          *)
(* FLAG                                                                     *)
(*                                                                          *)
(*   GUARANTEED_REFRESH - FALSE models an owner that refreshes its row when    *)
(*   it gets round to it, which is what a scheduled thread on a loaded        *)
(*   machine does.  TRUE models an owner that is guaranteed to refresh before *)
(*   its row ages past REFRESH_INTERVAL, by forbidding the clock to advance   *)
(*   past that point while the owner is running.  The flag exists because     *)
(*   the difference between the two is the difference between a bound that    *)
(*   can be documented and one that cannot.                                  *)
(***************************************************************************)
EXTENDS Integers, FiniteSets, TLC

CONSTANTS
    Nodes,              \* set of nodes, e.g. {n1, n2}
    SkewedNode,         \* the one node whose clock reads SKEW ticks ahead
    SKEW,               \* how far ahead that node's reading of the clock runs, in ticks
    CLAIM_TIMEOUT,      \* how long a claim survives without a refresh, in ticks
    REFRESH_INTERVAL,   \* how long an owner may go without refreshing, in ticks, when
                        \*   GUARANTEED_REFRESH holds it to that
    SKEW_ALLOWANCE,     \* the overlap the ownership oracle forgives, in ticks.  This is
                        \*   the parameter the invariant is stated in terms of; the suite's
                        \*   own arms set it from HuntTimescale.withSkewAllowance
    MaxTime,            \* bound on the logical clock
    MaxCrashes,         \* bound on the number of crashes
    GUARANTEED_REFRESH  \* BOOLEAN, see above

\* No node holds the row.
NoNode == "none"

\* No interval is open for this node.
NoInterval == -1

Min(a, b) == IF a < b THEN a ELSE b
Max(a, b) == IF a > b THEN a ELSE b

VARIABLES
    now,        \* the true logical clock
    rowOwner,   \* Nodes \cup {NoNode}: the node named in the token row
    rowStamp,   \* 0..MaxTime: the clock reading stamped in the row by whoever last touched it
    up,         \* [Nodes -> BOOLEAN]: is this node's process running
    ivStart,    \* [Nodes -> NoInterval..MaxTime]: when this node's current interval opened
    ivRefresh,  \* [Nodes -> NoInterval..MaxTime]: when it last refreshed inside that interval
    crashes     \* 0..MaxCrashes

vars == <<now, rowOwner, rowStamp, up, ivStart, ivRefresh, crashes>>

----------------------------------------------------------------------------
\* The clock as each node reads it.  One node reads SKEW ticks ahead.
Offset(n) == IF n = SkewedNode THEN SKEW ELSE 0
SeenNow(n) == now + Offset(n)

\* The store's own arbitration rule, evaluated with the asking node's clock.
RowExpiredAsSeenBy(n) == rowStamp + CLAIM_TIMEOUT < SeenNow(n)

\* An interval closes one claim timeout after its last refresh, unless something
\* closed it earlier.
IvEnd(n) == ivRefresh[n] + CLAIM_TIMEOUT

\* Is this node's derived interval open at the current instant?
IvOpen(n) == ivStart[n] # NoInterval /\ IvEnd(n) >= now

\* The length of the intersection of two nodes' intervals.  Both endpoints are
\* fixed once a steal has happened, so this does not drift as the clock advances.
Overlap(n, m) == Min(IvEnd(n), IvEnd(m)) - Max(ivStart[n], ivStart[m])

\* The owner is behind on its refresh and GUARANTEED_REFRESH will not let the
\* clock move until it catches up.
OwnerOwesRefresh ==
    /\ GUARANTEED_REFRESH
    /\ rowOwner # NoNode
    /\ up[rowOwner]
    /\ now - rowStamp >= REFRESH_INTERVAL

TypeOK ==
    /\ now \in 0..MaxTime
    /\ rowOwner \in Nodes \cup {NoNode}
    /\ rowStamp \in 0..MaxTime
    /\ up \in [Nodes -> BOOLEAN]
    /\ ivStart \in [Nodes -> NoInterval..MaxTime]
    /\ ivRefresh \in [Nodes -> NoInterval..MaxTime]
    /\ crashes \in 0..MaxCrashes

Init ==
    /\ now = 0
    /\ rowOwner = NoNode
    /\ rowStamp = 0
    /\ up = [n \in Nodes |-> TRUE]
    /\ ivStart = [n \in Nodes |-> NoInterval]
    /\ ivRefresh = [n \in Nodes |-> NoInterval]
    /\ crashes = 0

----------------------------------------------------------------------------
(* ACTIONS *)

\* Tick: the clock advances.  Under GUARANTEED_REFRESH it cannot advance past
\* the point at which a running owner owes a refresh.
Tick ==
    /\ now < MaxTime
    /\ ~OwnerOwesRefresh
    /\ now' = now + 1
    /\ UNCHANGED <<rowOwner, rowStamp, up, ivStart, ivRefresh, crashes>>

\* Refresh(n): the owner re-stamps its row.  This stands for a claim extension
\* and for the token write that refreshes the row in the same statement, which
\* the row cannot tell apart.  It is also how an owner re-takes its own claim,
\* expired or not.
Refresh(n) ==
    /\ up[n]
    /\ rowOwner = n
    /\ rowStamp' = now
    /\ ivRefresh' = [ivRefresh EXCEPT ![n] = now]
    \* A refresh whose interval had already lapsed opens a fresh one rather than
    \* extending the stale one: treating it as one long interval would manufacture
    \* an overlap out of an ordinary re-claim.
    /\ ivStart' = [ivStart EXCEPT ![n] = IF IvOpen(n) THEN ivStart[n] ELSE now]
    /\ UNCHANGED <<now, rowOwner, up, crashes>>

\* Claim(n): n takes the row.  The store grants it when the row is unowned or
\* when n's own reading of the clock says the claim has expired.  The previous
\* owner's interval is deliberately left open: nothing told it, and nothing
\* tells the oracle either.
Claim(n) ==
    /\ up[n]
    /\ rowOwner # n
    /\ (rowOwner = NoNode \/ RowExpiredAsSeenBy(n))
    /\ rowOwner' = n
    /\ rowStamp' = now
    /\ ivStart' = [ivStart EXCEPT ![n] = now]
    /\ ivRefresh' = [ivRefresh EXCEPT ![n] = now]
    /\ UNCHANGED <<now, up, crashes>>

\* Release(n): an orderly shutdown hands the row back and closes the interval.
Release(n) ==
    /\ up[n]
    /\ rowOwner = n
    /\ rowOwner' = NoNode
    /\ ivStart' = [ivStart EXCEPT ![n] = NoInterval]
    /\ ivRefresh' = [ivRefresh EXCEPT ![n] = NoInterval]
    /\ UNCHANGED <<now, rowStamp, up, crashes>>

\* Crash(n): the process is lost.  The row is NOT handed back -- a dead process
\* releases nothing -- so the claim stands until it expires.  Guarded so that the
\* clock still has room for the row to expire and be taken, because a crash after
\* that point would report the model's horizon as a liveness defect.
Crash(n) ==
    /\ up[n]
    /\ crashes < MaxCrashes
    /\ now + CLAIM_TIMEOUT + 1 <= MaxTime
    /\ up' = [up EXCEPT ![n] = FALSE]
    /\ crashes' = crashes + 1
    /\ UNCHANGED <<now, rowOwner, rowStamp, ivStart, ivRefresh>>

\* Recover(n): the process comes back.  It holds nothing until it claims or
\* refreshes; if its row survived, Refresh is how it re-takes it.
Recover(n) ==
    /\ ~up[n]
    /\ up' = [up EXCEPT ![n] = TRUE]
    /\ UNCHANGED <<now, rowOwner, rowStamp, ivStart, ivRefresh, crashes>>

Next ==
    \/ Tick
    \/ \E n \in Nodes : Refresh(n)
    \/ \E n \in Nodes : Claim(n)
    \/ \E n \in Nodes : Release(n)
    \/ \E n \in Nodes : Crash(n)
    \/ \E n \in Nodes : Recover(n)

Spec == Init /\ [][Next]_vars

\* Progress actions for the liveness run.  Weak fairness on the clock and on
\* claiming means a segment whose owner died is taken as soon as the store's own
\* rule permits it.  Crash is adversarial and bounded, so it is not made fair.
FairSpec ==
    /\ Spec
    /\ WF_vars(Tick)
    /\ \A n \in Nodes : WF_vars(Claim(n))
    /\ \A n \in Nodes : WF_vars(Recover(n))

----------------------------------------------------------------------------
(* INVARIANTS *)

\* AtMostOneSegmentOwner (registry MachineName, statement verbatim):
\* "For every segment, the intervals during which distinct nodes hold its token
\* claim never overlap by more than the run's declared clock-skew allowance."
\* SKEW_ALLOWANCE is that declared allowance, which is what makes this invariant
\* parameterised by the skew bound rather than by a tolerance the model chose.
AtMostOneSegmentOwner ==
    \A n, m \in Nodes :
        (n # m /\ IvOpen(n) /\ IvOpen(m)) => Overlap(n, m) <= SKEW_ALLOWANCE

\* The widest overlap any pair of intervals currently shows.  Not an invariant:
\* a number reported so that a run which holds can still say how close it came.
WidestOverlap ==
    LET pairs == { Overlap(n, m) : n, m \in { k \in Nodes : IvOpen(k) } }
    IN  IF pairs = {} THEN 0 ELSE CHOOSE x \in pairs : \A y \in pairs : y <= x

OwnerDown == rowOwner # NoNode /\ ~up[rowOwner]
OwnerLive == rowOwner # NoNode /\ up[rowOwner]

\* ClaimEventuallyAvailable (liveness).  A segment whose owner is not running is
\* eventually claimed by a node that is.
\* This name is NOT in the invariant registry and has no checker: no oracle in
\* the suite asserts it directly.  What a run observes instead is the
\* consequence, NoCommittedEventGoesUndelivered -- a segment nobody can claim
\* delivers nothing.  Checked here under FairSpec, and bounded by MaxTime, so
\* what holds is "within the model's horizon", which is the same shape of
\* statement the suite's declared liveness horizon makes.
ClaimEventuallyAvailable == [] (OwnerDown => <> OwnerLive)

===============================================================================
