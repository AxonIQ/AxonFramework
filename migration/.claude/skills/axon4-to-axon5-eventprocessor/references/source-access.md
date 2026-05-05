# Source access

How this machine resolves AF / AxonIQ source code for this skill.

- **Local clone:** `/Users/mateusznowak/GitRepos/AxonFramework/AxonFramework5`
- **Sources jar:** n/a (use the local clone)

Key entry points consulted while drafting the skill:

- `messaging/src/main/java/org/axonframework/messaging/commandhandling/gateway/CommandDispatcher.java`
- `messaging/src/main/java/org/axonframework/messaging/commandhandling/gateway/CommandResult.java`
- `messaging/src/main/java/org/axonframework/messaging/core/annotation/Namespace.java`
- `messaging/src/main/java/org/axonframework/messaging/core/annotation/MetadataValue.java`
- `messaging/src/main/java/org/axonframework/messaging/eventhandling/replay/annotation/DisallowReplay.java`

If both go missing, re-run the source-access step in `migration-skill-creator`
before invoking this skill.
