# Feature Specification: Event Upcasting API

**Feature Branch**: `feature/3597/upcasting-api`

**Created**: 2026-05-18

**Status**: Draft

**Input**: User description: "Upcasting API for Axon Framework 5.2.0 (issue #3597)"

---

## Background: Why Events Need Versioning

In an event-sourced system, every change in the application is recorded as an event and stored
permanently. Unlike a traditional database where you update a row, events are never changed after they
are written – they are immutable facts about what happened.

This immutability is a strength (complete audit trail, ability to replay history), but it creates a
challenge when your application evolves: old events stored in the past must still work with new code.

**Axon Framework 5 already handles many common versioning scenarios automatically** through a
mechanism called **payload conversion at handling time**: when an event handler declares the type it
expects, Axon converts the stored payload (the data content of the event) to that type on the fly.
For example, when you add a new field to an event class, old stored events simply receive a default
value for that field when they are read. You do not need to do anything.

**Transformations are for the scenarios that payload conversion cannot handle**: situations where the
stored event stream itself must change -- splitting one event into two, renaming an event type, or
restructuring a payload in a way that every handler needs to see uniformly. A transformation runs
when events are read from the event store, before any handler sees them.

---

## Clarifications

### Session 2026-05-18

- Q: When does the EventConverter convert a stored event's payload to a typed object relative to the transformation chain? → A: On-demand (lazy, pull-based) via `payloadAs()` -- Option B.
- Q: What target types are valid when an upcaster calls `payloadAs()` to access the event payload? → A: Structured representations -- typed Java classes (POJOs), `JsonNode`, and `ObjectNode`. Raw `byte[]` is not a valid target for upcasters. Events stored as `byte[]` in the event store are automatically converted to the requested structured type by the `ByteArrayToJsonNodeConverter` registered in `Jackson2Converter`. Upcasters never see or handle raw bytes; the converter infrastructure is the bridge. This keeps the separation clean: format/serialization conversion (byte[] ↔ structured type) is Converter responsibility; schema/identity change is Upcaster responsibility.
- Q: In which event-reading contexts must upcasters apply? → A: All three -- aggregate loading (event sourcing, bounded stream per aggregate), DCB reads (`SourcingCondition`-based, bounded stream across multiple aggregates for command-side consistency enforcement), and tracking processor reads (unbounded stream, all events, for projections). All three read from the same event store and must produce consistent results. If upcasters applied in some contexts but not others, a DCB decision model and a tracking processor projection would disagree on the current state of the domain for the same stored events.
- Q: Should the spec require a published Converter vs. transformation decision tree as a documentation deliverable? → A: Yes -- add as SC-007. A developer building a new AF5 system needs to know which tool to reach for when handling event schema changes. The Background section describes the boundary but a concrete decision tree with examples is a first-class documentation artifact, not an assumption.
- Q: Should the spec document the transformation retirement condition -- when it is safe to remove an upcaster from the codebase? → A: Yes -- document in Edge Cases. Without this, developers either accumulate upcasters forever or remove them too early and break replays. The retirement condition is deterministic: an upcaster may only be removed when no stored event at its target `from` version exists in any event store, including backups and cold archives.
- Q: Should the spec document what happens when a transformed event has no registered handler? → A: Yes -- document in Edge Cases as explicitly intended behavior. A transformed event with no handler is silently ignored, which is standard AF5 behavior for any unhandled event. Stating this prevents debugging confusion and makes the chain's correct operation independently testable.
- Q: How does the `from → to` registration model (FR-001) apply to splits and drops where there is no single output identity? → A: Two patterns. 1:1 transformations (structural transform, rename) use `from → to` + optional function as per FR-001. 1:N splits and 1:0 drops declare only `from` + a function that returns a list of zero or more complete output messages; each output message has its own MessageType set by the developer inside the function. The self-loop detection (FR-010) applies to 1:1 registrations only. Rename stays frictionless (no function needed). This mirrors AF4's SingleEventUpcaster / EventMultiUpcaster split and preserves FR-002's no-function guarantee for renames.
- Q: Should the version string format be constrained? → A: Yes -- semantic versioning format (major.minor.patch, e.g. `"1.0.0"`) enforced at registration time. Invalid format rejected with a clear error. Prevents silent mismatches where `"1.0"` and `"1.0.0"` are intended to be the same version but never match, breaking chaining and duplicate detection. Consistent with the existing default version `"0.0.1"` already in the spec.
- Q: Is transformation registration valid only at startup or also at runtime? → A: Startup/build-time only. Once event processing begins the chain is fixed. Runtime registration would allow a stream to be processed partially with one chain and partially with another, producing inconsistent results from the same stored event stream. Tests construct a fresh chain per test case, which startup-only naturally supports.
- Q: Should the framework emit observability signals during normal transformation chain operation? → A: Yes -- INFO level at startup when the chain is built (number of transformations registered), DEBUG level each time a transformation is applied (which transformation, which event name+version). INFO confirms correct wiring without flooding production logs; DEBUG is off by default but available for diagnosis. No public API changes required.
- Q: Should the "event name" in a transformation registration use the fully qualified name or local name only? → A: Fully qualified name required (e.g., `com.example.CourseCreated`). Consistent with how AF5 `MessageType` resolves event identity via `@Event` -- the namespace (package) + local name together form the unique identity. Using only the local name risks silent non-matches when two modules have events with the same local name. FR-001, FR-003, FR-005, and FR-010 updated to specify "fully qualified event name."
- Q: Can transformations modify event metadata, or is metadata always preserved unchanged? → A: Metadata is always preserved unchanged. Transformations may only change payload and event identity (name + version). Consistent with AF4 behavior -- `SingleEventUpcasterTest` explicitly asserts metadata is preserved after upcasting. Infrastructure metadata (correlationId, causationId, tracing headers) are written-once facts about the original message and must not be mutated during schema evolution. FR-012 updated to make this explicit.
- Q: FR-010 (conflict detection) and FR-015 (observability) had no traceable user story -- do they belong in the spec or move to the plan as technical constraints? → A: Add user stories. Both describe real developer-facing scenarios with testable acceptance criteria. US7 covers misconfiguration feedback at registration time (FR-010). US8 covers startup observability for the transformation chain (FR-015). Added to Part B.
- Q: When a transformation throws an exception, what should the framework do? → A: Propagate -- processing halts.
- Q: How are transformations registered? → A: Programmatic only for 5.2.0; annotation-based deferred until user demand justifies it.
- Q: Should snapshot upcasting ship in 5.2.0 or be deferred? → A: Deferred -- design the door open. Discard-and-replay is the correct primary strategy; snapshot upcasting is a niche escape hatch for massive aggregates. Follows the same pattern as command/query upcasting deferral.
- Q: How should the output version be specified, and how should infinite loops be prevented? → A: Explicit `from → to` registration for all transformations. The payload transform function is optional -- when absent, the payload passes through unchanged (this is the rename case, satisfying FR-002). The framework rejects registrations where `from` and `to` are identical (same name + same version) at registration time.

  The transformation calls `message.payloadAs(MyType.class)` only when it explicitly needs the typed payload.
  Non-matching events pass through the chain without any deserialization occurring.

  **Why not Option A (eager pre-conversion):** Pre-converting every event before the chain violates
  FR-013 -- events that no transformation targets would still be deserialized. Worse, if a stored event's
  type has been deleted from the codebase (the exact case a transformation exists to handle -- e.g., to
  drop or rename it), eager pre-conversion would fail before the transformation ever runs.

  **Why not Option C (transformations see raw bytes):** This directly contradicts FR-011. It recreates the
  AF4 `IntermediateEventRepresentation` problem: developers are forced to manipulate raw JSON or
  bytes, understand serializer-specific formats, and handle missing fields manually. The redesign
  exists precisely to eliminate this.

