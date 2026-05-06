# Source access

How this machine resolves AF / AxonIQ source code for this skill.

- **Local clone:** `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5`
- **Sources jar:** n/a (use the local clone)

Key entry points consulted while drafting the skill:

- `common/src/main/java/org/axonframework/common/configuration/AxonConfiguration.java`
  — AF5 root configuration interface (`extends Configuration`).
- `common/src/main/java/org/axonframework/common/configuration/Configuration.java`
  — `getComponent`, `getOptionalComponent`, `getComponents`,
  `getModuleConfiguration`, `getModuleConfigurations` API surface.
- `messaging/src/main/java/org/axonframework/messaging/eventhandling/processing/EventProcessor.java`
  — confirms `start()`, `shutdown()` returning `CompletableFuture<Void>`
  in AF5.
- `messaging/src/main/java/org/axonframework/messaging/eventhandling/processing/streaming/StreamingEventProcessor.java`
  — confirms `supportsReset()` (sync `boolean`),
  `resetTokens()` (`CompletableFuture<Void>`), and the AF5 package.
- `messaging/src/main/java/org/axonframework/messaging/eventhandling/processing/streaming/pooled/PooledStreamingEventProcessorModule.java`
  — confirms the module-name convention `"EventProcessor[" + processorName + "]"`.
- `messaging/src/main/java/org/axonframework/messaging/eventhandling/processing/subscribing/SubscribingEventProcessorModule.java`
  — same convention; both pooled-streaming and subscribing register
  under `"EventProcessor[<name>]"`.
- `extensions/spring/spring-boot-autoconfigure/src/main/java/org/axonframework/extension/springboot/autoconfig/AxonAutoConfiguration.java`
  — confirms that Spring auto-configuration exposes `AxonConfiguration`
  as the bean type that user code injects.

Use this fallback only when the migration-path docs and the
transformation instructions in `SKILL.md` leave a real gap — e.g. an
unfamiliar AF4 lookup method, an unknown AF5 module-name convention, or
an AF4 `Configuration#findComponent` shape this skill has not seen yet.
After consulting the source, run `reflect` so the missing knowledge
folds back into the transformation instructions and the fallback isn't
needed next time.

If both go missing, re-run the source-access step in `migration-skill-creator`
before invoking this skill.
