# Technique catalogue -- pick a technique by symptom

**This file is self-contained.** It is the selector page: match the suspicion on the left to
the technique on the right, then read that section for when to reach for it, what it catches,
what it misses, the tools, the papers and the cost.

Most work uses **two to four techniques in combination, not one**. One technique is
suspicious -- re-check whether several distinct hypotheses were collapsed into one.

## Before picking a technique: walk the pitfall catalogue

Open `pitfall-catalogue.md` first. It is the hypothesis generator; this file is the
implementation selector. For each pitfall, decide whether it applies: `y` / `n` / `maybe`.
Every `y` and most `maybe`s become a hypothesis, and only then does the question "with what
technique" have an answer.

## The selector

| Suspect this could break | Reach for |
|---|---|
| Linearizability or serializability under concurrent operations plus faults | section 1, consistency checking |
| Crash mid-operation, replay, or fsync loss correctness | section 7, crash recovery and upgrade |
| Non-deterministic interleavings that "passed in CI" | section 2, deterministic simulation, or section 4, fuzzing |
| Partial network partitions, asymmetric loss, clock skew | section 3, chaos and fault injection |
| Input parser or state-machine bugs from unexpected bytes or call sequences | section 4, fuzzing |
| Algorithm-level correctness of a protocol you wrote | section 5, formal methods |
| Whole-system invariants that should hold across many inputs | section 6, property and metamorphic |
| Tail latency, head-of-line blocking, throughput collapse under load | section 8, performance |
| Mixed-version cluster, rolling upgrade, downgrade, schema migration | section 7 |
| A limping node -- degraded but not dead | section 3 plus section 8 |
| Configuration typos or defaults silently changing behaviour | section 6, over the configuration space |

## What this suite already uses, and what it does not

| Technique | In this suite |
|---|---|
| Consistency checking over an operation history | **yes** -- the whole oracle spine. Our own checkers over our own history schema rather than a third-party checker, because the framework is in-process Java and the properties are framework-specific |
| Deterministic simulation | **partly** -- seeded workloads and fault schedules, with an honest determinism boundary. Not a fully simulated runtime; see section 2 |
| Chaos and fault injection | **yes** -- in-process fault wrappers plus a container tier with a TCP proxy, process kills and clock moves |
| Cross-implementation differential | **yes** -- the reference model, and the per-backend vector |
| Formal methods | **yes** -- TLA+ models bridged to the invariant registry by MachineName |
| Crash recovery and upgrade | **partly** -- process kill and restart, yes. Mixed-version and schema migration under traffic, **no**. This is the largest technique-shaped gap |
| Property and metamorphic | **partly** -- conservation laws are the metamorphic form we use. No configuration-space generation |
| Fuzzing | **partly** -- seed sweeps are the fuzz tier. No coverage-guided fuzzing, no input fuzzing of the conversion layer |
| Performance and benchmarking | **no** -- deliberately. No latency oracle, no fairness formula. Recorded as a residual |

---

## 1. Consistency checking over an operation history (Jepsen and Elle)

### When to reach for it

Distributed correctness: consistency models, isolation levels, linearizability under
concurrent clients with injected network faults. It finds anomalies no human would manually
trigger, because it searches the history rather than asserting a hand-picked case.

### What it detects well

- Lost updates, dirty reads, stale reads, lost writes under partition.
- Lost acknowledgements and split-brain after network partitions.
- Isolation anomalies -- G0, G1a, G1b, G1c, G-single, G2-item, G2.
- Reconfiguration races and leader-election failures.
- Concurrency bugs that need a precise message ordering.

### What it misses

- Performance regressions and resource leaks -- memory, threads.
- Client-side logic errors.
- Anything the generators do not emit.
- Slow correctness decay over very long runs; a history checker is bounded by history length.

### Tools

- `Jepsen` -- Clojure test framework with built-in nemeses and generators.
  https://github.com/jepsen-io/jepsen
- `Elle` -- transactional anomaly checker over operation histories, language-agnostic.
  https://github.com/jepsen-io/elle
- `Porcupine` -- Go linearizability checker. https://github.com/anishathalye/porcupine
- `Maelstrom` -- Jepsen toy-protocol workbench for any language.
  https://github.com/jepsen-io/maelstrom

### Papers

- "Elle: Inferring Isolation Anomalies from Experimental Observations", Kingsbury and Alvaro,
  VLDB 2020. A black-box anomaly classifier runnable against any store.
  https://github.com/jepsen-io/elle/raw/master/paper/elle.pdf
