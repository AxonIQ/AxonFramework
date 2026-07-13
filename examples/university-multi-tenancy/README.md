# Axon Framework 5 - Multi-Tenancy Demo

Tenant-aware components in Axoniq Framework 5.3.

A SaaS platform hosts several universities. Each is its own tenant, and their data must never
mix. The feature this demo teaches lets you register a tenant-scoped component once and have the
framework hand each message handler the instance belonging to the tenant of the message it is
handling. A handler never resolves a tenant itself.

## The idea

A tenant-aware component is described by two types you write: the component itself, and a
`TenantComponentFactory` saying how to build it for one tenant. You register a
`TenantComponentProvider` per component type. This demo registers two, to show that several
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

```
org.axonframework.examples.demo.multitenancy
+- MultiTenancyApplication          bootstrap that publishes events and prints each tenant's view
+- DemoTenantProvider               supplies the tenants (in memory, standing in for Axon Server)
+- university
   +- UniversityModuleConfiguration registers the providers and the projection
   +- events                        StudentEnrolledInCourse
   +- audit                         AuditLog + InMemoryAuditLog (a second per-tenant component)
   +- read/coursestats              the read side
      +- CourseStatsRepository      the per-tenant component (AutoCloseable)
      +- InMemoryCourseStatsRepository
      +- CourseStatsProjection      the @EventHandler that gets both components injected
```

`MultiTenancyApplication` enrols students by publishing `StudentEnrolledInCourse` events that carry their
tenant. The framework routes each event to the projection with the right tenant's instances
injected, exactly as it would in production.

## Running

From this module's directory:

```
mvn compile exec:java
```

Or run `MultiTenancyApplication#main` from your IDE.

## What to look for

The run walks the whole tenant lifecycle and both guardrails, and the log shows each step:

* **Multiple component types.** Every tenant view prints both an enrolment count and an audit-entry
  count, so both providers are injected, each matched by type.
* **Isolation.** Springfield, Shelbyville, and Ogdenville each see only their own enrolments.
* **Replay on startup.** The provider already knows the tenants before the first event.
* **Runtime tenants.** Ogdenville is added while running and its instances appear on its first event.
* **Unknown tenant rejected.** An enrolment for a tenant the application does not know fails with a
  `TenantNotResolvedException`, so no instance is ever built for it.
* **Ambiguity rejected.** Registering two providers for one component type is refused, because the
  framework cannot know which instance a parameter of that type should receive.
* **Cleanup.** Removing a tenant closes its instances, and shutting down closes the rest. The
  `logback.xml` raises `io.axoniq.framework.messaging.multitenancy` to `DEBUG`, so the subscription
  and per-tenant creation and destruction are visible.

## Against Axon Server

In production each tenant is a real Axon Server context that the `AxonServerTenantProvider`
discovers, and enrolments arrive as routed messages instead of being published directly. That path
builds on per-tenant message routing and lands as this branch grows.
