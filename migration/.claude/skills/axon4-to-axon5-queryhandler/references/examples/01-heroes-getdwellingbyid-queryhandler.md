# Heroes (Spring Boot): `GetDwellingByIdQueryHandler`

A minimal Spring `@Component` with one `@QueryHandler` method that
delegates to a read-model repository. The simplest possible AF4 →
AF5 case: only the `@QueryHandler` **import** changes; the body is
untouched.

**Before (AF4):**

```java
package com.dddheroes.heroesofddd.creaturerecruitment.read.getdwellingbyid;

import com.dddheroes.heroesofddd.creaturerecruitment.read.DwellingReadModel;
import com.dddheroes.heroesofddd.creaturerecruitment.read.DwellingReadModelRepository;
import org.axonframework.queryhandling.QueryHandler;
import org.springframework.stereotype.Component;

@Component
class GetDwellingByIdQueryHandler {
    private final DwellingReadModelRepository dwellingReadModelRepository;

    GetDwellingByIdQueryHandler(DwellingReadModelRepository dwellingReadModelRepository) {
        this.dwellingReadModelRepository = dwellingReadModelRepository;
    }

    @QueryHandler
    DwellingReadModel handle(GetDwellingById query){
        return dwellingReadModelRepository.findById(query.dwellingId().raw()).orElse(null);
    }
}
```

**After (AF5):**

```java
package com.dddheroes.heroesofddd.creaturerecruitment.read.getdwellingbyid;

import com.dddheroes.heroesofddd.creaturerecruitment.read.DwellingReadModel;
import com.dddheroes.heroesofddd.creaturerecruitment.read.DwellingReadModelRepository;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.springframework.stereotype.Component;

@Component
class GetDwellingByIdQueryHandler {
    private final DwellingReadModelRepository dwellingReadModelRepository;

    GetDwellingByIdQueryHandler(DwellingReadModelRepository dwellingReadModelRepository) {
        this.dwellingReadModelRepository = dwellingReadModelRepository;
    }

    @QueryHandler
    DwellingReadModel handle(GetDwellingById query){
        return dwellingReadModelRepository.findById(query.dwellingId().raw()).orElse(null);
    }
}
```

**Notes:**

- **Single import change** on `@QueryHandler`:
  `org.axonframework.queryhandling.QueryHandler` →
  `org.axonframework.messaging.queryhandling.annotation.QueryHandler`.
  Same annotation name, same usage at the method — only the package
  moves.
- **Body unchanged.** The method signature, the call to
  `dwellingReadModelRepository.findById(...).orElse(null)`, the
  query payload type (`GetDwellingById`), and the return type
  (`DwellingReadModel`) are all preserved. No `ResponseType` lives
  on the handler side; the AF5 `ResponseType` removal only affects
  the dispatch side (`QueryGateway`), which is out of scope for this
  skill.
- **Spring stereotype preserved.** `@Component` is kept as-is. The
  framework still discovers the class through Spring component
  scanning and registers it as an `AnnotatedQueryHandlingComponent`
  on the AF5 `QueryBus` — same discovery flow, new package.
- **No `queryName` here.** The handler relies on AF5's default
  `MessageTypeResolver` — the query name is derived from the payload
  type (the **first** parameter, `GetDwellingById`). If the AF4 site
  had `@QueryHandler(queryName = "...")`, the attribute would have
  been preserved verbatim across the migration.
- **Return type stays nullable.** `findById(...).orElse(null)`
  returning a possibly-null `DwellingReadModel` is fine on the AF5
  handler side — the AF5 gateway's `query(...)` resolves the
  `CompletableFuture<R>` to `null` when the handler returns `null`,
  so the calling-side migration (handled by
  `axon4-to-axon5-querygateway`) doesn't need a separate Optional
  bridge here.
