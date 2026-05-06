# Source access

How this machine resolves AF / AxonIQ source code for this skill.

- **Local clone:** `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5`
- **Sources jar:** n/a (use the local clone)

Key entry points consulted while drafting the skill:

- `common/src/main/java/org/axonframework/common/configuration/ConfigurationEnhancer.java`
  — AF5 replacement for `ConfigurerModule`. Single method
  `enhance(ComponentRegistry registry)`, plus an optional `order()`.
- `common/src/main/java/org/axonframework/common/configuration/ComponentRegistry.java`
  — destination of generic registration methods
  (`registerComponent`, `registerIfNotPresent`, `registerDecorator`,
  `registerModule`, …).
- `common/src/main/java/org/axonframework/common/configuration/ComponentDefinition.java`
  — fluent definition with `withBuilder`, `onStart`, `onShutdown` —
  the AF5 home for what AF4 expressed via `Lifecycle`.
- `common/src/main/java/org/axonframework/common/configuration/AxonConfiguration.java`
  — return type of `ApplicationConfigurer#build()`.
- `messaging/src/main/java/org/axonframework/messaging/core/configuration/MessagingConfigurer.java`
  — focused configurer for messaging-only apps. Confirms
  `create()` factory, `registerCommandBus`, `registerQueryBus`,
  `registerEventSink`, `componentRegistry`, `lifecycleRegistry`,
  `eventProcessing` escape hatches.
- `modelling/src/main/java/org/axonframework/modelling/configuration/ModellingConfigurer.java`
  — focused configurer for messaging + modelling.
- `eventsourcing/src/main/java/org/axonframework/eventsourcing/configuration/EventSourcingConfigurer.java`
  — top-of-chain configurer; the typical choice for full AF5 apps.
- `extensions/spring/spring/src/main/java/org/axonframework/extension/spring/config/EventProcessorDefinition.java`
  — fluent builder for Spring users:
  `pooledStreaming(name) / subscribing(name) /
  pooledStreamingMatching(name) / subscribingMatching(name)` →
  `assigningHandlers(EventHandlerSelector)` →
  `customized(Function) / notCustomized()`.
- `extensions/spring/spring/src/main/java/org/axonframework/extension/spring/config/EventHandlerSelector.java`
  — predicate used by `assigningHandlers(...)`. Includes
  `matchesNamespaceOnType(name)` for the `*Matching` shortcut path.
- `extensions/spring/spring/src/main/java/org/axonframework/extension/spring/config/EventProcessorSettings.java`
  — settings type bound from `axon.eventhandling.processors.<name>.*`
  properties. Confirms the `ProcessorMode.POOLED / SUBSCRIBING`
  enum.
- `extensions/spring/spring-boot-autoconfigure/src/main/java/org/axonframework/extension/springboot/EventProcessorProperties.java`
  — Spring Boot binding entry point for processor properties. Useful
  to confirm which YAML keys still bind cleanly post-migration.

Use this fallback only when the migration-path docs and the
transformation instructions in `SKILL.md` leave a real gap — e.g. an
unfamiliar AF4 `EventProcessingConfigurer` method, an
`EventHandlerSelector` shape this skill has not seen, or a question
about how a specific AF4 builder method maps onto the AF5 config
object. After consulting the source, run `reflect` so the missing
knowledge folds back into the transformation instructions and the
fallback isn't needed next time.

If both go missing, re-run the source-access step in `migration-skill-creator`
before invoking this skill.
