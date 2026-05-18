# Event Upcasting API -- Team Discussion Guide
**Feature**: issue #3597 | **Target**: AF 5.2.0 | **Full spec**: [spec.md](spec.md)

---

## The problem (2 min read)

**Upcasters are needed when none of the scenarios below in the table applies** – i.e., when the stored event stream itself
must change (payload restructured, event renamed at the MessageType level, one event split into
many, or an event suppressed entirely).

AF4 had upcasters but they were painful: raw bytes/DOM manipulation, unpredictable Spring Bean
ordering, null revision bugs, no testing support. AF5 has no upcaster API at all today.

**AF5 already handles these automatically – no upcaster needed:**

| Scenario | Mechanism |
|---|---|
| Added a new field | New field defaults to `null`; no code change needed |
| Removed a field | Old stored field ignored via `@JsonIgnoreProperties` |
| Renamed a field | Map old name to new field via `@JsonProperty("oldName")` |
| Compatible type change | Jackson coerces automatically (e.g. `int` -> `long`) |
| Renamed the Java class | Annotate new class with `@Event(name = "OldName")`; payload unchanged |
| Handler wants a different representation | Same stored event; handler declares `JsonNode` or concrete class |
| Switched serialization format | Reconfigure `EventConverter`; event classes require no changes |

---

## User stories and functional requirements

### US1 -- Structural Payload Transformation `P1`

A developer needs to change the payload of a stored event so every handler receives the new
structure without each implementing its own workaround.

> `CourseCreated` had `capacity: 30`. It now needs `minCapacity` and `maxCapacity`. Register one
> transformation; every handler and every aggregate replay sees the new fields automatically.

| FR | Requirement |
|---|---|
| FR-001 | Register a 1:1 transformation using `from -> to` + optional payload function |
| FR-005 | Exact match on fully qualified name + version; non-matching events pass through |
| FR-006 | Transformations must be pure -- same input always produces same output |
| FR-011 | Access payload as typed objects (POJO, JsonNode, ObjectNode) -- never raw bytes |
| FR-012 | Envelope (aggregate id, tracking token, sequence number) and metadata preserved unchanged |
| FR-013 | Non-matching events pass through without being deserialized (lazy, on-demand) |
| FR-014 | Chain applies consistently in all three reading contexts: aggregate load, DCB read, tracking processor |
| FR-018 | Events with no stored version are treated as version `0.0.1` |

---

### US2 -- Event Identity Change / Rename `P2`

A developer renamed an event type and needs stored events under the old name to reach handlers
registered for the new name. No payload change -- only the identity changes.

> `CourseOpened` v1.0.0 -> `CourseCreated` v1.0.0. Declare the mapping once; no function needed.

| FR | Requirement |
|---|---|
| FR-001 | Register `from -> to` without a payload function; payload passes through unchanged |
| FR-002 | Rename is the degenerate 1:1 case -- same API, no special rename endpoint needed |
| FR-005 | Exact name + version matching ensures only the targeted events are renamed |

---

### US3 -- Event Splitting `P3`

A developer needs one stored event to become two (or more) independent events when read.

> `StudentEnrolledAndCourseUpdated` v1.0.0 -> `StudentEnrolled` + `CourseCapacityUpdated` in that order.

| FR | Requirement |
|---|---|
| FR-003 | Register using `from` only + function returning a list of complete output messages |
| FR-007 | Each output event from the split flows through the remaining chain independently |
| FR-011 | Access original payload as typed objects to derive the split outputs |
| FR-012 | All output events carry the same envelope and metadata as the original stored event |

---

### US4 -- Event Dropping `P4`

A developer needs a stored event type to be silently suppressed -- zero events out.

> `SystemHeartbeat` events were accidentally stored. No handler should ever see them.

