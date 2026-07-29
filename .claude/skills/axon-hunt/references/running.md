# Running the suite

Every command here was executed from the worktree root of the hunt worktree on branch
`feature/dst-testing-suite`. Run the worktree check first, every session:

```bash
git rev-parse --show-toplevel && git branch --show-current
```

## How to judge a run

**By the exit code, and only by it.** Three ways that goes wrong:

```bash
# Right: the exit status is Maven's.
./mvnw -q -Phunt -pl simulation -am test > /tmp/hunt.log 2>&1; echo "EXIT=$?"

# Wrong: EXIT is tail's, and is 0 even when the build failed.
./mvnw -q -Phunt -pl simulation -am test 2>&1 | tail -20; echo "EXIT=$?"
```

- Under `-q` a clean build prints **nothing at all**. Absence of output is success, not a
  silent failure.
- `-Dtest=A+B` is not a thing; surefire wants `-Dtest=A,B`. With `$Nested` selectors a wrong
  separator runs **no tests at all** and reports `BUILD SUCCESS` in seconds. Check the
  `Tests run:` line, not only the exit code.
- `-DskipTests` skips failsafe too, so an integration-test run needs surefire silenced some
  other way: `-Dtest=NoSuchUnitTest -Dsurefire.failIfNoSpecifiedTests=false`.

## Counting tests honestly

Three traps, and the third is the worst because the number it produces looks right.

1. The per-class `.txt` summary reports `Tests run: 0` for a class whose cases all live in
   `@Nested` classes.
2. The XML `tests=` attribute is also wrong for the same reason.
3. **Leaving `target` in place counts runs that did not happen.** Surefire does not clean the
   report directory, so every report from every earlier build is still there and is still
   counted. A build that excludes the `container` tag therefore counts the previous container
   run's reports, and the number it produces looks entirely plausible.

Demonstrated, on this worktree, running **one** test class of three cases against a `target`
left over from a previous full build:

```
$ ./mvnw -q -Phunt -pl simulation -o test -Dtest=DeterminismProbeTest
EXIT=0
reports present after running ONE class (3 cases): 208
report files: 32
```

208 is the full module's count. Three cases ran. So:

```bash
rm -rf simulation/target                       # the whole directory, not just the reports
./mvnw -q -Phunt -pl simulation -am test > /tmp/hunt.log 2>&1; echo "EXIT=$?"
grep -ho '<testcase ' simulation/target/surefire-reports/*.xml | wc -l
```

Verified on a clean `target`:

```
EXIT=0
     208
```

Remove the whole directory rather than just `surefire-reports`, because `target/surefire/`
holds the forked virtual machine's own state as well.

Cross-check that the default build starts no container, which is stronger than reading the
tag list:

```bash
docker ps -aq | wc -l                          # before
./mvnw -q -Phunt -pl simulation -o test
docker ps -aq | wc -l                          # after: no new container
```

## The tiers

### The gate (what CI runs on a change)

```bash
./mvnw -q -Phunt -pl simulation -am test > /tmp/hunt.log 2>&1; echo "EXIT=$?"
```

The `fuzz` and `container` tags are excluded by the module's own POM property
(`hunt.excludedGroups`), so this is the smoke tier only.

### Iterating (much faster, offline, once dependencies are installed)

```bash
./mvnw -q -Phunt -pl simulation -o test
./mvnw -q -Phunt -pl simulation -o test -Dtest=DeterminismProbeTest
```

Verified: `EXIT=0`, 3 testcases in the probe's report.

Some other single classes worth knowing by name (all offline, all seconds to a couple of
minutes): `ScenarioRunnerTest`, `ClaimCapableBackendTest`, `ConcurrentBootstrapTest`,
`SegmentOwnershipUnderSkewTest`, `ReplayAfterResetTest`, `SplitAndMergeUnderLoadTest`,
`StoredProgressCheckerTest`, `PartialBatchVisibilityTest`, `TransactionPhaseFailureTest`,
`SequencingPolicyOrderTest`, `FaultsLandTest`, `ModelAndInMemoryEngineAgreeTest`.