---

## User Scenarios & Testing

This section is split into three parts:

- **Part A** -- scenarios that AF5 already handles automatically. No transformation is needed. Each
  scenario has a passing test in the university-demo module that proves the behaviour works today.
- **Part B** -- scenarios that require a transformation. These are the stories that drive the new API.
- **Part C** -- scenarios that are not an upcasting problem at all. Each entry explains what to do
  instead, and why an transformation is the wrong tool.

---

## Part A: What AF5 Handles For You (No Transformation Needed)

The scenarios below are handled automatically by AF5's **payload conversion at handling time**
mechanism. When an event is read from the event store, Axon converts its stored payload to whatever
Java type the handler declares. This conversion happens per-handler, at handling time, without touching
the stored event. No transformation needed, no migration script, no changes to other handlers.

Each scenario below is verified by a test in:
`examples/university-demo/src/test/java/.../faculty/PayloadConversionCapabilityTest.java`

Each test goes through the real AF5 conversion infrastructure: a `DelegatingEventConverter` backed
by `Jackson2Converter`, with events stored as `byte[]` (the actual stored form in the event store).
Tests do NOT use raw Jackson directly -- they prove the AF5 conversion pipeline handles each case.

---

### A1 - Added a New Field

**Scenario**: You add an optional `description` field to `CourseCreated`. Old stored events do not
have this field in their JSON.

**How AF5 handles it**: When an old event is deserialized, the missing field defaults to `null`.
Handlers that use `description` simply receive `null` for old events and the real value for new ones.
No code change needed in any handler.

**Example**: `CourseCreated` gains a `String description` field. A stored event written before this
field existed deserializes cleanly -- `description` is `null`.

**Test**: `PayloadConversionCapabilityTest$AddedNewField`
`#newFieldDefaultsToNull_whenMissingFromStoredPayload`

---

### A2 - Removed a Field

**Scenario**: You remove a field from your event class (e.g., `lastName` is dropped from
`StudentEnrolledInFaculty`). Old stored events still carry the field in their JSON.

**How AF5 handles it**: The extra field in the stored JSON is silently ignored during deserialization.
Add `@JsonIgnoreProperties(ignoreUnknown = true)` to your event class to make this explicit.
No handler changes needed.

**Example**: `StudentEnrolledInFaculty` drops `lastName`. Old stored events still have
`"lastName": "Doe"` in JSON -- the new class ignores it without error.

**Test**: `PayloadConversionCapabilityTest$RemovedField`
`#removedField_isIgnoredWhenPresentInStoredPayload`

---

### A3 - Renamed a Field

**Scenario**: You rename a field in your event class (e.g., `capacity` becomes `maxCapacity`). Old
stored events have the JSON key `"capacity"`.

**How AF5 handles it**: Annotate the new field with `@JsonProperty("capacity")`. Jackson maps the
old stored key to the new field name automatically. No handler changes needed.

**Example**: `CourseCreated` renames `capacity` to `maxCapacity`. Adding
`@JsonProperty("capacity")` on `maxCapacity` means old stored events deserialize correctly.

**Test**: `PayloadConversionCapabilityTest$RenamedField`
`#renamedField_isMappedViaJsonProperty`

---

### A4 - Changed a Field to a Compatible Type

**Scenario**: You widen a field's type (e.g., `capacity` changes from `int` to `long`).

**How AF5 handles it**: Jackson converts compatible numeric types automatically during
deserialization. No annotation or handler change needed.

**Example**: `CourseCreated.capacity` changes from `int` to `long`. Stored `30` (int) deserializes
cleanly as `30L` (long).

**Test**: `PayloadConversionCapabilityTest$CompatibleTypeChange`
`#intWidenedToLong_isCoercedByEventConverter`

---

### A5 - Renamed the Java Class Only

**Scenario**: You rename the Java class (e.g., `CourseCreatedEvent` becomes `CourseCreated`) to
follow naming conventions, but the business event is the same.

**How AF5 handles it**: Axon routes events by their **MessageType** name (set via
`@Event(name = "...")`) not by the Java class name. Keep the `@Event(name = ...)` value the same as
the old class name and Axon continues to match stored events to the renamed class. The payload JSON
is byte-for-byte identical -- nothing to convert.

**Example**: `CourseCreatedEvent` is renamed to `CourseCreated`. Adding
`@Event(name = "...CourseCreatedEvent")` to the new class preserves routing. All stored events
continue to reach the handler unchanged.

**Tests**: `PayloadConversionCapabilityTest$RenamedJavaClass`
`#messageTypeResolver_usesAnnotationName_notJavaClassName`
`#payloadIsUnchanged_soEventConverterDeserializesCorrectly`

---

### A6 - Handler Wants a Different Representation

**Scenario**: One handler wants to receive the event as a typed Java class, while another handler
(perhaps a diagnostic or schema-agnostic projector) wants to receive the raw JSON as a `JsonNode`.

**How AF5 handles it**: Different handlers for the same event can declare different parameter types.
Axon converts the stored payload to whatever type each handler requests. No changes to the event
class or the event store are needed.

**Example**: `@EventHandler public void on(CourseCreated event)` receives a typed object.
`@EventHandler(eventName = "...CourseCreated") public void on(JsonNode event)` on a different
handler receives the raw JSON. Both handle the same stored event simultaneously.

**Test**: `PayloadConversionCapabilityTest$HandlerReceivesDifferentRepresentation`
`#differentHandlers_receiveTheSameStoredEvent_inDifferentRepresentations`

---

### A7 - Switched Serialization Format

