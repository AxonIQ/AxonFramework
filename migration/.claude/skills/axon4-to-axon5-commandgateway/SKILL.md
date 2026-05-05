---
name: axon4-to-axon5-commandgateway
description: >
  Migrate ONE class that dispatches commands via `CommandGateway` from
  outside any message handler (REST controller, scheduler, CLI runner,
  service entry point — top-of-chain dispatch with no active
  `ProcessingContext`) from Axon Framework 4 to Axon Framework 5.
  Updates the `CommandGateway` import to its AF5 location, rewrites
  blocking `sendAndWait(...)` and AF4 `send(...)` calls to AF5's
  async `CommandResult` API, and adapts the surrounding method's
  return type (e.g. Spring controller `CompletableFuture<R>`) so the
  framework consumes the future. Atomic — exactly one class per run.
  Handler-resident dispatch (`@EventHandler` / `@CommandHandler` /
  `@QueryHandler` / `@MessageHandlerInterceptor`) is **out of scope** —
  use `axon4-to-axon5-eventprocessor` (and its siblings) for those.
---

# AF4 → AF5: `CommandGateway` caller (top-of-chain, non-handler)

Atomic migration of a single class that uses `CommandGateway` from
outside any message handler — typically a Spring `@RestController`,
`@Scheduled` runner, `CommandLineRunner`, or other input adapter / service
that is the **first cause** of a command and therefore has no active
`ProcessingContext`. The decision rule from the `CommandDispatcher`
javadoc applies: gateway for top-of-chain entry points, dispatcher inside
another handler. This skill handles only the gateway side.

> **Keep this skill generic.** It runs across many projects. Describe the
> source/target purely in framework terms (annotations, class shapes,
> method signatures) — never in terms of a specific project's package,
> module, or file layout. Project-specific knowledge lives in
> `references/examples/` only.

## What this migrates

- **From:** a class that:
  - imports `org.axonframework.commandhandling.gateway.CommandGateway`
    (AF4 location), AND
  - holds it as a class-level dependency (typically constructor-
    injected, sometimes field-injected), AND
  - calls `commandGateway.send(...)` and/or `commandGateway.sendAndWait(...)`,
    AND
  - is **not** itself a message-handling component — i.e. it has **no**
    methods annotated with `@EventHandler`, `@CommandHandler`,
    `@QueryHandler`, `@MessageHandlerInterceptor`, `@SagaEventHandler`,
    or any `@MessageHandler` meta-annotation.
- **To:** the same class, with:
  - the `CommandGateway` import switched to the AF5 location
    (`org.axonframework.messaging.commandhandling.gateway.CommandGateway`),
  - `sendAndWait(...)` calls converted to AF5 equivalents (still
    available; see step 3) — and **preferably** rewritten to async
    `send(...)` returning a future that the surrounding method exposes
    to its caller (Spring async controller, scheduler completion stage,
    etc.) so the request thread is not blocked,
  - AF4 `send(cmd, metadata)` (which used to return `CompletableFuture<R>`)
    rewritten through AF5's `CommandResult` shape — see step 3 for the
    exact idioms,
  - the surrounding method's return type adapted so the dispatch result
    flows out unblocked when feasible (`CompletableFuture<Void>` /
    `CompletableFuture<R>` for Spring MVC).
- **Scope per run:** exactly one class (see "Selection rule").

## Selection rule

If the user names a target (class, file path), use it. Otherwise: pick the
**first** candidate in lexical order by file path among classes that match
the "From" shape above. Never migrate more than one per run.

A class is **not** a candidate for this skill if it carries any of the
message-handler annotations listed above — those must be migrated by
`axon4-to-axon5-eventprocessor` (or the dedicated command/query/saga
skill, when added). When a class is mixed (some methods are handlers,
some are top-of-chain dispatchers — e.g. a saga-like reactor with a
public REST entry point), prefer running the handler skill first; this
skill then only touches the non-handler methods on a follow-up pass.

## Procedure

1. **Locate the candidate.** If no target was named, run a deterministic
   search for classes that still use the AF4 gateway import and are not
   message handlers:
   ```bash
   # Files that import the AF4 CommandGateway location.
   grep -rln --include='*.java' \
     'org.axonframework.commandhandling.gateway.CommandGateway' \
     <source roots>

   # From that list, exclude files that carry handler annotations.
   # The remaining ones are this skill's candidates.
   grep -L \
     -e '@EventHandler' \
     -e '@CommandHandler' \
     -e '@QueryHandler' \
     -e '@MessageHandlerInterceptor' \
     -e '@SagaEventHandler' \
     <files-from-previous-step>
   ```
   Pick the first remaining file (lexical order).

