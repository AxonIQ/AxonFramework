# Axoniq Framework Multi-Tenancy Demo

Tenant-aware components in Axoniq Framework 5.3.

A platform hosts several universities. Each is its own tenant, and their data must never mix. The
feature this demo teaches lets you register a tenant-scoped component once and have the framework
hand each message handler the instance belonging to the tenant of the message it is handling. A
handler never resolves a tenant itself.

## The idea

A tenant-aware component is described by two types you write: the component itself, and a
`TenantComponentFactory` saying how to build it for one tenant. You register a
`TenantComponentProvider` per component type. This demo registers two to show that several
tenant-scoped types coexist and are each matched to a handler parameter by its own type:

* `CourseStatsRepository`, a per-tenant read model of enrolment counts.
* `AuditLog`, a per-tenant audit trail.

A handler simply declares the types it needs, and each is injected for the message's tenant:

```java
@EventHandler
public void on(StudentEnrolledInCourse event, CourseStatsRepository statistics, AuditLog auditLog) {
    statistics.recordEnrolment(event.courseId());       // the current tenant's repository
    auditLog.record("enrolled " + event.studentId());   // the current tenant's audit log
}
```

`UniversityModuleConfiguration` does the wiring: it registers one provider per type and the
projection as an ordinary subscribing event handler. That is the entire configuration for the
feature.

## How the demo is built

Start with `MultiTenancyApplication`: its `runLifecycle` reads top to bottom as the story the demo
tells. The `university` package is the feature being multi-tenanted.

Everything in `scaffolding` is exactly that: scaffolding. Like the temporary structure builders put
up around a building, it is not the thing being built. It is the supporting machinery that runs and
shows the demo (providing the tenants, publishing enrolments, rendering each tenant's view, reading
the toggle, capturing the outcome), kept out of the way so `MultiTenancyApplication` and `university`
stay the focus of this demo.

```
org.axonframework.examples.demo.multitenancy
+- MultiTenancyApplication          the lesson: walks the tenant lifecycle top to bottom
+- university                       the feature being multi-tenanted
|  +- UniversityModuleConfiguration registers the providers and the projection
|  +- events                        StudentEnrolledInCourse
|  +- audit                         AuditLog + InMemoryAuditLog (a second per-tenant component)
|  +- read/coursestats              the read side
|     +- CourseStatsRepository      the per-tenant component (AutoCloseable)
|     +- InMemoryCourseStatsRepository
|     +- CourseStatsProjection      the @EventHandler that gets both components injected
+- scaffolding                      demo plumbing, not the lesson
   +- TenantProvisioning            how a run provisions tenants: the only difference between the two runs
   +- DemoTenantProvider            supplies the tenants in memory (the default run)
   +- AxonServerTenantContexts      creates and deletes tenant contexts (the Axon Server run)
   +- Enrolments                    publishes enrolment events and reads them back
   +- TenantView                    renders one tenant's isolated view
   +- ProviderAmbiguityGuardrail    the configuration-time guardrail
   +- ConfigurationProperties       reads the axon.server.enabled toggle
   +- DemoOutcome                   what a run observed, asserted by the smoke test
```

`MultiTenancyApplication` enrols students by publishing `StudentEnrolledInCourse` events that carry their
tenant. The framework routes each event to the projection with the right tenant's instances
injected, exactly as it would in production.

## Running

From this module's directory:

```
mvn compile exec:java
```

Or run `MultiTenancyApplication#main` from your IDE. By default, this runs the in-memory version. See
[Against Axon Server](#against-axon-server) to run the same lifecycle against Axon Server.

## What to look for

The run walks the whole tenant lifecycle and both guardrails, and the log shows each step:

* **Multiple component types.** Every tenant view prints both an enrolment count and an audit-entry
  count, so both providers are injected, each matched by type.
* **Isolation.** Springfield, Shelbyville, and Ogdenville each see only their own enrolments.
* **Replay on startup.** The provider already knows the tenants before the first event.
* **Runtime tenants.** Ogdenville is added while running and its instances appear on its first event.
* **Unknown tenant rejected.** An enrolment for a tenant the application does not know fails with a
  `TenantNotResolvedException`, so no instance is ever built for it.
* **Ambiguity rejected.** Registering two providers for one component type is refused because the
  framework cannot know which instance a parameter of that type should receive.
* **Cleanup.** Removing a tenant closes its instances, and shutting down closes the rest. The
  `logback.xml` raises `io.axoniq.framework.messaging.multitenancy` to `DEBUG`, so the subscription
  and per-tenant creation and destruction are visible.

## Against Axon Server

1. Place a licensed Axon Server's license file next to this README as `axon-server.license`
   (see [Requirements](#requirements) below). The `docker-compose.yaml` already mounts it.
2. `docker compose up -d` in this module's directory.
3. Flip `axon.server.enabled` to `true` in `src/main/resources/application.properties`.
4. Re-run `MultiTenancyApplication#main` (or run `runWithAxonServer` directly).

Each tenant is then a real Axon Server context that the `AxonServerTenantProvider` sources, rather
than an entry in the in-memory `DemoTenantProvider`. The dashboard at <http://localhost:8024> shows
the tenant contexts appear and disappear as the run adds and removes them.

The feature this demonstrates is where the tenants come from: the `AxonServerTenantProvider` treats
each configured context as a tenant, and the framework injects each context's per-tenant components
into the projection. Enrolments still flow through the same `SimpleEventBus` carrying their tenant in
metadata, so isolation, replay, the guardrails, and cleanup all behave exactly as in the in-memory
run. Per-tenant routing of the events themselves through Axon Server lands with later work on this
feature.

What the run does differently:

* **Predefined tenants.** `springfield` and `shelbyville` are passed to the provider as predefined
  contexts and created in Axon Server before it starts.
* **Runtime tenant.** `ogdenville` is added by creating its context through the Admin API and
  registering it on the provider, so its instances appear on its first event.
* **Tenant removal.** Removing `shelbyville` deregisters it and deletes its context.

### Requirements

Each tenant is its own context, and multiple contexts are an Enterprise Edition feature that needs a
licensed Axon Server. The `docker-compose.yaml` here uses the Enterprise Edition image, which without
a license runs a trial in standalone mode. That trial lets the server run, but it cannot create the
per-tenant contexts this run provisions (it rejects them with `AXONIQ-1700 Maximum number of contexts
reached`).

To run this path, provide a license: place your license file next to this README as
`axon-server.license` (it is git-ignored). The `docker-compose.yaml` mounts that file into the
container, so `docker compose up -d` fails if it is missing.

Without a license, the demo still runs fully in memory, which is the default.