**Scenario**: You migrate the serialization format of your event store (e.g., from XStream to
Jackson). Old events were written in the old format; new events are written in the new format.

**How AF5 handles it**: Configure an **EventConverter** (a component responsible for
format/serialization conversion, distinct from upcasters) to bridge the two formats. Event handlers
and event classes require no changes at all. The conversion is transparent.

**Example**: Migrating from XStream to Jackson. After configuring the `EventConverter`, all handlers
continue to receive correctly deserialized events regardless of which serializer wrote the stored
event.

**Tests**: `PayloadConversionCapabilityTest$SwitchedSerializationFormat`
`#eventClass_requiresNoChange_whenPayloadStoredAsByteArray`
`#eventClass_requiresNoChange_whenPayloadStoredAsJsonNode`

---

## Part B: When Transformations Are Needed

The scenarios below cannot be handled by payload conversion. They require registering a transformation because
the stored event stream itself must change. Each story is a distinct, independently testable use case.

---

### User Story 1 - Structural Payload Transformation (Priority: P1)

**Plain-English explanation**: Imagine you designed a `CourseCreated` event with a single `capacity`
field (e.g., `capacity: 30`). Later you realize you need both a minimum and a maximum capacity. You
change the event class to use `minCapacity` and `maxCapacity`. But thousands of old events are already
stored with just `capacity`. Every part of your application that reads those events -- **projections**
(components that listen to events and build read models, such as a database view of all courses) and
**aggregates** (the domain objects that rebuild their state by replaying their own event history) --
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
   **Then** all handlers receive the transformed payload -- no handler needs to implement its own
   conversion.

3. **Given** an event that does not match the transformation's target name and version,
   **When** it is read from storage,
   **Then** it passes through the chain unchanged.

4. **Given** the same stored `CourseCreated` v1.0.0 event and the same registered transformation,
   **When** the event is read in three different contexts -- (a) loading an event-sourced aggregate,
   (b) a DCB read enforcing command-side consistency, and (c) a tracking processor building a
   projection --
   **Then** all three contexts receive the identical transformed `CourseCreated` v2.0.0 payload.
   The reading context MUST NOT affect the transformation outcome.

5. **Given** a stored `CourseCreated` event that carries no version (written before versioning was
   introduced to the system),
   **When** a transformation registered for `com.example.CourseCreated` at version `0.0.1` is active,
   **Then** the transformation applies to that event -- the framework treats the absence of a version
   as version `0.0.1`, the AF5 default.

---

### User Story 2 - Event Identity Change / Rename (Priority: P2)

**Plain-English explanation**: Imagine you named an event `CourseOpened` during early development, but
after talking to domain experts you realize the correct business term is `CourseCreated`. You rename
the class in your code. But thousands of events are already stored under the old name `CourseOpened`.
Axon identifies events by their name, so a handler registered for `CourseCreated` will not receive
events stored as `CourseOpened` -- unless you declare the mapping.

Since the payload (the data inside the event) has not changed -- only the name changed -- you should
not need to write a transformation function. You declare the mapping in one place using the same
`from → to` registration as any other transformation, simply omitting the payload function:
"events stored as `CourseOpened` v1.0.0 are the same thing as `CourseCreated` v1.0.0."
The framework passes the payload through unchanged. No separate rename API is needed.

**Why this priority**: Event renames are common during domain modeling refinements. They should
require the least possible effort since no data actually changes -- only the label changes.

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

### User Story 3 - Event Splitting (Priority: P3)

**Plain-English explanation**: A good practice in event design is to avoid event names containing "and"
(e.g., `StudentEnrolledAndCourseUpdated`), because "and" signals that two distinct things happened and
were bundled together. Imagine you have exactly this problem: a single stored event that records two
facts. You want to split it into two proper events: `StudentEnrolled` and `CourseCapacityUpdated`.

Every time this stored event is read from the event store -- whether to rebuild a projection or to
replay an aggregate's history -- the transformation must produce both replacement events in the correct order.
Handlers and projections that were written for the individual events will then work correctly.

**Why this priority**: Splitting an event changes the shape of the event stream itself. This cannot be
done at the handler level -- every handler would need its own split logic. Upcasting is the right tool
because the transformation is applied once, transparently, for all consumers.

**Independent Test**: Register a splitting transformation for `StudentEnrolledAndCourseUpdated` v1.0.0
that produces `StudentEnrolled` followed by `CourseCapacityUpdated`. Replay the stream and verify:
the original event is gone, both output events appear in order, each handler receives only the event
it is registered for.

**Acceptance Scenarios**:

1. **Given** a stored `StudentEnrolledAndCourseUpdated` event at version 1.0.0,
   **When** the event stream is read with the splitting transformation registered,
   **Then** two events appear in its place -- `StudentEnrolled` first, then `CourseCapacityUpdated` --
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
   and the subsequent transformation then applies to the `StudentEnrolled` output event -- both output
   events from the split flow through the remainder of the chain.

---

### User Story 4 - Event Dropping (Priority: P4)

**Plain-English explanation**: Imagine a `SystemHeartbeat` event was accidentally stored in the event
store. It carries no business meaning -- it was a monitoring artifact that should never have been
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
   **Then** the processor resumes after the dropped `SystemHeartbeat` -- it does not reprocess it.
   The stream position (tracking token) advances past dropped events as if they had been handled.

---

### User Story 5 - Snapshot Upcasting (Deferred)

Snapshot upcasting is a valid use case but is deferred from 5.2.0. See Part C for the full
rationale, the hook point, and the design constraints that keep this option open for a future release.

The existing discard-and-replay fallback in `StoreBackedSnapshotter` remains the correct primary
strategy and is preserved unchanged.

---

### User Story 6 - Chaining Transformations Across Multiple Versions (Priority: P2)

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
   version 1.0.0), the v1→v2 transformation applies, and the output is version 2.0.0 -- demonstrating
   that registration order is the chain order and incorrect ordering leads to incomplete
   transformation, which is the developer's responsibility to get right.

### User Story 7 - Misconfiguration Feedback at Registration Time (Priority: P1)

**Plain-English explanation**: A developer registers two transformations that both target the same
event name and version -- perhaps by copy-pasting a registration block and forgetting to change the
`from` value. Or they register a transformation where the output identity is identical to the input
(same name and version), which would cause an infinite loop at runtime. Or they provide a version
string like `"1.0"` instead of `"1.0.0"`.

Without early detection, these mistakes produce no error at startup but cause silent failures at
runtime: events are double-processed, the application loops, or a transformation never fires because
its version string never matches. These bugs are hard to reproduce and hard to diagnose.

