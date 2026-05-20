# Event Upcasting API — Spec Summary

**Feature**: Axon Framework 5.2.0, issue #3597
**Branch**: `feature/3597/upcasting-api`

## What problem does it solve?

Event-sourced systems store events permanently and immutably, but applications evolve. When schemas change, old stored events must still work with new code. AF5 handles this with two layered mechanisms:

1. **Message converter** — handles representation changes (adding optional fields, renaming via aliases, type coercion, etc.) automatically.
2. **Message transformer** *(this spec)* — handles structural changes the converter can't (splits, drops, required fields, payload restructuring).

The transformer is a decorator around the converter, so the same mechanism works for events, commands, and queries.

## Scope (the three-part structure)

- **Part A — Converter-only**: decision tree showing what the converter handles natively (no transformer needed).
- **Part B — Transformer needed**: 9 user stories, the heart of the spec.
- **Part C — Out of scope**: deferred features and scenarios where a transformer is the wrong tool.

### Part A: What the converter handles (no transformer needed)

The converter absorbs most schema evolution on its own through two mechanisms: **payload
conversion at handling time** (each handler declares its preferred Java type and the converter
produces it from the stored payload) and **converter-level compatibility configuration** (Jackson
annotations, Avro reader/writer schemas, JAXB bindings, defaults, ignore-unknown settings -- all
on the converter, never on the transformation chain).

So **whether a change requires a transformer depends as much on how your converter is configured
as on the change itself.** A loose configuration absorbs more; a strict one rejects more.

> **The rule:** configure your converter first; reach for a transformer only when the change
> cannot be expressed there.


### User Stories (Part B)

| # | Story | Priority |
|---|---|---|
| US1 | Structural payload transformation (e.g. `capacity` → `min/maxCapacity`) | P1 |
| US2 | MessageType rename / version bump, payload unchanged | P2 |
| US3 | Event splitting (1 event → N events, ordered) | P3 |
| US4 | Event dropping (1 → 0, tracking token still advances) | P4 |
| US5 | Chaining transformations across versions (v1→v2→v3) | P2 |
| US6 | Misconfiguration & runtime failure feedback (fail fast, clear errors) | P1 |
| US7 | Startup observability (INFO at boot, DEBUG per event) | P2 |
| US8 | Command upcasting (1:1 only; reject splits/drops) | P3 |
| US9 | Query upcasting (1:1 only; reject splits/drops) | P3 |

### Deferred / Out of Scope (Part C)

- **Deferred**: N-to-1 merge, moving data between events, downcasting (sender-side), snapshot upcasting, annotation-based registration. Each has a documented "memory-scope" or "scope/focus" reason, with guidance on what to do instead (often: Copy-and-Replace migration, or stateful projection).
- **Wrong tool**: silent semantic-meaning changes, and events that can't be derived from the old payload — both corrupt the audit trail. Solution: a new event type.

## Functional Requirements (highlights)

- **FR-001/002/003**: 1:1 transformations (source + target + optional rule), pure renames (no rule), and 1:N/1:0 (split/drop) patterns.
- **FR-004**: Registration is programmatic and startup-only; chain locks once processing begins; registration order = application order.
- **FR-005**: Exact `(fully qualified name, version)` matching; non-matching events pass through.
- **FR-006**: Transformations must be deterministic and thread-safe (contract, not enforced).
- **FR-009**: Five hard-error classes detected before any event is processed — duplicate `from`, self-loop, unqualified name (at `register()`), and multi-step cycle, version-order violation (at `.build()` lock).
- **FR-010**: Typed payload access via declared target type *or* injected `Converter`.
- **FR-011**: Envelope (entity id, token, sequence) preserved; metadata may be modified.
- **FR-012**: Lazy deserialization — non-matching events never deserialized; O(1) per-event lookup, no allocations on the non-matching path (benchmark thresholds in plan.md).
- **FR-013**: Same output across all three read contexts (entity load, DCB read, tracking processor).
- **FR-014**: INFO once at startup; DEBUG per applied transformation.
- **FR-015**: Tracking token advances past dropped events.
- **FR-016**: Exceptions propagate immediately with full context; no silent skip.
- **FR-017**: Unversioned legacy events treated as version `0.0.1`.
- **FR-018**: Unit-testable without bootstrapping the framework.
- **FR-019**: Output identity verified against declared `to`; no silent coercion (1:1 only).
- **FR-020**: Mechanism extends to commands and queries; splits/drops rejected for those.
- **FR-021**: Required `VersionComparator` per chain (default `SemverComparator`); enforces version-order at lock time; opt out with `VersionComparator.disabled()`.
- **FR-022**: Data-protection (PII redaction etc.) runs *after* the transformer.

## Success Criteria (highlights)

- **SC-001**: A US1 transformation requires materially fewer lines than the AF4 equivalent (exact target in plan.md).
- **SC-004**: Every FR-009 conflict class detected before any event processed.
- **SC-005**: All in-scope use cases demonstrated in `examples/university-demo` with passing CI tests.
- **SC-010**: Concurrency test — multiple threads × sufficient iterations produce identical outputs (specific N/M in plan.md).
- **SC-011**: INFO + DEBUG observability verified by automated tests.

## Key design choices worth a reviewer's attention

1. **Programmatic registration only** for 5.2.0 — explicit reaction to AF4's Spring-Bean ordering issues. Annotations deferred.
2. **Fail-fast philosophy** — five misconfiguration classes caught at startup, not runtime.
3. **No context-aware transformations** — N-to-1 merge and field-borrowing across events deferred because per-entity vs. cross-entity memory scope was a known AF4 bug source.
4. **Single mechanism for events, commands, queries** — via the converter-decorator architecture; split/drop restricted to events.
5. **Snapshots architecturally ready but deferred** — design must not preclude future snapshot transformations without redesign.

