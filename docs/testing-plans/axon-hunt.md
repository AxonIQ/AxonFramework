# Axon Hunt -- Project-Wide Bug-Hunting Test Suite for Axon Framework 5

**New here? Read `HUNT.md` at the repository root first.** It is the onboarding guide: what the
suite is, the vocabulary, where everything lives, and what to run. This document is the plan,
and it is a lookup rather than a read-through.

Plan slug: `axon-hunt`. Mode: **project-wide** (holistic, claims-driven).
Destination when implementation starts: `docs/testing-plans/axon-hunt.md` on branch
`feature/dst-testing-suite` in a dedicated worktree.

Method: `designing-distributed-system-tests` skill. Inputs: axon-flow-spec `poc/tla_dst`
harness study, AxonFramework test inventory, 40 extracted claims + 10 missing claims,
Jepsen pitfall catalog, graphify knowledge graph of the codebase.

---

## 1. SUT model (one paragraph)

Axon Framework 5 is a Java 21 CQRS/event-sourcing framework. Commands mutate state by
sourcing events from an `EventStore` under a `SourcingCondition` (tag-based
`EventCriteria`), deciding, and appending new events under an `AppendCondition` whose
`ConsistencyMarker` makes conflicting concurrent appends fail
(`AppendEventsTransactionRejectedException`) -- the DCB protocol. Everything runs inside a
phased `ProcessingContext` (PRE_INVOCATION -> ... -> PREPARE_COMMIT -> COMMIT -> AFTER_COMMIT);
events become visible only on commit. Downstream, `PooledStreamingEventProcessor` (PSEP)
streams events from a `StreamableEventSource`, splits work across up to 16 `Segment`s,
each `WorkPackage` holding an exclusive claim on a `TokenStore` token (claim timeout 10s,
steal-on-expiry). Processing progress (token) and handler effects commit in one
UnitOfWork; exactly-once holds only when token and projection share a transactional
resource, else at-least-once. Storage backends: `InMemoryEventStorageEngine` (DCB-native),
`AggregateBasedJpaEventStorageEngine` (no DCB tags, gap-aware global sequence with
gapTimeout=60s/maxGapOffset=10000), Axon Server (DCB-native, via connector from Maven
Central -- module absent in this tree), and commercial
`io.axoniq.framework:PostgresqlEventStorageEngine` (DCB-native Postgres). Observability:
none purpose-built for testing; the suite must record its own operation histories.

## 1b. Claims (spine)

40 claims C1-C40 extracted with sources (see Appendix A, verbatim from the claims-mining
pass). Highest-severity clusters:

- **DCB append safety**: C1 (reject after marker), C2/C3 (none()/ORIGIN semantics),
  C5-C8 (marker derivation, lowerBound merge, deferred conflict check).
- **Commit atomicity/visibility**: C4, C29 (append in PREPARE_COMMIT, visible only after
  COMMIT, rollback discards), C37 (store+bus duality).
- **Ordering**: C10-C12 (append order, per-aggregate seq, global sequence), C32-C34
  (sequencing policies), C13/C14 (gap-aware token, gap timeout).
- **Segment/token membership**: C18/C19 (single-owner claim), C20-C22 (steal semantics,
  nodeId), C23-C25 (split/merge preconditions).
- **Delivery semantics**: C15 (batch+token one tx), C16 (exactly-once iff shared
  resource), C17 (steal => possible duplicate).
- **Replay**: C26-C28 (reset requires all claims, ReplayToken, 5.1+).

### 1c. Missing claims discovered (first-class findings, already)

M1 in-flight effects at claim-steal boundary; M2 gap timeout vs long transactions (silent
skip candidate); M3 cross-node clock-skew bound for claim stealing unstated; M4 TokenStore
unreachable mid-batch; M5 afterCommit/marker failure after durable commit; M6 resume
position after partial sourcing consumption undefined; M7 causal ordering across tags in
one segment; M8 exactly-once with two datasources/XA unspecified; M9 Axon Server
reconnect/redelivery semantics undocumented (connector absent from tree); M10 split/merge
vs concurrent appends unspecified. **These are exactly where production bugs live; the
scenario list targets them deliberately.**

## 2. Scope

In scope: eventsourcing (event store, DCB, storage engines), messaging (PSEP, tokens,
segments, sequencing, ProcessingContext, command bus dispatch as workload driver),
integrationtests infrastructure, real backends (in-memory, Axon Server, PostgreSQL via
JPA engine + optional commercial engine). Out of scope (this iteration): queries/
subscription queries (workload-level only), sagas/process managers (absent), DLQ (absent
from tree -- placeholder invariant only), Spring extension internals, tracing/metrics
extensions, multi-region.

## 2b. Existing-test inventory (condensed; full table in Appendix B)

- 2091 `@Test` methods across 258 messaging test files (A0-verified; the earlier "237" was
  a file-count transcription error). Single-JVM DCB conflict tests live in
  `StorageEngineTestSuite` (10 concurrent append tasks,
  `eventsourcing/src/test/java/org/axonframework/eventsourcing/eventstore/StorageEngineTestSuite.java:484`)
  and `AggregateBasedStorageEngineTestSuite` (4 concurrent append tasks,
  `.../AggregateBasedStorageEngineTestSuite.java:565`), the only two real-thread races in
  either suite; reuse as workload seeds.
- `integrationtests` has a **`TestInfrastructure` strategy** (start/configure/purge/stop)
  with 23 abstract class declarations and 19 concrete leaf ITs under
  `integrationtests/src/test/java/org/axonframework/integrationtests/testsuite/` (A0-verified;
  the earlier "~40" was an estimate), but ONLY `InMemoryTestInfrastructure` exists. No Axon
  Server container anywhere; JPA ITs run HSQLDB in-memory; Testcontainers deps declared but
  dead in `integrationtests` and `eventsourcing`.
- No chaos/fault-injection/crash-recovery/multi-node tests at all. No history-based
  oracles. CI reruns failing tests 5x (`-Dsurefire.rerunFailingTestsCount=5
  -Dfailsafe.rerunFailingTestsCount=5`, set as a CLI flag in
  `.github/workflows/pullrequest.yml:45` and `:52`, `.github/workflows/main.yml:47` and
  `:53`, `.github/workflows/examples.yml:66-67`, NOT in any pom), masking exactly the
  flaky-looking real bugs this suite hunts.

## 3. Architecture of the suite (three layers, one invariant spine)

Lesson from axon-flow-spec tla_dst: the invariant spine (one `MachineName` per property,
verbatim across TLA+ <-> DST assertion <-> docs) is the thing that makes findings
trustworthy. Its weaknesses -- single-node, in-memory only, per-invariant tolerance creep,
workflow-scoped invariants, weak liveness -- dictate this suite's shape.

```
             +--------------------------------------------------+
             |  INVARIANTS.md  (MachineName registry, C-refs)   |
             |  + OperationHistory recorder + Checkers (oracle) |
             +-------+--------------+--------------+------------+
                     |              |              |
        +------------v---+  +-------v--------+  +--v------------------+
        | L1 DST core    |  | L2 multi-node  |  | L3 real infra       |
        | 1 JVM, seeded, |  | sim: N engine  |  | Testcontainers:     |
        | virtual time,  |  | instances, one |  | Axon Server, PG16,  |
        | fault store,   |  | shared store + |  | Toxiproxy nemesis,  |
        | BUGGIFY        |  | token store    |  | kill -9 / restart   |
        | per-PR smoke   |  | per-PR + fuzz  |  | nightly             |
        +----------------+  +----------------+  +---------------------+
                     +-- same workloads, same history format, same checkers --+
```

**L1 -- DST core** (technique: `deterministic-simulation.md`). Seeded single-JVM harness
driving real AF5 components: real `SimpleCommandBus`, real `EventStore` over a
`ControllableEventStorageEngine` wrapper (fault hooks: vanish-next-commit,
duplicate-next-commit, latency, reject), real PSEP with injected deterministic clock +
same-thread/carrier executors, in-memory TokenStore behind a fault wrapper
(claim-denied, store-timeout injections). BUGGIFY points at commit/claim boundaries.
Fuzz: one seed fixes workload shape + fault schedule; failing seed prints reproduce
command; regression seeds pinned.

**L2 -- multi-node simulation** (fixes tla_dst weakness #1). N framework "nodes" (separate
Configurers/processors, same JVM) share one store + one TokenStore. Simulates: token
steal via clock jump past claimTimeout, node crash (drop node, keep store), rebalance,
split/merge under load, competing appends on overlapping tags. Deterministic per seed at
per-node level; global interleaving explicitly fuzzed (schedule axis).

**L3 -- real infrastructure** (fixes weakness #2; techniques:
`chaos-and-fault-injection.md`, `crash-recovery-and-upgrade.md`, `jepsen-and-elle.md`
style histories). Implements the missing `TestInfrastructure` backends:

- `AxonServerTestInfrastructure` -- Testcontainers `axoniq/axonserver` (DCB context),
  connector from Maven Central (module absent in tree -- released artifact used).
- `PostgresTestInfrastructure` -- `postgres:16` + `AggregateBasedJpaEventStorageEngine`
  (+ JDBC/JPA TokenStore on same or separate DB -- both matrix arms).
- `PostgresDcbTestInfrastructure` -- commercial `io.axoniq.framework`
  `PostgresqlEventStorageEngine` (DCB-native Postgres). DECIDED: both Postgres engines
  from day one.

This instantly multiplies the ~40 existing abstract ITs across real backends, then adds
chaos scenarios: every app<->store connection goes through **Toxiproxy** (partition,
latency, bandwidth, half-open); containers get `kill -9` + restart for crash-recovery;
app JVMs get killed mid-batch for token/claim recovery.

**Oracle discipline** (fixes weakness #3 -- no tolerance creep). The PRIMARY oracle is the
model-conformance check (D1) run differentially across backends (D2); property checkers
below are the secondary net. All three layers emit the same **operation history** (JSONL): `{op, invocation_ts, completion_ts, node, payload}`
for append-attempt (condition, tags, outcome incl. UNKNOWN), commit/rollback, claim/
extend/release/steal, delivery (event id, segment, token position, replay flag),
reset/split/merge. Checkers run post-hoc over the history:

- **DcbConflictChecker** -- no two committed appends conflict under their conditions
  (serializability of the DCB protocol per tag-set).
- **VisibilityChecker** -- no delivery of an event without a prior commit; no rolled-back
  event ever delivered.
- **OwnershipChecker** -- per segment, claim intervals never overlap (from claim history,
  with clock-skew allowance = explicit parameter, not silent tolerance).
- **DeliveryChecker** -- per backend mode: exactly-once (shared-tx arm) or
  at-least-once-no-loss (split-store arm); duplicates counted and bounded.
- **OrderChecker** -- per sequencing-policy key, delivery order equals append order.
- **LivenessChecker** -- every committed event delivered within horizon (virtual time in
  L1/L2, wall budget in L3); every accepted command completes.
- Ambiguous outcomes (timeout, connection-drop during commit) recorded as UNKNOWN and
  treated as "may or may not have happened" by checkers -- never silently dropped.

**Formal layer** (technique: `formal-methods-tla.md`). Two small TLA+ models with the
MachineName bridge, in `formal/tla/`, with violated/fixed cfg pairs where a real
finding exists:
- `DcbAppend.tla` -- writers, tags, markers, deferred conflict check. Invariants
  `AppendConformsToDcbModel` and `UnconditionalAppendNeverRejected` (both registry
  MachineNames, statements verbatim) plus three reference-model rules. The
  violated/fixed pair is finding F-14.
- `TokenClaim.tla` -- claims, steals, clock skew bound, crash. `AtMostOneSegmentOwner`
  (registry MachineName, parameterized by the declared skew allowance) and
  `ClaimEventuallyAvailable` (liveness, model-only -- no registry entry, no checker).
  The violated/fixed pair refined finding F-10.

Three names this section originally listed are not registry MachineNames and were not
modelled under those names: `AppendRejectedAfterMarker` names two scenarios rather than
an invariant, `NoConflictingCommits` was the `DcbConflictChecker` that design commitment
D1 replaced with the reference-model oracle, and `MarkerMonotonic` is modelled as
`CommitMarkerNeverRegresses`. `formal/tla/README.md` maps each one and names every
registry invariant that has no model, with the reason.

## 3b. Design commitments -- 14 expert-hardening decisions (binding)

Each entry: WHAT (the commitment), WHY (the failure mode it prevents), HOW (concrete
implementation). These are binding constraints on P0-P5; an implementing agent may not
skip one silently -- deviations get written into FINDINGS.adoc as harness decisions.

**D1. Model-conformance oracle is the SPINE (not an add-on).**
WHAT: an executable reference model (~300 lines) of the DCB event store semantics --
append with condition, conflict rules (tag-AND within criterion, OR across criteria,
marker validity), source, visibility. Every layer replays its operation history against
the model; SUT-observed state must match model state.
WHY: property checkers only catch what we thought of; a reference model catches semantic
drift we didn't enumerate (AWS S3 ShardStore "lightweight formal methods", TigerBeetle
VOPR state checking). It is also the differential base for D2.
HOW: `simulation` main scope: `DcbStoreModel` (pure, deterministic, no framework deps) +
`ModelConformanceChecker` consuming the history JSONL. Built in P0 alongside the
recorder. TLA+ `DcbAppend.tla` and `DcbStoreModel` must encode the same rules --
cross-checked in the TLC-to-Java direction rather than the other way round, because a
TLA+ module can enumerate a whole finite domain and hand it over as integers far more
cheaply than a Java enumeration can be handed to TLC: `formal/tla/DcbCrossCheck.tla`
emits the specification's decision for every case and `formal/tla/crosscheck/CrossCheck.java`
replays each one through `DcbStoreModel`. It is the whole domain, not a sample -- 960 of
960 agreed -- and it was verified to be capable of failing before the clean result was
believed.

**D2. Backend-differential is the attribution strategy.**
WHAT: identical workload + identical history schema + identical checkers run across ALL
backends (in-memory, Axon Server, Postgres-JPA, Postgres-DCB).
WHY: AF is a library; the SUT is library x store protocol. A failure needs attribution:
fails on all backends = core framework logic; fails on one = adapter or store semantics.
Without this, every finding starts a whose-bug argument.
HOW: `TestInfrastructure` is the only backend seam; scenario code is backend-agnostic;
CI matrix runs the same scenario class per backend; FINDINGS.adoc records the
per-backend verdict vector for every finding (e.g. `inmem:PASS axonserver:FAIL pg:PASS`).

**D3. Canonical conservation-law workload: the Ledger.**
WHAT: one default workload for all scenarios -- accounts, DCB-conditioned transfers,
balance projection. Global invariants: sum of balances constant (money neither created
nor destroyed), no balance below zero when the append condition enforced it, projection
converges to fold(committed events).
WHY: conservation laws are the strongest cheap oracle in industry (TigerBeetle
debits==credits): one inequality catches lost events, double-processing, conflict-check
bypass, and replay corruption without knowing which mechanism broke.
HOW: `simulation/.../workload/LedgerWorkload` with seeded generators; scenarios use it
unless they need a special shape; `ConservationChecker` added to the checker set and run
in EVERY scenario regardless of the scenario's primary claim.

**D4. Process PAUSE nemesis (distinct from crash).**
WHAT: SIGSTOP/SIGCONT of app JVMs (L3) and an injected safepoint-stall fake (L1/L2)
lasting longer than claimTimeout.
WHY: the classic production claim-loss bug -- GC pause/VM freeze; owner alive, mid-batch,
token stolen, zombie wakes and commits. kill -9 can never produce it; Jepsen runs pause
on every system for this reason.
HOW: L3: `docker pause`/`kill -STOP` via Testcontainers exec on the app container (apps
run containerized in chaos ITs for this reason); L1/L2: a `PauseFault` that suspends one
node's virtual execution while others progress. Targets: mid-batch, mid-claim-extension,
mid-commit.