The framework MUST catch all three classes of misconfiguration at registration time -- before any
event is read from the store -- and surface a clear error that names the conflicting or invalid
registration, so the developer can fix it immediately without debugging a live system.

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

5. **Given** a correctly configured transformation chain that has already started processing events,
   **When** an attempt is made to register an additional transformation at runtime,
   **Then** the framework rejects the registration immediately with a clear error stating that the
   chain is immutable once event processing has begun, and the chain remains unchanged.

6. **Given** a transformation whose function throws an exception when applied to a specific event,
   **When** that event is read from the event store,
   **Then** the framework propagates the exception immediately to the caller -- the aggregate load,
   DCB read, or tracking processor -- halting processing. The exception clearly identifies which
   transformation failed and the name, version, and stream position of the event that triggered it.
   The framework MUST NOT silently skip the failed event or log-and-continue.

---

### User Story 8 - Startup Observability for the Transformation Chain (Priority: P2)

**Plain-English explanation**: A developer deploys a new version of the application with two
transformations registered. The application starts, processes events, and the developer wants to
confirm that both transformations were actually picked up by the framework -- without reading
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

## Part C: Out of Scope and Non-Upcasting Scenarios

This section documents scenarios that are either deferred to a later release, or are not an
upcasting problem at all. Knowing what NOT to reach for a transformation for is as important as
knowing when to use one.

The entries fall into two categories:

- **Deferred**: upcasting is the right concept, but the scenario is out of scope for 5.2.0 for
  reasons of scope management or open design questions. The API is designed to support these without
  breaking changes.
- **Wrong tool**: a transformation would give the wrong answer or corrupt the audit trail. The correct
  solution is a different pattern entirely.

---

### Out of Scope: N-to-1 Merge (Deferred)

**Plain-English explanation**: Imagine you have two separate stored events -- `ItemAddedToCart`
followed by `CartCheckedOut` -- and you realize they should have been a single `OrderCompleted` event.
You want to combine (merge) multiple stored events into one output event.

**Why this is NOT in scope for 5.2.0:**

Merging N events into 1 requires the framework to "remember" earlier events while reading later ones.
Think of it like a shopping list: you cannot add up the totals until you have seen every item. In
event sourcing, this introduces two problems that cannot be solved cleanly in this release:

1. **Where does the memory live?** Events are read in two very different contexts in Axon:
   - When loading one **aggregate** (e.g., one specific Course), the framework reads only that
     aggregate's own events as a **bounded stream** (a finite, ordered sequence that ends when all
     the aggregate's events have been read). Memory that resets per aggregate is predictable here.
   - When a **tracking processor** (a background component that continuously reads all events from
     the entire event store to update projections) runs, it processes an **unbounded stream** (a
     continuous, never-ending sequence of events from every aggregate in the system). Memory
     spanning aggregate boundaries here would silently mix up data from completely unrelated
     entities -- causing bugs that are nearly impossible to reproduce or debug.

   In Axon Framework 4, **context-aware upcasters** (upcasters that carry state from one event to
   the next) had exactly this problem: the same transformation ran in both contexts but its memory scope
   was inconsistent between them. The team has explicitly chosen not to repeat this in AF5.

2. **The framework's streaming model (`MessageStream`) does not support it today.** Axon 5
   processes events as a continuous `MessageStream` -- a push-based flow where each event is
   processed in turn. That stream has no built-in concept of "group these N items together before
   moving on." Adding windowing or grouping to `MessageStream` would require significant changes
   to the core streaming infrastructure, well beyond the scope of this feature.

**What to do instead**: For most cases where N-to-1 seems necessary, a stateful projection (a
component that accumulates events over time and produces a combined result) is the right tool.
For cases where the event stream boundaries themselves must change, Gregory Young's "Copy and
Replace" pattern (reading old events, writing new ones to a fresh stream) solves the problem
completely, at the cost of a one-time migration.

**When to revisit**: After collecting concrete user cases that cannot be solved with the alternatives
above, the team will define a clear scope for context-aware upcasting in a future release.

---

### Out of Scope: Moving Data Between Events (Deferred)

**Plain-English explanation**: Imagine `OrderPlaced` contains a `discountCode` field. Later, you
realize that `discountCode` actually belongs on the `PaymentProcessed` event that follows it. You want
a transformation that reads `discountCode` from the first event and copies it into the second event
as they are read from storage.

**Why this is NOT in scope for 5.2.0:**

Moving data from one event to a later event requires a **context-aware transformation** -- one that
carries state (a "context") from one event to the next as it processes the stream. This is the same
memory problem described in the N-to-1 section above, applied to individual fields rather than
whole events.

The ambiguity of what "context" means in a framework that reads events both as bounded streams (per
aggregate) and as unbounded streams (via tracking processors across the entire event store) makes
this impossible to implement with consistent, predictable behavior. Axon Framework 4 had
context-aware upcasters and developers regularly encountered subtle, hard-to-reproduce bugs because
the context scope behaved differently depending on whether the read was triggered by event sourcing
or by a tracking processor.

**What to do instead**: If a field needs to be on a different event, the cleanest solution is a
one-time "Copy and Replace" migration: read all existing events, write corrected events to a fresh
stream, and switch the application over. This is a one-time migration, not ongoing upcasting.

**When to revisit**: Once Axon 5's streaming model and consistency boundaries are fully stabilized,
the team will revisit whether a well-scoped context-aware transformation is feasible.

---

### Not an Upcasting Problem: Semantic Meaning of a Field Changed Silently

**Scenario**: A field's name stays the same but its meaning changes. For example, `capacity` in
`CourseCreated` used to mean "total available seats" but now means "available seats remaining after
cancellations." The field name, type, and value range all look identical -- only what the number
represents has changed.

**Why a transformation is the wrong tool**: A transformation modifies the data. Here the data itself has not
changed -- the interpretation has. If you write a transformation to "fix" this, you corrupt your audit
trail: old events that were correct under the old meaning would be silently altered to appear as if
they carried a different number all along.

**Gregory Young's rule**: Never silently change the semantic meaning of a field. A value that now
means something different from what it meant when it was written is not a new version of the same
event -- it is a different event type.

**What to do instead**: Create a new event type with a name that makes the new meaning explicit
(e.g., `CourseOpened` with an `availableSeats` field). Keep `CourseCreated` with `capacity` meaning
what it always meant. Introduce a migration event or a compensating event to bridge the two.

**Example**: `CourseCreated.capacity` used to mean total seats. Now it should mean remaining seats.
Rather than changing the meaning of `capacity`, create `CourseRescheduled` with an `availableSeats`
field that reflects the new concept explicitly.

---

### Not an Upcasting Problem: New Event Cannot Be Derived from the Old One

