# Heroes (Spring Boot): `StreamProcessorsOperations`

A Spring `@Component` ops endpoint that exposes a `reset(processor)`
operation: it looks up the `TrackingEventProcessor` for a processing
group from `EventProcessingConfiguration`, and — if the processor
supports reset — runs the standard `shutDown / resetTokens / start`
cycle. The AF4 → AF5 migration covers:

- bean rename `EventProcessingConfiguration` → `AxonConfiguration`
  (and the import move from `org.axonframework.config.*` to
  `org.axonframework.common.configuration.*`),
- AF4 dedicated lookup → AF5 two-step
  `getModuleConfiguration("EventProcessor[" + processor + "]")
   .flatMap(m -> m.getOptionalComponent(StreamingEventProcessor.class))`,
- looked-up component rename `TrackingEventProcessor` →
  `StreamingEventProcessor` (with the package move from
  `o.a.eventhandling.*` to
  `o.a.messaging.eventhandling.processing.streaming.*`),
- sync → async lifecycle: `shutDown()` / `resetTokens()` / `start()`
  now return `CompletableFuture<Void>` and need an explicit-timeout
  bridge at this top-of-chain entry point.

The original AF4 file also injected `TokenStore` directly to compute
processor progress (`fetchSegments` / `fetchToken`). That is a
**separate** read-side migration: AF5 wants the same `TokenStore`
acquired through the same `axonConfiguration.getOptionalComponent(...)`
machinery rather than as a separate Spring bean. The example below
keeps just the `reset(...)` operation — the progress block is out of
scope for the per-class run.

**Before (AF4):**

```java
package com.dddheroes.heroesofddd.maintenance.write.resetprocessor;

import org.axonframework.config.EventProcessingConfiguration;
import org.axonframework.eventhandling.TrackingEventProcessor;
import org.springframework.stereotype.Component;

@Component
public class StreamProcessorsOperations {

    private final EventProcessingConfiguration eventProcessingConfiguration;

    StreamProcessorsOperations(EventProcessingConfiguration eventProcessingConfiguration) {
        this.eventProcessingConfiguration = eventProcessingConfiguration;
    }

    public void reset(String processor) {
        eventProcessingConfiguration
                .eventProcessorByProcessingGroup(processor, TrackingEventProcessor.class)
                .ifPresent(eventProcessor -> {
                    if (eventProcessor.supportsReset()) {
                        eventProcessor.shutDown();
                        eventProcessor.resetTokens();
                        eventProcessor.start();
                    }
                });
    }
}
```

**After (AF5):**

```java
package com.dddheroes.heroesofddd.maintenance.write.resetprocessor;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class StreamProcessorsOperations {

    private final AxonConfiguration axonConfiguration;

    public StreamProcessorsOperations(AxonConfiguration axonConfiguration) {
        this.axonConfiguration = axonConfiguration;
    }

    public void reset(String processor) {
        var moduleName = "EventProcessor[" + processor + "]";

        axonConfiguration.getModuleConfiguration(moduleName)
                         .flatMap(m -> m.getOptionalComponent(StreamingEventProcessor.class))
                         .ifPresent(eventProcessor -> {
                             if (eventProcessor.supportsReset()) {
                                 eventProcessor.shutdown().orTimeout(30, TimeUnit.SECONDS).join();
                                 eventProcessor.resetTokens().orTimeout(30, TimeUnit.SECONDS).join();
                                 eventProcessor.start().orTimeout(30, TimeUnit.SECONDS).join();
                             }
                         });
    }
}
```

**Notes:**

- **Bean rename + import move.**
  `org.axonframework.config.EventProcessingConfiguration` →
  `org.axonframework.common.configuration.AxonConfiguration`. The
  field, the constructor parameter, and the call sites all rename to
  `axonConfiguration`.
- **Module-name convention.** AF5 registers each event processor as a
  module under `"EventProcessor[" + processorName + "]"` — verified
  against `PooledStreamingEventProcessorModule.java` and
  `SubscribingEventProcessorModule.java` (`super("EventProcessor[" + processorName + "]")`).
  Keeping the assembled string in a `var moduleName = ...` local
  reads better than inlining the concatenation inside
  `.getModuleConfiguration(...)`.
- **Looked-up type.** AF4's `TrackingEventProcessor` is **removed** in
  AF5. Broaden to `StreamingEventProcessor` (parent of
  `PooledStreamingEventProcessor` and the natural target for
  `supportsReset / resetTokens / shutdown / start`). Using
  `PooledStreamingEventProcessor` directly would also work but
  over-commits.
- **Method-name change.** `shutDown()` → `shutdown()` (lowercase `d`).
  Easy to miss — it compiles in AF4, fails in AF5.
- **Async lifecycle + explicit timeout.** `start()`, `shutdown()`,
  `resetTokens()` all return `CompletableFuture<Void>` in AF5. This
  class is a top-of-chain ops entry point with no surrounding
  `ProcessingContext`, so blocking is acceptable — but only with an
  explicit timeout. The project rule
  (`.claude/rules/completablefuture-blocking.md`) forbids naked
  `.join()`. Default timeout used here: `30` seconds, matching the
  DLQ migration doc's worked example.
- **`TokenStore` injection dropped.** The AF4 file also injected
  `TokenStore` for a `progressOf(processor)` operation. That is a
  separate concern — AF5 wants the same `TokenStore` acquired through
  `axonConfiguration.getOptionalComponent(TokenStore.class[, name])`,
  and the surrounding `progressOf` method needs its own rewrite (the
  `ReplayToken` API moved). It was intentionally removed from the
  per-class example so the diff focuses on the read-configuration
  pattern; the user can run the skill again on the same file once the
  progress block is in scope, or follow up with a separate ticket.
- **Spring stereotype preserved.** `@Component` is kept as-is; the
  surrounding constructor visibility was bumped to `public` to match
  the other Spring-managed bean conventions in the project, but that
  is a project preference — the skill itself does not change
  constructor visibility.
