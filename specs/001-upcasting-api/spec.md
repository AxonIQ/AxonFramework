# Feature Specification: Event Upcasting API

**Feature Branch**: `feature/3597/upcasting-api`

**Created**: 2026-05-18

**Status**: Draft

**Input**: User description: "Upcasting API for Axon Framework 5.2.0 (issue #3597)"

---

## Background: Why Events Need Versioning

In an event-sourced system, every change in the application is recorded as an event and stored permanently. Events are never changed after they are written — they are immutable facts about what happened.

This immutability is a strength (complete audit trail, ability to replay history), but it creates a challenge when your application evolves: old events stored in the past must still work with new code.

Axon Framework 5 addresses this with two complementary mechanisms that run in order when a message is read:

1. A **message transformer** (called upcasters in the past) changes the *structure* of a message — splitting one event into two, renaming a message type, or reshaping a payload. It runs first, so every handler observes the same, up-to-date shape. A transformer is a decorator around the converter, intercepting messages as they flow through. Transformers are most commonly applied to events (where they have historically been called *upcasters*), but the same mechanism works for snapshots, commands, and queries.

2. A **message converter** changes the *representation* of the payload — producing the concrete type a handler declared. It runs after the transformer, on the already-restructured message. The converter handles many common versioning scenarios automatically: for example, when you add a new optional field to an event class, old stored events simply receive a default value for that field when they are read. You do not need to do anything.

You reach for a transformer only when the message itself must be restructured before any handler sees it — scenarios the converter cannot handle on its own.


## User Scenarios & Testing

This section is split into three parts:

- **Part A** – scenarios the converter handles on its own. No transformer is needed. 
- **Part B** – scenarios that require a transformer. These are the stories that drive the new API.
- **Part C** – scenarios that are out of scope or where a transformer is the wrong tool entirely.
  Each entry explains what to do instead and why a transformer does not solve the problem.

---

### Part A: What the converter handles (no transformer needed)

The converter absorbs most schema evolution on its own through two mechanisms: **payload
conversion at handling time** (each handler declares its preferred Java type and the converter
produces it from the stored payload) and **converter-level compatibility configuration** (Jackson
annotations, Avro reader/writer schemas, JAXB bindings, defaults, ignore-unknown settings -- all
on the converter, never on the upcaster chain).

So **whether a change requires a transformer depends as much on how your converter is configured
as on the change itself.** A loose configuration absorbs more; a strict one rejects more.

> **The rule:** configure your converter first; reach for a transformer only when the change
> cannot be expressed there. The decision below covers every common case.

---

#### Do I need a transformer?

**Does the stored message need to be restructured before any handler sees it?**

##### No -- the converter handles it natively

*Field changes:*

- **Add OPTIONAL field** -- converter supplies default for missing fields (Jackson `null`, Avro schema default, JAXB library default).
- **Remove a field** (optional or required) – converter ignores unknown fields (Jackson `@JsonIgnoreProperties(ignoreUnknown=true)`, Avro writer/reader schema resolution, JAXB tolerates unknown elements).
- **Change required → optional** (relax) -- schema / annotation update.
- **Rename a field** -- old-name → new-name mapping (Jackson `@JsonProperty("oldName")`, Avro `"aliases": ["oldName"]`, JAXB `@XmlElement(name="oldName")`).
- **Compatible type change** (e.g., `int -> long`) -- converter coerces (Jackson numeric coercion, Avro schema promotion).

*Class / format / handler changes:*

- **Rename Java class only** - `@Event(name = "OldName")` preserves `MessageType` routing; payload unchanged.
- **Switch serialization format** - reconfigure `EventConverter`; event classes unchanged.
- **Handler wants different representation** – each handler declares its preferred type (`JsonNode`, `GenericRecord`, `Document`, concrete class).

##### Yes -- transformer needed

*Payload can't be bridged at the converter level:*

- **Add REQUIRED field, no usable default** -> `SingleEventUpcaster` (populate from existing payload data).
- **Change optional -> required, no usable default** -> `SingleEventUpcaster`.
- **Rename triggers payload restructure beyond a simple alias** -> `SingleEventUpcaster`.
- **Incompatible type change** (e.g., `String -> int` requiring a parse step) -> `SingleEventUpcaster`.
- **Payload restructure** (one field → 2; combining fields; complex shape) -> `SingleEventUpcaster`.
- **Strict converter config rejects an otherwise-bridgeable difference** (`FAIL_ON_UNKNOWN_PROPERTIES = true`, Avro `NONE`, JAXB without an alias) -> relax the config, or write a `SingleEventUpcaster`.

*Identity / cardinality changes:*

- **Event identity changed (name or version), payload unchanged** -> `EventUpcaster.rename(from, to)`.
- **Event identity changed, payload also changes** -> `SingleEventUpcaster`.
- **One event becomes multiple events** -> `MultiEventUpcaster`.
- **Drop event entirely** -> `MultiEventUpcaster` returning an empty list.

---

#### Tests proving the No-branch leaves

Each NO-branch leaf is a `@Nested` class of the same name in [`PayloadConversionCapabilityTest`](../../examples/university-demo/src/test/java/org/axonframework/examples/demo/university/faculty/PayloadConversionCapabilityTest.java) (Jackson 2, full AF5 pipeline; also the sole home of `RenamedJavaClass`, which is format-independent), [`CborPayloadEvolutionCapabilityTest`](../../conversion/src/test/java/org/axonframework/conversion/jackson/CborPayloadEvolutionCapabilityTest.java) (Jackson 3 / CBOR), and [`AvroPayloadEvolutionCapabilityTest`](../../conversion/src/test/java/org/axonframework/conversion/avro/AvroPayloadEvolutionCapabilityTest.java) (Avro). "Change required -> optional" has no separate test -- it is a converter config change with no framework behaviour to assert.

---

### Part B: When Transformations Are Needed

The scenarios below cannot be handled by payload conversion. They require registering a transformation because
the stored event stream itself must change. Each story is a distinct, independently testable use case.

---

#### User Story 1 - Structural Payload Transformation (Priority: P1)

**Plain-English explanation**: Imagine you designed a `CourseCreated` event with a single `capacity`
field (e.g., `capacity: 30`). Later you realize you need both a minimum and a maximum capacity. You
change the event class to use `minCapacity` and `maxCapacity`. But thousands of old events are already
stored with just `capacity`. Every part of your application that reads those events – **projections**
(components that listen to events and build read models, such as a database view of all courses) and
**event-sourced entities** (the domain objects that rebuild their state by replaying their own event history) --
needs to receive the new structure. Without upcasting, you would have to add conversion logic to
every single handler. With a transformation, you write the transformation once and all handlers receive
the new structure automatically.

**Why this priority**: This is the most common reason a developer needs a transformation. Every other
story in this spec builds on the same foundation. It is the minimum viable case.

**Independent Test**: Register a single transformation that converts `CourseCreated` v1.0.0 (single
`capacity` field) to `CourseCreated` v2.0.0 (with `minCapacity` and `maxCapacity`). Replay all
events and verify that every handler receives the new structure and no handler ever sees the old
`capacity` field.