**D5. Time compression is a harness-wide config dimension.**
WHAT: every timeout-dependent scenario runs with all framework timeouts compressed
(claimTimeout, claimExtensionThreshold, tokenClaimInterval, gapTimeout scaled to ms) as
the default arm; one realistic-timescale arm exists for nightly.
WHY: the bugs live in the RATIOS (batch>claimTimeout, tx>gapTimeout, pause>extension);
compressed time turns hour-scale races into second-scale ones.
HOW: `HuntTimescale` config record (compressed/realistic) applied via processor/token
store configuration in `TestInfrastructure`; scenario budgets state which arm each tier
uses.

**D6. Fault-composition policy: single -> pairs -> storms, heal-and-settle before verdict.**
WHAT: staged fault schedules; the final oracle pass runs only after all faults healed
and the system drained to quiescence.
WHY: compound-first destroys attribution; verdict-during-fault manufactures false
positives at run boundaries.
HOW: `FaultSchedule` grammar in the harness (phases: warmup, fault window(s), heal,
settle, verdict); smoke tiers = single fault; hardening = pairs; release/fuzz = seeded
storms. Every injected fault logs landing evidence (Toxiproxy API state, container exit
code, fake-clock jump record) or the run is INCONCLUSIVE - never PASS.

**D7. Open-history discipline.**
WHAT: operations still in flight at run end are recorded with outcome UNKNOWN; checkers
treat UNKNOWN as may-or-may-not-have-happened; no checker truncates trailing ops.
WHY: the #1 source of fake findings in history-checked systems is run-boundary
truncation.
HOW: recorder writes invocation and completion as separate records; checker library has
one shared `HistoryView` that resolves op outcomes and exposes unknowns explicitly;
`history-discipline.md` (designing skill references) is the normative schema source.

**D8. Canary validation - the suite must catch planted bugs before it is trusted.**
WHAT: a set of deliberate framework mutations (weaken conflict check, drop a claim
guard, skip token update) applied in scratch commits; the suite MUST go red on each;
results recorded.
WHY: an oracle that never caught a planted bug is decoration; this is how you test the
tests (Jepsen "checker checking").
HOW: `formal/CANARIES.md` documents each mutation (diff snippet, expected failing
invariant, observed result). Run at P1 exit (L1 canaries) and P3 exit (backend
canaries). Canary diffs are never committed - applied, run, reverted; only the doc
persists.

**D9. Lincheck for concurrent primitives.**
WHAT: JetBrains Lincheck linearizability tests for the concurrent building blocks:
in-memory TokenStore, InMemoryEventStorageEngine append/source paths, WorkPackage
buffer/claim interactions where isolable.
WHY: unit-level interleaving exploration finds races system-level DST needs millions of
seeds to hit; it is JVM-native and off-the-shelf.
HOW: `simulation/src/test/.../lincheck/` suite, one dependency
(`org.jetbrains.lincheck`), run in the smoke tier (bounded iterations) and extended in
fuzz tier.

**D10. Elle-convertible history schema.**
WHAT: the JSONL history fields chosen so a converter to Elle's EDN history format is
mechanical (process id, op type invoke/ok/fail/info, value, index).
WHY: keeps the door open to a real transactional-anomaly checker without committing to
Elle integration now (DCB ops map awkwardly to SQL txns; integrate only if isolation
anomalies become the question).
HOW: schema documented in `formal/INVARIANTS.md` appendix; a `HistoryToElle` converter
stub with TODO lands in P0; no Elle dependency yet.

**D11. Coverage-fed seed corpus.**
WHAT: fuzz batches tracked with JaCoCo; seeds that reach new coverage (or new
invariant-state signatures) are retained in a growing corpus replayed nightly.
WHY: unguided fuzz plateaus silently - 1000 seeds re-exploring the same states looks
thorough and is not.
HOW: `-Dhunt.corpus=<dir>`; batch script compares coverage delta per seed; corpus file
checked in (small, text). Full AFL-style guidance is out of scope; this is the cheap
80%.

**D12. Startup/thundering-herd scenario class.**
WHAT: N nodes boot simultaneously against an empty token store: concurrent
`initializeTokenSegments`, 16-segment auto-init, first-claim stampede; also node
join/leave racing split/merge.
WHY: classic first-deploy bug class; C-claims cover steady state, not genesis.
HOW: scenario `S15 concurrent_bootstrap_initializes_segments_exactly_once` (falsifies
C18, initializeTokenSegments contract) in L2 + L3; added to Sec.5 list.

**D13. Contention shape as seeded swarm dimensions.**
WHAT: workload knobs - tag-access distribution (Zipfian hot-key vs uniform), tag
cardinality, criteria-overlap degree between writers, batch size - all pure functions
of the swarm seed.
WHY: state-space coverage comes from distribution shape, not op volume; hot-tag Zipfian
is where conflict-path bugs live.
HOW: `SwarmShape(seed)` record in the workload generator (mirrors tla_dst
`SimulationConfig.swarm`); shape logged into the history header for reproduction.

**D14. Zero-quarantine flake policy (suite constitution).**
WHAT: no @Disabled, no retry-mask, no silent quarantine - ever. Every intermittent
failure is classified: engine bug (reproduce in isolation -> finding), harness bug
(fix + regression-pin), or load artifact (documented with evidence).
WHY: quarantine lists are where distributed-systems test suites go to die; reruns bury
exactly the bugs this suite exists to find.
HOW: written into `formal/INVARIANTS.md` preamble as suite constitution together with:
never patch the engine, judge by exit code, landing evidence required, honest
determinism scoping. CI hunt jobs assert `rerunFailingTestsCount` is absent.

## 4. Failure-mode hypotheses (pitfall-catalog walk)

| Pitfall | Applies | Hypothesis (-> claims) |
|---|---|---|
| 1 lost updates under partition | y | H1 concurrent DCB appends on overlapping tags both commit when conflict check is deferred and the store connection flaps (->C1,C8) |
| 2 stale reads | y | H2 projection lag violates read-your-writes assumptions in subscription-style reads; monotonic per segment only (->C15, M7) |
| 3 replica divergence | maybe | H3 two nodes' projections diverge permanently after steal + replay overlap (->C17,C27) |
| 4 linearizability under timing | y | H4 marker validity broken by commit-order vs global-sequence-order mismatch on JPA engine (->C11,C12) |
| 5 lost acks | y | H5 command returns success but append rolled back on connection drop after server-side commit ambiguity; or error returned yet append durable (->C4,C29, M5) |
| 6 membership races | y | H6 split/merge racing claim-steal loses or double-claims a child segment (->C23-C25, M10) |
| 7 crash-recovery divergence | y | H7 kill -9 during append storm loses acked events or resurrects rolled-back ones on Postgres restart (->C35) |
| 9 seq collision | y | H8 JPA global sequence gap dropped after gapTimeout while a long tx later commits -> event silently never streamed (->C13,C14, M2). Prime bug candidate. |
| 10 change-feed loss/dup | y | H9 Axon Server stream reconnect after partition skips or redelivers events without token reflecting it (->M9) |
| 12 clock skew | y | H10 claim steal with skewed node clocks yields overlapping ownership -> concurrent duplicate processing beyond documented window (->C20, M3) |
| 14 lease expiry under contention | y | H11 slow batch (handler latency injection) starves claim extension past claimTimeout; owner loses claim mid-batch; effects doubled (->C19,C20, M1) |
| 15 idempotency cold cache | maybe | H12 processor restart between handler effect and token store write replays batch -- duplicate effects when stores split (->C16,C17, M4) |
| 16 outbox head-of-line | n/a now | DLQ absent from tree; placeholder invariant `DlqNoHeadOfLineBlock` reserved (->C39) |
| 8 schema migration | deferred | Sec.9 followup (message transformation is 5.2 commercial) |
| 11 cross-shard atomicity | partial | H13 multi-tag append spanning "boundaries" is atomic by design -- verify visible atomically to streaming consumers (->C4,C10) |
| 13 auth divergence | n/a | no auth layer in scope |

## 5. Scenarios (claim-named; each = executable spec)

Format: name (falsifies) | layers/backends | workload | faults | oracle | tiers.
Serious scenarios (safety/durability/idempotency/isolation/ordering/membership) carry
Sec.7.M: model + history + checker + nemesis evidence + ambiguity + reduction. Target test
files live under `simulation/src/test/java/.../scenarios/` (L1/L2) and
`integrationtests/src/test/java/.../chaos/` (L3), `*IT.java` naming for failsafe.

- **S1 `dcb_append_rejected_after_marker_under_contention`** (C1,C5,C6,C8; H1) --
  L1+L2+L3 all backends. Workload: K writers, overlapping tag sets, mixed
  source-then-append and withCriteria/ORIGIN appends. Faults: none (smoke) / latency +
  duplicated-append (hardening). Oracle: DcbConflictChecker. Sec.7.M: model=DCB-serializable
  register per tag-set; nemesis evidence=fault-schedule log. Smoke: 1k commands, 3 seeds.
  Hardening: 100k, 100 seeds. Release: nightly 1000 seeds x 3 backends.
- **S2 `commit_ack_matches_durability_under_partition`** (C4,C29,M5; H5, pitfall 5) --
  L3 AxonServer+Postgres. Workload: append storm. Faults: Toxiproxy cut/half-open exactly
  around commit; app kill between COMMIT and AFTER_COMMIT. Oracle: client verdicts vs
  post-heal authoritative store scan; SUCCESS=>present, ERROR=>absent, UNKNOWN=>either.
  Sec.7.M filled. Release: 30-min soak, partitions every 20-60s.
- **S3 `uncommitted_never_visible_rolledback_never_delivered`** (C4,C29; H13) -- L1+L3.
  Faults: injected handler exception in PREPARE_COMMIT vs COMMIT vs AFTER_COMMIT phases;
  vanish-next-commit. Oracle: VisibilityChecker.
- **S4 `at_most_one_segment_owner_with_skew`** (C18,C19,C20,C22,M3; H10,H11) -- L2 + L3
  Postgres TokenStore. Workload: 2-4 nodes, contended segments. Faults: clock jump past
  claimTimeout, node stall (BUGGIFY sleep at claim-extension), node crash. Oracle:
  OwnershipChecker with explicit skew parameter; DeliveryChecker duplicate window
  bounded + reported. Sec.7.M filled -- this quantifies M1/M3 instead of trusting docs.
- **S5 `exactly_once_when_token_and_projection_share_tx`** (C15,C16; H12) -- L3 Postgres,
  same-DB arm. Faults: app kill -9 mid-batch, restart; DB restart. Oracle: projection ==
  fold(history) exactly once; token monotone. Sec.7.M filled.
- **S6 `at_least_once_no_loss_when_stores_split`** (C16,C17,M4) -- L3 Postgres arm with
  separate token DB. Faults: token-DB outage mid-batch, app crash. Oracle:
  DeliveryChecker no-loss; duplicates counted, must be finite and attributable to
  documented steal/retry windows.
- **S7 `no_event_skipped_by_gap_timeout`** (C13,C14,M2; H8) -- L3 Postgres/JPA. Workload:
  writer A holds a tx open > gapTimeout (60s, and shrunk-config variant), writer B
  streams past it; A commits late. Oracle: LivenessChecker -- A's event eventually
  delivered; else falsified (silent skip). **Highest expected-yield scenario.** Sec.7.M.
- **S8 `replay_sees_full_prefix_and_flags_redelivery`** (C26,C27) -- L2+L3. Reset during
  traffic (must fail while running -- C26 precondition check), then legal reset; oracle:
  post-replay projection == fold(full history); ReplayToken flag correct per delivery.
- **S9 `split_merge_no_loss_no_dup_under_load`** (C23,C24,C25,M10; H6) -- L2 + L3
  AxonServer. Split/merge storm concurrent with appends + a steal. Oracle:
  DeliveryChecker + OrderChecker per sequencing key across segment epochs. Sec.7.M.
- **S10 `sequencing_policy_order_preserved`** (C32,C33,C34; M7 probe) -- L1+L3. Mixed
  policies (Sequential, PerAggregate, custom-key, empty=parallel). Oracle: OrderChecker;
  the empty-Optional arm documents (not asserts) cross-tag causal reordering -> M7
  evidence.
- **S11 `axonserver_stream_resume_no_loss_no_silent_dup`** (M9; H9, pitfall 10) -- L3
  AxonServer. Faults: Toxiproxy partition mid-stream, server restart, connector
  reconnect. Oracle: DeliveryChecker no-loss + token-monotonic; duplicates must
  correspond to token regressions visible in history.
- **S12 `crash_recovery_no_acked_loss_postgres`** (C35; H7, pitfall 7) -- L3. kill -9
  Postgres during append storm (fsync contract), restart, full scan vs acked set. Sec.7.M.
- **S13 `liveness_all_committed_eventually_processed`** (liveness umbrella) -- all layers,
  fault storms with heal; horizon oracle. Non-serious -> Sec.7.M n/a.
- **S14 formal: `DcbAppend.tla` + `TokenClaim.tla`** -- TLC cfgs per invariant, violated/
  fixed pairs when gaps found. Bridged by MachineName to S1/S4 assertions.
- **S15 `concurrent_bootstrap_initializes_segments_exactly_once`** (C18,
  initializeTokenSegments contract; D12) -- L2+L3. N nodes boot simultaneously against an
  empty token store; concurrent segment init, first-claim stampede, join/leave racing
  split/merge. Oracle: exactly 16 segments exist once, OwnershipChecker from t=0.

Sec.7.M.S: no boundary/fairness claims in this iteration (single-tenant framework surface)
-- `not applicable` on all scenarios; noted honestly rather than invented.

## 5b. Coverage adequacy & residual uncertainty

Adequacy: every safety cluster has >=2 independent falsification paths (e.g. C1 via S1
history checker on 3 backends AND TLA model AND S2 partition arm). Claim->scenario matrix
in Appendix C. Residual, accepted for iteration 1: no multi-region/AZ faults (no such
deploys targeted); XA/two-phase datasources (M8) documented not tested; DLQ absent in
tree; query side exercised only as workload; commercial Postgres engine arm contingent on
dependency access; kernel-level disk-fault injection (dm-flakey) deferred -- container
kill approximates. CI reruns (`rerunFailingTestsCount=5`) must be DISABLED for suite jobs
or findings get masked -- suite runs with rerun=0 always.

## 6. Environment requirements

Docker + Testcontainers; images: `axoniq/axonserver` (DCB-enabled version), `postgres:16`,
`ghcr.io/shopify/toxiproxy`; JDK 21 (CI also 25); Maven wrapper; no libfaketime (clock is
an injected seam in-JVM -- L3 skew via TokenStore-clock wrapper, not OS clock); Axon Server
license/image access for CI; optional `io.axoniq.framework` credentials for commercial
Postgres engine arm; CI runners with >=4 CPU for nightly chaos.

## 7. Repo layout & CI (implementation)

DECIDED: in-repo (AxonFramework), branch `feature/dst-testing-suite` in a dedicated
worktree. Layout mirrors axon-flow-spec exactly -- `formal/` + `simulation/` at root:

```
formal/                     # NOT in Maven reactor
  INVARIANTS.md             # MachineName registry, C-refs, cross-ref contract table
  FINDINGS.adoc             # living findings doc (F-numbers, severity, candidate fix)
  tla/                      # DcbAppend.tla, TokenClaim.tla, MC_*.cfg, README
simulation/                 # Maven module behind a profile (-Phunt): L1+L2 harness,
                            # faults, history recorder, checkers, scenarios
integrationtests/
  ...testsuite/...          # + AxonServer/Postgres(JPA)/PostgresDcb TestInfrastructures
  ...chaos/                 # L3 chaos ITs (@Tag("chaos"), Toxiproxy nemeses)
```

