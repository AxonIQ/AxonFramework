---
name: axon4-to-axon5-querygateway
description: >
  Migrate ONE class that dispatches queries via `QueryGateway` from
  outside any message handler (REST controller, scheduler, CLI runner,
  service entry point — top-of-chain dispatch with no active
  `ProcessingContext`) from Axon Framework 4 to Axon Framework 5.
  Updates the `QueryGateway` import to its AF5 location, removes the
  `ResponseType` wrapper (eliminated in AF5) and rewrites
  `query(...)` / `subscriptionQuery(...)` call sites to use plain
  `Class<R>` overloads, splits multi-response queries into
  `queryMany(...)`, and adapts the surrounding method's return type
  (e.g. Spring controller `CompletableFuture<R>`). Atomic — exactly
  one class per run. Sibling skill of `axon4-to-axon5-commandgateway`
  for the gateway side; in-handler dispatch is out of scope.
---

# AF4 → AF5: `QueryGateway` caller (top-of-chain, non-handler)

Atomic migration of a single class that uses `QueryGateway` from
outside any message handler — typically a Spring `@RestController`,
service entry point, or scheduler that is the **first cause** of a
query and therefore has no active `ProcessingContext`.

> **Keep this skill generic.** It runs across many projects. Describe
> the source/target purely in framework terms (annotations, class
> shapes, method signatures) — never in terms of a specific project's
> package, module, or file layout. Project-specific knowledge lives in
> `references/examples/` only.

> **Lean by design.** This skill ships with one real-world example
> (`GetDwellingByIdRestApi` — the simplest possible case: import-only
> change). The rules around `ResponseType` removal, `queryMany`,
> scatter-gather removal, and the new subscription-query split are
> drawn from the AF5 API-changes doc, not from concrete examples yet.
> They will get sharper as `reflect` folds in lessons from real runs.

## What this migrates

- **From:** a class that:
  - imports `org.axonframework.queryhandling.QueryGateway` (AF4
    location), AND
  - holds it as a class-level dependency (typically constructor-
    injected), AND
  - calls `queryGateway.query(...)`, `queryGateway.subscriptionQuery(...)`,
    `queryGateway.streamingQuery(...)`, and/or `queryGateway.scatterGather(...)`,
    AND
  - is **not** itself a message-handling component — i.e. it has **no**
    methods annotated with `@EventHandler`, `@CommandHandler`,
    `@QueryHandler`, `@MessageHandlerInterceptor`, `@SagaEventHandler`,
    or any `@MessageHandler` meta-annotation.
- **To:** the same class, with:
  - the `QueryGateway` import switched to the AF5 location
    (`org.axonframework.messaging.queryhandling.gateway.QueryGateway`),
  - any `ResponseType` / `ResponseTypes.instanceOf(...)` / `multipleInstancesOf(...)` / `optionalInstanceOf(...)`
    wrapper unwrapped — AF5 takes plain `Class<R>` instead,
  - multi-response queries (`multipleInstancesOf`) rewritten to
    `queryMany(...)` returning `CompletableFuture<List<R>>`,
  - any `scatterGather(...)` flagged for the user — **removed** in AF5
    with no drop-in replacement,
  - subscription-query call sites adapted to the AF5 API (drop
    `ResponseType` wrappers; for the dual-type form, the gateway
    returns a `SubscriptionQueryResponse<I, U>` whose
    `initialResult()` is a `Flux` rather than a `Mono`).
- **Scope per run:** exactly one class (see "Selection rule").

## Selection rule

If the user names a target (class, file path), use it. Otherwise: pick
the **first** candidate in lexical order by file path among classes
that match the "From" shape above. Never migrate more than one per run.

A class is **not** a candidate for this skill if it carries any of the
message-handler annotations listed above — those must be migrated by
`axon4-to-axon5-eventprocessor` (or the dedicated handler skill, when
added). When a class is mixed (some methods are handlers, some are
top-of-chain dispatchers), prefer running the handler skill first;
this skill then only touches the non-handler methods on a follow-up
pass.

## Procedure

