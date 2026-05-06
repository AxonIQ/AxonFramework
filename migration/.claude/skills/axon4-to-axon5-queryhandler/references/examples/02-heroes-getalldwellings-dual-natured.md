# Heroes (Spring Boot): `GetAllDwellingsQueryHandler` — recipe-pre-migrated dual-natured class

A read-model class that **handles queries AND evolves from events**:
one `@QueryHandler` method that returns the list of dwellings, plus
one `@EventHandler` method that maintains an in-memory write-through
cache. The class also carries `@Namespace` (the AF5 replacement for
`@ProcessingGroup`) and `@MetadataValue` on the event handler's
metadata parameter.

A class with mixed message annotations would normally be out of
scope for the query-handler skill — by the SKILL's own rule, route
to the event-handler skill first. **But** when phase-1 OpenRewrite
(`UpgradeAxon4ToAxon5` / `UpgradeAxon4ToAxoniq5`) has already run,
all four annotations have been moved to their AF5 packages and there
is nothing for either skill to do. The skill closes as a **no-op**
and the orchestrator records the unit of work as done.

**File state (after phase-1 OpenRewrite, before this skill ran):**

```java
package com.dddheroes.heroesofddd.creaturerecruitment.read.getalldwellings;

import com.dddheroes.heroesofddd.creaturerecruitment.read.DwellingReadModel;
import com.dddheroes.heroesofddd.creaturerecruitment.read.DwellingReadModelRepository;
import com.dddheroes.heroesofddd.creaturerecruitment.events.DwellingBuilt;
import com.dddheroes.heroesofddd.shared.application.GameMetaData;
import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.core.annotation.Namespace;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Stream;

@Namespace("Read_GetAllDwellings_QueryCache")
@Component
class GetAllDwellingsQueryHandler {

    private final DwellingReadModelRepository dwellingReadModelRepository;
    private final ConcurrentLinkedDeque<DwellingReadModel> cache = new ConcurrentLinkedDeque<>();

    GetAllDwellingsQueryHandler(DwellingReadModelRepository dwellingReadModelRepository) {
        this.dwellingReadModelRepository = dwellingReadModelRepository;
    }

    @QueryHandler
    GetAllDwellings.Result handle(GetAllDwellings query) {
        var gameId = query.gameId().raw();
        var dwellings = dwellingReadModelRepository.findAllByGameId(gameId);
        var result = Stream.concat(
                        dwellings.stream(),
                        cache.stream().filter(it -> it.getGameId().equals(gameId))
                )
                .distinct()
                .toList();
        return new GetAllDwellings.Result(result);
    }

    @EventHandler
    void evolve(DwellingBuilt event, @MetadataValue(GameMetaData.GAME_ID_KEY) String gameId) {
        while (cache.size() > 20) {
            cache.pollFirst();
        }
        var item = new DwellingReadModel(
                gameId,
                event.dwellingId(),
                event.creatureId(),
                event.costPerTroop(),
                0
        );
        if (!cache.contains(item)) {
            cache.push(item);
        }
    }
}
```

**Skill outcome:** **no-op close**. Pre-flight checklist passes:

1. `@QueryHandler` already at the AF5 location
   (`org.axonframework.messaging.queryhandling.annotation.QueryHandler`).
2. `@EventHandler` already at
   `org.axonframework.messaging.eventhandling.annotation.EventHandler`.
3. `@Namespace` already at
   `org.axonframework.messaging.core.annotation.Namespace` (the AF5
   replacement for AF4's `@ProcessingGroup`, surfaced by recipe
   `MigrateProcessingGroupToNamespace` or equivalent).
4. `@MetadataValue` already at
   `org.axonframework.messaging.core.annotation.MetadataValue`.

Verified via `mvn -Pmigration test-compile -DfailIfNoTests=false` →
BUILD SUCCESS.

**Notes:**

- **Recipe coverage is the rule, not the exception.** The Heroes
  project's phase-6 invocations were both no-ops — the recipe handled
  the imports for both the pure `@QueryHandler` (example 01) and
  this dual-natured class (example 02). Anticipate this when
  invoked: pre-flight first, edit only if pre-flight fails.
- **The "Mixed-message class — out of scope" rule still applies as a
  default**, but with an exception when every annotation is already
  AF5-shaped. Don't refuse to close; record the no-op so the
  orchestrator can move on.
- **Event-handler side belongs to a sibling skill** when there *is*
  AF4-shape work to do. If the class had a class-level
  `CommandGateway` field that the event handler dispatched through
  (the AF4 in-handler-dispatch pattern), the
  `axon4-to-axon5-eventprocessor` skill would handle it — and only
  after that work is done would this skill see the file.
- **The `@Namespace` annotation is AF5-only.** AF4's
  `@ProcessingGroup` was on the `org.axonframework.config` package;
  AF5 moved namespacing to `org.axonframework.messaging.core.annotation`
  and renamed it. The recipe handles the rename mechanically — if a
  class still has `@ProcessingGroup`, that's a recipe gap, not
  this skill's territory.
