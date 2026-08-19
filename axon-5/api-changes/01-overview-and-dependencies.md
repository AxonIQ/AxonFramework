# Axon Framework 5 — API Changes: Overview and Dependencies

> Part of the Axon Framework 4→5 migration guide.
> Covers: document structure, version/dependency requirements, and a high-level summary of all breaking change areas.

API Changes
===========

As is to be expected of a new major release, a lot of things have changed compared to the previous major release. This
document serves the purpose of containing all the changes that may prove breaking to users. Some of the changes have a
lower chance of directly impacting users of Axon Framework 4 (like the [Message Stream](03-messages-and-stream.md#message-stream)), while others
certainly impact all users (like the [Test Fixture](07-entities-and-test-fixtures.md#test-fixtures) adjustment).

This document can be broken down in five sections:

1. [Version and Dependency Compatibility](#version-and-dependency-compatibility)
2. [Major API Changes](#major-api-changes)
3. [Minor API Changes](09-queries-and-minor-changes.md#minor-api-changes)
4. [Stored Format Changes](10-stored-format-changes.md#stored-format-changes)
5. [Class and Method Changes](11-class-reference.md#class-and-method-changes)

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
  check the [Unit of Work](02-processing-context.md#unit-of-work) section for more details if you are facing this predicament.
* Messages have undergone a number of major changes. Firstly, they now contain a `MessageType`, decoupling a messages (
  business) type from Java's type system. You can find more details on this [here](03-messages-and-stream.md#message-type-and-qualified-name).
  Secondly, the `Metadata` of each `Message` now reflects a `Map<String, String>` instead of `Map<String, ?>`, thus
  forcing metadata values to strings. Please read [this](03-messages-and-stream.md#metadata-with-string-values) section for more details on this
  shift. Other noteworthy adjustments, are the removal of the [static
  `Message` factory methods](03-messages-and-stream.md#factory-methods-like-genericmessageasmessageobject)
  and [renaming of all getters](03-messages-and-stream.md#message-method-renames).
* All message-based infrastructure in Axon Framework will return the `MessageStream` interface. The `MessageStream` is
  intended to support empty results, results of one entry, and results of N entries, thus mirroring Event Handlers (no
  results), Command Handlers (one result), and Query Handlers (N results). Added, the `MessageStream` will function as a
  replacement for components like the `DomainEventStream` and `BlockingStream` on the `EventStore`. As such, the
  `MessageStream` changes **a lot** of (public) APIs within Axon Framework. Please check
  the [Message Stream](03-messages-and-stream.md#message-stream) section for more details.
* The API of all infrastructure components is rewritten to be "async native." This means that the
  aforementioned [Unit of Work](02-processing-context.md#unit-of-work) adjustments flow through most APIs, as well as the use of
  a [Message Stream](03-messages-and-stream.md#message-stream) to provide a way to support imperative and reactive message handlers. See
  the [Async Native APIs](03-messages-and-stream.md#async-native-apis) section for a list of all classes that have undergone changes.
* Axon's `EventStore` implementations let go their aggregate-focus, instead following the "Dynamic Consistency
  Boundary" approach. This shift changed the `EventStore` and `EventStorageEngine` API heavily, providing a lot of
  flexibility in defining how entities are event sourced and how events are appended for them. Although most users won't
  interact with the `EventStore` or `EventStorageEngine` directly, knowing the changes could still prove beneficial. For
  those that are curious, be sure to read the [Event Store](05-event-store-and-processors.md#event-store) section.
* The Configuration of Axon Framework has been flipped around. Instead of having a `axon-configuration` module that
  depends on all of Axon's modules to provide a global configuration, the core module (`axon-messaging`) of Axon now
  contains a `Configurer` with a base set of operations. This `Configurer` can either take `Components` or `Modules`.
  The former typically represents an infrastructure component (e.g. the `CommandBus`) whereas modules are themselves
  configurers for a specific module of an application. For an exhaustive list of all the operations that have been
  removed, moved, or altered, see the [Configurer and Configuration](06-configuration.md#applicationconfigurer-and-configuration) section.
* Event Processors have undergone a significant change with the removal of `TrackingEventProcessor`. The
  `PooledStreamingEventProcessor` is now the default and recommended
  streaming event processor, providing enhanced performance and better resource utilization. See the
  [Event Processors](05-event-store-and-processors.md#event-processors) section for more details on this transition.
* The Test Fixtures have been replaced by an approach that, instead of an Aggregate or Saga class, take in an
  `ApplicationConfigurer` instance. In doing so, test fixtures reflect the actual application configuration. This
  resolves the predicament that you need to configure your application twice (for production and testing), making the
  chance slimmer that parts will be skipped. For more on this change, please check the [Test Fixtures](07-entities-and-test-fixtures.md#test-fixtures)
  section of this document.
* Aggregates are now referred to as Entities, as the Dynamic Consistency Boundary allows for more fluid boundaries
  around entities.
  In addition, entities have been redesigned to make them more flexible, allowing for immutable
  entities, declarative modeling, and a more fluent API. For more on this, check the
  [Aggregates to Entities](07-entities-and-test-fixtures.md#aggregates-to-entities) section.
* We have switched the `Serializer` for the lower-level `Converter` API throughout Axon Framework. Furthermore, we
  stopped support for the `XStreamSerializer` altogether, making the `JacksonConverter` the default. For more details on
  the `Serializer`-to-`Converter` switch, please check [here](08-serialization-and-interceptors.md#serialization--conversion-changes).

