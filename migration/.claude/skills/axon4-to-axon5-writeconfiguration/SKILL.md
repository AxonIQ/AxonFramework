---
name: axon4-to-axon5-writeconfiguration
description: >
  Migrate ONE class that **configures** Axon Framework — typically a
  Spring `@Configuration` class with `@Bean ConfigurerModule` /
  `@Bean Configurer` / `@Bean EventProcessingConfigurer`-lambda methods,
  or a non-Spring entry point that builds a `Configurer` directly via
  `DefaultConfigurer.defaultConfiguration()` — from Axon Framework 4 to
  Axon Framework 5. Switches the configuration shape: `Configurer` /
  `DefaultConfigurer` → one of the focused `ApplicationConfigurer`s
  (`MessagingConfigurer` / `ModellingConfigurer` /
  `EventSourcingConfigurer`); `ConfigurerModule` → `ConfigurationEnhancer`
  acting on a `ComponentRegistry`; `EventProcessingConfigurer
  .registerPooledStreamingEventProcessor(...) /
  .assignHandlerTypesMatching(...) / .registerSequencingPolicy(...) /
  .registerDefaultErrorHandler(...)` → `EventProcessorDefinition
  .pooledStreaming(name).assigningHandlers(...).customized(...)`;
  `Configurer.onStart / onShutdown` → `lifecycleRegistry(...)`;
  `Lifecycle` interface → `ComponentDefinition.onStart / onShutdown` on
  the registered component. Atomic — exactly one configuration class per
  run. Sibling skill of `axon4-to-axon5-readconfiguration` for the
  read side; classes that only *read* the configuration belong to that
  skill, not this one.
---

# AF4 → AF5: Write access to `Configuration`

Atomic migration of a single class that **configures** Axon Framework
— defines beans, registers components, declares event processors,
attaches lifecycle handlers, customises error handling.

> **Keep this skill generic.** It runs across many projects. Describe
> the source/target purely in framework terms (annotations, types, API
> signatures) — never in terms of a specific project's package, module,
> or file layout. Project-specific knowledge lives in
> `references/examples/` only. If a project-specific quirk needs to
> influence the transformation, route on an observable shape (the
> annotation present on the class, the type returned from the `@Bean`
> method, the method called on the AF4 configurer), not on project
> identity.

## What this migrates

- **From:** a class that uses any AF4 *write-side* configuration API.
  Concretely, at least one of:
  - **`@Bean ConfigurerModule`** (Spring) — lambda registers
    components, customises buses, configures event processing.
  - **`@Bean Configurer` / `DefaultConfigurer.defaultConfiguration()`**
    (non-Spring or manual entry point) — builds a configurer and calls
    `buildConfiguration().start()`.
  - **`EventProcessingConfigurer` lambda** —
    `registerPooledStreamingEventProcessor(name, ...)`,
    `registerTrackingEventProcessor(name, ...)`,
    `registerSubscribingEventProcessor(name, ...)`,
    `assignHandlerTypesMatching(group, predicate)`,
    `byDefaultAssignTo(group)`,
    `registerSequencingPolicy(group, factory)`,
    `registerListenerInvocationErrorHandler(group, factory)`,
    `registerErrorHandler(group, factory)`,
    `registerDeadLetterQueueProvider(...)`.
  - **AF4 lifecycle hooks** —
    `configurer.onStart(Phase, () -> ...)` /
    `configurer.onShutdown(Phase, () -> ...)`,
    or a class that implements `org.axonframework.lifecycle.Lifecycle`.
  - **AF4 component registration** —
    `configurer.registerComponent(MyService.class, config -> ...)`.
