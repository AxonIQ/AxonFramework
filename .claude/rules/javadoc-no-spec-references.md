# Code Artifacts — No Spec / Plan / Task References

## Rule

**Public-facing code artifacts (Javadoc, test class names, file names) describe what the code does or what the test verifies — never internal process artifacts.**

Applies to:
- Javadoc (class-level, method-level, field-level)
- Test class names + test file names (e.g. `StructuralTransformationTest`, NOT `EventTransformationFr001Test`)
- Body comments (slightly relaxed — see end of file)

The following do NOT belong in any of the above:

- **Requirement IDs** (e.g. `FR-001`, `FR-018`, `SC-005`). They're meaningless to a consumer reading the JAR's docs; they presume access to a spec the consumer doesn't have.
- **User-story IDs** (e.g. `US1`, `US3`, `US8`).
- **Task IDs** (e.g. `T029`, `T044`).
- **Phase labels** (e.g. "Phase 2", "Phase 3 MVP", "5.3+").
- **Plan-document references** (e.g. "see plan.md", "Forward-compat invariant #5", "per contracts/spi-events.md").
- **"Reserved for a future release"** / "deferred" / "in 5.3+" prose. If a feature isn't shipping in this version, don't mention it in the published doc — its existence in the next release will be self-documenting then.

## What to write instead

Translate each internal reference into the implementation behaviour it represents.

| Internal reference | Javadoc-friendly phrasing |
|---|---|
| `(FR-006)` deterministic + thread-safe | "Implementations MUST be deterministic and thread-safe: no external services, no time or randomness, no mutable shared state." |
| `(FR-018)` output identity check | "raised when a 1:1 transformer's mapper returns a payload whose resolved `MessageType` differs from the declared `to`." |
| `(FR-004)` startup-only registration | "Built once at startup; further registration after `build()` is rejected." |
| `(FR-007)` fixed-point iteration | "Fixed-point iteration with last-match-wins per element." |
| `(FR-011)` hybrid lookup | "Hybrid `QualifiedName`-keyed lookup with a parallel predicate-based list for non-matching pass-through in constant time." |
| `Reserved for 5.3+` | (delete entirely — when the feature ships, add the prose then) |

## Test class naming examples

| Bad (encodes spec IDs) | Good (describes behavior) |
|---|---|
| `EventTransformationFr001Test` | `StructuralTransformationTest` |
| `EventTransformerChainFr005Test` | `NonMatchingPassThroughTest` |
| `TransformingEventStoreFr012Test` | `ReadContextConsistencyTest` |
| `EventTransformerChainFr018Test` | `OutputIdentityCheckTest` |
| `EventTransformerChainFr004LockTest` | `ChainLockingTest` |

## Body comments — same principle, slightly relaxed during scaffolding

Body comments (inside method bodies, not Javadoc) MAY transiently reference task IDs while a feature is being phased in (e.g. `// Foundational shell: body lands with T026`). These references MUST be removed when the corresponding task lands.

Body comments MUST NOT reference `FR-XXX` or `US-XXX` IDs — those are spec artifacts, not navigation aids for the code itself.

## Why

Javadoc is included in published JARs (via `-sources` artifacts, IDE tooltips, generated HTML). The consumer reading it has zero access to the spec / plan / tasks files. Every internal reference is dead weight at best and misleading at worst (e.g. mentioning `5.3+` features in a 5.2.0 JAR's docs).

## How to verify

Before committing any new Javadoc or modifying existing Javadoc, grep the touched files for:

```
FR-[0-9]\|Fr[0-9]\{2,\}\|US[0-9]\|Us[0-9]\{2,\}\|^.*T0[0-9]\{2\}\|5\.3+\|future release\|reserved for a future\|deferred\|plan\.md\|spec\.md\|Forward-compat invariant
```

The `Fr[0-9]\{2,\}` and `Us[0-9]\{2,\}` patterns catch dropped-dash variants snuck into class / file names (e.g. `EventTransformationFr001Test`).

Any hit is a candidate for removal or rephrasing. Body-comment task IDs are acceptable as transient scaffolding only.