1. **Locate the candidate.** If no target was named, run a
   deterministic search for classes that still use the AF4
   `QueryGateway` import and are not message handlers:
   ```bash
   # Files that import the AF4 QueryGateway location.
   grep -rln --include='*.java' \
     'org.axonframework.queryhandling.QueryGateway' \
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
   `docs/reference-guide/modules/migration/pages/paths/index.adoc`,
   plus the query-specific changes in
   `axon-5/api-changes/09-queries-and-minor-changes.md`. Local
   excerpts in `references/migration-paths.md`.

3. **Apply the transformation instructions** below. They are this
   skill's LLM-specific edits — narrower and more prescriptive than
   the doc, and they grow over time as `reflect` folds in lessons from
   real runs.

4. **Show the diff** and summarize what changed (import,
   `ResponseType` removal, `query` vs `queryMany` split, subscription-
   query shape, anything you flagged as out-of-scope).

5. **Stop and ask the human to verify.** Do **not** rely on `mvn
   compile` passing — peer constructs (the `@QueryHandler` side, the
   query message type, downstream config) are typically still on the
   old API mid-migration. The human decides acceptable / not-
   acceptable.

> **Fallback only:** if the migration-path doc and the instructions in
> this skill leave a real gap, inspect the AF source at the paths in
> `references/source-access.md`. Treat that as a signal to run
> `reflect` afterwards so the missing knowledge folds back into the
> transformation instructions and the fallback isn't needed next time.

## Pre-flight: recipe-pre-migrated no-op close

Before any rewrite, check whether the target file is already on AF5
shape. The OpenRewrite recipe (`UpgradeAxon4ToAxon5` /
`UpgradeAxon4ToAxoniq5`) covers the `QueryGateway` import move and
the simple `query(q, R.class)` form mechanically. When the
orchestrator hands you a target after phase 1 has run, the file is
often already AF5-shaped at the dispatch site — the per-construct
skill's only contribution is verifying nothing else (`ResponseType`
wrappers, bare `.get()` / `.join()`, scatter-gather, subscription
shape) needs adapting.

Pre-flight checklist:

1. The class imports `QueryGateway` from
   `org.axonframework.messaging.queryhandling.gateway` (AF5 FQN),
   not `org.axonframework.queryhandling` (AF4 FQN).
2. No imports of `org.axonframework.messaging.responsetypes.*`
   (`ResponseType` / `ResponseTypes` SPI removed in AF5).
3. No call sites wrapping the response in `instanceOf(...)`,
   `multipleInstancesOf(...)`, `optionalInstanceOf(...)`, or
   referencing `ResponseTypes` statically.
4. No call sites using `queryGateway.scatterGather(...)` (removed in
   AF5; if present, this is **not** a no-op — flag for the user).
5. Subscription-query call sites — if any — already pass plain
   `Class<I>` / `Class<U>` instead of `ResponseType` wrappers, and
   any caller that expected an AF4 `Mono<I>` initial result has
   already adapted to the AF5 `Flux<I>` shape.
6. **No bare blocking calls without a timeout.** If the surrounding
   code is a synchronous boundary (CLI runner, test, framework
   callback that requires a synchronous return — see the
   "Synchronous framework callback" variant below), `.get()` /
   `.join()` without `.orTimeout(...)` is a real fix the skill
   should apply. This is *not* a no-op condition — it is the most
   common diff this skill produces post-recipe.

If items 1–5 hold AND item 6 holds (no bare blocking call): the
file is recipe-pre-migrated and the skill closes as a **no-op**.
Verify scoped compilation, tell the orchestrator the class is done,
and stop.

If item 6 fails (any bare `.get()` / `.join()` without a timeout),
that's a real diff — apply the "Blocking caller" / "Synchronous
framework callback" rewrite from section 4 below, *then* close.

If items 1–5 fail in non-trivial ways (scatter-gather present,
subscription shape mismatch, custom `ResponseType` subclass), the
skill is not a no-op — apply the transformation instructions below.

## Transformation instructions

### 1. FQN cheat sheet

| Element                           | AF4 FQN | AF5 FQN |
|-----------------------------------|---------|---------|
| `QueryGateway` (interface)        | `org.axonframework.queryhandling.QueryGateway` | `org.axonframework.messaging.queryhandling.gateway.QueryGateway` |
| `ResponseType` / `ResponseTypes`  | `org.axonframework.messaging.responsetypes.ResponseType` / `ResponseTypes` | **removed** — pass `Class<R>` directly |
| `SubscriptionQueryResult` (AF4)   | `org.axonframework.queryhandling.SubscriptionQueryResult` | **split** — `SubscriptionQueryResponse<I, U>` (gateway, payloads) and `SubscriptionQueryResponseMessages` (bus, messages); FQN under `org.axonframework.messaging.queryhandling.*` |
| `Metadata`                        | `org.axonframework.messaging.MetaData` | `org.axonframework.messaging.core.Metadata` |

### 2. Update the `QueryGateway` import

Single-line change: switch the import to the AF5 FQN. The constructor
/ field type and the variable name stay the same — `QueryGateway` is
still the right interface for top-of-chain callers in AF5.

### 3. Rewrite call sites — AF4 → AF5 shape table

The biggest API-shape shift is the **removal of `ResponseType`**.
AF5's `QueryGateway` takes a plain `Class<R>` and exposes a separate
`queryMany(...)` for multi-response queries. The old smart "best-fit
matching" against `ResponseType` is gone.

| AF4 call | AF5 replacement | Returns |
|---|---|---|
| `queryGateway.query(q, R.class)` (already a Class overload) | `queryGateway.query(q, R.class)` — **import-only change** | `CompletableFuture<R>` |
| `queryGateway.query(q, instanceOf(R.class))` | `queryGateway.query(q, R.class)` — drop the wrapper | `CompletableFuture<R>` |
| `queryGateway.query(q, multipleInstancesOf(R.class))` | `queryGateway.queryMany(q, R.class)` — different method name | `CompletableFuture<List<R>>` |
| `queryGateway.query(q, optionalInstanceOf(R.class))` | `queryGateway.query(q, R.class)` — the future resolves to `null` if absent | `CompletableFuture<R>` (nullable) |
| `queryGateway.streamingQuery(q, R.class)` | `queryGateway.streamingQuery(q, R.class)` — **import-only change** | `Publisher<R>` |
| `queryGateway.scatterGather(...)` | **REMOVED** — flag for the user; no drop-in replacement | — |
| `queryGateway.subscriptionQuery(q, instanceOf(I.class), instanceOf(U.class))` | `queryGateway.subscriptionQuery(q, I.class, U.class)` — drop `ResponseType` wrappers | `SubscriptionQueryResponse<I, U>` (gateway flavour, payloads) |
| `queryGateway.subscriptionQuery(q, I.class, U.class)` (3-arg, simple `Class`) | `queryGateway.subscriptionQuery(q, I.class, U.class)` — same | `SubscriptionQueryResponse<I, U>` |

Notes:

- `ResponseType` and `ResponseTypes` are **gone** in AF5. Strip the
  wrapper everywhere and remove the corresponding import. The
  `Class<R>` form has always existed in AF4 too, so most simple call
  sites become an import-only diff.
- `query(...)` is **always single-response** in AF5. If the AF4 site
  used `multipleInstancesOf(...)`, switch to `queryMany(...)` — the
  return type changes from `CompletableFuture<List<R>>` to
  `CompletableFuture<List<R>>` (same shape, different method).
- `scatterGather(...)` was removed from both `QueryBus` and
  `QueryGateway` due to limited use. There is no drop-in. Flag it for
  the user — the rewrite is callsite-specific (e.g. dispatch the
  query through alternative routing, or switch to per-target queries
  fanned out manually).
- Subscription queries have a deeper redesign: the AF4
  `SubscriptionQueryResult` was split into two types in AF5
  (`SubscriptionQueryResponseMessages` for the bus, with `Message`s;
  `SubscriptionQueryResponse` for the gateway, with payloads). The
  gateway flavour is what callers of this skill see; its
  `initialResult()` returns a `Flux<I>` rather than the AF4 `Mono<I>`,
  because AF5 supports 0/1/N initial results uniformly. If the AF4
  caller assumed a single initial result, fold the `Flux` back to
  `Mono` (`.next()` / `.singleOrEmpty()`) so behaviour stays
  compatible — flag it for the user when in doubt.

### 4. Adapt the surrounding method's return type

Top-of-chain callers usually have one of four shapes. Pick the
rewrite that matches:

- **Spring MVC controller** (`@RestController` / `@Controller`
  method). Spring serves `CompletableFuture<R>` async out of the box.
  Most simple migrations end up unchanged here — just keep the
  `CompletableFuture<R>` return and the rewritten dispatch line on the
  `return`. Example:

  ```java
  // AF5 — controller method returning CompletableFuture<MyView>
  @GetMapping("/things/{id}")
  CompletableFuture<MyView> getThing(@PathVariable String id) {
      var query = GetThingById.query(id);
      return queryGateway.query(query, MyView.class);
  }
  ```

- **Reactive return type** (`Mono` / `Flux` / similar). For
  `streamingQuery` / subscription queries, AF5 already returns a
  `Publisher<R>` / a `SubscriptionQueryResponse<I, U>` exposing
  `Flux`es; bridge as needed (`Mono.fromFuture(...)` for `query(...)`,
  `Flux.from(publisher)` for `streamingQuery(...)`). Bridge shape is
  project-specific.

- **Blocking caller** (CLI runner, integration test): AF5's
  `query(...)` already returns `CompletableFuture<R>`. Block at the
  call site with `future.orTimeout(...).join()` — never bare
  `.join()` / `.get()`.

- **Synchronous framework callback** — an MCP resource handler, a
  Kafka `@KafkaListener`, a JMS `@JmsListener`, a Camel route step,
  or any other framework integration whose **callback signature is
  synchronous** and therefore the surrounding method must return a
  concrete value (not a `CompletableFuture`). Same rewrite as the
  blocking caller — bridge with
  `future.orTimeout(<duration>, <unit>).join()` — but motivated by
  the framework's signature, not by being "blocking code by
  choice".

  ```java
  // AF5 — synchronous framework callback (e.g. MCP resource handler)
  (exchange, request) -> {
      try {
          var query = GetAllDwellings.query(extractGameId(request.uri()));
          var result = queryGateway.query(query, GetAllDwellings.Result.class)
                                   .orTimeout(30, TimeUnit.SECONDS)
                                   .join();
          return new McpSchema.ReadResourceResult(/* ... format result ... */);
      } catch (Exception e) {
          // CompletionException from .join() is unchecked — the existing
          // catch (Exception) still matches; callers reading e.getMessage()
          // see the underlying cause's message.
          return errorResult(e);
      }
  }
  ```

  Pick the timeout consciously. 30 seconds matches the project-wide
  default in `.claude/rules/completablefuture-blocking.md`; pick a
  shorter value when the surrounding framework has its own request
  budget. Add `import java.util.concurrent.TimeUnit;` if not already
  present. This pattern also satisfies item 6 of the pre-flight
  checklist — without the timeout, the file is **not** a no-op,
  even when the import was already AF5.

  See `references/examples/02-heroes-getalldwellings-mcp-sync-callback.md`
  for a real example.

### 5. Out-of-scope

- The query message itself (the `Q` payload type, including any
  `@QueryName`/`@Query` annotations or argument-order refactors). The
  skill preserves the call's argument shape; if the message type was
  also refactored, that's a separate concern.
- The `@QueryHandler` side. Use the dedicated query-handler skill
  (TBD) — when this skill changes the gateway side, the handler side
  may temporarily not compile, which is the expected mid-migration
  state.
- Custom `ResponseType` subclasses. AF5 dropped the SPI entirely; if
  the project defined its own `ResponseType`, flag it for the user —
  the rewrite needs a per-callsite plan.

### 6. Verify nothing else needed migrating

After the rewrite, glance over the file for:

- Stale imports — remove any remaining
  `org.axonframework.queryhandling.*` AF4 imports and any
  `org.axonframework.messaging.responsetypes.*` imports that are no
  longer referenced.
- `ResponseTypes` static-import lines (`import static
  org.axonframework.messaging.responsetypes.ResponseTypes.*;`) — drop
  them when no longer referenced.
- Any try/catch on AF4 query-handling exceptions whose FQN moved.

Do not introduce abstractions or refactors that aren't required by
the AF5 API change.

## Reference docs

The migration-path .adoc(s) and API-changes notes this skill is
grounded in:

- `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5/docs/reference-guide/modules/migration/pages/paths/index.adoc` (import & package changes table)
- `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5/axon-5/api-changes/09-queries-and-minor-changes.md` (`ResponseType` removal, `queryMany`, scatter-gather removal, subscription-query redesign)

Key excerpts kept locally in `references/migration-paths.md`.

## Source references (fallback)

`references/source-access.md` records where AF4 / AF5 sources resolve
on this machine. Used only when the migration-path doc and the
transformation instructions above are insufficient.

## Examples

Each file in `references/examples/` is one real migration from one
project, preserved verbatim. **All project-specific knowledge lives
here** — never in the procedure or transformation instructions above.
New examples are added, not merged: if a new project shows a
different valid pattern, drop in
`references/examples/<NN>-<project>-<short-desc>.md` rather than
editing the existing ones.

- `references/examples/01-heroes-getdwellingbyid-restcontroller.md` —
  Spring `@RestController` with one `GET` endpoint and a
  `query(query, R.class)` call. Simplest possible case: import-only
  change, body untouched.
- `references/examples/02-heroes-getalldwellings-mcp-sync-callback.md`
  — Spring `@Component` exposing a Model Context Protocol (MCP)
  resource via a synchronous `(exchange, request) -> McpSchema.ReadResourceResult`
  lambda. The recipe already migrated the import; the diff is the
  bridge from `CompletableFuture<R>` to a synchronous return:
  bare `.get()` → `.orTimeout(30, TimeUnit.SECONDS).join()`. Documents
  the "Synchronous framework callback" variant.

## Variants

- **Pure async controller** — `CompletableFuture<R>` return; just
  change the import and (if present) drop the `ResponseType` wrapper.
- **Multi-response migration** — AF4 `query(q, multipleInstancesOf(R.class))`
  → AF5 `queryMany(q, R.class)`. Method name changes, return type
  shape stays `CompletableFuture<List<R>>`.
- **Subscription query** — AF4 wrappers dropped; gateway returns
  `SubscriptionQueryResponse<I, U>`; `initialResult()` is now `Flux`
  instead of `Mono`. Callers expecting "exactly one initial result"
  must collapse with `.next()` / `.singleOrEmpty()`.
- **Scatter-gather caller** — flag for the user; AF5 has **no
  scatter-gather support**. The rewrite is callsite-specific.
- **Custom `ResponseType` subclass** — flag for the user; SPI removed
  in AF5.
- **Synchronous framework callback** (MCP resource handler, Kafka
  `@KafkaListener`, JMS `@JmsListener`, Camel route step). The
  surrounding callback signature requires a synchronous return, so
  the `CompletableFuture<R>` from `query(...)` must be unwrapped at
  the call site. Always with a timeout —
  `.orTimeout(<duration>, <unit>).join()` — never bare `.get()` /
  `.join()`. This is often the **only** diff post-recipe (the
  import was already moved); see the pre-flight checklist item 6.
- **Recipe-pre-migrated no-op** — every item in the pre-flight
  checklist passes. Close the unit of work without a diff. Common
  outcome when the orchestrator sequences this skill after phase 1.

## Notes for the human

- This skill is iteratively improved via the `reflect` skill — after
  every correction, reflect to fold the lesson back into the
  instructions.
- If you change something manually (e.g. you decide on a particular
  bridge for subscription-query callers), mention it briefly so
  reflect can capture the *why*.
- Sibling skill: `axon4-to-axon5-commandgateway` covers the same
  shape on the command side. Both follow the AF5 rule "gateway for
  top-of-chain, dispatcher inside another handler"; both leave
  in-handler dispatch to `axon4-to-axon5-eventprocessor` and friends.
