# Heroes (Spring Boot): `WhenCreatureRecruitedThenAddToArmyProcessor`

A Spring `@Component` event-processor that reacted to `CreatureRecruited`
by dispatching `AddCreatureToArmy`, with a try/catch compensation that
dispatched `IncreaseAvailableCreatures` if the army was full.

**Before (AF4):**

```java
package com.dddheroes.heroesofddd.creaturerecruitment.automation;

import com.dddheroes.heroesofddd.armies.write.addcreature.AddCreatureToArmy;
import com.dddheroes.heroesofddd.creaturerecruitment.write.changeavailablecreatures.IncreaseAvailableCreatures;
import com.dddheroes.heroesofddd.creaturerecruitment.write.recruitcreature.CreatureRecruited;
import com.dddheroes.heroesofddd.shared.GameMetaData;
import org.axonframework.commandhandling.gateway.CommandGateway;
import org.axonframework.config.ProcessingGroup;
import org.axonframework.eventhandling.DisallowReplay;
import org.axonframework.eventhandling.EventHandler;
import org.axonframework.messaging.annotation.MetaDataValue;
import org.springframework.stereotype.Component;

@ProcessingGroup("Automation_WhenCreatureRecruitedThenAddToArmy_Processor")
@DisallowReplay
@Component
class WhenCreatureRecruitedThenAddToArmyProcessor {

    private final CommandGateway commandGateway;

    WhenCreatureRecruitedThenAddToArmyProcessor(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    @EventHandler
    void react(
            CreatureRecruited event,
            @MetaDataValue(GameMetaData.GAME_ID_KEY) String gameId,
            @MetaDataValue(GameMetaData.PLAYER_ID_KEY) String playerId
    ) {
        var command = AddCreatureToArmy.command(
                event.toArmy(),
                event.creatureId(),
                event.quantity()
        );

        try {
            commandGateway.sendAndWait(command, GameMetaData.with(gameId, playerId));
        } catch (Exception e) {
            var compensatingAction = IncreaseAvailableCreatures.command(
                    event.dwellingId(),
                    event.creatureId(),
                    event.quantity()
            );
            commandGateway.sendAndWait(compensatingAction, GameMetaData.with(gameId, playerId));
        }
    }
}
```

**After (AF5):**

```java
package com.dddheroes.heroesofddd.creaturerecruitment.automation;

import com.dddheroes.heroesofddd.armies.write.addcreature.AddCreatureToArmy;
import com.dddheroes.heroesofddd.creaturerecruitment.write.changeavailablecreatures.IncreaseAvailableCreatures;
import com.dddheroes.heroesofddd.creaturerecruitment.events.CreatureRecruited;
import com.dddheroes.heroesofddd.shared.application.GameMetaData;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.core.annotation.Namespace;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.replay.annotation.DisallowReplay;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Namespace("Automation_WhenCreatureRecruitedThenAddToArmy_Processor")
@DisallowReplay
@Component
class WhenCreatureRecruitedThenAddToArmyProcessor {

    @EventHandler
    CompletableFuture<?> react(
            CreatureRecruited event,
            @MetadataValue(GameMetaData.GAME_ID_KEY) String gameId,
            @MetadataValue(GameMetaData.PLAYER_ID_KEY) String playerId,
            CommandDispatcher commandDispatcher
    ) {
        try {
            var command = AddCreatureToArmy.command(
                    event.toArmy(),
                    event.creatureId(),
                    event.quantity()
            );
            return commandDispatcher.send(command, GameMetaData.with(gameId, playerId));
        } catch (Exception e) {
            var compensatingAction = IncreaseAvailableCreatures.command(
                    event.dwellingId(),
                    event.creatureId(),
                    event.quantity()
            );
            return commandDispatcher.send(compensatingAction, GameMetaData.with(gameId, playerId));
        }
    }
}
```

**Notes:**

- `@ProcessingGroup` → `@Namespace` (drop-in replacement; same string
  argument).
- All four AF4 imports moved (`EventHandler`, `DisallowReplay`,
  `MetaDataValue`, and the package-only move on `CommandGateway`'s side).
  Note also `MetaDataValue` → `MetadataValue` (capitalisation change).
- The `CommandGateway` field + constructor are removed entirely. The
  command dispatcher is now a **method parameter**, automatically resolved
  to the current `ProcessingContext` via
  `CommandDispatcherParameterResolverFactory`.
- `commandGateway.sendAndWait(cmd, md)` (blocking) →
  `commandDispatcher.send(cmd, md)` (async, returns
  `CommandResult` / `CompletableFuture`-friendly). The handler return
  type changes `void` → `CompletableFuture<?>` so the async chain is
  honoured by the framework.
- The unrelated import-path change on `CreatureRecruited` and
  `GameMetaData` (events package reorganisation, `shared` →
  `shared.application`) is **out of scope** for this skill — those moves
  came from a separate refactor in the same project. The skill should
  preserve whatever package the upstream events live in.
- The project's `GameMetaData.with(...)` helper still imports
  `org.axonframework.messaging.MetaData` (AF4). That helper must be
  migrated separately to `org.axonframework.messaging.core.Metadata`
  before the file actually compiles. Out of scope for the per-processor
  skill — flag it for the user.
