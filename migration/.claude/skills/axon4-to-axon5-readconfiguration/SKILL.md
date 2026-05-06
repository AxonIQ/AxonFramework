---
name: axon4-to-axon5-readconfiguration
description: >
  Migrate ONE class that **reads** Axon Framework configuration at runtime —
  e.g. injects `org.axonframework.config.Configuration` or
  `org.axonframework.config.EventProcessingConfiguration` to look up an
  event processor, a token store, a dead-letter processor, or any other
  registered component — from Axon Framework 4 to Axon Framework 5.
  Switches the injected bean type to `AxonConfiguration`
  (`org.axonframework.common.configuration.AxonConfiguration`), rewrites
  AF4 dedicated lookup methods (`eventProcessorByProcessingGroup`,
  `eventProcessor`, `sequencedDeadLetterProcessor`, …) into the generic
  AF5 two-step lookup
  `axonConfiguration.getModuleConfiguration("<module-name>")
      .flatMap(m -> m.getOptionalComponent(<Type>.class))`,
  updates type/import for the looked-up component (e.g.
  `TrackingEventProcessor` → `StreamingEventProcessor`,
  `org.axonframework.eventhandling.*` →
  `org.axonframework.messaging.eventhandling.processing.streaming.*`),
  and adapts call sites where the looked-up component's lifecycle methods
  became async (`CompletableFuture<Void>` return for `start()`,
  `shutdown()`, `resetTokens()`, …). Atomic — exactly one class per run.
  Sibling skill of `axon4-to-axon5-writeconfiguration` for the
  configurer / `ConfigurationEnhancer` write side; classes that only
  *configure* the framework belong to that skill, not this one.
---

# AF4 → AF5: Read access to `Configuration`

Atomic migration of a single class that **reads** Axon Framework
configuration at runtime — typically a Spring `@Component` or service
that injects a configuration bean to look up a registered framework
component (event processor, dead-letter processor, token store, custom
component) and call methods on it.

> **Keep this skill generic.** It runs across many projects. Describe the
> source/target purely in framework terms (annotations, types, API
> signatures) — never in terms of a specific project's package, module,
> or file layout. Project-specific knowledge lives in
> `references/examples/` only. If a project-specific quirk needs to
> influence the transformation, route on an observable shape (the
> injected type, the AF4 lookup method, the looked-up component type),
> not on project identity.

## What this migrates

- **From:** a class that has a field / constructor parameter / method
  parameter typed as one of the AF4 read-side configuration interfaces
  and uses it to look up a framework component:
  - `org.axonframework.config.Configuration` (root configuration)
  - `org.axonframework.config.EventProcessingConfiguration` (event
    processing — `eventProcessor(name)`,
    `eventProcessorByProcessingGroup(group)`,
    `eventProcessorByProcessingGroup(group, Class)`,
    `sequencedDeadLetterProcessor(group)`,
    `tokenStore(group)`, `sagaConfiguration(type)`, …)
  - any other AF4 sub-configuration whose dedicated lookup method has
    been replaced by the generic `getOptionalComponent` lookup in AF5
- **To:** the same class, with:
  - the injected bean changed to `AxonConfiguration`
    (`org.axonframework.common.configuration.AxonConfiguration`) — or
    `Configuration` (`org.axonframework.common.configuration.Configuration`)
    when the class only *reads* and never starts/shuts down the root,
  - dedicated AF4 lookup calls rewritten to the AF5 two-step
    `axonConfiguration
        .getModuleConfiguration("<module-name>")
        .flatMap(m -> m.getOptionalComponent(<TargetType>.class[, "<componentName>"]))`,
  - the looked-up component type updated where AF5 renamed/moved it
    (e.g. `TrackingEventProcessor` → `StreamingEventProcessor`),
  - call sites adapted where the looked-up component's lifecycle is now
    async (`CompletableFuture<Void>` return for `start()`, `shutdown()`,
    `resetTokens()`, `processAny()`, …) — bridged with
    `.orTimeout(<duration>, <unit>).join()` per the project rule on
    `CompletableFuture` blocking.
- **Scope per run:** exactly one class (see "Selection rule").

## Selection rule

If the user names a target (class, file path), use it. Otherwise: pick
the **first** candidate in lexical order by file path among classes
that match the "From" shape above. Never migrate more than one per
run.

## Procedure

