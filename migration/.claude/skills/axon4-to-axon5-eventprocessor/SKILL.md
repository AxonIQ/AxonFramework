---
name: axon4-to-axon5-eventprocessor
description: >
  Migrate ONE event-handling component (a class with `@EventHandler` methods,
  typically also annotated `@ProcessingGroup`) from Axon Framework 4 to
  Axon Framework 5. Switches imports/annotations to the new packages,
  replaces `@ProcessingGroup` with `@Namespace`, replaces a class-level
  `CommandGateway` field with a method-parameter `CommandDispatcher`
  bound to the current `ProcessingContext`, and rewrites blocking
  `commandGateway.sendAndWait(...)` calls into async
  `commandDispatcher.send(...)` returning `CompletableFuture<?>`.
  Atomic — exactly one event-handling class per run.
---

# AF4 → AF5: Event-handling component (Event Processor / Projector / Saga-like reactor)

Atomic migration of a single class that handles events via `@EventHandler`
methods and (optionally) dispatches commands in response.

> **Keep this skill generic.** It runs across many projects. Describe the
> source/target purely in framework terms (annotations, class shapes,
> method signatures) — never in terms of a specific project's package,
> module, or file layout. Project-specific knowledge lives in
> `references/examples/` only.

## What this migrates

- **From:** a class — typically Spring `@Component` or plain — that:
  - is annotated with `@ProcessingGroup("...")`
    (`org.axonframework.config.ProcessingGroup`), and/or
  - has at least one method annotated with `@EventHandler`
    (`org.axonframework.eventhandling.EventHandler`), and optionally:
  - injects `CommandGateway`
    (`org.axonframework.commandhandling.gateway.CommandGateway`) as a
    constructor / field dependency,
  - calls `commandGateway.sendAndWait(...)` /
    `commandGateway.send(...)` inside an `@EventHandler` method,
  - uses `@MetaDataValue`
    (`org.axonframework.messaging.annotation.MetaDataValue`) on handler
    parameters,
  - is annotated `@DisallowReplay`
    (`org.axonframework.eventhandling.DisallowReplay`).
- **To:** the same class, with:
  - `@Namespace("...")`
    (`org.axonframework.messaging.core.annotation.Namespace`) replacing
    `@ProcessingGroup` (1:1 string argument),
  - `@EventHandler` from
    `org.axonframework.messaging.eventhandling.annotation.EventHandler`,
  - `@DisallowReplay` from
    `org.axonframework.messaging.eventhandling.replay.annotation.DisallowReplay`,
  - `@MetadataValue` (note capitalisation) from
    `org.axonframework.messaging.core.annotation.MetadataValue`,
  - the `CommandGateway` field, constructor parameter, and any `final`
    field assignment **removed** if the gateway was used **only** inside
    handler methods,
  - `CommandDispatcher`
    (`org.axonframework.messaging.commandhandling.gateway.CommandDispatcher`)
    declared as a **method parameter** on each `@EventHandler` (and any
    other in-context handler) that needs to send commands,
  - blocking `commandGateway.sendAndWait(cmd)` /
    `commandGateway.sendAndWait(cmd, metadata)` calls rewritten to
    `commandDispatcher.send(cmd)` / `commandDispatcher.send(cmd, metadata)`,
  - the `@EventHandler` method return type changed `void` →
    `CompletableFuture<?>` whenever it now returns the dispatcher's
    result (no more manual `.join()` / blocking — the framework consumes
    the future).