### Reproducing one run

```bash
./mvnw -Phunt -pl simulation -o test -Dtest=HuntReproduceTest \
    -Dhunt.scenario=dcb_append_rejected_after_marker_single_writer \
    -Dhunt.seed=2 -Dhunt.backend=in-memory -Dhunt.timescale=compressed \
    -Dsurefire.failIfNoSpecifiedTests=false
```

Verified: `EXIT=0`,
`PASS dcb_append_rejected_after_marker_single_writer [tier=SMOKE, seed=2, wall=1549ms]`.

With no properties it replays the shipped contention scenario at its first seed, so the bare
command is a working example. Properties it accepts: `hunt.scenario`, `hunt.tier`,
`hunt.seed`, `hunt.backend`, `hunt.timescale`. Unknown scenario names produce an error that
lists every id this build ships.

**Under `REAL_THREADS` this is a re-sample, not a replay.** The violation message says so, and
so does the reproduce command the history header renders. Do not remove that annotation to
tidy the output.

### Replaying a recorded history offline

The only exact replay there is: no simulation, the whole registered checker set, the same
verdict for ever and on any machine.

```bash
./mvnw -Phunt -pl simulation -o test -Dtest=HistoryReplayTest \
    -Dhunt.history=simulation/src/test/resources/hunt-histories/pinned-conflict-check-bypass.jsonl \
    -Dsurefire.failIfNoSpecifiedTests=false
```

Verified: `EXIT=0`, and the replay prints
`FAIL pinned_conflict_check_bypass [tier=SMOKE, seed=3, wall=57ms]` with
`AppendConformsToDcbModel` violations. The test passes because it asserts the verdict the file
was recorded with -- a red replay is the finding, not a test to repair.

With no property it replays the histories the build ships. Live runs write theirs to
`simulation/target/hunt-histories/<name>/<scenario>-<seed>.jsonl`, under `target` rather than
a temporary directory, because the reproduce command a failure prints is only useful next to
the file it came from.

### The seed sweep (fuzz tier)

Tagged out of every normal build; the sweep clears the exclusion explicitly.

```bash
./mvnw -Phunt -pl simulation -o test -Dhunt.excludedGroups= -Dtest=HuntFuzzTest \
    -Dsurefire.failIfNoSpecifiedTests=false -Dhunt.seeds=3 -Dhunt.startSeed=90000
```

Verified at 3 seeds: `EXIT=0`, three histories written under
`simulation/target/hunt-histories/fuzz/`. The scheduled job runs the same command with
`-Dhunt.seeds=250` and a start seed per matrix chunk, so the sweep covers a contiguous range
rather than the same seeds from four directions. Only the seed count was reduced here; the
invocation is identical.

Anything a sweep finds is pinned into `RegressionSeedsTest` so the discovery outlives the
sweep -- as a **seed** only if the arm is `SINGLE_THREADED`, otherwise as a **history file**.

### The per-backend matrix (container tier, needs Docker)

```bash
./mvnw -Phunt -pl simulation -o test -Dhunt.excludedGroups=fuzz -Dtest=BackendDifferentialTest \
    -Dsurefire.failIfNoSpecifiedTests=false
```

The matrix is built once for the class, so selecting a single nested arm still runs the whole
thing. Budget for tens of minutes; the last recorded full run was five scenarios times four
stores times two seeds. It prints one `VECTOR` line per scenario and a `FIRES` line per
faulted scenario, and it asserts that a run whose declared fault never fired may not pass.