Default `./mvnw verify` unchanged (simulation behind profile; formal outside reactor).

- History recorder + checkers live in `simulation` main scope, reused by
  integrationtests (test-jar dep) so L1/L2/L3 share one oracle implementation.
- Findings discipline (copied verbatim from tla_dst): the engine is NEVER patched by the
  suite; a confirmed finding = expected-gap test (`document*` style, flips red when
  fixed) + FINDINGS.adoc entry + candidate fix; regression seeds pinned in
  `RegressionSeedsTest`.
- CI (`.github/workflows/hunt.yml`): `hunt-smoke` on PR (L1+L2 fixed seeds + in-memory
  L3 subset, <10 min, rerun=0); `hunt-nightly` (fuzz 1000+ seeds, chaos matrix
  in-memory x axonserver x postgres, Toxiproxy nemeses); `hunt-weekly` (release-tier
  soaks). Never `-Dsurefire.rerunFailingTestsCount` in any hunt job.

Phases (DECIDED: P0-P5, user approval checkpoint between phases; the old "P5 CI
automation" is deferred and NOT part of this plan):

- **P0 scaffolding** -- worktree, modules, INVARIANTS.md (incl. suite constitution D14 +
  determinism-boundary statement), history recorder (D7 discipline, D10 schema),
  `DcbStoreModel` + ModelConformanceChecker (D1) + VisibilityChecker.
- **P1 L1 DST core** -- harness + fault injectors (incl. PauseFault D4) + FaultSchedule
  grammar (D6) + LedgerWorkload/ConservationChecker (D3) + HuntTimescale (D5) +
  Lincheck suite (D9) + scenarios S1/S3/S10 + smoke CI job. Exit gate: L1 canaries
  (D8) all caught.
- **P2 L2 multi-node** -- shared-store nodes, steal/pause/crash, SwarmShape knobs (D13),
  scenarios S4/S8/S9/S15 (D12), remaining checkers (Ownership/Delivery/Order/Liveness).
- **P3 L3 real infra** -- TestInfrastructure x3 (AxonServer, Postgres-JPA, Postgres-DCB
  commercial; D2 differential matrix), existing-IT multiplication, containerized app
  for docker-pause (D4), Toxiproxy nemeses, chaos scenarios S2/S5/S6/S7/S11/S12,
  coverage-fed corpus (D11). Exit gate: backend canaries (D8).
- **P4 formal** -- DcbAppend.tla + TokenClaim.tla, MachineName bridge, violated/fixed
  cfg pairs, model<->TLA cross-check (D1 HOW).
- **P5 suite-usage skill (LAST PHASE)** -- author a SCENARIO-AGNOSTIC skill (working
  name `axon-hunt`) that teaches any future agent how to use the suite for maximum
  yield. Modeled on `axon-flow-tla-dst` (the method skill, pointing at live docs, never
  duplicating them). Must cover, scenario-agnostically:
  - the mental model (claims -> invariants -> history -> checkers; the D1/D2 spine);
  - how to run: smoke, fuzz, chaos, per-backend matrix, seed reproduction, corpus
    replay, time-compressed vs realistic arms;
  - the bug-hunting loop (hunt read-only -> triage -> reproduce -> verify yourself ->
    pin or reject), inherited from axon-flow-tla-dst and adapted;
  - how to add: an invariant, a checker, a fault, a workload shape, a backend, a
    canary -- each as a recipe with the golden rule (a property is done only when it
    exists in INVARIANTS.md + assertion + scenario/seed, wording identical);
  - how to interpret failures: attribution via backend vector (D2), flake
    classification (D14), landing-evidence and open-history rules (D6/D7);
  - the constitution (never patch engine, zero quarantine, exit-code judgment, honest
    determinism claims);
  - pointers, not copies: INVARIANTS.md, FINDINGS.adoc, CANARIES.md as the live
    sources of truth.
  Deliverable: skill directory (SKILL.md + references/) committed on the branch under
  `.claude/skills/axon-hunt/`, plus installable copy instructions. This is the seed of
  the future reproduce-client-issues / hunt-PRs agent.

Each phase lands green and independently valuable.

## 8. Extensibility charter (binding design constraint)

The scenarios in Sec.5 are INSTANCES, not the product. The product is the harness: any
future property, fault, workload, backend, or scenario class must be addable without
touching existing ones. Implementing agents design for this from P0:

- **Scenario = data, not code.** A scenario is a declarative record: workload shape +
  fault schedule + backend selector + timescale arm + oracle set + seed. New scenarios
  (including ones reproducing client issues or targeting a PR's blast radius) are new
  records, no harness changes. The Sec.5 list is merely the initial corpus.
- **Five open registries, each an SPI with its own recipe** (recipes land in the P5
  skill): invariants/checkers (new checker = implement `Checker` over `HistoryView` +
  register + INVARIANTS.md entry), faults (new `Fault` kind = one class; FaultSchedule
  grammar picks it up), workloads (new generator alongside LedgerWorkload; conservation
  hooks optional), backends (new `TestInfrastructure` impl instantly inherits every
  backend-agnostic scenario and the differential matrix), TLA models (new .tla + cfg
  pair bridged by MachineName).
- **History schema is the stable contract.** Checkers, converters, and future tools
  (Elle, dashboards, triage agents) consume the JSONL history -- schema versioned in
  INVARIANTS.md; fields are added, never repurposed. Anything that can emit this
  history (a new module, an example app, a client reproduction rig) gets the full
  oracle set for free.
- **Claims list is append-only and versioned.** New framework features (DLQ when it
  lands, message transformation, persistent streams, query side) enter by adding
  C-numbers + hypotheses + scenario records -- the Sec.4/Sec.5 method re-runs incrementally;
  the plan file is a living document on the branch.
- **No scenario-count assumptions anywhere**: caps, CI matrices, corpus sizes, and fuzz
  assertions derive from the registries, not hardcoded lists (tla_dst hard rule: grow
  the caps with the workload).
- **Reserved integration points**: `-Dhunt.focus=<package>` fuzz biasing (future
  PR-hunting agent); scenario-record import from external reports (future
  `hunt-reproduce` flow); history export for external analyzers; the P5 `axon-hunt`
  skill documents all of the above as recipes.

Acceptance test for this charter (checked at every phase review): "add a new invariant
+ a new fault + a new backend without editing any existing scenario" must be a
documented, mechanical recipe. If it requires surgery on existing classes, the design
failed this section and gets reworked before the phase closes.

## 9. Knowledge-usage guide for implementing agents

This section is a PLAYBOOK, not a link list. It tells the next agent WHICH resource to
open WHEN, WHAT to take from it, and what NOT to do. Follow it phase by phase.

### 9.1 Before writing ANY code (every phase, every session)

1. Confirm you are in the right worktree: `git rev-parse --show-toplevel` +
   `git branch --show-current` must show the `feature/dst-testing-suite` worktree.
   (Hard rule inherited from axon-flow-tla-dst: a wrong-checkout read silently
   analyzes the wrong code.)
2. Read this plan top to bottom. The claims (C1-C40, Appendix A) and missing claims
   (M1-M10) are the ONLY justification for any test you write. A test you cannot tie
   to a C or M number does not get written.
3. Load the **`axoniq-framework-5-expert`** skill once per session -- it carries this
   repo's contribution conventions (module layout, Javadoc style, test rules). All
   suite code must pass this repo's checkstyle and conventions; CLAUDE.md rules apply:
   Java 21, ASCII-only, LF, JSpecify nullability, fragment-style Javadoc tags,
   JUnit5 + AssertJ + Awaitility, `// given / // when / // then` comments, no mocks
   where a simple implementation works, no `join()`/`get()` without timeout.

### 9.2 How to navigate the codebase -- graphify FIRST, grep second

The repo has a prebuilt knowledge graph: `graphify-out/graph.json` (26k nodes, all of
messaging/modelling/eventsourcing + design docs + diagrams). Use it before grepping:

```
graphify query "How does WorkPackage extend a token claim?"     # BFS context, cites file:line
graphify query "<question>" --budget 3000                        # bigger answer
graphify path "Coordinator" "TokenStore"                         # shortest concept path
graphify explain "ConsistencyMarker"                             # one-node explanation
```

When to use which: `query` to orient in an unfamiliar subsystem before opening files;
`path` to find the wiring between two components (e.g. where PSEP touches the store);
`explain` for a single class's role. Then open the cited `file:line` with Read -- the
graph orients, the source decides. If you change framework-adjacent code significantly,
`graphify . --update` refreshes only changed files. Keep `graphify-out/` ignored
(`.git/info/exclude`), never commit it.

### 9.3 The reference implementation -- axon-flow-spec (the method to copy)

Location: `/Users/stefandragisic/Projects/axon-flow-spec`, branch **`poc/tla_dst`**
(NOT `tla_dst` -- that name does not exist). Do not check the branch out over someone's
working tree; read via `git show poc/tla_dst:<path>` or a temp worktree.

Load the **`axon-flow-tla-dst`** skill BEFORE designing any harness class -- it is the
distilled method. What to copy, and from where:

| Need | Copy from (poc/tla_dst) | Adapt how |
|---|---|---|
| Invariant registry + cross-ref table | `formal/INVARIANTS.md` | Same structure; our MachineNames map to C/M numbers instead of workflow invariants |
| Findings doc format | `formal/POC-TLA-DST.adoc` | Ours is `formal/FINDINGS.adoc`; keep F-numbers, severity, candidate-fix, reproduce cmd |
| Fault-injectable store | `ControllableEventStorageEngine` | Same pattern over `InMemoryEventStorageEngine`; add reject/claim-fault hooks for TokenStore too |
| Determinism seams | `MutableClock`, `ManualWorkflowScheduler`, `SeededWorkflowIdGenerator`, `SameThreadExecutorService`, `DeterministicVirtualThreadExecutor` | AF5 already injects `Clock` and executors in PSEP config -- prefer existing injection points; add seams ONLY as interface + registerIfNotPresent default + fake (production byte-identical) |
| BUGGIFY | `runtime/.../util/Buggify.java` | Same class shape; place fire() points in HARNESS wrappers, not framework code (we never patch the engine) |
| Fuzz/smoke/reproduce tests | `DstFuzzTest`, `DstReproduceTest`, `RegressionSeedsTest`, `@Tag("fuzz")` exclusion | Copy the surefire wiring incl. `-Ddst.seed`/`-Ddst.seeds`/`-Ddst.startSeed` |
| Anti-hang design | wall-clock deadline primary + max-steps cap secondary + enriched `InvariantViolation` with seed + fault trace + reproduce command | Copy verbatim |
| TLA layout | `formal/tla/` (MC.tla + per-property cfgs, violated/fixed pairs, tools/tla2tools.jar fetch) | Same; our two models are DcbAppend and TokenClaim |

Hard rules to inherit VERBATIM (from the skill; violating these is how a suite starts
lying): never patch the engine -- findings get expected-gap tests that flip when fixed;
never mask flakiness with surefire reruns; judge builds by exit code, not banner;
determinism claims must be scoped honestly (per-node, not global) on BOTH sides of an
assertion; invariant wording identical across INVARIANTS.md, .tla operator, and Java
assert method; paste real command output, never claim an unrun result.

Also read its `references/expanding.md` when adding an invariant, fault, scenario, or
regression seed -- it has the step-by-step recipes.

### 9.4 AF5 API knowledge -- which skill/file for which task

- **`axoniq-app-dev`** skill (routing table inside): the API reference for everything
  the harness wires up.
  - Building workloads (command handlers, DCB decision models): `commands/decision-models-dcb.md`
  - Exact AppendCondition/SourcingCondition/marker APIs: `event-store/primitives.md`
  - EventStore/EventStorageEngine layering: `event-store/internals.md`
  - PSEP, tokens, segments, split/merge, replay, multi-node: `events/processors.md`
  - Plain-Java wiring for harness Configurers (incl. PostgreSQL event store setup):
    `configuration/plain-java.md`
  - Maven coordinates for the COMMERCIAL Postgres DCB engine (`io.axoniq.framework`):
    `getting-started/dependencies.md`
  - AxonTestFixture (useful for workload sanity tests, NOT for the suite's oracles):
    `testing/*.md`
  - Anything not covered: published Javadoc https://apidocs.axoniq.io/5.2/
- **`dcb-axoniq`** / **`dynamic-consistency-boundaries`** skills: conceptual depth on
  DCB -- read before writing the DcbConflictChecker or DcbAppend.tla so the checker
  encodes the REAL semantics (tag-AND within criterion, OR across criteria, marker
  lower/upper bounds), not a folk version.
- In-repo claim sources (ground truth for oracle semantics): the Javadoc of the classes
  in Appendix A's source list, plus `axon-5/api-changes/05-event-store-and-processors.md`
  and `docs/reference-guide/modules/events/pages/event-processors/streaming.adoc`
  (defaults, all A0-verified against source in Appendix A.2: claimTimeout 10s
  (TokenStore only, NOT the processor), tokenClaimInterval 5000ms, initialSegmentCount 16,
  claimExtensionThreshold 5000ms, batchSize 1, gapTimeout 60000ms, maxGapOffset 10000,
  the numbers the scenarios manipulate. **WARNING: under Spring Boot autoconfigure
  gapTimeout and maxGapOffset are inverted (10000ms / 60000); see M12. Any scenario
  touching gap behaviour MUST state which configuration path built the engine.**)
- External library docs (Testcontainers, Toxiproxy, TLA+ tooling): `ctx7` CLI
  (context7) -- `npx ctx7@latest library <name> "<question>"` then `docs`.

### 9.5 Test-methodology knowledge -- when designing vs running

- **`designing-distributed-system-tests`** skill: this plan already applied it. Reopen
  its `references/` only when EXTENDING the plan: `common-distributed-systems-pitfalls.md`
  (new hypothesis), `deterministic-simulation.md` (P1/P2 design), `chaos-and-fault-injection.md`
  + `crash-recovery-and-upgrade.md` (P3 nemeses), `formal-methods-tla.md` (P4),
  `history-discipline.md` (operation-history schema -- READ THIS before writing the
  recorder in P0; our JSONL format must follow its 11-field discipline and
  ambiguous-outcome rules).
- **`executing-distributed-system-tests`** skill: load when RUNNING scenarios (P1
  onward). Non-negotiables from it: every fault needs LANDING EVIDENCE (proof the
  nemesis actually fired -- e.g. Toxiproxy API state, container exit code) or the run is
  INCONCLUSIVE, not PASS; run the green-but-broken audit before declaring any scenario
  PASS; its `references/oracle-patterns.md` "Checker picker" table is where our checker
  designs came from -- consult it before writing a new checker.

### 9.6 Existing repo assets to REUSE (do not reinvent)

- `integrationtests` `TestInfrastructure` strategy (`AbstractIT`,
  `InMemoryTestInfrastructure`) -- P3 implements new backends AGAINST this interface;
  do not build a parallel mechanism.
- `eventsourcing` shared suites: `StorageEngineTestSuite`,
  `AggregateBasedStorageEngineTestSuite`, `StorageEngineBackedEventStoreTestSuite` --
  extend them for new backends; their existing concurrent-append tests are workload
  seeds for S1.
- `test/` module: `AxonTestFixture`, `RecordingEventStore`, `RecordingCommandBus` --
  workload construction and sanity checks. NOT oracle material (recording != history
  discipline).
- Package-local test utils: `messaging/.../eventhandling/EventTestUtils` (sample
  events -- CLAUDE.md mandates it), `StubProcessingContext`, `UnitOfWorkTestUtils`.
- CI conventions: mirror `.github/workflows/pullrequest.yml` matrix (JDK 21/25, Zulu);
  hunt jobs NEVER set `rerunFailingTestsCount` (main CI's `=5` is a documented risk,
  not a pattern to copy).

## 10. Open questions / followups

Schema-evolution (message transformation) chaos; upgrade/rollback (AF 5.1->5.2 rolling)
scenarios; query-side + subscription-query oracles; DLQ scenarios when the module lands;
XA arm (M8); dm-flakey disk faults; Elle integration if histories warrant full
transactional-anomaly checking.

## 11. A0 review notes (claims-mining pass output)

Produced by the A0 pass. The scenario list in section 5 is NOT final; this section judges
each scenario against the evidence in Appendix A and gives sharpened replacements. Where a
line here contradicts section 5, this section wins.

### 11.1 Findings that change the harness design (read before P1)

| # | Finding | Evidence | Consequence |
|---|---|---|---|
| F-A0-1 | `InMemoryTokenStore` implements **no claim/ownership at all**: `releaseClaim` is a no-op, `fetchToken` never fails on ownership, `fetchAvailableSegments` == `fetchSegments`, no timestamp/owner field exists | `MSG/eventhandling/processing/streaming/token/store/inmemory/InMemoryTokenStore.java:126-137`, `:140-145`, `:191-193` | **L2 as specified is unbuildable.** C18-C22 are vacuous on the in-memory store. L2 must use `JdbcTokenStore` over an in-JVM H2/HSQLDB, or a purpose-built claim-aware fake implementing the `TokenEntry` claim algebra. Affects S4, S6, S15. |
| F-A0-2 | Claim expiry is evaluated with a **static** clock (`ClockUtils.instant()`), not the per-node injected `Clock` (which is `@Deprecated(forRemoval, since 5.2.0)` in `WorkPackage`/`Coordinator`) | `MSG/.../token/store/jpa/TokenEntry.java:159-161`, `MSG/.../pooled/WorkPackage.java:102-103` | Per-node skew injection must go through `ClockUtils`, not a constructor-injected `Clock`. A per-node `Clock` seam will silently not affect claim expiry. Affects S4, D5. |
| F-A0-3 | Spring Boot autoconfigure inverts `gapTimeout` and `maxGapOffset` relative to core defaults | `extensions/spring/spring-boot-autoconfigure/src/main/java/org/axonframework/extension/springboot/JpaEventStorageEngineConfigurationProperties.java:44,46` vs `ES/eventstore/jpa/AggregateBasedJpaEventStorageEngineConfiguration.java:84,86` | S7 must run both configuration paths as separate arms. Motivates new scenario S19. |
| F-A0-4 | Default event sequencing policy is `Hierarchical(SequentialPerAggregate, Sequential)`, and `SequentialPerAggregatePolicy` resolves from `LegacyResources.AGGREGATE_IDENTIFIER_KEY`, which DCB-native stores never set | `MSG/eventhandling/SimpleEventHandlingComponent.java:61-64`, `MSG/core/sequencing/SequentialPerAggregatePolicy.java:42-47` | On every DCB backend the default degrades to strictly-sequential single-key processing. S10's arms as written test the wrong thing; M7's premise is largely dissolved. |
| F-A0-5 | `SplitTask` blocks local re-claim of the segment for a hardcoded 60 seconds | `MSG/.../pooled/SplitTask.java:113-114` | Under compressed time (D5) a split arm looks wedged for 60 real seconds. S9 and S15 must budget for it or override it; it is not configurable. |
| F-A0-6 | `InMemoryEventStorageEngine.commit()` inserts batch events one at a time under `appendLock` while readers traverse the map lock-free | `ES/eventstore/inmemory/InMemoryEventStorageEngine.java:127-146` vs `:293-304` | A concurrent poller can observe a strict prefix of a committing batch. C9. Cheap, high-signal new scenario S16. |

### 11.2 Per-scenario review (S1-S15)

Each entry: verdict, then the sharpened replacement for the section 5 line. `ORACLE`,
`WORKLOAD`, `EVIDENCE`, `AMBIGUITY`, `BUDGET` are the five things section 5 left implicit.

**S1 `dcb_append_rejected_after_marker_under_contention`** - SUFFICIENT after sharpening.
- ORACLE (replace "Oracle: DcbConflictChecker"): replay the history against `DcbStoreModel`;
  every append recorded `ok` must be accepted by the model at its commit index, every append
  recorded `fail` must be rejected. Additionally: for the `AppendCondition.none()` control
  arm, ZERO rejections are permitted (C2). Additionally: every rejected append must leave
  zero events with its event identifiers in the store (no partial batch, C9).
- WORKLOAD: K writers (K in {2,4,8,16}) over T tags with Zipf(s=1.0) tag selection and a
  declared overlap degree; op mix 70% source-then-append, 20% `withCriteria` ORIGIN append,
  10% `none()` append (control). Batch size 1..5 events, seeded.
- EVIDENCE: smoke has no faults, so none. Hardening/release: `FaultSchedule` log must show
  fire-count > 0 per declared fault, else INCONCLUSIVE.
- AMBIGUITY: an append future completing with anything other than
  `AppendEventsTransactionRejectedException`, or not completing before the phase deadline,
  is `UNKNOWN`; resolved only by the post-heal authoritative scan.
- BUDGET: smoke 1k commands x 3 seeds, 0 violations, wall < 90s. Hardening 100k x 100 seeds,
  0 violations. Release nightly 1000 seeds x 3 backends, 0 violations. No tolerance.

**S2 `commit_ack_matches_durability_under_partition`** - SUFFICIENT after sharpening.
- ORACLE: the client verdict set (per event identifier: SUCCESS / ERROR / UNKNOWN) is frozen
  BEFORE heal; after heal and quiescence, an authoritative scan by event identifier must
  satisfy SUCCESS => present exactly once, ERROR => absent, UNKNOWN => either. Report the
  UNKNOWN rate.
- EVIDENCE: Toxiproxy API state transitions logged with timestamps; container exit codes.
  **A run with zero UNKNOWNs under an active partition is INCONCLUSIVE, not PASS** - the
  nemesis did not land in the commit window.
- BUDGET: release 30-min soak, partitions every 20-60s, >= 20 partition events, >= 1 UNKNOWN.

**S3 `uncommitted_never_visible_rolledback_never_delivered`** - SUFFICIENT after sharpening.
- ORACLE: per event identifier, no delivery record may precede the commit record of its
  transaction, and no event from a rolled-back transaction may appear in any delivery record
  or in a post-run store scan. Add the C9 probe: a reader polling at maximum rate during a
  100-event batch commit must never observe a strict prefix of that batch.
- WORKLOAD: injected failure at each of PREPARE_COMMIT / COMMIT / AFTER_COMMIT as three
  separate arms, each with its own verdict (do not fold into one).
- BUDGET: smoke 3 arms x 200 transactions x 3 seeds, 0 violations.

**S4 `at_most_one_segment_owner_with_skew`** - **NOT BUILDABLE AS WRITTEN** (F-A0-1, F-A0-2).
Replacement line: `S4 at_most_one_segment_owner_with_skew (C18,C19,C20,C21,C22,M3; H10,H11)
| L2 with JdbcTokenStore-over-H2 (NOT InMemoryTokenStore) + L3 Postgres TokenStore | 2-4
nodes with distinct nodeIds, contended segments, ledger workload | ClockUtils skew injection
per node (+/- delta around claimTimeout), BUGGIFY stall at claim-extension, node crash |
ORACLE: claim intervals per segment derived from the token table's own owner+timestamp
columns must not overlap by more than the declared skew delta; every delivery is attributed
to the segment owner at its processing instant; duplicates only inside a recorded
claim-transition window | smoke/hardening/release`.
- BUDGET: smoke 2 nodes, 4 segments, skew 0, 60s virtual; hardening 4 nodes, 16 segments,
  skew = claimTimeout/2; release skew = 2x claimTimeout (expected to violate - the run
  quantifies the violation window rather than asserting zero).

**S5 `exactly_once_when_token_and_projection_share_tx`** - **WEAK ORACLE as written.**
"projection == fold(history)" passes under a compensating double-apply. Replacement ORACLE:
the ledger projection writes, in the same transaction, a row `(eventIdentifier ->
appliedCount)`; after quiescence every delivered event has `appliedCount == 1` AND the
balance fold matches AND the stored token is monotone non-decreasing over the whole run.
- EVIDENCE: `kill -9` landing proven by container exit code 137; DB restart proven by a
  server-start log line with a timestamp inside the fault window.
- BUDGET: release 10 kill cycles, 0 events with `appliedCount != 1`.

**S6 `at_least_once_no_loss_when_stores_split`** - **UNFALSIFIABLE as written.** "duplicates
counted, must be finite and attributable" has no decision rule. Replacement ORACLE: no
delivered-event-identifier set may be missing any acked append (no loss, hard FAIL); a
duplicate is permitted only if its second delivery timestamp falls inside a recorded
claim-loss or crash-recovery window in the history; **any duplicate outside such a window is
a FAIL**. Report duplicates-per-window as a distribution.

**S7 `no_event_skipped_by_gap_timeout`** - SUFFICIENT after sharpening; still highest yield.
- MECHANISM (was under-specified): the skip has two independent causes, both must be driven.
  (a) gap cleaning removes timed-out gaps
  (`ES/eventstore/jpa/AggregateBasedJpaEventStorageEngineConfiguration.java:280-292`), and
  (b) `allowGaps = timestamp.isAfter(gapTimeoutThreshold)` means an event whose **message
  timestamp** is older than the threshold is never recorded as a gap at all
  (`ES/eventstore/jpa/AggregateBasedJpaEventStorageEngine.java:427-430`). The workload must
  therefore hold a transaction open past gapTimeout AND ensure the pending event's own
  timestamp predates the threshold.
- ARMS (was missing): core-configured engine (gapTimeout 60000 / maxGapOffset 10000) AND
  Spring-Boot-autoconfigured engine (10000 / 60000, F-A0-3), plus a compressed-time variant.
- ORACLE: every acked append's event identifier appears exactly once in the delivered set
  within the liveness horizon; a missing identifier is a confirmed silent skip.
- BUDGET: release 20 late-commit cycles per arm, 0 missing identifiers.

**S8 `replay_sees_full_prefix_and_flags_redelivery`** - SUFFICIENT after sharpening.
- Precondition arm: `resetTokens` while running must fail (`Assert.state`,
  `MSG/.../pooled/PooledStreamingEventProcessor.java:301`); assert the exception, not just
  "must fail".
- ORACLE: after the legal reset and quiescence, projection == fold(full committed history);
  and for every delivery, the `ReplayToken` replay flag is true exactly when the delivered
  position is at or below `tokenAtReset`.
- ADD: a cross-node arm documenting that `!isRunning()` is a local-JVM check only (M15) -
  document, do not assert, in iteration 1.

**S9 `split_merge_no_loss_no_dup_under_load`** - SUFFICIENT after sharpening.
- ADD: budget for the hardcoded 60s post-split claim block (F-A0-5); under compressed time
  this is the dominant term and a naive horizon will report a false liveness violation.
- ORACLE: across segment epochs, per sequencing key, the delivered subsequence is a
  subsequence of the append order; union of deliveries across all segments covers every
  committed event exactly once modulo recorded claim-transition windows.
- Precondition arms: merge of a single-segment processor must return false
  (`MSG/.../pooled/MergeTask.java:118-123`); split beyond max mask must throw
  (`MSG/.../segmenting/Segment.java:202-205`).

**S10 `sequencing_policy_order_preserved`** - **WRONG ARMS as written** (F-A0-4). The
"empty=parallel" arm tests `NoOpSequencingPolicy`, not the default. Replacement arms:
(a) framework default `Hierarchical(SequentialPerAggregate, Sequential)` on a DCB backend -
expect strictly-sequential single-key behaviour and assert it; (b) same default on the
aggregate-based JPA backend, where `AGGREGATE_IDENTIFIER_KEY` IS populated - expect
per-aggregate keying; (c) explicit `NoOpSequencingPolicy`; (d) explicit
`SequentialPerAggregatePolicy` alone on a DCB backend - expect empty sequence identifiers.
Arm (a) vs (b) is the differential that exposes the silent behaviour change.

**S11 `axonserver_stream_resume_no_loss_no_silent_dup`** - BUILDABLE ONLY WITH EXTERNAL
ARTIFACTS. The connector module is absent from this tree (verified: no `*axonserver*` or
`*connector*` directory at depth 3). Add explicitly: if the released connector artifact or a
DCB-enabled `axoniq/axonserver` image is unavailable, the scenario records
`axonserver:SKIPPED` in the D2 backend vector and the run is INCONCLUSIVE, never PASS.

**S12 `crash_recovery_no_acked_loss_postgres`** - SUFFICIENT after sharpening.
- EVIDENCE: `docker kill -s KILL`; landing proven by container exit code 137 plus a
  post-restart recovery log line.
- ORACLE: the acked set is captured client-side before the kill; post-restart full scan by
  event identifier must be a superset of the acked set (loss = FAIL) and must not contain any
  identifier the client saw rejected.

**S13 `liveness_all_committed_eventually_processed`** - REDUNDANT AS A SEPARATE SCENARIO but
cheap; keep. Its `LivenessChecker` already runs in every scenario (D3 mandates the checker
set everywhere). Its unique value is the fault-storm-with-heal arm. Sharpen the horizon:
L1/L2 horizon = 10x the maximum quiescent latency observed in the warmup phase of the same
run (never a hardcoded constant); L3 = 60s wall after the last fault healed. Define
"accepted command" = a command whose dispatch returned a non-exceptional future or whose
append was recorded `ok`.

**S14 formal models** - SUFFICIENT after one addition: `DcbAppend.tla` must model the
**two-phase** check (validate at append AND re-validate at commit,
`ES/eventstore/EventStorageEngine.java:84-86`, `ES/eventstore/inmemory/InMemoryEventStorageEngine.java:107-110`
and `:124-126`). A model that checks once cannot express C8 and therefore cannot find the
class of bug S1 exists to hunt.

**S15 `concurrent_bootstrap_initializes_segments_exactly_once`** - SUFFICIENT, and better
targeted than section 5 suggests: `InMemoryTokenStore.initializeTokenSegments` is a
non-atomic read-then-write (`fetchSegments` then `put`,
`MSG/.../token/store/inmemory/InMemoryTokenStore.java:79-94`), so the double-initialisation
property IS testable at L1 in-memory even though that store cannot express claims (F-A0-1).
- ORACLE: after N concurrent bootstraps, exactly `initialSegmentCount` segments exist, each
  initialised exactly once, and (on a claim-capable store) `OwnershipChecker` holds from t=0.
- ADD: the documented-undefined case must be probed and documented, not asserted -
  `TokenStore.java:54-55` states behaviour is undefined when tokens already exist and are not
  owned by the initialiser (C36).

### 11.3 Missing scenario classes (proposed additions)

Same one-line format as section 5.

- **S16 `partial_batch_never_visible`** (C9, C4; F-A0-6) - L1 in-memory + L3 all backends |
  single writer committing 100-event batches, one reader polling at max rate | none |
  ORACLE: the reader never observes a strict prefix of an in-flight batch (every observed
  batch member implies all members with lower positions are observable in the same poll) |
  smoke. Cheapest high-signal scenario in the set.
- **S17 `stored_token_never_regresses`** (C38) - L1+L2 | processor with an injected progress
  strategy that proposes regressed and incomparable tokens | none | ORACLE: the token-store
  write log for each segment is monotone non-decreasing under
  `TrackingTokenUtils.coversWhenUnwrapped`; a regressed proposal produces a WARN and no
  write | smoke.
- **S18 `max_segments_rebalance_is_fair_and_terminates`** (C40) - L2 | N nodes join and leave
  with `maxSegmentProvider` set below `initialSegmentCount / N` | node join/leave storm |
  ORACLE: no node ever holds more than `maxSegmentProvider.apply(name)` segments for longer
  than one `tokenClaimInterval`; every segment is owned by someone after quiescence |
  hardening.
- **S19 `effective_defaults_match_core_defaults`** (C14, M12; F-A0-3) - L3 config-only, no
  workload | build the JPA engine through both the core configuration record and Spring Boot
  autoconfigure | none | ORACLE: the effective `gapTimeout`, `maxGapOffset`, `batchSize`,
  `gapCleaningThreshold`, `lowestGlobalSequence`, `claimTimeout`, `tokenClaimInterval`,
  `initialSegmentCount`, `claimExtensionThreshold` are pairwise equal across configuration
  paths, or the difference is declared in an explicit allow-list | smoke. Catches the whole
  class of silent configuration drift, of which M12 is one live instance.
- **S20 `resume_position_callback_contract`** (C31, M6) - L1 all backends | source streams
  consumed fully, partially, closed early, and errored | injected stream error and early
  close | ORACLE: the callback fires at most once and only on full consumption; on an empty
  result the reported position is at or beyond the requested position | smoke.

Not proposed, and why: an upgrade/rollback scenario (section 10) needs two framework versions
in one harness and is out of scope for iteration 1; a schema-evolution scenario depends on the
commercial message-transformation module; a DLQ scenario has no module to target (C39).

---

Appendix A: claims C1-C40 with sources.

**Path shorthands** (every entry resolves to an absolute path under the repo root):
`ES/` = `eventsourcing/src/main/java/org/axonframework/eventsourcing/`;
`MSG/` = `messaging/src/main/java/org/axonframework/messaging/`;
`SB/` = `extensions/spring/spring-boot-autoconfigure/src/main/java/org/axonframework/extension/springboot/`;
`ADOC` = `docs/reference-guide/modules/events/pages/event-processors/streaming.adoc`.

**Confidence**: `documented` = stated in Javadoc or reference docs; `code-inferred` = only
visible in the implementation, the doc is silent (an inferred claim is weaker and is itself a
documentation finding).

### A.1 Claims

| ID | Claim | Kind | Conf | Source (path:line + verbatim quote) | Falsified by |
|---|---|---|---|---|---|
| C1 | An append whose `AppendCondition` criteria match any event stored after its `ConsistencyMarker` is rejected with `AppendEventsTransactionRejectedException` | safety | documented | `ES/eventstore/AppendCondition.java:74-76` "Appending will fail when there are events appended after this point that match the provided EventCriteria." | Two appends with overlapping criteria and the same pre-append marker both commit |
| C2 | `AppendCondition.none()` disables conflict detection entirely: its marker is `INFINITY` and `containsConflicts` short-circuits to false for `INFINITY` | semantics | documented | `ES/eventstore/NoAppendCondition.java:42-44` `return ConsistencyMarker.INFINITY;`; `ES/eventstore/inmemory/InMemoryEventStorageEngine.java:169-171` "if (Objects.equals(condition.consistencyMarker(), ConsistencyMarker.INFINITY)) { return WITHOUT_MARKER; }" | An `AppendCondition.none()` append is ever rejected as conflicting. NOTE: the plan's anchor name `EventCriteria.none()` does not exist; the no-criteria factory is `EventCriteria.havingAnyTag()` (`MSG/eventstreaming/EventCriteria.java:191-193`) |
| C3 | Under `ConsistencyMarker.ORIGIN` every event already in the store that matches the criteria is a conflict (ORIGIN resolves to position -1, so the conflict scan covers the whole store) | safety | documented | `ES/eventstore/ConsistencyMarker.java:47-49` "Effectively any event present in an event store would represent a conflict with this marker."; `ES/eventstore/GlobalIndexConsistencyMarker.java:49-50` `else if (consistencyMarker == ConsistencyMarker.ORIGIN) { return -1; }` | A `withCriteria(...)` append succeeds while a matching event already exists |
| C4 | Events are handed to the storage engine in PREPARE_COMMIT and are only made visible to consumers by `AppendTransaction.commit()` in the COMMIT phase | ordering | documented | `ES/eventstore/EventStorageEngine.java:187` "Events may only be visible to consumers after the invocation of `commit()`."; `:64` "Called during the ... PREPARE_COMMIT phase."; `ES/eventstore/DefaultEventStoreTransaction.java:199` `processingContext.onPrepareCommit(` and `:211` `processingContext.onCommit(c -> tx.commit()` | Any event observed by a streaming or sourcing consumer before its transaction's COMMIT phase begins |
| C5 | The `AppendCondition` used at commit derives its marker from the terminal `ConsistencyMarker` entry of the sourcing stream, not from the condition originally built | safety | documented | `ES/eventstore/DefaultEventStoreTransaction.java:117-135` (marker captured from `entry.getResource(ConsistencyMarker.RESOURCE_KEY)`) and `:230-231` `current.withMarker(getOrDefault(context.getResource(appendPositionKey), current.consistencyMarker()))` | An append is validated against ORIGIN (or the pre-sourcing marker) after a successful sourcing |
| C6 | When one `ProcessingContext` sources more than once, the append marker is the `lowerBound` of the markers received | safety | documented | `ES/eventstore/DefaultEventStoreTransaction.java:138-142` "we choose the lowest, non-ORIGIN appendPosition ... the lowest consistency marker that we received from those streams ... is the safest one to use." and `:152` `return current.lowerBound(marker);` | After two sourcings, an append is validated from the higher of the two markers |
| C7 | When one `ProcessingContext` sources more than once, the append criteria are the OR of all sourcing criteria | safety | documented | `ES/eventstore/DefaultEventStoreTransaction.java:107-112` (`ac.orCriteria(condition.criteria())`); `ES/eventstore/AppendCondition.java:65` "an AppendCondition that combined this condition's criteria and the given, using 'OR' semantics" | After sourcing tags A then B, a concurrent append touching only B is not detected as a conflict |
| C8 | Conflict detection may be deferred to commit time; an engine that detects early must also re-detect under the commit lock | safety | documented | `ES/eventstore/EventStorageEngine.java:84-86` "Implementations may be able to detect conflicts during the append stage ... Other implementations may delay such checks until the `AppendTransaction#commit()` is called."; `ES/eventstore/inmemory/InMemoryEventStorageEngine.java:107-110` (early) and `:122-126` (re-check inside `appendLock`) | A conflicting event committed between the append call and the commit call is not detected |
| C9 | **WEAKER THAN STATED.** A multi-event batch is NOT made atomically visible: `commit()` inserts events one at a time under `appendLock` while readers traverse the storage map lock-free, so a concurrent poller can observe a strict prefix of a committing batch | safety | code-inferred (docs silent; the only statement is the weaker "visible ... after the invocation of commit()") | `ES/eventstore/inmemory/InMemoryEventStorageEngine.java:127-146` (per-event `eventStorage.put(next, event)` inside the lock) vs `:293-304` (`next()` reads `eventStorage.containsKey` with no lock) | Confirmed by observing a strict prefix; refuted by never observing one across a high-rate poll of large batches |
| C10 | Events of one append are stored in the order they were offered | ordering | documented | `ES/eventstore/EventStorageEngine.java:57-58` "Events will be appended in the order that they are offered in, validating the given `condition` before being stored." | Positions assigned to a batch are not ascending in offer order |
| C11 | On the aggregate-based engine, two events with the same aggregate identifier and sequence number cannot both be stored; the resulting constraint violation is translated to `AppendEventsTransactionRejectedException` | safety | code-inferred (the unique index is a schema detail; no Javadoc states the guarantee) | `ES/eventstore/jpa/AggregateEventEntry.java:47` `@Table(indexes = @Index(columnList = "aggregateIdentifier,aggregateSequenceNumber", unique = true))`; `ES/eventstore/AggregateBasedEventStorageEngineUtils.java:119` `AppendEventsTransactionRejectedException translated = conflictingEventsDetected(consistencyMarker);` | Two events share (aggregateIdentifier, aggregateSequenceNumber), or the violation surfaces as a raw persistence exception instead of a rejection |
| C12 | **REFUTED for the aggregate-based JPA engine.** Global sequence order equals commit order only in-memory (positions assigned under `appendLock` at commit); on JPA the `globalIndex` comes from a database sequence allocated before commit, so commit order and index order can diverge - which is precisely why gap-aware tokens exist | ordering | code-inferred / contradicted | in-memory: `ES/eventstore/inmemory/InMemoryEventStorageEngine.java:122` `appendLock.lock();` and `:164-166` `nextIndex()`; JPA: `ES/eventstore/jpa/AggregateEventEntry.java:54-61` (`@GeneratedValue` sequence generator); `MSG/eventhandling/processing/streaming/token/GapAwareTrackingToken.java:38-40` "sequence numbers of events that may have been inserted but have not been committed to the store" | On JPA: a consumer streaming strictly by increasing globalIndex misses an event that commits later with a lower index. This is expected, not a bug - the bug is if the gap machinery fails to recover it (C14) |
| C13 | A `GapAwareTrackingToken` records the sequence numbers of not-yet-visible events so a consumer can revisit them when a later batch is fetched | liveness | documented | `MSG/.../token/GapAwareTrackingToken.java:34-41` "consumers are able to track the event store uninterruptedly even when there are gaps ... the event store can check if meanwhile this gap has been filled each time a new batch of events is fetched." | A gap that is later filled is never revisited |
| C14 | A gap older than `gapTimeout` (default 60000 ms) is dropped from the token, and an event whose own timestamp predates the gap-timeout threshold is never even recorded as a gap; such an event, if committed afterwards, is never streamed | liveness | code-inferred (the doc states only that timed-out gaps are removed "to improve performance"; the loss consequence is not stated) | `ES/eventstore/jpa/AggregateBasedJpaEventStorageEngineConfiguration.java:284-287` "Gaps that have timed out will be removed from Tracking Tokens to improve performance of reading events. Defaults to `60000`ms."; `ES/eventstore/jpa/AggregateBasedJpaEventStorageEngine.java:427` `boolean allowGaps = timestamp.isAfter(gapTimeoutThreshold);`; `:430` `token.advanceTo(globalIndex, allowGaps ? maxGapOffset : 0)` | An acked append never reaches any streaming consumer. **This is the highest-value falsification in the plan (S7).** |
| C15 | A batch of events and this cycle's token progress are persisted in one transaction | safety | documented (body comment + code) | `MSG/.../pooled/WorkPackage.java:386-387` "One transaction handles the batch AND persists this cycle's progress (if any)." with `unitOfWork.onPrepareCommit(progressStrategy::onBatchCommit);` | The stored token advances past events whose handler effects were rolled back, or vice versa |
| C16 | Exactly-once processing holds only when the token store and the projection share one transactional resource; otherwise the guarantee is at-least-once and handlers must be idempotent | semantics | documented | `ADOC:481-483` "Where possible, we recommend using a token store that stores tokens in the same database as to where the event handlers update the view models. This way, changes to the view model can be stored atomically with the changed tokens. Furthermore, it guarantees **exactly once** processing semantics." | Same-resource arm shows any event applied twice; or the split-resource arm shows event loss (only duplication is permitted there) |
| C17 | A stolen token causes the previous owner's token update to fail, rolling back its processing context; non-transactional side effects performed before the rollback are repeated | semantics | documented | `ADOC:423` "The consequence of token stealing is that an event may be handled twice (or more)."; `:426-428` "As the token claim is required to update the token, the original thread will fail the update. Following this, a rollback occurs" | A steal produces a duplicated effect on the same-resource arm, or produces no duplicate at all on the split-resource arm when the steal demonstrably landed mid-batch |
| C18 | Fetching a token claims it for the current process, preventing access by other instances | safety | documented | `MSG/.../token/store/TokenStore.java:98-99` "The token will be claimed by the current process (JVM instance), preventing access by other instances." | Two distinct nodeIds simultaneously hold the same (processorName, segmentId). **Vacuously true on `InMemoryTokenStore`, which has no ownership at all - see M11** |
| C19 | A claim may be taken only when the entry is unowned, owned by the same nodeId, or expired | safety | documented | `MSG/.../token/store/jpa/TokenEntry.java:155-157` `return this.owner == null \|\| owner.equals(this.owner) \|\| expired(claimTimeout);` | A claim succeeds against a live claim held by a different nodeId within the claim timeout |
| C20 | A claim older than `claimTimeout` (default 10 s on both durable stores) may be stolen | liveness | documented | `MSG/.../token/store/jpa/TokenEntry.java:131` "If a claim exists, but it is older than given `claimTimeout`, the claim may be 'stolen'."; default at `MSG/.../token/store/jpa/JpaTokenStoreConfiguration.java:54-56` `Duration.ofSeconds(10)`; `ADOC:402` "By default, the `claimTimeout` value equals 10 seconds." | A segment stays unprocessable after its owner dies and claimTimeout has elapsed |
| C21 | Extending a claim that has been lost fails with `UnableToClaimTokenException`, and the owning `WorkPackage` is aborted | safety | documented | `MSG/.../token/store/TokenStore.java:139-141` "The returned future will complete exceptionally with an `UnableToClaimTokenException` if the token did not exist or was claimed by another process."; `MSG/.../pooled/WorkPackage.java:487-488` `tokenStore.extendClaim(...).thenRun(...)`; abort path `:403-409` | A work package keeps processing after its extendClaim failed |
| C22 | Token ownership is identified by a configurable `nodeId` that defaults to the JVM runtime MXBean name; the contract requires it to be unique per node | safety | documented (uniqueness stated) / code-inferred risk (the default may not satisfy it) | `MSG/.../token/store/jpa/JpaTokenStoreConfiguration.java:54-56` `ManagementFactory.getRuntimeMXBean().getName()`; `MSG/.../token/store/jpa/TokenEntry.java:133-134` "The name of the current node, to register as owner. This name must be unique for multiple" | Two JVMs producing the same runtime name (same pid and hostname, common in containers) both hold the same segment while `mayClaim` returns true for both |
| C23 | Splitting a segment doubles its mask; a split beyond the maximum mask throws `IllegalStateException` | semantics | documented | `MSG/.../segmenting/Segment.java:202-205` "Unable to split the given segmentId, as the mask exceeds the max mask size."; `:194-195` "Callers must ensure that either the two returned Segments are used, or the instance from which they are derived, but not both." | A split produces overlapping or non-covering child segments, or a max-mask split succeeds |
| C24 | Two segments may be merged only when their masks are identical and they differ only in the first 1-bit of that mask | semantics | documented | `MSG/.../segmenting/Segment.java:141-142` "Two segments can be merged when their mask is identical, and the only difference in SegmentID is in the first 1-bit of their mask."; enforced at `:122` `Assert.isTrue(this.isMergeableWith(other), ...)` | A merge of non-sibling segments succeeds |
| C25 | A merge request against a processor with only one segment is refused (returns false, no state change) | semantics | documented | `MSG/.../pooled/MergeTask.java:118-123` "A merge request can only be fulfilled if there is more than one segment." | A single-segment merge mutates the token store |
| C26 | `resetTokens` requires that the handlers support reset AND that this processor instance is not running | safety | documented | `MSG/.../pooled/PooledStreamingEventProcessor.java:300-301` `Assert.state(supportsReset(), ...)` and `Assert.state(!isRunning(), () -> "The Processor must be shut down before triggering a reset.")` | A reset succeeds while the processor is running. NOTE: `isRunning()` is a local-JVM check only (M15) |
| C27 | After a reset, redelivered messages are detectable as replays via `ReplayToken` | semantics | documented | `MSG/.../token/ReplayToken.java:41-43` "Token keeping track of the position before a reset was triggered. This allows for downstream components to detect messages that are redelivered as part of a replay." | A redelivered event is not flagged as a replay, or a genuinely new event is |
| C28 | A reset fetches (and thereby claims) the token of every known segment and stores a `ReplayToken` for each, all inside one unit of work | safety | code-inferred | `MSG/.../pooled/PooledStreamingEventProcessor.java:304-317` (single `unitOfWork`), `:325-336` (`fetchSegments` then `fetchToken` per segment), `:389-397` (`storeToken` per segment) | A reset leaves some segments at their old token while others are reset |
| C29 | Rolling back an append transaction makes the appended events permanently unavailable to consumers, and any error in the processing context triggers that rollback | safety | documented | `ES/eventstore/EventStorageEngine.java:204` "Rolls back any events that have been appended, permanently making them unavailable for consumers."; `ES/eventstore/DefaultEventStoreTransaction.java:214` `processingContext.onError((c, p, e) -> tx.rollback());` | Any event from a rolled-back transaction appears in a post-run store scan or a delivery record |
| C30 | The final entry of a `source()` stream always carries a `ConsistencyMarker` paired with a `TerminalEventMessage`, and the transaction filters that entry out of the events it exposes | semantics | documented | `ES/eventstore/EventStorageEngine.java:103-106` "The final entry of the stream **always** contains a `ConsistencyMarker` ... paired with a `TerminalEventMessage`."; `ES/eventstore/EventStoreTransaction.java:48-50` "**Any** `EventStoreTransaction` ... is expected to filter the `TerminalEventMessage`" | A sourcing stream ends without a marker entry, or a `TerminalEventMessage` reaches an entity |
| C31 | The `resumePositionCallback` of `source(condition, callback)` is invoked at most once and only after the stream is fully consumed; on an empty result the reported position is at or beyond the requested one | semantics | documented | `ES/eventstore/EventStoreTransaction.java:65-75` "invoked at most once and only after the returned `MessageStream` has been consumed completely ... If sourcing completes and no events are found, the callback will be invoked with the position specified in `sourcingCondition` or with a greater position." | The callback fires twice, fires on a closed-early stream, or reports a position behind the requested one |
| C32 | **DIFFERENT FROM DOCUMENTED.** The Javadoc names `SequentialPerAggregatePolicy` as the "default event processing policy", but the wired default is `Hierarchical(SequentialPerAggregatePolicy, SequentialPolicy)`, and `SequentialPerAggregatePolicy` resolves only from `LegacyResources.AGGREGATE_IDENTIFIER_KEY`, which DCB-native stores never set - so on every DCB backend the effective default is full-sequential | ordering | code-inferred (contradicts the Javadoc) | doc: `MSG/core/sequencing/SequencingPolicy.java:34-35` "`SequentialPerAggregatePolicy`: Default event processing policy."; code: `MSG/eventhandling/SimpleEventHandlingComponent.java:61-64`; resolution: `MSG/core/sequencing/SequentialPerAggregatePolicy.java:47` `return Optional.ofNullable(context.getResource(LegacyResources.AGGREGATE_IDENTIFIER_KEY));` | On a DCB backend, two events in one segment are handled concurrently under the default policy (would refute full-sequential); on the aggregate-based backend, events of one aggregate are handled concurrently |
| C33 | `SequentialPolicy` assigns every message the same sequence identifier, forcing strictly sequential handling | ordering | documented | `MSG/core/sequencing/SequentialPolicy.java:27` "SequencingPolicy that requires sequential handling of all messages delivered to a message handler."; `:53` `return Optional.of(FULL_SEQUENTIAL_POLICY);` | Two events are handled concurrently under `SequentialPolicy` |
| C34 | Messages sharing a sequence identifier are chained so the next starts only after the previous completes, within one `ProcessingContext` | ordering | documented | `MSG/core/sequencing/SequencingPolicy.java:74-76` "When two messages have the same identifier (as defined by their equals method), they will be executed sequentially."; implementation `MSG/.../segmenting/SequencingEventHandlingComponent.java` (`chainedSequenceInvocations`, `previousInvocation.thenCompose(...)`) | Two same-key handler invocations overlap in time |
| C35 | **NOT STATED at framework level.** Durability of an acknowledged append is delegated entirely to the backing store; the framework's only statement is prose ("persist events durably") with no fsync or crash-visibility contract | durability | code-inferred (documentation gap; see M18) | `ES/eventstore/EventStore.java:35` "This dual role allows the EventStore to persist events durably"; no fsync/durability language anywhere in `ES/eventstore/EventStorageEngine.java` or `ADOC` (searched `durab`, `fsync`) | An append acked as successful is absent after a `kill -9` and restart of the backing store |
| C36 | `initializeTokenSegments` completes exceptionally with `UnableToClaimTokenException` when a segment already exists, and its behaviour is explicitly undefined when tokens exist that the initialiser does not own | safety | documented (including the undefined case) | `MSG/.../token/store/TokenStore.java:45-47` "will complete exceptionally with an `UnableToClaimTokenException` when a segment to be initialized already exists."; `:53-55` "The exact behavior when this method is called while tokens were already present is undefined in case the token already present is not owned by the initializing process." | Two concurrent bootstraps both succeed, or produce more/fewer than `segmentCount` segments |
| C37 | The `EventStore` is both the storage and the distribution mechanism (it extends `EventBus`), but the timing of publication to subscribers is explicitly implementation-dependent | semantics | documented | `ES/eventstore/EventStore.java:34-35` "As an extension of the `EventBus`, this `EventStore` serves as both the event storage mechanism and the event distribution mechanism."; `:39-41` "The exact timing of when events are published to subscribers is implementation-dependent" | A subscriber receives an event that is not (or not yet) in the store, on a backend where the plan assumed same-transaction publication |
| C38 | A stored token never regresses: a proposed token that does not cover the last stored token (strict regression or incomparable) is skipped with a warning instead of written | safety | documented | `MSG/.../pooled/WorkPackage.java:502-508` "keeping the stored `TrackingToken` monotonic ... is ignored with a warning rather than persisted, so a misbehaving component can never rewind progress on any source."; `:514-522` | The token-store write log for a segment is ever non-monotone under `coversWhenUnwrapped` |
| C39 | **PLACEHOLDER - MODULE ABSENT.** No dead-letter-queue implementation exists in this tree: `SequencedDeadLetterQueue` has no occurrence in `messaging/src/main`, `eventsourcing/src/main` or `modelling/src/main`; only a migration test (`migration/src/test/java/io/axoniq/framework/migration/Axon4ToAxoniq5DeadLetterTest.java`) and a migration doc page (`docs/reference-guide/modules/migration/pages/paths/dlq.adoc`) mention it | semantics | n/a - reserved | (absence verified by search) | Nothing testable in iteration 1. Reserved invariant name `DlqNoHeadOfLineBlock`; claim to be written when the module lands |
| C40 | A node holding more segments than `maxSegmentProvider` allows releases the surplus, blocking re-claim for `tokenClaimInterval` | liveness | code-inferred | `MSG/.../pooled/Coordinator.java:1081-1100` (`releaseSegmentsIfTooManyClaimed`, `releaseUntil(..., clock.instant().plusMillis(tokenClaimInterval))`); default `maxSegmentProvider` = `Short.MAX_VALUE` at `MSG/.../pooled/PooledStreamingEventProcessorConfiguration.java:103` | A node keeps more than `maxSegmentProvider.apply(name)` segments indefinitely, or a released segment is never picked up by another node |

Count: 40 anchored IDs, all filled with real guarantees. 3 are negative results carried as
first-class findings (C9 WEAKER THAN STATED, C12 REFUTED for JPA, C32 DIFFERENT FROM
DOCUMENTED), 2 are documentation gaps carried as claims (C35 NOT STATED, C39 module absent).
0 unassigned, 0 padded.

### A.2 Verified defaults (every number the scenarios manipulate)

| Setting | Plan said | Verified value | Source |
|---|---|---|---|
| TokenStore `claimTimeout` (JPA) | 10 s | **10 s - CORRECT** | `MSG/.../token/store/jpa/JpaTokenStoreConfiguration.java:54-56` `Duration.ofSeconds(10)` |
| TokenStore `claimTimeout` (JDBC) | (not stated) | 10 s | `MSG/.../token/store/jdbc/JdbcTokenStoreConfiguration.java:55-57` `Duration.ofSeconds(10)` |
| TokenStore `claimTimeout` (in-memory) | implied | **DOES NOT EXIST** | `MSG/.../token/store/inmemory/InMemoryTokenStore.java` has no ownership at all (see M11) |
| Processor `claimTimeout` | implied by "claim timeout 10s" in section 1 | **DOES NOT EXIST on the processor** - it is a TokenStore setting only | `MSG/.../pooled/PooledStreamingEventProcessorConfiguration.java:415` (Javadoc reference only) |
| `tokenClaimInterval` | 5 s | **5000 ms - CORRECT** | `MSG/.../pooled/PooledStreamingEventProcessorConfiguration.java:102`; duplicated at `MSG/.../pooled/Coordinator.java:428` |
| `initialSegmentCount` | 16 | **16 - CORRECT** | `MSG/.../pooled/PooledStreamingEventProcessorConfiguration.java:98`; duplicated at `MSG/.../pooled/Coordinator.java:435` |
| `claimExtensionThreshold` | "claim-extension threshold" (unquantified) | **5000 ms, an absolute deadline - NOT `claimTimeout/2`** | `MSG/.../pooled/PooledStreamingEventProcessorConfiguration.java:104`; `MSG/.../pooled/WorkPackage.java:711`; deadline logic `WorkPackage.java:142`, `:483`, `:488`, `:531`. The 2:1 ratio to the store's 10 s timeout is a coincidence of two independent defaults, not a computation |
| `gapTimeout` (core) | 60 s | **60000 ms - CORRECT** | `ES/eventstore/jpa/AggregateBasedJpaEventStorageEngineConfiguration.java:86` `DEFAULT_GAP_TIMEOUT = 60000` |
| `maxGapOffset` (core) | 10000 | **10000 - CORRECT** | `ES/eventstore/jpa/AggregateBasedJpaEventStorageEngineConfiguration.java:84` `DEFAULT_MAX_GAP_OFFSET = 10000` |
| `gapTimeout` / `maxGapOffset` (Spring Boot) | (not considered) | **INVERTED: 10000 ms / 60000** | `SB/JpaEventStorageEngineConfigurationProperties.java:44,46`; applied unconditionally at `SB/autoconfig/JpaEventStoreAutoConfiguration.java:107,109`. See M12 |
| PSEP `batchSize` | (not stated) | **1** | `MSG/.../pooled/PooledStreamingEventProcessorConfiguration.java:105`; `MSG/.../pooled/WorkPackage.java:710` |
| JPA engine `batchSize` | (not stated) | 100 | `ES/eventstore/jpa/AggregateBasedJpaEventStorageEngineConfiguration.java:82` |
| `gapCleaningThreshold` | (not stated) | 250 | `ES/eventstore/jpa/AggregateBasedJpaEventStorageEngineConfiguration.java:83` |
| `lowestGlobalSequence` | (not stated) | 1 | `ES/eventstore/jpa/AggregateBasedJpaEventStorageEngineConfiguration.java:85` |
| `maxSegmentProvider` | (not stated) | `Short.MAX_VALUE` (32767) | `MSG/.../pooled/PooledStreamingEventProcessorConfiguration.java:103` |
| WorkPackage queue soft limit | (not stated) | 1024 | `MSG/.../pooled/WorkPackage.java:89` `static final int BUFFER_SIZE = 1024;` |
| Worker / coordinator executor pool | (not stated) | 4 / 1 | `MSG/.../pooled/PooledStreamingEventProcessorModule.java:111`, `:115` |
| SplitTask post-split claim block | (not stated) | **60 s, hardcoded, not configurable** | `MSG/.../pooled/SplitTask.java:113-114` |
| Coordinator idle re-poll / no-capacity re-poll | (not stated) | 500 ms / 100 ms, hardcoded | `MSG/.../pooled/Coordinator.java:983`, `:1000` |
| `tokenStoreInitRetryInterval` / `MaxRetries` | (not stated) | 100 ms / 30 | `MSG/.../pooled/Coordinator.java:430`, `:431` |

Every number the plan quoted for the core configuration is correct. The two corrections are:
the processor has no `claimTimeout` of its own, and Spring Boot autoconfigure inverts the two
gap settings.

### A.3 Missing claims M1-M18 (verification of the section 1c list, plus new gaps)

| ID | Status | Evidence / what was searched |
|---|---|---|
| M1 in-flight effects at claim-steal boundary | **ACTUALLY SPECIFIED** at `ADOC:426-441` | "the original thread will fail the update. Following this, a rollback occurs on the processing context, resolving most issues"; and "If a rollback is out of the question ... we strongly recommend making the task idempotent." Residual gap: no bound on how long a de-claimed owner may keep executing before it discovers the loss. Keep as a scenario target, not as an undocumented claim |
| M2 gap timeout vs long transactions | **CONFIRMED-GAP** | Searched `gapTimeout` Javadoc (`ES/eventstore/jpa/AggregateBasedJpaEventStorageEngineConfiguration.java:280-292`), `GapAwareTrackingToken` class Javadoc, `ADOC`. The docs state that timed-out gaps are removed for performance and that gaps "may never be filled in if those events never get committed" - nothing states the outcome for an event that DOES commit after its gap timed out |
| M3 cross-node clock-skew bound for claim stealing | **CONFIRMED-GAP** | Searched `skew`, `clockSkew`, `clock skew` in `MSG/.../token/**` and `ADOC`: zero hits. `TokenEntry.expired` compares a remotely-written timestamp to the local clock (`MSG/.../token/store/jpa/TokenEntry.java:159-161`) with no stated tolerance |
| M4 TokenStore unreachable mid-batch | **PARTIALLY SPECIFIED** | Specified for the shared-resource case by the one-transaction design (`MSG/.../pooled/WorkPackage.java:386-387`) plus the abort path (`:403-409`). CONFIRMED-GAP for the split-resource case: nothing states whether handler effects survive a token-store failure |
| M5 afterCommit/marker failure after durable commit | **CONFIRMED-GAP** | `ES/eventstore/EventStorageEngine.java:208-219` documents what `afterCommit` returns, not what happens if it fails. `ES/eventstore/DefaultEventStoreTransaction.java:245-256` propagates the failure into the AFTER_COMMIT phase, so the unit of work reports an error while the events are already durable. This is exactly the S2 ack/durability mismatch |
| M6 resume position after partial sourcing consumption | **ACTUALLY SPECIFIED** at `ES/eventstore/EventStoreTransaction.java:65-75` | "invoked at most once and only after the returned `MessageStream` has been consumed completely ... If the stream terminates with an error, is closed prematurely, or is not consumed to completion, the callback is not guaranteed to be invoked." Promote to C31; retarget the scenario to verifying the stated contract (S20) |
| M7 causal ordering across tags in one segment | **LARGELY DISSOLVED** | With the wired default policy the whole segment is strictly sequential on DCB backends (C32, F-A0-4), so cross-tag order IS preserved by default. Restate as M7': "ordering across tags under an explicitly non-default sequencing policy is unstated" - CONFIRMED-GAP in that narrower form |
| M8 exactly-once with two datasources / XA | **CONFIRMED-GAP** | Searched `XAResource`, `JtaTransaction`, `two-phase`, `XA ` across `messaging/src/main`, `eventsourcing/src/main`, `ADOC`: zero hits. `ADOC:481-483` recommends the same database and is silent on XA |
| M9 Axon Server reconnect/redelivery semantics | **CONFIRMED-GAP, and now exercised** | The earlier evidence ("module absent") was about this tree's directory layout and was the wrong place to look: the connector is a released artefact under `io.axoniq.framework:axon-server-connector`, published to Maven Central. The gap in the *documentation* stands -- nothing states what a severed event stream resumes with -- and it is now measured rather than merely recorded. S11 severs the stream eight times and finds no loss and no duplicate at all; the same arms produced F-19 and F-20, neither of which is documented anywhere. See `formal/CONNECTOR-COMPATIBILITY.md` for which artefact versions can be loaded against this reactor and F-18 for why that is a question at all |
| M10 split/merge vs concurrent appends | **CONFIRMED-GAP** | Nothing in `SplitTask` / `MergeTask` / `Segment` Javadoc addresses concurrent appends. Additional evidence: the split-time claim block is stored in `releasesDeadlines`, a per-Coordinator-instance map (`MSG/.../pooled/Coordinator.java:113`, written at `MSG/.../pooled/SplitTask.java:113-114`), so the guard is per-node only and does not stop another node from claiming a segment mid-split |
| **M11** `InMemoryTokenStore` has no claim semantics | **NEW - CONFIRMED-GAP** | `releaseClaim` is a documented no-op ("the in-memory implementation isn't accessible by multiple processes", `MSG/.../token/store/inmemory/InMemoryTokenStore.java:143`), `fetchToken` never fails on ownership (`:126-137`), `fetchAvailableSegments` == `fetchSegments` (`:191-193`). The class Javadoc (`:44`) says only "thread-safe" and does not warn that C18-C22 do not hold |
| **M12** Spring Boot inverts gapTimeout / maxGapOffset | **NEW - CONFIRMED DEFECT** | `SB/JpaEventStorageEngineConfigurationProperties.java:44` `@DefaultValue("10000") int gapTimeout,` and `:46` `@DefaultValue("60000") int maxGapOffset,` versus core `60000` / `10000`. Applied unconditionally at `SB/autoconfig/JpaEventStoreAutoConfiguration.java:107,109` |
| **M13** publication timing to subscribers unspecified | **NEW - CONFIRMED-GAP** | `ES/eventstore/EventStore.java:39-41` "The exact timing of when events are published to subscribers is implementation-dependent and may occur within the same transaction if the `ProcessingContext` is shared". No cross-backend guarantee for subscribing processors |
| **M14** documented vs wired default sequencing policy | **NEW - CONFIRMED CONTRADICTION** | `MSG/core/sequencing/SequencingPolicy.java:34-35` versus `MSG/eventhandling/SimpleEventHandlingComponent.java:61-64`. See C32 |
| **M15** reset precondition is node-local | **NEW - CONFIRMED-GAP** | `MSG/.../pooled/PooledStreamingEventProcessor.java:301` asserts `!isRunning()` on this JVM only; nothing prevents another node of the same processor from running during the reset |
| **M16** `initializeTokenSegments` is non-atomic in-memory | **NEW - CONFIRMED-GAP** | `MSG/.../token/store/inmemory/InMemoryTokenStore.java:79-94` reads `fetchSegments` then writes; two concurrent bootstraps can both observe empty. The interface Javadoc declares the multi-writer case undefined (C36) rather than safe |
| **M17** partial-batch visibility during commit | **NEW - CONFIRMED-GAP** | See C9 |
| **M18** no framework-level durability contract | **NEW - CONFIRMED-GAP** | See C35 |

---

Appendix B: existing-test inventory.

### B.1 Event store / storage engine suites

| Module | Test class | Path | Layer | Property exercised | Reusable as workload seed |
|---|---|---|---|---|---|
| eventsourcing | `StorageEngineTestSuite` (abstract, 28 `@Test`) | `eventsourcing/src/test/java/org/axonframework/eventsourcing/eventstore/StorageEngineTestSuite.java:69` | suite | DCB append/source/stream, marker derivation, conflict rejection; the only real-thread race is `concurrentTransactionsForOverlappingTagsThrowAnAppendEventsTransactionRejectedException` at `:481` with `taskCount = 10` (`:484`) | YES - the 10-task race is the S1 seed |
| eventsourcing | `AggregateBasedStorageEngineTestSuite` (abstract, 27 `@Test`) | `.../eventstore/AggregateBasedStorageEngineTestSuite.java:70` | suite | aggregate-mode append/source, single-tag constraint, conflict rejection; real-thread race `whenConflictingTransactionsRunOnDifferentThreadsConcurrentlyThenOnlyOneOfThemIsCommited` at `:564` with 4 tasks (`:565-582`) | YES |
| eventsourcing | `StorageEngineBackedEventStoreTestSuite` (abstract, 20 `@Test`) | `.../eventstore/StorageEngineBackedEventStoreTestSuite.java:80` | suite | transaction-level marker/criteria behaviour incl. `narrowedCriteriaShouldAvoidFalseConflict` (`:796`) and `overrideReturningNoneShouldBypassConflictDetection` (`:891`) | YES - the override arms are C2/C7 seeds |
| eventsourcing | `SnapshottingEntityLifecycleHandlerTestSuite` (abstract, 5 `@Test`) | `eventsourcing/src/test/java/org/axonframework/eventsourcing/SnapshottingEntityLifecycleHandlerTestSuite.java:73` | suite | snapshot lifecycle | no |
| eventsourcing | `InMemoryEventStorageEngineTest` | `.../eventstore/inmemory/InMemoryEventStorageEngineTest.java:27` | unit | binds `StorageEngineTestSuite` to in-memory | n/a |
| eventsourcing | `InMemoryStorageEngineBackedEventStoreTest` | `.../eventstore/inmemory/InMemoryStorageEngineBackedEventStoreTest.java:35` | unit | binds the event-store suite to in-memory | n/a |
| spring-boot-autoconfigure | `AggregateBasedJpaEventStorageEngineIT` | `extensions/spring/spring-boot-autoconfigure/src/test/java/org/axonframework/extension/springboot/eventsourcing/eventstore/jpa/AggregateBasedJpaEventStorageEngineIT.java:99` | IT | binds the aggregate suite to HSQLDB in-memory (`:485`) | n/a |
| spring-boot-autoconfigure | `AggregateBasedJpaStorageEngineBackedEventStoreIT` | `.../jpa/AggregateBasedJpaStorageEngineBackedEventStoreIT.java:60` | IT | binds the event-store suite to HSQLDB in-memory (`:133`) | n/a |

**Coverage hole:** `StorageEngineTestSuite` (the DCB suite, and the only one with a 10-way
race) has **exactly one** binding, and it is in-memory. No persistent store ever runs the DCB
conflict tests. `AggregateBasedStorageEngineTestSuite` has exactly one binding, HSQLDB.

### B.2 Event processing (messaging)

| Test class (path prefix `messaging/src/test/java/org/axonframework/messaging/`) | `@Test` | Layer | Property exercised | Seed |
|---|---|---|---|---|
| `eventhandling/processing/streaming/pooled/PooledStreamingEventProcessorTest.java` | 65 | unit | claim/release (`:415`, `:881`, `:1655`), coordinator claim extension (`:948`, `:994`), abort on failed extend (`:1094`), split (`:1767`), split+merge of 4 (`:1804`), reset preconditions (`:1970`), reset positions (`:1978`, `:2114`), `isReplaying` (`:2081`), replay redelivery (`:1320`) | YES for S4/S8/S9 shapes |
| `.../pooled/CoordinatorTest.java` | 21 | unit | coordination scheduling, claim failure/abort/retry | partial |
| `.../pooled/WorkPackageTest.java` | 33 | unit | batching, token advance/persist, claim-extension threshold, abort | YES for C15/C38 |
| `.../pooled/ClaimTaskTest.java` | 5 | unit | single-segment claim instruction outcome | no |
| `.../pooled/SplitTaskTest.java` | 6 | unit | split from work package vs after claiming; delete-before-reinit ordering | YES for S9 |
| `.../pooled/MergeTaskTest.java` | 10 | unit | merge with both/mixed claims, lower-token-first, failure paths | YES for S9 |
| `.../pooled/PooledStreamingEventProcessorTestSuite.java` | 2 (abstract, `:35`) | suite | split-then-merge round trip; both children claimed | YES |
| `.../pooled/SegmentProgressStrategyTestSupport.java` | 0 (abstract, `:66`) | support | `WorkPackageHarness` (`:170`), `RecordingBatchProcessor` (`:254`) | **YES - closest existing thing to a harness; reuse rather than rebuild** |
| `.../progress/TokenStoringProgressStrategyTest.java` | 5 | unit | when progress is flushed | no |
| `.../token/store/inmemory/InMemoryTokenStoreTest.java` | 12 | unit | init/update/query; **no claim, no steal, no concurrency test** (consistent with M11) | no |
| `.../token/store/jpa/JpaTokenStoreTest.java` | 27 | unit | `claimTokenConcurrently` (`:403`), `stealToken` (`:416`), `extendingLostClaimFails` (`:443`), fetch fails during merge/split (`:274-306`), rollback (`:475`) | YES for S4 |
| `.../token/store/jdbc/JdbcTokenStoreTest.java` | 27 | unit | as JPA plus `claimTokenConcurrentlyAfterTimeLimit` (`:417`) and `fetchTokenFailsWhenClaimedByOtherNode` (`:521`) | YES for S4 - **the only existing claim-timeout test** |
| `.../segmenting/SegmentTest.java` | 12 | unit | split/merge algebra, mask, matches (C23/C24) | YES |
| `.../token/GapAwareTrackingTokenTest.java` | 33 | unit | gap tracking, advance, covers; real-thread `gapAwareTokenConcurrency` (`:42`) and `_HighConcurrency` (`:86`) | YES for S7 |
| `.../token/ReplayTokenTest.java` | 31 | unit | replay wrapping, `isReplay`, reset position (C27) | YES for S8 |
| `.../token/store/ReplayTokenWrappingComplexTokenTest.java` | 28 | unit | ReplayToken over Merged/GapAware tokens | YES for S8/S9 |
| `.../token/MergedTrackingTokenTest.java` | 20 | unit | merged-token advance/lower-bound | YES for S9 |
| `.../token/TrackingTokenUtilsTest.java` | 12 | unit | upper/lower-bound utilities (C38 primitive) | YES |
| `core/sequencing/*Test.java` (10 classes) | 44 total | unit | key-derivation only | partial |
| `eventhandling/.../SequencingEventHandlingComponentTest.java` | 2 | unit | sequencing delegation | no |
| Spring-bound PSEP ITs: `PooledStreamingJdbcTokenStoreIT.java:64`, `PooledStreamingJpaTokenStoreIT.java:51` (both under `extensions/spring/spring-boot-autoconfigure/src/test/.../streaming/pooled/`) | - | IT | bind `PooledStreamingEventProcessorSpringTestSuite` (`:44`) to real token stores | **YES - the only existing durable-token-store processor ITs; the P2/P3 starting point** |

**Coverage hole:** no test asserts that a sequencing policy preserves handler-invocation
order under a live multi-threaded processor. All 10 sequencing tests are key-derivation unit
tests.

### B.3 integrationtests module

| Item | Path:line | Note |
|---|---|---|
| `AbstractIT` (abstract) | `integrationtests/src/test/java/org/axonframework/integrationtests/testsuite/AbstractIT.java:45` | `:65` `testInfrastructure()`, `:73` `applicationConfigurer()`, `:81` `tearDown()`, `:99` `startApp()`, `:112` `purgeData()` |
| `TestInfrastructure` (interface) | `.../testsuite/infrastructure/TestInfrastructure.java:35` | `:41` `start()`, `:49` `configureInfrastructure(ComponentRegistry)`, `:57` `purgeData()`, `:67` `stop()` |
| `InMemoryTestInfrastructure` | `.../testsuite/infrastructure/InMemoryTestInfrastructure.java:45` | **the only implementation**; `:55` disables the Axon Server configuration enhancer by FQCN string |
| Abstract class declarations under `testsuite/` | 23 (A0-verified count) | direct `AbstractIT` subclasses: `administration/AbstractAdministrationIT.java:42`, `course/SealedClassCourseIT.java:35`, `multientity/MultiEntitySameEventHandlersIT.java:59`, `student/AbstractStudentIT.java:58` |
| Concrete leaf ITs under `testsuite/` | 19 (A0-verified count) | all bound to the single in-memory infrastructure |
| `AxonServerTestInfrastructure` | `.../testsuite/infrastructure/AxonServerTestInfrastructure.java` | **implemented.** Selected at run time with `-Dhunt.backend=axonserver`, like every other store; replaces the event store only, with the connector's configuration enhancer disabled so commands and queries stay local and a divergence is attributable to the event store. Isolation is a purge of one shared Dynamic Consistency Boundary context, because the standalone edition refuses a context per suite |
| Testcontainers declared | `integrationtests/pom.xml:118-120` (junit-jupiter), `:124-126` (oracle-xe); `eventsourcing/pom.xml:173-174`, `:178-179` (mysql); BOM at `build/parent/pom.xml:381-383`, version at `:105` | **dead in both modules** - the only `org.testcontainers` references in `integrationtests` are `import static org.testcontainers.shaded.org.awaitility.Awaitility.await;` (shaded Awaitility, not a container) at `.../testsuite/student/EventProcessingAnnotatedStateBasedPooledStreamingIT.java:46` and `.../testsuite/student/SubscribableEventSourceWithEventAppenderTest.java:34` |
| Real container usage anywhere in the repo | `extensions/spring/spring/src/test/java/org/axonframework/extension/spring/util/MysqlTestContainerExtension.java:22` and three `examples/` ITs | none in messaging / eventsourcing / modelling / integrationtests |

### B.4 Raw counts

| Module | test .java files | `@Test` | `@ParameterizedTest` | `@RepeatedTest` |
|---|---|---|---|---|
| messaging | 258 | 2091 | 34 | 3 |
| eventsourcing | 48 | 378 | 1 | 1 |
| modelling | 89 | 306 | 2 | 0 |
| integrationtests | 104 | 76 | 0 | 0 |

### B.5 Surefire / failsafe rerun

Not configured in any pom. Set as CLI flags only:

| Path:line | Value |
|---|---|
| `.github/workflows/pullrequest.yml:45` | `-Dsurefire.rerunFailingTestsCount=5 -Dfailsafe.rerunFailingTestsCount=5` |
| `.github/workflows/pullrequest.yml:52` | `-Dsurefire.rerunFailingTestsCount=5 -Dfailsafe.rerunFailingTestsCount=5` |
| `.github/workflows/main.yml:47` | `-Dsurefire.rerunFailingTestsCount=5 -Dfailsafe.rerunFailingTestsCount=5` |
| `.github/workflows/main.yml:53` | `-Dsurefire.rerunFailingTestsCount=5 -Dfailsafe.rerunFailingTestsCount=5` |
| `.github/workflows/examples.yml:66-67` | `-Dsurefire.rerunFailingTestsCount=5`, `-Dfailsafe.rerunFailingTestsCount=5` |

Plugin declarations without rerun config: surefire `build/parent/pom.xml:525`, failsafe
`build/parent/pom.xml:641` and `pom.xml:291`, `:299`.

### B.6 Gaps (each with evidence)

| Gap | Evidence |
|---|---|
| No chaos / fault injection / nemesis of any kind | searched `toxiproxy`, `testcontainers`, `GenericContainer`, `DockerComposeContainer`, `@Testcontainers`, `docker`, `kill`, `SIGKILL`, `chaos`, `nemesis`, `fault.?inject`, `partition`, `networkdisrupt`, `pumba` across `messaging/src/test`, `eventsourcing/src/test`, `modelling/src/test`, `integrationtests/src/test`: zero hits |
| No crash-recovery test | no process/container kill anywhere in the four modules |
| No multi-node test | no test instantiates two processors of the same name against one shared durable token store |
| No history-based oracle | searched `operationHistory`, `invocationOrder`, `linearizab`: zero hits. Closest is `RecordingBatchProcessor` (`messaging/src/test/.../pooled/SegmentProgressStrategyTestSupport.java:254`) and the `recordedEvents` lists at `.../PooledStreamingEventProcessorTest.java:1322,1377`, which assert set membership (`containsOnly`), not order |
| No property-based / seeded testing | jqwik, quickcheck, lincheck, jazzer: absent from every pom (the only `net.jqwik` string is an env detector at `update/src/main/java/org/axonframework/update/detection/TestEnvironmentDetector.java:73`). No `new Random(<seed>)` and no `.setSeed(` anywhere; unseeded `ThreadLocalRandom` appears in ~17 MessageStream tests and one real branch driver at `messaging/src/test/java/org/axonframework/messaging/core/unitofwork/ProcessingLifecycleTest.java:221`, so those failures are not reproducible |
| No Axon Server anywhere | closed. Two arms exist: a hunt backend (`axonserver`, plus a breakable `axonserver-chaos`) and a `TestInfrastructure`. Both link a released connector against this reactor with one method supplied by the harness; `formal/CONNECTOR-COMPATIBILITY.md` records the combination |
| No persistent-store binding of the DCB suite | see B.1 |
| No live-processor ordering test for sequencing policies | see B.2 |

---

Appendix C: claim x scenario coverage matrix.

`P` = primary falsification path, `s` = secondary. Scenarios S1-S15 from section 5 (as
sharpened in section 11.2); S16-S20 proposed in section 11.3. TLA columns: `DA` =
`DcbAppend.tla`, `TC` = `TokenClaim.tla`.

### C.0 Backend coverage, added in P3a

A claim is only covered where a backend can express it. The matrix above says which scenario falsifies a claim; this one
says which store the claim can be falsified on, and it is the reason a verdict vector records "not applicable" instead of
passing quietly.

`Y` = the store can express the claim; `n/a` = it cannot, and the suite says so; `F` = the claim was falsified there.

| ID | Claim, in short | in-memory | hsqldb-tokens | postgres-jpa | postgres-jpa-split-tokens |
|---|---|---|---|---|---|
| C1 | append rejected after marker | Y | Y | n/a (no boundary; a unique constraint instead) | n/a |
| C2 | `AppendCondition.none()` disables conflict detection | Y | Y | **F** (finding F-14) | **F** (finding F-14) |
| C3 | ORIGIN makes every matching event a conflict | Y | Y | n/a (ORIGIN and INFINITY share a branch) | n/a |
| C4 | append in prepare-commit, visible only after commit | Y | Y | **F** (finding F-17: the database transaction's commit and `AppendTransaction.commit()` are unordered) | **F** |
| C29, C35 | an acknowledged append is durable | n/a (nothing to kill) | n/a | **Y** -- held across 8 network cuts and 2 kill-and-restart cycles | Y |
| C5, C6, C7 | marker derivation from sourcing | Y | Y | **F** (finding F-15, `lowerBound` unimplemented) | **F** |
| C8 | deferred conflict check | Y | Y | n/a (the check is the database's) | n/a |
| C9, C10 | rejected append leaves nothing; batch atomicity | Y | Y | Y | Y |
| C13, C14 | gap-aware token, gap timeout | n/a (no gaps in an in-heap map) | n/a | **F** (finding F-16, on both configuration paths) | **F** |
| C15, C16 | batch and token in one transaction | n/a (nothing transactional) | partial (token only) | **Y** | Y, as two resources |
| C17 | a steal may cause a duplicate | n/a (no ownership) | Y | Y | Y |
| C18-C22 | single-owner claim, steal semantics | n/a (no ownership) | Y | Y | Y |
| C23-C25 | split and merge preconditions | n/a (no ownership) | Y | Y | Y |
| C26-C28 | reset, replay token | n/a (no ownership) | Y | Y | Y |
| C29 | rollback discards | Y | Y | Y | Y |
| C32-C34 | sequencing policies | Y | Y | **Y, and the differential arm F-6 needs** (this store populates the legacy aggregate-identifier resource the wired default reads) | Y |
| C38 | stored token never regresses | n/a (no recorded writes) | Y | Y | Y |

Two rows are worth reading twice. **C13 and C14 have no other home**: gap awareness exists because this engine's global
index comes from a sequence taken before the transaction commits, and an in-heap map has no such thing, so the
highest-expected-yield scenario in the plan (S7) could not have been written before this backend existed. And **C32-C34**
close the differential the plan asked for in section 11.2: the wired default sequencing policy resolves from a legacy
aggregate-identifier resource that DCB stores never set (finding F-6) and that this store does set, so arm (b) of S10 is
now buildable.

| ID | S1 | S2 | S3 | S4 | S5 | S6 | S7 | S8 | S9 | S10 | S11 | S12 | S13 | S15 | S16 | S17 | S18 | S19 | S20 | DA | TC |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| C1 | P | s | | | | | | | | | | | | | | | | | | P | |
| C2 | P | | | | | | | | | | | | | | | | | | | s | |
| C3 | P | | | | | | | | | | | | | | | | | | | s | |
| C4 | s | P | P | | | | | | | | | | | | s | | | | | | |
| C5 | P | | | | | | | | | | | | | | | | | | | s | |
| C6 | P | | | | | | | | | | | | | | | | | | | s | |
| C7 | P | | | | | | | | | | | | | | | | | | | s | |
| C8 | P | s | | | | | | | | | | | | | | | | | | P | |
| C9 | s | | P | | | | | | | | | | | | P | | | | | | |
| C10 | s | | s | | | | | | | | | | | | P | | | | | | |
| C11 | P | | | | | | | | | | | | | | | | | | | | |
| C12 | s | | | | | | P | | | | | | | | | | | | | | |
| C13 | | | | | | | P | | | | | | | | | | | | | | |
| C14 | | | | | | | P | | | | | | | | | | | | | | |
| C15 | | | s | P | P | s | | s | s | | | s | | | | s | | | | | |
| C16 | | | | P | P | P | | | | | | | | | | | | | | | |
| C17 | | | | P | | P | | | s | | s | | | | | | | | | | s |
| C18 | | | | P | | | | | | | | | | P | | | s | | | | P |
| C19 | | | | P | | | | | | | | | | s | | | | | | | P |
| C20 | | | | P | | s | | | | | | | | | | | | | | | P |
| C21 | | | | P | | | | | | | | | | | | | | | | | s |
| C22 | | | | P | | | | | | | | | | s | | | | | | | s |
| C23 | | | | | | | | | P | | | | | | | | | | | | |
| C24 | | | | | | | | | P | | | | | | | | | | | | |
| C25 | | | | | | | | | P | | | | | | | | | | | | |
| C26 | | | | | | | | P | | | | | | | | | | | | | |
| C27 | | | | | | | | P | | | | | | | | | | | | | |
| C28 | | | | | | | | P | | | | | | | | | | | | | |
| C29 | | P | P | | | | | | | | | s | | | | | | | | | |
| C30 | s | | | | | | | | | | | | | | | | | | P | | |
| C31 | | | | | | | | | | | | | | | | | | | P | | |
| C32 | | | | | | | | | s | P | | | | | | | | | | | |
| C33 | | | | | | | | | | P | | | | | | | | | | | |
| C34 | | | | | | | | | s | P | | | | | | | | | | | |
| C35 | | s | | | s | | | | | | | P | | | | | | | | | |
| C36 | | | | s | | | | | | | | | | P | | | | | | | |
| C37 | | s | s | | | | | | | | s | | | | | | | | | | |
| C38 | | | | P | s | | | P | P | | | | | | | -- | | | | | |
| C39 | | | | | | | | | | | | | | | | | | | | | |
| C40 | | | | s | | | | | | | | | | | | | P | | | | |
| M1 | | | | P | | s | | | | | | | | | | | | | | | s |
| M2 | | | | | | | P | | | | | | | | | | | | | | |
| M3 | | | | P | | | | | | | | | | | | | | | | | P |
| M4 | | | | s | s | P | | | | | | | | | | | | | | | |
| M5 | | P | | | | | | | | | | s | | | | | | | | | |
| M6 | | | | | | | | | | | | | | | | | | | P | | |
| M7' | | | | | | | | | s | P | | | | | | | | | | | |
| M8 | | | | | | | | | | | | | | | | | | | | | |
| M9 | | | | | | | | | | | P | | | | | | | | | | |
| M10 | | | | s | | | | | P | | | | | | | | | | | | |
| M11 | | | | P | | | | | | | | | | s | | | | | | | |
| M12 | | | | | | | s | | | | | | | | | | | P | | | |
| M13 | | s | s | | | | | | | | s | | | | | | | | | | |
| M14 | | | | | | | | | | P | | | | | | | | s | | | |
| M15 | | | | | | | | P | | | | | | | | | | | | | |
| M16 | | | | | | | | | | | | | | P | | | | | | | |
| M17 | | | P | | | | | | | | | | | | P | | | | | | |
| M18 | | s | | | | | | | | | | P | | | | | | | | | |

S14 is the formal scenario itself and is represented by the `DA` and `TC` columns. S13
(liveness umbrella) touches every row through the mandatory `LivenessChecker` and is omitted
as a column to avoid a meaningless all-`s` line; its unique arm is the fault storm.

### C.1 Claims with zero coverage

| ID | Disposition |
|---|---|
| C39 (DLQ) | **accepted residual** - no module exists in this tree to target (Appendix A.1 C39). Reserved invariant name `DlqNoHeadOfLineBlock`; enters the matrix when the module lands |
| M8 (XA / two datasources) | **accepted residual for iteration 1** - already declared out of scope in section 5b. Requires an XA transaction manager and two resource managers in the harness; the cost is not justified before S5/S6 have produced findings on the single-resource and split-resource arms |

Every other C and M id has at least one `P`. Claims that gained coverage only through the
proposed additions: C9/M17 (S16), C31/M6 (S20), C38 (S17), C40 (S18), M12 (S19). If S16-S20
are not adopted, those five become uncovered and must be listed as accepted residuals with
reasons - they are cheap, so adoption is the recommendation.

### C.2 Redundancy check

| Scenario | Verdict |
|---|---|
| S13 liveness umbrella | **Fully redundant on claims** - `LivenessChecker` runs in every scenario per D3. Cheap, so keep; its only unique content is the fault-storm-with-heal arm. Do not budget release-tier time for it separately |
| S3 vs S2 (C4, C29) | Not redundant: S3 injects phase-level failures at L1 (deterministic, attributable), S2 injects network faults at L3 (realistic, ambiguous). Different failure classes, both needed |
| S5 vs S6 (C16) | Not redundant: they are the two arms of the same conditional claim (shared resource vs split resource). C16 is only falsifiable by running both |
| S1 vs DA (C1, C8) | Deliberate double coverage per section 5b ("every safety cluster has >= 2 independent falsification paths"). Keep |
| S11 (M9) | **Sole path for M9 and expensive** (external artifacts, container, network faults). Justified only because M9 is otherwise entirely uncovered; if the artifacts are unavailable it degrades to `SKIPPED`, and M9 then becomes an accepted residual for that run |
| S12 (C35, M18) | **Sole path for the durability claims and expensive.** Justified: C35 is a documentation gap and the only way to establish the real behaviour is to crash a real store |
| S16-S20 | All cheap (smoke tier, no containers except S19). No redundancy concern |

### C.3 What shipped, per scenario, as of the L2 layer

The matrix above is the plan's intent. This section is what exists, because a matrix that records intent and is read as
coverage is the same mistake as a green test that never ran.

| Scenario | Shipped | Tier run | Verdict | Notes |
|---|---|---|---|---|
| S1 `dcb_append_rejected_after_marker_under_contention` (+ faulted and single-writer arms) | yes | SMOKE | PASS | |
| S3 `uncommitted_never_visible_rolledback_never_delivered` (3 phase arms) | yes | SMOKE | PASS | after-commit arm produced F-8 |
| S4 `at_most_one_segment_owner_with_skew` (3 skew arms) | yes | SMOKE | PASS / PASS / expected violation | the double-timeout arm quantifies F-10 |
| S8 `replay_sees_full_prefix_and_flags_redelivery` (+ cross-node arm) | yes | SMOKE | INCONCLUSIVE (replay repeats reported) | precondition asserted; cross-node arm documents M15 and produced F-12 |
| S9 `split_merge_no_loss_no_dup_under_load` (+ single-segment merge arm) | yes | SMOKE | INCONCLUSIVE (merge repeats reported) | produced F-11 and the F-5 correction |
| S10 `sequencing_policy_order_preserved` (3 policy arms) | arms a, c, d | SMOKE | PASS / INCONCLUSIVE | arm b needs an aggregate-based backend; produced F-6 and F-7 |
| S15 `concurrent_bootstrap_initializes_segments_exactly_once` (+ churn arm) | yes | SMOKE | PASS | produced F-9 |
| S16 `partial_batch_never_visible` | yes | SMOKE | PASS | produced F-3 |
| S17 `stored_token_never_regresses` | **as an invariant, not a scenario** | -- | -- | `StoredTokenNeverRegresses` and `StoredTokenCoversDeliveredEvents` ship in `StoredProgressChecker` and run against every history, so C38 is covered by every cluster arm rather than by a scenario of its own. A dedicated arm driving a deliberately regressing progress strategy is not built. |
| S2 `commit_ack_matches_durability_under_partition` | yes | SMOKE | FAIL (F-14 only; durability held) | 8 network cuts landed with the proxy's own reported state as evidence; 12 events left ambiguous, 0 of them stored; `AcknowledgedAppendIsDurable` clean. Produced the `RejectedAppendLeavesNoEvents` correction and the `StoredProgressChecker` not-applicable rule. |
| S7 `no_event_skipped_by_gap_timeout` (+ Spring-defaults arm) | yes | SMOKE | FAIL (real skip) | 3 committed events never delivered on each arm, decided rather than excused. Produced findings F-16 and F-17. |
| S12 `crash_recovery_no_acked_loss_postgres` | yes | SMOKE | FAIL (F-14 only; durability held) | 2 kill-and-restart cycles landed with exit code 137 and the store's own recovery lines; 138 acknowledged appends all present exactly once in a fresh-connection scan. |
| S11 `axonserver_stream_resume_no_loss_no_silent_dup` | yes | SMOKE | FAIL (F-19 only; **no loss and no duplicate**) | 8 severances of the read side's event stream, each evidenced by the proxy's own reported enabled state either side of the cut. The stream-resume property the scenario exists for **held**: `readableEvents=338 deliveredEvents=338 quiesced=true`, zero undelivered and zero repeats of any kind, licensed or otherwise. The arm's overall verdict is FAIL on a different invariant -- the append-side divergence F-19. Runs on `axonserver-chaos`: framework `5.3.0-SNAPSHOT`, connector `5.2.2`, image `2026.0.0`, with `EventStorageEngine.source(SourcingCondition, ProcessingContext)` supplied by the harness. |
| S11's siblings on Axon Server: the kill arm and the partition arm | yes | SMOKE | see notes | Both are S12 and S2 pointed at `axonserver-chaos` with nothing else changed, which is the extensibility charter's backend clause applied to the fault layer. The kill arm landed 2 cycles with exit code 137 and two distinct restart lines carrying the server's own timestamps, and is the first arm in the suite on which `AcknowledgedAppendIsDurable` is **decided** rather than declined. The partition arm landed 8 cuts and produced finding F-20. |
| S5, S6, S13, S18, S19, S20 | no | -- | NOT-RUN | S5 needs a transactional read model and an applied-count oracle; S6 needs a split-store cluster arm; S13's checkers already run everywhere; S18, S19, S20 remain cheap and unclaimed |

Two claims changed their primary path because S17 became an invariant rather than a scenario, and one gained coverage the
plan did not anticipate:

- **C38** is now primary on S4, S8 and S9 rather than on S17, because the monotonicity of a stored token is checked on
  every history the cluster arms produce -- including across a replay and across a merge, both of which rewind it
  legitimately and neither of which a dedicated S17 arm would have exercised.
- **C15** is now primary on S4 rather than secondary, through `ClaimHandoverRewindsAtMostOneBatch`: the one-transaction
  guarantee's only externally visible consequence is how far a stored token has fallen behind when somebody reads it back,
  and that is what the ownership arm measures.
- **C16** gains a primary path on S4 for the same reason, on the at-least-once half of the conditional only. The
  exactly-once half still has no deployment in this tree that can provide it.