**Acceptance Scenarios**:

1. **Given** a stored `CourseCreated` event at version 1.0.0 with a single `capacity` field,
   **When** an event handler processes it after the transformation is registered,
   **Then** the handler receives a `CourseCreated` event at version 2.0.0 with `minCapacity` and
   `maxCapacity` derived from the original `capacity`, and the old `capacity` field is absent.

2. **Given** multiple handlers registered for the same event,
   **When** the event is read from storage,
   **Then** all handlers receive the transformed payload – no handler needs to implement its own
   conversion.

3. **Given** an event that does not match the transformation's target name and version,
   **When** it is read from storage,
   **Then** it passes through the chain unchanged.

4. **Given** the same stored `CourseCreated` v1.0.0 event and the same registered transformation,
   **When** the event is read in three different contexts – (a) loading an event-sourced entity,
   (b) a DCB read enforcing command-side consistency, and (c) a tracking processor building a
   projection --
   **Then** all three contexts receive the identical transformed `CourseCreated` v2.0.0 payload.
   The reading context MUST NOT affect the transformation outcome.

5. **Given** a stored `CourseCreated` event that carries no version (written before versioning was
   introduced to the system),
   **When** a transformation registered for `com.example.CourseCreated` at version `0.0.1` is active,
   **Then** the transformation applies to that event – the framework treats the absence of a version
   as version `0.0.1`, the AF5 default.

6. **Given** a single registered `CourseCreated` v1.0.0 → v2.0.0 transformation and the same stored
   event read simultaneously by multiple threads (e.g. two tracking processors and an event-sourced
   entity load running in parallel),
   **When** the framework invokes the transformation function concurrently from those threads,
   **Then** every invocation produces an identical v2.0.0 payload, and the framework MUST NOT
   require external synchronization from the transformation author. The transformation is treated as
   a static function -- the framework does not serialize invocations per stream, per processor, or
   per entity.

---

#### User Story 2 - Event Identity Change / Rename (Priority: P2)

**Plain-English explanation**: Imagine you named an event `CourseOpened` during early development, but
after talking to domain experts you realize the correct business term is `CourseCreated`. You rename
the class in your code. But thousands of events are already stored under the old name `CourseOpened`.
Axon identifies events by their name, so a handler registered for `CourseCreated` will not receive
events stored as `CourseOpened` – unless you declare the mapping.

Since the payload (the data inside the event) has not changed – only the name changed – you should
not need to write a transformation function. You declare the mapping in one place using the same
`from → to` registration as any other transformation, simply omitting the payload function:
"events stored as `CourseOpened` v1.0.0 are the same thing as `CourseCreated` v1.0.0."
The framework passes the payload through unchanged. No separate rename API is needed.

**Why this priority**: Event renames are common during domain modeling refinements. They should
require the least possible effort since no data actually changes – only the label changes.

**Independent Test**: Declare a rename from `CourseOpened` v1.0.0 to `CourseCreated` v1.0.0. Replay
events and verify that a handler registered for `CourseCreated` receives the renamed events with the
payload intact.

**Acceptance Scenarios**:

1. **Given** a stored event named `CourseOpened` at version 1.0.0,
   **When** a handler registered for `CourseCreated` v1.0.0 processes the event stream,
   **Then** the handler receives the event with the original payload unchanged.

2. **Given** a rename from `CourseOpened` to `CourseCreated` is declared,
   **When** any handler registered for the old name `CourseOpened` is evaluated,
   **Then** it does not receive the renamed event (the old name no longer matches after transformation).

3. **Given** only a version bump is needed (same event name, version incremented),
   **When** a version-only rename is declared,
   **Then** handlers registered for the new version receive the event with the original payload.

---

#### User Story 3 - Event Splitting (Priority: P3)

**Plain-English explanation**: A good practice in event design is to avoid event names containing "and"
(e.g., `StudentEnrolledAndCourseUpdated`), because "and" signals that two distinct things happened and
were bundled together. Imagine you have exactly this problem: a single stored event that records two
facts. You want to split it into two proper events: `StudentEnrolled` and `CourseCapacityUpdated`.

Every time this stored event is read from the event store – whether to rebuild a projection or to
replay an entity's history – the transformation must produce both replacement events in the correct order.
Handlers and projections written for the individual events will then work correctly.

**Why this priority**: Splitting an event changes the shape of the event stream itself. This cannot be
done at the handler level – every handler would need its own split logic. Upcasting is the right tool
because the transformation is applied once, transparently, for all consumers.

**Independent Test**: Register a splitting transformation for `StudentEnrolledAndCourseUpdated` v1.0.0
that produces `StudentEnrolled` followed by `CourseCapacityUpdated`. Replay the stream and verify:
the original event is gone, both output events appear in order, each handler receives only the event
it is registered for.

**Acceptance Scenarios**:

1. **Given** a stored `StudentEnrolledAndCourseUpdated` event at version 1.0.0,
   **When** the event stream is read with the splitting transformation registered,
   **Then** two events appear in its place – `StudentEnrolled` first, then `CourseCapacityUpdated` --
   in that exact order.

2. **Given** a handler registered only for `StudentEnrolled`,
   **When** the split produces both events,
   **Then** that handler receives only the `StudentEnrolled` event and is unaware of
   `CourseCapacityUpdated`.

3. **Given** an event of a different type in the same stream,
   **When** it is read,
   **Then** it passes through the splitting transformation unchanged.

4. **Given** a split transformation for `StudentEnrolledAndCourseUpdated` v1.0.0 and a subsequent
   transformation for `StudentEnrolled` v1.0.0 registered in that order,
   **When** the stream is read,
   **Then** the split fires first producing `StudentEnrolled` v1.0.0 and `CourseCapacityUpdated` v1.0.0,
   and the subsequent transformation then applies to the `StudentEnrolled` output event – both output
   events from the split flow through the remainder of the chain.

---

#### User Story 4 - Event Dropping (Priority: P4)

**Plain-English explanation**: Imagine a `SystemHeartbeat` event was accidentally stored in the event
store. It carries no business meaning – it was a monitoring artifact that should never have been
persisted. Every projection and handler that replays history will encounter it and needs to ignore it.
Rather than adding "if it's a heartbeat, skip it" logic to every single handler, you want to register
a transformation that silently removes these events before any handler sees them.

This is the simplest special case of splitting: one event in, zero events out.

**Why this priority**: Dropping events keeps event streams clean and removes the need for defensive
"ignore this" logic scattered across handlers. It is also important for compliance: some events may
need to be suppressed from processing without physically deleting them from storage.

**Independent Test**: Register a drop transformation for `SystemHeartbeat`. Replay the stream and
verify that no handler receives any `SystemHeartbeat` event, while all other event types reach their
handlers normally.

**Acceptance Scenarios**:

1. **Given** a stored `SystemHeartbeat` event,
   **When** the event stream is read with the drop transformation registered,
   **Then** no output event appears in its place and no handler receives it.

2. **Given** a mix of `SystemHeartbeat` and `CourseCreated` events in the same stream,
   **When** the stream is read,
   **Then** only `CourseCreated` events reach handlers; every `SystemHeartbeat` is silently suppressed.