Registered backend names, in case a scenario needs one by hand: `in-memory`,
`hsqldb-tokens`, `postgres-jpa`, `postgres-jpa-split-tokens`, `postgres-jpa-chaos`,
`postgres-jpa-chaos-spring-defaults`. The last four are registered from test scope, which is
what keeps Hibernate, Testcontainers and a PostgreSQL driver off the default build's compile
path. A backend's constructor must therefore not start anything: every build instantiates
every registered provider.

### Infrastructure faults (container tier, needs Docker)

Each nested class is one arm and they are minutes apart in cost.

```bash
./mvnw -Phunt -pl simulation -o test -Dhunt.excludedGroups=fuzz \
    -Dtest='StoreInfrastructureFailureTest$HoldingCommitsOpenPastTheStoresGapTimeout' \
    -Dsurefire.failIfNoSpecifiedTests=false
```

Two arms at once -- comma, never plus:

```bash
./mvnw -Phunt -pl simulation -o test -Dhunt.excludedGroups=fuzz \
    -Dtest='StoreInfrastructureFailureTest$CuttingTheNetworkWhileLeavingTheStoreRunning,StoreInfrastructureFailureTest$HoldingCommitsOpenPastTheStoresGapTimeout' \
    -Dsurefire.failIfNoSpecifiedTests=false
```

Container traps, all of them measured:

- **Never recompile while a container arm is running.** Surefire reads the same
  `target/test-classes` the compiler writes to.
- **Let a container run finish.** Killing one mid-flight costs the next run about two minutes
  while Ryuk reaps the old container, and the build looks wedged.
- **A container-backed run that produces nothing is usually a lock, not a hang.** Surefire
  buffers a test's output until the method ends, so a blocked cleanup prints nothing at all.
  First command to run:

  ```bash
  docker exec <container> psql -U test -d test \
    -c "SELECT pid, state, wait_event_type, wait_event, left(query,60) FROM pg_stat_activity"
  ```

- **Container reuse is a developer setting** (`testcontainers.reuse.enable=true` in
  `~/.testcontainers.properties`), not a property of the code. The suite's real isolation
  mechanism is one container per virtual machine plus one schema per run.
- **Any arm against a store reached over a network needs widened claim timings.** The
  compressed default's hundred-millisecond claim timeout is hopeless over a socket, and the
  symptom does not mention timeouts: mass redelivery, token regressions and a conservation
  violation on a **single-node** run. `Scenario.withTimescale(...)` is how an arm gets them
  without editing the experiment, and every arm of a differential must get the same ones or
  the divergence stops being attributable to the store.

### The existing integration-test suite, per backend

The same classes, one property apart. No new test classes per store.

```bash
# In-memory components. No Docker.
./mvnw -Pintegration-test -pl integrationtests verify -Djacoco.skip=true \
    -Dtest=NoSuchUnitTest -Dsurefire.failIfNoSpecifiedTests=false

# The same classes against PostgreSQL.
./mvnw -Pintegration-test -pl integrationtests verify -Djacoco.skip=true \
    -Dtest=NoSuchUnitTest -Dsurefire.failIfNoSpecifiedTests=false -Dhunt.backend=postgres-jpa
```

`-o` fails the integration-test profile (`jacoco-maven-plugin` is not in the local
repository, so the profile cannot resolve offline). Add `-Djacoco.skip=true` and drop `-o`.
A Maven `-D` property does reach the forked JVM, so `-Dhunt.backend=...` selects the store
with no surefire or failsafe configuration.

## Versions, and the gate that checks a combination

A backend is not one thing. A store reached over a wire is this reactor crossed with a client library
crossed with a server version, and any of the three moving changes what a run means. This is not
theoretical: the Axon Server arm was recorded as blocked for a whole phase because an abstract method
was added to a storage-engine interface that the released connector had not implemented -- which
`javac` accepts and the JVM refuses at the first call.

**Answer the compatibility question first. It costs a second and starts no container.**

