# Source access

How this machine resolves AF / AxonIQ source code for this skill.

- **Local clone:** `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5`
- **Sources jar:** n/a (use the local clone)

Key entry points consulted while drafting the skill:

- `messaging/src/main/java/org/axonframework/messaging/queryhandling/gateway/QueryGateway.java`
  — AF5 interface; defines `query(...)` / `queryMany(...)` /
  `streamingQuery(...)` / `subscriptionQuery(...)` overloads, all
  taking plain `Class<R>` (no `ResponseType`) and a nullable
  `ProcessingContext`.
- `axon-5/api-changes/09-queries-and-minor-changes.md` — the AF5
  API-changes note covering: scatter-gather removal, `ResponseType`
  removal, `queryMany` introduction, subscription-query redesign
  (`SubscriptionQueryResponse` vs `SubscriptionQueryResponseMessages`,
  `initialResult()` returning `Flux` instead of `Mono`).

If both go missing, re-run the source-access step in
`migration-skill-creator` before invoking this skill.
