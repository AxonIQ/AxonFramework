# Upcasting Integration: Plan C (Single-Pass)

**Status**: For design meeting, 2026-05-21 -- alternative to the two-phase model in `discussion-points.md`.

## The core problem (unchanged)

A rename transformation (US2: `CourseOpened` -> `CourseCreated`) has to change the message's `MessageType` **before** routing reads it. `MessageConverter` is invoked lazily (in handler's `payloadAs(...)` call), which is after routing. So a pure converter-decorator can't fix renames.

## Proposed solution: single-pass at storage / connector

Run the entire transformation chain eagerly on matching events at the storage / connector decorator layer. No second decorator on `MessageConverter`. No stage 1 / stage 2 split.

```
INCOMING message (event from storage, or command/query from Axon Server)
       |
       v
[TransformingX decorator]   (one per ingress, in axoniq-framework)
       |
       v
chain.apply(message)
   |
   +-- HashMap lookup by (name, version)
   |
   +-- no match  -> pass through unchanged (FR-012 preserved)
   |
   +-- match     -> apply transformation:
                     - rename (no payload rule): rewrite MessageType,
                       payload bytes unchanged, NO deserialization
                     - 1:1 transform: deserialize, run rule, produce
                       new Message with new type + new typed payload
                     - 1:N split: deserialize, run rule, emit N Messages
                     - 1:0 drop: emit zero Messages
       |
       v
new Message (or pass-through, or N messages, or zero)
       |
       v
ROUTING reads (possibly transformed) MessageType
       |
       v
handler dispatched
       |
       v
handler.payloadAs(SomeType.class, converter)
       |
       v
MessageConverter does its NORMAL job (no decoration; no chain logic here)
       |
       v
typed payload to handler
```

**Developer experience stays the same**: one `MessageTransformationChain`, one registration call. The decorators are framework-internal.

```
+--------------------------------------------------------------+
|  MessageTransformationChain chain =                          |
|      MessageTransformationChain.builder()                    |
|          .register( EventTransformation.rename(...) )        |
|          .register( EventTransformation.from(...).to(...) )  |
|          .register( CommandTransformation.rename(...) )      |
|          .register( QueryTransformation.from(...) )          |
|          .versionComparator( VersionComparator.semver() )    |
|          .build();   <-- runs FR-009 + FR-021 validations    |
|                                                              |
|  config.registerTransformationChain(chain);                  |
+--------------------------------------------------------------+
                              |
                              v
                       ONE chain object
                       shared across all 3 decorators
```

## Wiring sites

All in `axoniq-framework`, single module: `axoniq-framework/messaging/axoniq-message-transformation/`.

