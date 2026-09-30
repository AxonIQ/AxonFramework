# Spring Cloud message distribution examples

This directory contains runnable university-domain applications that demonstrate Axoniq Framework command, query, and
subscription-query distribution through Spring Cloud Discovery. They deliberately do not start or connect to Axon
Server.

## Goal

The examples complement the connector integration tests with a separately runnable topology. A reader can start the
applications, create a course through the portal, and retrieve it again through a distributed query. The response
identifies the handling node, making the HTTP hop visible.

The first implementation is `springboot3`, using Spring Boot 3.5 and Spring Cloud 2025.0. Its Docker Compose topology
contains these processes:

```text
browser or curl
      |
      v
 portal (no Axon handlers) -- Spring Cloud connector --> courses (command and query handlers)
      |                                                   ^
      +------------------- Eureka discovery ------------+
```

All message-participating processes register under the same `university` service id. The connector learns the
members' capabilities through its HTTP endpoint, so the portal, which handles no messages, is not selected as a
handler. `courses` owns an in-memory course catalog for this initial slice; that keeps the example focused on message
distribution rather than event transport or storage.

## Run the Spring Boot 3.5 example

From this directory, build the executable JAR and start the three containers:

```bash
../../mvnw -Pexamples -pl examples/spring-cloud/springboot3 -am package
docker compose -f springboot3/compose.yaml up --build
```

After Eureka, `courses`, and `portal` are healthy, create and query a course through the portal:

```bash
curl -i -X POST http://localhost:8080/courses \
  -H 'Content-Type: application/json' \
  -d '{"courseId":"axon-5","name":"Axon Framework 5"}'

curl -i http://localhost:8080/courses/axon-5
```

Both responses contain `"handledBy":"courses"`. The portal has no command or query handler, so that value is
evidence that both requests were dispatched to the catalog through the Spring Cloud connector. The Eureka dashboard is
available at <http://localhost:8761>.

Stop the example with:

```bash
docker compose -f springboot3/compose.yaml down
```

## Scope and follow-up

The Boot 3.5 example intentionally proves command and point-to-point query distribution first. It does not claim to
distribute events: Spring Cloud discovery is used only for the connector's command and query transports.

The next increments are:

1. Add a `WatchCourse` subscription query to the portal and publish updates from the catalog when a course changes.
2. Add the mirrored `springboot4` module using Spring Boot 4.0 and Spring Cloud 2025.1, preserving the same roles and
   HTTP walkthrough.
3. Decide whether the tutorial should keep the intentionally ephemeral catalog or introduce durable storage and an
   explicit event-sharing mechanism. That is separate from Spring Cloud message distribution.

## Decisions to revisit

Eureka is chosen because it gives the example one locally runnable discovery dependency. The connector itself remains
agnostic: Consul, Kubernetes, or another Spring Cloud Discovery implementation can replace Eureka in a production
application. If the project prefers Consul as its standard local-development registry, replace the example's discovery
layer without changing the university message handlers.

