# Fault catalogue -- mechanism, evidence, cleanup

**This file is self-contained.** Every fault must satisfy three conditions, and a fault missing
any of them makes the run inconclusive rather than a pass:

1. **It actually fires.**
2. **It produces evidence that it fired** -- from the thing perturbed, not from the injector.
3. **It is reversible**, so the next scenario starts clean.

The evidence rule and its refinements are in `method-essentials.md` section 3. This file is the
mechanism-by-mechanism catalogue: what to use, what proves it landed, and how to undo it.

**Faults compose in stages.** Single faults first; pairs once the single-fault behaviour is
understood; storms only where nobody reads the result by hand. Compound-first destroys
attribution -- something broke and there are four candidate causes with no way to separate
them.

**The schedule is fixed:** warmup, fault window or windows, heal, settle, then judge. A verdict
taken during a fault manufactures false positives at run boundaries.

---

## Process and lifecycle faults

| Fault | Mechanism | Evidence it landed | Cleanup |
|---|---|---|---|
| Hard kill | `kill -9 <pid>`; for a container, `docker kill` | The exit log line, plus the restart timestamp; for a container, the runtime reporting `exited` with the signalled exit code | None -- the process is gone |
| Graceful stop | `kill -TERM` | The shutdown log lines, in order | None |
| Freeze -- the GC-pause analogue | `kill -STOP`, then `kill -CONT` | A gap in the heartbeat log; for a container, the runtime reporting itself paused | `kill -CONT` |
| Container restart | `docker restart`, or a pod kill | The container event, plus readiness afterwards | Wait for ready |
| Thread-pool starvation | Saturate the pool the target shares | The queue depth, plus the latency of the starved path measured separately | Stop the load |

**In this suite.** Kill and freeze exist for containers, with the container's own report as
evidence. **The freeze primitive was built, verified by hand, and no scenario declares one**, so
the whole class of failure a kill cannot produce -- a process that is stalled but alive, holding
its leases -- is unexercised. That is recorded as a gap, not as coverage. See F-13, which is the
finding that class predicts.

---

## Network faults

| Fault | Mechanism | Evidence it landed | Cleanup |
|---|---|---|---|
| Full partition, both directions | A TCP proxy disabled; `iptables` drop; a `tc` qdisc | The proxy's own API reporting `{"enabled":false}` then `{"enabled":true}`; or the dropper's packet counter, **plus** timeouts observed on both sides | Re-enable the proxy; reverse the rule |
| Asymmetric partition -- A reaches B, B does not reach A | `iptables` drop on one direction only | Timeouts on one side only. **Both halves must be checked**: one-side-only is the whole point | Reverse |
| Packet loss | `tc qdisc add ... netem loss 30%` | netem statistics, plus a retry-count rise | `tc qdisc del` |
| Latency injection | `tc ... netem delay 200ms` | A latency-histogram shift on the affected path | `tc qdisc del` |
| Bandwidth cap | `tc ... htb rate 1mbit` | A throughput drop | `tc qdisc del` |
| Minority-side partition | Drop minority-to-majority in both directions | Timeouts on the minority; the majority continues | Reverse |
| Majority-side partition | The inverse: drop traffic into the majority | The majority fails to make progress | Reverse |
| Leader or coordinator isolation | Partition just the current leader, identified live from cluster status | The leader sees its peers go silent; a re-election follows | Reverse when the re-election completes |
| Packet duplication | `tc qdisc add ... netem duplicate 10%` | A duplicate-packet counter, or a double-request counter | `tc qdisc del` |
| Packet reordering | `tc qdisc add ... netem reorder 25% delay 50ms` | An out-of-order-arrival counter | `tc qdisc del` |
| Metadata-store outage | Drop traffic to the coordination service by address and port | Dependent operations failing with an unreachable error | Reverse |

**The single most common failure here:** the rule lands in the wrong chain, or the qdisc on the
wrong interface. **Capture statistics from the dropper itself and from one victim, and record
both.** A proxy that reports enabled while the framework is talking directly to the backend is a
fault that never happened and a run that reports a pass.

