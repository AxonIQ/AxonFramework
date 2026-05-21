# Implementation Plan: Event Upcasting API

**Branch**: `enhancement/3597/revise-upcaster-api` | **Date**: 2026-05-21 | **Spec**: [spec.md](spec.md)

**Repo**: this feature now lives in `axoniq-framework` (issue moves there). `axon-framework` stays untouched except for one small additive change (see "Required axon-framework additions").

## Summary

Add a transformer chain to AxonIQ Framework 5.2.0 that runs at three ingress sites (the event storage engine for reads, the command bus connector for incoming commands, the query bus connector for incoming queries) and rewrites a message's identity (`MessageType`) and/or payload before routing. The chain is a single shared object the developer registers once; the framework wires three thin decorators around it. No converter-side decoration; matching events are deserialized eagerly. FR-012 still protects the non-matching path.

The internal SPI mirrors AF4's stream-in / stream-out shape (`MessageStream<M> -> MessageStream<M>`), exposed via typed specializations (`EventTransformer`, `CommandTransformer`, `QueryTransformer`). User-facing factories (`EventTransformation`, `CommandTransformation`, `QueryTransformation`) produce these instances; users rarely touch the SPI directly.

5.2.0 delivers **events** end-to-end. Command and query upcasting is architecturally supported by the same SPI but its delivery (the receive-side decorator wiring) is tracked as separate follow-on issues, lower priority. Snapshot transformations remain deferred (Part C of spec); architecture stays compatible.

## Technical Context

**Language/Version**: Java 21 (sealed types, records, pattern matching).

**Primary Dependencies**: `axon-framework` core modules (`common`, `messaging`, `modelling`, `eventsourcing`). JUnit 5, AssertJ, Awaitility for tests; JMH for FR-012 chain-cost benchmarks.

**Target Repo**: `axoniq-framework`. New module: `messaging/axoniq-message-transformation/`. Depends on `axon-framework`'s messaging + eventsourcing modules; also on `axoniq-distributed-messaging` for the `CommandBusConnector` / `QueryBusConnector` interfaces.

**Integration points** (decorator-around-component, registered via `ComponentRegistry.registerDecorator(...)`):

- **Events**: `EventStorageEngine` (covers local JPA / JDBC / in-memory engines AND the Axon Server event store connector, sharing the same component key). `SourcingCondition` and `StreamingCondition` filtering runs at the storage layer BEFORE the chain (FR-013).
- **Commands**: `CommandBusConnector`. Fires on `.handle()` (incoming), not `.dispatch()` (out-of-scope: downcasting).
- **Queries**: `QueryBusConnector`. Same pattern.

**Testing**: JUnit 5 + AssertJ; Awaitility for async; JMH for FR-012 thresholds.

**Target Platform**: JVM (Java 21+). Library code.

**Project Type**: AxonIQ Framework feature module + decorator integrations in the Axon Server connector module + demo in `axoniq-framework/examples/`.

**Performance Goals**:

- O(1) per-event lookup on the non-matching path with no per-event allocations (FR-012). Per-`QualifiedName` sub-chain map (FR-007) gives this directly.
- JMH benchmark dimensions: chain length in `{1, 10, 50, 100}` x event count `1M`, with `-prof gc`. Pass: per-event latency variance < 10% across chain lengths; `gc.alloc.rate.norm` constant in chain length.

**Constraints**:

- Programmatic registration only; chain locked once event processing begins (FR-004).
- No `IntermediateEventRepresentation`-equivalent (Constitution II). Transformer operates on `MessageStream<M>` over typed `Message` subtypes.
- Transformer is a decorator-around-storage-engine / decorator-around-bus-connector (NOT a decorator around `MessageConverter`).
- ASCII-only source files; LF line endings; JSpecify `@NullMarked` package-level (per CLAUDE.md).

**Scale/Scope**: Hundreds of registered transformations per chain feasible (typically <50 per QualifiedName). Millions of events per replay must not allocate per-event on the non-matching path.

## Constitution Check

Per `.specify/memory/constitution.md` v1.1.1.

