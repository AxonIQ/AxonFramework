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

From this directory, build the executable JAR and start the three containers in detached mode:

```bash
../../mvnw -Pexamples -pl examples/spring-cloud/springboot3 -am package
docker compose -f springboot3/compose.yaml up --build -d
```

Check that all containers are running:

```bash
docker compose -f springboot3/compose.yaml ps
```

Allow a few seconds for Eureka registration and capability discovery before sending the first message. If a request is
made immediately after startup, repeat it after discovery has converged.

### Manual command and query test

Use a course ID that is unique for this run, since the catalog is in memory. From a terminal, create the course:

```bash
curl -i -X POST http://localhost:8080/courses \
  -H 'Content-Type: application/json' \
  -d '{"courseId":"axon-5","name":"Axon Framework 5"}'

curl -i http://localhost:8080/courses/axon-5
```

Both responses contain `"handledBy":"courses"`. The portal has no command or query handler, so that value is
evidence that both requests were dispatched to the catalog through the Spring Cloud connector. The Eureka dashboard is
available at <http://localhost:8761>.

### Manual subscription-query test

The subscription test requires two terminals. The first terminal opens an SSE connection and must remain running while
the second terminal sends the rename command.

In terminal 1, open the subscription for the course created above:

```bash
curl --no-buffer -H 'Accept: text/event-stream' \
  http://localhost:8080/courses/axon-5/subscription
```

The stream should immediately print an initial event similar to:

```text
event:course
data:{"courseId":"axon-5","name":"Axon Framework 5","handledBy":"courses"}
```

Leave terminal 1 open. In terminal 2, rename the same course:

```bash
curl -i -X PUT http://localhost:8080/courses/axon-5 \
  -H 'Content-Type: application/json' \
  -d '{"name":"Axon Framework 5 - Updated"}'
```

The PUT response should be successful, and terminal 1 should then receive a second event:

```text
event:course
data:{"courseId":"axon-5","name":"Axon Framework 5 - Updated","handledBy":"courses"}
```

This proves the complete subscription path: the portal holds the subscription, the rename command is distributed to
`courses`, `courses` appends and handles `CourseRenamed`, and the query update is distributed back to the portal's open
SSE stream. The SSE request is intentionally long-lived; a curl timeout or stopping it with `Ctrl-C` after both events
have arrived is expected.

For a repeatable run, use a fresh course ID in all four requests. A course created before a container restart is lost
because the catalog is in memory.

### IntelliJ IDEA HTTP client

Open [`springboot3/courses.http`](springboot3/courses.http) in IntelliJ IDEA. Define or select values for:

```text
portalUrl = http://localhost:8080
courseId = axon-5
```

Run the requests in this order:

1. `createCourse`
2. the ordinary GET query
3. `Subscribe to course changes through the portal`, leaving it open
4. `Rename course through the portal` in another HTTP-client tab

The subscription request should show the initial course and, after the rename request, the updated course. The HTTP
client must support streaming responses; if it buffers or closes the request, use the curl commands above instead.

Stop the example with:

```bash
docker compose -f springboot3/compose.yaml down
```

## Scope and follow-up

The Boot 3.5 example proves command, point-to-point query, and subscription-query distribution. The course catalog is
intentionally ephemeral and uses an in-memory projection; the example does not claim to distribute an event store.

The next increments are:

1. Add the mirrored `springboot4` module using Spring Boot 4.0 and Spring Cloud 2025.1, preserving the same roles and
   HTTP walkthrough.
2. Decide whether the tutorial should keep the intentionally ephemeral catalog or introduce durable storage and an
   explicit event-sharing mechanism. That is separate from Spring Cloud message distribution.

## Decisions to revisit

Eureka is chosen because it gives the example one locally runnable discovery dependency. The connector itself remains
agnostic: Consul, Kubernetes, or another Spring Cloud Discovery implementation can replace Eureka in a production
application. If the project prefers Consul as its standard local-development registry, replace the example's discovery
layer without changing the university message handlers.