| FR | Requirement |
|---|---|
| FR-003 | Register using `from` + function returning an empty list |
| FR-011 | Access payload if needed to decide whether to suppress |
| FR-013 | Non-dropped events in the same stream pass through without deserialization |
| FR-016 | Tracking token advances past dropped events -- processor does not reprocess them on restart |

---

### US5 -- Snapshot Upcasting `Deferred`

When aggregate state schema changes, stored snapshots at the old schema cannot be used.
Currently AF5 discards the snapshot and replays from events. Snapshot upcasting is deferred.

| FR | Requirement |
|---|---|
| FR-008 | Snapshot upcasting is out of scope for 5.2.0 -- no regression in existing behavior |
| FR-009 | Discard-and-replay fallback remains the default and correct primary strategy |

---

### US6 -- Chaining Across Multiple Versions `P2`

A developer registers one transformation per version hop. The framework composes them so a v1
event passes through v1->v2 then v2->v3, arriving at the handler as v3.

> Registering v1->v2 and v2->v3 in order: a stored v1 event arrives at handlers as v3 automatically.

| FR | Requirement |
|---|---|
| FR-004 | Registration order is chain order -- framework never reorders; startup-only |
| FR-005 | Version-exact matching ensures each transformation only fires for its target version |
| FR-006 | Pure transformations guarantee the same chain always produces the same output |
| FR-007 | Output of each transformation feeds into the next as input |

---

### US7 -- Misconfiguration Feedback at Registration Time `P1`

A developer who registers conflicting or invalid transformations gets a clear error at startup,
before any event is processed.

> Duplicate `from`, self-loop (`from == to`), bad version string (`"1.0"`), unqualified name
> (`"CourseCreated"` instead of `"com.example.CourseCreated"`) -- all caught at registration time.

| FR | Requirement |
|---|---|
| FR-004 | Late registration (after processing started) rejected immediately with clear error |
| FR-010 | Duplicate `from`, self-loop, invalid semver, and non-qualified name rejected at registration |
| FR-017 | If a transformation throws at runtime, propagate immediately -- no silent skip or log-and-continue |

---

### US8 -- Startup Observability `P2`

A developer can confirm the transformation chain was wired correctly from log output, without
enabling debug logging for the whole system.

| FR | Requirement |
|---|---|
| FR-015 | INFO log at startup listing registered transformations; DEBUG log per transformation applied |

---

## Key design decisions (the "why")

**Two registration patterns, not one**
- 1:1 (transform + rename): `from -> to` + optional payload function
- 1:N / 1:0 (split + drop): `from` only + function returning a list of output messages

**Registration order = chain order**
No `@Order` annotations, no Spring Bean discovery. You call the API in order; that is the chain.
Registration is startup-only -- the chain is immutable once event processing begins.

**Transformations see typed Java objects, not bytes**
`payloadAs(MyEvent.class)` converts on demand. Lazy: non-matching events are never deserialized.
Keeps the boundary clean: EventConverter handles format (bytes <-> structured); transformations
handle schema/identity.

**Fully qualified event names required**
`com.example.CourseCreated`, not `CourseCreated`. Prevents silent non-matches when two modules
have events with the same local name.

**Semver enforced at registration**
`"1.0.0"` not `"1.0"`. Invalid strings are rejected immediately at registration time.

---

## What is explicitly out of scope

| Scenario | Why | Future path |
|---|---|---|
| N-to-1 merge | Requires stateful context; scope is inconsistent across bounded/unbounded streams | Revisit after streaming model stabilises |
| Moving data between events | Same context-scope problem as AF4 context-aware upcasters | Same |
| Snapshot upcasting | Discard-and-replay is correct primary strategy; niche use case | Hook point identified in `StoreBackedSnapshotter` |
| Command / query upcasting | Rolling deployment concern; different pipeline questions | Issue #746, API designed to support it without breaking changes |
| Annotation-based registration | Risks reintroducing AF4's ordering problem via a different path | Add on top of programmatic API once demand is proven |
