# What this orchestrator does NOT migrate

The Axon Framework 4 → 5 migration tooling covers the constructs the
per-construct skills can mechanically (or semi-mechanically) rewrite.
Several AF4 features have **no clear migration path in Axon Framework 5
yet** — either because the AF5 equivalent is still in design, or because
the feature was deliberately dropped from AF5 and replaced with a
different shape that requires bespoke project-by-project judgment.

If the target project uses any of the items below, the orchestrator will
**leave that surface untouched**. Compile errors and test failures from
those constructs are expected and need to be handled by the user
(usually by removing the feature, replacing it with an AF5-native
alternative chosen per project, or staying on AF4 for that slice until
official guidance lands).

## Out-of-scope features

### 1. Sagas (`@Saga`, `SagaConfigurer`, `EventScheduler` saga lifecycle)

- **AF4 surface:** `org.axonframework.modelling.saga.*`,
  `@Saga`, `@SagaEventHandler`, `@StartSaga`, `@EndSaga`,
  `org.axonframework.config.SagaConfigurer`,
  `org.axonframework.modelling.saga.repository.*`.
- **Why excluded:** AF5 reframes long-running coordination as a
  process-manager pattern with explicit projection-driven entities
  rather than the AF4 saga abstraction. There is no automatic
  rewrite — the right replacement depends on whether the saga's role
  was *correlation*, *temporal coordination*, or *state-machine over
  many aggregates*, each of which lands on a different AF5 shape.
- **Detection:**
  ```bash
  grep -RlE '@Saga\b|@SagaEventHandler|@StartSaga|@EndSaga|SagaConfigurer' \
       --include='*.java' --include='*.kt' src 2>/dev/null
  grep -RnE 'org\.axonframework\.modelling\.saga' \
       --include='*.java' --include='*.kt' src 2>/dev/null
  ```

### 2. Aggregate snapshotting

- **AF4 surface:** `Snapshotter`, `SnapshotTriggerDefinition`,
  `EventCountSnapshotTriggerDefinition`, `@Aggregate(snapshotTriggerDefinition = "...")`,
  `AggregateSnapshotter`, `AbstractSnapshotter`.
- **Why excluded:** AF5's event-sourced entity model (`@EventSourcedEntity`)
  does not yet expose a finalized snapshot API. The AF4 snapshotting
  flow (load latest snapshot, replay tail) maps imperfectly to AF5's
  async stream model and needs framework-side support that has not
  shipped.
- **Detection:**
  ```bash
  grep -RlE 'Snapshotter|SnapshotTriggerDefinition|snapshotTriggerDefinition' \
       --include='*.java' --include='*.kt' src 2>/dev/null
  ```

### 3. MongoDB extension (`axon-mongo`)

- **AF4 surface:** `org.axonframework.extensions.mongo.*`,
  `MongoEventStorageEngine`, `MongoTokenStore`,
  `MongoSagaStore`, `axon-mongo-spring-boot-autoconfigure`.
- **Why excluded:** No AF5 release of the Mongo extension at the time
  this orchestrator was written. The OpenRewrite recipe set does not
  cover `org.axonframework.extensions.mongo.*` package renames, and
  there is no AF5 equivalent of `MongoEventStorageEngine` for the
  aggregate-centric event store. Projects using Mongo as the event
  store typically need to either move to JPA / Axon Server, or stay on
  AF4 for the storage layer until the extension ships.
- **Detection:**
  ```bash
  grep -RlE 'org\.axonframework\.extensions\.mongo|axon-mongo' \
       --include='*.java' --include='*.kt' --include='pom.xml' --include='*.gradle*' \
       . 2>/dev/null
  ```

### 4. Kafka extension (`axon-kafka`)

- **AF4 surface:** `org.axonframework.extensions.kafka.*`,
  `KafkaPublisher`, `StreamableKafkaMessageSource`, `KafkaProperties`,
  `axon-kafka-spring-boot-autoconfigure`.
- **Why excluded:** Same as Mongo — no AF5 release of the Kafka
  extension yet. AF5's `EventBus` and streaming abstractions changed
  shape (async-first, `MessageStream<EventMessage>`), so the AF4
  `KafkaMessageSource` cannot be lifted directly.
- **Detection:**
  ```bash
  grep -RlE 'org\.axonframework\.extensions\.kafka|axon-kafka' \
       --include='*.java' --include='*.kt' --include='pom.xml' --include='*.gradle*' \
       . 2>/dev/null
  ```

## What "out of scope" means in practice

For each detected use of an out-of-scope feature, the orchestrator:

1. **Surfaces it to the user before phase 1 runs**, listing the
   concrete files / dependencies found and naming the feature.
2. Asks whether to proceed knowing that surface will not be migrated
   — the user can:
   - **Continue** — accept that the feature stays on AF4 shape and
     will fail to compile / fail at runtime in phase 10.
   - **Pause** — abort the migration so the user can either remove
     the feature first or hold the migration until upstream support
     lands.
3. **Records the decision** in `learnings.md` so future sessions don't
   re-prompt.
4. **Does not alter** the affected files in any phase. Even if a
   per-construct skill's detection grep matches a saga class, the
   orchestrator skips it.
5. Adds a final entry to phase-10 stabilization called
   "deferred — out of scope" that the user can choose to address
   manually or defer indefinitely.

## When to revisit this list

This list is a snapshot in time. As AF5 ships official migration paths
(saga rework, snapshot API, Mongo / Kafka 5.x extensions), entries here
can be promoted to dedicated migration skills. If you're migrating a
project and hit one of these features, check
[`docs/reference-guide/modules/migration/`](../../../../docs/reference-guide/modules/migration/)
in the AxonFramework5 repo for newer guidance before assuming nothing
exists yet.