1. **Locate the candidate.** If no target was named, run a deterministic
   search for classes that still inject AF4 read-side configuration:
   ```bash
   grep -rln --include='*.java' \
     -e 'org.axonframework.config.Configuration\b' \
     -e 'org.axonframework.config.EventProcessingConfiguration' \
     <source roots>
   ```
   Pick the first file (lexical order) that **uses** the injected type
   for a read operation (a method call on the field, e.g.
   `eventProcessorByProcessingGroup`, `getOptionalComponent`,
   `tokenStore`). Skip files where the AF4 type appears only inside
   `@Bean` / `Configurer` / `ConfigurerModule` configuration code —
   those belong to the sibling **write** skill.

2. **Read the canonical migration-path doc(s)** before transforming
   anything. This skill is grounded in:
   - `paths/configuration.adoc` — the `AxonConfiguration` /
     `ConfigurationEnhancer` / `ComponentRegistry` model. Confirms the
     AF5 root type and package.
   - `paths/dlq.adoc` — shows the canonical AF4 dedicated-lookup → AF5
     `getModuleConfiguration(name).flatMap(m -> m.getOptionalComponent(Type, [name]))`
     rewrite with an explicit timeout on the returned future. Use it as
     the worked reference for any "look up a component scoped to a
     processor" case.
   - `paths/projectors-event-processors.adoc` — explains the
     `TrackingEventProcessor` removal and `StreamingEventProcessor` /
     `PooledStreamingEventProcessor` replacement that you will hit
     whenever the AF4 lookup was typed to `TrackingEventProcessor`.

   Local excerpts in `references/migration-paths.md`.

3. **Apply the transformation instructions** below. They are this
   skill's LLM-specific edits — narrower and more prescriptive than the
   docs, and they grow over time as `reflect` folds in lessons from
   real runs.

4. **Show the diff** and summarize what changed (field type, lookup
   rewrites, type rename of looked-up component, async call-site
   adaptation, deleted imports).

5. **Stop and ask the human to verify.** Do **not** rely on
   `mvn compile` passing — peer constructs are typically still on the
   old API and the project is expected to be broken mid-migration. The
   human decides acceptable / not-acceptable.

> **Fallback only:** if the migration-path docs and the instructions in
> this skill leave a real gap (e.g. an AF4 read-side method without an
> obvious AF5 lookup target, an unfamiliar module-name convention),
> inspect the AF source at the paths in `references/source-access.md`.
> Treat that as a signal to run `reflect` afterwards so the missing
> knowledge folds back into the transformation instructions and the
> fallback isn't needed next time.

## Transformation instructions

The full migration is mechanical once the cheat sheet is in hand. Apply
each step in order; skip steps whose precondition isn't present in the
candidate file.

### 1. FQN cheat sheet

Use this for every step below — never guess imports.

#### AF4 (remove these)

| Element                              | FQN |
|--------------------------------------|---|
| `Configuration`                      | `org.axonframework.config.Configuration` |
| `EventProcessingConfiguration`       | `org.axonframework.config.EventProcessingConfiguration` |
| `TrackingEventProcessor`             | `org.axonframework.eventhandling.TrackingEventProcessor` |
| `EventProcessor` (AF4 location)      | `org.axonframework.eventhandling.EventProcessor` |
| `StreamingEventProcessor` (AF4 loc.) | `org.axonframework.eventhandling.StreamingEventProcessor` |
| `TokenStore` (AF4 location)          | `org.axonframework.eventhandling.tokenstore.TokenStore` |

#### AF5 (add these)

| Element                                  | FQN |
|------------------------------------------|---|
| `AxonConfiguration`                      | `org.axonframework.common.configuration.AxonConfiguration` |
| `Configuration` (AF5 read-only parent)   | `org.axonframework.common.configuration.Configuration` |
| `StreamingEventProcessor` (AF5 location) | `org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor` |
| `EventProcessor` (AF5 location)          | `org.axonframework.messaging.eventhandling.processing.EventProcessor` |
| `PooledStreamingEventProcessor`          | `org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor` |
| `SubscribingEventProcessor`              | `org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessor` |

### 2. Replace the injected bean type

- **Field / constructor parameter type.** Replace the AF4 configuration
  type with `AxonConfiguration` (preferred default — it is the bean
  Spring auto-configuration registers in AF5 and exposes the same read
  API as `Configuration`).
