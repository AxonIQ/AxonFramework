# Multi-Tenancy Demo - Declarative Configuration API

Wires the Axoniq Framework tenant-aware components feature through the declarative Configuration
API. For the feature itself and the core module, see the [parent README](../README.md).

This demo runs fully in memory by default, so it needs no infrastructure, and can also run against
Axon Server with a toggle.

## What this module adds

The university model, the two per-tenant components, the command and query with their handlers, and
the tenant lifecycle all live in the [core module](../university-multi-tenancy-core/README.md). This
module only adds the declarative wiring and the runnable application:

```
org.axonframework.examples.demo.multitenancy
+- MultiTenancyApplication   assembles the application and picks the tenant backing
+- UniversityConfiguration   the whole declarative wiring for the feature
+- DemoProperties            reads the demo.axon-server.enabled toggle
```

`UniversityConfiguration` is the entire configuration a developer writes: it registers the event-sourced
course write side, one `TenantComponentProvider` per tenant-scoped component type, and the statistics
query handler. `MultiTenancyApplication` builds an `EventSourcingConfigurer` from it, in memory by default
or against Axon Server with the toggle, starts it, and hands the gateways and providers to the
`DemoLifecycle`. In memory the event store is a single shared store; against Axon Server each tenant has
its own.

That difference decides where the statistics come from. Against Axon Server the configuration also
registers one ordinary pooled streaming processor running `CourseStatisticsProjection`, which builds every
tenant's statistics from the stored events. Making that processor tenant-aware takes no wiring at all: the
declaration names no tenant, and the multi-tenancy defaults make the event store it streams from
tenant-aware. In memory a shared event store leaves a streamed event with no tenant to attribute it to, so
that run registers no projection and has the command handler fill the read model instead. That inline write is a
shortcut, not the shape to copy: given per-tenant event stores, prefer the projection.

The course carries a snapshot policy, so a `SnapshotStore` is needed too. In memory `UniversityConfiguration`
registers one shared `InMemorySnapshotStore`. Against Axon Server it registers none: the multi-tenancy
defaults own that registration so each tenant's snapshots land in its own context, and a store the
application registers itself is refused.

## Running

From this module's directory:

```
mvn compile exec:java
```

Or run `MultiTenancyApplication#main` from your IDE. By default this runs the in-memory version.
`MultiTenancyDemoTest` runs the same lifecycle in memory as a smoke test, and separately asserts the
configuration-time guardrail.

## What to look for

The run walks the whole tenant lifecycle (the behaviors listed in the
[parent README](../README.md#what-the-demo-shows)), and the log shows each step:

* **Multiple component types.** Every tenant view prints both an enrollment count and an audit-entry
  count, so both providers are injected, each matched by type.
* **Isolation.** Springfield, Shelbyville, and Ogdenville each see only their own enrollments.
* **Subscription-query isolation.** Springfield's and Shelbyville's own subscriptions, opened before
  either enrolls a student, each receive only their own tenant's updates.
* **Subscription completion per tenant.** Springfield runs out of seats, so its own subscription is
  completed, while Shelbyville keeps a free seat and its subscription stays open.
* **Replay on startup.** The provider already knows the tenants before the first command.
* **Runtime tenants.** Ogdenville is added while running and its instances appear on its first command.
* **Unknown tenant rejected.** A command, and then a query, for a tenant the application does not know
  each fail with a `TenantNotResolvedException`, so no instance is ever built for it. A query carrying no
  tenant metadata at all, and a query for a tenant that has been removed, fail the same way.
* **Ambiguity rejected.** Registering two providers for one component type is refused.
* **Cleanup.** Removing a tenant closes its instances, and shutting down closes the rest.

## Against Axon Server

The same lifecycle runs against Axon Server, where each tenant is a real context rather than an entry
in the in-memory tenant provider.

1. License and start Axon Server (with a license file or an Axoniq Platform token) as described in the
   [parent README](../README.md#axon-server).
2. Flip `demo.axon-server.enabled` to `true` in `src/main/resources/application.properties`.
3. Re-run `MultiTenancyApplication#main` (or run `runWithAxonServer` directly).

The tenants are then discovered, not declared: the auto-discovering `AxonServerTenantProvider` watches
Axon Server's contexts and registers each as a tenant, injecting that context's per-tenant components
into the handlers. Its connect predicate filters out the `_admin` context, so the run logs that
`_admin` exists on the server but is not among the tenants. The dashboard at <http://localhost:8024>
shows the tenant contexts appear and disappear as the run adds and removes them. Component isolation,
replay, the guardrails, and cleanup all behave exactly as in the in-memory run.

The Axon Server run additionally shows per-tenant event storage, which the in-memory run cannot because
it has one shared event store. The two known tenants open a course under the same identifier: one fills
it and rejects a further enrollment as full, while the same identifier still accepts one in the other
tenant, because each tenant's events are sourced from its own store.

It also shows per-tenant snapshotting. Enrolling the second student crosses the course's snapshot
threshold, and each tenant's snapshot goes to its own snapshot store. Both tenants therefore hold a
snapshot of the same course identifier, and each holds only its own tenant's student. The in-memory run
snapshots into one store shared by every tenant, so it cannot show the isolation.

It also registers a `MultiTenantStreamingProcessorRestartConfiguration`, which is the only thing about tenant-aware
event processing an application configures. A tenant change restarts the running processors, and this bounds how
long each one gets to stop and start. It is registered at the value the framework already defaults to, so the
run behaves identically and the point is only to show where the knob is.

Against Axon Server, the demo also turns off preferring a locally subscribed query handler
(`DistributedQueryBusConfiguration.preferLocalQueryHandler(false)`). This whole demo runs in one process,
where a query handler is always subscribed locally, so without this a direct query would never reach the
per-tenant query connector at all: tenant resolution would only ever happen once handling starts, never at
dispatch. A subscription query always routes through the connector regardless of this setting.

It shows tenant-aware event processing too. The log reports how many streaming event processors served
all three tenants, and the answer is one rather than one per tenant. The two known tenants hold the same
course identifier, so their separate enrollment counts show that single processor keeping their read models
apart. Ogdenville, added while the application is running, is picked up
without any configuration change: the framework re-opens the stream to include it, and its enrollment is
projected into its own read model. Because the read model now trails the command that appended the event,
the run waits for the projection to catch up before reading it.

Finally, it shows tenant-aware subscription queries. Both known tenants subscribe to their own statistics
before either enrolls a student, and recording an enrollment emits an update through a deliberately
tenant-blind predicate. The log reports the enrollment totals each subscription saw: exactly its own tenant's
enrollments arriving one at a time, never inflated by the other tenant's activity, which is what proves the
framework isolates emission by the tenant it resolves for the update rather than by the predicate.

Completion is scoped the same way, through an equally tenant-blind predicate. Springfield runs out of seats, so
its own subscription is completed, while Shelbyville opened its course with a seat to spare and its subscription
stays open. A subscription reports the whole tenant, so it completes only once none of that tenant's courses has
a seat left. A query for an unknown tenant is rejected the same way an unknown-tenant command is,
and so are a query naming no tenant at all and a query for a tenant that has been removed.

## The same demo, wired by Spring Boot

The [Spring Boot demo](../university-multi-tenancy-springboot/README.md) proves the same behavior with
the same lifecycle, wired through Spring Boot auto-configuration instead of the Configuration
API. Comparing the two is the quickest way to see what each configuration style does and does not need.
