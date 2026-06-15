# Axon Framework 5 - Event Message Transformation Demo

An example of the event message transformation chain in AxonIQ Framework 5.2.

Stored events outlive the code that wrote them. The chain lifts each historic payload
into its current shape on the read path, so it is easier for handlers to handle them.

This demo wires transformers into one chain on a course catalog that has gone
through schema evolution, and exercises them end to end.

## The transformations

| Transformer | From / To | What it teaches |
|---|---|---|
| `CoursePublishedV1ToV2` | `CoursePublished#1.0.0` / `#2.0.0` | A single `capacity` field becomes `minCapacity` + `maxCapacity`. |
| `CoursePublishedV2ToV3` | `CoursePublished#2.0.0` / `#3.0.0` | Two-hop chain: a stored v1 event reaches handlers as v3. |
| `StudentRegisteredV1ToV2` | `StudentRegistered#1.0.0` / `#2.0.0` | `TypeReference<Map<String,Object>>` overload, no Jackson dependency in user code. |
| `SystemAnnouncementLegacyUplift` | `SystemAnnouncement#0.0.1` / `#1.0.0` | Unversioned legacy events: `@Event` without a `version` defaults to `0.0.1`. |
| `WelcomeMessageBetaCleanup` | `WelcomeMessageSent#0.*` / `#1.0.0` | Predicate-based source, one transformer matches every beta version. |

The chain is composed in
[`CourseCatalogTransformations`](src/main/java/org/axonframework/examples/demo/coursecatalog/catalog/transformations/CourseCatalogTransformations.java).
Registering it as an `EventTransformerChain` component is enough: the framework's
`EventTransformationConfigurationEnhancer` (discovered via `ServiceLoader`) installs
the `TransformingEventStore` decorator on top of the store.

[`LegacyEventSeeder`](src/main/java/org/axonframework/examples/demo/coursecatalog/catalog/seed/LegacyEventSeeder.java)
writes the historic events on startup (idempotently), so the application boots into
a state that actually exercises every transformer above.

The chain runs on all three read paths wired here: entity load on the write slices,
the consistency check used by `EnrollStudent`, and the catalog projection.

## Layout

```
org.axonframework.examples.demo.coursecatalog
+- catalog
|  +- automation/overbookingnotifier
|  +- events                          (current-shape event records)
|  +- read/catalogview                (projection, read model, query)
|  +- seed                            (legacy event seeder)
|  +- transformations                 (chain composition + transformers)
|  +- values                          (CapacityRange value object)
|  +- write                           (command slices + RequestRegionCommandInterceptor)
|     +- publishcourse
|     +- updatecoursecapacity
|     +- enrollstudent
+- shared
   +- ids                             (typed ids)
   +- notifier                        (NotificationService port + adapter)
   +- region                          (RequestRegion processing-context resource)
```

## Running

### In-memory (no infrastructure)

From this module's directory:

```
mvn compile exec:java
```

Or run `CourseCatalogApplication#main` from your IDE.

The bootstrap seeds the legacy history, dispatches a few sample commands, awaits
the projection, prints the resulting catalog view, and shuts down.

The bundled `logback.xml` enables `DEBUG` for `io.axoniq.framework.messaging.transformation`,
so the chain build log naming every registered transformer shows up on startup.

### Against Axon Server

1. `docker compose up -d` in this module's directory.
2. Flip `axon.server.enabled` to `true` in `src/main/resources/application.properties`.
3. Re-run `CourseCatalogApplication#main`.

The dashboard at <http://localhost:8024> shows the historic events as they were
written. The chain runs on the read path, so the store keeps the original
`CoursePublished#1.0.0` payload while handlers receive `#3.0.0`.

### Against AxonIQ Platform (optional)

Reports message flows, processors, and metrics to <https://console.axoniq.io>
through the bundled `axoniq-platform-framework-client`.

1. Create an environment at <https://console.axoniq.io> and open its Install
   Guide; copy the environment id and access token.
2. `cp .env.example .env` in this module's directory and paste them in.
3. Run `CourseCatalogApplication#main`. The log line
   `AxonIQ Platform reporting enabled for application '...'` confirms it.

Real env vars (uppercase, same names) override `.env`. Without credentials the
integration stays dormant.

### Interactive shell

Pass `--keep-alive` as the first program argument and the demo drops you into a
small stdin prompt after the initial catalog view is printed:

```
mvn compile exec:java -Dexec.args=--keep-alive
```

You type a command, press enter, the application processes it, and the prompt
comes back. Try this session:

```
course-catalog> publish ai-101 "AI Fundamentals" 10 40
[ok] published ai-101

course-catalog> enroll ai-101 alice EU
[ok] enrolled alice in ai-101 (region EU)

course-catalog> capacity ai-101 5 50
[ok] capacity updated for ai-101

course-catalog> view
Registered students: 4
Courses (7):
  ...
  - Course:ai-101 "AI Fundamentals" range=CapacityRange[min=5, max=50] enrolments=1
  ...
Enrolments (1):
  - Student:alice in Course:ai-101 region=EU
  ...

course-catalog> exit
```

The placeholders in `help` (`<courseId>`, `<name>`, `<min>`, `<max>`,
`<studentId>`) are values you choose: any string for `courseId` and `studentId`,
the course name in double quotes if it contains spaces, integers for `min` and
`max`. Type `exit`, `quit`, or press Ctrl+D to shut down cleanly.

The optional `[region]` on `enroll` is how you watch the processing context at
work. The seeded students `alice`, `bob`, `carol`, and `dave` were registered
before the catalog had regions, so their stored `StudentRegistered` events carry
none. Pass a region and a command interceptor lifts it onto the processing
context; when the enrolment handler sources the student, the same context threads
into the transformation chain and `StudentRegisteredV2ToV3` backfills it. The
enrolment then carries that region into the view. Leave the region off and the
enrolment falls back to `GLOBAL`.

## Testing

From this module's directory:

```
mvn test
```

Or open the module in IntelliJ and use the Maven tool window.

Tests live next to the production code they cover, in the matching package:

| Where | What you'll find |
|---|---|
| `catalog/transformations/` | each transformer in isolation, with fixture JSON under `src/test/resources/transformations/` |
| `catalog/chain/` | the composed chain (build log, locking, identity check, concurrency, decoration order) |
| `catalog/write/<slice>/` | one write slice per package, full app fixture |
| `catalog/read/catalogview/` | the catalog projection and query |
| `catalog/automation/overbookingnotifier/` | the overbooking notifier |
| `catalog/seed/` | the seeder plus the chain exercised end to end on the store |
| `catalog/testutil/` | shared test helpers (`TransformationTester`, `ChainTester`, JSON assertions) |
| `shared/notifier/` | a recording `NotificationService` for slice tests |
| (root) | `MainSmokeTest`, `CourseCatalogApplicationTest` |