2. **Read the canonical migration-path doc** before transforming
   anything: the import-and-package-changes section of
   `docs/reference-guide/modules/migration/pages/paths/index.adoc`, plus
   the gateway-vs-dispatcher decision rule in
   `paths/projectors-event-processors.adoc`. Local excerpts in
   `references/migration-paths.md`.

3. **Apply the transformation instructions** below. They are this skill's
   LLM-specific edits — narrower and more prescriptive than the doc, and
   they grow over time as `reflect` folds in lessons from real runs.

4. **Show the diff** and summarize what changed (import, call-site
   shape, return-type adaptation, anything you flagged as out-of-scope).

5. **Stop and ask the human to verify.** Do **not** rely on `mvn compile`
   passing — peer constructs (helper classes that still return AF4
   `MetaData`, downstream handlers still on AF4 imports) are typically
   on the old API and the project is expected to be broken
   mid-migration. The human decides acceptable / not-acceptable.

> **Fallback only:** if the migration-path doc and the instructions in
> this skill leave a real gap, inspect the AF source at the paths in
> `references/source-access.md`. Treat that as a signal to run `reflect`
> afterwards so the missing knowledge folds back into the transformation
> instructions and the fallback isn't needed next time.

## Transformation instructions

### 1. FQN cheat sheet

| Element                         | AF4 FQN | AF5 FQN |
|---------------------------------|---------|---------|
| `CommandGateway` (interface)    | `org.axonframework.commandhandling.gateway.CommandGateway` | `org.axonframework.messaging.commandhandling.gateway.CommandGateway` |
| `CommandResult` (new in AF5)    | n/a     | `org.axonframework.messaging.commandhandling.gateway.CommandResult` |
| `Metadata`                      | `org.axonframework.messaging.MetaData` | `org.axonframework.messaging.core.Metadata` |
| `CommandExecutionException`     | `org.axonframework.commandhandling.CommandExecutionException` | `org.axonframework.messaging.commandhandling.CommandExecutionException` |

### 2. Update the `CommandGateway` import

Single-line change: switch the import to the AF5 FQN. The constructor /
field type and the variable name stay the same — `CommandGateway` is
still the right interface for top-of-chain callers in AF5. Do **not**
replace it with `CommandDispatcher`; that's reserved for in-handler use.

### 3. Rewrite call sites — AF4 → AF5 shape table

AF5's `CommandGateway` returns a `CommandResult` (not a
`CompletableFuture`) from the `send(...)` family that takes metadata.
That breaks any AF4 line of the shape
`CompletableFuture<R> f = commandGateway.send(cmd, metadata);` —
the assignment compiles in AF4 but not in AF5. Use the AF5 conversions
from the table:

| AF4 call | AF5 replacement | Returns |
|---|---|---|
| `commandGateway.send(cmd)` | `commandGateway.send(cmd, Object.class)` | `CompletableFuture<Object>` |
| `commandGateway.send(cmd, metadata)` (returned `CompletableFuture<Void>` in AF4) | `commandGateway.send(cmd, metadata).resultAs(Void.class)` | `CompletableFuture<Void>` |
| `commandGateway.send(cmd)` returning typed `CompletableFuture<R>` (AF4) | `commandGateway.send(cmd, R.class)` | `CompletableFuture<R>` |
| `commandGateway.send(cmd, callback)` (AF4 callback overload) | `commandGateway.send(cmd).onSuccess(...).onError(...)` | `CommandResult` (chained) |
| `commandGateway.sendAndWait(cmd)` | `commandGateway.sendAndWait(cmd)` (still exists) — **prefer** rewriting to the async path above when the surrounding method can return `CompletableFuture` | `Object` (blocking) |
| `commandGateway.sendAndWait(cmd, R.class)` | `commandGateway.sendAndWait(cmd, R.class)` (still exists) — same preference | `R` (blocking) |
| `commandGateway.sendAndWait(cmd, timeout, unit)` | `commandGateway.sendAndWait(cmd)` *(no built-in timeout overload in AF5; if you need one, wrap as `commandGateway.send(cmd, R.class).orTimeout(timeout, unit).join()`)* | `Object` (blocking) |

Notes:
- `CommandResult` is **not** a `CompletableFuture`. Anywhere AF4 code
  assigned `commandGateway.send(...)` to a `CompletableFuture` variable
  or returned it from a `CompletableFuture<R>` method, you need
  `.resultAs(R.class)` or `.getResultMessage().thenApply(m -> ...)` to
  obtain a real future.
- `commandGateway.send(cmd, R.class)` (no metadata) is a
  convenience overload that returns `CompletableFuture<R>` directly —
  use it when no metadata is involved.
