# ADR 003: `DeadlineManager` finds its scope and `ProcessingContext` through the current `Scope` (issue [#5003](https://github.com/AxonIQ/AxonFramework/issues/5003))

Date: 2026-09-29
Status: proposed
Related: [#3065](https://github.com/AxonIQ/AxonFramework/issues/3065) (parent), [#3728](https://github.com/AxonIQ/AxonFramework/issues/3728) (saga port, merged), [#5001](https://github.com/AxonIQ/AxonFramework/issues/5001) (scope-routing types, including `Scope`), [#5005](https://github.com/AxonIQ/AxonFramework/issues/5005) (scheduler backends), [#5047](https://github.com/AxonIQ/AxonFramework/issues/5047) (saga delivery), [#5048](https://github.com/AxonIQ/AxonFramework/issues/5048) (migration tooling)

## Context

A call to an Axon Framework 4 `DeadlineManager` relied on two pieces of ambient state:

- **The current `Scope`.** The overloads without a `ScopeDescriptor` used `Scope.describeCurrentScope()`, which
  describes the Saga (or aggregate) whose handler is running, and threw `IllegalStateException` when none was.
- **The current unit of work.** `AbstractDeadlineManager.runOnPrepareCommitOrNow(...)` deferred the actual call
  to the prepare-commit phase of `CurrentUnitOfWork`, so a call made in a unit of work that later rolls back
  never reaches the backing store.

Both were available however the `DeadlineManager` was reached: as a handler parameter, as a field, or through
a collaborator service the Saga delegates to. The public `DeadlineManager` API must stay source compatible,
so neither can become an explicit parameter.

Axon Framework 5 passes the `ProcessingContext` explicitly and has no ambient unit of work. The merged saga
port already replaced the static `SagaLifecycle` with a `ProcessingContext` resource. The question this ADR
settles is where a `DeadlineManager` call gets the Saga's scope and `ProcessingContext` from.

## Options considered

**Option A: keep `Scope` and let it carry the `ProcessingContext` (chosen).** `Scope` is ported unchanged.
`AnnotatedSaga` starts a per-invocation scope around the synchronous handler call, exactly where Axon
Framework 4 used `executeWithResult`. That scope is an `@Internal` `ContextAwareScope`, which also exposes the
invocation's `ProcessingContext`. `DeadlineManager` and `AbstractDeadlineManager` keep their Axon Framework 4
shape; only `runOnPrepareCommitOrNow` changes where it looks for the unit of work.

**Option B: add `ProcessingContext`-accepting overloads to `DeadlineManager`.** Rejected: it grows the API of
a class meant to be ported unchanged, and the existing overloads would still have no scope and nothing to
defer to, so every call site would have to change anyway.

**Option C: a context-bound `DeadlineManager`, resolved as a handler parameter.** This was the first
implementation of this issue. Following the `SagaLifecycle` precedent, a `DeadlineManager` parameter
resolved to a wrapper bound to the invocation's `ProcessingContext`, and scope-less calls fell back to
`NoScopeDescriptor`. Rejected after review, because it changed behaviour without Axon Framework 5 forcing it:

- a `DeadlineManager` reached any other way (field, collaborator, custom Saga factory, and the test fixture's
  `StubDeadlineManager` once ported) had no scope and no deferral: a scope-less `schedule(...)` stored a deadline no
  `ScopeAware` can resolve, so it was silently never delivered, where Axon Framework 4 threw;
- the parameter resolver outranked the configuration resolver but had nothing registering what it looked
  up, so a `DeadlineManager` parameter failed where Axon Framework 4 injected the configured manager;
- `AbstractDeadlineManager` got a new extension contract (`doSchedule` and friends), so every Axon Framework 4
  backend and custom subclass had to be restructured instead of ported;
- dispatch interceptors ran when the call was made rather than inside the deferred call, and the deferral
  registered for `PREPARE_COMMIT`, which a `ProcessingContext` rejects while it is already running that phase.

## Decision

Option A.

- `Scope` lives in `axon-legacy` as `org.axonframework.messaging.core.Scope`, unchanged.
- `AnnotatedSaga.handle(...)` makes a per-invocation `ContextAwareScope` the current scope until the whole
  handler chain completed: interceptors, the handler and exception handlers. The chain is lazy, so the scope
  also covers consuming its result, which `requireCompleted` does. `Saga.invoke(...)` and `Saga.execute(...)`
  run within a plain scope describing the Saga, without a context. Both describe the Saga as Axon Framework 4
  did: the simple class name of the Saga instance plus the Saga identifier, which is what
  `AbstractSagaManager.canResolve(...)` compares against. `ScopeDescriptorParameterResolverFactory` gets its
  Axon Framework 4 body back.
- `DeadlineManager`'s scope-less overloads use `Scope.describeCurrentScope()` again and throw outside a scope.
- `AbstractDeadlineManager.runOnPrepareCommitOrNow(Runnable)` defers when a `ContextAwareScope` is current, and
  runs the call immediately otherwise. The deferred calls of all managers go into one FIFO queue per context,
  drained by a single action in `RUN_DEADLINE_CALLS` (`PREPARE_COMMIT + 7_500`), so they keep the order they
  were made in and a failing call stops the ones after it, as the prepare-commit handlers of an Axon Framework
  4 unit of work did. A deadline message built from a payload inside the scope takes the context's correlation
  data, as an Axon Framework 4 message took the unit of work's.
- A `DeadlineManager` handler parameter resolves through the configuration (or Spring) like any other
  component, as in Axon Framework 4. No dedicated resolver exists.

### Why a `ThreadLocal` is acceptable here

Axon Framework 5 avoids `ThreadLocal`s internally and allows them only at the edges, for imperative style.
This one is such an edge: it lives in `axon-legacy` only, is set and cleared in a single `try`/`finally` at the
hand-off to imperative user code, and is read only by legacy code. The merged saga port already fails a Saga
handler whose returned result is not complete, so work the framework itself continues is never done outside
the scope. A `void` handler that hands work to another thread on its own goes undetected, as in Axon
Framework 4; that work sees no scope. No Axon Framework 5 core module depends on `axon-legacy`, so only legacy code and applications
that opted into the module can reach `Scope` at all.

### Why `RUN_DEADLINE_CALLS` sits where it does

A `ProcessingContext` rejects a registration for the phase it is already running, and a subscribing event
processor fed by the `SimpleEventBus` invokes a Saga from within `PREPARE_COMMIT`. The deferred calls therefore
run in the gap above it, like the Saga write (`AnnotatedSagaRepository.WRITE_SAGA`, `PREPARE_COMMIT + 5_000`).
They run after the Saga write, because Axon Framework 4 registered the Saga write for prepare-commit when the
Saga was loaded, before its handler made any deadline call. One action per queue, instead of one per call,
keeps the calls in the order they were made: actions registered for the same phase may run concurrently.

## Consequences

- An Axon Framework 4 Saga schedules and cancels deadlines unchanged, whether it declares a `DeadlineManager`
  parameter or delegates to a collaborator holding one. No migration step or OpenRewrite recipe is needed for
  this.
- The [#5005](https://github.com/AxonIQ/AxonFramework/issues/5005) backends can be ported close to literally:
  they keep calling `runOnPrepareCommitOrNow(...)` and `processDispatchInterceptors(...)` as in Axon Framework 4.
- Divergences Axon Framework 5 forces, documented in `axon-5/api-changes/02-processing-context.md#scope`:
  - deferral needs a Saga invocation: a call made outside a Saga handler runs immediately, even while some
    other `ProcessingContext` is running, because there is no ambient unit of work to find it through;
  - deferred calls run in `RUN_DEADLINE_CALLS` instead of in `PREPARE_COMMIT` itself;
  - as a result of both: prepare-commit work runs before every deferred call, all Saga writes of a context run
    before all its deferred calls, a nested unit of work defers into the Saga's context, a call made while a
    deferred call runs runs immediately, a scope without a context stacked on the Saga's disables deferral, and
    the current scope is a per-invocation object rather than the `AnnotatedSaga`. Each is pinned by a test.
- A Saga handler that hands work to another thread cannot schedule deadlines from there within the Saga's
  scope: a scope-less call throws and an explicit-scope call runs immediately. Axon Framework 4 had the same
  limit. The saga port rejects a handler returning an incomplete result, but cannot detect a `void` handler
  doing this.
