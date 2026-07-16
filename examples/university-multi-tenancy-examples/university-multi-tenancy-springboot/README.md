# Multi-Tenancy Demo - Spring Boot Auto-Configuration

Wires the Axoniq Framework 5.3 tenant-aware components feature through Spring Boot auto-configuration.
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
+- UniversityConfiguration   the beans: the two per-tenant providers and the two handlers
+- DemoRunner                a CommandLineRunner that runs the lifecycle, then stops
```

`UniversityConfiguration` declares one `TenantComponentProvider` bean per tenant-scoped component type
plus the handler beans, and nothing else. The starter's multi-tenancy auto-configuration picks the
provider beans up, subscribes them to the tenant lifecycle, installs the tenant parameter resolver and
interceptor, and registers the default auto-discovering `AxonServerTenantProvider`. There is no manual
multi-tenancy wiring at all: the tenants are discovered from Axon Server's contexts (with `_admin`
filtered out), exactly as in the declarative demo's Axon Server path.

## Requires Axon Server

Tenants are Axon Server contexts, so the multi-tenancy auto-configuration activates only while Axon
Server is enabled. It steps aside when `axon.axonserver.enabled=false`. This demo therefore always runs
against Axon Server, and there is no in-memory mode for it. The in-memory path is the declarative
demo's job.

## Running

1. Provide a license and start Axon Server as described in the [parent README](../README.md#axon-server).
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
context, which exists on the server, is filtered out of the discovered tenants.

Because hosting several tenant contexts needs an Enterprise Edition license, the test mounts one into
the container, expecting it on the test classpath as `axon-server.license`:

* Locally, place your license as `axon-server.license` next to the [parent README](../README.md)
  (git-ignored). The module copies it onto the test classpath.
* In a repository CI run, the examples workflow writes the license from a secret to the same location
  before the build.

When no license is available (a fork PR, whose CI receives no repository secrets, or a clone without
the license file), the test skips itself so the build stays green. It runs in the repository's own CI
and locally, where the license is present. The test needs Docker (for the container). Run it with
`mvn verify` (it runs at the `verify` phase).

`MultiTenancyDisabledTest` proves the disable toggle: with `axon.multitenancy.enabled=false`, the
auto-configuration installs nothing, so the tenant-scoped handler parameter can no longer be resolved
and the application fails to start. That failure is the observable proof that the feature is fully off.
This test needs no Axon Server, so it runs as an ordinary unit test.
