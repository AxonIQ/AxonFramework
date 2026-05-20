# Event Upcasting API -- Team Discussion Guide
**Feature**: issue #3597 | **Target**: AF 5.2.0 | **Full spec**: spec.md

> Everything in this document is a **proposal for team discussion**, not a confirmed
> decision. User stories, FRs, design choices, and scope exclusions all need explicit
> team confirmation before any are locked in. Dates marked in items below indicate when
> the proposal was written or last adapted, not when it was approved.

---

## The problem

AF5 already handles many versioning scenarios automatically. Upcasters cover the rest -- where the stored event stream itself must change.

**AF5 handles these scenarios automatically -- no upcaster needed:**

How depends on the serialization format. AF5 ships three converters out of the box:
`Jackson2Converter` (Jackson 2), `JacksonConverter` (Jackson 3 -- JSON / CBOR), and
`AvroConverter` (Avro binary). User-supplied converters (e.g. XML via JAXB or Jackson
XML, Protobuf, or any custom format) plug into the same `EventConverter` infrastructure
and follow the same principle: **handle backward / forward compatibility at the
serialization layer, not via upcasters**.

The table shows what each shipped converter does; user-supplied converters use their
own format's equivalent mechanisms (examples in the third column).

| Scenario | Jackson / CBOR (shipped) | Avro (shipped) | User-supplied (e.g. XML) |
|---|---|---|---|
| Added a new field | New field defaults to `null` | New field receives its Avro schema default | Library-specific (e.g. JAXB tolerates missing elements; new fields default to null/library default) |
| Removed a field | `@JsonIgnoreProperties(ignoreUnknown = true)` | Writer-only fields dropped automatically by schema resolution | Library-specific (e.g. JAXB ignores unknown elements by default) |
| Renamed a field | `@JsonProperty("oldName")` on the new field | `"aliases": ["oldName"]` on the reader schema field | Library-specific (e.g. JAXB `@XmlElement(name="oldName")`, Jackson XML `@JacksonXmlProperty(localName=...)`) |
| Compatible type change | Jackson coerces automatically (e.g. `int` -> `long`) | Avro schema resolution promotes compatible types automatically | Library-specific (e.g. JAXB type adapters) |
| Renamed the Java class | `@Event(name = "OldName")` -- format-independent | Same -- `MessageType` routing is format-independent | Same -- format-independent |
| Handler wants different representation | Declare `JsonNode` or concrete class | Declare `GenericRecord` or generated class | Library-specific typed representation (e.g. DOM `Document`, JAXB-bound class) |
| Switched serialization format | Reconfigure `EventConverter`; event classes unchanged | Same -- only the converter configuration changes | Same -- only the converter configuration changes |