| Principle | Spec alignment | Status |
|---|---|---|
| I. Spec-First | 9 user stories, Given/When/Then throughout | PASS |
| II. Simpler than AF4 | No `IntermediateEventRepresentation`; SPI operates on `MessageStream<M extends Message<?>>` | PASS |
| III. Message-Based API | `MessageTransformer<M extends Message<?>>` applies to any subtype (FR-020) | PASS |
| IV. Registration Ordering | Registration order = sub-chain order (FR-007); optional `VersionComparator` adds a build-time order check (FR-021) | PASS |
| V. Converter vs Upcaster | Transformer decorates storage engine / bus connector; the `Converter` runs unchanged after routing (FR-013) | PASS |
| VI. Immutability First | Storage engine is append-only; chain runs at READ, never mutates stored events (FR-013, FR-022) | PASS |
| VII. Deterministic | FR-006 contract; SC-010 stress-test verifies | PASS |
| VIII. SRP per upcaster | One transformation = one `from`/`to` (or one source identity for 1:N) | PASS |
| IX. Prefer Chain over Direct | US5 explicitly tests v1->v2->v3 chained | PASS |
| X. Type-Based Versioning forbidden | Part C "Wrong tool: new event cannot be derived from old" entry | PASS |
| Scope Boundaries (5.2.0) | Events delivered; commands/queries architectural; snapshots deferred, matching Part C | PASS |
| AF5 Anchoring Types | Public surface stays on the anchor list (`Message`, `MessageType`, `MessageConverter`, `EventMessage`, `MessageStream`). Integration types we depend on (`EventStorageEngine`, `CommandBusConnector`, `QueryBusConnector`, `SnapshotEventMessage`) are framework infrastructure we call into. | PASS (see Complexity Tracking) |

**Gates**: Phase 0 entry passes.

## Project Structure

### Documentation

```text
specs/001-upcasting-api/
|-- spec.md                  # /speckit-specify + /speckit-clarify
|-- spec-review-summary.md   # high-level review summary
|-- plan.md                  # this file
|-- _archive/
|     |-- discussion-points.md     # pre-meeting two-phase exploration
|     `-- plan-c-proposal.md       # pre-meeting single-pass proposal (won)
|-- contracts/               # may be re-created post-plan; see follow-on
`-- tasks.md                 # generated by /speckit-tasks
```

### Source code (in `axoniq-framework`)

```text
axoniq-framework/messaging/axoniq-message-transformation/   (NEW module)
|-- src/main/java/io/axoniq/framework/messaging/transformation/
|     |-- MessageTransformer.java               # SPI base, generic over Message<?>
|     |-- EventTransformer.java                 # specialization, extends MessageTransformer<EventMessage<?>>
|     |-- CommandTransformer.java               # specialization, extends MessageTransformer<CommandMessage<?>>
|     |-- QueryTransformer.java                 # specialization, extends MessageTransformer<QueryMessage<?>>
|     |
|     |-- MessageTransformerChain.java          # per-QualifiedName sub-chains (FR-007), .build() locks (FR-004)
|     |-- VersionComparator.java                # optional (FR-021)
|     |-- SemverComparator.java                 # builder convenience
|     |
|     |-- EventTransformation.java              # factory: rename(...), from(...).to(...), split(...)
|     |-- CommandTransformation.java            # factory: rename(...), from(...).to(...)
|     |-- QueryTransformation.java              # factory: rename(...), from(...).to(...)
|     |
|     |-- TransformingEventStorageEngine.java   # decorator (Phase 1, delivered in 5.2.0)
|     |
|     `-- configuration/
|           `-- MessageTransformationConfigurationEnhancer.java
|                                               # registers the chain + wires decorators
|
`-- src/test/java/...                           # FR-009 conflict tests, FR-021 comparator, FR-007 sub-chain routing, ...

axoniq-framework/examples/                      # university-demo + analogous examples
`-- src/{main,test}/java/.../upcasting/
      |-- CourseCreatedV1V2.java                # US1
      |-- CourseOpenedRenamed.java              # US2
      |-- StudentEnrolledSplit.java             # US3
      |-- SystemHeartbeatDropped.java           # US4
      `-- CourseCreatedChain.java               # US5
