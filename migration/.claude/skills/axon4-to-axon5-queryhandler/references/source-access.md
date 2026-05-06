# Source access

How this machine resolves AF / AxonIQ source code for this skill.

- **Local clone:** `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5`
- **Sources jar:** n/a (use the local clone)

Key entry points consulted while drafting the skill:

- `messaging/src/main/java/org/axonframework/messaging/queryhandling/annotation/QueryHandler.java`
  — AF5 annotation; same `queryName()` attribute as AF4, meta-annotated with
  `@MessageHandler(messageType = QueryMessage.class)`.
- `messaging/src/main/java/org/axonframework/messaging/queryhandling/`
  — AF5 home of the query-handling core (`QueryBus`, `QueryMessage`,
  `AnnotatedQueryHandlingComponent`, etc.). Mirrors AF4
  `org.axonframework.queryhandling.*` with only the package prefix
  changed.
- `docs/reference-guide/modules/migration/pages/paths/index.adoc`
  — the canonical "AF4 → AF5 import & package changes" table; rows
  "Query Handler annotation" and "Query Handling Core" are the
  authoritative source for the FQN moves used by this skill.

If both go missing, re-run the source-access step in
`migration-skill-creator` before invoking this skill.
