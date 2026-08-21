# Saga recipes for Axon Framework 5

This example ports the bike-rental payment saga from Axon Framework 4 and implements the same process in five ways.
Axon Framework 5 does not prescribe a Saga SPI, SagaStore, DeadlineManager, or SagaTestFixture. A migration can
therefore choose the state model that fits the process instead of automatically storing an opaque saga blob.

The runnable application uses Spring Boot 4.1.1 and Axon Framework 5.3.1. Create-if-missing handlers such as
`PreparePayment` use `@Nullable @InjectEntity`: `null` means no matching first event exists, while a non-null entity is
the idempotency marker. The ordinary `@EntityCreator` is used only when there is event history to source.

The module has three sibling contexts:

- `rental` owns bikes and knows nothing about payments.
- `payment` owns generic payments and knows nothing about bikes or rentals.
- `saga` is the only context allowed to know both sides.

`ContextIsolationTest` enforces those boundaries.

## The three rules

1. Payment receives an opaque `paymentReference`, stores it, and echoes it. It never interprets the reference or
   imports a rental type.
2. Every target command is idempotent. Event processing is at least once, so repeating a completed command succeeds
   and appends no event.
3. Process progress is recorded only after the dispatched command succeeds. A failed handler must not advance its
   tracking token or leave process state claiming that work happened.

The repository recipe mutates JPA state in the handler and returns the dispatched command's future. Axon's Spring
transaction manager keeps the repository mutation and tracking-token update in one transaction, waits for that future,
and rolls both back when the command fails. A repository call in `CompletableFuture.thenRun` would be unsafe because
that continuation may execute on another thread, while Spring's JPA transaction is thread-bound.

## Two domain entities, not three

The model has a `Bike` and a `Payment`. There is no Rental aggregate. A rental is the long-running interaction between
those entities, so `rentalId` is a process correlation value carried as an event tag. The process must remember the
`bikeId` and `renter` needed by `ApproveRequest` and `RejectRequest`; no single domain entity owns that pair.

Introducing a Rental entity is a valid zeroth recipe. If commands can target a process entity directly, a separate saga
may no longer be necessary. This example deliberately keeps the Axon Framework 4 shape to demonstrate the alternatives
when that refactoring is not available.

## Payment ID and payment reference

Payment uses two identifiers on purpose:

- `paymentId` is minted by Payment and answers "which payment is this?". A payer confirms or rejects by this ID.
- `paymentReference` belongs to the caller and answers "what did the caller call this?". The caller prepares and
  cancels by this reference.

Think of a bank transfer: the bank assigns a transaction ID, while the sender types an invoice number into the reference
field. The bank prints that reference back without parsing it.

`RentalPaymentReference` is the only code that knows a rental ID and payment reference use the same canonical string.
This makes correlation reversible without storing a lookup table. It also explains why these two wrapper records keep
their `raw` values unprefixed: adding different type prefixes would break equality across the boundary.

## Selecting a recipe

Set `saga.recipe` to one of the following values:

| Value | Process state | Lifecycle end | Main trade-off |
|---|---|---|---|
| `repository` | Private JPA row | Delete row | Closest to an AF4 saga; owns a second transactional resource |
| `injectentity` | Re-sourced from domain events | Derived predicate | No saga write; requires both contexts in one event store |
| `eventsourced` | Process-owned events written by commands | Completion event | Auditable and works with external events; two commands per step |
| `eventsourced-append` | Same process-owned events, appended by event handlers | Completion event | Atomic with token; breaks the command-to-event convention |
| `automations` | Tracking tokens plus slice-private lookups | No central lifecycle | Small independent event-to-command vertical slices |

All event-driven handlers return the command future. The processor therefore waits for command completion before it can
commit its tracking token. Dropping the return changes the interaction into fire-and-forget and can permanently lose a
process step.

### Repository

`saga/repository` stores the `rentalId`, `bikeId`, and `renter` in a schema owned by the process. It writes or deletes the
row before dispatching the next command, but the event processor transaction commits that mutation only after the
returned command future succeeds. A command failure rolls the mutation back together with the tracking token. A
tombstone would be preferable if target commands were not idempotent.

### Inject entity

`saga/injectentity` defines an event-sourced decision model whose criteria ORs the Rental `rentalId` tag with Payment's
independent `paymentReference` tag. It re-sources `bikeId`, `renter`, payment progress, and settlement from events already
in the store. There is no saga-owned write to coordinate with the token.

This recipe is unavailable when a third-party payment provider's events cannot be sourced. It also cannot record work
that produces no domain event, such as an e-mail or arbitrary HTTP call.

### Event-sourced process

Both event-sourced variants use `RentalPaymentRequested` and `RentalPaymentProcessCompleted`. Their decision model reads
only those process events, which makes the process audit independent of the Rental and Payment event stores.

The command-recorded variant keeps a conventional write slice and makes repairs possible by re-sending the record
command. The direct-append variant uses less code and records in the event processor transaction. It always sources the
process state before appending so the append is condition-protected; appending without sourcing would be unconditional.
Neither saga processor handles its own process events, avoiding an output feedback loop.

### Automation slices

`saga/automations` treats each reaction as a separate to-do list. A `BikeRequested` already contains everything needed
to prepare a payment, so that slice needs only its tracking token. Payment events do not contain `bikeId` and `renter`,
so their slices each own a minimal event-sourced lookup derived from `BikeRequested`.

## Sequencing serialized events

Rental events expose `rentalId`; Payment events expose `paymentReference`. `RentalPaymentSequencingPolicy` maps concrete
event qualified names to converter-aware extraction policies and extracts the same raw string from both contexts.

Routing happens on `QualifiedName` before deserialization. This avoids two production traps:

- Targeting a sealed or marker supertype can pass with in-memory POJO messages but fail when Axon Server supplies a
  `byte[]` payload that the converter cannot instantiate as the supertype.
- Chaining typed policies with exception-based fallback can silently choose a wrong all-null value when deserialization
  is lenient.

The sequencing test intentionally uses serialized `byte[]` payloads and a configuration-backed processing context.

## Deadline replacement

`saga/deadline` is always active. It projects `PaymentPrepared` into a table of pending work and removes rows on every
settlement event. A scheduled reader selects rows older than the payment timeout and dispatches the ordinary idempotent
`CancelRentalPayment` command. Tests can call `cancelOverduePayments(Instant)` with synthetic time; no clock mocking or
sleeping is needed.

This is a deadline projection, not an exact deadline manager:

- Every application instance runs a sweeper. Duplicate commands are harmless here; a production cluster can add leader
  election or a distributed lock when duplicate work is expensive.
- A replay recreates old pending rows. Settled payments self-heal because cancellation is idempotent.
- Precision is the timeout plus or minus the polling interval.
- The repository queries by indexed `preparedAt`; it never loads every row to filter in memory.

## Build

```bash
./mvnw -Pexamples -pl examples/saga-recipes -am clean verify
```

The shared Spring contract matrix runs the same observable scenarios against all five recipe values. It covers the
request, confirmation, rejection, compensation, manual cancellation, projected timeout, late cancellation, and
redelivery paths. Recipe-specific tests additionally verify the repository row and both process-event recording
mechanisms.
