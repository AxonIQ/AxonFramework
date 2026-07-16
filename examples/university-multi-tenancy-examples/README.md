# Axoniq Framework Multi-Tenancy Demos

A runnable demonstration of Axoniq Framework 5.3's multi-tenancy support, shown two ways: wired through
the declarative Configuration API and through Spring Boot auto-configuration.

## What the demo shows

A platform hosts several universities. Each is its own tenant, and their data must never mix.
Multi-tenancy lets you register a tenant-scoped component once and have the framework inject the right
tenant's instance into each message handler, so a handler never resolves a tenant itself. The
[core module](university-multi-tenancy-core/README.md) holds that code. The demo shows, at the moment:

* **Tenant-scoped component injection** into command and query handlers, each component matched by
  type, with the tenant resolved from the message metadata.
* **The tenant lifecycle**: tenants known at startup, a tenant added at runtime, an unknown tenant
  rejected, a tenant removed (closing its instances), and cleanup on shutdown.
* **A configuration-time guardrail**: registering two providers for one component type is refused,
  because the framework cannot know which instance a parameter of that type should receive.
* **Context filtering** (Axon Server): tenants are discovered from Axon Server's contexts, with the
  `_admin` context filtered out so it never becomes a tenant.
* **The disable switch** (Spring Boot): setting `axon.multitenancy.enabled=false` turns the whole
  feature off.
* **Two configuration styles** producing the same result: the declarative Configuration API and Spring
  Boot auto-configuration.

## The three modules

| Module | What it adds |
|---|---|
| [`university-multi-tenancy-core`](university-multi-tenancy-core/README.md) | The demo itself: the university model (enrolment command, statistics query, their handlers, the two per-tenant components) and the `DemoLifecycle` that drives it. This is how multi-tenancy works, without any configuration wiring. It is a library, not runnable on its own. |
| [`university-multi-tenancy-declarative`](university-multi-tenancy-declarative/README.md) | Runs the core against the declarative Configuration API wiring. In memory by default, or against Axon Server with a toggle. |
| [`university-multi-tenancy-springboot`](university-multi-tenancy-springboot/README.md) | Runs the core against Spring Boot auto-configuration wiring. Against Axon Server, which is where that auto-configuration activates multi-tenancy. |

Both runnable modules drive the same `DemoLifecycle` from the core, so they prove the same behavior
and differ only in how the application is configured.

## Running

Choose the configuration style you want to learn, and follow that module's README for the exact steps.

* Prefer the **declarative Configuration API**? Use the
  [declarative demo](university-multi-tenancy-declarative/README.md). It runs in memory with no
  infrastructure, which is the quickest way to see the feature, and can also run against Axon Server.
* Prefer **Spring Boot auto-configuration**? Use the
  [Spring Boot demo](university-multi-tenancy-springboot/README.md). It runs against Axon Server.

Anything that runs against Axon Server needs the setup in the [Axon Server](#axon-server) section
below. If you are just here to see multi-tenancy work, the declarative demo in memory is the place to
start.

## Axon Server

The Axon Server paths above share one setup, done here once. Each tenant is its own Axon Server
context, and multiple contexts are an Enterprise Edition feature: without a license the server runs a
standalone trial that cannot create the per-tenant contexts the demos provision (it rejects them with
`AXONIQ-1700 Maximum number of contexts reached`). To provide a license and start the server:

1. Place your license file next to this README as `axon-server.license` (it is git-ignored).
2. Run `docker compose up -d` from this directory. The `docker-compose.yaml` uses the Enterprise
   Edition image, mounts the license into it, and exposes the dashboard at <http://localhost:8024>.

The declarative demo needs none of this: its default in-memory mode is the quickest way in.