- "Jepsen Analyses" -- case studies of real anomalies in production databases.
  https://jepsen.io/analyses

### Cost

Thirty minutes to several hours of wall clock per scenario. The analysis itself is sub-second
to minutes depending on operation count.

### How to use it

1. Name the consistency model claimed -- linearizable, serializable, snapshot isolation, read
   committed, causal.
2. Pick generators that exercise the operations whose anomalies you care about.
3. Pick a nemesis schedule with realistic partition shapes, **not only symmetric ones**.
4. State which anomaly classes count as failures and which are acceptable.
5. Record the history for offline re-analysis.
6. Define a minimum operation count per run for statistical relevance -- ten thousand
   operations per scenario is the usual floor.

### In this suite

Our checkers are the local form of this technique: `HistoryView` in, `CheckResult` out, run
over a recorded JSON Lines history. The two things to carry over from the Jepsen line of work
that we do not get for free: **anomaly classification** rather than a boolean, and **offline
re-analysis** -- which our `HistoryReplayTest` does provide.

---

## 2. Deterministic simulation testing

### When to reach for it

Concurrency-heavy or async-IO-heavy code where every IO and clock can be routed through a
controllable runtime. You want reproducibility from a seed alone and you do not need real
network or kernel behaviour.

### What it detects well

- Race conditions and ordering bugs in concurrent code.
- Retry storms and backoff pathologies.
- Deadlocks and livelocks.
- Bugs that surface only under a specific interleaving.
- Found bugs are minimally reproducible from one seed.

### What it misses

- Everything outside the simulated boundary: kernel, NIC driver, disk firmware.
- Real latency distributions and real timing weirdness.
- Performance pathologies depending on real CPU cache behaviour.
- Interactions with OS scheduling and resource limits.

### Tools

- `FoundationDB DST harness` -- the original. https://apple.github.io/foundationdb/testing.html
- `TigerBeetle VOPR` -- open-source DST harness with property assertions.
  https://github.com/tigerbeetle/tigerbeetle
- `madsim` -- Rust DST runtime for async. https://github.com/madsim-rs/madsim
- `Antithesis` -- commercial autonomous testing built on DST. https://antithesis.com
- `shuttle` -- deterministic scheduler for concurrent code. https://github.com/awslabs/shuttle
- `turmoil` -- distributed-systems simulator. https://github.com/tokio-rs/turmoil

### Papers

- "What's the big deal about Deterministic Simulation Testing?", Phil Eaton.
  https://notes.eatonphil.com/2024-08-20-deterministic-simulation-testing.html
- "Is something bugging you?", Antithesis. DST history and the autonomous extension.
  https://antithesis.com/blog/is_something_bugging_you/

### Cost

Large up-front cost to plumb IO and clock. Per-scenario runs are cheap -- seconds to minutes.
Total wall clock scales with the seed budget.

### How to use it

1. **Confirm the system under test uses a simulated runtime, or state the work needed to
   introduce one. DST without that plumbing is fiction.**
2. Define the property assertions and oracles **inside** the simulation, not only outside.
3. Set a seed budget per scenario -- ten thousand seeds per run is a normal target.
4. Record the seed of every failure for deterministic replay.
5. Include time-warp scenarios -- a slow node, a fast node -- not only partitions.

### In this suite, honestly

Point 1 is the one to be honest about. The framework is not built on a simulated runtime and
**we do not add seams to it**, so our determinism is partial and measured: only the write side
of a `SINGLE_THREADED` arm reproduces from a seed. Under `REAL_THREADS` a seed reproduces
nothing, and the **history file** is the reproducer instead. The seam rules and the
algebraically-equivalent-knob trick are in `method-essentials.md` section 5; the measured
boundary is in `formal/INVARIANTS.md`.

---

## 3. Chaos engineering and fault injection

### When to reach for it

You have a real cluster, or close enough, and you want to know whether it survives the faults
that actually happen. Bridges closed-box testing and real-world resilience.

### What it detects well

- Partial and asymmetric network partitions.
- Slow nodes -- limping -- and high-latency scenarios.
- Packet loss, latency injection, bandwidth constraints.
- Disk-full, fsync failure and storage anomalies.
- Process kill, container restart, orchestrator churn.
- Clock skew and GC-pause analogues.
- Limplock and gaps in partial-failure detection.

