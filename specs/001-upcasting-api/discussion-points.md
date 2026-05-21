# Upcasting Integration: Discussion Points

**Status**: For design meeting, 2026-05-21
**Context**: After our 2026-05-20 exchange, "decorator around MessageConverter" needs refinement -- the converter-decorator alone can't handle MessageType renames before routing.
**Update 2026-05-20 (evening)**: Steven confirmed that all upcasting code belongs in `axoniq-framework`, not `axon-framework`. This is a commercial-only AxonIQ feature. All decorators, factories, and the chain SPI live in `axoniq-framework/messaging/axoniq-message-transformation/`. `axon-framework` is untouched -- we only depend on its types (`Message`, `MessageType`, `MessageConverter`, `EventStorageEngine`).

## The core problem

A rename transformation (US2: `CourseOpened` -> `CourseCreated`) has to change the message's `MessageType` **before** routing reads it. The current `MessageConverter` is invoked lazily (in handler's `payloadAs(...)` call), which is after routing. So a pure converter-decorator can't fix renames.

## Proposed solution: two-phase model

Split the transformer chain into two stages that fire at different points in the pipeline:

```
                                Stage 1
                          (type/version upcast)
                                   |
                          - reads MessageType only
                          - applies rename / version bump
                          - cheap; no payload deserialization
                                   |
                                   v
                              ROUTING
                          reads transformed MessageType,
                          picks handler
                                   |
                                   v
                          handler.payloadAs(Type)
                                   |
                                Stage 2
                          (payload upcast + type conv)
                                   |
                          - decorator around MessageConverter
                          - chain's payload transform runs
                          - then converter does byte[] -> Type
                          - lazy; only fires on matching paths
                                   |
                                   v
                          typed payload to handler
```

**Developer experience stays the same**: one `MessageTransformationChain`, one registration call. The two-phase split is framework-internal.
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
                       shared across all
                       wiring sites
```
## Wiring sites

All in `axoniq-framework`, single module: `axoniq-framework/messaging/axoniq-message-transformation/`.

| Ingress | Stage 1 wired in |
|---|---|
| Events from local storage (JPA / JDBC / in-memory) | `TransformingEventStorageEngine` decorator |
| Events from Axon Server | Connector decorator (TBD) -- wires into `axon-server-connector` |
| Commands receive | `TransformingCommandBusConnector` decorator |
| Queries receive | `TransformingQueryBusConnector` decorator |
| Snapshots | flows through `TransformingEventStorageEngine` |

Stage 2 is one `TransformingMessageConverter` decorator wrapping `MessageConverter` -- invoked from `Message.payloadAs(...)` and from `SnapshottingEntityLifecycleHandler`'s direct converter call.

Module layout (everything in one place, sketch -- not final):

```
axoniq-framework/messaging/axoniq-message-transformation/
|
+-- MessageTransformationChain
|     one chain object, shared by every decorator below
|
+-- Stage-1 decorators (run BEFORE routing, on incoming messages):
|     |
|     +-- TransformingEventStorageEngine
|     |     wraps axon-framework's EventStorageEngine.source / .stream
|     |     covers local storage reads AND SnapshotEventMessage entries
|     |
|     +-- TransformingCommandBusConnector
|     |     wraps axoniq-framework's AxonServerCommandBusConnector
|     |     fires on .handle() (incoming command); NOT on .dispatch()
|     |
|     +-- TransformingQueryBusConnector
|           wraps axoniq-framework's AxonServerQueryBusConnector
|           fires on incoming query; NOT on outgoing
|
+-- Stage-2 decorator (runs LAZILY on payload access):
      |
      +-- TransformingMessageConverter
            wraps axon-framework's MessageConverter
            invoked by Message.payloadAs(Type, converter)
            also invoked by SnapshottingEntityLifecycleHandler's direct converter call
            runs the chain's payload-transform stage,
            then the underlying converter does the byte[] -> requested type conversion
```
## Discussion points 

1. **Splits (US3, 1:N) and conditional drops (US4, 1:0) break the laziness contract**
   The split/drop rule is `Function<Object, List<EventMessage>>` -- requires the payload to decide outputs. Stage 1 doesn't have the payload. Options:
   - (a) Pre-declare output `MessageType`s in the builder: `.split(SOURCE).into(TYPE_A, TYPE_B).expand(rule)`. Stage 1 multiplies the entry into placeholder messages with the declared types; stage 2 fills the payloads. Restrictive API but preserves FR-012.
   - (b) Run split/drop rules eagerly at stage 1 (deserialize the payload). Breaks FR-012's lazy contract specifically for splits and drops.
   - (c) For drops only: support only **unconditional drop-by-type** -- register a drop for a `MessageType` and every event with that `(name, version)` is dropped, no payload-dependent logic. Stage 1 handles this via a simple type lookup, no rule execution. Splits still need (a) or (b). Compared to current FR-003, this removes conditional dropping (e.g., "drop `SystemHeartbeat` only when `source == 'test-system'`").

2. **Product split + repo location** -- RESOLVED 2026-05-20 (Steven)
   Upcasting is a commercial-only AxonIQ feature. All code lives in `axoniq-framework`. Implications still to act on:
   - Pure Axon Framework users cannot use upcasting -- the spec needs an explicit assumption stating the `axoniq-framework` dependency.
   - The single-repo placement removes the cross-repo coordination concern entirely; ships as part of a normal `axoniq-framework` release.

3. **SC-005 demo location** -- RESOLVED 2026-05-20 (implicit from item 2)
   With all code in `axoniq-framework`, the demo moves to `axoniq-framework/examples/`. SC-005 in the spec needs to be updated to point at the new location.

### Implementation complexities 

4. **Multiple wiring sites for what's conceptually one feature**
   Real count is 4 stage-1 sites (local events / Axon Server events / commands / queries) + 2 stage-2 invocation paths (`payloadAs(...)` and snapshot conversion). Shared chain object mitigates drift risk but every site has to be touched.

5. **Stage 2 only fires if every `.withConverter()` site uses the decorated converter**
   `MessageConverter` is attached to messages at construction. Inconsistent attachment = stage 2 silently skipped on some paths. Discipline-based, not type-system-enforced.

6. **Service Provider Interface complexity grows**
   Each transformation now has a type-rewrite aspect (stage 1) and a payload-rewrite aspect (stage 2). For splits, stage 1 needs "what output types do I produce" separately from "given input, produce output payloads".

7. **Stage 2 + `ConversionCache` interaction**
   Cache key has to be chain-aware. Different handlers asking for different target types must either share the transformation result or re-run the chain consistently.

8. **Write-path stage 2**
   `MessageConverter` is also invoked at write time (`event.payloadAs(byte[].class, converter)`). The decorator must not fire on write -- either separate converter instances per direction, or smart detection in the decorator.

