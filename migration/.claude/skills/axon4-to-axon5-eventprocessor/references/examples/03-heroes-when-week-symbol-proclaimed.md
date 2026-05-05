# Heroes (Spring Boot): `WhenWeekSymbolProclaimedThenIncreaseDwellingAvailableCreaturesProcessor`

A Spring `@Component` event-processor that:

1. Reacts to `WeekSymbolProclaimed` and dispatches one
   `IncreaseAvailableCreatures` command per matching dwelling — the
   dispatch lives **inside a loop** in a private helper method.
2. Reacts to `DwellingBuilt` to build/update a JPA-backed read model —
   pure projection, no command dispatch on this handler.

This is the canonical "loop of `sendAndWait`" shape. The AF5 migration
demonstrates the **`CompletableFuture.allOf` aggregation pattern** for
keeping the loop non-blocking, and shows the **last-resort blocking
form** with the correct `.getResultMessage().orTimeout(...).join()`
chain for cases where async aggregation is genuinely impractical.

**Before (AF4):**

```java
package com.dddheroes.heroesofddd.astrologers.automation.whenweeksymbolproclaimedthenincreasedwellingavailablecreatures;

import com.dddheroes.heroesofddd.astrologers.events.WeekSymbolProclaimed;
import com.dddheroes.heroesofddd.creaturerecruitment.events.DwellingBuilt;
import com.dddheroes.heroesofddd.creaturerecruitment.write.changeavailablecreatures.IncreaseAvailableCreatures;
import com.dddheroes.heroesofddd.shared.application.GameMetaData;
import org.axonframework.commandhandling.gateway.CommandGateway;
import org.axonframework.config.ProcessingGroup;
import org.axonframework.eventhandling.DisallowReplay;
import org.axonframework.eventhandling.EventHandler;
import org.axonframework.messaging.annotation.MetaDataValue;
import org.springframework.stereotype.Component;

@ProcessingGroup("ReadModel_Dwelling")
@DisallowReplay
@Component
class WhenWeekSymbolProclaimedThenIncreaseDwellingAvailableCreaturesProcessor {

    private final CommandGateway commandGateway;
    private final BuiltDwellingReadModelRepository repository;

    WhenWeekSymbolProclaimedThenIncreaseDwellingAvailableCreaturesProcessor(
            CommandGateway commandGateway,
            BuiltDwellingReadModelRepository repository
    ) {
        this.commandGateway = commandGateway;
        this.repository = repository;
    }

    @EventHandler
    void react(
            WeekSymbolProclaimed event,
            @MetaDataValue(GameMetaData.GAME_ID_KEY) String gameId,
            @MetaDataValue(GameMetaData.PLAYER_ID_KEY) String playerId
    ) {
        var creature = event.weekOf();
        var increaseBy = event.growth();
        repository.findAllByGameId(gameId).stream()
                  .filter(dwelling -> dwelling.getCreatureId().equals(creature))
                  .forEach(dwelling -> increaseAvailableCreatures(dwelling, increaseBy, playerId));
    }

    private void increaseAvailableCreatures(BuiltDwellingReadModel dwelling,
                                            Integer increaseBy, String playerId) {
        var command = IncreaseAvailableCreatures.command(
                dwelling.getDwellingId(),
                dwelling.getCreatureId(),
                increaseBy
        );
        commandGateway.sendAndWait(command, GameMetaData.with(dwelling.getGameId(), playerId));
    }

    @EventHandler
    void on(DwellingBuilt event, @MetaDataValue(GameMetaData.GAME_ID_KEY) String gameId) {
        var state = new BuiltDwellingReadModel(
                gameId,
                event.dwellingId(),
                event.creatureId()
        );
        repository.save(state);
    }
}
```

**After (AF5) — preferred non-blocking with `CompletableFuture.allOf`:**

