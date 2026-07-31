# Axoniq Framework Multi-Tenancy Demos

A runnable demonstration of Axoniq Framework's multi-tenancy support, shown two ways: wired through
the declarative Configuration API and through Spring Boot autoconfiguration.

## What the demo shows

A platform hosts several universities. Each is its own tenant, and their data must never mix.
Multi-tenancy lets you register a tenant-scoped component once and have the framework inject the right
tenant's instance into each message handler, so a handler never resolves a tenant itself, whether it
handles a command, a query, or an event. The [core module](university-multi-tenancy-core/README.md) holds
that code. The demo shows, at the moment:

* **One enrollment, event-sourced per tenant.** Enrolling a student is a single command whose handler
  sources the `Course` from, and appends to, the tenant's own event store, without naming a tenant itself.
* **Per-tenant event storage** (Axon Server): because each tenant has its own event store, the same course
  identifier in two tenants is two isolated event streams, so a course full in one tenant still has free
  seats in another. In memory there is one shared event store, so this isolation is shown only against
  Axon Server.
* **Tenant-aware event processing** (Axon Server): one ordinary pooled streaming event processor consumes
  every tenant's events and writes each into that tenant's own read model. There is no processor and no
  token store per tenant, and nothing in the processor declaration mentions a tenant. Nothing identifies
  the tenant inside the stored event either: the tenant follows from which store the event was streamed
  from, and the framework puts it on the processing context so the handler's `@TenantScoped` parameters
  resolve to that tenant. Adding a tenant at runtime needs no configuration change, since the framework
  re-opens the stream to include it. In memory a shared event store leaves a streamed event with no tenant
  to attribute it to, so that run fills the read model from the command handler instead. That is a shortcut, not
  the shape to copy: given per-tenant event stores, prefer the projection.
* **The one knob there is** (Axon Server): a tenant change restarts the running processors, and each restart is
  bounded by a timeout. The framework defaults it, and both demos show where an application would raise it for a
  deployment whose processors are slow to stop and start.
* **An idempotent projection**, which a streamed read model has to be. Events arrive at least once, and
  re-opening the stream on a tenant change makes a repeat more likely than usual, so the statistics are
  derived from the student identifiers in the events rather than counted. Handling the same enrollment twice
  leaves the read model unchanged.
* **Per-tenant snapshotting** (Axon Server): the course carries a snapshot policy, and each tenant's
  snapshots live in that tenant's own context, so the same course identifier in two tenants is two
  unrelated snapshots. A snapshot is a performance optimization, so no behavior reveals which tenant's
  store it landed in. The demo therefore reads the per-tenant snapshot stores directly: both tenants hold
  their own snapshot of that identifier, and each holds only its own tenant's student. It compares snapshot
  contents rather than snapshot envelopes, since two envelopes never compare equal. In memory every tenant
  shares one snapshot store, so this isolation is not shown.
* **Tenant-scoped injection on the read side too**: the statistics query handler is handed the querying
  tenant's own components, matched by type, with the tenant resolved from the message metadata.
* **Tenant-aware subscription queries**: both tenants known at startup subscribe to their own statistics
  before enrolling a single student. Emitting an update, and completing the subscription once a tenant runs out
  of seats, both use a deliberately tenant-blind predicate, and each tenant still only ever sees its own. The
  framework scopes both to the tenant it resolves for the message, not to anything the predicate says.
* **Direct queries routed through the per-tenant connector** (Axon Server): the demos turn off preferring a
  locally subscribed handler, since in one process a direct query would otherwise never reach the connector.
  That is about what the demo can show, not about correctness: the tenant is checked before dispatch either way.
* **The tenant lifecycle**: tenants known at startup, a tenant added at runtime, a tenant removed (closing its
  instances), and cleanup on shutdown. A command or query whose tenant the framework cannot resolve is rejected
  before it reaches a handler, whether that tenant is unknown, removed, or not named at all.
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
