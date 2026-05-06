# Relevant migration-path excerpts

Quoted from `docs/reference-guide/modules/migration/pages/paths/`.

## configuration.adoc — `Configurer` split

> The `Configurer` and `DefaultConfigurer` from Axon Framework 4 have
> been replaced by three focused configurers in Axon Framework 5,
> called `ApplicationConfigurers`, each corresponding to a layer of
> the framework. Choose the one that matches the highest-level feature
> your application uses:
>
> * `MessagingConfigurer` - for applications that only require
>   messaging (commands, events, queries)
> * `ModellingConfigurer` - adds entity (formerly aggregate) and
>   repository support on top of messaging
> * `EventSourcingConfigurer` - adds event sourcing and event store
>   support on top of modelling
>
> These configurers form a delegation chain: `EventSourcingConfigurer`
> wraps `ModellingConfigurer`, which wraps `MessagingConfigurer`.
> When Axon Framework is combined with Spring you will typically never
> have to interact with any of these `ApplicationConfigurer` types.
> Instead, you take Spring's bean creation approach or adjust the
> configuration through the `ConfigurationEnhancer`.

> Each configurer provides escape-hatch methods to access the
> underlying layer when needed:
>
> ```java
> EventSourcingConfigurer configurer = EventSourcingConfigurer.create();
>
> // Access the modelling layer
> configurer.modelling(modellingConfigurer -> modellingConfigurer.registerEntity(/*...*/));
>
> // Access the messaging layer
> configurer.messaging(messagingConfigurer -> messagingConfigurer.registerCommandBus(config -> /*...*/));
> ```

## configuration.adoc — `ConfigurerModule` → `ConfigurationEnhancer`

> The `ConfigurerModule` interface from Axon Framework 4 allowed
> modules to hook into the configuration process. […] In Axon
> Framework 5, this concept has been renamed and slightly adjusted to
> the `ConfigurationEnhancer`.
>
> Largest difference between both, is that the latter deals with the
> `ComponentRegistry` (used under the hood by the
> `ApplicationConfigurer`).

> ```java
> // AF4
> public class MyConfigurerModule implements ConfigurerModule {
>     @Override
>     public void configureModule(Configurer configurer) {
>         configurer.registerComponent(MyService.class, config -> new MyService());
>     }
> }
>
> // AF5
> public class MyConfigurationEnhancer implements ConfigurationEnhancer {
>     @Override
>     public void enhance(ComponentRegistry registry) {
>         registry.registerComponent(MyService.class, config -> new MyService());
>     }
> }
> ```

## configuration.adoc — Spring Boot bean rewrite

> When using Spring Boot, the auto-configuration automatically sets up
> the appropriate configurer. Moving from the `ConfigurerModule` to
> the `ConfigurationEnhancer` in your beans is arguably most
> important:
>
> ```java
> // AF4
> @Configuration
> class AxonConfig {
>     @Bean
>     public ConfigurerModule myModule() {
>         return configurer -> configurer.registerComponent(
>                 MyService.class,
>                 config -> new MyService());
>     }
> }
>
> // AF5
> @Configuration
> class AxonConfig {
>     @Bean
>     public ConfigurationEnhancer myEnhancer() {
>         return registry -> registry.registerComponent(
>                 MyService.class,
>                 config -> new MyService());
>     }
> }
> ```

## configuration.adoc — Lifecycle handler registration

> In Axon Framework 4, lifecycle handlers like `onStart` or
> `onShutdown` were registered directly on the `Configurer`. In Axon
> Framework 5, these have moved to the `LifecycleRegistry`, accessible
> from any configurer via the `lifecycleRegistry` method.

> ```java
> // AF4
> configurer.onStart(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, () -> {
>     return CompletableFuture.completedFuture(null);
> });
>
> // AF5
> configurer.lifecycleRegistry(lr -> {
>     lr.onStart(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, config -> {
>         return CompletableFuture.completedFuture(null);
>     });
> });
> ```

> However, start and shutdown handlers are in most cases tied to the
> component you want to configure. You could configure this in Axon
> Framework 4 by having a component implement the `Lifecycle`
> interface. **This interface no longer exists in Axon Framework 5.**
> Instead, lifecycle handler registration is integrated into component
> registration:
>
> ```java
> // AF5
> configurer.componentRegistry(cr -> cr.registerComponent(
>         ComponentDefinition.ofType(MyComponent.class)
>                            .withBuilder(config -> new MyComponent())
>                            .onStart(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, config -> {})
>                            .onShutdown(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, config -> {})));
> ```

## projectors-event-processors.adoc — Spring event-processor config

> For Spring users, the configuration of event processors has moved
> towards the **Event Processor Specification** (implemented via
> `EventProcessorSettings`). This replaces the previous way of
> configuring processors through the `EventProcessingConfigurer` bean
> or complex property structures.

> ```java
> // AF4
> @Bean
> public ConfigurerModule configure() {
>     return configurer -> {
>         EventProcessingConfigurer processingConfigurer = configurer.eventProcessing();
>         processingConfigurer.registerPooledStreamingEventProcessor(
>                                     "my-processor",
>                                     org.axonframework.config.Configuration::eventStore,
>                                     (config, builder) -> builder.initialSegmentCount(8)
>                                                                 .batchSize(100))
>                             .assignHandlerTypesMatching(
>                                     "my-processor",
>                                     type -> type.getPackageName().startsWith("com.my.projectors"));
>     };
> }
>
> // AF5
> @Bean
> public EventProcessorDefinition myProcessorDefinition() {
>     return EventProcessorDefinition.pooledStreaming("my-processor")
>                                    .assigningHandlers(
>                                            descriptor -> descriptor.beanType().getPackageName()
>                                                                    .startsWith("com.my.projectors"))
>                                    .customized(config -> config.initialSegmentCount(8)
>                                                                .batchSize(100));
> }
> ```

> With Spring Boot you can also configure event processors using
> properties, which will be automatically converted to the appropriate
> `EventProcessorSettings` by Spring Boot's configuration binding.
>
> ```yaml
> axon:
>   eventhandling:
>     processors:
>       my-processor:
>         mode: pooled
>         thread-count: 8
> ```

## projectors-event-processors.adoc — `TrackingEventProcessor` removal

> The `TrackingEventProcessor` has been removed in Axon Framework 5.
> Use the `PooledStreamingEventProcessor` as a direct replacement.

Relevant for write-side migration when AF4 called
`EventProcessingConfigurer.registerTrackingEventProcessor(name, …)`.
The AF5 replacement is `EventProcessorDefinition.pooledStreaming(name)`.

## projectors-event-processors.adoc — `@ProcessingGroup` removal

> The `@ProcessingGroup` annotation has been removed in Axon
> Framework 5. […] In Axon Framework 5, event handlers are registered
> directly to an event processor in the configuration. A replacement
> for `@ProcessingGroup` in the form of a "namespace" annotation is
> expected to be introduced soon.

Cross-reference: `axon4-to-axon5-eventprocessor` already replaces
`@ProcessingGroup("X")` with `@Namespace("X")` on the handler class.
The write-side skill consumes the result — `EventProcessorDefinition
.pooledStreamingMatching(name)` auto-selects handlers by
`@Namespace(name)`, eliminating the explicit `assigningHandlers(...)`
call when names align.
