# Axon Framework 5 — API Changes: Messages and MessageStream

> Part of the Axon Framework 4→5 migration guide.
> Covers: `MessageType` / `QualifiedName` introduction, removal of factory methods, `Metadata` changed to
> `Map<String,String>`, message getter renames, `Message` conversion API, the new `MessageStream` abstraction,
> and a summary of all components that became async-native.

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

The changes incurred by the new [Unit of Work](02-processing-context.md#unit-of-work) and [Message Stream](#message-stream) combined form the
basis to make Axon Framework what we have dubbed "Async Native." In other words, it is intended to make Axon Framework
fully asynchronous, top to bottom, without requiring people to deal with asynchronous programming details (e.g.
`CompletableFuture` / `Mono`) at each and every turn.

This shift has an obvious impact on the API of Axon Framework's infrastructure components. The APIs now favor the use of
the `ProcessingContext`, `MessageStream`, and are generally made asynchronous through the use of a `CompletableFuture`.
As these APIs are in most cases not directly invoked by the user, they should typically not form an obstruction.
Nonetheless, if you **do** use these operations, it is good to know they've changed with the desire to be async native.

The following classes have undergone changes to accompany this shift:

* The `CommandBus` - Read [here](04-commands.md#command-dispatching-and-handling) for more details.
* The `CommandGateway` - Read [here](04-commands.md#command-dispatching-and-handling) for more details.
* The `EventStorageEngine` - Read [here](05-event-store-and-processors.md#event-storage) for more details.
* The `EventStore` - Read [here](05-event-store-and-processors.md#event-store) for more details.
* The `EventProcessors` - Read [here](05-event-store-and-processors.md#event-processors) for more details.
* The `Repository`
* The `StreamableMessageSource`
* The `QueryBus` - Read [here](09-queries-and-minor-changes.md#query-dispatching-and-handling) for more details.
* The `QueryGateway` - Read [here](09-queries-and-minor-changes.md#query-dispatching-and-handling) for more details.