- **Field name.** Rename the field to track the new type so the
  call-site reads naturally — e.g.
  `eventProcessingConfiguration` → `axonConfiguration`. Update the
  constructor parameter name to match.
- **Imports.** Remove the AF4 import (`org.axonframework.config.*`) and
  add `import org.axonframework.common.configuration.AxonConfiguration;`.
- **Spring stereotype** (`@Component`, `@Service`, …) and any
  `@Transactional` / `@Autowired` annotations on the surrounding class
  / constructor are preserved as-is.

### 3. Rewrite the AF4 dedicated-lookup call into the AF5 two-step lookup

AF4's read-side configuration exposed many *named* lookup methods. AF5
collapses all of them onto two generic operations on `Configuration`:
`getModuleConfiguration(String name)` (returns
`Optional<Configuration>`) and `getOptionalComponent(Class<C>)` /
`getOptionalComponent(Class<C>, String)` on that module configuration.

The rewrite is always:

```java
// AF5
axonConfiguration.getModuleConfiguration("<module-name>")
                 .flatMap(m -> m.getOptionalComponent(<Type>.class[, "<componentName>"]))
                 .ifPresent(...);
```

Pick `<module-name>` from the table below based on the AF4 method that
was being called. The string is what AF5's module naming convention
emits when the framework registers that module — verified by reading
the AF5 source for the module class.

| AF4 read call                                                         | AF5 module name                                                  | AF5 component type to look up                                 |
|-----------------------------------------------------------------------|------------------------------------------------------------------|---------------------------------------------------------------|
| `eventProcessor(name)` / `eventProcessor(name, Class)`                | `"EventProcessor[" + name + "]"`                                 | `EventProcessor` (or its subtype `StreamingEventProcessor`, `SubscribingEventProcessor`, `PooledStreamingEventProcessor`) |
| `eventProcessorByProcessingGroup(group)` / `…(group, Class)`          | `"EventProcessor[" + processorNameForGroup + "]"` *(see note)*   | same                                                          |
| `sequencedDeadLetterProcessor(group)`                                 | the processor's module *(`"EventProcessor[<name>]"`)* — then a second `getOptionalComponent` call by **component name** `"EventHandlingComponent[" + processorName + "][" + componentName + "]"` | `SequencedDeadLetterProcessor` |
| `tokenStore(processor)`                                               | `"EventProcessor[" + processor + "]"`                            | `TokenStore`                                                  |
| `sagaConfiguration(SagaType)`                                         | (no AF5 equivalent — sagas are reframed in AF5)                  | follow up via the `axon4-to-axon5-writeconfiguration` skill / saga path |
| custom component looked up via `findModule` / `getModuleConfiguration` | use the AF4 string verbatim                                      | the same target type — only the package may have moved        |

> **Note on `eventProcessorByProcessingGroup` (AF4) → AF5 module
> name.** AF4 had two distinct keys: the *processor name* (the bus
> registration key) and the *processing group* (the `@ProcessingGroup`
> argument). When they were the same string (the common case), the AF5
> module name is `"EventProcessor[" + group + "]"`. When the group was
> rebound to a different processor name in AF4 config, you must rebind
> in AF5 (write side) and use the **processor name** here, not the
> group name.

#### Default lookup (no name disambiguation)

When the AF4 call did not pass a name, prefer the no-name AF5
`getOptionalComponent(<Type>.class)` overload:

```java
// AF4
eventProcessingConfiguration
        .eventProcessorByProcessingGroup(processor, TrackingEventProcessor.class)
        .ifPresent(...);

// AF5
axonConfiguration.getModuleConfiguration("EventProcessor[" + processor + "]")
                 .flatMap(m -> m.getOptionalComponent(StreamingEventProcessor.class))
                 .ifPresent(...);
```

#### Named lookup (component disambiguation)

When the looked-up component is one of several inside the same module
(typical for dead-letter processors scoped to a specific event-handling
component), pass the AF5 component name as the second argument:

```java
// AF5
axonConfiguration.getModuleConfiguration(processorName)
                 .flatMap(m -> m.getOptionalComponent(
                         SequencedDeadLetterProcessor.class,
                         "EventHandlingComponent[" + processorName + "][" + componentName + "]"))
                 .ifPresent(...);
```

(Source: `paths/dlq.adoc` worked example.)

### 4. Update the looked-up component's type and import

