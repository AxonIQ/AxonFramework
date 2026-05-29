# Axon Framework 5 — Event Message Transformation Demo

A worked example of the AxonIQ Framework 5.2 event message transformation chain on a
realistic CQRS / DCB application — a course catalog that has evolved across three years
of production and now ships a chain of five transformers to lift every legacy event
into its current shape on read.

The module follows the same architectural conventions as
[`university-demo`](../university-demo): screaming-architecture vertical slices,
DCB-based write side, `@EventSourcedEntity`-per-slice modelling, a tracking projection,
and an automation slice.

## What this demo shows

The catalog has accumulated four kinds of legacy events that the current code can no
longer consume directly. Five transformations, registered as one chain, lift them on
the way out of the store so commands, projections, and automations all see today's shape.

| Transformation | From → to | Style | What it teaches |
|---|---|---|---|
| `CoursePublishedV1ToV2` | `CoursePublished#1.0.0` → `#2.0.0` | `JsonNode` mapper | Year-1 single `capacity` becomes year-2 `minCapacity`/`maxCapacity`. |
| `CoursePublishedV2ToV3` | `CoursePublished#2.0.0` → `#3.0.0` | `JsonNode` mapper | Two-hop chain: a stored v1 event lands at handlers as v3 via this transformer. |
| `StudentRegisteredV1ToV2` | `StudentRegistered#1.0.0` → `#2.0.0` | `TypeReference<Map<String,Object>>` overload | Generic carrier type — no Jackson dependency in user code. |
| `SystemAnnouncementLegacyUplift` | `SystemAnnouncement#0.0.1` → `#1.0.0` | `JsonNode` mapper | Unversioned legacy events: `@Event` without a `version` defaults to `0.0.1`. |
| `WelcomeMessageBetaCleanup` | `WelcomeMessageSent#0.*` → `#1.0.0` | predicate-based source | One transformer matches every beta version (`0.5`, `0.7`, `0.9`). |

The chain is composed in
[`CourseCatalogTransformations`](src/main/java/org/axonframework/examples/demo/coursecatalog/catalog/transformations/CourseCatalogTransformations.java)
and engaged automatically by registering it as an `EventTransformerChain` component —
the framework's `EventTransformationConfigurationEnhancer` (discovered via
`ServiceLoader`) installs the `TransformingEventStore` decorator on top of the store.

## Story: three years of course-catalog evolution

* **Year 1.** A `CoursePublished` event carries `name` + `capacity`. A
  `SystemAnnouncement` event is written without an explicit `version`. A
  `WelcomeMessageSent#0.5` event is sent to every new student (subject + body).
* **Year 2.** Capacity becomes a range, so `CoursePublished` is split into
  `minCapacity`/`maxCapacity`. `WelcomeMessageSent` continues its beta with `0.7` and
  `0.9` while design iterates on the subject.
* **Year 3 (today).** `CoursePublished` collapses capacity into a `CapacityRange` value
  object. `StudentRegistered` replaces `firstName` + `lastName` with one `fullName`.
  `SystemAnnouncement` becomes formally versioned at `1.0.0`. `WelcomeMessageSent` ships
  GA at `1.0.0` with only `studentId` + `body`.

The seeder
([`LegacyEventSeeder`](src/main/java/org/axonframework/examples/demo/coursecatalog/catalog/seed/LegacyEventSeeder.java))
writes the historic events idempotently at startup so the application boots into a
state that actually exercises every transformation.

## Architecture

Package layout mirrors the university-demo convention:

```
org.axonframework.examples.demo.coursecatalog
├── catalog
│   ├── automation
│   │   └── overbookingnotifier       — fires when capacity drops below current enrolments
│   ├── events                        — current-shape event records (@Event-versioned)
│   ├── read
│   │   └── catalogview               — tracking projection + read model + query
│   ├── seed                          — legacy event seeder (idempotent)
│   ├── transformations               — the chain composition site + 5 transformers
│   ├── values                        — CapacityRange value object
│   └── write
│       ├── publishcourse             — write slice (DCB, @EventSourcedEntity)
│       ├── updatecoursecapacity      — write slice
│       └── enrollstudent             — write slice (multi-tag DCB)
└── shared
    ├── ids                           — typed ids (CatalogId, CourseId, StudentId, EnrolmentId)
    └── notifier                      — NotificationService port + logging adapter
```

## Running the demo

The demo can run either fully in-memory (the default — convenient for CI) or against a
real Axon Server with DCB support.

### In-memory (no infrastructure)

```bash
mvn -pl examples/university-message-transformation -am compile exec:java \
    -Dexec.mainClass=org.axonframework.examples.demo.coursecatalog.CourseCatalogApplication
```

Equivalent: run `CourseCatalogApplication#main` from your IDE.

The bootstrap seeds the legacy history, dispatches a few sample commands, awaits the
projection, prints the resulting catalog view, and shuts down. The chain build log
appears at `DEBUG` level — set `org.axonframework=DEBUG` in `logback.xml` to see it.

### Against Axon Server

```bash
docker compose up -d                           # boots Axon Server with DCB enabled
# Toggle src/main/resources/application.properties: axon.server.enabled=true
mvn -pl examples/university-message-transformation -am compile exec:java \
    -Dexec.mainClass=org.axonframework.examples.demo.coursecatalog.CourseCatalogApplication
```

The Axon Server UI is at <http://localhost:8024>. You'll see the historic events as
they were written — the chain runs on the **read** path, so the store carries the
original `CoursePublished#1.0.0` payloads while handlers receive `#3.0.0`.

## Testing

`mvn -pl examples/university-message-transformation -am test`

The test pyramid runs four layers, mirroring the holixon convention:

| Layer | Subject | Examples |
|---|---|---|
| **L1 — unit** | each transformer in isolation | `CoursePublishedV1ToV2Test`, `WelcomeMessageBetaCleanupTest` |
| **L2 — chain** | the composed chain | `MultiHopChainTesterTest`, `ChainBuildLogTest`, `ChainLockingTest`, `OutputIdentityCheckTest`, `MessageTypesConsistencyTest`, `ChainConcurrencyTest`, `DecorationOrderTest` |
| **L3 — slice** | each slice with the full app fixture | `PublishCourseAxonFixtureTest`, `EnrollStudentAxonFixtureTest`, `CatalogViewProjectionAxonFixtureTest`, `OverbookingNotifierAxonFixtureTest` |
| **L4 — end-to-end** | the chain on top of the real store | `HistoricEventsUpcastingIntegrationTest`, `MainSmokeTest` |

L1 transformer tests use a small `TransformationTester`/`ChainTester` pair plus
golden JSON files under `src/test/resources/golden/` so the assertions read like
*"this stored shape becomes this current shape"* rather than dense fluent chains.

## Read contexts exercised

The framework's `TransformingEventStore` decorator transforms events in **three**
read contexts, all wired in this demo:

1. **Entity load** — `@EventSourcedEntity` rebuilds in the write slices read upcasted
   payloads when sourcing state from the store.
2. **DCB consistency check** — the `EnrollStudent` handler's `@EventCriteriaBuilder`
   query receives upcasted events when validating the consistency boundary.
3. **Tracking projection** — the `CatalogView` projection's `@EventHandler`s receive
   only current-shape events; legacy payloads never reach user code.

## Conventions checklist

The demo follows the same rules as the rest of `examples/`:

* No `@SuppressWarnings`.
* No blocking `future.join()` / `future.get()` without a timeout — `orTimeout(...).join()`
  everywhere.
* Generic `Type` is passed via `TypeReference<...>` constants, never raw `Class`.
* Jackson 3 (`tools.jackson.*`) only — the framework's internal JSON binding.
* Javadoc is consumer-facing: no `FR-XXX` / phase / plan references.