```bash
# The combination this build ships on.
./mvnw -q -Phunt -pl simulation -o test -Dtest=ConnectorCompatibilityTest \
    -Dsurefire.failIfNoSpecifiedTests=false

# Any other connector artefact. The jars are in the local repository already.
./mvnw -q -Phunt -pl simulation -o test -Dtest=ConnectorCompatibilityTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dhunt.connectorJar=$HOME/.m2/repository/io/axoniq/framework/axon-server-connector/5.1.2/axon-server-connector-5.1.2.jar
```

Real output from the second command:

```
CONNECTOR COMPATIBILITY axon-server-connector-5.1.2.jar against framework 5.3.0-SNAPSHOT
  classes=79 unresolvable=0 unimplemented=4
  UNIMPLEMENTED AxonServerEventStorageEngine -> EventStorageEngine.source(SourcingCondition, ProcessingContext)
  ...
  verdict=shimmable
```

`unresolvable` is a missing **type** and no shim fixes it; `unimplemented` is a missing **method** and a
shim may. The version table, the shimmed set, and the escalation order for a rejected combination live
in **`formal/CONNECTOR-COMPATIBILITY.md`** -- read them there, never from a copy, because a stale
version table is worse than none.

The knobs, each the exact property or file:

| Version | Where |
|---|---|
| Axon Server image | `AxonServerHuntBackend.IMAGE` (simulation) and `AxonServerTestInfrastructure.IMAGE` (integrationtests). The arm needs `AXONIQ_AXONSERVER_STANDALONE_DCB=true` for a boundary context, and both arms wait for the server's own `Creating DCB context: default` log line, not for the health endpoint. |
| Connector | `<version>` of `io.axoniq.framework:axon-server-connector` in `simulation/pom.xml` and `integrationtests/pom.xml`. Both exclude `org.axonframework:*` so this reactor's artefacts win. Then run the gate. |
| Framework | The reactor's `${revision}` in the root `pom.xml`. A repo-wide change, not a test knob -- and the one most likely to break a released connector silently, which is what the gate is for. |
| PostgreSQL image | `PostgresJpaHuntBackend.IMAGE` and `PostgresTestInfrastructure.IMAGE`. |

**Which combination a past run used is data, not memory.** Every history header carries it:

```bash
head -1 <history>.jsonl | python3 -c "import json,sys; print(json.load(sys.stdin)['versions'])"
```

On the Axon Server arm that prints the framework version, the connector coordinates and version, the
image tag, and the method the harness shimmed. A verdict quoted without them is not quotable.

## The Axon Server arm

```bash
# The arm itself: the linkage evidence, a real append and source, and one shipped scenario.
./mvnw -Phunt -pl simulation -o test -Dhunt.excludedGroups=fuzz -Dtest=AxonServerBackendTest \
    -Dsurefire.failIfNoSpecifiedTests=false

# Its chaos arms: kill, network cut, and a severed read-side stream. Tens of minutes.
./mvnw -Phunt -pl simulation -o test -Dhunt.excludedGroups=fuzz \
    -Dtest=AxonServerInfrastructureFailureTest -Dsurefire.failIfNoSpecifiedTests=false

# The existing integration tests against it. One property, no new test classes.
./mvnw -Pintegration-test -pl integrationtests verify -Djacoco.skip=true \
    -Dtest=NoSuchUnitTest -Dsurefire.failIfNoSpecifiedTests=false -Dhunt.backend=axonserver
```

Three things about this backend that will otherwise cost you time:

- **Runs must not overlap.** Isolation is a purge of one shared context, because the standalone edition
  refuses a context per run (`403 [AXONIQ-1700] Maximum number of replication groups reached`).
- **A gRPC stream cannot be drained with a `next()` loop.** The generic scan stops at the first empty
  answer and a gRPC stream is empty until its first message arrives, so it reports **zero** however much
  the store holds -- which makes quiescence trivially true and every delivery oracle hold vacuously. The
  backend overrides `readableEventIds` with `MessageStream.reduce`.
