# ADR 003: `DeadlineManager` as a `ProcessingContext`-scoped resource, injected via parameter resolution (issue [#5003](https://github.com/AxonIQ/AxonFramework/issues/5003))

Date: 2026-09-28
Status: proposed
Related: [#3065](https://github.com/AxonIQ/AxonFramework/issues/3065) (parent), [#3728](https://github.com/AxonIQ/AxonFramework/issues/3728) (saga port, merged; establishes the `SagaLifecycle` `ProcessingContext`-scoped-resource precedent this ADR follows), [#5001](https://github.com/AxonIQ/AxonFramework/issues/5001) (scope-routing types; reuses the same pattern for `CurrentScope`/`ScopeDescriptorParameterResolverFactory`), [#5005](https://github.com/AxonIQ/AxonFramework/issues/5005) (scheduler backends, consumes the new `doSchedule`/etc. contract), [#5047](https://github.com/AxonIQ/AxonFramework/issues/5047) (saga delivery, wires a real backend into `AnnotatedSaga`), [#5048](https://github.com/AxonIQ/AxonFramework/issues/5048) (migration tooling)

## Context

Axon Framework 4's `AbstractDeadlineManager.runOnPrepareCommitOrNow(...)` defers a scheduling or
cancellation call until the surrounding transaction's prepare-commit phase, so that a call made inside a
`UnitOfWork` that later rolls back never actually reaches the backing store — no orphaned deadline. It
does this by reaching for the currently active unit of work ambiently:
`CurrentUnitOfWork.isStarted() ? CurrentUnitOfWork.get().onPrepareCommit(...) : deadlineCall.run()`.

`CurrentUnitOfWork`/`LegacyUnitOfWork` didn't carry over from Axon Framework 4. Axon Framework 5's replacement, 
`ProcessingContext`/`ProcessingLifecycle.runOnPrepareCommit(Consumer<ProcessingContext>)`, is never available ambiently
anywhere live: every real use of it (`SimpleEventBus`, `DefaultEventStoreTransaction`) receives its
`ProcessingContext` as an explicit method parameter, never through a static or `ThreadLocal` lookup. This
is a deliberate, repo-wide constraint.

At the same time, `DeadlineManager.schedule(...)` (all overloads) is a **hard requirement** to keep its
exact Axon Framework 4 signature: a `@DeadlineHandler`-annotated `Saga` class must keep compiling and calling it unchanged with as minimal migration as possible. The public interface therefore
cannot simply grow a `ProcessingContext` parameter — that would break every existing call site the moment
a user upgrades, defeating the entire point of `axon-legacy`.

The design question this ADR settles: given deferred-until-commit scheduling genuinely needs a
`ProcessingContext`, and the public API that triggers it cannot be changed to carry one, where does that
`ProcessingContext` come from?

## Options considered

| | Option A: `ThreadLocal` ambient bridge | Option B: new `ProcessingContext`-accepting overload                                                                                                                                                                                                                                                                                              | Option C: `ProcessingContext`-scoped resource, injected via parameter resolution                                                                                                                                                                                                                                                                                                                                                                                                                                           |
|---|---|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **How** | Reintroduce a `CurrentUnitOfWork`-shaped `ThreadLocal` utility, populated by handler-dispatch machinery before invoking a handler method synchronously, mirroring Axon Framework 4 exactly. | Add one new, non-deprecated `schedule(...)` overload to `DeadlineManager` that takes a `ProcessingContext` explicitly, alongside the deprecated compat overloads.                                                                                                                                                                                 | `DeadlineManager` becomes a resource resolved from the active `ProcessingContext`, exactly like `SagaLifecycle` ([#3728](https://github.com/AxonIQ/AxonFramework/issues/3728), already merged). A handler method declares a `DeadlineManager`-typed parameter; the framework resolves it to an instance bound to that invocation's `ProcessingContext`. **Requires migration**: a field- or constructor-injected `DeadlineManager` must be changed to a handler-method parameter to regain deferred-until-commit scheduling;|
| **Pros** | Preserves exact Axon Framework 4 deferred semantics for every existing `schedule(...)` call site, with zero source changes required from a migrating user. | Real, transaction-safe deferral is available to any caller that has a `ProcessingContext` in hand, with no change to the resolution model used elsewhere in `axon-legacy`.                                                                                                                                                                        | No new public API surface beyond what `#5001` already established as this codebase's pattern for exactly this class of problem. No ambient state anywhere. A user gets deferred-until-commit safety back with a small, mechanical, OpenRewrite-automatable change (add a parameter) rather than a rewrite.                                                                                                                                                                                                                 |
| **Cons** | Reintroduces the ambient/`ThreadLocal` state this codebase has deliberately eliminated everywhere else (`SagaLifecycle`, `CurrentScope` both exist specifically to *replace* this pattern); a new population point would be needed in every handler-dispatch path that might call `schedule(...)`, an ongoing maintenance surface with no existing precedent to build it from. | Widens `DeadlineManager`'s public interface beyond what the issue describes ("ported unchanged" / "for compatibility only"); still leaves the deprecated, no-context overloads with no correct way to defer, unless they change their call sites — at which point Option C's smaller, established-pattern change achieves the same outcome. | A field/constructor-injected `DeadlineManager` (literal Axon Framework 4 usage) has no bound context. Regaining deferred-until-commit safety is not automatic: it requires migrating every such call site to declare a `DeadlineManager` handler-method parameter instead, an explicit follow-up step every migrating user must take, not a drop-in replacement.                                                |

## Decision: `ProcessingContext`-scoped resource, injected via parameter resolution (Option C)

`DeadlineManager` becomes a `ProcessingContext`-scoped resource. To access a context-bound instance, a
handler method declares a `DeadlineManager`-typed parameter; a new
`DeadlineManagerParameterResolverFactory` resolves it from the active `ProcessingContext`. Calling
`schedule(...)`/`cancelSchedule(...)`/etc. on that instance defers to the context's prepare-commit phase,
exactly matching Axon Framework 4 behavior. A `DeadlineManager` obtained any other way (field injection,
constructor injection — the literal Axon Framework 4 pattern which is still fully source-compatible) has no
bound context and runs every call immediately, un-deferred.

This decision includes a **required migration step**: any `Saga` (or other
handler) that calls `schedule(...)`/`cancelSchedule(...)`/etc. from within a message-handling method and
relies on deferred-until-commit safety must change that method to declare a `DeadlineManager` parameter
instead of reaching for a field- or constructor-injected instance. Code that skips this step keeps
compiling and running, but silently loses transactional safety around scheduling. This migration step
belongs in the same migration-path documentation and OpenRewrite tooling as the rest of `axon-legacy`'s
migration guidance.

Concretely:

- New `CurrentDeadlineManager` (`org.axonframework.deadline`) holds the
  `Context.ResourceKey<DeadlineManager>`, mirroring `CurrentScope`. A separate holder class rather than a
  field on `DeadlineManager` itself, because `DeadlineManager` must stay "ported unchanged" even
  additively.
- New `DeadlineManagerParameterResolverFactory` (`org.axonframework.deadline.annotation`) resolves the
  parameter. Unlike `CurrentScope.describeCurrentScope(...)` (which never throws, because
  `NoScopeDescriptor.INSTANCE` is a sensible fallback), there is no sensible no-op `DeadlineManager` — a
  silent no-op would lose deadlines rather than fail loudly. This resolver therefore throws
  `IllegalStateException` when no `DeadlineManager` is registered, mirroring
  `SagaLifecycle.forContext(...)`'s fail-fast precedent instead. **Unlike**
  `ScopeDescriptorParameterResolverFactory` ([#5001](https://github.com/AxonIQ/AxonFramework/issues/5001)
  Decision 2, deliberately type-check-only, no annotation guard), this resolver **does** gate on the
  handler method's annotation: it only matches methods annotated `@SagaEventHandler` or
  `@DeadlineHandler`. `DeadlineManager` access is deliberately scoped to legacy `Saga` classes only — the
  issue itself is explicit that this whole annotation mechanism "is kept solely for legacy `Saga`
  classes" and is never wired to Axon Framework 5 entities by this or any other #3065 sub-issue
  (aggregate deadlines go through command translation instead, per
  [ADR 001](adr-001-aggregate-deadline-command-translation.md)). A `DeadlineManager`-typed parameter on
  any other handler method (e.g. a plain `@CommandHandler` or `@EventHandler` on an
  `@EventSourcedEntity`) does not resolve, keeping this injection point saga-exclusive by construction
  rather than by convention.
- `AbstractDeadlineManager` gains `DeadlineManager forContext(ProcessingContext context)`, returning a
  private, context-bound delegate. Its implementation is restructured around a new protected extension
  point (`doSchedule`/`doCancelSchedule`/`doCancelAll`/`doCancelAllWithinScope`, each taking a `@Nullable
  ProcessingContext`) that concrete scheduler backends implement instead of `DeadlineManager`'s public
  methods directly; the public, unbound path calls these with `context = null`, the context-bound
  delegate calls them with its bound context. `runOnPrepareCommitOrNow` becomes explicit:
  `context.runOnPrepareCommit(...)` when `context != null`, otherwise runs immediately — the same shape
  as the Axon Framework 4 original, just explicit instead of ambient.[5003-implementation-plan.md](../../../../../plans/5003-implementation-plan.md)
- Wiring a real backend `DeadlineManager` into `AnnotatedSaga.sagaContext(...)` (so a live `Saga`'s other
  handler methods can request one) needs a working backend and is left to
  [#5047](https://github.com/AxonIQ/AxonFramework/issues/5047), which already owns saga-delivery wiring.
  [#5003](https://github.com/AxonIQ/AxonFramework/issues/5003) delivers and proves the mechanism itself
  against a minimal test-only `AbstractDeadlineManager` subclass.

## Consequences

- No `ThreadLocal`/ambient state is introduced anywhere in the deadline port, keeping it consistent with
  the precedent `SagaLifecycle` set ([#3728](https://github.com/AxonIQ/AxonFramework/issues/3728)) and
  every `axon-legacy` scope-routing decision since ([#5001](https://github.com/AxonIQ/AxonFramework/issues/5001)).
- A field/constructor-injected `DeadlineManager` — the exact Axon Framework 4 usage pattern, still fully
  source-compatible — loses deferred-until-commit safety: a `schedule(...)` call made this way runs
  immediately, so a later rollback of the surrounding transaction leaves an orphaned scheduled deadline.
- A user restores full Axon Framework 4 parity by adding a `DeadlineManager`
  parameter to the handler method instead of reaching for a field/constructor-injected instance. This
  rewrite is small and regular enough to be OpenRewrite-automatable — tracked as an open item for
  [#5048](https://github.com/AxonIQ/AxonFramework/issues/5048) or a new sub-issue, once
  [#5005](https://github.com/AxonIQ/AxonFramework/issues/5005)/[#5047](https://github.com/AxonIQ/AxonFramework/issues/5047)
  land and there is a working backend to validate a recipe against.
- `axon-5/api-changes/` gains a note explaining this pattern alongside the existing
  `DeadlineManager.schedule(...)` deprecation note: what changes for a field-injected manager versus a
  parameter-injected one, and why.
- [#5005](https://github.com/AxonIQ/AxonFramework/issues/5005)'s scheduler backends are not a pure copy
  from `stash/legacy`: each must implement the new `doSchedule`/`doCancelSchedule`/`doCancelAll`/
  `doCancelAllWithinScope` contract instead of overriding `DeadlineManager`'s public methods directly.
- No changes are made to `SagaLifecycle`, `ScopeAware`, `ScopeDescriptor` (merged by
  [#3728](https://github.com/AxonIQ/AxonFramework/issues/3728)), or `CurrentScope`,
  `ScopeDescriptorParameterResolverFactory`, `AggregateScopeDescriptor` (merged by
  [#5001](https://github.com/AxonIQ/AxonFramework/issues/5001)) — this decision only reuses the same
  `Context.ResourceKey` + parameter-resolver pattern those already established, applied to a new resource
  type.
