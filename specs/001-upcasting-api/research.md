# Phase 0 Research: Event Upcasting API

**Feature**: Event Upcasting API for AF 5.2.0 (issue #3597) | **Spec**: [spec.md](spec.md) | **Date**: 2026-05-19

Resolves the technical unknowns surfaced during Phase 0. Spec is feature-complete
(FR-001..FR-019, US1..US8); Phase 0 anchors the design to concrete points in the AF5
codebase. Each section: **Decision / Rationale / Alternatives**.

---

## R-1. Convergence point for the three reading contexts (FR-013)

**Decision**: decorate the `MessageStream<EventMessage>` returned by
`EventStorageEngine.source(...)` and `EventStorageEngine.stream(...)`.

**Rationale**: `EventStorageEngine` is the narrowest abstraction all read paths share --
entity loads, DCB reads, and tracking processors all funnel through these two methods.
**Anything that reads events MUST go through the engine, so anything that reads events
MUST go through the decorator.** Wrapping the returned stream applies the chain
uniformly across all engines without touching any engine implementation: it works for
`AggregateBasedJpaEventStorageEngine` (where `convertToEventMessage()` at `:420` has
already deserialised bytes by the time the stream is returned) and
`InMemoryEventStorageEngine` (live `EventMessage` objects, no materialisation step).
AF4 embedded the chain inside each engine's `readEvents()` -- the decorator avoids that
per-engine wiring.

**Snapshots out of scope by construction**: `Snapshotter` / `StoreBackedSnapshotter`
load on a separate path that does not flow through `EventStorageEngine`, so the
decorator does not intercept them. Intentional -- US5 is deferred (FR-008) and gets its
own hook later.

**Known constraint**: upcasters execute on the consumer thread (entity loader / tracking
processor worker). No separate thread pool, no time budget. FR-006 already obligates
upcasters to be deterministic, thread-safe, and free of I/O -- a misbehaving upcaster
slows the processor visibly, never fails silently.

**Architectural invariant**: today every event-read path in AF5 routes through this
interface. No upcasting architecture is bypass-proof; this approach minimises the
bypass surface to one boundary. Mitigations: (i) FR-013 integration test asserting all
three reading contexts observe upcast events; (ii) javadoc on `EventStorageEngine`
declaring it the authoritative upcasting boundary so future contributors see the
constraint when modifying the engine layer.

**Alternatives rejected**:
- Hook inside each engine (e.g. `convertToEventMessage()`) -- in-memory has no such
  step; every new engine must re-wire.
- Wrap `EventConverter` -- identity changes are outside its remit (Constitution Gate V).
- Hook each reading context at the caller -- three drift points; exactly the
  inconsistency FR-013 forbids.

---

## R-2. Storage-engine integration shape

**Decision**: introduce an immutable **`UpcasterChain`** that implements
`EventStorageEngine`, delegates to the real engine, and `flatMap`s the `MessageStream`
returned by `source(...)` and `stream(...)`. Constructed once at `.build()`. Holds a
`Converter` for FR-010 (deserialises bytes for JPA, identity pass-through for in-memory).
Pattern matches the existing `InterceptingEventStore` decorator over `EventStore`.

**Why `flatMap` and not `map`**: upcaster outputs span four cardinalities --
non-matching (`just(event)`), 1:1 (`just(transformed)`), 1:N (`fromItems(...)`), drop
(`empty()`). `map` only handles 1:1; `flatMap` collapses all four to one expression.

`MessageStream` does not expose `flatMap` today, but the codebase already wants it:
`InterceptingEventSink.java:107-113` requests it in a comment and uses a `concatWith`
loop as a workaround. **This work adds `MessageStream.flatMap(...)` plus
`FlatMappedMessageStream`** -- modelled after existing `FilteringMessageStream` /
`ConcatenatingMessageStream`. Load-bearing for upcasting; pays down a pre-existing gap.

**Empty-chain short-circuit**: the `ConfigurationEnhancer` installs the decorator only
when `UpcasterRegistry.entries` is non-empty. Zero upcasters = zero overhead -- the
user-configured engine is used unchanged.

**Runtime error attribution (FR-016)**: the flatMap function wraps every
`upcaster.apply(...)` in `try/catch`, converting any `RuntimeException` to
`UpcasterApplicationException(message, cause)` carrying upcaster identity + event
`MessageType` + stream position. Surfaced through `MessageStream.error()` so the
caller always knows which upcaster failed on which event.

---

## R-3. Registration entry point

**Decision**: `EventSourcingConfigurer.registerEventUpcaster(ComponentBuilder<EventUpcaster>)`.
Accumulates into an `UpcasterRegistry` (mirrors `DispatchInterceptorRegistry`), preserving
registration order. A `ConfigurationEnhancer` finalises and locks the chain at `.build()`.

**Rationale**:
```
EventSourcingConfigurer  -->  ModellingConfigurer  -->  MessagingConfigurer
       |                              |                       |
       | registers                    |                       | contains
       | EventStorageEngine           |                       v
       | EventStore                   |              EventProcessingConfigurer
       +------------------------------+-----------------------|
                                                              |
                                                              v
                                              PooledStreamingEventProcessorsConfigurer
                                                              |
                                                              | resolves StreamableEventSource
                                                              v
                                              -- same root ComponentRegistry --
```
- Transformations sit between event store and consumers, so `EventSourcingConfigurer` is
  the right scope (`MessagingConfigurer` is too broad; `EventProcessingConfigurer` is
  per-processor).
- `ComponentBuilder<EventUpcaster>` matches the existing AF5 registration convention.
- Ordered registry mirrors `DefaultDispatchInterceptorRegistry`
  (`messaging/.../configuration/DefaultDispatchInterceptorRegistry.java:64`).
- Locking falls naturally out of the configurer's `.build()` flow -- exactly FR-004.

**Reaches tracking processors transitively**: AF5 uses a single unified `ComponentRegistry`
across `EventSourcingConfigurer` -> `ModellingConfigurer` -> `MessagingConfigurer` ->
`EventProcessingConfigurer`. `EventStore` (a `StorageEngineBackedEventStore`) captures
the `EventStorageEngine` reference at construction. Tracking processors resolve
`StreamableEventSource.class`, which type-assignability-matches to the registered
`EventStore` instance, which holds the decorated `EventStorageEngine`. One registration
on `EventSourcingConfigurer` therefore covers all three FR-013 reading contexts.

**Wiring constraint**: the enhancer that installs `UpcasterChain` MUST run before
`EventStore` is built. `EventStore` captures the engine reference at construction; if it
constructs first, it holds the un-decorated engine permanently and tracking processors
silently bypass upcasting. Enforce via explicit `ConfigurationEnhancer` ordering.

**Future-proofing for #746**: when command/query upcasting lands, `registerEventUpcaster`
on `EventSourcingConfigurer` stays as-is. #746 adds parallel registration methods
(`registerCommandUpcaster` / `registerQueryUpcaster`) on the appropriate configurers --
no breaking move of the event method.

**Alternatives**:
- Separate `UpcastingConfigurer` -- premature; existing AF5 patterns put domain-specific
  registration on the configurer that owns the relevant component.
- Annotation-based discovery (`@Upcaster`) -- explicitly deferred (spec Part C);
  reintroduces the AF4 ordering ambiguity.

---

## R-4. Public interface for a transformation

**Decision**: generic root `Upcaster<M extends Message<?>>` in `messaging/upcasting/`
with only `MessageType from()`. Event-facing root `EventUpcaster extends Upcaster<EventMessage>`
is sealed with two convenience subtypes:

- `SingleEventUpcaster` — exactly one output with a declared `to()` identity
  (FR-001 structural, FR-002 rename).
- `MultiEventUpcaster` — zero or more outputs; the rule owns each output's identity
  (FR-003 split, drop).

Output-identity validation (FR-019) is enforced by the `SingleEventUpcaster` adapter,
which compares the produced event's `MessageType` against the declared `to()` and
propagates a runtime failure on mismatch.

**Rationale**:
- `Upcaster<M extends Message<?>>` lets #746 add `CommandUpcaster` / `QueryUpcaster`
  later without breaking the event API (Clarification 2026-05-19, Q1).
- Two subtypes match AF4's `SingleEventUpcaster` / `EventMultiUpcaster` distinction;
  naming preserved for team familiarity.
- A static factory `EventUpcaster.rename(from, to)` covers the degenerate rename case
  (FR-002) without a payload function.
- The `Converter` parameter exposes the advanced FR-010 access path. The declarative path
  pre-wires conversion inside a builder before invoking the user's lambda.

**Alternatives**:
- Single interface with `boolean isSplit()` — SRP violation; no compile-time guidance.
- Functional interface only — FR-019 needs type-level distinction so the output-identity
  check applies to the right subtype only.

---

## R-5. Payload access: declarative target type vs Converter access

**Decision**: two entry points (Clarification 2026-05-19, Q2):

1. **Declarative (simple, payload-only)** — static builder:
   ```java
   EventUpcaster.from(name, version)
       .to(name, version)
       .reading(CourseCreatedV1.class)
       .producing(CourseCreatedV2.class)
       .apply(v1 -> new CourseCreatedV2(v1.id(), v1.capacity(), v1.capacity()));
   ```
   The framework injects a `ChainedConverter` to convert stored bytes to
   `CourseCreatedV1` before the lambda runs.

2. **Converter access (advanced / message-level)** — the user implements
   `SingleEventUpcaster` or `MultiEventUpcaster` directly and receives a `Converter`.
   Multiple representations (peek as `JsonNode`, `GenericRecord`, `Map<String, Object>`,
   or a DOM `Document` for an XML converter, then return a POJO) are supported by
   repeated `.convert()` calls. This path also gives message-level control (FR-011): the
   upcaster returns a complete `EventMessage`, including its metadata.

Both entry points produce the same internal `EventUpcaster` instance. Conversion is
on-demand (FR-012): no pre-conversion of non-matching events.

**Rationale**: the bare-bones path requires zero converter knowledge (Constitution
Gate II); the advanced path is a single parameter type away. The shipped
`ContentTypeConverter` chain (Jackson, CBOR, Avro) already bridges stored bytes to
structured types; a user-supplied converter (e.g. XML) plugs into the same chain.

**Alternatives**:
- Advanced-only API — 80% of upcasters are simple 1:1; violates SC-001.
- Auto-detect target type via reflection — brittle, conflicts with FR-018 (plain JUnit
  testability).

---

## R-6. Chain composition and execution model

**Decision**: ordered list of `EventUpcaster` instances. Lazy, streaming execution:

1. If `upcaster.from()` does not match `event.type()`, the event passes through unchanged
   (FR-005, FR-012 — no deserialisation).
2. If it matches, `apply(...)` runs.
3. Each output re-enters the chain at the **next** upcaster, not the start — composition
   by registration order (FR-007, US6).
4. For `SingleEventUpcaster`, framework verifies output identity matches declared `to()`
   (FR-019); mismatch propagates with full context (FR-016).
5. Results flatten into the downstream `MessageStream<EventMessage>`.

For tracking processors, dropped events still advance the token (FR-015) — the chain
returns an empty list; the consumer uses the original entry's tracking position.

**Rationale**: streaming/visitor composition matches AF4's `GenericUpcasterChain` design
(familiar to the team), without `IntermediateEventRepresentation` baggage. "Next upcaster,
not start" is what makes registration order safe: v1->v2 produces a v2 that v2->v3 picks
up; cycle-free by construction.

**Alternatives**:
- Recursive re-entry from chain start — could loop on `from == to` (already caught by
  FR-009; defensive at chain level is wasted complexity).
- Eager batch transformation — violates FR-012 and blocks the on-demand FR-010 path.

---

## R-7. Converter type to expose to user code

**Decision**: expose `org.axonframework.conversion.Converter` as the parameter type for
the advanced FR-010 entry point.

**Rationale**: `Converter` is the foundational AF5 conversion interface, providing
`convert(Object, Class<T>)` — exactly what an upcaster needs. `ChainedConverter` is a
content-type converter (`ContentTypeConverter<S,T>`), wrong surface for typed user code.
`GeneralConverter` is a marker subtype with no extra methods; AF5 does not expose marker
types to user code. The shipped `ContentTypeConverter` instances (Jackson, Avro, CBOR,
user-supplied) are wired into the same `Converter` chain the storage engine uses, so the
upcaster receives the fully configured converter (handling `byte[] -> structured ->
POJO` automatically).

---

## R-8. Snapshot upcasting (deferred to US5)

**Decision**: ship event upcasting in 5.2.0; defer snapshot upcasting to US5 (future
release). The 5.2.0 implementation uses a **parallel hierarchy** under
`eventsourcing/snapshotting/upcasting/` (when US5 ships) -- no shared root with
`Upcaster<M extends Message<?>>` for now.

**What the team converged on (2026-05-19)**: (a) snapshot upcasting deferred from 5.2.0;
(b) the 5.2.0 integration is event-specific. The broader question -- whether snapshots
later share a common API-level root with events or remain a parallel hierarchy forever --
was discussed but deliberately left open. At the API surface an upcaster needs only
payload + name + version + optional target version, so a generic shared root is feasible
in principle. Parallel hierarchies are chosen as the 5.2.0 starting point because Gate II
forbids shipping an abstraction with only one concrete user, and the right shared
identity contract for snapshots is not yet known. A shared root remains possible as a
future internal refactor -- non-breaking for 5.2.0 customers either way (see table
below).

**Parallel hierarchy commitments** (the contract US5 must honour):

| Aspect | Commitment |
|--------|-----------|
| Location | `eventsourcing/snapshotting/upcasting/` (parallel to event-side `eventsourcing/upcasting/`) |
| Root | `SnapshotUpcaster` -- standalone interface, no shared parent with `Upcaster<M extends Message<?>>` |
| Cardinality | 1:1 only. `SingleSnapshotUpcaster` is the only subtype (sealed hierarchy enforces it). No splits, no drops -- domain rule (snapshots are state, not events). |
| Identity | New `SnapshotType` record -- parallel shape to `MessageType` (`QualifiedName` + version), distinct type. `Snapshot` is NOT a `Message`; reusing `MessageType` would be semantically wrong. |
| Builder DSL | Isomorphic in shape to `EventUpcaster.from(...).to(...).reading(...).producing(...).apply(...)` for mental-model transfer. |
| Registration | `EventSourcingConfigurer.registerSnapshotUpcaster(ComponentBuilder<SnapshotUpcaster>)` -- parallel to `registerEventUpcaster`. |
| Integration hook | `StoreBackedSnapshotter.load()`'s version-mismatch branch -- separate from event-side `EventStorageEngine` decorator. |

**Non-breaking guarantee for 5.2.0 customers**:

| Future change (US5) | Breaks 5.2.0 customers? | Why |
|---------------------|-------------------------|-----|
| Adding `SnapshotUpcaster` and related types | No | Pure additions; no existing references |
| Adding `registerSnapshotUpcaster(...)` to existing configurer | No | New method on existing class is binary-compatible |
| Adding `SnapshotType` record | No | New type, no existing references |
| Optional later "Path Y collapse" (shared marker `BaseUpcaster` root) | No | Adding a parent interface with no methods is binary-compatible |
| Event upcaster API shipped in 5.2.0 | Untouched | No changes to `Upcaster`, `EventUpcaster`, builders, or registration needed |

The only adjacent change that COULD be breaking is evolving the `Snapshot` record itself
when US5 lands (e.g., to carry `SnapshotType`). That is decoupled from the upcaster API
and is a US5 scope decision -- 5.2.0 customers using upcasters never touch `Snapshot`
directly.

**Why parallel, not a shared root**:

- Constitution Gate II: abstractions need at least one concrete scenario. A shared root
  (e.g., `Upcaster<M extends HasMessageType>`) has only one concrete user (events) in
  5.2.0 -- premature.
- `Snapshot` is NOT a `Message<?>`. Forcing them under a shared root requires either
  putting `MessageType` on `Snapshot` (semantically wrong) or specifying a new shared
  identity contract before the snapshot side is specced -- under-specified.
- N:1, splits, and drops do not apply to snapshots. A shared root that exposes all four
  cardinalities would over-promise on the snapshot side; sealing `SnapshotUpcaster` to
  1:1 only is a domain-level guarantee that a shared root would weaken.

A code sketch of how a shared root could be added LATER non-breakingly is in spec.md
(Snapshot Upcasting `[Deferred]` section). Confirms the 5.2.0 decision is consistent
with eventual unification when US5 lands.

**Hook point for US5**: `StoreBackedSnapshotter.load()` version-mismatch branch -- today
returns `null` to force full event replay; US5 plugs `SnapshotUpcasterChain.apply(...)`
there.

**Alternative rejected**: pre-ship a stub `SnapshotUpcaster` interface to "future-proof"
-- introduces unused public types and a refactoring tax (Gate II).

---

## R-9. Matching: how the chain selects an upcaster

**Decision**: `Map<MessageType, List<EventUpcaster>>` keyed by `from()`. For each
incoming event, look up by `MessageType` and apply matches in registration order.
Non-matching events are not deserialised (FR-012). Map built once at `.build()`.

**Rationale**: O(1) lookup on the hot path. `MessageType` is a record with structural
equality, so map keys work out of the box.

**Alternatives**: linear scan (quadratic in chain length); index by `QualifiedName` only
with secondary version filtering inside the upcaster (the framework owns matching per
FR-005).

---

## R-10. Observability (FR-014)

**Decision**:
- **INFO at `.build()`**: one log line summarising the chain ("Built upcaster chain with
  N transformations: [from->to, ...]").
- **DEBUG per applied transformation**: inside the chain's `apply` loop, only when a
  match fires.

**Rationale**: INFO once is the lowest-noise way to confirm wiring (US8 scenario 1).
DEBUG matches AF4's style and stays quiet in production (US8 scenarios 2, 3). No public
API addition — SLF4J is already transitive.

**Alternative**: metric instead of log — deferred; metrics module is an extension.

---

## R-11. Decision-tree doc (SC-007)

**Decision**: one AsciiDoc page in `docs/reference-guide/` with side-by-side examples
(same scenario, two solutions) cross-referencing the `EventConverter` page both ways.

**Rationale**: SC-007 is a documentation criterion; side-by-side examples beat prose for
"which tool do I reach for".

---

## R-12. AF4 migration guide (SC-006)

**Decision**: ship "Migrating AF4 upcasters to AF5" page under `docs/reference-guide/modules/migration/`
with three worked examples (rename, structural, split). Each shows AF4 code, AF5
equivalent, and the conceptual mapping (`IntermediateEventRepresentation` -> typed
payload, `@Order` -> registration order, context-aware -> unsupported / alternative).

**Rationale**: Clarification 2026-05-19, Q3 — migration guide only, no compatibility
shim (would reintroduce `IntermediateEventRepresentation`). SC-006 is testable.

**Alternative**: OpenRewrite recipes — deferred to a follow-on issue if community demand
surfaces.

---

## Summary

| # | Topic | Decision |
|---|---|---|
| R-1 | Convergence point (FR-013) | Decorate `MessageStream` returned by `EventStorageEngine.source()` / `stream()` |
| R-2 | Integration shape | `UpcasterChain` implements `EventStorageEngine` decorator, `flatMap`s the returned stream; installed only when non-empty; locked at `.build()`. Adds `MessageStream.flatMap(...)` + `FlatMappedMessageStream` |
| R-3 | Registration entry | `EventSourcingConfigurer.registerEventUpcaster(ComponentBuilder<EventUpcaster>)` |
| R-4 | Interface | `Upcaster<M extends Message<?>>` root; `EventUpcaster` with `Single` / `Multi` subtypes |
| R-5 | Payload access | Two entry points: declarative builder, message-level `Converter` access |
| R-6 | Chain execution | Lazy, registration-ordered; output flows to **next** upcaster |
| R-7 | Converter type | `org.axonframework.conversion.Converter` |
| R-8 | Snapshot upcasting | Deferred to US5; parallel hierarchy + distinct `SnapshotType` committed; non-breaking for 5.2.0 customers |
| R-9 | Matching | `Map<MessageType, List<EventUpcaster>>` for O(1) lookup |
| R-10 | Observability | INFO at build, DEBUG per applied transformation |
| R-11 | Decision tree | One AsciiDoc page with two-way cross-references |
| R-12 | Migration guide | AsciiDoc with three worked examples; no compatibility layer |

All Phase 0 NEEDS CLARIFICATION items resolved. Phase 1 may proceed.
