# Heroes (Spring Boot): `GetDwellingByIdRestApi`

A minimal Spring `@RestController` with one `GET` endpoint that
queries a single read-model object by ID via `QueryGateway` and
returns it as `CompletableFuture<DwellingReadModel>`. The simplest
possible AF4 → AF5 case: the AF4 site already used the `Class`-based
overload of `query(...)`, so only the **import** changes; the body is
untouched.

**Before (AF4):**

```java
package com.dddheroes.heroesofddd.creaturerecruitment.read.getdwellingbyid;

import com.dddheroes.heroesofddd.creaturerecruitment.read.DwellingReadModel;
import org.axonframework.queryhandling.QueryGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("games/{gameId}")
class GetDwellingByIdRestApi {

    private final QueryGateway queryGateway;

    GetDwellingByIdRestApi(QueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    @GetMapping("/dwellings/{dwellingId}")
    CompletableFuture<DwellingReadModel> getDwellings(
            @PathVariable String gameId,
            @PathVariable String dwellingId
    ) {
        var query = GetDwellingById.query(dwellingId, gameId);

        return queryGateway.query(
                query,
                DwellingReadModel.class
        );
    }
}
```

**After (AF5):**

```java
package com.dddheroes.heroesofddd.creaturerecruitment.read.getdwellingbyid;

import com.dddheroes.heroesofddd.creaturerecruitment.read.DwellingReadModel;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("games/{gameId}")
class GetDwellingByIdRestApi {

    private final QueryGateway queryGateway;

    GetDwellingByIdRestApi(QueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    @GetMapping("/dwellings/{dwellingId}")
    CompletableFuture<DwellingReadModel> getDwellings(
            @PathVariable String gameId,
            @PathVariable String dwellingId
    ) {
        var query = GetDwellingById.query(gameId, dwellingId);

        return queryGateway.query(
                query,
                DwellingReadModel.class
        );
    }
}
```

**Notes:**

- **Single import change** on `QueryGateway`:
  `org.axonframework.queryhandling.QueryGateway` →
  `org.axonframework.messaging.queryhandling.gateway.QueryGateway`.
  Same interface name, same field type — only the package moves.
- **Body unchanged on the dispatch side.** The AF4 site already used
  the `Class`-based overload of `query(...)` rather than wrapping
  with `ResponseTypes.instanceOf(...)`, so the `return` line is
  byte-for-byte identical post-import-rewrite. This is the simplest
  possible AF4 → AF5 path for `QueryGateway`. Sites that *did* wrap
  with `ResponseTypes.instanceOf(...)` need that wrapper stripped —
  see step 3 of the SKILL.
- **Argument-order swap on the query message** (`query(dwellingId, gameId)`
  → `query(gameId, dwellingId)`) is **out of scope** for this skill.
  It came from a separate refactor of the `GetDwellingById` message
  class in this project. The skill should preserve whatever shape
  the call has when it arrives.
- The `QueryGateway` field, constructor, and constructor parameter
  are **kept**: this is a top-of-chain caller (HTTP request → first
  query in the chain, no active `ProcessingContext`). The same
  decision rule from the command side applies: gateway for top-of-
  chain, dispatcher inside another handler.