**In this suite.** A TCP proxy in front of the container-backed store, with the proxy's own API
as evidence -- the strongest form available. Untested rows worth naming: **asymmetric
partition** (real partitions are frequently one-way; our arms are symmetric),
**duplication and reordering** (which the framework's ordering claims are exactly about), and
**latency injection** short of a cut (the limping-store case, which is worse than a dead one).

---

## Storage faults

| Fault | Mechanism | Evidence it landed | Cleanup |
|---|---|---|---|
| Disk full | Fill the filesystem with a junk file | Write errors in the store's own log | Remove the junk |
| Fsync loss, power loss | A block-level tracer; a filesystem mounted without barriers; or a process kill between the write and the fsync | A crash, then a mismatch on recovery | Reset the disk image |
| Slow disk | cgroup IO throttling | An IO-latency-histogram shift | Remove the throttle |
| Bit flip on read or write | `dm-flakey`, `dm-error`, or in-application injection | Checksum or integrity failures | Remove the device-mapper target |
| Fsync failure | `dm-flakey` configured to drop fsync; or syscall interception | Recovery uncovers lost writes | Remove the target |
| Typed corruption | `dm-flakey` returning `EIO`; `dm-error`; or writing random bytes into the device | The store surfacing integrity errors | Remove the target |
| Backup or restore race | Trigger a backup mid-workload, then a restore mid-workload, then continue | Restore-time markers in the log; the oracle compares before and after | Wait for the restore to complete |

**In this suite.** The in-process storage-engine wrapper injects failures at the framework's own
boundary -- an append that throws, a commit that no-ops, a read that returns a torn view. That
covers the *application* side of these rows. **The disk side is entirely absent**: no fsync
loss, no disk full, no corruption. The consequence to state honestly is that every durability
verdict in the suite is a verdict about the framework's handling of a *reported* failure, never
about an unreported one.

---

## Time faults

| Fault | Mechanism | Evidence it landed | Cleanup |
|---|---|---|---|
| Clock step | `date -s` inside the container; a fake-time preload library | The recorded jump; timestamp drift in the log | Reset the clock; drop the preload |
| Clock rate change | A fake-time library with a rate multiplier | Derived metrics drifting | Drop it |
| Timescale compression | A harness-wide dimension scaling every configured duration | The scaled values recorded in the history header | None -- it is a run parameter |

**Two traps this project paid for.**

- **A process-global clock seam is not a per-node seam.** Claim expiry here reads a single
  static clock reference for the whole process, so setting "a node's" clock sets every node's.
  Do not assume that because one component takes a clock, the path you care about does.
- **Compression is not uniform.** Several framework durations do not compress -- a hardcoded
  coordinator re-poll, a hardcoded post-split re-claim block -- and a timeout compressed below
  what a real store can answer in produces mass redelivery that looks like a framework defect.
  See `traps.md` section 6, and F-5.

When a clock seam is unreachable, look for an **algebraically equivalent knob**. Expiry is
`stamp + timeout < now`. A node whose clock reads `delta` ahead evaluates
`stamp + timeout < now + delta`, which is the same inequality as
`stamp + (timeout - delta) < now` -- so shortening one node's view of the timeout reproduces
that node's decisions exactly, with no clock substitution at all. **State what the emulation
does not model** -- here, the timestamps that node writes -- everywhere it appears.

---

## Cluster-level faults

| Fault | Mechanism | Evidence it landed | Cleanup |
|---|---|---|---|
| Rolling restart in the wrong order | Restart nodes in an order that crosses ownership transitions | Ownership changes during the restarts, counted | Finish the cycle |
| Split-brain attempt | Combine a partition with timeout pressure | Competing-owner log lines on both sides | End the partition |
| Slow follower or slow node | Latency injection on one node only | A per-node latency skew; back-pressure on the others | `tc qdisc del` |
| Mixed-version cluster | Half the nodes on version N, half on N+1 | Version-mismatch lines; the membership view | Finish the upgrade, or roll back |
| Rolling upgrade | Upgrade nodes one at a time with the workload running | A per-node version flip in status; the workload error rate | Finish the upgrade |
| Configuration divergence | One node holds a different value that the system does not propagate | The value read back per node; divergent behaviour | Reset it |
| Credential or secret divergence | Rotate on all but one node | An authentication-failure counter on the stale node | Rotate the last one |
| Compaction or cleanup during reads | Trigger the store's compaction while a read-heavy workload is in flight | A compaction-active line; a read-latency skew | Wait for it to finish |
| Rebalance or reconfiguration during writes | Trigger a membership or ownership change during a sustained write workload | An in-progress metric; per-unit ownership transitions | Wait for it to finish |

**In this suite.** Split-brain attempts and ownership churn under load are covered -- that is
what the multi-node arms and the split/merge arms are. **Mixed-version and rolling upgrade are
not covered at all**, and F-18 is an upgrade-shaped finding found by a static gate rather than
by an upgrade arm. Configuration divergence is uncovered, and F-1 says the configuration paths
already disagree with each other on one machine, never mind across two.

---

## Framework-level faults -- what this suite injects in process

These have no counterpart in the generic catalogue, because they perturb a framework boundary
rather than an operating-system one. They are listed here so the catalogue is complete for this
repository.

| Fault | Where it is injected | Evidence it landed |
|---|---|---|
| Append rejection | The storage-engine wrapper | A fire count taken inside the wrapper, plus the caller's recorded failure |
| Late commit | The storage-engine wrapper | The commit's own ordering against the transaction's, recorded |
| Torn read | The storage-engine wrapper | A recorded note on a read that observed a partial batch |
| Claim failure | The token-store wrapper | The failed claim recorded, plus the segment's subsequent ownership |
| Transaction-phase failure | A lifecycle hook | The phase that failed, and whether a rollback was requested after commit |
| Probabilistic perturbation | A harness wrapper, seeded | A fire count per fault, per seed |

**Two rules that govern all of them.**

- **Never add a seam to framework code.** The only substitution anywhere in this harness is a
  wrapper around a real component. A suite that modifies what it measures cannot tell you
  whether the release is broken.
- **Probabilistic injection points live in harness wrappers, never in framework code.**

---

## Anti-patterns

- **"Inject a fault and wait five seconds."** The fault may take longer to propagate. Gate on
  an **event** -- a timeout observed, a replica marked down, an ownership change recorded --
  not on wall clock.
- **"Reverse the fault and immediately check correctness."** Recovery takes time. Gate the
  oracle on quiescence, not on the moment of un-injection.
- **"Trust that the injector did the thing."** Always cite proof from the perturbed side.
- **"Mutate the raw backend, bypassing the layer the system writes through."** The most common
  silent no-op: deleting a row, corrupting a value or planting a key directly in the store when
  the framework writes through a prefix, a context, a schema, a converter or a tag-joining
  scheme. The fault lands on bytes the framework never reads, and the arm reports a pass.
  **Mirror the framework's own write path when injecting at the storage layer, and verify by
  reading the value back through the same path before counting the fault as landed.**
- **A primitive that cannot confirm its own effect must report a miss**, and the run is
  inconclusive. Each primitive here verifies its effect from the infrastructure before counting
  a fire.
- **A fault lands when the instruction reaches the system, not when the system agrees.** A
  refused instruction is evidence, and an arm built around a refusal is not a fault that never
  fired.
