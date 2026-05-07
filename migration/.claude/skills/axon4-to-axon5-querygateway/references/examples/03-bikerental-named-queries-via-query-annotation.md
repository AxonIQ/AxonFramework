# Example 03 — bike-rental named queries via `@Query`-annotated payload class

Project: `axon4-to-5-migration-workshop` (bike-rental demo). Two
modules — `rental` and `payment` — each had a Spring
`@RestController` calling `queryGateway.query(...)` against a
**named** query. AF5 removed the named-query overload, so both call
sites needed the same architectural rewrite: the AF4 query name
moved onto the payload class via `@Query(name = "...")`, and the
dispatch became plain `query(payload, R.class)` /
`queryMany(payload, R.class)`. No `GenericQueryMessage` wrapping
anywhere.

The two cases differ by **payload shape**, which is the decision
point this example exists to capture:

- `rental` already had dedicated payload records (`FindOneBike`,
  `FindAllBikes`). Migration was annotation-only on the existing
  classes; handler signatures stayed unchanged.
- `payment` dispatched bare scalars (`String paymentId`,
  `String paymentReference`, `PaymentStatus.Status status`).
  Migration introduced new `@Query`-annotated records and updated
  the matching `@QueryHandler` method parameters.

## Case A — payload classes already exist (`rental`)

### Before

`rental/.../query/FindOneBike.java` — plain record:

```java
package io.axoniq.demo.bikerental.query;

public record FindOneBike(String bikeId) {}
```

`rental/.../query/FindAllBikes.java`:

```java
package io.axoniq.demo.bikerental.query;

public record FindAllBikes() {}
```

`rental/.../ui/RentalController.java` — AF4 named-query dispatch:

```java
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.axonframework.messaging.responsetypes.ResponseTypes;

@GetMapping("/bikes")
public CompletableFuture<List<BikeStatus>> findAll() {
    return queryGateway.query("findAll",
                              new FindAllBikes(),
                              ResponseTypes.multipleInstancesOf(BikeStatus.class));
}

@GetMapping("/bikes/{bikeId}")
public CompletableFuture<BikeStatus> findStatus(@PathVariable("bikeId") String bikeId) {
    return queryGateway.query("findOne",
                              new FindOneBike(bikeId),
                              BikeStatus.class);
}
```

`rental/.../query/BikeStatusProjection.java` — AF4 handlers (`@QueryHandler(queryName = ...)`):

```java
@QueryHandler(queryName = "findAll")
public List<BikeStatus> findAll(FindAllBikes findAllBikes) { ... }

@QueryHandler(queryName = "findOne")
public BikeStatus findOne(FindOneBike query) { ... }
```

### After

Annotate the existing payload classes — that's the only payload-side
change:

```java
package io.axoniq.demo.bikerental.query;

import org.axonframework.messaging.queryhandling.annotation.Query;

@Query(name = "findOne")
public record FindOneBike(String bikeId) {}
```

```java
package io.axoniq.demo.bikerental.query;

import org.axonframework.messaging.queryhandling.annotation.Query;

@Query(name = "findAll")
public record FindAllBikes() {}
```

Dispatch loses the leading string and (for the multi-response form)
moves to `queryMany`:

```java
@GetMapping("/bikes")
public CompletableFuture<List<BikeStatus>> findAll() {
    return queryGateway.queryMany(new FindAllBikes(), BikeStatus.class);
}

@GetMapping("/bikes/{bikeId}")
public CompletableFuture<BikeStatus> findStatus(@PathVariable("bikeId") String bikeId) {
    return queryGateway.query(new FindOneBike(bikeId), BikeStatus.class);
}
```

The `BikeStatusProjection` handlers are **unchanged**. Their
`@QueryHandler(queryName = "findAll")` / `@QueryHandler(queryName =
"findOne")` annotations register under the same `QualifiedName` that
`@Query(name = "findAll")` / `@Query(name = "findOne")` produce on
the dispatch side, so the routing matches.

## Case B — bare-scalar payload, must introduce a new record (`payment`)

### Before

`payment/.../PaymentController.java` — AF4 dispatched `String` /
enum payloads with the query name supplied as the first argument:

```java
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.axonframework.messaging.responsetypes.ResponseTypes;

@GetMapping("/status/{paymentId}")
public CompletableFuture<PaymentStatus> getStatus(@PathVariable("paymentId") String paymentId) {
    return queryGateway.query("getStatus", paymentId, PaymentStatus.class);
}

@GetMapping("/findPayment")
public CompletableFuture<String> findPaymentId(@RequestParam("reference") String paymentReference) {
    return queryGateway.query("getPaymentId", paymentReference, String.class);
}

@GetMapping("/status")
public CompletableFuture<List<PaymentStatus>> getStatus(
        @RequestParam(value = "status", required = false) PaymentStatus.Status status) {
    return queryGateway.query("getAllPayments",
                              status,
                              ResponseTypes.multipleInstancesOf(PaymentStatus.class));
}
```

