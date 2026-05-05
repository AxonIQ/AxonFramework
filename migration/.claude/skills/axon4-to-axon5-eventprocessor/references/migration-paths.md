# Relevant migration-path excerpts

Quoted from `docs/reference-guide/modules/migration/pages/paths/`:

## projectors-event-processors.adoc

> Event Handler annotation:: Moved from `org.axonframework.eventhandling.EventHandler` to `org.axonframework.messaging.eventhandling.annotation.EventHandler`

> The `@ProcessingGroup` annotation has been removed in Axon Framework 5.
> Previously, this annotation was used to group event handling components into a single event processor.
> A replacement for `@ProcessingGroup` in the form of a "namespace" annotation is expected to be introduced soon.

(In AF 5.1+ the replacement landed: `@Namespace` at
`org.axonframework.messaging.core.annotation.Namespace` — used as a 1:1
drop-in for `@ProcessingGroup` on event-handling components.)

## index.adoc — Import and package changes

| AF4 | AF5 |
|---|---|
| `org.axonframework.eventhandling.EventHandler` | `org.axonframework.messaging.eventhandling.annotation.EventHandler` |
| `org.axonframework.eventhandling.DisallowReplay` | `org.axonframework.messaging.eventhandling.replay.annotation.DisallowReplay` |
| `org.axonframework.config.ProcessingGroup` | `org.axonframework.messaging.core.annotation.Namespace` |
| `org.axonframework.messaging.annotation.MetaDataValue` | `org.axonframework.messaging.core.annotation.MetadataValue` |
| `org.axonframework.commandhandling.gateway.CommandGateway` | `org.axonframework.messaging.commandhandling.gateway.CommandGateway` (still exists) |
| n/a | `org.axonframework.messaging.commandhandling.gateway.CommandDispatcher` (new — context-bound) |

## CommandGateway vs CommandDispatcher (from `CommandDispatcher` Javadoc)

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
- **Inside another handler** (`@EventHandler`, `@CommandHandler`,
  `@QueryHandler`, `@MessageHandlerInterceptor`, etc.) → use
  `CommandDispatcher` declared as a **method parameter** so it binds to
  the current `ProcessingContext`.