```java
package com.dddheroes.heroesofddd.astrologers.automation.whenweeksymbolproclaimedthenincreasedwellingavailablecreatures;

import com.dddheroes.heroesofddd.astrologers.events.WeekSymbolProclaimed;
import com.dddheroes.heroesofddd.creaturerecruitment.events.DwellingBuilt;
import com.dddheroes.heroesofddd.creaturerecruitment.write.changeavailablecreatures.IncreaseAvailableCreatures;
import com.dddheroes.heroesofddd.shared.application.GameMetaData;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.core.annotation.Namespace;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.replay.annotation.DisallowReplay;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Namespace("ReadModel_Dwelling")
@DisallowReplay
@Component
class WhenWeekSymbolProclaimedThenIncreaseDwellingAvailableCreaturesProcessor {

    private final BuiltDwellingReadModelRepository repository;

    WhenWeekSymbolProclaimedThenIncreaseDwellingAvailableCreaturesProcessor(
            BuiltDwellingReadModelRepository repository
    ) {
        this.repository = repository;
    }

    @EventHandler
    CompletableFuture<?> react(
            WeekSymbolProclaimed event,
            @MetadataValue(GameMetaData.GAME_ID_KEY) String gameId,
            @MetadataValue(GameMetaData.PLAYER_ID_KEY) String playerId,
            CommandDispatcher commandDispatcher
    ) {
        var creature = event.weekOf();
        var increaseBy = event.growth();
        var futures = repository.findAllByGameId(gameId).stream()
                .filter(dwelling -> dwelling.getCreatureId().equals(creature))
                .map(dwelling -> commandDispatcher
                        .send(
                                IncreaseAvailableCreatures.command(
                                        dwelling.getDwellingId(),
                                        dwelling.getCreatureId(),
                                        increaseBy
                                ),
                                GameMetaData.with(dwelling.getGameId(), playerId)
                        )
                        .getResultMessage())
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    @EventHandler
    void on(DwellingBuilt event, @MetadataValue(GameMetaData.GAME_ID_KEY) String gameId) {
        var state = new BuiltDwellingReadModel(
                gameId,
                event.dwellingId(),
                event.creatureId()
        );
        repository.save(state);
    }
}
```

**After (AF5) — last-resort blocking variant:**

If you must keep the loop blocking — e.g. the surrounding code can't be
async, the helper has imperative side-effects between dispatches, or you
explicitly want a per-dispatch timeout — use this shape. The handler
return type can stay `void`, and each dispatch blocks with an explicit
deadline:

```java
@EventHandler
void react(
        WeekSymbolProclaimed event,
        @MetadataValue(GameMetaData.GAME_ID_KEY) String gameId,
        @MetadataValue(GameMetaData.PLAYER_ID_KEY) String playerId,
        CommandDispatcher commandDispatcher
) {
    var creature = event.weekOf();
    var increaseBy = event.growth();
    repository.findAllByGameId(gameId).stream()
              .filter(dwelling -> dwelling.getCreatureId().equals(creature))
              .forEach(dwelling -> increaseAvailableCreatures(commandDispatcher, dwelling, increaseBy, playerId));
}

private void increaseAvailableCreatures(CommandDispatcher commandDispatcher,
                                        BuiltDwellingReadModel dwelling,
                                        Integer increaseBy, String playerId) {
    var command = IncreaseAvailableCreatures.command(
            dwelling.getDwellingId(),
            dwelling.getCreatureId(),
            increaseBy
    );
    // CommandResult is NOT a CompletableFuture — getResultMessage() first,
    // then orTimeout(...).join() per the project's blocking rule.
    commandDispatcher.send(command, GameMetaData.with(dwelling.getGameId(), playerId))
                     .getResultMessage()
                     .orTimeout(2, TimeUnit.SECONDS)
                     .join();
}
```

**Notes:**

- The dispatch lives in a **private helper** in the AF4 source, so the
  `CommandDispatcher` has to be threaded through as a method parameter
  on both the `@EventHandler` and the helper. The framework injects it
  on the `@EventHandler`; the helper just receives it as a regular Java
  parameter.
- The non-blocking variant is the default — pick the blocking variant
  only when the surrounding code genuinely resists going async. Both
  send the same commands; the difference is only in how the framework
  observes completion.
- **Compile pitfall.** `commandDispatcher.send(cmd, metadata)` returns
  `CommandResult`, which has **no** `.orTimeout(...)` method. Always
  call `.getResultMessage()` to get the `CompletableFuture<? extends
  Message>` before chaining `.orTimeout(d, unit).join()`. The same
  applies to the no-metadata overload `send(cmd)`.
- The second `@EventHandler` (`on(DwellingBuilt …)`) is a **pure
  projection** — no dispatcher, no command, return type stays `void`.
  Only the imports/annotations migrate (variant: pure projector — see
  SKILL.md "Variants").
- Same out-of-scope concern as examples 01 and 02: `GameMetaData.with`
  still returns the AF4 `MetaData` type. Flag the helper as a separate
  follow-up rather than editing it here.
- The blocking variant uses `TimeUnit.SECONDS`, so the AF5-only file
  needs `import java.util.concurrent.TimeUnit;`. The non-blocking
  variant does not.
