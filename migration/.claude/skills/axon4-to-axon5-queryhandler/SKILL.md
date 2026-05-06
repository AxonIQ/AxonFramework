---
name: axon4-to-axon5-queryhandler
description: >
  Migrate ONE class that exposes `@QueryHandler` methods from Axon
  Framework 4 to Axon Framework 5. The transformation is import-only
  in the simple case: `org.axonframework.queryhandling.QueryHandler`
  moves to `org.axonframework.messaging.queryhandling.annotation.QueryHandler`,
  and any sibling AF4 query-handling imports on the same class
  (`org.axonframework.queryhandling.*`) move under
  `org.axonframework.messaging.queryhandling.*`. Method bodies,
  parameter lists, return types, the `queryName` attribute, and
  Spring stereotypes (`@Component`, `@Service`) are preserved as-is.
  Atomic — exactly one class per run. Sibling skill of
  `axon4-to-axon5-querygateway` for the dispatch side; classes that
  *only* dispatch queries (no `@QueryHandler` methods) belong to that
  skill, not this one.
---

# AF4 → AF5: `@QueryHandler` class (annotation package move)

Atomic migration of a single class that handles queries via methods
annotated `@QueryHandler` — typically a Spring `@Component` /
`@Service` projection or read-model query handler.

> **Keep this skill generic.** It runs across many projects. Describe
> the source/target purely in framework terms (annotations, class
> shapes, method signatures) — never in terms of a specific project's
> package, module, or file layout. Project-specific knowledge lives in
> `references/examples/` only.

> **Lean by design.** This skill ships with one real-world example
> (`GetDwellingByIdQueryHandler` — the simplest possible case:
> import-only change). The transformation rules below cover the
> import move and the small surface around it (`queryName` attribute,
> sibling AF4 imports, Spring stereotypes). They will get sharper as
> `reflect` folds in lessons from real runs.

## What this migrates

- **From:** a class that:
  - has at least one method annotated with `@QueryHandler` from the
    AF4 location `org.axonframework.queryhandling.QueryHandler`, AND
  - is **not** also handling other message types via AF4 imports — if
    the same class carries AF4 `@CommandHandler` or `@EventHandler`,
    those are out of scope for this skill (run their dedicated skills
    first / instead).
- **To:** the same class, with:
  - the `@QueryHandler` import switched to the AF5 location
    `org.axonframework.messaging.queryhandling.annotation.QueryHandler`,
  - any other AF4 `org.axonframework.queryhandling.*` import on the
    same class moved under `org.axonframework.messaging.queryhandling.*`
    (matching the AF5 module reorganisation),
  - method bodies, parameter lists, return types, the `queryName`
    attribute on `@QueryHandler`, and Spring stereotypes
    (`@Component`, `@Service`) preserved as-is.
- **Scope per run:** exactly one class (see "Selection rule").

## Selection rule

If the user names a target (class, file path), use it. Otherwise:
pick the **first** candidate in lexical order by file path among
classes that match the "From" shape above. Never migrate more than
one per run.

A class is **not** a candidate for this skill if its only
query-handling import is already the AF5 one (already migrated), or
if it carries AF4 `@CommandHandler` / `@EventHandler` annotations as
well — let those be handled by their dedicated skills first.

## Procedure

1. **Locate the candidate.** If no target was named, run a
   deterministic search for classes that still use the AF4
   `@QueryHandler` import:
   ```bash
   grep -rln --include='*.java' \
     'org.axonframework.queryhandling.QueryHandler' \
     <source roots>
   ```
   Pick the first remaining file (lexical order).

2. **Read the canonical migration-path doc** before transforming
   anything: the import-and-package-changes section of
   `docs/reference-guide/modules/migration/pages/paths/index.adoc`.
   Local excerpts in `references/migration-paths.md`.

3. **Apply the transformation instructions** below. They are this
   skill's LLM-specific edits — narrower and more prescriptive than
   the doc, and they grow over time as `reflect` folds in lessons
   from real runs.

4. **Show the diff** and summarize what changed (the import move,
   any sibling AF4 query-handling imports rewritten, anything you
   flagged as out of scope).

5. **Stop and ask the human to verify.** Do **not** rely on `mvn
   compile` passing — peer constructs (the dispatch side, the query
   message type, downstream config) are typically still on the old
   API mid-migration. The human decides acceptable / not-acceptable.

> **Fallback only:** if the migration-path doc and the instructions
> in this skill leave a real gap, inspect the AF source at the paths
> in `references/source-access.md`. Treat that as a signal to run
> `reflect` afterwards so the missing knowledge folds back into the
> transformation instructions and the fallback isn't needed next
> time.

## Transformation instructions

### 1. FQN cheat sheet

| Element | AF4 FQN | AF5 FQN |
|---|---|---|
| `@QueryHandler` (annotation) | `org.axonframework.queryhandling.QueryHandler` | `org.axonframework.messaging.queryhandling.annotation.QueryHandler` |
| Query-handling core package | `org.axonframework.queryhandling` | `org.axonframework.messaging.queryhandling` |
| `Metadata` (annotation parameter) | `org.axonframework.messaging.MetaData` | `org.axonframework.messaging.core.Metadata` |