| Ingress | Decorator | Wraps |
|---|---|---|
| Events from local storage (JPA / JDBC / in-memory) | `TransformingEventStorageEngine` | axon-framework's `EventStorageEngine` |
| Events from Axon Server | same decorator (single `EventStorageEngine` component) | `AxonServerEventStorageEngine` (axoniq-framework) |
| Commands receive | `TransformingCommandBusConnector` | `AxonServerCommandBusConnector` (axoniq-framework) |
| Queries receive | `TransformingQueryBusConnector` | `AxonServerQueryBusConnector` (axoniq-framework) |
| Snapshots | flows through `TransformingEventStorageEngine` | -- (see Discussion point #1) |

Module layout (sketch -- not final):

```
axoniq-framework/messaging/axoniq-message-transformation/
|
+-- MessageTransformationChain
|     one chain object, shared by every decorator below
|
+-- TransformingEventStorageEngine           # NEW decorator
|     wraps axon-framework's EventStorageEngine.source / .stream
|     covers local storage reads AND Axon Server event reads
|     also processes SnapshotEventMessage entries on the stream
|
+-- TransformingCommandBusConnector          # NEW decorator
|     wraps axoniq-framework's AxonServerCommandBusConnector
|     fires on .handle() (incoming command); NOT on .dispatch()
|
+-- TransformingQueryBusConnector            # NEW decorator
      wraps axoniq-framework's AxonServerQueryBusConnector
      fires on incoming query; NOT on outgoing
```

No `TransformingMessageConverter`. Plan C doesn't need it.

Decorator registration order: `1` (right after `SnapshotCapableEventStorageEngine.DECORATION_ORDER = 0`). Verified against `ComponentRegistry.registerDecorator(...)` SPI (`common/.../ComponentRegistry.java:115-121`).

## Discussion points

### Real problems

1. **Snapshot payload upcasting is NOT covered**
   The chain matches `SnapshotEventMessage` entries flowing through `SnapshotCapableEventStorageEngine.source()` -- so it CAN rename / rebrand them at the identity level. But the snapshot's actual payload is converted via a separate code path:

   ```java
   // SnapshottingEntityLifecycleHandler.convertSnapshotPayload(), line 200-206
   snapshot.payload(converter.convert(snapshot.payload(), entityType))
   ```

   This is a direct `converter.convert(...)` call, not via `Message.payloadAs(...)`. Plan C doesn't decorate `MessageConverter`, so the chain's payload-transform rule never runs on the snapshot's actual payload data.

   **Implication**: a `(SnapshotV1) -> (SnapshotV2)` transformation with a payload rule does NOT work for snapshots. Only identity rename works.

   **For 5.2.0**: snapshot upcasting is deferred (Part C of spec), so this is acceptable but spec Part C needs tighter wording. Recommended:
   > "chain matches on `SnapshotEventMessage` identity (allowing rename / version mark) but does NOT transform the snapshot payload itself; a future snapshot-payload upcasting feature requires either decorating the converter call site in `SnapshottingEntityLifecycleHandler`, or adding a dedicated snapshot-upcasting SPI hook (small axon-framework change)."

2. **Eager payload materialization on the storage / worker thread**
   In the two-phase model, payload deserialization for matching events could be deferred to `payloadAs()` time. In Plan C, matching events get fully deserialized and a new `Message` object allocated on the storage / worker thread (`WorkPackage.java:66-73`).

   - Pure renames (no payload rule): no deserialization. Cheap.
   - 1:1 transform / split / conditional drop: deserialization was needed anyway -- same cost, different thread.

   **Risk**: chains with N transformations doing CPU-intensive payload work hit tracking-processor throughput. FR-006 already restricts transformations to deterministic / pure / thread-safe, so no blocking I/O is expected. FR-012's JMH benchmarks should explicitly cover this scenario.

3. **Still 3 wiring sites, not 1**
   Plan C does NOT collapse to a single decorator. AF5 has three ingress paths (`EventStorageEngine`, `CommandBusConnector`, `QueryBusConnector`); each gets its own decorator. The shared `MessageTransformationChain` object mitigates drift risk -- developer registers once, framework wires three decorators automatically.

   Same trade-off as the two-phase model: not a single integration site, but a single SPI and a single chain object.

### Implementation complexities

4. **Decorator ordering discipline**
   Register with order `1` to fire after `SnapshotCapableEventStorageEngine` and before any other decorators. Order is set in `ComponentRegistry.registerDecorator(EventStorageEngine.class, 1, ...)`. Discipline-based, well-defined.

5. **Chain runs on the processor's worker thread**
   For `PooledStreamingEventProcessor`, the chain executes on its dedicated worker thread. `ProcessingContext` is per-batch. Thread-safety is required (FR-006). Blocking I/O would stall the processor; transformations must be pure functions.

### What disappears compared to the two-phase model

| Two-phase problem | Status under Plan C |
|---|---|
| Splits / conditional drops break the laziness contract | Resolved -- payload deserialization for matching events is normal; FR-012 protects only the non-matching path. FR-003 unchanged, no pre-declaration of split outputs needed. |
| Stage 1 -> Stage 2 communication via Message | Resolved -- no second stage, no communication needed. |
| Converter-instance consistency | Resolved -- `MessageConverter` is NOT decorated. Every callsite uses the configurer-registered raw converter. |
| SPI complexity with type-aspect + payload-aspect | Resolved -- one transformation = `Message -> List<Message>`, single method. |
| ConversionCache + chain interaction | Resolved -- `Message.payloadAs(...)` uses raw converter, normal caching. |
| Write-path stage 2 | Resolved -- decorator does not wrap `MessageConverter`. Write path untouched. |

## Optional small axon-framework change (for 5.3+ only)

Plan C ships for 5.2.0 with **zero axon-framework changes**.

If snapshot payload upcasting becomes a 5.3+ feature, one small option:

| Option | Description | Size |
|---|---|---|
| A | `SnapshottingEntityLifecycleHandler` invokes a new `SnapshotPayloadUpcaster` hook before its direct `converter.convert(...)`. One interface + one hook call. | Small -- backward-compatible additive change. |
| B | `SnapshottingEntityLifecycleHandler` uses a configurer-registered `MessageConverter`; we then add a `TransformingMessageConverter` decorator just for that path. | Small but reintroduces converter-decoration for snapshots specifically. |

For 5.2.0 neither is needed.

## What needs decided tomorrow

1. **Plan C or two-phase?** Plan C's only real cost is "eager payload materialization for matching events". Two-phase's cost is "stage 1 / stage 2 connection requires axon-framework change or hacky metadata encoding".

2. **Spec Part C snapshot language**: tighten "chain processes `SnapshotEventMessage` entries like any other event" to reflect that only identity transformations work today, not payload upcasting.

3. **Splits / drops API** (carries over from the two-phase doc): independent of Plan C / two-phase decision. Recommended position under Plan C: keep `Function<Object, List<EventMessage>>` shape as-is. No pre-declaration of output types needed.