`payment/.../status/PaymentStatusProjection.java` — AF4 handlers
took the bare scalars directly:

```java
@QueryHandler(queryName = "getStatus")
public PaymentStatus getStatus(String paymentId) { ... }

@QueryHandler(queryName = "getPaymentId")
public String getPaymentId(String paymentReference) { ... }

@QueryHandler(queryName = "getAllPayments")
public Iterable<PaymentStatus> findByStatus(PaymentStatus.Status status) { ... }

@QueryHandler(queryName = "getAllPayments")
public Iterable<PaymentStatus> findAll() { ... }
```

### After

Introduce one `@Query`-annotated record per AF4 query name:

```java
package io.axoniq.demo.bikerental.payment.queries;

import org.axonframework.messaging.queryhandling.annotation.Query;

@Query(name = "getStatus")
public record GetPaymentStatusQuery(String paymentId) {}
```

```java
package io.axoniq.demo.bikerental.payment.queries;

import org.axonframework.messaging.queryhandling.annotation.Query;

@Query(name = "getPaymentId")
public record GetPaymentIdQuery(String paymentReference) {}
```

```java
package io.axoniq.demo.bikerental.payment.queries;

import io.axoniq.demo.bikerental.payment.status.PaymentStatus;
import org.axonframework.messaging.queryhandling.annotation.Query;

@Query(name = "getAllPayments")
public record GetAllPaymentsQuery(PaymentStatus.Status status) {}
```

Dispatch site moves to plain `query(payload, R.class)` /
`queryMany(payload, R.class)`:

```java
@GetMapping("/status/{paymentId}")
public CompletableFuture<PaymentStatus> getStatus(@PathVariable("paymentId") String paymentId) {
    return queryGateway.query(new GetPaymentStatusQuery(paymentId), PaymentStatus.class);
}

@GetMapping("/findPayment")
public CompletableFuture<String> findPaymentId(@RequestParam("reference") String paymentReference) {
    return queryGateway.query(new GetPaymentIdQuery(paymentReference), String.class);
}

@GetMapping("/status")
public CompletableFuture<List<PaymentStatus>> getStatus(
        @RequestParam(value = "status", required = false) PaymentStatus.Status status) {
    return queryGateway.queryMany(new GetAllPaymentsQuery(status), PaymentStatus.class);
}
```

**Coupled handler edit** — because the dispatch payload type
changed, the matching `@QueryHandler` parameter types had to change
too. This is the in-scope handler edit called out in section 3a:

```java
@QueryHandler(queryName = "getStatus")
public PaymentStatus getStatus(GetPaymentStatusQuery query) {
    return paymentStatusRepository.findById(query.paymentId()).orElse(null);
}

@QueryHandler(queryName = "getPaymentId")
public String getPaymentId(GetPaymentIdQuery query) {
    return paymentStatusRepository.findByReferenceAndStatus(query.paymentReference(), PENDING)
                                  .map(PaymentStatus::getId)
                                  .orElse(null);
}

@QueryHandler(queryName = "getAllPayments")
public Iterable<PaymentStatus> findByStatus(GetAllPaymentsQuery query) {
    if (query.status() == null) {
        return paymentStatusRepository.findAll();
    }
    return paymentStatusRepository.findAllByStatus(query.status());
}
```

The AF4 `findAll()` no-arg overload that also lived under
`queryName = "getAllPayments"` was folded into `findByStatus` (the
single AF5 handler now branches on `query.status() == null`). AF5
disallows two `@QueryHandler` methods registered under the same
`QualifiedName` in the same component, so two-into-one is the only
correct collapse here — and it falls out naturally because
`GetAllPaymentsQuery.status()` is the optional discriminator.

## Anti-pattern (do NOT do this)

The wrong migration that this example exists to prevent:

```java
// WRONG — never construct GenericQueryMessage at the dispatch site
return queryGateway.query(
        new GenericQueryMessage(new MessageType("getStatus"), paymentId),
        PaymentStatus.class);
```

This compiles, this routes, but it scatters the query name "getStatus"
across every dispatch site instead of centralising it on the payload
class. Every future rename or renumber of the query becomes an N-site
edit. The `@Query`-annotated record above is the only correct shape.

## Why this is an example, not a rule in the procedure

The procedure section (3a) covers the abstract rule. This file
captures the **decision point** that is hard to compress into a
rule: when to annotate vs. when to introduce a record. The signal
is the AF4 payload type — class-shaped vs scalar-shaped — and the
two halves of this example show both branches against the same
overall AF4 named-query pattern.
