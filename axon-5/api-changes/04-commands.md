# Axon Framework 5 — API Changes: Command Dispatching and Handling

> Part of the Axon Framework 4→5 migration guide.
> Covers: `CommandBus` async-native changes, `QualifiedName`-based handler subscription,
> removal of `CommandCallback`, `AsynchronousCommandBus` and `DisruptorCommandBus`;
> `CommandGateway` changes and removal of `CommandGatewayFactory`;
> new `CommandDispatcher` for dispatching commands from within message handlers.

## Command Dispatching and Handling

This section describes numerous changes around Command Dispatching and Handling. For a reintroduction to the
`CommandBus` and `CommandGateway`, check [this](#command-bus) and [this](#command-gateway) section respectively. For the
newly **recommended** approach to dispatch commands from within another message handling function, please check
the [Command Dispatcher](#command-dispatcher) section.

### Command Bus

The `CommandBus` has undergone some minor API changes to align with the [Async Native API](03-messages-and-stream.md#async-native-apis) and ease
of configuration. The alignment with the Async Native API shows itself in being able to provide the `ProcessingContext`.
Giving the active `ProcessingContext` is **paramount** if a command should be dispatched as part of a running message
handling task. For example, if an event handler should dispatch a command (e.g., as with process automations), it is
strongly advised to provide the active `ProcessingContext` as part of the dispatch operation.

The `CommandBus` is now fixed to an asynchronous flow, by sporting the
`CompletableFuture<CommandResultMessage<?>> dispatch(CommandMessage<?>, ProcessingContext)` as the sole operation for
dispatching. This means that the `CommandCallback` and all its implementations have been removed in favor of enforcing
the `CompletableFuture` as the means to deal with success or failures of command handling.

Subscribing command handlers was adjusted to allow easier registration of command handling lambdas. This shift was
combined with the new `QualifiedName` (as described [here](03-messages-and-stream.md#message-type-and-qualified-name)) replacing the previous
`String commandName` parameter. This makes it so that subscribe looks like
`CommandBus#subscribe(QualifiedName, CommandHandler)` i.o. `CommandBus#subscribe(String, MessageHandler<?>)`. On top of
that, it is now possible to register a single handler for multiple names, through
`CommandBus#subscribe(Set<QualifiedName>, CommandHandler)`. This ensures that registering a Command Handling
Component (read: object with several command handlers in it) can be performed seamlessly. For ease of use, there's thus
also a `CommandBus#subscribe(CommandHandlingComponent)` operation present. The "old-fashioned" aggregate is, for
example, a Command Handling Component at heart. With the current handler subscription API, this single class can be
given in one go to the `CommandBus`.

Besides API changes, we have also eliminated some concrete implementations of the `CommandBus` itself. Namely, the
`AsynchronousCommandBus` and the `DisruptorCommandBus`. The `AsynchronousCommandBus` has been replaced by the
`SimpleCommandBus` as we see it as a core concern of command dispatching to allow for the registration of an `Executor`.
The `DisruptorCommandBus` has been removed for lack of use in recent years. If you do use the `DisruptorCommandBus` and
would like to see it return to Axon Framework 5, be sure to
open [an issue](https://github.com/AxonFramework/AxonFramework/issues) for this.

### Command Gateway

The `CommandGateway` has undergone some minor API changes to align with the [Async Native API](03-messages-and-stream.md#async-native-apis).
This alignment shows itself in being able to provide the `ProcessingContext`. Giving the active `ProcessingContext` is *
*paramount** if a command should be dispatched as part of a running message handling task. For example, if an event
handler should dispatch a command (e.g., as with process automations), it is strongly advised to provide the active
`ProcessingContext` as part of the send operation.

For a removal perspective, similarly as with the `CommandBus`, the `CommandCallback` has not returned on this interface.
To deal with successes or failures of command handling, the now default `CompletableFuture` should be consulted instead.
Furthermore, the `Metadata` adding operations have mostly been removed. The only version left expects the user to deal
with the `CommandResult` manually. Lastly, we dropped the timeout options on the `sendAndWait` operations. Whenever
needed, adding these yourself around the `CompletableFuture` or `CommandResult` are straightforward. However, as with
anything, if you feel strongly about certain supported features that have been adjusted, please
construct [an issue](https://github.com/AxonFramework/AxonFramework/issues) for us.

As of Axon Framework 5, the `CommandGateway` is able to convert the result from handling. To correctly perform this
conversion, both the `send` and `sendAndWait` method now expect a `Class` parameter. This parameter allows you to state
the desired return format. If you do not care about the result, you can use the send operations that return the
aforementioned `CommandResult`.

Last point of note on the Command Gateway, is the removal of the `CommandGatewayFactory`. Similarly as with the
`DisruptorCommandBus`, we saw limited usages through our users. If you feel strongly about the `CommandGatewayFactory`
and would like to see it return to Axon Framework 5, be sure to
open [an issue](https://github.com/AxonFramework/AxonFramework/issues) for this.

### Command Dispatcher

The `CommandDispatcher` is the "new kid on the block" for command dispatching.
Where the `CommandBus` is the lowest level means to dispatch `CommandMessages`, the `CommandGateway` is the integration
point between other services to automatically wrap a user's command into a `CommandMessage`. To achieve this, the
`CommandGateway` uses a `CommandBus` to add the command wrapping and response unwrapping.

From there, the `CommandDispatcher` is the [processing context](02-processing-context.md#unit-of-work)-aware command dispatcher. To that end, is
uses a `CommandGateway`, automatically passing the active `ProcessingContext` for the handler it's invoked in. Due to
this knowledge, it is the recommended approach to dispatch commands when **inside** another message handling function.

To clarify, let us show the approach to dispatch a command as part of an existing `ProcessingContext` without the
`CommandDispatcher`:

```java

@EventHandler
public void handle(MoneyTransferredEvent event,
                   ProcessingContext context,
                   CommandGateway commandGateway) {
    // Checks/validation...
    commandGateway.send(new IncreaseBalanceCommand(/*...*/), context);
}
```

By dispatching a command while providing the `ProcessingContext`, you ensure that, for example, correlation data is kept
from one message to another. For distributed tracing, this is a must. As such, the `ProcessingContext` would become a
component users **always** need to wire into their message handler.

It is this requirement that the `CommandDispatcher` solves:

```java

@EventHandler
public void on(MoneyTransferredEvent event,
               CommandDispatcher commandDispatcher) {
    // Checks/validation...
    commandDispatcher.send(new IncreaseBalanceCommand(/*...*/));
}
```

Axon Framework automatically wires a `CommandDispatcher` for you that is aware of the `ProcessingContext` of the
`MoneyTransferredEvent`. This makes the `CommandDispatcher` an added convenience over the `CommandGateway` **within**
message handling functions. Note that this means that the `CommandDispatcher` does not, for example, work from a REST
endpoint. Axon Framework's `ProcessingContext` has not started at that point in time and as such, there is no
`CommandDispatcher` available.

