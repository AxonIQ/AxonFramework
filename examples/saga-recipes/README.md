# Saga Recipes

Four ways to implement an Axon Framework 4 Saga in Axon Framework 5, applied to the same business process, with one
shared test proving they are interchangeable.

The approaches are described in prose in the
[Sagas and Process Managers guide](https://docs.axoniq.io/saga-guide/). This module is the running code behind it.

## The process

The bike rental payment saga from the Axon Framework 4 sample application. A renter asks for a bike; the rental is not
confirmed until payment is; if payment is refused, cancelled or never arrives, the request is turned down and the bike
released.

Two contexts, which know nothing about each other:

- `rental` - bikes and rental requests. Never mentions payment.
- `payment` - a generic payment context that can be paid for anything. Never mentions renting.
- `saga` - the only place allowed to import from both. That is what makes it the saga.

An ArchUnit test enforces that boundary, including a check that no payment record carries a field named after a rental
concept. It is the one design property this module cannot afford to lose by accident.

## How the code is laid out

Both contexts follow **Vertical Slice Architecture**, like `examples/university-java`. There is no service layer and
no shared domain class. Each command gets a `write/<slicename>/` folder holding the command, its handler, and the
handler's own decision model, so a slice sources exactly the events its own rule needs rather than sharing one
aggregate-shaped model with every other slice. The `automations` recipe extends the same idea to the process itself,
one folder per reaction.

## The recipes

Exactly one runs at a time, chosen with `saga.recipe`:

| `saga.recipe`         | Where the process remembers                          | Package                 |
|-----------------------|------------------------------------------------------|-------------------------|
| `repository`          | a JPA row, committed with the tracking token          | `saga/repository`       |
| `injectentity`        | nowhere, derived from both contexts' events           | `saga/injectentity`     |
| `eventsourced`        | its own events, recorded through a command            | `saga/eventsourced`     |
| `eventsourced-append` | its own events, appended from the event handler       | `saga/eventsourced`     |
| `automations`         | mostly nowhere, six independent slices                | `saga/automations`      |

Read the class-level Javadoc of each `PaymentProcess` for what it buys, what it costs, and how the process ends. The
two event-sourced variants share a package, their events, and their `ProcessState`; they differ only in the two lines
that write a fact down. `automations` documents itself in `package-info.java`, because it has no central class to
document.

`saga/deadline` sits outside the recipes. It replaces Axon Framework 4's `DeadlineManager` with a projection of outstanding
payments plus a scheduled sweep, and applies to every recipe equally.

## Running the tests

```bash
./mvnw -Pexamples -pl examples/saga-recipes -am clean verify
```

Everything runs in memory. No Axon Server and no database are required.

## Running the application

```bash
./mvnw -Pexamples -pl examples/saga-recipes spring-boot:run
```

Axon Server is started through `examples/docker-compose.yaml`. Switch recipes with
`--saga.recipe=automations` and the observable behaviour should not change, which is the point.

## What to read first

`SagaRecipeContractTest` is the most useful file in the module. It holds the scenarios every recipe must satisfy, and
they mirror the bike rental sample application's `PaymentSagaTest` method for method, with two cases that suite never
had: a redelivered trigger, and a timeout arriving after the payment already settled.

Each recipe subclass adds only what is specific to it. Anything asserted in the shared class is, by construction,
behaviour that does not depend on the approach.

## Two rules the module exists to demonstrate

**Commands a process sends must be idempotent.** Event processors deliver at least once, so every command will
sometimes be sent twice. Approving an already-approved request appends nothing and reports success.

**Progress must not be recorded before the command succeeded.** There is no transaction spanning "dispatch a command"
and "write down that I did". Recording first wedges the process permanently: the dispatch fails, the token is not
committed, the event is redelivered, and the record now says the work is done.

The recipes that store nothing sidestep the second rule entirely, which is their main argument.

## Notes on the setup

- Spring Boot 4.1.0 is skipped deliberately. Combined with JPA and the Axon starter it deadlocks on a circular
  reference between `applicationTaskExecutor` and Axon's `UnitOfWorkFactory`; 4.1.1 resolves it.
- The aggregate-based JPA event storage engine is excluded. It rejects multiple tags per event, and this example tags
  events with `bikeId`, `rentalId` and `renter` at once.
- `spring.properties` disables Spring's test context pausing. Restarting a paused context re-runs Axon's start-up
  lifecycle, which re-subscribes handlers and fails with a duplicate subscription.
