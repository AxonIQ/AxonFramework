# ADR 002: Deliver fired saga deadlines through `ScopeAware`, with an explicit-list `ScopeAwareProvider` (issue [#5047](https://github.com/AxonIQ/AxonFramework/issues/5047))

Date: 2026-09-11
Status: proposed
Related: [#3065](https://github.com/AxonIQ/AxonFramework/issues/3065) (parent), [#3728](https://github.com/AxonIQ/AxonFramework/issues/3728) (saga port, merged), [#5001](https://github.com/AxonIQ/AxonFramework/issues/5001) (scope-routing types), [#5005](https://github.com/AxonIQ/AxonFramework/issues/5005) (scheduler backends), [#5053](https://github.com/AxonIQ/AxonFramework/issues/5053) (wires autodiscovery into this provider once it lands), [ADR 001, aggregate deadline delivery](adr-001-aggregate-deadline-command-translation.md)

## Context

Unlike an aggregate, a legacy `Saga` has no `@CommandHandler` migration path: it is an event-handling
component, not a command handler, so [ADR 001](adr-001-aggregate-deadline-command-translation.md)'s
translate-to-command approach does not apply here. `@DeadlineHandler` therefore stays supported for
`Saga` classes, unchanged, for as long as `axon-legacy` exists.

The saga port ([#3728](https://github.com/AxonIQ/AxonFramework/issues/3728)) landed on `main` before
this design was settled, and already merged the delivery *target*: `AbstractSagaManager` implements
`ScopeAware`, with a real `send(Message, ProcessingContext, ScopeDescriptor)` and
`canResolve(ScopeDescriptor)` pair (`AbstractSagaManager.java:301-326`) that resolves a
`SagaScopeDescriptor` to the right saga instance and invokes its handler, bridging the synchronous
`ScopeAware` contract to the saga's async `handle(...)` via `FutureUtils.joinAndUnwrap(...)`. What
remained undecided was how a fired deadline finds that target in the first place: `ScopeAwareProvider`
is a **hard-required builder parameter** on all four scheduler managers
(`QuartzDeadlineManager.Builder.scopeAwareProvider(ScopeAwareProvider)`,
`assertNonNull(...)`), so some implementation has to exist, but nothing in Axon Framework 5 builds one.

## Options considered

Both B1 and B2 build on the same already-merged delivery target
(`AbstractSagaManager.send()`/`canResolve()`); they differ only in how the `ScopeAwareProvider`
discovers which `ScopeAware` instances exist.

| | Option A: translate into a command (mirror ADR 001) | Option B1: `ScopeAware` + auto-discovery | Option B2: `ScopeAware` + explicit list |
|---|---|---|---|
| **How** | N/A for sagas | A `ConfigurationEnhancer`-style mechanism registers every `AnnotatedSagaManager` into a queryable registry; the `ScopeAwareProvider` enumerates it. | The `ScopeAwareProvider` is constructed from a user-supplied list of `ScopeAware` instances, e.g. `new LegacyScopeAwareProvider(sagaManager1, sagaManager2, ...)`. |
| **Pros** | N/A | No manual wiring required from the user. | Reuses real, working, already-merged code; matches how Axon Framework 4 users already worked; no registry to build. |
| **Cons** | Rejected outright: a `Saga` is an event handler, not a command handler, so there is no command to translate a deadline into. | No such mechanism exists yet in Axon Framework 5; building the discovery/scanning logic itself is a separate, larger effort, out of scope for this issue. (It would not, however, need a `ComponentRegistry` "enumerate all components of type X" capability: saga discovery happens once at configuration/startup time, not dynamically at runtime, so the scanning mechanism already holds a direct reference to each `AnnotatedSagaManager` as it builds it, and can assemble the same kind of list a user builds by hand today — see the Consequences note below.) | The user must remember to list every saga manager by hand. |

## Decision: reuse `ScopeAware` (Option B), with an explicit-list provider (B2)

Each of the four scheduler backends routes a fired deadline whose descriptor is a
`SagaScopeDescriptor` through `ScopeAwareProvider.provideScopeAwareStream(...)`, delivering to every
`ScopeAware` component that `canResolve(...)` it — exactly the Axon Framework 4 model, unchanged.

The provider itself is one plain implementation built from an explicit list of `ScopeAware` instances
the user supplies, e.g. `new LegacyScopeAwareProvider(sagaManager1, sagaManager2, ...)`, passed to
each scheduler's `.scopeAwareProvider(...)` call. No `ConfigurationEnhancer`, no registry lookup, no
dependency on the saga-configuration mechanism (`Sagas.of(...)`) beyond the user already holding a
reference to each manager they built with it.

This is not a shortcut taken for lack of a better option — it mirrors the existing shape of the API.
`ScopeAwareProvider` was already a hard-required, user-constructed builder parameter in Axon Framework
4 (`new ConfigurationScopeAwareProvider(configuration)`); asking a migrating user to construct one
explicitly is not new friction, since building one by hand was already the norm.

## Consequences

- `axon-legacy` ships a small, self-contained `ScopeAwareProvider` implementation with no registry or
  auto-discovery machinery. This keeps the saga-deadline delivery path decoupled from
  `Sagas.java`, `Configuration`, and `ComponentRegistry` entirely.
- A user with multiple `Saga` types must list every corresponding saga manager explicitly when
  constructing the provider. There is no mechanism that discovers a saga manager on the user's behalf.
- **Forward-compatibility note:** Spring autodiscovery for sagas (planned separately, out of scope for
  this ADR) is a configuration-time mechanism, not a runtime one — saga managers are discovered once
  at startup, never added dynamically afterward. That makes this explicit-list design a direct
  stepping stone rather than something to replace: an autodiscovery enhancer would build every
  `AnnotatedSagaManager` itself while scanning, the same way `Sagas.of(...)` does today, so it already
  holds a direct reference to each one at creation time. It can assemble that same list itself and
  construct `LegacyScopeAwareProvider` from it exactly as a user does by hand today — no
  `ComponentRegistry` "enumerate all components of type X" capability and no registry surgery needed.
  The only requirement this places on the implementation is that the provider's constructor accepts a
  collection of `ScopeAware` instances rather than something less flexible (e.g. fixed named fields),
  so both a hand-built list and an enhancer-assembled one can construct it the same way. Wiring
  autodiscovery into the provider once it lands is tracked as its own follow-up,
  [#5053](https://github.com/AxonIQ/AxonFramework/issues/5053), rather than folded into this issue --
  it is blocked on saga autodiscovery itself, which is not yet tracked as a GitHub issue.
- No changes are made to `AbstractSagaManager`, `SagaScopeDescriptor`, or anything else merged by
  [#3728](https://github.com/AxonIQ/AxonFramework/issues/3728) — this decision only adds the missing
  discovery/wiring layer on top of already-working code.
- Testing reuses [#5006](https://github.com/AxonIQ/AxonFramework/issues/5006)'s
  `StubDeadlineManager`/fixture tooling rather than a new fixture: a `Saga` with a `@DeadlineHandler`
  method schedules a deadline, the deadline fires, and the handler runs on the correct saga instance.
