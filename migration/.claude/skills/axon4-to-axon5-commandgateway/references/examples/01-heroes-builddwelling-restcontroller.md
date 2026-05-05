# Heroes (Spring Boot): `BuildDwellingRestApi`

A minimal Spring `@RestController` with one `PUT` endpoint that built an
`AF4` `BuildDwelling` command and dispatched it through `CommandGateway`,
returning a `CompletableFuture<Void>` so Spring served the request
asynchronously. Demonstrates the **single biggest gotcha** for non-handler
callers: AF4's `commandGateway.send(cmd, metadata)` returned
`CompletableFuture<Void>`, but AF5's returns `CommandResult` — so the
`return` line must be rewritten through `.resultAs(Void.class)`.

**Before (AF4):**

```java
package com.dddheroes.heroesofddd.creaturerecruitment.write.builddwelling;

import com.dddheroes.heroesofddd.shared.GameMetaData;
import com.dddheroes.heroesofddd.shared.restapi.Headers;
import org.axonframework.commandhandling.gateway.CommandGateway;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("games/{gameId}")
class BuildDwellingRestApi {

    record Body(String creatureId, Map<String, Integer> costPerTroop) {

    }

    private final CommandGateway commandGateway;

    BuildDwellingRestApi(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    @PutMapping("/dwellings/{dwellingId}")
    CompletableFuture<Void> putDwellings(
            @RequestHeader(Headers.PLAYER_ID) String playerId,
            @PathVariable String gameId,
            @PathVariable String dwellingId,
            @RequestBody Body requestBody
    ) {
        var command = BuildDwelling.command(
                dwellingId,
                requestBody.creatureId(),
                requestBody.costPerTroop()
        );
        return commandGateway.send(command, GameMetaData.with(gameId, playerId));
    }
}
```

**After (AF5):**

```java
package com.dddheroes.heroesofddd.creaturerecruitment.write.builddwelling;

import com.dddheroes.heroesofddd.shared.application.GameMetaData;
import com.dddheroes.heroesofddd.shared.restapi.Headers;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("games/{gameId}")
class BuildDwellingRestApi {

    record Body(String creatureId, Map<String, Integer> costPerTroop) {

    }

    private final CommandGateway commandGateway;

    BuildDwellingRestApi(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    @PutMapping("/dwellings/{dwellingId}")
    CompletableFuture<Void> putDwellings(
            @RequestHeader(Headers.PLAYER_ID) String playerId,
            @PathVariable String gameId,
            @PathVariable String dwellingId,
            @RequestBody Body requestBody
    ) {
        var command = BuildDwelling.command(
                dwellingId,
                requestBody.creatureId(),
                requestBody.costPerTroop()
        );
        return commandGateway.send(command, GameMetaData.with(gameId, playerId))
                             .resultAs(Void.class);
    }
}
```

**Notes:**

- **Single import change** on `CommandGateway`:
  `org.axonframework.commandhandling.gateway.CommandGateway` →
  `org.axonframework.messaging.commandhandling.gateway.CommandGateway`.
  Same interface name, same field type — only the package moves.
- **The `return` line is the substantive change.** AF4's
  `commandGateway.send(cmd, metadata)` returned `CompletableFuture<Void>`
  directly, so a controller method declaring
  `CompletableFuture<Void> putDwellings(...)` could `return` it as-is.
  In AF5 the same call returns `CommandResult` — not assignable to
  `CompletableFuture`. Append `.resultAs(Void.class)` to obtain the
  expected `CompletableFuture<Void>` (or `.getResultMessage().thenApply(m -> null)`
  if you also need the result `Message`).
- The `CommandGateway` field, constructor, and constructor parameter
  are **kept**: this is a top-of-chain caller (REST request → first
  command in the chain, no active `ProcessingContext`), exactly the
  case the AF5 javadoc earmarks for `CommandGateway` rather than
  `CommandDispatcher`.
- The unrelated package move on `GameMetaData` (`shared` →
  `shared.application`) is a separate refactor in this project and is
  **out of scope** for this skill — preserve whatever package the
  helper currently lives in.
- The `GameMetaData.with(...)` helper still imports the AF4
  `org.axonframework.messaging.MetaData`. Its `Metadata` migration is
  **out of scope** for this skill — flag it for the user as a follow-up.
  Without that helper migration the file will not actually compile,
  even though the controller-level rewrite is correct.
