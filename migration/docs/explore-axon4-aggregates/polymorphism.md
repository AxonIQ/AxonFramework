# axon4-explore — polymorphism detection

Two independent signals flag a class as polymorphic. Either one is sufficient; both firing
produces a clearer record in `notes` but doesn't change the role assignment.

## Signal 1 — extends-chain

**When it fires**: a class annotated with `@Aggregate` extends another class, and that
parent class is also annotated with `@Aggregate`.

**Detection**:
1. During the scanner, every `J.ClassDeclaration` has its `getExtends()` walked. If it's
   `null`, the recipe falls back to the first entry of `getImplements()` — Kotlin lumps
   parent-class supertypes into the implements list, so this catches the Kotlin
   `class Child : Parent()` form.
2. The parent FQN is captured on the child's `AggregateRecord`.
3. During merge, if the parent's FQN is in `acc.annotated` (the set of `@Aggregate`-flagged
   classes), both records are flagged:
   - parent → `polymorphic: true`, `polymorphic_role: parent`
   - child → `polymorphic: true`, `polymorphic_role: child`, `polymorphic_parent: <parent FQN>`
4. Both get a `notes: ["polymorphism detected via: extends-chain"]` entry.

**Limits**:
- Only direct parent. Multi-level chains (`A extends B extends C`) don't propagate — each
  edge is evaluated independently. If both edges qualify, you'll see B as both parent (of C)
  and child (of A). Manual review needed for deep hierarchies.
- The parent must itself be `@Aggregate`-annotated. A non-annotated abstract base class
  used purely for code reuse isn't flagged — that's the AF4 idiom for shared-state-only
  base classes, not polymorphic aggregates.

## Signal 2 — `withSubtypes(...)`

**When it fires**: a configuration call of the form
`configureAggregate(Parent.class).withSubtypes(Child1.class, Child2.class)`.

**Detection**:
1. During the scanner, every `J.MethodInvocation` with simple name `withSubtypes` is
   inspected.
2. The receiver chain is walked: if `mi.getSelect()` is itself a `J.MethodInvocation` named
   `configureAggregate`, the parent FQN is extracted from its first argument.
3. Each argument of `withSubtypes(...)` contributes a child FQN.
4. The accumulator stores `withSubtypesMap: Map<parentFqn, Set<childFqn>>`.
5. During merge, every entry promotes the parent and every child to `isAggregate: true`,
   marks them polymorphic, and assigns roles.

**Limits**:
- The chain must be **immediate**. `var cfg = configureAggregate(Parent.class); cfg.withSubtypes(...)` is missed because the recipe doesn't track variable bindings.
- Indirect calls through helpers (`registerPolymorphic(Parent.class, Child.class)`) aren't
  understood — only the literal AF4 fluent API.

## Conflict resolution

When both signals fire on the same class:

- **Role assignment** prefers Signal 1's resolution (extends-chain is more reliable than
  fluent-API analysis because it can't be fooled by reassigned receivers).
- **Notes** record both signals: the merge step adds one `notes` entry per signal that
  fired. The user reads both to understand provenance.

When neither signal fires, `polymorphic: false` and the role/parent fields are `null`.

## Edge cases the user should know

- **Sealed hierarchies**: Kotlin `sealed class` parents work via Signal 1 because the
  recipe doesn't filter by class kind. The `notes` field doesn't distinguish sealed from
  open — readers should infer from the source.
- **Single-child polymorphism**: a parent with exactly one Signal-2-declared child is still
  flagged polymorphic. Some codebases use `withSubtypes(...)` defensively. The user can
  decide whether to act on this in their migration plan.
- **Cross-module hierarchies**: when parent and child live in different Maven modules, the
  recipe still finds them as long as both modules are in the reactor scope. Otherwise the
  child's parent FQN won't resolve in `acc.classIndex` and Signal 1 silently misses.
  Workaround: run the recipe at the parent POM with the full reactor.
