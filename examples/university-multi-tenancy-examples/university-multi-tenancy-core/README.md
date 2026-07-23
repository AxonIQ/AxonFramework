# Multi-Tenancy Demo - Core

The actual multi-tenancy demo: the university model and the lifecycle that exercises it, independent of
how the application is configured. This module is a library, not runnable on its own. The
[declarative](../university-multi-tenancy-declarative/README.md) and
[Spring Boot](../university-multi-tenancy-springboot/README.md) modules run it two ways. For the
overview and what the demo shows, see the [parent README](../README.md).

## Layout

```
org.axonframework.examples.demo.multitenancy
+- university                         the modeled domain
|  +- component                       the tenant-aware components: CourseStatisticsStore and AuditLog (+ in-memory impls)
|  +- write/course                    the event-sourced course: Course, OpenCourse + EnrollStudent, events, handler, wiring
|  +- read/statistics                 the statistics query, its response, and its query handler
+- shared                             the driving utilities both runnable demos use
   +- DemoLifecycle                   the tenant lifecycle the demos walk, top to bottom
   +- TenantComponents                the two TenantComponentProviders
   +- Enrollments                     opens courses, enrolls, and reads statistics through the gateways
   +- EventStorageOutcome             what the per-tenant event-storage isolation observed, asserted by the tests
   +- TenantMetadataFactory           builds the metadata that carries a tenant on a message
   +- RemoteExceptions                recognizes a handler failure whether raised as itself or reconstructed over Axon Server
   +- TenantView                      renders one tenant's isolated view
   +- DemoTenantProvider              an in-memory TenantProvider (the declarative demo's default)
   +- TenantProvisioning              in-memory vs Axon Server tenant provisioning (and whether it isolates event stores)
   +- AxonServerTenantContextManager  creates and deletes Axon Server contexts
   +- ProviderAmbiguityGuardrail      the configuration-time guardrail
   +- DemoOutcome                     what a run observed, asserted by the demos' tests
```

## The lifecycle

`DemoLifecycle.run` reads top to bottom as the story both demos tell, against an already-started
application:

1. Enroll students in the tenants known at startup, and read each tenant's statistics back to show it
   sees only its own. Each enrollment is one command that both appends to the tenant's own event store
   and updates that tenant's components. Against Axon Server the two known tenants open a course under the
   same identifier: one fills it to capacity and a further enrollment is rejected as full, while the same
   identifier still accepts an enrollment in the other tenant, which proves each tenant's events live in
   its own store. In memory there is one shared event store, so the tenants use distinct identifiers and
   this isolation is not shown.
2. Add a tenant at runtime, enroll into it, and show its components appear on first use.
3. Send a command for an unknown tenant and confirm it is rejected.
4. Remove a tenant and confirm its per-tenant instances are closed.
5. Shut down and confirm every remaining tenant's instances are closed.

The configuration-time guardrail (`ProviderAmbiguityGuardrail`) is a separate, standalone check, since
it is about configuration rather than the running lifecycle.

The statistics the enrollment handler updates are an interim read model. Once per-tenant event streaming
lands, they become a projection built from the stored events instead of being written in the command
handler.

## Running

This module is a library. Run the demo through the
[declarative](../university-multi-tenancy-declarative/README.md) demo (in memory, no infrastructure, or
against Axon Server) or the [Spring Boot](../university-multi-tenancy-springboot/README.md) demo
(against Axon Server).
