---
name: migration-path
description: >
  Create a new Axon Framework 4→5 migration path documentation page.
  Use when the user says "create migration path", "migration path for", "document migration",
  "migration guide for", or any variation of creating migration documentation for a specific
  Axon Framework feature or API change.
---

# Migration Path Documentation Generator

Create a new Framework 4→5 migration path documentation page.

## Arguments
- $ARGUMENTS: The GitHub issue number (e.g. "4092") OR a description of what the migration path should cover.

## Instructions

### 1. Gather Requirements

If a GitHub issue number is provided, fetch it with `gh issue view <number> --repo AxonIQ/AxonFramework` to understand what the migration path should cover. 
If not found there, try `gh issue view <number> --repo AxonIQ/axoniq-framework`. 
Otherwise, use the provided description.

### 2. Study Existing Patterns

Read 1-2 existing migration path files from `docs/reference-guide/modules/migration/pages/paths/` to match the established structure and tone.

### 3. Research the AF5 APIs

Explore the codebase to understand the AF5 replacements for the AF4 concepts being migrated. Look at actual interfaces, classes, and method signatures so the documentation is accurate.

### 4. Create the New Page

Create the page at `docs/reference-guide/modules/migration/pages/paths/<name>.adoc` following these conventions:
- Start with a title (`= <Topic> Migration`) and `:navtitle:`
- Include an introductory paragraph explaining the change
- Add a `[NOTE]` block summarizing the most significant changes
- Organize into logical sections (API changes, configuration, Spring Boot, etc.)
- **Always use `[tabs]` blocks** with `Axon Framework 4::` shown first, then `Axon Framework 5::` second
- Include "Key changes" or "Key characteristics" bullet points after code samples
- Use accurate package names and method signatures from the actual codebase

### 5. Update the Nav

Add the new page to `docs/reference-guide/modules/migration/partials/nav.adoc`.

### 6. Update the Index

Add a reference link in `docs/reference-guide/modules/migration/pages/paths/index.adoc` under the "Specific Migration Paths" list. If there's a "Coming soon" placeholder for this topic, replace it.

### 7. Show a Summary

Show a summary of the new page's sections and what was updated.