```

**Phase 2 follow-on (separate issues, NOT in 5.2.0)**:
- `TransformingCommandBusConnector.java`
- `TransformingQueryBusConnector.java`
- `examples/.../EnrollStudentCommandUpcaster.java` (US8)
- `examples/.../FindCoursesByFacultyQueryUpcaster.java` (US9)

**Structure Decision**: Single module in `axoniq-framework`. Per Steven's meeting confirmation, all upcasting code lives there; pure Axon Framework users do not get upcasting. The new module sits next to `axoniq-distributed-messaging` and `axoniq-dead-letter` in the messaging family.

## SPI shape

The internal SPI mirrors AF4's `Upcaster<T>` (stream-in, stream-out) and reuses AF5's `MessageStream`:

```java
public interface MessageTransformer<M extends Message<?>> {
    MessageStream<M> transform(MessageStream<M> stream);
}
```

Per-type specializations expose convenience overloads for single-entry streams (commands and queries are single-intent):

```java
public interface CommandTransformer extends MessageTransformer<CommandMessage<?>> {
    MessageStream.Single<CommandMessage<?>> transform(MessageStream.Single<CommandMessage<?>> stream);

    @Override
    default MessageStream<CommandMessage<?>> transform(MessageStream<CommandMessage<?>> stream) {
        return transform(stream.first());
    }
}
```

`EventTransformer` and `QueryTransformer` follow the same shape.

Users almost never touch this SPI. They use the factories (`EventTransformation.rename(...)`, `EventTransformation.from(...).to(...).transform(...)`, `EventTransformation.split(...)`, `CommandTransformation.rename(...)`, etc.) which produce typed `MessageTransformer` instances internally.

## Required axon-framework additions

Plan C as a whole is implementable purely in `axoniq-framework` via existing decorator SPI. One small additive change to `axon-framework` is needed:

- **`MessageStream.flatMap(Function<? super M, ? extends MessageStream<R>>)`**: needed for the chain implementation (1:N splits re-enter sub-chains per FR-007). Backward-compatible default method or new operator. Approx <30 LOC + tests.

Snapshot delivery (deferred) may want one further small addition in a future release (a hook in `SnapshottingEntityLifecycleHandler` for snapshot-payload upcasting), but that is out of scope here.

## Phases of delivery

| Phase | Scope | Deliverable |
|---|---|---|
| **Phase 1** (5.2.0) | Events end-to-end (US1-US7) | `MessageTransformer` SPI + `EventTransformer` + chain + `TransformingEventStorageEngine` + demo examples for US1-US5 + observability per US7 |
| **Phase 2** (follow-on issues, not 5.2.0) | Commands + Queries (US8, US9) | `CommandTransformer` + `QueryTransformer` + connector decorators + corresponding demo examples |
| **Phase 3** (5.3+ candidate) | Snapshot payload upcasting | Either decorate `SnapshottingEntityLifecycleHandler`'s converter call site, or introduce a `SnapshotPayloadUpcaster` SPI hook. Small `axon-framework` change. |

The Phase 1 work is the issue tracked under #3597. Phase 2 and Phase 3 are separate issues.

## Complexity Tracking

| Drift / decision | Why needed | Simpler alternative rejected because |
|---|---|---|
| AF5 Anchoring Types extended with integration types (`EventStorageEngine`, `CommandBusConnector`, `QueryBusConnector`) | The transformer decorates these; we MUST call into them | Wrapping them in a new abstraction would reintroduce the AF4 `IntermediateEventRepresentation` pattern (Constitution II forbids) |
| One small additive change to `axon-framework` (`MessageStream.flatMap`) | Chain composition via per-QualifiedName sub-chains (FR-007) requires flatMap on `MessageStream` | Manually composing via existing `mapMessage` + reentrant calls is possible but uglier; flatMap is a standard stream operator the API was missing |
| Constitution references "spec FR-008 / US5" for snapshot deferral; current spec has snapshots in Part C with no FR-008 | Constitution v1.1.1 predates the spec restart that moved snapshots to Part C | Fix is a separate `/speckit-constitution` PATCH bump (1.1.1 -> 1.1.2). Substantive alignment intact; snapshots deferred in both documents. |
