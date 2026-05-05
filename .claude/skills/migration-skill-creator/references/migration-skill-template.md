# Migration Skill Template

Copy this skeleton into `migration/.claude/skills/<skill-dir>/SKILL.md` and
fill in every `<...>` placeholder. Strip sections that don't apply, but be
deliberate about it — most migrations need all of them.

---

```markdown
---
name: <source>-to-<target>:<scope>
description: >
  Migrate ONE <source-construct> to <target-construct> in the current project.
  Use when the user says "<trigger phrase 1>", "<trigger phrase 2>", or
  describes a <scope> migration step. This skill is atomic — it changes
  exactly one candidate per run; an orchestrator skill will dispatch
  multiple invocations.
---

# <Source> → <Target>: <Scope>

Atomic migration of a single <source-construct> to <target-construct>.

> **Keep this skill generic.** It runs across many projects. Describe the
> source/target purely in framework terms (annotations, class shapes, API
> signatures) — never in terms of a specific project's package, module, or
> file layout. Project-specific knowledge lives in `references/examples/`
> only, where each file documents one real run from one project. If a
> project-specific quirk needs to influence the transformation, route on an
> observable shape (annotation present/absent, base class, payload type),
> not on project identity.

## What this migrates

- **From:** <concrete identification — annotations, class shape, package,
  configuration key>
- **To:** <concrete target form, with the canonical AF5/AxonIQ API>
- **Scope per run:** exactly one candidate (see "Selection rule" below)

## Selection rule

If the user names a target (class, file path), use it. Otherwise: pick the
**first** candidate in a deterministic, project-agnostic order — lexical
by file path is the default. Never migrate more than one per run.

## Procedure

1. **Locate the candidate.**
   <Concrete grep / glob / inspection commands.>

2. **Read the canonical migration-path doc** listed under "Reference docs"
   below. Every run grounds itself in that .adoc — do not paraphrase from
   memory.

3. **Follow the transformation instructions** in the section below. These
   are this skill's LLM-specific steps — the concrete edits, the gotchas,
   the project-shape-specific routing — that extend the migration-path doc.
   They are intentionally narrower and more prescriptive than the doc, and
   they grow over time as `reflect` folds in lessons from real runs.

4. **Show the diff** and summarize what changed.

5. **Stop and ask the human to verify.** Do **not** rely on `mvn compile`
   passing — peer constructs are typically still on the old API and the
   project is expected to be broken mid-migration. The human decides
   acceptable / not-acceptable.

> **Fallback only:** if the migration-path doc and the instructions in this
> skill leave a real gap (unknown API shape, ambiguous target form), inspect
> the AF source at the paths in `references/source-access.md`. Treat that as
> a signal to run `reflect` afterwards so the missing knowledge folds back
> into the transformation instructions and the fallback isn't needed next
> time.

## Transformation instructions

<This section is the heart of the skill. Start lean — even one or two
concrete steps is fine — and let it grow through iteration. After each
`reflect` pass, fold lessons in here, not into freeform prose elsewhere.>

<Stay generic. Steps must work on any project that uses the source
construct. Use code snippets ("before" / "after") for the smallest
meaningful unit. Cover only what the migration-path doc doesn't already
make obvious — the goal is *additional* LLM-specific guidance, not a
restatement of the doc. If a lesson learned on one project doesn't
generalize, capture it as an example under `references/examples/` instead
of hard-coding it here.>

## Reference docs

The migration-path .adoc(s) this skill is grounded in:

- `<absolute path to relevant .adoc 1>`
- `<absolute path to relevant .adoc 2>`

Key excerpts kept locally in `references/migration-paths.md`.

## Source references (fallback)

`references/source-access.md` records where AF4 / AF5 / AxonIQ sources
resolve on this machine. Used only when the migration-path doc and the
transformation instructions above are insufficient.

## Examples

Each file in `references/examples/` is one real migration from one
project, preserved verbatim. **All project-specific knowledge lives here**
— never in the procedure or transformation instructions above. New
examples are added, not merged: if a new project shows a different valid
pattern, drop in `references/examples/<NN>-<project>-<short-desc>.md`
rather than editing the existing ones.

## Variants

<Document patterns that diverge meaningfully in the wild: different
annotation styles, different test conventions, different surrounding
shapes. Route between them based on observable input shape (an annotation,
a base class, a payload type) — never on project name or path.>

## Notes for the human

- This skill is iteratively improved via the `reflect` skill — after every
  correction, reflect to fold the lesson back into the instructions.
- If you change something manually, mention it briefly so reflect can
  capture the *why*.
```

---

## Skeletons for the reference files

### `references/source-access.md`

```markdown
# Source access

How this machine resolves AF / AxonIQ source code for this skill.

- **Local clone:** <path or "n/a">
- **Sources jar:** <unpacked path or "n/a">

If both go missing, re-run the source-access step in `migration-skill-creator`
before invoking this skill.
```

### `references/migration-paths.md`

```markdown
# Relevant migration-path excerpts

Quoted from `docs/reference-guide/modules/migration/pages/paths/`:

## <path-1>.adoc
> <key excerpt>

## <path-2>.adoc
> <key excerpt>
```

### `references/examples/01-<project>-<short-desc>.md`

```markdown
# <project>: <one-line description>

**Before:**

```<lang>
<original code>
```

**After:**

```<lang>
<migrated code>
```

**Notes:** <anything project-specific worth remembering — package layout,
annotation style, test convention, manual tweaks the human had to make>.
```