- **To:** the same class, with:
  - `Configurer` / `DefaultConfigurer` replaced by the matching
    `ApplicationConfigurer`:
    `MessagingConfigurer.create()` (messaging only),
    `ModellingConfigurer.create()` (+ entities/repositories),
    `EventSourcingConfigurer.create()` (+ event sourcing). Pick the
    highest layer the class touches — these form a delegation chain.
  - `@Bean ConfigurerModule` rewritten as `@Bean ConfigurationEnhancer`
    operating on a `ComponentRegistry` (lambda parameter renamed
    `configurer` → `registry`).
  - `EventProcessingConfigurer` calls rewritten to `@Bean
    EventProcessorDefinition` methods using
    `EventProcessorDefinition.pooledStreaming(name) /
    .subscribing(name) / .pooledStreamingMatching(name) /
    .subscribingMatching(name)` followed by
    `.assigningHandlers(...)` and either
    `.customized(config -> ...)` or `.notCustomized()`.
  - Lifecycle hooks moved either to `lifecycleRegistry(lr ->
    lr.onStart(Phase, config -> ...))` (when free-standing) or to
    `ComponentDefinition.ofType(...).onStart(Phase, ...)
    .onShutdown(Phase, ...)` (when tied to a specific component).
  - `Lifecycle` interface implementations on registered components
    folded into a `ComponentDefinition` registration.
  - Component registration moved to
    `componentRegistry(cr -> cr.registerComponent(MyService.class,
    config -> new MyService()))` or
    `cr.registerIfNotPresent(...)` / `ComponentDefinition` for richer
    cases.
