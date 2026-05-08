API Changes
===========

As is to be expected of a new major release, a lot of things have changed compared to the previous major release. This
document serves the purpose of containing all the changes that may prove breaking to users. Some of the changes have a
lower chance of directly impacting users of Axon Framework 4 (like the [Message Stream](#message-stream)), while others
certainly impact all users (like the [Test Fixture](#test-fixtures) adjustment).

This document can be broken down in five sections:

1. [Version and Dependency Compatibility](#version-and-dependency-compatibility)
2. [Major API Changes](#major-api-changes)
3. [Minor API Changes](#minor-api-changes)
4. [Stored Format Changes](#stored-format-changes)
5. [Class and Method Changes](#class-and-method-changes)

Version and Dependency Compatibility
====================================

* Axon Framework is no longer based on JDK 8, but on JDK 21 instead.
* Spring Boot 2 is no longer supported. You should upgrade to Spring Boot 3 or higher.
* Spring Framework 5 is no longer supported. You should upgrade to Spring Framework 6 or higher.
* Javax Persistence is completely replaced for Jakarta Persistence. This means the majority of `javax` reference no
  longer apply.
* EhCache 2 (from group identifier `net.sf.ehcache`) has been faced out entirely in favor of EhCache 3 (from group
  identifier `org.ehcache`).

Major API Changes
=================

* **All** code marked as `@Deprecated` in Axon Framework 4 is removed entirely. Each deprecation contains the
  recommended resolution path in the JavaDoc. It is strongly recommended to (1) upgrade to the latest Axon Framework 4
  version, (2) adjust any deprecations from Axon Framework you are using as recommended, and then (3) to make the change
  towards Axon Framework 5.
* The entire API of the `UnitOfWork` has been rewritten to (1) construct an 'async-native' flow to support both an
  imperative and reactive style of programming, (2) eliminate the use of `ThreadLocal`, and (3) protect users from
  internals APIs. This does mean that any direct interaction with the `UnitOfWork` has become a breaking change. Please
  check the [Unit of Work](#unit-of-work) section for more details if you are facing this predicament.
* Messages have undergone a number of major changes. Firstly, they now contain a `MessageType`, decoupling a messages (
  business) type from Java's type system. You can find more details on this [here](#message-type-and-qualified-name).
  Secondly, the `Metadata` of each `Message` now reflects a `Map<String, String>` instead of `Map<String, ?>`, thus
  forcing metadata values to strings. Please read [this](#metadata-with-string-values) section for more details on this
  shift. Other noteworthy adjustments, are the removal of the [static
  `Message` factory methods](#factory-methods-like-genericmessageasmessageobject)
  and [renaming of all getters](#message-method-renames).
* All message-based infrastructure in Axon Framework will return the `MessageStream` interface. The `MessageStream` is
  intended to support empty results, results of one entry, and results of N entries, thus mirroring Event Handlers (no
  results), Command Handlers (one result), and Query Handlers (N results). Added, the `MessageStream` will function as a
  replacement for components like the `DomainEventStream` and `BlockingStream` on the `EventStore`. As such, the
  `MessageStream` changes **a lot** of (public) APIs within Axon Framework. Please check
  the [Message Stream](#message-stream) section for more details.
* The API of all infrastructure components is rewritten to be "async native." This means that the
  aforementioned [Unit of Work](#unit-of-work) adjustments flow through most APIs, as well as the use of
  a [Message Stream](#message-stream) to provide a way to support imperative and reactive message handlers. See
  the [Async Native APIs](#async-native-apis) section for a list of all classes that have undergone changes.
* Axon's `EventStore` implementations let go their aggregate-focus, instead following the "Dynamic Consistency
  Boundary" approach. This shift changed the `EventStore` and `EventStorageEngine` API heavily, providing a lot of
  flexibility in defining how entities are event sourced and how events are appended for them. Although most users won't
  interact with the `EventStore` or `EventStorageEngine` directly, knowing the changes could still prove beneficial. For
  those that are curious, be sure to read the [Event Store](#event-store) section.
* The Configuration of Axon Framework has been flipped around. Instead of having a `axon-configuration` module that
  depends on all of Axon's modules to provide a global configuration, the core module (`axon-messaging`) of Axon now
  contains a `Configurer` with a base set of operations. This `Configurer` can either take `Components` or `Modules`.
  The former typically represents an infrastructure component (e.g. the `CommandBus`) whereas modules are themselves
  configurers for a specific module of an application. For an exhaustive list of all the operations that have been
  removed, moved, or altered, see the [Configurer and Configuration](#applicationconfigurer-and-configuration) section.
* Event Processors have undergone a significant change with the removal of `TrackingEventProcessor`. The
  `PooledStreamingEventProcessor` is now the default and recommended
  streaming event processor, providing enhanced performance and better resource utilization. See the
  [Event Processors](#event-processors) section for more details on this transition.
* The Test Fixtures have been replaced by an approach that, instead of an Aggregate or Saga class, take in an
  `ApplicationConfigurer` instance. In doing so, test fixtures reflect the actual application configuration. This
  resolves the predicament that you need to configure your application twice (for production and testing), making the
  chance slimmer that parts will be skipped. For more on this change, please check the [Test Fixtures](#test-fixtures)
  section of this document.
* Aggregates are now referred to as Entities, as the Dynamic Consistency Boundary allows for more fluid boundaries
  around entities.
  In addition, entities have been redesigned to make them more flexible, allowing for immutable
  entities, declarative modeling, and a more fluent API. For more on this, check the
  [Aggregates to Entities](#aggregates-to-entities) section.
* We have switched the `Serializer` for the lower-level `Converter` API throughout Axon Framework. Furthermore, we
  stopped support for the `XStreamSerializer` altogether, making the `JacksonConverter` the default. For more details on
  the `Serializer`-to-`Converter` switch, please check [here](#serialization--conversion-changes).

## Unit of Work

The `UnitOfWork` interface has been rewritten with roughly three goals in mind:

1. Ensure the API of the `UnitOfWork` easily supports imperative and reactive programming styles.
2. Remove the use of the `ThreadLocal` entirely. This change is paramount for a reactive programming style.
3. Guard users from operations they shouldn't touch. The biggest example of this, was the previous `UnitOfWork#commit`
   operation that **was not** intended to be used by users.

To that end, we broke down the `UnitOfWork` interface into two interfaces and a concrete implementation, being:

1. The `ProcessingLifecycle`, describing methods to register actions into distinct `ProcessingLifeCycle.Phases`, thus
   managing the "lifecycle of a process."
2. The `ProcessingContext`, an implementation of the `ProcessingLifecycle` adding resource management.
3. The `UnitOfWork`, an implementation of the `ProcessingContext` and thus `ProcessingLifecycle`.

The user is intended to interface with the `ProcessingLifecycle` when they need to add actions before/after/during
pre-defined `ProcessingLifecycle.DefaultPhases`.
This will allow us, and them, to customize processes like message handling.
Furthermore, the `ProcessingLifecycle` works with a `CompletableFuture` throughout.

The `ProcessingContext` will in turn provide the space to register resources to be used throughout the
`ProcessingLifecycle`.
Although roughly similar to the previous resource management of the old `UnitOfWork`, we intend this format to replace
the use of the `ThreadLocal`. As such, you will notice that the `ProcessingContext` will become a parameter throughout
virtually **all** infrastructure interfaces Axon Framework provides. This will become most apparent on all message
handlers.

It is the replacement of the interfaces with the old `UnitOfWork`, and the spreading of the `ProcessingContext`
instead of the `UnitOfWork` directly, will ensure that operation that are not intended for the end user cannot be
accessed easily anymore.

To conclude, here is a list of changes to take into account concerning the `UnitOfWork`:

1. Operations like `start()`, `commit()`, and `rollback()` are no longer available for the user directly.
2. The nesting functionality of the old `UnitOfWork` through operations like `parent()` and `root()` are completely
   removed.
3. The `UnitOfWork` used to revolve around a `Message`, which is no longer the case for the `ProcessingContext`/
   `ProcessingLifeycle`. Instead, the new approach revolves around a generic action, that may or may not return a
   result.
4. You are no longer tied to the predefined not-started, started, prepare-commit, commit, after-commit, rollback,
   clean-up, and closed phases. Instead, the default phases now are pre-invocation, invocation, post-invocation,
   prepare-commit, commit, and after-commit.
5. The default phases are ordered through the use of an `int`, with space between them to add action before, after, or
   during any phase.
6. The `rollback` logic has been replaced by an on-error, on-complete, and on-finally flow.
   `ProcessingLifecycle#onError` registers an action to be taken on error, while `whenComplete` registers an action to
   performed when after worked as intended. `ProcessingLifecycle#doFinally` registers an operation that is performed on
   success **and** failure of the `ProcessingLifecycle`.
7. Correlation data management, and thus construction of the initial `Metadata` of any `Message`, is removed entirely.
   This is inline with the `UnitOfWork` no longer revolving around a `Message`.
8. The "current" `UnitOfWork` (including the `CurrentUnitOfWork`) is no longer a concept. Instead, all infrastructure
   components will pass along the current context by containing the `ProcessingContext` as a parameter throughout.

Note that the rewrite of the `UnitOfWork` has caused _a lot_ of API changes and numerous removals. For an exhaustive
list of the latter, please check [here](#removed-classes).

## Legacy components

During the development of Axon Framework 5, we have decided that some features move to the legacy package, such as
Sagas. These are features that we think should be either removed, or that deserve a big overhaul in a future version.
Meanwhile, users can thus use the legacy package to continue using these features, while we can focus on the new
features and improvements in Axon Framework 5.

However, even these legacy components have seen some changes. The most notable one is that most of these components
require a `ProcessingContext` to be passed in. This is to ensure good cooperation between old and new parts of the
framework. This means that some changes might be necessary in your code, such as passing in the
`ProcessingContext` to the `InterceptorChain`:

```java
public class MyInterceptingEventHandler {

    @MessageHandlerInterceptor
    public void handle(MyEvent event, InterceptorChain chain, ProcessingContext context) {
        chain.proceedSync(context);
    }
}
```

You are able inject the `ProcessingContext` in any message-handling method, so this is always available. Any code that
uses the old `UnitOfWork` should be rewritten to put resources in this context.

We will provide a migration guide, as well as OpenWrite recipes for these scenarios.

## Message

### Message Type and Qualified Name

For added flexibility with Axon Framework's `Message` API, we introduced two classes, namely:

1. The `MessageType`, and
2. the `QualifiedName`.

The `MessageType` is a combination of a `QualifiedName` and a version (of type `String`). **Every** `Message`
implementation now has the `type()` method, returning its `MessageType`. The intent for this new class on the `Message`,
is to ensure all messages clarify their version and qualified name within the domain they act in. Note that both the
`QualifiedName` and version are non-null variables on the `MessageType`, ensuring they are always present.

This is a shift compared to Axon Framework 4 in roughly two areas, being:

1. The `version` (`revision` as it was called in AF4) is no longer an event-only thing. This makes it so that
   applications can describe the version of their commands and queries more easily, making it possible to construct
   converters or define default mappings between different application releases.
2. The introduction of the `QualifiedName` makes it so that Axon Framework does not have to rely on the
   `Message#getPayloadType` anymore as the defining factor of the `Message` in question.

Thus, through the introduction of the `QualifiedName`, users are able to decouple their message class implementations
from their definition within the application. For example, somebody can define business names for their messages, easing
and clarifying communication with the business and the developer. Or, users can create several unique message
implementations per (micro)service that all map to the same `QualifiedName`. The latter argument makes it so that users
don't have to rely on sharing their concrete message implementations between parties.

Next to adding the `MessageType` to the `Message`, this shift also introduced the dependency on a `QualifiedName` for
message handlers. This shift came from a similar desire as with the `Message`: to ensure somebody doesn't have to rely
on the FQCN and its implementation. On top of this, it allows Axon Framework to deal with messages that come outside the
JVM space more easily.

Although throughout Axon Framework now anticipates the `MessageType` on `Messages` and the `QualifiedName` when
subscribing message handlers, this does not change the default behavior: if you don't specify anything,
the framework will use the `Class#getName` to define the `QualifiedName`, and thus subsequently to define a
`MessageType`. This shift should make it feasible for those to stick to the old behavior or to decouple their concrete
classes and message from one another.

### Factory Methods, like GenericMessage#asMessage(Object)

The factory methods that would construct a `Mesage` implementation based on a given `Object` have been removed from Axon
Framework. These factory methods no longer align with the new API, which expects that the `MessageType` is set
consciously. Hence,
users of the factory methods need to revert to using the constructor of the `Message` implementation instead.

### Metadata with String values

The `Metadata` class (formerly `MetaData`) in Axon Framework changed its implementation. Originally, it was a
`Map<String, ?>` implementation. As of Axon Framework 5, it is a `Map<String, String>`.

The reason for this shift can be broken down in three main pillars:

1. It greatly simplifies de-/serialization for storing `Messages` and putting `Messages` over the wire, since any value
   is a `String` in all cases.
2. It aligns better with how other services, libraries, and frameworks view metadata, which tends to be a `String` or
   byte array.
3. Depending on application requirements, the de-/serialization of specific values can be different. By enforcing a
   `String`, we streamline the process.

Although this may seem like a devolution of the `Message`, we believe this stricter guardrails will help all users in
the long run.

### Message method renames

We have renamed the "get-styled" getters **all** `Message` implementations by removing "get" from the signature.
Thus, `Message#getIdentifier()` is now called `Message#identifier()`, `Message#getPayload()` is now called
`Message#payload()`, `Message#getPayloadType()` is now `Message#payloadType()`, and `Message#getMetaData()` is now
referred to as `Message#metadata()`. A similar rename occurred for the `EventMessage`, for which we renamed the
`getTimestamp()` method to `timestamp()`. Lastly, the `QueryMessage` and `SubscriptionQueryMessage` have undergone the
same rename, for `getResponseType()` and `getUpdateResponseType()` respectively.

### Message Conversion / Serialization

The `Message` and `ResultMessage` interfaces used to have three methods to serialize the payload, metadata, and
exception, called:

1. `Message#serializePayload(Serializer, Class<T>)`
2. `Message#serializeMetaData(Serializer, Class<T>)`
3. `ResultMessage#serializeExceptionResult(Serializer, Class<T>)`

These methods have been removed entirely, as we have redefined the conversion flow for Axon Framework 5.
Instead of using wrapper classes, like the `SerializedObject` returned by the above methods, the `Message` now contains
the required information to be converted itself.

This follows from the introduction of the `MessageType` (as explained [here](#message-type-and-qualified-name)), which
takes the place of the `Message#payloadType`.
This in turn allows the `payloadType` to reflect the format as it is stored within the `Message#payload` at that moment
in time.

On top of that, to keep providing means to retrieve a `Message's` payload in the required format, two new methods have
been introduced:

1. `Message#payloadAs(Type, Converter)`
2. `Message#withConvertedPayload(Type, Converter)`

The `payloadAs(Type, Converter)` method allows to convert the payload into the type required at that moment in time. For
example, one Event Handler requires a "subscription canceled event" as the `SubscriptionCanceledEvent` object, while
another simply wants it as a `JsonNode`. To that end, `EventMessage#payloadAs(Type, Converter)` may be invoked to
extract the payload as desired.

The `withConvertedPayload(Type, Converter)` method constructs a new `Message` instance, with the `payload` converted to
the desired format.
This is valuable if a consumer/publisher is certain that the payload will be required in a new format throughout the
upstream/downstream of the `Message` in question.

## Message Stream

We have introduced the so-called `MessageStream` to allow people to draft both imperative **and** reactive message
handlers. As such, the `MessageStream` is the expected result type from event handlers, command handlers, and query
handlers. Furthermore, the `MessageStream` can mirror response of nothing (zero), one, or N, thus reflecting the
expected behavior of an event handler (no response), a command handler (one response), and query handlers (N responses).
Besides being **the** response for all message handlers in Axon Framework, it is also the return type when
streaming and sourcing events from an `EventStore`.

To achieve all this, the `MessageStream` has several creational methods, like:

1. `MessageStream#fromIterable`
2. `MessageStream#fromStream`
3. `MessageStream#fromFlux`
4. `MessageStream#fromFuture`
5. `MessageStream#just`
6. `MessageStream#empty`

As can be expected, the `MessageStream` streams implementation of `Message`. Hence, the creational methods expect
`Message` implementations when invoked. On top of that, you can add context-specific information to each entry in the
`MessageStream`, by specifying a lambda that takes in the `Message` and returns a `Context` object. For example, Axon
Framework uses this `Context` to add the aggregate identifier, aggregate type, and sequence number for events that
originate from an aggregate-based event store (thus a pre-Dynamic Consistency Boundary event store).

## Async Native APIs

The changes incurred by the new [Unit of Work](#unit-of-work) and [Message Stream](#message-stream) combined form the
basis to make Axon Framework what we have dubbed "Async Native." In other words, it is intended to make Axon Framework
fully asynchronous, top to bottom, without requiring people to deal with asynchronous programming details (e.g.
`CompletableFuture` / `Mono`) at each and every turn.

This shift has an obvious impact on the API of Axon Framework's infrastructure components. The APIs now favor the use of
the `ProcessingContext`, `MessageStream`, and are generally made asynchronous through the use of a `CompletableFuture`.
As these APIs are in most cases not directly invoked by the user, they should typically not form an obstruction.
Nonetheless, if you **do** use these operations, it is good to know they've changed with the desire to be async native.

The following classes have undergone changes to accompany this shift:

* The `CommandBus` - Read [here](#command-dispatching-and-handling) for more details.
* The `CommandGateway` - Read [here](#command-dispatching-and-handling) for more details.
* The `EventStorageEngine` - Read [here](#event-storage) for more details.
* The `EventStore` - Read [here](#event-store) for more details.
* The `EventProcessors` - Read [here](#event-processors) for more details.
* The `Repository`
* The `StreamableMessageSource`
* The `QueryBus` - Read [here](#query-dispatching-and-handling) for more details.
* The `QueryGateway` - Read [here](#query-dispatching-and-handling) for more details.

## Command Dispatching and Handling

This section describes numerous changes around Command Dispatching and Handling. For a reintroduction to the
`CommandBus` and `CommandGateway`, check [this](#command-bus) and [this](#command-gateway) section respectively. For the
newly **recommended** approach to dispatch commands from within another message handling function, please check
the [Command Dispatcher](#command-dispatcher) section.

### Command Bus

The `CommandBus` has undergone some minor API changes to align with the [Async Native API](#async-native-apis) and ease
of configuration. The alignment with the Async Native API shows itself in being able to provide the `ProcessingContext`.
Giving the active `ProcessingContext` is **paramount** if a command should be dispatched as part of a running message
handling task. For example, if an event handler should dispatch a command (e.g., as with process automations), it is
strongly advised to provide the active `ProcessingContext` as part of the dispatch operation.

The `CommandBus` is now fixed to an asynchronous flow, by sporting the
`CompletableFuture<CommandResultMessage<?>> dispatch(CommandMessage<?>, ProcessingContext)` as the sole operation for
dispatching. This means that the `CommandCallback` and all its implementations have been removed in favor of enforcing
the `CompletableFuture` as the means to deal with success or failures of command handling.

Subscribing command handlers was adjusted to allow easier registration of command handling lambdas. This shift was
combined with the new `QualifiedName` (as described [here](#message-type-and-qualified-name)) replacing the previous
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

The `CommandGateway` has undergone some minor API changes to align with the [Async Native API](#async-native-apis).
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

From there, the `CommandDispatcher` is the [processing context](#unit-of-work)-aware command dispatcher. To that end, is
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

## Event Store

The `EventStore` has seen a rigorous change in Axon Framework 5 to accompany the Dynamic Consistency Boundary.

The Dynamic Consistency Boundary, or DCB for short, allows for a flexible boundary to what should be appended
consistently with other existing event streams in the event store. In doing so, it eliminates the focus on the
aggregate identifier, replacing it for user defined "tags." Note that tags are plural. As such, an event is no longer
either attached to zero or one aggregate/entity, but potentially several.

This shift will provide greater flexibility in deriving models, as there is no longer a hard boundary around the
aggregate stream. It allows users to depend on N-"aggregate" streams in one sourcing operation, allowing commands to
span a more complete view.

To not overencumber the sourcing operation, not only tags, but also (event) "types" are used during event store
operation. The types act as a filter on the entity streams that matching the tags. The tags and the types combined from
the `EventCriteria`. It is this `EventCriteria` that Axon Framework uses
for appending events, sourcing events, and streaming events.

It is the `EventCriteria` that thus allows you to define "slices" of an otherwise potentially large aggregate model.
Events that (although part of the entity's stream) don't influence the decision-making process, can be omitted when
sourcing an entity.

As becomes apparent, this is a rather massive changes for those interacting directly with the `EventStore` API from Axon
Framework. Luckily, most users will not interact with this infrastructure component directly. Although this shift
removes the aggregate focus entirely, it does not remove the option to use aggregates. It is purely the internals of
appending, sourcing, and streaming that shift from a 0-or-1 event stream focus to a 0-N event stream solution.

### Appending Events

In the past, you would use the `EventStore#publish` operation to publish events. To ensure the event would be part of an
aggregate stream, users would deal with the `AggregateLifecycle#apply` operation. This used, internally, a `ThreadLocal`
to find the "active" aggregate model, providing the `apply` operation knowledge about the aggregate identifier and
sequence number.

To append events in Axon Framework 5, users first need to start an `EventStoreTransaction` with an active
`ProcessingContext` (see [Unit of Work](#unit-of-work) for more on the `ProcessingContext`).
From there, to append, you would use the `EventStoreTransaction#appendEvent(EventMessage)` operation. To make it so that
appending events are part of an aggregate / consistency boundary that's active, users would first invoke
`EventStoreTransaction#source(SourcingCondition)` (as further explained [here](#sourcing-events)). It is the act of
sourcing that instructs Axon Framework to make a matching `AppendCondition` to use during appending events.

In code, this would like so:

```java
public void appendEvents(EventStore eventStore,
                         ProcessingContext context,
                         EventMessage<?> event) {
    EventStoreTransaction transaction = eventStore.transaction(context);
    transaction.appendEvent(event);
}
```

As stated in [Unit of Work](#unit-of-work), the `ProcessingContext` is propagated throughout Axon Framework. As such, it
is **always** available in message handling functions.

Note that above is the technical solution, applicable only to those interacting with the `EventStore` directly. To
publish events as part of an entity, an `EventAppender` can be injected in command handling methods. On an
`@CommandHandler` annotated method, this would look as follows:

```java

@CommandHandler
public void handle(SubscribeStudentCommand command,
                   EventAppender appender) {
    StudentSubscribedEvent event = this.decide(command);
    appender.append(event);
}
```

### Sourcing Events

In the past, to source an aggregate, the `EventStore#readEvents(String aggregateIdentifier)` method or
`EventStore#readEvents(String aggregateIdentifier, Long firstSequenceNumber)` method was used. Since events are no
longer attached to a single aggregate, neither exist as is anymore.

Instead, the `EventStoreTransaction`, that is also used for [appending events](#appending-events), should be used to
source an entity. More specifically, the `EventStoreTransaction#source(SourcingCondition)` method should be invoked. The
`SourcingCondition` in turn contains the `EventCriteria` to source for, as well as that it is able to define a start and
end position.

If you want to source an (old-fashioned) aggregate, the `EventCriteria` contains a single `Tag` of key `aggregateId` and
a value matching the aggregate to source. In code, this would look as follows:

```java
public void sourcingEvents(EventStore eventStore,
                           ProcessingContext context) {
    Tag aggregateIdTag = new Tag("aggregateId", UUID.randomUUID().toString());
    EventCriteria criteria = EventCriteria.havingTags(aggregateIdTag);
    SourcingCondition sourcingCondition = SourcingCondition.conditionFor(criteria);

    EventStoreTransaction transaction = eventStore.transaction(context);
    MessageStream<? extends EventMessage<?>> sourcedEvents = transaction.source(sourcingCondition);
    // Process the sourced events as desired...
}
```

Note that we do not expect users to source an aggregate / entity manually like this. Axon Framework has extensive
support to define both state-based and event-sourced entities, ensuring all components are in place such that you
*never* have to create any form of condition.

### Streaming Events

In the past, to stream events, the `StreamableMessageSource#openStream(TrackingToken)` method (which the `EventStore`
implements) would be used. This behavior shifted to align with a DCB-based event store. This means we now expect a
condition with an `EventCriteria`, referring to several tags and types. For streaming events, the most feasible filter
are the types, as event streaming is intended to create query models.

To stream events, Axon Framework 5 has replaced the `StreamableMessageSource` for a `StreamableEventSource`.
Furthermore, the `open` operation no longer expects a `TrackingToken`, but a `StreamingCondition` instead. When invoked
manually (thus without the use of Event Processors), this would look as such:

```java
public void streamingEvents(
        StreamableEventSource<EventMessage<?>> streamableEventSource
) throws ExecutionException, InterruptedException, TimeoutException {
    CompletableFuture<TrackingToken> asyncToken = streamableEventSource.headToken();
    TrackingToken trackingToken = asyncToken.get(500, TimeUnit.MILLISECONDS);
    StreamingCondition streamingCondition = StreamingCondition.startingFrom(trackingToken);

    MessageStream<EventMessage<?>> eventStream = streamableEventSource.open(streamingCondition);
    // Process the event stream as desired...
}
```

### Event Storage

#### Generic `EventStorageEngine` changes

The `EventStorageEngine` is now specific to the `StorageEngineBackedEventStore` and
aligns with the changes made to this store and `StreamableMessageSource` to service both
interfaces correctly.

As such, it's API now uses asynchronous operations throughout - all methods now return
`CompletableFuture` objects instead of blocking calls. Furthermore, event appending requires an `AppendCondition` and
uses
`TaggedEventMessage` objects, with operations wrapped in an `AppendTransaction` that must be explicitly committed or
rolled back for better transactional control. The `TaggedEventMessage` wraps an `EventMessage` and a set of `Tags` that
are important labels of the `EventMessage`.

Event retrieval is consolidated into two primary methods: `source()` for finite streams and `stream()` for infinite
streams. Both use condition objects (the aforementioned `SourcingCondition` in [appending events](#appending-events) and
`StreamingCondition` in [streaming events](#streaming-events)) to specify filtering and positioning
logic, replacing direct parameter-based methods. Token creation is streamlined with `firstToken()`, `latestToken()`, and
`tokenAt()` methods that all return futures.

Although the chance is slim users are hit with the API change on the `EventStorageEngine` itself, as we recommend event
reading and writing through higher level APIs, if you are hit, we recommend a manual transition. Both when directly
using the  `EventStorageEngine` or with a manual implementation of the old `EventStorageEngine`.
During such a migration, you'll need to handle async operations with `CompletableFutures`, replace direct append calls
with transactional ones using conditions, and convert aggregate-specific reads to condition-based sourcing calls. The
API removes aggregate-specific methods and snapshot functionality in favor of the more generic condition-based approach.

#### JPA based Event Storage

If you've chosen a JPA-based event storage solution in pre-Axon-Framework-5, that means you need to switch from the
`JpaEventStorageEngine` to the `AggregateBasedJpaEventStorageEngine`. Any changes to the `EventStorageEngine` API are
described shortly [here](#generic-eventstorageengine-changes).

We have introduced an entirely new JPA entry for the `AggregateBasedJpaEventStorageEngine`, called the
`AggregateBasedJpaEntry`. This entry has numerous difference compared to the `DomainEventEntry` used by the
`JpaEventStorageEngine`. For one, the layering of the `DomainEventEntry`, which had four abstract classes and two
interfaces (marked for removal [here](#removed-classes)) and will not return for the `AggregateBasedJpaEntry`.
Furthermore, next to the class name, resolution in a table rename, several columns have been renamed. Please see
the [Stored Format Changes](#stored-format-changes) section for more details on the actual changes.

Besides the entry, the construction of the storage engine changed slightly as well.
The previously used builder-pattern now only remains for the customizable fields, whereas the necessary fields are
simple required parameters of the constructor of the `AggregateBasedJpaEventStorageEngine`. The customizable fields (
like gap timeouts and batch size) can be found in the `AggregateBasedJpaEventStorageEngineConfiguration`.

#### Axon Server based Event Storage

The `AxonServerEventStore` has been removed entirely, in favor of two new `EventStorageEngine` implementations dedicated
to Axon Server.
These are:

1. The `AggregateBasedAxonServerEventStorageEngine` - mandatory for aggregate-based event store formats.
2. The `AxonServerEventStorageEngine` - mandatory for DCB-based event store formats.

As was the case for Axon Framework 4, whenever the `axon-server-connector` is on the classpath, Axon Framework will
default to Axon Server for commands, events, and queries. To disable this default, the `axon-server-connector` can once
more be excluded, or it can be disabled in the `AxonServerConfiguration`. Whenever Axon Server is present, Axon
Framework will assume you want a DCB-based event store. As such, it will construct an `AxonServerEventStorageEngine` by
default.

For green field projects this suffices. For those migrating, be mindful that the stored format of Axon Server needs to
align with DCB for it to work with the `AxonServerEventStorageEngine`!
If the stored format still relies on the aggregate-based format, be sure to configure the
`AggregateBasedAxonServerEventStorageEngine` instead.

## Event Processors

The `EventProcessingModule` (along with the `EventProcessingConfigurer` and `EventProcessingConfiguration` interfaces
that were implemented by this class) has been removed from the framework. To configure default settings for Event
Processors and register instances, use the `MessagingConfigurer#eventProcessing` method.

### Processing Group layer removal

The `ProcessingGroup` layer has been removed from the framework. This layer was used to group Event Handlers to be
assigned to a single Event Processor.
The new configuration API just allows you to register Event Handlers directly to an Event Processor with the following
syntax:

```java
public void configurePSEP() {
    EventProcessorModule.pooledStreaming("when-student-enrolled-to-max-courses-then-send-notification")
                        .eventHandlingComponents(components -> components.declarative(eventHandler1)
                                                                         .autodetected(eventHandler2))
                        .notCustomized();
}
```

With this usage the `eventHandler1` and `eventHandler2` will be assigned to the same Event Processor with the name
`when-student-enrolled-to-max-courses-then-send-notification`.
It's an equivalent of the `@ProcessingGroup("when-student-enrolled-to-max-courses-then-send-notification")` annotation
before.

### TrackingEventProcessor Removal

The `TrackingEventProcessor` has been removed from the framework, with `PooledStreamingEventProcessor` taking over as
the default streaming event processor. The main difference between these processors lies in their threading model, but
the benefits of the PooledStreaming event processor far outweighed the Tracking one.

In the `PooledStreamingEventProcessor` there is a much lower IO overhead, and more segments can be processed in parallel
with the same resources. The processor uses one thread pool to read the event stream and another thread pool to process
the events, so it reads the stream only once regardless of segment count. For example, when processing 8 segments on a
single instance, instead of reading the event stream 8 times, it now reads it once. In the contrary, the
`TrackingEventProcessor` opens a separate event stream per segment it claims.

The pooled streaming processor has one limitation: segments process as fast as the slowest segment. However, this minor
disadvantage is outweighed by the `PooledStreamingEventProcessor` advantages and does not warrant maintaining the
`TrackingEventProcessor`. Users who previously configured `TrackingEventProcessor` instances or used `tracking` mode in
Spring Boot configuration should migrate to `PooledStreamingEventProcessor`.

### SequencingPolicy Configuration

While using Dynamic Consistency Boundary (DCB) instead of the Aggregate approach, the framework cannot
ensure proper event ordering by default. To be on the safe side and avoid any potential out-of-order processing
issues, we set `SequentialPolicy` by default (for events published by Aggregates it's still
`SequentialPerAggregatePolicy`), which means that events are processed in the order they were published.
This is not efficient because you cannot distribute the processing of events across different `EventProcessor` segments.
But it's straightforward to tune the behavior. The new `@SequencingPolicy` annotation allows declaring sequencing
policies on event handler methods or classes. Alternatively, you can use the declarative approach with the builder
pattern. The most useful approach with DCB might be the `PropertySequencingPolicy`, which allows you to process events
in order when they have the same value for a certain property. For example, you can process `StudentEnrolledEvent`s in
order when they have the same `courseId` property, because they are related to the same course, but allow parallel
processing of events that are related to different courses.

```java
// Annotation approach
@EventHandler
@SequencingPolicy(type = PropertySequencingPolicy.class, properties = {"courseId"})
public void handle(StudentEnrolledEvent event) {
    // Handler logic
}

// Declarative approach
EventHandlingComponent component = new DefaultEventHandlingComponentBuilder(baseComponent)
        .sequencingPolicy(new PropertySequencingPolicy(StudentEnrolledEvent.class, "courseId"))
        .handles(new QualifiedName(StudentEnrolledEvent.class), /* event handling method*/)
        .build();
```

The annotation can be defined on the class or method level.
For comprehensive usage examples and configuration options, see the `@SequencingPolicy` JavaDoc documentation.

## ApplicationConfigurer and Configuration

The configuration API of Axon Framework has seen a big adjustment. You can essentially say it has been turned upside
down. We have done so, because the `axon-configuration` module enforced a dependency on all other modules of Axon
Framework. Due to this, it was, for example, not possible to make an Axon Framework application that only supports
command messaging and use the configuration API; the module just pulled in everything.

As an act to clean this up, we have broken down the `Configurer` and `Configuration` into manageable chunks.
As such, the (new) `ApplicationConfigurer` interface now only provides basic operations
to [register components](#registering-components-with-the-componentbuilder-interface), [decorate components](#decorating-components-with-the-componentdecorator-interface), [register enhancers](#registering-enhancers-with-the-configurationenhancer-interface), [register modules](#registering-modules-through-the-modulebuilder-interface),
and [register factories](#registering-component-factories), besides the basic [start-and-shutdown
handler registration](#component-lifecycle-management). It does this by having two different registries, being the
`ComponentRegistry` and `LifecycleRegistry`. The former takes care of the component, decorator, enhancer, and module
registration. The latter provides the aforementioned methods to register start and shutdown handlers as part of
registering components. The `Configuration` in turn now only has the means to retrieve components (optionally), and it's
modules' components. This means **all** infra-specific methods, like for example `Configuration#eventBus`, no longer
exist.

So, how do you start Axon's configuration? That depends on what you are going to use from Axon Framework. If you, for
example, only want to use the basic messaging concepts, you can start with the `MessagingConfigurer`. You can construct
one through the static `MessagingConfigurer#create` method. This `MessagingConfigurer` will provide you a
couple of defaults, like the `CommandBus` and `QueryBus`. Furthermore, on this configurer, you are able to provide new
or replace existing components, decorate these components, and register the aforementioned module-specific `Modules`.
Subsequently, if you want to do event sourcing with Axon Framework, you would start by invoking the
`EventSourcingConfigurer#create` operation

Each of these layers provides registration methods that are specific for the layer. Henceforth, the
`MessagingConfigurer` has a `registerCommandBus`, `registerEventSink`, and `registerQueryBus` method. Subsequently, the
`EventSourcingConfigurer` has the `registerEventStore` and `registerEventStorageEngine` method. To be able to reach the
lower level operations, each `ApplicationConfigurer` wraps a more low-level variant. This causes the "layering" we
talked about earlier. The `EventSourcingConfigurer` thus wraps a `ModellingConfigurer`, the `ModellingConfigurer` a
`MessagingConfigurer`, and the `MessagingConfigurer` contains the `ComponentRegistry` and `LifecycleRegistry`
components. You can move down each of these layers to access gradually lower-level APIs. For more details on this, read
the following [section](#accessing-lower-level-applicationconfigurer-methods).

In this fashion, we intend to ensure the following points:

1. We clean up the (old) `Configurer` and `Configuration` API substantially by splitting it into manageable chunks. This
   should simplify configuration of Axon applications, as well as ease the introduction of specific
   `ApplicationConfigurer` instances like the `MessagingConfigurer`.
2. We reverse the dependency order. In doing so, each Axon Framework module can provide its own `Configurer`. This
   allows users to pick and choose the Axon modules they need.

For more details on how to use the new configuration API, be sure to read the following subsections.

### Registering components with the ComponentBuilder interface

The configuration API boosts a new interface, called the `ComponentBuilder`. The `ComponentBuilder` can generate any
type of component you would need to register with Axon, based on a given `Configuration` instance. By providing the
`Configuration` instance, you are able to pull other (Axon) components out of it that you might require to construct
your component. The `ComponentRegistry#registerComponent` method is adjusted to expect such a `ComponentBuilder` upon
registration.

Here's an example of how to register a `DefaultCommandGateway` through the `registerComponent` method:

```java
public static void main(String[] args) {
    MessagingConfigurer.create()
                       .componentRegistry(registry -> registry.registerComponent(
                               CommandGateway.class,
                               config -> new DefaultCommandGateway(
                                       config.getComponent(CommandBus.class),
                                       config.getComponent(MessageTypeResolver.class)
                               )
                       ));
    // Further configuration...
}
```

Although the sample above uses the `MessagingConfigurer#componentRegistry(Consumer<ComponentRegistry>)` operation, the
same `ComponentBuilder` behavior resides on higher-level operations like `MessagingConfigurer#registerCommandBus`.

### Component Lifecycle Management

As part of any application configuration, there are certain tasks that should be completed on start-up or shutdown. Axon
Framework provided a space for this in three ways, being:

1. On the `Configurer` while registering components.
2. By implementing `Lifecycle` on the component.
3. By adding `@StartHandler` and `@ShutdownHandler` annotated methods to the component.

Since Axon Framework 5, the `Lifecycle` interface and `@StartHandler` and `@ShutdownHandler` annotations no longer
**exist**.

We have done so, because the interface and annotation approach **require** an instance of the component to correctly
invoke the register lifecycle handler operation. This requires eager initialization of components, as otherwise the
methods cannot be accessed. This breaks the desire that defaults given by Axon Framework are not constructed when they
are not used. On top of that, the annotations enforced reflection on all registered components, something we are
steering away from as core component of Axon Framework (as it should be a choice of the user).

Instead, we chose to stick to option one, as this allows for lazy initialization of the components. However, it still
slightly differs from Axon Framework 4. Let us provide an example of registering start and shutdown handlers, for
components **and** decorators:

```java
public static void main(String[] args) {
    EventSourcingConfigurer.create()
                           .componentRegistry(registry -> registry.registerComponent(
                                   ComponentDefinition.ofType(AxonServerConnectionManager.class)
                                                      .withInstance(AxonServerConnectionManager.builder()
                                                                                               /* left out for brevity*/
                                                                                               .build())
                                                      .onStart(
                                                              Phase.INSTRUCTION_COMPONENTS,
                                                              AxonServerConnectionManager::start
                                                      )
                           ))
                           .componentRegistry(registry -> registry.registerDecorator(
                                   DecoratorDefinition.forType(DeadlineManager.class)
                                                      .with((config, name, delegate) -> /* left out for brevity*/)
                                                      .onShutdown(
                                                              Phase.INBOUND_EVENT_CONNECTORS,
                                                              DeadlineManager::shutdown
                                                      )
                           ));
}
```

As shown in the example above, instead of directly registering the component or decorator, the so-called
`ComponentDefinition` and `DecoratorDefinition` are used. These definitions allow you to describe the full extent of how
the component/decorator should behave. Thus including any start or shutdown handlers that should be invoked. In this
example, a definition is created for an `AxonServerConnectionManager` that should start in the `INSTRUCTION_COMPONENTS`.
Furthermore, a decorator definition is given for all components of type `DeadlineManager`, that should be shutdown in
the `INBOUND_EVENT_CONNECTORS`.

This registration approach of a complete definition, wherein the construction of the component and the decoration
thereof are kept and **only** invoked when used in your end application, ensures that lifecycle management does not
cause eager initialization of _any_ component.

### Decorating components with the ComponentDecorator interface

New functionality to the configuration API, is the ability to provide decorators
for [registered components](#registering-components-with-the-componentbuilder-interface). The decorator pattern is what
Axon Framework uses to construct its infrastructure components, like the `CommandBus`, as of version 5.

In the command bus' example, concepts like intercepting, tracing, being distributed, and retrying, are now decorators
around a `SimpleCommandBus`. We register those through the `ComponentRegistry#registerDecorator` method, which expects
provisioning of a `ComponentDecorator` instance. The `ComponentDecorator` provides a `Configuration`, name, and
_delegate_ component when invoked, and expects a new instance of the `ComponentDecorator's` generic type to be returned.

Here's an example of how we can decorate the `SimpleCommandBus` in with a `ComponentDecorator`, in Java:

```java
public static void main(String[] args) {
    MessagingConfigurer.create()
                       .componentRegistry(registry -> registry.registerComponent(
                               CommandBus.class, config -> new SimpleCommandBus()
                       ))
                       .componentRegistry(registry -> registry.registerDecorator(
                               CommandBus.class,
                               0,
                               (config, name, delegate) -> new TracingCommandBus(
                                       delegate,
                                       config.getComponent(CommandBusSpanFactory.class)
                               )
                       ));
    // Further configuration...
}
```

By providing this functionality on the `ComponentRegistry`, you are able to decorate any of Axon's components
with your own custom logic. Since ordering of these decorates can be of importance, you are required to provide an
order upon registration of a `ComponentDecorator`.

### Registering enhancers with the ConfigurationEnhancer interface

The `ConfigurationEnhancer` replaces the old `ConfigurerModule`, with one major difference: A `ConfigurationEnhancer`
acts on the `ComponentRegistry` during `ApplicationConfigurer#build` instead of immediately.

This adjustment allows enhancers to enact on its `ComponentRegistry` in a pre-definable order. They are thus staged to
enhance when the configuration is ready for it. The order is either the registration order with the `ComponentRegistry`
or it is based on the `ConfigurationEnhancer#order` value.

Furthermore, a `ConfigurationEnhancer` can conditionally make adjustments as it sees fit through the
`ComponentRegistry#hasComponent` operation. Through this approach, the implementers of an enhancer can choose to replace
a component or decorate a component only when it (or another) is present.

See the example below where decorating a `CommandBus` with tracing logic is only done when a `CommandBus` component is
present:

```java
public static void main(String[] args) {
    MessagingConfigurer.create()
                       .componentRegistry(registry -> registry.registerEnhancer(configurer -> {
                           if (configurer.hasComponent(CommandBus.class)) {
                               configurer.registerDecorator(
                                       CommandBus.class, 0,
                                       (config, name, delegate) -> new TracingCommandBus(
                                               delegate,
                                               config.getComponent(CommandBusSpanFactory.class)
                                       )
                               );
                           }
                       }));
    // Further configuration...
}
```

In the above enhancer, we first validate if there is a `CommandBus` present. Only when that is the case do we choose to
decorate it as a `TracingCommandBus` by retrieving the `CommandBusSpanFactory` from the `Configuration` given to the
`ComponentDecorator`. Note that this sample does expect that somewhere else during the configuration a
`CommandBusSpanFactory` has been added.

### Registering Modules through the ModuleBuilder interface

To support clear encapsulation, each `ApplicationConfigurer` provides the means to register a `ModuleBuilder` that
constructs a `Module`. A `Module` is basically a container of a `ComponentRegistry` with a parent `ComponentRegistry`.
This structure ensures that (1) it has its own local registry that others cannot influence and (2) that it is still able
to retrieve components from the parent registry.

To emphasize it more, the `Module` **is** able to retrieve components from its parent configuration, but this
configuration **is not** able to retrieve components from the `Module`. This allows users to break down their
configuration into separate `Modules` with their own local components. Reusable components would, instead, reside in the
parent configuration.

Imagine you define an integration module in your project that should use a different `CommandBus` from the rest of your
application. By making a `Module` and registering this specific `CommandBus` on this `Module`, you ensure only **it** is
able to retrieve this `CommandBus`. But, if this `Module` requires common components from its parent, it can still
retrieve those.

Besides the exemplified infrastructure separation from above, Axon Framework uses these `Modules` to encapsulate message
handling. A concrete example of this, is the `StatefulCommandHandlingModule` (that can be registered with the
`ModellingConfigurer`). We have made this decision to strengthen the guideline that your message handlers "should not be
aware of, nor make any assumptions of other components." This rule comes from the location transparency definition,
which Axon Framework provides through it's messaging support. By having the `Module` encapsulated from the rest, we
ensure the parent `ApplicationConfigurer`, nor other `Modules`, are able to depend on it.

Down below is shortened example on how to register a `StatefulCommandHandlingModule`:

```java
public static void main(String[] args) {
    ModellingConfigurer.create()
                       .registerStatefulCommandHandlingModule(
                               StatefulCommandHandlingModule.named("my-module")
                               // Further MODULE configuration...
                       );
    // Further configuration...
}
```

### Registering Component Factories

The new `ComponentFactory` interface allows us, and users, to provide a component factory for components. This provides
a mechanism to, for example, construct a factory that can construct context-specific `CommandGateway` instances or
`EventStorageEngines`. Whenever a `ComponentFactory` constructs an instance, it will register it with the
`Configuration` for future reference. This ensures that when you request a component several times from the
`Configuration` that the same instance will be returned. Note that a `ComponentFactory` may decide against constructing
a component if (1) the `name` is not of the desired format or (2) if the `Configuration` does not contain the required
components to construct an instance.

Axon Framework uses the `ComponentFactory` to, for example, register an `AxonServerEventStorageEngineFactory`. This
`ComponentFactory` for the `AxonServerEventStorageEngine` can construct context-specific `AxonServerEventStorageEngine`
instances. To that end, it expects the `name` to comply to the following format: `"storageEngine@{context-name}"`.

A registered factory is consulted **only** when the `ComponentRegistry` does not contain a component for the
type-and-name combination. Hence, if the `ComponentRegistry` has a `CommandGateway` component registered with it **and**
there is a `ComponentFactory<CommandGateway>` present on the registry, the factory will not be invoked.

Down below is an example when a factory is **not** invoked:

```java
public static void main(String[] args) {
    AxonConfiguration configuration =
            MessagingConfigurer.create()
                               .componentRegistry(registry -> registry.registerComponent(
                                       CommandGateway.class,
                                       config -> new DefaultCommandGateway(
                                               config.getComponent(CommandBus.class),
                                               config.getComponent(MessageTypeResolver.class)
                                       )
                               ))
                               .componentRegistry(registry -> registry.registerFactory(new CommandGatewayFactory()))
                               // Further configuration...
                               .build();

    // This will invoke the CommandGatewayFactory!
    CommandGateway commandGateway = configuration.getComponent(CommandGateway.class, "some-context");
}
```

However, if we take the above example and invoke `getComponent` with a different `name`, the factory will be invoked:

```java
public static void main(String[] args) {
    AxonConfiguration configuration =
            MessagingConfigurer.create()
                               .componentRegistry(registry -> registry.registerComponent(
                                       CommandGateway.class,
                                       config -> new DefaultCommandGateway(
                                               config.getComponent(CommandBus.class),
                                               config.getComponent(MessageTypeResolver.class)
                                       )
                               ))
                               .componentRegistry(registry -> registry.registerFactory(new CommandGatewayFactory()))
                               // Further configuration...
                               .build();

    // This will return the registered DefaultCommandGateway!
    CommandGateway commandGateway = configuration.getComponent(CommandGateway.class);
}
```

### Accessing lower-level ApplicationConfigurer methods

Although the API of an `ApplicationConfigurer` is greatly simplified, we still believe it valuable to have specific
registration methods guiding the user. For example, the `ApplicationConfigurer` no longer has a `subscribeCommandBus`
operation, as that method does not belong on this low level API. However, the specific `MessagingConfigurer` still has
this operation, as registering your `CommandBus` on the messaging layer is intuitive.

To not overencumber users of the `MessagingConfigurer`, we did not give it lifecycle specific configuration operations
like the `LifecycleRegistry#registerLifecyclePhaseTimeout` operation. The same applies for modelling and event sourcing
configurers: these will not override the registration operations of their delegates.

To be able to access a "delegate" `ApplicationConfigurer` there are special accessor methods that expect a lambda of the
delegate to be given. For example the `MessagingConfigurer` has a `componentRegistry(Consumer<ComponentRegistry>)` and
`lifecycleRegistry(Consumer<LifecycleRegistry>)` operation to invoke operations on the `ComponentRegistry` and
`LifecycleRegistry` respectively. Furthermore, the `ModellingConfigurer` has the
`messaging(Consumer<MessagingConfigurer>)` operation to move up to the delegate `MessagingConfigurer` layer:

```java
public static void main(String[] args) {
    ModellingConfigurer.create()
                       .componentRegistry(componentRegistry -> componentRegistry.registerComponent(
                               CommandGateway.class,
                               config -> new DefaultCommandGateway(
                                       config.getComponent(CommandBus.class),
                                       config.getComponent(MessageTypeResolver.class)
                               )
                       ))
                       .lifecycleRegistry(lifecycleRegistry -> lifecycleRegistry.registerLifecyclePhaseTimeout(
                               5, TimeUnit.DAYS
                       ))
                       .messaging(messagingConfigurer -> messagingConfigurer.registerEventSink(
                               config -> new CustomEventSink()
                       ));
    // Further configuration...
}
```

## Aggregates to Entities

Axon Framework 5 elevates the concept of Entities to the top level, as aggregate no longer accurately
describes the concept. With the introduction of [DCB](#event-store), more fluid boundaries of entities are possible.

This section has been written in a way that is easy to follow if you read the sections in order. However, if you
are already familiar with the changes, you can jump to the relevant section using the links below:

- [Aggregates are now referred to as Entities](#aggregates-are-now-entities).
- [Entities can now be defined declaratively, instead of only through reflection.](#declarative-modeling-first).
- [Entities can be immutable, allowing for Java records and Kotlin data classes.](#immutable-entities).
- [Entity constructors can take in the first event as a payload or `EventMessage`, allowing for non-nullable
  fields.](#entity-constructor-changes)
- [Constructor command handlers are gone, and a creational command is a static method on the entity class.](#creational-command-handlers)
- [Reflection-based entities have gained some new capabilities](#reflection-based-entities)

### Aggregates are now Entities

In Axon Framework 5, the concept of aggregates has been replaced with entities. This change reflects the shift from
a strict aggregate boundary to a more flexible entity boundary, allowing for a more fluid definition of entities
that can span multiple event streams. The term "aggregate" is no longer used in the API, and all references to
aggregates have been replaced with "entities."

### Declarative modeling first

When handling messaging for an entity, the framework needs to know which commands and events can be handled
by the entity and which child entities it has. This is what we call the 'EntityMetamodel.'

While aggregates worked only through reflection before, with the Axon Framework 5' entities this can be declaratively
defined.
You can start defining a metamodel by calling `EntityMetamodel.forEntityType(entityType)` and declare command
handlers, event handlers, and
child entities. If you have a polymorphic entity, one that has multiple concrete types and extends one supertype,
you can use `EntityMetamodel.forPolymorphicEntityType(entityType)` to define the entity metamodel.

```java
EntityMetamodel<ImmutableTask> metamodel = EntityMetamodel
        .forEntityType(ImmutableTask.class)
        .entityEvolver(new AnnotationBasedEntityEvolvingComponent<>(ImmutableTask.class))
        .instanceCommandHandler(commandQualifiedName, (command, entity, context) -> {
            // Handle the command
            return MessageStream.empty().cast();
        })
        .addChild(/* child entity definition */)
        .build();
```

However, the use of reflection is still possible. The `AnnotatedEntityMetamodel` reads the entity information
in a way that is similar to Axon Framework 4, and creates a delegate `EntityMetamodel` of the right type, with
the right handlers. This means that the entity structure is clearly defined and debuggable,
and less reflection is needed at runtime, which improves performance.

```java
EntityMetamodel<ImmutableTask> metamodel = AnnotatedEntityMetamodel.forConcreteType(
        ImmutableTask.class,
        configuration.getComponent(ParameterResolverFactory.class),
        configuration.getComponent(MessageTypeResolver.class),
        configuration.getComponent(MessageConverter.class),
        configuration.getComponent(EventConverter.class)
);
```

### Immutable entities

Event-sourced entities can now be created in an immutable fashion, which wasn't possible before Axon Framework 5.
This allows you to create entities out of Java records or Kotlin data classes:

```java
record MyEntity(
        String id,
        String name
) {

    @EventSourcingHandler
    public MyEntity on(MyEntityNameChangedEvent event) {
        return new MyEntity(id, event.getNewName());
    }
}
```

Or, in Kotlin:

```kotlin
data class MyEntity(
    val id: String,
    val name: String
) {
    @EventSourcingHandler
    fun on(event: MyEntityNameChangedEvent): MyEntity {
        return copy(name = event.newName)
    }
}
```

By returning a new instance of the entity in the event sourcing handler, you can evolve the state of the entity
without mutating the original instance. This is particularly useful in functional programming paradigms and allows for
better immutability guarantees in your code. This works with both Java records and Kotlin data classes, as well as
traditional classes.

This is made possible because the first command is handled by a static method, not a constructor, and is responsible for
verifying the command and creating the entity. These static methods
are [creational command handler](#creational-command-handlers). Once the first event is published, the entity is
created using the constructor defining the payload or `EventMessage`. Commands after this will be handled by methods on
the instance of the entity.

To evolve, or change the state, of an entity, `@EventSourcingHandlers` or `EntityEvolvers` can return a new instance of
the entity based on an event. This entity will then be used for the next command or next event.

### Entity Constructor changes

The world is moving to non-nullability guarantees, and for good reason. However, aggregates required a no-arg
constructor to be able to instantiate the aggregate. This meant that fields could not be non-nullable, as the
constructor would not be able to set them. In Axon Framework 5, this has changed.

This is how a kotlin class would traditionally look:

```kotlin
class MyPreFiveClass {
    // Kotlin classes have inherently a no-arg constructor

    @AggregateIdentifier
    private lateinit var id: String

    @CommandHandler
    fun handle(command: CreateMyEntityCommand) {
        AggregateLifecycle.apply(MyEntityCreatedEvent(command.id, command.name))
        // Other initialization logic...
    }

    @EventSourcingHandler
    fun on(event: MyEntityCreatedEvent) {
        this.id = event.id
        // Other initialization logic...
    }
}
```

As you can see, the `lateinit var` makes the `id` field non-nullable, but it can throw if not set when accessed.
In addition, you can never make it a `val`, so it remains mutable.
Java had similar limitations, but it was simply not as visible as it is in Kotlin:

```java
public class MyPreFiveClass {

    private MyPreFiveClass() {
        // No-arg constructor required for Axon Framework 4
    }

    @AggregateIdentifier
    private String id;

    @CommandHandler
    public void handle(CreateMyEntityCommand command, EventAppender appender) {
        appender.append(new MyEntityCreatedEvent(command.getId(), command.getName()));
        // Other initialization logic...
    }

    @EventSourcingHandler
    public void on(MyEntityCreatedEvent event) {
        // this.id is null here
        this.id = event.getId();
        // Other initialization logic...
    }
}
```

From Axon Framework 5 onwards, the constructor of an entity can take in the first event as a payload or `EventMessage`.
This allows you to set the fields of the entity in a non-nullable way,
and it allows you to make them `val` in Kotlin or `final` in Java.
This is what the code would look like in Kotlin:

```kotlin
data class MyEntity(
    val id: String,
    val name: String
) {
    @EntityCreator
    constructor(event: MyEntityCreatedEvent) : this(
        id = event.id,
        name = event.name
    )

    companion object {
        @CommandHandler
        fun create(command: CreateMyEntityCommand, appender: EventAppender) {
            EventAppender.append(MyEntityCreatedEvent(command.id, command.name))
        }
    }
}
```

And this is what it would look like in Java:

```java
public class MyEntity {

    @AggregateIdentifier
    private final String id;
    private final String name;

    @EntityCreator
    public MyEntity(MyEntityCreatedEvent event) {
        this.id = event.getId();
        this.name = event.getName();
    }

    @CommandHandler
    public static void create(CreateMyEntityCommand command) {
        apply(new MyEntityCreatedEvent(command.getId(), command.getName()));
    }
}
```

The way Event-Sourced entities are constructed is defined by the `EventSourcedEntityFactory` that is passed into the
`EventSourcingRepository`. There are four possible ways to construct an entity:

1. **No-arg constructor**: This is the default behavior, where the entity is constructed using a no-arg constructor. Use
   `EventSourcedEntityFactory.fromNoArgument(...)` to use this.
2. **Identifier constructor**: The entity is constructed using a constructor that takes the identifier as a payload. Use
   `EventSourcedEntityFactory.fromIdentifier(...)` to use this.
3. **Event Message**: The entity is constructed using a constructor that takes the first event message as a payload. Use
   `EventSourcedEntityFactory.fromEventMessage(...)` to use this.
4. **Reflection**: Use the `AnnotationBasedEventSourcedEntityFactory` to construct the entity using reflection, marking
   constructors (or static methods) with the `@EntityCreator` annotation. This is the default behavior in Axon
   Framework.

### Creational Command Handlers

Axon Framework 5 distinguishes two types of command handlers:

1. **Creational Command Handlers**: These are static methods on the entity class that are responsible for creating the
   entity and creating the entity, for example, by publishing the first event.
2. **Instance Command Handlers**: These are instance methods on the entity class that handle commands after the entity
   has been created.

The `EntityModel` has the `handleCreate` and `handleInstance` methods to handle these two different kind of commands,
with the `EntityModelBuilder` providing the means to define these handlers. The same command can be registered as both
creational and instance command handler, allowing you to handle the command in a static method and an instance method
depending on whether the entity is already created or not.

Here is an example of both a creational and an instance command handler in Java:

```java
public class MyEntity {

    private String id;

    @EntityCreator
    public MyEntity(MyEntityCreatedEvent event) {
        this.id = event.getId();
        // Other initialization logic...
    }

    // Creational command handler
    @CommandHandler
    public static void create(CreateMyEntityCommand command, EventAppender appender) {
        appender.append(new MyEntityCreatedEvent(command.getId(), command.getName()));
    }

    // Instance command handler
    @CommandHandler
    public void handle(UpdateMyEntityCommand command, EventAppender appender) {
        appender.append(new MyEntityUpdatedEvent(id, command.getNewName()));
        // Other update logic...
    }
}
```

### Reflection-based entities

While very similar to the reflection-based aggregates from AF4, reflection-based entities have gained some new
capabilities.

First, it is now possible to define two or more children of the same type.
Note that the `@EntityMember#commandTargetResolver` must resolve to only one value over all children.

```java
public abstract class Project {

    @EntityMember
    private List<Developer> otherDevelopers = new ArrayList<>();

    @EntityMember
    private List<Milestone> features = new ArrayList<>();
}
```

Second, the `@EntityMember#commandTargetResolver` can now be customized.
By creating your own definition, you can route the command target using something else than the `@RoutingKey`.

```java
public class Project {

    @EntityMember(commandTargetResolver = AwesomeCommandTargetDefinition.class)
    private List<Milestone> features = new ArrayList<>();

    private static class AwesomeCommandTargetDefinition implements CommandTargetResolverDefinition {

        @Nonnull
        @Override
        public <E> CommandTargetResolver<E> createCommandTargetResolver(@Nonnull AnnotatedEntityModel<E> entity,
                                                                        @Nonnull Member member) {
            return (candidates, message, context) -> {
                return candidates.stream().filter(d -> d.isAwesome()).findFirst().orElse(null);
            };
        }
    }
}
```

Third, in Axon Framework 4, the default was to forward events to all entities by default. In Axon Framework 5, this
has changed to only forward events to entities that match the routing key. You can always customize this behavior
by providing a custom `@EntityMember#eventRoutingResolver`:

```java
public abstract class Project {

    @EntityMember(eventTargetMatcher = CustomEventTargetMatcher.class)
    private List<Milestone> features = new ArrayList<>();

    private static class CustomEventTargetMatcher implements EventTargetMatcherDefinition {

        @Nonnull
        @Override
        public <E> EventTargetMatcher<E> createEventRoutingResolver(@Nonnull AnnotatedEntityModel<E> entity,
                                                                    @Nonnull Member member) {
            return (entity, message, ctx) -> {
                return entity.isMostImportantMilestone();
            };
        }
    }
}
```

Fourth, `@EntityMember` can now be used on fields with a simple type, or a `List`. Other types of collections can
currently not be used.
This is due to a limitation of the immutability of child entities that we now support. We might support this in the
future, but for now, we recommend using a `List` or a simple type.

### Exception mapping

With the change from Aggregate to Entity, we've also changed some exceptions. If you depend on these
exceptions, you will need to change your code. The following table shows the changes:

| Old Exception                                                          | New Exception                                                     |
|------------------------------------------------------------------------|-------------------------------------------------------------------|
| `org.axonframework.modelling.command.AggregateEntityNotFoundException` | `org.axonframework.modelling.entity.ChildEntityNotFoundException` |

### Spring Configuration

AF5 fosters auto-detection and auto-configuration of entities, command and message handlers in Spring environment. To
not rely on the old `@Aggregate` stereotype, we introduced the
`@EventSourced` annotation. The `@EventSourced` annotation is still used as a Spring meta-annotation for a prototype
scoped component and now is additionally is meta-annotated with `@EventSourcedEntity` ( replicating all its attributes.)
This effectively means that you only need to put the `@EventSourced` annotation to your entity and the remaining
configuration will be executed by Spring Auto-Configuration. The following attributes are available:

| Attribute                    | Type                                                   | Description                                                                                      |
|------------------------------|--------------------------------------------------------|--------------------------------------------------------------------------------------------------|
| `type`                       | `String`                                               | Defines the type of the entity. If not provided, defaults to the simple class name.              |
| `idType`                     | `Class<?>`                                             | Defines the type of the identity of the entity. If not provided, defaults to `java.lang.String`. |
| `tagKey`                     | `String`                                               | See `EventSourcedEntity#tagKey`                                                                  |
| `concreteTypes`              | `Class<?>[]`                                           | See `EventSourcedEntity#concreteTypes`                                                           |
| `criteriaResolverDefinition` | `Class<? extends CriteriaResolverDefinition>`          | See `EventSourcedEntity#criteriaResolverDefinition`                                              |
| `entityFactoryDefinition`    | `Class<? extends EventSourcedEntityFactoryDefinition>` | See `EventSourcedEntity#entityFactoryDefinition`                                                 |
| `entityIdResolverDefinition` | `Class<? extends EntityIdResolverDefinition>`          | See `EventSourcedEntity#entityIdResolverDefinition`.                                             |

## Test Fixtures

The `axon-test` module of Axon Framework has historically provided two different test fixtures:

1. The `AggregateTestFixture`
2. The `SagaTestFixture`

Both provide a given-when-then style of testing, based on the messages going in and out of the aggregate and saga.
Although practical, we have encountered a couple of predicaments with this style over the years:

1. It is very easy to miss a part of the application configuration with the test fixtures. Although the fixtures have
   numerous registration methods for all things important with aggregate and saga testing, this does not resolve the
   case that somebody might simply forget to add the configuration in both the production and the test scenario.
2. Testing is limited to aggregates and sagas. Hence, Event Handling Components, or Projectors/Projections, do not have
   testing support at all.
3. The test fixtures do not support a form of integration testing. Differently put, it is not possible to validate
   whether the aggregate process (for example) flows through the upcaster process, or triggers snapshots.

In Axon Framework 5, we resolve this by replacing both fixtures with the `AxonTestFixture`. The `AxonTestFixture` is
created by inserting the `ApplicationConfigurer`. From there, it provides the usual given-when-then style of testing.
Any form of message can initiate the given-phase, any form of message can influence the when-phase, and any form of
message can be expected in the then-phase.

By basing the fixture on the `ApplicationConfigurer`, we resolve the concern that users might forget to add
configuration to their fixture that's used in their (production) system. Furthermore, by having the **entire**
`ApplicationConfigurer`, we can easily expand the test fixture to incorporate other areas for testing, like
snapshotting, dead-letter queues, and event scheduling (to name a few). And, lastly, it should serve as an easier
solution towards integration testing an Axon Framework application.

We acknowledge that this shift is a massive breaking changes between Axon Framework 4 and 5. Given the importance of
test suites, we will provide a legacy installment of the old fixtures, albeit deprecated. This way, users are able to
migrate the tests on their own pass.

## Serialization / Conversion changes

The `Serializer` and all `Serializer`-specific components have been removed entirely from Axon Framework 5. For
conversion, Axon Framework uses the `Converter` interface (present since Axon Framework 3), with several
implementations, instead. We have made this shift to simplify the overall conversion flow within Axon Framework.
Although this is not directly noticeable for the end-user, it will enable the Axon Framework team more flexibility in
the foreseeable future.

From a configuration perspective, this change means that any usages of `Serializer` can be replaced for the `Converter`.
For example, instead of a `JacksonSerializer`, Axon Framework 5 uses a `JacksonConverter`.

Furthermore, the default `Converter` switched, from XStream to Jackson. We have made this choice as XStream is most
likely nearing end of life (check [this link](https://github.com/x-stream/xstream/issues/262) for details). Due to that
we deemed it unwise to keep support for XStream. For those using an XML-based format, it is suggested to configure the
`JacksonConverter` with an `XmlMapper` (from artifact `jackson-dataformat-xml`).

This `Serializer`-to-`Converter` shift goes hand-in-hand with the `Metadata` value switch to `String` (as
described [here](#metadata-with-string-values)) and the conversion support on the `Message` directly (as
described [here](#message-conversion--serialization)). The changes on the `Message` directly are more apparent to the
user and worthwhile to be aware of.

### Converter types

Since Axon Framework 3, you had the opportunity to define three levels of Serializer/Converter, being:

1. `general` - Used for everything that needs to be converted, unless defined more specifically by the other levels.
2. `messages` - Used to convert **all** `Message` implementations, unless defined more specifically by the last level.
3. `events` - Used to convert **all** `EventMessage` implementations.

These levels still remain, but we streamlined configuration of these `Converters`. We did so, by introduced a dedicated
`MessageConverter` and `EventConverter` for the `messages` and `events` level respectively. Furthermore, we enforced
usages of a `MessageConverter` and `EventConverter` whenever Axon Framework expects it so.
For example, an `EventStorageEngine` would **always** need an `EventConverter` and nothing else. Hence, constructors of
the `EventStorageEngines` expect an `EventConverter`.

### Revision / Version Resolution

As of Axon Framework 5, the `RevisionResolver`, it's implementations, and `@Revision` annotation have been removed.
The `RevisionResolver` used to be an integral part of the `Serializers` in Axon Framework since 2.0. With the shift
towards a [Message Type](#message-type-and-qualified-name) carrying the `version`, defining the version is no longer
just a `Serializer` concern. Instead, it's a concern for any `Message` implementation, at all times.

Due to this shift, the `RevisionResolver` has been replaced by the `MessageTypeResolver`. Furthermore, the `@Revision`
annotation has been replaced by the `@Command`, `@Event`, and `@Query` for commands, events, and queries respectively
with their `version` field. For snapshots, through the default `RevisionSnapshotFilter`, this will be replaced (likely)
by a dedicated `@Snapshot` annotation.

## Message Handler Interceptors and Dispatch Interceptors

Axon Framework's message interceptor supports is split in two main parts:

1. Dispatch interceptors
2. Handler interceptors

Support for these are covered by the `MessageDispatchInterceptor` and `MessageHandlerInterceptor`.

As many parts of Axon Framework, these too are inclined to align with the [async native API](#async-native-apis) switch.

### Interceptor Interfaces

This means that interceptors as of Axon Framework 5 take in a `ProcessingContext` as the second parameter. This replaces
the old [Unit of Work](#unit-of-work), most clearly on the `MessageHandlerInterceptor` as the old implementation had a
`UnitOfWork` parameter. For `MessageDispatchInterceptors`, implementations that validated if there was an active
`UnitOfWork` through the old thread local support should now validate the **nullable** `ProcessingContext` parameter
that is passed on intercepting.

Next to the `ProcessingContext`, both interceptor interface now have an interceptor chain parameter. For the
`MessageDispatchInterceptors` this is the `MessageDispatchInterceptorChain`, while for the `MessageHandlerInterceptor`
this is the `MessageHandlerInterceptorChain`. Providing the chain of interceptors allows implementers of handler and
dispatch interceptor to execute tasks before **and** after intercepting.

Additionally, the interceptor chain provides a means to deal with the **result** of invoking the next step in the chain.
This is a new feature for the `MessageDispatchInterceptor`, as it allows dispatch interceptor to deal with the result of
dispatching as well. This paradigm shift becomes further apparent with the expected return type of the handler and
dispatch interceptor, which is a `MessageStream` (as described [here](#message-stream) in detail).

For those that interacted with the `InterceptorChain`, note this chain is now specific for `MessageHandlerInterceptors`.
As such, it has been renamed to the `MessageHandlerInterceptorChain`. Furthermore, it now expects the `Message` and
`ProcessingContext` to be passed, just as any other message handling task.

Lastly, the `MessageDispatchInterceptorSupport` and `MessageHandlerInterceptorSupport` have been removed. This will
change the configuration of interceptors somewhat, as is explained in
the [Interceptor Configuration](#interceptor-configuration) section.

### Interceptor Implementations

Most of the default interceptor implementation that came with Axon Framework still exist in Axon Framework 5. The only
exceptions to this are the `EventLoggingInterceptor` and `TransactionManagingInterceptor`. Whenever the
`EventLoggingInterceptor` we recommend to use the `LoggingInterceptor`. The `TransactionManagingInterceptor` is replaced
entirely with the (new) `TransactionalUnitOfWorkFactory`, which constructs a transaction-aware `UnitOfWork` for all
message handling components in Axon Framework 5.

If you have custom implementations of the `MessageDispatchInterceptor` and/or `MessageHandlerInterceptor`, you will be
required to rewrite these to align with the new API. If you encounter any issues during such a rewrite, be sure to reach
out for guidance.

### Interceptor Configuration

The registration process for interceptors changed as well. Previously, components implemented the
`MessageDispatchInterceptorSupport` or `MessageHandlerInterceptorSupport` interface to support registration of
interceptors. This allows interceptor registration during runtime, which made it "the" oddball in configuring components
for Axon Framework. Furthermore, this approach inclined components to be constructed **before** we could register
interceptors to them. For example, to register a `MessageDispatchInterceptor` to the `CommandBus` in Axon Framework 4,
you needed to be sure the `CommandBus` was constructed first.

We felt this solution to be suboptimal and not in line with the overall configuration experience in Axon Framework.
As such, interceptors should now be registered with
the [ApplicationConfigurer](#applicationconfigurer-and-configuration). As interceptors are a general messaging concern,
the operations for registration are present on the `MessagingConfigurer`. Down below is a snippet configuring dispatch
and handler interceptors, both generically and for specific `Message` types:

```java
public static void main(String[] args) {
    MessagingConfigurer.create()
                       .registerMessageHandlerInterceptor(config -> new BeanValidationInterceptor<>()) // 1
                       .registerEventHandlerInterceptor(config -> new LoggingInterceptor<>()) // 2
                       .registerDispatchInterceptor(config -> new LoggingInterceptor<>()) // 3
                       .registerCommandDispatchInterceptor(config -> new BeanValidationInterceptor<>()); // 4
    // Further configuration...
}
```

1. The `BeanValidationInterceptor` is registered as a **generic** `MessageHandlerInterceptor`. Registering a generic
   handler interceptor this way ensure it is set on **all** message handling components.
2. The `LoggingInterceptor` is registered as an `EventMessage` **specific** `MessageHandlerInterceptor`. Registering a
   `Message`-specific `MessageHandlerInterceptor` ensures it is set only for that type. Thus, in this case, the
   `LoggingInterceptor` will only be configured for event handling, and not command and query handling.
3. The `LoggingInterceptor` is registered as a **generic** `MessageDispatchInterceptor`. Registering a generic dispatch
   interceptor this way ensure it is set on **all** message dispatching components.
4. The `BeanValidationInterceptor` is registered as an `CommandMessage` **specific** `MessageDispatchInterceptor`.
   Registering a `Message`-specific `MessageDispatchInterceptor` ensures it is set only for that type. Thus, in this
   case, the `BeanValidationInterceptor` will only be configured for command dispatching, and not event publication and
   query dispatching.

As shown, there is no need to interact with the specific message dispatching or handling infrastructure components
anymore to register interceptors.
If you would still require this, we recommend to use
the [decorator](#decorating-components-with-the-componentdecorator-interface) support within the configuration API to
decorate the specific component.

Lastly, if you are in a Spring Boot environment, you can simply provide your interceptors as beans to the Application
Context.
Axon Framework will automatically gather them and set them on their respective infrastructure components. The `Message`
generic specified on the `MessageHandlerInterceptor` and `MessageDispatchInterceptor` will be taken into account in our
auto-configuration, ensuring (e.g.) that `MessageDispatchInterceptor<QueryMessage>` beans are **only** used for query
dispatching components.

## Query Dispatching and Handling

This section describes numerous changes around Query Dispatching and Handling. For a reintroduction to the `QueryBus`
and `QueryGateway`, check [this](#query-bus) and [this](#query-gateway-and-response-types) section respectively. For the
newly **recommended** approach to dispatch queries from within another message handling function, please check
the [Query Dispatcher](#query-dispatcher) section.

> Notice - Scatter-Gather has been removed!
>
> We decided to remove the Scatter-Gather query support on the `QueryBus` and `QueryGateway` due to limited use.
> If you did use Scatter-Gather with success, be sure to reach out! We are more than willing to reintroduce
> scatter-gather support based on user experience. If so, be sure to leave a comment
> under [this](https://github.com/AxonFramework/AxonFramework/issues/3689) issue to nudge the Axon Framework team
> accordingly

### Query Bus

The `QueryBus` has undergone some API changes to align with the [Async Native API](#async-native-apis) and ease
of configuration. The alignment with the Async Native API shows itself in being able to provide the `ProcessingContext`.
Giving the active `ProcessingContext` is **paramount** if a query should be dispatched as part of a running message
handling task. For example, if an event handler should dispatch a query (e.g., as with process automations), it is
strongly advised to provide the active `ProcessingContext` as part of the dispatch operation.

The dispatch operations now align with the newly introduced [Message Stream](#message-stream). This, for example,
adjusts the `QueryBus#query` method to return a `MessageStream` of the `QueryResponseMessage` instead of a
`CompletableFuture`. As the `MessageStream` supports 0, 1, or N responses, this shifts lets the `QueryBus#query` method
align with whatever query result coming back from query handlers.

Streaming solutions, like `QueryBus#streamingQuery` or the `subscriptionQuery` still rely on `Publisher` and `Flux` as
the return type. However, internally, these also depend on the `MessageStream`.
Furthermore, the subscription query support has seen a rigorous adjustment, as
described [here](#subscription-queries-and-the-query-update-emitter).

#### Subscribing Query Handlers

Subscribing query handlers has been adjusted to allow easier registration of query handling lambdas. This shift was
combined with the new `QualifiedName` (as described [here](#message-type-and-qualified-name)) replacing the previous
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

The `QueryGateway` has undergone some minor API changes to align with the [Async Native API](#async-native-apis).
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

Due to our move towards an [Async Native API](#async-native-apis), the `ProcessingContext` (the renewed `UnitOfWork`)
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
`completeExceptionally`) based on the [qualified name](#message-type-and-qualified-name) of the subscription query.
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

* The `Repository`, just as other components, has been made [async native](#async-native-apis). This means methods
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
  as described in the [Event Store](#event-store) section. Furthermore, operations have been made "async-native," as
  described [here](#async-native-apis). This is marked as a minor API changes since the `EventStorageEngine` should not
  be used directly.
* The `RollbackConfiguration` interface and the `rollbackConfiguration()` builder method have been removed from all
  EventProcessor builders. Exceptions need to be handled by an interceptor, or otherwise they are always considered an
  error.
* The `Lifecycle` interface has been removed, as component lifecycle management is done on component registration. This
  allows component construction to be lazy instead of eager, since we do not require an active instance anymore (as was
  the case with the `Lifecycle` interface). Please read
  the [Component Lifecycle Management](#component-lifecycle-management) section for more details on this.
* The `SequencingPolicy` interface no longer uses generics and now operates directly on `EventMessage<?>`. This
  simplifies its usage and implementation, as many implementations do not depend on the payload type and can ignore it
  entirely.
* The `MessageHandlerInterceptor` and `MessageDispatchInterceptor` have undergone some minor changes to align with
  the [Async Native API](#async-native-apis) of Axon Framework 5. For more details, please check
  the [interceptors section](#message-handler-interceptors-and-dispatch-interceptors).
* The annotation logic of all modules is moved to a separate `annotations` package.
* All reflection logic is moved to a dedicated "reflection" package per module.
* The `MessageOriginaProvider` adjusted its use of `correlationId` and `traceId` to align with the current industry
  standard. As such, the old `traceId` is now the `correlationId`. Furthermore, the old `correlationId` is now called
  the `causationId`, as it refers to the `Message` that caused it. Thus, for those basing **any** logic on the old
  `Message#metadata` keys called `traceId` and `correlationId`, we recommend to either (1) override the
  `MessageOriginaProvider` to use the old format or (2) have a transition period from the old to the new approach.

Stored Format Changes
=====================

## Events

The JPA `org.axonframework.eventsourcing.eventstore.jpa.DomainEventEntry` is replaced entirely for the
`org.axonframework.eventsourcing.eventstore.jpa.AggregateEventEntry`.
This thus changes the default table name from `domain_event_entry` to `aggregate_event_entry`.

Besides the entry and table rename, several columns have been renamed compared to the `DomainEventEntry`, being:

1. `DomainEventEntry#eventIdentifier` (inherited from `AbstractEventEntry`) is now called
   `AggregateEventEntry#identifier`.
2. `DomainEventEntry#payloadType` (inherited from `AbstractEventEntry`) is now called `AggregateEventEntry#type`.
3. `DomainEventEntry#payloadRevision` (inherited from `AbstractEventEntry`) is now called `AggregateEventEntry#version`.
4. `DomainEventEntry#timeStamp` (inherited from `AbstractEventEntry`) is now called `AggregateEventEntry#timestamp`.
5. `DomainEventEntry#type` (inherited from `AbstractDomainEventEntry`) is now called
   `AggregateEventEntry#aggregateType`.
6. `DomainEventEntry#sequenceNumber` (inherited from `AbstractDomainEventEntry`) is now called
   `AggregateEventEntry#aggregateSequenceNumber`.
7. `DomainEventEntry#metaData` (inherited from `AbstractEventEntry`) is now called `AggregateEventEntry#metadata`.

Furthermore, some of the expectations placed on the fields have adjusted, being:

1. The `payloadRevision`, renamed to `version`, is **not** optional anymore.
2. The `payload` field no longer has a max column length of 10_000.
3. The `metadata` field no longer has a max column length of 10_000.
4. The `aggregateIdentifier` **is** optional right now.
5. The `sequenceNumber`, renamed to `aggregateSequenceNumber`, is **not** optional anymore.

Lastly, the sequence generator for the global index (resulting in the event's position in the event store) has been
specified in more detail for the `AggregateEventEntry`. The `DomainEventEntry` had a simple `@GeneratedValue`. With
the upgrade from Hibernate 5 to Hibernate 6, this caused issues, as the default sequence generator configuration
changed. Notable changes were switching to an automated generator type, using a unique sequence generator per table and
a default allocation size of 50.

The automated generator type selection is not ideal for Axon Framework. Hence, this is fixed to a sequence-based
generator.
The 'generator-per-table' is desired and as such specified for the `AggregateEventEntry` under the sequence name
`aggregate-event-global-index-sequence`. The default allocation size of 50 is far from desired, however. This
introduces large amounts of gaps, which will slow down event streaming to event processors. Hence, the allocation size
is fixed to 1 to minimize the amount of gaps. Although this enforces a round trip to the database to retrieve the
`AggregateEventEntry#globalIndex` for **every** event that is being appended, this outweighs the concerns on
consuming events through the `EventStorageEngine#stream(StreamingCondition)` method tremendously.

## Dead Letters

1. The JPA `org.axonframework.messaging.jpa.deadletter.eventhandling.DeadLetterEventEntry` has renamed the `messageType`
   column to `eventType`.
2. The JPA `org.axonframework.messaging.jpa.deadletter.eventhandling.DeadLetterEventEntry` has renamed the `type` column
   to `aggregateType`.
3. The JPA `org.axonframework.messaging.jpa.deadletter.eventhandling.DeadLetterEventEntry` expects the `QualifiedName`
   to be present under the `type` column, non-nullable.
4. The JDBC `org.axonframework.messaging.jdbc.deadletter.eventhandling.DeadLetterSchema` has renamed the `messageType`
   column to `eventType`.
5. The JDBC `org.axonframework.messaging.jdbc.deadletter.eventhandling.DeadLetterSchema` has renamed the `type` column
   to `aggregateType`.
6. The JDBC `org.axonframework.messaging.jdbc.deadletter.eventhandling.DeadLetterSchema` expects the `QualifiedName` to
   be present under the `type` column, non-nullable.

## Deadlines

1. The JobRunr `org.axonframework.deadline.jobrunr.DeadlineDetails` expects the `QualifiedName` to be present under the
   field `type`.
2. The Quartz `org.axonframework.deadline.quartz.DeadlineJob` expects the QualifiedName to be present in the
   `JobDataMap` under the key `qualifiedType`.
3. The dbscheduler `org.axonframework.deadline.dbscheduler.DbSchedulerBinaryDeadlineDetails` expects the `QualifiedName`
   to be present under the field `t`.
4. The dbscheduler `org.axonframework.deadline.dbscheduler.DbSchedulerHumanReadableDeadlineDetails` expects the
   `QualifiedName` to be present under the field `type`.

## TokenStore

1. A `mask` column containing the mask associated with each segment was added to avoid
   having to query all segments in order to calculate it.

Class and Method Changes
========================

## Package Changes

We introduced a new project module structure and moved code for Spring Support, Monitoring and Tracing into Extensions
module. By doing so we aligned the top level packages for those Maven Modules in the following matter:

| Axon 4 package name                     | Axon 5 package name                               |
|-----------------------------------------|---------------------------------------------------|
| org.axonframework.spring                | org.axonframework.extension.spring                | 
| org.axonframework.actuator              | org.axonframework.extension.springboot.actuator   | 
| org.axonframework.springboot            | org.axonframework.extension.springboot            | 
| org.axonframework.metrics               | org.axonframework.extension.metrics.dropwizard    | 
| org.axonframework.micrometer            | org.axonframework.extension.metrics.micrometer    | 
| org.axonframework.tracing.opentelemetry | org.axonframework.extension.tracing.opentelemetry | 

## Class Changes

This section contains five tables:

1. [Moved or Renamed Classes](#moved-or-renamed-classes)
2. [Removed Classes](#removed-classes)
3. [Classes marked for removal](#marked-for-removal-classes)
4. [Changed implements or extends](#changed-implements-or-extends)
5. [Adjusted Constants](#adjusted-constants)

### Moved or Renamed Classes

| Axon 4                                                                                                 | Axon 5                                                                                                         | Module change?                 |
|--------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------|--------------------------------|
| org.axonframework.common.caching.EhCache3Adapter                                                       | org.axonframework.common.caching.EhCacheAdapter                                                                | No                             |
| org.axonframework.axonserver.connector.util.ExecutorServiceBuilder                                     | org.axonframework.common.util.ExecutorServiceFactory                                                           | Yes. Moved to `axon-messaging` |
| org.axonframework.eventsourcing.MultiStreamableMessageSource                                           | org.axonframework.messaging.streaming.processors.eventhandling.MultiStreamableMessageSource                    | No                             |
| org.axonframework.messaging.eventhandling.EventBus                                                     | org.axonframework.messaging.eventhandling.EventSink                                                            | No                             |
| org.axonframework.messaging.sequencing.eventhandling.MetadataSequencingPolicy                          | org.axonframework.messaging.sequencing.eventhandling.MetadataSequencingPolicy                                  | No                             |
| org.axonframework.messaging.commandhandling.CommandHandler                                             | org.axonframework.messaging.commandhandling.annotation.CommandHandler                                          | No                             |
| org.axonframework.messaging.eventhandling.EventHandler                                                 | org.axonframework.messaging.eventhandling.annotation.EventHandler                                              | No                             |
| org.axonframework.messaging.queryhandling.QueryHandler                                                 | org.axonframework.messaging.queryhandling.annotation.QueryHandler                                              | No                             |
| org.axonframework.config.Configuration                                                                 | org.axonframework.common.configuration.Configuration                                                           | Yes. Moved to `axon-messaging` |
| org.axonframework.config.Component                                                                     | org.axonframework.common.configuration.Component                                                               | Yes. Moved to `axon-messaging` |
| org.axonframework.config.ConfigurerModule                                                              | org.axonframework.common.configuration.ConfigurationEnhancer                                                   | Yes. Moved to `axon-messaging` |
| org.axonframework.config.ModuleConfiguration                                                           | org.axonframework.common.configuration.Module                                                                  | Yes. Moved to `axon-messaging` |
| org.axonframework.config.LifecycleHandler                                                              | org.axonframework.common.configuration.LifecycleHandler                                                        | Yes. Moved to `axon-messaging` |
| org.axonframework.config.LifecycleOperations                                                           | org.axonframework.common.configuration.LifecycleRegistry                                                       | Yes. Moved to `axon-messaging` |
| org.axonframework.commandhandling.CommandCallback                                                      | org.axonframework.messaging.gateway.commandhandling.CommandResult                                              | No                             |
| org.axonframework.commandhandling.callbacks.FutureCallback                                             | org.axonframework.messaging.gateway.commandhandling.FutureCommandResult                                        | No                             |
| org.axonframework.modelling.MetaDataAssociationResolver                                                | org.axonframework.modelling.MetadataAssociationResolver                                                        | No                             |
| org.axonframework.modelling.command.Repository                                                         | org.axonframework.modelling.repository.Repository                                                              | No                             |
| org.axonframework.modelling.command.CommandTargetResolver                                              | org.axonframework.modelling.EntityIdResolver                                                                   | No                             |
| org.axonframework.modelling.command.ForwardingMode                                                     | org.axonframework.modelling.command.entity.child.EventTargetMatcher                                            | No                             |
| org.axonframework.modelling.command.AggregateMember                                                    | org.axonframework.modelling.entity.annotation.EntityMember                                                     | No                             |
| org.axonframework.modelling.command.inspection.AnnotatedAggregateMetaModelFactory                      | org.axonframework.modelling.entity.annotation.AnnotatedEntityMetamodel                                         | No                             |
| org.axonframework.modelling.command.inspection.AggregateMemberAnnotatedChildEntityCollectionDefinition | org.axonframework.modelling.entity.annotation.ListEntityModelDefinition                                        | No                             |
| org.axonframework.modelling.command.inspection.AggregateMemberAnnotatedChildEntityDefinition           | org.axonframework.modelling.entity.annotation.SingleEntityChildModelDefinition                                 | No                             |
| org.axonframework.modelling.command.inspection.AbstractChildEntityDefinition                           | org.axonframework.modelling.entity.annotation.AbstractEntityChildModelDefinition                               | No                             |
| org.axonframework.axonserver.connector.ServerConnectorConfigurerModule                                 | org.axonframework.axonserver.connector.AxonServerConfigurationEnhancer                                         | No                             |
| org.axonframework.conversion.CannotConvertBetweenTypesException                                        | org.axonframework.conversion.ConversionException                                                               | No                             |
| org.axonframework.conversion.json.JacksonSerializer                                                    | org.axonframework.conversion.jackson.JacksonConverter                                                          | No                             |
| org.axonframework.commandhandling.distributed.CommandDispatchException                                 | org.axonframework.messaging.commandhandling.CommandDispatchException                                           | No                             |
| org.axonframework.axonserver.connector.command.CommandPriorityCalculator                               | org.axonframework.messaging.commandhandling.CommandPriorityCalculator                                          | Yes. Moved to `axon-messaging` |
| org.axonframework.commandhandling.distribute.MetaDataRoutingStrategy                                   | org.axonframework.messaging.commandhandling.MetadataRoutingStrategy                                            | Yes. Moved to `axon-messaging` |
| org.axonframework.commandhandling.distribute.RoutingStrategy                                           | org.axonframework.messaging.commandhandling.RoutingStrategy                                                    | Yes. Moved to `axon-messaging` |
| org.axonframework.commandhandling.distribute.UnresolvedRoutingKeyPolicy                                | org.axonframework.messaging.commandhandling.UnresolvedRoutingKeyPolicy                                         | Yes. Moved to `axon-messaging` |
| org.axonframework.commandhandling.distribute.AnnotationRoutingStrategy                                 | org.axonframework.messaging.core.annotation.commandhandling.AnnotationRoutingStrategy                          | Yes. Moved to `axon-messaging` |
| org.axonframework.conversion.json.JacksonSerializer                                                    | org.axonframework.conversion.json.JacksonConverter                                                             | No                             |
| org.axonframework.springboot.SerializerProperties                                                      | org.axonframework.extension.springboot.ConverterProperties                                                     | No                             |
| org.axonframework.springboot.SerializerProperties.SerializerType                                       | org.axonframework.extension.springboot.ConverterProperties.ConverterType                                       | No                             |
| org.axonframework.messaging.InterceptorChain                                                           | org.axonframework.messaging.MessageHandlerInterceptorChain                                                     | No                             |
| org.axonframework.messaging.MetaData                                                                   | org.axonframework.messaging.Metadata                                                                           | No                             |
| org.axonframework.messaging.core.annotation.MetaDataValue                                              | org.axonframework.messaging.core.annotation.MetadataValue                                                      | No                             |
| org.axonframework.conversion.SerializationException                                                    | org.axonframework.conversion.ConversionException                                                               | No                             |
| org.axonframework.conversion.avro.AvroSerializer                                                       | org.axonframework.conversion.avro.AvroConverter                                                                | No                             |
| org.axonframework.conversion.avro.AvroSerializerStrategy                                               | org.axonframework.conversion.avro.AvroConverterStrategy                                                        | No                             |
| org.axonframework.conversion.avro.AvroSerializerStrategyConfig                                         | org.axonframework.conversion.avro.AvroConverterStrategyConfiguration                                           | No                             |
| org.axonframework.conversion.avro.SpecificRecordBaseSerializerStrategy                                 | org.axonframework.conversion.avro.SpecificRecordBaseConverterStrategy                                          | No                             |
| org.axonframework.commandhandling.annotation.CommandMessageHandlingMember                              | org.axonframework.commandhandling.annotation.CommandHandlingMember                                             | No                             |
| org.axonframework.modelling.command.inspection.ForwardingCommandMessageHandlingMember                  | org.axonframework.modelling.command.inspection.ForwardingCommandHandlingMember                                 | No                             |
| org.axonframework.modelling.command.inspection.ChildForwardingCommandMessageHandlingMember             | org.axonframework.modelling.command.inspection.ChildForwardingCommandHandlingMember                            | No                             |
| org.axonframework.queryhandling.annotation.MethodQueryMessageHandlerDefinition                         | org.axonframework.queryhandling.annotation.MethodQueryHandlerDefinition                                        | No                             |
| org.axonframework.eventsourcing.EventSourcingHandler                                                   | org.axonframework.eventsourcing.annotation.EventSourcingHandler                                                | No                             |
| org.axonframework.commandhandling.annotation.AnnotationCommandHandlerAdapter                           | org.axonframework.messaging.core.annotation.commandhandling.AnnotatedCommandHandlingComponent                  | No                             |
| org.axonframework.commandhandling.annotation.CommandHandlingMember                                     | org.axonframework.messaging.core.annotation.commandhandling.CommandHandlingMember                              | No                             |
| org.axonframework.commandhandling.annotation.MethodCommandHandlerDefinition                            | org.axonframework.messaging.core.annotation.commandhandling.MethodCommandHandlerDefinition                     | No                             |
| org.axonframework.commandhandling.annotation.RoutingKey                                                | org.axonframework.messaging.core.annotation.commandhandling.RoutingKey                                         | No                             |
| org.axonframework.common.annotation.AnnotationUtils                                                    | org.axonframework.common.annotation.AnnotationUtils                                                            | No                             |
| org.axonframework.common.annotation.PriorityAnnotationComparator                                       | org.axonframework.common.annotation.PriorityAnnotationComparator                                               | No                             |
| org.axonframework.deadline.annotation.DeadlineHandler                                                  | org.axonframework.deadline.annotation.DeadlineHandler                                                          | No                             |
| org.axonframework.deadline.annotation.DeadlineHandlingMember                                           | org.axonframework.deadline.annotation.DeadlineHandlingMember                                                   | No                             |
| org.axonframework.deadline.annotation.DeadlineMethodMessageHandlerDefinition                           | org.axonframework.deadline.annotation.DeadlineMethodMessageHandlerDefinition                                   | No                             |
| org.axonframework.messaging.interceptors.ExceptionHandler                                              | org.axonframework.messaging.core.annotation.interceptors.ExceptionHandler                                      | No                             |
| org.axonframework.messaging.interceptors.MessageHandlerInterceptor                                     | org.axonframework.messaging.core.annotation.interceptors.MessageHandlerInterceptor                             | No                             |
| org.axonframework.messaging.interceptors.ResultHandler                                                 | org.axonframework.messaging.core.annotation.interceptors.ResultHandler                                         | No                             |
| org.axonframework.messaging.core.annotation.AbstractAnnotatedParameterResolverFactory                  | org.axonframework.messaging.core.annotation.AbstractAnnotatedParameterResolverFactory                          | No                             |
| org.axonframework.messaging.core.annotation.AggregateType                                              | org.axonframework.messaging.core.annotation.AggregateType                                                      | No                             |
| org.axonframework.messaging.core.annotation.AggregateTypeParameterResolverFactory                      | org.axonframework.messaging.core.annotation.AggregateTypeParameterResolverFactory                              | No                             |
| org.axonframework.messaging.core.annotation.AnnotatedHandlerAttributes                                 | org.axonframework.messaging.core.annotation.AnnotatedHandlerAttributes                                         | No                             |
| org.axonframework.messaging.core.annotation.AnnotatedHandlerInspector                                  | org.axonframework.messaging.core.annotation.AnnotatedHandlerInspector                                          | No                             |
| org.axonframework.messaging.core.annotation.AnnotatedMessageHandlingMemberDefinition                   | org.axonframework.messaging.core.annotation.AnnotatedMessageHandlingMemberDefinition                           | No                             |
| org.axonframework.messaging.core.annotation.ChainedMessageHandlerInterceptorMember                     | org.axonframework.messaging.core.annotation.ChainedMessageHandlerInterceptorMember                             | No                             |
| org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition                                 | org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition                                         | No                             |
| org.axonframework.messaging.core.annotation.ClasspathHandlerEnhancerDefinition                         | org.axonframework.messaging.core.annotation.ClasspathHandlerEnhancerDefinition                                 | No                             |
| org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory                          | org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory                                  | No                             |
| org.axonframework.messaging.core.annotation.DefaultParameterResolverFactory                            | org.axonframework.messaging.core.annotation.DefaultParameterResolverFactory                                    | No                             |
| org.axonframework.messaging.core.annotation.FixedValueParameterResolver                                | org.axonframework.messaging.core.annotation.FixedValueParameterResolver                                        | No                             |
| org.axonframework.messaging.core.annotation.HandlerAttributes                                          | org.axonframework.messaging.core.annotation.HandlerAttributes                                                  | No                             |
| org.axonframework.messaging.core.annotation.HandlerComparator                                          | org.axonframework.messaging.core.annotation.HandlerComparator                                                  | No                             |
| org.axonframework.messaging.core.annotation.HandlerDefinition                                          | org.axonframework.messaging.core.annotation.HandlerDefinition                                                  | No                             |
| org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition                                  | org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition                                          | No                             |
| org.axonframework.messaging.core.annotation.HasHandlerAttributes                                       | org.axonframework.messaging.core.annotation.HasHandlerAttributes                                               | No                             |
| org.axonframework.messaging.core.annotation.InterceptorChainParameterResolverFactory                   | org.axonframework.messaging.core.annotation.InterceptorChainParameterResolverFactory                           | No                             |
| org.axonframework.messaging.core.annotation.MessageHandler                                             | org.axonframework.messaging.core.annotation.MessageHandler                                                     | No                             |
| org.axonframework.messaging.core.annotation.MessageHandlerInterceptorDefinition                        | org.axonframework.messaging.core.annotation.interceptors.MessageHandlerInterceptorDefinition                   | No                             |
| org.axonframework.messaging.core.annotation.MessageHandlerInterceptorMemberChain                       | org.axonframework.messaging.core.annotation.interceptors.MessageHandlerInterceptorMemberChain                  | No                             |
| org.axonframework.messaging.core.annotation.MessageInterceptingMember                                  | org.axonframework.messaging.core.annotation.interceptors.MessageInterceptingMember                             | No                             |
| org.axonframework.messaging.core.annotation.NoMoreInterceptors                                         | org.axonframework.messaging.core.annotation.interceptors.NoMoreInterceptors                                    | No                             |
| org.axonframework.messaging.core.annotation.ResultParameterResolverFactory                             | org.axonframework.messaging.core.annotation.interceptors.ResultParameterResolverFactory                        | No                             |
| org.axonframework.messaging.core.annotation.MessageHandlingMember                                      | org.axonframework.messaging.core.annotation.MessageHandlingMember                                              | No                             |
| org.axonframework.messaging.core.annotation.MessageIdentifier                                          | org.axonframework.messaging.core.annotation.MessageIdentifier                                                  | No                             |
| org.axonframework.messaging.core.annotation.MessageIdentifierParameterResolverFactory                  | org.axonframework.messaging.core.annotation.MessageIdentifierParameterResolverFactory                          | No                             |
| org.axonframework.messaging.core.annotation.AnnotatedMessageHandlingMember                             | org.axonframework.messaging.core.annotation.MethodInvokingMessageHandlingMember                                | No                             |
| org.axonframework.messaging.core.annotation.MultiHandlerDefinition                                     | org.axonframework.messaging.core.annotation.MultiHandlerDefinition                                             | No                             |
| org.axonframework.messaging.core.annotation.MultiHandlerEnhancerDefinition                             | org.axonframework.messaging.core.annotation.MultiHandlerEnhancerDefinition                                     | No                             |
| org.axonframework.messaging.core.annotation.MultiParameterResolverFactory                              | org.axonframework.messaging.core.annotation.MultiParameterResolverFactory                                      | No                             |
| org.axonframework.messaging.core.annotation.ParameterResolver                                          | org.axonframework.messaging.core.annotation.ParameterResolver                                                  | No                             |
| org.axonframework.messaging.core.annotation.ParameterResolverFactory                                   | org.axonframework.messaging.core.annotation.ParameterResolverFactory                                           | No                             |
| org.axonframework.messaging.core.annotation.PayloadParameterResolver                                   | org.axonframework.messaging.core.annotation.PayloadParameterResolver                                           | No                             |
| org.axonframework.messaging.core.annotation.ScopeDescriptorParameterResolverFactory                    | org.axonframework.messaging.core.annotation.ScopeDescriptorParameterResolverFactory                            | No                             |
| org.axonframework.messaging.core.annotation.SimpleResourceParameterResolverFactory                     | org.axonframework.messaging.core.annotation.SimpleResourceParameterResolverFactory                             | No                             |
| org.axonframework.messaging.core.annotation.SourceId                                                   | org.axonframework.messaging.core.annotation.SourceId                                                           | No                             |
| org.axonframework.messaging.core.annotation.SourceIdParameterResolverFactory                           | org.axonframework.messaging.core.annotation.SourceIdParameterResolverFactory                                   | No                             |
| org.axonframework.messaging.core.annotation.UnsupportedHandlerException                                | org.axonframework.messaging.core.annotation.UnsupportedHandlerException                                        | No                             |
| org.axonframework.messaging.core.annotation.WrappedMessageHandlingMember                               | org.axonframework.messaging.core.annotation.WrappedMessageHandlingMember                                       | No                             |
| org.axonframework.queryhandling.annotation.AnnotationQueryHandlerAdapter                               | org.axonframework.messaging.core.annotation.queryhandling.AnnotatedQueryHandlingComponent                      | No                             |
| org.axonframework.queryhandling.annotation.MethodQueryHandlerDefinition                                | org.axonframework.messaging.core.annotation.queryhandling.MethodQueryHandlerDefinition                         | No                             |
| org.axonframework.queryhandling.annotation.QueryHandler                                                | org.axonframework.messaging.core.annotation.queryhandling.QueryHandler                                         | No                             |
| org.axonframework.queryhandling.annotation.QueryHandlingMember                                         | org.axonframework.messaging.core.annotation.queryhandling.QueryHandlingMember                                  | No                             |
| org.axonframework.queryhandling.UpdateHandlerRegistration                                              | org.axonframework.queryhandling.UpdateHandler                                                                  | No                             |
| org.axonframework.queryhandling.SubscriptionQueryResult                                                | org.axonframework.queryhandling.SubscriptionQueryResponse                                                      | No                             |
| org.axonframework.queryhandling.DefaultSubscriptionQueryResult                                         | org.axonframework.queryhandling.GenericSubscriptionQueryResponse                                               | No                             |
| org.axonframework.axonserver.connector.query.subscription.AxonServerSubscriptionQueryResult            | org.axonframework.axonserver.connector.query.subscription.AxonServerSubscriptionQueryResponseMessages          | No                             |
| org.axonframework.eventhandling.processors.streaming.token.store.GenericTokenEntry                     | org.axonframework.messaging.jdbc.store.token.streaming.processors.eventhandling.JdbcTokenEntry                 | No                             |
| org.axonframework.messaging.SubscribableMessageSource                                                  | org.axonframework.messaging.SubscribableEventSource                                                            | No                             |
| org.axonframework.configuration.SubscribableMessageSourceDefinition                                    | org.axonframework.messaging.configuration.eventhandling.SubscribableEventSourceDefinition                      | No                             |
| org.axonframework.axonserver.connector.event.axon.PersistentStreamMessageSourceDefinition              | org.axonframework.axonserver.connector.event.axon.PersistentStreamEventSourceDefinition                        | No                             |
| org.axonframework.eventhandling.tokenstore.TokenStore                                                  | org.axonframework.messaging.store.token.streaming.processors.eventhandling.TokenStore                          | No                             |
| org.axonframework.eventhandling.tokenstore.ConfigToken                                                 | org.axonframework.messaging.store.token.streaming.processors.eventhandling.ConfigToken                         | No                             |
| org.axonframework.eventhandling.tokenstore.UnableToClaimTokenException                                 | org.axonframework.messaging.store.token.streaming.processors.eventhandling.UnableToClaimTokenException         | No                             |
| org.axonframework.eventhandling.tokenstore.UnableToInitializeTokenException                            | org.axonframework.messaging.store.token.streaming.processors.eventhandling.UnableToInitializeTokenException    | No                             |
| org.axonframework.eventhandling.tokenstore.UnableToRetrieveIdentifierException                         | org.axonframework.messaging.store.token.streaming.processors.eventhandling.UnableToRetrieveIdentifierException | No                             |
| org.axonframework.eventsourcing.eventstore.EmbeddedEventStore                                          | org.axonframework.eventsourcing.eventstore.StorageEngineBackedEventStore                                       | No                             |

### Removed Classes

| Class                                                                                    | Why                                                                                                                                            |
|------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------|
| org.axonframework.config.Configurer                                                      | Made obsolete through introduction of several `ApplicationConfigurer` instances (see [Configuration](#applicationconfigurer-and-configuration) |
| org.axonframework.messaging.unitofwork.AbstractUnitOfWork                                | Made obsolete through the rewrite of the `UnitOfWork` (see [Unit of Work](#unit-of-work))                                                      |
| org.axonframework.messaging.unitofwork.BatchingUnitOfWork                                | Made obsolete through the rewrite of the `UnitOfWork` (see [Unit of Work](#unit-of-work))                                                      |
| org.axonframework.messaging.unitofwork.CurrentUnitOfWork                                 | Made obsolete through the rewrite of the `UnitOfWork` (see [Unit of Work](#unit-of-work))                                                      |
| org.axonframework.messaging.unitofwork.DefaultUnitOfWork                                 | Made obsolete through the rewrite of the `UnitOfWork` (see [Unit of Work](#unit-of-work))                                                      |
| org.axonframework.messaging.unitofwork.ExecutionResult                                   | Made obsolete through the rewrite of the `UnitOfWork` (see [Unit of Work](#unit-of-work))                                                      |
| org.axonframework.messaging.unitofwork.MessageProcessingContext                          | Made obsolete through the rewrite of the `UnitOfWork` (see [Unit of Work](#unit-of-work))                                                      |
| org.axonframework.eventsourcing.eventstore.AbstractEventStore                            | Made obsolete through the rewrite of the `EventStore` (see [Event Store](#event-store).                                                        |
| org.axonframework.modelling.command.AggregateLifecycle                                   | Made obsolete through the rewrite of the `EventStore` (see [Event Store](#event-store).                                                        |
| org.axonframework.eventsourcing.conflictresolution.ConflictDescription                   | No longer supported in Axon Framework 5 due to limited use by the community.                                                                   |
| org.axonframework.eventsourcing.conflictresolution.ConflictExceptionSupplier             | No longer supported in Axon Framework 5 due to limited use by the community.                                                                   |
| org.axonframework.eventsourcing.conflictresolution.ConflictResolution                    | No longer supported in Axon Framework 5 due to limited use by the community.                                                                   |
| org.axonframework.eventsourcing.conflictresolution.ConflictResolver                      | No longer supported in Axon Framework 5 due to limited use by the community.                                                                   |
| org.axonframework.eventsourcing.conflictresolution.Conflicts                             | No longer supported in Axon Framework 5 due to limited use by the community.                                                                   |
| org.axonframework.eventsourcing.conflictresolution.ContextAwareConflictExceptionSupplier | No longer supported in Axon Framework 5 due to limited use by the community.                                                                   |
| org.axonframework.eventsourcing.conflictresolution.DefaultConflictDescription            | No longer supported in Axon Framework 5 due to limited use by the community.                                                                   |
| org.axonframework.eventsourcing.conflictresolution.DefaultConflictResolver               | No longer supported in Axon Framework 5 due to limited use by the community.                                                                   |
| org.axonframework.eventsourcing.conflictresolution.NoConflictResolver                    | No longer supported in Axon Framework 5 due to limited use by the community.                                                                   |
| org.axonframework.modelling.command.ConflictingAggregateVersionException                 | No longer supported in Axon Framework 5 due to limited use by the community.                                                                   |
| org.axonframework.modelling.command.ConflictingModificationException                     | No longer supported in Axon Framework 5 due to limited use by the community.                                                                   |
| org.axonframework.modelling.command.TargetAggregateVersion                               | No longer supported in Axon Framework 5 due to limited use by the community.                                                                   |
| org.axonframework.modelling.command.VersionedAggregateIdentifier                         | No longer supported in Axon Framework 5 due to limited use by the community.                                                                   |
| org.axonframework.lifecycle.Lifecycle                                                    | [Lifecycle management](#component-lifecycle-management) is now only done lazy, eliminating the need for concrete component scanning.           |
| org.axonframework.config.LifecycleHandlerInspector                                       | [Lifecycle management](#component-lifecycle-management) is now only done lazy, eliminating the need for concrete component scanning.           |
| org.axonframework.lifecycle.StartHandler                                                 | [Lifecycle management](#component-lifecycle-management) is now only done lazy, eliminating the need for concrete component scanning.           |
| org.axonframework.lifecycle.ShutdownHandler                                              | [Lifecycle management](#component-lifecycle-management) is now only done lazy, eliminating the need for concrete component scanning.           |
| org.axonframework.eventhandling.TrackingEventProcessor                                   | Removed in favor of `PooledStreamingEventProcessor` (see [Event Processors](#event-processors)).                                               |
| org.axonframework.eventhandling.TrackingEventProcessorConfiguration                      | Removed along with `TrackingEventProcessor` (see [Event Processors](#event-processors)).                                                       |
| org.axonframework.eventsourcing.eventstore.jpa.JpaEventStorageEngine                     | Replaced in favor of the `AggregateBasedJpaEventStorageEngine`                                                                                 |
| org.axonframework.messaging.eventhandling.EventData                                      | Removed in favor of the `EventMessage` carrying all required data to map from stored to read formats.                                          |
| org.axonframework.messaging.eventhandling.AbstractEventEntry                             | Replaced by `...`                                                                                                                              |
| org.axonframework.messaging.eventhandling.DomainEventData                                | Removed in favor of the `EventMessage` carrying all required data to map from stored to read formats.                                          |
| org.axonframework.messaging.eventhandling.AbstractDomainEventEntry                       | Replaced by org.axonframework.eventsourcing.eventstore.jpa.AggregateEventEntry                                                                 |
| org.axonframework.messaging.eventhandling.GenericDomainEventEntry                        | Replaced by org.axonframework.eventsourcing.eventstore.jpa.AggregateEventEntry                                                                 |
| org.axonframework.messaging.eventhandling.AbstractSequencedDomainEventEntry              | Replaced by org.axonframework.eventsourcing.eventstore.jpa.AggregateEventEntry                                                                 |
| org.axonframework.eventsourcing.eventstore.jpa.DomainEventEntry                          | Replaced by org.axonframework.eventsourcing.eventstore.jpa.AggregateEventEntry                                                                 |
| org.axonframework.messaging.eventhandling.TrackedEventData                               | Removed in favor of adding a `TrackingToken` to the context of a `MessageStream.Entry`                                                         |
| org.axonframework.messaging.eventhandling.TrackedDomainEventData                         | Removed in favor of adding a `TrackingToken` to the context of a `MessageStream.Entry`                                                         |
| org.axonframework.messaging.Headers                                                      | Removed due to lack of use and foreseen use.                                                                                                   |
| org.axonframework.messaging.SubscribableMessageSource                                    | Replaced by `org.axonframework.messaging.SubscribableEventSource`, bacause just `EventMessage`s can be sourced.                                |
| org.axonframework.config.EventProcessingModule                                           | Removed due to changes in the Configuration API (see [Event Processors](#event-processors)).                                                   |
| org.axonframework.config.EventProcessingConfiguration                                    | Removed due to changes in the Configuration API (see [Event Processors](#event-processors)).                                                   |
| org.axonframework.config.EventProcessingConfigurer                                       | Removed due to changes in the Configuration API (see [Event Processors](#event-processors)).                                                   |
| org.axonframework.eventhandling.pooled.PooledStreamingEventProcessor.Builder             | Removed in favor of `PooledStreamingEventProcessorConfiguration` (see [Event Processors](#event-processors)).                                  |
| org.axonframework.eventhandling.subscribing.SubscribingEventProcessor.Builder            | Removed in favor of `SubscribingEventProcessorConfiguration` (see [Event Processors](#event-processors)).                                      |
| org.axonframework.axonserver.connector.event.axon.AxonServerEventStore                   | Removed in favor of the `AxonServerEventStorageEngine`                                                                                         |
| org.axonframework.axonserver.connector.event.axon.AxonServerEventStoreFactory            | Removed in favor of the `AxonServerEventStorageEngineFactory`                                                                                  |
| org.axonframework.axonserver.connector.event.axon.EventBuffer                            | Removed in favor of the `AxonServerMessageStream`, `SourcingEventMessageStream`, and `StreamingEventMessageStream`                             |
| org.axonframework.axonserver.connector.event.axon.GrpcBackedDomainEventData              | Removed as mapping is done to an `EventMessage` directly.                                                                                      |
| org.axonframework.axonserver.connector.event.axon.GrpcMetaDataAwareSerializer            | See [here](#metadata-with-string-values).                                                                                                      |
| org.axonframework.axonserver.connector.event.axon.QueryResult                            | Removed in favor of `EventCriteria` use.                                                                                                       |
| org.axonframework.axonserver.connector.event.axon.QueryResultStream                      | Removed in favor of `EventCriteria` use.                                                                                                       |
| org.axonframework.axonserver.connector.event.axon.QueryResultStreamAdapter               | Removed in favor of `EventCriteria` use.                                                                                                       |
| org.axonframework.conversion.xml.XStreamSerializer                                       | No longer supported in Axon Framework 5 due to undesired reflection support.                                                                   |
| org.axonframework.conversion.AbstractXStreamSerializer                                   | No longer supported in Axon Framework 5 due to undesired reflection support.                                                                   |
| org.axonframework.conversion.xml.CompactDriver                                           | No longer supported in Axon Framework 5 due to undesired reflection support.                                                                   |
| org.axonframework.conversion.xml.Dom4JToByteArrayConverter                               | No longer supported in Axon Framework 5 due to undesired reflection support.                                                                   |
| org.axonframework.conversion.xml.InputStreamToDom4jConverter                             | No longer supported in Axon Framework 5 due to undesired reflection support.                                                                   |
| org.axonframework.conversion.xml.InputStreamToXomConverter                               | No longer supported in Axon Framework 5 due to undesired reflection support.                                                                   |
| org.axonframework.conversion.xml.XomToStringConverter                                    | No longer supported in Axon Framework 5 due to undesired reflection support.                                                                   |
| SerializerProperties.SerializerType#XSTREAM                                              | No longer supported in Axon Framework 5 due to undesired reflection support.                                                                   |
| org.axonframework.eventsourcing.eventstore.EqualRevisionPredicate                        | Removed due to removal of the `DomainEventData`.                                                                                               |
| org.axonframework.eventhandling.interceptors.EventLoggingInterceptor                     | Removed as there is a more generic `LoggingInterceptor`                                                                                        |
| org.axonframework.axonserver.connector.DispatchInterceptors                              | Removed in favour of `DefaultDispatchInterceptorChain`                                                                                         |
| org.axonframework.commandhandling.CommandCallback                                        | See [here](#command-dispatching-and-handling).                                                                                                 |
| org.axonframework.commandhandling.callbacks.FailureLoggingCallback                       | See [here](#command-dispatching-and-handling).                                                                                                 |
| org.axonframework.commandhandling.callbacks.LoggingCallback                              | See [here](#command-dispatching-and-handling).                                                                                                 |
| org.axonframework.commandhandling.callbacks.NoOpCallback                                 | See [here](#command-dispatching-and-handling).                                                                                                 |
| org.axonframework.commandhandling.MonitorAwareCallback                                   | See [here](#command-dispatching-and-handling).                                                                                                 |
| org.axonframework.commandhandling.callbacks.FutureCallback                               | See [here](#command-dispatching-and-handling).                                                                                                 |
| org.axonframework.commandhandling.distributed.CommandCallbackWrapper                     | See [here](#command-dispatching-and-handling).                                                                                                 |
| org.axonframework.commandhandling.WrappedCommandCallback                                 | See [here](#command-dispatching-and-handling).                                                                                                 |
| org.axonframework.commandhandling.gateway.RetryingCallback                               | See [here](#command-dispatching-and-handling).                                                                                                 |
| org.axonframework.commandhandling.AsynchronousCommandBus                                 | See [here](#command-dispatching-and-handling).                                                                                                 |
| org.axonframework.disruptor.commandhandling.AggregateBlacklistedException                | See [here](#command-dispatching-and-handling).                                                                                                 |
| org.axonframework.disruptor.commandhandling.AggregateStateCorruptedException             | See [here](#command-dispatching-and-handling).                                                                                                 |
| org.axonframework.disruptor.commandhandling.BlacklistDetectingCallback                   | See [here](#command-dispatching-and-handling).                                                                                                 |
| org.axonframework.disruptor.commandhandling.CommandHandlerInvoker                        | See [here](#command-dispatching-and-handling).                                                                                                 |
| org.axonframework.disruptor.commandhandling.CommandHandlingEntry                         | See [here](#command-dispatching-and-handling).                                                                                                 |
| org.axonframework.disruptor.commandhandling.DisruptorCommandBus                          | See [here](#command-dispatching-and-handling).                                                                                                 |
| org.axonframework.disruptor.commandhandling.DisruptorUnitOfWork                          | See [here](#command-dispatching-and-handling).                                                                                                 |
| org.axonframework.disruptor.commandhandling.EventPublisher                               | See [here](#command-dispatching-and-handling).                                                                                                 |
| org.axonframework.commandhandling.gateway.AbstractCommandGateway                         | See [here](#command-dispatching-and-handling).                                                                                                 |
| org.axonframework.commandhandling.gateway.CommandGatewayFactory                          | See [here](#command-dispatching-and-handling).                                                                                                 |
| org.axonframework.commandhandling.gateway.Timeout                                        | See [here](#command-dispatching-and-handling).                                                                                                 |
| org.axonframework.messaging.interceptors.TransactionManagingInterceptor                  | Replaced by the `UnitOfWorkFactory` constructing transaction-aware UoWs.                                                                       |
| org.axonframework.messaging.MessageDispatchInterceptorSupport                            | See [here](#message-handler-interceptors-and-dispatch-interceptors)                                                                            |
| org.axonframework.messaging.MessageHandlerInterceptorSupport                             | See [here](#message-handler-interceptors-and-dispatch-interceptors)                                                                            |
| org.axonframework.messaging.MessageHandlerInterceptorSupport                             | See [here](#message-handler-interceptors-and-dispatch-interceptors)                                                                            |
| org.axonframework.springboot.autoconfig.InfraConfiguration                               | Removed in favour of `InfrastructureConfiguration`                                                                                             | 
| org.axonframework.spring.stereotype.Aggregate                                            | Removed in favour of `org.axonframework.extension.spring.stereotype.EventSourced`                                                              | 
| org.axonframework.queryhandling.QueryHandlerAdapter                                      | Redundant class with current handler registration flow                                                                                         |
| org.axonframework.queryhandling.registration.DuplicateQueryHandlerResolution             | Redundant class with current handler registration flow                                                                                         |
| org.axonframework.queryhandling.registration.DuplicateQueryHandlerResolver               | Redundant class with current handler registration flow                                                                                         |
| org.axonframework.queryhandling.registration.DuplicateQueryHandlerSubscriptionException  | Redundant class with current handler registration flow                                                                                         |
| org.axonframework.queryhandling.registration.FailingDuplicateQueryHandlerResolver        | Redundant class with current handler registration flow                                                                                         |
| org.axonframework.queryhandling.registration.LoggingDuplicateQueryHandlerResolver        | Redundant class with current handler registration flow                                                                                         |
| org.axonframework.queryhandling.QuerySubscription                                        | Redundant class with current handler registration flow                                                                                         |
| org.axonframework.queryhandling.QueryInvocationErrorHandler                              | Removed together with scatter-gather query removal, as described [here](#query-dispatching-and-handling)                                       |
| org.axonframework.queryhandling.LoggingQueryInvocationErrorHandler                       | Removed together with scatter-gather query removal, as described [here](#query-dispatching-and-handling)                                       |
| org.axonframework.queryhandling.SubscriptionQueryResponse                                | Removed to simplify the Subscription Query API to use a single stream with responses.                                                          |
| org.axonframework.queryhandling.UpdateHandler                                            | Removed to simplify the Subscription Query API to use a single stream with responses.                                                          |
| org.axonframework.eventhandling.processors.streaming.token.store.AbstractTokenEntry      | Content of the methods pushed up into `JdbcTokenEntry` as the only implementer.                                                                |
| org.axonframework.eventhandling.processors.subscribing.EventProcessingStrategy           | The sync/async processing is supported on `EventHandlingComponent` level                                                                       |
| org.axonframework.eventhandling.processors.subscribing.DirectEventProcessingStrategy     | The sync/async processing is supported on `EventHandlingComponent` level                                                                       |
| org.axonframework.queryhandling.StreamingQueryMessage                                    | Removed due to removal of `ResponseType`. Described [here](#query-dispatching-and-handling) why.                                               |
| org.axonframework.queryhandling.GenericStreamingQueryMessage                             | Removed due to removal of `ResponseType`. Described [here](#query-dispatching-and-handling) why.                                               |
| org.axonframework.messaging.responsetypes.AbstractResponseType                           | Removed to simplify querying and support none-JVM space, as described [here](#query-gateway-and-response-types).                               |
| org.axonframework.messaging.responsetypes.InstanceResponseType                           | Removed to simplify querying and support none-JVM space, as described [here](#query-gateway-and-response-types).                               |
| org.axonframework.messaging.responsetypes.MultipleInstancesResponseType                  | Removed to simplify querying and support none-JVM space, as described [here](#query-gateway-and-response-types).                               |
| org.axonframework.messaging.responsetypes.OptionalResponseType                           | Removed to simplify querying and support none-JVM space, as described [here](#query-gateway-and-response-types).                               |
| org.axonframework.messaging.responsetypes.PublisherResponseType                          | Removed to simplify querying and support none-JVM space, as described [here](#query-gateway-and-response-types).                               |
| org.axonframework.messaging.responsetypes.ResponseType                                   | Removed to simplify querying and support none-JVM space, as described [here](#query-gateway-and-response-types).                               |
| org.axonframework.messaging.responsetypes.ResponseTypes                                  | Removed to simplify querying and support none-JVM space, as described [here](#query-gateway-and-response-types).                               |
| org.axonframework.conversion.Revision                                                    | See [here](#revision--version-resolution)                                                                                                      |
| org.axonframework.conversion.RevisionResolver                                            | See [here](#revision--version-resolution)                                                                                                      |
| org.axonframework.conversion.FixedValueRevisionResolver                                  | See [here](#revision--version-resolution)                                                                                                      |
| org.axonframework.conversion.MavenArtifactRevisionResolver                               | See [here](#revision--version-resolution)                                                                                                      |
| org.axonframework.conversion.AnnotationRevisionResolver                                  | See [here](#revision--version-resolution)                                                                                                      |
| org.axonframework.messaging.monitoring.MessageMonitorFactory                             | Obsolete due to configuration changes.                                                                                                         |

### Marked for removal Classes

All classes in this table have been moved to the legacy package for ease in migration.
However, they will eventually be removed entirely from Axon Framework 5, as we expect users to migrate to the new (and
per class described) approach.

| Class                                          |
|------------------------------------------------|
| org.axonframework.modelling.command.Repository |
| org.axonframework.modelling.command.EntityId   |

### Changed implements or extends

Note that **any**  changes here may have far extending impact on the original class.

| Class                     | Before                    | After                 | Explanation                                                        | 
|---------------------------|---------------------------|-----------------------|--------------------------------------------------------------------|
| `MetaData`                | `Map<String, ?>`          | `Map<String, String>` | See the [metadata description](#metadata-with-string-values)       |
| `SubscriptionQueryResult` | `implements Registration` | nothing               | The `Registration#cancel` method moved to a local `close()` method |

### Adjusted Constants

| Class               | Constant                    | Change                                | Why                                   |
|---------------------|-----------------------------|---------------------------------------|---------------------------------------|
| `HandlerAttributes` | `START_PHASE`               | Removed                               | StartHandler annotation is removed    |
| `HandlerAttributes` | `SHUTDOWN_PHASE`            | Removed                               | ShutdownHandler annotation is removed |
| `TagsUtil`          | `META_DATA_TAGGER_FUNCTION` | Renamed to `METADATA_TAGGER_FUNCTION` | Consistent spelling                   |

## Method Signature Changes

This section contains four subsections, called:

1. [Constructor Parameter adjustments](#constructor-parameter-adjustments)
2. [Moved methods and constructors](#moved--renamed-methods-and-constructors)
3. [Removed methods and constructors](#removed-methods-and-constructors)
4. [Changed Method return types](#changed-method-return-types)

### Constructor Parameter adjustments

| Constructor                                                                                        | What                                                                                         | Why                                                            | 
|----------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|----------------------------------------------------------------|
| One org.axonframework.messaging.AbstractMessage constructor                                        | Added the `MessageType` type                                                                 | See [here](#message-type-and-qualified-name)                   |
| One org.axonframework.conversion.SerializedMessage constructor                                     | Added the `MessageType` type                                                                 | See [here](#message-type-and-qualified-name)                   |
| All none-copy org.axonframework.messaging.GenericMessage constructors                              | Added the `MessageType` type                                                                 | See [here](#message-type-and-qualified-name)                   |
| All none-copy org.axonframework.messaging.commandhandling.GenericCommandMessage constructors       | Added the `MessageType` type                                                                 | See [here](#message-type-and-qualified-name)                   |
| All none-copy org.axonframework.messaging.eventhandling.GenericEventMessage constructors           | Added the `MessageType` type                                                                 | See [here](#message-type-and-qualified-name)                   |
| All none-copy org.axonframework.messaging.eventhandling.GenericDomainEventMessage constructors     | Added the `MessageType` type                                                                 | See [here](#message-type-and-qualified-name)                   |
| All none-copy org.axonframework.messaging.queryhandling.GenericQueryMessage constructors           | Added the `MessageType` type                                                                 | See [here](#message-type-and-qualified-name)                   |
| All none-copy org.axonframework.queryhandling.GenericSubscriptionQueryMessage constructors         | Added the `MessageType` type                                                                 | See [here](#message-type-and-qualified-name)                   |
| All none-copy org.axonframework.queryhandling.GenericStreamingQueryMessage constructors            | Added the `MessageType` type                                                                 | See [here](#message-type-and-qualified-name)                   |
| All none-copy org.axonframework.deadline.GenericDeadlineMessage constructors                       | Added the `MessageType` type                                                                 | See [here](#message-type-and-qualified-name)                   |
| All none-copy org.axonframework.messaging.GenericResultMessage constructors                        | Added the `MessageType` type                                                                 | See [here](#message-type-and-qualified-name)                   |
| All none-copy org.axonframework.messaging.commandhandling.GenericCommandResultMessage constructors | Added the `MessageType` type                                                                 | See [here](#message-type-and-qualified-name)                   |
| All none-copy org.axonframework.messaging.queryhandling.GenericQueryResponseMessage constructors   | Added the `MessageType` type                                                                 | See [here](#message-type-and-qualified-name)                   |
| All org.axonframework.messaging.queryhandling.GenericSubscriptionQueryUpdateMessage constructors   | Added the `MessageType` type                                                                 | See [here](#message-type-and-qualified-name)                   |
| `DefaultSubscriptionQueryResult`                                                                   | Replaced `Mono` and `Flux` for `SubscriptionQueryResponseMessages` and payload map functions | See [here](#subscription-queries-and-the-query-update-emitter) |
| `JpaTokenStore`                                                                                    | Replaced `Serializer` with `Converter`                                                       | See [here](#Serialization-Conversion-changes)                  |
| `JdbcTokenStore`                                                                                   | Replaced `Serializer` with `Converter`                                                       | See [here](#Serialization-Conversion-changes)                  |

### Moved, Renamed, or parameter adjusted Methods

| Constructor / Method                                                                                                            | To where                                                                                                               |
|---------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| `Configurer#configureCommandBus`                                                                                                | `MessagingConfigurer#registerCommandBus`                                                                               | 
| `Configurer#configureEventBus`                                                                                                  | `MessagingConfigurer#registerEventSink`                                                                                | 
| `Configurer#configureQueryBus`                                                                                                  | `MessagingConfigurer#registerQueryBus`                                                                                 | 
| `Configurer#configureQueryUpdateEmitter`                                                                                        | `MessagingConfigurer#registerQueryUpdateEmitter`                                                                       | 
| `ConfigurerModule#configureModule`                                                                                              | `ConfigurationEnhancer#enhance`                                                                                        | 
| `ConfigurerModule#configureLifecyclePhaseTimeout`                                                                               | `LifecycleRegistry#registerLifecyclePhaseTimeout`                                                                      | 
| `Configurer#registerComponent(Function<Configuration, ? extends C>)`                                                            | `ComponentRegistry#registerComponent(ComponentBuilder<C>)`                                                             | 
| `Configurer#registerModule(ModuleConfiguration)`                                                                                | `ComponentRegistry#registerComponent(Module)`                                                                          | 
| `StreamableMessageSource#openStream(TrackingToken)`                                                                             | `StreamableEventSource#open(SourcingCondition)`                                                                        | 
| `StreamableMessageSource#createTailToken()`                                                                                     | `StreamableEventSource#firstToken()`                                                                                   | 
| `StreamableMessageSource#createHeadToken()`                                                                                     | `StreamableEventSource#latestToken()`                                                                                  | 
| `StreamableMessageSource#createTokenAt(Instant)`                                                                                | `StreamableEventSource#tokenAt(Instant)`                                                                               | 
| `Repository#newInstance(Callable<T>)`                                                                                           | `Repository#persist(ID, T, ProcessingContext)`                                                                         | 
| `Repository#load(String)`                                                                                                       | `Repository#load(ID, ProcessingContext)`                                                                               | 
| `Repository#loadOrCreate(String, Callable<T>)`                                                                                  | `Repository#loadOrCreate(ID, ProcessingContext)`                                                                       | 
| `EventStore#readEvents(String)`                                                                                                 | `EventStoreTransaction#source(SourcingCondition)`                                                                      | 
| `EventStorageEngine#readEvents(EventMessage<?>...)`                                                                             | `EventStorageEngine#appendEvents(AppendCondition, TaggedEventMessage...)`                                              | 
| `EventStorageEngine#appendEvents(List<? extends EventMessage<?>>)`                                                              | `EventStorageEngine#appendEvents(AppendCondition, List<TaggedEventMessage<?>>)`                                        | 
| `EventStorageEngine#appendEvents(List<? extends EventMessage<?>>)`                                                              | `EventStorageEngine#appendEvents(AppendCondition, List<TaggedEventMessage<?>>)`                                        | 
| `EventStorageEngine#readEvents(String)`                                                                                         | `EventStorageEngine#source(SourcingCondition)`                                                                         | 
| `EventStorageEngine#readEvents(String, long)`                                                                                   | `EventStorageEngine#source(SourcingCondition)`                                                                         | 
| `EventStorageEngine#readEvents(TrackingToken, boolean)`                                                                         | `EventStorageEngine#stream(StreamingCondition)`                                                                        | 
| `EventStorageEngine#createTailToken()`                                                                                          | `EventStorageEngine#firstToken()`                                                                                      | 
| `EventStorageEngine#createHeadToken()`                                                                                          | `EventStorageEngine#latestToken()`                                                                                     | 
| `EventStorageEngine#createTokenAt(Instant)`                                                                                     | `EventStorageEngine#tokenAt(Instant)`                                                                                  | 
| `StreamingEventProcessor#resetTokens(Function<StreamableMessageSource<TrackedEventMessage<?>>, TrackingToken>)`                 | `StreamingEventProcessor#resetTokens(Function<TrackingTokenSource, CompletableFuture<TrackingToken>>)`                 |
| `StreamingEventProcessor#resetTokens(Function<StreamableMessageSource<TrackedEventMessage<?>>, TrackingToken>, R resetContext)` | `StreamingEventProcessor#resetTokens(Function<TrackingTokenSource, CompletableFuture<TrackingToken>>, R resetContext)` |
| `PooledStreamingEventProcessor.Builder#initialToken(Function<StreamableMessageSource<TrackedEventMessage<?>>, TrackingToken>)`  | `PooledStreamingEventProcessor.Builder#initialToken(Function<TrackingTokenSource, CompletableFuture<TrackingToken>>)`  |
| `PooledStreamingEventProcessor.Builder#messageSource(StreamableMessageSource<TrackedEventMessage<?>>)`                          | `PooledStreamingEventProcessor.Builder#eventSource(StreamableEventSource<? extends EventMessage<?>>)`                  |
| `Converter#convert(Object, Class<T>)`                                                                                           | `Converter.#convert(S, Class<T>)`                                                                                      |
| `Converter#convert(Object, Class<?>, Class<T>)`                                                                                 | `Converter.#convert(S, Class<S>, Class<T>)`                                                                            |
| `EventGateway#publish(Object...)`                                                                                               | `EventGateway#publish(ProcessingContext, Object...)`                                                                   |
| `EventGateway#publish(List<?>)`                                                                                                 | `EventGateway#publish(ProcessingContext, List<?>)`                                                                     |
| `SequencingPolicy#getSequenceIdentifierFor(Object)`                                                                             | `SequencingPolicy#getSequenceIdentifierFor(EventMessage<?>, ProcessingContext)`                                        |
| `Message#getIdentifier()`                                                                                                       | `Message#identifier()`                                                                                                 |
| `Message#getPayload()`                                                                                                          | `Message#payload()`                                                                                                    |
| `Message#getPayloadType()`                                                                                                      | `Message#payloadType()`                                                                                                |
| `Message#getMetaData()`                                                                                                         | `Message#metadata()`                                                                                                   |
| `Message#andMetaData()`                                                                                                         | `Message#andMetadata()`                                                                                                |
| `Message#withMetaData()`                                                                                                        | `Message#withMetadata()`                                                                                               |
| `EventMessage#getTimestamp()`                                                                                                   | `EventMessage#timestamp()`                                                                                             |
| `QueryMessage#getReponseType()`                                                                                                 | `QueryMessage#responseType()`                                                                                          | 
| `SubscriptionQueryMessage#getUpdateReponseType()`                                                                               | `SubscriptionQueryMessage#updatesResponseType()`                                                                       | 
| `CommandBus#dispatch(CommandMessage<C>)`                                                                                        | `CommandBus#dispatch(CommandMessage<?>, ProcessingContext)`                                                            | 
| `CommandBus#subscribe(String, MessageHandler<? super CommandMessage<?>>)`                                                       | `CommandBus#subscribe(QualifiedName, CommandHandler)`                                                                  | 
| `CommandGateway#sendAndWait(Object)`                                                                                            | `CommandGateway#sendAndWait(Object, Class<R>)`                                                                         | 
| `CommandGateway#send(Object)`                                                                                                   | `CommandGateway#send(Object, ProcessingContext, Class<R>)`                                                             | 
| `MessageDispatchInterceptor#handle(T)`                                                                                          | `MessageDispatchInterceptor#interceptOnDispatch(M, ProcessingContext, MessageDispatchInterceptorChain<M>)`             | 
| `MessageHandlerInterceptor#handle(UnitOfWork<T>, InterceptorChain)`                                                             | `MessageHandlerInterceptor#interceptOnHandle(M, ProcessingContext, MessageHandlerInterceptorChain<M>)`                 | 
| `InterceptorChain#proceed()`                                                                                                    | `MessageHandlerInterceptorChain#proceed(M, ProcessingContext)`                                                         | 
| `QueryBus#subscribe(String, Type, MessageHandler<? super QueryMessage<?, R>>)`                                                  | `QueryBus#subscribe(QualifiedName, QualifiedName, QueryHandler)`                                                       | 
| `QueryBus#query(QueryMessage)`                                                                                                  | `QueryBus#query(QueryMessage, ProcessingContext)`                                                                      | 
| `QueryGateway#query(Q, Class<R>)`                                                                                               | `QueryGateway#query(Object, Class<R>, ProcessingContext)`                                                              | 
| `QueryGateway#query(String, Q, ResponseType<R>)`                                                                                | `QueryGateway#queryMany(Object, Class<R>, ProcessingContext)`                                                          | 
| `QueryHandlingMember#getQueryName()`                                                                                            | `QueryHandlingMember#queryName()`                                                                                      | 
| `QueryHandlingMember#getResultType()`                                                                                           | `QueryHandlingMember#resultType()`                                                                                     | 
| `QueryUpdateEmitter#registerUpdateHandler(SubscriptionQueryMessage, int)`                                                       | `QueryUpdateEmitter#subscribeUpdateHandler(SubscriptionQueryMessage, int)`                                             | 
| `UpdateHandlerRegistration#getUpdates()`                                                                                        | `UpdateHandler#updates()`                                                                                              | 
| `SubscriptionQueryResult#cancel()`                                                                                              | `SubscriptionQueryResponse#close()`                                                                                    | 
| `QueryUpdateEmitter#emit(Predicate<SubscriptionQueryMessage<?, ?, U>>, SubscriptionQueryUpdateMessage)`                         | `QueryBus#emitUpdate(Predicate<SubscriptionQueryMessage>, SubscriptionQueryUpdateMessage, ProcessingContext)`          | 
| `QueryUpdateEmitter#complete(Predicate<SubscriptionQueryMessage<?, ?, ?>>)`                                                     | `QueryBus#completeSubscriptions(Predicate<SubscriptionQueryMessage>, ProcessingContext)`                               | 
| `QueryUpdateEmitter#completeExceptionally(Predicate<SubscriptionQueryMessage<?, ?, ?>>, Throwable)`                             | `QueryBus#completeSubscriptionsExceptionally(Predicate<SubscriptionQueryMessage>, Throwable, ProcessingContext)`       | 
| `QueryUpdateEmitter#registerUpdateHandler(SubscriptionQueryMessage<?, ?, ?>, int)`                                              | `QueryBus#subscribeToUpdates(SubscriptionQueryMessage, int)`                                                           |
| `TokenStore#initializeTokenSegments(String, int, TrackingToken)`                                                                | `TokenStore#initializeTokenSegments(String, int, TrackingToken, ProcessingContext)`                                    |
| `TokenStore#storeToken(TrackingToken, String, int)`                                                                             | `TokenStore#storeToken(String, int, TrackingToken, ProcessingContext)`                                                 |
| `TokenStore#fetchToken(String, Segment)`                                                                                        | `TokenStore#fetchToken(String, Segment, ProcessingContext)`                                                            |
| `TokenStore#extendClaim(String, int)`                                                                                           | `TokenStore#extendClaim(String, int, ProcessingContext)`                                                               |
| `TokenStore#releaseClaim(String, int)`                                                                                          | `TokenStore#releaseClaim(String, int, ProcessingContext)`                                                              |
| `TokenStore#initializeSegment(TrackingToken, String, int)`                                                                      | `TokenStore#initializeSegment(TrackingToken, String, Segment, ProcessingContext)`                                      |
| `TokenStore#deleteToken(String, int)`                                                                                           | `TokenStore#deleteToken(String, int, ProcessingContext)`                                                               |
| `TokenStore#fetchSegments(String)`                                                                                              | `TokenStore#fetchSegments(String, ProcessingContext)`                                                                  |
| `TokenStore#fetchAvailableSegments(String)`                                                                                     | `TokenStore#fetchAvailableSegments(String, ProcessingContext)`                                                         |
| `TokenStore#retrieveStorageIdentifier()`                                                                                        | `TokenStore#retrieveStorageIdentifier(ProcessingContext)`                                                              |

### Removed Methods and Constructors

| Constructor / Method                                                                                                        | Why                                                                                                                         | 
|-----------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| `org.axonframework.config.ModuleConfiguration#initialize(Configuration)`                                                    | Initialize is now replace fully by start and shutdown handlers.                                                             |
| `org.axonframework.config.ModuleConfiguration#unwrap()`                                                                     | Unwrapping never reached its intended use in AF3 and AF4 and is thus redundant.                                             |
| `org.axonframework.config.ModuleConfiguration#isType(Class<?>)`                                                             | Only use by `unwrap()` that's also removed.                                                                                 |
| `org.axonframework.config.Configuration#lifecycleRegistry()`                                                                | A round about way to support life cycle handler registration.                                                               |
| `org.axonframework.config.Configurer#onInitialize(Consumer<Configuration>)`                                                 | Fully replaced by start and shutdown handler registration.                                                                  |
| `org.axonframework.config.Configurer#defaultComponent(Class<T>, Configuration)`                                             | Each Configurer now has get optional operation replacing this functionality.                                                |
| `org.axonframework.messaging.StreamableMessageSource#createTokenSince(Duration)`                                            | Can be replaced by the user with an `StreamableEventSource#tokenAt(Instant)` invocation.                                    |
| `org.axonframework.modelling.command.Repository#load(String, Long)`                                                         | Leftover behavior to support aggregate validation on subsequent invocations.                                                |
| `org.axonframework.modelling.command.Repository#newInstance(Callable<T>, Consumer<Aggregate<T>>)`                           | No longer necessary with replacement `Repository#persist(ID, T, ProcessingContext)`.                                        |
| `org.axonframework.eventsourcing.eventstore.EventStore#readEvents(String)`                                                  | Replaced for the `EventStoreTransaction` (see [appending events](#appending-events).                                        | 
| `org.axonframework.eventsourcing.eventstore.EventStore#readEvents(String, long)`                                            | Replaced for the `EventStoreTransaction` (see [appending events](#appending-events).                                        | 
| `org.axonframework.eventsourcing.eventstore.EventStore#storeSnapshot(DomainEventMessage<?>)`                                | Replaced for a dedicated `SnapshotStore`.                                                                                   |
| `org.axonframework.eventsourcing.eventstore.EventStore#lastSequenceNumberFor(String)`                                       | No longer necessary to support through the introduction of DCB.                                                             |
| `org.axonframework.eventsourcing.eventstore.EventStorageEngine#storeSnapshot(DomainEventMessage<?>)`                        | Replaced for a dedicated `SnapshotStore`.                                                                                   |
| `org.axonframework.eventsourcing.eventstore.EventStorageEngine#readSnapshot(String)`                                        | Replaced for a dedicated `SnapshotStore`.                                                                                   |
| `org.axonframework.eventsourcing.eventstore.EventStorageEngine#lastSequenceNumberFor(String)`                               | No longer necessary to support through the introduction of DCB.                                                             |
| `org.axonframework.eventsourcing.CachingEventSourcingRepository#validateOnLoad(Aggregate<T>, Long)`                         | Version-based loading is no longer supported due to limited use by the community.                                           |
| `org.axonframework.eventsourcing.CachingEventSourcingRepository#doLoadWithLock(String, Long)`                               | Version-based loading is no longer supported due to limited use by the community.                                           |
| `org.axonframework.eventsourcing.EventSourcingRepository#doLoadWithLock(String, Long)`                                      | Version-based loading is no longer supported due to limited use by the community.                                           |
| `org.axonframework.modelling.command.AbstractRepository#load(String, Long)`                                                 | Version-based loading is no longer supported due to limited use by the community.                                           |
| `org.axonframework.modelling.command.GenericJpaRepository#doLoadWithLock(String, Long)`                                     | Version-based loading is no longer supported due to limited use by the community.                                           |
| `org.axonframework.modelling.command.LockingRepository#doLoad(String, Long)`                                                | Version-based loading is no longer supported due to limited use by the community.                                           |
| `org.axonframework.modelling.command.LockingRepository#doLoadWithLock(String, Long)`                                        | Version-based loading is no longer supported due to limited use by the community.                                           |
| `org.axonframework.modelling.command.Repository#load(String, Long)`                                                         | Version-based loading is no longer supported due to limited use by the community.                                           |
| `org.axonframework.modelling.command.Aggregate#version()`                                                                   | Version-based loading is no longer supported due to limited use by the community.                                           |
| `org.axonframework.modelling.command.LockAwareAggregate#version()`                                                          | Version-based loading is no longer supported due to limited use by the community.                                           |
| `org.axonframework.deadline.dbscheduler.DbSchedulerDeadlineManager.Builder#startScheduler(boolean)`                         | [Lifecycle management](#component-lifecycle-management) has become a configuration concern.                                 |
| `org.axonframework.deadline.dbscheduler.DbSchedulerDeadlineManager.Builder#stopScheduler(boolean)`                          | [Lifecycle management](#component-lifecycle-management) has become a configuration concern.                                 |
| `org.axonframework.config.EventProcessingConfigurer#registerTrackingEventProcessor`                                         | Removed along with `TrackingEventProcessor`. Use `registerPooledStreamingEventProcessor` instead.                           |
| `org.axonframework.config.EventProcessingConfigurer#registerTrackingEventProcessorConfiguration`                            | Removed along with `TrackingEventProcessorConfiguration`. Use `registerPooledStreamingEventProcessorConfiguration` instead. |
| `org.axonframework.messaging.processors.eventhandling.EventProcessor#getHandlerInterceptors()`                              | Interceptors will be configured on the `EventHandlingComponent` level instead of the `EventProcessor`.                      |
| `org.axonframework.messaging.processors.eventhandling.EventProcessor#registerHandlerInterceptor(MessageHandlerInterceptor)` | Interceptors will be configured on the `EventHandlingComponent` level instead of the `EventProcessor`.                      |
| `PooledStreamingEventProcessor.Builder#coordinatorExecutor(Function<String, ScheduledExecutorService>)`                     | Removed due to changes in the Configuration API (see [Event Processors](#event-processors))                                 |
| `PooledStreamingEventProcessor.Builder#workerExecutor(Function<String, ScheduledExecutorService>)`                          | Removed due to changes in the Configuration API (see [Event Processors](#event-processors))                                 |
| `CommandBus#dispatch(CommandMessage<C>, CommandCallback<?,?>)`                                                              | See [here](#command-dispatching-and-handling).                                                                              |
| `CommandGateway#send(C, CommandCallback<?,?>)`                                                                              | See [here](#command-dispatching-and-handling).                                                                              |
| `CommandGateway#sendAndWait(Object, MetaData)`                                                                              | See [here](#command-dispatching-and-handling).                                                                              |
| `CommandGateway#sendAndWait(Object, long, TimeUnit)`                                                                        | See [here](#command-dispatching-and-handling).                                                                              |
| `CommandGateway#sendAndWait(Object, MetaData, long, TimeUnit)`                                                              | See [here](#command-dispatching-and-handling).                                                                              |
| `CommandGateway#send(Object, MetaData)`                                                                                     | See [here](#command-dispatching-and-handling).                                                                              |
| `org.axonframework.axonserver.connector.AxonServerConfiguration#getCommitTimeout`                                           | Removed as the `EventStorageEngine` is now asynchronous (we don't have to wait for commits).                                |
| `org.axonframework.axonserver.connector.AxonServerConfiguration#setCommitTimeout(int)`                                      | Removed as the `EventStorageEngine` is now asynchronous (we don't have to wait for commits).                                |
| `org.axonframework.axonserver.connector.AxonServerConfiguration#isEventBlockListingEnabled `                                | Removed as the `EventCriteria` allow for automated filtering.                                                               |
| `org.axonframework.axonserver.connector.AxonServerConfiguration#setEventBlockListingEnabled(boolean)`                       | Removed as the `EventCriteria` allow for automated filtering.                                                               |
| `MessageDispatchInterceptor#handle(List<? extends T>)`                                                                      | Removed due to limited usage.                                                                                               |
| `PropertySequencingPolicy#builder`                                                                                          | Use constructor instead. To define fallbackSequencingPolicy use `FallbackSequencingPolicy`.                                 |
| `EventProcessor#shutdownAsync()`                                                                                            | Use `shutdown` instead. It returns `CompletableFuture<Void>` since the version 5.0.0.                                       |
| `org.axonframework.spring.stereotype.Aggregate#repository`                                                                  | Conceptually configured on `EventSourcedEntityModule`.                                                                      |
| `org.axonframework.spring.stereotype.Aggregate#snapshotTriggerDefinition`                                                   | Removed and will be replaced as part of #3105.                                                                              |
| `org.axonframework.spring.stereotype.Aggregate#snapshotFilter`                                                              | Removed and will be replaced as part of #3105.                                                                              |
| `org.axonframework.spring.stereotype.Aggregate#commandTargetResolver`                                                       | Conceptually moved to `CommandHandlingComponent`.                                                                           |
| `org.axonframework.spring.stereotype.Aggregate#filterEventsByType`                                                          | Removed as not needed.                                                                                                      |
| `org.axonframework.spring.stereotype.Aggregate#cache`                                                                       | Conceptually configured on `EventSourcedEntityModule`.                                                                      |
| `org.axonframework.spring.stereotype.Aggregate#lockFactory`                                                                 | Conceptually configured on `EventSourcedEntityModule`.                                                                      |
| `QueryBus#scatterGather(QueryMessage<Q, R>, long, TimeUnit)`                                                                | Removed due to limited use (see [Query Dispatching and Handling](#query-dispatching-and-handling)                           |
| `QueryGateway#scatterGather(Q, ResponseType<R>, long, TimeUnit)`                                                            | Removed due to limited use (see [Query Dispatching and Handling](#query-dispatching-and-handling)                           |
| `QueryUpdateEmitter#queryUpdateHandlerRegistered(SubscriptionQueryMessage)`                                                 | Moved duplicate handler registration into `QueryUpdateEmitter#subscribeUpdateHandler` method.                               |
| `UpdateHandlerRegistration#getRegistration()`                                                                               | Replaced for `UpdateHandler#cancel()`                                                                                       |
| `QueryUpdateEmitter#activeSubscriptions()`                                                                                  | Removed due to limited use (see [Query Dispatching and Handling](#query-dispatching-and-handling)                           |
| `SubscribingEventProcessor#getMessageSource()`                                                                              | Removed due to no usages.                                                                                                   |
| `org.axonframework.eventhandling.tokenstore.TokenStore#initializeTokenSegments(String, int)`                                | Removed due to limited usage                                                                                                |
| `org.axonframework.eventhandling.tokenstore.TokenStore#requiresExplicitSegmentInitialization()`                             | Removed due to explicit initialization now being a requirement                                                              |

### Changed Method return types

| Method                                                                         | Before                                       | After                                        |
|--------------------------------------------------------------------------------|----------------------------------------------|----------------------------------------------|
| `CorrelationDataProvider#correlationDataFor()`                                 | `Map<String, String>`                        | `Map<String, ?>`                             | 
| `CommandTargetResolver#resolveTarget`                                          | `VersionedAggregateIdentifier`               | `String`                                     |
| `EventGateway#publish(Object...)`                                              | `void`                                       | `CompletableFuture<Void>`                    |
| `EventGateway#publish(List<?>)`                                                | `void`                                       | `CompletableFuture<Void>`                    |
| `SequencingPolicy#getSequenceIdentifierFor(List<?>)`                           | `Object`                                     | `Optional<Object>`                           |
| `CommandBus#dispatch(CommandMessage<C>)`                                       | `void`                                       | `CompletableFuture<CommandResultMessage<?>>` |
| `CommandBus#subscribe(String, MessageHandler<? super CommandMessage<?>>)`      | `Registration`                               | `<? extends CommandHandlerRegistry>`         |
| `CommandGateway#sendAndWait(Object)`                                           | `R`                                          | `void`                                       |
| `MessageDispatchInterceptor#handle(T)`                                         | `T`                                          | `MessageStream<?>`                           |
| `MessageHandlerInterceptor#handle(UnitOfWork<T>, InterceptorChain)`            | `Object`                                     | `MessageStream<?>`                           |
| `InterceptorChain#proceed()`                                                   | `Object`                                     | `MessageStream<?>`                           |
| `EventProcessor#start()`                                                       | `void`                                       | `CompletableFuture<Void>`                    |
| `EventProcessor#shutdown()`                                                    | `void`                                       | `CompletableFuture<Void>`                    |
| `StreamingEventProcessor#releaseSegment`                                       | `void`                                       | `CompletableFuture<Void>`                    |
| `StreamingEventProcessor#resetTokens`                                          | `void`                                       | `CompletableFuture<Void>`                    |
| `QueryBus#subscribe(String, Type, MessageHandler<? super QueryMessage<?, R>>)` | `Registration`                               | `void`                                       |
| `QueryBus#query(QueryMessage<Q, R>)`                                           | `CompletableFuture<QueryResponseMessage<R>>` | `MessageStream<QueryResponseMessage>`        |
| `QueryGateway#query(String, Q, ResponseType<R>)`                               | `CompletableFuture<R>`                       | `CompletableFuture<List<R>>`                 |`
| `SubscriptionQueryResult#initialResult()`                                      | `Mono<I>`                                    | `Flux<I>`                                    |`
| `QueryMessage#responseType()`                                                  | `ResponseType<?>`                            | `MessageType`                                |`
| `SubscriptionQueryMessage#updatesResponseType()`                               | `ResponseType<?>`                            | `MessageType`                                |`
| `TokenStore#initializeTokenSegments`                                           | `void`                                       | `CompletableFuture<List<Segment>>`           |
| `TokenStore#storeToken`                                                        | `void`                                       | `CompletableFuture<Void>`                    |
| `TokenStore#fetchToken`                                                        | `TrackingToken`                              | `CompletableFuture<TrackingToken>`           |
| `TokenStore#extendClaim`                                                       | `void`                                       | `CompletableFuture<Void>`                    |
| `TokenStore#releaseClaim`                                                      | `void`                                       | `CompletableFuture<Void>`                    |
| `TokenStore#initializeSegment`                                                 | `void`                                       | `CompletableFuture<Void>`                    |
| `TokenStore#deleteToken`                                                       | `void`                                       | `CompletableFuture<Void>`                    |
| `TokenStore#fetchSegments`                                                     | `void`                                       | `CompletableFuture<List<Segment>>`           |
| `TokenStore#fetchAvailableSegments`                                            | `void`                                       | `CompletableFuture<List<Segment>>`           |
| `TokenStore#retrieveStorageIdentifier`                                         | `Optional<String>`                           | `CompletableFuture<String>`                  |