- **Scope per run:** exactly one event-handling class (see "Selection
  rule").

## Selection rule

If the user names a target (class, file path), use it. Otherwise: pick the
**first** candidate in lexical order by file path among classes that match
the "From" shape above. Never migrate more than one per run.

## Procedure

1. **Locate the candidate.** If no target was named, run a deterministic
   search for classes that still use the AF4 shape:
   ```bash
   grep -rln --include='*.java' \
     -e 'org.axonframework.config.ProcessingGroup' \
     -e 'org.axonframework.eventhandling.EventHandler' \
     -e 'org.axonframework.commandhandling.gateway.CommandGateway' \
     <source roots>
   ```
   Pick the first file (lexical order) that has *both* an `@EventHandler`
   method and at least one of: `@ProcessingGroup`, a `CommandGateway`
   field, an AF4 `@DisallowReplay`, or an AF4 `@MetaDataValue` parameter.

2. **Sweep for external configuration tied to this processor.** Before
   transforming anything, grep the project for the AF4 processing-group
   name — i.e. the string argument inside the candidate's
   `@ProcessingGroup("...")`. Look in:
   - `application.yml` / `application.properties` /
     `application-*.{yml,properties}` (typical key shape:
     `axon.eventhandling.processors.<group>.*`)
   - `@Configuration` / `@Component` classes that declare `@Bean`
     methods returning `SequencingPolicy`, `ErrorHandler`,
     `ListenerInvocationErrorHandler`, etc., or that call
     `EventProcessingConfigurer#registerSequencingPolicy(group, ...)` /
     `#registerListenerInvocationErrorHandler(group, ...)` /
     `#registerErrorHandler(group, ...)` against this group name.

   ```bash
   grep -rln --include='*.yml' --include='*.yaml' --include='*.properties' \
     '<group-name>' <project root>
   grep -rln --include='*.java' \
     -e '<group-name>' \
     -e 'registerSequencingPolicy' \
     -e 'registerListenerInvocationErrorHandler' \
     <source roots>
   ```

   Note what the sweep finds. Some of it (sequencing policy) becomes a
   class-level annotation in transformation step 8; the rest may need
   to be **deleted** as part of this migration so dead config does not
   linger. If nothing turns up, skip step 8 — the AF5 defaults apply.

3. **Read the canonical migration-path doc** before transforming
   anything: `docs/reference-guide/modules/migration/pages/paths/projectors-event-processors.adoc`.
   Local excerpts in `references/migration-paths.md`.

4. **Apply the transformation instructions** below. They are this skill's
   LLM-specific edits — narrower and more prescriptive than the doc, and
   they grow over time as `reflect` folds in lessons from real runs.

5. **Show the diff** and summarize what changed (annotations swapped,
   imports moved, gateway → dispatcher, return type changes, external
   config deleted).

6. **Stop and ask the human to verify.** Do **not** rely on `mvn compile`
   passing — peer constructs are typically still on the old API and the
   project is expected to be broken mid-migration. The human decides
   acceptable / not-acceptable.

> **Fallback only:** if the migration-path doc and the instructions in
> this skill leave a real gap, inspect the AF source at the paths in
> `references/source-access.md`. Treat that as a signal to run `reflect`
> afterwards so the missing knowledge folds back into the transformation
> instructions and the fallback isn't needed next time.

## Transformation instructions

The full migration is mechanical once you have the cheat sheet. Apply
each step in order; skip steps whose precondition isn't present in the
candidate file.

### 1. Annotation / class FQN cheat sheet

Use this for every step below — never guess imports.

#### AF4 (remove these)

| Element                         | FQN |
|---------------------------------|---|
| `@ProcessingGroup`              | `org.axonframework.config.ProcessingGroup` |
| `@EventHandler`                 | `org.axonframework.eventhandling.EventHandler` |
| `@DisallowReplay`               | `org.axonframework.eventhandling.DisallowReplay` |
| `@ResetHandler`                 | `org.axonframework.eventhandling.ResetHandler` |
| `@MetaDataValue`                | `org.axonframework.messaging.annotation.MetaDataValue` |
| `CommandGateway` (AF4 location) | `org.axonframework.commandhandling.gateway.CommandGateway` |

#### AF5 (add these)

| Element                                               | FQN |
|-------------------------------------------------------|---|
| `@Namespace`                                          | `org.axonframework.messaging.core.annotation.Namespace` |
| `@EventHandler`                                       | `org.axonframework.messaging.eventhandling.annotation.EventHandler` |
| `@DisallowReplay`                                     | `org.axonframework.messaging.eventhandling.replay.annotation.DisallowReplay` |
| `@ResetHandler`                                       | `org.axonframework.messaging.eventhandling.replay.annotation.ResetHandler` |
| `@MetadataValue`                                      | `org.axonframework.messaging.core.annotation.MetadataValue` |
| `CommandDispatcher`                                   | `org.axonframework.messaging.commandhandling.gateway.CommandDispatcher` |
| `CommandGateway` (AF5 location, only when keeping it) | `org.axonframework.messaging.commandhandling.gateway.CommandGateway` |
| `Metadata` (only if you handle the type directly)     | `org.axonframework.messaging.core.Metadata` |
| `@SequencingPolicy`                                   | `org.axonframework.messaging.core.annotation.SequencingPolicy` |
| `SequentialPerAggregatePolicy` (AF5 location)         | `org.axonframework.messaging.core.sequencing.SequentialPerAggregatePolicy` |
| `SequentialPolicy`                                    | `org.axonframework.messaging.core.sequencing.SequentialPolicy` |
| `MetadataSequencingPolicy`                            | `org.axonframework.messaging.core.sequencing.MetadataSequencingPolicy` |
| `PropertySequencingPolicy`                            | `org.axonframework.messaging.core.sequencing.PropertySequencingPolicy` |
| `RoutingKeySequencingPolicy`                          | `org.axonframework.messaging.core.sequencing.RoutingKeySequencingPolicy` |
| `HierarchicalSequencingPolicy`                        | `org.axonframework.messaging.core.sequencing.HierarchicalSequencingPolicy` |
| `NoOpSequencingPolicy`                                | `org.axonframework.messaging.core.sequencing.NoOpSequencingPolicy` |

### 2. Class-level annotations

- Replace `@ProcessingGroup("X")` with `@Namespace("X")` — same string
  argument. Update the import accordingly.
- Keep `@DisallowReplay` and `@ResetHandler` (and any framework-agnostic
  stereotypes such as Spring's `@Component`); just update their imports
  to the AF5 FQNs. Both replay annotations move from
  `org.axonframework.eventhandling.*` to
  `org.axonframework.messaging.eventhandling.replay.annotation.*`.

### 3. Handler-method imports

- Update `@EventHandler` import to the AF5 FQN.
- Update `@MetaDataValue` → `@MetadataValue` (note the **lowercase `d`**
  in `Metadata`). Update both the annotation reference and the import.
- Do **not** change handler-method visibility, name, or parameter order;
  AF5 resolves parameters by type/annotation, not position.

### 4. Replace `CommandGateway` (class-level) with `CommandDispatcher` (method-level)

This is the substantive change. Apply it only when the gateway was used
**inside handler methods** (the in-context case). If the gateway is
genuinely used outside any handler — e.g. exposed publicly, called from a
non-handler helper, or used in a method without a `ProcessingContext` —
keep it as a class-level dependency and only update its import to the AF5
FQN. (Decision rule from the `CommandDispatcher` Javadoc: gateway for
top-of-chain entry points; dispatcher inside another handler.)

For the in-context case:

1. **Remove ONLY the gateway field** (e.g.
   `private final CommandGateway commandGateway;`). Other private fields
   on the class (calculators, repositories, helpers) stay untouched.
2. **Remove ONLY the gateway parameter** from the constructor — leave any
   other constructor parameters in place and keep their corresponding
   field assignments. Delete the entire constructor only when the gateway
   was its **sole** parameter; in that case Spring will use the default
   no-arg one.
3. **Add `CommandDispatcher commandDispatcher`** as a parameter on every
   `@EventHandler` method that previously called `commandGateway.*`. It
   will be auto-injected by `CommandDispatcherParameterResolverFactory`.
4. **Rewrite the call sites** inside that method:

   | AF4 | AF5 |
   |---|---|
   | `commandGateway.sendAndWait(cmd)` | `commandDispatcher.send(cmd)` |
   | `commandGateway.sendAndWait(cmd, metadata)` | `commandDispatcher.send(cmd, metadata)` |
   | `commandGateway.send(cmd)` (fire-and-forget) | `commandDispatcher.send(cmd)` |
   | `commandGateway.send(cmd, metadata)` | `commandDispatcher.send(cmd, metadata)` |
   | `commandGateway.sendAndWait(cmd, ResultType.class)` | `commandDispatcher.send(cmd, ResultType.class)` (returns `CompletableFuture<ResultType>`) |

### 5. Return-type change: `void` → `CompletableFuture<?>`

If the handler now returns the dispatcher's result instead of blocking:

- Change the return type from `void` to `CompletableFuture<?>`.
- Add `import java.util.concurrent.CompletableFuture;` if missing.
- `return commandDispatcher.send(cmd, metadata);` directly. The framework
  consumes the returned future / `CommandResult` so do **not** call
  `.join()`, `.get()`, or `.getResultMessage().join()`.
- If the handler dispatches in **multiple branches** (e.g. try/catch
  compensation, `if/else` with different commands), every branch must
  return a future. Return a `CompletableFuture` from each branch.
- If a branch does **no** command dispatch (early-return path), return
  `CompletableFuture.completedFuture(null)`.
- **Conditional dispatch — prefer early-return inversion.** When the AF4
  shape was `if (cond) { commandGateway.sendAndWait(...); }` (no `else`,
  the no-dispatch branch is empty), invert the condition and return
  early instead of wrapping the dispatch in `if`:

   ```java
   // WRONG — leaves an implicit no-return path on the false branch
   if (cond) {
       return commandDispatcher.send(cmd, metadata);
   }
   return CompletableFuture.completedFuture(null);

   // PREFERRED — early-return for the no-op branch, then dispatch flat
   if (!cond) {
       return CompletableFuture.completedFuture(null);
   }
   return commandDispatcher.send(cmd, metadata);
   ```

  Reads top-down, makes the dispatch the obvious main path, and keeps the
  guard-clause idiom familiar to most Java codebases.
- If the handler still has work to do after dispatch (logging,
  bookkeeping), prefer chaining via `.thenRun(...)` / `.thenApply(...)`
  on the dispatcher's result rather than blocking.

### 6. Delete unused private fields and constructors

After the gateway is gone, check the class for:

- Now-empty constructors → delete or leave as default.
- Now-unused private fields → delete.
- Stale imports (`CommandGateway`, AF4 packages) → delete.

### 7. Out-of-scope: helper-class imports

A common project pattern is a helper like `XxxMetaData.with(...)` that
returns the framework's metadata type. In AF5 that should be
`org.axonframework.messaging.core.Metadata`. **The per-processor skill
does not migrate that helper** — it lives in another package and may be
shared across many handlers. If `commandDispatcher.send(cmd, helperOut)`
fails to compile because the helper still returns the AF4
`org.axonframework.messaging.MetaData`, flag it for the user as a
follow-up rather than editing the helper here.

### 8. Sequencing policy — move from external config to `@SequencingPolicy` on the class

In AF4, sequencing policies were typically configured **outside** the
event-handling class — either in Spring Boot YAML
(`axon.eventhandling.processors.<name>.sequencing-policy`) or via a
`@Bean`-defined policy in a configuration class registered against the
processing group name. AF5 keeps the same policy *types* but lets you
attach them directly to the handling component with the `@SequencingPolicy`
annotation, so the ordering rule lives next to the code it governs.

This is **not always required** — if AF4 relied on the default
(`SequentialPerAggregatePolicy` on aggregate-based event stores), AF5's
default still resolves to the same behaviour for that case (the AF5
default is `HierarchicalSequencingPolicy(SequentialPerAggregatePolicy →
SequentialPolicy)`, which is identical for aggregate-based stores). Apply
this step only when AF4 had an **explicit** override for this processor.

How to detect the AF4 override:

- **YAML / properties.** Search application config for the processor's
  AF4 group name (the string from `@ProcessingGroup("...")` — now the
  argument of `@Namespace("...")`). Look for keys like
  `axon.eventhandling.processors.<group>.sequencing-policy` or any
  custom binding under the same group key.
- **Java config.** Search Spring configuration classes (e.g.
  `AxonConfiguration`, `GameConfiguration`) for a method or `@Bean` that
  registers a `SequencingPolicy` against the processor name — typically
  `EventProcessingConfigurer#registerSequencingPolicy(group, factory)`
  in AF4, or a bean returning a `SequencingPolicy<?>` that the AF4
  auto-config wires by name.
- **Custom `SequencingPolicy` implementation.** A class implementing
  AF4's `org.axonframework.eventhandling.async.SequencingPolicy` —
  these have to be migrated separately (signature change + package
  move); see `paths/sequencing-policies.adoc`.

How to apply:

1. **Pick the AF5 policy class** for the AF4 setting:

   | AF4 setting / bean | AF5 policy class |
   |---|---|
   | `SequentialPerAggregatePolicy` (default, explicit) | `SequentialPerAggregatePolicy` |
   | `SequentialPolicy` (full serial) | `SequentialPolicy` |
   | `FullConcurrencyPolicy` (no ordering) | `NoOpSequencingPolicy` |
   | "by metadata key" — keyed on a metadata field | `MetadataSequencingPolicy` (`parameters = "<metadataKey>"`) |
   | "by event property" — keyed on a payload property | `PropertySequencingPolicy` (`parameters = "<propertyName>"`) |
   | "by routing key" — `@RoutingKey` on the message | `RoutingKeySequencingPolicy` |
   | hierarchical / fallback chain | `HierarchicalSequencingPolicy` |
   | custom implementation | migrate per `paths/sequencing-policies.adoc`, then point `type = ...` at the new class |

2. **Annotate the event-processor class** (or a single `@EventHandler`
   method when the policy applies to just one handler — method-level
   wins over class-level):

   ```java
   @Namespace("...")
   @SequencingPolicy(type = MetadataSequencingPolicy.class, parameters = "<metadataKey>")
   class MyProcessor { ... }
   ```

   Notes:
   - `parameters` is `String[]`. A single string literal is fine; for
     multiple values use `parameters = {"a", "b"}`.
   - Constants like `GameMetaData.GAME_ID_KEY` work because they resolve
     to compile-time `String` constants — annotation-legal.
   - `type` must be `Class<? extends SequencingPolicy>` from
     `org.axonframework.messaging.core.sequencing.*`.

3. **Delete the AF4 configuration source** so it can't drift:
   - Remove the YAML / properties key.
   - Remove the `@Bean` / `registerSequencingPolicy(...)` from the Java
     configuration class.

4. **Flag for the user** if the policy was a custom AF4
   `SequencingPolicy<EventMessage<?>>` implementation. The annotation
   change here is mechanical, but the implementation class itself must
   migrate (package move + signature change to
   `Optional<Object> sequenceIdentifierFor(M, ProcessingContext)`). This
   is **out of scope** for the per-processor skill.

### 9. Tests

Tests for event-handling components are typically written as Spring
integration tests with a `RecordingCommandBus` (or equivalent). The
AF4 → AF5 change there is mostly the same import / annotation move; if
the test class fails to compile after this skill runs, that's expected
mid-migration — the test migration is a separate concern.

## Reference docs

The migration-path .adoc(s) this skill is grounded in:

- `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5/docs/reference-guide/modules/migration/pages/paths/projectors-event-processors.adoc`
- `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5/docs/reference-guide/modules/migration/pages/paths/index.adoc` (import & package changes table)
- `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5/docs/reference-guide/modules/migration/pages/paths/sequencing-policies.adoc` (sequencing-policy migration — annotation form, default change, custom-policy migration)

Key excerpts kept locally in `references/migration-paths.md`.

## Source references (fallback)

`references/source-access.md` records where AF4 / AF5 sources resolve on
this machine. Used only when the migration-path doc and the
transformation instructions above are insufficient.

## Examples

Each file in `references/examples/` is one real migration from one
project, preserved verbatim. **All project-specific knowledge lives here**
— never in the procedure or transformation instructions above. New
examples are added, not merged: if a new project shows a different valid
pattern, drop in `references/examples/<NN>-<project>-<short-desc>.md`
rather than editing the existing ones.

- `references/examples/01-heroes-when-creature-recruited.md` — Spring
  Boot processor with try/catch compensation, two `sendAndWait` calls.
- `references/examples/02-heroes-when-week-started.md` — Spring Boot
  processor with multi-DI constructor (gateway + a calculator) and a
  conditional `if (cond) sendAndWait(...)` branch with no-op false path
  (early-return inversion + `completedFuture(null)`).

## Variants

- **Pure projector (no command dispatch).** The handler reads the event
  and updates a read model — no `CommandGateway` is involved. Apply
  steps 1–3 (annotations + imports) and step 6 (cleanup) only. Skip
  steps 4 and 5 entirely; the return type stays `void`.
- **Saga-like reactor with non-handler dispatch.** If the class also
  exposes a public method that dispatches commands (e.g. a Spring-MVC
  endpoint or scheduler entry), keep `CommandGateway` as a class-level
  dependency for that path **and** add `CommandDispatcher` as a method
  parameter on the in-context handlers. Both can coexist on the same
  class.
- **Mixed `@EventHandler` + `@QueryHandler`.** The same parameter-
  resolution rule applies to query handlers — declare
  `CommandDispatcher` as a method parameter wherever the handler runs
  inside a `ProcessingContext`. Out of scope here; if encountered, run
  the dedicated query-handler skill (TBD) afterwards.

## Notes for the human

- This skill is iteratively improved via the `reflect` skill — after
  every correction, reflect to fold the lesson back into the
  instructions.
- If you change something manually (e.g. you decide to keep the gateway
  on a particular class because the handler dispatches through a
  non-context path), mention it briefly so reflect can capture the *why*.
- The example file's `commandDispatcher.send(cmd, metadata)` returns
  `CommandResult`, not `CompletableFuture` directly. The `@EventHandler`
  return type `CompletableFuture<?>` is honoured because AF5's handler
  adapter accepts the dispatcher's result; if you ever need to compose
  with other futures explicitly, use `commandResult.getResultMessage()`
  to obtain a `CompletableFuture<? extends Message>`.
