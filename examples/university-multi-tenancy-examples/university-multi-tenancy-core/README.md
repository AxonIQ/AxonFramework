# Multi-Tenancy Demo - Core

The actual multi-tenancy demo: the university model and the lifecycle that exercises it, independent of
how the application is configured. This module is a library, not runnable on its own. The
[declarative](../university-multi-tenancy-declarative/README.md) and
[Spring Boot](../university-multi-tenancy-springboot/README.md) modules run it two ways. For the
overview and what the demo shows, see the [parent README](../README.md).

## Layout

```
org.axonframework.examples.demo.multitenancy
+- university                         the modeled domain, organized as vertical slices
|  +- UniversityTags                  the shared event tag keys (courseId)
|  +- UniversityModuleConfiguration   registers every slice on an EventSourcingConfigurer
|  +- events                          the domain events: CourseOpened, StudentEnrolledInCourse
|  +- write/opencourse                the open-course slice: command, handler (+ its State entity), wiring
|  +- write/enrollstudent             the enroll-student slice: command, handler (+ its State entity), exceptions, wiring
|  +- read/statistics                 the statistics read slice: query, response, handler, the CourseStatisticsStore read model, wiring
|     +- CourseStatisticsProjection   projects every tenant's events into that tenant's own read model
|     +- ReadModelWrites              the one write both of them use, so either fills it identically
+- shared                             the demo harness both runnable demos use, grouped by what each part does
   +- DemoBacking                     in memory or Axon Server, the one fact the two runs' differences derive from
   +- run                             runs the scenario and reports what it observed
   |  +- DemoApplication              the started application to drive, assembled per backing in one place
   |  +- DemoLifecycle                the tenant lifecycle the demos walk, top to bottom
   |  +- DemoOutcome                  what a run observed, asserted by the demos' tests
   |  +- EventStorageOutcome          what the per-tenant event-storage isolation observed, asserted by the tests
   |  +- SnapshottingOutcome          what the per-tenant snapshot isolation observed, asserted by the tests
   |  +- StreamingOutcome             what the tenant-aware event processing observed, asserted by the tests
   |  +- TenantView                   renders one tenant's isolated view
   |  +- ProviderAmbiguityGuardrail   the configuration-time guardrail
   +- messaging                       drives the command and query gateways
   |  +- Enrollments                  opens courses, enrolls, and reads statistics through the gateways
   |  +- TenantMetadataFactory        builds the metadata that carries a tenant on a message
   |  +- RemoteExceptions             recognizes a handler failure whether raised as itself or reconstructed over Axon Server
   +- tenant                          supplies the tenants and their per-tenant components
   |  +- TenantProvisioning           in-memory vs Axon Server tenant provisioning (and whether it isolates event stores)
   |  +- TenantSnapshots              reads one tenant's own snapshot store, to observe where a snapshot landed
   |  +- DemoTenantProvider           an in-memory TenantProvider (the declarative demo's default)
   |  +- AxonServerTenantContextManager  creates and deletes Axon Server contexts
   |  +- TenantComponents             the two TenantComponentProviders
   +- audit                           the tenant-scoped audit component: AuditLog + its in-memory implementation
```

## The lifecycle

`DemoLifecycle.run` reads top to bottom as the story both demos tell, against an already-started
application:

1. Enroll students in the tenants known at startup, and read each tenant's statistics back to show it
   sees only its own. Each enrollment is one command that appends to the tenant's own event store. Against
   Axon Server the two known tenants open a course under the same identifier: one fills it to capacity and
   a further enrollment is rejected as full, while the same identifier still accepts an enrollment in the
   other tenant, which proves each tenant's events live in its own store. In memory there is one shared
   event store, so the tenants use distinct identifiers and this isolation is not shown.