### What it misses

- Deterministic reproducibility -- a re-run may not hit the same interleaving.
- Root cause, without good observability already in place.
- Anything needing whole-history analysis or post-hoc replay.

### Tools

- `Toxiproxy` -- TCP proxy with programmable network faults.
  https://github.com/Shopify/toxiproxy
- `Chaos Mesh` -- Kubernetes-native chaos platform. https://chaos-mesh.org
- `LitmusChaos` -- CNCF chaos platform. https://litmuschaos.io
- `AWS Fault Injection Service`. https://aws.amazon.com/fis/
- `tc` / `netem` -- Linux traffic control for latency, loss and bandwidth.
  https://man7.org/linux/man-pages/man8/tc-netem.8.html
- `iptables` -- kernel-level packet drops for partition emulation.
  https://netfilter.org/projects/iptables/
- `kill -STOP` / `kill -CONT` -- process freeze; the best available GC-pause analogue.

### Papers

- "Toward a Generic Fault Tolerance Technique for Partial Network Partitioning", Alfatafta et
  al., OSDI 2020. Taxonomy and mitigation for partial partitions.
  https://www.usenix.org/conference/osdi20/presentation/alfatafta
- "Understanding, Detecting and Localizing Partial Failures in Large System Software", Lou et
  al., NSDI 2020. Intrinsic watchdogs catch degraded-but-alive failures.
  https://www.usenix.org/conference/nsdi20/presentation/lou
- "The Case for Limping-Hardware Tolerant Clouds", Do et al. Degraded hardware is worse than
  dead. https://www.usenix.org/node/174577

### Cost

Minutes to hours per scenario. The budget is usually bounded by cluster availability, not
compute.

### How to use it

1. Catalogue the realistic faults -- power loss, partition (full, partial, asymmetric), slow
   disk, slow node, clock skew.
2. For each, define the property the system should preserve.
3. Require an oracle that actually runs **after** the fault clears.
4. **Demand evidence the fault was injected** -- a log line, a packet capture, a proxy
   statistic. A silent no-op is the most common false pass.
5. Include recovery time as an exit criterion, not only correctness.

The mechanism-by-mechanism tables are in `fault-catalogue.md`.

---

## 4. Fuzzing -- input and concurrency

### When to reach for it

Parsers, state machines, RPC servers -- anywhere untrusted or arbitrary bytes enter. Also
randomised concurrency fuzzing, to find scheduling and message-order bugs in lock-free or
async code.

### What it detects well

- Memory unsafety -- under ASan, UBSan, MSan.
- Parser crashes and panics on malformed input.
- State-machine invariant violations and integer overflow.
- Infinite loops triggered by malformed input.
- Message-order bugs, via interleaving-space fuzzing.
- Pathological inputs and regular-expression denial-of-service amplifiers.

### What it misses

- High-level correctness -- fuzzing stops at "did not crash"; right-answer-ness needs an
  oracle.
- Bugs needing multi-input setup or stateful interaction patterns.
- Performance regressions and leaks visible only under normal operation.

### Tools

- `libFuzzer` -- in-process coverage-guided fuzzer on LLVM.
  https://llvm.org/docs/LibFuzzer.html
- `AFL` / `AFL++`. https://github.com/AFLplusplus/AFLplusplus
- `cargo-fuzz`. https://github.com/rust-fuzz/cargo-fuzz
- `go test -fuzz`. https://go.dev/security/fuzz/
- `honggfuzz`. https://github.com/google/honggfuzz
- `FlyMC` -- research tool for scalable distributed-system interleaving fuzzing.
  https://dl.acm.org/doi/10.1145/3302424.3303986
- For the JVM: `Jazzer` -- coverage-guided fuzzing for Java, libFuzzer-backed.
  https://github.com/CodeIntelligenceTesting/jazzer

### Papers

- "FlyMC: Highly Scalable Testing of Complex Interleavings in Distributed Systems", Lukman et
  al., EuroSys 2019. https://dl.acm.org/doi/pdf/10.1145/3302424.3303986
- "Combining AFL and QuickCheck for Directed Fuzzing", Dan Luu.
  https://danluu.com/testing/

### Cost

CPU-hours to CPU-weeks depending on input complexity and coverage depth. Well suited to a
continuous fuzzing farm.

### How to use it

1. Identify each input boundary: external bytes, untrusted messages, arbitrary call sequences.
2. Write a target hitting the smallest meaningful entry point -- a parser, a state-machine
   step, a handler.
