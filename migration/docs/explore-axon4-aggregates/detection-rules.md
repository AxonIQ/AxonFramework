# axon4-explore — detection rules

Maps each YAML field to the AST patterns the recipe matches. Recipe source:
`migration/src/main/java/org/axonframework/migration/find/ExploreAxon4Aggregates.java`.

## Aggregate identification (the three passes)

| Pass | What it visits | What it captures |
|---|---|---|
| A — `@Aggregate` | `J.ClassDeclaration.getLeadingAnnotations()` | FQN of class, its `extends` clause, all body methods/fields |
| B — `configureAggregate(...)` | `J.MethodInvocation` with simple name `configureAggregate` | First argument's class-literal FQN (Java) or first type parameter FQN (Kotlin generic form) |
| C — `withSubtypes(...)` | `J.MethodInvocation` with simple name `withSubtypes` chained off Pass B | Argument FQNs (children) and the chained-receiver FQN (parent) |

A class becomes an aggregate if **any** pass flags it. The merge phase records which passes
fired in `discovery`.

## Annotation matching strategy

Each annotation check is **type-resolved first, simple-name fallback**:

```java
TypeUtils.isOfClassType(annotation.getType(), "org.axonframework.spring.stereotype.Aggregate")
    || "Aggregate".equals(annotation.getAnnotationType().getSimpleName())
```

This makes the recipe robust against partially-typed LSTs (test fixtures, projects with
broken imports), at the cost of false positives if the user has their own same-named
annotation in another package. False positives are usually obvious in the YAML output and
can be filtered downstream.

## Per-feature patterns

| Field | Pattern |
|---|---|
| `persistence` | `@org.axonframework.eventsourcing.EventSourcingHandler` on any method → `event-sourcing` |
| `features.multi_entity` | `@org.axonframework.modelling.command.AggregateMember` on any field |
| `features.deadline` | `@org.axonframework.deadline.annotation.DeadlineHandler` on any method |
| `features.create_new` | Any `J.MethodInvocation` with simple name `createNew` inside the class body |
| `features.has_snapshot_trigger` | `@Aggregate(snapshotTriggerDefinition = …)` — annotation argument named `snapshotTriggerDefinition` |
| `features.has_cache` | `@Aggregate(cache = …)` — annotation argument named `cache` |
| `features.has_revision` | `@org.axonframework.modelling.command.Revision` on the class |
| `features.has_aggregate_version` | `@AggregateVersion` simple-name match on any field |
| `features.has_creation_policy` | `@CreationPolicy` simple-name match on any method |
| `features.jpa_entity` | `@jakarta.persistence.Entity` or `@javax.persistence.Entity` on the class |

## Command and event vocabulary

For every `J.MethodDeclaration` in the class body:

1. If the method's leading annotations include `@CommandHandler` (AF4 or AF5 FQN, or simple
   name fallback), the FQN of the first parameter type is added to `commands`.
2. If they include `@EventHandler` OR `@EventSourcingHandler`, the FQN of the first
   parameter type is added to `events`.

Constructors qualify too — OpenRewrite represents them as `J.MethodDeclaration` with the
class name as method name, and the parameter list works the same. This catches the AF4
pattern `@CommandHandler public Bike(CreateBikeCommand cmd) { … }`.

First-parameter resolution:
- `md.getParameters()` → take the first non-`J.Empty` `J.VariableDeclarations`
- `vd.getTypeExpression().getType()` → `TypeUtils.asFullyQualified(...)` → FQN
- If type resolution fails, the entry is omitted (dropped from the list rather than
  surfaced as `<unresolved>`)

## Class-literal extraction (Pass B)

Two surface forms accepted:

- **Java** — `MyAggregate.class` arrives as `J.FieldAccess` with `getName().getSimpleName() == "class"`. The target's type resolves to `MyAggregate`'s FQN.
- **Kotlin** — `MyAggregate::class.java` (the standard Java-interop form) arrives as `J.FieldAccess` with selector `java`. Same target-type resolution.
- **Kotlin generic** — `configureAggregate<MyAggregate>(...)` puts the type on `J.MethodInvocation.getTypeParameters()` instead of in the args. Checked first; falls back to argument extraction if absent.

## What's NOT detected

- Aggregates configured indirectly via `Class<?>` variables: `Class<?> t = Bike.class; configureAggregate(t);` — the class literal isn't directly on the `configureAggregate` argument, so Pass B misses it. Workaround: refactor to inline.
- `withSubtypes(...)` calls not directly chained off `configureAggregate(...)` — e.g. `var cfg = configureAggregate(Parent.class); cfg.withSubtypes(...)`. The chain detector only walks immediate `J.MethodInvocation` selects.
- Aggregate references inside lambdas / method references that don't surface as direct
  invocations. These are flagged in `notes` if encountered, otherwise silently missed.
