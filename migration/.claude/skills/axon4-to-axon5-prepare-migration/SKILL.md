---
name: axon4-to-axon5-prepare-migration
description: Prepares a target project for incremental Axon 4 → Axon 5 migration by adding a `migration` Maven profile that limits compilation and test scope to a chosen subset of source files. Run once before starting per-aggregate migration work.
---

## Why this skill exists

Migrating a project from Axon Framework 4 to 5 is incremental — you migrate one aggregate, then run its tests, then move on. While part of the codebase is still on AF4, `./mvnw test` for the whole module fails at **main compile** on unrelated AF4-using classes (e.g. `org.axonframework.commandhandling.gateway.CommandGateway`, `org.axonframework.eventhandling.DomainEventMessage`). You can't get to your migrated tests at all.

`maven-compiler-plugin`'s `includes`/`excludes` parameters are not exposed as `-D` user properties (verified empirically against plugin 3.14.1; also see the [Apache Maven FAQ](https://maven.apache.org/general.html)). The supported way to scope compilation is **POM `<configuration>`** — but you don't want that always-on. Wrap it in a Maven profile and toggle with `-Pmigration`.

This skill adds that profile to the target project once, so the per-aggregate migration skill (`axon4-to-axon5-eventsourcing-aggregate`) can rely on a simple verification command:

```bash
./mvnw test -Pmigration -Dtest='<FQTestClasses>' -DfailIfNoTests=false
```

## When to run

- Run **once per project**, before invoking `axon4-to-axon5-eventsourcing-aggregate` for the first time.
- If the project is multi-module, run once per module that holds AF4 aggregates.
- Skip this skill entirely if the project's main sources still compile cleanly under AF5 (i.e. all AF4 references are inside the modules you're migrating right now).

## Instructions

1. **Locate the target `pom.xml`.** Default: the project root the user pointed at. For multi-module builds, the profile goes in the module(s) you'll migrate.
2. **Identify the first migration target.** Ask the user which aggregate they will migrate first (or look for the first class annotated with `@Aggregate` + `@EventSourcingHandler`). Use its package and any directly-referenced `shared/`/`common/` packages as the initial include set. Be conservative: the include list is a whitelist, and missing entries surface immediately as "cannot find symbol" — easy to fix incrementally.
3. **Add the `migration` profile to the POM.** Insert (or append to) a `<profiles>` block, sibling of `<build>`. Use **`<includes>`** to whitelist the migrated subset; this is more honest than `<excludes>` because you grow the list as you migrate, and the build complains loudly when something is missing.

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
                               <include>com/example/<firstAggregatePkg>/**/*.java</include>
                               <include>com/example/shared/<usedSubpkg>/**/*.java</include>
                           </includes>
                           <testIncludes>
                               <testInclude>com/example/<firstAggregatePkg>/**/*.java</testInclude>
                               <testInclude>com/example/shared/<testUtilSubpkg>/**/*.java</testInclude>
                           </testIncludes>
                       </configuration>
                   </plugin>
               </plugins>
           </build>
       </profile>
   </profiles>
   ```

4. **Verify the profile works** by running the project's existing test for the chosen aggregate (or any one test under the included paths):

   ```bash
   ./mvnw test -Pmigration -Dtest='<FQTestClass>' -DfailIfNoTests=false
   ```

   Expected: `BUILD SUCCESS` with the test running. If you see "cannot find symbol" for a class, add its package to the includes; if you see compilation errors *inside* the included files, that's normal pre-migration state — proceed with the per-aggregate skill.

5. **Tell the user how to grow the profile** as migration progresses. Each time `axon4-to-axon5-eventsourcing-aggregate` finishes a new aggregate, append its package to both `<includes>` and `<testIncludes>`. When the include list covers the whole module, delete the profile — the project no longer needs it.

## Caveats

- **Apache's own FAQ notes** ([maven-site/faq-unoffical](https://github.com/apache/maven-site/blob/master/content/markdown/faq-unoffical.md)): even with `<excludes>`/`<includes>`, `javac` will still try to compile a file if it is referenced from a kept file. So the include set must be **transitively closed** under compile-time references. If a kept file imports something outside the include list, add it. The build will tell you immediately.
- This profile only narrows compilation. It does not change runtime behavior, dependencies, or production builds — those continue to use the default profile (no `-Pmigration`).
- If the project uses Gradle instead of Maven, this skill does not apply. Adapt the same idea using Gradle's `sourceSets` filtering or a dedicated `migrationTest` task — out of scope here.
