# Documentation Code Samples

This module compiles the Java code samples that are included in the documentation. The sample
sources live in the Antora `examples` directories of each documentation component
(`docs/<guide>/modules/<module>/examples`) and are pulled into the pages through tagged regions.
The module is part of the default Maven reactor: a framework API change that breaks a documented
sample breaks the build. It is never deployed.

## How a page includes a sample

```adoc
[source,java]
----
include::example$commands/commandhandlers/FacultyAnnouncementCommandHandler.java[tag=stateless-command-handler,indent=0]
----
```

In the Java file, regions are delimited with tag comments:

```java
// tag::stateless-command-handler[]
...
// end::stateless-command-handler[]
```

## Conventions

- **Package per page**: `<antoramodule>.<pagename>`, lowercase with dashes removed
  (page `commands/pages/command-handlers.adoc` -> package `commands.commandhandlers`,
  page `commands/pages/entities/entity-creator.adoc` -> package `commands.entities.entitycreator`).
  Use a deeper sub-package (for example `commands.commandhandlers.declarative`) when a page
  displays the same class name more than once in different variants.
- **Self-contained pages**: every supporting type a snippet references (commands, events,
  queries, services, id types) is defined in the page's own package, so rendered snippets need
  no imports for them. Framework imports shown in the original snippet stay inside the tag.
- **Tag names describe the snippet** (`tag=declaring-command-type`), never positions.
- **Callouts** stay as `// <1>` comments inside the tagged region; Asciidoctor renders them as
  callout markers.
- **Nested types + `indent=0`**: a snippet that displays a top-level-looking record or method
  may live nested inside a wrapper class; `indent=0` strips the extra indentation. Use member
  imports (`import pkg.Outer.Inner;`) to keep references unqualified in the display.
- **Multi-fragment blocks** (for example records followed by bare handler methods) become
  several sibling top-level package-private classes in one file, one tag each, included with
  `tags=a;b;c`. Keep all tagged regions at the same nesting depth (indent normalization is
  per-include, not per-tag) and end a region with a blank line to separate it from the next.
- **Intentionally non-compiling blocks** stay inline in the page and are marked with a role:
  `[source,java,role=axon4]` for Axon Framework 4 "before" code on migration pages,
  `[source,java,role=pseudocode]` for illustrative pseudo-code. Everything else must be an
  include.
- **Test samples**: examples of the `testing` module are compiled as test sources, so they may
  use JUnit, AssertJ, and the `axon-test` fixtures.
- **ASCII only, LF line endings**, 4-space indentation, no license headers in sample files.

## Verifying

Compile everything (from the repository root):

```bash
./mvnw -pl docs/_samples test-compile
```

Check that a converted page renders the same code as before conversion:

```bash
python3 docs/_samples/bin/compare-snippets.py docs/reference-guide/modules/commands/pages/command-handlers.adoc HEAD
```

Every difference the script reports must be intentional (for example a dropped import of a
sample-local type). If compilation reveals that a documented snippet was wrong, fix the sample
so it keeps its teaching intent and record the finding in the commit message.
