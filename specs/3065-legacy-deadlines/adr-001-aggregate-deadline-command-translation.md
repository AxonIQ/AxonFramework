# ADR 001: Translate fired aggregate deadlines into commands (issue [#5004](https://github.com/AxonIQ/AxonFramework/issues/5004))

Date: 2026-09-11
Status: proposed
Related: [#3065](https://github.com/AxonIQ/AxonFramework/issues/3065) (parent), [#5001](https://github.com/AxonIQ/AxonFramework/issues/5001) (scope-routing types), [#5005](https://github.com/AxonIQ/AxonFramework/issues/5005) (scheduler backends), [#5048](https://github.com/AxonIQ/AxonFramework/issues/5048) (migration tooling), [ADR 002, saga deadline delivery](adr-002-saga-deadline-scope-aware-delivery.md)

## Context

`axon-legacy` exists purely so that deadlines already scheduled in a live Quartz/JobRunr/db-scheduler
store before a user migrates from Axon Framework 4 to 5 keep firing correctly during migration.
Creating new deadlines is deprecated from the start; only firing already-scheduled ones matters.

In Axon Framework 4, a fired, `AggregateScopeDescriptor`-scoped deadline was delivered by asking a
`ScopeAwareProvider` for every `ScopeAware` component that could resolve the descriptor, which for
aggregates meant a `Repository` loading the target aggregate and invoking its `@DeadlineHandler`
method directly. `#3065` already decided against carrying that model into Axon Framework 5 proper: a
deadline is "just a scheduled command", and `@DeadlineHandler` has no equivalent on an
`@EventSourcedEntity` (there is no annotation-processing path for it there, and none is planned). The
design question this ADR settles is how a fired, `AggregateScopeDescriptor`-scoped deadline reaches its
target once `@DeadlineHandler` is no longer available on the entity side.

## Options considered

| | Option A: port `Repository`-backed direct invocation | Option B: translate into a command, dispatch via `CommandGateway` |
|---|---|---|
| **How** | Reintroduce a `Repository`/`EntityMetamodel`-aware delivery path in `axon-legacy` that loads the target entity and invokes a handler directly, mirroring Axon Framework 4's `ScopeAware` model for aggregates. | The fired deadline's payload and metadata are dispatched as a `CommandMessage` via `CommandGateway.send(payload, metadata, null)`. The payload carries a `@TargetEntityId` field for routing. |
| **Pros** | No user-visible behavior change; `@DeadlineHandler` keeps working on entities unchanged. | Matches the direction `#3065` already committed to. No `Repository`/`EntityMetamodel` coupling added to `axon-legacy`. `CommandBus` routing (consistent hashing) already solves the addressing problem `Repository`/`ScopeAware` solved in Axon Framework 4. |
| **Cons** | Resurrects exactly the model `#3065` decided against; requires wiring `@DeadlineHandler` into the `EntityMetamodel`/annotation-processing pipeline, which does not exist and is not planned for entities. Every migrating user keeps a permanent dependency on a mechanism the framework is moving away from. | Users must migrate their `@DeadlineHandler` method to `@CommandHandler` and ensure the deadline payload carries a `@TargetEntityId` field, since neither exists in an unmodified Axon Framework 4 deadline payload. |

## Decision: translate into a command (Option B)

A fired deadline whose descriptor is an `AggregateScopeDescriptor` is translated into a
`CommandMessage` and dispatched through `CommandGateway.send(payload, metadata, null)`. No
`Repository` loading and no `EntityMetamodel` involvement is added to `axon-legacy` for this path.

`AggregateScopeDescriptor` itself is still ported (in [#5001](https://github.com/AxonIQ/AxonFramework/issues/5001)), because it must still be deserializable from a job payload that was serialized before migration — but it is read only to recognize "this fired deadline targets an aggregate", not to resolve or invoke anything through `ScopeAware`. The routing key `CommandBus` needs comes from a `@TargetEntityId` field on the payload class itself: a migration responsibility of the user's payload, not something `axon-legacy` resolves on their behalf. [#5048](https://github.com/AxonIQ/AxonFramework/issues/5048) ships an OpenRewrite recipe that renames `@DeadlineHandler` to `@CommandHandler` on aggregates and adds `@TargetEntityId` to the payload's identifier field where it can be reliably inferred, to reduce this migration's manual burden.

The dispatch happens with no active `ProcessingContext` (`null` is passed explicitly): the trigger
comes from an external scheduler thread (Quartz/JobRunr/db-scheduler), not from within a message
being handled, so there is nothing to pass.

## Consequences

- `@DeadlineHandler` is not supported on Axon Framework 5 entities, at any point — not even
  transitionally. A user migrating an aggregate must rewrite the handler method to `@CommandHandler`
  and add routing metadata to the payload before cutting over, or the deadline will not be delivered
  once it fires against the migrated entity.
- `axon-legacy` gains no dependency on `modelling`'s `Repository`/`EntityMetamodel` machinery for the
  deadline path, keeping the module's aggregate-side footprint limited to translation and dispatch.
- The saga path is architecturally different and is decided separately in
  [ADR 002](adr-002-saga-deadline-scope-aware-delivery.md), because a `Saga` is an event-handling
  component with no `@CommandHandler` equivalent to migrate to.
- Testing this path does not require a deadline-aware test fixture: the translation-and-dispatch half
  is tested with `StubDeadlineManager` plus a spied `CommandGateway`, and the migrated entity's
  `@CommandHandler` reaction is tested with plain, unmodified `AxonTestFixture` (see
  [#5006](https://github.com/AxonIQ/AxonFramework/issues/5006)).
