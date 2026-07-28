---------------------------- MODULE DcbCrossCheck ----------------------------
(***************************************************************************)
(* Emits the reference model's append decision for every case in a finite    *)
(* domain, so that the same domain can be replayed through the Java          *)
(* DcbStoreModel and the two compared mechanically rather than by eye.       *)
(*                                                                          *)
(* This module EXTENDS DcbRules, so the operators it evaluates are literally  *)
(* the operators DcbAppend checks, over literally the same event pool.  It    *)
(* does not restate a rule; if it did, the cross-check would be comparing a   *)
(* third model against the other two.                                        *)
(*                                                                          *)
(* The domain is every store of at most MaxLen events drawn from the event    *)
(* pool, crossed with every marker from ORIGIN to the maximum head plus       *)
(* INFINITY, crossed with every boundary.  It is deliberately larger than     *)
(* the append protocol's reachable state space: a store the protocol would    *)
(* never permit is still a store the Java model must decide the same way.    *)
(*                                                                          *)
(* Each line is printed as a tuple of integers only, so a parser needs no     *)
(* knowledge of TLA+ syntax:                                                 *)
(*                                                                          *)
(*   << <<s1, ..., sMaxLen>>, marker, boundaryIndex, accepted >>             *)
(*                                                                          *)
(* where an s of 0 means "no event at this position", marker is ORIGIN (-1)   *)
(* through MaxLen or INFINITY (MaxLen+1), and accepted is 1 or 0.            *)
(***************************************************************************)
EXTENDS DcbRules

VARIABLE probeStore

Markers == (ORIGIN..MaxLen) \cup {INFINITY}

\* Fixed-width store encoding, so that a parser can split the integers by
\* position instead of counting delimiters.
Pad(s) == [ i \in 1..MaxLen |-> IF i <= Len(s) THEN s[i] ELSE 0 ]

CInit == probeStore = << >>

\* Grow the probe store by any event, so every sequence up to MaxLen is reached.
Grow(e) ==
    /\ Len(probeStore) < MaxLen
    /\ probeStore' = probeStore \o <<e>>

\* Print one case.  The store does not change, so probing adds no states.
Probe(m, b) ==
    /\ PrintT(<< Pad(probeStore), m, b,
                 IF ModelAccepts(probeStore, m, b) THEN 1 ELSE 0 >>)
    /\ UNCHANGED probeStore

CNext ==
    \/ \E e \in EventIdx : Grow(e)
    \/ \E m \in Markers, b \in BoundaryIdx : Probe(m, b)

CSpec == CInit /\ [][CNext]_probeStore

ProbeTypeOK == probeStore \in Seq(EventIdx) /\ Len(probeStore) <= MaxLen

===============================================================================