2. Show where those snapshots ended up. The course carries a snapshot policy, so enrolling the second
   student snapshots it, and the rejected third enrollment sources the course from that snapshot. Against
   Axon Server both tenants end up with their own snapshot of the same course identifier, and each snapshot
   holds only its own tenant's student, which is what proves neither tenant read the other's. It compares
   the snapshots' contents, not the snapshot envelopes: two envelopes never compare equal, since each
   carries its own write timestamp, so comparing envelopes would report a difference even if one tenant had
   read the other's snapshot. A snapshot captures the state its triggering load sourced rather than the
   state that command leaves behind, so each holds one student. In memory every tenant shares one snapshot
   store, so this isolation is not shown.
3. Add a tenant at runtime, enroll into it, and show its components appear on first use. Against Axon
   Server this also shows the running processor picking the tenant up: it re-opens its stream to include a
   tenant that did not exist when it started, and projects that tenant's enrollment.
4. Count what served all three tenants' projections. Against Axon Server there is one streaming event
   processor rather than one per tenant, and each tenant's read model holds only its own enrollments even
   though two of them use the same course identifier. In memory no projection runs, so this is not shown.
5. Send a command for an unknown tenant and confirm it is rejected.
6. Remove a tenant and confirm its per-tenant instances are closed.
7. Shut down and confirm every remaining tenant's instances are closed.

The configuration-time guardrail (`ProviderAmbiguityGuardrail`) is a separate, standalone check, since
it is about configuration rather than the running lifecycle.

## Where the read model comes from

Against Axon Server the statistics are a projection. One ordinary pooled streaming event processor runs
`CourseStatisticsProjection` for every tenant at once, and each event is written into the read model of the
tenant whose event store it came from. The projection is an ordinary event handler: it names no tenant, and
the only thing multi-tenancy adds is that its `@TenantScoped` parameters are resolved for the event's
tenant. Nothing identifies the tenant inside the stored event. The tenant follows from which store the
event was streamed from, and the framework puts it on the processing context of the event being handled.

That makes the read model eventually consistent, so every observation of one in the lifecycle waits for the
projection to catch up.

### The projection is idempotent, on purpose

`ReadModelWrites` is written so that recording the same enrollment twice leaves the read model exactly
as it was. `CourseStatisticsStore` keeps the enrolled student identifiers per course and reports their count,
rather than incrementing a counter, and `AuditLog` records each entry once.

That is not defensive padding. Events are delivered at least once, and on a multi-tenant stream a duplicate
is more likely than usual: adding or removing a tenant re-opens the stream, and the processor cannot always
tell that an event belonging to another tenant was already handled. A counter would then drift upwards on
every tenant change, and the demo's own assertions would start failing for reasons that have nothing to do
with tenants. Deriving the read model from the identifiers in the event is what makes it correct, and it is
the shape any projection on a streamed event should have.

In memory all tenants share one event store, so an event streamed from it cannot be attributed to a tenant
and no projection could tell them apart. That run therefore has the command handler fill the read model
while it still knows the command's tenant. `DemoBacking` is the single fact that decides this, the same fact
`TenantProvisioning` reports, so the two cannot disagree. `ReadModelWrites` is the one write both paths use,
so what the lifecycle observes does not depend on which one filled it.

That inline write is a shortcut, not the shape to copy. It survives only because one shared event store leaves
a streamed event with no tenant to attribute it to. Given per-tenant event stores, prefer the projection.

The enroll-student slice's course entity is an immutable record whose event sourcing handlers return the
evolved course, rather than a mutable class that changes itself. A snapshot is the entity handed to the
`Converter`, and a record's components are exactly the state to capture, so it converts both ways without
any converter-specific annotation. A mutable class whose private fields have no accessors converts to an
empty document against the default converter, and the course then silently comes back blank, which is worth
knowing before adding a snapshot policy to an entity of your own. `CourseSnapshotConversionTest` is what
pins that round trip: the in-memory snapshot store keeps the entity instance as-is, so it never converts
anything and cannot catch the problem.

## Running

This module is a library. Run the demo through the
[declarative](../university-multi-tenancy-declarative/README.md) demo (in memory, no infrastructure, or
against Axon Server) or the [Spring Boot](../university-multi-tenancy-springboot/README.md) demo
(against Axon Server).
