---
name: migration-skill-creator
description: >
  Explicit-invoke only. Run **only** when the user explicitly invokes
  `/migration-skill-creator` or asks for this skill by name. Do not
  auto-trigger from inferred intent — phrases like "create a migration
  skill" or "let's migrate X to Y" are not enough on their own. When
  explicitly invoked, scaffolds a new atomic Axon Framework migration
  skill (one that transforms a single instance of a specific construct at
  a time) under `migration/.claude/skills/`.
---

# Migration Skill Creator

> **Explicit invocation only.** This skill is a scaffolding tool for the
> human author of migration skills. The harness must not auto-trigger it
> based on conversational hints. Only run when the user explicitly invokes
> it by name or via `/migration-skill-creator`.

Generates an atomic migration skill that transforms **ONE** instance of a
specific construct at a time. A future orchestrator skill will dispatch to
many of these; do not bundle multi-target transformations here.

The generated skill must be **project-agnostic**. It will run against many
codebases — its procedure and transformation instructions describe the
source/target construct in framework terms only. Project-specific patterns
accumulate as files under `references/examples/` of each migration skill,
never inside the procedure itself.

Output location (in the project being migrated):
`migration/.claude/skills/<skill-name>/`

## Process

### 1. Capture intent

Establish, and confirm with the user:

- **Skill name** in `<source>-to-<target>:<scope>` form, lowercased and
  hyphenated. Example: `axon4-to-axon5:aggregate-to-eventsourcedentity`.
  Use the colon form in the YAML `name:` field; use a hyphen in the
  directory name (filesystems differ on `:`).
- **Source construct** — what identifies a candidate? Annotations, class
  shape, file location, configuration key, etc. Be specific: e.g. *classes
  annotated with `@Aggregate` that contain at least one
  `@EventSourcingHandler` method*.
- **Target construct** — the migrated form, with a concrete API reference.
- **Selection rule when multiple candidates exist** — first found, by class
  name, by file path. Atomic means atomic: pick exactly one per run.

### 2. Pick the relevant migration paths

Browse `docs/reference-guide/modules/migration/pages/paths/` (in this repo,
or the project's documented equivalent) and pick the 1–3 `.adoc` files that
document the transformation. Reference them by path inside the new skill so
the migrator agent reads canonical guidance instead of inventing it.

If the docs don't yet cover the transformation, surface that — the user may
want to write a migration-path doc first using the `migration-path` skill.

### 3. Set up source-code access (as a fallback)

The primary instruction surface for every migration skill is the
migration-path doc plus the skill's own LLM-specific transformation
instructions. AF / AxonIQ source code is a **fallback** for cases where
those two leave a real gap.

Set up the fallback now so it's ready when needed:

1. **Local clone** — e.g. `~/GitRepos/AxonFramework/AxonFramework5`,
   `~/GitRepos/AxonIQ/...`. If present, record the absolute path.
2. **Maven sources jar** — download and unpack:
   ```bash
   mvn dependency:get \
     -Dartifact=org.axonframework:axon-messaging:4.10.0:jar:sources \
     -Ddest=/tmp/af-sources/
   unzip -o /tmp/af-sources/axon-messaging-4.10.0-sources.jar \
     -d /tmp/af-sources/axon-messaging-4.10.0
   ```

Record whatever resolves into the skill's `references/source-access.md`.
Mark it as "fallback only" so the migrator agent doesn't reach for it by
default.

If nothing resolves, that's fine for now — it just means the fallback is
unavailable. Note it and move on.

### 4. Draft the skill

Use `references/migration-skill-template.md` as the starting point. Fill in
every placeholder and keep the SKILL.md body short — push detail into
`references/`.

**Stay generic.** Procedure and transformation instructions describe the
source/target construct in framework terms only — annotations, class
shapes, API signatures. No project paths, no project names, no module
layouts. If a quirk needs to influence behavior, route on an observable
shape (annotation present/absent, base class, payload type), never on
project identity. Project-specific knowledge is captured by adding files
under `references/examples/`, not by editing the procedure.

Every migration skill has two grounding sources, and the procedure must
make both explicit:

1. **The migration-path doc** — required. Every run reads it before
   transforming anything.
2. **The skill's own transformation instructions** — required, and this is
   where the iterative work lives. Start lean (even one or two concrete
   steps is fine) and let it grow through `reflect`. These are the
   LLM-specific edits and gotchas that the migration-path doc doesn't
   cover on its own — written generically so they hold across projects.

The procedure section must also:

- State the **selection rule** explicitly (which single candidate gets
  migrated this run).
- End with a **diff summary + human review** step. It must not declare
  success based on `mvn compile`: during an incremental migration the
  project is usually broken until peer constructs catch up. The human is
  the gate.

Source inspection (step 3 above) appears in the procedure only as a
fallback — to be used when the migration-path doc and the skill's
instructions leave a gap. When that happens, the next action is `reflect`
so the gap closes for the next run.

### 5. Iterate against a sample project

Test the new skill on a real candidate in a sample repository. After every
correction (manual edit, "no, do it like this", regenerated file), invoke
the `reflect` skill on the new migration skill so the lesson folds back
into the instructions before the next iteration.

The migration skill is the artifact that needs to get better; this creator
skill is just scaffolding around that loop.

### 6. Be additive across projects

When the same skill runs on a second project and meets a different but
valid pattern — different annotation style, different package layout,
different testing convention — **append** it to the skill's
`references/examples/` directory and to a "Variants" section in SKILL.md.
Do not replace what's there. The skill should handle the union of patterns
we encounter, not the latest one only.

If two patterns genuinely conflict, document both and let the skill route
on the input shape (e.g. presence/absence of an annotation, package
prefix).

### 7. Keep the human in the loop

The user will frequently make manual changes mid-migration to drive things
forward. Treat those as feedback: at the next opportunity, run `reflect`
on the migration skill so it learns the pattern instead of fighting it.

## Output structure

```
migration/.claude/skills/<source-to-target>-<scope>/
├── SKILL.md
└── references/
    ├── examples/
    │   ├── 01-<project>-<short-desc>.md
    │   └── 02-<project>-<short-desc>.md
    ├── source-access.md     # how AF/AxonIQ sources resolve on this machine
    └── migration-paths.md   # quoted/excerpted relevant .adoc snippets
```

The directory name mirrors the skill name with `:` replaced by `-`.

## Reference

- `references/migration-skill-template.md` — the SKILL.md skeleton to copy
  for each generated migration skill. Read it before drafting.
