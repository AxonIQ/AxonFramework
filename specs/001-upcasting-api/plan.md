# Implementation Plan: Event Upcasting API

**Branch**: `enhancement/3597/revise-upcaster-api` | **Date**: 2026-05-19 | **Spec**: [spec.md](spec.md)

**Companion artifacts**: [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md), [DISCUSSION.md](DISCUSSION.md)
(item #0, #746 alignment, decided 2026-05-19).

## Summary

Add an event upcasting API to AF 5.2.0 (issue #3597) for the cases payload conversion at
handling time cannot solve: structural payload transforms, renames, 1:N splits, 1:0 drops.
AF5's `Message` model replaces AF4's `IntermediateEventRepresentation` — transformations
operate on typed Java payloads, with a `Converter` available for advanced cases.

**Technical approach** (full rationale in [research.md](research.md)):

- **Single convergence point**: wrap the `EventConverter`-backed materialisation inside
  `EventStorageEngine`. All three reading contexts (entity loads, DCB reads, tracking
  processors) flow through it — FR-013 falls out for free.
- **Public surface**: `EventUpcaster` with two subtypes (`SingleEventUpcaster`,
  `MultiEventUpcaster`) and a small builder for the declarative entry point. The generic
  root `Upcaster<M extends Message<?>>` lives in `messaging/upcasting/` so #746 can extend
  without breaking the event API.
- **Registration**: `EventSourcingConfigurer.registerEventUpcaster(...)` accumulating into
  an ordered registry, locked at `.build()`. Mirrors `DispatchInterceptorRegistry`.
- **Failure handling**: registration-time conflict detection (FR-009), output identity
  validation for 1:1 (FR-019), runtime failure propagation (FR-016) — no silent recovery.
- **Snapshot upcasting (US5)**: deferred. Hook point in `StoreBackedSnapshotter` is
  documented for a future release. See design decision below.

### Design Decision: generic root bound and snapshot upcasting (2026-05-19)

**Context**: team meeting (Jakob Hatzl, Laura Devriendt, Steven van Beelen) confirmed that
`Snapshot` is NOT a `Message<?>` subtype — envelope fields (tracking token, entity id)
have no clear snapshot analogue. Steven proposed a single generic root covering events,
commands, queries, and snapshots.

**Options evaluated** for `Upcaster<M>`:

- **A. Remove the bound (`Upcaster<M>`)**: `Upcaster<String>` becomes valid. **Rejected**
  on type safety.
- **D. Shared minimal interface (`Upcaster<M extends HasMessageType>`)**: introduce a new
  `HasMessageType` covering both `Message<?>` and `Snapshot`. Honors Steven's vision but
  bakes a new public-API interface for zero concrete 5.2.0 callers; the right identity
  contract for `Snapshot.type()` is not yet known. Violates Constitution Gate II
  (abstractions need at least one concrete scenario). **Rejected for 5.2.0.**
- **C. Parallel hierarchies (chosen)**: keep `Upcaster<M extends Message<?>>` scoped to
  messages (events now; commands/queries via #746). Snapshot upcasting, when specced,
  gets its own hierarchy in `eventsourcing/`. If unification later proves warranted, it
  can be introduced with a real contract for `Snapshot.type()`.

**Decision**: Option C. No changes to 5.2.0 deliverables.

## Technical Context

**Language**: Java 21 (sealed types, records, switch patterns where useful).

**AF5 dependencies (existing types only)**:
- Messaging: `Message`, `MessageType`, `QualifiedName`, `EventMessage`, `MessageStream`,
  `EventConverter`, `EventStorageEngine`, `EventSourcingConfigurer`, `ComponentRegistry`.
- Conversion: `org.axonframework.conversion.Converter` (advanced entry-point parameter),
  `ChainedConverter` (internal — bridges `byte[]` to typed objects in the declarative
  path), the registered `ContentTypeConverter` chain (Jackson, CBOR, Avro, or
  user-supplied converters such as XML).
- Logging: SLF4J (already transitive). **No new external dependencies.**

**Storage**: integrates with existing `EventStorageEngine` implementations
(`AggregateBasedJpaEventStorageEngine`, `InMemoryEventStorageEngine`). No schema changes.

**Testing**: JUnit 5 + AssertJ + Awaitility per project CLAUDE.md. FR-018 forbids mocks
for upcaster unit tests; integration tests use real storage engines. Avro and Jackson 2 /
Jackson 3 (CBOR) variants reuse `conversion/` module test infrastructure.

**Performance**:
- Non-matching events MUST NOT be deserialised (FR-012) — O(1) skip via `MessageType` index.
- Matching events: O(chain length matching the same `MessageType`).
- Chain built once at startup, immutable; no per-event allocation of chain metadata.

**Constraints**:
- Thread-safety: concurrent invocation from any thread (FR-006); SC-010 verifies N >= 8.
- No backwards compatibility with prior AF5 code (no upcaster API today). AF4 migration is
  a manual guide only — no compatibility shim (Clarification 2026-05-19, Q3).
- Public API stability: `EventUpcaster` MUST allow #746 to add `CommandUpcaster` /
  `QueryUpcaster` without breaking changes.

**Scale**: real-world chain length expected under 50 (Gregory Young's retirement
guidance). All three shipped converters (`Jackson2Converter`, `JacksonConverter`,
`AvroConverter`) must work end-to-end (covered by spec Part A).

## Constitution Check

All gates derived from `.specify/memory/constitution.md` v1.1.1. Initial check: **PASS**;
post-design re-check: **PASS** (no new abstractions added during Phase 1).

| Gate | Verdict | Evidence |
|------|---------|----------|
| I — Spec-First (NON-NEGOTIABLE) | PASS | Every contract type maps to US1-US8 in contracts/upcaster-api.md; no prototype code. |
| II — Simpler Than AF4 | PASS | No `IntermediateEventRepresentation` analogue. Every abstraction justified: `SingleEventUpcaster` (US1+US2), `MultiEventUpcaster` (US3+US4), builder (SC-001). |
| III — Message-Based API | PASS | `Upcaster<M extends Message<?>>` in `messaging/upcasting/` allows #746 to extend without modifying the event hierarchy. `MessageType`/payload/metadata reachable via `Message` accessors. Root has no `apply(...)` — typed subtypes own cardinality. |
| IV — Registration Ordering | PASS | FR-004: registration order = chain order. No `@Order`, no annotation discovery. Spring support out of scope; if added later, explicit `register*` calls still win. |
| V — Converter vs Upcaster separation | PASS | `EventConverter` unchanged. Upcaster receives `Converter` as a dependency, never overrides format conversion. |
| VI — Immutability First | PASS | Transformations apply at read time only. `EventStorageEngine` write path untouched. |
| VII — Deterministic Transformations | PASS | FR-006: deterministic + thread-safe; read-only constant lookups allowed; no `ProcessingContext` on the upcaster signature. |
| VIII — Single Responsibility per Upcaster | PASS | FR-001/FR-003 split 1:1 and 1:N into distinct registration patterns; contracts forbid bundling. |
| IX — Prefer Chain over Direct | PASS | FR-007 + US6 design chaining natively; no v1->v3 shortcut. Migration guide (SC-006) documents the rule. |
| X — Type-Based Versioning Forbidden | PASS | FR-009 rejects `from == to` at registration; spec Part C documents the "new event type" rule. |
| Scope boundaries for 5.2.0 | PASS | No context-aware API (`apply(EventMessage, Converter)` has no cross-event state slot). No command/query/snapshot/test-fixture/AF4-tooling/Copy-and-Replace/negotiation work. |
| AF5 anchoring types | PASS | Every new type consumes only listed AF5 types. `ProcessingContext` intentionally NOT exposed (see Gate VII). |

## Project Structure

### Source Code

```text
messaging/
└── src/main/java/org/axonframework/messaging/
    └── upcasting/                                   # NEW: messaging-level generic root
        ├── Upcaster.java                            # Upcaster<M extends Message<?>>; future-proof for #746
        └── package-info.java

eventsourcing/
└── src/
    ├── main/java/org/axonframework/eventsourcing/
    │   ├── upcasting/                               # NEW: event-facing API surface
    │   │   ├── EventUpcaster.java                   # extends Upcaster<EventMessage>; sealed root for events
    │   │   ├── SingleEventUpcaster.java             # 1:1 (FR-001, FR-002)
    │   │   ├── MultiEventUpcaster.java              # 1:N / 1:0 (FR-003)
    │   │   ├── UpcasterBuilder.java                 # Declarative entry point (FR-010 simple)
    │   │   ├── UpcasterChain.java                   # Ordered chain (FR-004, FR-005, FR-007)
    │   │   ├── UpcasterRegistry.java                # Configurer-facing registry (FR-009)
    │   │   ├── UpcasterRegistrationException.java
    │   │   ├── UpcasterApplicationException.java
    │   │   └── package-info.java
    │   ├── configuration/
    │   │   └── EventSourcingConfigurer.java         # MODIFIED: registerEventUpcaster(...)
    │   └── eventstore/
    │       └── AggregateBasedJpaEventStorageEngine.java  # MODIFIED: wrap materialisation
    │
    └── test/java/org/axonframework/eventsourcing/
        ├── upcasting/
        │   ├── SingleEventUpcasterTest.java         # FR-001, FR-002, FR-019
        │   ├── MultiEventUpcasterTest.java          # FR-003, FR-007
        │   ├── UpcasterChainTest.java               # FR-004/005/006/007, US6; FR-009 cycle at lock() (US7 sc. 5)
        │   ├── UpcasterRegistryTest.java            # FR-009 first four classes at register()
        │   ├── UpcasterBuilderTest.java             # FR-010 declarative
        │   └── UpcasterConcurrencyTest.java         # FR-006 thread-safety, SC-010 (N >= 8)
        └── upcasting/integration/
            ├── EventSourcedEntityUpcastingIT.java   # FR-013 context (a)
            ├── DcbReadUpcastingIT.java              # FR-013 context (b)
            └── TrackingProcessorUpcastingIT.java    # FR-013 context (c)

# Reference-guide additions. The single `event-versioning.adoc` is converted to a section
# directory; its content migrates to `index.adoc` and subpages are added.
docs/reference-guide/modules/events/pages/
├── event-versioning.adoc                            # REMOVED: content moves to directory below
└── event-versioning/
    ├── index.adoc                                   # NEW: section landing
    ├── decision-tree.adoc                           # NEW: Converter vs Upcaster (SC-007)
    ├── structural-transformation.adoc               # NEW: US1
    ├── renaming-events.adoc                         # NEW: US2
    ├── splitting-events.adoc                        # NEW: US3
    ├── dropping-events.adoc                         # NEW: US4
    └── chaining-versions.adoc                       # NEW: US6

docs/reference-guide/modules/migration/pages/
└── migrating-upcasters-from-af4.adoc                # NEW: SC-006 migration guide

examples/university-demo/src/main/java/.../upcasting/  # NEW: SC-005 demo
├── CourseCreatedV1ToV2Upcaster.java                 # US1
├── CourseOpenedToCourseCreatedRename.java           # US2
├── StudentEnrolledAndCourseUpdatedSplitter.java     # US3
├── SystemHeartbeatDropper.java                      # US4
└── UpcastingDemoConfiguration.java                  # wiring; stacking US2 + US1 implicitly demonstrates US6 chaining (CourseOpened@1.0.0 -> CourseCreated@1.0.0 -> CourseCreated@2.0.0)
```

**Structure rationale**: chain hooks into `EventStorageEngine` (lives in `eventsourcing/`),
so the bulk lives there. Only the generic root sits in `messaging/upcasting/` to allow
#746 to extend it later. Tests co-located per project CLAUDE.md; docs follow Antora
structure; example lives in `university-demo`.

## Complexity Tracking

Constitution Check passed with zero violations. No entries required.

## Phase status

- [x] Phase 0: research.md generated; all NEEDS CLARIFICATION resolved.
- [x] Phase 1: data-model.md, contracts/, quickstart.md generated; agent context updated.
- [x] Constitution re-check post-design: PASS.

Phase 2 (tasks.md) is produced by `/speckit-tasks`.
