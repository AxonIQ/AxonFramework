# Axon Server connector compatibility

Which released Axon Server connector artefacts load against this reactor, and what it costs when one does not.

This table is **derived from a runnable check**, not from reading. Re-derive any row with:

```bash
./mvnw -q -Phunt -pl simulation -o test -Dtest=ConnectorCompatibilityTest \
    -Dhunt.connectorJar=$HOME/.m2/repository/io/axoniq/framework/axon-server-connector/<version>/axon-server-connector-<version>.jar \
    -Dsurefire.failIfNoSpecifiedTests=false
```

It runs in about a second, starts no container, and prints one `CONNECTOR COMPATIBILITY` block per run.

## 1. Why this file exists

The Axon Server arm of the hunt suite was recorded as blocked for a whole phase, and the block was
not a test problem. It was an **undetected binary-compatibility break between the framework and its
own released client library**: `EventStorageEngine.source(SourcingCondition, ProcessingContext)` was
added as an abstract method, the single-argument form became a `default` delegating to it, and no
released connector implements the two-argument form. `javac` resolves a two-argument call against the
interface and accepts it. The JVM refuses it at the first invocation:

```
java.lang.AbstractMethodError: Receiver class
  io.axoniq.framework.axonserver.connector.event.AxonServerEventStorageEngine
does not define or inherit an implementation of the resolved method
  'abstract org.axonframework.messaging.core.MessageStream source(
     org.axonframework.eventsourcing.eventstore.SourcingCondition,
     org.axonframework.messaging.core.unitofwork.ProcessingContext)'
of interface org.axonframework.eventsourcing.eventstore.EventStorageEngine.
```

So the failure arrives minutes into a container run, from a stack trace naming a method rather than a
version, and it reads as a broken harness. `ConnectorCompatibilityTest` turns that into a named list
of methods before anything starts.

## 2. Status vocabulary

| Status | Meaning |
|---|---|
| `supported` | Every class resolves and no abstract method is left unimplemented. Nothing is shimmed. |
| `shimmed` | Every class resolves; some abstract methods are unimplemented and the harness supplies them. **A verdict from such an arm must name the shimmed set wherever it is recorded.** |
| `incompatible` | A class of the artefact cannot be loaded against this reactor at all. A missing *type* is a different break from a missing *method*, and no shim fixes it. |

## 3. The table

Framework: `5.3.0-SNAPSHOT` (this reactor). Server image: `docker.axoniq.io/axoniq/axonserver:2026.0.0`.

| Connector | Classes | Unresolvable | Unimplemented | Status | Notes |
|---|---|---|---|---|---|
| `io.axoniq.framework:axon-server-connector:5.2.2` | 90 | 0 | 4 | **shimmed** | The version the arm ships on. Newest on Maven Central at the time of writing. |
| `io.axoniq.framework:axon-server-connector:5.2.1` | 90 | 0 | 4 | **shimmed** | Same four methods. |
| `io.axoniq.framework:axon-server-connector:5.1.2` | 79 | 0 | 4 | **shimmed** | Same four methods. Eleven classes fewer than 5.2.x. |
| `org.axonframework:axon-server-connector:*` | -- | -- | -- | **incompatible** | The open-source coordinates were discontinued. Two packages the artefact references (`org.axonframework.messaging.commandhandling.distributed`, `...queryhandling.distributed`) now live in the commercial `axoniq-distributed-messaging` under `io.axoniq.framework.messaging.*`, so the artefact cannot resolve. Use the `io.axoniq.framework` coordinates. |

**No published connector is `supported`.** Every one of them predates the abstract method, so the arm
is a `shimmed` arm and says so in its label, in every history header it writes, and in every verdict
vector it appears in.

## 4. The four unimplemented methods, and what the harness does with each

