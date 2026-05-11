# Axon Framework 5 — API Changes: Serialization, Conversion, and Interceptors

> Part of the Axon Framework 4→5 migration guide.
> Covers: removal of `Serializer` in favor of `Converter` API, `JacksonConverter` as new default,
> removal of XStream support, `MessageConverter` / `EventConverter` levels, `RevisionResolver` replaced by
> `MessageTypeResolver`, `@Revision` replaced by `@Command` / `@Event` / `@Query`;
> interceptor interface changes (`ProcessingContext` parameter, `MessageStream` return type),
> removal of `MessageDispatchInterceptorSupport` / `MessageHandlerInterceptorSupport`,
> interceptor registration via `MessagingConfigurer`.

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
described [here](03-messages-and-stream.md#metadata-with-string-values)) and the conversion support on the `Message` directly (as
described [here](03-messages-and-stream.md#message-conversion--serialization)). The changes on the `Message` directly are more apparent to the
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
towards a [Message Type](03-messages-and-stream.md#message-type-and-qualified-name) carrying the `version`, defining the version is no longer
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

As many parts of Axon Framework, these too are inclined to align with the [async native API](03-messages-and-stream.md#async-native-apis) switch.

### Interceptor Interfaces

This means that interceptors as of Axon Framework 5 take in a `ProcessingContext` as the second parameter. This replaces
the old [Unit of Work](02-processing-context.md#unit-of-work), most clearly on the `MessageHandlerInterceptor` as the old implementation had a
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
dispatch interceptor, which is a `MessageStream` (as described [here](03-messages-and-stream.md#message-stream) in detail).

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
the [ApplicationConfigurer](06-configuration.md#applicationconfigurer-and-configuration). As interceptors are a general messaging concern,
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
the [decorator](06-configuration.md#decorating-components-with-the-componentdecorator-interface) support within the configuration API to
decorate the specific component.

Lastly, if you are in a Spring Boot environment, you can simply provide your interceptors as beans to the Application
Context.
Axon Framework will automatically gather them and set them on their respective infrastructure components. The `Message`
generic specified on the `MessageHandlerInterceptor` and `MessageDispatchInterceptor` will be taken into account in our
auto-configuration, ensuring (e.g.) that `MessageDispatchInterceptor<QueryMessage>` beans are **only** used for query
dispatching components.

