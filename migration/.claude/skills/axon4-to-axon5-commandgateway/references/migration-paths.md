# Relevant migration-path excerpts

Quoted from `docs/reference-guide/modules/migration/pages/paths/`:

## index.adoc — Import and package changes

The relevant rows for this skill (top-of-chain command-dispatch sites):

| AF4 | AF5 |
|---|---|
| `org.axonframework.commandhandling.gateway.CommandGateway` | `org.axonframework.messaging.commandhandling.gateway.CommandGateway` (interface kept, package moved) |
| `org.axonframework.commandhandling.CommandExecutionException` | `org.axonframework.messaging.commandhandling.CommandExecutionException` |
| `org.axonframework.messaging.MetaData` | `org.axonframework.messaging.core.Metadata` |

> Many classes have been moved to better align with the new modular
> structure. The most notable change is the move of Spring-specific and
> monitoring components into their own extension namespaces, and the
> nesting of core messaging components under the
> `org.axonframework.messaging` package.

## projectors-event-processors.adoc — Gateway vs dispatcher decision rule

Although this skill targets non-handler call sites, the decision rule is
the same one as for the projector skill — repeated here so the rule
shows up in both places.

From the `CommandDispatcher` Javadoc:

> Component that dispatches commands to a `CommandGateway` in a predefined
> `ProcessingContext`. This makes the `CommandDispatcher` the **preferred**
> way to send commands from within another message handling method. […]
> When using annotation-based `@MessageHandler`-methods and you have
> declared an argument of type `CommandDispatcher`, the dispatcher will
> automatically be injected by the `CommandDispatcherParameterResolverFactory`.

Decision rule:

- **Top of the chain** (REST controllers, schedulers, CLI entry points,
  any first-cause dispatch with **no** active `ProcessingContext`) → use
  `CommandGateway` injected as a constructor/class-level dependency.
  *This skill handles these.*
- **Inside another handler** (`@EventHandler`, `@CommandHandler`,
  `@QueryHandler`, `@MessageHandlerInterceptor`, etc.) → use
  `CommandDispatcher` declared as a **method parameter**. *Out of scope
  here — see `axon4-to-axon5-eventprocessor`.*

## CommandGateway javadoc — `send(...)` returns `CommandResult`, not `CompletableFuture`

From `CommandGateway.send(Object, Metadata, ProcessingContext)` in AF5:

> Sends the given `command` with the given `metadata` in the provided
> `context` (if available) and returns a `CommandResult` immediately,
> without waiting for the command to execute.
>
> A shorthand to retrieve a `CompletableFuture` is available through the
> `CommandResult#getResultMessage()` operation.

This is the single biggest API-shape change for non-handler callers:

- AF4's `commandGateway.send(cmd, metadata)` returned
  `CompletableFuture<Void>` directly.
- AF5's `commandGateway.send(cmd, metadata)` returns `CommandResult`,
  which is **not** a `CompletableFuture`.

To recover a `CompletableFuture` use one of:

- `commandGateway.send(cmd, metadata).resultAs(R.class)` →
  `CompletableFuture<R>` (typed; works even with `Void.class`).
- `commandGateway.send(cmd, metadata).getResultMessage().thenApply(...)` →
  when the result `Message` itself (its metadata, identifier) is needed.
- `commandGateway.send(cmd, R.class)` → `CompletableFuture<R>` directly,
  but **no metadata** (use only when the call had no AF4 metadata
  argument).
