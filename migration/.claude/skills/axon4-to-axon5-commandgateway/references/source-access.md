# Source access

How this machine resolves AF / AxonIQ source code for this skill.

- **Local clone:** `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5`
- **Sources jar:** n/a (use the local clone)

Key entry points consulted while drafting the skill:

- `messaging/src/main/java/org/axonframework/messaging/commandhandling/gateway/CommandGateway.java`
  — AF5 interface; defines `send(...)` / `sendAndWait(...)` overloads
  and shows that `send(cmd, metadata)` returns `CommandResult`, not
  `CompletableFuture`.
- `messaging/src/main/java/org/axonframework/messaging/commandhandling/gateway/CommandResult.java`
  — `CommandResult` API: `getResultMessage()`, `resultAs(Class<R>)`,
  `onSuccess(...)`, `onError(...)`, `wait(Class<R>)`. Not a
  `CompletableFuture`.
- `messaging/src/main/java/org/axonframework/messaging/core/Metadata.java`
  — AF5 metadata type that the `send(Object, Metadata, ProcessingContext)`
  overload expects (replaces AF4 `org.axonframework.messaging.MetaData`).

If both go missing, re-run the source-access step in `migration-skill-creator`
before invoking this skill.