3. Run under sanitizers. Fuzzing without them catches crashes and misses memory safety.
4. Seed the corpus from real samples, existing tests or protocol examples.
5. Set an exit criterion -- coverage plateau or operation count -- rather than unbounded wall
   clock.

### In this suite

Our fuzz tier is a **seed sweep over scenarios**, which is interleaving fuzzing with a fixed
generator, not coverage-guided input fuzzing. The uncovered surface with the clearest fuzzing
shape is the **conversion layer**: arbitrary bytes and arbitrary declared types entering a
converter, with a round-trip property as the oracle. Nothing in the suite does that today.

---

## 5. Formal methods -- TLA+ and friends

### When to reach for it

Designing a protocol or invariant where being wrong is expensive: consensus, replication,
leasing, transaction commit, crash-recovery workflows. The protocol must be small enough to
model -- one component, or the interaction of a few.

### What it detects well

- Protocol-level bugs: livelock, ordering violations, safety-invariant violations.
- Mixed-version protocol issues and reconfiguration edge cases.
- Implicit assumptions, made explicit and then violated by a counterexample.
- Fairness and liveness at design time, before implementing.

### What it misses

- Implementation bugs. The model is not the code.
- Performance and wall-clock behaviour.
- Anything outside the modelled state space -- an unmodelled message type, a new fault class.

### Tools

- `TLA+` / `PlusCal` -- with the TLC model checker and the TLAPS prover.
  https://lamport.azurewebsites.net/tla/tla.html
- `Alloy` -- bounded model checker with a SAT backend. https://alloytools.org
- `P` -- state-machine language for distributed protocols. https://github.com/p-org/P
- `Coq` / `Verdi` -- machine-verified distributed systems. https://github.com/uwplse/verdi

### Papers

- "How Amazon Web Services Uses Formal Methods", Newcombe et al., CACM 2015.
  https://cacm.acm.org/magazines/2015/4/184701-how-amazon-web-services-uses-formal-methods/fulltext
- "Using Lightweight Formal Methods to Validate a Key-Value Storage Node in Amazon S3",
  Bornholt et al., SOSP 2021. Property testing combined with model checking.
  https://www.amazon.science/publications/using-lightweight-formal-methods-to-validate-a-key-value-storage-node-in-amazon-s3

### Cost

Days to weeks of human time to write a model. Checking runs are minutes to hours, exponential
in the unmodelled variables.

### How to use it

1. State the invariants to preserve and the liveness goals -- no deadlock, fairness.
2. Model only the relevant slice. Abstract storage, networking and implementation detail away.
3. Check safety **and** liveness, documenting the fairness assumptions -- who moves, who may
   stutter.
4. Keep the model in version control and re-check on every protocol-level change.
5. **Write a mapping document** connecting model state to implementation state, so the gap is
   visible and reviewable.

### In this suite

Point 5 is our MachineName bridge, and it is the reason the models are worth anything: an
operator name in a `.tla` file quotes the registry statement character-identically. `formal/tla/README.md`
owns which registry invariants have a model and which do not, and why. The models have already
earned their keep once by **overturning an inference** drawn from a correct measurement --
see `traps.md`.

The working reference for actually writing one -- syntax, configuration sections, the review
checklist, and the traps that make a green TLC run mean nothing -- is
**`tla-modelling.md`**. To point a model at a **recorded run** instead of a state space, which
closes the gap between "the wording matches" and "the engine did what the design allows", see
**`tla-trace-validation.md`**: the histories are already in the format the checker reads.

---

## 6. Property-based and metamorphic testing

### When to reach for it

You can state an invariant or a relation that should hold across many inputs, even where
computing the right answer for any one input is hard. Metamorphic testing is especially
powerful for query engines, serializers, encoders, schema systems and configuration systems,
where round-trip or equivalence properties exist.

### What it detects well

- Algebraic-law violations: round-trip, commutativity, idempotency, associativity.
- Behaviour divergence between two implementations or two versions.
- Edge cases example-based tests hide: boundary integers, empty and huge inputs, Unicode,
  nulls.
- Configuration-space typos and type mismatches.

### What it misses

- Bugs that do not violate the stated invariant.
- Debuggability without good shrinking -- failures are hard to reproduce.
- Complex multi-part interactions no simple property captures.

### Tools