3. **Given** a tracking processor that has processed up to and including a `SystemHeartbeat` event,
   **When** the processor restarts,
   **Then** the processor resumes after the dropped `SystemHeartbeat` – it does not reprocess it.
   The stream position (tracking token) advances past dropped events as if they had been handled.

---

#### User Story 6 - Chaining Transformations Across Multiple Versions (Priority: P2)

**Plain-English explanation**: Real systems do not jump from v1 directly to the latest version in
one step. Over time, `CourseCreated` might have gone through three versions:

- v1.0.0: only `capacity`
- v2.0.0: `capacity` split into `minCapacity` and `maxCapacity`
- v3.0.0: `minCapacity` and `maxCapacity` merged into a `capacityRange` value object

A developer following Gregory Young's advice writes one transformation per version hop (v1 to v2, and
v2 to v3) rather than a single transformation that jumps straight from v1 to v3. Each transformation is small,
focused, and independently testable. When both are registered in order, the framework chains them:
a v1.0.0 event first passes through the v1→v2 transformation, and the output then passes through the
v2→v3 transformation, arriving at the handler as v3.0.0.

This "prefer chain over direct" approach means that when v4.0.0 arrives, you add one new transformation
(v3→v4) rather than rewriting all previous ones.

**Why this priority**: The chain mechanism is what makes upcasting maintainable over time. Without
it, every new version requires updating all previous transformations. This story tests that the
framework composes individual transformations correctly without any extra configuration.

**Independent Test**: Register two transformations for `CourseCreated` (v1→v2 and v2→v3) in order.
Replay a v1.0.0 event and verify the handler receives a v3.0.0 event with the correct
`capacityRange` value derived from the original `capacity`.

**Acceptance Scenarios**:

1. **Given** a stored `CourseCreated` event at version 1.0.0,
   **And** a v1→v2 transformation and a v2→v3 transformation registered in that order,
   **When** the event is read from storage,
   **Then** the handler receives the event as version 3.0.0 with the correct transformed payload,
   and neither intermediate version (2.0.0) is exposed to any handler.

2. **Given** a stored `CourseCreated` event at version 2.0.0 (written after the first migration),
   **When** the same chain is applied,
   **Then** the v1→v2 transformation does not apply (version does not match), the v2→v3 transformation applies,
   and the handler receives version 3.0.0.

3. **Given** a stored `CourseCreated` event at version 3.0.0 (written after both migrations),
   **When** the same chain is applied,
   **Then** neither transformation applies and the handler receives the event unchanged.

4. **Given** two transformations registered in the wrong order (v2→v3 before v1→v2),
   **When** a v1.0.0 event is read,
   **Then** the v2→v3 transformation does not apply (its input version 2.0.0 does not match the stored
   version 1.0.0), the v1→v2 transformation applies, and the output is version 2.0.0 – demonstrating
   that registration order is the chain order and incorrect ordering leads to incomplete
   transformation, which is the developer's responsibility to get right.

#### User Story 7 - Feedback on Misconfiguration and Runtime Failures (Priority: P1)

**Plain-English explanation**: A developer registers two transformations that both target the same
event name and version – perhaps by copy-pasting a registration block and forgetting to change the
source identity. Or they register a transformation where the target identity is identical to the
source (same name and version), which would cause an infinite loop at runtime. Or they provide a
version string like `"1.0"` instead of `"1.0.0"`.

Without early detection, these mistakes produce no error at startup but cause silent failures at
runtime: events matching two conflicting registrations are processed twice, the application loops
indefinitely on a self-referencing transformation, or a transformation never fires because its
version string never matches the stored events. These bugs are hard to reproduce and hard to
diagnose.