### 2. Update the `@QueryHandler` import

Single-line change: switch the import to the AF5 FQN. The annotation
name stays the same (`@QueryHandler`); only the package moves. The
`queryName` attribute, when present, is **preserved** — the AF5
annotation exposes the same `queryName()` member.

```java
// AF4
import org.axonframework.queryhandling.QueryHandler;

// AF5
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
```

### 3. Sweep for sibling AF4 query-handling imports

Inside a `@QueryHandler` class it's common to see other AF4 imports
from `org.axonframework.queryhandling.*` (e.g. exception types,
`QueryUpdateEmitter`). Rewrite each one to its AF5 equivalent under
`org.axonframework.messaging.queryhandling.*`. If a member of the
old package was renamed or removed in AF5 (e.g. `QueryUpdateEmitter`
shape changes), flag it for the user — that's outside the import-only
scope of this skill.

### 4. Preserve everything else

The following are **not** changed by this skill:

- Method bodies, parameter lists, return types, exception throws.
- Spring stereotypes on the class (`@Component`, `@Service`,
  `@RestController` if present, etc.).
- The query payload type and any of its annotations.
- Constructor injection of repositories or other dependencies.
- The `queryName` attribute on `@QueryHandler` (still supported in
  AF5 with the same name).

If you find yourself changing any of the above to satisfy AF5, it is
out of scope for this skill — surface it for the user instead of
silently rewriting.

### 5. Out-of-scope

- The dispatch side. Use `axon4-to-axon5-querygateway` for top-of-
  chain `QueryGateway` callers.
- Classes that also handle commands or events via AF4 annotations
  (`@CommandHandler`, `@EventHandler`). Run their dedicated skills
  first; this skill is not the right place to mix concerns.
- `QueryUpdateEmitter` API changes (subscription-query side). If the
  class injects `QueryUpdateEmitter` and uses it, flag the call sites
  for the user — the AF5 emitter shape is a separate migration.
- Any refactor of the query message type itself (renames, field
  reorderings). Preserve whatever shape the call has when it arrives.

### 6. Verify nothing else needed migrating

After the rewrite, glance over the file for:

- Stale imports — remove any remaining AF4
  `org.axonframework.queryhandling.*` imports that have been replaced.
- `Metadata` references — if the handler accepts a `Metadata`
  parameter, rewrite the import to
  `org.axonframework.messaging.core.Metadata`.
- Try/catch blocks on AF4 query-handling exceptions whose FQN moved.

Do not introduce abstractions or refactors that aren't required by
the AF5 API change.

## Reference docs

The migration-path .adoc(s) this skill is grounded in:

- `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5/docs/reference-guide/modules/migration/pages/paths/index.adoc`
  (import & package changes table — rows "Query Handler annotation"
  and "Query Handling Core")

Key excerpts kept locally in `references/migration-paths.md`.

## Source references (fallback)

`references/source-access.md` records where AF4 / AF5 sources
resolve on this machine. Used only when the migration-path doc and
the transformation instructions above are insufficient.

## Examples

Each file in `references/examples/` is one real migration from one
project, preserved verbatim. **All project-specific knowledge lives
here** — never in the procedure or transformation instructions
above. New examples are added, not merged: if a new project shows a
different valid pattern, drop in
`references/examples/<NN>-<project>-<short-desc>.md` rather than
editing the existing ones.

- `references/examples/01-heroes-getdwellingbyid-queryhandler.md` —
  Spring `@Component` with one `@QueryHandler` method that delegates
  to a read-model repository. Simplest possible case: import-only
  change, body untouched.

## Variants

- **Plain `@QueryHandler` projection** — Spring `@Component` /
  `@Service` with one or more `@QueryHandler` methods reading from a
  repository. Import-only change.
- **`@QueryHandler` with `queryName` attribute** —
  `@QueryHandler(queryName = "...")` is preserved as-is; the AF5
  annotation exposes the same attribute.
- **Multi-handler class** — a class with several `@QueryHandler`
  methods needs only one import change; all methods are migrated by
  the single import switch.
- **Mixed-message class** (also has `@CommandHandler` /
  `@EventHandler`) — out of scope; route to the dedicated skill for
  the other annotation first.

## Notes for the human

- This skill is iteratively improved via the `reflect` skill — after
  every correction, reflect to fold the lesson back into the
  instructions.
- If you change something manually (e.g. you decide to rewrite a
  `QueryUpdateEmitter` call site), mention it briefly so reflect can
  capture the *why*.
- Sibling skill: `axon4-to-axon5-querygateway` covers the dispatch
  side. The two skills are typically run together for a full query
  migration: dispatch site first, then handler site (or the other
  way round — both leave the project in a temporarily-broken state
  mid-migration, which is expected).