- **Scope per run:** exactly one configuration class (see "Selection
  rule").

## Selection rule

If the user names a target (class, file path), use it. Otherwise: pick
the **first** candidate in lexical order by file path among classes
that match the "From" shape above. Never migrate more than one per
run.

A configuration class typically lives at the top of the package tree
and has either a Spring `@Configuration` annotation, or a `main(...)`
method that builds a `Configurer`, or a class implementing
`ConfigurerModule`. Use the AF4 *type* (the imports
`org.axonframework.config.Configurer`,
`org.axonframework.config.ConfigurerModule`,
`org.axonframework.config.EventProcessingConfigurer`) as the strongest
signal.

## Procedure

1. **Locate the candidate.** If no target was named, run a
   deterministic search for AF4 write-side imports:
   ```bash
   grep -rln --include='*.java' \
     -e 'org.axonframework.config.Configurer\b' \
     -e 'org.axonframework.config.ConfigurerModule' \
     -e 'org.axonframework.config.EventProcessingConfigurer' \
     -e 'org.axonframework.config.DefaultConfigurer' \
     -e 'org.axonframework.lifecycle.Lifecycle\b' \
     <source roots>
   ```
   Pick the first file (lexical order) that **defines** AF4
   configuration (a `@Bean` returning `Configurer`/`ConfigurerModule`,
   a `main` building `DefaultConfigurer`, an `implements Lifecycle`).
   Skip files whose only AF4 reference is read-side — those belong to
   `axon4-to-axon5-readconfiguration`.

2. **Sweep for paired YAML / properties.** Before transforming the
   `@Configuration` class, grep `application.yml` /
   `application.properties` / `application-*.{yml,properties}` for the
   AF4 processor key shape:
   ```bash
   grep -rln --include='*.yml' --include='*.yaml' --include='*.properties' \
     'axon\.eventhandling\.processors\.' <project root>
   ```
   AF4's processor properties are scoped by **processing-group name**
   (`axon.eventhandling.processors.<group>.mode = tracking|pooled|...`,
   `.thread-count`, `.batch-size`, `.initial-segment-count`,
   `.sequencing-policy`, …). AF5 keeps the same root key, but:
   - It is scoped by **processor name** (after AF4's group/processor
     identity collapses, you typically use the same string).
   - Several leaves were renamed/removed (e.g. `mode: tracking` is
     gone — use `mode: pooled` as the streaming default; AF5 also
     accepts `mode: subscribing`).
   - `sequencing-policy` config moved to a class-level
     `@SequencingPolicy` annotation on the handler component (handled
     by `axon4-to-axon5-eventprocessor`, not here). When the only
     thing under a group key is `sequencing-policy`, the YAML entry
     can usually be **deleted** entirely after the per-processor skill
     has run.

   Note what the sweep finds in the diff summary; flag anything that
   needs a YAML edit so the user can do that file in a second pass.

3. **Read the canonical migration-path doc** before transforming
   anything. This skill is grounded in:
   - `paths/configuration.adoc` — `Configurer` split,
     `ConfigurerModule` → `ConfigurationEnhancer`, component
     registration, lifecycle handler registration, Spring Boot
     configuration.
   - `paths/projectors-event-processors.adoc` — non-Spring
     `EventProcessingConfigurer` lambda → `MessagingConfigurer
     #eventProcessing(...)` flow; Spring `EventProcessingConfigurer`
     bean → `EventProcessorDefinition` bean; `TrackingEventProcessor`
     removal.
   - `paths/sequencing-policies.adoc` — sequencing-policy
     registration moves out of write config and onto the handler
     class (out of scope here, but flag).

   Local excerpts in `references/migration-paths.md`.

4. **Apply the transformation instructions** below. They are this
   skill's LLM-specific edits — narrower and more prescriptive than
   the docs, and they grow over time as `reflect` folds in lessons
   from real runs.

5. **Show the diff** and summarize what changed
   (`Configurer`/`ConfigurerModule` swap, event-processor bean
   rewrites, lifecycle moves, component-registration moves, deleted
   imports, flagged YAML).

6. **Stop and ask the human to verify.** Do **not** rely on
   `mvn compile` passing — peer constructs (event processors, query
   handlers, aggregates) are typically still on AF4 mid-migration and
   the project is expected to be broken. The human decides
   acceptable / not-acceptable.

> **Fallback only:** if the migration-path docs and the instructions
> in this skill leave a real gap (an AF4 method without an obvious
> AF5 equivalent, an unfamiliar `EventProcessorDefinition` builder
> step), inspect the AF source at the paths in
> `references/source-access.md`. Treat that as a signal to run
> `reflect` afterwards so the missing knowledge folds back into the
> transformation instructions and the fallback isn't needed next
> time.

## Transformation instructions

Apply each step in order; skip steps whose precondition isn't present
in the candidate file.

### 1. FQN cheat sheet

Use this for every step below — never guess imports.

#### AF4 (remove these)

| Element                            | FQN |
|------------------------------------|---|
| `Configurer`                       | `org.axonframework.config.Configurer` |
| `DefaultConfigurer`                | `org.axonframework.config.DefaultConfigurer` |
| `ConfigurerModule`                 | `org.axonframework.config.ConfigurerModule` |
| `EventProcessingConfigurer`        | `org.axonframework.config.EventProcessingConfigurer` |
| `Configuration` (root, AF4)        | `org.axonframework.config.Configuration` |
| `Lifecycle`                        | `org.axonframework.lifecycle.Lifecycle` |
| `Phase`                            | `org.axonframework.common.lifecycle.Phase` *(stays — see below)* |
| `TrackingEventProcessorConfiguration` | `org.axonframework.eventhandling.TrackingEventProcessorConfiguration` |
| `PooledStreamingEventProcessor` (AF4 location) | `org.axonframework.eventhandling.pooled.PooledStreamingEventProcessor` |

#### AF5 (add these)

| Element                                | FQN |
|----------------------------------------|---|
| `ApplicationConfigurer`                | `org.axonframework.common.configuration.ApplicationConfigurer` |
| `AxonConfiguration`                    | `org.axonframework.common.configuration.AxonConfiguration` |
| `Configuration` (read-only, AF5)       | `org.axonframework.common.configuration.Configuration` |
| `ConfigurationEnhancer`                | `org.axonframework.common.configuration.ConfigurationEnhancer` |
| `ComponentRegistry`                    | `org.axonframework.common.configuration.ComponentRegistry` |
| `ComponentDefinition`                  | `org.axonframework.common.configuration.ComponentDefinition` |
| `LifecycleRegistry` (`config.lifecycleRegistry(...)` lambda parameter type) | `org.axonframework.common.configuration.LifecycleRegistry` |
| `Phase` (unchanged FQN)                | `org.axonframework.common.lifecycle.Phase` |
| `MessagingConfigurer`                  | `org.axonframework.messaging.core.configuration.MessagingConfigurer` |
| `ModellingConfigurer`                  | `org.axonframework.modelling.configuration.ModellingConfigurer` |
| `EventSourcingConfigurer`              | `org.axonframework.eventsourcing.configuration.EventSourcingConfigurer` |
| `EventProcessorDefinition`             | `org.axonframework.extension.spring.config.EventProcessorDefinition` |
| `EventHandlerSelector`                 | `org.axonframework.extension.spring.config.EventHandlerSelector` |
| `EventProcessorSettings`               | `org.axonframework.extension.spring.config.EventProcessorSettings` |
| `PooledStreamingEventProcessorConfiguration` | `org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration` |
| `SubscribingEventProcessorConfiguration`     | `org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessorConfiguration` |

### 2. `@Bean ConfigurerModule` → `@Bean ConfigurationEnhancer`

The most common Spring shape. The lambda parameter type changes from
`Configurer` (read+write) to `ComponentRegistry` (write only — the
build doesn't run yet).

```java
// AF4
@Bean
public ConfigurerModule myModule() {
    return configurer -> configurer.registerComponent(
            MyService.class,
            config -> new MyService());
}

// AF5
@Bean
public ConfigurationEnhancer myEnhancer() {
    return registry -> registry.registerComponent(
            MyService.class,
            config -> new MyService());
}
```

Notes:

- Rename the bean method from `*Module` → `*Enhancer` (or whatever
  reads naturally) to match the new type. The bean *name* matters for
  Spring resolution — keep it stable if other beans reference it by
  name; otherwise rename freely.
- `Configurer.registerComponent(Type, factory)` →
  `ComponentRegistry.registerComponent(Type, factory)`. Same generic
  signature, different receiver.
- The factory's `config` parameter is now the AF5 `Configuration`
  (read-only), not the old AF4 `Configuration`. Method calls that
  used the AF4 read API
  (`config.eventStore`, `config.commandBus`, …) must move to the AF5
  read shape — typically `config.getComponent(EventStore.class)` /
  `config.getComponent(CommandBus.class)`. (Cross-reference:
  `axon4-to-axon5-readconfiguration` covers the read side
  transformations.)
- If the AF4 bean used `Configurer#configureCommandBus`,
  `configureEventStore`, `configureSerializer`, etc., those move to
  dedicated `ApplicationConfigurer` registration methods — they are
  **not** available on `ComponentRegistry` directly. Switch to a
  bean returning `MessagingConfigurer`-shaped customisation (see
  step 3) or use the dedicated registration methods on the
  configurer in non-Spring code.

### 3. Manual `Configurer` → focused `ApplicationConfigurer`

Use this when the candidate is non-Spring and builds a configurer
directly. Pick the configurer that matches the highest-level feature
the application uses:

| Highest-level feature in use                    | Pick                       |
|-------------------------------------------------|----------------------------|
| Commands/events/queries only (no aggregates)    | `MessagingConfigurer`      |
| Above + aggregates / repositories (no ES)       | `ModellingConfigurer`      |
| Above + event sourcing / event store            | `EventSourcingConfigurer`  |

```java
// AF4
Configurer configurer = DefaultConfigurer.defaultConfiguration();
// register components, aggregates, event handlers...
Configuration configuration = configurer.buildConfiguration();
configuration.start();

// AF5 (event sourcing)
EventSourcingConfigurer configurer = EventSourcingConfigurer.create();
// register components, entities, event handlers...
AxonConfiguration configuration = configurer.build();
configuration.start();
```

Notes:

- `buildConfiguration()` → `build()`. Return type changes from AF4
  `Configuration` to AF5 `AxonConfiguration` (extends `Configuration`).
- Each configurer has escape-hatch methods to access lower layers:
  `configurer.modelling(modelling -> ...)`,
  `configurer.messaging(messaging -> ...)`,
  `configurer.componentRegistry(cr -> ...)`,
  `configurer.lifecycleRegistry(lr -> ...)`. Use them when the AF4
  call applied to a different layer than the one you picked.
- Dedicated bus / store registration methods stayed where they
  conceptually belong: `registerCommandBus` / `registerQueryBus` /
  `registerEventSink` on `MessagingConfigurer`; `registerEventStore`
  on `EventSourcingConfigurer`. Anything else generic now flows
  through `componentRegistry(cr -> cr.registerComponent(...))`.

### 4. `EventProcessingConfigurer` (Spring) → `@Bean EventProcessorDefinition`

This is the substantive Spring rewrite. AF4 typically had a
`@Bean ConfigurerModule` that called `configurer.eventProcessing()`
and chained processor registrations. AF5 expresses each processor as
its **own** `@Bean EventProcessorDefinition`. One bean per processor,
no shared lambda.

```java
// AF4
@Bean
public ConfigurerModule configure() {
    return configurer -> {
        EventProcessingConfigurer p = configurer.eventProcessing();
        p.registerPooledStreamingEventProcessor(
                "my-processor",
                org.axonframework.config.Configuration::eventStore,
                (config, builder) -> builder.initialSegmentCount(8)
                                            .batchSize(100))
         .assignHandlerTypesMatching(
                "my-processor",
                type -> type.getPackageName().startsWith("com.my.projectors"));
    };
}

// AF5 — one bean per processor
@Bean
public EventProcessorDefinition myProcessorDefinition() {
    return EventProcessorDefinition
            .pooledStreaming("my-processor")
            .assigningHandlers(
                    descriptor -> descriptor.beanType()
                                            .getPackageName()
                                            .startsWith("com.my.projectors"))
            .customized(config -> config.initialSegmentCount(8)
                                        .batchSize(100));
}
```

Method-mapping cheat sheet:

| AF4 call (on `EventProcessingConfigurer`)                | AF5 (`EventProcessorDefinition` builder)                   |
|----------------------------------------------------------|------------------------------------------------------------|
| `registerPooledStreamingEventProcessor(name)`            | `pooledStreaming(name).assigningHandlers(...).notCustomized()` *(or `.customized(...)`)* |
| `registerPooledStreamingEventProcessor(name, source, customisation)` | `pooledStreaming(name).assigningHandlers(...).customized(config -> /* translate builder */)` |
| `registerSubscribingEventProcessor(name)`                | `subscribing(name).assigningHandlers(...).notCustomized()` |
| `registerTrackingEventProcessor(name, ...)`              | **Removed in AF5.** Switch to `pooledStreaming(name)` — see `paths/projectors-event-processors.adoc` |
| `assignHandlerTypesMatching(group, predicate)`           | merged into `assigningHandlers(EventHandlerSelector)` on the same processor's `EventProcessorDefinition` |
| `byDefaultAssignTo(group)`                               | the receiving `EventProcessorDefinition` becomes the default sink — typically by giving it an `EventHandlerSelector` that matches everything not claimed by the others, or by relying on a `pooledStreamingMatching(name)` that auto-selects by `@Namespace(name)` |
| `registerSequencingPolicy(group, factory)`               | **Out of scope here** — moves to `@SequencingPolicy` on the handler class (see `axon4-to-axon5-eventprocessor`) |
| `registerListenerInvocationErrorHandler(group, factory)` | flagged for the user — AF5 surface differs; consult `paths/projectors-event-processors.adoc` and source |
| `registerErrorHandler(group, factory)`                   | flagged for the user — same |
| `registerDeadLetterQueueProvider(group, factory)`        | flagged for the user — DLQ migration is a separate path (`paths/dlq.adoc`) |

Notes:

- **`assigningHandlers`** takes an `EventHandlerSelector` lambda; the
  parameter is a `BeanDescriptor` (use `descriptor.beanType()`,
  `descriptor.beanName()`, etc.). It replaces *both*
  `assignHandlerTypesMatching` (predicate over the type) and
  `byDefaultAssignTo` semantics.
- **`pooledStreamingMatching(name)` / `subscribingMatching(name)`**
  are shortcut factories that auto-select handlers by
  `@Namespace(name)`. If the candidate's `@ProcessingGroup` argument
  matches the processor name and the per-processor skill has already
  run (so handlers carry `@Namespace`), prefer the `*Matching`
  variant — it eliminates the `assigningHandlers(...)` step entirely.
- **`.customized(...)` vs `.notCustomized()`** — pick `notCustomized`
  when AF4 passed defaults; pick `customized(config -> ...)` when AF4
  customised the builder. Translate each builder method one-to-one;
  the AF5 config object exposes the same surface
  (`initialSegmentCount`, `batchSize`, `maxClaimedSegments`, …).
- **Properties-based config still works.** AF5 binds
  `axon.eventhandling.processors.<name>.*` to `EventProcessorSettings`
  the same way Spring Boot did in AF4. `EventProcessorDefinition`
  beans coexist with properties; explicit
  `.customized(...)` overrides the properties.

### 5. Non-Spring `EventProcessingConfigurer` → `MessagingConfigurer#eventProcessing(...)`

When the candidate configures event processing programmatically (no
Spring), AF4's `configurer.eventProcessing()` becomes a nested
`MessagingConfigurer.eventProcessing(...)` lambda:

```java
// AF4
configurer.eventProcessing()
          .registerPooledStreamingEventProcessor("my-processor");

// AF5
messagingConfigurer.eventProcessing(
    eventProcessing -> eventProcessing.pooledStreaming(
        pooledStreaming -> pooledStreaming.processor(
            "my-processor",
            module -> module.eventHandlingComponents(components -> components)
                            .notCustomized())));
```

This shape is rare outside framework tests — most real projects use
Spring. If you encounter it, copy the structure verbatim from
`paths/projectors-event-processors.adoc` and adapt the processor
configuration body.

### 6. Lifecycle handlers

AF4 had three places to hook lifecycle:

1. `Configurer.onStart(Phase, Runnable)` /
   `Configurer.onShutdown(Phase, Runnable)`.
2. A component implementing `Lifecycle` and overriding
   `registerLifecycleHandlers(LifecycleRegistry)`.
3. `@StartHandler` / `@ShutdownHandler` annotations on framework
   components.

In AF5, lifecycle hooks attach to **two** places: the
`LifecycleRegistry` for free-standing hooks, and a `ComponentDefinition`
for hooks tied to a specific component.

#### Free-standing `onStart` / `onShutdown`

```java
// AF4
configurer.onStart(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, () -> {
    // Startup logic
    return CompletableFuture.completedFuture(null);
});

// AF5
configurer.lifecycleRegistry(lr -> {
    lr.onStart(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, config -> {
        // Startup logic
        return CompletableFuture.completedFuture(null);
    });
});
```

The lambda now takes the AF5 `Configuration` as a parameter (so the
hook can read components without capturing them at registration
time). Update the lambda signature and any references inside.

`Phase` constants (`Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS`,
`Phase.OUTBOUND_EVENT_CONNECTORS`, …) keep their AF4 FQN
(`org.axonframework.common.lifecycle.Phase`) — no import change.

#### Component-tied lifecycle (replacing `Lifecycle`)

The AF4 `Lifecycle` interface is **removed**. Move both the start and
shutdown hooks into the `ComponentDefinition` registration:

```java
// AF4 — class implements Lifecycle
class MyComponent implements Lifecycle {
    @Override
    public void registerLifecycleHandlers(@NotNull Lifecycle.LifecycleRegistry lifecycle) {
        lifecycle.onStart(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, () -> {});
        lifecycle.onShutdown(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, () -> {});
    }
}

// AF5 — registration owns the lifecycle
configurer.componentRegistry(cr -> cr.registerComponent(
        ComponentDefinition.ofType(MyComponent.class)
                           .withBuilder(config -> new MyComponent())
                           .onStart(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, config -> {})
                           .onShutdown(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, config -> {})));
```

Steps:
1. Remove `implements Lifecycle` and the `registerLifecycleHandlers`
   override from the component class.
2. Remove the AF4 import `org.axonframework.lifecycle.Lifecycle`.
3. Find the registration site (where `MyComponent` is `registerComponent`-ed
   in this candidate or another configuration class). Convert the
   plain `(Type, factory)` registration to a `ComponentDefinition`
   registration with the lifecycle hooks attached.
4. If the registration lives in a *different* class, **flag** it for
   the user rather than silently editing — atomic scope is one class
   per run.

### 7. Component registration

```java
// AF4
configurer.registerComponent(MyService.class, config -> new MyService());

// AF5 — generic
configurer.componentRegistry(cr -> cr.registerComponent(
        MyService.class,
        config -> new MyService()));

// AF5 — conditional, prevents overwriting
configurer.componentRegistry(cr -> cr.registerIfNotPresent(
        MyService.class,
        config -> new MyService()));

// AF5 — richer (lifecycle, decorators) — see ComponentDefinition above
```

When AF4 registered the same type *multiple* times under different
*names*, use `cr.registerComponent(Type, name, factory)` (the
3-arg overload) so each registration keeps its identity. AF5's
`getComponents(Type)` returns a `Map<String, T>` keyed by name.

### 8. Reading inside the registration factory

Factories given to `registerComponent(Type, factory)` receive a
`Configuration` (AF5 read-only) as their argument. AF4 factories that
called `config.eventStore`, `config.commandBus`, `config.serializer`
must rewrite those calls to the AF5 generic read API:

| AF4 factory call                      | AF5 factory call                                          |
|---------------------------------------|-----------------------------------------------------------|
| `config.eventStore()`                 | `config.getComponent(EventStore.class)`                   |
| `config.commandBus()`                 | `config.getComponent(CommandBus.class)`                   |
| `config.queryBus()`                   | `config.getComponent(QueryBus.class)`                     |
| `config.eventBus()`                   | `config.getComponent(EventSink.class)` *(name change — `EventSink` is the AF5 publish-side interface)* |
| `config.parameterResolverFactory()`   | `config.getComponent(ParameterResolverFactory.class)`     |
| custom `config.findComponent(Type)`   | `config.getOptionalComponent(Type.class)`                 |

This overlaps with `axon4-to-axon5-readconfiguration` — apply it
**only** for factories *defined inside* the candidate write-config
class. Read-only consumers in other classes are out of scope here.

### 9. Delete unused AF4 imports / methods / interfaces

After the rewrite, sweep the file for:

- Stale AF4 imports (`org.axonframework.config.*`,
  `org.axonframework.lifecycle.Lifecycle`).
- Now-unused private helper methods (factory lambdas inlined into
  `registerComponent`, helper builders).
- `implements Lifecycle` clauses on classes whose lifecycle hooks
  moved to `ComponentDefinition`.
- `@Bean ConfigurerModule` methods that became empty after the
  per-processor extraction — delete the bean entirely.

### 10. Out-of-scope: read-side access

If the candidate also **reads** configuration at runtime — i.e. it
injects `Configuration` / `EventProcessingConfiguration` and looks up
components — that part of the file is **out of scope** here. Leave
the read-side calls untouched, mention them in the diff summary, and
point the user at `axon4-to-axon5-readconfiguration` as the follow-up.

### 11. Out-of-scope: per-processor concerns

The following AF4 → AF5 changes affect the **handler** classes, not
this configuration class. If the candidate references them, **flag**
them but don't edit cross-class:

- `@ProcessingGroup` → `@Namespace` on the handler class.
- `SequencingPolicy` registration → `@SequencingPolicy` annotation on
  the handler class.
- `CommandGateway` field → method-parameter `CommandDispatcher`.
- `@EventHandler` / `@QueryHandler` import moves.

These are handled by `axon4-to-axon5-eventprocessor` and
`axon4-to-axon5-queryhandler` respectively.

## Reference docs

The migration-path .adoc(s) this skill is grounded in:

- `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5/docs/reference-guide/modules/migration/pages/paths/configuration.adoc` — primary
- `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5/docs/reference-guide/modules/migration/pages/paths/projectors-event-processors.adoc` — event-processor configuration (Spring + non-Spring)
- `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5/docs/reference-guide/modules/migration/pages/paths/sequencing-policies.adoc` — sequencing-policy migration (handler-side, but explains why `registerSequencingPolicy` disappears here)
- `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5/docs/reference-guide/modules/migration/pages/paths/dlq.adoc` — dead-letter queue config moves (called out where DLQ-related calls appear)

Key excerpts kept locally in `references/migration-paths.md`.

## Source references (fallback)

`references/source-access.md` records where AF4 / AF5 sources resolve
on this machine. Used only when the migration-path docs and the
transformation instructions above are insufficient — particularly for
confirming a fluent step on `EventProcessorDefinition` you have not
seen before, or an `ApplicationConfigurer` escape-hatch method.

## Examples

Each file in `references/examples/` is one real migration from one
project, preserved verbatim. **All project-specific knowledge lives
here** — never in the procedure or transformation instructions above.
New examples are added, not merged: if a new project shows a different
valid pattern, drop in `references/examples/<NN>-<project>-<short-desc>.md`
rather than editing the existing ones.

*(No examples yet. Add one after the first real run, captured via
`reflect`.)*

## Variants

- **Pure event-processor configuration class.** A `@Configuration`
  whose only AF4 bean is a single `ConfigurerModule` calling
  `configurer.eventProcessing()`. Apply step 4 (split into one
  `EventProcessorDefinition` bean per processor) and skip steps 2 (no
  general `ConfigurerModule` rewrite needed once split), 3 (no manual
  configurer), 6 (no lifecycle hooks), 7 (no component registration).
- **Component-only configuration class.** A `@Configuration` whose
  AF4 bean is a `ConfigurerModule` that *only* calls
  `configurer.registerComponent(...)`. Apply step 2 to convert to
  `ConfigurationEnhancer`, then step 7 implicitly inside the lambda.
  Skip event-processor steps.
- **Manual `Configurer` builder (non-Spring).** A class with a `main`
  building `DefaultConfigurer` and calling `start()`. Apply step 3
  (focused configurer + `build()` rename) and any of 5/6/7 that the
  builder body uses.
- **`@Configuration` with mixed read+write.** The class has BOTH a
  `@Bean ConfigurerModule` AND a method that injects
  `Configuration` to read state. Migrate **only** the write side here;
  flag the read side as a follow-up for
  `axon4-to-axon5-readconfiguration`.

## Notes for the human

- This skill is iteratively improved via the `reflect` skill — after
  every correction, reflect to fold the lesson back into the
  instructions.
- If you change something manually (kept a `ConfigurerModule` bean
  for a backwards-compat reason, picked `MessagingConfigurer` over
  `EventSourcingConfigurer` for a service module, inlined a processor
  configuration into a single big enhancer rather than splitting),
  mention it briefly so reflect can capture the *why*.
- AF5's `EventProcessorDefinition` is in the **Spring** extension
  module (`org.axonframework.extension.spring.config.*`). Non-Spring
  applications cannot use it — they configure event processors
  through `MessagingConfigurer.eventProcessing(...)` (step 5).
