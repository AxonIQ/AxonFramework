# Multi-Tenancy Demo - Declarative Configuration API

Wires the Axoniq Framework 5.3 tenant-aware components feature through the declarative Configuration
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

`UniversityConfiguration` is the entire configuration a developer writes: it registers one
`TenantComponentProvider` per tenant-scoped component type and registers the command and query handlers
as ordinary handling components. `MultiTenancyApplication` builds a `MessagingConfigurer` from it,
starts it, and hands the gateways and providers to the `DemoLifecycle`.

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

* **Multiple component types.** Every tenant view prints both an enrolment count and an audit-entry
  count, so both providers are injected, each matched by type.
* **Isolation.** Springfield, Shelbyville, and Ogdenville each see only their own enrolments.
* **Replay on startup.** The provider already knows the tenants before the first command.
* **Runtime tenants.** Ogdenville is added while running and its instances appear on its first command.
* **Unknown tenant rejected.** A command for a tenant the application does not know fails with a
  `TenantNotResolvedException`, so no instance is ever built for it.
* **Ambiguity rejected.** Registering two providers for one component type is refused.
* **Cleanup.** Removing a tenant closes its instances, and shutting down closes the rest.

## Against Axon Server

The same lifecycle runs against Axon Server, where each tenant is a real context rather than an entry
in the in-memory tenant provider.

1. Provide a license and start Axon Server as described in the [parent README](../README.md#axon-server).
2. Flip `demo.axon-server.enabled` to `true` in `src/main/resources/application.properties`.
3. Re-run `MultiTenancyApplication#main` (or run `runWithAxonServer` directly).

The tenants are then discovered, not declared: the auto-discovering `AxonServerTenantProvider` watches
Axon Server's contexts and registers each as a tenant, injecting that context's per-tenant components
into the handlers. Its connect predicate filters out the `_admin` context, so the run logs that
`_admin` exists on the server but is not among the tenants. The dashboard at <http://localhost:8024>
shows the tenant contexts appear and disappear as the run adds and removes them. Isolation, replay, the
guardrails, and cleanup all behave exactly as in the in-memory run.

## The same demo, wired by Spring Boot

The [Spring Boot demo](../university-multi-tenancy-springboot/README.md) proves the same behavior with
the same lifecycle, wired through Spring Boot auto-configuration instead of the Configuration
API. Comparing the two is the quickest way to see what each configuration style does and does not need.
