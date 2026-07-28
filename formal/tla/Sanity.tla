-------------------------------- MODULE Sanity --------------------------------
(***************************************************************************)
(* A one-variable counter with a trivial invariant.  It proves the TLC and  *)
(* .cfg wiring before either real model is run, so that a failure in a real *)
(* run is a statement about the model rather than about the tool chain.     *)
(***************************************************************************)
EXTENDS Naturals

CONSTANT Limit

VARIABLE n

Init == n = 0

Tick == /\ n < Limit
        /\ n' = n + 1

Idle == /\ n = Limit
        /\ UNCHANGED n

Next == Tick \/ Idle

Spec == Init /\ [][Next]_n

NeverExceedsLimit == n \in 0..Limit

===============================================================================
