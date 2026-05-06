# Heroes (Spring Boot): `GetAllDwellingsMcp` — synchronous framework callback

A Spring `@Component` that exposes a Model Context Protocol (MCP)
resource. The framework integration's resource specification takes a
**synchronous** lambda — `(exchange, request) -> McpSchema.ReadResourceResult` —
so the surrounding code must unwrap the `CompletableFuture<R>`
returned by `queryGateway.query(...)` and produce a concrete value.

This is the **"Synchronous framework callback"** variant of the
skill: the constraint isn't the developer's choice (it isn't a
test, it isn't a CLI runner), it's the integration's signature.

The phase-1 OpenRewrite recipe already moved the `QueryGateway`
import to its AF5 location, and the call site already used the
`Class<R>` overload — so the **only** diff this skill produces is at
the synchronous boundary: bare `.get()` becomes
`.orTimeout(30, TimeUnit.SECONDS).join()`, plus an added
`java.util.concurrent.TimeUnit` import.

**Before (post-recipe, pre-skill):**

```java
package com.dddheroes.heroesofddd.creaturerecruitment.read.getalldwellings;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@Configuration
public class GetAllDwellingsMcp {

    private final QueryGateway queryGateway;
    private final ObjectMapper objectMapper;

    public GetAllDwellingsMcp(QueryGateway queryGateway, ObjectMapper objectMapper) {
        this.queryGateway = queryGateway;
        this.objectMapper = objectMapper;
    }

    @Bean
    public List<McpServerFeatures.SyncResourceSpecification> getAllDwellingsResource() {
        // ... resource metadata elided ...

        var resourceSpecification = new McpServerFeatures.SyncResourceSpecification(
                /* resource */,
                (exchange, request) -> {
                    try {
                        var gameId = extractGameId(request.uri()).orElseThrow(/* ... */);
                        var query = GetAllDwellings.query(gameId);
                        var result = queryGateway.query(query, GetAllDwellings.Result.class).get();
                        // ↑↑↑ bare .get() — blocks indefinitely if the future hangs

                        return new McpSchema.ReadResourceResult(/* format result */);
                    } catch (Exception e) {
                        return new McpSchema.ReadResourceResult(/* error JSON */);
                    }
                }
        );
        return List.of(resourceSpecification);
    }
}
```

**After (skill diff):**

```diff
 import java.util.List;
 import java.util.Map;
 import java.util.Optional;
+import java.util.concurrent.TimeUnit;
```

```diff
                         var query = GetAllDwellings.query(gameId);
-                        var result = queryGateway.query(query, GetAllDwellings.Result.class).get();
+                        var result = queryGateway.query(query, GetAllDwellings.Result.class)
+                                .orTimeout(30, TimeUnit.SECONDS)
+                                .join();
```

That's the entire diff: 1 import added, `.get()` replaced with
`.orTimeout(30, TimeUnit.SECONDS).join()`.

**Notes:**

- **Why `.orTimeout(...).join()` and not `FutureUtils.joinAndUnwrap(...)`.**
  This is consumer code; `FutureUtils` is a framework-internal
  bridge for AF5 modules that must keep AF4-shaped synchronous
  contracts. Application code should default to the standard JDK
  shape — explicit timeout, `CompletionException` surfacing as
  unchecked. The 30-second value matches the project-wide default
  in `.claude/rules/completablefuture-blocking.md`; the user can
  tighten it if the MCP request budget is shorter.
- **The existing `catch (Exception e)` keeps working.**
  `Future.get()` threw checked `InterruptedException` /
  `ExecutionException`; `.join()` throws unchecked
  `CompletionException`. Both inherit from `Exception`, so the
  catch matches. The error path reads `e.getMessage()` — for
  `CompletionException`, that surfaces the underlying cause's
  toString (close enough for an error JSON; users can refine if
  they want a cleaner message).
- **Out of scope for this skill:** the surrounding MCP resource
  metadata, the `extractGameId(...)` URI parsing, and the
  `formatDwellings(...)` JSON serialization. Those are
  application-specific code that survives the migration unchanged.
- **The framework signature is the constraint.** The
  `SyncResourceSpecification` lambda is `(exchange, request) -> ReadResourceResult`
  — a synchronous return. There is no "make it async" option
  without changing to `AsyncResourceSpecification` (different MCP
  feature, different shape), which is out of scope. The skill
  bridges the async/sync gap at the call site and stops there.
- **Pre-flight item 6 is what triggered the diff.** Items 1–5 of
  the pre-flight checklist all passed (import already AF5, no
  `ResponseType` wrappers, no `multipleInstancesOf`, no
  `scatterGather`, no subscription query). Item 6 — "no bare
  blocking call without a timeout" — was the only failure, and it
  produced the only diff.
