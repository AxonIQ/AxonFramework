# Axon Framework 5 — API Changes: ApplicationConfigurer and Configuration

> Part of the Axon Framework 4→5 migration guide.
> Covers: new layered configuration API (`MessagingConfigurer`, `ModellingConfigurer`, `EventSourcingConfigurer`),
> `ComponentBuilder` / `ComponentRegistry`, `ComponentDecorator`, `ConfigurationEnhancer` (replacing `ConfigurerModule`),
> `ModuleBuilder`, `ComponentFactory`, component lifecycle management (removal of `@StartHandler` / `@ShutdownHandler`),
> and how to access lower-level configurer methods.

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