- `Hypothesis` -- Python, excellent shrinking. https://hypothesis.works
- `QuickCheck` -- the Haskell original.
  https://hackage.haskell.org/package/QuickCheck
- `PropEr` -- Erlang. https://propertesting.com
- `proptest` -- Rust. https://github.com/proptest-rs/proptest
- `fast-check` -- TypeScript and JavaScript. https://github.com/dubzzz/fast-check
- `ScalaCheck` -- Scala. https://scalacheck.org
- For the JVM: `jqwik` -- property-based testing on JUnit 5. https://jqwik.net

### Papers

- "Metamorphic Testing: A Review of Challenges and Opportunities", Chen et al., 2017.
  https://www.cs.hku.hk/data/techreps/document/TR-2017-04.pdf
- "Metamorphic Testing", Hillel Wayne. Pragmatic introduction with concrete examples.
  https://www.hillelwayne.com/post/metamorphic-testing/

### Cost

Seconds to minutes per property. The cost is in stating properties that actually find bugs.

### How to use it

1. List the algebraic laws and metamorphic relations the system must obey.
2. Colocate properties next to the code they test.
3. Keep generators tight: boundary integers, empty and huge collections, Unicode, null.
4. **Require shrinking** so failures are minimal and reproducible.
5. For configuration systems, treat the configuration space itself as the input domain, and
   generate configurations as aggressively as inputs.

### In this suite

Point 5 is an open gap with two findings already sitting in it: F-1 and F-23 are both
configuration-path defects found by reading, not by generating. A generator over the
configuration space -- core against Spring Boot against YAML, asserting the effective
component is the same -- would have found both mechanically, and would find the next one.

Our conservation-law workload is the metamorphic form we do use: the property is arithmetic
over the projection, and it caught a double-processing mutation nobody had written an
assertion for, twice.

---

## 7. Crash recovery and upgrade testing

### When to reach for it

Anything touching durability, replay, idempotency or version-to-version state migration. Per
Gunawi et al., "What Bugs Live in the Cloud" (SoCC 2014), this category is the leading source
of distributed-systems production incidents.

### What it detects well

- Lost writes after a crash, and double-apply on replay.
- Duplicate side effects from a retried operation.
- Mixed-version protocol bugs and incompatible state during rolling upgrades.
- Schema-migration corruption and downgrade-incompatible state.
- Fsync gaps and partial-checkpoint corruption.
- Write-ahead-log truncation races and orphaned temporary files.

### What it misses

- Steady-state correctness bugs -- use section 1.
- Performance during recovery -- use section 8.
- Invariants needing whole-history analysis across machines -- use section 1.

### Tools

- `ALICE` -- application-level crash explorer for filesystem-level bugs.
  https://github.com/madthanu/alice
- Torturing-Databases framework -- power-loss simulator with block-level tracing.
  https://www.usenix.org/system/files/conference/osdi14/osdi14-paper-zheng_mai.pdf
- `CrashMonkey` -- filesystem crash-consistency tester.
  https://github.com/utsaslab/crashmonkey
- Rolling-upgrade harnesses -- project-specific; most mature systems have one.
- Write-ahead-log replay tooling -- most durability systems already ship replay. Surface it in
  tests.

### Papers

- "Torturing Databases for Fun and Profit", Zheng et al., OSDI 2014.
  https://www.usenix.org/system/files/conference/osdi14/osdi14-paper-zheng_mai.pdf
- "An Empirical Study on Crash Recovery Bugs in Large-Scale Distributed Systems", Gao et al.,
  FSE 2018. https://dl.acm.org/doi/10.1145/3236024.3236030
- "Understanding and Detecting Software Upgrade Failures in Distributed Systems", Zhang et
  al., SOSP 2021. https://dl.acm.org/doi/10.1145/3477132.3483577

### Cost

Minutes to hours per scenario. Planning cost is high, because the fault matrix -- a crash at
every IO boundary, times every upgrade step -- is large.

### How to use it

1. Enumerate the operation's IO boundaries and inject a crash at each one.
2. After recovery, assert the state equals a known-good baseline by **replay equivalence**.
3. For idempotent operations, drive explicit retry storms to surface double-apply.
4. For upgrades, test mixed-version state **at every intermediate step**, not only N to N+1.
5. Treat fsync as a contract. If the filesystem does not fsync, the system does not fsync for
   test purposes -- make that assumption explicit.

### In this suite