- Prefer `.resultAs(R.class)` over `.getResultMessage().thenApply(...)`
  for plain type extraction; reach for the latter only when you also
  need the `Message` (metadata, identifier).
- If the AF4 site used a `CommandCallback`, AF5's idiomatic equivalent
  is `commandResult.onSuccess(...).onError(...)`. Keep behavior
  identical; do not silently change error semantics.

### 4. Adapt the surrounding method's return type

Top-of-chain callers usually have one of three shapes. Pick the rewrite
that matches:

- **Spring MVC controller** (`@RestController` / `@Controller` method).
  Spring serves `CompletableFuture<R>` async out of the box. **Prefer**:
  return `CompletableFuture<R>` from the handler method and pipe the
  dispatch result through `.resultAs(R.class)` (or `Void.class`). Keep
  the response body type as it was.

  ```java
  // AF5 — controller method returning CompletableFuture<Void>
  @PutMapping("/things/{id}")
  CompletableFuture<Void> putThings(...) {
      var command = ...;
      return commandGateway.send(command, MyMetadata.with(...))
                           .resultAs(Void.class);
  }
  ```

- **Scheduler / runner returning `void`**. AF5's `sendAndWait(...)`
  still exists; keep it if the surrounding method must run to
  completion before returning (cron jobs, startup runners, integration
  tests). Just update the import.

- **Reactive return type** (Mono / Flux / similar). Bridge the AF5
  `CompletableFuture` from `.resultAs(R.class)` into the reactive type
  the method already returns (`Mono.fromFuture(...)`, etc.). The shape
  of the bridge is project-specific; do it only if the caller already
  uses reactive types.

If the AF4 method already returned `CompletableFuture<R>` and used
`commandGateway.send(...)` directly, use the cheat-sheet rewrite from
step 3; the method signature itself usually doesn't change, only the
expression on the `return` line.

### 5. Out-of-scope: the metadata helper

A common project pattern is a helper such as `XxxMetaData.with(...)`
(or a wrapper / mapper) that returns the framework's metadata type. In
AF4 that helper returned `org.axonframework.messaging.MetaData`; in AF5
the corresponding type is `org.axonframework.messaging.core.Metadata`.
**This skill does not migrate that helper** — it is shared across many
call sites and lives in another package. If the migrated call site
fails to compile because the helper still returns AF4 `MetaData`, flag
it for the user as a follow-up rather than editing the helper here.
The skill that migrates the helper is a separate concern (TBD).

### 6. Verify nothing else needed migrating

After the rewrite, glance over the file for:

- Stale imports — remove any remaining `org.axonframework.commandhandling.*`
  AF4 imports that are no longer referenced.
- Any try/catch on `CommandExecutionException` — its FQN moved
  (`org.axonframework.commandhandling` →
  `org.axonframework.messaging.commandhandling`). Update if present.
- Any callback class implementing AF4's `CommandCallback` SPI directly
  — that SPI was removed; flag for the user, do not silently change.

Do not introduce abstractions or refactors that aren't required by the
AF5 API change.

## Reference docs

The migration-path .adoc(s) this skill is grounded in:

- `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5/docs/reference-guide/modules/migration/pages/paths/index.adoc` (import & package changes table)
- `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5/docs/reference-guide/modules/migration/pages/paths/projectors-event-processors.adoc` (gateway-vs-dispatcher decision rule, indirectly)

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

- `references/examples/01-heroes-builddwelling-restcontroller.md` —
  Spring `@RestController` with one `PUT` endpoint, AF4 `send(cmd, metadata)`
  returning `CompletableFuture<Void>` rewritten through `.resultAs(Void.class)`.

## Variants

- **Pure async controller** — `CompletableFuture<R>` return; just change
  the import and append `.resultAs(R.class)` to the dispatch line.
- **Blocking entry point** (`CommandLineRunner`, `@Scheduled` `void`
  method, integration test): `sendAndWait(...)` still exists in AF5;
  update the import only.
- **Mixed class** — same class has handler methods *and* a non-handler
  dispatch method. Run `axon4-to-axon5-eventprocessor` first; this
  skill then only touches the non-handler dispatch on a follow-up pass.
- **Custom callback (AF4 `CommandCallback`)** — flag for the user;
  AF5 replaces the SPI with `CommandResult.onSuccess(...)/onError(...)`
  and the rewrite is callsite-specific.

## Notes for the human

- This skill is iteratively improved via the `reflect` skill — after
  every correction, reflect to fold the lesson back into the
  instructions.
- If you change something manually, mention it briefly so reflect can
  capture the *why*.