**Scenario**: An event has changed so fundamentally that the new version cannot be computed from the
old stored data. For example, `CourseCreated` was originally modeled around rooms and time slots, but
is now modeled around a completely new scheduling system with different concepts, different fields,
and different business rules.

**Why a transformation is the wrong tool**: A transformation must produce the new event from the old data. If
the old data does not contain enough information to construct the new event correctly, the transformation
would be forced to invent or default values -- producing events that misrepresent what actually
happened. This destroys the audit trail and violates the immutability principle.

**Gregory Young's rule**: A new version of an event MUST be derivable from the old version. If it
cannot be derived, it is not a new version -- it is a genuinely new business event. Give it a new,
distinct name that reflects the new concept.

**What to do instead**: Introduce a new event type with a name that reflects the new concept (e.g.,
`CourseScheduled` under the new model). Run both event types in parallel during migration. Old
projections continue to consume `CourseCreated`; new projections consume `CourseScheduled`.
Use Copy and Replace as a last resort if the old stream must be fully replaced.

**Example**: `CourseCreated` was structured around rooms and manual time slots. The new scheduling
model is built around recurring templates and automated slot generation -- a fundamentally different
concept. This is not "CourseCreated v2.0.0" -- it is `CourseScheduled`, a new business event.

---

### Deferred to #746: Command Upcasting (and Downcasting)

**The real-world driver -- rolling deployments**: Unlike events (which are stored permanently and
read long after they were written), commands are sent live between running services. The versioning
challenge arises specifically in **rolling deployments**, where two versions of an application run
concurrently: for example, service v2 sends commands to a mix of v1 and v2 receivers.

In this situation there are two distinct problems:

- **Upcasting (old sender, new receiver)**: Service v1 sends an `EnrollStudent` command without
  `enrollmentReason`. Service v2's command handler expects `enrollmentReason` to be present. The
  receiver needs a transformation that fills in a default for the missing field.
- **Downcasting (new sender, old receiver)**: Service v2 sends an `EnrollStudent` command with
  `enrollmentReason` included. Service v1's command handler does not know this field. Ideally the
  sender would strip the extra field before dispatching, so v1 does not choke on unknown data. This
  is called **downcasting** -- transforming a newer-format message to an older format for backward
  compatibility.

Downcasting is the inverse of upcasting: where a transformation converts old-to-new at the receiver,
a downcaster transforms new-to-old at the sender. Both were discussed during the design of issue
#80 by Allard Buijze and the team. Both are relevant in distributed rolling deployments.

**Why this is NOT in scope for 5.2.0:**

Commands, events, and queries in AF5 all share the same `Message` contract (name, version,
payload, metadata) and the same converter hierarchy. The upcasting mechanism generalises naturally
across all message types, and the transformation interface is deliberately built on `Message` rather than
`EventMessage` so that command and query support can be added later without a breaking change.

It is deferred because:

- The primary pain point today is event upcasting (events are stored indefinitely and accumulate
  across years; commands and queries are transient). Delivering event upcasting first keeps the
  scope focused.
- Command upcasting introduces pipeline placement questions (at what point in the command dispatch
  pipeline does the transformation run?) and downcasting introduces sender-awareness questions (how does
  the sender know what version the receiver understands?) that require their own specification.
- The deferred decision is about scope and timing, not technical feasibility.

**API design constraint**: The transformation interface defined in this release MUST operate on `Message`,
not on `EventMessage`. This is the guarantee that command and query support can be added in #746
without changing the interface or breaking existing event transformations.

**Tracked as**: issue #746 ("Allow registration of Upcasters on all components"), which is listed
as a parent of issue #3597 and is on the 5.2.0 milestone for design consideration.

**When to deliver**: As a follow-on once the event upcasting API is stable and the team has
collected concrete rolling-deployment command/query versioning cases from users.

---

### Deferred to #746: Query Upcasting

**The real-world driver -- rolling deployments**: The same rolling-deployment scenario applies to
queries. Service v2 sends a `FindCoursesByFaculty` query with a new `includeArchived` filter
parameter. Service v1's query handler does not know this field.

- **Upcasting at the query handler**: the receiver fills in `includeArchived = false` for queries
  that do not include the field.
- **Downcasting at the query sender**: the sender strips `includeArchived` before dispatching to
  an old receiver that cannot handle it.

**Why this is NOT in scope for 5.2.0:**

The same reasoning as command upcasting above. The `Message`-based transformation interface ensures
this can be added in issue #746 without breaking the event upcasting API introduced here.

**Tracked as**: issue #746.

---

### Deferred: Snapshot Upcasting

**The use case**: An aggregate with a large event history (tens of thousands of events) uses
snapshots to avoid expensive full replays. When the aggregate's state schema changes -- e.g.,
`capacity` is renamed to `maxCapacity` -- stored snapshots at the old schema cannot be deserialized.
The current fallback discards the snapshot and replays all events from the beginning, which for
large aggregates can take seconds to minutes.

Snapshot upcasting would let a developer register a `Snapshot → Snapshot` transformation for a
specific snapshot version, so the framework can transform the old snapshot into the current format
and avoid the expensive replay.

**Why not in 5.2.0**:

Gregory Young's own guidance is that the normal way to handle snapshot schema changes is to
**rebuild the snapshot from events** -- not to upcast it. Snapshots are a cache, not a source of
truth. The event stream is always the source of truth; a snapshot is just an optimization. Discarding
a stale snapshot and replaying is correct behavior, not a fallback of last resort.

Snapshot upcasting is only justified when all three of these are simultaneously true:
1. The aggregate has a very large event history (tens of thousands of events or more).
2. A full replay from the discarded snapshot position takes an unacceptable amount of time.
3. The aggregate's state schema changed and stored snapshots are incompatible.

This is a rare combination. Most teams deploying AF5 will not hit all three conditions at once.
And even when they do, a one-time migration script that re-creates snapshots in the new format
is often simpler than ongoing snapshot upcasting infrastructure.

Delivering event upcasting first (the far more common need) keeps 5.2.0 focused.

**What keeps the door open**:

The hook point is already identified: `StoreBackedSnapshotter.load()` has an explicit version
mismatch branch (currently logs a warning and returns `null`). A future snapshot upcasting feature
would slot in exactly there, with no changes to the event upcasting API introduced in this release.

The snapshot transformation interface would operate on `Snapshot` objects directly (not on `Message`)
-- a simple `Snapshot → Snapshot` function registered by entity type and snapshot version. It does
not need to share an interface with event transformations because snapshots are never split, never
dropped, and carry no event identity (name + version together) -- only a version string.

**Acceptance scenarios (preserved for future spec)**:

1. **Given** a stored aggregate snapshot at version 1.0.0 and a snapshot transformation registered,
   **When** the aggregate is loaded,
   **Then** the framework applies the transformation and uses the resulting snapshot without replaying
   all events from the beginning.

2. **Given** no snapshot transformation registered for a version mismatch,
   **When** the aggregate is loaded,
   **Then** the framework discards the snapshot and replays all events (existing behavior -- no
   regression).

3. **Given** a snapshot transformation registered for version 1.0.0,
   **When** a snapshot at a different version (e.g., 2.0.0) is read,
   **Then** the transformation does not apply.

---

### Deferred: Annotation-Based Transformation Registration

In 5.2.0, transformations are registered programmatically only. An annotation-based model (e.g.,
`@Upcaster` on methods, discovered at startup) is intentionally deferred.

**Why not now**: The spec's primary AF4 complaint is that Spring Bean-based registration made chain
order unpredictable. Introducing an annotation-discovery mechanism in the same release risks
reintroducing that problem through a different path. Programmatic registration keeps the ordering
guarantee trivially enforceable: the order of the API calls is the chain order, with no scanning,
no priority attributes, and no framework magic.

**When to add**: If enough users request annotation-based registration after 5.2.0 ships, it can be
layered on top of the programmatic API without any breaking change -- annotations would simply be
syntactic sugar that calls the same registration API in a defined discovery order. The programmatic
API introduced here is the stable foundation that makes that future extension safe.

---

### Edge Cases

**Registration-time failures** (problems the framework must catch at startup, not during event processing):

- Two transformations with the same `from` identity (same name + version): duplicate targeting.
  The framework must detect and report this clearly at registration time.
- A transformation where `from` and `to` are identical (same name AND same version): guaranteed
  infinite loop. The output re-matches the same transformation on the next pass. Rejected at
  registration time with a clear error pointing at the `from` and `to` values.
- An attempt to register a transformation after event processing has started: rejected immediately
  with a clear error. The chain is immutable once processing begins; late registration would cause
  different events in the same stored stream to be processed with different chains, producing
  inconsistent results.
- A null, empty, or non-fully-qualified event name (missing namespace) in either `from` or `to`,
  or a null, empty, or non-semver version: rejected at registration time with a clear configuration
  error identifying the invalid value. Valid name form: `namespace.LocalName` (e.g.,
  `com.example.CourseCreated`). Valid version format: major.minor.patch (e.g. `"1.0.0"`). The
  default version `"0.0.1"` is always valid. Discovered from `EventTypeUpcasterTest` in the AF4
  test suite.

**Chain composition**:

- What if the output of one transformation is itself subject to another registered transformation?
  Transformations must chain correctly: the output of step A flows into step B.
- What if upcasters are registered in the wrong order (e.g., v2→v3 registered before v1→v2)?
  The chain produces no error -- it silently under-converts (a v1 event reaches the v2→v3 transformation
  as v1, does not match, passes through as v1). This is the user's responsibility. The framework
  guarantees chain order is registration order; it cannot detect logical ordering mistakes.
  Discovered from `GenericUpcasterChainTest#upcastingResultOfOtherUpcasterOnlyWorksIfUpcastersAreInCorrectOrder`.
- What if a splitting transformation produces events of types that are themselves subject to
  further transformations? Each output event must flow through the remaining chain.

**Envelope field preservation**:

- What must happen to the event's envelope fields (aggregate type, aggregate identifier, tracking
  token, sequence number) when a transformation transforms an event? These fields MUST be preserved
  unchanged through any 1-to-1 transformation. In a 1-to-N split, every output event MUST carry
  the same aggregate type, aggregate identifier, tracking token, and sequence number as the
  original stored event. Transformations are only allowed to change the payload, the MessageType
  name, and the version -- not the event's storage envelope.
  Discovered from `SingleEventUpcasterTest#upcastingDomainEventData` and
  `EventMultiUpcasterTest#upcastingDomainEventData`.

**Lazy evaluation**:

- What if an event does not match the transformation's target name and version? The framework MUST pass
  it through without calling any deserialization on its payload. Upcasters act as a filter first:
  if `canUpcast` returns false, the event's payload data must never be read. This is both a
  performance requirement (avoid unnecessary deserialization of every non-matching event in a
  large stream) and a correctness requirement (the payload format may be incompatible with the
  transformation's expected type).
  Discovered from `SingleEventUpcasterTest#ignoresUnknownType` and
  `EventMultiUpcasterTest#upcasterIgnoresWrongEventType`.

**Streaming position after event drop**:

- What happens to the stream's position tracking when a transformation drops (suppresses) events?
  The streaming position -- the **tracking token** (a marker that tells a background processor
  where it is in the event store, so it can resume after a restart) -- MUST advance past dropped
  events. If a transformation drops the last event in a stream, the position must still reflect that
  the dropped event was read from storage. A processor that drops events must not re-process them
  on the next restart.
  Discovered from `EventStreamUtilsTest#domainEventStream_lastSequenceNumberEqualToLastProcessedEntryAfterIgnoringLastEntry`.

**Exception handling**:

- If a transformation throws an exception, the framework MUST propagate it immediately -- processing
  halts and the exception surfaces to the caller (the event store read, aggregate load, or tracking
  processor). Silently skipping or logging-and-continuing is explicitly rejected: a failed
  transformation leaves the output event undefined, and delivering corrupted or missing state to
  aggregates and projections without a visible signal is worse than a hard failure. The exception
  MUST clearly identify which transformation failed and for which event (name, version, and position
  in the stream) with enough information to diagnose the problem quickly.

**Unversioned legacy events**:

- What if a stored event carries no version (events written before versioning was introduced)?
  The framework treats these as version "0.0.1" (the default version in AF5's `MessageType`).
  Developers who need to target these legacy events register their transformation for version "0.0.1".

**Transformed event with no registered handler**:

- What if the transformation chain produces an event version that no handler is registered for?
  The transformed event is silently ignored -- this is standard AF5 behavior for any event with no
  registered handler, not a transformation failure. The transformation ran correctly; the absence of a handler
  is a separate, independent concern. Developers debugging a scenario where events appear to
  disappear should verify two things independently: (1) that the transformation chain ran and produced
  the expected output version, and (2) that a handler is registered for that output version.
  The SC-005 university demo examples serve as reference for proving both.

**Transformation retirement**:

- When is it safe to remove a transformation from the codebase? A transformation may only be removed when
  no stored event at its target `from` version exists in any event store -- including backups, cold
  archives, and disaster-recovery copies. Removing a transformation while old-format events still exist
  anywhere will cause replays, aggregate loads, DCB reads, and tracking processor reads to see the
  original unupcasted format, silently breaking projections and aggregate state reconstruction
  without any framework-level error. The safe retirement sequence is: (1) confirm all event stores
  including backups contain no events at the `from` version, then (2) remove the transformation.
  Upcasters that have never been deployed to production can be removed freely.

## Requirements

### Functional Requirements

- **FR-001**: For 1:1 transformations (structural payload change or rename), developers MUST be able
  to register by declaring both the input identity (`from`: fully qualified event name + version) and
  the output identity (`to`: fully qualified event name + version), together with an optional payload
  transformation function. The fully qualified event name is the namespace (Java package) combined
  with the local name (e.g., `com.example.CourseCreated`), matching how `MessageType` resolves event
  identity via `@Event`.
  The framework applies the function to produce the output payload; if no function is supplied, the
  payload passes through unchanged. The framework MUST reject any registration where `from` and `to`
  are identical (same name AND same version), as this guarantees an infinite loop.
  _Traces to: US1 (structural transform needs from→to + function), US2 (rename needs from→to without function)._
- **FR-002**: Developers MUST be able to declare an event rename (old name + version to new name
  and/or version) without writing a payload transformation function. This is the natural degenerate
  case of FR-001: supply `from` and `to` with different names or versions, omit the function.
  The payload is preserved byte-for-byte. No special rename API is required -- the same
  `from → to` registration serves both structural transformations and pure renames.
  _Traces to: US2 (rename without a payload function is the primary scenario)._
- **FR-003**: For 1:N splits and 1:0 drops, developers MUST be able to register by declaring only
  the input identity (`from`: fully qualified event name + version) together with a function that returns a list of
  zero or more complete output messages. Each output message in the list carries its own MessageType
  (name + version) set by the developer inside the function. The framework applies no `to` constraint
  on this pattern -- the function has full control over the identity and payload of every output event.
  Self-loop detection (FR-010) does not apply to this pattern since there is no declared `to`.
  _Traces to: US3 (split: from + list-returning function), US4 (drop: from + function returning empty list)._
- **FR-004**: The order in which transformations are registered MUST be the order in which they are
  applied. The framework MUST NOT reorder transformations based on any implicit or explicit priority
  mechanism. Registration is programmatic (calling a framework API) and is valid only during
  application startup or build time -- the chain is immutable once event processing begins. Any
  attempt to register a transformation after event processing has started MUST be rejected immediately
  with a clear error stating that the chain is locked.
  Annotation-based registration is not supported in this release (see out-of-scope section).
  _Traces to: US6 (correct chain order is what makes version-hop chaining work), US1 (startup-only registration is the prerequisite for all transformations to apply consistently), US7 scenario 5 (late registration rejected with clear error)._
- **FR-005**: Each registered transformation MUST target exactly one specific fully qualified event
  name and version combination. A transformation MUST NOT apply to events it was not explicitly
  registered for. Matching is exact: both the fully qualified name and the version must match the
  stored event's `MessageType` precisely.
  _Traces to: US1 scenario 3 (non-matching events pass through unchanged), US6 (version-exact matching is what routes v1 to v1→v2 and v2 to v2→v3, not both)._
- **FR-006**: Transformations MUST be pure: given the same input event, a transformation MUST always
  produce the same output. Transformations MUST NOT call external services, read from databases,
  or depend on the current time or any other external state.
  _Traces to: US1 scenario 4 (all three reading contexts produce identical output -- only guaranteed if the transformation is pure), US6 scenario 1 (chaining always produces the same final version regardless of when or how often it runs)._
- **FR-007**: When events pass through the transformation chain, the output of each transformation
  MUST flow into the next transformation as input (chaining). This applies to all output events,
  including every event produced by a 1:N split: each event in the split's output list MUST
  continue through the remaining transformations in the chain independently.
  _Traces to: US6 (the entire chaining story depends on output-as-input composition; without this, v1→v2 and v2→v3 would not compose), US3 scenario 4 (split output events flow through the remainder of the chain)._
- **FR-008**: (Deferred) Snapshot upcasting is out of scope for 5.2.0. The framework MUST preserve
  the existing discard-and-replay fallback behavior unchanged: when a stored snapshot's version does
  not match the current entity version, the snapshot is discarded and all events are replayed from
  the beginning. The hook point in `StoreBackedSnapshotter` is identified for a future release.
  _Traces to: US5 (deferred snapshot upcasting; the no-regression requirement comes from the deferral decision)._
- **FR-009**: (Retained from existing behavior) The discard-and-replay fallback MUST remain the
  default when no snapshot transformation is registered. This is the correct primary strategy, not
  a workaround -- snapshots are a cache and can always be regenerated from the event stream.
  _Traces to: US5 (the discard-and-replay fallback is the existing behavior US5 defers to)._
- **FR-010**: The framework MUST detect and report the following conflicts at registration time,
  not during event processing:
  - Two transformations with the same `from` identity (same name + version) -- duplicate targeting.
  - A transformation where `from` and `to` are identical (same name AND same version) -- guaranteed
    infinite loop: the output would immediately re-match the same transformation on the next pass.
  - A version string in `from` or `to` that does not conform to semantic versioning format
    (major.minor.patch, e.g. `"1.0.0"`) -- rejected with a clear error identifying the invalid value.
  _Traces to: US7 (all four acceptance scenarios in US7 correspond directly to the conflict classes listed here)._
- **FR-011**: Transformations MUST operate on event data as structured, typed objects. Developers
  MUST NOT need to work with raw bytes, XML documents, or other serialized binary formats. The
  framework provides an on-demand conversion mechanism: a transformation requests the typed payload
  by specifying the target type, and the EventConverter resolves it at that point. The EventConverter
  does NOT pre-convert all events before the chain runs. Valid target types are typed Java classes
  (POJOs), `JsonNode`, and `ObjectNode`. Raw `byte[]` is not a valid target for transformation payloads --
  events stored as `byte[]` in the event store are automatically bridged to the requested structured
  type by the registered ContentTypeConverter chain (e.g., `ByteArrayToJsonNodeConverter`), invisible
  to the transformation author.
  _Traces to: US1 (developer writes a transformation function that reads and produces typed payload objects), US3 (split function accesses the original payload to derive two new payloads), US4 (drop function may inspect the payload to decide whether to suppress)._
- **FR-012**: The event envelope -- aggregate type, aggregate identifier, tracking token, and sequence
  number -- MUST be preserved unchanged through any transformation. Event metadata (including
  infrastructure fields such as correlationId, causationId, and tracing headers) MUST also be
  preserved unchanged; transformations MUST NOT modify metadata. For a 1-to-N split, ALL output
  events MUST carry the same envelope and metadata as the original stored event. Transformations
  MUST only change the payload and the event identity (name and version).
  _Traces to: US1 scenario 1 (transformed event carries correct payload; envelope is unchanged), US3 scenario 1 (split output events are independent and complete -- they inherit the envelope of the original)._
- **FR-013**: The framework MUST NOT deserialize an event's payload unless that event matches the
  target name and version of at least one registered transformation. Non-matching events MUST pass
  through the transformation chain without triggering deserialization. This is enforced by the
  on-demand model: payload conversion only occurs when the transformation explicitly requests it,
  never eagerly.
  _Traces to: US1 scenario 3 (events that do not match pass through the chain unchanged -- without being deserialized), US4 scenario 2 (non-heartbeat events in the same stream pass through untouched)._
- **FR-014**: The transformation chain MUST be applied consistently across all three event-reading
  contexts: aggregate loading (event sourcing, bounded stream per aggregate), DCB reads
  (`SourcingCondition`-based bounded stream across multiple aggregates for command-side consistency
  enforcement), and tracking processor reads (unbounded stream across the full event store for
  projections). All three contexts read events from the same event store; a stored event MUST
  produce the same transformed output regardless of which reading context triggered the read.
  Inconsistent application -- where one context sees transformed events and another sees the original
  stored format -- is explicitly prohibited.
  _Traces to: US1 scenario 4 (explicitly tests all three reading contexts and requires identical output from each)._
- **FR-015**: The framework MUST emit observability signals at two levels:
  - **INFO** at startup when the transformation chain is built: the number of registered
    transformations and their `from` identities, giving immediate confirmation that the chain is
    wired correctly without flooding production logs.
  - **DEBUG** each time a transformation is applied: which transformation ran, and the name and
    version of the event it was applied to. This level is off by default in production but available
    when diagnosing whether a transformation ran, was skipped (non-matching event), or was never
    registered correctly.
  No public API changes are required to support this -- logging is a framework-internal concern.
  _Traces to: US8 (all three acceptance scenarios in US8 map directly to the two log levels specified here)._
- **FR-016**: The streaming position (tracking token) MUST advance past dropped events. When a
  transformation drops an event (returns an empty list), the framework MUST record that the event
  was read and advance the position marker accordingly. A tracking processor that restarts MUST NOT
  reprocess any event that was dropped in a previous run. The drop is transparent to position
  tracking: the stream advances as if the event had been handled normally.
  _Traces to: US4 scenario 3 (processor restarts after dropping a SystemHeartbeat and does not reprocess it)._
- **FR-017**: If a transformation function throws an exception, the framework MUST propagate it
  immediately to the caller -- the event store read, aggregate load, DCB read, or tracking processor
  invocation. Processing halts. Silently skipping the failed event or logging-and-continuing is
  explicitly prohibited: a failed transformation leaves the output event undefined, and delivering
  corrupted or absent state to downstream consumers without a signal is worse than a hard failure.
  The propagated exception MUST identify which transformation failed and the name, version, and
  stream position of the event that triggered the failure.
  _Traces to: US7 scenario 6 (transformation throws during event read; caller receives propagated exception with clear context)._
- **FR-018**: Events stored without an explicit version MUST be treated by the framework as carrying
  version `0.0.1` -- the AF5 default version in `MessageType`. Transformation registrations that
  target version `0.0.1` apply to these legacy events. No special API is required; the developer
  registers for `0.0.1` exactly as they would for any other version.
  _Traces to: US1 scenario 5 (a stored event with no version is matched by a transformation registered for version 0.0.1)._

### Key Entities

- **Event transformation**: A single unit of logic that targets one event type at one
  version. "Transformation" is the canonical term used throughout this spec. Two patterns exist:
  (1) **1:1** -- declares `from` and `to`
  identity with an optional payload function; covers structural changes and renames. (2) **1:N/1:0**
  -- declares only `from` with a function returning a list of zero or more complete output messages;
  covers splits and drops. Each transformation has exactly one reason to change. Think of it as a simple
  recipe: "given this event in this format, produce these events in this format."
- **Transformation chain**: The ordered sequence of registered transformations applied when reading
  events from storage. Order is determined entirely by registration sequence -- first registered
  runs first.
- **Snapshot transformation**: (Deferred to a future release) A unit of logic that would convert
  a stored aggregate snapshot from an old format to the current format, avoiding a full event replay
  for large aggregates. The hook point exists in `StoreBackedSnapshotter` but the API is not defined
  in this release. The discard-and-replay fallback remains the correct primary strategy.
- **MessageType / Event identity**: Every event has a **MessageType** -- the combination of a
  fully qualified name (namespace + local name, e.g., `com.example.CourseCreated`) and a version
  (e.g., `1.0.0`). Both components are always present; there is no concept of a "versionless" event
  in AF5. Events stored before versioning was introduced are assigned the default version `0.0.1`
  by the framework. Transformation registration uses the fully qualified name in both `from` and
  `to` -- using only the local name (e.g., `CourseCreated`) will silently fail to match stored
  events whose `MessageType` includes the namespace.

## Success Criteria

### Measurable Outcomes

- **SC-001**: A developer can implement and register a complete structural event transformation using
  fewer steps than the equivalent implementation required in Axon Framework 4.
- **SC-002**: 100% of registered transformations are applied in the exact order they were registered,
  verifiable across all four in-scope use cases.
- **SC-003**: (Deferred) Snapshot upcasting is out of scope for 5.2.0. The existing discard-and-replay
  fallback behavior is preserved and remains the correct primary strategy for snapshot version mismatches.
- **SC-004**: A conflict between two transformations targeting the same event name and version is
  detected and reported before any event is processed (at startup or registration time).
- **SC-005**: All four in-scope use cases -- structural transform, rename, split, drop -- are
  independently demonstrated with running examples in the university demo application.
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

## Assumptions

- The primary actor is an application developer building an event-sourced system with Axon
  Framework 5. They may be new to Axon but are comfortable with Java and understand basic
  event-sourcing concepts.
- Transformations apply at event read time, not at write time. Events already stored in the event
  store are never modified.
- Events stored without an explicit version are assigned the default version `0.0.1` by the
  framework. Transformations that need to target these legacy events use `0.0.1` as the version.
- The scope is limited to events. Snapshot upcasting and command/query upcasting are both deferred
  to future releases (snapshot upcasting to a future release; commands/queries tracked as issue #746).
- N-to-1 event merging and moving data between events (context-aware transformations) are
  out of scope for this release. See the out-of-scope stories above for full rationale.
- The university demo application (plain Java, no Spring) is the target for demonstrating all four
  in-scope use cases (structural transform, rename, split, drop). Spring Boot integration is a follow-on concern.
