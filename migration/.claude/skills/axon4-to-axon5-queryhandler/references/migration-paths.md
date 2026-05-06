# Relevant migration-path excerpts

Quoted from `docs/reference-guide/modules/migration/pages/paths/`:

## index.adoc — Import and package changes

The relevant rows for this skill (`@QueryHandler` annotation and the
core query-handling package):

| Description | AF4 | AF5 |
|---|---|---|
| Query Handler annotation | `org.axonframework.queryhandling.QueryHandler` | `org.axonframework.messaging.queryhandling.annotation.QueryHandler` |
| Query Handling Core | `org.axonframework.queryhandling` | `org.axonframework.messaging.queryhandling` |
| Metadata (if used as a handler parameter) | `org.axonframework.messaging.MetaData` | `org.axonframework.messaging.core.Metadata` |

> Many classes have been moved to better align with the new modular
> structure. The most notable change is the move of Spring-specific
> and monitoring components into their own extension namespaces, and
> the nesting of core messaging components under the
> `org.axonframework.messaging` package.

## AF5 `@QueryHandler` annotation contract

From `messaging/src/main/java/org/axonframework/messaging/queryhandling/annotation/QueryHandler.java`:

- Same `queryName()` attribute as AF4 — preserved across the migration.
- First parameter is the query payload (unchanged from AF4).
- Parameter resolvers in AF5 still cover `Metadata`,
  `@MetadataValue`-annotated parameters, the full `Message`, and the
  active `ProcessingContext` (the AF5 replacement for AF4's
  `UnitOfWork` parameter — out of scope of this skill for now).
- `@QueryHandler` is meta-annotated with the AF5
  `@MessageHandler(messageType = QueryMessage.class)` — the discovery
  pipeline is unchanged from a user's standpoint; only the
  annotation's package moves.