The framework MUST catch all five classes of misconfiguration before any event is read from the
store. Four are detected at individual registration calls (`register()`): duplicate `from` identity,
self-loop, invalid semver, and non-qualified name. The fifth – a multi-step cycle introduced by
chaining 1:1 transformations – is detected at chain-build time (the configurer's `.build()` step),
because a cycle only becomes visible once the full chain is known. All five produce a clear error
that names the conflicting or invalid declaration so the developer can fix it immediately without
debugging a live system. When a transformation fails at runtime (throws an exception during event
processing), the framework must equally refuse to continue silently: it propagates the failure
immediately with full context (which transformation failed, and which event triggered it).

**Why this priority**: Silent misconfiguration errors are the highest-cost failure mode for an API
used only at startup. A clear message at startup costs almost nothing; a runtime bug in an event
replay can corrupt projections silently and take hours to diagnose.

**Independent Test**: Attempt to register each invalid configuration in isolation and verify that
the framework raises a descriptive error at registration time, before any event is processed.

**Acceptance Scenarios**:

1. **Given** two transformations registered with the same `from` identity (same fully qualified name
   AND same version),
   **When** the second registration is attempted,
   **Then** the framework raises an error at registration time identifying the conflicting `from`
   value, and no event processing occurs.

2. **Given** a 1:1 transformation where `from` and `to` are identical (same name AND same version),
   **When** the registration is attempted,
   **Then** the framework raises an error at registration time identifying the self-loop, and no
   event processing occurs.

3. **Given** a transformation registered with a version string that does not conform to
   major.minor.patch format (e.g., `"1.0"` or `"v1"`),
   **When** the registration is attempted,
   **Then** the framework raises an error at registration time identifying the invalid version string,
   and no event processing occurs.

4. **Given** a transformation registered with a non-fully-qualified event name (e.g., `"CourseCreated"`
   instead of `"com.example.CourseCreated"`),
   **When** the registration is attempted,
   **Then** the framework raises an error at registration time identifying the invalid name, and no
   event processing occurs.

5. **Given** two 1:1 transformations forming a cycle (transformation A maps `X@1.0.0 → X@2.0.0`
   and transformation B maps `X@2.0.0 → X@1.0.0`),
   **When** the configurer's `.build()` step locks the chain,
   **Then** the framework raises an error at lock time enumerating the full cycle
   (`X@1.0.0 → X@2.0.0 → X@1.0.0`), and no event processing occurs. The framework MUST NOT attempt
   to silently break the cycle or apply only part of it.

6. **Given** a correctly configured transformation chain that has already started processing events,
   **When** an attempt is made to register an additional transformation at runtime,
   **Then** the framework rejects the registration immediately with a clear error stating that the
   chain is immutable once event processing has begun, and the chain remains unchanged.

7. **Given** a transformation whose function throws an exception when applied to a specific event,
   **When** that event is read from the event store,
   **Then** the framework propagates the exception immediately to the caller – the event-sourced entity load,
   DCB read, or tracking processor – halting processing. The exception clearly identifies which
   transformation failed and the name, version, and stream position of the event that triggered it.
   The framework MUST NOT silently skip the failed event or log-and-continue.

8. **Given** a 1:1 transformation declared with `to = (com.example.CourseCreated, 2.0.0)` whose
   function returns a payload of a different type (e.g. `OrderPlacedV2`) or a payload whose
   resolved `MessageType` does not match the declared `to` (e.g. version `3.0.0` or name
   `com.example.CourseScheduled`),
   **When** the event is read from the event store and the transformation is applied,
   **Then** the framework propagates a runtime failure under the same path as scenario 6, with a
   message identifying the offending transformation, the declared `to`, and the actual output
   `MessageType`. The framework MUST NOT silently coerce the output identity to match the declared
   `to`, and MUST NOT let the wrong-typed output flow into the rest of the chain.

---

#### User Story 8 - Startup Observability for the Transformation Chain (Priority: P2)

**Plain-English explanation**: A developer deploys a new version of the application with two
transformations registered. The application starts, processes events, and the developer wants to
confirm that both transformations were actually picked up by the framework – without reading
framework internals, enabling debug logs for the whole system, or writing a test that inspects
internal state.

Later, during an incident, they want to be able to turn on detailed logging and see exactly which
transformation ran for which event, so they can tell whether the transformation chain is the source
of incorrect projection state.

**Why this priority**: Transformation registration is a startup-time concern; a developer cannot
inspect the chain at runtime the way they can inspect a running handler registry. Log output is the
only way to confirm correct wiring in a production deployment. Debug-level logging is the standard
escape hatch for diagnosing whether a transformation ran.

**Independent Test**: Register two transformations, start the application, and verify that INFO-level
log output names both registered transformations. Then replay an event that matches one transformation
and verify that DEBUG-level output identifies which transformation ran and for which event.

**Acceptance Scenarios**:

1. **Given** two transformations registered at startup,
   **When** the transformation chain is built,
   **Then** a single INFO-level log entry is emitted listing the number of registered transformations
   and their `from` identities, without requiring DEBUG logging to be enabled.

2. **Given** a stored event that matches a registered transformation,
   **When** the transformation is applied,
   **Then** a DEBUG-level log entry is emitted identifying which transformation ran and the name and
   version of the event it was applied to.

3. **Given** a stored event that does not match any registered transformation,
   **When** it passes through the chain,
   **Then** no DEBUG log entry for a transformation being applied is emitted for that event (the
   chain is silent for non-matching events at DEBUG level).

---

#### User Story 9 - Command Upcasting (Priority: P3)

**Use case**: a receiver applies the transformation chain to an incoming command before
dispatching it to the command handler -- same mechanism as events, because the transformer is a
decorator around the message converter and works for any `Message`. Common scenarios: structural
field changes, renames, version bumps. Most relevant in rolling deployments where old and new
service versions coexist.

**Why this priority**: deliverable on top of the event upcasting infrastructure. Adds command
versioning support without a separate API.

**Scope**: 1:1 (structural transform, rename) applies. 1:N split and 1:0 drop do NOT apply --
each command is a single intent expecting a response. Downcasting (new-to-old at the sender) is
deferred (see Part C).

**Independent Test**: register a 1:1 command transformer that fills a default `enrollmentReason`
on old `EnrollStudent` commands; dispatch a v1 command; assert the handler receives the
transformed payload.

---

#### User Story 10 - Query Upcasting (Priority: P3)

**Use case**: a receiver applies the transformation chain to an incoming query before dispatching
it to the query handler -- same mechanism as commands and events. Common scenario: a new optional
filter parameter (e.g., `includeArchived`) is added and the handler needs a default value for
queries sent by older callers.

**Why this priority**: deliverable on top of the same `Message`-based transformer mechanism. No
separate API.

**Scope**: 1:1 (structural transform, rename) applies. 1:N split and 1:0 drop do NOT apply.
Downcasting is deferred (see Part C).

**Independent Test**: register a 1:1 query transformer that fills a default
`includeArchived = false` on old `FindCoursesByFaculty` queries; dispatch a v1 query; assert
the handler receives the transformed payload.

---

#### Edge Cases

Each edge case below is fully specified in the referenced FR. This table exists as a quick-lookup
index; see the FR for the authoritative requirement.

| Scenario | Specified in |
|---|---|
| Two transformations with the same `from` identity | FR-009 |
| Self-loop registration (`from` == `to`) | FR-009 |
| Multi-step cycle introduced by chaining (e.g. A: X@1→X@2; B: X@2→X@1) | FR-009; US7 scenario 5 |
| Invalid version format (not major.minor.patch) | FR-009 |
| Non-fully-qualified event name in registration | FR-009 |
| Late registration after event processing starts | FR-004 |
| Wrong chain order silently under-converts | FR-004; US6 scenario 4 shows expected behavior |
| Split output events flow through the remaining chain | FR-007 |
| Envelope (entity, token, sequence number) preserved; metadata preserved by default, may be modified via message-level entry point; split outputs inherit metadata by default | FR-011 |
| Non-matching events pass through without deserialization | FR-012 |
| Tracking token advances past dropped events | FR-015 |
| Transformation exception propagates immediately | FR-016 |
| Unversioned events treated as version 0.0.1 | FR-017 |
| 1:1 transformation output does not match declared `to` | FR-019; US7 scenario 8 |
| Concurrent invocation of same transformation from multiple threads | FR-006; US1 scenario 6 |
| Runtime cycle induced by a `MultiEventUpcaster` rule (rule emits an identity that re-enters the chain through a 1:1 transformation whose output re-matches the rule) | Developer responsibility – not statically detectable per FR-009 carve-out for rule-produced identities; surfaces as stack overflow at read time. Mitigation: ensure split rules never emit identities that later 1:1 transformations rewrite back to the rule's `from`. |
| Transformed event with no registered handler | Standard AF5 no-handler behavior – not a transformation failure |
| Transformation retirement (when safe to remove) | Developer responsibility – safe only when no stored event exists at the `from` version in any event store, including backups |

---

### Part C: Out of Scope and Non-Transformer Scenarios

This section documents scenarios that are either deferred to a later release, or are not a
transformer problem at all. Knowing when NOT to reach for a transformer is as important as
knowing when to use one.

The entries fall into two categories:

- **Deferred**: a transformer is the right concept, but the scenario is out of scope for 5.2.0. The transformer API shipped in 5.2.0
  is designed so these can land in a later release without breaking changes for existing users.
- **Wrong tool**: a transformer would give the wrong answer or corrupt the audit trail. The
  correct solution is a different pattern entirely (a new event type, a stateful projection, a
  Copy and Replace migration, etc.).

---

#### N-to-1 Merge `[Deferred]`

**Scenario**: combine multiple (N) stored events into one (1) output event -- for example,
merge `StudentRegistered` + `StudentEnrolledInFaculty` into a single `StudentJoined` event.

**Why deferred for 5.2.0**: merging requires the framework to "remember" earlier events while
reading later ones. Two unresolved problems:

1. **Memory scope is context-dependent.** Entity loads are bounded streams (per-entity, finite);
   tracking processors are unbounded streams (every entity, continuous). Memory that resets
   per-entity is predictable for the first; cross-entity memory in the second would silently
   mix unrelated entities – exactly the AF4 context-aware-upcaster bug class the team has
   chosen not to repeat.

2. **`MessageStream` has no grouping / windowing today.** Adding it would touch core streaming
   infrastructure well outside this feature's scope.

**What to do instead**:

- **Stateful projection** – accumulate events over time and produce a combined read model. Right
  tool for most cases.
- **Copy and Replace** (Gregory Young) -- one-time migration: read old events, write new ones
  to a fresh stream. Right tool when the stored stream itself must change.
---

#### Moving Data Between Events `[Deferred]`

**Scenario**: enrich a stored event with data captured from an earlier event in the stream -- for
example, when reading `TuitionPaid`, attach the `scholarshipCode` that was present on the earlier
`StudentEnrolled` event of the same student.

**Why deferred for 5.2.0**: this requires a **context-aware transformation** – one that
remembers data from earlier events and uses it when transforming a later event. Same memory-scope
problem as the N-to-1 entry above, applied at the field level: per-entity scope works for entity
loads but silently mixes data across entities on tracking processors. AF4 context-aware upcasters
had this exact inconsistency, causing subtle and hard-to-reproduce bugs.

**What to do instead**: **Copy and Replace** – read existing events, rewrite them with the
fields they should have carried, switch the application over to the corrected stream. One-time
migration, not ongoing transformation.

---

#### Downcasting `[Deferred]`

**Scenario**: in a rolling deployment, a newer sender strips fields that an older receiver does
not yet understand. For example, service v2 sends a `FindCoursesByFaculty` query with a new
`includeArchived` filter; service v1's handler does not know the field. Ideally the sender
removes it before dispatch.

**Why deferred for 5.2.0**: downcasting is new-to-old at the
sender, instead of old-to-new at the receiver. It introduces sender-awareness questions (how
does the sender know what version the receiver understands?) that need their own specification.

Command and query upcasting (the receiver-side, old-to-new direction) IS in scope for 5.2.0 –
see US9 (commands) and US10 (queries) in Part B. The transformer is a decorator around the
message converter, so the same mechanism works for any `Message` type.

**When to revisit**: when concrete rolling-deployment cases surface that cannot be solved by
receiver-side upcasting alone.

---

#### Snapshot Upcasting `[Deferred]`

**Use case**: a 1:1 `Snapshot -> Snapshot` transformer would let the framework apply a
state-schema change to a stored snapshot instead of discarding it and replaying all events.

**Why deferred for 5.2.0**: scope and focus, NOT architecture. The snapshot-on-`MessageStream`
rework has landed -- `SnapshotEventMessage extends GenericEventMessage` exists, and
`SnapshotCapableEventStorageEngine` delivers the snapshot as the first entry of the
`MessageStream` returned by `EventStoreTransaction.source(...)`. The same converter-decorator we
use for events on the storage-engine stream would naturally see `SnapshotEventMessage` entries,
so the integration point is already in place.

**In scope for 5.2.0**: the design MUST keep snapshot transformations easily triggerable through the
same decorator mechanism – no special-casing of `SnapshotEventMessage` in the chain, no
additional registration API surface. A future snapshot transformation should require only a new user
story and the corresponding tests, not a redesign.

**Ergonomic gap (not a blocker)**: `Snapshot` is a plain record without a `.payloadAs(Class<?>)`
accessor. A future snapshot transformation either uses the `Converter` directly
(`converter.convert(snapshot.payload(), TargetType.class)`) or a `.payloadAs(...)` helper added
on `Snapshot` later. Both options remain open.

**When justified** (Gregory Young): snapshots are a cache, not a source of truth.
Discard-and-replay is the correct primary strategy; snapshot upcasting is the optimisation,
worth it only when the event history is huge AND replay is slow AND the snapshot schema changed.

---

#### Annotation-Based Transformation Registration `[Deferred]`

In 5.2.0, transformations are registered programmatically only. An annotation-based model is intentionally deferred.

**Why not now**: The spec's primary AF4 complaint is that Spring Bean-based registration made chain
order unpredictable. Introducing an annotation-discovery mechanism in the same release risks
reintroducing that problem through a different path. Programmatic registration keeps the ordering
guarantee trivially enforceable: the order of the API calls is the chain order, with no scanning,
no priority attributes, and no framework magic.

#### Semantic Meaning of a Field Changed Silently `[Wrong tool]`

**Scenario**: a field's name and type stay the same, but its meaning changes. For example,
`CourseCreated.capacity` used to mean "total available seats" but should now mean "seats
remaining after cancellations" -- same field, different interpretation.

**Why a transformer is the wrong tool**: the stored data did not change, only its meaning did.
A transformer that rewrites old events to match the new interpretation corrupts the audit trail:
events that were correct under the old meaning would be silently presented as if they had always
carried the new number.

**What to do instead** (Gregory Young's rule): a value that now means something different from
what it meant when it was written is a different event type, not a new version. Create a new
event with a name that makes the new meaning explicit -- e.g., `CourseRescheduled` with an
`availableSeats` field. Keep `CourseCreated.capacity` meaning what it always meant.

---

#### New Event Cannot Be Derived from the Old One `[Wrong tool]`

**Scenario**: an event has changed so fundamentally that the new version cannot be computed
from the old stored data. For example, `CourseCreated` was originally modeled around rooms
and manual time slots; the new model uses recurring templates and automated slot generation –
a different concept, not a new version.

**Why a transformer is the wrong tool**: a transformer must produce the new event from the old
data. If the old data lacks enough information, the transformer is forced to invent or default
values – producing events that misrepresent what actually happened and destroying the audit
trail.

**What to do instead** (Gregory Young's rule): a new event version MUST be derivable from the
old version. If it cannot, it is a genuinely new business event – give it a new name (e.g.,
`CourseScheduled`). Run both event types in parallel during migration: old projections continue
to consume `CourseCreated`, new projections consume `CourseScheduled`. Use Copy and Replace as
a last resort if the old stream must be fully replaced.

---


## Requirements

### Functional Requirements

- **FR-001**: For 1:1 transformations (structural payload change or rename), developers MUST be able
  to declare both the source event identity (`from`: fully qualified event name + version) and the
  target event identity (`to`: fully qualified event name + version), together with an optional
  payload transformation rule. The fully qualified event name is the namespace (Java package) combined
  with the local name (e.g., `com.example.CourseCreated`), matching how `MessageType` resolves event
  identity via `@Event`.
  The framework applies the rule to produce the output payload; if no rule is supplied, the payload
  passes through unchanged. Identity conflicts (including the `from == to` self-loop and multi-step
  cycles created by chaining) are rejected per FR-009.
  _Traces to: US1 (structural transform needs source identity, target identity, and a payload rule), US2 (rename needs source and target identity without a payload rule)._
- **FR-002**: Developers MUST be able to declare an event rename (old name + version to new name
  and/or version) without providing a payload transformation rule. This is the natural degenerate
  case of FR-001: declare source and target identities with different names or versions; the payload
  is preserved unchanged. No special rename declaration is required – declaring source and target
  identities serves both structural transformations and pure renames.
  _Traces to: US2 (rename without a payload rule is the primary scenario)._
- **FR-003**: For 1:N splits and 1:0 drops, developers MUST be able to declare only the source event
  identity (`from`: fully qualified event name + version) together with a rule that produces zero or
  more complete replacement events. Each replacement event carries its own identity (name + version)
  and payload as determined by the developer's rule. The framework imposes no constraint on the
  target identity for this pattern – the rule has full control over the identity and payload of
  every replacement event. Self-loop detection (FR-009) does not apply since no target identity is
  declared.
  _Traces to: US3 (split: source identity + rule producing two replacement events), US4 (drop: source identity + rule producing zero replacement events)._
- **FR-004**: The order in which transformations are registered MUST be the order in which they are
  applied. The framework MUST NOT reorder transformations based on any implicit or explicit priority
  mechanism. Registration is programmatic (calling a framework API) and is valid only during
  application startup or build time – the chain is immutable once event processing begins. Any
  attempt to register a transformation after event processing has started MUST be rejected immediately
  with a clear error stating that the chain is locked.
  Annotation-based registration is not supported in this release (see out-of-scope section).
  _Traces to: US6 (correct chain order is what makes version-hop chaining work), US1 (startup-only registration is the prerequisite for all transformations to apply consistently), US7 scenario 6 (late registration rejected with clear error)._
- **FR-005**: Each registered transformation MUST target exactly one specific fully qualified event
  name and version combination. A transformation MUST NOT apply to events it was not explicitly
  registered for. Matching is exact: both the fully qualified name and the version must match the
  stored event's `MessageType` precisely.
  _Traces to: US1 scenario 3 (non-matching events pass through unchanged), US6 (version-exact matching is what routes v1 to v1→v2 and v2 to v2→v3, not both)._
- **FR-006**: Transformations MUST be deterministic and thread-safe: given the same input event, a
  transformation MUST always produce the same output. Concretely this means:
  - MUST NOT call external services or read from databases.
  - MUST NOT depend on the current time, random number generators, or any other non-deterministic
    source.
  - MUST NOT mutate shared instance state across invocations.
  - MAY read from in-process, read-only data structures (e.g., a constant `Map` used as a lookup
    table for field renames). Read-only access to immutable data does not violate determinism.
  The framework MAY invoke a transformation concurrently from any thread (e.g., two tracking
  processors reading the same stream in parallel, simultaneous event-sourced entity loads).
  Per-invocation local state (variables and intermediate values computed inside the function body)
  is always permitted. This requirement is a contract for documentation and communication; the
  framework does not enforce it at runtime -- violations will produce hard-to-diagnose
  non-deterministic bugs.
  _Traces to: US1 scenario 4 (all three reading contexts produce identical output -- only guaranteed if the transformation is deterministic and thread-safe), US1 scenario 6 (concurrent invocation from multiple threads produces identical output without requiring external synchronization), US6 scenario 1 (chaining always produces the same final version regardless of when or how often it runs)._
- **FR-007**: When events pass through the transformation chain, the output of each transformation
  MUST flow into the next transformation as input (chaining). This applies to all output events,
  including every replacement event produced by a 1:N split: each replacement event MUST
  continue through the remaining transformations in the chain independently.
  _Traces to: US6 (the entire chaining story depends on output-as-input composition; without this, v1→v2 and v2→v3 would not compose), US3 scenario 4 (split output events flow through the remainder of the chain)._
- **FR-009**: The framework MUST detect and report the following conflicts before any event is
  processed. Four are detected at each `register(...)` call; one (multi-step cycle) is detected
  at chain `lock()` during `.build()`. None are deferred to event-processing time:
  - Two transformations with the same `from` identity (same name + version) – duplicate targeting.
  - A transformation where `from` and `to` are identical (same name AND same version) – guaranteed
    infinite loop: the output would immediately re-match the same transformation on the next pass.
  - A version string in `from` or `to` that does not conform to semantic versioning format
    (major.minor.patch, e.g. `"1.0.0"`) – rejected with a clear error identifying the invalid value.
  - An event name in `from` or `to` that is not fully qualified (e.g., `"CourseCreated"` instead of
    `"com.example.CourseCreated"`) – rejected with a clear error identifying the unqualified name.
    Unqualified names would silently never match stored events, since `MessageType` always stores the
    fully qualified name.
  - A multi-step cycle introduced by chaining (e.g., upcaster A: `X@1.0.0 → X@2.0.0` and upcaster
    B: `X@2.0.0 → X@1.0.0`) – detected by graph walk over the union of all declared `from → to`
    edges from registered 1:1 `SingleEventUpcaster`s. 1:N / 1:0 upcasters (`MultiEventUpcaster`)
    are excluded from this analysis: their replacement events' identities are rule-determined and
    cannot be inspected statically. The framework MUST surface the cycle's full edge list in the
    error message. Detection runs at chain `lock()` (the configurer's `.build()` step), not at each
    individual `register(...)` call, because the cycle only manifests once the full chain is known.
  _Traces to: US7 (all five acceptance scenarios in US7 correspond directly to the conflict classes listed here)._
- **FR-010**: Transformations MUST operate on event data as structured, typed objects. Developers
  MUST NOT need to work with raw bytes or serializer-internal wire formats. The framework provides
  two entry points for accessing the payload, and a transformation chooses one:
  - **Declarative target type (simple case)**: the transformation declares a target type at
    registration; the framework auto-resolves the stored bytes to that type via the registered
    `ChainedConverter` before invoking the transformation function. The function receives the
    typed payload directly.
  - **Converter access (advanced case)**: the transformation receives a `Converter` instance and
    calls `.convert()` inline when it needs the payload in a specific representation. This entry
    point is intended for transformations that need to inspect or produce multiple representations
    of the same payload (e.g., read as a structural representation for branching, then return a
    POJO).
  The two entry points share the same registration and chain semantics; only the payload-access
  shape differs. The `EventConverter` does NOT pre-convert all events before the chain runs --
  conversion is on-demand in both cases.

  Valid target types are determined by what the registered `Converter` for the stored event's
  format can produce. A transformation MAY request any representation supported by that converter.
  The shipped converters support at minimum:
  - **Jackson 2 / Jackson 3 / CBOR**: typed Java class (POJO), `JsonNode`, `ObjectNode`,
    `Map<String, Object>`.
  - **Avro**: typed `SpecificRecordBase` subclass (generated or hand-written), or `GenericRecord`
    for schema-only field access without a concrete Java class.
  - **Other formats (user-supplied `Converter`, e.g. an XML-based serializer)**: the typed
    representations exposed by that converter. For an XML converter this is typically a DOM
    `Document` / `Element` or the converter's generated binding type. XML's element-order
    sensitivity is the user's responsibility -- the framework treats whatever the converter
    produces as authoritative.

  Raw `byte[]` is not a valid target for transformation payloads -- events stored as `byte[]` in
  the event store are automatically bridged to the requested structured type by the registered
  `ContentTypeConverter` chain, invisible to the transformation author.
  _Traces to: US1 (developer writes a transformation function that reads and produces typed payload objects), US3 (split function accesses the original payload to derive two new payloads), US4 (drop function may inspect the payload to decide whether to suppress)._
- **FR-011**: The event envelope -- entity type, entity identifier, tracking token, and sequence
  number -- MUST be preserved unchanged through any transformation. These are storage-level facts
  the framework owns; transformations have no say.

  Event metadata (correlationId, causationId, tracing headers, custom metadata) MAY be modified by
  message-level transformations. The framework provides two transformation shapes and the user
  chooses per upcaster:
  - **Payload-only (default, simple case)**: the transformation returns a new payload; the
    framework rebinds it to a new `EventMessage` with the declared `to` identity and carries the
    original metadata forward unchanged. This is the recommended shape for the vast majority of
    schema/identity changes.
  - **Message-level (advanced case)**: the transformation returns a complete `EventMessage`,
    including its metadata. The framework preserves the envelope (entity, token, sequence number)
    but accepts whatever metadata the transformation produced. Use this for explicit metadata
    migrations (e.g., renaming a metadata key, migrating a tracing header format).

  The event store itself is append-only and never modified -- the original stored event always
  remains intact on disk. Metadata modification only affects the in-memory `EventMessage` that
  flows downstream to handlers, projections, and tracking processors. Operators who need the
  original metadata can always re-read the raw stored event.

  For a 1-to-N split (`MultiEventUpcaster`), every output event inherits the original event's
  envelope and metadata by default. A split rule MAY override the metadata on any individual
  output event independently -- the rule is responsible for explicit overrides; the framework
  applies "inherit from input" as the default for any output where metadata was not explicitly
  set.

  Transformations MUST NOT modify the envelope (entity type, entity id, tracking token, sequence
  number). Any attempt to do so via the advanced entry point is overridden by the framework
  before delivery downstream -- this is a framework invariant, not a user responsibility.
  _Traces to: US1 scenario 1 (transformed event carries correct payload; envelope is unchanged), US3 scenario 1 (split output events are independent and complete -- they inherit the envelope of the original by default)._
- **FR-012**: The framework MUST NOT deserialize an event's payload unless that event matches the
  target name and version of at least one registered transformation. Non-matching events MUST pass
  through the transformation chain without triggering deserialization. This is enforced by the
  on-demand model: payload conversion only occurs when the transformation explicitly requests it,
  never eagerly.
  _Traces to: US1 scenario 3 (events that do not match pass through the chain unchanged – without being deserialized), US4 scenario 2 (non-heartbeat events in the same stream pass through untouched)._
- **FR-013**: The transformation chain MUST be applied consistently across all three event-reading
  contexts: event-sourced entity loading (bounded stream per entity), DCB reads
  (`SourcingCondition`-based bounded stream across multiple entities for command-side consistency
  enforcement), and tracking processor reads (unbounded stream across the full event store for
  projections). All three contexts read events from the same event store; a stored event MUST
  produce the same transformed output regardless of which reading context triggered the read.
  Inconsistent application – where one context sees transformed events and another sees the original
  stored format – is explicitly prohibited.
  _Traces to: US1 scenario 4 (explicitly tests all three reading contexts and requires identical output from each)._
- **FR-014**: The framework MUST emit observability signals at two levels. The exact log
  message format is framework-internal, but each entry MUST include at minimum the listed
  fields so log-scraping and incident review remain reliable across releases:
  - **INFO** at startup, once, when the transformation chain is built. Required fields:
    (a) the count of registered transformations and (b) each registered transformation's
    `from` identity (fully qualified name + version) and, for 1:1 transformations, its `to`
    identity. INFO level avoids flooding production logs while still confirming correct wiring.
  - **DEBUG** each time a transformation is applied to a matching event. Required fields:
    (a) the transformation's identifier -- for a hand-rolled `SingleEventUpcaster` or
    `MultiEventUpcaster` this is the implementation class's simple name; for the declarative
    `EventUpcaster.from(...)` builder path (where the user supplies only a lambda and has no
    nameable class) the framework substitutes the `from()` identity as the identifier, which
    is unique within a chain by FR-009 conflict 1, (b) the matched event's `from` identity,
    and (c) the event's stream position (sequence number for entity loads / tracking token
    for processor reads). DEBUG is off by default in production.
  No public API changes are required to support this – logging is a framework-internal concern.
  _Traces to: US8 (all three acceptance scenarios in US8 map directly to the two log levels specified here)._
- **FR-015**: The streaming position (tracking token) MUST advance past dropped events. When a
  transformation drops an event (returns an empty list), the framework MUST record that the event
  was read and advance the position marker accordingly. A tracking processor that restarts MUST NOT
  reprocess any event that was dropped in a previous run. The drop is transparent to position
  tracking: the stream advances as if the event had been handled normally.
  _Traces to: US4 scenario 3 (processor restarts after dropping a SystemHeartbeat and does not reprocess it)._
- **FR-016**: If a transformation function throws an exception, the framework MUST propagate it
  immediately to the caller – the event store read, event-sourced entity load, DCB read, or tracking processor
  invocation. Processing halts. Silently skipping the failed event or logging-and-continuing is
  explicitly prohibited: a failed transformation leaves the output event undefined, and delivering
  corrupted or absent state to downstream consumers without a signal is worse than a hard failure.
  The propagated exception MUST identify which transformation failed and the name, version, and
  stream position of the event that triggered the failure.
  _Traces to: US7 scenario 7 (transformation throws during event read; caller receives propagated exception with clear context)._
- **FR-017**: Events stored without an explicit version MUST be treated by the framework as carrying
  version `0.0.1` – the AF5 default version in `MessageType`. Transformation registrations that
  target version `0.0.1` apply to these legacy events. No special API is required; the developer
  registers for `0.0.1` exactly as they would for any other version.
  _Traces to: US1 scenario 5 (a stored event with no version is matched by a transformation registered for version 0.0.1)._
- **FR-018**: A transformation MUST be invocable from a plain JUnit test without an event store,
  tracking processor, or framework bootstrapping. The only dependency a transformation may require
  at invocation time is a `Converter` instance (and only when the advanced entry point of FR-010 is
  used; the declarative-target-type entry point requires no `Converter` from the test author). This
  guarantees that AF4's testing pain (manual construction of serialized representations, full
  upcaster chain simulation) does not recur. A full test fixture API is deferred to a separate
  issue; this requirement is an architectural invariant on the transformation API itself, not a
  fixture deliverable.
  _Traces to: US1, US3, US4, US6 (every transformation in scope must be unit-testable in isolation to support TDD-style development of versioning logic)._
- **FR-019**: For 1:1 transformations that supply a payload transformation rule, after the function
  returns, the framework MUST verify that the output payload's identity (fully qualified name +
  version) matches the `to` declared at registration. A mismatch MUST be treated as a runtime
  transformation failure under FR-016 -- propagated immediately with full context (which
  transformation failed, the declared `to`, the actual output identity, and the stream position of
  the event that triggered it). The framework MUST NOT silently coerce the output identity to the
  declared `to`. Rationale: cheap insurance against author bugs that would otherwise produce silent
  data drift downstream (the wrong-typed output flows through FR-007 chaining and is silently
  ignored by later transformations that don't match its name).
  This requirement applies to every 1:1 transformation that supplies a payload function. It
  is satisfied trivially -- but still checked -- for **pure renames (FR-002)**, because the
  rename factory produces an upcaster whose `apply(...)` is an identity-rebinding operation
  owned by the framework: the output payload is the input payload and the output identity is
  set to the declared `to` before the check runs. The check therefore never fails for a
  rename produced by the framework's factory.
  This requirement does NOT apply to:
  - **1:N / 1:0 transformations (FR-003)** -- the rule has full control over each replacement
    event's identity by design.
  _Traces to: US1 (1:1 structural transform produces output matching declared target), US7 scenario 8 (output identity mismatch is propagated as runtime failure with declared-vs-actual diagnostic context)._
- **FR-020**: The transformer mechanism MUST support commands and queries in addition to events.
  The transformer is a decorator around the message converter and applies to any `Message`
  subtype (events, commands, queries). The 1:1 patterns -- structural transform (FR-001) and
  rename (FR-002) -- apply to all three message types. The 1:N split and 1:0 drop patterns
  (FR-003) apply ONLY to events: commands and queries are single-intent messages and the
  framework MUST reject registration of a `MultiEventUpcaster`-equivalent for command or query
  message types. Downcasting (new-to-old at the sender) is out of scope (see Part C).
  _Traces to: US9 (command upcasting), US10 (query upcasting)._

## Key Entities

- **Event transformation**: A single unit of logic that targets one event type at one
  version. "Transformation" is the canonical term used throughout this spec. Two patterns exist:
  (1) **1:1** – declares source and target event identity with an optional payload rule; covers
  structural changes and renames. (2) **1:N/1:0** – declares only the source identity with a rule
  producing zero or more replacement events; covers splits and drops. Each transformation has exactly one reason to change. Think of it as a simple
  recipe: "given this event in this format, produce these events in this format."
- **Transformation chain**: The ordered sequence of registered transformations applied when reading
  events from storage. Order is determined entirely by registration sequence – first registered
  runs first.
- **Snapshot transformation**: (Deferred to a future release) A unit of logic that would convert
  a stored entity snapshot from an old format to the current format, avoiding a full event replay
  for large entities. The hook point exists in `StoreBackedSnapshotter` but the API is not defined
  in this release. The discard-and-replay fallback remains the correct primary strategy.
- **MessageType / Event identity**: Every event has a **MessageType** – the combination of a
  fully qualified name (namespace + local name, e.g., `com.example.CourseCreated`) and a version
  (e.g., `1.0.0`). Both components are always present; there is no concept of a "versionless" event
  in AF5. Events stored before versioning was introduced are assigned the default version `0.0.1`
  by the framework. Transformation registration uses the fully qualified name in both `from` and
  `to` – using only the local name (e.g., `CourseCreated`) will silently fail to match stored
  events whose `MessageType` includes the namespace.
- **DCB read (`SourcingCondition`)**: **Dynamic Consistency Boundary** -- an AF5 mechanism for
  enforcing command-side consistency without requiring a fixed aggregate root. A `SourcingCondition`
  declares which events the command handler needs to make a decision (e.g., "all events tagged
  with this course id AND all events tagged with this student id"). The framework reads exactly
  those events as a bounded stream, applies the transformation chain (FR-013), and feeds the result
  into the command handler. DCB is one of the three event-reading contexts in FR-013 alongside
  event-sourced entity loading and tracking processor reads. DCB reads are bounded (finite) and may
  span multiple entity boundaries, in contrast to entity loads (bounded, single entity) and
  tracking processor reads (unbounded, all entities).

## Success Criteria

### Measurable Outcomes

- **SC-001**: A developer can implement and register a complete 1:1 structural event
  transformation in **no more than 10 lines of Java**, counted as: import-stripped registration
  block including `MessageType` constants and the transformation lambda. Reference scenario:
  US1's `CourseCreated` v1.0.0 -> v2.0.0 (see [quickstart.md](quickstart.md#step-1----structural-transformation-us1)).
  AF4's equivalent registration of the same scenario (one `SingleEventUpcaster` subclass plus
  the `org.springframework.context.annotation.Configuration` wiring) requires materially more
  code; the AF4-to-AF5 migration guide (SC-006) captures the side-by-side count.
- **SC-002**: 100% of registered transformations are applied in the exact order they were registered,
  verifiable across all four in-scope use cases.
- **SC-004**: A conflict between two transformations targeting the same event name and version is
  detected and reported before any event is processed (at startup or registration time).
- **SC-005**: All in-scope use cases -- structural transform, rename, split, drop (events),
  1:1 command upcasting (US9), and 1:1 query upcasting (US10) -- are demonstrated by code in
  `examples/university-demo`, each with at least one passing automated test executed under the
  Maven `-Pexamples` profile in CI. A reviewer running `./mvnw -Pexamples clean verify` MUST
  observe all transformers built, registered, and exercised by tests, without manual
  configuration.
- **SC-006**: A developer migrating from Axon Framework 4 upcasters can identify the equivalent
  AF5 approach for each of their existing upcasters from documentation alone, without reading
  framework source code.
- **SC-007**: A published Converter vs. transformation decision tree is available as a documentation
  artifact. It covers: (a) at least one concrete scenario where the EventConverter is the right
  tool (e.g., serialization format migration, adding/removing fields), (b) at least one concrete
  scenario where a transformation is the right tool (e.g., structural transformation, event splitting),
  and (c) the boundary rule stated in one sentence. A developer who has never used AF5 upcasters
  can read this artifact and correctly classify their own versioning scenario without reading
  framework source code.
- **SC-008**: Every in-scope transformation example (structural transform, rename, split, drop) is
  unit-tested in isolation -- the test exercises the transformation function with a plain JUnit
  test, without starting an event store, processor, or framework bootstrapping. Verifies FR-018.
- **SC-009**: A 1:1 transformation with a payload rule whose function returns an output with a
  `MessageType` other than the declared `to` produces a runtime error that names the offending
  transformation, the declared `to`, and the actual output identity. Verified by automated test.
  100% of mismatch cases produce the error; 0% silently coerce. Verifies FR-019.
- **SC-010**: A registered transformation invoked from N concurrent threads (N >= 8) on the same
  input event produces N identical outputs and requires no external synchronization from the
  transformation author. Verified by automated test. Verifies the thread-safety contract in FR-006.

## Assumptions

- The primary actor is an application developer building an event-sourced system with Axon
  Framework 5. They may be new to Axon but are comfortable with Java and understand basic
  event-sourcing concepts.
- Transformations apply at event read time, not at write time. Events already stored in the event
  store are never modified.
- Events stored without an explicit version are assigned the default version `0.0.1` by the
  framework. Transformations that need to target these legacy events use `0.0.1` as the version.
- Scope covers events (US1-US4, US6-US8) plus 1:1 command and query upcasting (US9, US10).
  Snapshot upcasting and downcasting are deferred (see Part C for the conditions under which
  each becomes feasible).
- N-to-1 event merging and moving data between events (context-aware transformations) are
  out of scope for this release. See the out-of-scope stories above for full rationale.
- The university demo application (plain Java, no Spring) is the target for demonstrating all four
  in-scope use cases (structural transform, rename, split, drop). Spring Boot integration is a follow-on concern.
