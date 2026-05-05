---
name: axon4-to-axon5-maven-migration-profile
description: Adds (or updates) a `migration` Maven profile in the target project that scopes `maven-compiler-plugin` compilation and tests to the files currently being migrated. Detects the migration scope from the working git diff so the includes match the user's actual progress, regardless of whether they're migrating aggregates, projections, sagas, configuration, or anything else.
---

## Why this skill exists

Migrating a project from Axon Framework 4 to 5 is incremental — you migrate one chunk at a time and want to keep verifying it as you go. While the rest of the codebase still uses AF4, `./mvnw test` for the whole module fails at **main compile** on unrelated AF4-using classes (e.g. `org.axonframework.commandhandling.gateway.CommandGateway`, `org.axonframework.eventhandling.DomainEventMessage`). You can't reach your migrated tests at all.

`maven-compiler-plugin`'s `includes`/`excludes` parameters are not exposed as `-D` user properties (verified empirically against plugin 3.14.1). The supported way to scope compilation is **POM `<configuration>`** — wrapped in a Maven profile so it isn't always-on. With that profile in place, the per-construct migration skills (e.g. `axon4-to-axon5-eventsourcing-aggregate`) can verify their work with:

```bash
./mvnw test -Pmigration -Dtest='<FQTestClasses>' -DfailIfNoTests=false
```

This skill is **construct-agnostic**. Aggregates are one example; the same profile works for any AF4→AF5 migration step (projections, sagas, configuration, sequencing policies, …).

## When to run

- **First time** in a project that hasn't started migrating yet, or when no `<profile id="migration">` exists in the relevant POM.
- **Every time** the migrated set grows and the existing include list no longer covers it (the build complains "cannot find symbol" for files outside the includes).

The skill is idempotent: if a `migration` profile already exists, this skill **augments** its include lists rather than replacing them.

## Instructions

1. **Locate the target `pom.xml`.** Default: the project root the user pointed at. For multi-module builds, the profile goes in the module(s) being migrated.

2. **Detect the migration scope from the working git state.** The user is migrating something *right now* — those changed Java files are the migrated set. Run, in the target project's root:

   ```bash
   git diff --name-only HEAD -- '*.java'
   git diff --cached --name-only -- '*.java'
   git ls-files --others --exclude-standard -- '*.java'
   ```

   Combine and deduplicate. Then partition into:
   - **Main sources** — paths under `src/main/java/`
   - **Test sources** — paths under `src/test/java/`

   If the working tree is clean (the user has committed but not pushed), use `git diff --name-only <base>...HEAD -- '*.java'` instead, where `<base>` is the branch the work diverged from (usually `main`). Ask the user if you're not sure.

   If the working tree is **completely** clean (nothing in progress), ask the user which package(s) they're about to migrate — don't fabricate scope.

3. **Map changed files to compiler include patterns.** Strip the `src/main/java/` or `src/test/java/` prefix and convert to a relative classpath path. Two strategies, pick the one that matches the user's intent:

   - **Per-file** (precise, verbose): one `<include>` per changed file. Good when changes are scattered across many packages.
     ```
     com/example/foo/Bar.java
     com/example/foo/Baz.java
     ```
   - **Per-package** (concise, forward-friendly): collapse all changes under a directory into a single `**/*.java` glob. Good when most changes cluster in a few packages and you'll add more files there soon.
     ```
     com/example/foo/**/*.java
     ```

   Default to **per-package** at the deepest common-prefix directory, since AF4→AF5 migration tends to touch full feature slices. Show the user the proposed list and ask for adjustments before writing.

4. **Add or update the `migration` profile in `pom.xml`.**

   - If `<profile id="migration">` does **not** exist: append the block below as a sibling of `<build>` (creating a `<profiles>` parent if needed).
   - If it **already exists**: merge the new patterns into the existing `<includes>` and `<testIncludes>` (deduplicating).

   ```xml
   <profiles>
       <profile>
           <id>migration</id>
           <build>
               <plugins>
                   <plugin>
                       <groupId>org.apache.maven.plugins</groupId>
                       <artifactId>maven-compiler-plugin</artifactId>
                       <configuration>
                           <includes>
                               <!-- one <include> per main-source pattern from step 3 -->
                           </includes>
                           <testIncludes>
                               <!-- one <testInclude> per test-source pattern from step 3 -->
                           </testIncludes>
                       </configuration>
                   </plugin>
               </plugins>
           </build>
       </profile>
   </profiles>
   ```

5. **Verify the profile compiles.** Pick one migrated test class (or any class under the include patterns) and run:

   ```bash
   ./mvnw test -Pmigration -Dtest='<FQTestClass>' -DfailIfNoTests=false
   ```

   - **`BUILD SUCCESS`** → done.
   - **"cannot find symbol"** for a class outside the include list → that file is transitively referenced from a kept file. Add its package/file to the includes and re-run. (Apache's own [Maven FAQ](https://github.com/apache/maven-site/blob/master/content/markdown/faq-unoffical.md) notes this transitivity: javac still pulls in references through kept files.)
   - **Compile errors *inside* the included files** → that's normal pre-migration state for the construct the user is currently working on; hand off to the per-construct migration skill.

6. **Tell the user how to grow the include list** as migration progresses. Each subsequent migration step expands the diff; re-run this skill (or hand-edit the profile) so includes keep up. When the include list covers the entire module, **delete the profile** — the project no longer needs scoping.

## Caveats

- **Transitive references leak.** Per Apache's Maven FAQ, javac will still compile a file that's referenced from a kept file, even if it's not in `<includes>`. So if `KeptClass.java` imports `BrokenClass.java`, you must either include `BrokenClass.java` too or migrate it. The build error tells you exactly what to add.
- **Production builds are unaffected.** The profile is opt-in via `-Pmigration`; the default profile (no flag) still compiles everything. Don't activate `migration` by default.
- **Gradle projects** are out of scope. Adapt the same idea using Gradle's `sourceSets` filtering or a dedicated `migrationTest` task.