Note on XML in particular: order of elements / attributes IS significant (unlike JSON
where field order is irrelevant). Schema migration on element ordering may require an
upcaster even when nothing else changes. Avro has its own incompatibility levels
(BACKWARD / FORWARD / FULL / NONE) summarised in the [Confluent
documentation](https://docs.confluent.io/platform/current/schema-registry/fundamentals/schema-evolution.html).

**Upcasters are needed when** the stored event stream itself must change: payload restructured, event renamed at MessageType level, one event split into many, or an event suppressed entirely.

---

## User stories

| Story | Priority | What it solves | Key FRs |
|---|---|---|---|
| US1 Structural transform | P1 | Restructure payload for all handlers (e.g. `capacity` -> `minCapacity` + `maxCapacity`) | FR-001, FR-005, FR-006, FR-010, FR-011, FR-012, FR-013, FR-017, FR-018, FR-019 |
| US2 Rename | P2 | Stored events under old name reach handlers registered for new name | FR-001, FR-002, FR-005 |
| US3 Splitting | P3 | One stored event becomes two or more independent events | FR-003, FR-007, FR-010, FR-011 |
| US4 Dropping | P4 | Suppress a stored event type entirely; tracking token still advances | FR-003, FR-010, FR-012, FR-015 |
| US5 Snapshot upcasting | Deferred | Transform old snapshots -- discard-and-replay remains the default | FR-008 |
| US6 Chaining | P2 | v1->v2 + v2->v3 registered in order compose automatically | FR-004, FR-005, FR-006, FR-007 |
| US7 Misconfiguration + runtime failures | P1 | Clear errors at registration or chain-build time (cycle detected at `.build()`); runtime failures propagate immediately | FR-004, FR-009, FR-016, FR-019 |
| US8 Startup observability | P2 | INFO log confirms chain wiring; DEBUG log traces each transformation applied | FR-014 |

---

## Proposed scope exclusions (need team confirmation)

| Scenario | Why | Future path |
|---|---|---|
| N-to-1 merge | Requires stateful context; scope inconsistent across bounded/unbounded streams | Revisit after streaming model stabilises |
| Moving data between events | Same context-scope problem as AF4 context-aware upcasters | Same |
| Snapshot upcasting | Discard-and-replay is the correct primary strategy; niche use case | Hook point in `StoreBackedSnapshotter` identified; concrete future shared-root proposal sketched in spec.md `[Deferred]` section -- non-breaking refactor when US5 lands |
| Command / query upcasting | Rolling deployment concern; different pipeline | Issue #746; API designed to support it without breaking changes |
| Annotation-based registration | Risks reintroducing AF4's ordering problem | Add on top of programmatic API once demand is proven |
| AF4 migration tooling (OpenRewrite / compatibility layer) | Compat layer would pull back `IntermediateEventRepresentation`; structural rewrites are a non-trivial up-front investment | 5.2.0 ships a manual migration guide with worked examples; OpenRewrite recipes added later if community asks |
| Stream squashing / versioning bankruptcy (built-in upcaster retirement) | Substantial feature: race conditions, projection rebuilds, tracking-token preservation; premature without evidence of accumulation pain | Manual retirement rule documented in Edge Cases; revisit when community signals real friction |

---

## Functional requirements

| FR | Category | Requirement |
|---|---|---|
| FR-001 | Registration | Declare source and target event identity + optional payload rule; payload passes through unchanged if no rule supplied |
| FR-002 | Registration | Rename is the degenerate 1:1 case -- source and target with different identity, no payload rule |
| FR-003 | Registration | Declare source identity + rule producing zero or more replacement events (split / drop) |
| FR-004 | Registration | Declaration order IS chain order; startup-only; late registration rejected with clear error |
| FR-005 | Matching | Exact match on fully qualified name + version; non-matching events pass through |
| FR-006 | Correctness | Transformations must be deterministic AND thread-safe -- same input always produces same output; no external I/O, no time/random sources, no cross-invocation mutable state; read-only in-process data (e.g. constant lookup tables) is allowed; framework may invoke concurrently from any thread |
| FR-007 | Chain | Output of each transformation feeds into the next; split replacement events continue through the remaining chain |
| FR-008 | Deferred | Snapshot upcasting out of scope; discard-and-replay fallback preserved unchanged |
| FR-009 | Validation | Five conflict classes rejected before any event is processed: duplicate `from`, self-loop, invalid semver, non-qualified name (all at `register()`); multi-step 1:1 cycle (at `.build()` lock) |
| FR-010 | Payload | Access payload as typed Java objects, in any representation the registered `Converter` exposes; shipped converters support at minimum POJO (all formats), `JsonNode`/`ObjectNode`/`Map<String,Object>` (Jackson/CBOR), `SpecificRecordBase`/`GenericRecord` (Avro); user-supplied converters (e.g. XML) provide their own typed representations; conversion is on-demand, never raw bytes |
| FR-011 | Envelope + metadata | Envelope (entity id, tracking token, sequence number) preserved unchanged. Metadata preserved by default (payload-only entry point) but modifiable via message-level entry point. Split (1:N) outputs inherit input metadata by default; rule may override per-output |
| FR-012 | Lazy evaluation | Non-matching events pass through without being deserialized |
| FR-013 | Consistency | Chain applies identically in all three reading contexts: event-sourced entity load, DCB read, tracking processor |
| FR-014 | Observability | INFO log at startup listing the chain; DEBUG log per transformation applied |
| FR-015 | Dropping | Tracking token advances past dropped events; processor does not reprocess them on restart |
| FR-016 | Error handling | Runtime transformation failure propagates immediately -- no silent skip or log-and-continue |
| FR-017 | Versioning | Events stored without a version are treated as version `0.0.1` |
| FR-018 | Testability | Transformation MUST be unit-testable in isolation (no event store / framework bootstrapping); only a `Converter` instance allowed as dependency (advanced entry point only) |
| FR-019 | Correctness | For 1:1, framework MUST verify output identity matches declared `to`; mismatch propagated under FR-016. Does not apply to 1:N / 1:0 splits. For rename-factory upcasters (`EventUpcaster.rename(from, to)`), the check runs and passes trivially by construction (the factory rebinds the event to `to` before the check). |

---

## What needs team input

| # | Question |
|---|---|
| 0 | **#746 alignment -- universal Message API (current proposal, team confirmation needed)**: a non-sealed `Upcaster<M extends Message<?>>` root interface in `org.axonframework.messaging.upcasting`, carrying only `MessageType from()`. The event-specific sealed hierarchy (`EventUpcaster`, `SingleEventUpcaster`, `MultiEventUpcaster`) extends it. Public 5.2.0 API surface is event-only; #746 can add command/query subtypes later without breaking the event API. Envelope asymmetry (tracking token, sequence number, entity id) stays on `EventUpcaster`; the generic root holds only the cross-cutting `from()` contract. **Does the team agree with this shape, or should the generic root carry more (or less)?** Steven's 2026-05-19 suggestion that the root could also span snapshots is captured in questions 6 + 7 below. |
| 1 | **Two declaration patterns** -- 1:1 uses source + target; split/drop uses source + replacement rule. Does this feel right, or should split/drop also declare a target set? |
| 2 | **Startup-only registration** -- chain locked once processing begins. Is there a valid use case for runtime registration we have not considered? |
| 3 | **Fully qualified name requirement** -- forces namespace inclusion. Is this a breaking ergonomics concern for developers coming from AF4? |
| 4 | **Deferred scope** -- snapshot upcasting, command/query upcasting all deferred. Is any of these P1 for 5.2.0 given the team's current projects? |
| 5 | **Simpler than AF4** -- do we have an agreed baseline for what AF4 required, or do we need a comparison example? |
| 6 | **Snapshot direction for 5.2.0** (spec.md `[Deferred]` section) -- 5.2.0 ships parallel hierarchies: `Upcaster<M extends Message<?>>` stays scoped to messages; snapshot upcasting (deferred) gets its own root with distinct `SnapshotType`. The spec now contains a side-by-side comparison (shared root vs parallel) and a non-breaking guarantee table for 5.2.0 customers. **Does the team agree** with (a) the parallel-hierarchies choice for 5.2.0, (b) the comparison framing, (c) the customer reassurance ("compiling and running unchanged, no migration, no rewrites")? |
| 7 | **Future shared root proposal -- concrete 5-step code sketch** (spec.md `[Deferred]` section) -- the proposal: introduce `TypeIdentifier` + `HasTypeIdentifier`, retrofit `Message`/`MessageType` via covariant return, add `Snapshot`/`SnapshotType`, relax the upcaster bound to `Upcaster<T extends HasTypeIdentifier>`. Each step is binary-compatible. Marked as "starting point, not committed -- shape may evolve when snapshots are specced." **Does the proposed shape look right**? Any concerns about publishing a concrete sketch now vs leaving the door open without specifying a shape? |
| 8 | **FR-005 -- predicate / matcher registration** (open). Spec currently uses exact `(name, version)` match. A predicate-based form (e.g. "any version of EventX") would be more expressive but makes FR-009 cycle/overlap detection harder -- with arbitrary lambdas, overlap can't always be detected statically. Pick an option: (A) keep exact match for 5.2.0, add predicates later if demand surfaces; (B) predicates only for 1:0 drop (no `to`, no chaining, no cycle concern); (C) allow predicates broadly with runtime enforcement -- framework throws if an event matches more than one transformation path; (D) explicit naming -- upcasters register with unique names, runtime resolution uses names to disambiguate; (E) other. |