Some AF4 component types changed package, name, or both in AF5. Apply
the rename **inside the lookup** and update the import accordingly:

| AF4 type (read-side lookup)                | AF5 type / location                                                                                              |
|--------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| `TrackingEventProcessor`                   | `StreamingEventProcessor` (`org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor`) — `TrackingEventProcessor` is **removed** in AF5 |
| `StreamingEventProcessor` (`o.a.eventhandling.*`) | `StreamingEventProcessor` (`o.a.messaging.eventhandling.processing.streaming.*`) — same name, new package |
| `EventProcessor` (`o.a.eventhandling.*`)   | `EventProcessor` (`o.a.messaging.eventhandling.processing.*`)                                                    |
| `PooledStreamingEventProcessor` (`o.a.eventhandling.pooled.*`) | `PooledStreamingEventProcessor` (`o.a.messaging.eventhandling.processing.streaming.pooled.*`)        |
| `TokenStore` (`o.a.eventhandling.tokenstore.*`) | confirm with `references/source-access.md` — package likely moved under `o.a.eventstreaming.tokenstore.*`    |

If the AF4 lookup was specifically typed (e.g.
`eventProcessorByProcessingGroup(processor, TrackingEventProcessor.class)`),
**broaden** the AF5 lookup to the most general type that still gives
the caller the methods it uses. `StreamingEventProcessor` is the right
default for code that calls `supportsReset()`, `resetTokens()`,
`shutdown()`, `start()` — no need to commit to
`PooledStreamingEventProcessor`.

### 5. Adapt call sites where the looked-up component went async

In AF4, `EventProcessor` lifecycle methods were synchronous:

- `start()` → `void`
- `shutDown()` → `void` *(note the capital `D`)*
- `resetTokens()` → `void`

In AF5, they are async:

- `start()` → `CompletableFuture<Void>`
- `shutdown()` → `CompletableFuture<Void>` *(now lowercase `d`)*
- `resetTokens()` → `CompletableFuture<Void>`

The same shift applies to `SequencedDeadLetterProcessor#processAny()`,
`#process(...)`, etc. (per `paths/dlq.adoc`).

#### Method-name change

Rename `shutDown()` → `shutdown()` at every call site touched by this
migration.

#### Default: bridge with an explicit-timeout `.join()`

The class doing the read access is typically a top-of-chain entry
point (REST controller, scheduler, CLI, ops endpoint) with no surrounding
`ProcessingContext`. It is allowed to block, but must use an explicit
timeout — never plain `.join()` or `.get()`. This is the AF5 project
rule (see `.claude/rules/completablefuture-blocking.md`):

```java
// AF5 — explicit timeout, never naked .join()
eventProcessor.shutdown().orTimeout(30, TimeUnit.SECONDS).join();
eventProcessor.resetTokens().orTimeout(30, TimeUnit.SECONDS).join();
eventProcessor.start().orTimeout(30, TimeUnit.SECONDS).join();
```

Add `import java.util.concurrent.TimeUnit;` if not already present.

#### Naked `.join()` is a smell — flag it

Code that calls `eventProcessor.shutdown().join()` (no timeout) compiles
and runs but violates the project rule. If the example you are
migrating from already uses naked `.join()`, **upgrade it** to
`.orTimeout(...).join()` as part of this migration. Choose a default of
`30` seconds unless the surrounding code suggests a tighter bound.

#### When the caller can stay async

If the caller is itself async-capable (returns `CompletableFuture<?>`,
or is composed via `thenCompose`), prefer chaining over blocking:

```java
return axonConfiguration.getModuleConfiguration("EventProcessor[" + processor + "]")
        .flatMap(m -> m.getOptionalComponent(StreamingEventProcessor.class))
        .filter(StreamingEventProcessor::supportsReset)
        .map(p -> p.shutdown()
                   .thenCompose(__ -> p.resetTokens())
                   .thenCompose(__ -> p.start()))
        .orElseGet(() -> CompletableFuture.completedFuture(null));
```

This keeps the timeout responsibility at the outer layer (the caller
already times out the whole operation).

### 6. Delete unused AF4 imports / fields / parameters

After the rewrite, sweep the file for:

- Stale AF4 imports (`org.axonframework.config.*`,
  `org.axonframework.eventhandling.TrackingEventProcessor`,
  `org.axonframework.eventhandling.tokenstore.TokenStore` if only
  `TokenStore` was looked up and no longer is).