Points 1, 3 and 4 are the gap. We kill and restart processes, but we do not enumerate the IO
boundaries of an append and crash at each; we have no mixed-version arm at all; and the
connector-compatibility gate is a static check, not an upgrade test. F-18 is exactly an
upgrade-shaped finding found without an upgrade technique.

---

## 8. Performance and benchmarking

### When to reach for it

Latency tail, throughput, fairness -- any "the system slowed down" rather than "the system
gave a wrong answer".

### What it detects well

- Coordinated-omission lies in the load generator's own measurements.
- Regressions under load, latency amplification, GC-induced tail latency.
- Head-of-line blocking, queue build-up, cascading slowdown under contention.
- Fairness violations across tenants or request classes.
- Resource exhaustion: file descriptors, connection pools, memory growth.

### What it misses

- Correctness bugs that do not change timing.
- Bugs visible only at scales or load patterns you do not test.
- Rarely-hit code paths -- simulation and fuzzing are better there.

### Tools

- `wrk2` -- constant-throughput load generator; avoids coordinated omission.
  https://github.com/giltene/wrk2
- `k6`. https://k6.io
- `fortio` -- HTTP and gRPC load testing and latency analysis.
  https://github.com/fortio/fortio
- `vegeta`. https://github.com/tsenart/vegeta
- `HdrHistogram` -- high-dynamic-range latency histograms. http://hdrhistogram.org
- `YCSB` -- Yahoo Cloud Serving Benchmark. https://github.com/brianfrankcooper/YCSB
- For the JVM: `JMH` -- the only defensible microbenchmark harness on this platform.
  https://github.com/openjdk/jmh

### Papers

- "How NOT to Measure Latency", Gil Tene. Coordinated omission and the percentile fallacies.
  https://www.youtube.com/watch?v=lJ8ydIuPFeU
- "Your Load Generator Is Probably Lying To You".
  https://highscalability.com/blog/2015/10/5/your-load-generator-is-probably-lying-to-you-take-the-red-pi.html
- "Performance Analysis Methodology", Brendan Gregg. The USE and RED methods.
  https://www.brendangregg.com/methodology.html

### Cost

Minutes to days per scenario. The real bottleneck is environment realism.

### How to use it

1. Measure latency as a **full distribution** -- p50, p99, p99.9, max. Never the average
   alone.
2. Confirm the load generator avoids coordinated omission. Use open-loop, not closed-loop.
3. Drive the open-loop arrival rate as a constant. Closed-loop load hides queue build-up.
4. Measure under realistic contention, not single-tenant. Include mixed workloads.
5. Capture system metrics alongside -- CPU, GC, network, disk -- so a slowdown can be
   attributed.

### In this suite

Absent on purpose, and it is worth naming why that costs something. Our timescale compression
makes latency numbers meaningless, so a latency oracle here would be dishonest. But **fairness
is a correctness property in disguise**: a starved segment, a starved processing group, or a
lease renewal starved by workload on a shared pool is a safety failure that only a per-group
formula surfaces. See `boundary-and-isolation.md` section 5.

---

## How to combine techniques

- **Consistency checking plus chaos.** One drives a workload and an oracle; the other injects
  realistic faults. Together they are the standard shape for distributed correctness.
- **DST plus property assertions.** Simulation gives reproducibility; properties give the
  oracle. Both layered is how FoundationDB and TigerBeetle catch most regressions before
  merge.
- **Formal plus tests.** TLA+ proves the design; tests catch the implementation's drift from
  it. Neither replaces the other.
- **Fuzzing plus sanitizers.** Fuzz inputs are only as useful as the detectors running
  underneath.
- **Reference model plus formal specification.** Ours. The model is the primary oracle; the
  specification checks the model over a whole finite domain, which is what stops the model
  from sharing the implementation's bug.

## Do-not-skip rules from the literature

- **Most production failures come from a small number of causes that simple testing catches.**
  "Simple Testing Can Prevent Most Critical Failures", Yuan et al., OSDI 2014. Always test the
  **error-handling paths**, not only the happy paths -- the bug is usually in the code that
  handles the fault, not in the code the fault interrupts.
- **Random testing is effective for partition-tolerance bugs**, without deep symbolic
  reasoning. "Why Is Random Testing Effective for Partition Tolerance Bugs?", Majumdar et al.,
  POPL 2018. When in doubt, run more random scenarios.
- **Redundancy does not imply fault tolerance.** Replicas can all mishandle the same corrupted
  byte. Test the fault, not only the failover.