- **A killed container does not keep its published port.** Measured: `55015` before `docker kill`,
  `55016` after `docker start`. Testcontainers' `getMappedPort` still answers the old one.

## The formal layer

Fetch the checker once (git-ignored):

```bash
mkdir -p formal/tla/tools
curl -fsSL -o formal/tla/tools/tla2tools.jar \
  https://github.com/tlaplus/tlaplus/releases/download/v1.7.4/tla2tools.jar
```

Run from the worktree root with a path-qualified spec -- TLC resolves `EXTENDS`ed modules
from the spec file's own directory, so no `cd` is needed:

```bash
J="java -XX:+UseParallelGC -cp formal/tla/tools/tla2tools.jar tlc2.TLC -workers auto -metadir formal/tla/states"

$J -config formal/tla/Sanity.cfg formal/tla/Sanity.tla     # proves the wiring first

for c in safe unconditional unconditional_fixed conformance conformance_fixed illegalcommit; do
  printf "%-22s " "$c"
  java -XX:+UseParallelGC -cp formal/tla/tools/tla2tools.jar tlc2.TLC -workers auto \
    -metadir formal/tla/states -config formal/tla/MCAppend_$c.cfg formal/tla/DcbAppend.tla 2>&1 \
    | grep -E 'Error: (Invariant|Deadlock|Temporal)|Model checking completed|distinct states found' \
    | tr '\n' ' '; echo
done
```

Verified output of that loop:

```
safe                   Model checking completed. No error has been found. 8141 states generated, 2784 distinct states found, 0 states left on queue.
unconditional          Error: Invariant UnconditionalAppendNeverRejected is violated. 611 states generated, 287 distinct states found, 199 states left on queue.
unconditional_fixed    Model checking completed. No error has been found. 8141 states generated, 2784 distinct states found, 0 states left on queue.
conformance            Error: Invariant AppendConformsToDcbModel is violated. 539 states generated, 254 distinct states found, 175 states left on queue.
conformance_fixed      Model checking completed. No error has been found. 8141 states generated, 2784 distinct states found, 0 states left on queue.
illegalcommit          Model checking completed. No error has been found. 16041 states generated, 5532 distinct states found, 0 states left on queue.
```

The same loop over `MCClaim_{noskew,skew_below_margin,skew_below_margin_fixed,skew_bounded_by_skew,skew_double,skew_double_tight,live}` against
`formal/tla/TokenClaim.tla`, verified:

```
noskew                     No error.  1030 distinct states
skew_below_margin          Error: Invariant AtMostOneSegmentOwner is violated.
skew_below_margin_fixed    No error.   308 distinct states
skew_bounded_by_skew       No error.  1506 distinct states
skew_double                No error.  4838 distinct states
skew_double_tight          Error: Invariant AtMostOneSegmentOwner is violated.
live                       No error.  7658 distinct states  (temporal)
```

A "No error" run explored its whole reachable state space, so its count is exact and
reproducible. A violated run stopped at the first counterexample, so with several workers the
states-explored number moves between invocations. The counterexample does not.

Model-to-model cross-check (needs `simulation/target/classes`, which any earlier build
produces; otherwise `./mvnw -q -Phunt -pl simulation -am test-compile`):

```bash
java -cp formal/tla/tools/tla2tools.jar tlc2.TLC -workers 1 -metadir formal/tla/states \
    -config formal/tla/MCAppend_crosscheck.cfg formal/tla/DcbCrossCheck.tla 2>/dev/null \
  | java -cp simulation/target/classes formal/tla/crosscheck/CrossCheck.java
```

Verified: `cases=960 agreed=960 disagreed=0`.

**`timeout` does not exist on macOS.** `timeout 600 java ...` exits 127 with no output, which
inside a loop reads as every configuration silently doing nothing. There is nothing worth
wrapping: every run above finishes in about a second.

## The canary loop

