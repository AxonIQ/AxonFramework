# Multi-Tenancy Demo - Spring Boot Auto-Configuration

Wires the Axoniq Framework tenant-aware components feature through Spring Boot auto-configuration.
For the feature itself and the core module, see the [parent README](../README.md).

Where the [declarative demo](../university-multi-tenancy-declarative/README.md) wires the feature by
hand on a `MessagingConfigurer`, this demo declares a few beans and lets the Axoniq Framework Spring
Boot starter do the wiring. Both run the same lifecycle, so comparing them shows exactly what
each configuration style needs.

## What this module adds

The university model, components, command, query, handlers, and the tenant lifecycle all live in the
[core module](../university-multi-tenancy-core/README.md). This module only adds the Spring Boot
application and its beans:

```
org.axonframework.examples.demo.multitenancy
+- MultiTenancyApplication   the @SpringBootApplication, no multi-tenancy wiring of its own
+- UniversityConfiguration   the beans: providers, handlers, course modules, projection, processor, stores, timeout, query routing
+- DemoRunner                a CommandLineRunner that runs the lifecycle, then stops
```

`UniversityConfiguration` declares one `TenantComponentProvider` bean per tenant-scoped component type,
the statistics query handler, the event-sourced course as two module beans, and the projection processor,
and nothing else. The enrollment command handler is registered with the course, so there is no separate
handler bean for it. The starter's multi-tenancy auto-configuration picks the provider beans up, subscribes
them to the tenant lifecycle, installs the tenant parameter resolver and interceptor, and registers the
default auto-discovering `AxonServerTenantProvider`. The starter also registers the course module beans, so
the course is sourced from and appended to its tenant's own event store, and snapshotted into that tenant's
own snapshot store. There is no manual multi-tenancy wiring at all: the tenants are discovered from Axon
Server's contexts (with `_admin` filtered out), exactly as in the declarative demo's Axon Server path.

The projection processor bean is worth looking at for what it does not say. It names no tenant and declares no
processor per tenant. A single one serves every tenant, because the auto-configuration makes the event store it
streams from tenant-aware and re-opens that stream when the set of tenants changes.

One bean is there purely to show a knob: `MultiTenantStreamingProcessorRestartConfiguration` bounds how long each
processor gets to stop and start when the set of tenants changes. The starter defaults it, so an application only
declares it to raise it, which a deployment with slow-starting processors would.

Another bean turns off preferring a locally subscribed query handler
(`DistributedQueryBusConfiguration.preferLocalQueryHandler(false)`). This whole demo runs in one process,
where a query handler is always subscribed locally, so without this a direct query would never reach the
per-tenant query connector at all. A subscription query always routes through the connector regardless of
this setting.

Two Spring specifics are worth knowing, since neither applies to the declarative demo. The processor is
declared through an `EventProcessorDefinition`, because a `Module` bean holding an event processor is silently
ignored on this path. And the token store bean has to be named exactly `tokenStore`, because Spring resolves a
processor's token store by bean name and fails hard when that name is missing, where the declarative path
resolves by type and falls back to an in-memory store.

## Requires Axon Server

Tenants are Axon Server contexts, so the multi-tenancy auto-configuration activates only while Axon
Server is enabled. It steps aside when `axon.axonserver.enabled=false`. This demo therefore always runs
against Axon Server, and there is no in-memory mode for it. The in-memory path is the declarative
demo's job.

## Running

1. License and start Axon Server (with a license file or an Axoniq Platform token) as described in the
   [parent README](../README.md#axon-server).
2. From this module's directory:

   ```
   mvn spring-boot:run
   ```

   Or run `MultiTenancyApplication#main` from your IDE. The `DemoRunner` runs the lifecycle against the
   server on its default `localhost` address, logs the outcome, and stops the application. The log
   walks the same steps as the declarative demo, so its
   [What to look for](../university-multi-tenancy-declarative/README.md#what-to-look-for) applies here
   too.

## The tests

`MultiTenancyDemoIT` boots the auto-configured application against a real Axon Server started in a
Testcontainers container, and drives the lifecycle. It asserts the same outcome as the
declarative demo, this time proving the auto-configuration path. It also asserts that the `_admin`
context, which exists on the server, is filtered out of the discovered tenants. Because it runs against a
real Axon Server, it also asserts the per-tenant event-storage demonstration: the same course identifier fills
to capacity and rejects a further enrollment in one tenant, while still accepting one in another,
sourced from that tenant's own event store. It asserts the per-tenant snapshot demonstration for the same
reason: both tenants hold their own snapshot of that same course identifier, and each holds only its own
tenant's student.

It also asserts tenant-aware event processing, which likewise needs per-tenant event stores. Exactly one
streaming event processor served all three tenants, and each tenant's read model holds only its own
enrollments even though two of them use the same course identifier. The tenant added while the
application was running is projected too, which only happens if the processor re-opened its stream to
include a tenant that did not exist when it started.

It also asserts tenant-aware subscription queries and the query-side guardrails: both known tenants' own
subscriptions received only their own updates, and only Springfield's, whose course filled up, was completed,
while Shelbyville's kept a free seat and stayed open. A query for an unknown tenant is rejected the same way
an unknown-tenant command is, and so are a query naming no tenant at all and a query for a tenant that has
been removed. Being the Axon Server path, with `preferLocalQueryHandler(false)` set, this is also where a
direct query is proven to actually route through the per-tenant connector rather than being served from a
locally subscribed handler.

Because hosting several tenant contexts needs a licensed Enterprise Edition server, the test licenses
the container in one of two ways, checked in that order:

* **License file** (the path CI uses): the test mounts a license expected on the test classpath as
  `axon-server.license`. Locally, place your license as `axon-server.license` next to the
  [parent README](../README.md). It is git-ignored, and the module copies it onto the test classpath. In
  a repository CI run, the examples workflow writes the license from a secret to the same location before
  the build.
* **Axoniq Platform token** (local fallback): if no license file is present, the test uses the token
  from the `AXONIQ_PLATFORM_AUTHENTICATION` environment variable, or, when that is not set, from the
  `AXONIQ_PLATFORM_AUTHENTICATION` entry in the `.env` file next to the demos. That is the same file
  docker-compose reads, so putting your token in `.env` is enough to run the test, with no shell or IDE
  setup. The test passes the token to the container so it fetches its license from Axoniq Platform. Find
  the token at <https://platform.axoniq.io/resource-center/install/server/download/?version=latest>.

When neither is available (a fork PR, whose CI receives no repository secrets, or a clone with neither
a license file nor a token), the test skips itself so the build stays green. It runs in the
repository's own CI and locally, where the license is present. The test needs Docker (for the
container). Run it with `mvn verify` (it runs at the `verify` phase).

`MultiTenancyDisabledTest` proves the disable toggle. With `axon.multitenancy.enabled=false`, the
auto-configuration installs no tenant resolution, so dispatching a tenant-scoped enrollment fails because
its tenant is never resolved. That failure is the observable proof that the feature is fully off. The
test disables Axon Server as well, so it needs none and runs as an ordinary unit test. Because the course
carries a snapshot policy, `UniversityConfiguration` contributes one shared `SnapshotStore` while
multi-tenancy is off, which is what keeps that configuration runnable: with the feature on, the defaults
supply one per tenant and a store the application registers itself is refused.
