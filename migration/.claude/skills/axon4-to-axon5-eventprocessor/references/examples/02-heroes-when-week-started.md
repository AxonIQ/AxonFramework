# Heroes (Spring Boot): `WhenWeekStartedThenProclaimWeekSymbolProcessor`

A Spring `@Component` event-processor that reacts to `DayStarted` only on
the first day of the week, dispatching `ProclaimWeekSymbol`. Three shape
variants over the first example:

1. **Multi-DI constructor** — the class injects both `CommandGateway` and
   a domain helper (`WeekSymbolCalculator`). Only the gateway is
   removed; the calculator stays.
2. **Conditional dispatch with empty false branch** — original AF4 shape
   was `if (isWeekStarted) { commandGateway.sendAndWait(...); }`. After
   migration the handler must return a `CompletableFuture` from every
   path. Use **early-return inversion** so the dispatch is the flat main
   path.
3. **Sequencing policy moved from external config to `@SequencingPolicy`
   annotation** — the AF4 setup had a `gameIdSequencingPolicy` `@Bean`
   in `GameConfiguration` keyed by metadata `gameId`. AF5 replaces that
   external wiring with `@SequencingPolicy(type = MetadataSequencingPolicy.class, parameters = GameMetaData.GAME_ID_KEY)`
   on the processor class itself; the AF4 `@Bean` is deleted.

**Before (AF4):**

```java
package com.dddheroes.heroesofddd.astrologers.automation.whenweekstartedthenproclaimweeksymbol;


import com.dddheroes.heroesofddd.astrologers.write.MonthWeek;
import com.dddheroes.heroesofddd.astrologers.write.proclaimweeksymbol.ProclaimWeekSymbol;
import com.dddheroes.heroesofddd.calendar.events.DayStarted;
import com.dddheroes.heroesofddd.shared.application.GameMetaData;
import org.axonframework.commandhandling.gateway.CommandGateway;
import org.axonframework.config.ProcessingGroup;
import org.axonframework.eventhandling.DisallowReplay;
import org.axonframework.eventhandling.EventHandler;
import org.axonframework.messaging.annotation.MetaDataValue;
import org.springframework.stereotype.Component;

@ProcessingGroup("Automation_WhenWeekStartedThenProclaimWeekSymbol_Processor")
@DisallowReplay
@Component
class WhenWeekStartedThenProclaimWeekSymbolProcessor {

    public static final int FIRST_DAY_OF_THE_WEEK = 1;

    private final CommandGateway commandGateway;
    private final WeekSymbolCalculator weekSymbolCalculator;

    WhenWeekStartedThenProclaimWeekSymbolProcessor(
            CommandGateway commandGateway,
            WeekSymbolCalculator weekSymbolCalculator
    ) {
        this.commandGateway = commandGateway;
        this.weekSymbolCalculator = weekSymbolCalculator;
    }

    @EventHandler
    void react(
            DayStarted event,
            @MetaDataValue(GameMetaData.GAME_ID_KEY) String gameId,
            @MetaDataValue(GameMetaData.PLAYER_ID_KEY) String playerId
    ) {
        var isWeekStarted = event.day() == FIRST_DAY_OF_THE_WEEK;
        if (isWeekStarted) {
            var weekSymbol = weekSymbolCalculator.apply(MonthWeek.of(event.month(), event.week()));
            var command = ProclaimWeekSymbol.command(
                    event.calendarId(),
                    event.month(),
                    event.week(),
                    weekSymbol.weekOf().raw(),
                    weekSymbol.growth()
            );
            commandGateway.sendAndWait(command, GameMetaData.with(gameId, playerId));
        }
    }
}
```

**After (AF5):**

```java
package com.dddheroes.heroesofddd.astrologers.automation.whenweekstartedthenproclaimweeksymbol;


import com.dddheroes.heroesofddd.astrologers.write.MonthWeek;
import com.dddheroes.heroesofddd.astrologers.write.proclaimweeksymbol.ProclaimWeekSymbol;
import com.dddheroes.heroesofddd.calendar.events.DayStarted;
import com.dddheroes.heroesofddd.shared.application.GameMetaData;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.core.annotation.Namespace;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.core.sequencing.MetadataSequencingPolicy;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.replay.annotation.DisallowReplay;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Namespace("Automation_WhenWeekStartedThenProclaimWeekSymbol_Processor")
@DisallowReplay
@Component
@SequencingPolicy(type = MetadataSequencingPolicy.class, parameters = GameMetaData.GAME_ID_KEY)
class WhenWeekStartedThenProclaimWeekSymbolProcessor {

    public static final int FIRST_DAY_OF_THE_WEEK = 1;

    private final WeekSymbolCalculator weekSymbolCalculator;

    WhenWeekStartedThenProclaimWeekSymbolProcessor(WeekSymbolCalculator weekSymbolCalculator) {
        this.weekSymbolCalculator = weekSymbolCalculator;
    }

    @EventHandler
    CompletableFuture<?> react(
            DayStarted event,
            @MetadataValue(GameMetaData.GAME_ID_KEY) String gameId,
            @MetadataValue(GameMetaData.PLAYER_ID_KEY) String playerId,
            CommandDispatcher commandDispatcher
    ) {
        var isWeekStarted = event.day() == FIRST_DAY_OF_THE_WEEK;
        if (!isWeekStarted) {
            return CompletableFuture.completedFuture(null);
        }
        var weekSymbol = weekSymbolCalculator.apply(MonthWeek.of(event.month(), event.week()));
        var command = ProclaimWeekSymbol.command(
                event.calendarId(),
                event.month(),
                event.week(),
                weekSymbol.weekOf().raw(),
                weekSymbol.growth()
        );
        return commandDispatcher.send(command, GameMetaData.with(gameId, playerId));
    }
}
```

**Notes:**

- The `WeekSymbolCalculator` field, constructor parameter, and assignment
  are all preserved — only the gateway-related lines are removed.
- The constructor now takes a single argument; Spring still wires it
  because there's exactly one constructor.
- The original `if (isWeekStarted) { … }` had no `else`. After migration
  every branch must return a `CompletableFuture`, so the false path
  early-returns `CompletableFuture.completedFuture(null)` and the
  dispatch becomes the flat main path of the method body.
- Same out-of-scope concerns as example 01: the `GameMetaData` helper
  still imports the AF4 `MetaData` type and must be migrated separately.
- The `@SequencingPolicy` annotation replaces an AF4 `@Bean` in
  `GameConfiguration` named `gameIdSequencingPolicy` that was wired
  against this processing group. The bean was deleted as part of the
  same change so the policy can't drift.
- `parameters = GameMetaData.GAME_ID_KEY` is valid even though
  `parameters()` is declared `String[]`: a single `String` constant is
  promoted to a one-element array, and `GAME_ID_KEY` is a compile-time
  `String` constant so it's annotation-legal.