Identical across 5.1.2, 5.2.1 and 5.2.2, and all four come from one framework commit
(`d3dc55f338bc9eb3d6e5b32b6c4ef315f952185d`, "[#3594] feature(eventsourcing): EventStorageEngine#source
and SnapshotStore methods - add ProcessingContext parameter").

| Unimplemented method | Harness | Why |
|---|---|---|
| `AxonServerEventStorageEngine -> EventStorageEngine.source(SourcingCondition, ProcessingContext)` | **shimmed** by `ContextCarryingAxonServerEngine` | The boundary-native engine every Axon Server hunt arm drives. The shim drops the context and calls the connector's own single-argument implementation. |
| `AggregateBasedAxonServerEventStorageEngine -> EventStorageEngine.source(SourcingCondition, ProcessingContext)` | **not shimmed** | The aggregate-based engine. No scenario drives Axon Server through it: the point of this arm is the boundary protocol, and the aggregate-based protocol already has a backend. |
| `AxonServerSnapshotStore -> SnapshotStore.load(QualifiedName, Object, ProcessingContext)` | **not shimmed** | No scenario loads a snapshot. See the note below: the connector's snapshot store is fully implemented, against the pre-context signatures. |
| `AxonServerSnapshotStore -> SnapshotStore.store(QualifiedName, Object, Snapshot, ProcessingContext)` | **not shimmed** | No scenario stores a snapshot. Same note. |

Not shimming the three is deliberate and is enforced: `ConnectorCompatibilityTest` asserts that every
unimplemented method is either shimmed **or** on the recorded not-driven list, so the day a scenario
loads a snapshot on this backend it fails on a method this table says is absent rather than on a
mystery. Shimming a method nothing exercises would model something nothing measures.

**"Unimplemented" is about the overloads, not about the class.** `AxonServerSnapshotStore` is a real, fully implemented
snapshot store: it has working bodies for `store(QualifiedName, Object, Snapshot)` and `load(QualifiedName, Object)`, both
public. What it does not have is the *context-carrying* overloads the interface now declares. And the snapshot side is a
**harder** break than the storage-engine side, which is worth knowing before anyone plans a fix:

* `EventStorageEngine` kept the one-argument `source(SourcingCondition)` as a `default`, so the connector's method is
  still a valid override of something on the interface. That is why a three-line shim works.
* `SnapshotStore` declares **only** the context-carrying forms and no `default` at all:
  `store(QualifiedName, Object, Snapshot, ProcessingContext)` and `load(QualifiedName, Object, ProcessingContext)`. The
  connector's two methods therefore override nothing -- they are unrelated methods that happen to share a name, and the
  class satisfies neither of the interface's abstract methods.

The consequence for a shim is that one is entirely *possible* -- the bodies exist and are public, so a subclass could
delegate the four-argument form to the three-argument one exactly as the engine's shim does -- and it is not written
because no scenario in this suite loads or stores a snapshot. The consequence for a *fix* is that adding a `default` is
not available here: there is no older form on the interface to delegate from, so it needs either a deprecated overload
pair on the interface or a lockstep connector release.

`ContextCarryingAxonServerEngine`'s Javadoc is the normative statement of what the one shim does and
does not model. The short version: the interface's own Javadoc says the two forms "behave identically"
and that the context is "passed to decorators ... that need to correlate the sourcing operation with
the surrounding unit of work", so a leaf engine that ignores it reads events exactly as the connector
reads them -- and any behaviour a future connector might derive from the context itself is **not**
modelled. A finding on this arm that could be explained by the absent context is not a finding.

## 5. What to do when a combination fails the gate

In order of preference. The first that applies is the right one.

1. **Pick a version the table records as `supported` or `shimmed`.** Cheapest, and it keeps the arm
   testing this reactor. Change the `<version>` of `io.axoniq.framework:axon-server-connector` in
   `simulation/pom.xml` and re-run the gate.
2. **Extend the shim** -- add the method to `ContextCarryingAxonServerEngine`, add it to the `SHIMMED`
   set in `ConnectorCompatibilityTest`, add a row to section 4 saying exactly what it models and what
   it therefore does not, and add the method to the backend's `versions()` so it reaches every history
   header. Only worth doing for a method a scenario actually drives.
3. **Record the combination as `incompatible`** with the real error, and say what coverage is lost.
   This is the right answer for an unresolvable class: no shim fixes a missing type. It is also the
   right answer when the method's absence would make the arm model something other than the framework
   -- a shim that has to guess at behaviour produces findings about the guess.

**Never** relax the gate to make it pass. A green gate that has stopped comparing is how the arm got
blocked in the first place.

## 6. Deferred, deliberately

Named so the next reader knows the ladder continues rather than ends here.

| Deferred | Why |
|---|---|
| A framework-by-connector-by-image CI matrix | Three axes of container runs. The gate answers the linkage question for every combination in a second, which is most of the value; running the whole corpus on each is a phase of its own. |
| Resolving released artefacts automatically | The gate takes a path. Resolving `5.2.x` from a repository, or sweeping every published version, needs dependency resolution inside a test. |
| `japicmp` or `revapi` | Either would do this properly, and structurally: an API-compatibility plugin on the framework's own build would catch the break at the point it was introduced rather than in a test downstream of it. That is the right long-term answer and it is a build-level decision, not a harness one. |
| Classloader isolation for mixed-version nodes | The gate's loader prefers the artefact for the connector's own packages, which is enough to compare one artefact against this reactor. Running two connector versions in one virtual machine is a different problem. |
