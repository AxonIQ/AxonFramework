# Axoniq Framework Multi-Tenancy Demos

A runnable demonstration of Axoniq Framework's multi-tenancy support, shown two ways: wired through
the declarative Configuration API and through Spring Boot autoconfiguration.

## What the demo shows

A platform hosts several universities. Each is its own tenant, and their data must never mix.
Multi-tenancy lets you register a tenant-scoped component once and have the framework inject the right
tenant's instance into each message handler, so a handler never resolves a tenant itself. The
[core module](university-multi-tenancy-core/README.md) holds that code. The demo shows, at the moment:

* **One enrollment, both features at once.** Enrolling a student is a single event-sourced command whose
  handler sources the `Course` from, and appends to, the tenant's own event store, and updates that
  tenant's `@TenantScoped` read-model components, each resolved from the message's tenant. The realistic
  part is the write side: event sourcing plus tenant-scoped injection in one handler. Updating the
  read-model components from that same handler is a deliberate interim shortcut (see the next bullet).
* **Per-tenant event storage** (Axon Server): because each tenant has its own event store, the same course
  identifier in two tenants is two isolated event streams, so a course full in one tenant still has free
  seats in another. Reading a tenant's events back as a stream, to rebuild the statistics as a projection
  instead of updating them in the handler, is added in a later step. In memory there is one shared event
  store, so this isolation is shown only against Axon Server.
* **Tenant-scoped injection on the read side too**: the statistics query handler is handed the querying
  tenant's own components, matched by type, with the tenant resolved from the message metadata.
* **The tenant lifecycle**: tenants known at startup, a tenant added at runtime, an unknown tenant
  rejected, a tenant removed (closing its instances), and cleanup on shutdown.
* **A configuration-time guardrail**: registering two providers for one component type is refused 
  because the framework cannot know which instance a parameter of that type should receive.
* **Context filtering** (Axon Server): tenants are discovered from Axon Server's contexts, with the
  `_admin` context filtered out so it never becomes a tenant.
* **The disable switch** (Spring Boot): setting `axon.multitenancy.enabled=false` turns the whole
  feature off.
* **Tenant-scoped command distribution** dispatching commands via the tenants own Axon Server context
* **Two configuration styles** produce the same result: the declarative Configuration API and Spring
  Boot autoconfiguration.

## The three modules

| Module | What it adds |
|---|---|
| [`university-multi-tenancy-core`](university-multi-tenancy-core/README.md) | The demo itself: the university model (the event-sourced course with its enrollment command, the statistics query, and the two per-tenant components) and the `DemoLifecycle` that drives it. This is how multi-tenancy works, without any configuration wiring. It is a library, not runnable on its own. |
| [`university-multi-tenancy-declarative`](university-multi-tenancy-declarative/README.md) | Runs the core against the declarative Configuration API wiring. In memory by default, or against Axon Server with a toggle. |
| [`university-multi-tenancy-springboot`](university-multi-tenancy-springboot/README.md) | Runs the core against Spring Boot auto-configuration wiring. Against Axon Server, which is where that auto-configuration activates multi-tenancy. |

Both runnable modules drive the same `DemoLifecycle` from the core, so they prove the same behavior
and differ only in how the application is configured.

## Running

Choose the configuration style you want to learn and follow that module's README for the exact steps.

* Prefer the **declarative Configuration API**? Use the
  [declarative demo](university-multi-tenancy-declarative/README.md). It runs in memory with no
  infrastructure, which is the quickest way to see the feature, and can also run against Axon Server.
* Prefer **Spring Boot autoconfiguration**? Use the
  [Spring Boot demo](university-multi-tenancy-springboot/README.md). It runs against Axon Server.

Anything that runs against Axon Server needs the setup in the [Axon Server](#axon-server) section
below. If you are just here to see multi-tenancy work, the declarative demo in memory is the place to
start.

## Axon Server

The Axon Server paths above share one setup, done here once. Each tenant is its own Axon Server
context, and multiple contexts are an Enterprise feature: without a valid license the server
runs a standalone trial that cannot create the per-tenant contexts the demos provision (it rejects them
with `AXONIQ-1700 Maximum number of contexts reached`).

There are two ways to license the server. Pick the one you have and start matching Docker Compose
profile from this directory. Both expose the dashboard at <http://localhost:8024>. 

### Option 1: license file

Use this if you have an Axon Server license file that allows running disconnected from Axoniq Platform.

1. Place your license file next to this README as `axon-server.license` (it is git-ignored).
2. Run `docker compose --profile license up -d`. The compose file mounts the license into the server.
3. Verify if Axon Server is running with the correct enterprise license by visiting <http://localhost:8024/utilities/license> in your browser.

### Option 2: Axoniq Platform authentication token

Use this if you do not have a license file but do have an Axoniq Platform account. The server connects
to Axoniq Platform, which provides and renews its license automatically. Find your token on the Axon
Server download page:
<https://platform.axoniq.io/resource-center/install/server/download/?version=latest>

1. Put the token in a git-ignored `.env` file next to this README:
   ```properties
   AXONIQ_PLATFORM_AUTHENTICATION="<your-token>"
   ```
   (Or export `AXONIQ_PLATFORM_AUTHENTICATION` in your shell.)
2. Run `docker compose --profile token up -d`.
3. Verify if Axon Server is running with the correct enterprise license by visiting <http://localhost:8024/utilities/license> in your browser.

The server connects to Axoniq Platform under a demo-specific node name
(`university-multitenancy-demo`), so it does not clash with any other Axon Server registered in your
workspace. If the logs show `Node names must be unique` and the server never gets licensed, that name is
already taken in your workspace (usually a leftover from an earlier run that did not shut down cleanly).
Either remove that node from your workspace at <https://platform.axoniq.io/workspace/> or set a
different `AXONIQ_AXONSERVER_NAME` in your `.env` file. A node name is tied to the server's stored data,
so after changing it start fresh with `docker compose --profile token down -v` before `up`.

The declarative demo needs none of this: its default in-memory mode is the quickest way in.