- Now-unused secondary fields. A common AF4 shape was to inject **both**
  `EventProcessingConfiguration` and `TokenStore` — the latter to call
  `fetchSegments` / `fetchToken` outside the framework. AF5 wants the
  same lookups to go through `axonConfiguration.getOptionalComponent(TokenStore.class[, name])`,
  so a separately injected `TokenStore` field is usually now redundant.
  Delete it (and its constructor parameter) unless the user has flagged
  the lookup as out of scope for this run.

### 7. Out-of-scope: write-side configuration

This skill is **read-only**. If the candidate also configures the
framework — i.e. it has a `@Bean` returning a `Configurer`,
`ConfigurerModule`, `EventProcessingConfigurer` lambda,
`registerSequencingPolicy`, `registerErrorHandler`, etc. — that part of
the file is **out of scope** here. Leave the write-side calls
untouched, mention them in the diff summary, and point the user at
`axon4-to-axon5-writeconfiguration` as the follow-up.

## Reference docs

The migration-path .adoc(s) this skill is grounded in:

- `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5/docs/reference-guide/modules/migration/pages/paths/configuration.adoc`
- `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5/docs/reference-guide/modules/migration/pages/paths/dlq.adoc` (worked example for the lookup pattern)
- `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5/docs/reference-guide/modules/migration/pages/paths/projectors-event-processors.adoc` (TrackingEventProcessor removal, StreamingEventProcessor replacement)
- `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5/.claude/rules/completablefuture-blocking.md` (project rule on `.orTimeout(...).join()`)

Key excerpts kept locally in `references/migration-paths.md`.

## Source references (fallback)

`references/source-access.md` records where AF4 / AF5 sources resolve
on this machine. Used only when the migration-path docs and the
transformation instructions above are insufficient — particularly for
confirming an AF5 module-name convention you have not seen before
(grep for `super("...[" + ... + "]")` in the relevant `*Module.java`).

## Examples

Each file in `references/examples/` is one real migration from one
project, preserved verbatim. **All project-specific knowledge lives here**
— never in the procedure or transformation instructions above. New
examples are added, not merged: if a new project shows a different valid
pattern, drop in `references/examples/<NN>-<project>-<short-desc>.md`
rather than editing the existing ones.

- `references/examples/01-heroes-stream-processors-operations.md` —
  Spring `@Component` ops endpoint that injects
  `EventProcessingConfiguration` + `TokenStore`, looks up a
  `TrackingEventProcessor` by processing group, and runs the
  `shutDown / resetTokens / start` cycle. Demonstrates: bean rename,
  `EventProcessor[<name>]` module-name convention, `TrackingEventProcessor`
  → `StreamingEventProcessor`, sync → async lifecycle (`shutDown` →
  `shutdown` + `CompletableFuture<Void>`), and the `.join()` → flag
  for `.orTimeout(...).join()` upgrade.

## Variants

- **Read-only injection of root `Configuration`.** Class injects
  `org.axonframework.config.Configuration` and only calls
  `getComponent` / `findComponent`. Same migration: replace with
  `org.axonframework.common.configuration.AxonConfiguration` (or
  `Configuration` if start/shutdown are not used) and route every
  lookup through `getOptionalComponent` / `getModuleConfiguration`.
  Skip the lifecycle-async section if no lifecycle methods are called.
- **Mixed read + write.** The class also defines configuration (e.g.
  `@Bean ConfigurerModule`). Migrate **only** the read calls in this
  run; leave the write-side block untouched and flag it as a follow-up
  for `axon4-to-axon5-writeconfiguration`.
- **Lookup over a non-AF type.** AF4 sometimes exposed application
  beans through the `Configuration` (`registerComponent`). The AF5
  lookup is unchanged — `getOptionalComponent(MyBean.class)` — only
  the surrounding bean type and import change.

## Notes for the human

- This skill is iteratively improved via the `reflect` skill — after
  every correction, reflect to fold the lesson back into the
  instructions.
- If you change something manually (different module name, different
  default timeout, kept `Configuration` instead of `AxonConfiguration`
  for a reason), mention it briefly so reflect can capture the *why*.
- The `getModuleConfiguration` string is a **convention** maintained by
  the framework's own `*Module` classes. If a future AF5 release renames
  or reformats it, this skill needs an update — surface that as a doc
  bug rather than working around it locally.
