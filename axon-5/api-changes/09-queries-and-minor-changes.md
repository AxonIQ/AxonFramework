# Axon Framework 5 — API Changes: Query Dispatching, Event Handling, and Minor API Changes

> Part of the Axon Framework 4→5 migration guide.
> Covers: `QueryBus` async-native and `QualifiedName`-based subscription changes, removal of scatter-gather,
> `ResponseType` removal, `QueryGateway` new API, subscription query (`Flux` initial result, `SubscriptionQueryResponse`),
> `QueryUpdateEmitter` as parameter injection, emitter methods moved to `QueryBus`;
> event handling multi-handler invocation behavior change;
> and all minor API changes: `EventBus` → `EventSink`, `Repository` async-native,
> `StreamableEventSource`, `AggregateLifecycle#apply` → `EventAppender#append`, etc.

## Query Dispatching and Handling

This section describes numerous changes around Query Dispatching and Handling. For a reintroduction to the `QueryBus`
and `QueryGateway`, check [this](#query-bus) and [this](#query-gateway-and-response-types) section respectively.

> Notice - Scatter-Gather has been removed!
>
> We decided to remove the Scatter-Gather query support on the `QueryBus` and `QueryGateway` due to limited use.
> If you did use Scatter-Gather with success, be sure to reach out! We are more than willing to reintroduce
> scatter-gather support based on user experience. If so, be sure to leave a comment
> under [this](https://github.com/AxonFramework/AxonFramework/issues/3689) issue to nudge the Axon Framework team
> accordingly

### Query Bus

The `QueryBus` has undergone some API changes to align with the [Async Native API](03-messages-and-stream.md#async-native-apis) and ease
of configuration. The alignment with the Async Native API shows itself in being able to provide the `ProcessingContext`.
Giving the active `ProcessingContext` is **paramount** if a query should be dispatched as part of a running message
handling task. For example, if an event handler should dispatch a query (e.g., as with process automations), it is
strongly advised to provide the active `ProcessingContext` as part of the dispatch operation.

The dispatch operations now align with the newly introduced [Message Stream](03-messages-and-stream.md#message-stream). This, for example,
adjusts the `QueryBus#query` method to return a `MessageStream` of the `QueryResponseMessage` instead of a
`CompletableFuture`. As the `MessageStream` supports 0, 1, or N responses, this shifts lets the `QueryBus#query` method
align with whatever query result coming back from query handlers.

Streaming solutions, like `QueryBus#streamingQuery` or the `subscriptionQuery` still rely on `Publisher` and `Flux` as
the return type. However, internally, these also depend on the `MessageStream`.
Furthermore, the subscription query support has seen a rigorous adjustment, as
described [here](#subscription-queries-and-the-query-update-emitter).

#### Subscribing Query Handlers

Subscribing query handlers has been adjusted to allow easier registration of query handling lambdas. This shift was
combined with the new `QualifiedName` (as described [here](03-messages-and-stream.md#message-type-and-qualified-name)) replacing the previous
`String queryName` parameter. Lastly, the old subscribe operation enforced providing a `Type`, which has been replaced
by a `QualifiedName` for the query response. Both the query name and the response name are combined in a
`QueryHandlerName` object. This makes it so that subscribe looks like
`QueryBus#subscribe(QueryHandlerName, QueryHandler)` i.o. `QueryBus#subscribe(String, Type, MessageHandler<?>)`. On top
of that, it is now possible to register a single handler for multiple names, through
`QueryBus#subscribe(Set<QueryHandlerName>, QueryHandler)`. This ensures that registering a Query Handling Component (
read: object with several query handlers in it) can be performed seamlessly. For ease of use, there's thus also a
`QueryBus#subscribe(QueryHandlingComponent)` operation present.

Now a note on `QueryHandler` uniqueness within a JVM.

In Axon Framework 4 you were able to register multiple Query Handlers for the same query name and response name.
This had to do with the scatter-gather query, that would hit multiple query handlers to gather the responses.
Since we decided to remove scatter-gather entirely, there's no necessity to being able to register multiple handlers
for the same combination anymore.

On top of that, Axon Framework 4 be "smart about" selecting a Query Handler that best fit the expected `ResponseType`.
As Query Handler registration is no longer based on a the `ResponeType`/`Type`, we lose the capability to, for
example, let a single-response query favor a single-response query handler. Or, for a multiple-response query to favor
a multiple-response query handler, while a query handler was registered for both single and multiple responses.

However, we view losing this capability as a benefit, as (1) it led to complex code and (2) led to unclarity in use,
as we have noticed over the years. As a consequence, the local `QueryBus` will now throw a
`DuplicateQueryHandlerSubscriptionException` whenever a `QueryHandler` for an already existing query name and response
name is being registered.

As with any change, if you feel strongly about the previous solution, be sure to reach out to use. We would love to
hear your use case to deduce the best way forward.

### Query Gateway and Response Types

The `QueryGateway` has undergone some minor API changes to align with the [Async Native API](03-messages-and-stream.md#async-native-apis).
This alignment shows itself in being able to provide the `ProcessingContext`. Giving the active `ProcessingContext` is *
*paramount** if a query should be dispatched as part of a running message handling task. For example, if an event
handler should dispatch a query (e.g., as with process automations), it is strongly advised to provide the active
`ProcessingContext` as part of the send operation.

On top of that, we have eliminated use of the `ResponseType` **entirely**. Both from all `QueryMessage` implementations
as well as from the `QueryGateway`/`QueryBus`. We felt the `ResponseType` was cumbersome to deal with and as such viewed
as a nuisance for the user. Furthermore, it tied our query solution in the JVM space when distributing queries, which is
**not** desirable at all. However, to keep support for querying a single or multiple instances, the gateway now has
dedicated methods:

1. `CompletableFuture<R> QueryGateway#query(Object, Class<R>, ProcessingContext)`
2. `CompletableFuture<List<R>> QueryGateway#queryMany(Object, Class<R>, ProcessingContext)`

This shift is inline with the streaming query (introduced in Axon Framework 4.6), which already did **not** allow you to
define the `ResponseType`.

Besides the `CompletableFuture`, the `QueryGateway` has two `Publisher`-minded solution as well, being the
`streamingQuery` and `subscriptionQuery`:

1. `Publisher<R> QueryGateway#streamingQuery(Object, Class<R>, ProcessingContext)`
2. `Publisher<R> QueryGateway#subscriptionQuery(Object, Class<R>, ProcessingContext)`
3. `SubscriptionQueryResponse<I, U> QueryGateway#subscriptionQuery(Object, Class<I>, Class<U>, ProcessingContext)`

As is clear, the `ResponseType` did not return for any of these methods either. Instead, a **nullable**
`ProcessingContext` can be given (for example important to have correlation data populated). There have been more
changes to the subscription query, for which we suggest you read up on
in [this](#subscription-queries-and-the-query-update-emitter) section.

As might be clear, the `QueryGateway` has an entirely new look and feel. If there are any operations we have
removed/adjusted you miss, or if you have any other suggestions for improvement, please
construct [an issue](https://github.com/AxonFramework/AxonFramework/issues) for us.

### Subscription Queries and the Query Update Emitter

The subscription query support in Axon Framework 5 has seen somewhat of a shift. With the intent to simplify things for
the user.

#### Subscription Query API

First and foremost, we tackled the typical touch points of this API, being the `QueryGateway` and `QueryUpdateEmitter`.
The former no longer has `ResponseType` variants on the API at all. Instead, the desired `Class` type should be provided
and the `QueryGateway` will ensure correct conversion. Removing the `ResponseType` has the side effect that you are no
longer able to, for example, specify a collection as the initial result of a subscription query. To keep support for 0,
1, or N, we switched the initial result from a `Mono` to a `Flux`.

This becomes clear when you check the `SubscriptionQueryResponseMessages` (returned by the `QueryBus` when invoking a
subscription query) and the `SubscriptionQueryResponse` (returned by the `QueryGateway` when invoking a subscription
query), as for both the `initialResult()` operation returns a `Flux`. This should make concatenating initial results
with updates more straightforward. On top of that, it aligns with Axon Framework's shift towards the `MessageStream` as
the de facto response. Lastly on the topic of the return type, is the split of the `SubscriptionQueryResult`. In Axon
Framework 4 the `SubscriptionQueryResult` was used by both the `QueryBus` and `QueryGateway`. This meant that you
sometimes received a `SubscriptionQueryResult` with `Messages` in it and sometimes with payloads. By having a
`SubscriptionQueryResponseMessages` that uses `Messages`, and a `SubscriptionQueryResponse` that uses payloads, we keep
the symmetry between the bus-and-gateway as is present on other infrastructure components of Axon Framework.

Knowing the above, we can have a look at the concrete subscription query methods on the `QueryBus` and `QueryGateway`:

- `SubscriptionQueryResponseMessages QueryBus#subscriptionQuery(SubscriptionQueryMessage, ProcessingContext, int)`
- `Publisher<R> QueryGateway#subscriptionQuery(Object, Class<R>, ProcessingContext)`
- `Publisher<R> QueryGateway#subscriptionQuery(Object, Class<R>, ProcessingContext, int)`
- `SubscriptionQueryResponse<I, U> QueryGateway#subscriptionQuery(Object, Class<I>, Class<U>, ProcessingContext)`
- `SubscriptionQueryResponse<I, U> QueryGateway#subscriptionQuery(Object, Class<I>, Class<U>, ProcessingContext, int)`

The `QueryUpdateEmitter` makes a similar shift from `Message`-to-payload. As such, **all** methods on the
`QueryUpdateEmitter` that accepted a filter of the `SubscriptionQueryMessage` or a `SubscriptionQueryUpdateMessage` as
the update have been removed. Note that this does not mean the `QueryUpdateEmitter` does not accept `Message`
implementations; it is simply no longer a part of the API.

#### Query Update Emitter method move to the Query Bus

Due to our move towards an [Async Native API](03-messages-and-stream.md#async-native-apis), the `ProcessingContext` (the renewed `UnitOfWork`)
has taken a very important spot within the framework.
The `SimpleQueryUpdateEmitter` interacted with the old `UnitOfWork`, allowing users to emit updates, complete
subscriptions, and complete subscriptions exceptionally within a `UnitOfWork` or outside a `UnitOfWork`. Whether these
operations are done within `UnitOfWork` depends on the fact whether the `QueryUpdateEmitter` is invoked within a message
handling function.

We believe that the `QueryUpdateEmitter` should **at all times** be used within a message handling function. Hence, we
adjusted the `QueryUpdateEmitter` to be `ProcessingContext`-aware. This makes it so that the `QueryUpdateEmitter` should
be injected in message handling functions instead of wired for the entire class. This makes it so that (for example)
projectors would interact with the emitter like so:

```java
public class MyEmittingProjector {

    // Add QueryUpdateEmitter as a parameter, NOT as field of MyEmittingProjector! 
    @EventHandler
    public void on(MyEvent event, QueryUpdateEmitter emitter) {
        // update projection(s)...
        emitter.emit(MyQuery.class, query -> /*filter queries to emit the update to*/, () -> new MyUpdate());
    }
}
```

Besides the "old" emit method filtering based on the concrete type, we added filter support (for `emit`, `complete`, and
`completeExceptionally`) based on the [qualified name](03-messages-and-stream.md#message-type-and-qualified-name) of the subscription query.
Furthermore, you can now provide a `Supplier` of the update, ensuring the update object is **not** created whenever
there are no matching subscription queries to emit the update to.

Although we strongly believe this is the correct move for the `QueryUpdateEmitter` it does lead to the fact the emitter
can no longer be used outside the scope of a message handling function. To not lose this support entirely, the
`QueryBus` now allows for the switch between emitting updates within a `ProcessingContext` or outside a
`ProcessingContext`. This means the `QueryBus` inherited some methods from the `QueryUpdateEmitter`, being:

1. `CompletableFuture<Void> emitUpdate(Predicate<SubscriptionQueryMessage>, Supplier<SubscriptionQueryUpdateMessage>, ProcessingContext)`
2. `CompletableFuture<Void> completeSubscriptions(Predicate<SubscriptionQueryMessage>, ProcessingContext)`
3. `CompletableFuture<Void> completeSubscriptionsExceptionally(Predicate<SubscriptionQueryMessage>, Throwable, ProcessingContext)`

As becomes clear from the above, the `QueryBus` now sports the methods that (1) take in a `SubscriptionQueryMessage` and
supplier of a `SubscriptionQueryUpdateMessage`, and (2) take in a nullable `ProcessingContext`.

Lastly, to further simplify the `QueryUpdateEmitter` API, we moved the `subscribe` method which generated the
`UpdateHandler` from the emitter to the `QueryBus`.
This makes it so that the `QueryUpdateEmitter`, which we expect to be **the** touch point for emitting updates, no
longer bothers the users with the possibility to register additional update handlers. Concluding, this mean the
`QueryBus` takes on this role with the following method:

* `UpdateHandler subscribeToUpdates(SubscriptionQueryMessage, int)`

Although we expect users to benefit from the provided `QueryBus#subscriptionQuery` method to have the `UpdateHandler`
managed by Axon Framework itself, you are (obviously) entirely free to register custom `UpdateHandlers` manually if
desired.

## Event Handling

In Axon, an _Event Handling Component_ may declare multiple `@EventHandler` annotated methods.
For each incoming event, Axon inspects all annotated handler methods available on the instance,
including those inherited from supertypes. It then determines which handlers best match the payload type.
Contrary to previous versions, **all** matching handlers are now invoked.

For each handler, the supported Message Type is determined. It looks at the `eventName` attribute of the `@EventHandler`
annotation. If the attribute is not set, the handler's payload parameter is used to detect the type. If that payload
type is annotated with `@Event`, the name is taken from the attributes on that annotation. If not set, the message type
defaults to the fully qualified class name of the payload.

Handler resolution follows these rules:

1. Given the event handling component instance, Axon inspects all `@EventHandler` methods visible on it,
   including inherited methods.
2. From this full set, Axon invokes the handlers that declare to handle that type of message
3. If no handler on the instance can accept the payload, the event is ignored.

This ensures that only handlers matching the most specific applicable payload type are invoked,
while still allowing multiple handlers of equal specificity to run.

Minor API Changes
=================

* The `Repository`, just as other components, has been made [async native](03-messages-and-stream.md#async-native-apis). This means methods
  return a `CompletableFuture` instead of the loaded `Aggregate`. Furthermore, the notion of aggregate was removed from
  the `Repository`, in favor of talking about `ManagedEntity` instances. This makes the `Repository` applicable for
  non-aggregate solutions too.
* The `EventBus` has been renamed to `EventSink`, with adjusted APIs. All publish methods now expect a `String context`
  to define in which (bounded-)context an event should be published. Furthermore, either the method holding the
  `ProcessingContext` or the `publish` returning a `CompletableFuture<Void>` should be used, as these make it possible
  to perform the publication asynchronously.
* The `StreamableEventSource` replaces the `StreamableMessageSource`, enforcing the `Message` type streamed to an
  `EventMessage` implementation. Furthermore, the `StreamableMessageSource#openStream` returns a `MessageStream` instead
  of a `BlockingStream`, taking a `StreamingCondition` (that can be based on a `TrackingToken`) as input. Lastly, all
  `TrackingToken` methods now return a `CompletableFuture<TrackingToken>`, signaling they're potential asynchronous
  operations.
* To append events within an aggregate / entity, use the `EventAppender#append` instead of the
  `AggregateLifecycle#apply` method.
* The `EventStorageEngine` uses append, source, and streaming conditions, for appending, sourcing, and streaming events,
  as described in the [Event Store](05-event-store-and-processors.md#event-store) section. Furthermore, operations have been made "async-native," as
  described [here](03-messages-and-stream.md#async-native-apis). This is marked as a minor API changes since the `EventStorageEngine` should not
  be used directly.
* The `RollbackConfiguration` interface and the `rollbackConfiguration()` builder method have been removed from all
  EventProcessor builders. Exceptions need to be handled by an interceptor, or otherwise they are always considered an
  error.
* The `Lifecycle` interface has been removed, as component lifecycle management is done on component registration. This
  allows component construction to be lazy instead of eager, since we do not require an active instance anymore (as was
  the case with the `Lifecycle` interface). Please read
  the [Component Lifecycle Management](06-configuration.md#component-lifecycle-management) section for more details on this.
* The `SequencingPolicy` interface no longer uses generics and now operates directly on `EventMessage<?>`. This
  simplifies its usage and implementation, as many implementations do not depend on the payload type and can ignore it
  entirely.
* The `MessageHandlerInterceptor` and `MessageDispatchInterceptor` have undergone some minor changes to align with
  the [Async Native API](03-messages-and-stream.md#async-native-apis) of Axon Framework 5. For more details, please check
  the [interceptors section](08-serialization-and-interceptors.md#message-handler-interceptors-and-dispatch-interceptors).
* The annotation logic of all modules is moved to a separate `annotations` package.
* All reflection logic is moved to a dedicated "reflection" package per module.
* The `MessageOriginaProvider` adjusted its use of `correlationId` and `traceId` to align with the current industry
  standard. As such, the old `traceId` is now the `correlationId`. Furthermore, the old `correlationId` is now called
  the `causationId`, as it refers to the `Message` that caused it. Thus, for those basing **any** logic on the old
  `Message#metadata` keys called `traceId` and `correlationId`, we recommend to either (1) override the
  `MessageOriginaProvider` to use the old format or (2) have a transition period from the old to the new approach.

