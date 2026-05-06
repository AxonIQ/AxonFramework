# Relevant migration-path excerpts

Quoted from `docs/reference-guide/modules/migration/pages/paths/`.

## configuration.adoc

> Axon Framework 5 restructures the configuration system, splitting the
> monolithic `Configurer` into focused configurers and replacing the
> `ConfigurerModule` with the `ConfigurationEnhancer`.

Relevant for the read side: the AF5 root configuration is
`org.axonframework.common.configuration.AxonConfiguration`, built via
one of `MessagingConfigurer.create()` / `ModellingConfigurer.create()` /
`EventSourcingConfigurer.create()` followed by `.build()`. In Spring
applications, the framework's auto-configuration registers an
`AxonConfiguration` bean — that is the bean user code should inject for
read access.

> [source,java]
> ----
> import org.axonframework.common.configuration.AxonConfiguration;
> import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
>
> class AxonApp {
>     public static void main(String[] args) {
>         EventSourcingConfigurer configurer = EventSourcingConfigurer.create();
>         // Register components, entities, event handlers...
>
>         AxonConfiguration configuration = configurer.build();
>         configuration.start();
>     }
> }
> ----

(`AxonConfiguration` extends `Configuration`. For pure read-only access
that does not need `start()` / `shutdown()`, the parent `Configuration`
type from the same package is sufficient.)

## dlq.adoc — canonical lookup-rewrite example

The DLQ migration page is the worked example for AF4 dedicated-lookup →
AF5 two-step `getModuleConfiguration().flatMap(getOptionalComponent())`.
Apply the same pattern to *every* AF4 read-side lookup, varying only
the module name and the looked-up component type.

> Axon Framework 4::
> +
> [source,java]
> ----
> public class DeadletterProcessor {
>
>     private final EventProcessingConfiguration config;
>
>     public void retryAnySequence(String processingGroup) {
>         config.sequencedDeadLetterProcessor(processingGroup)
>               .ifPresent(SequencedDeadLetterProcessor::processAny);
>     }
> }
> ----
>
> Axon Framework 5::
> +
> [source,java]
> ----
> import org.axonframework.common.configuration.Configuration;
> import io.axoniq.framework.messaging.deadletter.SequencedDeadLetterProcessor;
> import java.util.concurrent.TimeUnit;
>
> public class DeadLetterProcessor {
>
>     private final Configuration configuration;
>
>     public void retryAnySequence(String processorName, String componentName) {
>         configuration.getModuleConfiguration(processorName)
>                 .flatMap(m -> m.getOptionalComponent(SequencedDeadLetterProcessor.class, "EventHandlingComponent[" + processorName + "][" + componentName + "]"))
>                 .ifPresent(dlp ->
>                         dlp.processAny()
>                            .orTimeout(30, TimeUnit.SECONDS)
>                            .join());
>     }
> }
> ----

> Key changes:
>
> * Retrieval of a specific queue requires to use the component name
> * the processing methods on `SequencedDeadLetterProcessor` now return
>   `CompletableFuture` instead of blocking synchronously

This is the canonical reference for two patterns this skill applies
across the board:

1. **Two-step lookup** — `getModuleConfiguration("<module>").flatMap(m
   -> m.getOptionalComponent(<Type>.class[, "<componentName>"]))`.
2. **Async bridging at the top of chain** —
   `.orTimeout(<duration>, <unit>).join()` rather than naked `.join()`.

## projectors-event-processors.adoc

> The `TrackingEventProcessor` has been removed in Axon Framework 5.
> Use the `PooledStreamingEventProcessor` as a direct replacement.
> The `PooledStreamingEventProcessor` is a more robust and performant
> implementation of the streaming processor concept.

Relevant when an AF4 read-side lookup typed the result as
`TrackingEventProcessor` — typically
`eventProcessingConfiguration.eventProcessorByProcessingGroup(group, TrackingEventProcessor.class)`.
On the read side, broaden to `StreamingEventProcessor` (the parent
interface that exposes `supportsReset` / `resetTokens` / `shutdown` /
`start`) rather than committing to `PooledStreamingEventProcessor`
specifically.

## .claude/rules/completablefuture-blocking.md (project rule)

> **Never call `CompletableFuture.join()` or `.get()` without a timeout
> in framework code.** A blocking call without a deadline silently
> turns a transient issue (connection pool exhaustion, lock contention,
> deadlock, network partition) into a permanent thread leak with no
> diagnostics.

> **Preferred Approach (Java 21+)**
>
> `future.orTimeout(...).join()` is the idiomatic choice in modern Java.

This is why the skill upgrades any naked `.join()` it encounters in the
candidate to `.orTimeout(<duration>, <unit>).join()` as part of the
migration — even when the existing AF5-style code already builds and
runs.