```bash
# 1. Apply exactly one mutation to framework code.
# 2. Install the mutated module, because the hunt module resolves it from the local
#    repository rather than from the reactor when built alone.
./mvnw -q -o -pl eventsourcing -am install -DskipTests     # or -pl messaging

# 3. Run the WHOLE suite. The point is to learn which arms catch it, including the ones
#    nobody expected to.
./mvnw -q -Phunt -pl simulation -o test

# 4. Record the verdict in formal/CANARIES.md, then revert and reinstall the clean module.
git checkout -- eventsourcing
./mvnw -q -o -pl eventsourcing -am install -DskipTests
```

A mutation that makes the store keep less than it was offered stretches the suite from about
a minute to about eleven, because the read side can never catch up and every scenario burns
its settle budget. Budget for it; nothing is wedged.

## The gates that must hold before anything is committed

```bash
# No framework code was touched, by the whole branch. Must print nothing.
#
# Compare against `origin/main`, and note the two things this command gets right that the two
# obvious wrong forms get wrong.
#
#   `git diff --stat main -- ...` diffs against the LOCAL `main` ref, which is not the merge base:
#   this branch merges `origin/main` periodically, so once upstream has moved this reports every
#   framework commit the merge brought in and reads as a catastrophic gate failure. Measured on
#   this branch: 172 files and 13537 insertions of unrelated upstream work, none of it local.
#
#   `git diff --stat HEAD -- ...` diffs the WORKING TREE against HEAD, so it is empty the moment
#   anything is committed, whatever it contained. A gate that cannot fail after a commit is not a
#   gate -- and this gate's whole job is to be checked after committing.
#
#   `integrationtests` is NOT in the list. The suite owns its own integration-test infrastructure
#   and legitimately changes it (26 files on this branch); folding it in makes the gate red for ever
#   and trains the next reader to ignore it.
git diff --stat origin/main HEAD -- messaging eventsourcing modelling common conversion extensions test

# Nothing uncommitted anywhere either. Must print nothing.
git status --short

# ASCII only, across everything the suite adds. Must print nothing.
LC_ALL=C grep -rn '[^ -~\t]' simulation formal .claude/skills/axon-hunt
```

## Reading a history by hand

Every false finding this project produced was diagnosed in one pass of a script like this,
and none of them was diagnosed by reading the assertion message.

```bash
python3 - simulation/target/hunt-histories/<dir>/<scenario>-<seed>.jsonl <<'EOF'
import json,sys,collections
lines=[json.loads(l) for l in open(sys.argv[1]) if l.strip()]
recs=lines[1:]
print(collections.Counter((r['op'],r['type']) for r in recs))
for r in recs:
    if r['op'] in ('claim','store-token','split','merge','reset','node','phase','fault'):
        print(r['idx'], r['logicalTs']//1000000, r['node'], r['op'], r['type'], r.get('key'),
              {k:v for k,v in r['value'].items()
               if k in ('position','segment','action','carriedOut','quiesced')},
              r.get('error'))
EOF
```

Line 1 is the header; every following line is a record. `idx` defines the order -- **never
file order**, because records are serialized outside the recorder's write lock. `logicalTs`
is nanoseconds from one monotonic source and is what every latency and interval is derived
from; `wallTs` is for correlating with external evidence only and is never used for ordering.

## The CI shape

`.github/workflows/hunt.yml`: the smoke tier on every pull request across two JDKs; the seed
sweep nightly, chunked across a start-seed matrix; the container tiers weekly, split into a
backend-differential job and an infrastructure-fault job because a red differential names a
store and a red chaos arm names a fault. Every job asserts that
`surefire.rerunFailingTestsCount` is still pinned to 0 and that the workflow passes no rerun
count itself. Histories are archived as artifacts -- always for the sweep and the container
tiers, on failure for the smoke tier -- because under real threads the history is the only
exact record of the run that broke.
